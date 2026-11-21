import urllib.request, json

def test(descriptor):
    prompt = f"""Identify the actual merchant brand name from this bank transaction descriptor.
Ignore payment gateways like RAZORPAY, RZP, BILLDESK, PAYU, CASHFREE, CCAVENUE.
Ignore location and transaction noise like MKTP, MARKETPLACE, US, SEATTLE, WA, MUMBAI, BLR, ACH, INB, SI, MNDT.
If you recognize the merchant, reply with format: BRAND|CONFIDENCE|CATEGORY
Where CONFIDENCE is a number between 0.70 and 0.95.
If you cannot recognize the merchant, reply UNKNOWN|0.0|Uncategorized.
Example: AMZN MKTP US*2X49L091 -> AMAZON|0.92|E-Commerce
Example: BILLDESK_LENSKART_GOLD -> LENSKART|0.90|Eyewear & Healthcare
Descriptor: {descriptor}
Reply:"""

    body = {
        "model": "sarvam-105b-conversations",
        "messages": [{"role": "user", "content": prompt}],
        "max_tokens": 50,
        "temperature": 0.0
    }
    req = urllib.request.Request(
        "https://api.sarvam.ai/v1/chat/completions",
        data=json.dumps(body).encode(),
        headers={"api-subscription-key": "sk_k72fneb6_QLpwt506zt6YnqoRzSfsRMiX", "Content-Type": "application/json"}
    )
    res = json.loads(urllib.request.urlopen(req).read().decode())
    content = res["choices"][0]["message"]["content"]
    print(f"Descriptor: {descriptor} -> Sarvam: {content}")

test("RAZP MKTP US*2X49L091 SEATTLE WA")
test("AMZN MKTP US*2X49L091 SEATTLE WA")
test("BILLDESK_LENSKART_GOLD_SUBSCRIPTION_DL")
test("ACH_INB_SPOTIFY_MUMBAI_SI")
test("RANDOM_PORTAL_XYZ_9832")
