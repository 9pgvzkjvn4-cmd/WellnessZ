-- Demo OH chart for E1004 (Rami Ramp, ramp agent) – local and test profiles ONLY (flyway location classpath:db/demo).
-- Idempotent. Encrypted free-text columns (notes, reactions, values) are left empty here because field encryption keys
-- differ per environment; structured data and codes are populated.

-- penicillin allergy (alert on the chart summary, drives the allergy-class CDS check)
insert into ohrecord.allergy (id, employee_id, substance_system, substance_code, substance_display, category, criticality, reaction,
                              status, recorded_by, recorded_at, updated_at)
values ('7e03de00-0000-4000-8000-000000000001', 'E1004', 'SNOMED_CT', '91936005', 'Allergy to penicillin', 'MEDICATION', 'HIGH', null,
        'ACTIVE', 'demo', now() - interval '400 days', now() - interval '400 days')
on conflict (id) do nothing;

-- active, work-related problem
insert into ohrecord.problem (id, employee_id, code_system, code, code_display, status, onset_date, resolved_date, work_related, case_id,
                              note, classification, recorded_by, recorded_at, updated_at)
values ('7e03de00-0000-4000-8000-000000000002', 'E1004', 'ICD_10', 'M54.5', 'Low back pain', 'ACTIVE', current_date - 120, null, 'YES', null,
        null, 'CLINICAL', 'demo', now() - interval '120 days', now() - interval '120 days')
on conflict (id) do nothing;

-- hepatitis B course completed
insert into ohrecord.immunisation (id, employee_id, vaccine, code_system, code, administered_on, dose_number, lot_number, status,
                                   next_due_date, recorded_by, recorded_at)
values ('7e03de00-0000-4000-8000-000000000003', 'E1004', 'Hepatitis B', null, null, date '2024-03-01', 3, 'HB-DEMO-0324', 'COMPLETE',
        null, 'demo', now() - interval '600 days')
on conflict (id) do nothing;

-- baseline audiogram (hearing conservation, noise exposure) – 13 months ago, so the annual audiometry is overdue
insert into ohrecord.result (id, order_id, employee_id, case_id, category, code_system, code, code_display, value, unit, reference_range,
                             flag, status, effective_at, received_at, source, device_id, device_model, calibration_id, calibration_date,
                             measurements, detail, baseline, classification, recorded_by)
values ('7e03de00-0000-4000-8000-000000000004', null, 'E1004', null, 'AUDIOMETRY', 'LOINC', '28539-6', 'Audiogram study', null, 'dB HL',
        null, 'NORMAL', 'FINAL', now() - interval '395 days', now() - interval '395 days', 'DEVICE', 'AUD-LHR-01', 'Demo audiometer 300',
        'CAL-2025-0417', current_date - 420,
        '{"left":{"500":10,"1000":10,"2000":15,"3000":20,"4000":25,"6000":30,"8000":25},"right":{"500":10,"1000":10,"2000":10,"3000":15,"4000":20,"6000":25,"8000":20}}',
        '{"average234":{"left":20.0,"right":15.0},"baseline":true}', true, 'CLINICAL', 'demo')
on conflict (id) do nothing;

-- earlier job (manual ODH entry); the current job is synchronised from the HRIS master on first chart read
insert into ohrecord.job_history (id, employee_id, job_code, job_title, employer, industry, start_date, end_date, is_current, source,
                                  hazard_codes, duties, weekly_hours, recorded_by, recorded_at)
values ('7e03de00-0000-4000-8000-000000000005', 'E1004', 'BAGGAGE_HANDLER', 'Baggage hall handler', 'Group / GND', 'Air transportation',
        date '2019-04-01', date '2022-06-30', false, 'MANUAL', 'NOISE,MANUAL_HANDLING', 'Loading and sorting checked baggage', 40,
        'demo', now() - interval '400 days')
on conflict (id) do nothing;
