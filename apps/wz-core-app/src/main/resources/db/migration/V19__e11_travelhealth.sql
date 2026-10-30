create schema if not exists travelhealth;

-- V19 – E11 Travel health, immunisation campaigns, communicable disease & exposure management (schema travelhealth).
-- Builds on the E07 immunisation registry (administrations are recorded there with the campaign id) and on the kernel
-- (EXPOSURE / ILLNESS cases, case tasks for protocol timers, status-only fitness feeds). Free-text clinical fields are
-- encrypted at field level by the application (columns marked "encrypted"). Lists of codes are CSV; structured lists JSON.

-- ---------- reference numbers (CD / EXP / PHR / VC per year) ----------
create table travelhealth.ref_counter (
    prefix      varchar(10)  not null,
    seq_year    int          not null,
    last_value  bigint       not null,
    primary key (prefix, seq_year)
);

-- ---------- destination risk content (E11-F01, in-house – licensed source decision E11-BL-002 pending) ----------
create table travelhealth.destination (
    country_code          varchar(2)    primary key,
    name                  varchar(100)  not null,
    station_codes         varchar(200),              -- CSV of airport / station codes mapped to the country (roster destinations)
    risks                 text          not null,    -- JSON array of strings
    recommended_vaccines  text          not null,    -- JSON array of vaccine codes (E07 vocabulary)
    prophylaxis           text          not null,    -- JSON array of strings
    advice                text          not null,
    source                varchar(20)   not null,
    reviewed_on           date          not null,
    updated_by            varchar(200)  not null,
    updated_at            timestamptz   not null,
    version               bigint        not null default 0,
    constraint ck_destination_source check (source in ('IN_HOUSE','LICENSED'))
);

create table travelhealth.consultation (
    id                       uuid primary key,
    employee_id              varchar(64)   not null,
    destinations             varchar(200)  not null,  -- CSV of country codes
    depart_date              date          not null,
    return_date              date          not null,
    purpose                  varchar(20)   not null,
    vaccines_given           text,                    -- JSON [{vaccineCode, doseNumber, lotNumber, site, route, administrationId}]
    prophylaxis              text,                    -- encrypted JSON [{drug, regimen, startDate}]
    advice                   text,                    -- encrypted
    recommendations          text,                    -- JSON snapshot of the recommendation engine
    certificate_document_id  uuid,
    consulted_by             varchar(200)  not null,
    created_at               timestamptz   not null,
    constraint ck_consultation_purpose check (purpose in ('LAYOVER','POSTING','BUSINESS')),
    constraint ck_consultation_dates check (return_date >= depart_date)
);
create index ix_consultation_employee on travelhealth.consultation (employee_id, created_at desc);

-- consultation-due reminders sent to rostered crew (at most every 30 days per employee and country)
create table travelhealth.consultation_reminder (
    employee_id   varchar(64)  not null,
    country_code  varchar(2)   not null,
    last_sent_on  date         not null,
    primary key (employee_id, country_code)
);

-- ---------- communicable conditions, cases, contact tracing (E11-F02) ----------
create table travelhealth.condition (
    code                    varchar(40)   primary key,
    name                    varchar(200)  not null,
    notifiable              boolean       not null,
    report_within_hours     int,
    exclusion_days          int,
    food_handler_exclusion  boolean       not null,
    monitoring_days         int           not null,
    return_criteria         varchar(500),             -- CSV of return-criteria codes
    updated_at              timestamptz   not null
);

