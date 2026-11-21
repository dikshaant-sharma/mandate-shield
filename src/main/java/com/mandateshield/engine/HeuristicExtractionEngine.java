package com.mandateshield.engine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.mandateshield.domain.HeuristicDictionaryDocument;
import com.mandateshield.repository.HeuristicDictionaryRepository;
import jakarta.annotation.PostConstruct;
import lombok.Getter;

/**
 * Implements the four-stage heuristic extraction pipeline (Tier 2).
 *
 * 1. Tokenise & standardise the raw descriptor (merging spaced/separated single characters).
 * 2. Detect and strip known payment-rail prefixes.
 * 3. Remove stop-words and numeric noise.
 * 4. Resolve the brand using exact alias, substring, Levenshtein fuzzy distance, or longest token.
 */
@Component
@Slf4j
@Getter
public class HeuristicExtractionEngine {

    private static final Map<String, String> COMMON_ALIASES = Map.ofEntries(
            Map.entry("NFLX", "NETFLIX"), Map.entry("NETFLIX", "NETFLIX"),
            Map.entry("AMZN", "AMAZON"), Map.entry("AMAZON", "AMAZON"),
            Map.entry("SPOTIFY", "SPOTIFY"), Map.entry("SPOTITY", "SPOTIFY"),
            Map.entry("DISNEY", "DISNEY+"), Map.entry("HOTSTAR", "DISNEY+"),
            Map.entry("HULU", "HULU"), Map.entry("AWS", "AMAZON WEB SERVICES"),
            Map.entry("APPLE", "APPLE"), Map.entry("ITUNES", "APPLE"),
            Map.entry("GOOGLE", "GOOGLE"), Map.entry("YOUTUBE", "YOUTUBE PREMIUM"),
            Map.entry("SWIGGY", "SWIGGY"), Map.entry("SWIGGYONE", "SWIGGY"),
            Map.entry("ZOMATO", "ZOMATO"), Map.entry("ADOBE", "ADOBE"),
            Map.entry("NOTION", "NOTION"), Map.entry("LINKEDIN", "LINKEDIN"),
            Map.entry("DROPBOX", "DROPBOX"), Map.entry("AUDIBLE", "AUDIBLE"),
            Map.entry("LENSKART", "LENSKART"), Map.entry("FLIPKART", "FLIPKART"),
            Map.entry("MYNTRA", "MYNTRA"), Map.entry("MEESHO", "MEESHO"),
            Map.entry("AJIO", "AJIO"), Map.entry("NYKAA", "NYKAA"),
            Map.entry("BLINKIT", "BLINKIT"), Map.entry("BLNKT", "BLINKIT"),
            Map.entry("ZEPTO", "ZEPTO"), Map.entry("BIGBASKET", "BIGBASKET"),
            Map.entry("DUNZO", "DUNZO"), Map.entry("UBER", "UBER"),
            Map.entry("OLA", "OLA CABS"), Map.entry("RAPIDO", "RAPIDO"),
            Map.entry("JIOCINEMA", "JIO CINEMA"), Map.entry("SONYLIV", "SONY LIV"),
            Map.entry("ZEE5", "ZEE5"), Map.entry("MXPLAYER", "MX PLAYER"),
            Map.entry("ZERODHA", "ZERODHA"), Map.entry("GROWW", "GROWW"),
            Map.entry("UPSTOX", "UPSTOX"), Map.entry("POLICYBAZAAR", "POLICYBAZAAR"),
            Map.entry("ACKO", "ACKO"), Map.entry("CULTFIT", "CULT.FIT"),
            Map.entry("PRACTO", "PRACTO"), Map.entry("PHARMEASY", "PHARMEASY"),
            Map.entry("NETMEDS", "NETMEDS"), Map.entry("HEADSPACE", "HEADSPACE"),
            Map.entry("CALM", "CALM"), Map.entry("STEAM", "STEAM"),
            Map.entry("PLAYSTATION", "PLAYSTATION"), Map.entry("XBOX", "XBOX"),
            Map.entry("MICROSOFT", "MICROSOFT 365"), Map.entry("GITHUB", "GITHUB"),
            Map.entry("SLACK", "SLACK"), Map.entry("ZOOM", "ZOOM"),
            Map.entry("CANVA", "CANVA"), Map.entry("FIGMA", "FIGMA")
    );
    private static final Map<String, String> DEFAULT_CATEGORIES = Map.ofEntries(
            Map.entry("SPOTIFY", "Audio & Music"),
            Map.entry("NETFLIX", "Entertainment"),
            Map.entry("AMAZON", "E-Commerce"),
            Map.entry("AMAZON WEB SERVICES", "Cloud Infrastructure"),
            Map.entry("DISNEY+", "Entertainment"),
            Map.entry("HULU", "Entertainment"),
            Map.entry("AWS", "Cloud Infrastructure"),
            Map.entry("APPLE", "Software & Services"),
            Map.entry("GOOGLE", "Digital Services"),
            Map.entry("YOUTUBE PREMIUM", "Streaming"),
            Map.entry("SWIGGY", "Food & Dining"),
            Map.entry("SWIGGY ONE", "Food & Dining"),
            Map.entry("ZOMATO", "Food & Dining"),
            Map.entry("ADOBE", "Creative & Software"),
            Map.entry("NOTION", "Productivity"),
            Map.entry("LINKEDIN", "Professional Network"),
            Map.entry("DROPBOX", "Cloud Storage"),
            Map.entry("AUDIBLE", "Books & Audio"),
            Map.entry("LENSKART", "Eyewear & Healthcare"),
            Map.entry("FLIPKART", "E-Commerce"),
            Map.entry("MYNTRA", "Fashion & Apparel"),
            Map.entry("MEESHO", "E-Commerce"),
            Map.entry("AJIO", "Fashion & Apparel"),
            Map.entry("NYKAA", "Beauty & Personal Care"),
            Map.entry("BLINKIT", "Grocery"),
            Map.entry("ZEPTO", "Grocery"),
            Map.entry("BIGBASKET", "Grocery"),
            Map.entry("DUNZO", "Delivery"),
            Map.entry("UBER", "Transportation"),
            Map.entry("OLA CABS", "Transportation"),
            Map.entry("RAPIDO", "Transportation"),
            Map.entry("JIO CINEMA", "Entertainment"),
            Map.entry("SONY LIV", "Entertainment"),
            Map.entry("ZEE5", "Entertainment"),
            Map.entry("MX PLAYER", "Entertainment"),
            Map.entry("ZERODHA", "Investments"),
            Map.entry("GROWW", "Investments"),
            Map.entry("UPSTOX", "Investments"),
            Map.entry("POLICYBAZAAR", "Insurance"),
            Map.entry("ACKO", "Insurance"),
            Map.entry("CULT.FIT", "Health & Fitness"),
            Map.entry("PRACTO", "Health"),
            Map.entry("PHARMEASY", "Health"),
            Map.entry("NETMEDS", "Health"),
            Map.entry("HEADSPACE", "Mental Wellness"),
            Map.entry("CALM", "Mental Wellness"),
            Map.entry("STEAM", "Gaming"),
            Map.entry("PLAYSTATION", "Gaming"),
            Map.entry("XBOX", "Gaming"),
            Map.entry("MICROSOFT 365", "Productivity"),
            Map.entry("GITHUB", "Developer Tools"),
            Map.entry("SLACK", "Productivity"),
            Map.entry("ZOOM", "Productivity"),
            Map.entry("CANVA", "Creative & Software"),
            Map.entry("FIGMA", "Design")
    );
    private static final Set<String> DEFAULT_GATEWAYS = Set.of(
            "RAZORPAY", "RZP", "RAZP", "PAYU", "BILLDESK", "CASHFREE", "INSTAMOJO",
            "STRIPE", "PAYPAL", "CCAVENUE", "EBS", "HDFC", "ICICI", "SBI", "KOTAK",
            "AXIS", "YESBANK", "ACH", "NACH", "NPCI", "BHIM", "AIRPAY", "PAYTM", "PHONEPE"
    );
    private static final Set<String> DEFAULT_STOPWORDS = Set.of(
            "ACH", "INB", "NACH", "UPI", "POS", "ECOM", "BILL", "AUTOPAY", "SUB", "SUBSCR",
            "MANDATE", "DESCRIPTOR", "TXN", "PAYMENT", "TRANSFER", "PURCHASE", "DEBIT", "CREDIT",
            "CARD", "BANK", "REF", "NO", "LIMITED", "LTD", "PVT", "CORP", "INC", "UNKNOWN",
            "MKTP", "MARKETPLACE", "DIRECT", "RETAIL", "ONLINE", "PORTAL", "WEB", "SERVICES",
            "US", "USA", "IN", "IND", "UK", "SEATTLE", "WA", "MUMBAI", "MUM", "BLR", "BANGALORE",
            "BENGALURU", "DELHI", "DEL", "HYD", "HYDERABAD", "PUNE", "KOLKATA", "CC", "DC",
            "MNDT", "SI", "ECS", "RECURRING", "AUTO", "RENEWAL", "SUBSCRIPTION", "ORDER", "GOLD"
    );

