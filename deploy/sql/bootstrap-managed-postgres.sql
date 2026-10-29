-- =============================================================================
-- WellnessZ - one-time role/schema bootstrap for MANAGED PostgreSQL
-- (Azure Database for PostgreSQL Flexible Server, Cloud SQL for PostgreSQL).
-- Idempotent. Run as the server administrator over TLS from a jump host /
-- private runner, NEVER from a laptop on the internet:
--
--   Azure (Entra admin group token):
--     export PGPASSWORD=$(az account get-access-token --resource-type oss-rdbms --query accessToken -o tsv)
--     psql "host=<server>.postgres.database.azure.com dbname=wellnessz user=<entra-group> sslmode=verify-full sslrootcert=system" \
--       -v migrator_pw="$(az keyvault secret show --vault-name <kv> -n wz-migrator-db-password --query value -o tsv)" \
--       -v app_pw="$(az keyvault secret show --vault-name <kv> -n wz-db-password --query value -o tsv)" \
--       -v bff_pw="$(az keyvault secret show --vault-name <kv> -n wz-bff-db-password --query value -o tsv)" \
--       -f bootstrap-managed-postgres.sql
--
--   GCP (Terraform already created the users and their passwords; IAM DBA login):
--     psql "host=<private-ip> dbname=wellnessz user=<dba@example.com> sslmode=verify-ca sslrootcert=server-ca.pem" \
--       -f bootstrap-managed-postgres.sql
--
-- Table-level privileges are NOT granted here: the application's Flyway
-- migrations grant exactly what each role needs via ${app_role}
-- (e.g. platform.audit_event is SELECT/INSERT only = append-only).
-- =============================================================================
\set ON_ERROR_STOP on

-- 1) Roles (create if missing)
SELECT format('CREATE ROLE %I LOGIN', r)
  FROM unnest(ARRAY['wz_migrator', 'wz_app', 'wz_bff']) AS r
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) \gexec

-- SUPERUSER/REPLICATION/BYPASSRLS are off by default and can only be changed by a superuser
ALTER ROLE wz_migrator NOCREATEDB NOCREATEROLE NOINHERIT;
ALTER ROLE wz_app      NOCREATEDB NOCREATEROLE NOINHERIT CONNECTION LIMIT 200;
ALTER ROLE wz_bff      NOCREATEDB NOCREATEROLE NOINHERIT CONNECTION LIMIT 50;

-- Passwords only when supplied (Azure); on GCP Terraform manages them
\if :{?migrator_pw}
ALTER ROLE wz_migrator PASSWORD :'migrator_pw';
\endif
\if :{?app_pw}
ALTER ROLE wz_app PASSWORD :'app_pw';
\endif
\if :{?bff_pw}
ALTER ROLE wz_bff PASSWORD :'bff_pw';
\endif

-- Cloud SQL adds API-created users to cloudsqlsuperuser; strip it (least privilege)
SELECT format('REVOKE cloudsqlsuperuser FROM %I', r)
  FROM unnest(ARRAY['wz_migrator', 'wz_app', 'wz_bff']) AS r
 WHERE EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cloudsqlsuperuser')
   AND pg_has_role(r, 'cloudsqlsuperuser', 'MEMBER') \gexec

ALTER ROLE wz_app SET statement_timeout = '30s';
ALTER ROLE wz_app SET idle_in_transaction_session_timeout = '60s';
ALTER ROLE wz_app SET search_path = platform, masterdata, intake, casemgmt, triage, docs, ohrecord, fitness, absence, surveillance, scheduling, analytics;
ALTER ROLE wz_bff SET statement_timeout = '10s';
ALTER ROLE wz_bff SET search_path = bff;
ALTER ROLE wz_migrator SET search_path = platform, masterdata, intake, casemgmt, triage, docs, ohrecord, fitness, absence, surveillance, scheduling, analytics;

-- 2) Ownership: the admin (non-superuser on managed services) needs SET on
--    wz_migrator to hand over ownership (PostgreSQL 16 semantics).
SELECT format('GRANT wz_migrator TO %I', current_user)
 WHERE NOT pg_has_role(current_user, 'wz_migrator', 'SET') \gexec
ALTER DATABASE wellnessz OWNER TO wz_migrator;

REVOKE ALL ON DATABASE wellnessz FROM PUBLIC;
-- keep the administrator able to connect (re-runs, break-glass) after ownership moves
SELECT format('GRANT CONNECT ON DATABASE wellnessz TO %I', current_user) \gexec
GRANT CONNECT, TEMPORARY ON DATABASE wellnessz TO wz_app;
GRANT CONNECT ON DATABASE wellnessz TO wz_bff;
GRANT CONNECT, TEMPORARY, CREATE ON DATABASE wellnessz TO wz_migrator;

REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO wz_app;
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;

-- 3) Application schemas owned by the migrator; runtime role gets USAGE only
SELECT format('CREATE SCHEMA IF NOT EXISTS %I AUTHORIZATION wz_migrator', s)
  FROM unnest(ARRAY['platform', 'masterdata', 'intake', 'casemgmt', 'triage', 'docs', 'ohrecord', 'fitness', 'absence', 'surveillance', 'scheduling', 'analytics']) AS s \gexec
SELECT format('ALTER SCHEMA %I OWNER TO wz_migrator', s)
  FROM unnest(ARRAY['platform', 'masterdata', 'intake', 'casemgmt', 'triage', 'docs', 'ohrecord', 'fitness', 'absence', 'surveillance', 'scheduling', 'analytics']) AS s \gexec
SELECT format('REVOKE ALL ON SCHEMA %I FROM PUBLIC', s)
  FROM unnest(ARRAY['platform', 'masterdata', 'intake', 'casemgmt', 'triage', 'docs', 'ohrecord', 'fitness', 'absence', 'surveillance', 'scheduling', 'analytics']) AS s \gexec
SELECT format('GRANT USAGE ON SCHEMA %I TO wz_app', s)
  FROM unnest(ARRAY['platform', 'masterdata', 'intake', 'casemgmt', 'triage', 'docs', 'ohrecord', 'fitness', 'absence', 'surveillance', 'scheduling', 'analytics']) AS s \gexec

ALTER DEFAULT PRIVILEGES FOR ROLE wz_migrator REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

-- 4) The administrator keeps the ADMIN OPTION PostgreSQL 16 gives the creator
--    of a role (needed to rotate passwords); it holds no other app privileges.

\echo 'WellnessZ bootstrap complete: wz_migrator owns wellnessz + schemas; wz_app/wz_bff have CONNECT only (table grants via Flyway).'
