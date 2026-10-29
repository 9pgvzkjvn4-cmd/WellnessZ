create schema if not exists absence;

-- V10 – E06 Absence & sickness management (schema absence).
-- Absence reasons (reason_category) are OH-only; clinical free text (contact notes, advice context, certificate OCR fields,
-- validation notes, employee decline reasons) is encrypted at field level by the application (columns marked "encrypted").
-- Manager / HR facing free text (HR stage notes, advice report wording, employee comments) is screened before it is stored.

-- ---------- feed runs & data quality (E06-F01) ----------
create table absence.feed_run (
    id              uuid primary key,
    source_system   varchar(40)   not null,
    run_type        varchar(20)   not null,           -- PUSH | PULL
    started_at      timestamptz   not null,
    finished_at     timestamptz,
    received        int           not null default 0,
    matched         int           not null default 0,
    unmatched       int           not null default 0,
    updated         int           not null default 0,
    triggered       int           not null default 0,
    errors          int           not null default 0,
    error_detail    varchar(2000),
    started_by      varchar(200)  not null
);
create index ix_absence_feed_run_started on absence.feed_run (started_at desc);

create table absence.episode (
    id               uuid primary key,
    source_system    varchar(40)   not null,
    source_ref       varchar(200)  not null,
    employee_id      varchar(64)   not null,
    start_date       date          not null,
    end_date         date,
    absence_type     varchar(30)   not null,
    reason_category  varchar(30),                      -- OH only
    certified        boolean       not null default false,
    case_id          uuid,
    feed_run_id      uuid,
    lta_signalled    boolean       not null default false,
    created_at       timestamptz   not null,
    updated_at       timestamptz   not null,
    constraint uq_absence_episode_source unique (source_system, source_ref),
    constraint ck_absence_episode_dates check (end_date is null or end_date >= start_date),
    constraint ck_absence_episode_type check (absence_type in ('SICKNESS','INJURY_WORK','INJURY_NON_WORK','MATERNITY_RELATED','OTHER_AUTHORISED'))
);
create index ix_absence_episode_employee on absence.episode (employee_id, start_date desc);
create index ix_absence_episode_start on absence.episode (start_date);

create table absence.feed_mismatch (
    id                    uuid primary key,
    run_id                uuid          not null,
    source_system         varchar(40)   not null,
    source_ref            varchar(200)  not null,
    employee_ref          varchar(64)   not null,
    start_date            date          not null,
    end_date              date,
    absence_type          varchar(30)   not null,
    reason_category       varchar(30),
    certified             boolean       not null default false,
    reason                varchar(40)   not null,       -- UNKNOWN_EMPLOYEE
    status                varchar(20)   not null,       -- OPEN | RESOLVED | IGNORED
    created_at            timestamptz   not null,
    resolved_by           varchar(200),
    resolved_at           timestamptz,
    resolved_employee_id  varchar(64),
    resolution_note       varchar(500),
    episode_id            uuid
);
create index ix_absence_mismatch_status on absence.feed_mismatch (status, created_at desc);
create unique index uq_absence_mismatch_open on absence.feed_mismatch (source_system, source_ref) where status = 'OPEN';

-- ---------- trigger rules & early-intervention queue (E06-F02) ----------
create table absence.trigger_rule (
    id               uuid primary key,
    code             varchar(40)   not null,
    name             varchar(200)  not null,
    subsidiary_code  varchar(20),
    rule_type        varchar(30)   not null,
    params           varchar(2000) not null,           -- JSON
    action           varchar(30)   not null,
    active           boolean       not null default true,
    version          int           not null default 1,
    updated_by       varchar(200)  not null,
    updated_at       timestamptz   not null,
    constraint uq_absence_rule_code unique (code),
    constraint ck_absence_rule_type check (rule_type in ('DURATION','FREQUENCY','PATTERN','POST_INCIDENT','SAFETY_CRITICAL','CREW_SICKNESS')),
    constraint ck_absence_rule_action check (action in ('EARLY_INTERVENTION','LONG_TERM','AME_REVIEW'))
);

