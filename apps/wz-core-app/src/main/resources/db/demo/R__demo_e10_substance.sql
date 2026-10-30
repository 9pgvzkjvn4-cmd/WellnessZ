-- Demo data for E10 drug & alcohol testing – local and test profiles only (flyway location classpath:db/demo).
-- Idempotent (fixed ids e10de000-…, "on conflict do nothing" / "where not exists"); dates are relative to current_date / now()
-- at first load. Encrypted columns (laboratory results, breath readings, MRO notes, treatment provider) are left empty here:
-- the module's demo seeder (profiles local / test) writes them through the application.
--
--   random selections for last month (seed + pool snapshot reproducible via /testing/selections/{id}/verify):
--     RSL-…-900001 FLIGHT_CREW DRUGS → E7001 test DAT-…-900001 VERIFIED_NEGATIVE
--     RSL-…-900002 CABIN_CREW  DRUGS → E1003 test DAT-…-900002 IN_LAB (sealed, courier, received by TOXLAB – lab order open)
--     RSL-…-900003 FORKLIFT    DRUGS → E1008 test DAT-…-900003 MRO_REVIEW with laboratory results (opiates non-negative) – mro1 decides
--     RSL-…-900004 SECURITY    DRUGS → E1007 test DAT-…-900004 COLLECTED (specimen SPC-DEMO-9004 awaiting seal / courier)
--   DAT-…-900005 E1004 Rami Ramp POST_INCIDENT (INC-DEMO-E10-1, 3 h ago): DISPATCHED, alcohol window ~5 h left, drugs ~29 h
--   DAT-…-900006 E1009 Cole Cleaner FOR_CAUSE VERIFIED_POSITIVE 30 days ago: restricted SUBSTANCE case GND-DAT-DEMO1,
--                duty status REMOVED, return-to-duty plan RTD-…-900001 ACTIVE with 6 unannounced follow-up tests over 12 months
--   completed tests earlier this year for the programme statistics (some cells deliberately below the small-cell threshold)

-- ---------- programme staff (notifications; Keycloak users damanager1 / mro1 / collector1) ----------
insert into masterdata.staff_member (username, display_name, email, roles, site_codes, languages, specialties, caseload_cap) values
    ('damanager1', 'Dana DA Manager', 'damanager1@wellnessz.local', 'DA_PROGRAMME_MANAGER', 'LHR,HQ', 'en', 'SUBSTANCE_PROGRAMME', 0),
    ('mro1',       'Dr Max MRO',      'mro1@wellnessz.local',       'MRO',                  'HQ',     'en', 'MEDICAL_REVIEW',      0),
    ('collector1', 'Colin Collector', 'collector1@wellnessz.local', 'COLLECTOR',            'LHR',    'en', 'SPECIMEN_COLLECTION', 0)
on conflict (username) do nothing;

-- ---------- selections (last month) ----------
insert into substance.selection (id, ref, role_group, period, test_type, rate_pct, pool_size, pool_hash, pool_snapshot, seed_hex, seed_commitment,
                                 selected_count, selected, policy_version, policy_approval, created_by, created_at, notified_at, notified_by)
