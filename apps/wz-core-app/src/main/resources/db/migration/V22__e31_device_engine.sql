create schema if not exists deviceint;
-- =====================================================================================================
-- V22 – E31 Medical device integration engine: pipeline messages and stages, dead letters, canonical FHIR store (Device, DeviceMetric, Observation, DiagnosticReport, Provenance as validated JSON), raw archive with SHA-256, association and unmatched queue, terminology maps, simulators and golden datasets, connector metrics.
-- Base migration created by the platform increment (schema + usage grant). The E31 developer adds the tables, indexes,
-- seed rows and table-level grants below this header (least privilege per table, as in V17–V20). Contract:
-- docs/increment-06/API-Contract-E31-E34.md.
-- =====================================================================================================

grant usage on schema deviceint to ${app_role};

-- ---------- raw archive (E31-HLR-04): every inbound body / attachment before parsing, AES-GCM (per-object key, HKDF) ----------
create table deviceint.raw_object (
    id               uuid primary key,
    tenant_code      varchar(40)   not null,
    kind             varchar(20)   not null check (kind in ('MESSAGE','PDF','WAVEFORM','IMAGE','DICOM','RAW')),
    media_type       varchar(100)  not null,
    sha256           varchar(64)   not null,
    size_bytes       bigint        not null,
    content          bytea         not null,      -- AES-256-GCM ciphertext (12-byte IV prefix), key = HKDF(master key, object id)
    endpoint_id      varchar(64),
    retention_class  varchar(40)   not null check (retention_class in ('DEVICE_RAW_UNFILED','DEVICE_RAW_PAYLOAD')),
    purge_after      date,
    referenced       boolean       not null default false,
    created_at       timestamptz   not null
);
create index ix_raw_object_unreferenced on deviceint.raw_object (created_at) where referenced = false;

-- ---------- pipeline work table (E31-HLR-01, contract section 8) ----------
create table deviceint.message (
    id                 uuid primary key,
    tenant_code        varchar(40)   not null,
    endpoint_id        varchar(64)   not null,
    site_code          varchar(40),
    device_instance_id varchar(64),
    kind               varchar(20)   not null check (kind in ('HL7V2','FHIR','POCT1A','FILE','BLE','SERIAL','WEBHOOK','DICOMWEB','FALLBACK','SIMULATOR')),
    content_type       varchar(100),
    idempotency_key    varchar(200)  not null,
    gateway_sequence   bigint,
    captured_at        timestamptz,
    edge_clock         timestamptz,
    received_at        timestamptz   not null,
    raw_object_id      uuid          not null references deviceint.raw_object(id),
    sha256             varchar(64)   not null,
    headers            text,                       -- PHI-free transport metadata (JSON: file format, record index, port)
    stage              varchar(20)   not null,
    status             varchar(20)   not null check (status in ('RECEIVED','PROCESSING','RETRY','PROCESSED','DEAD_LETTER','PARKED','DISCARDED')),
    attempts           int           not null default 0,
    next_attempt_at    timestamptz,
    error_code         varchar(80),
    driver_package_id  varchar(120),
    driver_version     varchar(40),
    replayed           boolean       not null default false,
    test_traffic       boolean       not null default false,
    route_test_form    boolean       not null default false,
    processed_at       timestamptz,
    unique (endpoint_id, idempotency_key)
);
create index ix_message_due on deviceint.message (tenant_code, next_attempt_at) where status in ('RECEIVED','RETRY');
create index ix_message_instance on deviceint.message (device_instance_id, received_at desc);
create index ix_message_received on deviceint.message (received_at);

create table deviceint.stage_log (            -- append-only
    id              uuid primary key,
    message_id      uuid          not null,
    observation_id  uuid,
    stage           varchar(20)   not null,
    outcome         varchar(20)   not null,
    code            varchar(80),
    duration_ms     bigint,
    at              timestamptz   not null
);
create index ix_stage_log_message on deviceint.stage_log (message_id, at);

create table deviceint.dead_letter (
    id                 uuid primary key,
    tenant_code        varchar(40)   not null,
    message_id         uuid          not null references deviceint.message(id),
    observation_id     uuid,
    device_instance_id varchar(64),
    stage              varchar(20)   not null,
    code               varchar(80)   not null,
    detail_masked      varchar(500),
    attempts           int           not null,
    first_failed_at    timestamptz   not null,
    last_failed_at     timestamptz   not null,
    status             varchar(20)   not null check (status in ('OPEN','REPLAYED','DISCARDED')),
    resolved_by        varchar(200),
    resolved_at        timestamptz,
    reason             varchar(500)
);
create index ix_dead_letter_open on deviceint.dead_letter (status, stage, last_failed_at desc);

-- ---------- canonical observations (contract section 4) ----------
create table deviceint.observation (
    id                   uuid primary key,          -- UUIDv5(messageId + record index + binding key set): replay never duplicates
    message_id           uuid          not null references deviceint.message(id),
    record_index         int           not null,
    report_id            uuid,
    tenant_code          varchar(40)   not null,
    site_code            varchar(40),
    employee_id          varchar(64),
    case_id              uuid,
    order_ref            varchar(80),
    device_instance_id   varchar(64),
    equipment_device_id  varchar(100),
    category             varchar(20)   not null,
    status               varchar(30)   not null check (status in ('RECEIVED','NORMALISED','ASSOCIATED','UNMATCHED','VALIDATED','PENDING_VERIFICATION','BLOCKED','ROUTED','REJECTED','DEAD_LETTER','ROUTE_PENDING')),
    capture_method       varchar(20)   not null check (capture_method in ('DEVICE','OCR_VERIFIED','MANUAL_ATTESTED')),
    restricted           boolean       not null default false,
    association_method   varchar(30),
    confidence           numeric(4,2),
    session_id           uuid,
    form_code            varchar(80),
    form_version         varchar(10),
    route_target         varchar(40),
    verify_before_post   boolean       not null default true,
    routed_record_type   varchar(40),
    routed_record_id     varchar(80),
    effective_at         timestamptz   not null,
    clock_skew_ms        bigint        not null default 0,
    gateway_sequence     bigint,
    driver_package_id    varchar(120),
    driver_version       varchar(40),
    canonical            text          not null,    -- encrypted (field level), CLINICAL: components, flags, provenance, attachments (JSON)
    bound_fields         text,                      -- encrypted (field level), CLINICAL: form field → value (JSON)
    flags_summary        varchar(1000),             -- gate:outcome:code list (codes only, never values)
    test_traffic         boolean       not null default false,
    verified_by          varchar(200),
    verified_at          timestamptz,
    reject_reason        varchar(30),
    route_attempts       int           not null default 0,
    created_at           timestamptz   not null,
    updated_at           timestamptz   not null,
    version              bigint        not null default 0
);
create index ix_observation_employee on deviceint.observation (employee_id, category, effective_at desc);
create index ix_observation_instance on deviceint.observation (device_instance_id, created_at desc);
create index ix_observation_status on deviceint.observation (status, updated_at);
create index ix_observation_message on deviceint.observation (message_id);

