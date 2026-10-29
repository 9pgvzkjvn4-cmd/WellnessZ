-- =====================================================================================================
-- V7 – E03 Occupational health record & clinical encounter documentation (schema ohrecord)
--      chart (problems, allergies, medications, immunisations, ODH job history), encounters (templates, body map,
--      signatures, addenda, photos), terminology starter content + favourites, orders / results / devices, CDS.
-- Clinical free text is encrypted at field level by the application (columns typed text); codes stay in clear so that
-- they can be searched and aggregated. The runtime role gets no DELETE on clinical tables (corrections are state changes).
-- =====================================================================================================
create schema if not exists ohrecord;

-- ---------- chart ----------
create table ohrecord.problem (
    id              uuid primary key,
    employee_id     varchar(64)   not null,
    code_system     varchar(20)   not null,
    code            varchar(50)   not null,
    code_display    varchar(500)  not null,
    status          varchar(20)   not null,
    onset_date      date,
    resolved_date   date,
    work_related    varchar(20)   not null,
    case_id         uuid,
    note            text,                       -- encrypted
    classification  varchar(30)   not null,
    recorded_by     varchar(200)  not null,
    recorded_at     timestamptz   not null,
    updated_at      timestamptz   not null
);
create index ix_problem_employee on ohrecord.problem (employee_id, recorded_at desc);

create table ohrecord.allergy (
    id                 uuid primary key,
    employee_id        varchar(64)   not null,
    substance_system   varchar(20),
    substance_code     varchar(50),
    substance_display  varchar(500)  not null,
    category           varchar(20)   not null,
    criticality        varchar(20)   not null,
    reaction           text,                    -- encrypted
    status             varchar(20)   not null,
    recorded_by        varchar(200)  not null,
    recorded_at        timestamptz   not null,
    updated_at         timestamptz   not null
);
create index ix_allergy_employee on ohrecord.allergy (employee_id);

create table ohrecord.medication (
    id              uuid primary key,
    employee_id     varchar(64)   not null,
    name            text          not null,     -- encrypted
    code_system     varchar(20),
    code            varchar(50),
    code_display    varchar(500),
    dose            text,                       -- encrypted
    route           varchar(40),
    frequency       varchar(100),
    source          varchar(20)   not null,
    status          varchar(20)   not null,
    start_date      date,
    end_date        date,
    classification  varchar(30)   not null,
    overrides       text,                       -- encrypted JSON [{alertCode, reason}]
    recorded_by     varchar(200)  not null,
    recorded_at     timestamptz   not null,
    updated_at      timestamptz   not null
);
create index ix_medication_employee on ohrecord.medication (employee_id);

create table ohrecord.immunisation (
    id              uuid primary key,
    employee_id     varchar(64)   not null,
    vaccine         varchar(200)  not null,
    code_system     varchar(20),
    code            varchar(50),
    administered_on date          not null,
    dose_number     int,
    lot_number      varchar(60),
    status          varchar(20)   not null,
    next_due_date   date,
    recorded_by     varchar(200)  not null,
    recorded_at     timestamptz   not null
);
create index ix_immunisation_employee on ohrecord.immunisation (employee_id, administered_on desc);

-- ODH job history: the current job is synchronised from the HRIS master (source HRIS); clinicians add earlier jobs (MANUAL)
create table ohrecord.job_history (
    id            uuid primary key,
    employee_id   varchar(64)   not null,
    job_code      varchar(64),
    job_title     varchar(200)  not null,
    employer      varchar(200),
    industry      varchar(200),
    start_date    date,
    end_date      date,
    is_current    boolean       not null,
    source        varchar(10)   not null,
    hazard_codes  varchar(1000),
    duties        varchar(1000),
    weekly_hours  int,
    recorded_by   varchar(200)  not null,
    recorded_at   timestamptz   not null
);
create index ix_job_history_employee on ohrecord.job_history (employee_id);

-- ---------- encounters ----------
create table ohrecord.encounter (
    id                         uuid primary key,
    employee_id                varchar(64)   not null,
    case_id                    uuid,
    type                       varchar(30)   not null,
    template_version           int           not null,
    status                     varchar(20)   not null,
    classification             varchar(30)   not null,
    title                      varchar(200)  not null,
    author                     varchar(200)  not null,
    created_at                 timestamptz   not null,
    updated_at                 timestamptz   not null,
    answers                    text,         -- encrypted JSON
    soap                       text,         -- encrypted JSON
    fitness_standard_code      varchar(40),
    primary_diagnosis_system   varchar(20),
    primary_diagnosis_code     varchar(50),
    primary_diagnosis_display  varchar(500),
    ai_assisted                boolean       not null default false,
    ai_consent_id              uuid,
    signed_by                  varchar(200),
    signed_at                  timestamptz,
    signature_sha256           varchar(64),
    eie_by                     varchar(200),
    eie_at                     timestamptz,
    eie_reason                 text          -- encrypted
);
create index ix_encounter_employee on ohrecord.encounter (employee_id, created_at desc);
create index ix_encounter_case on ohrecord.encounter (case_id);
create index ix_encounter_unsigned on ohrecord.encounter (created_at) where status = 'DRAFT';

create table ohrecord.encounter_diagnosis (
    id            uuid primary key,
    encounter_id  uuid          not null references ohrecord.encounter (id),
    code_system   varchar(20)   not null,
    code          varchar(50)   not null,
    code_display  varchar(500)  not null,
    is_primary    boolean       not null,
    position      int           not null,
    active        boolean       not null default true   -- superseded rows of a DRAFT edit are deactivated, never deleted
);
create index ix_encounter_diagnosis on ohrecord.encounter_diagnosis (encounter_id);

-- body map entries stored as coded observations (site / laterality / view / mechanism)
create table ohrecord.encounter_observation (
    id              uuid primary key,
    encounter_id    uuid          not null references ohrecord.encounter (id),
    kind            varchar(20)   not null,
    code_system     varchar(30)   not null,
    site_code       varchar(40)   not null,
    laterality      varchar(20),
    body_view       varchar(10),
    mechanism_code  varchar(30),
    position        int           not null,
    active          boolean       not null default true
);
create index ix_encounter_observation on ohrecord.encounter_observation (encounter_id);

