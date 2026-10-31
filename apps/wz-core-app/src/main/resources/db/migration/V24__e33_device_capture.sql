create schema if not exists devicecap;
-- =====================================================================================================
-- V24 – E33 Module-embedded device capture: capture sessions bound to OH forms (state machine), fall-back path records, batch queues, kiosk sessions with consent, employee result summaries.
-- Base migration created by the platform increment (schema + usage grant). The E33 developer adds the tables, indexes,
-- seed rows and table-level grants below this header (least privilege per table, as in V17–V20). Contract:
-- docs/increment-06/API-Contract-E31-E34.md.
-- =====================================================================================================

grant usage on schema devicecap to ${app_role};
