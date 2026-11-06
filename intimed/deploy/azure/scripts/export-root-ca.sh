#!/usr/bin/env bash
# Step 8 – exports the platform's installation root CA (public certificate only) for Application Gateway backend trust
# and for devices / gateways that must verify the platform. Writes deploy/azure/terraform/envs/<env>-root-ca.pem.
source "$(dirname "$0")/common.sh"
OUT="$TF_DIR/envs/$ENV-root-ca.pem"
kc exec -n "$NS" intimed-0 -c platform -- cat /data/pki/root-ca.pem > "$OUT"
openssl x509 -in "$OUT" -noout -subject -enddate -fingerprint -sha256
echo "→ set console_root_ca_pem = file(\"envs/$ENV-root-ca.pem\") via -var or tfvars and enable_app_gateway = true"
