package com.mandateshield.engine;

import com.mandateshield.domain.MerchantPatternDocument;
import com.mandateshield.domain.RawTransactionDocument;
import com.mandateshield.domain.ResolutionTier;
import com.mandateshield.repository.MerchantPatternRepository;
import com.mandateshield.repository.RawTransactionRepository;
import com.mandateshield.service.SharedMerchantKnowledgeService;
import com.mandateshield.service.SarvamMerchantEnrichmentService;
import com.mandateshield.service.PineconeMerchantVectorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Core 3-tier merchant resolver:
 *
 * Tier 1  — System-approved alias lookup (SharedMerchantKnowledgeService → shared_merchant_aliases collection).
 *           Only pre-seeded, human/admin-approved entries live here. Auto-learned names NEVER enter Tier 1.
 *
 * Tier 2  — Heuristic extraction (regex tokens, alias map, Levenshtein fuzzy match from HeuristicExtractionEngine)
 *           + Pinecone vector similarity. High-confidence (≥0.80) results are returned immediately.
 *
 * Tier 2.5 — Unnamed descriptor layer: if the descriptor produced no recognisable brand, check whether the
 *            same raw descriptor string recurs across the user's history. If it does, extract the common
 *            stable sub-string as a placeholder name so recurring detection can group it.
 *
 * Tier 3  — Sarvam AI async enrichment: fired only for truly unknown, low-confidence descriptors.
 *            Never called synchronously. Never writes to Tier 1 cache.
 *
 * Cache policy: NO in-memory pattern cache. NO auto-write-back to merchant_patterns from AI or Tier 2 results.
 * Only reads from pattern repo (system-seeded records) and shared_merchant_aliases.
 */
@Service
@Slf4j
public class MerchantResolverEngine {

    private final MerchantPatternRepository patternRepo;
    private final RawTransactionRepository transactionRepo;
    private final HeuristicExtractionEngine heuristicEngine;
    private final SharedMerchantKnowledgeService sharedKnowledge;
    private final PineconeMerchantVectorService pinecone;
    private final SarvamMerchantEnrichmentService sarvamEnrichment;

    @Autowired
    public MerchantResolverEngine(MerchantPatternRepository patternRepo,
                                  RawTransactionRepository transactionRepo,
                                  HeuristicExtractionEngine heuristicEngine,
                                  SharedMerchantKnowledgeService sharedKnowledge,
                                  PineconeMerchantVectorService pinecone,
                                  SarvamMerchantEnrichmentService sarvamEnrichment) {
        this.patternRepo = patternRepo;
        this.transactionRepo = transactionRepo;
        this.heuristicEngine = heuristicEngine;
        this.sharedKnowledge = sharedKnowledge;
        this.pinecone = pinecone;
        this.sarvamEnrichment = sarvamEnrichment;
    }

