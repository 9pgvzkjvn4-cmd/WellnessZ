create schema if not exists devicecat;
-- =====================================================================================================
-- V23 – E32 Device catalogue and provisioning console: families / brands / models (seeded from the 133-model device inventory by the E32 seed loader), option packs P1–P10, driver-package registry, wizard drafts, configuration versions with four-eyes approval and change history, bindings, certification records, fleet, calibration / QC logs, vendor posture, request-a-model, decommissioning.
-- Base migration created by the platform increment (schema + usage grant). The E32 developer adds the tables, indexes,
-- seed rows and table-level grants below this header (least privilege per table, as in V17–V20). Contract:
-- docs/increment-06/API-Contract-E31-E34.md.
-- =====================================================================================================

grant usage on schema devicecat to ${app_role};

-- ---------- identifiers (CFG-000101…, DEV-000101…; DEV-000001…099 are reserved for the local / test demo instances) ----------
create sequence devicecat.configuration_seq start with 101;
create sequence devicecat.instance_seq start with 101;
create sequence devicecat.certification_seq start with 1;
create sequence devicecat.model_request_seq start with 1;
create sequence devicecat.local_model_seq start with 1;

-- ---------- catalogue (E32-F01, E32-HLR-01): seeded from e32/device-inventory.json by CatalogueSeedLoader ----------
create table devicecat.seed_state (
    seed_name      varchar(60)   primary key,
    sha256         varchar(64)   not null,
    models         int           not null,
    loaded_at      timestamptz   not null
);

create table devicecat.model (
    model_code        varchar(20)    primary key,            -- inventory id MD-001…MD-133, or WZ-000001… for console-created models
    inventory_id      varchar(20)    unique,
    family            varchar(20)    not null,
    family_label      varchar(80)    not null,
    manufacturer      varchar(200)   not null,
    model             varchar(300)   not null,
    oh_use            varchar(1000),
    connectivity      varchar(1000),
    protocol          varchar(1000),
    fda_codes         varchar(300),
    sprint            varchar(20),
    confidence        varchar(60),
    options           varchar(60)    not null,               -- comma-separated P1..P10 supported by the model
    detail            text           not null,               -- JSON: variants, guideline drivers, units, forms, option notes, vendor software, UDI, regulatory per market, security
    lifecycle         varchar(20)    not null,
    posture           varchar(30)    not null,
    locally_modified  boolean        not null default false,
    modified_fields   varchar(1000)  not null default '',    -- fields edited in the console – never overwritten by the seed loader
    seed_version      varchar(64),
    created_by        varchar(200)   not null,
    created_at        timestamptz    not null,
    updated_by        varchar(200)   not null,
    updated_at        timestamptz    not null,
    row_version       bigint         not null default 0,
    constraint ck_model_family check (family in ('SPIROMETRY','AUDIOMETRY','VITALS','CARDIOLOGY','VISION','POC','DRUG_ALCOHOL','RESP_ERGONOMICS','EXPOSURE','WEARABLE','IMAGING')),
    constraint ck_model_lifecycle check (lifecycle in ('CANDIDATE','CERTIFIED','DEPRECATED','RETIRED')),
    constraint ck_model_posture check (posture in ('ACCEPTABLE','EXCEPTION_APPROVED','BLOCKED','NOT_ASSESSED'))
);
create index ix_model_family on devicecat.model (family, manufacturer);

-- ---------- vendor security posture exceptions (E32-F07; approved by SECURITY_OPS, expiry re-blocks) ----------
create table devicecat.posture_exception (
    id                     uuid           primary key,
    model_code             varchar(20)    not null references devicecat.model (model_code),
    compensating_controls  text           not null,          -- JSON array of control descriptions (no PHI)
    expires_on             date           not null,
    risk_owner             varchar(200)   not null,
    status                 varchar(20)    not null,
    requested_by           varchar(200)   not null,
    requested_at           timestamptz    not null,
    decided_by             varchar(200),
    decided_at             timestamptz,
    note                   varchar(1000),
    constraint ck_posture_exception_status check (status in ('REQUESTED','APPROVED','REJECTED','EXPIRED'))
);
create index ix_posture_exception_model on devicecat.posture_exception (model_code, status);

