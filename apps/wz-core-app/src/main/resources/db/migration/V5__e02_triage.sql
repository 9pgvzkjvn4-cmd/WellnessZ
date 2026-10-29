-- =====================================================================================================
-- V5 – E02 Clinical triage, risk stratification & case assignment (schema "triage")
-- =====================================================================================================
create schema if not exists triage;

create table triage.work_queue (
    code           varchar(40)  primary key,
    name           varchar(200) not null,
    description    varchar(500),
    required_role  varchar(40),
    email          varchar(320),
    restricted     boolean      not null default false
);
insert into triage.work_queue (code, name, description, required_role, email, restricted) values
    ('OH_GENERAL',      'OH nursing – general',         'Default nurse triage queue',                       'OH_CLINICIAN',    'oh-nursing@wellnessz.local',   false),
    ('AME_AEROMEDICAL', 'Aeromedical (AME)',            'Flight crew, cabin crew, ATC fitness',             'AME',             'aeromedical@wellnessz.local',  false),
    ('FOOD_SAFETY',     'Food safety – physician',      'Food handlers; exclusion decisions',               'OH_PHYSICIAN',    'oh-physicians@wellnessz.local', false),
    ('PHYSICIAN',       'OH physician',                 'Safety-critical ground roles, escalations',        'OH_PHYSICIAN',    'oh-physicians@wellnessz.local', false),
    ('MENTAL_HEALTH',   'Psychological health (restricted)', 'Restricted class – E09 / E10',               'PSYCH_CLINICIAN', 'peer-support@wellnessz.local', true),
    ('CMO',             'Chief Medical Officer',        'Medico-legal and complex cases',                   'CMO',             'cmo-office@wellnessz.local',   false);

create table triage.sla_policy (
    id                bigint generated always as identity primary key,
    case_type         varchar(40),
    urgency           varchar(20) not null,
    milestone         varchar(30) not null,
    target_minutes    int         not null,
    amber_percent     int         not null,
    escalate_to_role  varchar(40)
);
-- E02 KPI: urgent ≤ 1 h, routine ≤ 1 working day (480 min)
insert into triage.sla_policy (case_type, urgency, milestone, target_minutes, amber_percent, escalate_to_role) values
    (null, 'EMERGENCY',   'TRIAGE', 15,  75, 'NURSING_HEAD'),
    (null, 'URGENT',      'TRIAGE', 60,  75, 'NURSING_HEAD'),
    (null, 'SEMI_URGENT', 'TRIAGE', 240, 75, 'NURSING_HEAD'),
    (null, 'ROUTINE',     'TRIAGE', 480, 75, 'NURSING_HEAD'),
    (null, 'ADVICE',      'TRIAGE', 960, 75, 'NURSING_HEAD');

create table triage.sla_timer (
    id                        uuid primary key,
    case_id                   uuid        not null,
    milestone                 varchar(30) not null,
    started_at                timestamptz not null,
    due_at                    timestamptz not null,
    amber_at                  timestamptz not null,
    stopped_at                timestamptz,
    status                    varchar(20) not null,
    paused_remaining_seconds  bigint,
    amber_notified            boolean     not null default false,
    breached_at               timestamptz
);
create index ix_sla_timer_case on triage.sla_timer (case_id) where stopped_at is null;
create index ix_sla_timer_due on triage.sla_timer (due_at) where status = 'RUNNING';
create index ix_sla_timer_started on triage.sla_timer (started_at);

create table triage.assignment_rule (
    id               bigint generated always as identity primary key,
    name             varchar(200) not null,
    priority         int          not null,
    active           boolean      not null,
    subsidiary_code  varchar(20),
    site_code        varchar(40),
    language         varchar(8),
    case_type        varchar(40),
    safety_category  varchar(40),
    specialty        varchar(60),
    target_queue     varchar(40)  not null references triage.work_queue(code),
    strategy         varchar(20)  not null,
    target_username  varchar(200),
    version          bigint       not null default 0,
    updated_by       varchar(200) not null,
    updated_at       timestamptz  not null
);
insert into triage.assignment_rule (name, priority, active, language, case_type, target_queue, strategy, updated_by, updated_at) values
    ('Arabic-speaking employees → Arabic-speaking nurse', 10, true, 'ar', null, 'OH_GENERAL', 'LEAST_LOADED', 'migration', now()),
    ('French-speaking employees → French-speaking nurse', 11, true, 'fr', null, 'OH_GENERAL', 'LEAST_LOADED', 'migration', now()),
    ('Surveillance follow-up → physician',               20, true, null, 'SURVEILLANCE_FOLLOW_UP', 'PHYSICIAN', 'LEAST_LOADED', 'migration', now()),
    ('Default – least-loaded OH nurse',                  100, true, null, null, 'OH_GENERAL', 'LEAST_LOADED', 'migration', now());

create table triage.case_assignment (
    id             uuid primary key,
    case_id        uuid         not null,
    from_username  varchar(200),
    to_username    varchar(200),
    queue_code     varchar(40),
    reason         varchar(500),
    actor          varchar(200) not null,
    assigned_at    timestamptz  not null
);
create index ix_case_assignment_case on triage.case_assignment (case_id, assigned_at desc);
create index ix_case_assignment_user on triage.case_assignment (to_username, assigned_at desc);

create table triage.triage_protocol (
    id            bigint generated always as identity primary key,
    code          varchar(40)  not null,
    version       int          not null,
    title         varchar(200) not null,
    presentation  varchar(60)  not null,
    definition    text         not null,
    status        varchar(20)  not null,
    approved_by   varchar(200),
    approved_at   timestamptz,
    created_at    timestamptz  not null,
    unique (code, version)
);

