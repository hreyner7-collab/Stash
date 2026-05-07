#!/usr/bin/env bash
#
# Stash Tip Jar — automated Cloudflare Worker setup.
#
# This script automates EVERY step that doesn't require human action
# in a browser. Three things still need you:
#
#   (1) Cloudflare login   — opens browser for OAuth (one click).
#   (2) Ko-fi token        — copy/paste from https://ko-fi.com/manage/webhooks.
#   (3) Ko-fi webhook URL  — paste the deployed Worker URL into the
#                            same Ko-fi webhooks page after deploy.
#
# The script handles everything else: npm install, KV namespace
# creation, writing the namespace ID into wrangler.toml, setting the
# secret, deploying, and updating the Stash app's BuildConfig URL to
# point at the new Worker.
#
# Run from this directory:
#   ./setup.sh
#
# Cross-platform: tested in Git Bash on Windows + macOS/Linux bash.
# Requires: node, npm, sed (Git Bash includes all three).

set -euo pipefail

cd "$(dirname "$0")"

# ── Pretty output helpers ──────────────────────────────────────────
GREEN="\033[0;32m"
YELLOW="\033[0;33m"
RED="\033[0;31m"
BLUE="\033[0;34m"
RESET="\033[0m"

step() { echo -e "\n${BLUE}=>${RESET} ${1}"; }
ok()   { echo -e "${GREEN}✓${RESET} ${1}"; }
warn() { echo -e "${YELLOW}!${RESET} ${1}"; }
err()  { echo -e "${RED}✗${RESET} ${1}" >&2; }
ask()  { echo -e "\n${YELLOW}?${RESET} ${1}"; }

# ── Prereqs ────────────────────────────────────────────────────────
step "Checking prerequisites"
command -v node >/dev/null || { err "node not installed. Install from https://nodejs.org"; exit 1; }
command -v npm >/dev/null || { err "npm not installed. Comes with node."; exit 1; }
ok "node $(node -v) and npm $(npm -v) present"

# ── 1. npm install ─────────────────────────────────────────────────
step "Installing wrangler (Cloudflare Worker CLI)"
if [ ! -d node_modules ]; then
    npm install
    ok "wrangler installed"
else
    ok "node_modules already present"
fi

# ── 2. Cloudflare login (manual) ───────────────────────────────────
step "Cloudflare login"
if ! npx wrangler whoami >/dev/null 2>&1; then
    ask "We'll open a browser for Cloudflare OAuth. Press Enter when ready."
    read -r
    npx wrangler login
    ok "Logged in to Cloudflare"
else
    ok "Already logged in to Cloudflare as $(npx wrangler whoami 2>&1 | grep -i 'email' || echo 'an account')"
fi

# ── 3. Create KV namespace + write ID into wrangler.toml ───────────
step "Creating Cloudflare KV namespace 'STASH_KV'"
# Idempotency: detect existing namespace ID in wrangler.toml.
EXISTING_ID=$(grep -E '^id = "[a-f0-9]+"' wrangler.toml | sed -E 's/.*"([a-f0-9]+)".*/\1/' || echo "")
if [ -n "${EXISTING_ID}" ] && [ "${EXISTING_ID}" != "REPLACE_WITH_NAMESPACE_ID" ]; then
    ok "KV namespace already configured (id=${EXISTING_ID})"
else
    KV_OUTPUT=$(npx wrangler kv:namespace create STASH_KV 2>&1) || true
    KV_ID=$(echo "${KV_OUTPUT}" | grep -oE 'id = "[a-f0-9]+"' | head -1 | sed -E 's/id = "([a-f0-9]+)"/\1/')
    if [ -z "${KV_ID}" ]; then
        # Maybe it already exists? Try listing.
        warn "Couldn't parse new namespace id. Checking existing namespaces..."
        KV_LIST=$(npx wrangler kv:namespace list 2>/dev/null || true)
        KV_ID=$(echo "${KV_LIST}" | grep -B1 'STASH_KV' | grep -oE '"id":\s*"[a-f0-9]+"' | head -1 | sed -E 's/.*"([a-f0-9]+)".*/\1/')
    fi
    if [ -z "${KV_ID}" ]; then
        err "Failed to create or find STASH_KV namespace. Run manually: npx wrangler kv:namespace create STASH_KV"
        echo "${KV_OUTPUT}"
        exit 1
    fi
    # Write the ID into wrangler.toml in-place (works on macOS + Git Bash + Linux).
    sed -i.bak "s/REPLACE_WITH_NAMESPACE_ID/${KV_ID}/" wrangler.toml
    rm -f wrangler.toml.bak
    ok "KV namespace ${KV_ID} written into wrangler.toml"