-- ---------- driver-package registry (E31-HLR-03; immutable versions, signatures verified by E34) ----------
create table devicecat.driver_package (
    package_id                   varchar(120)  not null,
    version                      varchar(30)   not null,
    family                       varchar(20)   not null,
    adapter                      varchar(30)   not null,
    models                       varchar(1000) not null,     -- comma-separated model codes
    options                      varchar(60)   not null,
    engine_range                 varchar(60),
    manifest                     text          not null,     -- canonical JSON without the signature member
    sha256                       varchar(64)   not null,
    signature_key_id             varchar(100),
    signature_algorithm          varchar(30),
    signature_value              varchar(400),
    status                       varchar(20)   not null,
    conformance_run_id           varchar(100),
    conformance_evidence_sha256  varchar(64),
    registered_by                varchar(200)  not null,
    registered_at                timestamptz   not null,
    certified_by                 varchar(200),
    certified_at                 timestamptz,
    status_changed_by            varchar(200),
    status_changed_at            timestamptz,
    status_reason                varchar(500),
    primary key (package_id, version),
    constraint ck_driver_status check (status in ('REGISTERED','CERTIFIED','DEPRECATED','REVOKED'))
);

-- ---------- stations (site / room / station codes the wizard assigns devices to) ----------
create table devicecat.station (
    station_code   varchar(60)   primary key,
    site_code      varchar(40)   not null,
    room_code      varchar(60),
    name           varchar(200)  not null,
    kind           varchar(20)   not null,
    test_site      boolean       not null default false,   -- test stations: simulator-only tests, INTEGRATION_TESTER configurations
    active         boolean       not null default true,
    constraint ck_station_kind check (kind in ('ROOM','BOOTH','STATION','KIOSK','MOBILE'))
);
create index ix_station_site on devicecat.station (site_code);

-- ---------- configurations: one row per version (wizard state machine, contract §13.2) ----------
create table devicecat.configuration (
    row_id               uuid          primary key,
    configuration_id     varchar(20)   not null,
    version              int           not null,
    device_instance_id   varchar(20)   not null,
    status               varchar(20)   not null,
    paused               boolean       not null default false,
    current_step         int           not null,
    model_code           varchar(20)   not null references devicecat.model (model_code),
    option_code          varchar(4),
    site_code            varchar(40),
    room_code            varchar(60),
    station_code         varchar(60),
    tenant_code          varchar(40)   not null,
    endpoint_id          varchar(100),
    endpoint_kind        varchar(30),
    zone                 varchar(40),
    equipment_device_id  varchar(100),
    serial               varchar(100),
    udi                  varchar(200),
    firmware             varchar(60),
    software_version     varchar(100),
    driver_package_id    varchar(120),
    driver_version       varchar(30),
    data                 text          not null,   -- JSON: per-step status / payload, parameters (secrets as references only), bindings, tests, reviews
    cloned_from          varchar(20),
    template_id          uuid,
    created_by           varchar(200)  not null,
    created_at           timestamptz   not null,
    updated_by           varchar(200)  not null,
    updated_at           timestamptz   not null,
    submitted_by         varchar(200),
    submitted_at         timestamptz,
    approved_by          varchar(200),
    approved_at          timestamptz,
    co_approved_by       varchar(200),
    published_by         varchar(200),
    published_at         timestamptz,
    status_reason        varchar(500),
    row_version          bigint        not null default 0,
    constraint uq_configuration_version unique (configuration_id, version),
    constraint ck_configuration_status check (status in ('DRAFT','AWAITING_APPROVAL','CERTIFIED','PUBLISHED','SUSPENDED','QUARANTINED','SUPERSEDED','DECOMMISSIONED')),
    constraint ck_configuration_step check (current_step between 1 and 8)
);
create index ix_configuration_instance on devicecat.configuration (device_instance_id, status);
create index ix_configuration_endpoint on devicecat.configuration (endpoint_id) where endpoint_id is not null;
create index ix_configuration_site on devicecat.configuration (site_code, station_code, status);

-- ---------- change history (append-only, E32-F06) ----------
create table devicecat.change_history (
    id              uuid           primary key,
    entity_type     varchar(30)    not null,   -- CONFIGURATION | MODEL | DRIVER | SCHEDULE | TEMPLATE | IMPORT | INSTANCE
    entity_id       varchar(140)   not null,
    version         int,
    actor           varchar(200)   not null,
    action          varchar(60)    not null,
    step            int,
    diff            text,                      -- JSON [{path, from, to}] – secrets as references only
    approval_ref    varchar(60),
    at              timestamptz    not null
);
create index ix_change_history_entity on devicecat.change_history (entity_type, entity_id, at);
create index ix_change_history_at on devicecat.change_history (at);

-- ---------- approvals (four-eyes queue: submissions, decommissioning, imports, driver certifications, schedules, exceptions) ----------
create table devicecat.approval (
    id              uuid           primary key,
    kind            varchar(30)    not null,
    reference       varchar(140)   not null,
    ref_version     int,
    status          varchar(20)    not null,
    requested_by    varchar(200)   not null,
    requested_at    timestamptz    not null,
    decided_by      varchar(200),
    decided_at      timestamptz,
    note            varchar(1000),
    detail          text,
    constraint ck_approval_kind check (kind in ('CONFIGURATION','DECOMMISSION','IMPORT','DRIVER_CERTIFICATION','POSTURE_EXCEPTION','SCHEDULE_CHANGE')),
    constraint ck_approval_status check (status in ('PENDING','APPROVED','REJECTED','WITHDRAWN'))
);
create index ix_approval_status on devicecat.approval (status, kind);

