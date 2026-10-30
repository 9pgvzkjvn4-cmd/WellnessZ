#!/usr/bin/env bash
# =============================================================================
# Run the Maven build against the local compose PostgreSQL instead of
# Testcontainers: recreates wellnessz_test, sets WZ_TEST_DB_* for this process
# only and runs ./mvnw with the given arguments (default: verify).
#
#   docs/developer-setup/ide/scripts/verify-with-local-db.sh
#   docs/developer-setup/ide/scripts/verify-with-local-db.sh -pl apps/wz-core-app -am verify
#
# Passwords are read from deploy/local/.env at run time; nothing is stored in an
# IDE configuration.
# =============================================================================
set -euo pipefail
# shellcheck source=lib/common.sh
. "$(dirname "$0")/lib/common.sh"
require_dotenv

DB="${WZ_TEST_DB_NAME:-wellnessz_test}"
"$(dirname "$0")/recreate-test-db.sh" "$DB"

WZ_TEST_DB_PASSWORD=$(env_value "$WZ_DOTENV" WZ_APP_DB_PASSWORD) || die "WZ_APP_DB_PASSWORD missing in .env"
WZ_TEST_DB_MIGRATOR_PASSWORD=$(env_value "$WZ_DOTENV" WZ_MIGRATOR_DB_PASSWORD) || die "WZ_MIGRATOR_DB_PASSWORD missing in .env"
export WZ_TEST_DB_URL="jdbc:postgresql://localhost:5432/$DB"
export WZ_TEST_DB_USERNAME=wz_app
export WZ_TEST_DB_MIGRATOR_USERNAME=wz_migrator
export WZ_TEST_DB_PASSWORD WZ_TEST_DB_MIGRATOR_PASSWORD

cd "$WZ_ROOT"
if [[ $# -eq 0 ]]; then
  set -- verify
fi
exec ./mvnw "$@"
