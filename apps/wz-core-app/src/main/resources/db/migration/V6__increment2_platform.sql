-- Increment 2 platform additions: E18 fitness standards / restriction catalogue / job demands, document service,
-- break-glass access, disclosure log, single-run guard for scheduled jobs, kernel primary diagnosis.

create schema if not exists docs;

-- ---------- master data (E18) ----------
create table masterdata.fitness_standard (
    code                     varchar(40)   not null,
    version                  int           not null,
    title                    varchar(200)  not null,
    role_category            varchar(40)   not null,
    source                   varchar(300)  not null,
    reference_url            varchar(500),
    validity_months          int           not null,
    validity_months_over_40  int,
    requires_signoff_role    varchar(20),
    limitation_codes         varchar(500),
    status                   varchar(20)   not null,
    updated_by               varchar(200)  not null,
    updated_at               timestamptz   not null,
    primary key (code, version)
);

insert into masterdata.fitness_standard (code, version, title, role_category, source, reference_url, validity_months,
        validity_months_over_40, requires_signoff_role, limitation_codes, status, updated_by, updated_at) values
    ('CAA_CLASS1', 1, 'Pilot – Part-MED Class 1 medical', 'FLIGHT_CREW', 'EASA Part-MED (Reg. (EU) 1178/2011 Annex IV) MED.A/MED.B; local CAA', 'https://www.easa.europa.eu/en/regulations/aircrew', 12, 6, 'AME', 'TML,VDL,VML,VNL,CCL,VCL,OML,OCL,OSL,OPL,ORL,SSL,HAL,SIC,RXO', 'ACTIVE', 'migration', now()),
    ('CAA_CLASS2', 1, 'Pilot – Part-MED Class 2 medical', 'FLIGHT_CREW', 'EASA Part-MED MED.A/MED.B; local CAA', 'https://www.easa.europa.eu/en/regulations/aircrew', 24, 12, 'AME', 'TML,VDL,VML,VNL,CCL,VCL,OSL,OPL,ORL,SSL,HAL,SIC,RXO', 'ACTIVE', 'migration', now()),
    ('CABIN_CREW', 1, 'Cabin crew medical report', 'CABIN_CREW', 'EASA Part-MED MED.C (cabin crew medical fitness); IATA Medical Manual', 'https://www.easa.europa.eu/en/regulations/aircrew', 60, null, 'AME', 'TML,VDL,VNL,CCL,HAL,SIC,RXO,SSL', 'ACTIVE', 'migration', now()),
    ('AIRSIDE_DRIVING', 1, 'Airside driving permit – medical fitness', 'AIRSIDE_DRIVER', 'Local airport authority airside driving rules; national Group 2 driving standards', null, 36, 12, null, null, 'ACTIVE', 'migration', now()),
    ('FORKLIFT', 1, 'Forklift / powered industrial truck operator', 'FORKLIFT', 'Local OH&S regulation; national driving standards (benchmark OSHA 1910.178)', null, 36, null, null, null, 'ACTIVE', 'migration', now()),
    ('WORKING_AT_HEIGHT', 1, 'Working at height', 'WORKING_AT_HEIGHT', 'Local OH&S regulation (work at height)', null, 36, null, null, null, 'ACTIVE', 'migration', now()),
    ('FOOD_HANDLER', 1, 'Food handler – fitness to handle food', 'FOOD_HANDLER', 'Codex Alimentarius / ISO 22000 / HACCP; local food-safety law', null, 12, null, null, null, 'ACTIVE', 'migration', now()),
    ('RESPIRATOR', 1, 'Respirator use (tight-fitting RPE)', 'GENERAL', 'Local OH&S regulation (benchmark OSHA 1910.134)', null, 12, null, null, null, 'ACTIVE', 'migration', now()),
    ('NIGHT_WORK', 1, 'Night work health assessment', 'GENERAL', 'Local labour law (night-worker health assessment)', null, 12, null, null, null, 'ACTIVE', 'migration', now()),
    ('VDU', 1, 'Display screen equipment (VDU) user', 'GENERAL', 'Local OH&S regulation (display screen equipment)', null, 24, null, null, null, 'ACTIVE', 'migration', now()),
    ('SECURITY_SCREENING', 1, 'Security screening officer', 'GENERAL', 'National aviation security programme', null, 24, null, null, null, 'ACTIVE', 'migration', now()),
    ('GENERAL_DUTIES', 1, 'General duties (non safety-critical)', 'GENERAL', 'Group OH policy', null, 12, null, null, null, 'ACTIVE', 'migration', now());

