create schema if not exists claims;

-- V15 – E08 Workers' compensation, insurance & claims coordination (schema claims).
-- Claims never hold the OH clinical record: claim notes, first-report fields, secure messages and medico-legal questions
-- are encrypted at field level by the application (columns marked "encrypted"); free text shown to adjusters, HR or
-- finance is screened for clinical content before it is stored. Money is numeric(14,2) with an ISO 4217 currency.

-- ---------- reference numbers (CLM / FROI / LEG per year) ----------
create table claims.ref_counter (
    prefix      varchar(10)  not null,
    seq_year    int          not null,
    last_value  bigint       not null,
    primary key (prefix, seq_year)
);

-- ---------- claim record & lifecycle (E08-F01) ----------
create table claims.claim (
    id                 uuid primary key,
    claim_ref          varchar(30)   not null unique,
    case_id            uuid          not null,
    case_ref           varchar(30)   not null,
    employee_id        varchar(64)   not null,
    claim_type         varchar(30)   not null,
    status             varchar(20)   not null,
    payer_code         varchar(40)   not null,
    policy_number      varchar(100),
    adjuster_name      varchar(200),
    adjuster_email     varchar(320),
    adjuster_username  varchar(200),
    lost_time          boolean       not null default false,
    lost_time_at       timestamptz,
    incident_date      date,
    notes              text,                      -- encrypted (screened)
    notified_at        timestamptz   not null,
    opened_at          timestamptz,
    settled_at         timestamptz,
    closed_at          timestamptz,
    nurse_username     varchar(200),
    nurse_assigned_at  timestamptz,
    reserves           text,                      -- JSON {CATEGORY: {amount, currency}}
    paid_total         numeric(14,2),
    paid_currency      varchar(3),
    subrogation        text,                      -- JSON, screened free text
    litigation_hold    boolean       not null default false,
    hold_reason        varchar(1000),
    hold_set_by        varchar(200),
    hold_set_at        timestamptz,
    privileged         boolean       not null default false,
    privilege_basis    varchar(1000),
    privilege_set_by   varchar(200),
    privilege_set_at   timestamptz,
    created_by         varchar(200)  not null,
    created_at         timestamptz   not null,
    updated_at         timestamptz   not null,
    version            bigint        not null default 0,
    constraint uq_claim_case_type unique (case_id, claim_type),
    constraint ck_claim_type check (claim_type in ('WORKERS_COMP','EMPLOYER_LIABILITY','GROUP_MEDICAL','THIRD_PARTY')),
    constraint ck_claim_status check (status in ('NOTIFIED','OPEN','UNDER_REVIEW','SETTLED','CLOSED','LITIGATED'))
);
create index ix_claim_case on claims.claim (case_id);
create index ix_claim_employee on claims.claim (employee_id);
create index ix_claim_payer on claims.claim (payer_code, status);
create index ix_claim_status on claims.claim (status);

create table claims.claim_history (
    id           uuid primary key,
    claim_id     uuid          not null references claims.claim (id),
    event_type   varchar(40)   not null,
    from_status  varchar(20),
    to_status    varchar(20),
    reason       varchar(1000),
    actor        varchar(200)  not null,
    occurred_at  timestamptz   not null
);
create index ix_claim_history_claim on claims.claim_history (claim_id, occurred_at);

-- ---------- statutory & insurer e-reporting (E08-F02) ----------
create table claims.report_form (
    code                      varchar(40)   primary key,
    jurisdiction              varchar(10)   not null,
    recipient_type            varchar(20)   not null,       -- AUTHORITY | INSURER
    recipient_code            varchar(40),                  -- null = the claim's payer
    title                     varchar(200)  not null,
    deadline_hours            int           not null,
    specified_deadline_hours  int,                          -- shorter deadline for specified injuries / fatalities
    deadline_anchor           varchar(20)   not null,       -- INCIDENT | NOTIFIED
    trigger_rule              varchar(30)   not null,       -- ANY_CLAIM | LOST_TIME_OVER_7_DAYS | SPECIFIED_INJURY | FATALITY
    fields                    text          not null,       -- JSON [{code, label, source}]
    sign_off_status           varchar(40)   not null,
    active                    boolean       not null default true,
    constraint ck_report_form_recipient check (recipient_type in ('AUTHORITY','INSURER'))
);

