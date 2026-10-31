create schema if not exists devicecap;
-- =====================================================================================================
-- V24 – E33 Module-embedded device capture: capture sessions bound to OH forms (state machine), fall-back path records, batch queues, kiosk sessions with consent, employee result summaries.
-- Base migration created by the platform increment (schema + usage grant). The E33 developer adds the tables, indexes,
-- seed rows and table-level grants below this header (least privilege per table, as in V17–V20). Contract:
-- docs/increment-06/API-Contract-E31-E34.md.
-- =====================================================================================================

grant usage on schema devicecap to ${app_role};

-- ---------- form rules (E33-F01 / F02): verification, sign-off and release rule per bound OH form ----------
-- The field catalogue of each form lives in the module resource e33/form-registry.json (versioned with the code); the rules
-- below are clinical-governance configuration (who may verify, whether interpretation needs physician sign-off, whether a
-- plain-language summary is released to the employee, the time-and-motion estimate used for the "minutes saved" KPI).
create table devicecap.form_rule (
    form_code            varchar(60)   primary key,
    form_version         varchar(10)   not null,
    module               varchar(10)   not null,
    category             varchar(30)   not null,
    target               varchar(40)   not null,
    verifier_roles       varchar(300)  not null,          -- comma separated roles allowed to accept / reject
    sign_off_roles       varchar(300),                    -- interpretation sign-off (ECG / ABPM / Holter); null = none
    verify_always        boolean       not null,          -- verify-before-post regardless of the binding
    release_to_employee  boolean       not null,          -- plain-language summary released on acceptance
    restricted           boolean       not null,          -- RESTRICTED_CLINICAL (E10 breath alcohol / drug screen)
    minutes_saved        numeric(4,1)  not null,          -- time-and-motion estimate per device capture vs typing (KPI)
    updated_by           varchar(200)  not null,
    updated_at           timestamptz   not null
);

insert into devicecap.form_rule (form_code, form_version, module, category, target, verifier_roles, sign_off_roles, verify_always,
                                 release_to_employee, restricted, minutes_saved, updated_by, updated_at) values
    ('E03.DEVICE_RESULT.AUDIOMETRY',       '1', 'E03', 'AUDIOMETRY',       'E03.RESULT',         'OH_CLINICIAN,OH_PHYSICIAN,AME,CMO,NURSING_HEAD', null, false, true,  false, 4.0, 'migration', now()),
    ('E03.DEVICE_RESULT.SPIROMETRY',       '1', 'E03', 'SPIROMETRY',       'E03.RESULT',         'OH_CLINICIAN,OH_PHYSICIAN,AME,CMO,NURSING_HEAD', null, false, true,  false, 4.0, 'migration', now()),
    ('E03.DEVICE_RESULT.VISION',           '1', 'E03', 'VISION',           'E03.RESULT',         'OH_CLINICIAN,OH_PHYSICIAN,AME,CMO,NURSING_HEAD', null, false, true,  false, 3.0, 'migration', now()),
    ('E03.DEVICE_RESULT.ECG',              '1', 'E03', 'ECG',              'E03.RESULT',         'OH_CLINICIAN,OH_PHYSICIAN,AME,CMO,NURSING_HEAD', 'OH_PHYSICIAN,AME,CMO', true, false, false, 5.0, 'migration', now()),
    ('E03.DEVICE_RESULT.VITALS',           '1', 'E03', 'VITALS',           'E03.RESULT',         'OH_CLINICIAN,OH_PHYSICIAN,AME,CMO,NURSING_HEAD', null, false, true,  false, 3.0, 'migration', now()),
    ('E03.DEVICE_RESULT.BODY_COMPOSITION', '1', 'E03', 'BODY_COMPOSITION', 'E03.RESULT',         'OH_CLINICIAN,OH_PHYSICIAN,AME,CMO,NURSING_HEAD', null, false, true,  false, 3.0, 'migration', now()),
    ('E03.DEVICE_RESULT.ABPM_HOLTER',      '1', 'E03', 'ABPM_HOLTER',      'E03.RESULT',         'OH_CLINICIAN,OH_PHYSICIAN,AME,CMO,NURSING_HEAD', 'OH_PHYSICIAN,AME,CMO', true, false, false, 6.0, 'migration', now()),
    ('E03.DEVICE_RESULT.POC',              '1', 'E03', 'POC',              'E03.RESULT',         'OH_CLINICIAN,OH_PHYSICIAN,AME,CMO,NURSING_HEAD', null, false, false, false, 3.0, 'migration', now()),
    ('E03.DEVICE_RESULT.FIT_TEST',         '1', 'E03', 'FIT_TEST',         'E03.RESULT',         'OH_CLINICIAN,OH_PHYSICIAN,AME,CMO,NURSING_HEAD', null, false, false, false, 4.0, 'migration', now()),
    ('E03.DEVICE_RESULT.ERGONOMICS',       '1', 'E03', 'ERGONOMICS',       'E03.RESULT',         'OH_CLINICIAN,OH_PHYSICIAN,AME,CMO,NURSING_HEAD', null, false, false, false, 3.0, 'migration', now()),
    ('E03.DEVICE_RESULT.IMAGING',          '1', 'E03', 'IMAGING',          'E03.RESULT',         'OH_CLINICIAN,OH_PHYSICIAN,AME,CMO,NURSING_HEAD', null, true,  false, false, 2.0, 'migration', now()),
    ('E07.IH_MEASUREMENT',                 '1', 'E07', 'EXPOSURE',         'E07.IH_MEASUREMENT', 'OH_CLINICIAN,OH_PHYSICIAN,AME,CMO,NURSING_HEAD', null, false, false, false, 2.0, 'migration', now()),
    ('E10.BREATH_ALCOHOL',                 '1', 'E10', 'BREATH_ALCOHOL',   'E10.BREATH_ALCOHOL', 'COLLECTOR,MRO',                                  null, true,  false, true,  3.0, 'migration', now()),
    ('E10.DRUG_SCREEN',                    '1', 'E10', 'DRUG_SCREEN',      'E10.BREATH_ALCOHOL', 'COLLECTOR,MRO',                                  null, true,  false, true,  3.0, 'migration', now()),
    ('E12.SCREENING_RESULT',               '1', 'E12', 'VITALS',           'E12.SCREENING',      'OH_CLINICIAN,OH_PHYSICIAN,AME,CMO,NURSING_HEAD', null, false, true,  false, 2.0, 'migration', now()),
    ('E12.WEARABLE_DAILY',                 '1', 'E12', 'WEARABLE',         'E12.WEARABLE',       'OH_CLINICIAN,OH_PHYSICIAN,AME,CMO,NURSING_HEAD', null, false, false, false, 0.0, 'migration', now());