create table travelhealth.cd_case (
    id                  uuid primary key,
    cd_ref              varchar(30)   not null unique,
    case_id             uuid          not null,
    case_ref            varchar(30)   not null,
    employee_id         varchar(64)   not null,
    condition_code      varchar(40)   not null references travelhealth.condition (code),
    onset_date          date          not null,
    confirmed_on        date,
    confirmed_at        timestamptz,               -- KPI: contact list within 4 h of confirmation
    status              varchar(20)   not null,
    notifiable          boolean       not null,
    report_due_at       timestamptz,
    food_handler        boolean       not null default false,
    exclusion_decision  varchar(20),
    exclusion_from      date,
    exclusion_until     date,
    return_criteria     varchar(500),
    trace_generated_at  timestamptz,               -- KPI: first contact list generated at
    trace_hours         numeric(8,2),              -- KPI: hours from confirmation to the first contact list
    last_traced_at      timestamptz,
    cleared_at          timestamptz,
    criteria_met        varchar(500),
    created_by          varchar(200)  not null,
    created_at          timestamptz   not null,
    updated_at          timestamptz   not null,
    version             bigint        not null default 0,
    constraint ck_cd_status check (status in ('SUSPECTED','CONFIRMED','EXCLUDED','ISOLATING','CLEARED')),
    constraint ck_cd_exclusion check (exclusion_decision is null or exclusion_decision in ('EXCLUDED','ISOLATING','CLEARED'))
);
create index ix_cd_case_employee on travelhealth.cd_case (employee_id);
create index ix_cd_case_status on travelhealth.cd_case (status, created_at desc);

create table travelhealth.contact (
    id                uuid primary key,
    cd_case_id        uuid          not null references travelhealth.cd_case (id),
    employee_id       varchar(64)   not null,
    contact_type      varchar(20)   not null,
    duty_ref          varchar(60),
    overlap_minutes   int           not null,
    risk_level        varchar(10)   not null,
    notified_at       timestamptz,
    monitoring_until  date,
    last_check_in     timestamptz,
    symptomatic       boolean       not null default false,
    created_at        timestamptz   not null,
    updated_at        timestamptz   not null,
    constraint uq_contact_case_employee unique (cd_case_id, employee_id),
    constraint ck_contact_type check (contact_type in ('SAME_DUTY','PROXIMITY_GROUP','FACILITY')),
    constraint ck_contact_risk check (risk_level in ('HIGH','MEDIUM','LOW'))
);
create index ix_contact_employee on travelhealth.contact (employee_id);

create table travelhealth.check_in (
    id             uuid primary key,
    contact_id     uuid          not null references travelhealth.contact (id),
    employee_id    varchar(64)   not null,
    due_on         date          not null,
    status         varchar(10)   not null,
    symptoms       varchar(200),
    temperature_c  numeric(4,1),
    symptomatic    boolean       not null default false,
    submitted_at   timestamptz,
    reminded_at    timestamptz,
    constraint uq_check_in_day unique (contact_id, due_on),
    constraint ck_check_in_status check (status in ('PENDING','DONE','MISSED'))
);
create index ix_check_in_employee on travelhealth.check_in (employee_id, due_on desc);
create index ix_check_in_due on travelhealth.check_in (due_on, status);

-- ---------- exposure pathways with protocol timers (E11-F03) ----------
create table travelhealth.pathway (
    code         varchar(40)    primary key,
    name         varchar(200)   not null,
    description  varchar(1000)  not null,
    timers       text           not null,   -- JSON [{code, label, offsetMinutes}]
    serology     text           not null,   -- JSON [{code, label, offset (ISO-8601 period)}]
    active       boolean        not null default true,
    updated_at   timestamptz    not null
);

create table travelhealth.chemical (
    code        varchar(40)   primary key,
    name        varchar(200)  not null,
    un_number   varchar(10),
    sds_url     varchar(500)  not null,
    hazards     varchar(500)  not null      -- CSV
);

