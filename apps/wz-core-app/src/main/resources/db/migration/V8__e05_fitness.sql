-- E05 Fitness for duty, work restrictions & return to work (schema fitness).
-- Clinical free text (rationale, findings, exclusion reasons, notes) is encrypted at field level by the application
-- (columns marked "encrypted"); manager / HR facing text is screened for clinical content before it is stored.

create schema if not exists fitness;

-- ---------- fitness decisions (E05-F01, E05-F04) ----------
create table fitness.fitness_decision (
    id                        uuid primary key,
    case_id                   uuid          not null,
    case_ref                  varchar(30)   not null,
    employee_id               varchar(64)   not null,
    standard_code             varchar(40)   not null,
    standard_version          int           not null,
    outcome                   varchar(30)   not null,
    kernel_status             varchar(30)   not null,
    limitation_codes          varchar(200),
    restrictions              text,                    -- JSON [{code,label,value}] – functional wording only
    valid_from                date          not null,
    valid_until               date,
    review_date               date,
    rationale                 text,                    -- encrypted
    duration_override_reason  text,                    -- encrypted
    encounter_id              uuid,
    status                    varchar(20)   not null,
    required_signoff_role     varchar(20),
    decided_by                varchar(200)  not null,
    decided_at                timestamptz   not null,
    signoff_by                varchar(200),
    signoff_at                timestamptz,
    signoff_note              text,                    -- encrypted
    certificate_document_id   uuid,
    crew_assessment           boolean       not null default false,
    examination_date          date,
    report_document_id        uuid
);
create index ix_fitness_decision_case on fitness.fitness_decision (case_id, decided_at desc);
create index ix_fitness_decision_employee on fitness.fitness_decision (employee_id, standard_code, decided_at desc);
create index ix_fitness_decision_status on fitness.fitness_decision (status) where status = 'AWAITING_SIGNOFF';

-- ---------- restriction instances (E05-F02, E05-F06) ----------
create table fitness.restriction_instance (
    id               uuid primary key,
    employee_id      varchar(64)   not null,
    unit_code        varchar(20),
    department_code  varchar(64),
    case_id          uuid          not null,
    case_ref         varchar(30)   not null,
    decision_id      uuid,
    code             varchar(40)   not null,
    label            varchar(200)  not null,
    restriction_value numeric(8,2),
    valid_from       date          not null,
    valid_until      date,
    status           varchar(20)   not null,
    alerted_at       timestamptz,
    closed_at        timestamptz,
    closed_by        varchar(200),
    close_reason     varchar(500),
    created_by       varchar(200)  not null,
    created_at       timestamptz   not null,
    updated_at       timestamptz   not null
);
create index ix_restriction_employee on fitness.restriction_instance (employee_id, status);
create index ix_restriction_case on fitness.restriction_instance (case_id);
create index ix_restriction_expiry on fitness.restriction_instance (status, valid_until);

-- ---------- modified-duty proposals (E05-F02) ----------
create table fitness.duty_proposal (
    id             uuid primary key,
    case_id        uuid          not null,
    case_ref       varchar(30)   not null,
    employee_id    varchar(64)   not null,
    job_code       varchar(64)   not null,
    job_title      varchar(200)  not null,
    duties         varchar(1000) not null,
    restrictions   text,                      -- JSON [{code,label,value}]
    hours_per_day  numeric(4,1),
    start_date     date          not null,
    end_date       date,
    status         varchar(20)   not null,
    proposed_by    varchar(200)  not null,
    proposed_at    timestamptz   not null,
    decided_by     varchar(200),
    decided_at     timestamptz,
    reason_code    varchar(30),
    note           varchar(1000)
);
create index ix_duty_proposal_case on fitness.duty_proposal (case_id);
create index ix_duty_proposal_status on fitness.duty_proposal (status, employee_id);

-- ---------- RTW plans and reviews (E05-F03) ----------
create table fitness.rtw_plan (
    id                 uuid primary key,
    case_id            uuid          not null,
    case_ref           varchar(30)   not null,
    employee_id        varchar(64)   not null,
    status             varchar(20)   not null,
    start_date         date          not null,
    goal               varchar(1000) not null,
    phases             text          not null,   -- JSON, functional wording only
    review_dates       varchar(400),             -- planned review dates while DRAFT (rows created on activation)
    created_by         varchar(200)  not null,
    created_at         timestamptz   not null,
    activated_at       timestamptz,
    employee_ack_at    timestamptz,
    employee_agree     boolean,
    employee_comment   text,                     -- encrypted (employee and OH only)
    manager_ack_at     timestamptz,
    manager_ack_by     varchar(200),
    manager_comment    varchar(1000),
    document_id        uuid
);
create index ix_rtw_plan_case on fitness.rtw_plan (case_id);
create index ix_rtw_plan_employee on fitness.rtw_plan (employee_id, status);

create table fitness.rtw_review (
    id            uuid primary key,
    plan_id       uuid          not null references fitness.rtw_plan (id),
    due_date      date          not null,
    completed_at  timestamptz,
    completed_by  varchar(200),
    outcome       varchar(20),
    note          varchar(1000),
    reminded_at   timestamptz,
    escalated_at  timestamptz
);
create index ix_rtw_review_plan on fitness.rtw_review (plan_id, due_date);
create index ix_rtw_review_open on fitness.rtw_review (due_date) where completed_at is null;

