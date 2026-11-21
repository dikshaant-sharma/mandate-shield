package com.mandateshield.controller;

import com.mandateshield.domain.SubscriptionDocument;
import com.mandateshield.domain.SubscriptionStatus;
import com.mandateshield.domain.UserDocument;
import com.mandateshield.repository.SubscriptionRepository;
import com.mandateshield.repository.UserRepository;
import com.mandateshield.service.SarvamAiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1/voice")
@RequiredArgsConstructor
public class VoiceController {

    private final SarvamAiService sarvamService;
    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;

    @GetMapping("/brief/{subscriptionId}")
    public ResponseEntity<SarvamAiService.VoiceBriefingResult> getSubscriptionBriefing(
            @AuthenticationPrincipal Long userId,
            @PathVariable String subscriptionId) {
        SubscriptionDocument subscription = subscriptionRepository.findByIdAndUserId(subscriptionId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recurring payment not found."));
        UserDocument user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found."));

        SarvamAiService.VoiceBriefingResult briefing = sarvamService.generateHinglishBriefing(subscription, user.getFullName());
        return ResponseEntity.ok(briefing);
    }

    @GetMapping("/summary")
    public ResponseEntity<SarvamAiService.VoiceBriefingResult> getAccountSummary(
            @AuthenticationPrincipal Long userId) {
        List<SubscriptionDocument> active = subscriptionRepository.findByUserIdAndStatusIn(
                userId, List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.TENTATIVE)
        );
        UserDocument user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found."));

        if (active.isEmpty()) {
            return ResponseEntity.ok(new SarvamAiService.VoiceBriefingResult(
                    "summary",
                    "All Merchants",
                    "Namaste " + (user.getFullName() != null ? user.getFullName().split("\\s+")[0] : "User") + ", aapke account mein abhi koi active recurring payment nahi hai.",
                    null,
                    "hi-IN (Hinglish)",
                    "meera",
                    false
            ));
        }

        // Generate summary from the earliest upcoming or highest value subscription
        SubscriptionDocument primary = active.get(0);
        SarvamAiService.VoiceBriefingResult result = sarvamService.generateHinglishBriefing(primary, user.getFullName());
        return ResponseEntity.ok(result);
    }
}