    /**
     * Resolve the descriptor across Tier 1 → Tier 2 → Tier 2.5 → Tier 3 and persist.
     * Confidence score is only set after the full check for each tier is complete.
     */
    public ResolveResult resolve(String rawDescriptor,
                                Long userId,
                                BigDecimal amount,
                                String currency,
                                Instant transactionDate) {

        log.info("Resolving descriptor: '{}'", rawDescriptor);

        // ------------------------------------------------------------------
        // Tier 1 — System-approved alias lookup (shared_merchant_aliases only)
        // ------------------------------------------------------------------
        Optional<MerchantPatternDocument> tier1Match = sharedKnowledge.find(rawDescriptor).map(alias ->
                MerchantPatternDocument.builder()
                        .patternRegex("SHARED:" + alias.getDescriptorFingerprint())
                        .brandName(alias.getMerchantName())
                        .category(alias.getCategory())
                        .confidenceScore(0.98)
                        .verificationSource(alias.getVerificationSource())
                        .build());
        if (tier1Match.isEmpty()) tier1Match = findApprovedPatternMatch(rawDescriptor, userId);

        if (tier1Match.isPresent()) {
            MerchantPatternDocument pat = tier1Match.get();
            // Confidence is only written after full Tier 1 lookup is confirmed
            double confirmedConfidence = pat.getConfidenceScore() != null ? pat.getConfidenceScore() : 0.98;
            log.info("Tier 1 match for '{}' -> brand='{}', confidence={}", rawDescriptor, pat.getBrandName(), confirmedConfidence);
            saveRawTransaction(userId, rawDescriptor, amount, currency, transactionDate,
                    pat.getBrandName(), pat.getCategory(), confirmedConfidence, ResolutionTier.TIER_1);
            return new ResolveResult(pat.getBrandName(), pat.getCategory(),
                    extractGateway(rawDescriptor), confirmedConfidence,
                    ResolutionTier.TIER_1, pat.getPatternRegex());
        }

        // ------------------------------------------------------------------
        // Tier 2 — Pinecone vector similarity search
        // ------------------------------------------------------------------
        PineconeMerchantVectorService.Match vectorMatch = pinecone.find(rawDescriptor);
        if (vectorMatch != null) {
            // Only accept vector match after confirming the score is meaningful
            double vectorConfidence = vectorMatch.score();
            String category = heuristicEngine.getBrandCategoryMap().getOrDefault(vectorMatch.merchant(), "Uncategorized");
            log.info("Tier 2 (Vector) match for '{}' -> brand='{}', score={}", rawDescriptor, vectorMatch.merchant(), vectorConfidence);
            saveRawTransaction(userId, rawDescriptor, amount, currency, transactionDate,
                    vectorMatch.merchant(), category, vectorConfidence, ResolutionTier.TIER_2);
            return new ResolveResult(vectorMatch.merchant(), category, extractGateway(rawDescriptor),
                    vectorConfidence, ResolutionTier.TIER_2, "PINECONE_VECTOR");
        }

        // ------------------------------------------------------------------
        // Tier 2 — Heuristic extraction (fuzzy + alias + token)
        // The confidence score is derived from the full heuristic pipeline output, NOT set prematurely.
        // ------------------------------------------------------------------
        HeuristicExtractionEngine.HeuristicResolutionResult heur = heuristicEngine.resolve(rawDescriptor);
        if (!"UNKNOWN".equalsIgnoreCase(heur.brandName()) && heur.confidenceScore() >= 0.80) {
            log.info("Tier 2 (Heuristic) match for '{}' -> brand='{}', confidence={}", rawDescriptor, heur.brandName(), heur.confidenceScore());
            saveRawTransaction(userId, rawDescriptor, amount, currency, transactionDate,
                    heur.brandName(), heur.category(), heur.confidenceScore(), heur.resolutionTier());
            // NOTE: No write-back to pattern repo. Tier 2 results are NOT promoted to Tier 1.
            return new ResolveResult(heur.brandName(), heur.category(), heur.gateway(),
                    heur.confidenceScore(), heur.resolutionTier(), "HEURISTIC_RULE");
        }

        // ------------------------------------------------------------------
        // Tier 2.5 — Unnamed descriptor layer
        // If no brand resolved, check if the same descriptor string recurs for this user.
        // If it does, extract the common unique part of the descriptor string as name,
        // so that the recurring detection engine can group these payments.
        // ------------------------------------------------------------------
        RecurringExtraction recurringMatch = resolveByRecurringPattern(userId, rawDescriptor);
        if (recurringMatch != null) {
            String category = "Recurring — Unidentified";
            log.info("Tier 2.5 (Recurring unnamed) match for '{}' -> name='{}', confidence={}",
                    rawDescriptor, recurringMatch.merchantName(), recurringMatch.confidence());
            saveRawTransaction(userId, rawDescriptor, amount, currency, transactionDate,
                    recurringMatch.merchantName(), category, recurringMatch.confidence(), ResolutionTier.TIER_2);
            // Schedule Sarvam async enrichment — it's an unnamed recurring descriptor
            sarvamEnrichment.scheduleIfSuspicious(userId, rawDescriptor, recurringMatch.confidence(), "UNKNOWN");
            return new ResolveResult(recurringMatch.merchantName(), category, extractGateway(rawDescriptor),
                    recurringMatch.confidence(), ResolutionTier.TIER_2, "RECURRING_PATTERN_EXTRACT");
        }

        // ------------------------------------------------------------------
        // Tier 3 — Sarvam AI async enrichment (scheduled only, never synchronous)
        // Fire and forget. The transaction is saved now with low confidence;
        // the async task will update it once Sarvam responds.
        // ------------------------------------------------------------------
        double unknownConfidence = heur.confidenceScore(); // actual score from full heuristic run
        log.info("No Tier 1/2 match for '{}' — confidence={}. Scheduling Sarvam async enrichment.",
                rawDescriptor, unknownConfidence);
        saveRawTransaction(userId, rawDescriptor, amount, currency, transactionDate,
                heur.brandName(), heur.category(), unknownConfidence, heur.resolutionTier());
        sarvamEnrichment.scheduleIfSuspicious(userId, rawDescriptor, unknownConfidence, heur.brandName());
        return new ResolveResult(heur.brandName(), heur.category(), heur.gateway(),
                unknownConfidence, heur.resolutionTier(), null);
    }

