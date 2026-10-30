#!/usr/bin/env bash
# =============================================================================
# Build and start core or BFF with the local profile and a JDWP debug port,
# for IDEs that attach to a running JVM (Eclipse, VS Code "Attach", terminals).
#
#   docs/developer-setup/ide/scripts/run-service.sh core   # API 8080, mgmt 8081, debug 5005
#   docs/developer-setup/ide/scripts/run-service.sh bff    # BFF 8090, mgmt 8091, debug 5006
#
# Environment comes from deploy/local/core.local.env / bff.local.env
# (make-ide-env.sh). Options via environment:
#   WZ_DEBUG_PORT=5007    other debug port
#   WZ_DEBUG_SUSPEND=y    wait for the debugger before starting
#   WZ_SKIP_BUILD=1       reuse the existing jar
# =============================================================================
set -euo pipefail
# shellcheck source=lib/common.sh
. "$(dirname "$0")/lib/common.sh"

case "${1:-}" in
  core) MODULE=apps/wz-core-app JAR=wz-core-app.jar ENV_FILE=core.local.env PORT=5005 ;;
  bff) MODULE=apps/wz-bff JAR=wz-bff.jar ENV_FILE=bff.local.env PORT=5006 ;;
  *) die "usage: $0 core|bff" ;;
esac

ENV_PATH="$WZ_ROOT/deploy/local/$ENV_FILE"
[[ -f $ENV_PATH ]] || die "$ENV_PATH not found - run docs/developer-setup/ide/scripts/make-ide-env.sh"

cd "$WZ_ROOT"
if [[ ${WZ_SKIP_BUILD:-0} != 1 ]]; then
  info "Packaging $MODULE (tests skipped)"
  ./mvnw -q -DskipTests -pl "$MODULE" -am package
fi

export_env_file "$ENV_PATH"
DEBUG="-agentlib:jdwp=transport=dt_socket,server=y,suspend=${WZ_DEBUG_SUSPEND:-n},address=127.0.0.1:${WZ_DEBUG_PORT:-$PORT}"
info "Starting $JAR (profile $SPRING_PROFILES_ACTIVE, debugger on 127.0.0.1:${WZ_DEBUG_PORT:-$PORT})"
exec java "$DEBUG" -jar "$MODULE/target/$JAR"
