create schema if not exists treatment;
-- =====================================================================================================
-- V14 – E04 Injury & illness management, treatment coordination & partner referrals: treatment plans and follow-ups,
--       variance alerts, e-referral service catalogue and referrals (FHIR R4 ServiceRequest), provider network profiles,
--       versioned contracts / rate cards / SLAs and monthly scorecards, pre-authorisations and provider invoices, clinic
--       formulary / stock batches / administrations, outcome measures and telehealth sessions (tokens hashed, chat
--       encrypted). Clinical free text is encrypted at field level by the application (columns typed text).
-- =====================================================================================================

-- gap-tolerant per-prefix, per-year numbering (RFL-2026-000001, PA-2026-000001, TH-2026-000001)
create table treatment.ref_sequence (
    prefix      varchar(10)  not null,
    seq_year    int          not null,
    last_value  bigint       not null,
    primary key (prefix, seq_year)
);

-- ---------- treatment plans & follow-up (E04-F01) ----------
create table treatment.plan_template (
    code            varchar(40)   primary key,
    injury_type     varchar(60)   not null,
    title           varchar(200)  not null,
    goals           text          not null,     -- JSON ["…"]
    interventions   text          not null,     -- JSON [{type, description, frequency, weeks}]
    follow_up_days  varchar(100)  not null,     -- CSV of days after plan start
    guideline_code  varchar(10),                -- triage.duration_guideline.icd_code
    expected_days   int,
    active          boolean       not null default true
);

create table treatment.treatment_plan (
    id                      uuid primary key,
    series_id               uuid          not null,
    version                 int           not null,
    case_id                 uuid          not null,
    employee_id             varchar(64)   not null,
    status                  varchar(20)   not null,
    template_code           varchar(40),
    goals                   text,                       -- encrypted JSON (null = template content)
    interventions           text,                       -- encrypted JSON (null = template content)
    start_date              date          not null,
    expected_recovery_days  int,
    guideline_code          varchar(10),
    variance_threshold_pct  int,
    responsible_clinician   varchar(200)  not null,
    document_id             uuid,
    status_note             text,                       -- encrypted
    created_by              varchar(200)  not null,
    created_at              timestamptz   not null,
    updated_at              timestamptz   not null,
    constraint uq_plan_version unique (series_id, version),
    constraint ck_plan_status check (status in ('DRAFT','ACTIVE','COMPLETED','CANCELLED','SUPERSEDED')),
    constraint ck_plan_threshold check (variance_threshold_pct is null or variance_threshold_pct between 1 and 500)
);
create index ix_plan_case on treatment.treatment_plan (case_id);
create index ix_plan_employee on treatment.treatment_plan (employee_id);
create index ix_plan_status on treatment.treatment_plan (status);

create table treatment.follow_up (
    id                 uuid primary key,
    plan_id            uuid          not null,
    case_id            uuid          not null,
    employee_id        varchar(64)   not null,
    due_date           date          not null,
    follow_up_type     varchar(20)   not null,
    site_code          varchar(40),
    status             varchar(20)   not null,
    appointment_id     uuid,
    task_id            uuid,
    employee_response  varchar(10),
    response_reason    varchar(500),               -- screened (functional wording only)
    note               text,                       -- encrypted
    reminded_at        timestamptz,
    book_requested     boolean       not null default false,
    created_at         timestamptz   not null,
    updated_at         timestamptz   not null,
    constraint ck_follow_up_type check (follow_up_type in ('OH_REVIEW','PHONE','PHYSIOTHERAPY','SPECIALIST','TELEHEALTH')),
    constraint ck_follow_up_status check (status in ('PLANNED','BOOKED','CONFIRMED','DECLINED','DONE','MISSED','CANCELLED')),
    constraint ck_follow_up_response check (employee_response is null or employee_response in ('CONFIRM','DECLINE'))
);
create index ix_follow_up_plan on treatment.follow_up (plan_id);
create index ix_follow_up_due on treatment.follow_up (status, due_date);

create table treatment.variance_alert (
    id             uuid primary key,
    plan_id        uuid          not null,
    case_id        uuid          not null,
    employee_id    varchar(64)   not null,
    expected_days  int           not null,
    actual_days    int           not null,
    pct_over       numeric(8,1)  not null,
    status         varchar(20)   not null,
    raised_at      timestamptz   not null,
    acted_by       varchar(200),
    acted_at       timestamptz,
    note           text,                          -- encrypted
    constraint ck_variance_status check (status in ('OPEN','ACKNOWLEDGED','ESCALATED','CLOSED'))
);
create index ix_variance_status on treatment.variance_alert (status);
create index ix_variance_plan on treatment.variance_alert (plan_id);

-- ---------- e-referrals (E04-F02) ----------
create table treatment.service_catalogue (
    code                      varchar(40)   primary key,
    name                      varchar(200)  not null,
    provider_types            varchar(300)  not null,    -- CSV of partner organisation types
    required_fields           text          not null,    -- JSON [{code, label, type, required}]
    report_due_days           int           not null,
    urgent_appointment_hours  int           not null,
    routine_appointment_days  int           not null,
    active                    boolean       not null default true
);

