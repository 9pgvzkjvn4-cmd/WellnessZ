create schema if not exists promotion;
-- =====================================================================================================
-- V20 – E12 Health promotion, wellbeing & prevention campaigns: campaign planner (portfolio E12-BL-008), invitations and
--       participation (campaign app), screening events / participants / results with follow-up into the case kernel,
--       hearing-conservation education attendance, ergonomic checklists / assessments / photos / actions, consented
--       wearable enrolments and daily aggregates. Measures, flags and wearable values are encrypted at field level by the
--       application (columns typed text). Analytics are aggregated with small-cell suppression in the application.
-- =====================================================================================================

create sequence promotion.ref_seq start 1;

-- ---------- campaign planner (E12-F01) ----------
create table promotion.campaign (
    id               uuid primary key,
    campaign_ref     varchar(30)    not null unique,
    title            varchar(200)   not null,
    risk             varchar(30)    not null,
    objective        varchar(2000)  not null,
    target_units     varchar(1000)  not null,          -- comma-separated org unit (department) codes
    insight_ref      varchar(200),
    budget_amount    numeric(12,2),
    budget_currency  varchar(3),
    start_date       date           not null,
    end_date         date           not null,
    owners           varchar(1000)  not null,          -- comma-separated usernames / names
    kpis             text           not null,          -- JSON [{metric, target}]
    status           varchar(20)    not null,
    published_at     timestamptz,
    published_by     varchar(200),
    created_by       varchar(200)   not null,
    created_at       timestamptz    not null,
    updated_by       varchar(200)   not null,
    updated_at       timestamptz    not null,
    version          bigint         not null default 0,
    constraint ck_campaign_risk check (risk in ('MSK','HEARING','METABOLIC','FATIGUE','MENTAL_WELLBEING')),
    constraint ck_campaign_status check (status in ('PLANNED','ACTIVE','COMPLETED','CANCELLED')),
    constraint ck_campaign_dates check (end_date >= start_date),
    constraint ck_campaign_budget check (budget_amount is null or budget_amount >= 0)
);
create index ix_campaign_status on promotion.campaign (status, start_date);

create table promotion.campaign_invitation (
    id           uuid primary key,
    campaign_id  uuid          not null references promotion.campaign(id),
    employee_id  varchar(64)   not null,
    unit_code    varchar(64),
    invited_at   timestamptz   not null,
    constraint uq_invitation unique (campaign_id, employee_id)
);
create index ix_invitation_employee on promotion.campaign_invitation (employee_id);

-- participation flowing back from the campaign app, screenings, education sessions and wearable programmes (E12-F04/F05)
create table promotion.participation (
    id           uuid primary key,
    campaign_id  uuid           not null references promotion.campaign(id),
    employee_id  varchar(64)    not null,
    unit_code    varchar(64),
    activity     varchar(40)    not null,
    occurred_at  timestamptz    not null,
    value        numeric(14,2),
    source       varchar(20)    not null,
    source_ref   varchar(120),
    received_at  timestamptz    not null,
    constraint uq_participation unique (campaign_id, employee_id, activity, occurred_at),
    constraint ck_participation_source check (source in ('APP','SCREENING','EDUCATION','WEARABLE'))
);
create index ix_participation_campaign on promotion.participation (campaign_id, unit_code);
create index ix_participation_employee on promotion.participation (employee_id);

-- ---------- screening events & follow-up (E12-F02) ----------
create table promotion.screening_event (
    id           uuid primary key,
    event_ref    varchar(30)   not null unique,
    campaign_id  uuid          references promotion.campaign(id),
    event_type   varchar(30)   not null,
    site_code    varchar(40)   not null,
    event_date   date          not null,
    status       varchar(20)   not null,
    created_by   varchar(200)  not null,
    created_at   timestamptz   not null,
    updated_at   timestamptz   not null,
    constraint ck_screening_type check (event_type in ('BIOMETRIC','MSK','HEARING','VISION','MENTAL_WELLBEING')),
    constraint ck_screening_status check (status in ('PLANNED','COMPLETED','CANCELLED'))
);
create index ix_screening_event_date on promotion.screening_event (event_date);