    private final HeuristicDictionaryRepository dictionaryRepository;

    private Set<String> gateways;
    private Set<String> stopwords;
    private Map<String, String> aliasMap;
    private Map<String, String> brandCategoryMap;

    public HeuristicExtractionEngine(HeuristicDictionaryRepository dictionaryRepository) {
        this.dictionaryRepository = dictionaryRepository;
        this.gateways = new HashSet<>();
        this.stopwords = new HashSet<>();
        this.aliasMap = new HashMap<>();
        this.brandCategoryMap = new HashMap<>();
    }

    @PostConstruct
    public void init() {
        refreshDictionary();
    }

    public void refreshDictionary() {
        if (dictionaryRepository != null) {
            dictionaryRepository.findAll().stream().findFirst().ifPresent(dict -> {
                this.gateways = dict.getGateways() != null ? dict.getGateways() : new HashSet<>();
                this.stopwords = dict.getStopwords() != null ? dict.getStopwords() : new HashSet<>();
                this.aliasMap = dict.getAliasMap() != null ? dict.getAliasMap() : new HashMap<>();
                this.brandCategoryMap = dict.getBrandCategoryMap() != null ? dict.getBrandCategoryMap() : new HashMap<>();
                log.info("Loaded heuristic dictionary from MongoDB. Gateways: {}, Stopwords: {}", gateways.size(), stopwords.size());
            });
        }
        COMMON_ALIASES.forEach(aliasMap::putIfAbsent);
        DEFAULT_CATEGORIES.forEach(brandCategoryMap::putIfAbsent);
        gateways.addAll(DEFAULT_GATEWAYS);
        stopwords.addAll(DEFAULT_STOPWORDS);
    }

