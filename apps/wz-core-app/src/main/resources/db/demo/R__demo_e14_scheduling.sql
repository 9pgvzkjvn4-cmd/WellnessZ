-- Demo data for E14 scheduling & clinic operations – local and test profiles only (flyway location classpath:db/demo).
-- Idempotent (fixed ids, on conflict do nothing); dates are relative to current_date / now() at migration time.
-- Fixed ids the end-to-end scripts rely on:
--   equipment  e14e0000-0000-4000-8000-0000000000{01 AUD-LHR-01, 02 AUD-LHR-02, 03 SPIRO-HQ-01, 04 ECG-HQ-01, 05 VIS-LHR-01, 06 FRIDGE-HQ-01, 07 FRIDGE-LHR-01}
--   appointment e14a0000-0000-4000-8000-000000000001 (E1001 VACCINATION at HQ-MC, today, 30 minutes after migration – app check-in open)
--   campaign   e14c0000-0000-4000-8000-000000000001 ("LHR ramp audiometry day")

-- ---------- site resources ----------
insert into scheduling.resource (id, site_code, type, code, name, username, room_type, equipment_id, active, updated_at) values
    ('e14b0000-0000-4000-8000-000000000001', 'HQ-MC',       'CLINICIAN', 'nurse1',      'Nina Nurse',              'nurse1',      null,          null, true, now()),
    ('e14b0000-0000-4000-8000-000000000002', 'HQ-MC',       'CLINICIAN', 'physician1',  'Dr Phil Physician',       'physician1',  null,          null, true, now()),
    ('e14b0000-0000-4000-8000-000000000003', 'HQ-MC',       'ROOM',      'HQ-C1',       'Consulting room 1',       null,          'CONSULT',     null, true, now()),
    ('e14b0000-0000-4000-8000-000000000004', 'HQ-MC',       'ROOM',      'HQ-C2',       'Consulting room 2',       null,          'CONSULT',     null, true, now()),
    ('e14b0000-0000-4000-8000-000000000005', 'HQ-MC',       'ROOM',      'HQ-VAC',      'Vaccination room',        null,          'VACCINATION', null, true, now()),
    ('e14b0000-0000-4000-8000-000000000006', 'HQ-MC',       'ROOM',      'HQ-TR',       'Treatment room',          null,          'TREATMENT',   null, true, now()),
    ('e14b0000-0000-4000-8000-000000000007', 'HQ-MC',       'ROOM',      'HQ-TELE',     'Telehealth booth',        null,          'TELEHEALTH',  null, true, now()),
    ('e14b0000-0000-4000-8000-000000000010', 'LHR-AIRSIDE', 'CLINICIAN', 'nurse2',      'Noah Nurse',              'nurse2',      null,          null, true, now()),
    ('e14b0000-0000-4000-8000-000000000011', 'LHR-AIRSIDE', 'ROOM',      'LHRA-C1',     'Airside consulting room', null,          'CONSULT',     null, true, now()),
    ('e14b0000-0000-4000-8000-000000000012', 'LHR-AIRSIDE', 'ROOM',      'LHRA-VAC',    'Airside vaccination bay', null,          'VACCINATION', null, true, now()),
    ('e14b0000-0000-4000-8000-000000000020', 'LHR-CAT',     'CLINICIAN', 'nurse1',      'Nina Nurse',              'nurse1',      null,          null, true, now()),
    ('e14b0000-0000-4000-8000-000000000021', 'LHR-CAT',     'ROOM',      'LHRC-C1',     'Catering medical room',   null,          'CONSULT',     null, true, now()),
    ('e14b0000-0000-4000-8000-000000000022', 'LHR-CAT',     'ROOM',      'LHRC-VAC',    'Catering vaccination bay', null,         'VACCINATION', null, true, now()),
    ('e14b0000-0000-4000-8000-000000000030', 'LHR-CARGO',   'CLINICIAN', 'clinician1',  'Clara Clinician',         'clinician1',  null,          null, true, now()),
    ('e14b0000-0000-4000-8000-000000000031', 'LHR-CARGO',   'ROOM',      'LHRG-AUD1',   'Audiometry booth',        null,          'AUDIO_BOOTH', null, true, now()),
    ('e14b0000-0000-4000-8000-000000000032', 'LHR-CARGO',   'ROOM',      'LHRG-C1',     'Cargo consulting room',   null,          'CONSULT',     null, true, now()),
    ('e14b0000-0000-4000-8000-000000000040', 'DXB-OUT',     'CLINICIAN', 'ame1',        'Dr Ava AME',              'ame1',        null,          null, true, now()),
    ('e14b0000-0000-4000-8000-000000000041', 'DXB-OUT',     'ROOM',      'DXB-C1',      'Outstation consulting room', null,       'CONSULT',     null, true, now()),
    ('e14b0000-0000-4000-8000-000000000050', 'MOBILE-1',    'ROOM',      'MOB-AUD1',    'Mobile audiometry booth 1', null,        'AUDIO_BOOTH', null, true, now()),
    ('e14b0000-0000-4000-8000-000000000051', 'MOBILE-1',    'ROOM',      'MOB-AUD2',    'Mobile audiometry booth 2', null,        'AUDIO_BOOTH', null, true, now())
