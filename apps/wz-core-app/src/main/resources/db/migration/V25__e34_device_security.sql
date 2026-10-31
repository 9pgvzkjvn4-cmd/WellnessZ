create schema if not exists devicesec;
-- =====================================================================================================
-- V25 – E34 Endpoint and integration security: endpoint identities (certificate thumbprints, hashed per-endpoint tokens), enrolment tokens, zones and conduits, package-signing keys, connector secrets (encrypted), append-only hash-chained security events, anomaly rules and alerts, isolation records, vendor posture exceptions, security test-pack runs.
-- Base migration created by the platform increment (schema + usage grant). The E34 developer adds the tables, indexes,
-- seed rows and table-level grants below this header (least privilege per table, as in V17–V20). Contract:
-- docs/increment-06/API-Contract-E31-E34.md.
-- =====================================================================================================

grant usage on schema devicesec to ${app_role};

-- ---------- endpoint identities (E34-HLR-01, E34-F01; contract §15.1) – classification: CONFIDENTIAL (security), no PHI ----------
create table devicesec.endpoint (
    endpoint_id              varchar(64)  primary key,                 -- EP-<SITE>-<KIND>-<NN>
    kind                     varchar(30)  not null check (kind in ('GATEWAY','DEVICE_HOST_AGENT','MOBILE_APP','KIOSK','DATA_MANAGER','VENDOR_CLOUD','DEVICE')),
    site_code                varchar(20)  not null,
    zone                     varchar(30)  not null,
    tenant_code              varchar(30)  not null,
    status                   varchar(20)  not null check (status in ('PENDING_ENROLMENT','ACTIVE','ISOLATED','REVOKED')),
    status_reason            varchar(60),
    cert_sha256              varchar(64),                              -- bound client-certificate thumbprint (lowercase hex)
    cert_subject             varchar(500),
    cert_issuer              varchar(500),
    cert_serial              varchar(100),
    cert_not_before          timestamptz,
    cert_not_after           timestamptz,
    token_hash               varchar(64),                              -- HMAC-SHA256(pepper, token), base64url – never the token
    token_issued_at          timestamptz,
    token_expires_at         timestamptz,
    prev_token_hash          varchar(64),                              -- rotation grace (15 min)
    prev_token_valid_until   timestamptz,
    allowed_cidrs            varchar(1000),                            -- per-endpoint source-address policy (comma separated CIDRs)
    hostname                 varchar(200),
    platform_os              varchar(100),
    platform_os_version      varchar(100),
    agent_version            varchar(60),
    hardening_baseline       varchar(100),
    hardening_status         varchar(20),
    attested                 boolean      not null default false,
    recertification_required boolean      not null default false,
    created_by               varchar(200) not null,
    created_at               timestamptz  not null,
    enrolled_at              timestamptz,
    first_seen_at            timestamptz,
    last_seen_at             timestamptz,
    isolated_at              timestamptz,
    released_at              timestamptz,
    revoked_at               timestamptz,
    version                  bigint       not null default 0
);
create index ix_devsec_endpoint_site on devicesec.endpoint (site_code, status);
create index ix_devsec_endpoint_cert_expiry on devicesec.endpoint (cert_not_after) where status = 'ACTIVE';

create table devicesec.endpoint_instance (
    endpoint_id         varchar(64)  not null references devicesec.endpoint(endpoint_id),
    device_instance_id  varchar(64)  not null,
    added_by            varchar(200) not null,
    added_at            timestamptz  not null,
    primary key (endpoint_id, device_instance_id)
);
create index ix_devsec_endpoint_instance on devicesec.endpoint_instance (device_instance_id);

-- one-time enrolment tokens (256-bit, hashed at rest, single use, <= 72 h) – contract §15.2
create table devicesec.enrolment_token (
    id           uuid         primary key,
    endpoint_id  varchar(64)  not null references devicesec.endpoint(endpoint_id),
    token_hash   varchar(64)  not null unique,
    kind         varchar(30)  not null,
    created_by   varchar(200) not null,
    created_at   timestamptz  not null,
    expires_at   timestamptz  not null,
    used_at      timestamptz,
    used_from    varchar(64),
    revoked_at   timestamptz
);
create index ix_devsec_enrolment_endpoint on devicesec.enrolment_token (endpoint_id);

