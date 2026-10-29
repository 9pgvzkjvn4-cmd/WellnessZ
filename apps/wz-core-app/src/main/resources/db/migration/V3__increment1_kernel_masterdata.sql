-- =====================================================================================================
-- V3 – Increment 1 kernel: HRIS employee master, OH staff directory, case identity (E01-HLR-05),
--      routing/triage fields, unit-year case numbering, links, timeline, tasks, encrypted attachments
-- =====================================================================================================
alter table masterdata.job_code add column safety_category varchar(40);
update masterdata.job_code set safety_category = 'FLIGHT_CREW' where code = 'PILOT';
update masterdata.job_code set safety_category = 'CABIN_CREW' where code = 'CABIN_CREW';
update masterdata.job_code set safety_category = 'AIRSIDE_DRIVER' where code = 'RAMP_AGENT';
update masterdata.job_code set safety_category = 'CERTIFYING_ENGINEER' where code = 'ENGINEER';
update masterdata.job_code set safety_category = 'FOOD_HANDLER', safety_critical = true where code = 'CATERING_ASSOC';
insert into masterdata.job_code (code, title, safety_critical, hazard_codes, safety_category) values
    ('FORKLIFT_OP', 'Forklift operator', true, 'NOISE,MANUAL_HANDLING', 'FORKLIFT'),
    ('LINE_MAINT', 'Line maintenance – working at height', true, 'NOISE', 'WORKING_AT_HEIGHT'),
    ('OFFICE_ADMIN', 'Office administrator', false, null, null)
on conflict (code) do nothing;
insert into masterdata.org_unit (code, name, parent_code, path, unit_type, updated_at) values
    ('CORP', 'Corporate', 'AIRLINE', '/AIRLINE/CORP', 'DIVISION', now()),
    ('DUTYFREE', 'Duty Free', 'AIRLINE', '/AIRLINE/DUTYFREE', 'DIVISION', now()),
    ('DISTRIBUTION', 'Distribution & Logistics', 'AIRLINE', '/AIRLINE/DISTRIBUTION', 'DIVISION', now()),
    ('OH', 'Occupational Health', 'AIRLINE', '/AIRLINE/OH', 'DEPARTMENT', now())
on conflict (code) do nothing;

create table masterdata.employee (
    employee_id          varchar(64)  primary key,
    display_name         varchar(200) not null,
    email                varchar(320),
    phone                varchar(40),
    subsidiary_code      varchar(20)  not null,
    department_code      varchar(64)  not null,
    cost_centre          varchar(40),
    job_code             varchar(64),
    location_code        varchar(40),
    shift_pattern        varchar(40),
    preferred_language   varchar(8),
    badge_id             varchar(64)  unique,
    manager_employee_id  varchar(64),
    active               boolean      not null default true,
    updated_at           timestamptz  not null
);
create index ix_employee_department on masterdata.employee (department_code);
create index ix_employee_name on masterdata.employee (lower(display_name) varchar_pattern_ops);

create table masterdata.staff_member (
    username             varchar(200) primary key,
    display_name         varchar(200) not null,
    email                varchar(320),
    roles                varchar(500) not null,
    site_codes           varchar(500),
    languages            varchar(100),
    specialties          varchar(500),
    caseload_cap         int          not null,
    out_of_office_until  date,
    cover_username       varchar(200),
    active               boolean      not null default true
);

-- ---------- case aggregate extensions ----------
alter table casemgmt.oh_case alter column case_ref type varchar(30);
alter table casemgmt.oh_case
    add column subsidiary_code     varchar(20),
    add column cost_centre         varchar(40),
    add column job_code            varchar(64),
    add column location_code       varchar(40),
    add column shift               varchar(40),
    add column hris_override       boolean     not null default false,
    add column case_type           varchar(40),
    add column incident_ref        varchar(100),
    add column body_part           varchar(60),
    add column occurred_on         date,
    add column language            varchar(8),
    add column template_code       varchar(40),
    add column urgency             varchar(20) not null default 'ROUTINE',
    add column safety_category     varchar(40),
    add column owner_username      varchar(200),
    add column queue_code          varchar(40),
    add column waiting_state       varchar(30) not null default 'NONE',
    add column intensity           varchar(20) not null default 'STANDARD',
    add column complexity_score    int,
    add column early_intervention  boolean     not null default false,
    add column duplicate_suspect   boolean     not null default false,
    add column escalated           boolean     not null default false,
    add column merged_into_case_id uuid,
    add column recordability       varchar(30) not null default 'NOT_CLASSIFIED',
    add column work_related        varchar(20),
    add column intake_details      text;           -- encrypted (field level)