create table deviceint.fhir_resource (
    id              uuid primary key,
    resource_type   varchar(30)   not null check (resource_type in ('Device','DeviceMetric','Observation','DiagnosticReport','Provenance')),
    resource_id     varchar(80)   not null,
    tenant_code     varchar(40)   not null,
    employee_id     varchar(64),
    observation_id  uuid,
    category        varchar(20),
    effective_at    timestamptz,
    status          varchar(20)   not null,
    json            text          not null,     -- encrypted (field level), CLINICAL: validated FHIR R4 JSON
    version         int           not null default 1,
    created_at      timestamptz   not null,
    updated_at      timestamptz   not null,
    unique (resource_type, resource_id)
);
create index ix_fhir_observation on deviceint.fhir_resource (observation_id);

-- ---------- association (E31-HLR-05) ----------
create table deviceint.association_candidate (    -- append-only audit of every candidate considered
    id              uuid primary key,
    observation_id  uuid          not null,
    employee_id     varchar(64)   not null,
    method          varchar(30)   not null,
    confidence      numeric(4,2)  not null,
    chosen          boolean       not null,
    created_at      timestamptz   not null
);
create index ix_assoc_candidate_obs on deviceint.association_candidate (observation_id);

create table deviceint.unmatched_item (
    id              uuid primary key,
    observation_id  uuid          not null unique,
    tenant_code     varchar(40)   not null,
    site_code       varchar(40),
    category        varchar(20)   not null,
    restricted      boolean       not null default false,
    device_instance_id varchar(64),
    received_at     timestamptz   not null,
    sla_due_at      timestamptz   not null,
    reason_code     varchar(40)   not null,       -- below_threshold | ambiguous | association_conflict | no_identity
    suggestions     text,                         -- encrypted (field level), CLINICAL: [{employeeId, displayName, method, confidence}]
    status          varchar(20)   not null check (status in ('OPEN','RESOLVED','DISCARDED')),
    resolved_by     varchar(200),
    resolved_at     timestamptz,
    resolution_code varchar(30),
    note            text,                         -- encrypted (field level)
    version         bigint        not null default 0
);
create index ix_unmatched_open on deviceint.unmatched_item (status, site_code, received_at);

-- ---------- terminology & clinical reference data (E31-F04; versioned, clinical SME sign-off E31-BL-013 / -017) ----------
create table deviceint.terminology_release (
    version      varchar(40)  primary key,
    description  varchar(500) not null,
    active       boolean      not null,
    loaded_at    timestamptz  not null
);
create table deviceint.terminology_map (
    id              uuid primary key,
    release_version varchar(40)   not null references deviceint.terminology_release(version),
    source_system   varchar(20)   not null check (source_system in ('MDC','LOINC','LOCAL')),
    source_code     varchar(80)   not null,
    mdc_code        varchar(80),
    loinc_code      varchar(40),
    snomed_code     varchar(40),
    display         varchar(300)  not null,
    ucum            varchar(40),
    unique (release_version, source_system, source_code)
);
create table deviceint.unit_conversion (
    from_unit  varchar(40)    not null,
    to_unit    varchar(40)    not null,
    factor     numeric(20,10) not null,
    shift      numeric(20,10) not null default 0,     -- value_to = value_from · factor + shift
    primary key (from_unit, to_unit)
);
create table deviceint.reference_range (
    id          uuid primary key,
    loinc_code  varchar(40)   not null,
    sex         varchar(1)    not null check (sex in ('M','F','*')),
    age_min     int           not null,
    age_max     int           not null,
    low         numeric(12,4),
    high        numeric(12,4),
    text        varchar(200)
);
create table deviceint.predicted_equation (       -- GLI-2012 LMS coefficients (Quanjer et al. 2012), per parameter and sex
    equation    varchar(20)   not null,
    parameter   varchar(20)   not null,           -- FEV1 | FVC | FEV1FVC
    sex         varchar(1)    not null check (sex in ('M','F')),
    l_a0        numeric(12,6) not null,           -- L = l_a0 + l_a1·ln(age)
    l_a1        numeric(12,6) not null,
    m_a0        numeric(12,6) not null,           -- ln M = m_a0 + m_a1·ln(height cm) + m_a2·ln(age) + Mspline(age)
    m_a1        numeric(12,6) not null,
    m_a2        numeric(12,6) not null,
    s_a0        numeric(12,6) not null,           -- ln S = s_a0 + s_a1·ln(age) + Sspline(age)
    s_a1        numeric(12,6) not null,
    source      varchar(300)  not null,
    primary key (equation, parameter, sex)
);
create table deviceint.predicted_spline (         -- GLI-2012 age-dependent spline terms (lookup table, linear interpolation)
    equation    varchar(20)   not null,
    parameter   varchar(20)   not null,
    sex         varchar(1)    not null,
    age         numeric(5,2)  not null,
    m_spline    numeric(12,6) not null,
    s_spline    numeric(12,6) not null,
    primary key (equation, parameter, sex, age)
);
create table deviceint.osha_age_correction (      -- OSHA 29 CFR 1910.95 Appendix F, tables F-1 (male) and F-2 (female)
    sex       varchar(1)  not null check (sex in ('M','F')),
    age       int         not null,
    hz1000    int not null,
    hz2000    int not null,
    hz3000    int not null,
    hz4000    int not null,
    hz6000    int not null,
    primary key (sex, age)
);