values
    ('e10de000-0000-4000-c000-000000000001', 'RSL-' || extract(year from current_date)::int || '-900001', 'FLIGHT_CREW',
     to_char(current_date - interval '1 month', 'YYYY-MM'), 'DRUGS', 25.00, 2,
     'fb2c944d1a25ecc419a437a5ff8b97517777ea8f1633d17014407f7aeae300a3', '["E1002","E7001"]',
     'e10de0000000000000000000000000000000000000000000000000000000f001', '0a7de41ef4b012b1239ae780b9ffbf131d51ed5b8f9f7038db0165ef92e6d17f',
     1, '["E7001"]', 1, 'PENDING', 'damanager1', now() - interval '35 days', now() - interval '35 days', 'damanager1'),
    ('e10de000-0000-4000-c000-000000000002', 'RSL-' || extract(year from current_date)::int || '-900002', 'CABIN_CREW',
     to_char(current_date - interval '1 month', 'YYYY-MM'), 'DRUGS', 25.00, 1,
     '4ba60d18bd08cdc99c642a00b3d6ccec3de01fdebe27fad8600051f656440c05', '["E1003"]',
     'e10de0000000000000000000000000000000000000000000000000000000f002', '257af54d5f3854e3012eae18d48c60e6be21abde62c9188bcd42f88bf416fc87',
     1, '["E1003"]', 1, 'PENDING', 'damanager1', now() - interval '35 days', now() - interval '35 days', 'damanager1'),
    ('e10de000-0000-4000-c000-000000000003', 'RSL-' || extract(year from current_date)::int || '-900003', 'FORKLIFT',
     to_char(current_date - interval '1 month', 'YYYY-MM'), 'DRUGS', 10.00, 1,
     'b95887d8e1955ad6f3b341d7595eca23548ef6a620ac3e0486f6bb065dba444c', '["E1008"]',
     'e10de0000000000000000000000000000000000000000000000000000000f003', '337eaad5ec2c00cf8e861af3b7ed2c4d0da56ee0b0a820876c48a49a740fd179',
     1, '["E1008"]', 1, 'PENDING', 'damanager1', now() - interval '35 days', now() - interval '35 days', 'damanager1'),
    ('e10de000-0000-4000-c000-000000000004', 'RSL-' || extract(year from current_date)::int || '-900004', 'SECURITY',
     to_char(current_date - interval '1 month', 'YYYY-MM'), 'DRUGS', 10.00, 1,
     '25bfe54442a1c178498f6aebffa0adfc8ffb400f32537f012fd045293d796717', '["E1007"]',
     'e10de0000000000000000000000000000000000000000000000000000000f004', '249f2994d2bd1c691f6bdc267aeea9355495d9e8940306b30b621901555b484c',
     1, '["E1007"]', 1, 'PENDING', 'damanager1', now() - interval '35 days', now() - interval '35 days', 'damanager1')
on conflict do nothing;

-- ---------- restricted SUBSTANCE case of E1009 (verified positive) ----------
insert into casemgmt.oh_case (id, case_ref, employee_id, employee_display_name, org_unit_code, org_unit_path, subsidiary_code, cost_centre,
                              job_code, location_code, category, case_type, source, source_ref, status, classification, fitness_status,
                              restriction_until, urgency, owner_username, occurred_on, created_at, updated_at)
values
    ('e10de000-0000-4000-a000-000000000001', 'GND-DAT-DEMO1', 'E1009', 'Cole Cleaner', 'GROUND', '/AIRLINE/OPS/GROUND', 'GND', 'CC-GND-420',
     'CLEANER', 'LHR', 'SUBSTANCE', 'SUBSTANCE', 'DA_TEST', 'da-test:e10de000-0000-4000-b000-000000000006', 'MONITORING', 'RESTRICTED_CLINICAL',
     'TEMPORARILY_UNFIT', null, 'URGENT', null, current_date - 31, now() - interval '30 days', now() - interval '10 days')
on conflict do nothing;

-- ---------- tests ----------
insert into substance.test (id, ref, employee_id, role_group, reason, panels, status, source_ref, selection_id, incident_ref, rtd_plan_id,
                            scheduled_for, due_at, alcohol_due_at, drug_due_at, notified_at, dispatched_at, collected_at, alcohol_collected_at,
                            site_code, observed_at, observations, second_observer_name, location, requested_by, lab_ref, lab_validity,
                            lab_received_at, verdict, prescription_verified, mro_username, mro_decided_at, case_id, created_by, created_at, updated_at)