create table masterdata.restriction (
    code            varchar(40)   primary key,
    label           varchar(200)  not null,
    dimension       varchar(40)   not null,
    value_required  boolean       not null,
    unit            varchar(20),
    active          boolean       not null default true,
    updated_by      varchar(200)  not null,
    updated_at      timestamptz   not null
);

insert into masterdata.restriction (code, label, dimension, value_required, unit, updated_by, updated_at) values
    ('LIFT_MAX_KG',          'No lifting above {value} kg',               'LIFT_KG',        true,  'kg',    'migration', now()),
    ('PUSH_PULL_MAX_KG',     'No pushing or pulling above {value} kg',     'PUSH_PULL_KG',   true,  'kg',    'migration', now()),
    ('NO_CLIMBING',          'No climbing of steps or aircraft stairs',    'CLIMB',          false, null,    'migration', now()),
    ('NO_LADDER_WORK',       'No ladder or work at height',                'HEIGHT',         false, null,    'migration', now()),
    ('NO_AIRSIDE_DRIVING',   'No airside driving',                         'AIRSIDE_DRIVE',  false, null,    'migration', now()),
    ('NO_NIGHT_DUTY',        'No night duty',                              'NIGHT',          false, null,    'migration', now()),
    ('NO_KNIFE_WORK',        'No knife or blade work',                     'KNIFE',          false, null,    'migration', now()),
    ('NO_CHEMICAL_HANDLING', 'No handling of chemicals or fuel',           'CHEMICAL',       false, null,    'migration', now()),
    ('NO_RESPIRATOR',        'No tasks requiring a tight-fitting respirator', 'RESPIRATOR',  false, null,    'migration', now()),
    ('NO_NOISE_EXPOSURE',    'No work in high-noise areas',                'NOISE',          false, null,    'migration', now()),
    ('NO_HEAT_EXPOSURE',     'No work in hot environments',                'HEAT',           false, null,    'migration', now()),
    ('NO_FLIGHT_DUTY',       'No flight duty',                             'FLIGHT_DUTY',    false, null,    'migration', now()),
    ('NO_FOOD_HANDLING',     'No handling of open food',                   'FOOD_HANDLING',  false, null,    'migration', now()),
    ('MAX_HOURS_PER_DAY',    'Maximum {value} hours per day',              'HOURS_PER_DAY',  true,  'hours', 'migration', now()),
    ('MAX_SCREEN_HOURS',     'Maximum {value} hours of screen work per day', 'SCREEN_HOURS', true,  'hours', 'migration', now()),
    ('NO_SAFETY_CRITICAL',   'No safety-critical duties',                  'SAFETY_CRITICAL', false, null,   'migration', now());

create table masterdata.job_demand_profile (
    job_code      varchar(64)   primary key,
    title         varchar(200)  not null,
    unit          varchar(20)   not null,
    demands       text          not null,   -- JSON object {dimension: number|boolean}
    sitting       varchar(10),
    validated_by  varchar(200),
    validated_at  timestamptz,
    updated_by    varchar(200)  not null,
    updated_at    timestamptz   not null
);

