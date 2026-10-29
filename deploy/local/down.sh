#!/usr/bin/env bash
# Stop the WellnessZ local stack.
#   ./down.sh          stop containers, keep data volume
#   ./down.sh --purge  stop containers AND delete the postgres volume (all local data)
#
# Works by compose project name, so it does not depend on .env being complete.
set -euo pipefail

ARGS=(-p wellnessz down --remove-orphans)
if [[ "${1:-}" == "--purge" ]]; then
  ARGS+=(--volumes)
  echo "[down] Removing containers and the wellnessz-pgdata volume"
fi
# Run outside this directory so compose does not parse docker-compose.yml/.env
(cd / && docker compose "${ARGS[@]}")
