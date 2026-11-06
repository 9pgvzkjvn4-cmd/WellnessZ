#!/usr/bin/env bash
# Step 6 – installs / upgrades the Helm release on the private GKE cluster (DNS endpoint, IAM-authenticated) and runs
# `helm test`. The console TLS certificate is loaded once from CONSOLE_TLS_CERT / CONSOLE_TLS_KEY files (manual step M-02).
# Usage: ENV=uat CONSOLE_HOSTNAME=intimed.uat.example.qa IMAGE_TAG=2.2.0 [IMAGE_DIGEST=sha256:…] deploy/gcp/scripts/deploy.sh
source "$(dirname "$0")/common.sh"
need gcloud helm kubectl
"$(dirname "$0")/render-values.sh"
creds
kubectl get ns "$NS" >/dev/null 2>&1 || kubectl create ns "$NS"
if [ -n "${CONSOLE_TLS_CERT:-}" ] && [ -n "${CONSOLE_TLS_KEY:-}" ]; then
  kubectl -n "$NS" create secret tls intimed-console-tls --cert "$CONSOLE_TLS_CERT" --key "$CONSOLE_TLS_KEY" --dry-run=client -o yaml | kubectl apply -f -
fi
kubectl -n "$NS" get secret intimed-console-tls >/dev/null 2>&1 || { echo "console TLS secret intimed-console-tls missing – set CONSOLE_TLS_CERT/CONSOLE_TLS_KEY (Manual guide M-02)" >&2; exit 3; }
cd "$ROOT/deploy/helm"
helm upgrade --install intimed ./intimed -n "$NS" -f "./intimed/values-gcp-$ENV.yaml" -f "./intimed/values-gcp-$ENV.generated.yaml" --wait --timeout 15m
helm test intimed -n "$NS" --logs
kubectl -n "$NS" get gateway intimed-console -o jsonpath='{.status.addresses[0].value}{"\n"}' | sed 's/^/console address: /'
