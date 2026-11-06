# Shared helpers for the IntiMed_GCP scripts (sourced). ENV=uat|prod selects envs/<env>.tfvars; PROJECT is the GCP project.
set -euo pipefail
ENV="${ENV:-uat}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
TF_DIR="$ROOT/deploy/gcp/terraform"
NS="${NS:-intimed}"
REGION=me-central1   # Doha – the only region IntiMed_GCP deploys to (health data residency)
need() { for c in "$@"; do command -v "$c" >/dev/null || { echo "missing tool: $c" >&2; exit 2; }; done; }
tfo() { terraform -chdir="$TF_DIR" output -raw "$1"; }
log() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
project() { if [ -n "${PROJECT:-}" ]; then echo "$PROJECT"; else tfo project_id 2>/dev/null || gcloud config get-value project 2>/dev/null; fi; }
# Credentials for the private GKE cluster through its IAM-authenticated DNS endpoint (no VPN / bastion needed).
creds() { gcloud container clusters get-credentials "$(tfo gke_name)" --region "$REGION" --project "$(project)" --dns-endpoint >/dev/null; }