create table travelhealth.exposure (
    id             uuid primary key,
    exp_ref        varchar(30)   not null unique,
    case_id        uuid          not null,
    case_ref       varchar(30)   not null,
    employee_id    varchar(64)   not null,
    pathway        varchar(40)   not null references travelhealth.pathway (code),
    occurred_at    timestamptz   not null,
    details        text,                    -- encrypted
    chemical_code  varchar(40)   references travelhealth.chemical (code),
    flight_ref     varchar(20),
    source_known   boolean,
    incident_id    uuid          unique,    -- idempotency for the EHS incident feed
    created_by     varchar(200)  not null,
    created_at     timestamptz   not null,
    updated_at     timestamptz   not null,
    version        bigint        not null default 0
);
create index ix_exposure_employee on travelhealth.exposure (employee_id);
create index ix_exposure_occurred on travelhealth.exposure (occurred_at desc);

create table travelhealth.exposure_task (
    id                   uuid primary key,
    exposure_id          uuid          not null references travelhealth.exposure (id),
    kind                 varchar(10)   not null,
    code                 varchar(40)   not null,
    label                varchar(200)  not null,
    position             int           not null,
    due_at               timestamptz,               -- timers
    due_on               date,                      -- serology schedule
    status               varchar(10)   not null,
    completed_at         timestamptz,
    completed_by         varchar(200),
    note                 text,                      -- encrypted
    case_task_id         uuid,
    due_soon_alerted_at  timestamptz,
    overdue_alerted_at   timestamptz,
    constraint uq_exposure_task unique (exposure_id, code),
    constraint ck_exposure_task_kind check (kind in ('TIMER','SEROLOGY')),
    constraint ck_exposure_task_status check (status in ('PENDING','DONE'))
);
create index ix_exposure_task_pending on travelhealth.exposure_task (status, due_at) where status = 'PENDING';

-- ---------- vaccination campaigns (E11-F04) ----------
create table travelhealth.campaign (
    id                 uuid primary key,
    campaign_ref       varchar(30)   not null unique,
    name               varchar(200)  not null,
    vaccine_code       varchar(40)   not null,
    eligibility        text          not null,    -- JSON {all, jobCodes[], subsidiaries[]}
    start_date         date          not null,
    end_date           date          not null,
    site_codes         varchar(500),              -- CSV
    target_uptake_pct  numeric(5,1)  not null,
    created_by         varchar(200)  not null,
    created_at         timestamptz   not null,
    updated_at         timestamptz   not null,
    constraint ck_campaign_dates check (end_date >= start_date)
);

create table travelhealth.campaign_invitation (
    id                uuid primary key,
    campaign_id       uuid          not null references travelhealth.campaign (id),
    employee_id       varchar(64)   not null,
    invited_at        timestamptz   not null,
    response          varchar(10),
    responded_at      timestamptz,
    appointment_id    uuid,
    appointment_at    timestamptz,
    appointment_site  varchar(40),
    constraint uq_invitation unique (campaign_id, employee_id),
    constraint ck_invitation_response check (response is null or response in ('ACCEPT','DECLINE'))
);

create table travelhealth.campaign_decline (
    id           uuid primary key,
    campaign_id  uuid          not null references travelhealth.campaign (id),
    employee_id  varchar(64)   not null,
    reason       text,                      -- encrypted
    recorded_by  varchar(200)  not null,
    recorded_at  timestamptz   not null,
    constraint uq_decline unique (campaign_id, employee_id)
);

create table travelhealth.adverse_event (
    id                 uuid primary key,
    campaign_id        uuid          not null references travelhealth.campaign (id),
    employee_id        varchar(64)   not null,
    administration_id  uuid,
    event              varchar(20)   not null,
    severity           varchar(10)   not null,
    onset_at           timestamptz   not null,
    actions            text,                      -- encrypted
    recorded_by        varchar(200)  not null,
    recorded_at        timestamptz   not null,
    constraint ck_ae_event check (event in ('LOCAL_REACTION','FEVER','SYNCOPE','ANAPHYLAXIS','OTHER')),
    constraint ck_ae_severity check (severity in ('MILD','MODERATE','SEVERE'))
);
create index ix_adverse_event_campaign on travelhealth.adverse_event (campaign_id);

