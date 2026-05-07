# Stash Tip Jar - automated Cloudflare Worker setup (PowerShell).
#
# Wrangler invocations are wrapped through `cmd /c` so PowerShell
# doesn't wrap their stderr lines as ErrorRecords (a Windows
# PowerShell 5.1 quirk that aborts the script on benign warnings).
#
# Three things still need you in a browser:
#   (1) Create a Cloudflare API token at
#       https://dash.cloudflare.com/profile/api-tokens (one-time, "Edit
#       Cloudflare Workers" template)
#   (2) Get Ko-fi verification token from
#       https://ko-fi.com/manage/webhooks
#   (3) Paste the deployed Worker URL into the same Ko-fi page after
#       deploy
#
# Run from this directory:
#   powershell -ExecutionPolicy Bypass -File setup.ps1

# Don't auto-abort on stderr writes from native exes.
$ErrorActionPreference = "Continue"
Set-Location -Path $PSScriptRoot

function Step($msg) { Write-Host ""; Write-Host "=> $msg" -ForegroundColor Blue }
function Ok($msg)   { Write-Host "  $msg" -ForegroundColor Green }
function Warn($msg) { Write-Host "  $msg" -ForegroundColor Yellow }
function Err($msg)  { Write-Host "  $msg" -ForegroundColor Red }
function Ask($msg)  { Write-Host ""; Write-Host "? $msg" -ForegroundColor Yellow }

# Wrap wrangler in cmd /c so PowerShell's stderr-wrapping doesn't fire
# on benign warnings ("out-of-date", "deprecation notice", etc.).
function Run-Wrangler {
    param([Parameter(ValueFromRemainingArguments=$true)][string[]]$WranglerArgs)
    $line = "npx --yes wrangler " + ($WranglerArgs -join " ") + " 2>&1"
    return (cmd /c $line | Out-String)
}

# --- Prereqs ---------------------------------------------------------
Step "Checking prerequisites"
if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    Err "node is not installed. Install from https://nodejs.org (LTS)."
    exit 1
}
$nodeVer = (node -v); $npmVer = (npm -v)
Ok "node $nodeVer and npm $npmVer present"

# --- 1. npm install --------------------------------------------------
Step "Installing wrangler"
if (-not (Test-Path "node_modules")) {
    npm install
    Ok "wrangler installed"
} else {
    Ok "node_modules already present"
}

# --- 2. CLOUDFLARE_API_TOKEN ----------------------------------------
Step "Cloudflare API token"
if ([string]::IsNullOrWhiteSpace($env:CLOUDFLARE_API_TOKEN)) {
    Write-Host ""
    Write-Host "  We need a Cloudflare API token (NOT your account password)."
    Write-Host ""
    Write-Host "  How to create one (one-time, ~60 seconds):"
    Write-Host ""
    Write-Host "    1. https://dash.cloudflare.com/profile/api-tokens"
    Write-Host "    2. Click 'Create Token'"
    Write-Host "    3. Find 'Edit Cloudflare Workers' template -> 'Use template'"
    Write-Host "    4. Scroll to bottom -> 'Continue to summary' -> 'Create Token'"
    Write-Host "    5. Copy the token (Cloudflare only shows it ONCE)"
    Write-Host ""
    $tokenSecure = Read-Host -Prompt "Paste your Cloudflare API token (input is hidden)" -AsSecureString
    $token = [System.Net.NetworkCredential]::new("", $tokenSecure).Password
    if (-not $token) {
        Err "Empty token. Aborting."
        exit 1
    }
    $env:CLOUDFLARE_API_TOKEN = $token
    Ok "Token set for this PowerShell session"
    Warn "(Set CLOUDFLARE_API_TOKEN in System Properties -> Environment"
    Warn " Variables to skip this prompt next time.)"
} else {
    Ok "CLOUDFLARE_API_TOKEN already set in environment"
}

# Verify the token authenticates.
Step "Verifying API token"
$verify = Run-Wrangler whoami
if ($verify -match "Unable to authenticate" -or $verify -match "Authentication error" -or $verify -match "401") {
    Err "Token rejected. Output:"
    Write-Host $verify
    Err "Re-create at https://dash.cloudflare.com/profile/api-tokens and try again."
    exit 1
}
Ok "Token authenticated"