-- ---------- zones, conduits and zone address ranges (E34-HLR-02, E34-F02; contract §15.4) ----------
create table devicesec.zone (
    code            varchar(30)  primary key,
    name            varchar(100) not null,
    description     varchar(500) not null,
    default_policy  varchar(10)  not null check (default_policy in ('DENY','ALLOW'))
);

create table devicesec.conduit (
    id          varchar(40)  primary key,
    from_zone   varchar(30)  not null references devicesec.zone(code),
    to_zone     varchar(30)  not null references devicesec.zone(code),
    protocol    varchar(20)  not null check (protocol in ('HTTPS_MTLS','MLLP_TLS','DICOM_TLS','BLE','SERIAL','HTTPS_HMAC','HTTPS_OAUTH2')),
    ports       varchar(100) not null,
    direction   varchar(20)  not null check (direction in ('OUTBOUND','INBOUND','BIDIRECTIONAL','LOCAL')),
    description varchar(500) not null
);

create table devicesec.zone_cidr (
    id          uuid         primary key,
    site_code   varchar(20)  not null,                                -- '*' = every site
    zone        varchar(30)  not null references devicesec.zone(code),
    cidr        varchar(64)  not null,
    created_by  varchar(200) not null,
    created_at  timestamptz  not null,
    unique (site_code, zone, cidr)
);

-- clinic hours per site (anomaly rules OFF_HOURS_TRAFFIC / OFF_HOURS_CONFIG_ACCESS); '*' = default
create table devicesec.site_hours (
    site_code  varchar(20)  primary key,
    time_zone  varchar(60)  not null,
    opens      time         not null,
    closes     time         not null,
    days       varchar(40)  not null                                  -- e.g. MON,TUE,WED,THU,FRI,SAT
);

-- ---------- driver-package signing keys (E34-HLR-06; contract §15.5) ----------
create table devicesec.signing_key (
    key_id       varchar(40)  primary key,
    algorithm    varchar(20)  not null check (algorithm in ('Ed25519','SHA256withECDSA')),
    public_key   text         not null,                               -- base64 X.509 SubjectPublicKeyInfo
    private_key  text         not null,                               -- encrypted (field level), PKCS#8 – production: HSM / KMS reference
    status       varchar(10)  not null check (status in ('ACTIVE','RETIRED','REVOKED')),
    created_by   varchar(200) not null,
    created_at   timestamptz  not null,
    retired_at   timestamptz,
    revoked_at   timestamptz,
    revoked_by   varchar(200)
);
create unique index ux_devsec_signing_key_active on devicesec.signing_key (status) where status = 'ACTIVE';

-- ---------- connector secrets (E34-HLR-05, E34-F04; contract §15.6) ----------
create table devicesec.secret (
    reference         varchar(80)   primary key,                      -- devsec:<uuid>
    scope             varchar(80)   not null,
    name              varchar(120)  not null,
    ciphertext        text,                                           -- encrypted (field level), context devsec:<ref>; null once destroyed
    bound_instances   varchar(2000),
    created_by        varchar(200)  not null,
    created_at        timestamptz   not null,
    rotated_by        varchar(200),
    rotated_at        timestamptz,
    last_resolved_at  timestamptz,
    destroyed_by      varchar(200),
    destroyed_at      timestamptz
);
create index ix_devsec_secret_scope on devicesec.secret (scope);

