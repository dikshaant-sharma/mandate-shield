file = r'c:\Users\sdiks\OneDrive\Desktop\MandateShield\src\main\java\com\mandateshield\engine\MerchantResolverEngine.java'
with open(file, 'r', encoding='utf-8') as f:
    content = f.read()

# Fix the empty-uniqueTokens fallback in resolveByRecurringPattern
old_fallback = """            String commonPart;
            if (uniqueTokens.isEmpty()) {
                String clean = rawDescriptor.replaceAll("[^A-Za-z]+", " ").trim();
                commonPart = clean.isBlank() ? "RECURRING_MANDATE" : clean;
            } else {
                commonPart = String.join(" ", uniqueTokens);
            }"""

new_fallback = """            String commonPart;
            if (uniqueTokens.isEmpty()) {
                // No meaningful tokens after all filters — return null so caller keeps heuristic placeholder
                return null;
            } else {
                commonPart = String.join(" ", uniqueTokens);
            }"""

if old_fallback in content:
    content = content.replace(old_fallback, new_fallback)
    print('Fallback fix applied: empty uniqueTokens now returns null instead of raw string')
else:
    print('NOT FOUND')
    idx = content.find('uniqueTokens.isEmpty()')
    print(repr(content[max(0,idx-50):idx+400]))

with open(file, 'w', encoding='utf-8', newline='\n') as f:
    f.write(content)
print('Done')