-- ---------- simulators, golden datasets, gates, tenants, connectors ----------
create table deviceint.golden_dataset (
    dataset_id   varchar(160) primary key,
    family       varchar(20)  not null,
    adapter      varchar(20)  not null,
    package_id   varchar(120) not null,
    kind         varchar(20)  not null check (kind in ('NORMAL','EDGE','MALFORMED','TIME_SKEW','DUPLICATE','PERFORMANCE')),
    resource     varchar(300) not null,
    description  varchar(500) not null
);
create table deviceint.simulator_run (
    id              uuid primary key,
    tenant_code     varchar(40)  not null,
    adapter         varchar(20)  not null,
    dataset_id      varchar(160) not null,
    requested_by    varchar(200) not null,
    messages        int          not null,
    accepted        int          not null,
    failed          int          not null,
    evidence_sha256 varchar(64),
    started_at      timestamptz  not null,
    finished_at     timestamptz
);
create table deviceint.gate_signoff (
    gate_code   varchar(40)  primary key,
    status      varchar(20)  not null check (status in ('PENDING','APPROVED')),
    signed_by   varchar(200),
    signed_at   timestamptz
);
create table deviceint.tenant_quota (
    code                varchar(40)  primary key,
    messages_per_minute int          not null,
    max_connectors      int          not null,
    catalogue_scope     varchar(40)  not null,
    updated_by          varchar(200) not null,
    updated_at          timestamptz  not null
);
create table deviceint.connector_state (           -- heartbeat / last seen per device instance (E31-HLR-11)
    device_instance_id varchar(64) primary key,
    tenant_code        varchar(40)  not null,
    endpoint_id        varchar(64),
    site_code          varchar(40),
    option_code        varchar(4),
    last_seen_at       timestamptz,
    device_state       varchar(20),
    endpoint_status    varchar(20)  not null default 'ACTIVE',
    updated_at         timestamptz  not null
);
create table deviceint.gateway_state (
    endpoint_id        varchar(64) primary key,
    tenant_code        varchar(40)  not null,
    site_code          varchar(40),
    gateway_version    varchar(40),
    config_version     int,
    ntp_offset_ms      bigint,
    queue_depth        int,
    queue_capacity_pct int,
    oldest_captured_at timestamptz,
    disk_free_pct      int,
    hardening_status   varchar(10),
    last_heartbeat_at  timestamptz  not null
);
create table deviceint.gateway_command (
    id                 uuid primary key,
    endpoint_id        varchar(64)  not null,
    type               varchar(30)  not null check (type in ('RELOAD_CONFIG','RESEND_LAST','ROTATE_TOKEN','ISOLATE','PUSH_WORKLIST','START_ACQUISITION')),
    device_instance_id varchar(64),
    payload            text,
    status             varchar(20)  not null check (status in ('PENDING','SENT','DONE','FAILED')),
    detail             varchar(500),
    created_by         varchar(200) not null,
    created_at         timestamptz  not null,
    sent_at            timestamptz,
    completed_at       timestamptz
);
create index ix_gateway_command_pending on deviceint.gateway_command (endpoint_id, status);
create table deviceint.webhook_nonce (             -- replay protection for vendor webhooks (window 300 s)
    connector_id  varchar(64)  not null,
    nonce         varchar(140) not null,
    received_at   timestamptz  not null,
    primary key (connector_id, nonce)
);
create table deviceint.replay_job (
    id                 uuid primary key,
    requested_by       varchar(200) not null,
    from_at            timestamptz  not null,
    to_at              timestamptz  not null,
    device_instance_id varchar(64),
    stage_from         varchar(20)  not null,
    messages           int          not null,
    created_at         timestamptz  not null
);

create table deviceint.driver_sign_request (       -- four-eyes signing of driver packages (INTEGRATION_TESTER requests, DEVICE_APPROVER approves)
    id               uuid primary key,
    package_id       varchar(120) not null,
    version          varchar(40)  not null,
    canonical_sha256 varchar(64)  not null,
    package_json     text         not null,       -- the package as submitted (no secrets: refused by the secret scan)
    requested_by     varchar(200) not null,
    requested_at     timestamptz  not null,
    status           varchar(20)  not null check (status in ('REQUESTED','APPROVED','REJECTED')),
    decided_by       varchar(200),
    decided_at       timestamptz,
    key_id           varchar(80),
    signature        text
);

-- ---------- reference data (published codes; confirm against the loaded LOINC / RTMMS releases, E31-BL-013) ----------
insert into deviceint.terminology_release (version, description, active, loaded_at) values
    ('WZ-DEV-2026.10', 'Device terminology release 2026-10: IEEE 11073-10101 (RTMMS) terms used by the certified drivers, LOINC codes, UCUM units, WellnessZ LOCAL codes', true, now());