create table ohrecord.encounter_addendum (
    id            uuid primary key,
    encounter_id  uuid          not null references ohrecord.encounter (id),
    text          text          not null,  -- encrypted
    author        varchar(200)  not null,
    signed_at     timestamptz   not null,
    sha256        varchar(64)   not null
);
create index ix_encounter_addendum on ohrecord.encounter_addendum (encounter_id);

create table ohrecord.encounter_photo (
    id                uuid primary key,
    encounter_id      uuid          not null references ohrecord.encounter (id),
    document_id       uuid          not null,
    body_region       varchar(40),
    taken_at          timestamptz   not null,
    taken_by          varchar(200)  not null,
    consent_recorded  boolean       not null
);
create index ix_encounter_photo on ohrecord.encounter_photo (encounter_id);

-- template governance: the eight templates ship as versioned JSON resources in DRAFT pending CMO approval (E03-BL-004)
create table ohrecord.encounter_template (
    type         varchar(30)   not null,
    version      int           not null,
    status       varchar(20)   not null,
    approved_by  varchar(200),
    approved_at  timestamptz,
    primary key (type, version)
);
insert into ohrecord.encounter_template (type, version, status) values
    ('INJURY', 1, 'DRAFT'), ('ILLNESS', 1, 'DRAFT'), ('FITNESS_ASSESSMENT', 1, 'DRAFT'), ('SURVEILLANCE_EXAM', 1, 'DRAFT'),
    ('MENTAL_HEALTH', 1, 'DRAFT'), ('TRAVEL', 1, 'DRAFT'), ('CREW_AEROMEDICAL', 1, 'DRAFT'), ('FOOD_HANDLER', 1, 'DRAFT');

-- ---------- orders, results, devices ----------
create table ohrecord.lab_order (
    id                   uuid primary key,
    placer_order_number  varchar(30)   not null unique,
    employee_id          varchar(64)   not null,
    case_id              uuid,
    category             varchar(20)   not null,
    items                text          not null,   -- JSON [{system, code, display}] (codes only)
    order_set_code       varchar(40),
    priority             varchar(10)   not null,
    status               varchar(20)   not null,
    clinical_note        text,                      -- encrypted
    classification       varchar(30)   not null,
    ordered_by           varchar(200)  not null,
    ordered_at           timestamptz   not null,
    hl7_message          text,                      -- encrypted ORM^O01 for the lab gateway pull queue
    sent_at              timestamptz,
    cancelled_by         varchar(200),
    cancelled_at         timestamptz,
    updated_at           timestamptz   not null
);
create index ix_lab_order_employee on ohrecord.lab_order (employee_id, ordered_at desc);
create index ix_lab_order_status on ohrecord.lab_order (status);

create table ohrecord.order_sequence (
    year      int primary key,
    next_val  bigint not null
);

create table ohrecord.result (
    id                  uuid primary key,
    order_id            uuid,
    employee_id         varchar(64)   not null,
    case_id             uuid,
    category            varchar(20)   not null,
    code_system         varchar(20)   not null,
    code                varchar(50)   not null,
    code_display        varchar(500)  not null,
    value               text,                       -- encrypted
    unit                varchar(40),
    reference_range     varchar(60),
    flag                varchar(10)   not null,
    status              varchar(20)   not null,
    effective_at        timestamptz   not null,
    received_at         timestamptz   not null,
    source              varchar(10)   not null,
    device_id           varchar(100),
    device_model        varchar(100),
    calibration_id      varchar(100),
    calibration_date    date,
    measurements        text,                       -- structured device data (JSON thresholds / volumes)
    detail              text,                       -- rule evaluation (JSON, e.g. STS flags)
    baseline            boolean       not null default false,
    classification      varchar(30)   not null,
    acknowledged_by     varchar(200),
    acknowledged_at     timestamptz,
    ack_action          varchar(30),
    ack_note            text,                       -- encrypted
    escalated_at        timestamptz,
    letter_document_id  uuid,
    recorded_by         varchar(200)  not null
);
create index ix_result_employee on ohrecord.result (employee_id, effective_at desc);
create index ix_result_order on ohrecord.result (order_id);
create index ix_result_worklist on ohrecord.result (received_at) where acknowledged_at is null and flag <> 'NORMAL';

-- ---------- clinical decision support (E03-F05) ----------
-- curated starter content; to be replaced / extended with a licensed drug knowledge base after pharmacist review
create table ohrecord.drug_class (
    drug_name   varchar(100)  primary key,   -- lower-case generic or class name
    drug_class  varchar(40)   not null
);
insert into ohrecord.drug_class (drug_name, drug_class) values
    ('penicillin', 'PENICILLIN'), ('penicillins', 'PENICILLIN'), ('amoxicillin', 'PENICILLIN'), ('ampicillin', 'PENICILLIN'),
    ('flucloxacillin', 'PENICILLIN'), ('co-amoxiclav', 'PENICILLIN'), ('phenoxymethylpenicillin', 'PENICILLIN'),
    ('piperacillin', 'PENICILLIN'), ('benzylpenicillin', 'PENICILLIN'),
    ('cefalexin', 'CEPHALOSPORIN'), ('cephalexin', 'CEPHALOSPORIN'), ('cefuroxime', 'CEPHALOSPORIN'), ('ceftriaxone', 'CEPHALOSPORIN'),
    ('cephalosporin', 'CEPHALOSPORIN'), ('cephalosporins', 'CEPHALOSPORIN'),
    ('clarithromycin', 'MACROLIDE'), ('erythromycin', 'MACROLIDE'), ('azithromycin', 'MACROLIDE'),
    ('ciprofloxacin', 'QUINOLONE'), ('levofloxacin', 'QUINOLONE'),
    ('trimethoprim', 'TRIMETHOPRIM'), ('co-trimoxazole', 'SULFONAMIDE'), ('sulfamethoxazole', 'SULFONAMIDE'), ('sulfonamide', 'SULFONAMIDE'),
    ('ibuprofen', 'NSAID'), ('naproxen', 'NSAID'), ('diclofenac', 'NSAID'), ('aspirin', 'NSAID'), ('celecoxib', 'NSAID'),
    ('nsaid', 'NSAID'), ('nsaids', 'NSAID'),
    ('warfarin', 'ANTICOAGULANT'), ('apixaban', 'ANTICOAGULANT'), ('rivaroxaban', 'ANTICOAGULANT'),
    ('sertraline', 'SSRI'), ('fluoxetine', 'SSRI'), ('citalopram', 'SSRI'), ('escitalopram', 'SSRI'),
    ('tramadol', 'OPIOID_SEROTONERGIC'), ('codeine', 'OPIOID'), ('morphine', 'OPIOID'), ('oxycodone', 'OPIOID'),
    ('diazepam', 'BENZODIAZEPINE'), ('lorazepam', 'BENZODIAZEPINE'), ('temazepam', 'BENZODIAZEPINE'),
    ('simvastatin', 'STATIN_CYP3A4'), ('atorvastatin', 'STATIN_CYP3A4'),
    ('methotrexate', 'METHOTREXATE'), ('lithium', 'LITHIUM'),
    ('chlorphenamine', 'SEDATING_ANTIHISTAMINE'), ('promethazine', 'SEDATING_ANTIHISTAMINE'),
    ('latex', 'LATEX'), ('chlorhexidine', 'CHLORHEXIDINE'), ('lidocaine', 'AMIDE_LOCAL_ANAESTHETIC');