-- ---------- capture sessions (contract 14.1): one employee in one OH form bound to one device instance ----------
create table devicecap.capture_batch (
    id                   uuid          primary key,
    tenant_code          varchar(30)   not null default 'GROUP',
    site_code            varchar(20)   not null,
    screening_event_id   varchar(80),
    form_code            varchar(60)   not null references devicecap.form_rule(form_code),
    device_instance_ids  varchar(1000) not null,          -- comma separated published instances paired to the tablet
    operator             varchar(200)  not null,
    status               varchar(10)   not null check (status in ('OPEN','CLOSED')),
    entries              integer       not null,
    created_at           timestamptz   not null,
    closed_at            timestamptz,
    version              bigint        not null default 0
);

create table devicecap.capture_session (
    id                   uuid          primary key,
    tenant_code          varchar(30)   not null default 'GROUP',
    state                varchar(20)   not null check (state in ('LISTENING','PREVIEW','FALLBACK_OFFERED','FALLBACK_ENTRY','ACCEPTED','REJECTED','EXPIRED','CANCELLED')),
    mode                 varchar(10)   not null check (mode in ('FORM','BATCH','KIOSK')),
    employee_id          varchar(64)   not null,
    case_id              uuid,
    order_ref            varchar(80),
    form_code            varchar(60)   not null references devicecap.form_rule(form_code),
    form_version         varchar(10)   not null,
    field_group          varchar(60),
    category             varchar(30)   not null,
    restricted           boolean       not null,          -- RESTRICTED_CLINICAL session (E10)
    device_instance_id   varchar(64),                     -- current device (the alternate after a fall-back)
    original_device_instance_id varchar(64),
    kiosk_instances      varchar(1000),                   -- KIOSK: instances of the kiosk endpoint bound to this session
    endpoint_id          varchar(64),                     -- KIOSK endpoint
    path                 varchar(20)   check (path in ('DEVICE','ALTERNATE_DEVICE','OCR_VERIFIED','MANUAL_ATTESTED')),
    operator             varchar(200)  not null,
    site_code            varchar(20)   not null,
    station_code         varchar(60),
    batch_id             uuid          references devicecap.capture_batch(id),
    queue_position       integer,
    context_push         varchar(30),                     -- NONE | PUSH_WORKLIST | START_ACQUISITION (E31-HLR-08)
    opened_at            timestamptz   not null,
    expires_at           timestamptz   not null,
    last_activity_at     timestamptz   not null,
    closed_at            timestamptz,
    observation_id       uuid,                            -- current observation shown in PREVIEW
    observation_status   varchar(30),
    flags                text,                            -- JSON quality flags (gate, outcome, code, plain message – no values, no PHI)
    routed_record_type   varchar(40),
    routed_record_id     varchar(80),
    fallback_reason      varchar(30),
    reject_reason        varchar(30),
    retest_count         integer       not null default 0,
    verified_by          varchar(200),
    verified_at          timestamptz,
    interpretation_status varchar(20)  check (interpretation_status in ('UNVERIFIED','SIGNED')),
    signed_by            varchar(200),
    signed_at            timestamptz,
    consent_id           uuid,                            -- KIOSK: KIOSK_SELF_MEASUREMENT consent
    language             varchar(8),
    note                 text,                            -- encrypted (field level), CLINICAL: verifier note
    version              bigint        not null default 0
);
-- at most one form session holds a device instance (LISTENING, or PREVIEW of a sequence) – 409 device_busy
create unique index ux_capture_session_listening on devicecap.capture_session (device_instance_id)
    where state in ('LISTENING','PREVIEW') and mode = 'FORM';