create table claims.first_report (
    id                uuid primary key,
    report_ref        varchar(30)   not null unique,
    claim_id          uuid          not null references claims.claim (id),
    form_code         varchar(40)   not null references claims.report_form (code),
    recipient_type    varchar(20)   not null,
    recipient_code    varchar(40)   not null,
    status            varchar(20)   not null,
    due_at            timestamptz   not null,
    specified_injury  boolean       not null default false,
    fields            text,                       -- encrypted JSON (facts of the event, minimum injury information)
    channel           varchar(20),
    document_id       uuid,
    message_id        varchar(64),
    submitted_at      timestamptz,
    submitted_by      varchar(200),
    ack_ref           varchar(100),
    acknowledged_at   timestamptz,
    ack_document_id   uuid,
    rejection_reason  varchar(1000),
    reminded_at       timestamptz,
    escalated_at      timestamptz,
    created_by        varchar(200)  not null,
    created_at        timestamptz   not null,
    updated_at        timestamptz   not null,
    constraint ck_first_report_status check (status in ('DRAFT','SUBMITTED','ACKNOWLEDGED','REJECTED','LATE'))
);
create index ix_first_report_claim on claims.first_report (claim_id);
create index ix_first_report_open on claims.first_report (due_at) where status in ('DRAFT','LATE');

-- ---------- consent-gated document exchange (E08-F03) ----------
create table claims.disclosure_set (
    id              uuid primary key,
    claim_id        uuid          not null references claims.claim (id),
    recipient_code  varchar(40)   not null,
    purpose         varchar(500)  not null,
    status          varchar(20)   not null,
    consent_id      uuid,
    sent_at         timestamptz,
    sent_by         varchar(200),
    revoked_at      timestamptz,
    revoked_by      varchar(200),
    created_by      varchar(200)  not null,
    created_at      timestamptz   not null,
    constraint ck_disclosure_set_status check (status in ('DRAFT','SENT','REVOKED'))
);
create index ix_disclosure_set_claim on claims.disclosure_set (claim_id);

create table claims.disclosure_set_document (
    id                uuid primary key,
    set_id            uuid          not null references claims.disclosure_set (id),
    position          int           not null,
    document_id       uuid          not null,
    title             varchar(300)  not null,
    doc_type          varchar(60)   not null,
    redacted_from_id  uuid,
    added_by          varchar(200)  not null,
    added_by_oh       boolean       not null default false,
    share_id          uuid
);
create index ix_disclosure_doc_set on claims.disclosure_set_document (set_id, position);

-- ---------- nurse case manager <-> adjuster collaboration (E08-F04) ----------
create table claims.claim_task (
    id             uuid primary key,
    claim_id       uuid          not null references claims.claim (id),
    title          varchar(200)  not null,             -- screened
    due_date       date          not null,
    assignee_role  varchar(20)   not null,
    status         varchar(10)   not null,
    created_by     varchar(200)  not null,
    created_at     timestamptz   not null,
    completed_at   timestamptz,
    completed_by   varchar(200),
    constraint ck_claim_task_role check (assignee_role in ('CLAIMS','NURSE_CM','ADJUSTER')),
    constraint ck_claim_task_status check (status in ('OPEN','DONE'))
);
create index ix_claim_task_claim on claims.claim_task (claim_id, status);

create table claims.claim_message (
    id           uuid primary key,
    claim_id     uuid          not null references claims.claim (id),
    sender       varchar(200)  not null,
    sender_role  varchar(20)   not null,
    body         text          not null,               -- encrypted (screened)
    sent_at      timestamptz   not null
);
create index ix_claim_message_claim on claims.claim_message (claim_id, sent_at);

create table claims.message_read (
    claim_id  uuid          not null references claims.claim (id),
    reader    varchar(200)  not null,
    read_at   timestamptz   not null,
    primary key (claim_id, reader)
);

-- ---------- cost ledger, budgets & ERP export (E08-F05) ----------
create table claims.cost_entry (
    id               uuid primary key,
    claim_id         uuid references claims.claim (id),   -- null = case-level entry (no claim yet)
    case_id          uuid          not null,
    employee_id      varchar(64)   not null,
    category         varchar(20)   not null,
    kind             varchar(20)   not null,
    amount           numeric(14,2) not null,
    currency         varchar(3)    not null,
    incurred_on      date          not null,
    provider_code    varchar(40),
    description      varchar(500),                         -- screened
    invoice_number   varchar(60),
    source_type      varchar(40)   not null,
    source_ref       varchar(100),
    subsidiary_code  varchar(20),
    department_code  varchar(64),
    cost_centre      varchar(40),
    created_by       varchar(200)  not null,
    created_at       timestamptz   not null,
    updated_at       timestamptz   not null,
    constraint ck_cost_category check (category in ('MEDICAL','REHAB','INDEMNITY','ADMIN','SETTLEMENT','LEGAL')),
    constraint ck_cost_kind check (kind in ('ESTIMATE','INVOICE','PAYMENT','RESERVE'))
);
create unique index uq_cost_entry_source on claims.cost_entry (source_type, source_ref, kind) where source_ref is not null;
create index ix_cost_entry_claim on claims.cost_entry (claim_id);
create index ix_cost_entry_case on claims.cost_entry (case_id);
create index ix_cost_entry_incurred on claims.cost_entry (incurred_on);