on conflict do nothing;

-- ---------- weekly diary templates (local site time) ----------
insert into scheduling.diary_template (id, site_code, weekday, start_time, end_time, clinician_username, room_code, appointment_types, telehealth, updated_by, updated_at)
select ('e14d0000-0000-4000-8000-0000000001' || lpad(d::text, 2, '0'))::uuid, 'HQ-MC', d,
       '08:00', case when d in (2, 4) then '12:00'::time else '16:00'::time end, 'nurse1', 'HQ-C1',
       'NURSE_REVIEW,VACCINATION,SPIROMETRY,ECG,GENERAL_OH,FOOD_HANDLER_CLEARANCE,TELEHEALTH_CONSULT', false, 'demo', now()
  from generate_series(1, 5) d
on conflict do nothing;
insert into scheduling.diary_template (id, site_code, weekday, start_time, end_time, clinician_username, room_code, appointment_types, telehealth, updated_by, updated_at)
select ('e14d0000-0000-4000-8000-0000000002' || lpad(d::text, 2, '0'))::uuid, 'HQ-MC', d, '09:00', '17:00', 'physician1', 'HQ-C2',
       'PHYSICIAN_REVIEW,GENERAL_OH,PRE_EMPLOYMENT,RTW_REVIEW,ABSENCE_REVIEW,ECG,TELEHEALTH_CONSULT', true, 'demo', now()
  from generate_series(1, 5) d
on conflict do nothing;
insert into scheduling.diary_template (id, site_code, weekday, start_time, end_time, clinician_username, room_code, appointment_types, telehealth, updated_by, updated_at)
select ('e14d0000-0000-4000-8000-0000000003' || lpad(d::text, 2, '0'))::uuid, 'LHR-AIRSIDE', d, '07:00', '15:00', 'nurse2', 'LHRA-C1',
       'NURSE_REVIEW,VACCINATION,VISION,GENERAL_OH,FOOD_HANDLER_CLEARANCE,RTW_REVIEW', false, 'demo', now()
  from generate_series(1, 5) d
on conflict do nothing;
insert into scheduling.diary_template (id, site_code, weekday, start_time, end_time, clinician_username, room_code, appointment_types, telehealth, updated_by, updated_at)
select ('e14d0000-0000-4000-8000-0000000004' || lpad(d::text, 2, '0'))::uuid, 'LHR-CAT', d, '13:00', '16:00', 'nurse1', 'LHRC-C1',
       'FOOD_HANDLER_CLEARANCE,VACCINATION,NURSE_REVIEW', false, 'demo', now()
  from (values (2), (4)) as t(d)