create table ohrecord.cds_rule (
    id          varchar(40)   primary key,
    rule_type   varchar(20)   not null,       -- ALLERGY_CROSS (allergy class → drug class) | INTERACTION (drug class ↔ drug class)
    class_a     varchar(40)   not null,
    class_b     varchar(40)   not null,
    severity    varchar(10)   not null,
    message     varchar(300)  not null,
    reference   varchar(300)  not null
);
insert into ohrecord.cds_rule (id, rule_type, class_a, class_b, severity, message, reference) values
    ('X-PEN-CEPH', 'ALLERGY_CROSS', 'PENICILLIN', 'CEPHALOSPORIN', 'WARNING', 'Penicillin allergy recorded – possible cross-reactivity with cephalosporins', 'BNF: penicillin hypersensitivity'),
    ('I-WARF-NSAID', 'INTERACTION', 'ANTICOAGULANT', 'NSAID', 'CRITICAL', 'Anticoagulant with NSAID – increased bleeding risk', 'BNF interactions (draft, pharmacist review pending)'),
    ('I-WARF-MACRO', 'INTERACTION', 'ANTICOAGULANT', 'MACROLIDE', 'WARNING', 'Anticoagulant with macrolide – anticoagulant effect may increase', 'BNF interactions (draft, pharmacist review pending)'),
    ('I-WARF-QUIN', 'INTERACTION', 'ANTICOAGULANT', 'QUINOLONE', 'WARNING', 'Anticoagulant with quinolone – anticoagulant effect may increase', 'BNF interactions (draft, pharmacist review pending)'),
    ('I-SSRI-NSAID', 'INTERACTION', 'SSRI', 'NSAID', 'WARNING', 'SSRI with NSAID – increased risk of gastrointestinal bleeding', 'BNF interactions (draft, pharmacist review pending)'),
    ('I-SSRI-TRAM', 'INTERACTION', 'SSRI', 'OPIOID_SEROTONERGIC', 'CRITICAL', 'SSRI with tramadol – risk of serotonin syndrome and seizures', 'BNF interactions (draft, pharmacist review pending)'),
    ('I-STAT-MACRO', 'INTERACTION', 'STATIN_CYP3A4', 'MACROLIDE', 'CRITICAL', 'Statin with macrolide – increased risk of myopathy', 'BNF interactions (draft, pharmacist review pending)'),
    ('I-OPI-BENZO', 'INTERACTION', 'OPIOID', 'BENZODIAZEPINE', 'CRITICAL', 'Opioid with benzodiazepine – risk of respiratory depression and sedation', 'BNF interactions (draft, pharmacist review pending)'),
    ('I-TRAM-BENZO', 'INTERACTION', 'OPIOID_SEROTONERGIC', 'BENZODIAZEPINE', 'CRITICAL', 'Tramadol with benzodiazepine – risk of respiratory depression and sedation', 'BNF interactions (draft, pharmacist review pending)'),
    ('I-MTX-TRIM', 'INTERACTION', 'METHOTREXATE', 'TRIMETHOPRIM', 'CRITICAL', 'Methotrexate with trimethoprim – risk of bone-marrow toxicity', 'BNF interactions (draft, pharmacist review pending)'),
    ('I-LITH-NSAID', 'INTERACTION', 'LITHIUM', 'NSAID', 'WARNING', 'Lithium with NSAID – lithium levels may rise', 'BNF interactions (draft, pharmacist review pending)'),
    ('I-NSAID-NSAID', 'INTERACTION', 'NSAID', 'NSAID', 'WARNING', 'Two NSAIDs – duplicate therapy, increased gastrointestinal risk', 'BNF interactions (draft, pharmacist review pending)'),
    ('I-SED-BENZO', 'INTERACTION', 'SEDATING_ANTIHISTAMINE', 'BENZODIAZEPINE', 'WARNING', 'Sedating antihistamine with benzodiazepine – additive sedation (safety-critical duties)', 'BNF interactions (draft, pharmacist review pending)');

create table ohrecord.cds_alert_log (
    id               uuid primary key,
    employee_id      varchar(64)   not null,
    context          varchar(20)   not null,  -- MEDICATION | ORDER | ENCOUNTER
    alert_code       varchar(30)   not null,
    severity         varchar(10)   not null,
    overridden       boolean       not null,
    override_reason  text,                     -- encrypted
    username         varchar(200)  not null,
    fired_at         timestamptz   not null
);
create index ix_cds_alert_log_fired on ohrecord.cds_alert_log (fired_at);

-- ---------- terminology (E03-F03) ----------
create table ohrecord.term_favourite (
    id          uuid primary key,
    owner_type  varchar(10)   not null,   -- USER | ROLE
    owner       varchar(200)  not null,
    system      varchar(20)   not null,
    code        varchar(50)   not null,
    created_at  timestamptz   not null,
    unique (owner_type, owner, system, code)
);

create table ohrecord.term_usage (
    username  varchar(200)  not null,
    system    varchar(20)   not null,
    code      varchar(50)   not null,
    used_at   timestamptz   not null,
    primary key (username, system, code)
);
create index ix_term_usage_recent on ohrecord.term_usage (username, used_at desc);