-- ---------- health advisories (E11-F05) ----------
create table travelhealth.advisory (
    id                uuid primary key,
    title             varchar(200)   not null,
    destination_code  varchar(40),
    base_code         varchar(40),
    roles             varchar(500),              -- CSV of job codes for base staff
    severity          varchar(10)    not null,
    body              varchar(4000)  not null,
    valid_from        date           not null,
    valid_until       date           not null,
    published_by      varchar(200)   not null,
    published_at      timestamptz    not null,
    constraint ck_advisory_severity check (severity in ('INFO','WARNING','CRITICAL')),
    constraint ck_advisory_target check (destination_code is not null or base_code is not null),
    constraint ck_advisory_dates check (valid_until >= valid_from)
);

create table travelhealth.advisory_target (
    id               uuid primary key,
    advisory_id      uuid          not null references travelhealth.advisory (id),
    employee_id      varchar(64)   not null,
    reason           varchar(20)   not null,
    duty_ref         varchar(60),
    notified_at      timestamptz,
    acknowledged_at  timestamptz,
    constraint uq_advisory_target unique (advisory_id, employee_id),
    constraint ck_advisory_target_reason check (reason in ('ROSTERED','BASE_ROLE'))
);
create index ix_advisory_target_employee on travelhealth.advisory_target (employee_id);

-- ---------- public-health reporting (E11-F06) ----------
create table travelhealth.ph_report (
    id                    uuid primary key,
    report_ref            varchar(30)   not null unique,
    cd_case_id            uuid          references travelhealth.cd_case (id),
    exposure_id           uuid          references travelhealth.exposure (id),
    case_id               uuid,
    employee_id           varchar(64)   not null,
    authority_code        varchar(40)   not null,
    subject               varchar(200)  not null,
    status                varchar(10)   not null,
    due_at                timestamptz   not null,
    document_id           uuid,
    channel               varchar(10),
    submitted_at          timestamptz,
    submission_reference  varchar(100),
    submitted_by          varchar(200),
    due_soon_alerted_at   timestamptz,
    overdue_alerted_at    timestamptz,
    created_by            varchar(200)  not null,
    created_at            timestamptz   not null,
    updated_at            timestamptz   not null,
    constraint ck_ph_subject check ((cd_case_id is null) <> (exposure_id is null)),
    constraint ck_ph_status check (status in ('PENDING','SUBMITTED')),
    constraint ck_ph_channel check (channel is null or channel in ('EMAIL','PORTAL','PHONE'))
);
create index ix_ph_report_status on travelhealth.ph_report (status, due_at);

create table travelhealth.ph_correspondence (
    id           uuid primary key,
    report_id    uuid          not null references travelhealth.ph_report (id),
    direction    varchar(3)    not null,
    summary      text          not null,     -- encrypted
    document_id  uuid,
    recorded_by  varchar(200)  not null,
    recorded_at  timestamptz   not null,
    constraint ck_ph_direction check (direction in ('IN','OUT'))
);
create index ix_ph_correspondence_report on travelhealth.ph_correspondence (report_id, recorded_at);

-- ==================================================================================== seed data

