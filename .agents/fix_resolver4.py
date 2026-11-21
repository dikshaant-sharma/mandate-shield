file = r'c:\Users\sdiks\OneDrive\Desktop\MandateShield\src\main\java\com\mandateshield\engine\MerchantResolverEngine.java'
with open(file, 'r', encoding='utf-8') as f:
    content = f.read()

# Replace the entire uniqueTokens extraction block in resolveByRecurringPattern
old = """            // Extract the common unique part: strip stopwords, gateways, numbers, and separators
            List<String> uniqueTokens = Arrays.stream(rawDescriptor.toUpperCase(Locale.ROOT)
                            .replaceAll("[*_\\\\-./#@|:;,(){}\\\\[\\\\]\\\\d]+", " ")
                            .trim()
                            .split("\\\\s+"))
                    .filter(t -> t.length() >= 3)                        // drop short noise tokens
                    .filter(t -> !t.matches("\\\\d+"))                              // drop pure numbers
                    .filter(t -> !t.matches("[A-Z]*\\\\d+[A-Z0-9]*"))              // drop txn IDs like XYZ99182
                    .filter(t -> !t.matches("[A-Z]{1,2}"))                         // drop 2-char state/country codes
                    .filter(t -> !heuristicEngine.getStopwords().contains(t))
                    .filter(t -> !heuristicEngine.getGateways().contains(t))
                    .distinct()
                    .toList();

            String commonPart;
            if (uniqueTokens.isEmpty()) {
                // No meaningful tokens after all filters — return null so caller keeps heuristic placeholder
                return null;
            } else {
                commonPart = String.join(" ", uniqueTokens);
            }"""

new = """            // Extract the common unique part: split on delimiters, extract alpha prefixes from mixed tokens,
            // drop pure noise (numbers, stopwords, gateways, 1-2 char codes)
            java.util.regex.Pattern alphaPrefix = java.util.regex.Pattern.compile("^([A-Z]{3,})");
            List<String> uniqueTokens = Arrays.stream(rawDescriptor.toUpperCase(Locale.ROOT)
                            .replaceAll("[*_\\\\-./#@|:;,(){}\\\\[\\\\]]+", " ")
                            .trim()
                            .split("\\\\s+"))
                    .flatMap(t -> {
                        // If token is pure alpha >=3: keep as-is
                        if (t.matches("[A-Z]{3,}")) return java.util.stream.Stream.of(t);
                        // If token starts with 3+ alpha chars before digits (e.g. XYZ99182): extract alpha prefix
                        java.util.regex.Matcher m = alphaPrefix.matcher(t);
                        if (m.find()) return java.util.stream.Stream.of(m.group(1));
                        // Otherwise drop (pure numbers, starts-with-digit IDs like 2X49L091, short codes)
                        return java.util.stream.Stream.empty();
                    })
                    .filter(t -> !t.matches("[A-Z]{1,2}"))                         // drop 1-2 char state/country codes
                    .filter(t -> !heuristicEngine.getStopwords().contains(t))
                    .filter(t -> !heuristicEngine.getGateways().contains(t))
                    .distinct()
                    .toList();

            String commonPart;
            if (uniqueTokens.isEmpty()) {
                // No meaningful tokens after all filters — return null so caller keeps heuristic placeholder
                return null;
            } else {
                commonPart = String.join(" ", uniqueTokens);
            }"""

if old in content:
    content = content.replace(old, new)
    print('Fix applied: smart alpha-prefix extraction for mixed tokens')
else:
    print('NOT FOUND — checking:')
    idx = content.find('Extract the common unique part')
    print(repr(content[max(0,idx-20):idx+600]))

with open(file, 'w', encoding='utf-8', newline='\n') as f:
    f.write(content)
print('Done')
