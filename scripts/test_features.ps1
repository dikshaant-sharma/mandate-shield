$headers = @{"Content-Type" = "application/json"}

Write-Host "=== TEST 1: Actuator Health ==="
$health = Invoke-RestMethod -Uri "http://localhost:8080/actuator/health" -Method Get
Write-Host "Health:" ($health | ConvertTo-Json -Compress)

Write-Host "`n=== TEST 2: Frontend HTML Served ==="
$frontend = Invoke-WebRequest -Uri "http://127.0.0.1:5173" -Method Get -UseBasicParsing
Write-Host "Frontend Status Code:" $frontend.StatusCode

Write-Host "`n=== TEST 3: Auth Registration & Token ==="
$testUser = "user_" + (Get-Random) + "@example.com"
$regBody = @{
    email = $testUser
    password = "Password123!"
    fullName = "Test User"
} | ConvertTo-Json
$authResp = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/auth/register" -Method Post -Body $regBody -Headers $headers
$token = $authResp.token
Write-Host "Token obtained: $($token.Substring(0, 20))..."

$authHeaders = @{
    "Content-Type" = "application/json"
    "Authorization" = "Bearer $token"
}

Write-Host "`n=== TEST 4: Tier 1 Resolution (System-Approved Seeded Alias) ==="
$tier1Req = @{
    rawDescriptor = "ACH INB NFLX IN 9912"
    amount = 649.00
    currency = "INR"
} | ConvertTo-Json
$tier1Resp = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/resolver/resolve" -Method Post -Body $tier1Req -Headers $authHeaders
Write-Host "Brand:" $tier1Resp.brandName
Write-Host "Category:" $tier1Resp.category
Write-Host "Tier:" $tier1Resp.resolutionTier
Write-Host "Confidence:" $tier1Resp.confidenceScore
Write-Host "Matched Pattern:" $tier1Resp.matchedPattern

Write-Host "`n=== TEST 5: Tier 2 Resolution (Heuristic Engine) ==="
$tier2Req = @{
    rawDescriptor = "PAYU*SWIGGYONE_MANDATE_HYD"
    amount = 149.00
    currency = "INR"
} | ConvertTo-Json
$tier2Resp = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/resolver/resolve" -Method Post -Body $tier2Req -Headers $authHeaders
Write-Host "Brand:" $tier2Resp.brandName
Write-Host "Category:" $tier2Resp.category
Write-Host "Tier:" $tier2Resp.resolutionTier
Write-Host "Confidence:" $tier2Resp.confidenceScore
Write-Host "Matched Pattern:" $tier2Resp.matchedPattern

Write-Host "`n=== TEST 6: Tier 2.5 Resolution (Recurring Unnamed Descriptor) ==="
$cryptic = "ACH_INB_CRYPTIC_PORTAL_XYZ123"
# First occurrence
$tx1 = @{
    rawDescriptor = $cryptic
    amount = 299.00
    currency = "INR"
    transactionDate = (Get-Date).AddDays(-30).ToString("yyyy-MM-ddTHH:mm:ssZ")
} | ConvertTo-Json
$res1 = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/transactions" -Method Post -Body $tx1 -Headers $authHeaders
Write-Host "Occurrence 1 (not recurring yet) -> Brand:" $res1.merchant ", Tier:" $res1.resolutionTier

# Second occurrence of exact same cryptic descriptor (now recurring -> Tier 2.5 activates!)
$tx2 = @{
    rawDescriptor = $cryptic
    amount = 299.00
    currency = "INR"
    transactionDate = (Get-Date).ToString("yyyy-MM-ddTHH:mm:ssZ")
} | ConvertTo-Json
$res2 = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/transactions" -Method Post -Body $tx2 -Headers $authHeaders
Write-Host "Occurrence 2 (recurring detected) -> Brand:" $res2.merchant ", Tier:" $res2.resolutionTier ", Confidence:" $res2.confidenceScore

Write-Host "`n=== TEST 7: Pattern List & Cache Clear Endpoint ==="
$patterns = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/resolver/patterns" -Method Get -Headers $authHeaders
Write-Host "Seeded Approved Patterns in DB:" $patterns.Count
$cacheClear = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/resolver/patterns/cache/clear" -Method Post -Headers $authHeaders
Write-Host "Cache Clear Status:" ($cacheClear | ConvertTo-Json -Compress)