create table promotion.screening_participant (
    id                     uuid primary key,
    event_id               uuid          not null references promotion.screening_event(id),
    employee_id            varchar(64)   not null,
    unit_code              varchar(64),
    status                 varchar(20)   not null,
    appointment_id         uuid,
    appointment_starts_at  timestamptz,
    added_by               varchar(200)  not null,
    added_at               timestamptz   not null,
    constraint uq_screening_participant unique (event_id, employee_id),
    constraint ck_participant_status check (status in ('INVITED','BOOKED','SCREENED'))
);
create index ix_screening_participant_employee on promotion.screening_participant (employee_id);

create table promotion.screening_result (
    id                   uuid primary key,
    event_id             uuid          not null references promotion.screening_event(id),
    participant_id       uuid          not null references promotion.screening_participant(id),
    employee_id          varchar(64)   not null,
    unit_code            varchar(64),
    measures             text,                         -- encrypted JSON {systolic, diastolic, bmi, …}
    flags                text,                         -- encrypted, comma-separated threshold codes
    outcome              varchar(10)   not null,
    case_id              uuid,
    case_ref             varchar(30),
    follow_up_due_at     timestamptz,
    follow_up_opened_at  timestamptz,
    letter_document_id   uuid,
    recorded_by          varchar(200)  not null,
    recorded_at          timestamptz   not null,
    constraint uq_screening_result unique (event_id, employee_id),
    constraint ck_screening_outcome check (outcome in ('NORMAL','ABNORMAL'))
);
create index ix_screening_result_followup on promotion.screening_result (recorded_at) where outcome = 'ABNORMAL' and case_id is null;
create index ix_screening_result_employee on promotion.screening_result (employee_id);

-- ---------- hearing-conservation (and other) education attendance (E12-BL-007) ----------
create table promotion.education_attendance (
    id            uuid primary key,
    employee_id   varchar(64)   not null,
    unit_code     varchar(64),
    programme     varchar(40)   not null,
    session_date  date          not null,
    campaign_id   uuid          references promotion.campaign(id),
    recorded_by   varchar(200)  not null,
    recorded_at   timestamptz   not null,
    constraint uq_education_attendance unique (employee_id, programme, session_date),
    constraint ck_education_programme check (programme in ('HEARING_CONSERVATION','MANUAL_HANDLING','FATIGUE_SLEEP','METABOLIC_HEALTH','MENTAL_WELLBEING'))
);
create index ix_education_programme on promotion.education_attendance (programme, session_date);

-- ---------- ergonomics (E12-F03) ----------
create table promotion.ergonomic_checklist (
    code        varchar(30)   primary key,
    title       varchar(200)  not null,
    items       text          not null,               -- JSON [{code, label, weight}]
    amber_from  int           not null,
    red_from    int           not null,
    updated_by  varchar(200)  not null,
    updated_at  timestamptz   not null,
    constraint ck_checklist_bands check (amber_from between 1 and 100 and red_from between amber_from and 100)
);

create table promotion.ergonomic_assessment (
    id               uuid primary key,
    assessment_ref   varchar(30)    not null unique,
    assessment_type  varchar(30)    not null references promotion.ergonomic_checklist(code),
    unit_code        varchar(64)    not null,
    job_code         varchar(64)    not null,
    employee_id      varchar(64),
    location         varchar(200)   not null,
    checklist        text           not null,          -- JSON {item: 0..3}
    findings         text,                              -- non-clinical (screened)
    risk_score       int            not null,
    rag              varchar(5)     not null,
    job_demand       text,                              -- JSON snapshot of the masterdata job-demand profile
    assessed_by      varchar(200)   not null,
    assessed_at      timestamptz    not null,
    updated_at       timestamptz    not null,
    constraint ck_assessment_rag check (rag in ('GREEN','AMBER','RED')),
    constraint ck_assessment_score check (risk_score between 0 and 100)
);
create index ix_assessment_unit on promotion.ergonomic_assessment (unit_code, assessed_at desc);

