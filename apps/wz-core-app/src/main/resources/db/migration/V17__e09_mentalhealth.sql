create schema if not exists mentalhealth;

-- V17 – E09 Mental health, psychosocial risk & peer support programmes (schema mentalhealth).
-- Restricted class: every support case is backed by a restricted kernel case (category PSYCHOLOGICAL, classification
-- RESTRICTED_CLINICAL). Clinical free text (notes, self-referral messages, contact notes, findings, medication lists, contact
-- phone numbers) is encrypted at field level by the application (columns marked "encrypted"). Peer-support contact data is held
-- in tables WITHOUT any employee identifier – requests and contact logs carry a pseudonymous contact code only; the callback
-- phone is encrypted and purged after mentalhealth.peer-callback-retention-days (job peer-callback-purge).

-- ---------- reference numbers (SUP / EAP / CISM / SMS per year) ----------
create table mentalhealth.ref_counter (
    prefix      varchar(10)  not null,
    seq_year    int          not null,
    last_value  bigint       not null,
    primary key (prefix, seq_year)
);

-- ---------- confidential support case (E09-F01 / F02) ----------
create table mentalhealth.support_case (
    id                   uuid primary key,
    case_ref             varchar(30)   not null unique,
    kernel_case_id       uuid          not null,
    employee_id          varchar(64)   not null,
    status               varchar(20)   not null,
    risk                 varchar(20)   not null,
    concern              varchar(20)   not null,
    source               varchar(30)   not null,
    source_type          varchar(40),
    source_ref           varchar(120),
    preferred_contact    varchar(20),
    contact_phone        text,                      -- encrypted
    best_time            varchar(100),
    message              text,                      -- encrypted
    next_contact_by      timestamptz,
    opened_at            timestamptz   not null,
    opened_by            varchar(200)  not null,
    last_contact_at      timestamptz,
    sharing              varchar(30)   not null default 'NOTHING',
    sharing_adjustments  varchar(1000),             -- screened (non-clinical) – shown to the manager when shared
    sharing_changed_at   timestamptz,
    closed_at            timestamptz,
    close_reason         text,                      -- encrypted
    updated_at           timestamptz   not null,
    version              bigint        not null default 0,
    constraint ck_support_status check (status in ('OPEN','SCREENED','ACTIVE','REFERRED','RELIEF','CLOSED')),
    constraint ck_support_risk check (risk in ('LOW','MODERATE','HIGH','URGENT')),
    constraint ck_support_concern check (concern in ('WORK_STRESS','LOW_MOOD','ANXIETY','SLEEP','BEREAVEMENT','TRAUMA','SUBSTANCE','RELATIONSHIP','OTHER')),
    constraint ck_support_source check (source in ('SELF','CLINICIAN','PEER','CISM','PROGRAMME_REFERRAL')),
    constraint ck_support_sharing check (sharing in ('NOTHING','FITNESS_ONLY','FITNESS_AND_ADJUSTMENTS')),
    constraint ck_support_contact check (preferred_contact is null or preferred_contact in ('PHONE','APP_CHAT','VIDEO'))
);
create index ix_support_case_employee on mentalhealth.support_case (employee_id, status);
create index ix_support_case_status on mentalhealth.support_case (status, risk);
create unique index uq_support_case_source on mentalhealth.support_case (source_type, source_ref) where source_ref is not null;

create table mentalhealth.support_note (
    id               uuid primary key,
    support_case_id  uuid          not null references mentalhealth.support_case (id),
    text             text          not null,      -- encrypted
    author           varchar(200)  not null,
    created_at       timestamptz   not null
);
create index ix_support_note_case on mentalhealth.support_note (support_case_id, created_at);

create table mentalhealth.support_contact (
    id               uuid primary key,
    support_case_id  uuid          not null references mentalhealth.support_case (id),
    contact_type     varchar(30)   not null,
    outcome          varchar(40),
    note             text,                        -- encrypted
    actor            varchar(200)  not null,
    occurred_at      timestamptz   not null
);
create index ix_support_contact_case on mentalhealth.support_contact (support_case_id, occurred_at);

-- non-clinical timeline of a support case (separate audit trail of the restricted class)
create table mentalhealth.support_event (
    id               uuid primary key,
    support_case_id  uuid          not null references mentalhealth.support_case (id),
    event_type       varchar(40)   not null,
    summary          varchar(300)  not null,
    actor            varchar(200)  not null,
    occurred_at      timestamptz   not null
);
create index ix_support_event_case on mentalhealth.support_event (support_case_id, occurred_at);

