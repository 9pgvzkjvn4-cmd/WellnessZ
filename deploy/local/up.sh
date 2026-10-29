#!/usr/bin/env bash
# Start the WellnessZ local stack.
#   ./up.sh            infra only (postgres, keycloak, mailpit, greenmail, otel)
#   ./up.sh --apps     infra + core + bff (built from deploy/docker/*)
set -euo pipefail
cd "$(dirname "$0")"

PROFILE_ARGS=()
if [[ "${1:-}" == "--apps" ]]; then
  PROFILE_ARGS=(--profile apps)
fi

if [[ ! -f .env ]]; then
  command -v openssl >/dev/null || { echo "openssl is required to generate .env" >&2; exit 1; }
  echo "[up] No .env found - generating one with random local-only secrets"
  umask 077
  gen() { openssl rand -base64 "$1" | tr -d '\n'; }
  # Passwords use hex to avoid characters that need escaping in URLs/JDBC.
  pw() { openssl rand -hex 18; }
  sed \
    -e "s|^POSTGRES_SUPERUSER_PASSWORD=.*|POSTGRES_SUPERUSER_PASSWORD=$(pw)|" \
    -e "s|^WZ_MIGRATOR_DB_PASSWORD=.*|WZ_MIGRATOR_DB_PASSWORD=$(pw)|" \
    -e "s|^WZ_APP_DB_PASSWORD=.*|WZ_APP_DB_PASSWORD=$(pw)|" \
    -e "s|^WZ_BFF_DB_PASSWORD=.*|WZ_BFF_DB_PASSWORD=$(pw)|" \
    -e "s|^KEYCLOAK_DB_PASSWORD=.*|KEYCLOAK_DB_PASSWORD=$(pw)|" \
    -e "s|^KEYCLOAK_ADMIN_PASSWORD=.*|KEYCLOAK_ADMIN_PASSWORD=$(pw)|" \
    -e "s|^WZ_MASTER_KEY=.*|WZ_MASTER_KEY=$(gen 32)|" \
    -e "s|^WZ_PSEUDONYM_KEY=.*|WZ_PSEUDONYM_KEY=$(gen 32)|" \
    -e "s|^WZ_BFF_MASTER_KEY=.*|WZ_BFF_MASTER_KEY=$(gen 32)|" \
    -e "s|^WZ_IMAP_PASSWORD=.*|WZ_IMAP_PASSWORD=$(pw)|" \
    .env.example > .env
  chmod 600 .env
  echo "[up] Wrote $(pwd)/.env (mode 600). Keycloak admin: see KEYCLOAK_ADMIN_* in .env"
fi

docker compose "${PROFILE_ARGS[@]}" up -d --build --wait

cat <<'EOF'

WellnessZ local stack is up:
  PostgreSQL     localhost:5432   db wellnessz (wz_app / wz_migrator), db keycloak
  Keycloak       http://localhost:8180  realm: wellnessz  (admin console /admin)
  Mailpit UI     http://localhost:8025  SMTP localhost:1025
  GreenMail      IMAP localhost:3143  user ehs-feed@wellnessz.local
  OTel collector OTLP grpc localhost:4317 / http localhost:4318
  (with --apps)  core http://localhost:8080 (mgmt 8081), bff http://localhost:8090

Test users (temporary password ChangeMe!123, must be changed on first login):
  employee1 manager1 hr1 clinician1 psych1 auditor1 admin1
EOF
