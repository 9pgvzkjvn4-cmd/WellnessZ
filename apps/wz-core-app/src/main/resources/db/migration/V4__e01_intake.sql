-- =====================================================================================================
-- V4 – E01 Case intake & incident-triggered case initiation
-- =====================================================================================================
alter table intake.ehs_incident
    add column incident_class  varchar(40),
    add column body_part       varchar(60),
    add column lost_time       boolean,
    add column exposure_type   varchar(60),
    add column unit_code       varchar(20),
    add column shift           varchar(40),
    add column reporter_email  varchar(320);
-- multi-casualty: one row per injured person (E01-BL-020)
alter table intake.ehs_incident drop constraint uq_ehs_incident_ref;
create unique index uq_ehs_incident_ref_person on intake.ehs_incident (source_system, external_ref, coalesce(employee_id, ''));
create index ix_ehs_incident_received on intake.ehs_incident (received_at desc);
create index ix_ehs_incident_employee on intake.ehs_incident (employee_id, received_at desc);

create table intake.incident_dlq (
    id           uuid primary key,
    received_at  timestamptz  not null,
    source       varchar(100) not null,
    reason       varchar(500) not null,
    payload      text         not null,   -- encrypted
    status       varchar(20)  not null,
    resolved_by  varchar(200),
    resolved_at  timestamptz
);

create table intake.intake_rule (
    id              bigint generated always as identity primary key,
    name            varchar(200) not null,
    priority        int          not null,
    active          boolean      not null,
    incident_class  varchar(40),
    min_severity    varchar(20),
    lost_time       boolean,
    injury          boolean,
    exposure_type   varchar(60),
    unit_code       varchar(20),
    body_part       varchar(60),
    action          varchar(20)  not null check (action in ('AUTO_CREATE','SUGGEST','IGNORE')),
    case_type       varchar(40),
    urgency         varchar(20),
    version         bigint       not null default 0,
    updated_by      varchar(200) not null,
    updated_at      timestamptz  not null
);
insert into intake.intake_rule (name, priority, active, incident_class, lost_time, injury, min_severity, action, case_type, urgency, updated_by, updated_at) values
    ('Any lost-time incident',                     5, true, null,                       true, null, null,     'AUTO_CREATE', 'INJURY',   'URGENT',      'migration', now()),
    ('Lost-time injury',                          10, true, 'LOST_TIME_INJURY',         null, null, null,     'AUTO_CREATE', 'INJURY',   'URGENT',      'migration', now()),
    ('Injury – medical treatment',                20, true, 'INJURY_MEDICAL_TREATMENT', null, null, null,     'AUTO_CREATE', 'INJURY',   'SEMI_URGENT', 'migration', now()),
    ('Injury – first aid plus',                   30, true, 'INJURY_FIRST_AID_PLUS',    null, null, null,     'AUTO_CREATE', 'INJURY',   'ROUTINE',     'migration', now()),
    ('Body-fluid exposure',                       40, true, 'BODY_FLUID_EXPOSURE',      null, null, null,     'AUTO_CREATE', 'EXPOSURE', 'URGENT',      'migration', now()),
    ('Fume event',                                41, true, 'FUME_EVENT',               null, null, null,     'AUTO_CREATE', 'EXPOSURE', 'URGENT',      'migration', now()),
    ('Heat illness',                              42, true, 'HEAT_ILLNESS',             null, null, null,     'AUTO_CREATE', 'ILLNESS',  'URGENT',      'migration', now()),
    ('Occupational illness',                      50, true, 'OCCUPATIONAL_ILLNESS',     null, null, null,     'AUTO_CREATE', 'ILLNESS',  'ROUTINE',     'migration', now()),
    ('Other exposure – nurse review',             60, true, 'EXPOSURE',                 null, null, null,     'SUGGEST',     'EXPOSURE', 'ROUTINE',     'migration', now()),
    ('First aid only – nurse review',             70, true, 'INJURY_FIRST_AID',         null, null, null,     'SUGGEST',     'INJURY',   'ADVICE',      'migration', now()),
    ('Near miss – no health consequence',         80, true, 'NEAR_MISS',                null, null, null,     'IGNORE',      null,       null,          'migration', now()),
    ('Property damage only',                      81, true, 'PROPERTY_DAMAGE',          null, null, null,     'IGNORE',      null,       null,          'migration', now()),
    ('Legacy feed: injury flag',                  90, true, null,                       null, true, null,     'AUTO_CREATE', 'INJURY',   'ROUTINE',     'migration', now()),
    ('Legacy feed: medium+ severity',             95, true, null,                       null, null, 'MEDIUM', 'SUGGEST',     'OTHER',    'ROUTINE',     'migration', now());