-- representative job-demand library (E05-BL-004: the full top-150 set is loaded via POST /masterdata/job-demands/import)
insert into masterdata.job_demand_profile (job_code, title, unit, demands, sitting, updated_by, updated_at) values
    ('PILOT', 'Pilot', 'FLT', '{"LIFT_KG":10,"PUSH_PULL_KG":10,"CLIMB":true,"AIRSIDE_DRIVE":false,"NIGHT":true,"KNIFE":false,"CHEMICAL":false,"RESPIRATOR":false,"NOISE":true,"HEAT":false,"HEIGHT":false,"FLIGHT_DUTY":true,"FOOD_HANDLING":false,"HOURS_PER_DAY":13,"SCREEN_HOURS":6,"SAFETY_CRITICAL":true}', 'HIGH', 'migration', now()),
    ('CABIN_CREW', 'Cabin crew', 'CAB', '{"LIFT_KG":15,"PUSH_PULL_KG":40,"CLIMB":true,"AIRSIDE_DRIVE":false,"NIGHT":true,"KNIFE":false,"CHEMICAL":false,"RESPIRATOR":false,"NOISE":false,"HEAT":false,"HEIGHT":false,"FLIGHT_DUTY":true,"FOOD_HANDLING":true,"HOURS_PER_DAY":13,"SCREEN_HOURS":0,"SAFETY_CRITICAL":true}', 'LOW', 'migration', now()),
    ('RAMP_AGENT', 'Ramp agent', 'GND', '{"LIFT_KG":32,"PUSH_PULL_KG":40,"CLIMB":true,"AIRSIDE_DRIVE":true,"NIGHT":true,"KNIFE":false,"CHEMICAL":true,"RESPIRATOR":false,"NOISE":true,"HEAT":true,"HEIGHT":false,"FLIGHT_DUTY":false,"FOOD_HANDLING":false,"HOURS_PER_DAY":10,"SCREEN_HOURS":0,"SAFETY_CRITICAL":true}', 'LOW', 'migration', now()),
    ('BAGGAGE_HANDLER', 'Baggage hall handler', 'GND', '{"LIFT_KG":32,"PUSH_PULL_KG":30,"CLIMB":false,"AIRSIDE_DRIVE":false,"NIGHT":true,"KNIFE":false,"CHEMICAL":false,"RESPIRATOR":false,"NOISE":true,"HEAT":false,"HEIGHT":false,"FLIGHT_DUTY":false,"FOOD_HANDLING":false,"HOURS_PER_DAY":10,"SCREEN_HOURS":0,"SAFETY_CRITICAL":false}', 'LOW', 'migration', now()),
    ('GSE_DRIVER', 'Ground support equipment driver', 'GND', '{"LIFT_KG":10,"PUSH_PULL_KG":10,"CLIMB":true,"AIRSIDE_DRIVE":true,"NIGHT":true,"KNIFE":false,"CHEMICAL":false,"RESPIRATOR":false,"NOISE":true,"HEAT":true,"HEIGHT":false,"FLIGHT_DUTY":false,"FOOD_HANDLING":false,"HOURS_PER_DAY":10,"SCREEN_HOURS":0,"SAFETY_CRITICAL":true}', 'HIGH', 'migration', now()),
    ('LOAD_CONTROL', 'Load control agent', 'GND', '{"LIFT_KG":5,"PUSH_PULL_KG":5,"CLIMB":false,"AIRSIDE_DRIVE":false,"NIGHT":true,"KNIFE":false,"CHEMICAL":false,"RESPIRATOR":false,"NOISE":false,"HEAT":false,"HEIGHT":false,"FLIGHT_DUTY":false,"FOOD_HANDLING":false,"HOURS_PER_DAY":10,"SCREEN_HOURS":8,"SAFETY_CRITICAL":false}', 'HIGH', 'migration', now()),
    ('GND_DISPATCH', 'Ground operations dispatcher', 'GND', '{"LIFT_KG":5,"PUSH_PULL_KG":5,"CLIMB":false,"AIRSIDE_DRIVE":false,"NIGHT":false,"KNIFE":false,"CHEMICAL":false,"RESPIRATOR":false,"NOISE":false,"HEAT":false,"HEIGHT":false,"FLIGHT_DUTY":false,"FOOD_HANDLING":false,"HOURS_PER_DAY":8,"SCREEN_HOURS":7,"SAFETY_CRITICAL":false}', 'HIGH', 'migration', now()),
    ('CARGO_WAREHOUSE', 'Cargo warehouse operative', 'CGO', '{"LIFT_KG":25,"PUSH_PULL_KG":35,"CLIMB":false,"AIRSIDE_DRIVE":false,"NIGHT":true,"KNIFE":true,"CHEMICAL":false,"RESPIRATOR":false,"NOISE":true,"HEAT":false,"HEIGHT":false,"FLIGHT_DUTY":false,"FOOD_HANDLING":false,"HOURS_PER_DAY":10,"SCREEN_HOURS":0,"SAFETY_CRITICAL":false}', 'LOW', 'migration', now()),
    ('FORKLIFT_OPERATOR', 'Forklift operator', 'CGO', '{"LIFT_KG":10,"PUSH_PULL_KG":10,"CLIMB":false,"AIRSIDE_DRIVE":false,"NIGHT":true,"KNIFE":false,"CHEMICAL":false,"RESPIRATOR":false,"NOISE":true,"HEAT":false,"HEIGHT":false,"FLIGHT_DUTY":false,"FOOD_HANDLING":false,"HOURS_PER_DAY":10,"SCREEN_HOURS":0,"SAFETY_CRITICAL":true}', 'HIGH', 'migration', now()),
    ('DGR_SPECIALIST', 'Dangerous goods acceptance specialist', 'CGO', '{"LIFT_KG":10,"PUSH_PULL_KG":10,"CLIMB":false,"AIRSIDE_DRIVE":false,"NIGHT":false,"KNIFE":false,"CHEMICAL":true,"RESPIRATOR":true,"NOISE":false,"HEAT":false,"HEIGHT":false,"FLIGHT_DUTY":false,"FOOD_HANDLING":false,"HOURS_PER_DAY":8,"SCREEN_HOURS":4,"SAFETY_CRITICAL":false}', 'MEDIUM', 'migration', now()),
    ('CARGO_ADMIN', 'Cargo documentation clerk', 'CGO', '{"LIFT_KG":5,"PUSH_PULL_KG":5,"CLIMB":false,"AIRSIDE_DRIVE":false,"NIGHT":false,"KNIFE":false,"CHEMICAL":false,"RESPIRATOR":false,"NOISE":false,"HEAT":false,"HEIGHT":false,"FLIGHT_DUTY":false,"FOOD_HANDLING":false,"HOURS_PER_DAY":8,"SCREEN_HOURS":7,"SAFETY_CRITICAL":false}', 'HIGH', 'migration', now()),
    ('CATERING_ASSOC', 'Catering associate', 'CAT', '{"LIFT_KG":20,"PUSH_PULL_KG":40,"CLIMB":false,"AIRSIDE_DRIVE":false,"NIGHT":true,"KNIFE":true,"CHEMICAL":true,"RESPIRATOR":false,"NOISE":false,"HEAT":true,"HEIGHT":false,"FLIGHT_DUTY":false,"FOOD_HANDLING":true,"HOURS_PER_DAY":10,"SCREEN_HOURS":0,"SAFETY_CRITICAL":false}', 'LOW', 'migration', now()),
    ('CATERING_COLD_ROOM', 'Catering cold-room operative', 'CAT', '{"LIFT_KG":20,"PUSH_PULL_KG":30,"CLIMB":false,"AIRSIDE_DRIVE":false,"NIGHT":true,"KNIFE":false,"CHEMICAL":false,"RESPIRATOR":false,"NOISE":false,"HEAT":false,"HEIGHT":false,"FLIGHT_DUTY":false,"FOOD_HANDLING":true,"HOURS_PER_DAY":10,"SCREEN_HOURS":0,"SAFETY_CRITICAL":false}', 'LOW', 'migration', now()),
    ('CATERING_PACKING', 'Catering tray-set packer (seated option)', 'CAT', '{"LIFT_KG":5,"PUSH_PULL_KG":10,"CLIMB":false,"AIRSIDE_DRIVE":false,"NIGHT":false,"KNIFE":false,"CHEMICAL":false,"RESPIRATOR":false,"NOISE":false,"HEAT":false,"HEIGHT":false,"FLIGHT_DUTY":false,"FOOD_HANDLING":true,"HOURS_PER_DAY":8,"SCREEN_HOURS":0,"SAFETY_CRITICAL":false}', 'HIGH', 'migration', now()),
    ('CATERING_STORES_ADMIN', 'Catering stores administrator', 'CAT', '{"LIFT_KG":5,"PUSH_PULL_KG":5,"CLIMB":false,"AIRSIDE_DRIVE":false,"NIGHT":false,"KNIFE":false,"CHEMICAL":false,"RESPIRATOR":false,"NOISE":false,"HEAT":false,"HEIGHT":false,"FLIGHT_DUTY":false,"FOOD_HANDLING":false,"HOURS_PER_DAY":8,"SCREEN_HOURS":6,"SAFETY_CRITICAL":false}', 'HIGH', 'migration', now()),
    ('CATERING_DISHWASH', 'Dish-wash operative', 'CAT', '{"LIFT_KG":15,"PUSH_PULL_KG":20,"CLIMB":false,"AIRSIDE_DRIVE":false,"NIGHT":true,"KNIFE":false,"CHEMICAL":true,"RESPIRATOR":false,"NOISE":true,"HEAT":true,"HEIGHT":false,"FLIGHT_DUTY":false,"FOOD_HANDLING":false,"HOURS_PER_DAY":10,"SCREEN_HOURS":0,"SAFETY_CRITICAL":false}', 'LOW', 'migration', now()),
    ('DF_SALES', 'Duty-free sales associate', 'DF', '{"LIFT_KG":10,"PUSH_PULL_KG":10,"CLIMB":false,"AIRSIDE_DRIVE":false,"NIGHT":true,"KNIFE":false,"CHEMICAL":false,"RESPIRATOR":false,"NOISE":false,"HEAT":false,"HEIGHT":false,"FLIGHT_DUTY":false,"FOOD_HANDLING":false,"HOURS_PER_DAY":9,"SCREEN_HOURS":2,"SAFETY_CRITICAL":false}', 'LOW', 'migration', now()),
    ('DF_STOCKROOM', 'Duty-free stockroom operative', 'DF', '{"LIFT_KG":20,"PUSH_PULL_KG":25,"CLIMB":true,"AIRSIDE_DRIVE":false,"NIGHT":true,"KNIFE":true,"CHEMICAL":false,"RESPIRATOR":false,"NOISE":false,"HEAT":false,"HEIGHT":true,"FLIGHT_DUTY":false,"FOOD_HANDLING":false,"HOURS_PER_DAY":9,"SCREEN_HOURS":0,"SAFETY_CRITICAL":false}', 'LOW', 'migration', now()),
    ('DIST_DRIVER', 'Distribution driver', 'DIST', '{"LIFT_KG":25,"PUSH_PULL_KG":30,"CLIMB":true,"AIRSIDE_DRIVE":true,"NIGHT":true,"KNIFE":false,"CHEMICAL":false,"RESPIRATOR":false,"NOISE":false,"HEAT":false,"HEIGHT":false,"FLIGHT_DUTY":false,"FOOD_HANDLING":false,"HOURS_PER_DAY":10,"SCREEN_HOURS":0,"SAFETY_CRITICAL":true}', 'HIGH', 'migration', now()),
    ('DIST_PLANNER', 'Logistics planner', 'DIST', '{"LIFT_KG":5,"PUSH_PULL_KG":5,"CLIMB":false,"AIRSIDE_DRIVE":false,"NIGHT":false,"KNIFE":false,"CHEMICAL":false,"RESPIRATOR":false,"NOISE":false,"HEAT":false,"HEIGHT":false,"FLIGHT_DUTY":false,"FOOD_HANDLING":false,"HOURS_PER_DAY":8,"SCREEN_HOURS":7,"SAFETY_CRITICAL":false}', 'HIGH', 'migration', now()),
    ('ENGINEER', 'Aircraft engineer', 'ENG', '{"LIFT_KG":25,"PUSH_PULL_KG":30,"CLIMB":true,"AIRSIDE_DRIVE":true,"NIGHT":true,"KNIFE":false,"CHEMICAL":true,"RESPIRATOR":true,"NOISE":true,"HEAT":true,"HEIGHT":true,"FLIGHT_DUTY":false,"FOOD_HANDLING":false,"HOURS_PER_DAY":12,"SCREEN_HOURS":2,"SAFETY_CRITICAL":true}', 'LOW', 'migration', now()),
    ('ENG_STORES', 'Engineering stores controller', 'ENG', '{"LIFT_KG":15,"PUSH_PULL_KG":20,"CLIMB":false,"AIRSIDE_DRIVE":false,"NIGHT":true,"KNIFE":false,"CHEMICAL":false,"RESPIRATOR":false,"NOISE":false,"HEAT":false,"HEIGHT":false,"FLIGHT_DUTY":false,"FOOD_HANDLING":false,"HOURS_PER_DAY":10,"SCREEN_HOURS":4,"SAFETY_CRITICAL":false}', 'MEDIUM', 'migration', now()),
    ('ENG_PLANNER', 'Maintenance planner', 'ENG', '{"LIFT_KG":5,"PUSH_PULL_KG":5,"CLIMB":false,"AIRSIDE_DRIVE":false,"NIGHT":false,"KNIFE":false,"CHEMICAL":false,"RESPIRATOR":false,"NOISE":false,"HEAT":false,"HEIGHT":false,"FLIGHT_DUTY":false,"FOOD_HANDLING":false,"HOURS_PER_DAY":8,"SCREEN_HOURS":7,"SAFETY_CRITICAL":false}', 'HIGH', 'migration', now()),
    ('SECURITY_OFFICER', 'Security screening officer', 'SEC', '{"LIFT_KG":15,"PUSH_PULL_KG":15,"CLIMB":false,"AIRSIDE_DRIVE":false,"NIGHT":true,"KNIFE":false,"CHEMICAL":false,"RESPIRATOR":false,"NOISE":false,"HEAT":false,"HEIGHT":false,"FLIGHT_DUTY":false,"FOOD_HANDLING":false,"HOURS_PER_DAY":10,"SCREEN_HOURS":5,"SAFETY_CRITICAL":true}', 'MEDIUM', 'migration', now()),
    ('FIRE_RESCUE', 'Airport fire and rescue officer', 'SEC', '{"LIFT_KG":40,"PUSH_PULL_KG":50,"CLIMB":true,"AIRSIDE_DRIVE":true,"NIGHT":true,"KNIFE":false,"CHEMICAL":true,"RESPIRATOR":true,"NOISE":true,"HEAT":true,"HEIGHT":true,"FLIGHT_DUTY":false,"FOOD_HANDLING":false,"HOURS_PER_DAY":12,"SCREEN_HOURS":0,"SAFETY_CRITICAL":true}', 'LOW', 'migration', now()),
    ('LOUNGE_HOST', 'Lounge host', 'HOSP', '{"LIFT_KG":10,"PUSH_PULL_KG":15,"CLIMB":false,"AIRSIDE_DRIVE":false,"NIGHT":true,"KNIFE":false,"CHEMICAL":false,"RESPIRATOR":false,"NOISE":false,"HEAT":false,"HEIGHT":false,"FLIGHT_DUTY":false,"FOOD_HANDLING":true,"HOURS_PER_DAY":9,"SCREEN_HOURS":1,"SAFETY_CRITICAL":false}', 'LOW', 'migration', now()),
    ('TRAINING_INSTRUCTOR', 'Training academy instructor', 'TRN', '{"LIFT_KG":10,"PUSH_PULL_KG":10,"CLIMB":false,"AIRSIDE_DRIVE":false,"NIGHT":false,"KNIFE":false,"CHEMICAL":false,"RESPIRATOR":false,"NOISE":false,"HEAT":false,"HEIGHT":false,"FLIGHT_DUTY":false,"FOOD_HANDLING":false,"HOURS_PER_DAY":8,"SCREEN_HOURS":4,"SAFETY_CRITICAL":false}', 'MEDIUM', 'migration', now()),
    ('OFFICE_ADMIN', 'Office administrator', 'CORP', '{"LIFT_KG":5,"PUSH_PULL_KG":5,"CLIMB":false,"AIRSIDE_DRIVE":false,"NIGHT":false,"KNIFE":false,"CHEMICAL":false,"RESPIRATOR":false,"NOISE":false,"HEAT":false,"HEIGHT":false,"FLIGHT_DUTY":false,"FOOD_HANDLING":false,"HOURS_PER_DAY":8,"SCREEN_HOURS":7,"SAFETY_CRITICAL":false}', 'HIGH', 'migration', now());

