#!/usr/bin/env bash
# Runs the security test kit against an IntiMed deployment on AKS (UAT acceptance, every release).
# Prerequisites: az login + `az aks get-credentials` (Entra RBAC: Azure Kubernetes Service RBAC Reader + exec on the
# namespace), kubectl, python3, openssl. Accounts: either a UAT deployed with the demo profile while the console is not
# yet exposed (setup guide §7), or set INTIMED_PASSWORD / INTIMED_TOTP_SECRET / KIT_ADMIN / KIT_SOC / KIT_DEV.
# Usage: deploy/../security-test-kit/run-kit-aks.sh [namespace]
set -u
cd "$(dirname "$0")"
NS="${1:-intimed}"; POD="${INTIMED_KUBE_POD:-intimed-0}"
WORK="$(mktemp -d)"; trap 'kill $PF 2>/dev/null; rm -rf "$WORK"' EXIT
mkdir -p "$WORK/pki" "$WORK/siem"
# public material only: installation root CA (to verify the server) and the masked SIEM stream (chain hashes)
kubectl exec -n "$NS" "$POD" -c platform -- cat /data/pki/root-ca.pem > "$WORK/pki/root-ca.pem"
kubectl exec -n "$NS" "$POD" -c platform -- sh -c 'tail -n 2000 /data/siem/audit.jsonl' > "$WORK/siem/audit.jsonl" 2>/dev/null || true
kubectl port-forward -n "$NS" "pod/$POD" 9443:9443 8443:8443 2575:2575 >/dev/null 2>&1 &
PF=$!
for i in $(seq 1 30); do (echo > /dev/tcp/127.0.0.1/9443) 2>/dev/null && break; sleep 1; done
export INTIMED_HOME="$WORK" INTIMED_KUBE_POD="$POD" INTIMED_KUBE_NS="$NS"
bash ./run-kit.sh
rc=$?
python3 16_azure_posture_check.py ${AZ_RESOURCE_GROUP:+--resource-group "$AZ_RESOURCE_GROUP"} || rc=1
python3 05_container_runtime_check.py --namespace "$NS" || rc=1
python3 15_report_builder.py
exit $rc