values
    -- random FLIGHT_CREW → verified negative
    ('e10de000-0000-4000-b000-000000000001', 'DAT-' || extract(year from current_date)::int || '-900001', 'E7001', 'FLIGHT_CREW', 'RANDOM',
     'DRUGS_10', 'VERIFIED_NEGATIVE', 'selection:e10de000-0000-4000-c000-000000000001:E7001', 'e10de000-0000-4000-c000-000000000001', null, null,
     null, now() - interval '33 days' + interval '2 hours', null, null, now() - interval '33 days', null, now() - interval '33 days' + interval '1 hour',
     null, 'LHR-OH-CLINIC', null, null, null, null, null, 'TOX-DEMO-9001', 'VALID', now() - interval '31 days', 'NEGATIVE', null, 'mro1',
     now() - interval '29 days', null, 'damanager1', now() - interval '33 days', now() - interval '29 days'),
    -- random CABIN_CREW → in the laboratory
    ('e10de000-0000-4000-b000-000000000002', 'DAT-' || extract(year from current_date)::int || '-900002', 'E1003', 'CABIN_CREW', 'RANDOM',
     'DRUGS_10', 'IN_LAB', 'selection:e10de000-0000-4000-c000-000000000002:E1003', 'e10de000-0000-4000-c000-000000000002', null, null,
     null, now() - interval '3 days' + interval '2 hours', null, null, now() - interval '3 days', null, now() - interval '3 days' + interval '1 hour',
     null, 'LHR-OH-CLINIC', null, null, null, null, null, null, null, null, null, null, null, null, null, 'damanager1', now() - interval '3 days',
     now() - interval '2 days'),
    -- random FORKLIFT → laboratory results awaiting MRO review (results written by the demo seeder)
    ('e10de000-0000-4000-b000-000000000003', 'DAT-' || extract(year from current_date)::int || '-900003', 'E1008', 'FORKLIFT', 'RANDOM',
     'DRUGS_5', 'MRO_REVIEW', 'selection:e10de000-0000-4000-c000-000000000003:E1008', 'e10de000-0000-4000-c000-000000000003', null, null,
     null, now() - interval '5 days' + interval '2 hours', null, null, now() - interval '5 days', null, now() - interval '5 days' + interval '1 hour',
     null, 'LHR-OH-CLINIC', null, null, null, null, null, 'TOX-DEMO-9003', 'VALID', now() - interval '3 days', null, null, null, null, null,
     'damanager1', now() - interval '5 days', now() - interval '1 day'),
    -- random SECURITY → collected, awaiting seal / courier
    ('e10de000-0000-4000-b000-000000000004', 'DAT-' || extract(year from current_date)::int || '-900004', 'E1007', 'SECURITY', 'RANDOM',
     'DRUGS_5', 'COLLECTED', 'selection:e10de000-0000-4000-c000-000000000004:E1007', 'e10de000-0000-4000-c000-000000000004', null, null,
     null, now() - interval '6 hours', null, null, now() - interval '8 hours', null, now() - interval '7 hours', null, 'LHR-OH-CLINIC', null, null,
     null, null, null, null, null, null, null, null, null, null, null, 'damanager1', now() - interval '8 hours', now() - interval '7 hours'),
    -- post-incident E1004 (ramp): dispatched, not collected – alcohol window ~5 h left
    ('e10de000-0000-4000-b000-000000000005', 'DAT-' || extract(year from current_date)::int || '-900005', 'E1004', 'AIRSIDE_DRIVER', 'POST_INCIDENT',
     'DRUGS_5,ALCOHOL_BREATH', 'DISPATCHED', 'incident:e10de000-0000-4000-9000-000000000001', null, 'INC-DEMO-E10-1', null,
     null, now() + interval '29 hours', now() + interval '5 hours', now() + interval '29 hours', now() - interval '170 minutes',
     now() - interval '170 minutes', null, null, 'LHR', now() - interval '3 hours', null, null, null, null, null, null, null, null, null, null,
     null, null, 'ehs-incident-feed', now() - interval '170 minutes', now() - interval '170 minutes'),
    -- for-cause E1009 → verified positive (restricted case, duty removed, RTD plan)
    ('e10de000-0000-4000-b000-000000000006', 'DAT-' || extract(year from current_date)::int || '-900006', 'E1009', 'AIRSIDE_DRIVER', 'FOR_CAUSE',
     'DRUGS_10,ALCOHOL_BREATH', 'VERIFIED_POSITIVE', 'for-cause:e10de000-0000-4000-b000-000000000006', null, null, null,
     null, now() - interval '31 days' + interval '32 hours', now() - interval '31 days' + interval '8 hours', now() - interval '31 days' + interval '32 hours',
     now() - interval '31 days', now() - interval '31 days', now() - interval '31 days' + interval '2 hours', now() - interval '31 days' + interval '2 hours',
     'LHR', now() - interval '31 days', 'ODOUR_OF_ALCOHOL,UNSTEADY_GAIT', 'Rami Ramp', 'LHR stand 512', 'safety1', 'TOX-DEMO-9006', 'VALID',
     now() - interval '30 days', 'POSITIVE', null, 'mro1', now() - interval '30 days', 'e10de000-0000-4000-a000-000000000001', 'safety1',
     now() - interval '31 days', now() - interval '30 days')