create index ix_capture_session_device on devicecap.capture_session (device_instance_id, state);
create index ix_capture_session_employee on devicecap.capture_session (employee_id, opened_at);
create index ix_capture_session_expiry on devicecap.capture_session (expires_at) where state in ('LISTENING','FALLBACK_OFFERED','FALLBACK_ENTRY');
create index ix_capture_session_batch on devicecap.capture_session (batch_id, queue_position);
create index ix_capture_session_site on devicecap.capture_session (site_code, opened_at);

-- every observation that reached a session (one sequence – e.g. vitals or a kiosk visit – may carry several)
create table devicecap.capture_observation (
    id                   uuid          primary key,
    session_id           uuid          not null references devicecap.capture_session(id),
    observation_id       uuid          not null unique,
    employee_id          varchar(64)   not null,
    category             varchar(30)   not null,
    capture_method       varchar(20)   check (capture_method in ('DEVICE','OCR_VERIFIED','MANUAL_ATTESTED')),
    device_instance_id   varchar(64),
    model                varchar(200),
    status               varchar(30)   not null,          -- engine status of the observation
    flags                text,                            -- JSON quality flags (no values)
    effective_at         timestamptz,
    received_at          timestamptz   not null,
    decision             varchar(20)   check (decision in ('ACCEPTED','REJECTED','AUTO_POSTED')),
    decided_by           varchar(200),
    decided_at           timestamptz,
    reject_reason        varchar(30),
    routed_record_type   varchar(40),
    routed_record_id     varchar(80),
    values_json          text,                            -- encrypted (field level), CLINICAL: accepted values (comparison / trend, E33-F11)
    version              bigint        not null default 0
);
create index ix_capture_observation_session on devicecap.capture_observation (session_id);
create index ix_capture_observation_employee on devicecap.capture_observation (employee_id, category, effective_at);

-- append-only transition log (every state change is also audited DEVICE_CAPTURE_<STATE>)
create table devicecap.capture_event (
    id                   uuid          primary key,
    session_id           uuid          not null references devicecap.capture_session(id),
    occurred_at          timestamptz   not null,
    actor                varchar(200)  not null,
    from_state           varchar(20),
    to_state             varchar(20)   not null,
    event                varchar(40)   not null,
    detail               varchar(200)                     -- codes only, never values or names
);
create index ix_capture_event_session on devicecap.capture_event (session_id, occurred_at);

-- clinician annotations on a capture (E33-F11), screened and encrypted
create table devicecap.capture_annotation (
    id                   uuid          primary key,
    session_id           uuid          not null references devicecap.capture_session(id),
    author               varchar(200)  not null,
    created_at           timestamptz   not null,
    text                 text          not null           -- encrypted (field level), CLINICAL
);

