#!/usr/bin/env bash
# =============================================================================
# Drop and recreate the integration-test database in the local compose
# PostgreSQL. Run it before EVERY test run that uses WZ_TEST_DB_URL: the
# integration tests generate new WZ_MASTER_KEY / WZ_PSEUDONYM_KEY values per
# test JVM, so rows encrypted by the previous run can no longer be decrypted.
#
#   docs/developer-setup/ide/scripts/recreate-test-db.sh [db-name]   (default wellnessz_test)
#
# The name must end in _test (the E07 demo data relies on the suffix).
# The superuser password is taken from the container's own environment, so it
# never appears on a command line.
# =============================================================================
set -euo pipefail
# shellcheck source=lib/common.sh
. "$(dirname "$0")/lib/common.sh"
require_dotenv

DB="${1:-wellnessz_test}"
[[ $DB =~ ^[a-z][a-z0-9_]*_test$ ]] || die "Database name must match ^[a-z][a-z0-9_]*_test\$ (got '$DB')"

info "Recreating database $DB"
docker compose -f "$WZ_ROOT/deploy/local/docker-compose.yml" exec -T postgres sh -c "
  PGPASSWORD=\"\$POSTGRES_PASSWORD\" psql -q -v ON_ERROR_STOP=1 -h 127.0.0.1 -U postgres -d postgres \
    -c 'DROP DATABASE IF EXISTS $DB WITH (FORCE)' \
    -c 'CREATE DATABASE $DB OWNER wz_migrator' \
    -c 'GRANT CONNECT, TEMPORARY ON DATABASE $DB TO wz_app'
"
info "Database $DB is empty and ready"
