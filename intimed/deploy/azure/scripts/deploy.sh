#!/usr/bin/env bash
# Step 6 – installs / upgrades the Helm release (works for private clusters through `az aks command invoke`) and runs
# `helm test`. Usage: ENV=uat IMAGE_TAG=2.0.0 [IMAGE_DIGEST=sha256:…] deploy/azure/scripts/deploy.sh
source "$(dirname "$0")/common.sh"
need az helm
CHART="$ROOT/deploy/helm/intimed"
"$(dirname "$0")/render-values.sh"
RG=$(tfo resource_group); AKS=$(tfo aks_name)
ARGS="upgrade --install intimed ./intimed -n $NS --create-namespace -f ./intimed/values-$ENV.yaml -f ./intimed/values-$ENV.generated.yaml --wait --timeout 15m"
if [ "$(az aks show -g "$RG" -n "$AKS" --query apiServerAccessProfile.enablePrivateCluster -o tsv)" = "true" ]; then
  log "private cluster – helm via az aks command invoke"
  az aks command invoke -g "$RG" -n "$AKS" --file "$CHART" --command "helm $ARGS && helm test intimed -n $NS --logs" -o tsv --query logs
else
  az aks get-credentials -g "$RG" -n "$AKS" --overwrite-existing >/dev/null
  command -v kubelogin >/dev/null && kubelogin convert-kubeconfig -l azurecli
  (cd "$ROOT/deploy/helm" && helm $ARGS && helm test intimed -n "$NS" --logs)
fi