create table absence.trigger_rule_version (
    id          uuid primary key,
    rule_id     uuid          not null,
    version     int           not null,
    snapshot    varchar(4000) not null,                -- JSON of the rule as saved
    change_type varchar(20)   not null,                -- CREATE | UPDATE | DEACTIVATE
    changed_by  varchar(200)  not null,
    changed_at  timestamptz   not null
);
create index ix_absence_rule_version on absence.trigger_rule_version (rule_id, version);

create table absence.queue_item (
    id                uuid primary key,
    employee_id       varchar(64)   not null,
    rule_id           uuid,
    rule_code         varchar(40)   not null,
    rule_name         varchar(200)  not null,
    action            varchar(30)   not null,
    status            varchar(20)   not null,           -- NEW | ACCEPTED | CONTACTED | CLOSED
    raised_at         timestamptz   not null,
    sla_due_at        timestamptz   not null,
    episode_id        uuid,
    detail            varchar(500),
    case_id           uuid,
    case_ref          varchar(30),
    assigned_to       varchar(200),
    accepted_at       timestamptz,
    first_contact_at  timestamptz,
    closed_at         timestamptz,
    closed_by         varchar(200),
    close_reason      varchar(500)
);
create unique index uq_absence_queue_open on absence.queue_item (employee_id, action) where status <> 'CLOSED';
create index ix_absence_queue_status on absence.queue_item (status, raised_at);

create table absence.contact (
    id                uuid primary key,
    queue_item_id     uuid,
    case_id           uuid,
    employee_id       varchar(64)   not null,
    contacted_at      timestamptz   not null,
    channel           varchar(20)   not null,
    outcome           varchar(20)   not null,
    note              text,                              -- encrypted
    contacted_by      varchar(200)  not null,
    next_contact_due  date
);
create index ix_absence_contact_case on absence.contact (case_id, contacted_at);
create index ix_absence_contact_employee on absence.contact (employee_id, contacted_at);

-- ---------- medical certificates (E06-F03) ----------
create table absence.certificate (
    id                   uuid primary key,
    employee_id          varchar(64)   not null,
    episode_id           uuid,
    source               varchar(30)   not null,         -- EMPLOYEE_UPLOAD | CLINIC_SCAN | PARTNER_ELECTRONIC
    document_id          uuid,
    from_date            date          not null,
    to_date              date          not null,
    issuer_name          varchar(200),
    issuer_registration  varchar(40),
    certificate_number   varchar(60),
    partner_org          varchar(60),
    issue_date           date,
    fit_note             varchar(20),
    extracted            text,                            -- encrypted JSON (OCR fields)
    checklist            varchar(1000),                   -- JSON
    status               varchar(30)   not null,
    duplicate_of_id      uuid,
    alteration_flags     varchar(300),
    sha256               varchar(64),
    received_at          timestamptz   not null,
    received_by          varchar(200)  not null,
    validated_by         varchar(200),
    validated_at         timestamptz,
    validation_note      text,                            -- encrypted
    verified_by          varchar(200),
    verified_at          timestamptz,
    escalated            boolean       not null default false,
    constraint ck_absence_certificate_dates check (to_date >= from_date)
);
create index ix_absence_certificate_employee on absence.certificate (employee_id, from_date desc);
create index ix_absence_certificate_status on absence.certificate (status, received_at desc);
create index ix_absence_certificate_sha on absence.certificate (sha256);

create table absence.certificate_call (
    id              uuid primary key,
    certificate_id  uuid          not null,
    called_party    varchar(200)  not null,
    outcome         varchar(20)   not null,
    note            text,                                 -- encrypted
    called_by       varchar(200)  not null,
    called_at       timestamptz   not null
);
create index ix_absence_certificate_call on absence.certificate_call (certificate_id, called_at);

-- ---------- long-term absence pathway (E06-F04) ----------
create table absence.lta_pathway (
    case_id        uuid primary key,
    case_ref       varchar(30)   not null,
    employee_id    varchar(64)   not null,
    episode_id     uuid,
    absence_start  date          not null,
    status         varchar(20)   not null,             -- ACTIVE | CLOSED
    hr_stage       varchar(30)   not null default 'NONE',
    hr_stage_note  varchar(1000),
    hr_stage_by    varchar(200),
    hr_stage_at    timestamptz,
    rtw_date       date,
    rtw_plan_id    uuid,
    started_at     timestamptz   not null,
    closed_at      timestamptz
);
create unique index uq_absence_lta_active on absence.lta_pathway (employee_id) where status = 'ACTIVE';