on conflict do nothing;

-- completed tests earlier this year (statistics / coverage); created at least a day ago and inside the current year
insert into substance.test (id, ref, employee_id, role_group, reason, panels, status, source_ref, due_at, notified_at, collected_at, site_code,
                            lab_ref, lab_validity, lab_received_at, verdict, mro_username, mro_decided_at, created_by, created_at, updated_at)
select v.id::uuid, 'DAT-' || extract(year from current_date)::int || '-' || v.n, v.emp, v.grp, v.reason, v.panels, 'VERIFIED_NEGATIVE',
       'demo-history:' || v.id, h.at + interval '2 hours', h.at, h.at + interval '1 hour', 'LHR-OH-CLINIC', 'TOX-HIST-' || v.n, 'VALID',
       h.at + interval '2 days', 'NEGATIVE', 'mro1', h.at + interval '3 days', 'damanager1', h.at, h.at + interval '3 days'
from (values
    ('e10de000-0000-4000-b100-000000000001', '900101', 'E1004', 'AIRSIDE_DRIVER', 'RANDOM',        'DRUGS_5',        20),
    ('e10de000-0000-4000-b100-000000000002', '900102', 'E1004', 'AIRSIDE_DRIVER', 'RANDOM',        'ALCOHOL_BREATH', 50),
    ('e10de000-0000-4000-b100-000000000003', '900103', 'E1004', 'AIRSIDE_DRIVER', 'RANDOM',        'DRUGS_5',        80),
    ('e10de000-0000-4000-b100-000000000004', '900104', 'E1004', 'AIRSIDE_DRIVER', 'RANDOM',        'DRUGS_5',        110),
    ('e10de000-0000-4000-b100-000000000005', '900105', 'E1004', 'AIRSIDE_DRIVER', 'RANDOM',        'ALCOHOL_BREATH', 140),
    ('e10de000-0000-4000-b100-000000000006', '900106', 'E1004', 'AIRSIDE_DRIVER', 'RANDOM',        'DRUGS_5',        170),
    ('e10de000-0000-4000-b100-000000000007', '900107', 'E1002', 'FLIGHT_CREW',    'RANDOM',        'DRUGS_10',       30),
    ('e10de000-0000-4000-b100-000000000008', '900108', 'E7001', 'FLIGHT_CREW',    'RANDOM',        'DRUGS_10',       60),
    ('e10de000-0000-4000-b100-000000000009', '900109', 'E1002', 'FLIGHT_CREW',    'RANDOM',        'ALCOHOL_BREATH', 90),
    ('e10de000-0000-4000-b100-000000000010', '900110', 'E7001', 'FLIGHT_CREW',    'RANDOM',        'DRUGS_10',       120),
    ('e10de000-0000-4000-b100-000000000011', '900111', 'E1002', 'FLIGHT_CREW',    'RANDOM',        'DRUGS_10',       150),
    ('e10de000-0000-4000-b100-000000000012', '900112', 'E1008', 'FORKLIFT',       'POST_INCIDENT', 'DRUGS_5,ALCOHOL_BREATH', 70),
    ('e10de000-0000-4000-b100-000000000013', '900113', 'E1003', 'CABIN_CREW',     'PRE_EMPLOYMENT','DRUGS_10',       100)
) as v(id, n, emp, grp, reason, panels, days_into_year)
cross join lateral (select least(date_trunc('year', now()) + make_interval(days => v.days_into_year), now() - interval '1 day') as at) h(at)
where not exists (select 1 from substance.test x where x.id = v.id::uuid);

-- ---------- specimens & chain of custody ----------
insert into substance.specimen (id, specimen_id, test_id, specimen_type, kind, collected_at, site_code, temperature_in_range, seal_intact,
                                donor_identity_checked, split_specimen, collector, lab_code, out_of_window, lab_received_at, resulted_at, created_at)