create table treatment.referral (
    id                        uuid primary key,
    ref                       varchar(30)   not null unique,
    case_id                   uuid          not null,
    employee_id               varchar(64)   not null,
    service_code              varchar(40)   not null references treatment.service_catalogue (code),
    provider_code             varchar(40),
    urgency                   varchar(10)   not null,
    status                    varchar(20)   not null,
    channel                   varchar(20)   not null,
    reason                    text,                       -- encrypted
    question                  text,                       -- encrypted
    data_set                  text,                       -- encrypted JSON {fieldCode: value}
    consent_id                uuid,
    attachments               text,                       -- JSON [{documentId, title}]
    letter_document_id        uuid,
    report_document_id        uuid,
    report_summary            text,                       -- encrypted
    fhir                      text,                       -- encrypted FHIR R4 ServiceRequest as sent
    created_by                varchar(200)  not null,
    created_at                timestamptz   not null,
    updated_at                timestamptz   not null,
    sent_by                   varchar(200),
    sent_at                   timestamptz,
    accepted_at               timestamptz,
    declined_at               timestamptz,
    decline_reason            varchar(500),
    appointment_at            timestamptz,
    appointment_location      varchar(300),
    appointment_instructions  varchar(1000),
    seen_at                   timestamptz,
    report_due_at             timestamptz,
    report_received_at        timestamptz,
    closed_at                 timestamptz,
    cancelled_at              timestamptz,
    cancel_reason             text,                       -- encrypted
    chase_count               int           not null default 0,
    last_chased_at            timestamptz,
    escalated                 boolean       not null default false,
    rating                    int,
    rating_comment            text,                       -- encrypted
    rated_by                  varchar(200),
    source_type               varchar(40),
    source_ref                varchar(200),
    partner_read_at           timestamptz,
    constraint ck_referral_status check (status in ('DRAFT','SENT','ACCEPTED','DECLINED','SCHEDULED','SEEN','REPORT_RECEIVED','CLOSED','CANCELLED')),
    constraint ck_referral_urgency check (urgency in ('ROUTINE','URGENT')),
    constraint ck_referral_channel check (channel in ('PORTAL','FHIR','SECURE_EMAIL')),
    constraint ck_referral_rating check (rating is null or rating between 1 and 5)
);
create index ix_referral_case on treatment.referral (case_id);
create index ix_referral_status on treatment.referral (status);
create index ix_referral_provider on treatment.referral (provider_code, status);
create index ix_referral_source on treatment.referral (source_type, source_ref);

create table treatment.referral_history (
    id           uuid primary key,
    referral_id  uuid          not null,
    status       varchar(20)   not null,
    at           timestamptz   not null,
    by_actor     varchar(200)  not null
);
create index ix_referral_history on treatment.referral_history (referral_id, at);

-- ---------- provider network (E04-F03) ----------
create table treatment.provider_profile (
    code            varchar(40)   primary key references masterdata.partner_organisation (code),
    specialties     varchar(500),
    services        varchar(1000),               -- CSV of service catalogue codes
    languages       varchar(200),
    accreditation   text,                        -- JSON [{body, validUntil}]
    coverage        varchar(20),
    latitude        numeric(9,6),
    longitude       numeric(9,6),
    preferred       boolean       not null default false,
    updated_by      varchar(200)  not null,
    updated_at      timestamptz   not null,
    constraint ck_provider_coverage check (coverage is null or coverage in ('HUB','OUTSTATION','LAYOVER'))
);

create table treatment.provider_contract (
    id                uuid primary key,
    provider_code     varchar(40)   not null references masterdata.partner_organisation (code),
    ref               varchar(40)   not null,
    version           int           not null,
    start_date        date          not null,
    end_date          date          not null,
    rates             text          not null,    -- JSON [{serviceCode, amount, currency}]
    appointment_days  int,
    urgent_hours      int,
    report_days       int,
    current_version   boolean       not null,
    created_by        varchar(200)  not null,
    created_at        timestamptz   not null,
    constraint uq_contract_version unique (provider_code, version),
    constraint ck_contract_dates check (end_date >= start_date)
);
create index ix_contract_current on treatment.provider_contract (provider_code) where current_version;

create table treatment.provider_scorecard (
    id                          uuid primary key,
    provider_code               varchar(40)   not null,
    month                       varchar(7)    not null,
    referrals                   int           not null,
    median_days_to_appointment  numeric(6,1),
    report_within7_pct          numeric(5,1),
    avg_report_quality          numeric(3,1),
    outcome_improved_pct        numeric(5,1),
    avg_invoice                 numeric(12,2),
    currency                    varchar(3),
    timeliness                  numeric(5,1),
    report_quality              numeric(5,1),
    outcome_score               numeric(5,1),
    cost_score                  numeric(5,1),
    overall                     numeric(5,1),
    computed_at                 timestamptz   not null,
    constraint uq_scorecard unique (provider_code, month)
);

-- ---------- pre-authorisation & invoices (E04-F04) ----------
create table treatment.preauth (
    id                uuid primary key,
    ref               varchar(30)   not null unique,
    case_id           uuid          not null,
    employee_id       varchar(64)   not null,
    payer_code        varchar(40)   not null,
    service_code      varchar(40)   not null,
    provider_code     varchar(40)   not null,
    referral_id       uuid,
    estimate_amount   numeric(12,2) not null,
    currency          varchar(3)    not null,
    justification     text,                       -- encrypted (OH only)
    consent_id        uuid,
    status            varchar(20)   not null,
    document_id       uuid,
    requested_by      varchar(200)  not null,
    created_at        timestamptz   not null,
    updated_at        timestamptz   not null,
    submitted_at      timestamptz,
    sla_due_at        timestamptz,
    decided_at        timestamptz,
    decided_by        varchar(200),
    approval_ref      varchar(60),
    approved_amount   numeric(12,2),
    valid_until       date,
    decision_note     varchar(1000),               -- screened (non-clinical)
    constraint ck_preauth_status check (status in ('DRAFT','SUBMITTED','APPROVED','PARTIALLY_APPROVED','DECLINED','EXPIRED','CANCELLED')),
    constraint ck_preauth_amount check (estimate_amount > 0)
);
create index ix_preauth_case on treatment.preauth (case_id);
create index ix_preauth_status on treatment.preauth (status);
create index ix_preauth_payer on treatment.preauth (payer_code, status);