create table promotion.ergonomic_photo (
    id             uuid primary key,
    assessment_id  uuid          not null references promotion.ergonomic_assessment(id),
    document_id    uuid          not null,
    uploaded_by    varchar(200)  not null,
    uploaded_at    timestamptz   not null
);
create index ix_photo_assessment on promotion.ergonomic_photo (assessment_id);

create table promotion.ergonomic_action (
    id                uuid primary key,
    assessment_id     uuid          not null references promotion.ergonomic_assessment(id),
    action_ref        varchar(40)   not null unique,
    title             varchar(300)  not null,
    owner             varchar(200)  not null,
    due_date          date          not null,
    status            varchar(10)   not null,
    unit_code         varchar(64)   not null,
    created_by        varchar(200)  not null,
    created_at        timestamptz   not null,
    updated_by        varchar(200)  not null,
    updated_at        timestamptz   not null,
    closed_at         timestamptz,
    last_reminded_on  date,
    version           bigint        not null default 0,
    constraint ck_action_status check (status in ('OPEN','DONE'))
);
create index ix_action_due on promotion.ergonomic_action (status, due_date);
create index ix_action_unit on promotion.ergonomic_action (unit_code);

-- ---------- wearables (E12-BL-005): daily aggregates only, consent-gated, purged on withdrawal ----------
create table promotion.wearable_enrolment (
    id            uuid primary key,
    employee_id   varchar(64)   not null,
    campaign_id   uuid          not null references promotion.campaign(id),
    device_type   varchar(30)   not null,
    consent_id    uuid          not null,
    status        varchar(20)   not null,
    enrolled_at   timestamptz   not null,
    withdrawn_at  timestamptz,
    purged_rows   int,
    constraint ck_wearable_status check (status in ('ACTIVE','WITHDRAWN'))
);
create unique index uq_wearable_active on promotion.wearable_enrolment (employee_id, campaign_id) where status = 'ACTIVE';

create table promotion.wearable_daily (
    id             uuid primary key,
    enrolment_id   uuid          not null references promotion.wearable_enrolment(id),
    employee_id    varchar(64)   not null,
    campaign_id    uuid          not null,
    metric         varchar(20)   not null,
    metric_date    date          not null,
    value          text          not null,             -- encrypted
    received_at    timestamptz   not null,
    constraint uq_wearable_daily unique (enrolment_id, metric, metric_date),
    constraint ck_wearable_metric check (metric in ('STEPS','SLEEP_MINUTES','ACTIVE_MINUTES'))
);
create index ix_wearable_daily_campaign on promotion.wearable_daily (campaign_id, metric, metric_date);

-- ---------- seed: ergonomic checklists (scores 0 = no risk … 3 = high risk; weight per item) ----------
insert into promotion.ergonomic_checklist (code, title, items, amber_from, red_from, updated_by, updated_at) values
 ('DSE_WORKSTATION', 'Display screen equipment – workstation assessment',
  '[{"code":"CHAIR","label":"Chair adjustment and lumbar support","weight":1},{"code":"SCREEN_POSITION","label":"Screen distance and height (top at eye level)","weight":1},{"code":"KEYBOARD_MOUSE","label":"Keyboard and mouse within reach, wrists neutral","weight":1},{"code":"DESK_SPACE","label":"Desk space and legroom","weight":1},{"code":"LIGHTING_GLARE","label":"Lighting and screen glare","weight":1},{"code":"BREAKS","label":"Breaks and task variation","weight":1},{"code":"SCREEN_TIME","label":"Daily screen time","weight":2},{"code":"POSTURE","label":"Observed posture (neck, back, shoulders)","weight":2},{"code":"DISCOMFORT","label":"Reported discomfort while working","weight":2}]',
  30, 60, 'migration', now()),
 ('MANUAL_HANDLING', 'Manual handling – task, individual, load, environment',
  '[{"code":"LOAD_WEIGHT","label":"Load weight","weight":3},{"code":"LOAD_DISTANCE","label":"Load held away from the trunk","weight":2},{"code":"TWIST_STOOP","label":"Twisting or stooping","weight":2},{"code":"REACH_HIGH","label":"Reaching above shoulder height","weight":2},{"code":"PUSH_PULL","label":"Pushing / pulling force","weight":2},{"code":"CARRY_DISTANCE","label":"Carrying distance","weight":1},{"code":"FREQUENCY","label":"Repetition and frequency","weight":2},{"code":"CONFINED_POSTURE","label":"Space constraints on posture","weight":1},{"code":"FLOOR","label":"Floor surface and level changes","weight":1},{"code":"ENVIRONMENT","label":"Temperature, lighting, weather","weight":1},{"code":"TRAINING","label":"Training and individual capability","weight":1},{"code":"EQUIPMENT","label":"Handling aids available and used","weight":1}]',
  30, 55, 'migration', now()),
 ('TASK', 'Task assessment – force, repetition, posture',
  '[{"code":"FORCE","label":"Force required","weight":2},{"code":"REPETITION","label":"Repetition","weight":2},{"code":"AWKWARD_POSTURE","label":"Awkward postures","weight":2},{"code":"DURATION","label":"Duration without recovery","weight":1},{"code":"VIBRATION","label":"Vibration","weight":1},{"code":"ENVIRONMENT","label":"Environment","weight":1},{"code":"WORK_ORGANISATION","label":"Work organisation and pace","weight":1}]',
  30, 60, 'migration', now());