values
    ('e10de000-0000-4000-d000-000000000001', 'SPC-DEMO-9001', 'e10de000-0000-4000-b000-000000000001', 'URINE', 'PRIMARY',
     now() - interval '33 days' + interval '1 hour', 'LHR-OH-CLINIC', true, true, true, true, 'collector1', 'TOXLAB', false,
     now() - interval '32 days', now() - interval '31 days', now() - interval '33 days' + interval '1 hour'),
    ('e10de000-0000-4000-d000-000000000002', 'SPC-DEMO-9002', 'e10de000-0000-4000-b000-000000000002', 'URINE', 'PRIMARY',
     now() - interval '3 days' + interval '1 hour', 'LHR-OH-CLINIC', true, true, true, true, 'collector1', 'TOXLAB', false,
     now() - interval '2 days', null, now() - interval '3 days' + interval '1 hour'),
    ('e10de000-0000-4000-d000-000000000003', 'SPC-DEMO-9003', 'e10de000-0000-4000-b000-000000000003', 'URINE', 'PRIMARY',
     now() - interval '5 days' + interval '1 hour', 'LHR-OH-CLINIC', true, true, true, true, 'collector1', 'TOXLAB', false,
     now() - interval '4 days', now() - interval '3 days', now() - interval '5 days' + interval '1 hour'),
    ('e10de000-0000-4000-d000-000000000004', 'SPC-DEMO-9004', 'e10de000-0000-4000-b000-000000000004', 'URINE', 'PRIMARY',
     now() - interval '7 hours', 'LHR-OH-CLINIC', true, true, true, false, 'collector1', 'TOXLAB', false, null, null, now() - interval '7 hours'),
    ('e10de000-0000-4000-d000-000000000005', 'SPC-DEMO-9006', 'e10de000-0000-4000-b000-000000000006', 'URINE', 'PRIMARY',
     now() - interval '31 days' + interval '2 hours', 'LHR', true, true, true, true, 'collector1', 'TOXLAB', false,
     now() - interval '30 days' - interval '12 hours', now() - interval '30 days', now() - interval '31 days' + interval '2 hours'),
    ('e10de000-0000-4000-d000-000000000006', 'SPC-DEMO-9006A', 'e10de000-0000-4000-b000-000000000006', 'BREATH', 'PRIMARY',
     now() - interval '31 days' + interval '2 hours', 'LHR', null, true, true, false, 'collector1', null, false, null, null,
     now() - interval '31 days' + interval '2 hours')
on conflict do nothing;

insert into substance.custody_event (specimen_id, event, event_at, by_name, signer, signer_role, recorded_at)
select v.specimen_id, v.event, v.at, v.by_name, v.signer, v.signer_role, v.at
from (values
    ('SPC-DEMO-9001', 'COLLECTED',      now() - interval '33 days' + interval '1 hour',  'Colin Collector', 'collector1', 'COLLECTOR'),
    ('SPC-DEMO-9001', 'SEALED',         now() - interval '33 days' + interval '70 minutes', 'Colin Collector', 'collector1', 'COLLECTOR'),
    ('SPC-DEMO-9001', 'COURIER_PICKUP', now() - interval '33 days' + interval '4 hours', 'MedCourier – J. Swift', 'collector1', 'COLLECTOR'),
    ('SPC-DEMO-9001', 'LAB_RECEIVED',   now() - interval '32 days',                      'TOXLAB', 'toxlab-system', 'LABORATORY:TOXLAB'),
    ('SPC-DEMO-9002', 'COLLECTED',      now() - interval '3 days' + interval '1 hour',   'Colin Collector', 'collector1', 'COLLECTOR'),
    ('SPC-DEMO-9002', 'SEALED',         now() - interval '3 days' + interval '70 minutes', 'Colin Collector', 'collector1', 'COLLECTOR'),
    ('SPC-DEMO-9002', 'COURIER_PICKUP', now() - interval '3 days' + interval '4 hours',  'MedCourier – J. Swift', 'collector1', 'COLLECTOR'),
    ('SPC-DEMO-9002', 'LAB_RECEIVED',   now() - interval '2 days',                       'TOXLAB', 'toxlab-system', 'LABORATORY:TOXLAB'),
    ('SPC-DEMO-9003', 'COLLECTED',      now() - interval '5 days' + interval '1 hour',   'Colin Collector', 'collector1', 'COLLECTOR'),
    ('SPC-DEMO-9003', 'SEALED',         now() - interval '5 days' + interval '70 minutes', 'Colin Collector', 'collector1', 'COLLECTOR'),
    ('SPC-DEMO-9003', 'COURIER_PICKUP', now() - interval '5 days' + interval '4 hours',  'MedCourier – J. Swift', 'collector1', 'COLLECTOR'),
    ('SPC-DEMO-9003', 'LAB_RECEIVED',   now() - interval '4 days',                       'TOXLAB', 'toxlab-system', 'LABORATORY:TOXLAB'),
    ('SPC-DEMO-9004', 'COLLECTED',      now() - interval '7 hours',                      'Colin Collector', 'collector1', 'COLLECTOR'),
    ('SPC-DEMO-9006', 'COLLECTED',      now() - interval '31 days' + interval '2 hours', 'Colin Collector', 'collector1', 'COLLECTOR'),
    ('SPC-DEMO-9006', 'SEALED',         now() - interval '31 days' + interval '130 minutes', 'Colin Collector', 'collector1', 'COLLECTOR'),
    ('SPC-DEMO-9006', 'COURIER_PICKUP', now() - interval '31 days' + interval '5 hours', 'MedCourier – J. Swift', 'collector1', 'COLLECTOR'),
    ('SPC-DEMO-9006', 'LAB_RECEIVED',   now() - interval '30 days' - interval '12 hours', 'TOXLAB', 'toxlab-system', 'LABORATORY:TOXLAB'),
    ('SPC-DEMO-9006A', 'COLLECTED',     now() - interval '31 days' + interval '2 hours', 'Colin Collector', 'collector1', 'COLLECTOR')
) as v(specimen_id, event, at, by_name, signer, signer_role)
where not exists (select 1 from substance.custody_event c where c.specimen_id = v.specimen_id and c.event = v.event);