-- ---------- validated screening instruments (governance: E09-BL-014) ----------
create table mentalhealth.instrument (
    code            varchar(20)   primary key,
    title           varchar(200)  not null,
    version         varchar(20)   not null,
    licence         varchar(300)  not null,
    licence_status  varchar(20)   not null,
    item_count      int           not null,
    max_score       int           not null
);

create table mentalhealth.instrument_item (
    instrument  varchar(20)   not null references mentalhealth.instrument (code),
    position    int           not null,
    code        varchar(20)   not null,
    text        varchar(500)  not null,
    primary key (instrument, position)
);

create table mentalhealth.instrument_option (
    instrument  varchar(20)   not null references mentalhealth.instrument (code),
    value       int           not null,
    label       varchar(100)  not null,
    primary key (instrument, value)
);

create table mentalhealth.instrument_band (
    instrument  varchar(20)   not null references mentalhealth.instrument (code),
    band        varchar(30)   not null,
    min_score   int           not null,
    max_score   int           not null,
    primary key (instrument, band)
);

create table mentalhealth.screen_result (
    id               uuid primary key,
    support_case_id  uuid references mentalhealth.support_case (id),
    employee_id      varchar(64)   not null,
    instrument       varchar(20)   not null references mentalhealth.instrument (code),
    answers          varchar(100)  not null,
    score            int           not null,
    band             varchar(30)   not null,
    urgent           boolean       not null,
    administered_by  varchar(200)  not null,
    created_at       timestamptz   not null
);
create index ix_screen_employee on mentalhealth.screen_result (employee_id, created_at);
create index ix_screen_case on mentalhealth.screen_result (support_case_id);

create table mentalhealth.urgent_item (
    id               uuid primary key,
    support_case_id  uuid          not null references mentalhealth.support_case (id),
    screen_id        uuid references mentalhealth.screen_result (id),
    kernel_task_id   uuid,
    trigger_code     varchar(40)   not null,
    call_back_by     timestamptz   not null,
    status           varchar(20)   not null,
    outcome          varchar(40),
    resolved_by      varchar(200),
    resolved_at      timestamptz,
    created_at       timestamptz   not null,
    constraint ck_urgent_status check (status in ('OPEN','RESOLVED'))
);
create index ix_urgent_open on mentalhealth.urgent_item (status, call_back_by);

create table mentalhealth.protocol (
    code           varchar(40)   primary key,
    title          varchar(200)  not null,
    version        varchar(20)   not null,
    steps          text          not null,      -- JSON array of steps
    status         varchar(20)   not null,
    signed_off_by  varchar(200),
    signed_off_at  timestamptz
);

create table mentalhealth.signposting (
    code           varchar(40)   primary key,
    title          varchar(200)  not null,
    summary        varchar(1000) not null,
    url            varchar(500),
    phone          varchar(40),
    available_24h  boolean       not null default false,
    sort_order     int           not null,
    active         boolean       not null default true
);

-- ---------- EAP / counselling partner referral (attendance and closure feedback only) ----------
create table mentalhealth.eap_referral (
    id                       uuid primary key,
    ref                      varchar(30)   not null unique,
    support_case_id          uuid          not null references mentalhealth.support_case (id),
    employee_id              varchar(64)   not null,
    partner_code             varchar(40)   not null,
    sessions_authorised      int           not null,
    urgency                  varchar(20)   not null,
    consent_id               uuid,
    status                   varchar(20)   not null,
    first_appointment_due_by date          not null,
    sent_at                  timestamptz   not null,
    sent_by                  varchar(200)  not null,
    attendance               text,                    -- JSON [{date, attended}] – no free text
    closure_reason           varchar(20),
    feedback_at              timestamptz,
    feedback_by              varchar(200),
    updated_at               timestamptz   not null,
    version                  bigint        not null default 0,
    constraint ck_eap_status check (status in ('SENT','ACCEPTED','IN_PROGRESS','CLOSED')),
    constraint ck_eap_urgency check (urgency in ('ROUTINE','PRIORITY')),
    constraint ck_eap_sessions check (sessions_authorised between 1 and 12),
    constraint ck_eap_closure check (closure_reason is null or closure_reason in ('COMPLETED','DISENGAGED','REFERRED_ON'))
);
create index ix_eap_partner on mentalhealth.eap_referral (partner_code, status);
create index ix_eap_case on mentalhealth.eap_referral (support_case_id);