create table claims.budget (
    budget_year  int            not null,
    unit_code    varchar(64)    not null,
    amount       numeric(14,2)  not null,
    currency     varchar(3)     not null,
    updated_by   varchar(200)   not null,
    updated_at   timestamptz    not null,
    primary key (budget_year, unit_code)
);

create table claims.cost_export (
    id             uuid primary key,
    export_month   varchar(7)     not null,
    batch_id       varchar(40)    not null unique,
    row_count      int            not null,
    total          numeric(14,2)  not null,
    currency       varchar(3)     not null,
    checksum       varchar(64)    not null,
    status         varchar(20)    not null,
    content        text           not null,               -- CSV: units, categories, amounts – no names
    erp_total      numeric(14,2),
    erp_reference  varchar(100),
    reconciled_at  timestamptz,
    reconciled_by  varchar(200),
    created_by     varchar(200)   not null,
    created_at     timestamptz    not null,
    constraint ck_cost_export_status check (status in ('GENERATED','RECONCILED','MISMATCH'))
);
create index ix_cost_export_month on claims.cost_export (export_month);

-- ---------- medico-legal & litigation support (E08-F06) ----------
create table claims.legal_request (
    id               uuid primary key,
    request_ref      varchar(30)   not null unique,
    claim_id         uuid          not null references claims.claim (id),
    case_id          uuid          not null,
    request_type     varchar(30)   not null,
    status           varchar(20)   not null,
    questions        text          not null,               -- encrypted
    due_date         date          not null,
    provider_code    varchar(40),
    referral_id      uuid,
    referral_ref     varchar(40),
    referral_status  varchar(30),
    created_by       varchar(200)  not null,
    created_at       timestamptz   not null,
    updated_at       timestamptz   not null,
    closed_at        timestamptz,
    closed_by        varchar(200),
    constraint ck_legal_request_type check (request_type in ('MEDICO_LEGAL_REPORT','EXPERT_OPINION','IME')),
    constraint ck_legal_request_status check (status in ('OPEN','IN_PROGRESS','REPORT_READY','CLOSED'))
);
create index ix_legal_request_claim on claims.legal_request (claim_id);
create index ix_legal_request_status on claims.legal_request (status);

create table claims.legal_report (
    id           uuid primary key,
    request_id   uuid          not null references claims.legal_request (id),
    version      int           not null,
    document_id  uuid          not null,
    author       varchar(200)  not null,
    created_at   timestamptz   not null,
    constraint uq_legal_report_version unique (request_id, version)
);

