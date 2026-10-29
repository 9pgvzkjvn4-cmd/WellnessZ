create schema if not exists surveillance;
-- =====================================================================================================
-- V11 – E07 Medical surveillance, periodic & statutory medicals: programme builder (versioned), enrolments / due list,
--       invitations, pre-employment (candidates are NOT in the HRIS master – no FK to masterdata.employee anywhere),
--       crew medical certificates, immunisation matrix / administrations / titres, exposure groups (SEG) and IH
--       measurements, cosmic dose, result evaluation rules and follow-ups, return-after-illness, compliance snapshots and
--       audit packs. Clinical free text is encrypted at field level by the application (columns typed text).
-- =====================================================================================================

-- ---------- programmes (E07-F01) ----------
create table surveillance.programme (
    id                      uuid primary key,
    code                    varchar(40)   not null,
    version                 int           not null,
    title                   varchar(200)  not null,
    status                  varchar(20)   not null,
    effective_from          date,
    eligibility             text          not null,     -- JSON {jobCodes[], departmentCodes[], subsidiaryCodes[], hazardCodes[], exposureGroups[], licenceTypes[], minAge?}
    panel                   text          not null,     -- JSON [{test, standard?}]
    frequency_months        int           not null,
    frequency_months_over40 int,
    standard_code           varchar(40),
    responsible_role        varchar(40)   not null,
    service_code            varchar(40)   not null,
    regulatory_basis        varchar(500),
    approved_by             varchar(200),
    approved_at             timestamptz,
    created_by              varchar(200)  not null,
    created_at              timestamptz   not null,
    updated_at              timestamptz   not null,
    constraint uq_programme_version unique (code, version),
    constraint ck_programme_status check (status in ('DRAFT','ACTIVE','RETIRED')),
    constraint ck_programme_frequency check (frequency_months between 1 and 120)
);
create index ix_programme_code_status on surveillance.programme (code, status);

-- per-person attributes the HRIS master does not hold (date of birth for age-based frequency, licences)
create table surveillance.employee_profile (
    employee_id    varchar(64)  primary key,
    date_of_birth  date,
    licence_types  varchar(200),
    updated_at     timestamptz  not null
);

-- ---------- enrolments, completions, invitations (E07-F02) ----------
create table surveillance.enrolment (
    id                 uuid primary key,
    employee_id        varchar(64)   not null,
    programme_code     varchar(40)   not null,
    reason             varchar(20)   not null,
    active             boolean       not null default true,
    enrolled_on        date          not null,
    last_completed_on  date,
    last_outcome       varchar(30),
    next_due_on        date          not null,
    exempt_reason      text,                      -- encrypted (OH only)
    exempt_until       date,
    exempt             boolean       not null default false,
    invitations        int           not null default 0,
    last_invited_at    timestamptz,
    escalated_at       timestamptz,
    due_event_for      date,
    updated_at         timestamptz   not null,
    version            bigint        not null default 0,
    constraint uq_enrolment unique (employee_id, programme_code),
    constraint ck_enrolment_reason check (reason in ('JOB_CODE','HAZARD','EXPOSURE_GROUP','LICENCE','MANUAL'))
);
create index ix_enrolment_programme on surveillance.enrolment (programme_code) where active;
create index ix_enrolment_due on surveillance.enrolment (next_due_on) where active;

create table surveillance.completion (
    id            uuid primary key,
    enrolment_id  uuid          not null references surveillance.enrolment(id),
    employee_id   varchar(64)   not null,
    programme_code varchar(40)  not null,
    performed_on  date          not null,
    outcome       varchar(30)   not null,
    encounter_id  uuid,
    result_ids    varchar(2000),
    recorded_by   varchar(200)  not null,
    recorded_at   timestamptz   not null
);
create index ix_completion_programme on surveillance.completion (programme_code, performed_on);