    /**
     * Runs the full heuristic pipeline and returns a {@link HeuristicResolutionResult}.
     *
     * Pipeline:
     *  Pass A — Alias / fuzzy dictionary match → 83–90% confidence (known merchant)
     *  Pass B — Intelligent token distillation → ~50% confidence (unrecognised)
     *           Strips gateways, stopwords, numbers, country/city noise, short tokens (< 3 chars)
     *           and alphanumeric IDs (all-digit or mixed digit-letter transaction codes).
     *           Remaining tokens form a readable placeholder name.
     *           Always triggers async Sarvam AI to resolve properly.
     */
    public HeuristicResolutionResult resolve(String rawDescriptor) {
        if (rawDescriptor == null || rawDescriptor.isBlank()) {
            return new HeuristicResolutionResult("UNKNOWN", "Uncategorized", null, 0.0,
                    com.mandateshield.domain.ResolutionTier.TIER_2);
        }
        log.debug("Heuristic extraction start for raw: {}", rawDescriptor);

        // 1. Split on ALL delimiters: space, underscore, dash, dot, star, slash, hash, @, etc.
        //    Then upper-case and merge runs of single letters that may spell a brand (e.g. S P O T I F Y)
        List<String> tokens = splitAndNormalise(rawDescriptor);
        log.trace("Tokens after split: {}", tokens);

        // 2. Identify the first gateway token for metadata (keep but don't use for brand)
        String gateway = detectGateway(tokens);
        log.trace("Detected gateway: {}", gateway);

        // 3. Remove gateways, stopwords, pure numbers, country/city codes, short tokens, and
        //    transaction-ID-style tokens (alphanumeric codes with embedded digits like 2X49L091)
        List<String> meaningful = tokens.stream()
                .filter(t -> !gateways.contains(t))
                .filter(t -> !stopwords.contains(t))
                .filter(t -> t.length() >= 3)                        // drop very short tokens
                .filter(t -> !t.matches("\\d+"))                     // drop pure numbers
                .filter(t -> !t.matches("[A-Z]*\\d+[A-Z0-9]*"))      // drop txn IDs like 2X49L091, 9812
                .filter(t -> !t.matches("[A-Z]{1,2}"))               // drop country/state codes: WA, US
                .distinct()
                .collect(Collectors.toCollection(ArrayList::new));
        log.trace("Meaningful tokens after filtering: {}", meaningful);

        // 4. PASS A: Try alias / fuzzy match on meaningful tokens → high confidence known brand
        BrandMatch aliasMatch = extractBrand(meaningful);
        if (!"UNKNOWN".equalsIgnoreCase(aliasMatch.brand())) {
            String category = brandCategoryMap.getOrDefault(aliasMatch.brand(), "Uncategorized");
            log.info("Heuristic resolved -> brand='{}', category='{}', gateway='{}', confidence={}",
                    aliasMatch.brand(), category, gateway, aliasMatch.confidence());
            return new HeuristicResolutionResult(aliasMatch.brand(), category, gateway,
                    aliasMatch.confidence(), com.mandateshield.domain.ResolutionTier.TIER_2);
        }

        // 5. PASS B: No known brand matched.
        //    Score the remaining tokens by merchantability — prefer longer, alphabetic-only tokens
        //    that look like brand names (not abbreviations or noise).
        //    Build a clean placeholder name from the top candidate tokens.
        if (meaningful.isEmpty()) {
            log.info("Heuristic resolved -> brand='UNKNOWN', no meaningful tokens, gateway='{}'", gateway);
            return new HeuristicResolutionResult("UNKNOWN", "Uncategorized", gateway, 0.0,
                    com.mandateshield.domain.ResolutionTier.TIER_2);
        }

        // Score tokens: longer pure-alpha tokens score higher (more likely brand words)
        List<String> scored = meaningful.stream()
                .sorted(Comparator
                        .comparingInt((String t) -> t.matches("[A-Z]+") ? 1 : 0).reversed() // pure alpha first
                        .thenComparingInt(String::length).reversed())                        // longer first
                .limit(3)
                .toList();

        String placeholder = String.join(" ", scored);
        // Assign ~50% confidence — honest signal to the user that this is unidentified
        // and async Sarvam enrichment is in progress
        double placeholderConf = 0.50;
        log.info("Heuristic resolved -> brand='{}' (placeholder, 50%), gateway='{}', meaningful tokens: {}",
                placeholder, gateway, meaningful);

        return new HeuristicResolutionResult(placeholder, "Identifying...", gateway,
                placeholderConf, com.mandateshield.domain.ResolutionTier.TIER_2);
    }