create table treatment.invoice (
    id                  uuid primary key,
    case_id             uuid          not null,
    employee_id         varchar(64)   not null,
    provider_code       varchar(40)   not null,
    invoice_number      varchar(60)   not null,
    amount              numeric(12,2) not null,
    currency            varchar(3)    not null,
    service_date        date          not null,
    referral_id         uuid,
    preauth_id          uuid,
    document_id         uuid,
    status              varchar(20)   not null,
    matched_preauth_id  uuid,
    variance            numeric(12,2),
    note                varchar(1000),             -- screened (non-clinical)
    created_by          varchar(200)  not null,
    created_at          timestamptz   not null,
    updated_at          timestamptz   not null,
    constraint uq_invoice_number unique (provider_code, invoice_number),
    constraint ck_invoice_status check (status in ('RECEIVED','MATCHED','DISPUTED','APPROVED','PAID')),
    constraint ck_invoice_amount check (amount > 0)
);
create index ix_invoice_case on treatment.invoice (case_id);
create index ix_invoice_service_date on treatment.invoice (service_date);

-- ---------- clinic medication administration (E04-F05) ----------
create table treatment.formulary_item (
    code            varchar(40)   primary key,
    name            varchar(200)  not null,
    form            varchar(40)   not null,
    strength        varchar(60),
    unit            varchar(20)   not null,
    routes          varchar(200)  not null,      -- CSV
    snomed_code     varchar(20),
    stock_tracked   boolean       not null default true,
    reorder_level   int           not null default 0,
    active          boolean       not null default true,
    updated_by      varchar(200)  not null,
    updated_at      timestamptz   not null
);

create table treatment.stock_batch (
    id            uuid primary key,
    item_code     varchar(40)   not null references treatment.formulary_item (code),
    batch_number  varchar(40)   not null,
    expiry        date          not null,
    quantity      int           not null,
    site_code     varchar(40)   not null,
    received_by   varchar(200)  not null,
    received_at   timestamptz   not null,
    constraint uq_stock_batch unique (item_code, batch_number, site_code),
    constraint ck_stock_quantity check (quantity >= 0)
);

create table treatment.administration (
    id                uuid primary key,
    case_id           uuid          not null,
    employee_id       varchar(64)   not null,
    item_code         varchar(40)   not null,
    item_name         varchar(200)  not null,
    dose              text          not null,      -- encrypted
    route             varchar(40)   not null,
    batch_number      varchar(40)   not null,
    expiry            date          not null,
    site_code         varchar(40)   not null,
    administered_by   varchar(200)  not null,
    administered_at   timestamptz   not null,
    allergy_override  text,                        -- encrypted reason
    medication_id     uuid
);
create index ix_administration_case on treatment.administration (case_id);
create index ix_administration_at on treatment.administration (administered_at);

-- ---------- outcomes (E04-F06) ----------
create table treatment.outcome (
    id                       uuid primary key,
    case_id                  uuid          not null,
    employee_id              varchar(64)   not null,
    milestone                varchar(10)   not null,
    pain_score               int           not null,
    function_scale           varchar(10)   not null,
    function_score           int           not null,
    rtw_date                 date,
    residual_impairment_pct  int,
    note                     text,                 -- encrypted
    recorded_by              varchar(200)  not null,
    recorded_at              timestamptz   not null,
    constraint ck_outcome_milestone check (milestone in ('BASELINE','REVIEW','CLOSURE')),
    constraint ck_outcome_pain check (pain_score between 0 and 10),
    constraint ck_outcome_scale check (function_scale in ('NONE','ODI','QUICKDASH','PSFS','LEFS')),
    constraint ck_outcome_function check (function_score between 0 and 100),
    constraint ck_outcome_impairment check (residual_impairment_pct is null or residual_impairment_pct between 0 and 100)
);
create index ix_outcome_case on treatment.outcome (case_id, recorded_at);

-- ---------- telehealth (E04-F07) ----------
create table treatment.telehealth_session (
    id                  uuid primary key,
    ref                 varchar(30)   not null unique,
    employee_id         varchar(64)   not null,
    case_id             uuid,
    scheduled_at        timestamptz   not null,
    duration_minutes    int           not null,
    mode                varchar(10)   not null,
    status              varchar(20)   not null,
    clinician_username  varchar(200)  not null,
    provider_code       varchar(40),
    crew_layover        boolean       not null default false,
    layover_city        varchar(100),
    consent_id          uuid,
    appointment_id      uuid,
    encounter_id        uuid,
    cancel_reason       text,                      -- encrypted
    partner_read_at     timestamptz,
    created_by          varchar(200)  not null,
    created_at          timestamptz   not null,
    updated_at          timestamptz   not null,
    completed_at        timestamptz,
    constraint ck_telehealth_mode check (mode in ('VIDEO','CHAT')),
    constraint ck_telehealth_status check (status in ('SCHEDULED','IN_PROGRESS','COMPLETED','CANCELLED','NO_SHOW')),
    constraint ck_telehealth_duration check (duration_minutes between 10 and 90)
);
create index ix_telehealth_scheduled on treatment.telehealth_session (scheduled_at);
create index ix_telehealth_employee on treatment.telehealth_session (employee_id);