create table surveillance.invitation (
    id                    uuid primary key,
    enrolment_id          uuid          not null references surveillance.enrolment(id),
    employee_id           varchar(64)   not null,
    sequence_no           int           not null,
    sent_at               timestamptz   not null,
    channel               varchar(20)   not null,
    status                varchar(20)   not null,
    appointment_id        uuid,
    appointment_starts_at timestamptz,
    site_code             varchar(40),
    campaign_id           uuid,
    sent_by               varchar(200)  not null,
    updated_at            timestamptz   not null,
    constraint ck_invitation_status check (status in ('INVITED','BOOKED','ATTENDED','NO_SHOW','CANCELLED','ESCALATED'))
);
create index ix_invitation_enrolment on surveillance.invitation (enrolment_id, sent_at desc);
create index ix_invitation_appointment on surveillance.invitation (appointment_id) where appointment_id is not null;

-- ---------- pre-employment (E07-F03) – candidate ids are not HRIS employees ----------
create table surveillance.pre_employment (
    id                        uuid primary key,
    offer_ref                 varchar(60)   not null unique,
    candidate_id              varchar(64)   not null,
    candidate_name            varchar(200)  not null,
    email                     varchar(320),
    job_code                  varchar(64)   not null,
    subsidiary_code           varchar(20),
    department_code           varchar(64),
    location_code             varchar(40),
    proposed_start            date,
    panel                     varchar(1000) not null,
    status                    varchar(30)   not null,
    hr_clearance              varchar(20)   not null,
    outcome                   varchar(30),
    conditions                text,                      -- functional wording (screened), encrypted
    questionnaire             text,                      -- encrypted JSON answers (OH only)
    questionnaire_completed_at timestamptz,
    consent_given             boolean       not null default false,
    consent_purposes          varchar(200),
    consent_at                timestamptz,
    appointment_id            uuid,
    appointment_starts_at     timestamptz,
    appointment_site          varchar(40),
    gov_type                  varchar(20),
    gov_reference             varchar(100),
    gov_status                varchar(20),
    gov_expires_on            date,
    documents                 text,                      -- JSON [{id,title}]
    outcome_document_id       uuid,
    outcome_by                varchar(200),
    outcome_at                timestamptz,
    hr_message_id             varchar(64),
    sla_due_at                timestamptz   not null,
    retention_until           date,
    withdrawn_at              timestamptz,
    purged_at                 timestamptz,
    created_by                varchar(200)  not null,
    created_at                timestamptz   not null,
    updated_at                timestamptz   not null,
    version                   bigint        not null default 0,
    constraint ck_pre_employment_status check (status in ('INVITED','QUESTIONNAIRE_DONE','BOOKED','EXAMINED','OUTCOME_RECORDED','WITHDRAWN')),
    constraint ck_pre_employment_clearance check (hr_clearance in ('PENDING','FIT','UNFIT'))
);
create index ix_pre_employment_candidate on surveillance.pre_employment (candidate_id);
create index ix_pre_employment_status on surveillance.pre_employment (status);

-- ---------- crew medical certificates (E07-F04) ----------
create table surveillance.crew_certificate (
    id                  uuid primary key,
    employee_id         varchar(64)   not null,
    certificate_type    varchar(20)   not null,
    certificate_number  varchar(60)   not null,
    issuing_authority   varchar(100)  not null,
    examined_on         date          not null,
    valid_until         date,
    limitation_codes    varchar(200),
    outcome             varchar(30)   not null,
    suspended           boolean       not null default false,
    source              varchar(20)   not null,
    aemc_name           varchar(200),
    ame_name            varchar(200),
    answers             text,                      -- encrypted JSON (AME only)
    document_id         uuid,
    case_id             uuid,
    alerts_sent         varchar(40),
    expired_published   boolean       not null default false,
    superseded          boolean       not null default false,
    created_by          varchar(200)  not null,
    created_at          timestamptz   not null,
    constraint ck_crew_cert_type check (certificate_type in ('CLASS1','CLASS2','LAPL','CABIN_CREW')),
    constraint ck_crew_cert_source check (source in ('INTERNAL','EXTERNAL_AME'))
);
create index ix_crew_cert_employee on surveillance.crew_certificate (employee_id, examined_on desc);
create index ix_crew_cert_valid on surveillance.crew_certificate (valid_until) where not superseded;