-- ---------- tamper-evident security events (E34-HLR-08, E34-F07; contract §15.9) – append only, hash chained ----------
create table devicesec.security_event (
    id                  bigserial     primary key,
    event_id            uuid          not null unique,
    type                varchar(60)   not null,
    severity            varchar(10)   not null check (severity in ('INFO','LOW','MEDIUM','HIGH','CRITICAL')),
    endpoint_id         varchar(64),
    device_instance_id  varchar(64),
    site_code           varchar(20),
    zone                varchar(30),
    actor               varchar(200)  not null,
    source_module       varchar(10)   not null,
    reason_code         varchar(60),
    remote_address      varchar(64),
    attributes          text          not null,                       -- PHI-free, allow-listed keys only (canonical JSON)
    occurred_at         timestamptz   not null,
    recorded_at         timestamptz   not null,
    prev_hash           varchar(64)   not null,
    hash                varchar(64)   not null
);
create index ix_devsec_event_type_time on devicesec.security_event (type, occurred_at);
create index ix_devsec_event_endpoint_time on devicesec.security_event (endpoint_id, occurred_at);
create index ix_devsec_event_remote_time on devicesec.security_event (remote_address, occurred_at);

create or replace function devicesec.reject_event_change() returns trigger language plpgsql as $$
begin
    raise exception 'devicesec.security_event is append-only';
end;
$$;
create trigger trg_devsec_event_append_only before update or delete on devicesec.security_event
    for each row execute function devicesec.reject_event_change();
create trigger trg_devsec_event_no_truncate before truncate on devicesec.security_event
    for each statement execute function devicesec.reject_event_change();

-- ---------- anomaly rules, alerts, traffic counters, isolation (E34-HLR-09, E34-F08; contract §15.8) ----------
create table devicesec.anomaly_rule (
    code               varchar(40)  primary key,
    description        varchar(500) not null,
    severity           varchar(10)  not null check (severity in ('INFO','LOW','MEDIUM','HIGH','CRITICAL')),
    enabled            boolean      not null,
    threshold          integer      not null check (threshold >= 1),
    window_minutes     integer      not null check (window_minutes between 1 and 1440),
    action             varchar(20)  not null check (action in ('ALERT','REJECT_ALERT','THROTTLE','ISOLATE','NOTIFY')),
    isolate_threshold  integer      check (isolate_threshold is null or isolate_threshold >= 1),
    updated_by         varchar(200) not null,
    updated_at         timestamptz  not null
);

create table devicesec.alert (
    id                uuid         primary key,
    rule_code         varchar(40)  not null references devicesec.anomaly_rule(code),
    severity          varchar(10)  not null,
    endpoint_id       varchar(64),
    subject_key       varchar(200) not null,                          -- endpoint id, source address or connector
    site_code         varchar(20),
    status            varchar(15)  not null check (status in ('OPEN','ACKNOWLEDGED','CLOSED')),
    detected_at       timestamptz  not null,
    last_seen_at      timestamptz  not null,
    occurrences       integer      not null,
    first_event_id    bigint,
    last_event_id     bigint,
    auto_action       varchar(20),
    isolation_due_at  timestamptz,
    isolated_at       timestamptz,
    ticket_ref        varchar(40),
    acknowledged_by   varchar(200),
    acknowledged_at   timestamptz,
    closed_by         varchar(200),
    closed_at         timestamptz,
    resolution        varchar(500),
    version           bigint       not null default 0
);
create index ix_devsec_alert_open on devicesec.alert (rule_code, subject_key) where status <> 'CLOSED';
create index ix_devsec_alert_isolation_due on devicesec.alert (isolation_due_at) where isolation_due_at is not null and isolated_at is null;

create table devicesec.traffic_minute (
    endpoint_id   varchar(64)  not null,
    minute_start  timestamptz  not null,
    requests      integer      not null,
    primary key (endpoint_id, minute_start)
);

create table devicesec.isolation (
    id            uuid         primary key,
    endpoint_id   varchar(64)  not null references devicesec.endpoint(endpoint_id),
    alert_id      uuid,
    reason_code   varchar(60)  not null,
    actor         varchar(200) not null,
    detected_at   timestamptz  not null,
    isolated_at   timestamptz  not null,
    incident_ref  varchar(20),
    released_at   timestamptz,
    released_by   varchar(200),
    release_note  varchar(500)
);
create index ix_devsec_isolation_endpoint on devicesec.isolation (endpoint_id, isolated_at);

