file = r'c:\Users\sdiks\OneDrive\Desktop\MandateShield\src\main\java\com\mandateshield\engine\MerchantResolverEngine.java'
with open(file, 'r', encoding='utf-8') as f:
    content = f.read()

# Replace the name-override Tier 2.5 block with a full return when recurring match found
old = """        // Tier 2.5 — Recurring unnamed: if same descriptor recurred before, extract cleaner name
        // Overrides the placeholder name only — no duplicate save, Sarvam fires once below
        RecurringExtraction recurringMatch = resolveByRecurringPattern(userId, rawDescriptor);
        if (recurringMatch != null && !"UNKNOWN".equalsIgnoreCase(recurringMatch.merchantName())) {
            displayName = recurringMatch.merchantName();
            displayConf = recurringMatch.confidence();
            displayCat  = "Recurring \u2014 Unidentified";
            log.info("Tier 2.5 upgraded placeholder for '{}' -> name='{}', conf={}",
                    rawDescriptor, displayName, displayConf);
        }

        // Always fire Sarvam async for unconfirmed descriptors (placeholder or UNKNOWN)
        sarvamEnrichment.scheduleIfSuspicious(userId, rawDescriptor, displayConf, displayName);
        return new ResolveResult(displayName, displayCat, heur.gateway(), displayConf, heur.resolutionTier(), null);"""

new = """        // Tier 2.5 — Recurring unnamed: if same descriptor recurred before, extract cleaner name
        // Returns immediately with RECURRING_PATTERN_EXTRACT matchedPattern for grouping/tracking
        RecurringExtraction recurringMatch = resolveByRecurringPattern(userId, rawDescriptor);
        if (recurringMatch != null && !"UNKNOWN".equalsIgnoreCase(recurringMatch.merchantName())) {
            String recurringCat = "Recurring \u2014 Unidentified";
            log.info("Tier 2.5 upgraded placeholder for '{}' -> name='{}', conf={}",
                    rawDescriptor, recurringMatch.merchantName(), recurringMatch.confidence());
            // Update the already-saved transaction with the better Tier 2.5 name
            saveRawTransaction(userId, rawDescriptor, amount, currency, transactionDate,
                    recurringMatch.merchantName(), recurringCat, recurringMatch.confidence(), ResolutionTier.TIER_2);
            sarvamEnrichment.scheduleIfSuspicious(userId, rawDescriptor, recurringMatch.confidence(), recurringMatch.merchantName());
            return new ResolveResult(recurringMatch.merchantName(), recurringCat,
                    heur.gateway(), recurringMatch.confidence(), ResolutionTier.TIER_2, "RECURRING_PATTERN_EXTRACT");
        }

        // Always fire Sarvam async for unconfirmed descriptors (placeholder or UNKNOWN)
        sarvamEnrichment.scheduleIfSuspicious(userId, rawDescriptor, displayConf, displayName);
        return new ResolveResult(displayName, displayCat, heur.gateway(), displayConf, heur.resolutionTier(), null);"""

if old in content:
    content = content.replace(old, new)
    print('Fix applied: Tier 2.5 now returns RECURRING_PATTERN_EXTRACT matchedPattern')
else:
    print('NOT FOUND — checking current state:')
    idx = content.find('Tier 2.5')
    print(repr(content[max(0,idx-50):idx+500]))

with open(file, 'w', encoding='utf-8', newline='\n') as f:
    f.write(content)
print('Done')
