package com.mandateshield;

import com.mandateshield.domain.*;
import com.mandateshield.engine.HeuristicExtractionEngine;
import com.mandateshield.engine.MerchantResolverEngine;
import com.mandateshield.repository.*;
import com.mandateshield.service.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class DescriptorMatchingAndFeaturesTest {

    private final HeuristicDictionaryRepository dictionaryRepo = Mockito.mock(HeuristicDictionaryRepository.class);

    private HeuristicExtractionEngine createEngine() {
        when(dictionaryRepo.findAll()).thenReturn(List.of());
        HeuristicExtractionEngine engine = new HeuristicExtractionEngine(dictionaryRepo);
        engine.init();
        return engine;
    }

    @Test
    @DisplayName("Tier 2 Heuristic: Resolves S_P_O_T_I_T_Y with single-letter concatenation and fuzzy matching to SPOTIFY")
    void testSpotityResolution() {
        HeuristicExtractionEngine engine = createEngine();
        var result = engine.resolve("S_P_O_T_I_T_Y");

        assertEquals("SPOTIFY", result.brandName(), "S_P_O_T_I_T_Y must resolve to SPOTIFY");
        assertEquals("Audio & Music", result.category());
        assertTrue(result.confidenceScore() >= 0.80, "Confidence score should be >= 0.80");
        assertEquals(ResolutionTier.TIER_2, result.resolutionTier());
    }

    @Test
    @DisplayName("Tier 2 Heuristic: Resolves S_P_O_T_I_F_Y with single-letter concatenation to SPOTIFY")
    void testSpotifySeparatedResolution() {
        HeuristicExtractionEngine engine = createEngine();
        var result = engine.resolve("S_P_O_T_I_F_Y");

        assertEquals("SPOTIFY", result.brandName(), "S_P_O_T_I_F_Y must resolve to SPOTIFY");
        assertEquals("Audio & Music", result.category());
        assertTrue(result.confidenceScore() >= 0.85);
    }

    @Test
    @DisplayName("Tier 2 Heuristic: Strips bank stopwords and resolves ACH_INB_S_P_O_T_I_F_Y_IN_9812 to SPOTIFY")
    void testBankDescriptorWithStopwords() {
        HeuristicExtractionEngine engine = createEngine();
        var result = engine.resolve("ACH_INB_S_P_O_T_I_F_Y_IN_9812");

        assertEquals("SPOTIFY", result.brandName());
        assertEquals("Audio & Music", result.category());
    }

    @Test
    @DisplayName("Tier 2 Heuristic: Detects gateway and resolves RAZORPAY*HOTSTAR_PREMIUM_BLR")
    void testGatewayAndAliasExtraction() {
        HeuristicExtractionEngine engine = createEngine();
        var result = engine.resolve("RAZORPAY*HOTSTAR_PREMIUM_BLR");

        assertEquals("DISNEY+", result.brandName());
        assertEquals("Entertainment", result.category());
    }

    @Test
    @DisplayName("Tier 2 Heuristic: Resolves SWIGGYONE and ZOMATO")
    void testDeliveryServices() {
        HeuristicExtractionEngine engine = createEngine();
        assertEquals("SWIGGY", engine.resolve("PAYU*SWIGGYONE_MANDATE_HYD").brandName());
        assertEquals("ZOMATO", engine.resolve("ZOMATO*GOLDMEMBERSHIP_GURGAON_IN").brandName());
    }

    @Test
    @DisplayName("Tier 1: MerchantResolverEngine matches stored system-approved pattern and returns TIER_1")
    void testTier1StoredPatternMatch() {
        MerchantPatternRepository patternRepo = Mockito.mock(MerchantPatternRepository.class);
        RawTransactionRepository txRepo = Mockito.mock(RawTransactionRepository.class);
        SharedMerchantKnowledgeService sharedKnowledge = Mockito.mock(SharedMerchantKnowledgeService.class);
        PineconeMerchantVectorService pinecone = Mockito.mock(PineconeMerchantVectorService.class);
        SarvamMerchantEnrichmentService sarvam = Mockito.mock(SarvamMerchantEnrichmentService.class);

        HeuristicExtractionEngine heuristic = createEngine();

        MerchantPatternDocument stored = MerchantPatternDocument.builder()
                .patternRegex("\\QNETFLIX SUBSCRIPTION\\E")
                .brandName("NETFLIX")
                .category("Entertainment")
                .confidenceScore(0.98)
                .verificationSource("SYSTEM_SEEDED")
                .build();

        when(sharedKnowledge.find(any())).thenReturn(Optional.empty());
        when(patternRepo.findAll()).thenReturn(List.of(stored));

        MerchantResolverEngine resolver = new MerchantResolverEngine(
                patternRepo, txRepo, heuristic, sharedKnowledge, pinecone, sarvam
        );

        var result = resolver.resolve("NETFLIX SUBSCRIPTION", 101L, BigDecimal.valueOf(499), "INR", Instant.now());
        assertEquals(ResolutionTier.TIER_1, result.resolutionTier());
        assertEquals("NETFLIX", result.brandName());
        assertEquals(0.98, result.confidenceScore());
    }

    @Test
    @DisplayName("Tier 2: Pinecone Vector Service match returns TIER_2 with score")
    void testTier2PineconeVectorMatch() {
        MerchantPatternRepository patternRepo = Mockito.mock(MerchantPatternRepository.class);
        RawTransactionRepository txRepo = Mockito.mock(RawTransactionRepository.class);
        SharedMerchantKnowledgeService sharedKnowledge = Mockito.mock(SharedMerchantKnowledgeService.class);
        PineconeMerchantVectorService pinecone = Mockito.mock(PineconeMerchantVectorService.class);
        SarvamMerchantEnrichmentService sarvam = Mockito.mock(SarvamMerchantEnrichmentService.class);

        HeuristicExtractionEngine heuristic = createEngine();

        when(sharedKnowledge.find(any())).thenReturn(Optional.empty());
        when(patternRepo.findAll()).thenReturn(List.of());
        when(pinecone.find("OBSCURE_STREAMING_SERVICE")).thenReturn(new PineconeMerchantVectorService.Match("SPOTIFY", 0.89));

        MerchantResolverEngine resolver = new MerchantResolverEngine(
                patternRepo, txRepo, heuristic, sharedKnowledge, pinecone, sarvam
        );

        var result = resolver.resolve("OBSCURE_STREAMING_SERVICE", 101L, BigDecimal.valueOf(119), "INR", Instant.now());
        assertEquals(ResolutionTier.TIER_2, result.resolutionTier());
        assertEquals("SPOTIFY", result.brandName());
        assertEquals(0.89, result.confidenceScore());
        assertEquals("PINECONE_VECTOR", result.matchedPattern());
    }

    @Test
    @DisplayName("Tier 3 (async): Unknown descriptor below threshold schedules Sarvam async enrichment and returns low-confidence result")
    void testTier3SarvamScheduled() {
        MerchantPatternRepository patternRepo = Mockito.mock(MerchantPatternRepository.class);
        RawTransactionRepository txRepo = Mockito.mock(RawTransactionRepository.class);
        SharedMerchantKnowledgeService sharedKnowledge = Mockito.mock(SharedMerchantKnowledgeService.class);
        PineconeMerchantVectorService pinecone = Mockito.mock(PineconeMerchantVectorService.class);
        SarvamMerchantEnrichmentService sarvam = Mockito.mock(SarvamMerchantEnrichmentService.class);

        HeuristicExtractionEngine heuristic = createEngine();

        when(sharedKnowledge.find(any())).thenReturn(Optional.empty());
        when(patternRepo.findAll()).thenReturn(List.of());
        when(pinecone.find(any())).thenReturn(null);

        MerchantResolverEngine resolver = new MerchantResolverEngine(
                patternRepo, txRepo, heuristic, sharedKnowledge, pinecone, sarvam
        );

        // A truly cryptic descriptor that heuristics cannot resolve
        var result = resolver.resolve("ZXQ99182KMNOP", 101L, BigDecimal.valueOf(119), "INR", Instant.now());
        // Should be UNKNOWN or partial with low confidence
        assertTrue(result.confidenceScore() < 0.80, "Unknown descriptor must have low confidence");
        // Sarvam scheduleIfSuspicious was called (verify via Mockito)
        Mockito.verify(sarvam).scheduleIfSuspicious(any(), any(), any(Double.class), any());
    }

    @Test
    @DisplayName("Tier 2.5: Unnamed recurring descriptor produces group name placeholder")
    void testTier25UnnamedRecurring() {
        MerchantPatternRepository patternRepo = Mockito.mock(MerchantPatternRepository.class);
        RawTransactionRepository txRepo = Mockito.mock(RawTransactionRepository.class);
        SharedMerchantKnowledgeService sharedKnowledge = Mockito.mock(SharedMerchantKnowledgeService.class);
        PineconeMerchantVectorService pinecone = Mockito.mock(PineconeMerchantVectorService.class);
        SarvamMerchantEnrichmentService sarvam = Mockito.mock(SarvamMerchantEnrichmentService.class);

        HeuristicExtractionEngine heuristic = createEngine();

        String crypticDesc = "ACH_INB_XYZ99182_MANDATE_SUB";

        // Simulate 2 previous occurrences of the same descriptor (recurring)
        RawTransactionDocument prev1 = RawTransactionDocument.builder()
                .userId(101L).rawDescriptor(crypticDesc).amount(BigDecimal.valueOf(500))
                .transactionDate(Instant.now().minusSeconds(60 * 60 * 24 * 30)).build();
        RawTransactionDocument prev2 = RawTransactionDocument.builder()
                .userId(101L).rawDescriptor(crypticDesc).amount(BigDecimal.valueOf(500))
                .transactionDate(Instant.now().minusSeconds(60 * 60 * 24 * 60)).build();

        when(sharedKnowledge.find(any())).thenReturn(Optional.empty());
        when(patternRepo.findAll()).thenReturn(List.of());
        when(pinecone.find(any())).thenReturn(null);
        when(txRepo.findByUserIdAndRawDescriptor(101L, crypticDesc)).thenReturn(List.of(prev1, prev2));

        MerchantResolverEngine resolver = new MerchantResolverEngine(
                patternRepo, txRepo, heuristic, sharedKnowledge, pinecone, sarvam
        );

        var result = resolver.resolve(crypticDesc, 101L, BigDecimal.valueOf(500), "INR", Instant.now());
        // Tier 2.5 extracts the common unique token string (XYZ) from the recurring descriptor
        assertTrue(result.brandName().contains("XYZ"), "Unnamed recurring descriptor must extract common unique part: " + result.brandName());
        assertEquals("RECURRING_PATTERN_EXTRACT", result.matchedPattern());
        assertEquals(0.85, result.confidenceScore(), 0.01);
    }
}
