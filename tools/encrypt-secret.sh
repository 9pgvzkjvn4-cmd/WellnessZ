#!/usr/bin/env bash
# Produces an ENC(...) value (AES-256-GCM) for configuration, e.g. a database connector password.
# The secret is read without echo; it never appears in shell history or process lists.
#   export WZ_MASTER_KEY=$(openssl rand -base64 32)   # or WZ_MASTER_KEY_FILE=/path/to/key
#   ./tools/encrypt-secret.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CLASSES="$ROOT/platform/wz-secrets/target/classes"
if [[ ! -d "$CLASSES" ]]; then
  (cd "$ROOT" && ./mvnw -q -pl platform/wz-secrets -am compile)
fi
if [[ -z "${WZ_MASTER_KEY:-}" && -z "${WZ_MASTER_KEY_FILE:-}" ]]; then
  echo "Set WZ_MASTER_KEY or WZ_MASTER_KEY_FILE first (generate: openssl rand -base64 32)" >&2
  exit 2
fi
exec java -cp "$CLASSES" com.wellnessz.secrets.EncryptCli