insert into deviceint.terminology_map (id, release_version, source_system, source_code, mdc_code, loinc_code, snomed_code, display, ucum)
select gen_random_uuid(), 'WZ-DEV-2026.10', t.sys, t.code, t.mdc, t.loinc, t.snomed, t.display, t.ucum
from (values
    ('MDC', 'MDC_PRESS_BLD_NONINV_SYS', 'MDC_PRESS_BLD_NONINV_SYS', '8480-6', '271649006', 'Systolic blood pressure', 'mm[Hg]'),
    ('MDC', 'MDC_PRESS_BLD_NONINV_DIA', 'MDC_PRESS_BLD_NONINV_DIA', '8462-4', '271650006', 'Diastolic blood pressure', 'mm[Hg]'),
    ('MDC', 'MDC_PRESS_BLD_NONINV_MEAN', 'MDC_PRESS_BLD_NONINV_MEAN', '8478-0', null, 'Mean blood pressure', 'mm[Hg]'),
    ('MDC', 'MDC_PULS_RATE_NON_INV', 'MDC_PULS_RATE_NON_INV', '8867-4', '364075005', 'Heart rate', '/min'),
    ('MDC', 'MDC_PULS_OXIM_PULS_RATE', 'MDC_PULS_OXIM_PULS_RATE', '8889-8', null, 'Heart rate by pulse oximetry', '/min'),
    ('MDC', 'MDC_PULS_OXIM_SAT_O2', 'MDC_PULS_OXIM_SAT_O2', '59408-5', '431314004', 'Oxygen saturation by pulse oximetry', '%'),
    ('MDC', 'MDC_TEMP_BODY', 'MDC_TEMP_BODY', '8310-5', '386725007', 'Body temperature', 'Cel'),
    ('MDC', 'MDC_MASS_BODY_ACTUAL', 'MDC_MASS_BODY_ACTUAL', '29463-7', '27113001', 'Body weight', 'kg'),
    ('MDC', 'MDC_LEN_BODY_ACTUAL', 'MDC_LEN_BODY_ACTUAL', '8302-2', '50373000', 'Body height', 'cm'),
    ('MDC', 'MDC_RATIO_MASS_BODY_LEN_SQ', 'MDC_RATIO_MASS_BODY_LEN_SQ', '39156-5', '60621009', 'Body mass index', 'kg/m2'),
    ('MDC', 'MDC_BODY_FAT', 'MDC_BODY_FAT', '41982-0', null, 'Percentage of body fat', '%'),
    ('MDC', 'MDC_VOL_AWAY_EXP_FORCED_1S', 'MDC_VOL_AWAY_EXP_FORCED_1S', '20150-9', null, 'FEV1', 'L'),
    ('MDC', 'MDC_VOL_AWAY_EXP_FORCED_CAPACITY', 'MDC_VOL_AWAY_EXP_FORCED_CAPACITY', '19870-5', null, 'FVC', 'L'),
    ('MDC', 'MDC_FLOW_AWAY_EXP_FORCED_PEAK', 'MDC_FLOW_AWAY_EXP_FORCED_PEAK', '33452-4', null, 'Peak expiratory flow', 'L/s'),
    ('MDC', 'MDC_ECG_HEART_RATE', 'MDC_ECG_HEART_RATE', '8867-4', null, 'Heart rate', '/min'),
    ('MDC', 'MDC_ECG_TIME_PD_PR', 'MDC_ECG_TIME_PD_PR', '8625-6', null, 'PR interval', 'ms'),
    ('MDC', 'MDC_ECG_TIME_PD_QRS', 'MDC_ECG_TIME_PD_QRS', '8633-0', null, 'QRS duration', 'ms'),
    ('MDC', 'MDC_ECG_TIME_PD_QTc', 'MDC_ECG_TIME_PD_QTc', '8636-3', null, 'QTc interval', 'ms'),
    ('LOINC', '8480-6', 'MDC_PRESS_BLD_NONINV_SYS', '8480-6', '271649006', 'Systolic blood pressure', 'mm[Hg]'),
    ('LOINC', '8462-4', 'MDC_PRESS_BLD_NONINV_DIA', '8462-4', '271650006', 'Diastolic blood pressure', 'mm[Hg]'),
    ('LOINC', '8478-0', 'MDC_PRESS_BLD_NONINV_MEAN', '8478-0', null, 'Mean blood pressure', 'mm[Hg]'),
    ('LOINC', '8867-4', 'MDC_PULS_RATE_NON_INV', '8867-4', '364075005', 'Heart rate', '/min'),
    ('LOINC', '59408-5', 'MDC_PULS_OXIM_SAT_O2', '59408-5', '431314004', 'Oxygen saturation by pulse oximetry', '%'),
    ('LOINC', '8310-5', 'MDC_TEMP_BODY', '8310-5', '386725007', 'Body temperature', 'Cel'),
    ('LOINC', '29463-7', 'MDC_MASS_BODY_ACTUAL', '29463-7', '27113001', 'Body weight', 'kg'),
    ('LOINC', '8302-2', 'MDC_LEN_BODY_ACTUAL', '8302-2', '50373000', 'Body height', 'cm'),
    ('LOINC', '39156-5', 'MDC_RATIO_MASS_BODY_LEN_SQ', '39156-5', '60621009', 'Body mass index', 'kg/m2'),
    ('LOINC', '41982-0', 'MDC_BODY_FAT', '41982-0', null, 'Percentage of body fat', '%'),
    ('LOINC', '20150-9', 'MDC_VOL_AWAY_EXP_FORCED_1S', '20150-9', null, 'FEV1', 'L'),
    ('LOINC', '19870-5', 'MDC_VOL_AWAY_EXP_FORCED_CAPACITY', '19870-5', null, 'FVC', 'L'),
    ('LOINC', '33452-4', 'MDC_FLOW_AWAY_EXP_FORCED_PEAK', '33452-4', null, 'Peak expiratory flow', 'L/s'),
    ('LOINC', '8625-6', 'MDC_ECG_TIME_PD_PR', '8625-6', null, 'PR interval', 'ms'),
    ('LOINC', '8633-0', 'MDC_ECG_TIME_PD_QRS', '8633-0', null, 'QRS duration', 'ms'),
    ('LOINC', '8636-3', 'MDC_ECG_TIME_PD_QTc', '8636-3', null, 'QTc interval', 'ms'),
    ('LOINC', '19926-5', null, '19926-5', null, 'FEV1/FVC', '%'),
    ('LOINC', '19927-3', null, '19927-3', null, 'FEF 25-75%', 'L/s'),
    ('LOINC', '20149-1', null, '20149-1', null, 'FEV1 predicted', 'L'),
    ('LOINC', '19869-7', null, '19869-7', null, 'FVC predicted', 'L'),
    ('LOINC', '8358-4', null, '8358-4', null, 'Blood pressure device cuff size', null),
    ('LOINC', '18844-1', null, '18844-1', null, 'ECG interpretation (device, unverified)', null),
    ('LOINC', '75367-0', null, '75367-0', null, 'Alcohol [Mass/volume] in Breath', 'g/(210.L)'),
    ('LOINC', '4548-4', null, '4548-4', null, 'Hemoglobin A1c/Hemoglobin.total in Blood', '%'),
    ('LOINC', '2345-7', null, '2345-7', null, 'Glucose [Mass/volume] in Serum or Plasma', 'mg/dL'),
    ('LOINC', '2093-3', null, '2093-3', null, 'Cholesterol [Mass/volume] in Serum or Plasma', 'mg/dL'),
    ('LOINC', '5671-3', null, '5671-3', null, 'Lead [Mass/volume] in Blood', 'ug/dL'),
    ('LOINC', '55423-8', null, '55423-8', null, 'Number of steps', '{steps}'),
    ('LOINC', '93832-4', null, '93832-4', null, 'Sleep duration', 'min'),
    ('LOINC', '79880-1', null, '79880-1', null, 'Visual acuity right eye', null),
    ('LOINC', '79881-9', null, '79881-9', null, 'Visual acuity left eye', null),
    ('LOINC', '98498-9', null, '98498-9', null, 'Visual acuity both eyes', null),
    ('LOINC', '28539-6', null, '28539-6', null, 'Audiogram study', null),
    ('LOINC', '89016-0', null, '89016-0', null, 'Hearing threshold 500 Hz left ear (air)', 'dB[HL]'),
    ('LOINC', '89017-8', null, '89017-8', null, 'Hearing threshold 1000 Hz left ear (air)', 'dB[HL]'),
    ('LOINC', '89018-6', null, '89018-6', null, 'Hearing threshold 2000 Hz left ear (air)', 'dB[HL]'),
    ('LOINC', '89020-2', null, '89020-2', null, 'Hearing threshold 3000 Hz left ear (air)', 'dB[HL]'),
    ('LOINC', '89019-4', null, '89019-4', null, 'Hearing threshold 4000 Hz left ear (air)', 'dB[HL]'),
    ('LOINC', '89021-0', null, '89021-0', null, 'Hearing threshold 6000 Hz left ear (air)', 'dB[HL]'),
    ('LOINC', '89022-8', null, '89022-8', null, 'Hearing threshold 8000 Hz left ear (air)', 'dB[HL]'),
    ('LOINC', '89023-6', null, '89023-6', null, 'Hearing threshold 500 Hz right ear (air)', 'dB[HL]'),
    ('LOINC', '89024-4', null, '89024-4', null, 'Hearing threshold 1000 Hz right ear (air)', 'dB[HL]'),
    ('LOINC', '89025-1', null, '89025-1', null, 'Hearing threshold 2000 Hz right ear (air)', 'dB[HL]'),
    ('LOINC', '89026-9', null, '89026-9', null, 'Hearing threshold 3000 Hz right ear (air)', 'dB[HL]'),
    ('LOINC', '89027-7', null, '89027-7', null, 'Hearing threshold 4000 Hz right ear (air)', 'dB[HL]'),
    ('LOINC', '89028-5', null, '89028-5', null, 'Hearing threshold 6000 Hz right ear (air)', 'dB[HL]'),
    ('LOINC', '89029-3', null, '89029-3', null, 'Hearing threshold 8000 Hz right ear (air)', 'dB[HL]'),
    ('LOCAL', 'LOCAL:ATS_GRADE', null, 'LOCAL:ATS_GRADE', null, 'ATS/ERS session grade', null),
    ('LOCAL', 'LOCAL:N_ACCEPTABLE', null, 'LOCAL:N_ACCEPTABLE', null, 'Acceptable manoeuvres', '1'),
    ('LOCAL', 'LOCAL:FEV1_TRIALS', null, 'LOCAL:FEV1_TRIALS', null, 'FEV1 of the acceptable manoeuvres', 'L'),
    ('LOCAL', 'LOCAL:FVC_TRIALS', null, 'LOCAL:FVC_TRIALS', null, 'FVC of the acceptable manoeuvres', 'L'),
    ('LOCAL', 'LOCAL:BREATH_TEST_TYPE', null, 'LOCAL:BREATH_TEST_TYPE', null, 'Breath test sequence', null),
    ('LOCAL', 'LOCAL:BREATH_TEST_NUMBER', null, 'LOCAL:BREATH_TEST_NUMBER', null, 'Breath test number', null),
    ('LOCAL', 'LOCAL:LAST_CALIBRATION', null, 'LOCAL:LAST_CALIBRATION', null, 'Last device calibration date', null),
    ('LOCAL', 'LOCAL:DEVICE_SERIAL', null, 'LOCAL:DEVICE_SERIAL', null, 'Device serial number', null),
    ('LOCAL', 'LOCAL:FIT_FACTOR_OVERALL', null, 'LOCAL:FIT_FACTOR_OVERALL', null, 'Respirator overall fit factor', '1'),
    ('LOCAL', 'LOCAL:FIT_PASS', null, 'LOCAL:FIT_PASS', null, 'Respirator fit test passed', null),
    ('LOCAL', 'LOCAL:FIT_PROTOCOL', null, 'LOCAL:FIT_PROTOCOL', null, 'Fit test protocol', null),
    ('LOCAL', 'LOCAL:EXERCISES', null, 'LOCAL:EXERCISES', null, 'Fit test exercises', null),
    ('LOCAL', 'LOCAL:RESPIRATOR', null, 'LOCAL:RESPIRATOR', null, 'Respirator model', null),
    ('LOCAL', 'LOCAL:RESPIRATOR_SIZE', null, 'LOCAL:RESPIRATOR_SIZE', null, 'Respirator size', null),
    ('LOCAL', 'LOCAL:DICOM_STUDY_UID', null, 'LOCAL:DICOM_STUDY_UID', null, 'Imaging study', null),
    ('LOCAL', 'LOCAL:DICOM_MODALITY', null, 'LOCAL:DICOM_MODALITY', null, 'Modality', null),
    ('LOCAL', 'LOCAL:DICOM_BODY_PART', null, 'LOCAL:DICOM_BODY_PART', null, 'Body part examined', null),
    ('LOCAL', 'LOCAL:AMBIENT_NOISE_FLAG', null, 'LOCAL:AMBIENT_NOISE_FLAG', null, 'Ambient noise exceeded during audiometry', null),
    ('LOCAL', 'LOCAL:AUDIO_RETEST_1000_L', null, 'LOCAL:AUDIO_RETEST_1000_L', null, 'Hearing threshold 1000 Hz left ear re-test', 'dB[HL]'),
    ('LOCAL', 'LOCAL:AUDIO_RETEST_1000_R', null, 'LOCAL:AUDIO_RETEST_1000_R', null, 'Hearing threshold 1000 Hz right ear re-test', 'dB[HL]'),
    ('LOCAL', 'LOCAL:TRANSDUCER', null, 'LOCAL:TRANSDUCER', null, 'Audiometric transducer', null),
    ('LOCAL', 'LOCAL:OPERATOR', null, 'LOCAL:OPERATOR', null, 'Device operator', null),
    ('LOCAL', 'LOCAL:QC_LOT', null, 'LOCAL:QC_LOT', null, 'Reagent / QC lot number', null),
    ('LOCAL', 'LOCAL:NOISE_LEX8H', null, 'LOCAL:NOISE_LEX8H', null, 'Personal noise exposure LEX,8h', 'dB'),
    ('LOCAL', 'LOCAL:CORRECTED', null, 'LOCAL:CORRECTED', null, 'Vision tested with correction', null),
    ('LOCAL', 'LOCAL:CUFF_SIZE_CODE', null, 'LOCAL:CUFF_SIZE_CODE', null, 'Cuff size code', null),
    ('LOCAL', 'LOCAL:ARM_CIRCUMFERENCE', null, 'LOCAL:ARM_CIRCUMFERENCE', null, 'Arm circumference', 'cm'),
    ('LOCAL', 'LOCAL:AGE_YEARS', null, 'LOCAL:AGE_YEARS', null, 'Age at test', 'a'),
    ('LOCAL', 'LOCAL:SEX', null, 'LOCAL:SEX', null, 'Sex used for predicted values', null),
    ('LOCAL', 'LOCAL:FEV1_LLN', null, 'LOCAL:FEV1_LLN', null, 'FEV1 lower limit of normal (GLI-2012)', 'L'),
    ('LOCAL', 'LOCAL:FVC_LLN', null, 'LOCAL:FVC_LLN', null, 'FVC lower limit of normal (GLI-2012)', 'L'),
    ('LOCAL', 'LOCAL:RATIO_LLN', null, 'LOCAL:RATIO_LLN', null, 'FEV1/FVC lower limit of normal (GLI-2012)', '%'),
    ('LOCAL', 'LOCAL:FEV1_PCT_PRED', null, 'LOCAL:FEV1_PCT_PRED', null, 'FEV1 % predicted', '%'),
    ('LOCAL', 'LOCAL:FVC_PCT_PRED', null, 'LOCAL:FVC_PCT_PRED', null, 'FVC % predicted', '%'),
    ('LOCAL', 'LOCAL:FEV1_Z', null, 'LOCAL:FEV1_Z', null, 'FEV1 z-score (GLI-2012)', '1'),
    ('LOCAL', 'LOCAL:BASAL_METABOLISM', null, 'LOCAL:BASAL_METABOLISM', null, 'Basal metabolism', 'kJ'),
    ('LOCAL', 'LOCAL:IMPEDANCE', null, 'LOCAL:IMPEDANCE', null, 'Body impedance', 'Ohm'),
    ('LOCAL', 'LOCAL:MUSCLE_PCT', null, 'LOCAL:MUSCLE_PCT', null, 'Muscle percentage', '%'),
    ('LOCAL', 'LOCAL:PULSE_AMPLITUDE', null, 'LOCAL:PULSE_AMPLITUDE', null, 'Pulse amplitude index', '%'),
    ('LOCAL', 'LOCAL:TEMP_SITE', null, 'LOCAL:TEMP_SITE', null, 'Temperature measurement site', null),
    ('LOCAL', 'LOCAL:ECG_CLASS', null, 'LOCAL:ECG_CLASS', null, 'ECG interpretation class (device, unverified)', null),
    ('LOCAL', 'LOCAL:SOFT_LEAN_MASS', null, 'LOCAL:SOFT_LEAN_MASS', null, 'Soft lean mass', 'kg'),
    ('LOCAL', 'LOCAL:BODY_WATER_MASS', null, 'LOCAL:BODY_WATER_MASS', null, 'Body water mass', 'kg')
) as t(sys, code, mdc, loinc, snomed, display, ucum);

