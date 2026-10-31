-- V21 – Increment 6 platform additions (E31–E34 medical device integration engine): retention classes for device data,
-- device provenance on the E03 result (the engine extends the existing device-result capture instead of duplicating it),
-- module enablement flags and the engine-wide thresholds of the API contract (docs/increment-06/API-Contract-E31-E34.md).
-- Module schemas are created by their own migrations: V22 deviceint (E31), V23 devicecat (E32), V24 devicecap (E33),
-- V25 devicesec (E34). Platform first, as in increments 3–5 (V9, V13, V16), so module migrations may rely on it.

-- ---------- E03 result: device provenance (E31-HLR-14, E33-HLR-02 "visibly marked as device-sourced") ----------
alter table ohrecord.result
    add column device_observation_id  uuid,          -- E31 canonical observation id (idempotency key of the E03.RESULT sink)
    add column capture_method         varchar(20),   -- DEVICE | OCR_VERIFIED | MANUAL_ATTESTED (null = typed in the form)
    add column driver_version         varchar(60),   -- driver package id@version that mapped the value
    add column verified_by            varchar(200),  -- clinician who accepted the device preview
    add column verified_at            timestamptz;
create unique index ux_result_device_observation on ohrecord.result (device_observation_id) where device_observation_id is not null;

-- ---------- retention classes (proposed, pending DPO / CMO approval – E34-HLR-10) ----------
insert into docs.retention_schedule (record_class, retention_years, trigger_event, legal_basis, updated_by, updated_at) values
    ('DEVICE_RAW_PAYLOAD',    40, 'LAST_ENCOUNTER', 'Proposed: raw device payloads, waveforms, PDFs and images of filed observations follow the OH / exposure record they evidence (hash-linked provenance, E31-HLR-04 / -14)', 'migration', now()),
    ('DEVICE_RAW_UNFILED',     1, 'RECEIVED_DATE',  'Proposed: raw payloads never filed – rejected, unmatched and discarded, dead letters after resolution, simulator and test traffic (data minimisation)', 'migration', now()),
    ('DEVICE_CALIBRATION_QC', 40, 'RECORD_DATE',    'Proposed: calibration, daily check and QC-lot records – evidence for the validity of device results (benchmark OSHA 1910.95 / 1910.1020)', 'migration', now()),
    ('DEVICE_CONFIGURATION',  10, 'DECOMMISSIONED', 'Proposed: device configuration versions, certification records, approvals and decommissioning records (IEC 80001-1 / ISO 27001 change evidence)', 'migration', now()),
    ('DEVICE_SECURITY_EVENT',  6, 'EVENT_DATE',     'Proposed: endpoint security events, enrolment / revocation and isolation records (ISO 27001 A.8.15, SIEM retention)', 'migration', now())
on conflict (record_class) do nothing;

-- ---------- configuration (E18) ----------
insert into masterdata.config_entry (config_key, config_value, description, updated_by, updated_at) values
    ('modules.e31.enabled', 'true', 'Medical device integration engine (pipeline, adapters, routing)', 'migration', now()),
    ('modules.e32.enabled', 'true', 'Device catalogue, administration & provisioning console', 'migration', now()),
    ('modules.e33.enabled', 'true', 'Module-embedded device capture', 'migration', now()),
    ('modules.e34.enabled', 'true', 'Endpoint & integration security', 'migration', now()),
    ('device.association.auto-file-threshold', '0.95', 'Association confidence at or above which a result may be routed without manual resolution (E31-HLR-05)', 'migration', now()),
    ('device.association.suggest-threshold', '0.60', 'Association confidence at or above which an unmatched result shows the candidate as a suggestion', 'migration', now()),
    ('device.association.session-window-seconds', '900', 'A result is bound to a listening capture session only if measured within this window after the session opened', 'migration', now()),
    ('device.clock.max-skew-seconds', '120', 'Device clock skew above which the result is flagged (corrected, WARN); above 10x it is held for review', 'migration', now()),
    ('device.capture.session-timeout-minutes', '10', 'Capture sessions without a result expire after this many minutes (E33)', 'migration', now()),
    ('device.unmatched.resolution-sla-hours', '8', 'Unmatched results must be resolved within one working day (KPI, E31-F05)', 'migration', now()),
    ('device.ocr.min-confidence', '0.90', 'OCR fields below this confidence are left empty for the clinician to type (verification is always mandatory)', 'migration', now()),
    ('device.ingest.max-payload-bytes', '52428800', 'Largest accepted device payload (waveform / PDF / DICOM instance, E31-HLR-13)', 'migration', now()),
    ('device.dlq.max-attempts', '5', 'Pipeline retries (exponential back-off) before a message is dead-lettered', 'migration', now()),
    ('device.security.isolation-sla-minutes', '15', 'Device-compromise playbook: isolation within this many minutes (E34-HLR-09)', 'migration', now()),
    ('device.security.revocation-sla-minutes', '5', 'Endpoint revocation effective within this many minutes (E34-F01)', 'migration', now()),
    ('device.security.cert-expiry-warning-days', '30', 'Endpoint certificates expiring within this many days raise CERTIFICATE_EXPIRING (SC-14)', 'migration', now())
on conflict (config_key) do nothing;