create table absence.lta_milestone (
    id                   uuid primary key,
    case_id              uuid          not null,
    week                 int           not null,
    milestone_type       varchar(30)   not null,
    due_date             date          not null,
    status               varchar(20)   not null,       -- PENDING | DONE | OVERDUE | SKIPPED
    completed_at         timestamptz,
    completed_by         varchar(200),
    note                 text,                          -- encrypted
    overdue_notified_at  timestamptz,
    appointment_id       uuid
);
create index ix_absence_milestone_case on absence.lta_milestone (case_id, due_date);
create index ix_absence_milestone_due on absence.lta_milestone (status, due_date);

create table absence.case_conference (
    id             uuid primary key,
    case_id        uuid          not null,
    scheduled_for  timestamptz   not null,
    participants   varchar(100)  not null,
    note           text,                                -- encrypted
    created_by     varchar(200)  not null,
    created_at     timestamptz   not null
);
create index ix_absence_conference_case on absence.case_conference (case_id);

-- ---------- management advice requests & OH advice reports (E06-F05) ----------
create sequence absence.advice_ref_seq;

create table absence.advice_request (
    id                     uuid primary key,
    ref                    varchar(30)   not null unique,
    employee_id            varchar(64)   not null,
    requested_by           varchar(200)  not null,
    requester_role         varchar(20)   not null,     -- MANAGER | HR
    requester_employee_id  varchar(64),
    question_codes         varchar(400)  not null,
    context                text,                        -- encrypted
    episode_id             uuid,
    status                 varchar(30)   not null,
    requested_at           timestamptz   not null,
    sla_due_at             timestamptz   not null,
    case_id                uuid,
    case_ref               varchar(30),
    accepted_by            varchar(200),
    accepted_at            timestamptz,
    withdrawn_at           timestamptz,
    updated_at             timestamptz   not null
);
create index ix_absence_advice_employee on absence.advice_request (employee_id, requested_at desc);
create index ix_absence_advice_status on absence.advice_request (status, requested_at);

create table absence.advice_report (
    id                uuid primary key,
    request_id        uuid          not null,
    version           int           not null,
    status            varchar(30)   not null,           -- DRAFT | AWAITING_EMPLOYEE | RELEASED | WITHHELD | SUPERSEDED
    fitness           varchar(30)   not null,
    adjustments       varchar(2000),                    -- screened (functional wording only)
    restrictions      varchar(2000),                    -- screened
    prognosis         varchar(30)   not null,
    expected_return   date,
    review_date       date,
    answers           varchar(12000),                   -- JSON {questionCode: text}, screened
    drafted_by        varchar(200)  not null,
    drafted_at        timestamptz   not null,
    signed_by         varchar(200),
    signed_at         timestamptz,
    employee_decision varchar(20),
    employee_comment  varchar(1000),                    -- screened (released with the report)
    decline_reason    text,                             -- encrypted (OH only)
    decided_at        timestamptz,
    document_id       uuid,
    released_at       timestamptz,
    constraint uq_absence_advice_report_version unique (request_id, version)
);
create index ix_absence_advice_report_request on absence.advice_report (request_id, version desc);