create table treatment.telehealth_token (
    id            uuid primary key,
    session_id    uuid          not null,
    participant   varchar(20)   not null,
    username      varchar(200)  not null,
    token_hash    varchar(64)   not null unique,
    valid_from    timestamptz   not null,
    valid_until   timestamptz   not null,
    used_at       timestamptz,
    revoked       boolean       not null default false,
    created_at    timestamptz   not null
);
create index ix_telehealth_token_session on treatment.telehealth_token (session_id);

create table treatment.telehealth_message (
    id           uuid primary key,
    session_id   uuid          not null,
    sender       varchar(200)  not null,
    sender_role  varchar(20)   not null,
    text         text          not null,           -- encrypted
    sent_at      timestamptz   not null
);
create index ix_telehealth_message on treatment.telehealth_message (session_id, sent_at);

-- =====================================================================================================
-- seed data
-- =====================================================================================================

-- plan templates (draft content for CMO approval); guideline codes refer to triage.duration_guideline
insert into treatment.plan_template (code, injury_type, title, goals, interventions, follow_up_days, guideline_code, expected_days) values
 ('BACK_STRAIN', 'BACK', 'Acute low back strain',
  '["Pain controlled with simple analgesia","Return to full ramp / manual handling duties","Independent home exercise programme"]',
  '[{"type":"PHYSIOTHERAPY","description":"Physiotherapy – mobility and core stability","frequency":"1x weekly","weeks":4},{"type":"SELF_CARE","description":"Graded activity and home exercises","frequency":"daily","weeks":4},{"type":"OH_REVIEW","description":"OH review of fitness and restrictions","frequency":"fortnightly","weeks":4}]',
  '7,14,21', 'M54.5', 21),
 ('SHOULDER_STRAIN', 'SHOULDER', 'Shoulder strain / rotator cuff irritation',
  '["Restore pain-free range of movement","Return to overhead and lifting tasks"]',
  '[{"type":"PHYSIOTHERAPY","description":"Physiotherapy – rotator cuff programme","frequency":"1x weekly","weeks":6},{"type":"MEDICATION","description":"Simple analgesia as required","frequency":"as required","weeks":2},{"type":"OH_REVIEW","description":"OH review","frequency":"fortnightly","weeks":6}]',
  '14,28,42', null, 28),
 ('WRIST_SPRAIN', 'WRIST', 'Wrist sprain',
  '["Pain-free grip","Return to full keyboard / handling duties"]',
  '[{"type":"SELF_CARE","description":"Support splint and graded mobilisation","frequency":"daily","weeks":2},{"type":"PHYSIOTHERAPY","description":"Hand therapy if not improving","frequency":"1x weekly","weeks":3}]',
  '7,21', null, 21),
 ('KNEE_SPRAIN', 'KNEE', 'Knee sprain',
  '["Full weight bearing without pain","Return to stairs, steps and aircraft steps"]',
  '[{"type":"PHYSIOTHERAPY","description":"Physiotherapy – strengthening and proprioception","frequency":"1x weekly","weeks":6},{"type":"IMAGING","description":"MRI if instability persists","frequency":"once","weeks":1},{"type":"OH_REVIEW","description":"OH review","frequency":"fortnightly","weeks":6}]',
  '14,28', 'S83.6', 28),
 ('HAND_LACERATION', 'HAND', 'Hand laceration',
  '["Wound healed without infection","Return to full hand use"]',
  '[{"type":"OH_REVIEW","description":"Wound check and dressing","frequency":"every 2–3 days","weeks":1},{"type":"SELF_CARE","description":"Keep dressing clean and dry","frequency":"daily","weeks":1}]',
  '3,7', 'S61.4', 7),
 ('CONCUSSION_MILD', 'HEAD', 'Mild concussion',
  '["Symptom-free at rest and on exertion","Graded return to safety-critical duties"]',
  '[{"type":"SELF_CARE","description":"Relative rest then graded return to activity","frequency":"daily","weeks":2},{"type":"OH_REVIEW","description":"OH review before safety-critical duties","frequency":"weekly","weeks":2}]',
  '2,7,14', null, 14),
 ('WORK_STRESS', 'MENTAL', 'Work-related stress',
  '["Improved sleep and coping","Agreed workplace adjustments","Sustained return to work"]',
  '[{"type":"PSYCHOLOGICAL_THERAPY","description":"Talking therapy (6 sessions)","frequency":"1x weekly","weeks":6},{"type":"OH_REVIEW","description":"OH review and stress risk assessment","frequency":"fortnightly","weeks":6}]',
  '14,28,42', 'F43.2', 42),
 ('NOISE_HEARING', 'EAR', 'Noise-induced hearing change',
  '["Hearing protection fit-tested and used","Confirmed audiological assessment"]',
  '[{"type":"SPECIALIST_REVIEW","description":"Audiology / ENT assessment","frequency":"once","weeks":4},{"type":"OH_REVIEW","description":"Repeat audiometry","frequency":"once","weeks":4}]',
  '28', null, 30);