create table intake.incident_evaluation (
    incident_id     uuid primary key references intake.ehs_incident(id),
    rule_id         bigint,
    rule_name       varchar(200),
    outcome         varchar(20)  not null,
    case_id         uuid,
    dismiss_reason  varchar(40),
    dismiss_note    varchar(500),
    decided_by      varchar(200),
    decided_at      timestamptz,
    reporter_email  varchar(320),
    evaluated_at    timestamptz  not null
);
create index ix_incident_eval_outcome on intake.incident_evaluation (outcome, evaluated_at desc);
create index ix_incident_eval_case on intake.incident_evaluation (case_id);

create table intake.encounter (
    id              uuid primary key,
    client_ref      uuid         not null unique,
    mode            varchar(20)  not null,
    employee_id     varchar(64)  not null,
    employee_name   varchar(200),
    occurred_at     timestamptz  not null,
    site_code       varchar(40),
    location        varchar(200),
    chief_complaint text,          -- encrypted
    action_taken    text,          -- encrypted
    notes           text,          -- encrypted
    status          varchar(20)  not null,
    enrichment_due  boolean      not null,
    case_id         uuid,
    case_ref        varchar(30),
    created_by      varchar(200) not null,
    created_at      timestamptz  not null,
    enriched_at     timestamptz
);
create index ix_encounter_status on intake.encounter (status, occurred_at desc);
create index ix_encounter_employee on intake.encounter (employee_id, occurred_at desc);

create table intake.absence_episode (
    id               uuid primary key,
    source_ref       varchar(100) not null unique,
    employee_id      varchar(64)  not null,
    start_date       date         not null,
    end_date         date,
    reason_category  varchar(40),
    received_at      timestamptz  not null
);
create index ix_absence_employee on intake.absence_episode (employee_id, start_date);

create table intake.trigger_event (
    id            uuid primary key,
    trigger_type  varchar(30)  not null,
    source_ref    varchar(100) not null,
    employee_id   varchar(64)  not null,
    detail_code   varchar(60),
    outcome       varchar(20)  not null,
    case_id       uuid,
    received_at   timestamptz  not null
);
create index ix_trigger_received on intake.trigger_event (received_at desc);
create index ix_trigger_employee on intake.trigger_event (employee_id, received_at desc);

create table intake.recordability_assessment (
    id              uuid primary key,
    case_id         uuid         not null,
    version         int          not null,
    answers         text         not null,   -- encrypted
    computed        varchar(30)  not null,
    final_class     varchar(30)  not null,
    work_related    varchar(20)  not null,
    reason_code     varchar(40),
    rationale       text,                    -- encrypted
    local_category  varchar(100),
    caa_reportable  boolean      not null,
    assessed_by     varchar(200) not null,
    assessed_at     timestamptz  not null,
    unique (case_id, version)
);