-- ---------- incidents and evidence (compromise playbook) ----------
create sequence devicesec.incident_seq;
create table devicesec.incident (
    incident_ref            varchar(20)  primary key,              -- DSI-2026-000001
    endpoint_id             varchar(64)  not null,
    status                  varchar(10)  not null check (status in ('OPEN','CLOSED')),
    summary                 varchar(500),
    opened_by               varchar(200) not null,
    opened_at               timestamptz  not null,
    detected_at             timestamptz  not null,
    isolation_completed_at  timestamptz,
    closed_by               varchar(200),
    closed_at               timestamptz,
    resolution              varchar(500),
    version                 bigint       not null default 0
);
create table devicesec.incident_alert (
    incident_ref  varchar(20) not null references devicesec.incident(incident_ref),
    alert_id      uuid        not null,
    primary key (incident_ref, alert_id)
);
create table devicesec.incident_evidence (
    id            uuid         primary key,
    incident_ref  varchar(20)  not null references devicesec.incident(incident_ref),
    type          varchar(20)  not null check (type in ('RAW_MESSAGES','EVENTS','HEARTBEATS')),
    sha256        varchar(64)  not null,
    archive_id    varchar(100),
    description   varchar(300),
    added_by      varchar(200) not null,
    added_at      timestamptz  not null
);
create table devicesec.incident_timeline (
    id            uuid         primary key,
    incident_ref  varchar(20)  not null references devicesec.incident(incident_ref),
    at            timestamptz  not null,
    entry         varchar(300) not null,
    actor         varchar(200) not null
);
create index ix_devsec_incident_timeline on devicesec.incident_timeline (incident_ref, at);

-- ---------- vendor posture: compensating-control register (E34-HLR-07, E34-F06; contract §15.7) ----------
create table devicesec.compensating_control (
    id           uuid          primary key,
    code         varchar(40)   not null unique,
    title        varchar(200)  not null,
    description  varchar(1000) not null,
    applies_to   varchar(200)  not null,
    status       varchar(15)   not null check (status in ('ACTIVE','RETIRED')),
    owner        varchar(200)  not null,
    review_on    date          not null,
    created_by   varchar(200)  not null,
    created_at   timestamptz   not null
);