-- destinations (in-house content, reviewed by the OH physician team; vaccine codes from the E07 registry vocabulary)
insert into travelhealth.destination (country_code, name, station_codes, risks, recommended_vaccines, prophylaxis, advice, source, reviewed_on, updated_by, updated_at) values
 ('SG', 'Singapore', 'SIN',
  '["Dengue (mosquito-borne, day-biting)","Heat and humidity","Air quality episodes (seasonal haze)"]',
  '["HEP_A","TYPHOID","TDAP_IPV"]', '[]',
  'Use insect repellent during the day, drink plenty of water and limit strenuous activity outdoors at midday. Food and water hygiene standards are high.',
  'IN_HOUSE', date '2026-09-01', 'migration', now()),
 ('AE', 'United Arab Emirates', 'DXB,AUH,SHJ',
  '["Extreme heat (May to September)","Dehydration","MERS-CoV (avoid contact with camels)"]',
  '["HEP_A","TDAP_IPV"]', '[]',
  'Avoid outdoor exertion in the heat of the day, carry water at all times and avoid contact with camels and unpasteurised camel milk.',
  'IN_HOUSE', date '2026-09-01', 'migration', now()),
 ('IN', 'India', 'DEL,BOM,BLR,MAA',
  '["Food- and water-borne illness","Dengue and chikungunya","Malaria (rural areas)","Rabies (dogs and monkeys)","Air pollution"]',
  '["HEP_A","TYPHOID","TDAP_IPV","HEP_B","RABIES"]', '["Malaria chemoprophylaxis for rural or overland travel – discuss with OH"]',
  'Drink bottled or treated water, eat freshly cooked food, use repellent day and night and avoid contact with animals. Seek care the same day after any animal bite.',
  'IN_HOUSE', date '2026-09-01', 'migration', now()),
 ('NG', 'Nigeria', 'LOS,ABV',
  '["Malaria (high risk, all areas)","Yellow fever","Lassa fever","Food- and water-borne illness","Meningococcal disease (dry season, north)"]',
  '["YELLOW_FEVER","HEP_A","TYPHOID","TDAP_IPV","MENACWY"]', '["Malaria chemoprophylaxis required for all stays including layovers"]',
  'A yellow fever certificate is required. Take malaria tablets exactly as prescribed, sleep under air-conditioning or nets and use repellent from dusk. Report any fever within 3 months of travel immediately.',
  'IN_HOUSE', date '2026-09-01', 'migration', now()),
 ('KE', 'Kenya', 'NBO,MBA',
  '["Malaria (coast and lowlands)","Yellow fever (some areas)","Food- and water-borne illness","Rabies"]',
  '["YELLOW_FEVER","HEP_A","TYPHOID","TDAP_IPV"]', '["Malaria chemoprophylaxis for the coast and lowlands"]',
  'Use repellent and nets, take malaria tablets as prescribed when visiting the coast and avoid contact with animals.',
  'IN_HOUSE', date '2026-09-01', 'migration', now()),
 ('BR', 'Brazil', 'GRU,GIG',
  '["Dengue, Zika and chikungunya","Yellow fever (forested areas)","Food- and water-borne illness"]',
  '["YELLOW_FEVER","HEP_A","TYPHOID","TDAP_IPV"]', '[]',
  'Use repellent during the day, avoid forested areas without yellow fever protection and follow food and water hygiene.',
  'IN_HOUSE', date '2026-09-01', 'migration', now()),
 ('TH', 'Thailand', 'BKK,HKT',
  '["Dengue","Food- and water-borne illness","Rabies (dogs, monkeys)","Heat"]',
  '["HEP_A","TYPHOID","TDAP_IPV"]', '[]',
  'Use repellent, eat freshly cooked food, avoid animals and keep well hydrated.',
  'IN_HOUSE', date '2026-09-01', 'migration', now()),
 ('US', 'United States', 'JFK,LAX,ORD,MIA',
  '["Seasonal influenza","Lyme disease (north-east, outdoor activities)","West Nile virus (summer)"]',
  '["TDAP_IPV","MMR","INFLUENZA"]', '[]',
  'Routine vaccinations should be up to date. Use repellent for outdoor activities in summer.',
  'IN_HOUSE', date '2026-09-01', 'migration', now()),
 ('ZA', 'South Africa', 'JNB,CPT',
  '["Malaria (north-east)","Tuberculosis","Food- and water-borne illness","Rabies"]',
  '["HEP_A","TYPHOID","TDAP_IPV","HEP_B"]', '["Malaria chemoprophylaxis only for north-eastern game areas"]',
  'City layovers carry low infection risk; take malaria precautions for game reserves and avoid contact with animals.',
  'IN_HOUSE', date '2026-09-01', 'migration', now()),
 ('EG', 'Egypt', 'CAI,HRG',
  '["Food- and water-borne illness","Heat","Rabies","Schistosomiasis (fresh water)"]',
  '["HEP_A","TYPHOID","TDAP_IPV"]', '[]',
  'Drink bottled water, avoid swimming in fresh water (the Nile), keep hydrated and avoid animals.',
  'IN_HOUSE', date '2026-09-01', 'migration', now()),
 ('GH', 'Ghana', 'ACC',
  '["Malaria (high risk)","Yellow fever","Meningococcal disease","Food- and water-borne illness"]',
  '["YELLOW_FEVER","HEP_A","TYPHOID","TDAP_IPV","MENACWY"]', '["Malaria chemoprophylaxis required for all stays including layovers"]',
  'A yellow fever certificate is required. Take malaria tablets as prescribed and report any fever after travel immediately.',
  'IN_HOUSE', date '2026-09-01', 'migration', now()),
 ('PE', 'Peru', 'LIM,CUZ',
  '["Altitude illness (Cusco, Andes)","Yellow fever (Amazon)","Food- and water-borne illness","Dengue"]',
  '["HEP_A","TYPHOID","TDAP_IPV","YELLOW_FEVER"]', '[]',
  'Ascend gradually at altitude and rest on arrival in Cusco; yellow fever vaccination is needed only for the Amazon region.',
  'IN_HOUSE', date '2026-09-01', 'migration', now()),
 ('CN', 'China', 'PEK,PVG,CAN',
  '["Air pollution","Food- and water-borne illness","Avian influenza (avoid live-bird markets)","Rabies"]',
  '["HEP_A","TYPHOID","TDAP_IPV"]', '[]',
  'Avoid live-animal and bird markets, follow food and water hygiene and limit outdoor exertion on high-pollution days.',
  'IN_HOUSE', date '2026-09-01', 'migration', now()),
 ('JP', 'Japan', 'NRT,HND,KIX',
  '["Seasonal influenza","Heat (summer)"]',
  '["TDAP_IPV","INFLUENZA"]', '[]',
  'Low infection risk; keep routine vaccinations up to date and stay hydrated in summer.',
  'IN_HOUSE', date '2026-09-01', 'migration', now()),
 ('SA', 'Saudi Arabia', 'JED,RUH,MED',
  '["Meningococcal disease (Hajj and Umrah)","MERS-CoV","Extreme heat"]',
  '["MENACWY","HEP_A","TDAP_IPV","INFLUENZA"]', '[]',
  'Meningococcal ACWY vaccination is required for pilgrimage seasons. Avoid camels, keep hydrated and avoid the midday heat.',
  'IN_HOUSE', date '2026-09-01', 'migration', now())
