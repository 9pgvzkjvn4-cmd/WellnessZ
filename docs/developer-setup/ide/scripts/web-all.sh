#!/usr/bin/env bash
# =============================================================================
# Run the same web steps as CI (.github/workflows/ci.yml, job "web") for all
# four React apps.
#
#   docs/developer-setup/ide/scripts/web-all.sh install   # npm ci --ignore-scripts --no-audit --no-fund
#   docs/developer-setup/ide/scripts/web-all.sh lint      # eslint --max-warnings 0
#   docs/developer-setup/ide/scripts/web-all.sh test      # vitest run
#   docs/developer-setup/ide/scripts/web-all.sh build     # tsc -b && vite build
#   docs/developer-setup/ide/scripts/web-all.sh audit     # npm audit --omit=dev --audit-level=high
#   docs/developer-setup/ide/scripts/web-all.sh check     # install + lint + test + build + audit
# =============================================================================
set -euo pipefail
# shellcheck source=lib/common.sh
. "$(dirname "$0")/lib/common.sh"

step() {
  case "$1" in
    install) npm ci --ignore-scripts --no-audit --no-fund ;;
    lint) npm run lint --if-present ;;
    test) npm test --if-present ;;
    build) npm run build ;;
    audit) npm audit --omit=dev --audit-level=high ;;
    *) die "unknown step '$1'" ;;
  esac
}

ACTION="${1:-check}"
if [[ $ACTION == check ]]; then
  STEPS=(install lint test build audit)
else
  STEPS=("$ACTION")
fi

for app in "${WZ_WEB_APPS[@]}"; do
  for s in "${STEPS[@]}"; do
    info "web/$app: $s"
    (cd "$WZ_ROOT/web/$app" && step "$s")
  done
done
info "All four web apps: ${STEPS[*]} OK"
