# One-shot helper to check / register your workers.dev subdomain.
# First GETs the existing subdomain (if any), then PUTs a new one
# only if needed. Prints Cloudflare's error response body verbatim
# on failure so we can see WHY a 400/409 happened.
#
#   powershell -ExecutionPolicy Bypass -File register-subdomain.ps1

$accountId = "533c789ac46b951522b39e12c3544718"
$tok = Read-Host "Paste your Cloudflare API token" -AsSecureString
$tokPlain = [System.Net.NetworkCredential]::new("", $tok).Password
$headers = @{ "Authorization" = "Bearer $tokPlain" }

# --- Step 1: check for an existing subdomain ----------------------
Write-Host ""
Write-Host "Checking for an existing subdomain..." -ForegroundColor Blue
try {
    $getResp = Invoke-RestMethod -Method Get `
        -Uri "https://api.cloudflare.com/client/v4/accounts/$accountId/workers/subdomain" `
        -Headers $headers
    if ($getResp.result.subdomain) {
        Write-Host ""
        Write-Host "You already have a subdomain registered:" -ForegroundColor Green
        Write-Host "    $($getResp.result.subdomain).workers.dev" -ForegroundColor Green
        Write-Host ""
        Write-Host "Your Worker URL will be: https://stash-tipjar.$($getResp.result.subdomain).workers.dev"
        Write-Host "No need to register a new one. Re-run setup.ps1 to deploy."
        exit 0
    }
} catch {
    Write-Host "  (none yet, or API call failed: $($_.Exception.Message))" -ForegroundColor Yellow
}

# --- Step 2: register a new subdomain -----------------------------
$sub = Read-Host "Pick a subdomain (3-32 chars, lowercase letters/digits/hyphens, e.g. rawnaldclark)"
$body = @{ subdomain = $sub } | ConvertTo-Json -Compress
Write-Host ""
Write-Host "Registering '$sub.workers.dev'..." -ForegroundColor Blue
try {
    $putResp = Invoke-RestMethod -Method Put `
        -Uri "https://api.cloudflare.com/client/v4/accounts/$accountId/workers/subdomain" `
        -Headers $headers -Body $body -ContentType "application/json"
    if ($putResp.success) {
        Write-Host ""
        Write-Host "Registered: $sub.workers.dev" -ForegroundColor Green
    } else {
        Write-Host "Registration response:" -ForegroundColor Yellow
        $putResp | ConvertTo-Json -Depth 5
    }
} catch {
    # Capture and print Cloudflare's actual error response body so we
    # can see WHY (e.g. "subdomain already taken", "minimum length", etc.).
    Write-Host "Registration failed:" -ForegroundColor Red
    if ($_.Exception.Response) {
        try {
            $stream = $_.Exception.Response.GetResponseStream()
            $reader = New-Object System.IO.StreamReader($stream)
            $errBody = $reader.ReadToEnd()
            Write-Host "Cloudflare said:" -ForegroundColor Yellow
            Write-Host $errBody
        } catch {
            Write-Host "(couldn't read response body)" -ForegroundColor Yellow
        }
    } else {
        Write-Host $_.Exception.Message
    }
}