insert into deviceint.unit_conversion (from_unit, to_unit, factor, shift) values
    ('kPa', 'mm[Hg]', 7.5006168, 0),
    ('mm[Hg]', 'kPa', 0.1333224, 0),
    ('[degF]', 'Cel', 0.5555555556, -17.7777777778),
    ('lb', 'kg', 0.45359237, 0),
    ('[lb_av]', 'kg', 0.45359237, 0),
    ('g', 'kg', 0.001, 0),
    ('m', 'cm', 100, 0),
    ('[in_i]', 'cm', 2.54, 0),
    ('mL', 'L', 0.001, 0),
    ('L/min', 'L/s', 0.0166666667, 0),
    ('mg/dL', 'mmol/L', 0.0555, 0),
    ('mmol/mol', '%', 0.09148, 2.152),
    ('s', 'ms', 1000, 0),
    ('1', '%', 100, 0),
    ('%', '1', 0.01, 0);

insert into deviceint.reference_range (id, loinc_code, sex, age_min, age_max, low, high, text)
select gen_random_uuid(), r.loinc, r.sex, r.amin, r.amax, r.low, r.high, r.txt
from (values
    ('8480-6', '*', 18, 120, 90.0, 139.0, 'Systolic 90–139 mm[Hg]'),
    ('8462-4', '*', 18, 120, 60.0, 89.0, 'Diastolic 60–89 mm[Hg]'),
    ('8867-4', '*', 18, 120, 50.0, 100.0, 'Heart rate 50–100 /min'),
    ('59408-5', '*', 18, 120, 94.0, 100.0, 'SpO2 94–100 %'),
    ('8310-5', '*', 18, 120, 35.5, 37.9, 'Body temperature 35.5–37.9 Cel'),
    ('4548-4', '*', 18, 120, null, 6.0, 'HbA1c below 6.0 %'),
    ('5671-3', '*', 18, 120, null, 5.0, 'Blood lead below 5 ug/dL'),
    ('39156-5', '*', 18, 120, 18.5, 24.9, 'BMI 18.5–24.9 kg/m2'),
    ('75367-0', '*', 16, 120, null, 0.019, 'Breath alcohol below 0.020 g/210 L')
) as r(loinc, sex, amin, amax, low, high, txt);