-- ---------- break-glass access (E03-BL-021) ----------
create table platform.break_glass_grant (
    id              uuid primary key,
    employee_id     varchar(64)   not null,
    granted_to      varchar(200)  not null,
    reason          text          not null,   -- encrypted
    granted_at      timestamptz   not null,
    expires_at      timestamptz   not null,
    review          varchar(20)   not null,
    review_due_at   timestamptz   not null,
    reviewed_by     varchar(200),
    reviewed_at     timestamptz,
    review_note     text                       -- encrypted
);
create index ix_break_glass_active on platform.break_glass_grant (granted_to, employee_id, expires_at);
create index ix_break_glass_review on platform.break_glass_grant (review, review_due_at);

-- ---------- disclosure log (E03-BL-022) ----------
create table platform.disclosure_log (
    id               uuid primary key,
    employee_id      varchar(64)   not null,
    recipient_type   varchar(20)   not null,
    recipient_name   varchar(200)  not null,
    resource_type    varchar(40)   not null,
    resource_id      varchar(64)   not null,
    resource_title   varchar(300)  not null,
    purpose          varchar(60)   not null,
    consent_id       uuid,
    disclosed_by     varchar(200)  not null,
    disclosed_at     timestamptz   not null,
    revoked_at       timestamptz,
    revoked_reason   varchar(200)
);
create index ix_disclosure_employee on platform.disclosure_log (employee_id, disclosed_at desc);
create index ix_disclosure_consent on platform.disclosure_log (consent_id);