-- ---------- seed: initial campaign portfolio (E12-BL-008) – PLANNED, owners and KPIs for approval ----------
insert into promotion.campaign (id, campaign_ref, title, risk, objective, target_units, insight_ref, budget_amount, budget_currency, start_date,
                                end_date, owners, kpis, status, created_by, created_at, updated_by, updated_at) values
 ('e12c0000-0000-4000-8000-000000000001', 'CMP-PORTFOLIO-MSK', 'MSK prevention – ramp, cargo and catering', 'MSK',
  'Reduce musculoskeletal injuries in ramp, cargo and catering through ergonomic assessments, manual-handling coaching and MSK screening.',
  'GROUND,CARGO,CATERING', 'MSK:GROUND:MSK_INJURY_CASES', 25000.00, 'GBP', current_date + 30, current_date + 395,
  'promo1,ergo1', '[{"metric":"PARTICIPATION_PCT","target":40},{"metric":"MSK_CASE_RATE_CHANGE_PCT","target":-20},{"metric":"ERGONOMIC_ACTIONS_CLOSED_60D_PCT","target":80}]',
  'PLANNED', 'migration', now(), 'migration', now()),
 ('e12c0000-0000-4000-8000-000000000002', 'CMP-PORTFOLIO-HEAR', 'Hearing conservation – airside and engineering', 'HEARING',
  'Complete hearing-conservation education alongside annual audiometry for every noise-exposed employee airside and in engineering.',
  'GROUND,ENG', 'HEARING:ENG:AUDIOMETRY_STS', 8000.00, 'GBP', current_date + 30, current_date + 395,
  'promo1,nursinghead1', '[{"metric":"PARTICIPATION_PCT","target":40},{"metric":"EDUCATION_ATTENDANCE_PCT","target":90}]',
  'PLANNED', 'migration', now(), 'migration', now()),
 ('e12c0000-0000-4000-8000-000000000003', 'CMP-PORTFOLIO-FATG', 'Fatigue and sleep – shift workers and crew', 'FATIGUE',
  'Fatigue and sleep education and referral for shift workers and crew (complements the FRMS operated by Flight Ops).',
  'FLIGHTOPS,CABIN,GROUND,CARGO', null, 12000.00, 'GBP', current_date + 60, current_date + 425,
  'promo1', '[{"metric":"PARTICIPATION_PCT","target":40}]',
  'PLANNED', 'migration', now(), 'migration', now()),
 ('e12c0000-0000-4000-8000-000000000004', 'CMP-PORTFOLIO-META', 'Metabolic health checks – corporate', 'METABOLIC',
  'Biometric screening (blood pressure, BMI, glucose, cholesterol, waist) for corporate staff with follow-up of abnormal results.',
  'CORP', null, 15000.00, 'GBP', current_date + 45, current_date + 410,
  'promo1,nurse1', '[{"metric":"PARTICIPATION_PCT","target":40},{"metric":"ABNORMAL_FOLLOW_UP_PCT","target":95}]',
  'PLANNED', 'migration', now(), 'migration', now()),
 ('e12c0000-0000-4000-8000-000000000005', 'CMP-PORTFOLIO-WELL', 'Mental wellbeing – all staff', 'MENTAL_WELLBEING',
  'Group-wide wellbeing campaign: awareness sessions, wellbeing checks and signposting to support.',
  'FLIGHTOPS,CABIN,GROUND,ENG,CATERING,CARGO,CORP', null, 20000.00, 'GBP', current_date + 90, current_date + 455,
  'promo1,cmo1', '[{"metric":"PARTICIPATION_PCT","target":40}]',
  'PLANNED', 'migration', now(), 'migration', now());

