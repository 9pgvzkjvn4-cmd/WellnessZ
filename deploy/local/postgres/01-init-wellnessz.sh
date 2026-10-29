#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# WellnessZ local PostgreSQL bootstrap (runs once, on first container start,
# from /docker-entrypoint-initdb.d as the POSTGRES_USER superuser).
#
# Roles (least privilege):
#   wz_migrator  - owns database `wellnessz` and all application schemas.
#                  Used ONLY by Flyway (spring.flyway.user) to run DDL.
#   wz_app       - runtime role for the core API. LOGIN, no DDL. Table-level
#                  privileges are granted ONLY by the Flyway migrations
#                  (e.g. platform.audit_event is SELECT/INSERT = append-only),
#                  so no broad default privileges are created here.
#   wz_bff       - runtime role for the BFF session store (schema `bff`, created
#                  by the BFF migrations). No access to clinical schemas.
#   keycloak     - owns the separate `keycloak` database (IdP state isolated
#                  from OH clinical data).
#
# Passwords come from environment variables supplied by docker compose
# (.env, never committed). The script fails if any is missing.
# -----------------------------------------------------------------------------
# shellcheck disable=SC2034  # loop variable is consumed inside a heredoc below
set -euo pipefail

: "${WZ_MIGRATOR_DB_PASSWORD:?WZ_MIGRATOR_DB_PASSWORD must be set}"
: "${WZ_APP_DB_PASSWORD:?WZ_APP_DB_PASSWORD must be set}"
: "${WZ_BFF_DB_PASSWORD:?WZ_BFF_DB_PASSWORD must be set}"
: "${KEYCLOAK_DB_PASSWORD:?KEYCLOAK_DB_PASSWORD must be set}"

SCHEMAS=(platform masterdata intake casemgmt analytics)

psql -v ON_ERROR_STOP=1 \
     --username "$POSTGRES_USER" --dbname postgres \
     --set=migrator_pw="$WZ_MIGRATOR_DB_PASSWORD" \
     --set=app_pw="$WZ_APP_DB_PASSWORD" \
     --set=bff_pw="$WZ_BFF_DB_PASSWORD" \
     --set=kc_pw="$KEYCLOAK_DB_PASSWORD" <<'SQL'
-- SCRAM only (also enforced via POSTGRES_INITDB_ARGS / pg_hba)
SET password_encryption = 'scram-sha-256';

CREATE ROLE wz_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS
  PASSWORD :'migrator_pw';
CREATE ROLE wz_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS
  CONNECTION LIMIT 100 PASSWORD :'app_pw';
CREATE ROLE wz_bff LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS
  CONNECTION LIMIT 50 PASSWORD :'bff_pw';
CREATE ROLE keycloak LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS
  PASSWORD :'kc_pw';

-- Runtime guard rails for the application role
ALTER ROLE wz_app SET statement_timeout = '30s';
ALTER ROLE wz_app SET idle_in_transaction_session_timeout = '60s';
ALTER ROLE wz_app SET search_path = platform, masterdata, intake, casemgmt, analytics;
ALTER ROLE wz_bff SET statement_timeout = '10s';
ALTER ROLE wz_bff SET search_path = bff;
ALTER ROLE wz_migrator SET search_path = platform, masterdata, intake, casemgmt, analytics;

CREATE DATABASE wellnessz OWNER wz_migrator ENCODING 'UTF8' LC_COLLATE 'C.UTF-8' LC_CTYPE 'C.UTF-8' TEMPLATE template0;
CREATE DATABASE keycloak  OWNER keycloak    ENCODING 'UTF8' TEMPLATE template0;

-- Nobody connects unless explicitly granted
REVOKE ALL ON DATABASE wellnessz FROM PUBLIC;
REVOKE ALL ON DATABASE keycloak  FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE wellnessz TO wz_app;
GRANT CONNECT ON DATABASE wellnessz TO wz_bff;
GRANT CONNECT, TEMPORARY, CREATE ON DATABASE wellnessz TO wz_migrator;
GRANT CONNECT ON DATABASE keycloak TO keycloak;
SQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname wellnessz <<SQL
-- Lock down the public schema (CVE-2018-1058 class issues)
REVOKE ALL ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO wz_migrator;

-- Extensions that migrations may need are pre-created by the superuser so the
-- migrator does not require elevated privileges.
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;
GRANT USAGE ON SCHEMA public TO wz_app;

$(for s in "${SCHEMAS[@]}"; do cat <<EOS
CREATE SCHEMA IF NOT EXISTS ${s} AUTHORIZATION wz_migrator;
REVOKE ALL ON SCHEMA ${s} FROM PUBLIC;
GRANT USAGE ON SCHEMA ${s} TO wz_app;
EOS
done)

-- Functions are executable by PUBLIC by default; revoke for new objects
ALTER DEFAULT PRIVILEGES FOR ROLE wz_migrator REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
SQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname keycloak <<'SQL'
REVOKE ALL ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO keycloak;
SQL

echo "[wellnessz-init] roles wz_migrator/wz_app/keycloak, databases wellnessz/keycloak and schemas created."
# NOTE: table-level grants (least privilege, append-only audit trail) are
# owned by the application's Flyway migrations via the ${app_role} placeholder.