on conflict do nothing;
insert into scheduling.diary_template (id, site_code, weekday, start_time, end_time, clinician_username, room_code, appointment_types, telehealth, updated_by, updated_at)
select ('e14d0000-0000-4000-8000-0000000005' || lpad(d::text, 2, '0'))::uuid, 'LHR-CARGO', d, '08:00', '14:00', 'clinician1', 'LHRG-AUD1',
       'AUDIOMETRY,SURVEILLANCE_PANEL,NURSE_REVIEW,GENERAL_OH', false, 'demo', now()
  from (values (1), (3), (5)) as t(d)
on conflict do nothing;
insert into scheduling.diary_template (id, site_code, weekday, start_time, end_time, clinician_username, room_code, appointment_types, telehealth, updated_by, updated_at)
select ('e14d0000-0000-4000-8000-0000000006' || lpad(d::text, 2, '0'))::uuid, 'DXB-OUT', d, '09:00', '13:00', 'ame1', 'DXB-C1',
       'CREW_AEROMEDICAL', false, 'demo', now()
  from (values (1), (3)) as t(d)
on conflict do nothing;

-- ---------- equipment register ----------
insert into scheduling.equipment (id, asset_tag, device_id, category, model, serial_number, site_code, status, calibration_interval_months,
                                  last_calibrated_on, calibration_due_on, last_certificate_number, min_temp_c, max_temp_c, created_at, updated_at) values
    ('e14e0000-0000-4000-8000-000000000001', 'AUD-LHR-01',    'AUD-LHR-01',    'AUDIOMETER',      'Demo audiometer 300',   'AU300-1001', 'LHR-CARGO',   'IN_SERVICE', 12, current_date - 60,  current_date + 305, 'CAL-AUD-LHR-01-A', null, null, now(), now()),
    ('e14e0000-0000-4000-8000-000000000002', 'AUD-LHR-02',    'AUD-LHR-02',    'AUDIOMETER',      'Demo audiometer 300',   'AU300-1002', 'LHR-CARGO',   'IN_SERVICE', 12, current_date - 400, current_date - 35,  'CAL-AUD-LHR-02-A', null, null, now(), now()),
    ('e14e0000-0000-4000-8000-000000000003', 'SPIRO-HQ-01',   'SPIRO-HQ-01',   'SPIROMETER',      'Demo spirometer S2',    'SP2-2001',   'HQ-MC',       'IN_SERVICE', 12, current_date - 345, current_date + 20,  'CAL-SPIRO-HQ-01-A', null, null, now(), now()),
    ('e14e0000-0000-4000-8000-000000000004', 'ECG-HQ-01',     'ECG-HQ-01',     'ECG',             'Demo 12-lead ECG',      'ECG12-3001', 'HQ-MC',       'IN_SERVICE', 12, current_date - 165, current_date + 200, 'CAL-ECG-HQ-01-A', null, null, now(), now()),
    ('e14e0000-0000-4000-8000-000000000005', 'VIS-LHR-01',    'VIS-LHR-01',    'VISION_SCREENER', 'Demo vision screener',  'VS-4001',    'LHR-AIRSIDE', 'IN_SERVICE', 24, current_date - 580, current_date + 150, 'CAL-VIS-LHR-01-A', null, null, now(), now()),
    ('e14e0000-0000-4000-8000-000000000006', 'FRIDGE-HQ-01',  'FRIDGE-HQ-01',  'VACCINE_FRIDGE',  'Demo vaccine fridge 90', 'VF-5001',   'HQ-MC',       'IN_SERVICE', 12, current_date - 265, current_date + 100, 'CAL-FRIDGE-HQ-01-A', 2.00, 8.00, now(), now()),
    ('e14e0000-0000-4000-8000-000000000007', 'FRIDGE-LHR-01', 'FRIDGE-LHR-01', 'VACCINE_FRIDGE',  'Demo vaccine fridge 90', 'VF-5002',   'LHR-AIRSIDE', 'IN_SERVICE', 12, current_date - 100, current_date + 265, 'CAL-FRIDGE-LHR-01-A', 2.00, 8.00, now(), now())
