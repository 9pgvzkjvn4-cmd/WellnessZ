create schema if not exists substance;

-- V18 – E10 Drug & alcohol / psychoactive substance testing programme (schema substance, restricted class).
-- Laboratory results, breath readings, MRO interview notes, reasonable-cause descriptions, refusal observations and
-- evaluation notes are encrypted at field level by the application (columns marked "encrypted"). Chain-of-custody
-- events are append-only (no update / delete grant, and a trigger rejects changes even for the owner).

-- ---------- reference numbers (DAT / RSL / RTD per year) ----------
create table substance.ref_counter (
    prefix      varchar(10)  not null,
    seq_year    int          not null,
    last_value  bigint       not null,
    primary key (prefix, seq_year)
);

-- ---------- test panels ----------
create table substance.panel (
    code            varchar(20)  primary key,
    name            varchar(200) not null,
    test_type       varchar(10)  not null,
    specimen_types  varchar(100) not null,
    analytes        text         not null,      -- JSON [{analyte, cutoff, unit}]
    constraint ck_panel_type check (test_type in ('DRUGS','ALCOHOL'))
);

insert into substance.panel (code, name, test_type, specimen_types, analytes) values
    ('DRUGS_5', '5-panel drug screen (urine)', 'DRUGS', 'URINE,ORAL_FLUID',
     '[{"analyte":"AMPHETAMINES","cutoff":"500","unit":"ng/mL"},{"analyte":"COCAINE_METABOLITES","cutoff":"150","unit":"ng/mL"},{"analyte":"CANNABINOIDS","cutoff":"50","unit":"ng/mL"},{"analyte":"OPIATES","cutoff":"2000","unit":"ng/mL"},{"analyte":"PHENCYCLIDINE","cutoff":"25","unit":"ng/mL"}]'),
    ('DRUGS_10', '10-panel drug screen (urine)', 'DRUGS', 'URINE,ORAL_FLUID',
     '[{"analyte":"AMPHETAMINES","cutoff":"500","unit":"ng/mL"},{"analyte":"COCAINE_METABOLITES","cutoff":"150","unit":"ng/mL"},{"analyte":"CANNABINOIDS","cutoff":"50","unit":"ng/mL"},{"analyte":"OPIATES","cutoff":"2000","unit":"ng/mL"},{"analyte":"PHENCYCLIDINE","cutoff":"25","unit":"ng/mL"},{"analyte":"BENZODIAZEPINES","cutoff":"300","unit":"ng/mL"},{"analyte":"BARBITURATES","cutoff":"300","unit":"ng/mL"},{"analyte":"METHADONE","cutoff":"300","unit":"ng/mL"},{"analyte":"PROPOXYPHENE","cutoff":"300","unit":"ng/mL"},{"analyte":"MDMA","cutoff":"500","unit":"ng/mL"}]'),
    ('ALCOHOL_BREATH', 'Evidential breath alcohol', 'ALCOHOL', 'BREATH',
     '[{"analyte":"ETHANOL_BREATH","cutoff":"0.02","unit":"g/210L"}]');

-- ---------- testing policies by role group (E10-F01; approval E10-BL-010) ----------
create table substance.policy (
    role_group               varchar(30)   primary key,
    job_codes                text          not null,   -- comma list of HRIS job codes
    panels                   text          not null,   -- comma list of panel codes
    random_drug_rate_pct     numeric(5,2)  not null,
    random_alcohol_rate_pct  numeric(5,2)  not null,
    alcohol_window_hours     int           not null default 8,
    drug_window_hours        int           not null default 32,
    lead_time_minutes        int           not null default 120,
    jurisdiction             varchar(120)  not null,
    consent_required         boolean       not null default true,
    approval_status          varchar(10)   not null default 'PENDING',
    approved_by              varchar(200),
    approved_at              timestamptz,
    updated_by               varchar(200)  not null,
    updated_at               timestamptz   not null,
    version                  bigint        not null default 1,
    constraint ck_policy_group check (role_group in ('FLIGHT_CREW','CABIN_CREW','AIRSIDE_DRIVER','ENGINEER','FORKLIFT','SECURITY')),
    constraint ck_policy_approval check (approval_status in ('PENDING','APPROVED')),
    constraint ck_policy_rates check (random_drug_rate_pct between 0 and 100 and random_alcohol_rate_pct between 0 and 100),
    constraint ck_policy_windows check (alcohol_window_hours between 1 and 24 and drug_window_hours between 1 and 72 and lead_time_minutes between 0 and 1440)
);

