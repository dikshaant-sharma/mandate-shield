package com.mandateshield.controller;

import com.mandateshield.engine.MerchantResolverEngine;
import com.mandateshield.service.SarvamMerchantEnrichmentService;
import com.mandateshield.engine.MerchantResolverEngine.ResolveResult;
import com.mandateshield.dto.ResolveResponse;
import com.mandateshield.dto.ResolveRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/resolver")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Merchant Resolver", description = "Resolve raw transaction descriptors to merchant identities")
public class MerchantResolverController {

    private final MerchantResolverEngine resolverEngine;
    private final SarvamMerchantEnrichmentService sarvamEnrichment;

    @PostMapping("/resolve")
    @Operation(summary = "Resolve a raw transaction descriptor",
               description = "Resolves a raw bank/payment descriptor through Tier 1 (system-approved aliases), "
                       + "Tier 2 (heuristic/vector), Tier 2.5 (unnamed recurring), and Tier 3 (Sarvam AI async).")
    public ResponseEntity<ResolveResponse> resolve(@AuthenticationPrincipal Long userId, @RequestBody ResolveRequest request) {
        if (request.rawDescriptor() == null || request.rawDescriptor().isBlank()) {
            throw new IllegalArgumentException("rawDescriptor must be provided");
        }

        ResolveResult result = resolverEngine.resolve(
                request.rawDescriptor(),
                userId,
                request.amount(),
                request.currency(),
                request.transactionDate()
        );

        // Sarvam enrichment is already scheduled inside MerchantResolverEngine for Tier 2.5/unknown cases.
        // We also schedule here defensively in case the caller bypasses the engine for a suspicious result.
        sarvamEnrichment.scheduleIfSuspicious(userId, request.rawDescriptor(),
                result.confidenceScore(), result.brandName());

        ResolveResponse response = new ResolveResponse(
                result.brandName(),
                result.category(),
                result.gateway(),
                result.confidenceScore(),
                result.resolutionTier(),
                result.matchedPattern()
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/patterns")
    @Operation(summary = "List system-approved merchant patterns",
               description = "Returns only system-seeded/admin-approved merchant patterns. Auto-learned entries are excluded.")
    public ResponseEntity<?> listPatterns(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(resolverEngine.getAllPatterns(userId));
    }

    @PostMapping("/patterns/cache/clear")
    @Operation(summary = "No-op cache clear (retained for API compatibility)",
               description = "No in-memory pattern cache exists. Endpoint retained for client compatibility.")
    public ResponseEntity<?> clearCache() {
        resolverEngine.clearPatternCache();
        return ResponseEntity.ok(Map.of("status", "no_cache_exists", "message", "Pattern cache removed by design. Only system-approved DB entries are used."));
    }
}