-- ---------- flight-crew peer support (E09-F03) – NO employee identifiers below this line ----------
create table mentalhealth.peer_volunteer (
    id            uuid primary key,
    username      varchar(200)  not null unique,
    display_name  varchar(200)  not null,
    email         varchar(320),
    base          varchar(20)   not null,
    languages     varchar(200),
    available     boolean       not null default true,
    created_at    timestamptz   not null,
    updated_at    timestamptz   not null,
    version       bigint        not null default 0
);

create table mentalhealth.peer_training (
    id            uuid primary key,
    volunteer_id  uuid          not null references mentalhealth.peer_volunteer (id),
    course        varchar(200)  not null,
    completed_on  date          not null,
    expires_on    date
);
create index ix_peer_training_volunteer on mentalhealth.peer_training (volunteer_id);

create table mentalhealth.peer_contact_request (
    id                  uuid primary key,
    contact_code        varchar(20)   not null unique,
    alias               varchar(60)   not null,
    callback_phone      text,                       -- encrypted, purged after the retention period
    base                varchar(20),
    language            varchar(10),
    volunteer_username  varchar(200)  not null,
    status              varchar(20)   not null,
    requested_at        timestamptz   not null,
    phone_purged_at     timestamptz,
    constraint ck_peer_request_status check (status in ('OPEN','LOGGED'))
);
create index ix_peer_request_volunteer on mentalhealth.peer_contact_request (volunteer_username, requested_at);
create index ix_peer_request_purge on mentalhealth.peer_contact_request (requested_at) where callback_phone is not null;

create table mentalhealth.peer_contact (
    id                  uuid primary key,
    contact_code        varchar(20)   not null,
    volunteer_username  varchar(200)  not null,
    contact_date        date          not null,
    duration_minutes    int           not null,
    category            varchar(20)   not null,
    outcome             varchar(40)   not null,
    base                varchar(20)   not null,
    referred_at         timestamptz,
    created_at          timestamptz   not null,
    constraint ck_peer_category check (category in ('STRESS','FATIGUE','RELATIONSHIP','BEREAVEMENT','SUBSTANCE','TRAUMA','OTHER')),
    constraint ck_peer_outcome check (outcome in ('SUPPORTED','SIGNPOSTED','REFERRED_TO_TRUSTED_PROFESSIONAL','EMERGENCY')),
    constraint ck_peer_duration check (duration_minutes between 1 and 600)
);
create index ix_peer_contact_volunteer on mentalhealth.peer_contact (volunteer_username, contact_date);
create index ix_peer_contact_date on mentalhealth.peer_contact (contact_date);

create table mentalhealth.trusted_referral (
    id               uuid primary key,
    peer_contact_id  uuid          not null unique references mentalhealth.peer_contact (id),
    contact_code     varchar(20)   not null,
    referred_by      varchar(200)  not null,
    referred_at      timestamptz   not null,
    status           varchar(20)   not null,
    picked_up_by     varchar(200),
    picked_up_at     timestamptz,
    constraint ck_trusted_status check (status in ('OPEN','PICKED_UP'))
);

create table mentalhealth.sms_report (
    id          uuid primary key,
    quarter     varchar(7)    not null unique,
    report_ref  varchar(30)   not null unique,
    cells       int           not null,
    sent_by     varchar(200)  not null,
    sent_at     timestamptz   not null
);

-- ---------- temporary relief from duties (status-only roster feed) ----------
create table mentalhealth.relief (
    id               uuid primary key,
    support_case_id  uuid          not null references mentalhealth.support_case (id),
    employee_id      varchar(64)   not null,
    from_date        date          not null,
    expected_until   date,
    status           varchar(20)   not null,
    decision         varchar(20),
    decided_by       varchar(200),
    decided_at       timestamptz,
    created_by       varchar(200)  not null,
    created_at       timestamptz   not null,
    version          bigint        not null default 0,
    constraint ck_relief_status check (status in ('ACTIVE','RETURNED'))
);
create unique index uq_relief_active on mentalhealth.relief (employee_id) where status = 'ACTIVE';