    /**
     * Splits on all common delimiter characters, upper-cases, and merges consecutive
     * single-letter tokens that may represent a spaced brand name (e.g. "S P O T I F Y" → "SPOTIFY").
     */
    private List<String> splitAndNormalise(String raw) {
        // Replace all delimiter characters with space
        String expanded = raw.toUpperCase(Locale.ROOT)
                .replaceAll("[*_\\-./#@|:;,(){}\\[\\]!&+=]+", " ")
                .replaceAll("\\s+", " ")
                .trim();

        List<String> rawTokens = Arrays.asList(expanded.split(" "));
        List<String> merged = new ArrayList<>();
        StringBuilder singleCharSeq = new StringBuilder();

        for (String token : rawTokens) {
            if (token.length() == 1 && Character.isLetter(token.charAt(0))) {
                // Accumulate single letters — may be a spaced-out brand name
                singleCharSeq.append(token);
            } else {
                if (singleCharSeq.length() > 0) {
                    merged.add(singleCharSeq.toString());
                    singleCharSeq.setLength(0);
                }
                if (!token.isBlank()) merged.add(token);
            }
        }
        if (singleCharSeq.length() > 0) merged.add(singleCharSeq.toString());
        return merged;
    }

    /** Return the first gateway token that appears in the list, or null. */
    private String detectGateway(List<String> tokens) {
        for (String token : tokens) {
            if (gateways.contains(token)) {
                return token;
            }
        }
        return null;
    }

