-- Demo data for E07 medical surveillance – local and test profiles ONLY (flyway location classpath:db/demo).
-- Idempotent (fixed ids, on conflict do nothing); dates relative to current_date. Encrypted columns are left empty because
-- field-encryption keys differ per environment. Candidates (C9001 …) are not HRIS employees.

-- one more crew member whose medical has expired (status EXPIRED on the crew register / without-valid-medical list)
insert into masterdata.employee (employee_id, display_name, email, subsidiary_code, department_code, cost_centre, job_code,
                                 location_code, shift_pattern, preferred_language, badge_id, manager_employee_id, updated_at) values
    ('E7001', 'Fred Flyer', 'pilot2@wellnessz.local', 'FLT', 'FLIGHTOPS', 'CC-FLT-200', 'PILOT', 'LHR', 'ROSTER', 'en', 'B-7001', null, now())
on conflict (employee_id) do nothing;

-- dates of birth (age-based frequency: Class 1 every 6 months from age 40)
insert into surveillance.employee_profile (employee_id, date_of_birth, licence_types, updated_at) values
    ('E1002', date '1979-05-10', 'FLIGHT_CREW', now()),
    ('E1003', date '1994-02-20', 'CABIN_CREW', now()),
    ('E7001', date '1985-09-01', 'FLIGHT_CREW', now())
on conflict (employee_id) do nothing;

-- ---------- enrolments (CURRENT / DUE / OVERDUE mix) ----------
insert into surveillance.enrolment (id, employee_id, programme_code, reason, active, enrolled_on, last_completed_on, last_outcome,
                                    next_due_on, invitations, last_invited_at, escalated_at, updated_at) values
 ('7e07de00-0000-4000-8000-000000000101', 'E1001', 'FOOD_HANDLER',         'JOB_CODE', true, current_date - 800, current_date - 350, 'FIT', current_date + 15,  0, null, null, now()),
 ('7e07de00-0000-4000-8000-000000000102', 'E1002', 'PILOT_CLASS1',         'LICENCE',  true, current_date - 900, current_date - 135, 'FIT', ((current_date - 135) + interval '6 months')::date, 0, null, null, now()),
 ('7e07de00-0000-4000-8000-000000000103', 'E1002', 'HEARING_CONSERVATION', 'HAZARD',   true, current_date - 900, current_date - 200, 'FIT', current_date + 165, 0, null, null, now()),
 ('7e07de00-0000-4000-8000-000000000104', 'E1003', 'CABIN_CREW',           'LICENCE',  true, current_date - 900, current_date - 300, 'FIT', ((current_date - 300) + interval '24 months')::date, 0, null, null, now()),
 ('7e07de00-0000-4000-8000-000000000105', 'E1004', 'HEARING_CONSERVATION', 'HAZARD',   true, current_date - 800, current_date - 395, 'FIT', current_date - 30,  1, now() - interval '20 days', null, now()),
 ('7e07de00-0000-4000-8000-000000000106', 'E1004', 'AIRSIDE_DRIVER',       'JOB_CODE', true, current_date - 800, current_date - 400, 'FIT', current_date + 695, 0, null, null, now()),
 ('7e07de00-0000-4000-8000-000000000107', 'E1004', 'NIGHT_WORKER',         'HAZARD',   true, current_date - 800, current_date - 340, 'FIT', current_date + 25,  0, null, null, now()),
 ('7e07de00-0000-4000-8000-000000000108', 'E1004', 'HAZMAT_DGR',           'HAZARD',   true, current_date - 800, current_date - 200, 'FIT', current_date + 530, 0, null, null, now()),
 ('7e07de00-0000-4000-8000-000000000109', 'E1005', 'VDU',                  'HAZARD',   true, current_date - 800, current_date - 600, 'FIT', current_date + 130, 0, null, null, now()),
 ('7e07de00-0000-4000-8000-000000000110', 'E1006', 'HEARING_CONSERVATION', 'HAZARD',   true, current_date - 800, current_date - 380, 'FIT', current_date - 15,  0, null, null, now()),
 ('7e07de00-0000-4000-8000-000000000111', 'E1006', 'RESPIRATORY',          'HAZARD',   true, current_date - 800, current_date - 100, 'FIT', current_date + 630, 0, null, null, now()),
 ('7e07de00-0000-4000-8000-000000000112', 'E1007', 'SECURITY_SCREENER',    'JOB_CODE', true, current_date - 800, current_date - 710, 'FIT', current_date + 20,  1, now() - interval '5 days', null, now()),
 ('7e07de00-0000-4000-8000-000000000113', 'E1007', 'NIGHT_WORKER',         'HAZARD',   true, current_date - 800, current_date - 100, 'FIT', current_date + 265, 0, null, null, now()),
 ('7e07de00-0000-4000-8000-000000000114', 'E1007', 'VDU',                  'HAZARD',   true, current_date - 800, current_date - 300, 'FIT', current_date + 430, 0, null, null, now()),
 ('7e07de00-0000-4000-8000-000000000115', 'E1008', 'FORKLIFT',             'JOB_CODE', true, current_date - 800, current_date - 300, 'FIT', current_date + 795, 0, null, null, now()),
 ('7e07de00-0000-4000-8000-000000000116', 'E1008', 'HEARING_CONSERVATION', 'HAZARD',   true, current_date - 800, current_date - 350, 'FIT', current_date + 15,  0, null, null, now()),
 ('7e07de00-0000-4000-8000-000000000117', 'E1008', 'HAZMAT_DGR',           'HAZARD',   true, current_date - 800, current_date - 300, 'FIT', current_date + 430, 0, null, null, now()),
 ('7e07de00-0000-4000-8000-000000000118', 'E1008', 'NIGHT_WORKER',         'HAZARD',   true, current_date - 800, current_date - 200, 'FIT', current_date + 165, 0, null, null, now()),
 ('7e07de00-0000-4000-8000-000000000119', 'E1009', 'CLINICAL_BBP',         'HAZARD',   true, current_date - 800, current_date - 200, 'FIT', current_date + 1625, 0, null, null, now()),
 ('7e07de00-0000-4000-8000-000000000120', 'E1009', 'RESPIRATORY',          'HAZARD',   true, current_date - 900, current_date - 760, 'FIT', current_date - 30,  0, null, null, now()),
 ('7e07de00-0000-4000-8000-000000000121', 'E1009', 'NIGHT_WORKER',         'HAZARD',   true, current_date - 800, current_date - 150, 'FIT', current_date + 215, 0, null, null, now()),
 ('7e07de00-0000-4000-8000-000000000122', 'E1010', 'FOOD_HANDLER',         'JOB_CODE', true, current_date - 800, current_date - 400, 'FIT', current_date - 35,  2, now() - interval '10 days', now() - interval '3 days', now()),
 ('7e07de00-0000-4000-8000-000000000123', 'M2001', 'VDU',                  'HAZARD',   true, current_date - 800, current_date - 200, 'FIT', current_date + 530, 0, null, null, now()),
 ('7e07de00-0000-4000-8000-000000000124', 'E7001', 'PILOT_CLASS1',         'LICENCE',  true, current_date - 900, current_date - 375, 'FIT', current_date - 10,  0, null, null, now())
