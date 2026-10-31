-- Demo data for E32 – device catalogue, administration & provisioning console (local / test profiles only; synthetic).
-- Sites LHR (stations LHR-OH-ROOM-1, LHR-OH-ROOM-2, LHR-OH-BOOTH-1) and DXB (outstation), a test bench, the device console
-- staff (notification recipients) and the on-duty rota. The catalogue itself is seeded by CatalogueSeedLoader; the bundled
-- driver packages, the published demo configurations DEV-000001 … DEV-000008, QC history and certification PDFs are created
-- by E32DemoSeeder at start-up (they need the driver-package signing service and the PDF renderer).

insert into devicecat.station (station_code, site_code, room_code, name, kind, test_site, active) values
    ('LHR-OH-ROOM-1',  'LHR',  'LHR-OH-ROOM-1',  'LHR OH centre – examination room 1', 'ROOM',    false, true),
    ('LHR-OH-ROOM-2',  'LHR',  'LHR-OH-ROOM-2',  'LHR OH centre – examination room 2', 'ROOM',    false, true),
    ('LHR-OH-BOOTH-1', 'LHR',  'LHR-OH-BOOTH-1', 'LHR OH centre – audiometric booth 1', 'BOOTH',  false, true),
    ('LHR-OH-KIOSK-1', 'LHR',  'LHR-OH-ROOM-2',  'LHR OH centre – self-measurement kiosk', 'KIOSK', false, true),
    ('DXB-OUT-ROOM-1', 'DXB',  'DXB-OUT-ROOM-1', 'DXB outstation – clinic room', 'ROOM',            false, true),
    ('TST-BENCH-1',    'TEST', 'TST-LAB',        'Integration test bench 1', 'STATION',             true,  true),
    ('TST-BENCH-2',    'TEST', 'TST-LAB',        'Integration test bench 2', 'STATION',             true,  true)
on conflict (station_code) do nothing;

insert into masterdata.staff_member (username, display_name, email, roles, site_codes, languages, specialties, caseload_cap) values
    ('deviceadmin1',    'Dana Device-Admin',    'deviceadmin1@wellnessz.local',    'DEVICE_ADMIN',       'LHR,DXB', 'en',    'DEVICES', 0),
    ('deviceapprover1', 'Dev Approver',         'deviceapprover1@wellnessz.local', 'DEVICE_APPROVER',    'LHR,DXB', 'en',    'DEVICES', 0),
    ('inttester1',      'Ian Integration',      'inttester1@wellnessz.local',      'INTEGRATION_TESTER', 'TEST',    'en',    'DEVICES', 0),
    ('secops1',         'Sam Security-Ops',     'secops1@wellnessz.local',         'SECURITY_OPS',       'LHR,DXB', 'en,ar', 'SECURITY', 0)
on conflict (username) do nothing;

insert into devicecat.on_duty (id, site_code, username, from_at, to_at, updated_by, updated_at) values
    ('e32d0000-0000-4000-8000-000000000001', '*',   'deviceadmin1', now() - interval '1 day', now() + interval '3650 days', 'demo', now()),
    ('e32d0000-0000-4000-8000-000000000002', 'DXB', 'deviceadmin1', now() - interval '1 day', now() + interval '3650 days', 'demo', now())
on conflict (id) do nothing;