    /**
     * Resolve and update an already-ingested transaction document without inserting a duplicate.
     * Same tier ordering as resolve(); Sarvam is async-only and never called here directly.
     */
    public ResolveResult resolveExisting(RawTransactionDocument transaction) {
        if (transaction == null || transaction.getRawDescriptor() == null || transaction.getRawDescriptor().isBlank()) {
            throw new IllegalArgumentException("Raw transaction descriptor is required");
        }
        String descriptor = transaction.getRawDescriptor();

        // Tier 1
        Optional<MerchantPatternDocument> match = sharedKnowledge.find(descriptor)
                .map(alias -> MerchantPatternDocument.builder()
                        .patternRegex("SHARED:" + alias.getDescriptorFingerprint())
                        .brandName(alias.getMerchantName())
                        .category(alias.getCategory())
                        .confidenceScore(0.98)
                        .verificationSource(alias.getVerificationSource())
                        .build());
        if (match.isEmpty()) match = findApprovedPatternMatch(descriptor, transaction.getUserId());
        if (match.isPresent()) {
            MerchantPatternDocument pattern = match.get();
            double conf = pattern.getConfidenceScore() != null ? pattern.getConfidenceScore() : 0.98;
            return new ResolveResult(pattern.getBrandName(), pattern.getCategory(),
                    extractGateway(descriptor), conf, ResolutionTier.TIER_1, pattern.getPatternRegex());
        }

        // Tier 2 — Vector
        PineconeMerchantVectorService.Match vectorMatch = pinecone.find(descriptor);
        if (vectorMatch != null) {
            String category = heuristicEngine.getBrandCategoryMap().getOrDefault(vectorMatch.merchant(), "Uncategorized");
            return new ResolveResult(vectorMatch.merchant(), category, extractGateway(descriptor),
                    vectorMatch.score(), ResolutionTier.TIER_2, "PINECONE_VECTOR");
        }

        // Tier 2 — Heuristic
        HeuristicExtractionEngine.HeuristicResolutionResult result = heuristicEngine.resolve(descriptor);
        if (!"UNKNOWN".equalsIgnoreCase(result.brandName()) && result.confidenceScore() >= 0.80) {
            return new ResolveResult(result.brandName(), result.category(), result.gateway(),
                    result.confidenceScore(), result.resolutionTier(), "HEURISTIC_RULE");
        }

        // Tier 2.5 — Recurring unnamed
        RecurringExtraction recurringMatch = resolveByRecurringPattern(transaction.getUserId(), descriptor);
        if (recurringMatch != null) {
            return new ResolveResult(recurringMatch.merchantName(), "Recurring — Unidentified", extractGateway(descriptor),
                    recurringMatch.confidence(), ResolutionTier.TIER_2, "RECURRING_PATTERN_EXTRACT");
        }

        // Return heuristic partial result (Sarvam will update async if applicable)
        return new ResolveResult(result.brandName(), result.category(), result.gateway(),
                result.confidenceScore(), result.resolutionTier(), null);
    }

    /**
     * Tier 2.5: Unnamed descriptor recurring check.
     * <p>
     * When a descriptor contains no recognizable merchant name from Tier 1 or Tier 2:
     * Check if previous transactions exist with the exact same descriptor string for this user.
     * If recurring payments are found, extract the common unique part of that descriptor string
     * as the merchant name and assign a confidence score reflecting recurrence strength.
     */
    private RecurringExtraction resolveByRecurringPattern(Long userId, String rawDescriptor) {
        if (userId == null || rawDescriptor == null || rawDescriptor.isBlank()) return null;
        try {
            List<RawTransactionDocument> existing = transactionRepo.findByUserIdAndRawDescriptor(userId, rawDescriptor);
            if (existing.isEmpty()) return null; // No previous transaction found, cannot confirm recurring yet

            // Extract the common unique part: strip stopwords, gateways, numbers, and separators
            List<String> uniqueTokens = Arrays.stream(rawDescriptor.toUpperCase(Locale.ROOT)
                            .replaceAll("[*_\\-./#@|:;,(){}\\[\\]\\d]+", " ")
                            .trim()
                            .split("\\s+"))
                    .filter(t -> t.length() >= 2)
                    .filter(t -> !heuristicEngine.getStopwords().contains(t))
                    .filter(t -> !heuristicEngine.getGateways().contains(t))
                    .distinct()
                    .toList();

            String commonPart;
            if (uniqueTokens.isEmpty()) {
                String clean = rawDescriptor.replaceAll("[^A-Za-z]+", " ").trim();
                commonPart = clean.isBlank() ? "RECURRING_MANDATE" : clean;
            } else {
                commonPart = String.join(" ", uniqueTokens);
            }

            // Confidence score is set only after complete empirical check:
            // 1 previous occurrence (2 total) -> 0.75; 2+ previous (3+ total) -> 0.85
            double confidence = existing.size() >= 2 ? 0.85 : 0.75;
            return new RecurringExtraction(commonPart, confidence);
        } catch (Exception e) {
            log.warn("Tier 2.5 recurring check failed for '{}': {}", rawDescriptor, e.getMessage());
            return null;
        }
    }

