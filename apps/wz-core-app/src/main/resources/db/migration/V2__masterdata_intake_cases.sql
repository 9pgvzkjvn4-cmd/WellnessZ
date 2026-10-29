-- =====================================================================================================
-- V2 – Master data (E18), EHS intake, case kernel, de-identified analytics views (E17)
-- =====================================================================================================
create table masterdata.org_unit (
    code         varchar(64)   primary key,
    name         varchar(200)  not null,
    parent_code  varchar(64)   references masterdata.org_unit(code),
    path         varchar(1000) not null,
    unit_type    varchar(40)   not null,
    active       boolean       not null default true,
    updated_at   timestamptz   not null
);
create index ix_org_unit_path on masterdata.org_unit (path varchar_pattern_ops);

create table masterdata.job_code (
    code             varchar(64)  primary key,
    title            varchar(200) not null,
    safety_critical  boolean      not null,
    hazard_codes     varchar(1000)
);

create table masterdata.hazard (
    code                          varchar(64)  primary key,
    name                          varchar(200) not null,
    category                      varchar(60)  not null,
    surveillance_required         boolean      not null,
    surveillance_interval_months  int
);

create table masterdata.config_entry (
    config_key    varchar(200) primary key,
    config_value  text         not null,
    description   varchar(500),
    updated_by    varchar(200) not null,
    updated_at    timestamptz  not null,
    version       bigint       not null default 0
);

-- reference data seed (illustrative; replaced by HRIS sync)
insert into masterdata.org_unit (code, name, parent_code, path, unit_type, updated_at) values
    ('AIRLINE', 'WellnessZ Airline', null, '/AIRLINE', 'COMPANY', now()),
    ('OPS', 'Operations', 'AIRLINE', '/AIRLINE/OPS', 'DIVISION', now()),
    ('FLIGHTOPS', 'Flight Operations', 'OPS', '/AIRLINE/OPS/FLIGHTOPS', 'DEPARTMENT', now()),
    ('CABIN', 'Cabin Crew', 'OPS', '/AIRLINE/OPS/CABIN', 'DEPARTMENT', now()),
    ('GROUND', 'Ground Handling', 'OPS', '/AIRLINE/OPS/GROUND', 'DEPARTMENT', now()),
    ('ENG', 'Engineering & Maintenance', 'AIRLINE', '/AIRLINE/ENG', 'DIVISION', now()),
    ('CATERING', 'Catering', 'AIRLINE', '/AIRLINE/CATERING', 'DIVISION', now()),
    ('CARGO', 'Cargo', 'AIRLINE', '/AIRLINE/CARGO', 'DIVISION', now());
insert into masterdata.hazard (code, name, category, surveillance_required, surveillance_interval_months) values
    ('NOISE', 'Occupational noise', 'PHYSICAL', true, 12),
    ('COSMIC_RAD', 'Cosmic radiation', 'PHYSICAL', true, 12),
    ('FATIGUE', 'Fatigue / circadian disruption', 'PSYCHOSOCIAL', false, null),
    ('MANUAL_HANDLING', 'Manual handling', 'ERGONOMIC', false, null),
    ('FOOD_HYGIENE', 'Food handler hygiene', 'BIOLOGICAL', true, 12),
    ('JET_FUEL', 'Jet fuel / chemical exposure', 'CHEMICAL', true, 24);
insert into masterdata.job_code (code, title, safety_critical, hazard_codes) values
    ('PILOT', 'Pilot', true, 'NOISE,COSMIC_RAD,FATIGUE'),
    ('CABIN_CREW', 'Cabin crew', true, 'COSMIC_RAD,FATIGUE,MANUAL_HANDLING'),
    ('RAMP_AGENT', 'Ramp agent', true, 'NOISE,MANUAL_HANDLING,JET_FUEL'),
    ('ENGINEER', 'Aircraft engineer', true, 'NOISE,JET_FUEL'),
    ('CATERING_ASSOC', 'Catering associate', false, 'FOOD_HYGIENE,MANUAL_HANDLING');
insert into masterdata.config_entry (config_key, config_value, description, updated_by, updated_at) values
    ('modules.e01.enabled', 'true', 'Intake & incident-to-case', 'migration', now()),
    ('analytics.min-group-size', '5', 'Minimum group size for published aggregates', 'migration', now());

-- ---------- EHS intake ----------
create table intake.import_batch (
    id              uuid primary key,
    channel         varchar(20)  not null,
    source_name     varchar(300),
    content_sha256  varchar(64),
    rows_total      int not null default 0,
    rows_accepted   int not null default 0,
    rows_duplicate  int not null default 0,
    rows_rejected   int not null default 0,
    errors          text,
    created_by      varchar(200) not null,
    created_at      timestamptz  not null
);

create table intake.ehs_incident (
    id             uuid primary key,
    source_system  varchar(100) not null,
    external_ref   varchar(100) not null,
    occurred_at    timestamptz,
    location       varchar(200),
    employee_id    varchar(64),
    employee_name  varchar(200),
    org_unit_code  varchar(64),
    incident_type  varchar(100),
    severity       varchar(20),
    injury         boolean      not null,
    description    text,        -- encrypted (field level)
    channel        varchar(20)  not null,
    status         varchar(20)  not null,
    batch_id       uuid references intake.import_batch(id),
    received_at    timestamptz  not null,
    constraint uq_ehs_incident_ref unique (source_system, external_ref)
);

-- ---------- case kernel ----------
create sequence casemgmt.case_ref_seq start 1;
create table casemgmt.oh_case (
    id                     uuid primary key,
    case_ref               varchar(20)  not null unique,
    employee_id            varchar(64)  not null,
    employee_display_name  varchar(200),
    org_unit_code          varchar(64),
    org_unit_path          varchar(1000),
    category               varchar(40)  not null,
    source                 varchar(40)  not null,
    source_ref             varchar(200),
    status                 varchar(40)  not null,
    classification         varchar(40)  not null,
    fitness_status         varchar(40)  not null,
    restriction_until      date,
    clinical_summary       text,        -- encrypted (field level)
    created_at             timestamptz  not null,
    updated_at             timestamptz  not null,
    version                bigint       not null default 0
);
create index ix_case_employee on casemgmt.oh_case (employee_id);
create index ix_case_org_path on casemgmt.oh_case (org_unit_path varchar_pattern_ops);
create unique index uq_case_source_ref on casemgmt.oh_case (source, source_ref) where source_ref is not null;

-- ---------- E17: de-identified analytics view ----------
-- no names, no employee ids, no free text, restricted class excluded from org-level breakdowns
create view analytics.v_case_facts as
select c.org_unit_code,
       c.category,
       c.source,
       c.status,
       c.fitness_status,
       date_trunc('month', c.created_at)::date as opened_month
  from casemgmt.oh_case c
 where c.classification <> 'RESTRICTED_CLINICAL';

grant usage on schema masterdata, intake, casemgmt, analytics to ${app_role};
grant select, insert, update on all tables in schema masterdata to ${app_role};
grant select, insert, update on all tables in schema intake to ${app_role};
grant select, insert, update on all tables in schema casemgmt to ${app_role};
grant usage on sequence casemgmt.case_ref_seq to ${app_role};
grant select on analytics.v_case_facts to ${app_role};