-- ---------- secure life-cycle, pen test, IEC 80001-1, DPIA, test pack (E34-HLR-06, -10, -11, -12; contract §15.10–15.12) ----------
create table devicesec.threat_model (
    id                  uuid         primary key,
    adapter_or_option   varchar(60)  not null,
    version             varchar(30)  not null,
    reviewed_on         date         not null,
    reviewer            varchar(200) not null,
    document_ref        varchar(200),
    unique (adapter_or_option, version)
);
create table devicesec.sbom_record (
    release       varchar(40)  primary key,
    document_id   varchar(200) not null,
    format        varchar(20)  not null check (format in ('CycloneDX','SPDX')),
    sha256        varchar(64),
    generated_on  date         not null,
    recorded_by   varchar(200) not null,
    recorded_at   timestamptz  not null
);
create table devicesec.vulnerability (
    id          varchar(60)  primary key,                           -- CVE / GHSA / internal id
    component   varchar(200) not null,
    severity    varchar(10)  not null check (severity in ('CRITICAL','HIGH','MEDIUM','LOW')),
    found_on    date         not null,
    due_on      date         not null,                              -- critical 7 d, high 30 d, medium 90 d, low 180 d
    status      varchar(15)  not null check (status in ('OPEN','FIXED','ACCEPTED')),
    fixed_on    date,
    note        varchar(500),
    updated_by  varchar(200) not null,
    updated_at  timestamptz  not null
);
create table devicesec.pentest_finding (
    ref         varchar(40)  primary key,
    severity    varchar(10)  not null check (severity in ('CRITICAL','HIGH','MEDIUM','LOW','INFO')),
    title       varchar(300) not null,
    status      varchar(10)  not null check (status in ('OPEN','FIXED','ACCEPTED')),
    engagement  varchar(100),
    found_on    date         not null,
    closed_on   date,
    updated_by  varchar(200) not null,
    updated_at  timestamptz  not null
);
create table devicesec.responsibility_agreement (
    id              smallint     primary key check (id = 1),
    version         varchar(30)  not null,
    parties         text         not null,                          -- JSON array of {party, role, signatory?}
    signed_on       date,
    review_cadence  varchar(30)  not null,
    updated_by      varchar(200) not null,
    updated_at      timestamptz  not null
);
create table devicesec.dpia (
    id           smallint     primary key check (id = 1),
    version      varchar(30)  not null,
    status       varchar(10)  not null check (status in ('PENDING','APPROVED')),
    approved_by  varchar(200),
    approved_at  timestamptz,
    updated_at   timestamptz  not null
);
create table devicesec.test_pack_run (
    id           uuid         primary key,
    version      varchar(30)  not null,
    environment  varchar(30)  not null,
    run_by       varchar(200) not null,
    run_at       timestamptz  not null,
    passed       integer      not null,
    failed       integer      not null,
    not_run      integer      not null
);
create table devicesec.test_pack_result (
    run_id           uuid         not null references devicesec.test_pack_run(id),
    script           varchar(80)  not null,
    control          varchar(20)  not null,
    outcome          varchar(10)  not null check (outcome in ('PASS','FAIL','NOT_RUN')),
    evidence_sha256  varchar(64),
    primary key (run_id, script, control)
);

-- ---------- seed: zones and conduits (reference network design, E34-BL-002) ----------
insert into devicesec.zone (code, name, description, default_policy) values
    ('DEVICE_VLAN',  'Medical-device VLAN / SSID', 'Medical devices and vendor PCs; deny-by-default, no internet, no direct path to the core', 'DENY'),
    ('GATEWAY_ZONE', 'Gateway zone (clinic DMZ)',  'Clinic gateways and Device Host agents; outbound-only mTLS to the core', 'DENY'),
    ('CLINIC_LAN',   'Clinic LAN / staff SSID',    'Nurse tablets and kiosks (managed devices, MDM)', 'DENY'),
    ('VENDOR_CLOUD', 'Vendor cloud (internet)',    'Vendor clouds sending webhooks or polled with OAuth2 client credentials', 'DENY'),
    ('CORE',         'Core platform',              'Integration engine and OH record (no inbound from device zones except listed conduits)', 'DENY')
on conflict (code) do nothing;

insert into devicesec.conduit (id, from_zone, to_zone, protocol, ports, direction, description) values
    ('C-DEV-GW-MLLP',  'DEVICE_VLAN',  'GATEWAY_ZONE', 'MLLP_TLS',     '2575',   'INBOUND',  'HL7 v2 over MLLP (TLS; plaintext only with a documented segmented-VLAN exception) from devices and data managers to the gateway'),
    ('C-DEV-GW-POCT',  'DEVICE_VLAN',  'GATEWAY_ZONE', 'MLLP_TLS',     '5001',   'INBOUND',  'POCT1-A2 XML from the POC data manager to the gateway'),
    ('C-DEV-GW-DICOM', 'DEVICE_VLAN',  'GATEWAY_ZONE', 'DICOM_TLS',    '2762',   'INBOUND',  'DICOM export drop / C-STORE to the gateway'),
    ('C-DEV-GW-BLE',   'DEVICE_VLAN',  'GATEWAY_ZONE', 'BLE',          'n/a',    'LOCAL',    'Bluetooth LE Secure Connections, bonded-device allow-list'),
    ('C-DEV-AGT-SER',  'DEVICE_VLAN',  'GATEWAY_ZONE', 'SERIAL',       'n/a',    'LOCAL',    'RS-232 / USB-CDC on the Device Host (USB VID/PID allow-list)'),
    ('C-GW-CORE',      'GATEWAY_ZONE', 'CORE',         'HTTPS_MTLS',   '443',    'OUTBOUND', 'Gateway / Device Host agent to the ingest API (mutual TLS + per-endpoint token)'),
    ('C-LAN-CORE',     'CLINIC_LAN',   'CORE',         'HTTPS_MTLS',   '443',    'OUTBOUND', 'Nurse tablet and kiosk to the ingest API (mutual TLS + per-endpoint token)'),
    ('C-VC-CORE',      'VENDOR_CLOUD', 'CORE',         'HTTPS_HMAC',   '443',    'INBOUND',  'Vendor webhooks (HMAC-SHA256 signature, 300 s replay window)'),
    ('C-CORE-VC',      'CORE',         'VENDOR_CLOUD', 'HTTPS_OAUTH2', '443',    'OUTBOUND', 'Engine polls vendor clouds with OAuth2 client credentials (egress allow-list)')
