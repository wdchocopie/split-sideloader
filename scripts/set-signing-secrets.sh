#!/usr/bin/env bash
# Copies the local signing key into the repository's Actions secrets. Run it once, from the
# project root, on the machine that holds splitsideloader.jks and keystore.properties.
#
#   bash scripts/set-signing-secrets.sh [owner/repo]
#
# The values go straight from the files to GitHub; nothing is printed.
set -euo pipefail

repo="${1:-$(gh repo view --json nameWithOwner -q .nameWithOwner)}"
owner="${repo%%/*}"
props=keystore.properties

if [ ! -f "$props" ]; then
    echo "no $props here; run this from the project root" >&2
    exit 1
fi

# Use the owner's own login, whichever gh account happens to be active.
if gh auth token --user "$owner" >/dev/null 2>&1; then
    GH_TOKEN="$(gh auth token --user "$owner")"
    export GH_TOKEN
fi

get() { grep -E "^$1=" "$props" | head -1 | cut -d= -f2- | tr -d '\r'; }

store_file="$(get storeFile)"
if [ ! -f "$store_file" ]; then
    echo "keystore $store_file not found" >&2
    exit 1
fi

base64 -w0 "$store_file" | gh secret set SIGNING_KEYSTORE_BASE64 --repo "$repo"
printf '%s' "$(get storePassword)" | gh secret set SIGNING_STORE_PASSWORD --repo "$repo"
printf '%s' "$(get keyAlias)" | gh secret set SIGNING_KEY_ALIAS --repo "$repo"
printf '%s' "$(get keyPassword)" | gh secret set SIGNING_KEY_PASSWORD --repo "$repo"

echo "signing secrets set on $repo"
