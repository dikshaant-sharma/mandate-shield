file = r'c:\Users\sdiks\OneDrive\Desktop\MandateShield\src\main\java\com\mandateshield\engine\MerchantResolverEngine.java'
with open(file, 'r', encoding='utf-8') as f:
    content = f.read()

# Fix 1: resolveByRecurringPattern — upgrade filters
old_filter = """                    .filter(t -> t.length() >= 2)
                    .filter(t -> !heuristicEngine.getStopwords().contains(t))
                    .filter(t -> !heuristicEngine.getGateways().contains(t))"""

new_filter = """                    .filter(t -> t.length() >= 3)                        // drop short noise tokens
                    .filter(t -> !t.matches("\\\\d+"))                              // drop pure numbers
                    .filter(t -> !t.matches("[A-Z]*\\\\d+[A-Z0-9]*"))              // drop txn IDs like XYZ99182
                    .filter(t -> !t.matches("[A-Z]{1,2}"))                         // drop 2-char state/country codes
                    .filter(t -> !heuristicEngine.getStopwords().contains(t))
                    .filter(t -> !heuristicEngine.getGateways().contains(t))"""

if old_filter in content:
    content = content.replace(old_filter, new_filter)
    print('Fix 1 applied: resolveByRecurringPattern filters upgraded')
else:
    print('Fix 1 NOT FOUND — dumping nearby context:')
    idx = content.find('t.length() >= 2')
    print(repr(content[max(0,idx-100):idx+200]))

# Fix 2: restore Tier 2.5 name-override pass in resolve()
old_sarvam = """        // Always fire Sarvam async for unconfirmed descriptors (placeholder or UNKNOWN)
        sarvamEnrichment.scheduleIfSuspicious(userId, rawDescriptor, displayConf, displayName);
        return new ResolveResult(displayName, displayCat, heur.gateway(), displayConf, heur.resolutionTier(), null);"""

new_sarvam = """        // Tier 2.5 — Recurring unnamed: if same descriptor recurred before, extract cleaner name
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

if old_sarvam in content:
    content = content.replace(old_sarvam, new_sarvam)
    print('Fix 2 applied: Tier 2.5 name-override pass restored in resolve()')
else:
    print('Fix 2 NOT FOUND')

with open(file, 'w', encoding='utf-8', newline='\n') as f:
    f.write(content)
print('Done')
