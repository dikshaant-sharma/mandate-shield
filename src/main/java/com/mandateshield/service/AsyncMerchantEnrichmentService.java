package com.mandateshield.service;

import com.mandateshield.domain.RawTransactionDocument;
import com.mandateshield.domain.ResolutionTier;
import com.mandateshield.repository.RawTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Asynchronous enrichment service using Sarvam AI (Indic descriptor resolution)
 * with Gemini fallback.
 * Strictly scheduled asynchronously and ONLY for suspicious or low-confidence transactions.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AsyncMerchantEnrichmentService {

    private final ObjectProvider<SarvamAiService> sarvamProvider;
    private final ObjectProvider<GeminiMerchantEnrichmentService> geminiEnrichmentProvider;
    private final RawTransactionRepository transactionRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Set<String> inFlightKeys = ConcurrentHashMap.newKeySet();

    public record AsyncEnrichmentEvent(Long userId, String rawDescriptor, String transactionId, double currentConfidence) {}

    /**
     * Schedules asynchronous enrichment ONLY if the confidence score is low (< 0.75)
     * or descriptor resolved to UNKNOWN/suspicious.
     */
    public void scheduleIfLowConfidenceOrSuspicious(Long userId, String descriptor, String transactionId, double confidence, String brandName) {
        if (userId == null || descriptor == null || descriptor.isBlank()) return;

        boolean isLowConfidence = confidence < 0.75;
        boolean isSuspiciousOrUnknown = brandName == null || brandName.isBlank()
                || "UNKNOWN".equalsIgnoreCase(brandName)
                || brandName.equalsIgnoreCase(descriptor);

        if (!isLowConfidence && !isSuspiciousOrUnknown) {
            // High confidence - do not invoke LLM
            return;
        }

        String dedupeKey = userId + "#" + descriptor.trim().toUpperCase();
        if (!inFlightKeys.add(dedupeKey)) {
            return; // Already being processed asynchronously
        }

        log.info("Scheduling async Sarvam AI enrichment for low-confidence/suspicious descriptor: '{}' (confidence={})", descriptor, confidence);
        try {
            eventPublisher.publishEvent(new AsyncEnrichmentEvent(userId, descriptor, transactionId, confidence));
        } catch (Exception e) {
            inFlightKeys.remove(dedupeKey);
            log.warn("Could not dispatch async enrichment event: {}", e.getMessage());
        }
    }

    @Async
    @EventListener
    public void handleEnrichment(AsyncEnrichmentEvent event) {
        String dedupeKey = event.userId() + "#" + event.rawDescriptor().trim().toUpperCase();
        try {
            String resolvedBrand = null;
            String source = "SARVAM_AI_ASYNC";

            SarvamAiService sarvam = sarvamProvider != null ? sarvamProvider.getIfAvailable() : null;
            if (sarvam != null) {
                resolvedBrand = sarvam.resolveDescriptor(event.rawDescriptor());
            }

            if (resolvedBrand == null || "UNKNOWN".equalsIgnoreCase(resolvedBrand)) {
                GeminiMerchantEnrichmentService gemini = geminiEnrichmentProvider != null ? geminiEnrichmentProvider.getIfAvailable() : null;
                if (gemini != null) {
                    resolvedBrand = gemini.resolveSynchronous(event.rawDescriptor());
                    if (resolvedBrand != null && !"UNKNOWN".equalsIgnoreCase(resolvedBrand)) {
                        source = "GEMINI_ASYNC";
                    }
                }
            }

            if (resolvedBrand != null && !"UNKNOWN".equalsIgnoreCase(resolvedBrand)) {
                log.info("Async {} resolved descriptor '{}' -> '{}'", source, event.rawDescriptor(), resolvedBrand);
                updateMatchingTransactions(event.userId(), event.rawDescriptor(), resolvedBrand, 0.92, ResolutionTier.TIER_3);
            } else {
                log.debug("Async resolution could not identify merchant for descriptor '{}'", event.rawDescriptor());
            }
        } catch (Exception e) {
            log.warn("Error in async merchant enrichment for '{}': {}", event.rawDescriptor(), e.getMessage());
        } finally {
            inFlightKeys.remove(dedupeKey);
        }
    }

    private void updateMatchingTransactions(Long userId, String rawDescriptor, String resolvedBrand, double confidence, ResolutionTier tier) {
        var matching = transactionRepository.findByUserIdAndRawDescriptor(userId, rawDescriptor);
        if (matching.isEmpty()) return;

        for (RawTransactionDocument tx : matching) {
            tx.setResolvedMerchantName(resolvedBrand);
            tx.setConfidenceScore(confidence);
            tx.setResolutionTier(tier);
        }
        transactionRepository.saveAll(matching);
        log.info("Updated {} transactions with async-resolved merchant '{}'", matching.size(), resolvedBrand);
    }
}
