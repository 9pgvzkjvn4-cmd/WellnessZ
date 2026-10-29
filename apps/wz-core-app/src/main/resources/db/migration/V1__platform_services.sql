-- =====================================================================================================
-- V1 – Platform services (E15 integration, E16 identity/consent/audit, notifications)
-- Executed by the migrator (owner) role. The runtime role ${app_role} receives DML-only grants.
-- =====================================================================================================
create schema if not exists platform;
create schema if not exists masterdata;
create schema if not exists intake;
create schema if not exists casemgmt;
create schema if not exists analytics;

-- ---------- E16: immutable, hash-chained audit trail ----------
create table platform.audit_event (
    id              bigint generated always as identity primary key,
    occurred_at     timestamptz  not null,
    actor           varchar(200) not null,
    actor_roles     varchar(500),
    action          varchar(100) not null,
    resource_type   varchar(100),
    resource_id     varchar(200),
    outcome         varchar(20)  not null,
    reason          varchar(500),
    correlation_id  varchar(64),
    client_ip       varchar(64),
    prev_hash       varchar(64)     not null,
    hash            varchar(64)     not null unique
);
create index ix_audit_event_occurred on platform.audit_event (occurred_at);
create index ix_audit_event_resource on platform.audit_event (resource_type, resource_id);
create index ix_audit_event_actor on platform.audit_event (actor, occurred_at);

create or replace function platform.audit_event_immutable() returns trigger language plpgsql as $$
begin
    raise exception 'audit_event is append-only (% blocked)', tg_op using errcode = 'insufficient_privilege';
end;
$$;
create trigger trg_audit_event_no_update before update or delete on platform.audit_event
    for each row execute function platform.audit_event_immutable();
create trigger trg_audit_event_no_truncate before truncate on platform.audit_event
    for each statement execute function platform.audit_event_immutable();

-- ---------- E16: consent ----------
create table platform.consent (
    id              uuid primary key,
    employee_id     varchar(64)  not null,
    purpose         varchar(50)  not null,
    scope           varchar(500),
    status          varchar(20)  not null check (status in ('GRANTED','WITHDRAWN')),
    notice_version  varchar(20)  not null,
    granted_at      timestamptz  not null,
    withdrawn_at    timestamptz,
    captured_by     varchar(200) not null,
    version         bigint       not null default 0
);
create index ix_consent_employee on platform.consent (employee_id, purpose, status);

-- ---------- E15: transactional outbox, idempotency, inbound store ----------
create table platform.outbox_event (
    id               uuid primary key,
    event_type       varchar(200) not null,
    aggregate_type   varchar(100) not null,
    aggregate_id     varchar(200) not null,
    destination      varchar(100) not null,
    payload          text         not null,
    status           varchar(20)  not null check (status in ('PENDING','PUBLISHED','FAILED','DEAD')),
    attempts         int          not null default 0,
    next_attempt_at  timestamptz  not null,
    created_at       timestamptz  not null,
    published_at     timestamptz,
    last_error       varchar(1000),
    correlation_id   varchar(64)
);
create index ix_outbox_due on platform.outbox_event (next_attempt_at) where status in ('PENDING','FAILED');
create index ix_outbox_created on platform.outbox_event (created_at desc);

create table platform.processed_message (
    consumer      varchar(100) not null,
    message_id    uuid         not null,
    processed_at  timestamptz  not null,
    primary key (consumer, message_id)
);

create table platform.idempotency_record (
    scope            varchar(100) not null,
    idempotency_key  varchar(200) not null,
    request_hash     varchar(64)     not null,
    response_status  int          not null,
    response_body    text,
    created_at       timestamptz  not null,
    primary key (scope, idempotency_key)
);

create table platform.inbound_message (
    id            uuid primary key,
    source        varchar(100) not null,
    format        varchar(40)  not null,
    message_type  varchar(100),
    external_id   varchar(200),
    payload       text         not null, -- AES-256-GCM encrypted (field level)
    status        varchar(20)  not null,
    received_at   timestamptz  not null,
    received_by   varchar(200) not null
);
create index ix_inbound_source on platform.inbound_message (source, received_at desc);

-- ---------- terminology cache (SNOMED CT / ICD / LOINC / ODH) ----------
create table platform.code_concept (
    system   varchar(20)  not null,
    code     varchar(50)  not null,
    display  varchar(500) not null,
    active   boolean      not null default true,
    primary key (system, code)
);
insert into platform.code_concept (system, code, display) values
    ('LOINC', '718-7', 'Hemoglobin [Mass/volume] in Blood'),
    ('LOINC', '28539-6', 'Audiogram study'),
    ('LOINC', '19659-3', 'Drug screen comment'),
    ('ICD_10', 'S93.4', 'Sprain of ankle'),
    ('ICD_10', 'H83.3', 'Noise effects on inner ear'),
    ('ICD_10', 'Z02.1', 'Pre-employment examination'),
    ('ODH', 'UNK', 'Unknown occupation');

-- ---------- notifications ----------
create table platform.notification (
    id            uuid primary key,
    recipient_id  varchar(64)   not null,
    address       varchar(320)  not null,
    channel       varchar(10)   not null,
    priority      varchar(10)   not null,
    template_key  varchar(100)  not null,
    body          varchar(1000) not null,
    status        varchar(20)   not null,
    scheduled_at  timestamptz   not null,
    sent_at       timestamptz,
    attempts      int           not null default 0
);
create index ix_notification_due on platform.notification (scheduled_at) where status = 'SCHEDULED';

-- ---------- least-privilege grants for the runtime role ----------
grant usage on schema platform to ${app_role};
grant select, insert on platform.audit_event to ${app_role};           -- append-only
grant select, insert, update on platform.consent to ${app_role};       -- no delete: withdrawal is a state change
grant select, insert, update, delete on platform.outbox_event, platform.processed_message,
      platform.idempotency_record to ${app_role};
grant select, insert, update on platform.inbound_message, platform.notification to ${app_role};
grant select on platform.code_concept to ${app_role};