-- ---------- duty status & return-to-duty plan of E1009 ----------
insert into substance.duty_status (employee_id, status, since, case_id, updated_by, updated_at) values
    ('E1009', 'REMOVED', now() - interval '30 days', 'e10de000-0000-4000-a000-000000000001', 'mro1', now() - interval '30 days')
on conflict do nothing;

insert into substance.rtd_plan (id, ref, employee_id, case_id, test_id, status, evaluator, follow_up_tests, follow_up_months, start_date, end_date,
                                created_by, created_at, updated_at)
values
    ('e10de000-0000-4000-e000-000000000001', 'RTD-' || extract(year from current_date)::int || '-900001', 'E1009',
     'e10de000-0000-4000-a000-000000000001', 'e10de000-0000-4000-b000-000000000006', 'ACTIVE', 'Dr Max MRO (substance abuse professional)',
     6, 12, current_date - 10, (current_date - 10) + interval '12 months', 'mro1', now() - interval '30 days', now() - interval '10 days')
on conflict do nothing;

insert into substance.test (id, ref, employee_id, role_group, reason, panels, status, source_ref, rtd_plan_id, scheduled_for, created_by,
                            created_at, updated_at)
select v.id::uuid, 'DAT-' || extract(year from current_date)::int || '-' || v.n, 'E1009', 'AIRSIDE_DRIVER', 'FOLLOW_UP', 'DRUGS_10,ALCOHOL_BREATH',
       'REQUIRED', 'rtd:e10de000-0000-4000-e000-000000000001:fu:' || v.n, 'e10de000-0000-4000-e000-000000000001'::uuid, current_date + v.days,
       'mro1', now() - interval '10 days', now() - interval '10 days'
from (values
    ('e10de000-0000-4000-b200-000000000001', '900201', 23),
    ('e10de000-0000-4000-b200-000000000002', '900202', 71),
    ('e10de000-0000-4000-b200-000000000003', '900203', 128),
    ('e10de000-0000-4000-b200-000000000004', '900204', 187),
    ('e10de000-0000-4000-b200-000000000005', '900205', 262),
    ('e10de000-0000-4000-b200-000000000006', '900206', 331)
) as v(id, n, days)
where not exists (select 1 from substance.test x where x.id = v.id::uuid);

-- reference counters stay below the demo range (9000xx)
insert into substance.ref_counter (prefix, seq_year, last_value) values
    ('DAT', extract(year from current_date)::int, 0), ('RSL', extract(year from current_date)::int, 0), ('RTD', extract(year from current_date)::int, 0)
on conflict do nothing;
