#!/usr/bin/env bash
# =============================================================================
# Helm release operations for one cloud environment (used by
# .github/workflows/deploy-env.yml; usable by operators with the same inputs).
#
# Runs on the CI runner or inside a private AKS cluster through
# `az aks command invoke` - in that case the inputs come from ./release.env in
# the uploaded bundle (written by the workflow). Needs only bash, helm, kubectl.
#
#   helm-release.sh previous        print the currently deployed revision ("" if none)
#   helm-release.sh upgrade         helm upgrade --install --atomic with the layered
#                                   values and the promoted image digests
#   helm-release.sh smoke           rollout status of every Deployment of the release and
#                                   check that running pods use exactly the promoted digests
#   helm-release.sh rollback <rev>  helm rollback to <rev> and wait
#
# Inputs (environment or ./release.env):
#   CLOUD (azure|gcp)  DEPLOY_ENV (dev|uat|prod)  RELEASE  NAMESPACE  IMAGE_REGISTRY
#   IMAGE_TAG (label only)  DIGESTS_FILE (lines "<image> sha256:<digest>", default digests.txt)
#   EXTRA_VALUES_FILES (space separated, optional - client customisation overlays)
#   CHART (default deploy/helm/wellnessz)  HELM_TIMEOUT (default 15m)
# =============================================================================
set -euo pipefail

if [[ -f ./release.env ]]; then
  # shellcheck disable=SC1091
  . ./release.env
fi

CHART=${CHART:-deploy/helm/wellnessz}
DIGESTS_FILE=${DIGESTS_FILE:-digests.txt}
HELM_TIMEOUT=${HELM_TIMEOUT:-15m}

die() { echo "helm-release: $*" >&2; exit 1; }

require() {
  local v
  for v in "$@"; do [[ -n "${!v:-}" ]] || die "$v is required"; done
}

# Image name (container.yml matrix) -> Helm component key
component_key() {
  case "$1" in
    wz-core-app) echo core ;;
    wz-bff) echo bff ;;
    employee-app) echo employeePortal ;;
    manager-workspace) echo managerWorkspace ;;
    oh-workspace) echo ohWorkspace ;;
    partner-portal) echo partnerPortal ;;
    *) return 1 ;;
  esac
}

read_digests() {
  [[ -f "$DIGESTS_FILE" ]] || die "digest file $DIGESTS_FILE not found"
  local img digest n=0
  while read -r img digest; do
    [[ -z "${img}" || "${img}" == \#* ]] && continue
    component_key "$img" >/dev/null || die "unknown image '$img' in $DIGESTS_FILE"
    [[ "$digest" =~ ^sha256:[a-f0-9]{64}$ ]] || die "invalid digest for $img: '$digest'"
    printf '%s %s\n' "$img" "$digest"
    n=$((n + 1))
  done < "$DIGESTS_FILE"
  [[ $n -gt 0 ]] || die "no digests in $DIGESTS_FILE"
}

cmd_previous() {
  require RELEASE NAMESPACE
  # last *deployed* revision; empty on first install
  helm list -n "$NAMESPACE" --deployed --filter "^${RELEASE}\$" -o json 2>/dev/null \
    | grep -o '"revision":"[0-9]*"' | head -n1 | grep -o '[0-9][0-9]*' || true
}

cmd_upgrade() {
  require CLOUD DEPLOY_ENV RELEASE NAMESPACE IMAGE_REGISTRY
  local f args=() sets=()
  args+=(-f "$CHART/values.yaml" -f "$CHART/values-${CLOUD}.yaml" -f "$CHART/environments/${CLOUD}/${DEPLOY_ENV}.yaml")
  for f in ${EXTRA_VALUES_FILES:-}; do
    [[ -f "$f" ]] || die "extra values file $f not found"
    args+=(-f "$f")
  done
  for f in "${args[@]}"; do [[ "$f" == -f ]] || [[ -f "$f" ]] || die "values file $f not found"; done

  sets+=(--set-string "global.imageRegistry=${IMAGE_REGISTRY}")
  sets+=(--set-string "global.environment=${DEPLOY_ENV}")
  [[ -n "${IMAGE_TAG:-}" ]] && sets+=(--set-string "global.imageTag=${IMAGE_TAG}")
  local img digest
  read_digests >/dev/null # validate in this shell (die must abort the script)
  while read -r img digest; do
    sets+=(--set-string "$(component_key "$img").image.digest=${digest}")
  done < <(read_digests)

  echo "helm upgrade ${RELEASE} (${CLOUD}/${DEPLOY_ENV}) values: ${args[*]}"
  helm upgrade --install "$RELEASE" "$CHART" \
    --namespace "$NAMESPACE" \
    "${args[@]}" "${sets[@]}" \
    --atomic --wait --timeout "$HELM_TIMEOUT" --history-max 10
  helm -n "$NAMESPACE" status "$RELEASE"
}

cmd_smoke() {
  require RELEASE NAMESPACE IMAGE_REGISTRY
  local selector="app.kubernetes.io/instance=${RELEASE}" d deploys
  deploys=$(kubectl -n "$NAMESPACE" get deploy -l "$selector" -o name)
  [[ -n "$deploys" ]] || die "no Deployments found for release ${RELEASE}"
  for d in $deploys; do
    kubectl -n "$NAMESPACE" rollout status "$d" --timeout=10m
  done

  # Every running application container must use one of the promoted digests
  local expected img digest image bad=0 seen=0
  read_digests >/dev/null
  expected=$(read_digests | while read -r img digest; do echo "${IMAGE_REGISTRY%/}/${img}@${digest}"; done)
  while read -r image; do
    [[ -z "$image" ]] && continue
    seen=$((seen + 1))
    if ! grep -qxF "$image" <<<"$expected"; then
      echo "unexpected image running: $image" >&2
      bad=1
    fi
  done < <(kubectl -n "$NAMESPACE" get pods -l "$selector" --field-selector=status.phase=Running \
    -o jsonpath='{range .items[*]}{range .spec.containers[*]}{.image}{"\n"}{end}{end}')
  [[ $seen -gt 0 ]] || die "no running pods for release ${RELEASE}"
  [[ $bad -eq 0 ]] || die "running images do not match the promoted digests"
  echo "smoke: ${seen} running containers use the promoted digests"
}

cmd_rollback() {
  require RELEASE NAMESPACE
  local rev=${1:-}
  [[ "$rev" =~ ^[0-9]+$ ]] || die "rollback needs a revision number"
  helm rollback "$RELEASE" "$rev" --namespace "$NAMESPACE" --wait --timeout 10m
  helm -n "$NAMESPACE" status "$RELEASE"
}

case "${1:-}" in
  previous) cmd_previous ;;
  upgrade) cmd_upgrade ;;
  smoke) cmd_smoke ;;
  rollback) shift; cmd_rollback "$@" ;;
  *) die "usage: $0 previous|upgrade|smoke|rollback <revision>" ;;
esac
