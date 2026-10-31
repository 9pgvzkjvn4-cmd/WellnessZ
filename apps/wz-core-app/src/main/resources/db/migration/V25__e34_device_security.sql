create schema if not exists devicesec;
-- =====================================================================================================
-- V25 – E34 Endpoint and integration security: endpoint identities (certificate thumbprints, hashed per-endpoint tokens), enrolment tokens, zones and conduits, package-signing keys, connector secrets (encrypted), append-only hash-chained security events, anomaly rules and alerts, isolation records, vendor posture exceptions, security test-pack runs.
-- Base migration created by the platform increment (schema + usage grant). The E34 developer adds the tables, indexes,
-- seed rows and table-level grants below this header (least privilege per table, as in V17–V20). Contract:
-- docs/increment-06/API-Contract-E31-E34.md.
-- =====================================================================================================

grant usage on schema devicesec to ${app_role};