-- ---------- immunisation (E07-F05) ----------
create table surveillance.immunisation_requirement (
    id              uuid primary key,
    job_code        varchar(64),
    hazard_code     varchar(64),
    vaccine_code    varchar(40)   not null,
    vaccine_name    varchar(200)  not null,
    doses           int           not null,
    schedule_months varchar(100),
    booster_months  int,
    titre_required  boolean       not null default false,
    mandatory       boolean       not null default true,
    active          boolean       not null default true,
    updated_by      varchar(200)  not null,
    updated_at      timestamptz   not null,
    constraint ck_imm_req_target check (job_code is not null or hazard_code is not null),
    constraint ck_imm_req_doses check (doses between 1 and 10)
);

create table surveillance.vaccine_administration (
    id                 uuid primary key,
    employee_id        varchar(64)   not null,
    vaccine_code       varchar(40)   not null,
    dose_number        int           not null,
    lot_number         varchar(60)   not null,
    lot_expiry         date,
    body_site          varchar(20)   not null,
    route              varchar(10)   not null,
    campaign_id        uuid,
    administered_at    timestamptz   not null,
    consent_confirmed  boolean       not null,
    record_id          uuid,
    recorded_by        varchar(200)  not null,
    recorded_at        timestamptz   not null
);
create index ix_vacc_admin_employee on surveillance.vaccine_administration (employee_id, vaccine_code);

create table surveillance.titre (
    id           uuid primary key,
    employee_id  varchar(64)    not null,
    antigen      varchar(40)    not null,
    value        numeric(12,3)  not null,
    unit         varchar(30)    not null,
    protective   boolean        not null,
    tested_on    date           not null,
    recorded_by  varchar(200)   not null,
    recorded_at  timestamptz    not null
);
create index ix_titre_employee on surveillance.titre (employee_id, antigen, tested_on desc);

create table surveillance.immunisation_reminder (
    employee_id   varchar(64)  not null,
    vaccine_code  varchar(40)  not null,
    last_sent_on  date         not null,
    primary key (employee_id, vaccine_code)
);

-- ---------- exposure registers & IH linkage (E07-F06) ----------
create table surveillance.exposure_group (
    code           varchar(40)    primary key,
    name           varchar(200)   not null,
    location_code  varchar(40)    not null,
    job_codes      varchar(1000)  not null,
    hazard_code    varchar(64)    not null,
    unit           varchar(30)    not null,
    action_level   numeric(12,3)  not null,
    limit_value    numeric(12,3)  not null,
    active         boolean        not null default true,
    updated_by     varchar(200)   not null,
    updated_at     timestamptz    not null
);

create table surveillance.ih_measurement (
    id                uuid primary key,
    source_ref        varchar(100)   not null unique,
    seg_code          varchar(40)    not null references surveillance.exposure_group(code),
    hazard_code       varchar(64)    not null,
    measurement_type  varchar(30)    not null,
    value             numeric(12,3)  not null,
    unit              varchar(30)    not null,
    measured_on       date           not null,
    imported_by       varchar(200)   not null,
    imported_at       timestamptz    not null,
    constraint ck_ih_type check (measurement_type in ('NOISE_DOSIMETRY','AIR_SAMPLING','BIOLOGICAL','VIBRATION'))
);
create index ix_ih_seg on surveillance.ih_measurement (seg_code, measured_on desc);

create table surveillance.cosmic_dose (
    id           uuid primary key,
    employee_id  varchar(64)    not null,
    dose_year    int            not null,
    dose_msv     numeric(8,3)   not null,
    source       varchar(60)    not null,
    imported_by  varchar(200)   not null,
    imported_at  timestamptz    not null,
    alerted      boolean        not null default false,
    constraint uq_cosmic_dose unique (employee_id, dose_year)
);

create table surveillance.pregnancy_declaration (
    id           uuid primary key,
    employee_id  varchar(64)   not null,
    declared_on  date          not null,
    case_id      uuid,
    created_at   timestamptz   not null
);
create index ix_pregnancy_employee on surveillance.pregnancy_declaration (employee_id);