-- GLI-2012 all-age spirometry equations, Caucasian reference population (Quanjer PH et al., Eur Respir J 2012;40:1324-43),
-- LMS method. The coefficients and the coarse spline lookup below must be replaced by / verified against the published GLI lookup
-- tables before clinical release (E31-BL-013 clinical sign-off); the engine reads them from these tables only.
insert into deviceint.predicted_equation (equation, parameter, sex, l_a0, l_a1, m_a0, m_a1, m_a2, s_a0, s_a1, source) values
    ('GLI_2012', 'FEV1',    'M', 0.8866,  0.0850, -10.3420, 2.2196,  0.0574, -2.3268, 0.0798, 'GLI-2012 Caucasian male FEV1 (to be verified, E31-BL-013)'),
    ('GLI_2012', 'FEV1',    'F', 1.1540,  0.0000,  -9.6987, 2.1211, -0.0270, -2.3765, 0.0972, 'GLI-2012 Caucasian female FEV1 (to be verified, E31-BL-013)'),
    ('GLI_2012', 'FVC',     'M', 0.9481,  0.0000, -11.2281, 2.4135,  0.0865, -2.2963, 0.0718, 'GLI-2012 Caucasian male FVC (to be verified, E31-BL-013)'),
    ('GLI_2012', 'FVC',     'F', 0.8236,  0.0000, -10.4030, 2.2633,  0.0234, -2.3549, 0.1017, 'GLI-2012 Caucasian female FVC (to be verified, E31-BL-013)'),
    ('GLI_2012', 'FEV1FVC', 'M', 3.8243, -0.3328,   0.6497, -0.1000, -0.1000, -2.9387, 0.1066, 'GLI-2012 Caucasian male FEV1/FVC (to be verified, E31-BL-013)'),
    ('GLI_2012', 'FEV1FVC', 'F', 6.6490, -0.9920,   0.6688, -0.1000, -0.1000, -3.1734, 0.1306, 'GLI-2012 Caucasian female FEV1/FVC (to be verified, E31-BL-013)');

