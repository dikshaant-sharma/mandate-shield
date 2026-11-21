package com.mandateshield.service;

import com.mandateshield.domain.MerchantPatternDocument;
import com.mandateshield.repository.MerchantPatternRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Initializes and seeds canonical, system-approved merchant patterns directly into MongoDB.
 * Only system-approved or user-confirmed patterns belong here.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MerchantPatternDatabaseInitializer {

    private final MerchantPatternRepository patternRepository;

    public record SeedPattern(String regex, String brandName, String category, double confidence) {}

    private static final List<SeedPattern> SYSTEM_APPROVED_PATTERNS = List.of(
            new SeedPattern("(?i).*NETFLIX.*|.*NFLX.*", "NETFLIX", "Entertainment", 0.99),
            new SeedPattern("(?i).*SPOTIFY.*|.*SPOTITY.*", "SPOTIFY", "Audio & Music", 0.99),
            new SeedPattern("(?i).*AMAZON.*|.*AMZN.*|.*PRIME.*", "AMAZON", "E-Commerce", 0.99),
            new SeedPattern("(?i).*HOTSTAR.*|.*DISNEY.*", "DISNEY+", "Entertainment", 0.99),
            new SeedPattern("(?i).*SWIGGY.*|.*SWIGGYONE.*", "SWIGGY", "Food & Dining", 0.99),
            new SeedPattern("(?i).*ZOMATO.*|.*GOLDMEMBERSHIP.*", "ZOMATO", "Food & Dining", 0.99),
            new SeedPattern("(?i).*YOUTUBE.*|.*GOOGLE.*YTB.*", "YOUTUBE", "Streaming", 0.99),
            new SeedPattern("(?i).*APPLE.*|.*ITUNES.*|.*ICLOUD.*", "APPLE", "Software & Services", 0.99),
            new SeedPattern("(?i).*GOOGLE.*|.*GSUITE.*|.*PLAYSTORE.*", "GOOGLE", "Digital Services", 0.99),
            new SeedPattern("(?i).*ADOBE.*|.*CREATIVE.*CLOUD.*", "ADOBE", "Creative & Software", 0.99),
            new SeedPattern("(?i).*NOTION.*", "NOTION", "Productivity", 0.99),
            new SeedPattern("(?i).*LINKEDIN.*", "LINKEDIN", "Professional Network", 0.99),
            new SeedPattern("(?i).*DROPBOX.*", "DROPBOX", "Cloud Storage", 0.99),
            new SeedPattern("(?i).*AUDIBLE.*", "AUDIBLE", "Books & Audio", 0.99),
            new SeedPattern("(?i).*JIO.*|.*JIOCINEMA.*|.*JIOFIBER.*", "JIO", "Telecom & Utilities", 0.98),
            new SeedPattern("(?i).*AIRTEL.*|.*AIRTELXSTREAM.*", "AIRTEL", "Telecom & Utilities", 0.98),
            new SeedPattern("(?i).*CULT.*FIT.*|.*CULTFIT.*|.*CULT.*PASS.*", "CULT.FIT", "Fitness", 0.98),
            new SeedPattern("(?i).*MICROSOFT.*|.*OFFICE365.*|.*MSFT.*", "MICROSOFT", "Productivity", 0.99),
            new SeedPattern("(?i).*AWS.*|.*AMAZON.*WEB.*SERVICES.*", "AWS", "Cloud Infrastructure", 0.99),
            new SeedPattern("(?i).*CHATGPT.*|.*OPENAI.*", "OPENAI", "AI & Tools", 0.99)
    );

    @PostConstruct
    public void seedSystemApprovedPatterns() {
        try {
            long existingCount = patternRepository.count();
            if (existingCount < SYSTEM_APPROVED_PATTERNS.size()) {
                log.info("Seeding system-approved Tier 1 merchant patterns into database (existing={})", existingCount);
                for (SeedPattern sp : SYSTEM_APPROVED_PATTERNS) {
                    boolean exists = patternRepository.findAll().stream()
                            .anyMatch(p -> sp.brandName().equalsIgnoreCase(p.getBrandName())
                                    && sp.regex().equalsIgnoreCase(p.getPatternRegex())
                                    && p.getUserId() == null);
                    if (!exists) {
                        patternRepository.save(MerchantPatternDocument.builder()
                                .patternRegex(sp.regex())
                                .brandName(sp.brandName())
                                .category(sp.category())
                                .userId(null) // Global system-approved pattern
                                .confidenceScore(sp.confidence())
                                .verificationSource("SYSTEM_CURATED")
                                .createdAt(Instant.now())
                                .build());
                    }
                }
                log.info("System-approved patterns successfully verified/seeded.");
            }
        } catch (Exception e) {
            log.warn("Database initialization of system approved patterns skipped or deferred: {}", e.getMessage());
        }
    }
}