on conflict do nothing;

insert into surveillance.completion (id, enrolment_id, employee_id, programme_code, performed_on, outcome, recorded_by, recorded_at) values
 ('7e07de00-0000-4000-8000-000000000151', '7e07de00-0000-4000-8000-000000000101', 'E1001', 'FOOD_HANDLER', current_date - 350, 'FIT', 'demo', now()),
 ('7e07de00-0000-4000-8000-000000000152', '7e07de00-0000-4000-8000-000000000105', 'E1004', 'HEARING_CONSERVATION', current_date - 395, 'FIT', 'demo', now()),
 ('7e07de00-0000-4000-8000-000000000153', '7e07de00-0000-4000-8000-000000000110', 'E1006', 'HEARING_CONSERVATION', current_date - 380, 'FIT', 'demo', now()),
 ('7e07de00-0000-4000-8000-000000000154', '7e07de00-0000-4000-8000-000000000116', 'E1008', 'HEARING_CONSERVATION', current_date - 350, 'FIT', 'demo', now())
on conflict do nothing;

-- ---------- invitations ----------
insert into surveillance.invitation (id, enrolment_id, employee_id, sequence_no, sent_at, channel, status, sent_by, updated_at) values
 ('7e07de00-0000-4000-8000-000000000201', '7e07de00-0000-4000-8000-000000000112', 'E1007', 1, now() - interval '5 days',  'EMAIL', 'INVITED',   'scheduler1', now()),
 ('7e07de00-0000-4000-8000-000000000202', '7e07de00-0000-4000-8000-000000000105', 'E1004', 1, now() - interval '20 days', 'EMAIL', 'INVITED',   'scheduler1', now()),
 ('7e07de00-0000-4000-8000-000000000203', '7e07de00-0000-4000-8000-000000000122', 'E1010', 1, now() - interval '40 days', 'EMAIL', 'NO_SHOW',   'scheduler1', now()),
 ('7e07de00-0000-4000-8000-000000000204', '7e07de00-0000-4000-8000-000000000122', 'E1010', 2, now() - interval '10 days', 'EMAIL', 'ESCALATED', 'system',     now())