create table triage.triage_assessment (
    id                uuid primary key,
    case_id           uuid         not null,
    protocol_code     varchar(40)  not null,
    protocol_version  int          not null,
    mode              varchar(20)  not null,
    answers           text         not null,   -- encrypted
    red_flags         varchar(500),
    computed_urgency  varchar(20)  not null,
    final_urgency     varchar(20)  not null,
    disposition       varchar(500),
    advice_key        varchar(60),
    override_reason   text,                    -- encrypted
    call_log          text,                    -- encrypted
    assessed_by       varchar(200) not null,
    assessed_at       timestamptz  not null
);
create index ix_triage_assessment_case on triage.triage_assessment (case_id, assessed_at desc);
create index ix_triage_assessment_at on triage.triage_assessment (assessed_at);

create table triage.escalation (
    id               uuid primary key,
    case_id          uuid         not null,
    level            varchar(30)  not null,
    reason_code      varchar(40)  not null,
    note             text,         -- encrypted
    requested_by     varchar(200) not null,
    requested_at     timestamptz  not null,
    due_at           timestamptz  not null,
    acknowledged_by  varchar(200),
    acknowledged_at  timestamptz,
    decision         text,         -- encrypted
    decided_by       varchar(200),
    decided_at       timestamptz,
    status           varchar(20)  not null
);
create index ix_escalation_case on triage.escalation (case_id);
create index ix_escalation_open on triage.escalation (status, due_at);

create table triage.case_conference (
    id            uuid primary key,
    case_id       uuid          not null,
    scheduled_at  timestamptz   not null,
    attendees     varchar(2000) not null,
    agenda        text,          -- encrypted
    minutes       text,          -- encrypted
    decisions     text,          -- encrypted
    visibility    varchar(20)   not null,
    created_by    varchar(200)  not null,
    created_at    timestamptz   not null
);

create table triage.case_risk_flags (
    case_id                uuid primary key,
    comorbidity            boolean      not null,
    psychosocial_positive  boolean      not null,
    claim_or_litigation    boolean      not null,
    primary_icd10          varchar(10),
    updated_by             varchar(200) not null,
    updated_at             timestamptz  not null
);

create table triage.complexity_assessment (
    id                      uuid primary key,
    case_id                 uuid        not null,
    score                   int         not null,
    drivers                 text        not null,
    intensity               varchar(20) not null,
    expected_duration_days  int,
    actual_absence_days     int,
    calculated_at           timestamptz not null
);
create index ix_complexity_case on triage.complexity_assessment (case_id, calculated_at desc);

create table triage.duration_guideline (
    icd_code       varchar(10)  primary key,
    title          varchar(200) not null,
    expected_days  int          not null,
    source         varchar(100) not null
);
-- in-house illustrative reference values (E02-BL-014); replace with licensed guideline content (MDGuidelines / ODG)
insert into triage.duration_guideline values
    ('S93.4', 'Sprain of ankle', 14, 'In-house draft'),
    ('S61.4', 'Open wound of hand', 7, 'In-house draft'),
    ('M54.5', 'Low back pain', 21, 'In-house draft'),
    ('S83.6', 'Sprain of knee', 28, 'In-house draft'),
    ('S52.5', 'Fracture of lower end of radius', 56, 'In-house draft'),
    ('T67.0', 'Heatstroke and sunstroke', 3, 'In-house draft'),
    ('J20.9', 'Acute bronchitis', 7, 'In-house draft'),
    ('F43.2', 'Adjustment disorders', 42, 'In-house draft');

create table triage.case_checklist (
    id            uuid primary key,
    case_id       uuid         not null,
    code          varchar(20)  not null,
    answers       text         not null,   -- encrypted
    outcome       varchar(20)  not null,
    completed_by  varchar(200) not null,
    completed_at  timestamptz  not null
);
create index ix_case_checklist_case on triage.case_checklist (case_id);

create table triage.handover (
    id                   uuid primary key,
    case_id              uuid         not null,
    from_username        varchar(200) not null,
    to_username          varchar(200) not null,
    summary              text         not null,   -- encrypted
    outstanding_actions  text,                    -- encrypted
    next_contact_at      timestamptz,
    created_at           timestamptz  not null,
    acknowledged_at      timestamptz
);
create index ix_handover_pending on triage.handover (to_username) where acknowledged_at is null;

create table triage.saved_view (
    id        uuid primary key,
    username  varchar(200)  not null,
    name      varchar(100)  not null,
    queue     varchar(30)   not null,
    filters   varchar(2000) not null
);

create table triage.triage_audit_sample (
    id             uuid primary key,
    case_id        uuid         not null,
    assessment_id  uuid         not null unique,
    sampled_at     timestamptz  not null,
    reviewer       varchar(200),
    agree          boolean,
    comment        text,          -- encrypted
    reviewed_at    timestamptz
);

create table triage.case_advice (
    id           uuid primary key,
    case_id      uuid         not null,
    employee_id  varchar(64)  not null,
    advice_key   varchar(60)  not null,
    language     varchar(8),
    sent_by      varchar(200) not null,
    sent_at      timestamptz  not null
);
create index ix_case_advice_employee on triage.case_advice (employee_id, sent_at desc);

grant usage on schema triage to ${app_role};
grant select on triage.work_queue, triage.duration_guideline to ${app_role};
grant select, insert, update, delete on triage.assignment_rule, triage.saved_view to ${app_role};
grant select, update on triage.sla_policy to ${app_role};
grant select, insert, update on triage.sla_timer, triage.triage_protocol, triage.escalation, triage.case_conference,
      triage.case_risk_flags, triage.handover, triage.triage_audit_sample to ${app_role};
grant select, insert on triage.case_assignment, triage.triage_assessment, triage.complexity_assessment, triage.case_checklist,
      triage.case_advice to ${app_role};