-- ---------- certification records (immutable, retention DEVICE_CONFIGURATION, E32-F10) ----------
create table devicecat.certification (
    certification_id         varchar(30)    primary key,
    configuration_id         varchar(20)    not null,
    version                  int            not null,
    device_instance_id       varchar(20)    not null,
    record                   text           not null,
    snapshot_sha256          varchar(64)    not null,
    pdf                      bytea          not null,
    pdf_sha256               varchar(64)    not null,
    approval_signature_hash  varchar(64)    not null,
    retention_class          varchar(40)    not null default 'DEVICE_CONFIGURATION',
    created_at               timestamptz    not null,
    constraint uq_certification_version unique (configuration_id, version)
);

-- ---------- calibration / QC (E32-F04; E14 stays the calibration-certificate authority) ----------
create table devicecat.calibration_schedule (
    family                 varchar(20)   not null,
    check_type             varchar(40)   not null,
    interval_hours         int           not null,
    grace_minutes          int           not null default 0,
    lockout                boolean       not null default true,
    reminder_before_hours  int           not null default 2,
    per_lot                boolean       not null default false,
    description            varchar(300)  not null,
    updated_by             varchar(200)  not null,
    updated_at             timestamptz   not null,
    primary key (family, check_type),
    constraint ck_schedule_interval check (interval_hours between 1 and 87600)
);

-- seeded schedules (proposed, pending clinical sign-off – contract §13.6)
insert into devicecat.calibration_schedule (family, check_type, interval_hours, grace_minutes, lockout, reminder_before_hours, per_lot, description, updated_by, updated_at) values
    ('AUDIOMETRY',      'AUDIO_DAILY_BIOLOGICAL', 24,   0, true, 2,   false, 'Daily biological (listening) check before the first test of the day (ISO 8253-1, OSHA 1910.95 App. E)', 'migration', now()),
    ('SPIROMETRY',      'SPIRO_SYRINGE_3L',       24,   0, true, 2,   false, 'Daily 3-L syringe volume calibration check (ATS/ERS 2019, OSHA 3637)', 'migration', now()),
    ('SPIROMETRY',      'SPIRO_LINEARITY',        168,  0, true, 24,  false, 'Weekly flow linearity check (ATS/ERS 2019)', 'migration', now()),
    ('VITALS',          'BP_VALIDATION_INTERVAL', 8760, 0, true, 720, false, 'Blood-pressure device accuracy check interval (ISO 81060-2 validated device, annual check)', 'migration', now()),
    ('DRUG_ALCOHOL',    'BREATH_ACCURACY_CHECK',  720,  0, true, 72,  false, 'Breath-alcohol accuracy / calibration check (DOT 49 CFR Part 40 quality-assurance plan, vendor schedule)', 'migration', now()),
    ('POC',             'POC_QC_LOT',             720,  0, true, 72,  true,  'Point-of-care QC: each new reagent lot and every 30 days', 'migration', now()),
    ('RESP_ERGONOMICS', 'FIT_DAILY_CHECK',        24,   0, true, 2,   false, 'Fit-tester daily check (OSHA 1910.134 App. A, vendor daily check)', 'migration', now());

create table devicecat.qc_check (
    id                    uuid           primary key,
    device_instance_id    varchar(20)    not null,
    check_type            varchar(40)    not null,
    performed_at          timestamptz    not null,
    outcome               varchar(10)    not null,
    check_values          text,                      -- JSON of measured check values (device metrology, no person data)
    lot                   varchar(60),
    lot_expiry            date,
    evidence_document_id  varchar(100),
    source                varchar(20)    not null,   -- MANUAL | DEVICE | DEMO
    recorded_by           varchar(200)   not null,
    recorded_at           timestamptz    not null,
    retention_class       varchar(40)    not null default 'DEVICE_CALIBRATION_QC',
    constraint ck_qc_outcome check (outcome in ('PASS','FAIL'))
);
create index ix_qc_check_instance on devicecat.qc_check (device_instance_id, check_type, performed_at desc);

create table devicecat.lockout_event (
    id                   uuid           primary key,
    device_instance_id   varchar(20)    not null,
    check_type           varchar(40)    not null,
    event                varchar(20)    not null,   -- LOCKED | RELEASED
    reason               varchar(40),
    actor                varchar(200)   not null,
    at                   timestamptz    not null
);
create index ix_lockout_event_instance on devicecat.lockout_event (device_instance_id, check_type, at desc);

