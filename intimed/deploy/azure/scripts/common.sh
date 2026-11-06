# Shared helpers for the IntiMed Azure scripts (sourced). ENV=uat|prod selects envs/<env>.tfvars and the state key.
set -euo pipefail
ENV="${ENV:-uat}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
TF_DIR="$ROOT/deploy/azure/terraform"
NS="${NS:-intimed}"
need() { for c in "$@"; do command -v "$c" >/dev/null || { echo "missing tool: $c" >&2; exit 2; }; done; }
tfo() { terraform -chdir="$TF_DIR" output -raw "$1"; }
log() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
# kubectl that works for public and private AKS: private clusters go through `az aks command invoke` (no VPN needed).
kc() {
  local rg aks; rg=$(tfo resource_group); aks=$(tfo aks_name)
  if [ "$(az aks show -g "$rg" -n "$aks" --query apiServerAccessProfile.enablePrivateCluster -o tsv)" = "true" ]; then
    az aks command invoke -g "$rg" -n "$aks" --command "kubectl $*" -o tsv --query logs
  else
    kubectl "$@"
  fi
}