on conflict do nothing;

-- ---------- pre-employment: candidate C9001 (Keycloak user candidate1) waits for questionnaire + consent ----------
insert into surveillance.pre_employment (id, offer_ref, candidate_id, candidate_name, email, job_code, subsidiary_code, department_code,
                                         location_code, proposed_start, panel, status, hr_clearance, sla_due_at, created_by, created_at, updated_at) values
 ('7e07de00-0000-4000-8000-000000000301', 'OFF-2026-0001', 'C9001', 'Casey Candidate', 'candidate1@wellnessz.local', 'CATERING_ASSOC', 'CAT',
  'CATERING', 'LHR', current_date + 30, 'FOOD_HANDLER', 'INVITED', 'PENDING', now() + interval '10 days', 'hris', now() - interval '1 day', now() - interval '1 day'),
 ('7e07de00-0000-4000-8000-000000000302', 'OFF-2026-0002', 'C9002', 'Dana Driver', 'candidate2@wellnessz.local', 'RAMP_AGENT', 'GND',
  'GROUND', 'LHR', current_date + 14, 'AIRSIDE_DRIVER,HEARING_CONSERVATION,NIGHT_WORKER,HAZMAT_DGR', 'OUTCOME_RECORDED', 'FIT',
  now() - interval '2 days', 'hris', now() - interval '12 days', now() - interval '3 days')
on conflict do nothing;
update surveillance.pre_employment set outcome = 'FIT', outcome_by = 'nurse1', outcome_at = now() - interval '3 days',
       consent_given = true, consent_purposes = 'HR_STATUS_FEED,PRE_EMPLOYMENT_ASSESSMENT', consent_at = now() - interval '10 days'
 where id = '7e07de00-0000-4000-8000-000000000302' and outcome is null;

-- ---------- crew medical certificates ----------
insert into surveillance.crew_certificate (id, employee_id, certificate_type, certificate_number, issuing_authority, examined_on, valid_until,
                                           limitation_codes, outcome, suspended, source, ame_name, alerts_sent, expired_published, superseded,
                                           created_by, created_at) values
 ('7e07de00-0000-4000-8000-000000000401', 'E1002', 'CLASS1',     'C1-DEMO-E1002', 'UK CAA', current_date - 135, current_date + 45,  'VDL', 'FIT_WITH_LIMITATIONS',
  false, 'INTERNAL', 'Dr Ava AME', '90', false, false, 'ame1', now() - interval '135 days'),
 ('7e07de00-0000-4000-8000-000000000402', 'E1003', 'CABIN_CREW', 'CC-DEMO-E1003', 'UK CAA', current_date - 300, current_date + 430, null,  'FIT',
  false, 'INTERNAL', 'Dr Ava AME', null, false, false, 'ame1', now() - interval '300 days'),
 ('7e07de00-0000-4000-8000-000000000403', 'E7001', 'CLASS1',     'UK-AEMC-77120', 'UK CAA', current_date - 375, current_date - 10,  null,  'FIT',
  false, 'EXTERNAL_AME', null, '30,60,90', false, false, 'integration', now() - interval '375 days')
on conflict do nothing;
update surveillance.crew_certificate set aemc_name = 'Heathrow Aeromedical Centre' where id = '7e07de00-0000-4000-8000-000000000403' and aemc_name is null;