    private record RecurringExtraction(String merchantName, double confidence) {}

    /**
     * Look up system-approved patterns from the pattern repository.
     * Only patterns with verificationSource set (i.e., seeded/admin-approved) are used.
     * Auto-learned patterns from AI/heuristics are excluded.
     */
    private Optional<MerchantPatternDocument> findApprovedPatternMatch(String rawDescriptor, Long userId) {
        if (rawDescriptor == null || rawDescriptor.isBlank()) return Optional.empty();
        List<MerchantPatternDocument> patterns = patternRepo.findAll().stream()
                .filter(p -> p.getUserId() == null || Objects.equals(p.getUserId(), userId))
                // Only system-approved (seeded) patterns; not auto-learned ones
                .filter(p -> p.getVerificationSource() != null
                        && !p.getVerificationSource().startsWith("RESOLVER_AUTO")
                        && !p.getVerificationSource().startsWith("GEMINI_USER")
                        && !p.getVerificationSource().startsWith("SARVAM_USER"))
                .filter(p -> p.getBrandName() != null && !"UNKNOWN".equalsIgnoreCase(p.getBrandName()))
                .toList();

        // Exact descriptor match first
        Optional<MerchantPatternDocument> exact = patterns.stream()
                .filter(pat -> Pattern.quote(rawDescriptor).equals(pat.getPatternRegex()))
                .findFirst();
        if (exact.isPresent()) return exact;

        // Regex match
        for (MerchantPatternDocument pat : patterns) {
            if (pat.getPatternRegex() == null || pat.getPatternRegex().isBlank()) continue;
            try {
                Pattern p = Pattern.compile(pat.getPatternRegex(), Pattern.CASE_INSENSITIVE);
                if (p.matcher(rawDescriptor).matches()) return Optional.of(pat);
            } catch (Exception e) {
                log.warn("Invalid regex pattern for brand {}: {}", pat.getBrandName(), pat.getPatternRegex());
            }
        }
        return Optional.empty();
    }

    private void saveRawTransaction(Long userId, String rawDescriptor,
                                   BigDecimal amount, String currency,
                                   Instant txnDate, String brand, String category,
                                   Double confidence, ResolutionTier tier) {
        try {
            RawTransactionDocument tx = RawTransactionDocument.builder()
                    .userId(userId)
                    .rawDescriptor(rawDescriptor)
                    .amount(amount)
                    .currency(currency != null ? currency : "INR")
                    .transactionDate(txnDate != null ? txnDate : Instant.now())
                    .paymentRail(extractGateway(rawDescriptor))
                    .resolvedMerchantName(brand)
                    .resolvedCategory(category)
                    .confidenceScore(confidence)
                    .resolutionTier(tier)
                    .build();
            transactionRepo.save(tx);
            log.debug("Saved raw transaction for descriptor: '{}'", rawDescriptor);
        } catch (Exception e) {
            log.error("Failed to save raw transaction for '{}': {}", rawDescriptor, e.getMessage(), e);
        }
    }

    private String extractGateway(String raw) {
        String upper = raw.toUpperCase();
        for (String gw : heuristicEngine.getGateways()) {
            if (upper.startsWith(gw + "_") || upper.startsWith(gw + "/")
                    || upper.startsWith(gw + "-") || upper.startsWith(gw + " ")) {
                return gw;
            }
        }
        for (String gw : heuristicEngine.getGateways()) {
            if (upper.contains(gw)) return gw;
        }
        return "UNKNOWN";
    }

    /** Returns only system-approved patterns (not auto-learned). Used by the controller. */
    public List<MerchantPatternDocument> getAllPatterns(Long userId) {
        return patternRepo.findAll().stream()
                .filter(p -> p.getUserId() == null || Objects.equals(p.getUserId(), userId))
                .filter(p -> p.getVerificationSource() != null
                        && !p.getVerificationSource().startsWith("RESOLVER_AUTO")
                        && !p.getVerificationSource().startsWith("GEMINI_USER")
                        && !p.getVerificationSource().startsWith("SARVAM_USER"))
                .toList();
    }

    /** No-op kept for API compatibility — no in-memory cache exists anymore. */
    public void clearPatternCache() {
        log.info("clearPatternCache() called — no in-memory cache to clear (by design).");
    }

    public record ResolveResult(
            String brandName,
            String category,
            String gateway,
            double confidenceScore,
            ResolutionTier resolutionTier,
            String matchedPattern) {}
}