-- ---------- alternative-duty job offers (E05-F03) ----------
create table fitness.job_offer (
    id              uuid primary key,
    case_id         uuid          not null,
    case_ref        varchar(30)   not null,
    employee_id     varchar(64)   not null,
    job_code        varchar(64),
    job_title       varchar(200)  not null,
    duties          varchar(2000) not null,
    location        varchar(200)  not null,
    shift           varchar(100)  not null,
    hours_per_week  numeric(5,1)  not null,
    pay_basis       varchar(20)   not null,
    pay_note        varchar(500),
    restrictions    text,                     -- JSON [{code,label,value}]
    start_date      date          not null,
    end_date        date,
    contact_name    varchar(200)  not null,
    contact_email   varchar(200),
    respond_by      date          not null,
    status          varchar(20)   not null,
    issued_by       varchar(200)  not null,
    issued_at       timestamptz   not null,
    responded_at    timestamptz,
    response_comment varchar(1000),
    document_id     uuid
);
create index ix_job_offer_case on fitness.job_offer (case_id);
create index ix_job_offer_employee on fitness.job_offer (employee_id, status);

-- ---------- crew roster-status feed log (E05-F04) ----------
create table fitness.roster_feed_message (
    message_id              varchar(64)   primary key,
    employee_id             varchar(64)   not null,
    case_id                 uuid,
    fitness_status          varchar(30)   not null,
    roster_status           varchar(20)   not null,
    valid_from              date,
    valid_until             date,
    operational_limitations varchar(200),
    sent_at                 timestamptz   not null,
    ack_at                  timestamptz,
    ack_by                  varchar(200),
    alerted_at              timestamptz
);
create index ix_roster_feed_sent on fitness.roster_feed_message (sent_at);
create index ix_roster_feed_employee on fitness.roster_feed_message (employee_id, sent_at desc);
create index ix_roster_feed_unacked on fitness.roster_feed_message (sent_at) where ack_at is null;

-- crew operational attributes for the CMO dashboard (fleet / base are not held in the HRIS employee master)
create table fitness.crew_profile (
    employee_id  varchar(64)  primary key,
    fleet        varchar(40)  not null,
    base         varchar(40)  not null,
    updated_at   timestamptz  not null
);

-- ---------- food handlers (E05-F05) ----------
create table fitness.food_handler_episode (
    id                   uuid primary key,
    case_id              uuid          not null,
    employee_id          varchar(64)   not null,
    department_code      varchar(64),
    status               varchar(30)   not null,
    reason_category      text          not null,   -- encrypted (OH only)
    checklist            text,                     -- encrypted JSON (OH only)
    excluded_from        date          not null,
    expected_return      date,
    return_requested_at  timestamptz,
    return_requested_by  varchar(200),
    cleared_at           timestamptz,
    cleared_by           varchar(200),
    criteria             text,                     -- encrypted JSON
    clearance_note       text,                     -- encrypted
    document_id          uuid,
    created_by           varchar(200)  not null,
    created_at           timestamptz   not null,
    updated_at           timestamptz   not null
);
create index ix_food_episode_case on fitness.food_handler_episode (case_id);
create index ix_food_episode_dept on fitness.food_handler_episode (department_code, updated_at desc);

-- ---------- ill-health retirement / redeployment (E05-F07) ----------
create table fitness.ihr_assessment (
    id                    uuid primary key,
    case_id               uuid          not null,
    case_ref              varchar(30)   not null,
    employee_id           varchar(64)   not null,
    assessment_type       varchar(30)   not null,
    findings              text          not null,   -- encrypted
    recommendation        text          not null,   -- encrypted
    policy_reference      varchar(200),
    status                varchar(20)   not null,
    assessed_by           varchar(200)  not null,
    assessed_at           timestamptz   not null,
    approved              boolean,
    decision_text         varchar(4000),
    cmo_note              text,                     -- encrypted
    decided_by            varchar(200),
    decided_at            timestamptz,
    appeal_deadline       date,
    letter_document_id    uuid,
    appeal_grounds        text,                     -- encrypted
    second_opinion_by     varchar(200),
    appealed_by           varchar(200),
    appealed_at           timestamptz,
    appeal_due_by         date,
    appeal_upheld         boolean,
    appeal_decision_text  varchar(4000),
    appeal_decided_by     varchar(200),
    appeal_decided_at     timestamptz,
    appeal_letter_document_id uuid
);
create index ix_ihr_case on fitness.ihr_assessment (case_id);

grant usage on schema fitness to ${app_role};
grant select, insert, update on fitness.fitness_decision, fitness.restriction_instance, fitness.duty_proposal, fitness.rtw_plan,
      fitness.rtw_review, fitness.job_offer, fitness.roster_feed_message, fitness.food_handler_episode, fitness.ihr_assessment
      to ${app_role};
grant select on fitness.crew_profile to ${app_role};