-- ---------- fleet (E32-F05) ----------
create table devicecat.fleet_alert (
    id                   uuid           primary key,
    device_instance_id   varchar(20),
    site_code            varchar(40),
    alert_type           varchar(40)    not null,
    level                varchar(10)    not null,
    detail               varchar(500),
    dedup_key            varchar(200)   not null,
    status               varchar(20)    not null,
    raised_at            timestamptz    not null,
    acknowledged_by      varchar(200),
    acknowledged_at      timestamptz,
    ticket_ref           varchar(60),
    closed_at            timestamptz,
    constraint ck_fleet_alert_status check (status in ('OPEN','ACKNOWLEDGED','CLOSED'))
);
create unique index ux_fleet_alert_open on devicecat.fleet_alert (dedup_key) where status <> 'CLOSED';

create table devicecat.consumable (
    id                   uuid           primary key,
    device_instance_id   varchar(20)    not null,
    item                 varchar(200)   not null,
    lot                  varchar(60),
    expiry               date,
    quantity             int            not null,
    recorded_by          varchar(200)   not null,
    recorded_at          timestamptz    not null
);
create index ix_consumable_instance on devicecat.consumable (device_instance_id);

create table devicecat.on_duty (
    id           uuid           primary key,
    site_code    varchar(40)    not null,
    username     varchar(200)   not null,
    from_at      timestamptz    not null,
    to_at        timestamptz    not null,
    updated_by   varchar(200)   not null,
    updated_at   timestamptz    not null
);

-- ---------- request-a-new-model (E32-F08) ----------
create table devicecat.model_request (
    id                      uuid           primary key,
    request_ref             varchar(30)    not null unique,
    manufacturer            varchar(200)   not null,
    model                   varchar(300)   not null,
    family                  varchar(20)    not null,
    connectivity            varchar(1000)  not null,
    interface_document_ids  varchar(1000)  not null default '',
    sites                   varchar(1000)  not null default '',
    urgency                 varchar(10)    not null,
    status                  varchar(20)    not null,
    scoping                 text,
    requested_by            varchar(200)   not null,
    requested_at            timestamptz    not null,
    scoped_by               varchar(200),
    first_scoped_at         timestamptz,
    updated_at              timestamptz    not null,
    constraint ck_model_request_status check (status in ('SUBMITTED','SCOPING','SCOPED','IN_DEVELOPMENT','CERTIFIED','REJECTED'))
);

-- ---------- governance: export / import, templates, batches (E32-F06) ----------
create table devicecat.governance_export (
    export_id            uuid           primary key,
    source_environment   varchar(20)    not null,
    payload              text           not null,
    sha256               varchar(64)    not null,
    created_by           varchar(200)   not null,
    created_at           timestamptz    not null
);

create table devicecat.governance_import (
    import_id               uuid           primary key,
    source_environment      varchar(20),
    export_id               varchar(60),
    payload                 text           not null,
    items                   text           not null,
    status                  varchar(20)    not null,
    created_configurations  text,
    imported_by             varchar(200)   not null,
    imported_at             timestamptz    not null,
    decided_by              varchar(200),
    decided_at              timestamptz,
    constraint ck_import_status check (status in ('PENDING','APPROVED','REJECTED'))
);

create table devicecat.template (
    id           uuid           primary key,
    name         varchar(200)   not null,
    scope        varchar(10)    not null,
    items        text           not null,
    created_by   varchar(200)   not null,
    created_at   timestamptz    not null,
    updated_by   varchar(200)   not null,
    updated_at   timestamptz    not null,
    constraint ck_template_scope check (scope in ('SITE','FAMILY'))
);

create table devicecat.batch (
    id           uuid           primary key,
    template_id  uuid           not null references devicecat.template (id),
    site_code    varchar(40)    not null,
    report       text           not null,
    created_by   varchar(200)   not null,
    created_at   timestamptz    not null
);

-- runtime role: least privilege per table; history, certifications, QC checks, lockout events, exports and batches are
-- append-only evidence (no update / delete); on-duty rota entries may be replaced
grant select, insert, update on devicecat.seed_state, devicecat.model, devicecat.posture_exception, devicecat.driver_package,
    devicecat.station, devicecat.configuration, devicecat.approval, devicecat.calibration_schedule, devicecat.fleet_alert,
    devicecat.on_duty, devicecat.model_request, devicecat.governance_import, devicecat.template to ${app_role};
grant delete on devicecat.on_duty to ${app_role};
grant select, insert on devicecat.change_history, devicecat.certification, devicecat.qc_check, devicecat.lockout_event,
    devicecat.consumable, devicecat.governance_export, devicecat.batch to ${app_role};
grant usage, select on sequence devicecat.configuration_seq, devicecat.instance_seq, devicecat.certification_seq,
    devicecat.model_request_seq, devicecat.local_model_seq to ${app_role};