on conflict (country_code) do nothing;

-- communicable conditions (§5.2); return criteria are codes the clinician confirms at clearance
insert into travelhealth.condition (code, name, notifiable, report_within_hours, exclusion_days, food_handler_exclusion, monitoring_days, return_criteria, updated_at) values
 ('NOROVIRUS',     'Norovirus gastroenteritis',      true,  72,  2,    true,  3,  'SYMPTOM_FREE_48H', now()),
 ('SALMONELLA',    'Salmonellosis',                  true,  72,  2,    true,  5,  'SYMPTOM_FREE_48H,OH_REVIEW', now()),
 ('HEP_A',         'Hepatitis A',                    true,  24,  7,    true,  50, 'ISOLATION_PERIOD_COMPLETE,OH_REVIEW', now()),
 ('INFLUENZA',     'Influenza',                      false, null, 5,   false, 3,  'FEVER_FREE_24H', now()),
 ('COVID19',       'COVID-19',                       false, null, 5,   false, 5,  'FEVER_FREE_24H,SYMPTOMS_IMPROVING', now()),
 ('MEASLES',       'Measles',                        true,  24,  4,    false, 21, 'ISOLATION_PERIOD_COMPLETE', now()),
 ('TB',            'Tuberculosis (pulmonary)',       true,  72,  14,   false, 0,  'TREATMENT_14_DAYS,OH_REVIEW', now()),
 ('CHICKENPOX',    'Chickenpox (varicella)',         false, null, 5,   true,  21, 'LESIONS_CRUSTED', now()),
 ('MENINGOCOCCAL', 'Invasive meningococcal disease', true,  24,  null, false, 7,  'TREATMENT_COMPLETED,OH_REVIEW', now())