-- seeded pending Legal / Safety / HR approval (E10-BL-010); rates follow the regulator models (EU 2018/1042 / FAA Part 120)
insert into substance.policy (role_group, job_codes, panels, random_drug_rate_pct, random_alcohol_rate_pct, alcohol_window_hours,
                              drug_window_hours, lead_time_minutes, jurisdiction, consent_required, approval_status, updated_by, updated_at) values
    ('FLIGHT_CREW',    'PILOT',              'DRUGS_10,ALCOHOL_BREATH', 25.00, 10.00, 8, 32, 120, 'EASA CAT.GEN.MPA.170 / UK CAA', true, 'PENDING', 'migration', now()),
    ('CABIN_CREW',     'CABIN_CREW',         'DRUGS_10,ALCOHOL_BREATH', 25.00, 10.00, 8, 32, 120, 'EASA CAT.GEN.MPA.170 / UK CAA', true, 'PENDING', 'migration', now()),
    ('AIRSIDE_DRIVER', 'RAMP_AGENT',         'DRUGS_5,ALCOHOL_BREATH',  10.00, 10.00, 8, 32, 120, 'Airport airside rules / local OH&S', true, 'PENDING', 'migration', now()),
    ('ENGINEER',       'ENGINEER,ENG_HEIGHT','DRUGS_10,ALCOHOL_BREATH', 25.00, 10.00, 8, 32, 120, 'Part-145 maintenance / local OH&S', true, 'PENDING', 'migration', now()),
    ('FORKLIFT',       'FORKLIFT_OP',        'DRUGS_5,ALCOHOL_BREATH',  10.00, 10.00, 8, 32, 120, 'Local OH&S (lifting equipment)', true, 'PENDING', 'migration', now()),
    ('SECURITY',       'SECURITY_SCREENER',  'DRUGS_5,ALCOHOL_BREATH',  10.00, 10.00, 8, 32, 120, 'Aviation security regulation / local OH&S', true, 'PENDING', 'migration', now());

-- ---------- pool exclusions ----------
create table substance.pool_exclusion (
    id           uuid primary key,
    role_group   varchar(30)  not null,
    employee_id  varchar(64)  not null,
    reason       varchar(30)  not null,
    until        date         not null,
    created_by   varchar(200) not null,
    created_at   timestamptz  not null,
    constraint ck_exclusion_reason check (reason in ('LEAVE','LONG_TERM_ABSENCE','NOT_SAFETY_SENSITIVE'))
);
create index ix_exclusion_group on substance.pool_exclusion (role_group, until);

-- ---------- random selections (auditable: seed commitment + pool snapshot) ----------
create table substance.selection (
    id               uuid primary key,
    ref              varchar(30)   not null unique,
    role_group       varchar(30)   not null,
    period           varchar(7)    not null,
    test_type        varchar(10)   not null,
    rate_pct         numeric(5,2)  not null,
    pool_size        int           not null,
    pool_hash        varchar(64)   not null,
    pool_snapshot    text          not null,   -- JSON array of employee ids (sorted) – reproduces the draw
    seed_hex         varchar(128)  not null,
    seed_commitment  varchar(64)   not null,   -- SHA-256 of the seed, shown at creation
    selected_count   int           not null,
    selected         text          not null,   -- JSON array of employee ids in draw order
    policy_version   bigint        not null,
    policy_approval  varchar(10)   not null,
    created_by       varchar(200)  not null,
    created_at       timestamptz   not null,
    notified_at      timestamptz,
    notified_by      varchar(200),
    constraint uq_selection_period unique (role_group, period, test_type),
    constraint ck_selection_type check (test_type in ('DRUGS','ALCOHOL'))
);