-- ---------- immunisations (mirrored into the E03 record) and a titre ----------
insert into surveillance.vaccine_administration (id, employee_id, vaccine_code, dose_number, lot_number, lot_expiry, body_site, route,
                                                 administered_at, consent_confirmed, record_id, recorded_by, recorded_at) values
 ('7e07de00-0000-4000-8000-000000000501', 'E1001', 'TYPHOID', 1, 'TY-DEMO-2501', current_date + 200, 'LEFT_DELTOID', 'IM', now() - interval '300 days', true, '7e07de00-0000-4000-8000-000000000511', 'nurse1', now() - interval '300 days'),
 ('7e07de00-0000-4000-8000-000000000502', 'E1001', 'HEP_A',   1, 'HA-DEMO-2502', current_date + 200, 'RIGHT_DELTOID', 'IM', now() - interval '200 days', true, '7e07de00-0000-4000-8000-000000000512', 'nurse1', now() - interval '200 days'),
 ('7e07de00-0000-4000-8000-000000000503', 'E1009', 'HEP_B',   1, 'HB-DEMO-2401', current_date + 100, 'LEFT_DELTOID', 'IM', now() - interval '400 days', true, '7e07de00-0000-4000-8000-000000000513', 'nurse1', now() - interval '400 days'),
 ('7e07de00-0000-4000-8000-000000000504', 'E1009', 'HEP_B',   2, 'HB-DEMO-2401', current_date + 100, 'LEFT_DELTOID', 'IM', now() - interval '370 days', true, '7e07de00-0000-4000-8000-000000000514', 'nurse1', now() - interval '370 days'),
 ('7e07de00-0000-4000-8000-000000000505', 'E1009', 'HEP_B',   3, 'HB-DEMO-2402', current_date + 100, 'LEFT_DELTOID', 'IM', now() - interval '220 days', true, '7e07de00-0000-4000-8000-000000000515', 'nurse1', now() - interval '220 days')
on conflict do nothing;
insert into ohrecord.immunisation (id, employee_id, vaccine, code_system, code, administered_on, dose_number, lot_number, status, next_due_date,
                                   recorded_by, recorded_at) values
 ('7e07de00-0000-4000-8000-000000000511', 'E1001', 'Typhoid',     'LOCAL', 'TYPHOID', current_date - 300, 1, 'TY-DEMO-2501', 'COMPLETE', null, 'nurse1', now() - interval '300 days'),
 ('7e07de00-0000-4000-8000-000000000512', 'E1001', 'Hepatitis A', 'LOCAL', 'HEP_A',   current_date - 200, 1, 'HA-DEMO-2502', 'PARTIAL', current_date - 20, 'nurse1', now() - interval '200 days'),
 ('7e07de00-0000-4000-8000-000000000513', 'E1009', 'Hepatitis B', 'LOCAL', 'HEP_B',   current_date - 400, 1, 'HB-DEMO-2401', 'PARTIAL', null, 'nurse1', now() - interval '400 days'),
 ('7e07de00-0000-4000-8000-000000000514', 'E1009', 'Hepatitis B', 'LOCAL', 'HEP_B',   current_date - 370, 2, 'HB-DEMO-2401', 'PARTIAL', null, 'nurse1', now() - interval '370 days'),
 ('7e07de00-0000-4000-8000-000000000515', 'E1009', 'Hepatitis B', 'LOCAL', 'HEP_B',   current_date - 220, 3, 'HB-DEMO-2402', 'COMPLETE', null, 'nurse1', now() - interval '220 days')
on conflict do nothing;
insert into surveillance.titre (id, employee_id, antigen, value, unit, protective, tested_on, recorded_by, recorded_at) values
 ('7e07de00-0000-4000-8000-000000000521', 'E1009', 'HEP_B', 120, 'mIU/mL', true, current_date - 160, 'nurse1', now() - interval '160 days')
on conflict do nothing;

-- ---------- IH measurements (LHR ramp noise SEG above the 80 dB(A) action level) and cosmic dose ----------
insert into surveillance.ih_measurement (id, source_ref, seg_code, hazard_code, measurement_type, value, unit, measured_on, imported_by, imported_at) values
 ('7e07de00-0000-4000-8000-000000000601', 'HSE-DEMO-NOISE-001', 'LHR-RAMP-NOISE',  'NOISE',   'NOISE_DOSIMETRY', 84.2, 'dB(A)', current_date - 20,  'hse1', now() - interval '19 days'),
 ('7e07de00-0000-4000-8000-000000000602', 'HSE-DEMO-NOISE-002', 'LHR-CARGO-NOISE', 'NOISE',   'NOISE_DOSIMETRY', 78.5, 'dB(A)', current_date - 25,  'hse1', now() - interval '24 days'),
 ('7e07de00-0000-4000-8000-000000000603', 'HSE-DEMO-SOLV-001',  'LHR-ENG-SOLVENT', 'SOLVENT', 'AIR_SAMPLING',    18.0, 'ppm',   current_date - 60,  'hse1', now() - interval '59 days')