-- ---------- result evaluation & follow-up (E07-F07), return after illness (E07-BL-020) ----------
create table surveillance.evaluation_rule (
    code                     varchar(40)   primary key,
    test                     varchar(80)   not null,
    rule_type                varchar(20)   not null,
    params                   text          not null,
    follow_up_days           int           not null,
    regulatory_notification  boolean       not null default false,
    active                   boolean       not null default true,
    updated_by               varchar(200)  not null,
    updated_at               timestamptz   not null,
    constraint ck_eval_rule_type check (rule_type in ('STS','SPIRO_DECLINE','BM_LIMIT','VISION','FLAG_ABNORMAL'))
);

create table surveillance.follow_up (
    id                               uuid primary key,
    employee_id                      varchar(64)   not null,
    programme_code                   varchar(40)   not null,
    rule_code                        varchar(40)   not null,
    result_id                        uuid          not null,
    case_id                          uuid          not null,
    case_ref                         varchar(20)   not null,
    recheck_due_on                   date,
    regulatory_notification_required boolean       not null,
    status                           varchar(20)   not null,
    created_at                       timestamptz   not null,
    reviewed_by                      varchar(200),
    reviewed_at                      timestamptz,
    review_decision                  varchar(20),
    review_note                      text,         -- encrypted
    constraint uq_follow_up unique (result_id, rule_code),
    constraint ck_follow_up_status check (status in ('OPEN','REVIEWED','CLOSED'))
);
create index ix_follow_up_status on surveillance.follow_up (status, created_at desc);

create table surveillance.return_after_illness (
    id            uuid primary key,
    employee_id   varchar(64)   not null,
    reason        varchar(30)   not null,
    source        varchar(10)   not null,
    case_id       uuid,
    status        varchar(20)   not null,
    created_by    varchar(200)  not null,
    created_at    timestamptz   not null,
    completed_by  varchar(200),
    completed_at  timestamptz,
    encounter_id  uuid,
    note          text,          -- encrypted
    constraint ck_rai_reason check (reason in ('LONG_TERM_ABSENCE','SURGERY','CARDIAC_EVENT','OTHER_SERIOUS_ILLNESS')),
    constraint ck_rai_status check (status in ('REQUIRED','COMPLETED','WAIVED'))
);
create index ix_rai_employee on surveillance.return_after_illness (employee_id, status);

-- ---------- compliance snapshots & audit packs (E07-F08) ----------
create table surveillance.compliance_snapshot (
    id              uuid primary key,
    snapshot_date   date          not null,
    group_by        varchar(20)   not null,
    programme_code  varchar(40)   not null,   -- '*' = all programmes
    group_key       varchar(64)   not null,
    label           varchar(200)  not null,
    enrolled        int           not null,
    current_count   int           not null,
    due             int           not null,
    overdue         int           not null,
    created_at      timestamptz   not null,
    constraint uq_compliance_snapshot unique (snapshot_date, group_by, programme_code, group_key)
);

create table surveillance.audit_pack (
    id              uuid primary key,
    template        varchar(30)   not null,
    programme_code  varchar(40)   not null,
    from_date       date          not null,
    to_date         date          not null,
    anonymised      boolean       not null,
    pdf             bytea         not null,
    csv             text          not null,
    generated_by    varchar(200)  not null,
    generated_at    timestamptz   not null
);

