#!/usr/bin/env bash
# Step 4 – creates the runtime secrets in Key Vault once (idempotent): the platform master key (root KEK, 32 random
# bytes) and the application database password. Values are generated locally with openssl and piped to Key Vault –
# never printed, never written to disk, never in Terraform state or Git.
source "$(dirname "$0")/common.sh"
need az openssl
KV=$(tfo key_vault_name)
put_if_absent() {
  local name="$1" gen="$2"
  if az keyvault secret show --vault-name "$KV" -n "$name" --query id -o tsv >/dev/null 2>&1; then echo "exists  $name"; return; fi
  eval "$gen" | az keyvault secret set --vault-name "$KV" -n "$name" --file /dev/stdin --encoding utf-8 \
     --content-type text/plain --expires "$(date -u -d '+365 days' +%Y-%m-%dT%H:%M:%SZ)" --query id -o tsv >/dev/null
  echo "created $name"
}
put_if_absent intimed-master-key "openssl rand -base64 32 | tr -d '\n'"
put_if_absent intimed-db-password "openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 40"
echo "Key Vault $KV ready. Back up the master key with the break-glass procedure (Operations-and-DR.md §4): losing it makes all PHI unrecoverable."