on conflict do nothing;
insert into surveillance.cosmic_dose (id, employee_id, dose_year, dose_msv, source, imported_by, imported_at, alerted) values
 ('7e07de00-0000-4000-8000-000000000701', 'E1002', extract(year from current_date)::int - 1, 3.2, 'CARI-7 roster model', 'ame1', now() - interval '30 days', false),
 ('7e07de00-0000-4000-8000-000000000702', 'E1003', extract(year from current_date)::int - 1, 6.4, 'CARI-7 roster model', 'ame1', now() - interval '30 days', true)
on conflict do nothing;

-- ---------- one open follow-up (E1006 standard threshold shift) with its kernel case ----------
insert into ohrecord.result (id, order_id, employee_id, case_id, category, code_system, code, code_display, value, unit, reference_range,
                             flag, status, effective_at, received_at, source, device_id, device_model, calibration_id, calibration_date,
                             measurements, detail, baseline, classification, recorded_by)
values ('7e07de00-0000-4000-8000-000000000801', null, 'E1006', '7e07de00-0000-4000-8000-000000000802', 'AUDIOMETRY', 'LOINC', '28539-6',
        'Audiogram study', null, 'dB HL', 'STS < 10 dB vs baseline', 'ABNORMAL', 'FINAL', now() - interval '6 days', now() - interval '6 days',
        'DEVICE', 'AUD-LHR-01', 'Demo audiometer 300', 'CAL-2026-0101', current_date - 60,
        '{"left":{"500":15,"1000":20,"2000":30,"3000":40,"4000":45},"right":{"500":10,"1000":15,"2000":20,"3000":25,"4000":30}}',
        '{"average234":{"left":38.3,"right":25.0},"sts":{"left":true,"right":false},"shiftDb":{"left":13.3,"right":3.3}}',
        false, 'CLINICAL', 'nurse1')
on conflict (id) do nothing;
insert into casemgmt.oh_case (id, case_ref, employee_id, employee_display_name, org_unit_code, org_unit_path, category, source, source_ref,
                              status, classification, fitness_status, case_type, subsidiary_code, job_code, location_code, created_at, updated_at)
values ('7e07de00-0000-4000-8000-000000000802', 'ENG-' || extract(year from current_date)::int || '-907001', 'E1006', 'Ellie Engineer', 'ENG',
        '/AIRLINE/ENG', 'EXPOSURE', 'SURVEILLANCE_TRIGGER', 'surveillance:7e07de00-0000-4000-8000-000000000801', 'ASSESSMENT', 'CLINICAL',
        'NOT_ASSESSED', 'SURVEILLANCE_FOLLOW_UP', 'ENG', 'ENGINEER', 'LHR', now() - interval '6 days', now() - interval '6 days')
on conflict do nothing;
insert into surveillance.follow_up (id, employee_id, programme_code, rule_code, result_id, case_id, case_ref, recheck_due_on,
                                    regulatory_notification_required, status, created_at)
select '7e07de00-0000-4000-8000-000000000803', 'E1006', 'HEARING_CONSERVATION', 'STS_HEARING', '7e07de00-0000-4000-8000-000000000801',
       c.id, c.case_ref, current_date + 24, true, 'OPEN', now() - interval '6 days'
  from casemgmt.oh_case c where c.id = '7e07de00-0000-4000-8000-000000000802'
on conflict do nothing;

-- ---------- return-after-illness REQUIRED for E1004 (safety-critical ramp agent) ----------
-- Demo environments only: the automated test databases keep E1004 unblocked for the increment-2 fitness-decision tests.
insert into surveillance.return_after_illness (id, employee_id, reason, source, case_id, status, created_by, created_at)
select '7e07de00-0000-4000-8000-000000000901', 'E1004', 'LONG_TERM_ABSENCE', 'E06', null, 'REQUIRED', 'system', now() - interval '2 days'
 where current_database() <> 'test' and current_database() not like '%\_test%' and current_database() not like 'wellnessz\_test%'
on conflict do nothing;