-- ---------- single-run guard for scheduled jobs across instances ----------
create table platform.scheduled_job_run (
    job_name    varchar(100)  not null,
    run_key     varchar(40)   not null,
    started_at  timestamptz   not null,
    instance    varchar(200),
    primary key (job_name, run_key)
);

-- ---------- documents (E03-F06) ----------
create table docs.document (
    id               uuid primary key,
    document_number  varchar(30)   not null unique,
    series_id        uuid          not null,          -- all versions of one document share the series
    version          int           not null,
    type             varchar(40)   not null,
    template_code    varchar(60),
    title            varchar(300)  not null,
    employee_id      varchar(64)   not null,
    case_id          uuid,
    status           varchar(20)   not null,
    classification   varchar(30)   not null,
    content_type     varchar(100)  not null,
    size_bytes       int           not null,
    sha256           varchar(64)   not null,
    content          text          not null,          -- base64, encrypted (field level)
    fields           text,                            -- JSON merge fields, encrypted
    extracted_text   text,                            -- encrypted; searched through search_vector
    search_vector    tsvector,
    signed_by        varchar(200),
    signed_at        timestamptz,
    signature_sha256 varchar(64),
    legal_hold       boolean       not null default false,
    legal_hold_reason varchar(500),
    acknowledged_at  timestamptz,
    created_by       varchar(200)  not null,
    created_at       timestamptz   not null
);
create index ix_document_employee on docs.document (employee_id, created_at desc);
create index ix_document_case on docs.document (case_id);
create index ix_document_series on docs.document (series_id, version);
create index ix_document_search on docs.document using gin (search_vector);