-- =====================================================================================================
-- seed: the 13 initial programmes (E07-BL-002) – ACTIVE, version 1
-- =====================================================================================================
insert into surveillance.programme (id, code, version, title, status, effective_from, eligibility, panel, frequency_months,
                                    frequency_months_over40, standard_code, responsible_role, service_code, regulatory_basis,
                                    approved_by, approved_at, created_by, created_at, updated_at) values
 ('7e070000-0000-4000-8000-000000000001', 'PILOT_CLASS1', 1, 'Pilot Class 1 medical', 'ACTIVE', date '2026-01-01',
  '{"licenceTypes":["FLIGHT_CREW"]}', '[{"test":"PHYSICAL_EXAM","standard":"CAA_CLASS1"},{"test":"ECG"},{"test":"AUDIOMETRY"},{"test":"VISION"}]',
  12, 6, 'CAA_CLASS1', 'AME', 'CREW_AEROMEDICAL', 'UK CAA Part-MED MED.A.045 – Class 1 validity (6 months over 40 for single-pilot commercial operations)',
  'migration', now(), 'migration', now(), now()),
 ('7e070000-0000-4000-8000-000000000002', 'CABIN_CREW', 1, 'Cabin crew medical assessment', 'ACTIVE', date '2026-01-01',
  '{"licenceTypes":["CABIN_CREW"]}', '[{"test":"PHYSICAL_EXAM","standard":"CABIN_CREW"},{"test":"VISION"}]',
  24, null, 'CABIN_CREW', 'AME', 'CREW_AEROMEDICAL', 'Part-MED Subpart C – cabin crew medical report',
  'migration', now(), 'migration', now(), now()),
 ('7e070000-0000-4000-8000-000000000003', 'FOOD_HANDLER', 1, 'Food handler fitness', 'ACTIVE', date '2026-01-01',
  '{"jobCodes":["CATERING_ASSOC"]}', '[{"test":"QUESTIONNAIRE"},{"test":"IMMUNISATION:TYPHOID"}]',
  12, null, null, 'OH_CLINICIAN', 'FOOD_HANDLER_CLEARANCE', 'Food hygiene regulations – fitness to work as a food handler',
  'migration', now(), 'migration', now(), now()),
 ('7e070000-0000-4000-8000-000000000004', 'HEARING_CONSERVATION', 1, 'Hearing conservation', 'ACTIVE', date '2026-01-01',
  '{"hazardCodes":["NOISE"]}', '[{"test":"AUDIOMETRY"},{"test":"QUESTIONNAIRE"}]',
  12, null, null, 'OH_CLINICIAN', 'AUDIOMETRY', 'Control of Noise at Work Regulations 2005, regulation 9 (health surveillance)',
  'migration', now(), 'migration', now(), now()),
 ('7e070000-0000-4000-8000-000000000005', 'RESPIRATORY', 1, 'Respiratory surveillance', 'ACTIVE', date '2026-01-01',
  '{"hazardCodes":["DUST_RESP","SOLVENT"]}', '[{"test":"SPIROMETRY"},{"test":"QUESTIONNAIRE"}]',
  24, null, null, 'OH_CLINICIAN', 'SPIROMETRY', 'COSHH Regulations 2002, regulation 11 (health surveillance)',
  'migration', now(), 'migration', now(), now()),
 ('7e070000-0000-4000-8000-000000000006', 'HAZMAT_DGR', 1, 'Dangerous goods / hazardous materials', 'ACTIVE', date '2026-01-01',
  '{"hazardCodes":["DGR"]}', '[{"test":"QUESTIONNAIRE"},{"test":"PHYSICAL_EXAM"}]',
  24, null, null, 'OH_CLINICIAN', 'GENERAL_OH', 'ICAO Technical Instructions for dangerous goods; COSHH',
  'migration', now(), 'migration', now(), now()),
 ('7e070000-0000-4000-8000-000000000007', 'AIRSIDE_DRIVER', 1, 'Airside driver medical', 'ACTIVE', date '2026-01-01',
  '{"jobCodes":["RAMP_AGENT"]}', '[{"test":"VISION"},{"test":"QUESTIONNAIRE"}]',
  36, null, 'AIRSIDE_DRIVING', 'OH_CLINICIAN', 'VISION', 'Airside driving permit scheme (CAP 642)',
  'migration', now(), 'migration', now(), now()),
 ('7e070000-0000-4000-8000-000000000008', 'FORKLIFT', 1, 'Forklift operator medical', 'ACTIVE', date '2026-01-01',
  '{"jobCodes":["FORKLIFT_OP"]}', '[{"test":"VISION"},{"test":"QUESTIONNAIRE"}]',
  36, null, null, 'OH_CLINICIAN', 'VISION', 'PUWER 1998 / L117 rider-operated lift trucks',
  'migration', now(), 'migration', now(), now()),
 ('7e070000-0000-4000-8000-000000000009', 'WORKING_AT_HEIGHT', 1, 'Working at height', 'ACTIVE', date '2026-01-01',
  '{"hazardCodes":["HEIGHT"]}', '[{"test":"PHYSICAL_EXAM"},{"test":"VISION"}]',
  36, null, null, 'OH_CLINICIAN', 'GENERAL_OH', 'Work at Height Regulations 2005',
  'migration', now(), 'migration', now(), now()),
 ('7e070000-0000-4000-8000-000000000010', 'NIGHT_WORKER', 1, 'Night worker health assessment', 'ACTIVE', date '2026-01-01',
  '{"hazardCodes":["NIGHT_WORK"]}', '[{"test":"QUESTIONNAIRE"}]',
  12, null, null, 'OH_CLINICIAN', 'NURSE_REVIEW', 'Working Time Regulations 1998, regulation 7',
  'migration', now(), 'migration', now(), now()),
 ('7e070000-0000-4000-8000-000000000011', 'SECURITY_SCREENER', 1, 'Security screener vision', 'ACTIVE', date '2026-01-01',
  '{"jobCodes":["SECURITY_SCREENER"]}', '[{"test":"VISION"}]',
  24, null, null, 'OH_CLINICIAN', 'VISION', 'Aviation security screener standards (acuity and colour vision)',
  'migration', now(), 'migration', now(), now()),
 ('7e070000-0000-4000-8000-000000000012', 'CLINICAL_BBP', 1, 'Blood-borne pathogen protection', 'ACTIVE', date '2026-01-01',
  '{"hazardCodes":["BBP"]}', '[{"test":"IMMUNISATION:HEP_B"},{"test":"LAB:16935-9"},{"test":"QUESTIONNAIRE"}]',
  60, null, null, 'OH_CLINICIAN', 'VACCINATION', 'COSHH biological agents; Green Book chapter 18 (hepatitis B, titre after course)',
  'migration', now(), 'migration', now(), now()),
 ('7e070000-0000-4000-8000-000000000013', 'VDU', 1, 'Display screen equipment eyesight', 'ACTIVE', date '2026-01-01',
  '{"hazardCodes":["VDU"]}', '[{"test":"VISION"}]',
  24, null, null, 'OH_CLINICIAN', 'VISION', 'Health and Safety (Display Screen Equipment) Regulations 1992, regulation 5',
  'migration', now(), 'migration', now(), now());