update casemgmt.oh_case set case_type = case category
        when 'INJURY' then 'INJURY' when 'ILLNESS' then 'ILLNESS' when 'DECREASE_IN_FITNESS' then 'FITNESS'
        when 'PSYCHOLOGICAL' then 'MENTAL_HEALTH' when 'SUBSTANCE' then 'SUBSTANCE' when 'EXPOSURE' then 'EXPOSURE' else 'OTHER' end;
alter table casemgmt.oh_case alter column case_type set not null;

-- queue / SLA board access paths (E02-BL-010: < 2 s with 5 000 open cases)
create index ix_case_open_owner on casemgmt.oh_case (owner_username) where status not in ('CLOSED','MERGED');
create index ix_case_open_queue on casemgmt.oh_case (queue_code) where status not in ('CLOSED','MERGED');
create index ix_case_open_unassigned on casemgmt.oh_case (created_at) where owner_username is null and status not in ('CLOSED','MERGED');
create index ix_case_incident_ref on casemgmt.oh_case (incident_ref) where incident_ref is not null;
create index ix_case_employee_body on casemgmt.oh_case (employee_id, body_part, created_at);
create index ix_case_safety_dept on casemgmt.oh_case (safety_category, org_unit_code) where safety_category is not null;

create table casemgmt.case_sequence (
    unit_code   varchar(20) not null,
    seq_year    int         not null,
    last_value  bigint      not null,
    primary key (unit_code, seq_year)
);

create table casemgmt.case_link (
    id          uuid primary key,
    case_id     uuid         not null references casemgmt.oh_case(id),
    link_type   varchar(20)  not null,
    ref         varchar(200) not null,
    created_by  varchar(200) not null,
    created_at  timestamptz  not null
);
create index ix_case_link_case on casemgmt.case_link (case_id);
create index ix_case_link_ref on casemgmt.case_link (link_type, ref);

create table casemgmt.case_event (
    id          uuid primary key,
    case_id     uuid         not null references casemgmt.oh_case(id),
    event_type  varchar(60)  not null,
    summary     varchar(500) not null,
    actor       varchar(200) not null,
    occurred_at timestamptz  not null
);
create index ix_case_event_case on casemgmt.case_event (case_id, occurred_at);

create table casemgmt.case_task (
    id            uuid primary key,
    case_id       uuid         not null references casemgmt.oh_case(id),
    task_type     varchar(20)  not null,
    title         varchar(300) not null,
    due_at        timestamptz,
    assignee      varchar(200),
    status        varchar(20)  not null,
    created_by    varchar(200) not null,
    created_at    timestamptz  not null,
    completed_at  timestamptz
);
create index ix_case_task_open on casemgmt.case_task (case_id) where status = 'OPEN';
create index ix_case_task_assignee on casemgmt.case_task (assignee) where status = 'OPEN';

create table casemgmt.case_attachment (
    id            uuid primary key,
    case_id       uuid         not null references casemgmt.oh_case(id),
    file_name     varchar(200) not null,
    content_type  varchar(100) not null,
    size_bytes    bigint       not null,
    sha256        varchar(64)  not null,
    content       text         not null,   -- base64, encrypted (field level)
    uploaded_by   varchar(200) not null,
    uploaded_at   timestamptz  not null
);
create index ix_case_attachment_case on casemgmt.case_attachment (case_id);

grant select, insert, update on masterdata.employee, masterdata.staff_member to ${app_role};
grant select, insert, update on casemgmt.case_sequence, casemgmt.case_link, casemgmt.case_task to ${app_role};
grant select, insert on casemgmt.case_event to ${app_role};                  -- timeline is append-only
grant select, insert, update on casemgmt.case_attachment to ${app_role};     -- merge moves attachments; no delete