insert into deviceint.predicted_spline (equation, parameter, sex, age, m_spline, s_spline) values
    ('GLI_2012', 'FEV1', 'M', 18, -0.020, 0.010),
    ('GLI_2012', 'FEV1', 'M', 25, 0.030, 0.008),
    ('GLI_2012', 'FEV1', 'M', 40, 0.020, 0.005),
    ('GLI_2012', 'FEV1', 'M', 60, -0.070, 0.003),
    ('GLI_2012', 'FEV1', 'M', 80, -0.190, 0.000),
    ('GLI_2012', 'FEV1', 'M', 95, -0.270, 0.000),
    ('GLI_2012', 'FEV1', 'F', 18, -0.010, 0.010),
    ('GLI_2012', 'FEV1', 'F', 25, 0.040, 0.008),
    ('GLI_2012', 'FEV1', 'F', 40, 0.030, 0.005),
    ('GLI_2012', 'FEV1', 'F', 60, -0.060, 0.003),
    ('GLI_2012', 'FEV1', 'F', 80, -0.180, 0.000),
    ('GLI_2012', 'FEV1', 'F', 95, -0.260, 0.000),
    ('GLI_2012', 'FVC', 'M', 18, -0.030, 0.010),
    ('GLI_2012', 'FVC', 'M', 25, 0.020, 0.008),
    ('GLI_2012', 'FVC', 'M', 40, 0.020, 0.005),
    ('GLI_2012', 'FVC', 'M', 60, -0.040, 0.003),
    ('GLI_2012', 'FVC', 'M', 80, -0.120, 0.000),
    ('GLI_2012', 'FVC', 'M', 95, -0.180, 0.000),
    ('GLI_2012', 'FVC', 'F', 18, -0.020, 0.010),
    ('GLI_2012', 'FVC', 'F', 25, 0.030, 0.008),
    ('GLI_2012', 'FVC', 'F', 40, 0.030, 0.005),
    ('GLI_2012', 'FVC', 'F', 60, -0.030, 0.003),
    ('GLI_2012', 'FVC', 'F', 80, -0.110, 0.000),
    ('GLI_2012', 'FVC', 'F', 95, -0.170, 0.000),
    ('GLI_2012', 'FEV1FVC', 'M', 18, 0.000, 0.010),
    ('GLI_2012', 'FEV1FVC', 'M', 25, 0.000, 0.008),
    ('GLI_2012', 'FEV1FVC', 'M', 40, 0.000, 0.005),
    ('GLI_2012', 'FEV1FVC', 'M', 60, 0.000, 0.003),
    ('GLI_2012', 'FEV1FVC', 'M', 80, 0.000, 0.000),
    ('GLI_2012', 'FEV1FVC', 'M', 95, 0.000, 0.000),
    ('GLI_2012', 'FEV1FVC', 'F', 18, 0.000, 0.010),
    ('GLI_2012', 'FEV1FVC', 'F', 25, 0.000, 0.008),
    ('GLI_2012', 'FEV1FVC', 'F', 40, 0.000, 0.005),
    ('GLI_2012', 'FEV1FVC', 'F', 60, 0.000, 0.003),
    ('GLI_2012', 'FEV1FVC', 'F', 80, 0.000, 0.000),
    ('GLI_2012', 'FEV1FVC', 'F', 95, 0.000, 0.000);