-- evaluation rules (E07-F07)
insert into surveillance.evaluation_rule (code, test, rule_type, params, follow_up_days, regulatory_notification, active, updated_by, updated_at) values
 ('STS_HEARING',   'AUDIOMETRY',             'STS',           '{"shiftDb":10}',                         30, true,  true, 'migration', now()),
 ('SPIRO_DECLINE', 'SPIROMETRY',             'SPIRO_DECLINE', '{"fev1DropPct":15,"ratioMin":0.70}',     30, false, true, 'migration', now()),
 ('BM_BLOOD_LEAD', 'BIOMONITORING:5671-3',   'BM_LIMIT',      '{"limit":30,"unit":"ug/dL"}',            14, true,  true, 'migration', now()),
 ('VISION_ABN',    'VISION',                 'VISION',        '{}',                                     30, false, true, 'migration', now()),
 ('ECG_ABN',       'ECG',                    'FLAG_ABNORMAL', '{}',                                     14, false, true, 'migration', now()),
 ('ANY_ABNORMAL',  '*',                      'FLAG_ABNORMAL', '{}',                                     30, false, true, 'migration', now());

-- immunisation role matrix (E07-F05)
insert into surveillance.immunisation_requirement (id, job_code, hazard_code, vaccine_code, vaccine_name, doses, schedule_months, booster_months,
                                                   titre_required, mandatory, updated_by, updated_at) values
 ('7e070100-0000-4000-8000-000000000001', 'CATERING_ASSOC',    null, 'TYPHOID',      'Typhoid',         1, '0',     36,   false, true,  'migration', now()),
 ('7e070100-0000-4000-8000-000000000002', 'CATERING_ASSOC',    null, 'HEP_A',        'Hepatitis A',     2, '0,6',   null, false, true,  'migration', now()),
 ('7e070100-0000-4000-8000-000000000003', 'CLINICAL_STAFF',    null, 'HEP_B',        'Hepatitis B',     3, '0,1,6', null, true,  true,  'migration', now()),
 ('7e070100-0000-4000-8000-000000000004', 'CLEANER',           null, 'HEP_B',        'Hepatitis B',     3, '0,1,6', null, true,  true,  'migration', now()),
 ('7e070100-0000-4000-8000-000000000005', 'SECURITY_SCREENER', null, 'HEP_B',        'Hepatitis B',     3, '0,1,6', null, true,  true,  'migration', now()),
 ('7e070100-0000-4000-8000-000000000006', 'CLINICAL_STAFF',    null, 'MMR',          'MMR',             2, '0,1',   null, false, true,  'migration', now()),
 ('7e070100-0000-4000-8000-000000000007', 'CLINICAL_STAFF',    null, 'INFLUENZA',    'Influenza',       1, '0',     12,   false, true,  'migration', now()),
 ('7e070100-0000-4000-8000-000000000008', 'PILOT',             null, 'YELLOW_FEVER', 'Yellow fever',    1, '0',     null, false, true,  'migration', now()),
 ('7e070100-0000-4000-8000-000000000009', 'CABIN_CREW',        null, 'YELLOW_FEVER', 'Yellow fever',    1, '0',     null, false, true,  'migration', now()),
 ('7e070100-0000-4000-8000-000000000010', 'PILOT',             null, 'INFLUENZA',    'Influenza',       1, '0',     12,   false, false, 'migration', now()),
 ('7e070100-0000-4000-8000-000000000011', 'CABIN_CREW',        null, 'INFLUENZA',    'Influenza',       1, '0',     12,   false, false, 'migration', now());

