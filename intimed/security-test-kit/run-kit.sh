#!/usr/bin/env bash
# IntiMed security test kit – runs every locally applicable script against the running platform and builds the report.
# Usage: INTIMED_HOME=~/IntiMed-Demo-Data ./run-kit.sh      (demo profile personas; see README for other environments)
set -u
cd "$(dirname "$0")"
export INTIMED_HOME="${INTIMED_HOME:-$HOME/IntiMed-Demo-Data}"
rm -rf results; mkdir -p results
fail=0
for s in 02_tls_mtls_check.py 03_mllp_auth_and_robustness.py 04_fhir_api_authz.py 07_sbom_and_vuln_scan.sh 08_secrets_and_config_audit.py 09_audit_log_integrity.py \
         10_encryption_at_rest_check.sh 13_time_sync_and_cert_expiry.py 14_rate_limit_and_anomaly_check.py \
         17_tenant_isolation_check.py 18_recommendation_guardrails_check.py 19_exchange_harness_gate_check.py 12_device_default_credentials_check.py; do
  echo "== $s"
  case "$s" in *.py) python3 "$s" || fail=1 ;; *.sh) bash "$s" || fail=1 ;; esac
  sleep 1
done
python3 15_report_builder.py
exit $fail