    /** Alias lookup -> fuzzy distance matching -> longest-token fallback. */
    private BrandMatch extractBrand(List<String> tokens) {
        // Pass 1: exact alias match
        for (String token : tokens) {
            String alias = aliasMap.get(token);
            if (alias != null) return new BrandMatch(alias, 0.90);
        }
        // Pass 2: partial substring match (token contains alias key or vice versa)
        // Sort keys by length descending to match specific brands before short acronyms
        List<Map.Entry<String, String>> sortedAliases = aliasMap.entrySet().stream()
                .sorted((e1, e2) -> Integer.compare(e2.getKey().length(), e1.getKey().length()))
                .toList();
        for (String token : tokens) {
            for (Map.Entry<String, String> entry : sortedAliases) {
                String key = entry.getKey();
                if (key.length() >= 3 && token.contains(key)) {
                    return new BrandMatch(entry.getValue(), 0.88);
                } else if (key.length() >= 4 && token.length() >= 4 && key.contains(token)) {
                    return new BrandMatch(entry.getValue(), 0.82);
                }
            }
        }
        // Pass 3: fuzzy matching via Levenshtein distance (e.g., SPOTITY -> SPOTIFY)
        for (String token : tokens) {
            if (token.length() >= 4) {
                for (Map.Entry<String, String> entry : sortedAliases) {
                    String key = entry.getKey();
                    if (Math.abs(token.length() - key.length()) <= 1) {
                        int dist = levenshteinDistance(token, key);
                        if (dist <= (key.length() >= 6 ? 2 : 1)) {
                            return new BrandMatch(entry.getValue(), 0.83);
                        }
                    }
                }
            }
        }
        // Pass 4: No known merchant match in aliases, substrings, or fuzzy distance.
        // Return UNKNOWN with 0.0 confidence so that downstream layers (e.g. recurring unnamed check)
        // can handle descriptors without an identified merchant, rather than fabricating a name.
        return new BrandMatch("UNKNOWN", 0.0);
    }

    private int levenshteinDistance(String s1, String s2) {
        int[] costs = new int[s2.length() + 1];
        for (int j = 0; j <= s2.length(); j++) costs[j] = j;
        for (int i = 1; i <= s1.length(); i++) {
            costs[0] = i;
            int nw = i - 1;
            for (int j = 1; j <= s2.length(); j++) {
                int cj = Math.min(1 + Math.min(costs[j], costs[j - 1]),
                        s1.charAt(i - 1) == s2.charAt(j - 1) ? nw : nw + 1);
                nw = costs[j];
                costs[j] = cj;
            }
        }
        return costs[s2.length()];
    }

    private record BrandMatch(String brand, double confidence) {}

    /** Record that carries the heuristic result. */
    public record HeuristicResolutionResult(
            String brandName,
            String category,
            String gateway,
            double confidenceScore,
            com.mandateshield.domain.ResolutionTier resolutionTier) {}
}