-- ---------- critical incident stress management (E09-F04) ----------
create table mentalhealth.cism_event (
    id            uuid primary key,
    ref           varchar(30)   not null unique,
    title         varchar(200)  not null,
    category      varchar(30)   not null,
    occurred_at   timestamptz   not null,
    location      varchar(200),
    duty_refs     varchar(500),
    source        varchar(20)   not null,
    incident_id   varchar(64)   unique,
    offer_due_by  timestamptz   not null,
    created_by    varchar(200)  not null,
    created_at    timestamptz   not null,
    constraint ck_cism_source check (source in ('INCIDENT','MANUAL'))
);
create index ix_cism_event_occurred on mentalhealth.cism_event (occurred_at);

create table mentalhealth.cism_affected (
    id                    uuid primary key,
    event_id              uuid          not null references mentalhealth.cism_event (id),
    employee_id           varchar(64)   not null,
    duty_role             varchar(40),
    offer_sent_at         timestamptz,
    response              varchar(20),
    preferred_contact     varchar(20),
    responded_at          timestamptz,
    session_type          varchar(20),
    attended_at           timestamptz,
    follow_up_1w_due      date          not null,
    follow_up_1w_done     boolean       not null default false,
    follow_up_1w_sent_at  timestamptz,
    follow_up_4w_due      date          not null,
    follow_up_4w_done     boolean       not null default false,
    follow_up_4w_sent_at  timestamptz,
    referred              boolean       not null default false,
    support_case_id       uuid,
    updated_at            timestamptz   not null,
    constraint uq_cism_affected unique (event_id, employee_id),
    constraint ck_cism_response check (response is null or response in ('ACCEPTED','DECLINED')),
    constraint ck_cism_session check (session_type is null or session_type in ('GROUP','INDIVIDUAL'))
);
create index ix_cism_affected_employee on mentalhealth.cism_affected (employee_id);
create index ix_cism_follow_up on mentalhealth.cism_affected (follow_up_1w_due, follow_up_4w_due);

-- ---------- psychosocial risk register (E09-F05, ISO 45003) ----------
create table mentalhealth.psychosocial_assessment (
    id             uuid primary key,
    unit_code      varchar(64)   not null,
    assessed_on    date          not null,
    review_date    date          not null,
    factors        text          not null,      -- JSON [{factor, likelihood, severity, controls}] – controls screened
    overall_score  int           not null,
    rag            varchar(10)   not null,
    assessed_by    varchar(200)  not null,
    created_at     timestamptz   not null
);
create index ix_psychosocial_unit on mentalhealth.psychosocial_assessment (unit_code, assessed_on);

create table mentalhealth.psychosocial_action (
    id             uuid primary key,
    assessment_id  uuid          not null references mentalhealth.psychosocial_assessment (id),
    title          varchar(300)  not null,
    owner          varchar(200)  not null,
    due_date       date          not null,
    status         varchar(10)   not null,
    created_by     varchar(200)  not null,
    created_at     timestamptz   not null,
    updated_at     timestamptz   not null,
    completed_at   timestamptz,
    constraint ck_psychosocial_action check (status in ('OPEN','DONE'))
);
create index ix_psychosocial_action on mentalhealth.psychosocial_action (assessment_id);

-- ---------- safety-critical mental-health fitness assessment (E09-F06) ----------
create table mentalhealth.fitness_assessment (
    id                         uuid primary key,
    employee_id                varchar(64)   not null,
    support_case_id            uuid          not null references mentalhealth.support_case (id),
    kernel_case_id             uuid          not null,
    standard_code              varchar(64)   not null,
    medications                text,                    -- encrypted JSON
    findings                   text,                    -- encrypted
    decision                   varchar(30)   not null,
    limitations                varchar(1000),
    review_date                date          not null,
    authority_report_required  boolean       not null,
    document_id                uuid,
    review_task_id             uuid,
    assessed_by                varchar(200)  not null,
    assessed_at                timestamptz   not null,
    constraint ck_mh_fitness_decision check (decision in ('FIT','FIT_WITH_LIMITATIONS','TEMPORARILY_UNFIT','REFER_TO_AUTHORITY'))
);
create index ix_mh_fitness_employee on mentalhealth.fitness_assessment (employee_id, assessed_at);