on conflict do nothing;

insert into scheduling.resource (id, site_code, type, code, name, username, room_type, equipment_id, active, updated_at) values
    ('e14b0000-0000-4000-8000-000000000060', 'LHR-CARGO',   'DEVICE', 'AUD-LHR-01',  'Audiometer 1',     null, null, 'e14e0000-0000-4000-8000-000000000001', true, now()),
    ('e14b0000-0000-4000-8000-000000000061', 'LHR-CARGO',   'DEVICE', 'AUD-LHR-02',  'Audiometer 2',     null, null, 'e14e0000-0000-4000-8000-000000000002', true, now()),
    ('e14b0000-0000-4000-8000-000000000062', 'HQ-MC',       'DEVICE', 'SPIRO-HQ-01', 'Spirometer',       null, null, 'e14e0000-0000-4000-8000-000000000003', true, now()),
    ('e14b0000-0000-4000-8000-000000000063', 'HQ-MC',       'DEVICE', 'ECG-HQ-01',   'ECG',              null, null, 'e14e0000-0000-4000-8000-000000000004', true, now()),
    ('e14b0000-0000-4000-8000-000000000064', 'LHR-AIRSIDE', 'DEVICE', 'VIS-LHR-01',  'Vision screener',  null, null, 'e14e0000-0000-4000-8000-000000000005', true, now())
on conflict do nothing;

insert into scheduling.calibration (id, equipment_id, performed_on, vendor, certificate_number, result, next_due_on, recorded_by, recorded_at)
select ('e14f0000-0000-4000-8000-0000000000' || lpad(n::text, 2, '0'))::uuid, e.id, e.last_calibrated_on, 'Demo Biomedical Services Ltd',
       e.last_certificate_number, 'PASS', e.calibration_due_on, 'biomed1', now()
  from (select row_number() over (order by asset_tag) as n, * from scheduling.equipment where id::text like 'e14e0000-%') e
on conflict do nothing;

-- ---------- cold-chain readings: every 4 hours for 3 days; FRIDGE-HQ-01 had an excursion yesterday night (still OPEN) ----------
insert into scheduling.temperature_reading (id, equipment_id, recorded_at, temperature_c, source, excursion, recorded_by)
select md5('demo-fridge-hq-' || h)::uuid, 'e14e0000-0000-4000-8000-000000000006',
       date_trunc('hour', now()) - make_interval(hours => h),
       case when h in (26, 22) then 9.40 else 4.50 + (h % 3) * 0.40 end, 'IOT', h in (26, 22), 'iot-gateway'
  from generate_series(2, 72, 4) h
on conflict do nothing;
insert into scheduling.temperature_reading (id, equipment_id, recorded_at, temperature_c, source, excursion, recorded_by)
select md5('demo-fridge-lhr-' || h)::uuid, 'e14e0000-0000-4000-8000-000000000007',
       date_trunc('hour', now()) - make_interval(hours => h), 4.20 + (h % 4) * 0.30, 'IOT', false, 'iot-gateway'
  from generate_series(2, 72, 4) h
on conflict do nothing;
insert into scheduling.excursion (id, equipment_id, started_at, ended_at, min_c, max_c, status)
values ('e14f1000-0000-4000-8000-000000000001', 'e14e0000-0000-4000-8000-000000000006',
        date_trunc('hour', now()) - interval '26 hours', date_trunc('hour', now()) - interval '18 hours', 9.40, 9.40, 'OPEN')
on conflict do nothing;