on conflict (id) do nothing;

insert into devicesec.site_hours (site_code, time_zone, opens, closes, days) values
    ('*', 'UTC', '06:00', '22:00', 'MON,TUE,WED,THU,FRI,SAT,SUN')
on conflict (site_code) do nothing;

-- ---------- seed: anomaly rules (contract §15.8 plus unknown endpoint, off-hours traffic, payload schema, clock skew) ----------
insert into devicesec.anomaly_rule (code, description, severity, enabled, threshold, window_minutes, action, isolate_threshold, updated_by, updated_at) values
    ('NEW_DEVICE_IDENTITY',        'First authentication of an endpoint, or first message from an unknown device serial behind a known gateway', 'MEDIUM', true, 1, 60, 'ALERT', null, 'migration', now()),
    ('UNKNOWN_ENDPOINT',           'Authentication attempts with an endpoint id that does not exist (per source address)', 'MEDIUM', true, 3, 15, 'ALERT', null, 'migration', now()),
    ('FAILED_AUTH_BURST',          'Failed authentications for one endpoint id or source address within the window; auto-isolation of the endpoint at the isolation threshold', 'HIGH', true, 10, 5, 'ISOLATE', 30, 'migration', now()),
    ('MESSAGE_RATE_SPIKE',         'Requests per minute above 5x the 7-day p95 or above the ceiling (threshold = ceiling per minute); sustained for the window -> throttle', 'MEDIUM', true, 600, 10, 'THROTTLE', null, 'migration', now()),
    ('UNEXPECTED_PROTOCOL',        'Message kind / path not allowed for the endpoint type', 'HIGH', true, 1, 60, 'REJECT_ALERT', null, 'migration', now()),
    ('UNEXPECTED_PORT',            'Gateway heartbeat reports a listener / port outside its conduits', 'HIGH', true, 1, 60, 'ALERT', null, 'migration', now()),
    ('ZONE_VIOLATION',             'Endpoint called from an address outside its zone / CIDR policy, or a device tried to reach the core directly', 'HIGH', true, 1, 60, 'REJECT_ALERT', null, 'migration', now()),
    ('CERTIFICATE_ANOMALY',        'Thumbprint change without renewal, unexpected issuer or expired certificate presented', 'HIGH', true, 1, 60, 'REJECT_ALERT', null, 'migration', now()),
    ('CERTIFICATE_EXPIRING',       'Endpoint certificate expires within the warning period (threshold = days)', 'LOW', true, 30, 1440, 'NOTIFY', null, 'migration', now()),
    ('HARDENING_DRIFT',            'Heartbeat hardening status DRIFT or FAIL', 'MEDIUM', true, 1, 60, 'ALERT', null, 'migration', now()),
    ('WEBHOOK_SIGNATURE_FAILURES', 'Webhook signature failures per connector within the window', 'HIGH', true, 5, 10, 'ALERT', null, 'migration', now()),
    ('OFF_HOURS_TRAFFIC',          'Data traffic from an endpoint outside the clinic hours of its site', 'LOW', true, 1, 1440, 'ALERT', null, 'migration', now()),
    ('OFF_HOURS_CONFIG_ACCESS',    'Configuration export or secret metadata access outside clinic hours', 'LOW', true, 1, 1440, 'ALERT', null, 'migration', now()),
    ('PAYLOAD_SCHEMA_ANOMALY',     'Unparseable or profile-violating payloads from one endpoint within the window', 'MEDIUM', true, 5, 10, 'ALERT', null, 'migration', now()),
    ('CLOCK_SKEW',                 'Edge or device clock skew above the configured maximum', 'LOW', true, 1, 60, 'ALERT', null, 'migration', now())