# --- 3. KV namespace + write ID to wrangler.toml --------------------
Step "Creating Cloudflare KV namespace 'STASH_KV'"
$tomlContent = Get-Content -Path "wrangler.toml" -Raw
if ($tomlContent -notmatch 'REPLACE_WITH_NAMESPACE_ID') {
    $existing = [regex]::Match($tomlContent, 'id\s*=\s*"([a-f0-9]+)"').Groups[1].Value
    Ok "KV namespace already configured (id=$existing)"
} else {
    $kvOutput = Run-Wrangler kv namespace create STASH_KV
    Write-Host $kvOutput

    $kvId = [regex]::Match($kvOutput, 'id\s*=\s*"([a-f0-9]+)"').Groups[1].Value
    if (-not $kvId) {
        Warn "Couldn't parse new namespace id; checking existing namespaces..."
        $kvList = Run-Wrangler kv namespace list
        $matches = [regex]::Matches($kvList, '\{\s*"id"\s*:\s*"([a-f0-9]+)"\s*,\s*"title"\s*:\s*"([^"]+)"')
        foreach ($m in $matches) {
            if ($m.Groups[2].Value -match "STASH_KV") {
                $kvId = $m.Groups[1].Value
                break
            }
        }
    }
    if (-not $kvId) {
        Err "Failed to create or find STASH_KV namespace. Output above."
        Err "If the issue persists, run manually:"
        Err "  npx wrangler kv namespace create STASH_KV"
        exit 1
    }
    (Get-Content -Path "wrangler.toml" -Raw) -replace "REPLACE_WITH_NAMESPACE_ID", $kvId | Set-Content -Path "wrangler.toml" -NoNewline
    Ok "KV namespace $kvId written into wrangler.toml"
}

# --- 4. Ko-fi verification token ------------------------------------
Step "Ko-fi verification token"
Ask "Open https://ko-fi.com/manage/webhooks and copy the 'Verification Token'."
$kofiSecure = Read-Host -Prompt "Paste it here (input is hidden)" -AsSecureString
$kofiToken = [System.Net.NetworkCredential]::new("", $kofiSecure).Password
if (-not $kofiToken) {
    Err "Empty token. Aborting."
    exit 1
}
# `wrangler secret put` expects the secret on stdin. Use a temp file
# rather than `echo X | ...` because the inline pipe closes the
# script's stdin handle, which then breaks Read-Host calls later in
# the same script (subdomain prompt, etc.).
$secretFile = New-TemporaryFile
[System.IO.File]::WriteAllText($secretFile.FullName, $kofiToken, (New-Object System.Text.UTF8Encoding $false))
$secretCmd = "type `"$($secretFile.FullName)`" | npx --yes wrangler secret put KOFI_VERIFICATION_TOKEN 2>&1"
$secretOut = (cmd /c $secretCmd | Out-String)
Remove-Item -Force $secretFile.FullName
Write-Host $secretOut
if ($secretOut -match "Success!" -or $secretOut -match "Created secret") {
    Ok "Verification token stored as Worker secret"
} else {
    Warn "Secret-put output was unusual; verifying it landed..."
    $secretList = Run-Wrangler secret list
    if ($secretList -match "KOFI_VERIFICATION_TOKEN") {
        Ok "Verified: KOFI_VERIFICATION_TOKEN present in Worker secrets"
    } else {
        Err "Could not confirm the secret was set. Try manually:"
        Err "  npx wrangler secret put KOFI_VERIFICATION_TOKEN"
        exit 1
    }
}

# --- 4b. Ensure workers.dev subdomain is registered -----------------
Step "Checking workers.dev subdomain registration"
# Look up the user's account ID. The list-accounts API works with any
# valid token; first account is usually correct for a personal account.
$accountsResp = Invoke-RestMethod -Method Get `
    -Uri "https://api.cloudflare.com/client/v4/accounts" `
    -Headers @{ "Authorization" = "Bearer $env:CLOUDFLARE_API_TOKEN" }
$accountId = $accountsResp.result[0].id
if (-not $accountId) {
    Err "Couldn't fetch your Cloudflare account ID."
    exit 1
}
Ok "Account ID: $accountId"

# Check existing subdomain.
$subdomainResp = $null
try {
    $subdomainResp = Invoke-RestMethod -Method Get `
        -Uri "https://api.cloudflare.com/client/v4/accounts/$accountId/workers/subdomain" `
        -Headers @{ "Authorization" = "Bearer $env:CLOUDFLARE_API_TOKEN" }
} catch {
    $subdomainResp = $null
}

if ($subdomainResp -and $subdomainResp.result.subdomain) {
    Ok "Subdomain already registered: $($subdomainResp.result.subdomain).workers.dev"
} else {
    Ask "No workers.dev subdomain registered yet. Pick one now (letters/digits/dashes, ~3-30 chars)."
    Write-Host "  Suggestion: your GitHub username or 'stash-<something>'."
    $desired = Read-Host -Prompt "Desired subdomain (e.g. rawnaldclark -> rawnaldclark.workers.dev)"
    if (-not $desired) {
        Err "Empty subdomain. Aborting."
        exit 1
    }
    $body = (@{ subdomain = $desired } | ConvertTo-Json -Compress)
    try {
        $regResp = Invoke-RestMethod -Method Put `
            -Uri "https://api.cloudflare.com/client/v4/accounts/$accountId/workers/subdomain" `
            -Headers @{ "Authorization" = "Bearer $env:CLOUDFLARE_API_TOKEN" } `
            -Body $body -ContentType "application/json"
        if ($regResp.success) {
            Ok "Registered subdomain: $desired.workers.dev"
        } else {
            Err "Registration failed:"
            Write-Host ($regResp | ConvertTo-Json -Depth 5)
            exit 1
        }
    } catch {
        Err "Subdomain registration error: $($_.Exception.Message)"
        Err "If the subdomain is already taken, try a different name."
        exit 1
    }
}