-- local reporting categories (longest code prefix wins); draft pending CMO approval
create table ohrecord.term_mapping (
    system          varchar(20)   not null,
    code_prefix     varchar(50)   not null,
    category        varchar(40)   not null,
    category_label  varchar(200)  not null,
    primary key (system, code_prefix)
);
insert into ohrecord.term_mapping (system, code_prefix, category, category_label) values
    ('ICD_10', 'A', 'INFECTIOUS', 'Infectious disease'),
    ('ICD_10', 'B', 'INFECTIOUS', 'Infectious disease'),
    ('ICD_10', 'A0', 'INFECTIOUS_GI', 'Gastrointestinal infection (food-handler exclusion review)'),
    ('ICD_10', 'A01', 'FOOD_HANDLER_NOTIFIABLE', 'Typhoid / paratyphoid (food-handler exclusion, notifiable)'),
    ('ICD_10', 'A02', 'FOOD_HANDLER_NOTIFIABLE', 'Salmonella infection (food-handler exclusion, notifiable)'),
    ('ICD_10', 'A03', 'FOOD_HANDLER_NOTIFIABLE', 'Shigellosis (food-handler exclusion, notifiable)'),
    ('ICD_10', 'A04.3', 'FOOD_HANDLER_NOTIFIABLE', 'STEC / EHEC infection (food-handler exclusion, notifiable)'),
    ('ICD_10', 'B15', 'FOOD_HANDLER_NOTIFIABLE', 'Hepatitis A (food-handler exclusion, notifiable)'),
    ('ICD_10', 'A15', 'NOTIFIABLE_RESPIRATORY', 'Tuberculosis (notifiable)'),
    ('ICD_10', 'U07', 'INFECTIOUS_RESPIRATORY', 'COVID-19'),
    ('ICD_10', 'S', 'INJURY', 'Injury'),
    ('ICD_10', 'S06', 'HEAD_INJURY', 'Head injury'),
    ('ICD_10', 'T0', 'INJURY', 'Injury'),
    ('ICD_10', 'T1', 'INJURY_EYE_FOREIGN_BODY', 'Foreign body'),
    ('ICD_10', 'T2', 'BURN', 'Burn'),
    ('ICD_10', 'T3', 'BURN', 'Burn'),
    ('ICD_10', 'T5', 'TOXIC_EXPOSURE', 'Toxic effect / chemical exposure'),
    ('ICD_10', 'T6', 'ENVIRONMENTAL', 'Environmental injury (heat, barotrauma, electricity)'),
    ('ICD_10', 'T7', 'ENVIRONMENTAL', 'Environmental injury (heat, barotrauma, electricity)'),
    ('ICD_10', 'T78', 'ALLERGY', 'Allergy / anaphylaxis'),
    ('ICD_10', 'M', 'MSK', 'Musculoskeletal disorder'),
    ('ICD_10', 'G56', 'MSK_UPPER_LIMB', 'Upper-limb disorder (work-related review)'),
    ('ICD_10', 'L', 'SKIN', 'Skin disorder'),
    ('ICD_10', 'L23', 'OCCUPATIONAL_DERMATITIS', 'Contact dermatitis (occupational review)'),
    ('ICD_10', 'L24', 'OCCUPATIONAL_DERMATITIS', 'Contact dermatitis (occupational review)'),
    ('ICD_10', 'L25', 'OCCUPATIONAL_DERMATITIS', 'Contact dermatitis (occupational review)'),
    ('ICD_10', 'J', 'RESPIRATORY', 'Respiratory disorder'),
    ('ICD_10', 'J68', 'OCCUPATIONAL_RESPIRATORY', 'Respiratory condition due to chemicals / fumes'),
    ('ICD_10', 'H', 'EYE_EAR', 'Eye / ear disorder'),
    ('ICD_10', 'H83.3', 'NOISE_INDUCED_HEARING_LOSS', 'Noise-induced hearing loss (hearing conservation)'),
    ('ICD_10', 'H90', 'HEARING', 'Hearing loss'),
    ('ICD_10', 'H91', 'HEARING', 'Hearing loss'),
    ('ICD_10', 'H93', 'HEARING', 'Hearing disorder'),
    ('ICD_10', 'F', 'MENTAL_HEALTH', 'Mental health (restricted class)'),
    ('ICD_10', 'F10', 'SUBSTANCE', 'Substance use (restricted class)'),
    ('ICD_10', 'F12', 'SUBSTANCE', 'Substance use (restricted class)'),
    ('ICD_10', 'Z73', 'WORK_STRESS', 'Work-related stress'),
    ('ICD_10', 'Z56', 'WORK_STRESS', 'Work-related stress'),
    ('ICD_10', 'Z57', 'OCCUPATIONAL_EXPOSURE', 'Occupational exposure'),
    ('ICD_10', 'I', 'CARDIOVASCULAR', 'Cardiovascular'),
    ('ICD_10', 'E', 'METABOLIC', 'Endocrine / metabolic'),
    ('ICD_10', 'G', 'NEUROLOGICAL', 'Neurological'),
    ('ICD_10', 'K', 'GASTROINTESTINAL', 'Gastrointestinal'),
    ('ICD_10', 'N', 'GENITOURINARY', 'Genitourinary'),
    ('ICD_10', 'R', 'SYMPTOM', 'Symptom / sign'),
    ('ICD_10', 'Z', 'HEALTH_SERVICE_CONTACT', 'Health service contact'),
    ('ICD_10', 'Y96', 'WORK_RELATED', 'Work-related condition'),
    ('SNOMED_CT', '44465007', 'INJURY', 'Injury'),
    ('SNOMED_CT', '312608009', 'INJURY', 'Injury'),
    ('SNOMED_CT', '125605004', 'INJURY', 'Injury'),
    ('SNOMED_CT', '125666000', 'BURN', 'Burn'),
    ('SNOMED_CT', '279039007', 'MSK', 'Musculoskeletal disorder'),
    ('SNOMED_CT', '161891005', 'MSK', 'Musculoskeletal disorder'),
    ('SNOMED_CT', '40275004', 'OCCUPATIONAL_DERMATITIS', 'Contact dermatitis (occupational review)'),
    ('SNOMED_CT', '60700002', 'HEARING', 'Hearing loss'),
    ('SNOMED_CT', '15188001', 'HEARING', 'Hearing loss'),
    ('SNOMED_CT', '35489007', 'MENTAL_HEALTH', 'Mental health (restricted class)'),
    ('SNOMED_CT', '197480006', 'MENTAL_HEALTH', 'Mental health (restricted class)'),
    ('SNOMED_CT', '47505003', 'MENTAL_HEALTH', 'Mental health (restricted class)'),
    ('SNOMED_CT', '62315008', 'INFECTIOUS_GI', 'Gastrointestinal infection (food-handler exclusion review)'),
    ('LOINC', '28539-6', 'HEARING_SURVEILLANCE', 'Hearing conservation (audiometry)'),
    ('LOINC', '20150-9', 'RESPIRATORY_SURVEILLANCE', 'Respiratory surveillance (spirometry)'),
    ('LOINC', '19870-5', 'RESPIRATORY_SURVEILLANCE', 'Respiratory surveillance (spirometry)'),
    ('LOINC', '19926-5', 'RESPIRATORY_SURVEILLANCE', 'Respiratory surveillance (spirometry)'),
    ('LOINC', '5643-2', 'SUBSTANCE_TESTING', 'Substance testing (restricted class)'),
    ('LOINC', '19659-3', 'SUBSTANCE_TESTING', 'Substance testing (restricted class)'),
    ('LOINC', '5195-3', 'BLOOD_BORNE_VIRUS', 'Blood-borne virus serology'),
    ('LOINC', '16935-9', 'IMMUNITY', 'Immunity check'),
    ('LOINC', '625-4', 'FOOD_HANDLER_SCREEN', 'Food-handler clearance screen'),
    ('LOINC', '5671-3', 'CHEMICAL_SURVEILLANCE', 'Biological monitoring (metals)');

