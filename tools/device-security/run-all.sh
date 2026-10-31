#!/usr/bin/env bash
# Runs the whole device security test pack (CI stage "device-security-pack", pre-release and quarterly – E34-BL-011) and builds
# the consolidated report. Defensive checks of the organisation's own systems only.
#   tools/device-security/run-all.sh --authorised --targets targets.json [--out <dir>] [--submit]
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
AUTH=""; TARGETS=""; OUT="${WZ_PACK_OUT:-$PWD/device-security-evidence}"; SUBMIT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --authorised) AUTH="--authorised" ;;
    --targets) TARGETS="$2"; shift ;;
    --out) OUT="$2"; shift ;;
    --submit) SUBMIT="--submit" ;;
  esac
  shift
done
if [ -z "$AUTH" ] || [ -z "$TARGETS" ]; then
  echo "Refusing to run: --authorised and --targets <file> are required." >&2
  exit 2
fi
mkdir -p "$OUT"
FAILED=0
for s in 01_network_segmentation_check.mjs 02_tls_mtls_check.mjs 03_mllp_auth_and_robustness.mjs 04_fhir_api_authz.mjs \
         05_gateway_hardening_linux.sh 05b_device_host_hardening_windows.mjs 06_ble_pairing_policy.mjs 07_sbom_and_vuln_scan.mjs \
         08_secrets_and_config_audit.mjs 09_audit_log_integrity.mjs 10_encryption_at_rest_check.sh 11_zap_api_baseline.sh \
         12_device_default_credentials_check.mjs 13_time_sync_and_cert_expiry.mjs 14_rate_limit_and_anomaly_check.mjs; do
  case "$s" in
    *.sh) bash "$HERE/$s" $AUTH --targets "$TARGETS" --out "$OUT" || FAILED=1 ;;
    *) node "$HERE/$s" $AUTH --targets "$TARGETS" --out "$OUT" || FAILED=1 ;;
  esac
done
node "$HERE/15_report_builder.mjs" $AUTH --targets "$TARGETS" --out "$OUT" $SUBMIT || FAILED=1
exit $FAILED