fi

# ── 4. Ko-fi verification token ────────────────────────────────────
step "Ko-fi verification token"
ask "Open https://ko-fi.com/manage/webhooks and copy the 'Verification Token'."
ask "Paste it here (input is hidden):"
read -rs KOFI_TOKEN
echo
if [ -z "${KOFI_TOKEN}" ]; then
    err "Empty token. Aborting."
    exit 1
fi

# Pipe the token to wrangler secret put (avoids the interactive prompt).
echo "${KOFI_TOKEN}" | npx wrangler secret put KOFI_VERIFICATION_TOKEN
ok "Verification token stored as Worker secret"

# ── 5. Deploy ──────────────────────────────────────────────────────
step "Deploying to Cloudflare"
DEPLOY_OUTPUT=$(npx wrangler deploy 2>&1)
echo "${DEPLOY_OUTPUT}"
WORKER_URL=$(echo "${DEPLOY_OUTPUT}" | grep -oE 'https://[a-zA-Z0-9.-]+\.workers\.dev' | head -1)
if [ -z "${WORKER_URL}" ]; then
    err "Could not detect deployed Worker URL. Re-run wrangler deploy manually and copy the URL."
    exit 1
fi
ok "Worker deployed at: ${WORKER_URL}"

# ── 6. Update app's BuildConfig SUPPORTERS_JSON_URL ────────────────
step "Pointing the Stash app at the new Worker URL"
APP_GRADLE="../../app/build.gradle.kts"
if [ ! -f "${APP_GRADLE}" ]; then
    warn "Couldn't find ${APP_GRADLE} — skipping app config update."
    warn "Manually edit app/build.gradle.kts and set SUPPORTERS_JSON_URL to ${WORKER_URL}"
else
    # Replace the SUPPORTERS_JSON_URL value (any URL between the quotes).
    # Cross-platform sed: use a temp file rather than -i (BSD/GNU diff).
    TMP=$(mktemp)
    awk -v url="${WORKER_URL}" '
        /SUPPORTERS_JSON_URL/ && /\\"https:/ {
            sub(/"\\".*\\""/, "\"\\\""url"\\\"\"")
        }
        { print }
    ' "${APP_GRADLE}" > "${TMP}"
    # Simpler: just sed the line. The buildConfigField is a single line.
    sed -E "s|\"\\\\\"https://[^\"]+\\\\\"\"|\"\\\\\"${WORKER_URL}\\\\\"\"|" "${APP_GRADLE}" > "${TMP}"
    mv "${TMP}" "${APP_GRADLE}"
    ok "Set SUPPORTERS_JSON_URL = ${WORKER_URL} in app/build.gradle.kts"
fi

# ── 7. Final manual step ───────────────────────────────────────────
step "ALMOST DONE — one manual step left"
echo
echo "    1. Open https://ko-fi.com/manage/webhooks"
echo "    2. Set the 'Webhook URL' to:"
echo
echo -e "       ${GREEN}${WORKER_URL}${RESET}"
echo
echo "    3. Click Save."
echo
echo "    Then click 'Send Test Donation' on that page and run:"
echo
echo -e "       ${BLUE}curl ${WORKER_URL}${RESET}"
echo
echo "    You should see the test donation in the JSON response."
echo
ok "All scripted steps complete. Rebuild the app once to pick up the new SUPPORTERS_JSON_URL."