on conflict (code) do nothing;

-- exposure pathway templates with protocol timers (offsets from the time of exposure) and serology schedules
insert into travelhealth.pathway (code, name, description, timers, serology, active, updated_at) values
 ('NEEDLESTICK_BBP', 'Needle-stick / body-fluid exposure',
  'Blood-borne pathogen exposure: first aid, risk assessment within 1 hour, post-exposure prophylaxis decision within 2 hours, source testing and follow-up serology.',
  '[{"code":"FIRST_AID","label":"First aid and wound care documented","offsetMinutes":15},{"code":"RISK_ASSESSMENT","label":"Exposure risk assessment","offsetMinutes":60},{"code":"PEP_DECISION","label":"Post-exposure prophylaxis decision","offsetMinutes":120},{"code":"SOURCE_TESTING","label":"Source testing requested (with consent)","offsetMinutes":1440}]',
  '[{"code":"SEROLOGY_6W","label":"Serology at 6 weeks","offset":"P42D"},{"code":"SEROLOGY_3M","label":"Serology at 3 months","offset":"P3M"},{"code":"SEROLOGY_6M","label":"Serology at 6 months","offset":"P6M"}]',
  true, now()),
 ('CHEMICAL_DGR', 'Chemical / dangerous-goods exposure',
  'Chemical or DGR exposure (cargo, engineering, catering): SDS review, decontamination, clinical monitoring and industrial hygiene notification.',
  '[{"code":"SDS_REVIEW","label":"Safety data sheet reviewed","offsetMinutes":15},{"code":"DECONTAMINATION","label":"Decontamination completed","offsetMinutes":30},{"code":"CLINICAL_MONITORING","label":"Clinical monitoring review","offsetMinutes":240},{"code":"IH_NOTIFICATION","label":"Industrial hygiene notified","offsetMinutes":1440}]',
  '[]', true, now()),
 ('CABIN_FUME', 'Cabin air / fume event',
  'Crew fume event: symptom protocol, biomonitoring sample within 6 hours, safety report and authority report task.',
  '[{"code":"SYMPTOM_PROTOCOL","label":"Symptom protocol completed","offsetMinutes":60},{"code":"BIOMONITORING_SAMPLE","label":"Biomonitoring sample taken","offsetMinutes":360},{"code":"SAFETY_REPORT","label":"Safety report linked","offsetMinutes":1440},{"code":"AUTHORITY_REPORT","label":"Authority report task","offsetMinutes":4320}]',
  '[]', true, now()),
 ('HEAT_ILLNESS', 'Heat illness',
  'Heat illness (ramp, cargo): active cooling, return-to-work criteria and anonymised prevention feedback to HSE.',
  '[{"code":"COOLING","label":"Active cooling started","offsetMinutes":15},{"code":"RETURN_CRITERIA","label":"Return-to-work criteria assessed","offsetMinutes":1440},{"code":"HSE_FEEDBACK","label":"Anonymised prevention feedback to HSE","offsetMinutes":4320}]',
  '[]', true, now()),
 ('BITE_STING', 'Bite / sting',
  'Animal or insect bite / sting: first aid, assessment (rabies / tetanus / allergy risk) and follow-up.',
  '[{"code":"FIRST_AID","label":"First aid documented","offsetMinutes":15},{"code":"ASSESSMENT","label":"Rabies, tetanus and allergy risk assessed","offsetMinutes":120},{"code":"FOLLOW_UP","label":"Follow-up review","offsetMinutes":2880}]',
  '[]', true, now())