-- e-referral service catalogue (E04-BL-005): required data set per service
insert into treatment.service_catalogue (code, name, provider_types, required_fields, report_due_days, urgent_appointment_hours, routine_appointment_days) values
 ('ORTHOPAEDICS', 'Orthopaedic consultation', 'HOSPITAL,SPECIALIST',
  '[{"code":"bodyPart","label":"Body part","type":"CODE","required":true},{"code":"injuryDate","label":"Date of injury","type":"DATE","required":true},{"code":"imagingDone","label":"Imaging already performed","type":"LIST","required":false}]', 14, 48, 10),
 ('PHYSIOTHERAPY', 'Physiotherapy assessment & treatment', 'PHYSIOTHERAPY,HOSPITAL',
  '[{"code":"bodyPart","label":"Body part","type":"CODE","required":true},{"code":"sessions","label":"Sessions requested","type":"TEXT","required":true},{"code":"precautions","label":"Precautions","type":"TEXT","required":false}]', 21, 48, 5),
 ('IMAGING_MRI', 'MRI scan', 'IMAGING,HOSPITAL',
  '[{"code":"bodyPart","label":"Body part","type":"CODE","required":true},{"code":"clinicalIndication","label":"Clinical indication","type":"TEXT","required":true},{"code":"implants","label":"Metal implants / pacemaker","type":"LIST","required":true}]', 7, 24, 7),
 ('IMAGING_XRAY', 'X-ray', 'IMAGING,HOSPITAL,CLINIC',
  '[{"code":"bodyPart","label":"Body part","type":"CODE","required":true},{"code":"clinicalIndication","label":"Clinical indication","type":"TEXT","required":true}]', 5, 24, 3),
 ('CARDIOLOGY', 'Cardiology review', 'SPECIALIST,HOSPITAL',
  '[{"code":"ecgDate","label":"Date of last ECG","type":"DATE","required":true},{"code":"symptoms","label":"Symptoms","type":"TEXT","required":true}]', 14, 48, 14),
 ('OPHTHALMOLOGY', 'Ophthalmology review', 'SPECIALIST,HOSPITAL',
  '[{"code":"visualAcuity","label":"Visual acuity","type":"TEXT","required":true}]', 14, 48, 14),
 ('ENT_AUDIOLOGY', 'ENT / audiology assessment', 'SPECIALIST,HOSPITAL',
  '[{"code":"audiogramDate","label":"Date of last audiogram","type":"DATE","required":true},{"code":"noiseExposure","label":"Noise exposure","type":"TEXT","required":false}]', 14, 72, 21),
 ('DERMATOLOGY', 'Occupational dermatology', 'SPECIALIST',
  '[{"code":"exposure","label":"Workplace exposure","type":"TEXT","required":true},{"code":"patchTesting","label":"Patch testing requested","type":"LIST","required":false}]', 21, 72, 21),
 ('MENTAL_HEALTH_THERAPY', 'Psychological therapy', 'MENTAL_HEALTH',
  '[{"code":"sessions","label":"Sessions requested","type":"TEXT","required":true},{"code":"riskLevel","label":"Risk level","type":"CODE","required":true}]', 28, 72, 10),
 ('PSYCHIATRY', 'Psychiatric assessment', 'MENTAL_HEALTH',
  '[{"code":"riskLevel","label":"Risk level","type":"CODE","required":true},{"code":"currentTreatment","label":"Current treatment","type":"TEXT","required":false}]', 14, 24, 14),
 ('LAB_TESTS', 'Laboratory tests', 'LABORATORY,HOSPITAL',
  '[{"code":"tests","label":"Tests requested","type":"LIST","required":true},{"code":"fasting","label":"Fasting required","type":"LIST","required":false}]', 3, 24, 3),
 ('TELEHEALTH_SPECIALIST', 'Telehealth specialist consultation', 'TELEHEALTH',
  '[{"code":"specialty","label":"Specialty","type":"CODE","required":true}]', 7, 24, 3),
 ('ASSISTANCE_LAYOVER', 'Crew medical assistance at layover', 'ASSISTANCE,CLINIC,HOSPITAL',
  '[{"code":"layoverCity","label":"Layover city","type":"TEXT","required":true},{"code":"flightNumber","label":"Return flight","type":"CODE","required":true},{"code":"hotel","label":"Crew hotel","type":"TEXT","required":false}]', 3, 12, 1),
 ('IME', 'Independent medical examination', 'SPECIALIST,HOSPITAL',
  '[{"code":"questions","label":"Questions for the examiner","type":"TEXT","required":true},{"code":"injuryDate","label":"Date of injury","type":"DATE","required":true}]', 21, 72, 21);

