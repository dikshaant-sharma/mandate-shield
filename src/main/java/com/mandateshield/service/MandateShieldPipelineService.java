package com.mandateshield.service;

import com.mandateshield.domain.*;
import com.mandateshield.engine.MerchantResolverEngine;
import com.mandateshield.engine.anomaly.AnomalyDetectionEngine;
import com.mandateshield.engine.discovery.RecurringDiscoveryEngine;
import com.mandateshield.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MandateShieldPipelineService {
    private final RawTransactionRepository transactionRepository;
    private final MerchantResolverEngine resolverEngine;
    private final RecurringDiscoveryEngine discoveryEngine;
    private final AnomalyDetectionEngine anomalyDetectionEngine;
    private final SubscriptionRepository subscriptionRepository;
    private final AnomalyRepository anomalyRepository;
    private final UserRepository userRepository;

    public MandateShieldPipelineService(
            RawTransactionRepository transactionRepository,
            MerchantResolverEngine resolverEngine,
            RecurringDiscoveryEngine discoveryEngine,
            AnomalyDetectionEngine anomalyDetectionEngine,
            SubscriptionRepository subscriptionRepository,
            AnomalyRepository anomalyRepository,
            UserRepository userRepository) {
        this.transactionRepository = transactionRepository;
        this.resolverEngine = resolverEngine;
        this.discoveryEngine = discoveryEngine;
        this.anomalyDetectionEngine = anomalyDetectionEngine;
        this.subscriptionRepository = subscriptionRepository;
        this.anomalyRepository = anomalyRepository;
        this.userRepository = userRepository;
    }

    @Async
    public CompletableFuture<PipelineRunResult> processUser(Long userId) {
        return CompletableFuture.completedFuture(processUserNow(userId));
    }

    /** Runs the same user-scoped pipeline with batched in-memory scale optimizations. */
    public PipelineRunResult processUserNow(Long userId) {
        UserDocument user = userRepository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("User " + userId + " was not found"));
        List<RawTransactionDocument> transactions = transactionRepository.findByUserId(userId);
        if (transactions.isEmpty()) return new PipelineRunResult(userId, 0, 0, 0);

        List<RawTransactionDocument> dirtyTransactions = new ArrayList<>();
        for (RawTransactionDocument tx : transactions) {
            if (tx.getRawDescriptor() == null || tx.getRawDescriptor().isBlank()) continue;
            MerchantResolverEngine.ResolveResult result = resolverEngine.resolveExisting(tx);
            if ("UNKNOWN".equalsIgnoreCase(result.brandName()) && result.confidenceScore() <= 0.50) continue;

            boolean dirty = false;
            if (result.brandName() != null && !result.brandName().isBlank()
                    && !"UNKNOWN".equalsIgnoreCase(result.brandName())
                    && !result.brandName().equals(tx.getResolvedMerchantName())) {
                tx.setResolvedMerchantName(result.brandName());
                dirty = true;
            }
            if ((tx.getResolvedCategory() == null || tx.getResolvedCategory().isBlank()
                    || "Uncategorized".equalsIgnoreCase(tx.getResolvedCategory()))
                    && result.category() != null && !result.category().isBlank()) {
                tx.setResolvedCategory(result.category());
                dirty = true;
            }
            if ((tx.getPaymentRail() == null || tx.getPaymentRail().isBlank()
                    || "OTHER".equalsIgnoreCase(tx.getPaymentRail()) || "UNKNOWN".equalsIgnoreCase(tx.getPaymentRail()))
                    && result.gateway() != null && !"UNKNOWN".equalsIgnoreCase(result.gateway())) {
                tx.setPaymentRail(result.gateway());
                dirty = true;
            }
            if (tx.getConfidenceScore() == null || Math.abs(tx.getConfidenceScore() - result.confidenceScore()) > 0.001) {
                tx.setConfidenceScore(result.confidenceScore());
                dirty = true;
            }
            if (tx.getResolutionTier() != result.resolutionTier()) {
                tx.setResolutionTier(result.resolutionTier());
                dirty = true;
            }
            if (dirty) {
                dirtyTransactions.add(tx);
            }
        }

        // Batch save dirty transactions in chunks of 200
        if (!dirtyTransactions.isEmpty()) {
            for (int i = 0; i < dirtyTransactions.size(); i += 200) {
                int end = Math.min(i + 200, dirtyTransactions.size());
                transactionRepository.saveAll(dirtyTransactions.subList(i, end));
            }
        }

        Map<String, List<RawTransactionDocument>> grouped = transactions.stream()
                .filter(tx -> tx.getResolvedMerchantName() != null && !tx.getResolvedMerchantName().isBlank()
                        && !"UNKNOWN".equalsIgnoreCase(tx.getResolvedMerchantName()))
                .collect(Collectors.groupingBy(RawTransactionDocument::getResolvedMerchantName));

        // Single query for all existing subscriptions to avoid N+1 queries
        Map<String, SubscriptionDocument> existingSubs = subscriptionRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(
                        s -> s.getMerchantName() != null ? s.getMerchantName().toUpperCase(Locale.ROOT) : "",
                        s -> s,
                        (s1, s2) -> s1
                ));

        List<SubscriptionDocument> toSaveSubscriptions = new ArrayList<>();
        Map<String, List<RawTransactionDocument>> merchantTxMap = new HashMap<>();

        for (var entry : grouped.entrySet()) {
            SubscriptionDocument candidate = discoveryEngine.discover(userId, entry.getKey(), entry.getValue(), null);
            if (candidate == null) continue;
            SubscriptionDocument existing = existingSubs.get(entry.getKey().toUpperCase(Locale.ROOT));
            if (existing != null) {
                candidate.setId(existing.getId());
                candidate.setUserReportedUnused(existing.getUserReportedUnused());
                candidate.setLastUserActivityDate(existing.getLastUserActivityDate());
                candidate.setFirstDetectedAt(existing.getFirstDetectedAt() == null ? candidate.getFirstDetectedAt() : existing.getFirstDetectedAt());
                if (existing.getStatus() == SubscriptionStatus.CANCELLED
                        || existing.getStatus() == SubscriptionStatus.CANCELLATION_REQUESTED) {
                    candidate.setStatus(existing.getStatus());
                }
            }
            toSaveSubscriptions.add(candidate);
            merchantTxMap.put(candidate.getMerchantName(), entry.getValue());
        }

        List<SubscriptionDocument> savedSubscriptions = subscriptionRepository.saveAll(toSaveSubscriptions);
        int discovered = savedSubscriptions.size();
        int anomalyCount = 0;

        List<AnomalyDocument> allAnomalies = new ArrayList<>();
        for (SubscriptionDocument saved : savedSubscriptions) {
            anomalyRepository.deleteBySubscriptionId(saved.getId());
            List<RawTransactionDocument> txList = merchantTxMap.get(saved.getMerchantName());
            if (txList != null) {
                List<AnomalyDocument> anomalies = anomalyDetectionEngine.detectAnomalies(saved, txList);
                allAnomalies.addAll(anomalies);
            }
        }

        if (!allAnomalies.isEmpty()) {
            anomalyRepository.saveAll(allAnomalies);
            anomalyCount = allAnomalies.size();
        }

        log.info("Batched pipeline processed user {}: {} transactions, {} subscriptions, {} anomalies",
                userId, transactions.size(), discovered, anomalyCount);
        return new PipelineRunResult(userId, transactions.size(), discovered, anomalyCount);
    }

    public record PipelineRunResult(Long userId, int processedTransactions, int discoveredSubscriptions, int detectedAnomalies) {}
}