-- role favourites (specialty starter sets, E03-BL-009); users add their own via the API
insert into ohrecord.term_favourite (id, owner_type, owner, system, code, created_at) values
    ('7e030000-0000-4000-8000-000000000001', 'ROLE', 'OH_CLINICIAN', 'ICD_10', 'S93.4', now()),
    ('7e030000-0000-4000-8000-000000000002', 'ROLE', 'OH_CLINICIAN', 'ICD_10', 'M54.5', now()),
    ('7e030000-0000-4000-8000-000000000003', 'ROLE', 'OH_CLINICIAN', 'ICD_10', 'S61.4', now()),
    ('7e030000-0000-4000-8000-000000000004', 'ROLE', 'OH_CLINICIAN', 'ICD_10', 'T23.2', now()),
    ('7e030000-0000-4000-8000-000000000005', 'ROLE', 'OH_CLINICIAN', 'ICD_10', 'L24.9', now()),
    ('7e030000-0000-4000-8000-000000000006', 'ROLE', 'OH_CLINICIAN', 'ICD_10', 'A09.9', now()),
    ('7e030000-0000-4000-8000-000000000007', 'ROLE', 'OH_PHYSICIAN', 'ICD_10', 'H83.3', now()),
    ('7e030000-0000-4000-8000-000000000008', 'ROLE', 'OH_PHYSICIAN', 'ICD_10', 'J68.0', now()),
    ('7e030000-0000-4000-8000-000000000009', 'ROLE', 'OH_PHYSICIAN', 'ICD_10', 'Z10.0', now()),
    ('7e030000-0000-4000-8000-000000000010', 'ROLE', 'AME', 'ICD_10', 'T70.0', now()),
    ('7e030000-0000-4000-8000-000000000011', 'ROLE', 'AME', 'ICD_10', 'G47.3', now()),
    ('7e030000-0000-4000-8000-000000000012', 'ROLE', 'AME', 'ICD_10', 'Z02.7', now()),
    ('7e030000-0000-4000-8000-000000000013', 'ROLE', 'PSYCH_CLINICIAN', 'ICD_10', 'F43.2', now()),
    ('7e030000-0000-4000-8000-000000000014', 'ROLE', 'PSYCH_CLINICIAN', 'ICD_10', 'F32.9', now()),
    ('7e030000-0000-4000-8000-000000000015', 'ROLE', 'PSYCH_CLINICIAN', 'ICD_10', 'Z73.0', now());

-- ---------- terminology starter content (platform.code_concept) ----------
-- Starter subset for development, demos and tests only. Production loads the licensed national editions (SNOMED CT
-- national release, WHO / national ICD-10 or ICD-11, LOINC) through POST /api/v1/terminology/concepts/import and records
-- each release in platform.terminology_release. Licence terms of each code system apply.
create table platform.terminology_release (
    id             uuid primary key,
    system         varchar(20)   not null,
    version        varchar(60)   not null,
    licence        varchar(300)  not null,
    concept_count  int           not null,
    loaded_by      varchar(200)  not null,
    loaded_at      timestamptz   not null
);
insert into platform.terminology_release (id, system, version, licence, concept_count, loaded_by, loaded_at) values
    ('7e030000-0000-4000-9000-000000000001', 'ICD_10', 'starter-2026.1', 'WHO ICD-10 (starter subset; production requires the national licence)', 0, 'migration', now()),
    ('7e030000-0000-4000-9000-000000000002', 'LOINC', 'starter-2026.1', 'LOINC (Regenstrief licence, free of charge with attribution)', 0, 'migration', now()),
    ('7e030000-0000-4000-9000-000000000003', 'SNOMED_CT', 'starter-2026.1', 'SNOMED CT sample (production requires an affiliate licence / national release centre)', 0, 'migration', now());

create index ix_code_concept_display on platform.code_concept using gin (to_tsvector('simple', display));