-- ---------- campaign "LHR ramp audiometry day": yesterday (attendance recorded) and next week ----------
insert into scheduling.campaign (id, name, campaign_type, site_code, appointment_type, days, day_start, day_end, slot_minutes, stations,
                                 device_kit, staff, target_count, status, created_by, created_at, updated_at) values
    ('e14c0000-0000-4000-8000-000000000001', 'LHR ramp audiometry day', 'SURVEILLANCE', 'MOBILE-1', 'AUDIOMETRY',
     (current_date - 1)::text || ',' || (current_date + 7)::text, '08:00', '16:00', 20, 4,
     'e14e0000-0000-4000-8000-000000000001', 'nurse2,clinician1', 150, 'IN_PROGRESS', 'scheduler1', now(), now())
on conflict do nothing;

insert into scheduling.campaign_slot (id, campaign_id, day, station, starts_at, ends_at)
select md5('demo-campaign-slot-' || d.k || '-' || s || '-' || st)::uuid, 'e14c0000-0000-4000-8000-000000000001', d.day, st,
       ((d.day + time '08:00') + make_interval(mins => s * 20)) at time zone 'Europe/London',
       ((d.day + time '08:00') + make_interval(mins => s * 20 + 20)) at time zone 'Europe/London'
  from (values (1, current_date - 1), (2, current_date + 7)) as d(k, day), generate_series(0, 23) s, generate_series(1, 4) st
on conflict do nothing;