-- ---------- seeded default trigger rules (E06-BL: subsidiary rules override defaults of the same type and action) ----------
insert into absence.trigger_rule (id, code, name, subsidiary_code, rule_type, params, action, active, version, updated_by, updated_at) values
    ('6e060000-0000-4000-8000-000000000001', 'DURATION_10D',       'Absence longer than 10 days',                         null,  'DURATION',        '{"minDays":11}',                                         'EARLY_INTERVENTION', true, 1, 'migration', now()),
    ('6e060000-0000-4000-8000-000000000002', 'FREQ_3_IN_90',       '3 absences within 90 days',                           null,  'FREQUENCY',       '{"episodes":3,"windowDays":90}',                         'EARLY_INTERVENTION', true, 1, 'migration', now()),
    ('6e060000-0000-4000-8000-000000000003', 'MON_FRI_PATTERN',    'Monday / Friday absence pattern (3 in 180 days)',     null,  'PATTERN',         '{"pattern":"MONDAY_FRIDAY","minOccurrences":3,"windowDays":180}', 'EARLY_INTERVENTION', true, 1, 'migration', now()),
    ('6e060000-0000-4000-8000-000000000004', 'LTA_28D',            'Long-term absence (28 days or more)',                 null,  'DURATION',        '{"minDays":28}',                                         'LONG_TERM',          true, 1, 'migration', now()),
    ('6e060000-0000-4000-8000-000000000005', 'SAFETY_CRITICAL_3D', 'Safety-critical role absent more than 3 days',        null,  'SAFETY_CRITICAL', '{"minDays":4}',                                          'EARLY_INTERVENTION', true, 1, 'migration', now()),
    ('6e060000-0000-4000-8000-000000000006', 'POST_INCIDENT_7D',   'Absence starting within 7 days of a workplace incident', null, 'POST_INCIDENT',  '{"withinDays":7}',                                       'EARLY_INTERVENTION', true, 1, 'migration', now()),
    ('6e060000-0000-4000-8000-000000000007', 'CREW_SICKNESS',      'Crew sickness: more than 7 days or 3 in 90 days',     null,  'CREW_SICKNESS',   '{"minDays":8,"episodes":3,"windowDays":90}',             'AME_REVIEW',         true, 1, 'migration', now()),
    ('6e060000-0000-4000-8000-000000000008', 'CAT_DURATION_2D',    'Catering food handlers: absence longer than 2 days',  'CAT', 'DURATION',        '{"minDays":3}',                                          'EARLY_INTERVENTION', true, 1, 'migration', now())
on conflict (code) do nothing;

insert into absence.trigger_rule_version (id, rule_id, version, snapshot, change_type, changed_by, changed_at)
select gen_random_uuid(), r.id, 1, '{"code":"' || r.code || '","params":' || r.params || ',"action":"' || r.action || '"}', 'CREATE', 'migration', now()
  from absence.trigger_rule r;

insert into masterdata.config_entry (config_key, config_value, description, updated_by, updated_at) values
    ('modules.e06.enabled', 'true', 'Absence & sickness management', 'migration', now()),
    ('absence.notify-hr-on-engagement', 'true', 'Tell the line manager and HR (status only) when OH accepts an absence trigger', 'migration', now()),
    ('absence.advice.employee-review-required', 'true', 'Employees review OH advice reports before they are released to the requester', 'migration', now()),
    ('absence.hr-email', 'hr-absence@wellnessz.local', 'HR absence distribution list (status-only notifications)', 'migration', now()),
    ('absence.queue.sla-days', '5', 'Early-intervention queue: days from trigger to first contact (KPI target)', 'migration', now()),
    ('absence.queue.ame-sla-days', '1', 'AME review queue: days from trigger to AME review', 'migration', now()),
    ('absence.advice.sla-working-days', '5', 'Management advice requests: working days to a released report', 'migration', now()),
    ('absence.lta.contact-interval-days', '14', 'Long-term absence: fortnightly contact schedule', 'migration', now()),
    ('absence.public-holidays', '2026-01-01,2026-04-03,2026-04-06,2026-05-04,2026-05-25,2026-08-31,2026-12-25,2026-12-28,2027-01-01,2027-03-26,2027-03-29,2027-05-03,2027-05-31,2027-08-30,2027-12-27,2027-12-28',
        'Public holidays used for the POST_HOLIDAY absence pattern', 'migration', now())
on conflict (config_key) do nothing;

grant usage on schema absence to ${app_role};
grant select, insert, update on absence.feed_run, absence.episode, absence.feed_mismatch, absence.trigger_rule, absence.queue_item,
      absence.certificate, absence.lta_pathway, absence.lta_milestone, absence.advice_request, absence.advice_report to ${app_role};
grant select, insert on absence.trigger_rule_version, absence.contact, absence.certificate_call, absence.case_conference to ${app_role};
grant usage, select on sequence absence.advice_ref_seq to ${app_role};