-- ---------- test requirements ----------
create table substance.test (
    id                     uuid primary key,
    ref                    varchar(30)   not null unique,
    employee_id            varchar(64)   not null,
    role_group             varchar(30),
    reason                 varchar(20)   not null,
    panels                 varchar(200)  not null,
    status                 varchar(20)   not null,
    source_ref             varchar(200)  not null unique,
    selection_id           uuid references substance.selection(id),
    incident_ref           varchar(100),
    rtd_plan_id            uuid,
    scheduled_for          date,
    due_at                 timestamptz,
    alcohol_due_at         timestamptz,
    drug_due_at            timestamptz,
    notified_at            timestamptz,
    dispatched_at          timestamptz,
    collected_at           timestamptz,
    alcohol_collected_at   timestamptz,
    site_code              varchar(40),
    observed_at            timestamptz,
    observations           varchar(300),
    description            text,                   -- encrypted
    second_observer_name   varchar(200),
    location               varchar(200),
    requested_by           varchar(200),
    refusal_reason         varchar(30),
    refusal_observations   text,                   -- encrypted
    lab_ref                varchar(100),
    lab_validity           varchar(20),
    lab_received_at        timestamptz,
    results                text,                   -- encrypted JSON
    breath_result          text,                   -- encrypted
    split_results          text,                   -- encrypted JSON
    split_requested_at     timestamptz,
    verdict                varchar(40),
    prescription_verified  boolean,
    interview_note         text,                   -- encrypted
    mro_username           varchar(200),
    mro_decided_at         timestamptz,
    case_id                uuid,
    support_case_id        uuid,
    created_by             varchar(200)  not null,
    created_at             timestamptz   not null,
    updated_at             timestamptz   not null,
    version                bigint        not null default 0,
    constraint ck_test_reason check (reason in ('PRE_EMPLOYMENT','RANDOM','POST_INCIDENT','FOR_CAUSE','RETURN_TO_DUTY','FOLLOW_UP')),
    constraint ck_test_status check (status in ('REQUIRED','NOTIFIED','DISPATCHED','COLLECTED','IN_LAB','MRO_REVIEW','VERIFIED_NEGATIVE',
                                                'VERIFIED_POSITIVE','REFUSAL','CANCELLED','WINDOW_MISSED'))
);
create index ix_test_status on substance.test (status, due_at);
create index ix_test_employee on substance.test (employee_id, created_at);
create index ix_test_selection on substance.test (selection_id);
create index ix_test_plan on substance.test (rtd_plan_id);

-- ---------- specimens & chain of custody ----------
create table substance.specimen (
    id                       uuid primary key,
    specimen_id              varchar(40)  not null unique,
    test_id                  uuid         not null references substance.test(id),
    specimen_type            varchar(20)  not null,
    kind                     varchar(10)  not null default 'PRIMARY',
    parent_specimen_id       varchar(40),
    collected_at             timestamptz  not null,
    site_code                varchar(40)  not null,
    temperature_in_range     boolean,
    seal_intact              boolean      not null,
    donor_identity_checked   boolean      not null,
    split_specimen           boolean      not null,
    collector                varchar(200) not null,
    remarks                  text,                    -- encrypted
    lab_code                 varchar(40),
    out_of_window            boolean      not null default false,
    document_id              uuid,
    lab_received_at          timestamptz,
    resulted_at              timestamptz,
    created_at               timestamptz  not null,
    constraint ck_specimen_type check (specimen_type in ('URINE','ORAL_FLUID','BREATH')),
    constraint ck_specimen_kind check (kind in ('PRIMARY','SPLIT'))
);
create index ix_specimen_test on substance.specimen (test_id);

create table substance.custody_event (
    id            bigserial primary key,
    specimen_id   varchar(40)  not null,
    event         varchar(20)  not null,
    event_at      timestamptz  not null,
    by_name       varchar(200) not null,
    signer        varchar(200) not null,
    signer_role   varchar(40)  not null,
    recorded_at   timestamptz  not null,
    constraint ck_custody_event check (event in ('COLLECTED','SEALED','COURIER_PICKUP','LAB_RECEIVED','SPLIT_ORDERED'))
);
create index ix_custody_specimen on substance.custody_event (specimen_id, id);

create function substance.custody_append_only() returns trigger language plpgsql as $$
begin
    raise exception 'chain-of-custody events are append-only';
end;
$$;
create trigger custody_event_append_only before update or delete on substance.custody_event
    for each row execute function substance.custody_append_only();

-- ---------- duty status (status only – shared with HR / crew scheduling) ----------
create table substance.duty_status (
    employee_id  varchar(64)  primary key,
    status       varchar(20)  not null,
    since        timestamptz  not null,
    case_id      uuid,
    updated_by   varchar(200) not null,
    updated_at   timestamptz  not null,
    constraint ck_duty_status check (status in ('AVAILABLE','REMOVED','RETURN_PENDING'))
);