insert into deviceint.osha_age_correction (sex, age, hz1000, hz2000, hz3000, hz4000, hz6000) values
    ('M',20,5,3,4,5,8),('M',21,5,3,4,5,8),('M',22,5,3,4,5,8),('M',23,5,3,4,6,9),('M',24,5,3,5,6,9),('M',25,5,3,5,7,10),
    ('M',26,5,4,5,7,10),('M',27,5,4,6,7,11),('M',28,6,4,6,8,11),('M',29,6,4,6,8,12),('M',30,6,4,6,9,12),('M',31,6,4,7,9,13),
    ('M',32,6,5,7,10,14),('M',33,6,5,7,10,14),('M',34,6,5,8,11,15),('M',35,7,5,8,11,15),('M',36,7,5,9,12,16),('M',37,7,6,9,12,17),
    ('M',38,7,6,9,13,17),('M',39,7,6,10,14,18),('M',40,7,6,10,14,19),('M',41,7,6,10,14,20),('M',42,8,7,11,16,20),('M',43,8,7,12,16,21),
    ('M',44,8,7,12,17,22),('M',45,8,7,13,18,23),('M',46,8,8,13,19,24),('M',47,8,8,14,19,24),('M',48,9,8,14,20,25),('M',49,9,9,15,21,26),
    ('M',50,9,9,16,22,27),('M',51,9,9,16,23,28),('M',52,9,10,17,24,29),('M',53,9,10,18,25,30),('M',54,10,10,18,26,31),('M',55,10,11,19,27,32),
    ('M',56,10,11,20,28,34),('M',57,10,11,21,29,35),('M',58,10,12,22,31,36),('M',59,11,12,22,32,37),('M',60,11,13,23,33,38),
    ('F',20,7,4,3,3,6),('F',21,7,4,4,3,6),('F',22,7,4,4,4,6),('F',23,7,5,4,4,7),('F',24,7,5,4,4,7),('F',25,8,5,4,4,7),
    ('F',26,8,5,5,4,8),('F',27,8,5,5,5,8),('F',28,8,5,5,5,8),('F',29,8,5,5,5,9),('F',30,8,6,5,5,9),('F',31,8,6,6,5,9),
    ('F',32,9,6,6,6,10),('F',33,9,6,6,6,10),('F',34,9,6,6,6,10),('F',35,9,6,7,7,11),('F',36,9,7,7,7,11),('F',37,9,7,7,7,12),
    ('F',38,10,7,7,7,12),('F',39,10,7,8,8,12),('F',40,10,7,8,8,13),('F',41,10,8,8,8,13),('F',42,10,8,9,9,13),('F',43,11,8,9,9,14),
    ('F',44,11,8,9,9,14),('F',45,11,8,10,10,15),('F',46,11,9,10,10,15),('F',47,11,9,10,11,16),('F',48,12,9,11,11,16),('F',49,12,9,11,11,16),
    ('F',50,12,10,11,12,17),('F',51,12,10,12,12,17),('F',52,12,10,12,13,18),('F',53,13,10,13,13,18),('F',54,13,11,13,14,19),('F',55,13,11,14,14,19),
    ('F',56,13,11,14,15,20),('F',57,13,11,15,15,20),('F',58,14,12,15,16,21),('F',59,14,12,16,16,21),('F',60,14,12,16,17,22);

insert into deviceint.gate_signoff (gate_code, status) values
    ('CALIBRATION','PENDING'),('QC_DAILY','PENDING'),('QC_LOT','PENDING'),('ATS_ERS_GRADE','PENDING'),('PREDICTED_VALUES','PENDING'),
    ('AUDIO_TEST_RETEST','PENDING'),('AUDIO_AMBIENT_NOISE','PENDING'),('BP_CUFF_VALIDATION','PENDING'),('FIT_EXERCISES_COMPLETE','PENDING'),
    ('PLAUSIBILITY','PENDING'),('CLOCK_SKEW','PENDING'),('CONSENT','PENDING'),('ATTESTATION','PENDING'),('OCR_CONFIDENCE','PENDING'),
    ('VERIFY_BEFORE_POST','PENDING');

insert into deviceint.tenant_quota (code, messages_per_minute, max_connectors, catalogue_scope, updated_by, updated_at) values
    ('GROUP', 6000, 1000, 'GROUP', 'migration', now());

insert into masterdata.config_entry (config_key, config_value, description, updated_by, updated_at) values
    ('device.cloud.allowed-hosts', '', 'Hosts the engine may call for P6 vendor-cloud polling (comma-separated; empty = no outbound call)', 'migration', now()),
    ('device.pipeline.inline', 'true', 'Process an accepted message immediately in the request thread (the workers retry); false = workers only', 'migration', now())
on conflict (config_key) do nothing;

-- ---------- least-privilege grants (append-only evidence tables get no update) ----------
grant select, insert, update on deviceint.raw_object to ${app_role};
grant delete on deviceint.raw_object to ${app_role};                    -- purge of unreferenced attachments after 24 h
grant select, insert, update on deviceint.message to ${app_role};
grant select, insert on deviceint.stage_log to ${app_role};
grant select, insert, update on deviceint.dead_letter to ${app_role};
grant select, insert, update on deviceint.observation to ${app_role};
grant select, insert, update on deviceint.fhir_resource to ${app_role};
grant select, insert on deviceint.association_candidate to ${app_role};
grant select, insert, update on deviceint.unmatched_item to ${app_role};
grant select on deviceint.terminology_release, deviceint.terminology_map, deviceint.unit_conversion, deviceint.reference_range,
    deviceint.predicted_equation, deviceint.predicted_spline, deviceint.osha_age_correction to ${app_role};
grant select, insert, update on deviceint.golden_dataset to ${app_role};
grant select, insert, update on deviceint.simulator_run to ${app_role};
grant select, update on deviceint.gate_signoff to ${app_role};
grant select, insert, update on deviceint.tenant_quota to ${app_role};
grant select, insert, update on deviceint.connector_state to ${app_role};
grant select, insert, update on deviceint.gateway_state to ${app_role};
grant select, insert, update on deviceint.gateway_command to ${app_role};
grant select, insert, delete on deviceint.webhook_nonce to ${app_role};
grant select, insert on deviceint.replay_job to ${app_role};
grant select, insert, update on deviceint.driver_sign_request to ${app_role};
