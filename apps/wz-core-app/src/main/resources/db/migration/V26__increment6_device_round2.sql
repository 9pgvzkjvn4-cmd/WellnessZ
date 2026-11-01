-- =====================================================================================================
-- V26 – Increment 6, second build round for E31–E34 (software gaps of the traceability matrix):
--   * E31 P6 vendor-cloud polling (OAuth2 client credentials): per-connector poll state with the high-water mark
--   * E31 outbound orders / worklist (ORM^O01, OML^O21, ADT^A04 / A08 generated with HAPI) and their acknowledgements
--   * E31 historic retrieval (device / data-manager query for a time range) with result-batch ingest
--   * gateway command RETRIEVE_HISTORY
--   * tenant scoping: tenant residency (E31 tenants), tenant-scoped catalogue rows (E32), per-tenant signing keys (E34)
-- V22–V25 are left unchanged (a live development database may already have run them – Flyway checksums).
-- Contract: docs/increment-06/API-Contract-E31-E34.md (§7, §12, §13.3, §15.5, §16.1).
-- =====================================================================================================

-- ---------- gateway commands: historic retrieval ----------
alter table deviceint.gateway_command drop constraint if exists gateway_command_type_check;
alter table deviceint.gateway_command add constraint gateway_command_type_check
    check (type in ('RELOAD_CONFIG','RESEND_LAST','ROTATE_TOKEN','ISOLATE','PUSH_WORKLIST','START_ACQUISITION','RETRIEVE_HISTORY'));

-- ---------- P6 vendor-cloud polling (E31-BL-008): one row per published P6 connector ----------
create table deviceint.cloud_poll_state (
    device_instance_id   varchar(64)   primary key,
    tenant_code          varchar(40)   not null,
    high_water_mark      varchar(200),                 -- vendor cursor / last record time; advances only after durable ingest
    last_poll_at         timestamptz,
    next_poll_at         timestamptz,
    last_status          varchar(30),                  -- OK | AUTH_FAILED | HOST_NOT_ALLOWED | RESIDENCY_MISMATCH | RATE_LIMITED | ERROR | DISABLED
    last_error           varchar(200),                 -- error code only (never a response body)
    consecutive_failures int           not null default 0,
    records_total        bigint        not null default 0,
    records_last         int           not null default 0,
    duplicates_total     bigint        not null default 0,
    skipped_no_consent   bigint        not null default 0,
    updated_at           timestamptz   not null
);

-- ---------- outbound orders / worklist messages (E31-HLR-08, E31-BL-004, E31-BL-016): no message body is stored ----------
create table deviceint.outbound_message (
    id                 uuid          primary key,
    tenant_code        varchar(40)   not null,
    command_id         uuid          not null,
    command_type       varchar(30)   not null,
    endpoint_id        varchar(64)   not null,
    device_instance_id varchar(64)   not null,
    session_id         uuid,
    order_ref          varchar(64),
    transport          varchar(10)   not null check (transport in ('MLLP','SERIAL','GDT')),
    message_type       varchar(20)   not null,         -- ORM^O01 | OML^O21 | ADT^A04 | ADT^A08 | QRY^R02 | START
    control_id         varchar(40)   not null,
    status             varchar(20)   not null check (status in ('SENT','ACKED','REJECTED','FAILED')),
    ack_code           varchar(4),
    detail             varchar(200),
    created_at         timestamptz   not null,
    updated_at         timestamptz   not null,
    unique (command_id, control_id)
);
create index ix_outbound_session on deviceint.outbound_message (session_id);
create index ix_outbound_device on deviceint.outbound_message (device_instance_id, created_at);

-- ---------- historic retrieval (E31-HLR-08): request → gateway command → result batch ----------
create table deviceint.historic_retrieval (
    id                 uuid          primary key,
    tenant_code        varchar(40)   not null,
    device_instance_id varchar(64)   not null,
    endpoint_id        varchar(64)   not null,
    from_at            timestamptz   not null,
    to_at              timestamptz   not null,
    method             varchar(20)   not null,         -- HL7_QUERY | VENDOR_DB
    status             varchar(20)   not null check (status in ('REQUESTED','SENT','RECEIVING','COMPLETED','FAILED')),
    command_id         uuid          not null,
    requested_by       varchar(200)  not null,
    requested_at       timestamptz   not null,
    completed_at       timestamptz,
    received           int           not null default 0,
    accepted           int           not null default 0,
    duplicates         int           not null default 0,
    failed             int           not null default 0,
    detail             varchar(200)
);
create index ix_historic_device on deviceint.historic_retrieval (device_instance_id, requested_at);

-- ---------- tenants (E31-HLR-12): data residency next to quota and catalogue scope ----------
alter table deviceint.tenant_quota add column residency_region varchar(10) not null default 'UK';
alter table deviceint.tenant_quota add constraint ck_tenant_catalogue_scope check (catalogue_scope in ('GROUP','TENANT'));

-- ---------- tenant-scoped catalogue (E32): null = shared group catalogue, otherwise visible to that tenant only ----------
alter table devicecat.model add column tenant_code varchar(40);
alter table devicecat.driver_package add column tenant_code varchar(40);
create index ix_model_tenant on devicecat.model (tenant_code);

-- ---------- per-tenant driver-package signing keys (E34-HLR-06): one ACTIVE key per tenant ----------
alter table devicesec.signing_key add column tenant_code varchar(30) not null default 'GROUP';
drop index if exists devicesec.ux_devsec_signing_key_active;
create unique index ux_devsec_signing_key_active on devicesec.signing_key (tenant_code) where status = 'ACTIVE';

-- ---------- least-privilege grants ----------
grant select, insert, update on deviceint.cloud_poll_state to ${app_role};
grant select, insert, update on deviceint.outbound_message to ${app_role};
grant select, insert, update on deviceint.historic_retrieval to ${app_role};