create table docs.document_share (
    id            uuid primary key,
    document_id   uuid          not null references docs.document (id),
    target        varchar(20)   not null,
    recipient     varchar(200),           -- manager employee id / partner org code; null = employee / HR scope
    consent_id    uuid,
    shared_by     varchar(200)  not null,
    shared_at     timestamptz   not null,
    revoked_at    timestamptz,
    revoked_by    varchar(200),
    revoke_reason varchar(200)
);
create index ix_document_share_doc on docs.document_share (document_id);
create index ix_document_share_target on docs.document_share (target, recipient);

create table docs.document_sequence (
    year      int primary key,
    next_val  bigint not null
);

create table docs.retention_schedule (
    record_class     varchar(40)  primary key,
    retention_years  int          not null,
    trigger_event    varchar(30)  not null,
    legal_basis      varchar(500) not null,
    approved_by      varchar(200),
    approved_at      timestamptz,
    updated_by       varchar(200) not null,
    updated_at       timestamptz  not null
);
-- proposed defaults, pending DPO / CMO approval (E03-BL-017); verify against local law and aviation record rules
insert into docs.retention_schedule (record_class, retention_years, trigger_event, legal_basis, updated_by, updated_at) values
    ('OH_RECORD',            40, 'LAST_ENCOUNTER',  'Proposed: OH records with exposure history – long-term (benchmark: 40 years for health surveillance under hazardous-substance rules)', 'migration', now()),
    ('EXPOSURE_RECORD',      40, 'DOCUMENT_DATE',   'Proposed: exposure and surveillance records retained long-term (benchmark OSHA 1910.1020: employment + 30 years)', 'migration', now()),
    ('AEROMEDICAL_RECORD',   10, 'DOCUMENT_DATE',   'Proposed: aeromedical records per Part-MED / local CAA retention (verify, minimum 10 years)', 'migration', now()),
    ('FITNESS_CERTIFICATE',   6, 'DOCUMENT_DATE',   'Proposed: certificates retained for limitation period of employment claims', 'migration', now()),
    ('CORRESPONDENCE',        6, 'DOCUMENT_DATE',   'Proposed: letters and referral correspondence', 'migration', now()),
    ('PHOTO',                 6, 'DOCUMENT_DATE',   'Proposed: clinical photographs (wound tracking)', 'migration', now()),
    ('EXTERNAL_REPORT',      10, 'DOCUMENT_DATE',   'Proposed: scanned external reports', 'migration', now());

-- ---------- kernel: primary coded diagnosis (E03-F03) ----------
alter table casemgmt.oh_case
    add column primary_diagnosis_system  varchar(20),
    add column primary_diagnosis_code    varchar(50),
    add column primary_diagnosis_display varchar(500);

grant select on masterdata.fitness_standard, masterdata.restriction, masterdata.job_demand_profile to ${app_role};
grant insert, update on masterdata.fitness_standard, masterdata.restriction, masterdata.job_demand_profile to ${app_role};
grant select, insert, update on platform.break_glass_grant to ${app_role};
grant select, insert, update on platform.disclosure_log to ${app_role};
grant select, insert on platform.scheduled_job_run to ${app_role};
grant usage on schema docs to ${app_role};
grant select, insert, update on docs.document, docs.document_share, docs.document_sequence, docs.retention_schedule to ${app_role};