-- ---------- seed: configuration (screening thresholds: abnormal when the measure matches the comparison) ----------
insert into masterdata.config_entry (config_key, config_value, description, updated_by, updated_at) values
 ('promotion.screening.threshold.systolic',        '>=140', 'Screening: systolic blood pressure (mmHg) that opens a follow-up', 'migration', now()),
 ('promotion.screening.threshold.diastolic',       '>=90',  'Screening: diastolic blood pressure (mmHg) that opens a follow-up', 'migration', now()),
 ('promotion.screening.threshold.bmi',             '>=30',  'Screening: body-mass index that opens a follow-up', 'migration', now()),
 ('promotion.screening.threshold.glucoseMmol',     '>=7.0', 'Screening: fasting glucose (mmol/l) that opens a follow-up', 'migration', now()),
 ('promotion.screening.threshold.cholesterolMmol', '>=6.5', 'Screening: total cholesterol (mmol/l) that opens a follow-up', 'migration', now()),
 ('promotion.screening.threshold.waistCm',         '>=102', 'Screening: waist circumference (cm) that opens a follow-up', 'migration', now()),
 ('promotion.screening.threshold.mskScore',        '>=7',   'Screening: MSK symptom score (0-10) that opens a follow-up', 'migration', now()),
 ('promotion.screening.threshold.wellbeingScore',  '<=40',  'Screening: wellbeing score (14-70) at or below which a follow-up is opened', 'migration', now()),
 ('promotion.screening.follow-up-hours',           '48',    'Hours within which an abnormal screening result must be followed up (E12-F02)', 'migration', now()),
 ('promotion.screening.service.BIOMETRIC',         'NURSE_REVIEW', 'Appointment type booked for biometric screening participants', 'migration', now()),
 ('promotion.screening.service.MSK',               'NURSE_REVIEW', 'Appointment type booked for MSK screening participants', 'migration', now()),
 ('promotion.screening.service.HEARING',           'AUDIOMETRY',   'Appointment type booked for hearing screening participants', 'migration', now()),
 ('promotion.screening.service.VISION',            'VISION',       'Appointment type booked for vision screening participants', 'migration', now()),
 ('promotion.screening.service.MENTAL_WELLBEING',  'NURSE_REVIEW', 'Appointment type booked for wellbeing check participants', 'migration', now()),
 ('promotion.ergonomics.reminder-days',            '7',     'Ergonomic actions: reminder window before the due date and interval between reminders', 'migration', now()),
 ('promotion.ergonomics.closure-target-days',      '60',    'Ergonomic actions: KPI closure window in days (target 80 %)', 'migration', now()),
 ('promotion.participation.target-pct',            '40',    'Campaign participation target per unit (%)', 'migration', now()),
 ('promotion.wearables.max-age-days',              '31',    'Wearable daily aggregates older than this are rejected', 'migration', now())
on conflict (config_key) do nothing;

-- runtime role: DML; deletes only for the wearable purge (withdrawal) and wearable-derived participation
grant usage on schema promotion to ${app_role};
grant select, insert, update on all tables in schema promotion to ${app_role};
grant delete on promotion.wearable_daily, promotion.participation to ${app_role};
grant usage, select on sequence promotion.ref_seq to ${app_role};