-- ---------- seed: signposting content (pending clinical governance sign-off, E09-BL-014) ----------
insert into mentalhealth.signposting (code, title, summary, url, phone, available_24h, sort_order) values
    ('CONFIDENTIAL_LINE', 'Confidential support line', 'Talk to the confidential support team in Occupational Health. Nothing is shared with your manager unless you decide so.', null, '+44 20 7000 0900', false, 1),
    ('EAP', 'Employee assistance programme', 'Free, confidential counselling and practical advice for you and your family, provided by our EAP partner.', 'https://wellbeingassist.example', '+44 800 000 0030', true, 2),
    ('PEER_SUPPORT', 'Crew peer support', 'Trained crew colleagues who listen in confidence. You choose an alias – the operator never sees who called.', null, null, true, 3),
    ('SLEEP_FATIGUE', 'Sleep and fatigue', 'Practical guidance on rest, rostering and recovery after night shifts and long-haul flying.', 'https://intranet.wellnessz.local/wellbeing/sleep', null, false, 4),
    ('BEREAVEMENT', 'Bereavement', 'Support after the loss of someone close, including time off and where to find help.', 'https://intranet.wellnessz.local/wellbeing/bereavement', null, false, 5),
    ('MONEY_WORRIES', 'Money worries', 'Confidential financial guidance through the employee assistance programme.', 'https://wellbeingassist.example/money', null, false, 6)
on conflict (code) do nothing;

-- ---------- seed: instruments (public domain; licence register kept under governance) ----------
insert into mentalhealth.instrument (code, title, version, licence, licence_status, item_count, max_score) values
    ('PHQ9', 'Patient Health Questionnaire (PHQ-9)', '1.0', 'Public domain – no permission required to reproduce, translate, display or distribute', 'NOT_REQUIRED', 9, 27),
    ('GAD7', 'Generalised Anxiety Disorder scale (GAD-7)', '1.0', 'Public domain – no permission required to reproduce, translate, display or distribute', 'NOT_REQUIRED', 7, 21)
on conflict (code) do nothing;

insert into mentalhealth.instrument_item (instrument, position, code, text) values
    ('PHQ9', 1, 'PHQ9_1', 'Little interest or pleasure in doing things'),
    ('PHQ9', 2, 'PHQ9_2', 'Feeling down, depressed, or hopeless'),
    ('PHQ9', 3, 'PHQ9_3', 'Trouble falling or staying asleep, or sleeping too much'),
    ('PHQ9', 4, 'PHQ9_4', 'Feeling tired or having little energy'),
    ('PHQ9', 5, 'PHQ9_5', 'Poor appetite or overeating'),
    ('PHQ9', 6, 'PHQ9_6', 'Feeling bad about yourself – or that you are a failure or have let yourself or your family down'),
    ('PHQ9', 7, 'PHQ9_7', 'Trouble concentrating on things, such as reading the newspaper or watching television'),
    ('PHQ9', 8, 'PHQ9_8', 'Moving or speaking so slowly that other people could have noticed – or the opposite, being so fidgety or restless that you have been moving around a lot more than usual'),
    ('PHQ9', 9, 'PHQ9_9', 'Thoughts that you would be better off dead, or of hurting yourself in some way'),
    ('GAD7', 1, 'GAD7_1', 'Feeling nervous, anxious, or on edge'),
    ('GAD7', 2, 'GAD7_2', 'Not being able to stop or control worrying'),
    ('GAD7', 3, 'GAD7_3', 'Worrying too much about different things'),
    ('GAD7', 4, 'GAD7_4', 'Trouble relaxing'),
    ('GAD7', 5, 'GAD7_5', 'Being so restless that it is hard to sit still'),
    ('GAD7', 6, 'GAD7_6', 'Becoming easily annoyed or irritable'),
    ('GAD7', 7, 'GAD7_7', 'Feeling afraid, as if something awful might happen')
on conflict do nothing;

insert into mentalhealth.instrument_option (instrument, value, label) values
    ('PHQ9', 0, 'Not at all'), ('PHQ9', 1, 'Several days'), ('PHQ9', 2, 'More than half the days'), ('PHQ9', 3, 'Nearly every day'),
    ('GAD7', 0, 'Not at all'), ('GAD7', 1, 'Several days'), ('GAD7', 2, 'More than half the days'), ('GAD7', 3, 'Nearly every day')
on conflict do nothing;

insert into mentalhealth.instrument_band (instrument, band, min_score, max_score) values
    ('PHQ9', 'MINIMAL', 0, 4), ('PHQ9', 'MILD', 5, 9), ('PHQ9', 'MODERATE', 10, 14), ('PHQ9', 'MODERATELY_SEVERE', 15, 19), ('PHQ9', 'SEVERE', 20, 27),
    ('GAD7', 'MINIMAL', 0, 4), ('GAD7', 'MILD', 5, 9), ('GAD7', 'MODERATE', 10, 14), ('GAD7', 'SEVERE', 15, 21)