-- photo of a device display / printout kept as evidence of an OCR-verified value (E33-HLR-10)
create table devicecap.ocr_image (
    id                   uuid          primary key,
    device_instance_id   varchar(64),
    model_code           varchar(40),
    uploaded_by          varchar(200)  not null,
    uploaded_at          timestamptz   not null,
    media_type           varchar(40)   not null,
    size_bytes           bigint        not null,
    sha256               varchar(64)   not null,
    content              text          not null,          -- encrypted (field level), CLINICAL: base64 image
    retention_class      varchar(40)   not null default 'DEVICE_RAW_UNFILED'
);

-- every engine notice seen (KPI: unmatched rate, usage) – identifiers and status only
create table devicecap.observation_notice (
    observation_id       uuid          primary key,
    device_instance_id   varchar(64),
    category             varchar(30),
    session_id           uuid,
    first_status         varchar(30)   not null,
    last_status          varchar(30)   not null,
    ever_unmatched       boolean       not null,
    first_at             timestamptz   not null,
    last_at              timestamptz   not null
);
create index ix_observation_notice_first on devicecap.observation_notice (first_at);

-- ---------- employee-facing (E33-F12) ----------
create table devicecap.employee_summary (
    id                   uuid          primary key,
    employee_id          varchar(64)   not null,
    session_id           uuid          not null references devicecap.capture_session(id),
    observation_id       uuid          not null unique,
    category             varchar(30)   not null,
    measured_at          timestamptz   not null,
    language             varchar(8)    not null,
    plain_language       text          not null,          -- encrypted (field level), CLINICAL (shown to the data subject only)
    education_link       varchar(300),
    released_by          varchar(200)  not null,
    released_at          timestamptz   not null
);
create index ix_employee_summary_employee on devicecap.employee_summary (employee_id, measured_at);

-- granular wearable consent scopes on top of the platform consent (WEARABLE_DATA)
create table devicecap.device_consent (
    id                   uuid          primary key,
    employee_id          varchar(64)   not null,
    consent_id           uuid          not null,
    purpose              varchar(40)   not null check (purpose in ('WEARABLE_DATA')),
    scopes               varchar(200)  not null,          -- STEPS,SLEEP,HEART_RATE,ACTIVITY
    campaign_id          uuid,
    status               varchar(10)   not null check (status in ('ACTIVE','REVOKED')),
    granted_at           timestamptz   not null,
    revoked_at           timestamptz,
    version              bigint        not null default 0
);
create index ix_device_consent_employee on devicecap.device_consent (employee_id, status);
create index ix_device_consent_consent on devicecap.device_consent (consent_id);

-- ---------- configuration (E18) ----------
insert into masterdata.config_entry (config_key, config_value, description, updated_by, updated_at) values
    ('device.capture.ocr-categories', 'AUDIOMETRY,SPIROMETRY,VISION,ECG,VITALS,BODY_COMPOSITION,ABPM_HOLTER,POC,FIT_TEST,ERGONOMICS',
     'Categories with a display-OCR (P9) package: the capture fall-back chain offers photo / OCR with mandatory verification (never for restricted categories)', 'migration', now()),
    ('device.capture.kiosk-form', 'E12.SCREENING_RESULT',
     'Form bound to kiosk self-measurement sessions (auto-posted screening result; E03.DEVICE_RESULT.VITALS for clinic verification)', 'migration', now())
on conflict (config_key) do nothing;

-- ---------- least-privilege grants ----------
grant select, update on devicecap.form_rule to ${app_role};
grant select, insert, update on devicecap.capture_batch to ${app_role};
grant select, insert, update on devicecap.capture_session to ${app_role};
grant select, insert, update on devicecap.capture_observation to ${app_role};
grant select, insert on devicecap.capture_event to ${app_role};          -- append-only evidence
grant select, insert on devicecap.capture_annotation to ${app_role};     -- append-only evidence
grant select, insert on devicecap.ocr_image to ${app_role};              -- evidence, immutable
grant select, insert, update on devicecap.observation_notice to ${app_role};
grant select, insert on devicecap.employee_summary to ${app_role};       -- released summaries are immutable
grant select, insert, update on devicecap.device_consent to ${app_role};