-- provider network profiles (E04-BL-019, illustrative)
insert into treatment.provider_profile (code, specialties, services, languages, accreditation, coverage, latitude, longitude, preferred, updated_by, updated_at) values
 ('CITYHOSP',      'Orthopaedics,Emergency medicine,Radiology', 'ORTHOPAEDICS,IMAGING_MRI,IMAGING_XRAY,CARDIOLOGY,LAB_TESTS,IME', 'en,ar,fr',
  '[{"body":"CQC","validUntil":"2027-06-30"}]', 'HUB', 51.517300, -0.106000, true, 'migration', now()),
 ('HEATHROWMED',   'Occupational medicine,General practice', 'IMAGING_XRAY,LAB_TESTS,PHYSIOTHERAPY', 'en,hi',
  '[{"body":"CQC","validUntil":"2027-03-31"}]', 'HUB', 51.470000, -0.454300, true, 'migration', now()),
 ('STMARYS_ORTHO', 'Orthopaedics,Sports medicine', 'ORTHOPAEDICS,IME', 'en',
  '[{"body":"CQC","validUntil":"2027-09-30"}]', 'HUB', 51.517000, -0.174000, true, 'migration', now()),
 ('CARDIO_LDN',    'Cardiology', 'CARDIOLOGY', 'en',
  '[{"body":"CQC","validUntil":"2026-12-31"}]', 'HUB', 51.520000, -0.150000, false, 'migration', now()),
 ('EYE_LDN',       'Ophthalmology', 'OPHTHALMOLOGY', 'en',
  '[{"body":"CQC","validUntil":"2027-01-31"}]', 'HUB', 51.524000, -0.088000, false, 'migration', now()),
 ('ENT_AUDIO',     'ENT,Audiology', 'ENT_AUDIOLOGY', 'en',
  '[{"body":"CQC","validUntil":"2027-04-30"}]', 'HUB', 51.511000, -0.595000, false, 'migration', now()),
 ('DERM_LDN',      'Dermatology,Occupational dermatology', 'DERMATOLOGY', 'en',
  '[{"body":"CQC","validUntil":"2027-02-28"}]', 'HUB', 51.500000, -0.120000, false, 'migration', now()),
 ('PHYSIOPLUS',    'Physiotherapy,Work conditioning', 'PHYSIOTHERAPY', 'en,pl',
  '[{"body":"CSP","validUntil":"2027-05-31"}]', 'HUB', 51.468000, -0.361000, true, 'migration', now()),
 ('REHAB_HUB',     'Physiotherapy,Functional restoration', 'PHYSIOTHERAPY', 'en',
  '[{"body":"CSP","validUntil":"2026-11-30"}]', 'HUB', 51.433000, -0.508000, false, 'migration', now()),
 ('LDN_IMAGING',   'Radiology', 'IMAGING_MRI,IMAGING_XRAY', 'en',
  '[{"body":"ISAS","validUntil":"2027-08-31"}]', 'HUB', 51.515000, -0.141000, true, 'migration', now()),
 ('PATHLAB',       'Pathology', 'LAB_TESTS', 'en',
  '[{"body":"UKAS ISO 15189","validUntil":"2027-10-31"}]', 'HUB', 51.502000, -0.118000, false, 'migration', now()),
 ('MINDCARE',      'Clinical psychology,CBT', 'MENTAL_HEALTH_THERAPY', 'en,ar',
  '[{"body":"BPS","validUntil":"2027-03-31"}]', 'HUB', 51.509000, -0.134000, true, 'migration', now()),
 ('OCC_PSYCH',     'Psychiatry,Aviation peer support', 'PSYCHIATRY,MENTAL_HEALTH_THERAPY', 'en',
  '[]', 'HUB', 51.507000, -0.128000, false, 'migration', now()),
 ('TELEDOC',       'Telehealth,Dermatology,Internal medicine', 'TELEHEALTH_SPECIALIST,DERMATOLOGY', 'en,fr,ar,es',
  '[{"body":"CQC","validUntil":"2027-12-31"}]', 'LAYOVER', null, null, true, 'migration', now()),
 ('GLOBALASSIST',  'Travel medicine,Crew assistance', 'ASSISTANCE_LAYOVER', 'en,fr,es,zh,ar',
  '[{"body":"ISO 9001","validUntil":"2027-06-30"}]', 'LAYOVER', 51.507000, -0.127000, true, 'migration', now()),
 ('DXB_AMERICAN',  'Emergency medicine,Orthopaedics,Radiology', 'ORTHOPAEDICS,IMAGING_XRAY,IMAGING_MRI,ASSISTANCE_LAYOVER', 'en,ar',
  '[{"body":"JCI","validUntil":"2027-02-28"}]', 'OUTSTATION', 25.234000, 55.315000, true, 'migration', now()),
 ('DXB_PHYSIO',    'Physiotherapy', 'PHYSIOTHERAPY', 'en,ar',
  '[{"body":"DHA","validUntil":"2027-01-31"}]', 'OUTSTATION', 25.204000, 55.270000, false, 'migration', now()),
 ('JFK_URGENT',    'Urgent care', 'IMAGING_XRAY,ASSISTANCE_LAYOVER', 'en,es',
  '[{"body":"UCAOA","validUntil":"2026-09-30"}]', 'LAYOVER', 40.641000, -73.778000, false, 'migration', now()),
 ('SIN_MEDICAL',   'Travel medicine,Urgent care', 'ASSISTANCE_LAYOVER,IMAGING_XRAY', 'en,zh,ms',
  '[{"body":"MOH Singapore","validUntil":"2027-05-31"}]', 'LAYOVER', 1.364000, 103.991000, false, 'migration', now()),
 ('HKG_CLINIC',    'Crew medicine', 'ASSISTANCE_LAYOVER', 'en,zh',
  '[]', 'LAYOVER', 22.308000, 113.918000, false, 'migration', now());

-- contracts, rate cards and SLAs: REHAB_HUB expiring, JFK_URGENT expired, OCC_PSYCH and HKG_CLINIC without contract
insert into treatment.provider_contract (id, provider_code, ref, version, start_date, end_date, rates, appointment_days, urgent_hours, report_days,
                                         current_version, created_by, created_at)
