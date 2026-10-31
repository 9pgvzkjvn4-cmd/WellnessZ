create schema if not exists devicecat;
-- =====================================================================================================
-- V23 – E32 Device catalogue and provisioning console: families / brands / models (seeded from the 133-model device inventory by the E32 seed loader), option packs P1–P10, driver-package registry, wizard drafts, configuration versions with four-eyes approval and change history, bindings, certification records, fleet, calibration / QC logs, vendor posture, request-a-model, decommissioning.
-- Base migration created by the platform increment (schema + usage grant). The E32 developer adds the tables, indexes,
-- seed rows and table-level grants below this header (least privilege per table, as in V17–V20). Contract:
-- docs/increment-06/API-Contract-E31-E34.md.
-- =====================================================================================================

grant usage on schema devicecat to ${app_role};
