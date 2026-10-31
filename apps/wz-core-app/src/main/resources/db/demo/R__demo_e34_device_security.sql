-- Demo data for E34 endpoint & integration security – local and test profiles ONLY (flyway location classpath:db/demo).
-- Idempotent (fixed ids e34de000-…, on conflict do nothing). Endpoint identities with their TEST-ONLY tokens and the demo
-- certificate thumbprints are written by E34DemoSeeder (the token hash depends on the environment's pepper); the plaintext
-- demo tokens are documented there and in tools/device-security/README.md as non-secrets for the local stack.
--
--   gateway-zone address ranges (all sites)  loopback and RFC 1918 ranges – the dev stack has no clinic network
--   clinic hours                            LHR 07:00–20:00 Mon–Sat (Europe/London), DXB 06:00–22:00 daily (Asia/Dubai)
--   secure life-cycle                       threat models P1–P10, SBOM of 0.6.0, one open high vulnerability
--   penetration test                        engagement PT-2026-Q4: one fixed high, one open medium finding
--   compensating controls                   legacy audiometer vendor PC (Windows 7), shared-account POC data manager

insert into devicesec.zone_cidr (id, site_code, zone, cidr, created_by, created_at) values
 ('e34de000-0000-4000-8000-000000000001', '*', 'GATEWAY_ZONE', '127.0.0.0/8',    'demo', now()),
 ('e34de000-0000-4000-8000-000000000002', '*', 'GATEWAY_ZONE', '::1/128',        'demo', now()),
 ('e34de000-0000-4000-8000-000000000003', '*', 'GATEWAY_ZONE', '10.0.0.0/8',     'demo', now()),
 ('e34de000-0000-4000-8000-000000000004', '*', 'GATEWAY_ZONE', '172.16.0.0/12',  'demo', now()),
 ('e34de000-0000-4000-8000-000000000005', '*', 'GATEWAY_ZONE', '192.168.0.0/16', 'demo', now())
on conflict do nothing;

insert into devicesec.site_hours (site_code, time_zone, opens, closes, days) values
 ('LHR', 'Europe/London', '07:00', '20:00', 'MON,TUE,WED,THU,FRI,SAT'),
 ('DXB', 'Asia/Dubai',    '06:00', '22:00', 'MON,TUE,WED,THU,FRI,SAT,SUN')
on conflict (site_code) do nothing;

insert into devicesec.threat_model (id, adapter_or_option, version, reviewed_on, reviewer, document_ref) values
 ('e34de000-0000-4000-8000-000000000101', 'P1_SERIAL_USB',      '1.0', current_date - 20, 'secops1', 'TM-DEV-P1-1.0'),
 ('e34de000-0000-4000-8000-000000000102', 'P2_VENDOR_SOFTWARE', '1.0', current_date - 20, 'secops1', 'TM-DEV-P2-1.0'),
 ('e34de000-0000-4000-8000-000000000103', 'P3_BLUETOOTH',       '1.0', current_date - 18, 'secops1', 'TM-DEV-P3-1.0'),
 ('e34de000-0000-4000-8000-000000000104', 'P4_HL7_MLLP',        '1.0', current_date - 18, 'secops1', 'TM-DEV-P4-1.0'),
 ('e34de000-0000-4000-8000-000000000105', 'P5_POCT1A',          '1.0', current_date - 15, 'secops1', 'TM-DEV-P5-1.0'),
 ('e34de000-0000-4000-8000-000000000106', 'P6_CLOUD_API',       '1.0', current_date - 15, 'secops1', 'TM-DEV-P6-1.0'),
 ('e34de000-0000-4000-8000-000000000107', 'P7_DICOM',           '1.0', current_date - 12, 'secops1', 'TM-DEV-P7-1.0'),
 ('e34de000-0000-4000-8000-000000000108', 'P8_FHIR',            '1.0', current_date - 12, 'secops1', 'TM-DEV-P8-1.0'),
 ('e34de000-0000-4000-8000-000000000109', 'GATEWAY_EDGE',       '1.0', current_date - 10, 'secops1', 'TM-DEV-GW-1.0')
on conflict do nothing;

insert into devicesec.sbom_record (release, document_id, format, sha256, generated_on, recorded_by, recorded_at) values
 ('0.6.0', 'urn:uuid:3e671687-395b-41f5-a30f-a58921a69b79', 'CycloneDX', null, current_date - 5, 'secops1', now())
on conflict (release) do nothing;

insert into devicesec.vulnerability (id, component, severity, found_on, due_on, status, note, updated_by, updated_at) values
 ('DEMO-VULN-0001', 'gateway base image (synthetic example)', 'HIGH', current_date - 6, current_date + 24, 'OPEN',
  'Synthetic demo record – patched image scheduled in the next maintenance window', 'secops1', now())
on conflict (id) do nothing;

insert into devicesec.pentest_finding (ref, severity, title, status, engagement, found_on, closed_on, updated_by, updated_at) values
 ('PT-2026-Q4-01', 'HIGH',   'Verbose error detail on a console endpoint (synthetic demo finding)', 'FIXED', 'PT-2026-Q4', current_date - 30, current_date - 21, 'secops1', now()),
 ('PT-2026-Q4-02', 'MEDIUM', 'Missing rate limit on a diagnostics read endpoint (synthetic demo finding)', 'OPEN', 'PT-2026-Q4', current_date - 30, null, 'secops1', now())
on conflict (ref) do nothing;

insert into devicesec.compensating_control (id, code, title, description, applies_to, status, owner, review_on, created_by, created_at) values
 ('e34de000-0000-4000-8000-000000000201', 'CC-LEGACY-PC-01', 'Legacy audiometer vendor PC behind a Device Host agent',
  'Unsupported operating system: PC kept in the device VLAN, no internet, outbound-only Device Host agent to the core, EDR reported in the heartbeat, USB allow-list, brokered and recorded vendor remote support only',
  'MD-015 audiometer vendor PC (LHR)', 'ACTIVE', 'Security Operations', current_date + 90, 'secops1', now()),
 ('e34de000-0000-4000-8000-000000000202', 'CC-POC-DM-01', 'POC data manager with a shared local account',
  'Data manager reachable only from the gateway zone conduit (POCT1-A2 over TLS); local account password held in the vault and rotated quarterly',
  'MD-081 POC data manager (LHR)', 'ACTIVE', 'Security Operations', current_date + 60, 'secops1', now())
on conflict do nothing;