insert into platform.code_concept (system, code, display) values
    -- ICD-10: musculoskeletal
    ('ICD_10', 'M54.5', 'Low back pain'),
    ('ICD_10', 'M54.2', 'Cervicalgia'),
    ('ICD_10', 'M75.1', 'Rotator cuff syndrome'),
    ('ICD_10', 'M77.1', 'Lateral epicondylitis'),
    ('ICD_10', 'M65.3', 'Trigger finger'),
    ('ICD_10', 'M25.5', 'Pain in joint'),
    ('ICD_10', 'M79.1', 'Myalgia'),
    ('ICD_10', 'M17.9', 'Gonarthrosis, unspecified'),
    ('ICD_10', 'M51.2', 'Other specified intervertebral disc displacement'),
    ('ICD_10', 'M62.6', 'Muscle strain'),
    ('ICD_10', 'G56.0', 'Carpal tunnel syndrome'),
    -- injuries
    ('ICD_10', 'S83.6', 'Sprain and strain of other and unspecified parts of knee'),
    ('ICD_10', 'S61.0', 'Open wound of finger(s) without damage to nail'),
    ('ICD_10', 'S61.4', 'Open wound of hand'),
    ('ICD_10', 'S60.2', 'Contusion of other parts of wrist and hand'),
    ('ICD_10', 'S52.5', 'Fracture of lower end of radius'),
    ('ICD_10', 'S62.6', 'Fracture of other finger'),
    ('ICD_10', 'S92.3', 'Fracture of metatarsal bone'),
    ('ICD_10', 'S42.0', 'Fracture of clavicle'),
    ('ICD_10', 'S13.4', 'Sprain and strain of cervical spine'),
    ('ICD_10', 'S33.5', 'Sprain and strain of lumbar spine'),
    ('ICD_10', 'S43.4', 'Sprain and strain of shoulder joint'),
    ('ICD_10', 'S63.5', 'Sprain and strain of wrist'),
    ('ICD_10', 'S06.0', 'Concussion'),
    ('ICD_10', 'S05.0', 'Injury of conjunctiva and corneal abrasion without mention of foreign body'),
    ('ICD_10', 'S01.8', 'Open wound of other parts of head'),
    ('ICD_10', 'T15.0', 'Foreign body in cornea'),
    ('ICD_10', 'T15.1', 'Foreign body in conjunctival sac'),
    ('ICD_10', 'T23.2', 'Burn of second degree of wrist and hand'),
    ('ICD_10', 'T30.0', 'Burn of unspecified body region, unspecified degree'),
    ('ICD_10', 'T59.9', 'Toxic effect of gases, fumes and vapours, unspecified'),
    ('ICD_10', 'T67.0', 'Heatstroke and sunstroke'),
    ('ICD_10', 'T67.5', 'Heat exhaustion, unspecified'),
    ('ICD_10', 'T70.0', 'Otitic barotrauma'),
    ('ICD_10', 'T70.1', 'Sinus barotrauma'),
    ('ICD_10', 'T75.4', 'Effects of electric current'),
    ('ICD_10', 'T78.2', 'Anaphylactic shock, unspecified'),
    ('ICD_10', 'T78.4', 'Allergy, unspecified'),
    -- skin
    ('ICD_10', 'L23.9', 'Allergic contact dermatitis, unspecified cause'),
    ('ICD_10', 'L24.9', 'Irritant contact dermatitis, unspecified cause'),
    ('ICD_10', 'L25.9', 'Unspecified contact dermatitis, unspecified cause'),
    ('ICD_10', 'L30.9', 'Dermatitis, unspecified'),
    ('ICD_10', 'L50.0', 'Allergic urticaria'),
    ('ICD_10', 'L55.9', 'Sunburn, unspecified'),
    ('ICD_10', 'L03.1', 'Cellulitis of other parts of limb'),
    ('ICD_10', 'L08.9', 'Local infection of skin and subcutaneous tissue, unspecified'),
    ('ICD_10', 'L01.0', 'Impetigo'),
    -- respiratory
    ('ICD_10', 'J06.9', 'Acute upper respiratory infection, unspecified'),
    ('ICD_10', 'J11.1', 'Influenza with other respiratory manifestations, virus not identified'),
    ('ICD_10', 'J20.9', 'Acute bronchitis, unspecified'),
    ('ICD_10', 'J30.4', 'Allergic rhinitis, unspecified'),
    ('ICD_10', 'J44.9', 'Chronic obstructive pulmonary disease, unspecified'),
    ('ICD_10', 'J45.9', 'Asthma, unspecified'),
    ('ICD_10', 'J68.0', 'Bronchitis and pneumonitis due to chemicals, gases, fumes and vapours'),
    -- ear / eye / hearing
    ('ICD_10', 'H90.3', 'Sensorineural hearing loss, bilateral'),
    ('ICD_10', 'H91.9', 'Hearing loss, unspecified'),
    ('ICD_10', 'H93.1', 'Tinnitus'),
    ('ICD_10', 'H61.2', 'Impacted cerumen'),
    ('ICD_10', 'H66.9', 'Otitis media, unspecified'),
    ('ICD_10', 'H10.9', 'Conjunctivitis, unspecified'),
    ('ICD_10', 'H52.4', 'Presbyopia'),
    ('ICD_10', 'H53.5', 'Colour vision deficiencies'),
    -- mental health (restricted class in reporting)
    ('ICD_10', 'F32.9', 'Depressive episode, unspecified'),
    ('ICD_10', 'F41.1', 'Generalized anxiety disorder'),
    ('ICD_10', 'F41.9', 'Anxiety disorder, unspecified'),
    ('ICD_10', 'F43.0', 'Acute stress reaction'),
    ('ICD_10', 'F43.1', 'Post-traumatic stress disorder'),
    ('ICD_10', 'F43.2', 'Adjustment disorders'),
    ('ICD_10', 'F10.1', 'Mental and behavioural disorders due to use of alcohol: harmful use'),
    ('ICD_10', 'F10.2', 'Mental and behavioural disorders due to use of alcohol: dependence syndrome'),
    ('ICD_10', 'F12.1', 'Mental and behavioural disorders due to use of cannabinoids: harmful use'),
    ('ICD_10', 'F51.0', 'Nonorganic insomnia'),
    ('ICD_10', 'Z73.0', 'Burn-out'),
    ('ICD_10', 'Z56.3', 'Stressful work schedule'),
    ('ICD_10', 'Z56.6', 'Other physical and mental strain related to work'),
    -- infections incl. food-handler relevant
    ('ICD_10', 'A01.0', 'Typhoid fever'),
    ('ICD_10', 'A01.1', 'Paratyphoid fever A'),
    ('ICD_10', 'A02.0', 'Salmonella enteritis'),
    ('ICD_10', 'A03.9', 'Shigellosis, unspecified'),
    ('ICD_10', 'A04.3', 'Enterohaemorrhagic Escherichia coli infection'),
    ('ICD_10', 'A05.9', 'Bacterial foodborne intoxication, unspecified'),
    ('ICD_10', 'A08.1', 'Acute gastroenteropathy due to Norwalk agent'),
    ('ICD_10', 'A08.4', 'Viral intestinal infection, unspecified'),
    ('ICD_10', 'A09.9', 'Gastroenteritis and colitis of unspecified origin'),
    ('ICD_10', 'A15.0', 'Tuberculosis of lung, confirmed by sputum microscopy with or without culture'),
    ('ICD_10', 'B15.9', 'Hepatitis A without hepatic coma'),
    ('ICD_10', 'B16.9', 'Acute hepatitis B without delta-agent and without hepatic coma'),
    ('ICD_10', 'B18.1', 'Chronic viral hepatitis B without delta-agent'),
    ('ICD_10', 'B35.1', 'Tinea unguium'),
    ('ICD_10', 'B86', 'Scabies'),
    ('ICD_10', 'U07.1', 'COVID-19, virus identified'),
    -- other common conditions
    ('ICD_10', 'I10', 'Essential (primary) hypertension'),
    ('ICD_10', 'I25.9', 'Chronic ischaemic heart disease, unspecified'),
    ('ICD_10', 'I48.9', 'Atrial fibrillation and atrial flutter, unspecified'),
    ('ICD_10', 'E10.9', 'Type 1 diabetes mellitus without complications'),
    ('ICD_10', 'E11.9', 'Type 2 diabetes mellitus without complications'),
    ('ICD_10', 'E66.9', 'Obesity, unspecified'),
    ('ICD_10', 'E78.5', 'Hyperlipidaemia, unspecified'),
    ('ICD_10', 'G40.9', 'Epilepsy, unspecified'),
    ('ICD_10', 'G43.9', 'Migraine, unspecified'),
    ('ICD_10', 'G47.3', 'Sleep apnoea'),
    ('ICD_10', 'K21.9', 'Gastro-oesophageal reflux disease without oesophagitis'),
    ('ICD_10', 'K52.9', 'Noninfective gastroenteritis and colitis, unspecified'),
    ('ICD_10', 'N39.0', 'Urinary tract infection, site not specified'),
    ('ICD_10', 'R42', 'Dizziness and giddiness'),
    ('ICD_10', 'R51', 'Headache'),
    ('ICD_10', 'R53', 'Malaise and fatigue'),
    ('ICD_10', 'R55', 'Syncope and collapse'),
    -- factors influencing health status / occupational
    ('ICD_10', 'Z02.7', 'Issue of medical certificate'),
    ('ICD_10', 'Z04.2', 'Examination and observation following work accident'),
    ('ICD_10', 'Z10.0', 'Occupational health examination'),
    ('ICD_10', 'Z20.1', 'Contact with and exposure to tuberculosis'),
    ('ICD_10', 'Z20.5', 'Contact with and exposure to viral hepatitis'),
    ('ICD_10', 'Z20.6', 'Contact with and exposure to human immunodeficiency virus [HIV]'),
    ('ICD_10', 'Z57.0', 'Occupational exposure to noise'),
    ('ICD_10', 'Z57.1', 'Occupational exposure to radiation'),
    ('ICD_10', 'Z57.5', 'Occupational exposure to toxic agents in other industries'),
    ('ICD_10', 'Z88.0', 'Personal history of allergy to penicillin'),
    ('ICD_10', 'Y96', 'Work-related condition'),
    -- LOINC: vitals and anthropometry
    ('LOINC', '8480-6', 'Systolic blood pressure'),
    ('LOINC', '8462-4', 'Diastolic blood pressure'),
    ('LOINC', '8867-4', 'Heart rate'),
    ('LOINC', '9279-1', 'Respiratory rate'),
    ('LOINC', '8310-5', 'Body temperature'),
    ('LOINC', '59408-5', 'Oxygen saturation in Arterial blood by Pulse oximetry'),
    ('LOINC', '29463-7', 'Body weight'),
    ('LOINC', '8302-2', 'Body height'),
    ('LOINC', '39156-5', 'Body mass index (BMI) [Ratio]'),
    -- full blood count
    ('LOINC', '58410-2', 'CBC panel - Blood by Automated count'),
    ('LOINC', '6690-2', 'Leukocytes [#/volume] in Blood by Automated count'),
    ('LOINC', '789-8', 'Erythrocytes [#/volume] in Blood by Automated count'),
    ('LOINC', '4544-3', 'Hematocrit [Volume Fraction] of Blood by Automated count'),
    ('LOINC', '777-3', 'Platelets [#/volume] in Blood by Automated count'),
    -- glucose / lipids / liver / renal / thyroid / electrolytes
    ('LOINC', '2345-7', 'Glucose [Mass/volume] in Serum or Plasma'),
    ('LOINC', '4548-4', 'Hemoglobin A1c/Hemoglobin.total in Blood'),
    ('LOINC', '2093-3', 'Cholesterol [Mass/volume] in Serum or Plasma'),
    ('LOINC', '2085-9', 'Cholesterol in HDL [Mass/volume] in Serum or Plasma'),
    ('LOINC', '13457-7', 'Cholesterol in LDL [Mass/volume] in Serum or Plasma by calculation'),
    ('LOINC', '2571-8', 'Triglyceride [Mass/volume] in Serum or Plasma'),
    ('LOINC', '1742-6', 'Alanine aminotransferase [Enzymatic activity/volume] in Serum or Plasma'),
    ('LOINC', '1920-8', 'Aspartate aminotransferase [Enzymatic activity/volume] in Serum or Plasma'),
    ('LOINC', '2324-2', 'Gamma glutamyl transferase [Enzymatic activity/volume] in Serum or Plasma'),
    ('LOINC', '6768-6', 'Alkaline phosphatase [Enzymatic activity/volume] in Serum or Plasma'),
    ('LOINC', '1975-2', 'Bilirubin.total [Mass/volume] in Serum or Plasma'),
    ('LOINC', '2160-0', 'Creatinine [Mass/volume] in Serum or Plasma'),
    ('LOINC', '3094-0', 'Urea nitrogen [Mass/volume] in Serum or Plasma'),
    ('LOINC', '62238-1', 'Glomerular filtration rate/1.73 sq M.predicted [Volume Rate/Area] in Serum, Plasma or Blood by Creatinine-based formula (CKD-EPI)'),
    ('LOINC', '2951-2', 'Sodium [Moles/volume] in Serum or Plasma'),
    ('LOINC', '2823-3', 'Potassium [Moles/volume] in Serum or Plasma'),
    ('LOINC', '3016-3', 'Thyrotropin [Units/volume] in Serum or Plasma'),
    -- serology, microbiology, toxicology, biological monitoring
    ('LOINC', '5195-3', 'Hepatitis B virus surface Ag [Presence] in Serum'),
    ('LOINC', '16935-9', 'Hepatitis B virus surface Ab [Units/volume] in Serum'),
    ('LOINC', '625-4', 'Bacteria identified in Stool by Culture'),
    ('LOINC', '5643-2', 'Ethanol [Mass/volume] in Serum or Plasma'),
    ('LOINC', '5671-3', 'Lead [Mass/volume] in Blood'),
    ('LOINC', '20563-3', 'Carboxyhemoglobin/Hemoglobin.total in Blood'),
    -- device studies
    ('LOINC', '20150-9', 'FEV1'),
    ('LOINC', '19870-5', 'Forced vital capacity [Volume] Respiratory system by Spirometry'),
    ('LOINC', '19926-5', 'FEV1/FVC'),
    ('LOINC', '11524-6', 'EKG study'),
    -- occupational data for health
    ('LOINC', '11341-5', 'History of Occupation'),
    ('LOINC', '21843-8', 'History of Usual occupation'),
    -- SNOMED CT sample (only concepts verified against the international release)
    ('SNOMED_CT', '44465007', 'Sprain of ankle'),
    ('SNOMED_CT', '279039007', 'Low back pain'),
    ('SNOMED_CT', '161891005', 'Backache'),
    ('SNOMED_CT', '312608009', 'Laceration - injury'),
    ('SNOMED_CT', '125605004', 'Fracture of bone'),
    ('SNOMED_CT', '125666000', 'Burn'),
    ('SNOMED_CT', '40275004', 'Contact dermatitis'),
    ('SNOMED_CT', '24079001', 'Atopic dermatitis'),
    ('SNOMED_CT', '43116000', 'Eczema'),
    ('SNOMED_CT', '128045006', 'Cellulitis'),
    ('SNOMED_CT', '15188001', 'Hearing loss'),
    ('SNOMED_CT', '60700002', 'Sensorineural hearing loss'),
    ('SNOMED_CT', '60862001', 'Tinnitus'),
    ('SNOMED_CT', '195967001', 'Asthma'),
    ('SNOMED_CT', '13645005', 'Chronic obstructive lung disease'),
    ('SNOMED_CT', '54150009', 'Upper respiratory infection'),
    ('SNOMED_CT', '6142004', 'Influenza'),
    ('SNOMED_CT', '840539006', 'Disease caused by severe acute respiratory syndrome coronavirus 2'),
    ('SNOMED_CT', '49727002', 'Cough'),
    ('SNOMED_CT', '267036007', 'Dyspnea'),
    ('SNOMED_CT', '29857009', 'Chest pain'),
    ('SNOMED_CT', '25064002', 'Headache'),
    ('SNOMED_CT', '37796009', 'Migraine'),
    ('SNOMED_CT', '386661006', 'Fever'),
    ('SNOMED_CT', '62315008', 'Diarrhea'),
    ('SNOMED_CT', '422587007', 'Nausea'),
    ('SNOMED_CT', '422400008', 'Vomiting'),
    ('SNOMED_CT', '38341003', 'Hypertensive disorder'),
    ('SNOMED_CT', '22298006', 'Myocardial infarction'),
    ('SNOMED_CT', '49436004', 'Atrial fibrillation'),
    ('SNOMED_CT', '73211009', 'Diabetes mellitus'),
    ('SNOMED_CT', '44054006', 'Diabetes mellitus type 2'),
    ('SNOMED_CT', '46635009', 'Diabetes mellitus type 1'),
    ('SNOMED_CT', '55822004', 'Hyperlipidemia'),
    ('SNOMED_CT', '414916001', 'Obesity'),
    ('SNOMED_CT', '84757009', 'Epilepsy'),
    ('SNOMED_CT', '73430006', 'Sleep apnea'),
    ('SNOMED_CT', '193462001', 'Insomnia'),
    ('SNOMED_CT', '35489007', 'Depressive disorder'),
    ('SNOMED_CT', '197480006', 'Anxiety disorder'),
    ('SNOMED_CT', '47505003', 'Posttraumatic stress disorder'),
    ('SNOMED_CT', '69896004', 'Rheumatoid arthritis'),
    ('SNOMED_CT', '396275006', 'Osteoarthritis'),
    ('SNOMED_CT', '235595009', 'Gastroesophageal reflux disease'),
    ('SNOMED_CT', '271737000', 'Anemia'),
    ('SNOMED_CT', '91936005', 'Allergy to penicillin'),
    ('SNOMED_CT', '300916003', 'Latex allergy'),
    ('SNOMED_CT', '91935009', 'Allergy to peanuts'),
    ('SNOMED_CT', '39579001', 'Anaphylaxis')