on conflict (code) do nothing;

-- chemical register (safety data sheets in the document management system)
insert into travelhealth.chemical (code, name, un_number, sds_url, hazards) values
 ('JET_A1',          'Aviation turbine fuel (Jet A-1)',            'UN1863', 'https://sds.wellnessz.local/sds/jet-a1.pdf',             'FLAMMABLE,ASPIRATION,SKIN_IRRITANT'),
 ('HYDRAULIC_FLUID', 'Phosphate-ester hydraulic fluid',            null,     'https://sds.wellnessz.local/sds/hydraulic-fluid.pdf',    'EYE_IRRITANT,SKIN_IRRITANT,INHALATION'),
 ('DEICING_FLUID',   'Aircraft de-icing fluid (propylene glycol)', null,     'https://sds.wellnessz.local/sds/deicing-fluid.pdf',      'EYE_IRRITANT'),
 ('DRY_ICE',         'Carbon dioxide, solid (dry ice)',            'UN1845', 'https://sds.wellnessz.local/sds/dry-ice.pdf',            'COLD_BURN,ASPHYXIANT'),
 ('LITHIUM_BATTERY', 'Lithium-ion batteries',                      'UN3480', 'https://sds.wellnessz.local/sds/lithium-ion.pdf',        'FIRE,TOXIC_FUMES'),
 ('DISINFECTANT_QAC','Quaternary-ammonium cabin disinfectant',      null,     'https://sds.wellnessz.local/sds/cabin-disinfectant.pdf', 'SKIN_IRRITANT,RESPIRATORY_SENSITISER')
on conflict (code) do nothing;

-- configuration
insert into masterdata.config_entry (config_key, config_value, description, updated_by, updated_at) values
 ('travelhealth.tracing.high-risk-minutes', '15', 'Contact tracing: minimum overlap (minutes) in the same proximity group for HIGH risk', 'migration', now()),
 ('travelhealth.tracing.max-window-days', '21', 'Contact tracing: maximum tracing window (days)', 'migration', now()),
 ('travelhealth.tracing.kpi-hours', '4', 'KPI: contact list produced within this many hours of confirmation', 'migration', now()),
 ('travelhealth.check-in.fever-threshold-c', '38.0', 'Contact check-in: temperature (°C) from which a contact counts as symptomatic', 'migration', now()),
 ('travelhealth.exposure.alert-before-minutes', '30', 'Exposure timers: alert this many minutes before a protocol deadline', 'migration', now()),
 ('travelhealth.public-health.alert-before-hours', '12', 'Public-health reports: alert this many hours before the statutory deadline', 'migration', now()),
 ('travelhealth.public-health.exposure-report-hours', '72', 'Public-health reports on exposures: deadline from the time of exposure (hours)', 'migration', now()),
 ('travelhealth.consultation.lookahead-days', '30', 'Travel: roster look-ahead for consultation reminders and the employee travel view (days)', 'migration', now())
on conflict (config_key) do nothing;

-- ==================================================================================== grants
grant usage on schema travelhealth to ${app_role};
grant select, insert, update on travelhealth.ref_counter, travelhealth.destination, travelhealth.consultation, travelhealth.consultation_reminder,
      travelhealth.condition,
      travelhealth.cd_case, travelhealth.contact, travelhealth.check_in, travelhealth.pathway, travelhealth.chemical, travelhealth.exposure,
      travelhealth.exposure_task, travelhealth.campaign, travelhealth.campaign_invitation, travelhealth.campaign_decline,
      travelhealth.adverse_event, travelhealth.advisory, travelhealth.advisory_target, travelhealth.ph_report to ${app_role};
grant select, insert on travelhealth.ph_correspondence to ${app_role};