on conflict do nothing;

-- ---------- seed: urgent-risk protocol (pending CMO / mental-health lead sign-off) ----------
insert into mentalhealth.protocol (code, title, version, steps, status) values
    ('URGENT_RISK', 'Same-day urgent-risk call protocol', '2026.1',
     '["Call the employee on the preferred number within the call-back window; introduce yourself as the confidential support team.",'
     || '"Confirm identity and current location; ask whether they are safe right now.",'
     || '"Ask directly about thoughts of harming themselves, plans, means and protective factors; listen without judgement.",'
     || '"If there is immediate danger: stay on the line, call emergency services (999 / 112) and record REACHED_ESCALATED_EMERGENCY.",'
     || '"If safe: agree a safety plan, the next contact and signposting (24-hour lines); record REACHED_SAFE.",'
     || '"If not reached: retry within one hour (NOT_REACHED_RETRY); after two failed attempts request a welfare check (NOT_REACHED_WELFARE_CHECK).",'
     || '"Document the call in the restricted case and inform the named clinician on duty."]',
     'PENDING_SIGN_OFF')
on conflict (code) do nothing;

-- ---------- configuration ----------
insert into masterdata.config_entry (config_key, config_value, description, updated_by, updated_at) values
    ('modules.e09.enabled', 'true', 'Mental health, psychosocial risk & peer support programmes', 'migration', now()),
    ('mentalhealth.named-clinicians', 'psych1', 'Named mental-health clinicians (usernames, comma separated) who may read restricted E09 content; empty = every PSYCH_CLINICIAN', 'migration', now()),
    ('mentalhealth.urgent-callback-hours', '4', 'Urgent screens: same-day call back within this many hours', 'migration', now()),
    ('mentalhealth.first-contact-working-days', '2', 'Self-referrals: first contact by the support team within this many working days', 'migration', now()),
    ('mentalhealth.eap-first-appointment-working-days', '5', 'EAP referrals: first appointment due within this many working days (KPI ≤ 5 days)', 'migration', now()),
    ('mentalhealth.peer-callback-retention-days', '30', 'Peer-support callback phone numbers are purged after this many days', 'migration', now()),
    ('mentalhealth.emergency-text', 'If you are in immediate danger or thinking about ending your life, call the emergency services now or go to the nearest emergency department.', 'Emergency signposting text (employee app)', 'migration', now()),
    ('mentalhealth.emergency-phone', '999', 'Emergency number shown with the signposting', 'migration', now()),
    ('mentalhealth.governance.sign-off', 'PENDING', 'Instruments, risk protocol and signposting content pending CMO / mental-health lead sign-off (E09-BL-014)', 'migration', now()),
    ('cism.trigger-classes', 'FATALITY,ASSAULT,FUME_EVENT,AIRCRAFT_ACCIDENT,SERIOUS_INJURY,EMERGENCY_LANDING', 'Incident classes that create a critical-incident stress management event (besides severity HIGH / CRITICAL)', 'migration', now()),
    ('cism.offer-sla-hours', '72', 'Critical incidents: support offered to every affected person within this many hours', 'migration', now()),
    ('cism.duty-window-hours', '2', 'Critical incidents: co-workers on the same duty / proximity group within ± this many hours are affected', 'migration', now())
on conflict (config_key) do nothing;

grant usage on schema mentalhealth to ${app_role};
grant select, insert, update on mentalhealth.ref_counter, mentalhealth.support_case, mentalhealth.urgent_item, mentalhealth.eap_referral,
      mentalhealth.peer_volunteer, mentalhealth.peer_contact_request, mentalhealth.peer_contact, mentalhealth.trusted_referral,
      mentalhealth.relief, mentalhealth.cism_event, mentalhealth.cism_affected, mentalhealth.psychosocial_action to ${app_role};
grant select, insert, update, delete on mentalhealth.peer_training to ${app_role};
grant select, insert on mentalhealth.support_note, mentalhealth.support_contact, mentalhealth.support_event, mentalhealth.screen_result,
      mentalhealth.sms_report, mentalhealth.psychosocial_assessment, mentalhealth.fitness_assessment to ${app_role};
grant select on mentalhealth.instrument, mentalhealth.instrument_item, mentalhealth.instrument_option, mentalhealth.instrument_band,
      mentalhealth.protocol, mentalhealth.signposting to ${app_role};