on conflict (code) do nothing;

-- ---------- seed: governance records ----------
insert into devicesec.responsibility_agreement (id, version, parties, signed_on, review_cadence, updated_by, updated_at) values
    (1, '0.1-draft', '[{"party":"Occupational Health unit","role":"Responsible organisation (medical IT-network owner)"},{"party":"Group IT","role":"IT-network operator"},{"party":"Group Security","role":"Security operations and incident response"},{"party":"Device vendors","role":"Manufacturer disclosure (MDS2 / SBOM), patches, brokered remote support"}]', null, 'ANNUAL', 'migration', now())
on conflict (id) do nothing;
insert into devicesec.dpia (id, version, status, updated_at) values (1, '2026.1', 'PENDING', now()) on conflict (id) do nothing;

-- ---------- configuration (E18) ----------
insert into masterdata.config_entry (config_key, config_value, description, updated_by, updated_at) values
    ('device.security.enrolment-token-hours', '72', 'Validity of one-time enrolment tokens (hours, at most 72)', 'migration', now()),
    ('device.security.endpoint-token-days', '90', 'Validity of per-endpoint tokens before rotation is required (days, at most 90)', 'migration', now()),
    ('device.security.token-grace-minutes', '15', 'Previous endpoint token stays valid this many minutes after a rotation', 'migration', now()),
    ('device.security.soc-email', 'soc@wellnessz.local', 'Mailbox of Security Operations on duty for device security alerts (content-free notifications)', 'migration', now())
on conflict (config_key) do nothing;

-- ---------- grants (least privilege; append-only evidence gets no update / delete) ----------
grant select, insert, update on devicesec.endpoint to ${app_role};
grant select, insert, delete on devicesec.endpoint_instance to ${app_role};
grant select, insert, update on devicesec.enrolment_token to ${app_role};
grant select on devicesec.zone, devicesec.conduit to ${app_role};
grant select, insert, delete on devicesec.zone_cidr to ${app_role};
grant select, insert, update on devicesec.site_hours to ${app_role};
grant select, insert, update on devicesec.signing_key to ${app_role};
grant select, insert, update on devicesec.secret to ${app_role};
grant select, insert on devicesec.security_event to ${app_role};
grant usage, select on sequence devicesec.security_event_id_seq to ${app_role};
grant select, update on devicesec.anomaly_rule to ${app_role};
grant select, insert, update on devicesec.alert to ${app_role};
grant select, insert, update, delete on devicesec.traffic_minute to ${app_role};
grant select, insert, update on devicesec.isolation to ${app_role};
grant select, insert, update on devicesec.incident to ${app_role};
grant usage, select on sequence devicesec.incident_seq to ${app_role};
grant select, insert on devicesec.incident_alert, devicesec.incident_evidence, devicesec.incident_timeline to ${app_role};
grant select, insert, update on devicesec.compensating_control to ${app_role};
grant select, insert, update on devicesec.threat_model, devicesec.sbom_record, devicesec.vulnerability, devicesec.pentest_finding to ${app_role};
grant select, update on devicesec.responsibility_agreement, devicesec.dpia to ${app_role};
grant select, insert on devicesec.test_pack_run, devicesec.test_pack_result to ${app_role};