-- ---------- return-to-duty plans ----------
create table substance.rtd_plan (
    id                  uuid primary key,
    ref                 varchar(30)   not null unique,
    employee_id         varchar(64)   not null,
    case_id             uuid,
    test_id             uuid,
    status              varchar(20)   not null,
    evaluator           varchar(200),
    treatment_provider  text,                    -- encrypted
    follow_up_tests     int           not null default 0,
    follow_up_months    int           not null default 0,
    start_date          date,
    end_date            date,
    evaluation_outcome  varchar(20),
    evaluation_note     text,                    -- encrypted
    evaluated_by        varchar(200),
    evaluated_at        timestamptz,
    rtd_test_id         uuid,
    returned_at         timestamptz,
    completed_at        timestamptz,
    created_by          varchar(200)  not null,
    created_at          timestamptz   not null,
    updated_at          timestamptz   not null,
    version             bigint        not null default 0,
    constraint ck_rtd_status check (status in ('DRAFT','ACTIVE','RTD_PENDING','MONITORING','COMPLETED','CANCELLED'))
);
create index ix_rtd_employee on substance.rtd_plan (employee_id);

-- ---------- post-incident rule table (incident class / severity) ----------
create table substance.post_incident_rule (
    id              bigserial primary key,
    name            varchar(200) not null,
    priority        int          not null,
    active          boolean      not null default true,
    incident_class  varchar(40),
    min_severity    varchar(20),
    action          varchar(10)  not null,
    updated_by      varchar(200) not null,
    updated_at      timestamptz  not null,
    constraint ck_pi_action check (action in ('TEST','IGNORE'))
);

insert into substance.post_incident_rule (name, priority, active, incident_class, min_severity, action, updated_by, updated_at) values
    ('Near miss – no test',                          5, true, 'NEAR_MISS',                null,       'IGNORE', 'migration', now()),
    ('Lost-time injury',                            10, true, 'LOST_TIME_INJURY',         null,       'TEST',   'migration', now()),
    ('Injury – medical treatment',                  20, true, 'INJURY_MEDICAL_TREATMENT', null,       'TEST',   'migration', now()),
    ('Vehicle / GSE collision',                     30, true, 'VEHICLE_COLLISION',        null,       'TEST',   'migration', now()),
    ('Aircraft damage',                             31, true, 'AIRCRAFT_DAMAGE',          null,       'TEST',   'migration', now()),
    ('Property damage – high severity',             40, true, 'PROPERTY_DAMAGE',          'HIGH',     'TEST',   'migration', now()),
    ('Any incident – high severity',                90, true, null,                       'HIGH',     'TEST',   'migration', now());

-- ---------- configuration ----------
insert into masterdata.config_entry (config_key, config_value, description, updated_by, updated_at) values
    ('modules.e10.enabled', 'true', 'Drug & alcohol / psychoactive substance testing programme', 'migration', now()),
    ('substance.laboratory-code', 'TOXLAB', 'Partner organisation code of the accredited toxicology laboratory (lab orders / results)', 'migration', now()),
    ('substance.collection-site', 'LHR-OH-CLINIC', 'Default specimen collection site shown on notices', 'migration', now()),
    ('substance.hr-status-email', 'hr-status@wellnessz.local', 'HR mailbox for duty-status notices (status only)', 'migration', now()),
    ('substance.breath-alcohol-cutoff', '0.02', 'Breath alcohol confirmation cut-off (g/210L) shown to the MRO', 'migration', now()),
    ('substance.acting-mro-physicians', '', 'Comma list of OH physician usernames designated to act as MRO (results access)', 'migration', now())
on conflict (config_key) do nothing;

grant usage on schema substance to ${app_role};
grant select, insert, update on substance.ref_counter, substance.policy, substance.selection, substance.test, substance.specimen,
      substance.duty_status, substance.rtd_plan, substance.post_incident_rule to ${app_role};
grant select, insert, delete on substance.pool_exclusion to ${app_role};
grant select on substance.panel to ${app_role};
grant select, insert on substance.custody_event to ${app_role};
grant usage, select on sequence substance.custody_event_id_seq, substance.post_incident_rule_id_seq to ${app_role};
