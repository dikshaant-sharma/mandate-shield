package com.mandateshield.config;

import com.mandateshield.domain.HeuristicDictionaryDocument;
import com.mandateshield.domain.MerchantAliasDocument;
import com.mandateshield.repository.HeuristicDictionaryRepository;
import com.mandateshield.repository.MerchantAliasRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Seeds the MongoDB database with:
 * 1. System-approved merchant aliases (shared_merchant_aliases) — these are the ONLY entries that
 *    get Tier 1 treatment. Auto-learned or AI-generated names are NEVER added here automatically.
 * 2. Heuristic dictionary (gateways, stopwords, alias map, brand-category map).
 *
 * To add a new approved merchant, add it to APPROVED_MERCHANTS below and redeploy.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MongoSeeder {

    private final MerchantAliasRepository aliasRepository;
    private final HeuristicDictionaryRepository heuristicDictionaryRepository;
    private final com.mandateshield.repository.MerchantPatternRepository patternRepository;

    // -----------------------------------------------------------------------
    // System-approved merchant descriptors → canonical brand name + category.
    // Format: { normalised descriptor string, merchant name, category }
    // These are the ONLY entries that bypass heuristics and go straight to Tier 1.
    // -----------------------------------------------------------------------
    private static final List<String[]> APPROVED_MERCHANTS = List.of(
            // Streaming
            new String[]{"NFLX IN",             "NETFLIX",              "Entertainment"},
            new String[]{"NETFLIX",              "NETFLIX",              "Entertainment"},
            new String[]{"ACH INB NFLX IN",      "NETFLIX",              "Entertainment"},
            new String[]{"SPOTIFY",              "SPOTIFY",              "Audio & Music"},
            new String[]{"SPOTITY",              "SPOTIFY",              "Audio & Music"},
            new String[]{"S P O T I F Y",        "SPOTIFY",              "Audio & Music"},
            new String[]{"HOTSTAR",              "DISNEY+ HOTSTAR",      "Entertainment"},
            new String[]{"DISNEY HOTSTAR",       "DISNEY+ HOTSTAR",      "Entertainment"},
            new String[]{"RAZORPAY HOTSTAR",     "DISNEY+ HOTSTAR",      "Entertainment"},
            new String[]{"YOUTUBE PREMIUM",      "YOUTUBE PREMIUM",      "Streaming"},
            new String[]{"GOOGLE YTB",           "YOUTUBE PREMIUM",      "Streaming"},
            new String[]{"PRIME VIDEO",          "AMAZON PRIME",         "Entertainment"},
            new String[]{"AMAZON PRIME VIDEO",   "AMAZON PRIME",         "Entertainment"},
            new String[]{"JIOCINEMA",            "JIO CINEMA",           "Entertainment"},
            new String[]{"SONY LIV",             "SONY LIV",             "Entertainment"},
            new String[]{"SONYLIV",              "SONY LIV",             "Entertainment"},
            new String[]{"ZEE",                  "ZEE5",                 "Entertainment"},
            new String[]{"MXPLAYER",             "MX PLAYER",            "Entertainment"},
            // Shopping
            new String[]{"AMZN MKTP",            "AMAZON",               "E-Commerce"},
            new String[]{"AMAZON",               "AMAZON",               "E-Commerce"},
            new String[]{"FLIPKART",             "FLIPKART",             "E-Commerce"},
            new String[]{"MYNTRA",               "MYNTRA",               "E-Commerce"},
            new String[]{"MEESHO",               "MEESHO",               "E-Commerce"},
            new String[]{"AJIO",                 "AJIO",                 "E-Commerce"},
            new String[]{"NYKAA",                "NYKAA",                "E-Commerce"},
            // Food & Dining
            new String[]{"SWIGGY",               "SWIGGY",               "Food & Dining"},
            new String[]{"PAYU SWIGGYONE",       "SWIGGY",               "Food & Dining"},
            new String[]{"ZOMATO",               "ZOMATO",               "Food & Dining"},
            new String[]{"ZOMATO GOLD",          "ZOMATO GOLD",          "Food & Dining"},
            new String[]{"BLINKIT",              "BLINKIT",              "Grocery"},
            new String[]{"ZEPTO",                "ZEPTO",                "Grocery"},
            new String[]{"BIGBASKET",            "BIGBASKET",            "Grocery"},
            new String[]{"DUNZO",                "DUNZO",                "Delivery"},
            // Productivity & Software
            new String[]{"ADOBE",                "ADOBE",                "Creative & Software"},
            new String[]{"NOTION",               "NOTION",               "Productivity"},
            new String[]{"LINKEDIN",             "LINKEDIN",             "Professional Network"},
            new String[]{"DROPBOX",              "DROPBOX",              "Cloud Storage"},
            new String[]{"MICROSOFT",            "MICROSOFT",            "Software & Services"},
            new String[]{"OFFICE",               "MICROSOFT 365",        "Productivity"},
            new String[]{"GOOGLE ONE",           "GOOGLE ONE",           "Cloud Storage"},
            new String[]{"GOOGLE WORKSPACE",     "GOOGLE WORKSPACE",     "Productivity"},
            new String[]{"GITHUB",               "GITHUB",               "Developer Tools"},
            new String[]{"ATLASSIAN",            "ATLASSIAN",            "Developer Tools"},
            new String[]{"SLACK",                "SLACK",                "Productivity"},
            new String[]{"ZOOM",                 "ZOOM",                 "Productivity"},
            new String[]{"CANVA",                "CANVA",                "Creative & Software"},
            new String[]{"FIGMA",                "FIGMA",                "Design"},
            // Cloud & Infra
            new String[]{"AWS",                  "AMAZON WEB SERVICES",  "Cloud Infrastructure"},
            new String[]{"AMAZON WEB SERVICES",  "AMAZON WEB SERVICES",  "Cloud Infrastructure"},
            new String[]{"GOOGLE CLOUD",         "GOOGLE CLOUD",         "Cloud Infrastructure"},
            new String[]{"AZURE",                "MICROSOFT AZURE",      "Cloud Infrastructure"},
            new String[]{"DIGITALOCEAN",         "DIGITALOCEAN",         "Cloud Infrastructure"},
            new String[]{"RENDER",               "RENDER",               "Cloud Infrastructure"},
            new String[]{"VERCEL",               "VERCEL",               "Cloud Infrastructure"},
            // Travel
            new String[]{"MAKE MY TRIP",         "MAKEMYTRIP",           "Travel"},
            new String[]{"MAKEMYTRIP",           "MAKEMYTRIP",           "Travel"},
            new String[]{"GOIBIBO",              "GOIBIBO",              "Travel"},
            new String[]{"IXIGO",                "IXIGO",                "Travel"},
            new String[]{"IRCTC",                "IRCTC",                "Travel"},
            new String[]{"OYO",                  "OYO ROOMS",            "Travel"},
            new String[]{"UBER",                 "UBER",                 "Transportation"},
            new String[]{"OLA",                  "OLA CABS",             "Transportation"},
            new String[]{"RAPIDO",               "RAPIDO",               "Transportation"},
            // Finance & Insurance
            new String[]{"ZERODHA",              "ZERODHA",              "Investments"},
            new String[]{"GROWW",                "GROWW",                "Investments"},
            new String[]{"UPSTOX",               "UPSTOX",               "Investments"},
            new String[]{"COIN",                 "ZERODHA COIN",         "Investments"},
            new String[]{"POLICYBAZAAR",         "POLICYBAZAAR",         "Insurance"},
            new String[]{"ACKO",                 "ACKO",                 "Insurance"},
            new String[]{"DIGIT INSURANCE",      "DIGIT",                "Insurance"},
            // Telecom
            new String[]{"JIO",                  "RELIANCE JIO",         "Telecom"},
            new String[]{"AIRTEL",               "AIRTEL",               "Telecom"},
            new String[]{"BSNL",                 "BSNL",                 "Telecom"},
            new String[]{"VI TELECOM",           "VODAFONE IDEA",        "Telecom"},
            new String[]{"VODAFONE IDEA",        "VODAFONE IDEA",        "Telecom"},
            // Health & Fitness
            new String[]{"CULT FIT",             "CULT.FIT",             "Health & Fitness"},
            new String[]{"CULTFIT",              "CULT.FIT",             "Health & Fitness"},
            new String[]{"PRACTO",               "PRACTO",               "Health"},
            new String[]{"PHARMEASY",            "PHARMEASY",            "Health"},
            new String[]{"NETMEDS",              "NETMEDS",              "Health"},
            // Audio
            new String[]{"AUDIBLE",              "AUDIBLE",              "Books & Audio"},
            new String[]{"HEADSPACE",            "HEADSPACE",            "Mental Wellness"},
            new String[]{"CALM",                 "CALM",                 "Mental Wellness"},
            // Gaming
            new String[]{"STEAM",                "STEAM",                "Gaming"},
            new String[]{"PLAYSTATION",          "PLAYSTATION",          "Gaming"},
            new String[]{"XBOX",                 "XBOX",                 "Gaming"}
    );

    @PostConstruct
    public void seed() {
        seedApprovedMerchants();
        seedApprovedPatterns();
        seedHeuristicDictionary();
    }

    private void seedApprovedPatterns() {
        // Remove unapproved/auto-learned patterns so only system-approved entries exist in DB
        patternRepository.findAll().stream()
                .filter(p -> p.getVerificationSource() == null
                        || p.getVerificationSource().startsWith("RESOLVER_AUTO")
                        || p.getVerificationSource().startsWith("GEMINI_USER")
                        || p.getVerificationSource().startsWith("SARVAM_USER"))
                .forEach(patternRepository::delete);

        // Seed approved patterns if not already present
        int added = 0;
        for (String[] entry : APPROVED_MERCHANTS) {
            String descriptor = entry[0];
            String merchantName = entry[1];
            String category = entry[2];
            String regex = "(?i).*" + descriptor.replace(" ", ".*") + ".*";
            boolean exists = patternRepository.findAll().stream()
                    .anyMatch(p -> "SYSTEM_SEEDED".equals(p.getVerificationSource()) && merchantName.equals(p.getBrandName()));
            if (!exists) {
                com.mandateshield.domain.MerchantPatternDocument pat = com.mandateshield.domain.MerchantPatternDocument.builder()
                        .patternRegex(regex)
                        .brandName(merchantName)
                        .category(category)
                        .confidenceScore(0.98)
                        .verificationSource("SYSTEM_SEEDED")
                        .createdAt(Instant.now())
                        .build();
                patternRepository.save(pat);
                added++;
            }
        }
        log.info("System-approved merchant patterns in DB: {} newly seeded, total {}", added, patternRepository.count());
    }

    private void seedApprovedMerchants() {
        int added = 0;
        int skipped = 0;
        for (String[] entry : APPROVED_MERCHANTS) {
            String descriptor = entry[0];
            String merchantName = entry[1];
            String category = entry[2];
            String fingerprint = fingerprint(descriptor);
            if (fingerprint == null) {
                log.warn("Skipping merchant seed — could not fingerprint '{}'", descriptor);
                skipped++;
                continue;
            }
            if (aliasRepository.findByDescriptorFingerprint(fingerprint).isPresent()) {
                skipped++;
                continue;
            }
            MerchantAliasDocument alias = MerchantAliasDocument.builder()
                    .descriptorFingerprint(fingerprint)
                    .merchantName(merchantName)
                    .category(category)
                    .verificationSource("SYSTEM_SEEDED")
                    .confirmedAt(Instant.now())
                    .build();
            aliasRepository.save(alias);
            added++;
        }
        log.info("Merchant alias seeding complete: {} added, {} already present", added, skipped);
    }

    private void seedHeuristicDictionary() {
        if (heuristicDictionaryRepository.count() > 0) {
            log.info("Heuristic dictionary already seeded — skipping.");
            return;
        }
        HeuristicDictionaryDocument dict = HeuristicDictionaryDocument.builder()
                .gateways(Set.of(
                        "RAZORPAY", "PAYU", "BILLDESK", "CASHFREE", "INSTAMOJO",
                        "STRIPE", "PAYPAL", "CCAVENUE", "EBS", "HDFC",
                        "ICICI", "SBI", "KOTAK", "AXIS", "YESBANK",
                        "RZP", "ACH", "NACH", "NPCI", "BHIM"
                ))
                .stopwords(Set.of(
                        "ACH", "INB", "NACH", "UPI", "POS", "ECOM", "BILL", "AUTOPAY", "SUB", "SUBSCR",
                        "MANDATE", "DESCRIPTOR", "TXN", "PAYMENT", "TRANSFER", "PURCHASE", "DEBIT", "CREDIT",
                        "CARD", "BANK", "REF", "NO", "LIMITED", "LTD", "PVT", "CORP", "INC", "UNKNOWN",
                        "MNDT", "SI", "ECS", "RECURRING", "AUTO", "RENEWAL", "SUBSCRIPTION", "ORDER"
                ))
                .aliasMap(Map.ofEntries(
                        Map.entry("NFLX",    "NETFLIX"),
                        Map.entry("NETFLIX", "NETFLIX"),
                        Map.entry("AMZN",    "AMAZON"),
                        Map.entry("AMAZON",  "AMAZON"),
                        Map.entry("SPOTITY", "SPOTIFY"),
                        Map.entry("SPOTIFY", "SPOTIFY"),
                        Map.entry("DISNEY",  "DISNEY+ HOTSTAR"),
                        Map.entry("HOTSTAR", "DISNEY+ HOTSTAR"),
                        Map.entry("HULU",    "HULU"),
                        Map.entry("AWS",     "AMAZON WEB SERVICES"),
                        Map.entry("APPLE",   "APPLE"),
                        Map.entry("ITUNES",  "APPLE"),
                        Map.entry("GOOGLE",  "GOOGLE"),
                        Map.entry("YOUTUBE", "YOUTUBE PREMIUM"),
                        Map.entry("SWIGGY",  "SWIGGY"),
                        Map.entry("SWIGGYONE", "SWIGGY ONE"),
                        Map.entry("ZOMATO",  "ZOMATO"),
                        Map.entry("ADOBE",   "ADOBE"),
                        Map.entry("NOTION",  "NOTION"),
                        Map.entry("LINKEDIN","LINKEDIN"),
                        Map.entry("DROPBOX", "DROPBOX"),
                        Map.entry("AUDIBLE", "AUDIBLE"),
                        Map.entry("FLIPKART","FLIPKART"),
                        Map.entry("UBER",    "UBER"),
                        Map.entry("OLA",     "OLA CABS"),
                        Map.entry("JIOCINEMA","JIO CINEMA"),
                        Map.entry("SONYLIV", "SONY LIV"),
                        Map.entry("BLINKIT", "BLINKIT"),
                        Map.entry("ZEPTO",   "ZEPTO"),
                        Map.entry("GROWW",   "GROWW")
                ))
                .brandCategoryMap(Map.ofEntries(
                        Map.entry("NETFLIX",              "Entertainment"),
                        Map.entry("SPOTIFY",              "Audio & Music"),
                        Map.entry("AMAZON",               "E-Commerce"),
                        Map.entry("AMAZON WEB SERVICES",  "Cloud Infrastructure"),
                        Map.entry("DISNEY+ HOTSTAR",      "Entertainment"),
                        Map.entry("YOUTUBE PREMIUM",      "Streaming"),
                        Map.entry("APPLE",                "Software & Services"),
                        Map.entry("GOOGLE",               "Digital Services"),
                        Map.entry("SWIGGY",               "Food & Dining"),
                        Map.entry("SWIGGY ONE",           "Food & Dining"),
                        Map.entry("ZOMATO",               "Food & Dining"),
                        Map.entry("ADOBE",                "Creative & Software"),
                        Map.entry("NOTION",               "Productivity"),
                        Map.entry("LINKEDIN",             "Professional Network"),
                        Map.entry("DROPBOX",              "Cloud Storage"),
                        Map.entry("AUDIBLE",              "Books & Audio"),
                        Map.entry("FLIPKART",             "E-Commerce"),
                        Map.entry("UBER",                 "Transportation"),
                        Map.entry("OLA CABS",             "Transportation"),
                        Map.entry("JIO CINEMA",           "Entertainment"),
                        Map.entry("SONY LIV",             "Entertainment"),
                        Map.entry("BLINKIT",              "Grocery"),
                        Map.entry("ZEPTO",                "Grocery"),
                        Map.entry("GROWW",                "Investments"),
                        Map.entry("HULU",                 "Entertainment")
                ))
                .build();
        heuristicDictionaryRepository.save(dict);
        log.info("Heuristic dictionary seeded with {} gateways, {} stopwords, {} aliases, {} categories.",
                dict.getGateways().size(), dict.getStopwords().size(),
                dict.getAliasMap().size(), dict.getBrandCategoryMap().size());
    }

    /**
     * Fingerprints a descriptor the same way SharedMerchantKnowledgeService does,
     * so the seeded aliases are found correctly at runtime.
     */
    private String fingerprint(String descriptor) {
        if (descriptor == null || descriptor.isBlank()) return null;
        String normalized = descriptor.toUpperCase(Locale.ROOT)
                .replaceAll("\\b\\d{2,}\\b", " ")
                .replaceAll("[^A-Z]+", " ").trim().replaceAll("\\s+", " ");
        if (normalized.length() < 3) return null;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