-- ---------- seed: statutory / insurer report forms – PENDING Legal / HSE sign-off (E08-BL-003) ----------
insert into claims.report_form (code, jurisdiction, recipient_type, recipient_code, title, deadline_hours, specified_deadline_hours,
                                deadline_anchor, trigger_rule, fields, sign_off_status) values
    ('GB_OSH_F2508', 'GB', 'AUTHORITY', 'AUTH_OSH', 'Report of an injury (F2508) – over-7-day / specified injury', 360, 240, 'INCIDENT',
     'LOST_TIME_OVER_7_DAYS',
     '[{"code":"employer","label":"Employer","source":"CONFIG"},{"code":"employerRef","label":"Employer reference","source":"CONFIG"},'
     || '{"code":"employeeName","label":"Injured person","source":"HRIS"},{"code":"employeeId","label":"Employee number","source":"HRIS"},'
     || '{"code":"jobTitle","label":"Occupation","source":"HRIS"},{"code":"occurredAt","label":"Date and time of incident","source":"INCIDENT"},'
     || '{"code":"location","label":"Place of incident","source":"INCIDENT"},{"code":"description","label":"How the incident happened","source":"INCIDENT"},'
     || '{"code":"bodyPart","label":"Part of body","source":"CASE"},{"code":"injuryNature","label":"Nature of injury","source":"CASE"},'
     || '{"code":"lostTime","label":"Days lost / over-7-day incapacity","source":"CLAIM"},{"code":"reportedBy","label":"Reported by","source":"USER"}]',
     'PENDING_LEGAL_HSE_SIGN_OFF'),
    ('AE_LABOUR_INJURY', 'AE', 'AUTHORITY', 'AUTH_LABOUR_AE', 'Work injury notice to the labour inspectorate (UAE)', 48, null, 'INCIDENT',
     'ANY_CLAIM',
     '[{"code":"employer","label":"Employer","source":"CONFIG"},{"code":"employerRef","label":"Establishment number","source":"CONFIG"},'
     || '{"code":"employeeName","label":"Injured worker","source":"HRIS"},{"code":"employeeId","label":"Employee number","source":"HRIS"},'
     || '{"code":"jobTitle","label":"Occupation","source":"HRIS"},{"code":"occurredAt","label":"Date and time of injury","source":"INCIDENT"},'
     || '{"code":"location","label":"Place of injury","source":"INCIDENT"},{"code":"description","label":"How the injury happened","source":"INCIDENT"},'
     || '{"code":"bodyPart","label":"Part of body","source":"CASE"},{"code":"injuryNature","label":"Nature of injury","source":"CASE"},'
     || '{"code":"lostTime","label":"Time lost from work","source":"CLAIM"},{"code":"reportedBy","label":"Reported by","source":"USER"}]',
     'PENDING_LEGAL_HSE_SIGN_OFF'),
    ('INSURER_FNOL', 'ANY', 'INSURER', null, 'First notification of loss to the insurer / TPA', 72, null, 'NOTIFIED',
     'ANY_CLAIM',
     '[{"code":"employer","label":"Policyholder","source":"CONFIG"},{"code":"claimRef","label":"Claim reference","source":"CLAIM"},'
     || '{"code":"policyNumber","label":"Policy number","source":"CLAIM"},{"code":"employeeName","label":"Injured employee","source":"HRIS"},'
     || '{"code":"employeeId","label":"Employee number","source":"HRIS"},{"code":"jobTitle","label":"Occupation","source":"HRIS"},'
     || '{"code":"occurredAt","label":"Date and time of injury","source":"INCIDENT"},{"code":"location","label":"Place of injury","source":"INCIDENT"},'
     || '{"code":"description","label":"How the injury happened","source":"INCIDENT"},{"code":"bodyPart","label":"Part of body","source":"CASE"},'
     || '{"code":"injuryNature","label":"Nature of injury","source":"CASE"},{"code":"lostTime","label":"Time lost from work","source":"CLAIM"},'
     || '{"code":"reportedBy","label":"Reported by","source":"USER"}]',
     'PENDING_LEGAL_HSE_SIGN_OFF')
on conflict (code) do nothing;

insert into masterdata.config_entry (config_key, config_value, description, updated_by, updated_at) values
    ('modules.e08.enabled', 'true', 'Workers'' compensation, insurance & claims coordination', 'migration', now()),
    ('claims.legal-email', 'legal@wellnessz.local', 'Legal counsel mailbox (subrogation and medico-legal notifications, content-free)', 'migration', now()),
    ('claims.mailbox-email', 'claims@wellnessz.local', 'Claims team mailbox (report deadlines, late-report escalation, shared tasks)', 'migration', now()),
    ('claims.nurse-sla-hours', '48', 'Lost-time claims: hours from lost-time flag to nurse case manager assignment (KPI)', 'migration', now()),
    ('claims.report-reminder-hours', '24', 'First reports: remind the claims team this many hours before the statutory deadline', 'migration', now()),
    ('claims.base-currency', 'GBP', 'Reporting currency of cost roll-ups, budgets and the ERP export', 'migration', now()),
    ('claims.fx-rates', 'GBP:1,AED:0.21,USD:0.79,EUR:0.85,SGD:0.58,HKD:0.10', 'Conversion rates to the base currency for roll-ups (proposed – finance to confirm)', 'migration', now()),
    ('claims.employer-name', 'WellnessZ Airline', 'Employer / policyholder name on first reports', 'migration', now()),
    ('claims.employer-ref', 'WZ-EMP-0001', 'Employer reference with the reporting authorities (proposed – HSE to confirm)', 'migration', now()),
    ('claims.forms.sign-off', 'PENDING', 'Report form definitions are pending Legal / HSE sign-off (E08-BL-003)', 'migration', now())
on conflict (config_key) do nothing;

grant usage on schema claims to ${app_role};
grant select, insert, update on claims.ref_counter, claims.claim, claims.report_form, claims.first_report, claims.disclosure_set,
      claims.disclosure_set_document, claims.claim_task, claims.message_read, claims.cost_entry, claims.budget, claims.cost_export,
      claims.legal_request to ${app_role};
grant select, insert on claims.claim_history, claims.claim_message, claims.legal_report to ${app_role};
