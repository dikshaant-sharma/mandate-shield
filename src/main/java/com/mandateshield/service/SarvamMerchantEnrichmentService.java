package com.mandateshield.service;

import com.mandateshield.domain.RawTransactionDocument;
import com.mandateshield.domain.ResolutionTier;
import com.mandateshield.repository.RawTransactionRepository;
import com.mandateshield.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Resolves low-confidence descriptors using Sarvam AI.
 * <p>
 * Sarvam AI is invoked ONLY asynchronously and ONLY when:
 * 1. The descriptor is fully UNKNOWN (no brand resolved), AND
 * 2. The confidence score is below the configured threshold (default 0.7).
 * <p>
 * No result is auto-written to any cache (Tier 1). Resolved merchants are only
 * persisted to the transaction record itself; promotion to system-approved aliases
 * must happen through the admin/seeder path.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SarvamMerchantEnrichmentService {

    private static final Pattern ONE_WORD_BRAND = Pattern.compile("[A-Z0-9][A-Z0-9&+.'\\-\\s]{0,49}");
    private static final double AI_CONFIDENCE = 0.88; // Confident Sarvam identification

    private final ApplicationEventPublisher events;
    private final RawTransactionRepository transactions;
    private final SubscriptionRepository subscriptions;
    private final org.springframework.beans.factory.ObjectProvider<MandateShieldPipelineService> pipelineProvider;
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    @Value("${mandateshield.sarvam.api-key:}")
    private String sarvamApiKey;

    @Value("${mandateshield.sarvam.base-url:https://api.sarvam.ai/v1}")
    private String sarvamBaseUrl;

    @Value("${mandateshield.resolver.llm.confidence-threshold:0.65}")
    private double confidenceThreshold;

    /**
     * Schedule async Sarvam enrichment if the descriptor is unknown OR confidence is below 65% (0.65).
     * This is the ONLY entry point; there is no synchronous path.
     */
    public void scheduleIfSuspicious(Long userId, String descriptor, double confidence, String resolvedBrand) {
        if (userId == null || descriptor == null || descriptor.isBlank()) return;
        boolean isUnknown = resolvedBrand == null || "UNKNOWN".equalsIgnoreCase(resolvedBrand);
        boolean isLowConfidence = confidence < 0.65;
        if (!isUnknown && !isLowConfidence) {
            log.debug("Sarvam enrichment not needed for '{}': resolved={}, confidence={}", descriptor, resolvedBrand, confidence);
            return;
        }
        String key = userId + "\u0000" + descriptor;
        if (!pending.add(key)) {
            log.debug("Sarvam enrichment already pending for key: {}", key);
            return;
        }
        try {
            log.info("Scheduling Sarvam async enrichment for user {} descriptor '{}' (conf: {}, brand: {})",
                    userId, descriptor, confidence, resolvedBrand);
            events.publishEvent(new SarvamEnrichmentRequest(userId, descriptor, key));
        } catch (RuntimeException e) {
            pending.remove(key);
            log.warn("Could not queue Sarvam enrichment for user {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Async event listener — runs on the Spring task executor, never on the HTTP request thread.
     */
    @Async
    @EventListener
    public void enrich(SarvamEnrichmentRequest request) {
        try {
            EnrichmentResult parsed = callSarvamApi(request.descriptor());
            if (parsed == null || "UNKNOWN".equalsIgnoreCase(parsed.brand())) {
                log.info("Sarvam could not identify merchant for descriptor '{}' (user {})", request.descriptor(), request.userId());
                return;
            }
            boolean updated = updateTransactions(request.userId(), request.descriptor(), parsed.brand(), parsed.confidence(), parsed.category());
            if (updated) {
                // Re-run the pipeline so recurring detection picks up the newly resolved name
                pipelineProvider.ifAvailable(p -> p.processUserNow(request.userId()));
                log.info("Sarvam resolved '{}' -> brand='{}', conf={}, cat='{}' for user {}",
                        request.descriptor(), parsed.brand(), parsed.confidence(), parsed.category(), request.userId());
            }
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            log.warn("Sarvam enrichment failed for user {} descriptor '{}': {} - {}",
                    request.userId(), request.descriptor(), root.getClass().getSimpleName(), root.getMessage());
        } finally {
            pending.remove(request.key());
        }
    }

    public record EnrichmentResult(String brand, double confidence, String category) {}

    /**
     * Calls the Sarvam AI API synchronously within the async task.
     * Returns null if the API is unavailable, returns no result, or the key is blank.
     */
    private EnrichmentResult callSarvamApi(String descriptor) {
        if (sarvamApiKey == null || sarvamApiKey.isBlank()) {
            log.debug("Sarvam API key not configured — skipping LLM enrichment for '{}'", descriptor);
            return null;
        }
        try {
            RestClient client = RestClient.builder()
                    .baseUrl(sarvamBaseUrl)
                    .defaultHeader("api-subscription-key", sarvamApiKey)
                    .defaultHeader("Content-Type", "application/json")
                    .build();

            String prompt = """
                    Identify the actual merchant brand name from this bank transaction descriptor.
                    Ignore payment gateways like RAZORPAY, RZP, RAZP, BILLDESK, PAYU, CASHFREE, CCAVENUE.
                    Ignore location and transaction noise like MKTP, MARKETPLACE, US, SEATTLE, WA, MUMBAI, BLR, ACH, INB, SI, MNDT.
                    If you recognize the merchant, reply with format: BRAND|CONFIDENCE|CATEGORY
                    Where CONFIDENCE is a number between 0.70 and 0.95.
                    If you cannot recognize the merchant, reply UNKNOWN|0.0|Uncategorized.
                    Example: AMZN MKTP US*2X49L091 -> AMAZON|0.92|E-Commerce
                    Example: BILLDESK_LENSKART_GOLD -> LENSKART|0.90|Eyewear & Healthcare
                    Descriptor: %s
                    Reply:"""
                    .formatted(descriptor);

            var requestBody = java.util.Map.of(
                    "model", "sarvam-105b-conversations",
                    "messages", java.util.List.of(
                            java.util.Map.of("role", "user", "content", prompt)
                    ),
                    "max_tokens", 60,
                    "temperature", 0.0
            );

            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> response = client.post()
                    .uri("/chat/completions")
                    .body(requestBody)
                    .retrieve()
                    .body(java.util.Map.class);

            if (response == null) return null;
            @SuppressWarnings("unchecked")
            var choices = (java.util.List<java.util.Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) return null;
            @SuppressWarnings("unchecked")
            var message = (java.util.Map<String, Object>) choices.get(0).get("message");
            if (message == null) return null;
            String content = (String) message.get("content");
            if (content == null || content.isBlank()) {
                return null;
            }
            return parseEnrichmentResult(content);
        } catch (Exception e) {
            log.warn("Sarvam API call failed for '{}': {}", descriptor, e.getMessage());
            return null;
        }
    }

    private EnrichmentResult parseEnrichmentResult(String response) {
        if (response == null || response.isBlank()) return null;
        String line = response.trim();
        if (line.contains("\n")) {
            line = line.substring(0, line.indexOf("\n")).trim();
        }
        String[] parts = line.split("\\|");
        if (parts.length >= 2) {
            String brand = normalizeBrand(parts[0]);
            if (brand == null || "UNKNOWN".equalsIgnoreCase(brand)) return null;
            double conf = AI_CONFIDENCE;
            try {
                conf = Double.parseDouble(parts[1].trim());
            } catch (Exception ignored) {}
            String cat = parts.length >= 3 && !parts[2].isBlank() ? parts[2].trim() : "Subscriptions & Services";
            return new EnrichmentResult(brand, conf, cat);
        } else {
            String brand = normalizeBrand(line);
            if (brand == null || "UNKNOWN".equalsIgnoreCase(brand)) return null;
            return new EnrichmentResult(brand, AI_CONFIDENCE, "Subscriptions & Services");
        }
    }

    /**
     * Update all transactions for this user+descriptor with the Sarvam-resolved merchant.
     * Does NOT write to any pattern cache or alias store.
     */
    private boolean updateTransactions(Long userId, String descriptor, String merchant, double confidence, String category) {
        var matching = transactions.findByUserIdAndRawDescriptor(userId, descriptor);
        if (matching.isEmpty()) return false;
        for (RawTransactionDocument tx : matching) {
            tx.setResolvedMerchantName(merchant);
            tx.setConfidenceScore(confidence);
            tx.setResolutionTier(ResolutionTier.TIER_3);
            if (category != null && !category.isBlank() && !"Uncategorized".equalsIgnoreCase(category)) {
                tx.setResolvedCategory(category);
            }
            transactions.save(tx);
        }
        // Sync subscription name if the old name matched the raw descriptor
        subscriptions.findByUserIdAndMerchantName(userId, descriptor).ifPresent(sub -> {
            sub.setMerchantName(merchant);
            subscriptions.save(sub);
        });
        return true;
    }

    private String normalizeBrand(String response) {
        if (response == null) return null;
        String value = response.trim().toUpperCase(Locale.ROOT)
                // Remove any punctuation Sarvam might add
                .replaceAll("[^A-Z0-9&+'\\-.]", "");
        if (value.isBlank() || value.equals("UNKNOWN")) return null;
        if (!ONE_WORD_BRAND.matcher(value).matches() || value.matches("\\d+")) return null;
        return value;
    }

    public record SarvamEnrichmentRequest(Long userId, String descriptor, String key) {}
}