select v.id::uuid, v.code, v.ref, 1, current_date - v.started, current_date + v.ends, v.rates, v.appt, v.urgent, v.report, true, 'migration', now()
  from (values
 ('e0400000-0000-4000-c000-000000000001', 'CITYHOSP',      'CT-CITYHOSP-2026',    300, 430, '[{"serviceCode":"ORTHOPAEDICS","amount":"280.00","currency":"GBP"},{"serviceCode":"IMAGING_MRI","amount":"450.00","currency":"GBP"},{"serviceCode":"IMAGING_XRAY","amount":"95.00","currency":"GBP"},{"serviceCode":"CARDIOLOGY","amount":"320.00","currency":"GBP"},{"serviceCode":"LAB_TESTS","amount":"60.00","currency":"GBP"},{"serviceCode":"IME","amount":"900.00","currency":"GBP"}]', 7, 48, 7),
 ('e0400000-0000-4000-c000-000000000002', 'HEATHROWMED',   'CT-HEATHROWMED-2026', 200, 530, '[{"serviceCode":"IMAGING_XRAY","amount":"85.00","currency":"GBP"},{"serviceCode":"LAB_TESTS","amount":"55.00","currency":"GBP"},{"serviceCode":"PHYSIOTHERAPY","amount":"65.00","currency":"GBP"}]', 3, 24, 5),
 ('e0400000-0000-4000-c000-000000000003', 'STMARYS_ORTHO', 'CT-STMARYS-2026',     100, 630, '[{"serviceCode":"ORTHOPAEDICS","amount":"260.00","currency":"GBP"},{"serviceCode":"IME","amount":"850.00","currency":"GBP"}]', 10, 48, 7),
 ('e0400000-0000-4000-c000-000000000004', 'CARDIO_LDN',    'CT-CARDIO-2026',      150, 400, '[{"serviceCode":"CARDIOLOGY","amount":"340.00","currency":"GBP"}]', 14, 72, 10),
 ('e0400000-0000-4000-c000-000000000005', 'EYE_LDN',       'CT-EYE-2026',         150, 400, '[{"serviceCode":"OPHTHALMOLOGY","amount":"210.00","currency":"GBP"}]', 14, 72, 10),
 ('e0400000-0000-4000-c000-000000000006', 'ENT_AUDIO',     'CT-ENT-2026',         150, 400, '[{"serviceCode":"ENT_AUDIOLOGY","amount":"230.00","currency":"GBP"}]', 21, 72, 10),
 ('e0400000-0000-4000-c000-000000000007', 'DERM_LDN',      'CT-DERM-2026',        150, 400, '[{"serviceCode":"DERMATOLOGY","amount":"220.00","currency":"GBP"}]', 21, 72, 14),
 ('e0400000-0000-4000-c000-000000000008', 'PHYSIOPLUS',    'CT-PHYSIOPLUS-2026',  250, 480, '[{"serviceCode":"PHYSIOTHERAPY","amount":"60.00","currency":"GBP"}]', 5, 48, 7),
 ('e0400000-0000-4000-c000-000000000009', 'REHAB_HUB',     'CT-REHABHUB-2025',    335, 30,  '[{"serviceCode":"PHYSIOTHERAPY","amount":"70.00","currency":"GBP"}]', 7, 72, 10),
 ('e0400000-0000-4000-c000-000000000010', 'LDN_IMAGING',   'CT-LDNIMAGING-2026',  200, 530, '[{"serviceCode":"IMAGING_MRI","amount":"420.00","currency":"GBP"},{"serviceCode":"IMAGING_XRAY","amount":"80.00","currency":"GBP"}]', 5, 24, 3),
 ('e0400000-0000-4000-c000-000000000011', 'PATHLAB',       'CT-PATHLAB-2026',     200, 530, '[{"serviceCode":"LAB_TESTS","amount":"45.00","currency":"GBP"}]', 2, 24, 3),
 ('e0400000-0000-4000-c000-000000000012', 'MINDCARE',      'CT-MINDCARE-2026',    180, 550, '[{"serviceCode":"MENTAL_HEALTH_THERAPY","amount":"95.00","currency":"GBP"}]', 10, 72, 14),
 ('e0400000-0000-4000-c000-000000000014', 'TELEDOC',       'CT-TELEDOC-2026',     120, 610, '[{"serviceCode":"TELEHEALTH_SPECIALIST","amount":"75.00","currency":"GBP"},{"serviceCode":"DERMATOLOGY","amount":"90.00","currency":"GBP"}]', 2, 12, 3),
 ('e0400000-0000-4000-c000-000000000015', 'GLOBALASSIST',  'CT-GLOBALASSIST-2026',220, 510, '[{"serviceCode":"ASSISTANCE_LAYOVER","amount":"150.00","currency":"GBP"}]', 1, 12, 3),
 ('e0400000-0000-4000-c000-000000000016', 'DXB_AMERICAN',  'CT-DXBAH-2026',       160, 570, '[{"serviceCode":"ORTHOPAEDICS","amount":"300.00","currency":"GBP"},{"serviceCode":"IMAGING_XRAY","amount":"100.00","currency":"GBP"},{"serviceCode":"IMAGING_MRI","amount":"480.00","currency":"GBP"},{"serviceCode":"ASSISTANCE_LAYOVER","amount":"180.00","currency":"GBP"}]', 3, 24, 5),
 ('e0400000-0000-4000-c000-000000000017', 'DXB_PHYSIO',    'CT-DXBPHYSIO-2026',   160, 570, '[{"serviceCode":"PHYSIOTHERAPY","amount":"55.00","currency":"GBP"}]', 5, 48, 7),
 ('e0400000-0000-4000-c000-000000000018', 'JFK_URGENT',    'CT-JFK-2025',         385, -20, '[{"serviceCode":"IMAGING_XRAY","amount":"140.00","currency":"GBP"},{"serviceCode":"ASSISTANCE_LAYOVER","amount":"220.00","currency":"GBP"}]', 1, 12, 3),
 ('e0400000-0000-4000-c000-000000000019', 'SIN_MEDICAL',   'CT-SINMED-2026',      140, 590, '[{"serviceCode":"ASSISTANCE_LAYOVER","amount":"160.00","currency":"GBP"},{"serviceCode":"IMAGING_XRAY","amount":"90.00","currency":"GBP"}]', 1, 12, 3)
  ) as v(id, code, ref, started, ends, rates, appt, urgent, report);