on conflict (system, code) do nothing;

update platform.terminology_release r set concept_count = (select count(*) from platform.code_concept c where c.system = r.system)
 where r.loaded_by = 'migration';

-- ---------- least-privilege grants for the runtime role ----------
grant usage on schema ohrecord to ${app_role};
grant select, insert, update on ohrecord.problem, ohrecord.allergy, ohrecord.medication, ohrecord.immunisation,
      ohrecord.job_history, ohrecord.encounter, ohrecord.lab_order, ohrecord.result, ohrecord.order_sequence to ${app_role};
-- coded child rows of a DRAFT encounter are deactivated (not deleted) when the author edits it; locked after signing
grant select, insert, update on ohrecord.encounter_diagnosis, ohrecord.encounter_observation to ${app_role};
grant select, insert on ohrecord.encounter_addendum, ohrecord.encounter_photo, ohrecord.cds_alert_log to ${app_role};
grant select, update on ohrecord.encounter_template to ${app_role};
grant select on ohrecord.drug_class, ohrecord.cds_rule, ohrecord.term_mapping to ${app_role};
grant select, insert, delete on ohrecord.term_favourite to ${app_role};
grant select, insert, update on ohrecord.term_usage to ${app_role};
grant insert, update on platform.code_concept to ${app_role};
grant select, insert on platform.terminology_release to ${app_role};