-- similar exposure groups (SEG) for IH linkage (E07-F06)
insert into surveillance.exposure_group (code, name, location_code, job_codes, hazard_code, unit, action_level, limit_value, updated_by, updated_at) values
 ('LHR-RAMP-NOISE',   'LHR ramp – apron noise',              'LHR', 'RAMP_AGENT',            'NOISE',   'dB(A)', 80, 87,  'migration', now()),
 ('LHR-CARGO-NOISE',  'LHR cargo shed – forklift noise',     'LHR', 'FORKLIFT_OP',           'NOISE',   'dB(A)', 80, 87,  'migration', now()),
 ('LHR-ENG-SOLVENT',  'LHR hangar – solvent cleaning',       'LHR', 'ENGINEER,ENG_HEIGHT',   'SOLVENT', 'ppm',   50, 100, 'migration', now()),
 ('LHR-CLEAN-SOLVENT','LHR cabin cleaning – detergents',     'LHR', 'CLEANER',               'SOLVENT', 'ppm',   50, 100, 'migration', now());

insert into masterdata.config_entry (config_key, config_value, description, updated_by, updated_at) values
 ('surveillance.max-invitations',            '2',    'Invitations sent before the line manager is asked to support attendance (E07-F02)', 'migration', now()),
 ('surveillance.cosmic-dose-alert-msv',      '6.0',  'Annual cosmic dose (mSv) above which an AME dose review is raised (E07-F06)', 'migration', now()),
 ('surveillance.new-enrolment-grace-days',   '30',   'Days after enrolment by which the baseline examination is due', 'migration', now()),
 ('surveillance.pre-employment-sla-days',    '10',   'Days to complete a pre-employment assessment after the offer', 'migration', now()),
 ('surveillance.crew.issuing-authority',     'UK CAA', 'Authority named on internally issued crew medical certificates', 'migration', now()),
 ('notifications.crew-licensing.email',      'crew-licensing@wellnessz.local', 'Crew training & licensing distribution list (status only)', 'migration', now()),
 ('notifications.hr-onboarding.email',       'hr-onboarding@wellnessz.local',  'HR onboarding mailbox for pre-employment status (status only)', 'migration', now())
on conflict (config_key) do nothing;

-- runtime role: DML only; clinical tables have no DELETE (corrections are state changes)
grant usage on schema surveillance to ${app_role};
grant select, insert, update on all tables in schema surveillance to ${app_role};
grant delete on surveillance.immunisation_reminder, surveillance.compliance_snapshot to ${app_role};