-- ---------- appointments ----------
insert into scheduling.appointment (id, employee_id, appointment_type, site_code, starts_at, ends_at, status, clinician_username, room_code,
                                    device_id, campaign_id, source_type, telehealth, ticket, checked_in_at, called_at, started_at, completed_at,
                                    booked_by, created_at, updated_at) values
    -- E1001 (employee1): vaccination at HQ today, 30 minutes after the demo load → app check-in is open
    ('e14a0000-0000-4000-8000-000000000001', 'E1001', 'VACCINATION', 'HQ-MC',
     date_trunc('minute', now()) + interval '30 minutes', date_trunc('minute', now()) + interval '40 minutes', 'BOOKED', 'nurse1', 'HQ-VAC',
     null, null, 'MANUAL', false, null, null, null, null, null, 'scheduler1', now() - interval '3 days', now()),
    -- other HQ appointments today
    ('e14a0000-0000-4000-8000-000000000002', 'E1005', 'GENERAL_OH', 'HQ-MC',
     (current_date + time '09:30') at time zone 'Europe/London', (current_date + time '10:00') at time zone 'Europe/London', 'ATTENDED', 'physician1', 'HQ-C2',
     null, null, 'MANUAL', false, 'A001', (current_date + time '09:20') at time zone 'Europe/London', (current_date + time '09:31') at time zone 'Europe/London',
     (current_date + time '09:32') at time zone 'Europe/London', (current_date + time '09:58') at time zone 'Europe/London', 'scheduler1', now() - interval '5 days', now()),
    ('e14a0000-0000-4000-8000-000000000003', 'E1002', 'PHYSICIAN_REVIEW', 'HQ-MC',
     (current_date + time '15:00') at time zone 'Europe/London', (current_date + time '15:30') at time zone 'Europe/London', 'BOOKED', 'physician1', 'HQ-C2',
     null, null, 'MANUAL', false, null, null, null, null, null, 'scheduler1', now() - interval '4 days', now()),
    ('e14a0000-0000-4000-8000-000000000004', 'E1003', 'TELEHEALTH_CONSULT', 'HQ-MC',
     (current_date + 1 + time '10:00') at time zone 'Europe/London', (current_date + 1 + time '10:20') at time zone 'Europe/London', 'BOOKED', 'physician1', 'HQ-C2',
     null, null, 'MANUAL', true, null, null, null, null, null, 'scheduler1', now() - interval '2 days', now()),
    -- E1004 (ramp agent): audiometry at the cargo clinic in three days
    ('e14a0000-0000-4000-8000-000000000005', 'E1004', 'AUDIOMETRY', 'LHR-CARGO',
     (current_date + 3 + time '09:00') at time zone 'Europe/London', (current_date + 3 + time '09:20') at time zone 'Europe/London', 'BOOKED', 'clinician1', 'LHRG-AUD1',
     'AUD-LHR-01', null, 'MANUAL', false, null, null, null, null, null, 'scheduler1', now() - interval '1 day', now()),
    -- campaign: yesterday one attended and one no-show; next week two booked
    ('e14a0000-0000-4000-8000-000000000011', 'E1006', 'AUDIOMETRY', 'MOBILE-1',
     (current_date - 1 + time '08:00') at time zone 'Europe/London', (current_date - 1 + time '08:20') at time zone 'Europe/London', 'ATTENDED', null, null,
     'AUD-LHR-01', 'e14c0000-0000-4000-8000-000000000001', 'MANUAL', false, 'A001', (current_date - 1 + time '07:55') at time zone 'Europe/London',
     (current_date - 1 + time '08:00') at time zone 'Europe/London', (current_date - 1 + time '08:01') at time zone 'Europe/London',
     (current_date - 1 + time '08:19') at time zone 'Europe/London', 'scheduler1', now() - interval '10 days', now()),
    ('e14a0000-0000-4000-8000-000000000012', 'E1007', 'AUDIOMETRY', 'MOBILE-1',
     (current_date - 1 + time '08:00') at time zone 'Europe/London', (current_date - 1 + time '08:20') at time zone 'Europe/London', 'NO_SHOW', null, null,
     'AUD-LHR-01', 'e14c0000-0000-4000-8000-000000000001', 'MANUAL', false, null, null, null, null, null, 'scheduler1', now() - interval '10 days', now()),
    ('e14a0000-0000-4000-8000-000000000013', 'E1008', 'AUDIOMETRY', 'MOBILE-1',
     (current_date + 7 + time '08:00') at time zone 'Europe/London', (current_date + 7 + time '08:20') at time zone 'Europe/London', 'BOOKED', null, null,
     'AUD-LHR-01', 'e14c0000-0000-4000-8000-000000000001', 'MANUAL', false, null, null, null, null, null, 'scheduler1', now() - interval '10 days', now()),
    ('e14a0000-0000-4000-8000-000000000014', 'E1009', 'AUDIOMETRY', 'MOBILE-1',
     (current_date + 7 + time '08:00') at time zone 'Europe/London', (current_date + 7 + time '08:20') at time zone 'Europe/London', 'BOOKED', null, null,
     'AUD-LHR-01', 'e14c0000-0000-4000-8000-000000000001', 'MANUAL', false, null, null, null, null, null, 'scheduler1', now() - interval '10 days', now())
on conflict do nothing;

update scheduling.campaign_slot s set appointment_id = a.id
  from scheduling.appointment a
 where a.campaign_id = s.campaign_id and a.starts_at = s.starts_at and s.appointment_id is null
   and a.id in ('e14a0000-0000-4000-8000-000000000011', 'e14a0000-0000-4000-8000-000000000013')
   and s.station = 1;
update scheduling.campaign_slot s set appointment_id = a.id
  from scheduling.appointment a
 where a.campaign_id = s.campaign_id and a.starts_at = s.starts_at and s.appointment_id is null
   and a.id in ('e14a0000-0000-4000-8000-000000000012', 'e14a0000-0000-4000-8000-000000000014')
   and s.station = 2;

-- ---------- waitlist ----------
insert into scheduling.waitlist_entry (id, employee_id, appointment_type, site_code, earliest, latest, priority, status, created_by, created_at, updated_at) values
    ('e14f2000-0000-4000-8000-000000000001', 'E1010', 'FOOD_HANDLER_CLEARANCE', 'LHR-CAT', current_date, current_date + 21, 2, 'WAITING', 'scheduler1', now(), now())
on conflict do nothing;