# --- 5. Deploy ------------------------------------------------------
Step "Deploying to Cloudflare"
$deployOutput = Run-Wrangler deploy
Write-Host $deployOutput
$workerUrl = [regex]::Match($deployOutput, 'https://[A-Za-z0-9.\-]+\.workers\.dev').Value
if (-not $workerUrl) {
    Err "Could not detect deployed Worker URL."
    Err "Re-run 'npx wrangler deploy' manually and copy the URL."
    exit 1
}
Ok "Worker deployed at: $workerUrl"

# --- 6. Update app's BuildConfig SUPPORTERS_JSON_URL ----------------
Step "Pointing the Stash app at the new Worker URL"
$appGradle = Join-Path $PSScriptRoot "..\..\app\build.gradle.kts"
if (-not (Test-Path $appGradle)) {
    Warn "Couldn't find app/build.gradle.kts at $appGradle - skipping."
    Warn "Manually edit app/build.gradle.kts and set SUPPORTERS_JSON_URL to $workerUrl"
} else {
    $gradleContent = Get-Content -Path $appGradle -Raw
    $pattern = '(buildConfigField\([^,]+,\s*"SUPPORTERS_JSON_URL"\s*,\s*"\\")(https://[^\\"]+)(\\"")'
    $replacement = "`${1}$workerUrl`$3"
    $gradleNew = [regex]::Replace($gradleContent, $pattern, $replacement)
    if ($gradleNew -eq $gradleContent) {
        Warn "Couldn't find SUPPORTERS_JSON_URL in app/build.gradle.kts."
        Warn "Manually set it to: $workerUrl"
    } else {
        Set-Content -Path $appGradle -Value $gradleNew -NoNewline
        Ok "Set SUPPORTERS_JSON_URL = $workerUrl in app/build.gradle.kts"
    }
}

# --- 7. Final manual step -------------------------------------------
Step "ALMOST DONE - one manual step left"
Write-Host ""
Write-Host "    1. Open https://ko-fi.com/manage/webhooks"
Write-Host "    2. Set the 'Webhook URL' to:"
Write-Host ""
Write-Host "       $workerUrl" -ForegroundColor Green
Write-Host ""
Write-Host "    3. Click Save."
Write-Host ""
Write-Host "    Then click 'Send Test Donation' on that page and run:"
Write-Host ""
Write-Host "       curl $workerUrl" -ForegroundColor Blue
Write-Host ""
Write-Host "    You should see the test donation in the JSON response."
Write-Host ""
Ok "All scripted steps complete. Rebuild the app once to pick up the new SUPPORTERS_JSON_URL."