-- clinic formulary (E04-F05)
insert into treatment.formulary_item (code, name, form, strength, unit, routes, snomed_code, stock_tracked, reorder_level, updated_by, updated_at) values
 ('PARACETAMOL_500',   'Paracetamol',                      'TABLET',    '500 mg',          'tablet',  'ORAL',                    '322236009', true,  100, 'migration', now()),
 ('IBUPROFEN_400',     'Ibuprofen',                        'TABLET',    '400 mg',          'tablet',  'ORAL',                    '329653008', true,  100, 'migration', now()),
 ('CETIRIZINE_10',     'Cetirizine',                       'TABLET',    '10 mg',           'tablet',  'ORAL',                    '320800008', true,  30,  'migration', now()),
 ('HYDROCORTISONE_1',  'Hydrocortisone cream',             'CREAM',     '1 %',             'tube',    'TOPICAL',                 '331646005', true,  5,   'migration', now()),
 ('CHLORHEXIDINE_05',  'Chlorhexidine',                    'SOLUTION',  '0.5 %',           'ml',      'TOPICAL',                 '387092000', true,  2,   'migration', now()),
 ('ADRENALINE_AI_300', 'Adrenaline auto-injector',         'INJECTION', '300 micrograms',  'device',  'INTRAMUSCULAR',           '317938005', true,  4,   'migration', now()),
 ('SALBUTAMOL_100',    'Salbutamol inhaler',               'INHALER',   '100 micrograms',  'inhaler', 'INHALATION',              '320176004', true,  3,   'migration', now()),
 ('ORS_SACHET',        'Oral rehydration salts',           'POWDER',    'sachet',          'sachet',  'ORAL',                    '387553006', true,  20,  'migration', now()),
 ('TD_VACCINE',        'Tetanus / diphtheria vaccine',     'INJECTION', '0.5 ml',          'dose',    'INTRAMUSCULAR',           '871826000', true,  10,  'migration', now()),
 ('LIDOCAINE_1',       'Lidocaine 1 %',                    'INJECTION', '1 %',             'ml',      'SUBCUTANEOUS,INFILTRATION','387480006', true,  5,   'migration', now());

insert into masterdata.config_entry (config_key, config_value, description, updated_by, updated_at) values
 ('modules.e04.enabled',                    'true', 'Treatment coordination, referrals & provider network', 'migration', now()),
 ('treatment.variance.threshold-pct',       '20',   'Expected-recovery overrun (%) that raises a treatment variance alert (E04-F01)', 'migration', now()),
 ('treatment.follow-up.reminder-days',      '2',    'Days before a follow-up when the employee is reminded', 'migration', now()),
 ('treatment.referral.max-chases',          '2',    'Automatic report chases before a referral is escalated to the referring clinician (E04-F02)', 'migration', now()),
 ('treatment.referral.chase-interval-days', '2',    'Minimum days between two automatic report chases', 'migration', now()),
 ('treatment.preauth.sla-hours',            '48',   'Insurer / TPA decision SLA for pre-authorisation requests (E04-F04)', 'migration', now()),
 ('treatment.invoice.tolerance-pct',        '5',    'Invoice tolerance (%) against the approved pre-authorisation amount', 'migration', now()),
 ('treatment.base-currency',                'GBP',  'Reporting currency of treatment cost roll-ups', 'migration', now()),
 ('treatment.contract.expiring-days',       '60',   'Provider contracts ending within this many days are EXPIRING', 'migration', now()),
 ('treatment.network.cities',               'London:HUB,Dubai:OUTSTATION,New York:LAYOVER,Singapore:LAYOVER,Hong Kong:LAYOVER',
                                            'Hub / outstation / layover cities checked by the provider network-gap report', 'migration', now()),
 ('treatment.telehealth.base-url',          'https://telehealth.wellnessz.local/room/', 'Default telehealth link provider base URL (E04-F07)', 'migration', now()),
 ('treatment.eprescribing.route',           'NONE', 'E-prescribing route (pending legal confirmation, E04-BL-013)', 'migration', now())
on conflict (config_key) do nothing;

-- runtime role: DML only; clinical tables have no DELETE (corrections are state changes)
grant usage on schema treatment to ${app_role};
grant select, insert, update on all tables in schema treatment to ${app_role};
grant select on triage.duration_guideline to ${app_role};
