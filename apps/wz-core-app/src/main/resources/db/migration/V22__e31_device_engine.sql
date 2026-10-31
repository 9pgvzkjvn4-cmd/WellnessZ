create schema if not exists deviceint;
-- =====================================================================================================
-- V22 – E31 Medical device integration engine: pipeline messages and stages, dead letters, canonical FHIR store (Device, DeviceMetric, Observation, DiagnosticReport, Provenance as validated JSON), raw archive with SHA-256, association and unmatched queue, terminology maps, simulators and golden datasets, connector metrics.
-- Base migration created by the platform increment (schema + usage grant). The E31 developer adds the tables, indexes,
-- seed rows and table-level grants below this header (least privilege per table, as in V17–V20). Contract:
-- docs/increment-06/API-Contract-E31-E34.md.
-- =====================================================================================================

grant usage on schema deviceint to ${app_role};