create table intake.recordability_mapping (
    recordability   varchar(30)  primary key,
    local_category  varchar(100) not null,
    caa_reportable  boolean      not null,
    description     varchar(500),
    updated_by      varchar(200) not null,
    updated_at      timestamptz  not null
);
-- placeholder mapping (E01-BL-022): to be replaced with the national OH&S / CAA mapping signed off by CMO and HSE Head
insert into intake.recordability_mapping values
    ('NOT_CLASSIFIED',    'Not classified',                       false, 'Awaiting classification', 'migration', now()),
    ('FIRST_AID',         'First aid – not recordable',           false, 'Internal statistics only', 'migration', now()),
    ('MEDICAL_TREATMENT', 'Recordable – medical treatment',       false, 'Regulatory log entry', 'migration', now()),
    ('RESTRICTED_DUTY',   'Recordable – restricted / transfer',   false, 'Regulatory log entry', 'migration', now()),
    ('LOST_TIME',         'Recordable – lost-time injury (LTI)',  true,  'Regulatory log; notify authority where thresholds apply', 'migration', now()),
    ('FATALITY',          'Reportable – fatality',                true,  'Immediate notification to authority and CAA occurrence report', 'migration', now());

create table intake.notification_rule (
    id            bigint generated always as identity primary key,
    event         varchar(20)  not null,
    source        varchar(40),
    case_type     varchar(40),
    recipient     varchar(20)  not null,
    channel       varchar(10)  not null,
    template_key  varchar(100) not null,
    active        boolean      not null
);
-- notification matrix (E01-BL-025) – content-free templates; subject to DPO / CMO sign-off
insert into intake.notification_rule (event, source, case_type, recipient, channel, template_key, active) values
    ('CASE_OPENED',   null,               null, 'OH_QUEUE',        'EMAIL', 'case.opened.team',        true),
    ('CASE_OPENED',   'EHS_INCIDENT',     null, 'HSE_REPORTER',    'EMAIL', 'case.opened.stakeholder', true),
    ('CASE_OPENED',   'EHS_INCIDENT',     null, 'LINE_MANAGER',    'EMAIL', 'case.opened.stakeholder', true),
    ('CASE_OPENED',   'MANAGER_REFERRAL', null, 'LINE_MANAGER',    'EMAIL', 'case.opened.stakeholder', true),
    ('CASE_OPENED',   null,               null, 'CREW_SCHEDULING', 'EMAIL', 'case.opened.stakeholder', true),
    ('CASE_OPENED',   null,               null, 'HYGIENE_MANAGER', 'EMAIL', 'case.opened.stakeholder', true),
    ('CASE_ASSIGNED', null,               null, 'CASE_OWNER',      'EMAIL', 'case.assigned',           true),
    ('SLA_AT_RISK',   null,               null, 'CASE_OWNER',      'EMAIL', 'sla.alert',               true),
    ('SLA_BREACHED',  null,               null, 'NURSING_HEAD',    'EMAIL', 'sla.alert',               true),
    ('ESCALATED',     null,               null, 'OH_QUEUE',        'EMAIL', 'escalation.raised',       true);

insert into masterdata.config_entry (config_key, config_value, description, updated_by, updated_at) values
    ('modules.e02.enabled', 'true', 'Clinical triage, risk stratification & assignment', 'migration', now()),
    ('intake.absence.default.consecutive-days', '3', 'Watch case when an absence exceeds N consecutive days', 'migration', now()),
    ('intake.absence.default.episodes-in-90-days', '3', 'Watch case after N absence episodes within 90 days', 'migration', now()),
    ('intake.absence.CAT.consecutive-days', '2', 'Catering: food-handler sick-exclusion rules – earlier intervention', 'migration', now()),
    ('triage.complexity.intensive-threshold', '60', 'Score at which intensive case management starts', 'migration', now())
on conflict (config_key) do nothing;

grant select, insert, update on intake.incident_dlq, intake.incident_evaluation, intake.encounter, intake.absence_episode,
      intake.trigger_event, intake.recordability_mapping to ${app_role};
grant select, insert, update, delete on intake.intake_rule, intake.notification_rule to ${app_role};
grant select, insert on intake.recordability_assessment to ${app_role};     -- versioned, append-only
