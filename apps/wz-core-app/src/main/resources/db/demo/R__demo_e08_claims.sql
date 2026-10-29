-- Demo data for E08 claims coordination – local and test profiles only (flyway location classpath:db/demo).
-- Idempotent (fixed ids, "on conflict do nothing"); dates are relative to current_date / now() at first load.
-- Encrypted columns (claim notes, first-report fields, secure messages, legal questions) are left empty here: the demo
-- seeder of the claims module (profiles local / test) writes them through the application – together with the signed
-- first-report document that fills the draft disclosure set.
--
--   GND-CLM-DEMO1  E1004 Rami Ramp    – WORKERS_COMP CLM-<year>-900001 (OPEN, lost-time, payer GULFTPA, adjuster1, nurse clinician1):
--                                       RTW plan ACTIVE, F2508 report FROI-<year>-900001 DRAFT due within 24 h,
--                                       FNOL FROI-<year>-900002 SUBMITTED + ACKNOWLEDGED, shared tasks, draft disclosure set
--   CAT-CLM-DEMO2  E1001 Erin Employee – EMPLOYER_LIABILITY CLM-<year>-900002 (LITIGATED, litigation hold, privileged, payer AVIA_INSURE),
--                                       medico-legal request LEG-<year>-900001 (OPEN)
--   GND-CLM-DEMO3  E1007 Sam Screener  – GROUP_MEDICAL CLM-<year>-900003 CLOSED this month (paid total reconciled with the ledger)
--   GND-CLM-DEMO4  E1009 Cole Cleaner  – THIRD_PARTY CLM-<year>-900004 NOTIFIED (payer GROUPMED)
--   further injury cases (GND / CAT ≥ 5 cases each, ENG / CGO single cases) carry case-level costs for the roll-ups;
--   budgets for this year, a GENERATED ERP export for last month, INSURER_SHARING consent of E1004.

-- ---------- kernel cases ----------
insert into casemgmt.oh_case (id, case_ref, employee_id, employee_display_name, org_unit_code, org_unit_path, subsidiary_code, cost_centre,
                              job_code, location_code, category, case_type, source, source_ref, status, classification, fitness_status,
                              restriction_until, urgency, owner_username, occurred_on, incident_ref, body_part, recordability, work_related,
                              created_at, updated_at)
values
    ('8e080000-0000-4000-a000-000000000001', 'GND-CLM-DEMO1', 'E1004', 'Rami Ramp', 'GROUND', '/AIRLINE/OPS/GROUND', 'GND', 'CC-GND-400',
     'RAMP_AGENT', 'LHR', 'INJURY', 'INJURY', 'EHS_INCIDENT', 'claims-demo:INC-DEMO-8001', 'TREATMENT', 'CLINICAL', 'FIT_WITH_RESTRICTIONS',
     current_date + 21, 'ROUTINE', 'clinician1', current_date - 20, 'INC-DEMO-8001', 'Lower back', 'LOST_TIME', 'YES',
     now() - interval '20 days', now() - interval '1 day'),
    ('8e080000-0000-4000-a000-000000000002', 'CAT-CLM-DEMO2', 'E1001', 'Erin Employee', 'CATERING', '/AIRLINE/CATERING', 'CAT', 'CC-CAT-100',
     'CATERING_ASSOC', 'LHR', 'INJURY', 'INJURY', 'EHS_INCIDENT', 'claims-demo:INC-DEMO-8002', 'CLAIMS', 'CLINICAL', 'FIT',
     null, 'ROUTINE', 'nurse1', current_date - 120, 'INC-DEMO-8002', 'Right hand', 'MEDICAL_TREATMENT', 'YES',
     now() - interval '120 days', now() - interval '5 days'),
    ('8e080000-0000-4000-a000-000000000003', 'GND-CLM-DEMO3', 'E1007', 'Sam Screener', 'GROUND', '/AIRLINE/OPS/GROUND', 'GND', 'CC-GND-410',
     'SECURITY_SCREENER', 'LHR', 'INJURY', 'INJURY', 'SELF_REFERRAL', 'claims-demo:3', 'CLOSED', 'CLINICAL', 'FIT',
     null, 'ROUTINE', 'nurse2', current_date - 60, null, 'Left knee', 'MEDICAL_TREATMENT', 'YES', now() - interval '60 days', now() - interval '2 hours'),
    ('8e080000-0000-4000-a000-000000000004', 'GND-CLM-DEMO4', 'E1009', 'Cole Cleaner', 'GROUND', '/AIRLINE/OPS/GROUND', 'GND', 'CC-GND-420',
     'CLEANER', 'LHR', 'INJURY', 'INJURY', 'SELF_REFERRAL', 'claims-demo:4', 'TREATMENT', 'CLINICAL', 'FIT',
     null, 'ROUTINE', 'nurse1', current_date - 9, null, 'Right shoulder', 'MEDICAL_TREATMENT', 'YES', now() - interval '9 days', now() - interval '3 days'),
    ('8e080000-0000-4000-a000-000000000005', 'GND-CLM-DEMO5', 'E1004', 'Rami Ramp', 'GROUND', '/AIRLINE/OPS/GROUND', 'GND', 'CC-GND-400',
     'RAMP_AGENT', 'LHR', 'INJURY', 'INJURY', 'SELF_REFERRAL', 'claims-demo:5', 'CLOSED', 'CLINICAL', 'FIT',
     null, 'ROUTINE', 'nurse2', current_date - 200, null, 'Left ankle', 'FIRST_AID', 'YES', now() - interval '200 days', now() - interval '150 days'),
    ('8e080000-0000-4000-a000-000000000006', 'GND-CLM-DEMO6', 'E1007', 'Sam Screener', 'GROUND', '/AIRLINE/OPS/GROUND', 'GND', 'CC-GND-410',
     'SECURITY_SCREENER', 'LHR', 'INJURY', 'INJURY', 'SELF_REFERRAL', 'claims-demo:6', 'CLOSED', 'CLINICAL', 'FIT',
     null, 'ROUTINE', 'nurse2', current_date - 150, null, 'Neck', 'FIRST_AID', 'YES', now() - interval '150 days', now() - interval '120 days'),
    ('8e080000-0000-4000-a000-000000000007', 'CAT-CLM-DEMO7', 'E1010', 'Cathy Cook', 'CATERING', '/AIRLINE/CATERING', 'CAT', 'CC-CAT-110',
     'CATERING_ASSOC', 'LHR', 'INJURY', 'INJURY', 'SELF_REFERRAL', 'claims-demo:7', 'TREATMENT', 'CLINICAL', 'FIT',
     null, 'ROUTINE', 'nurse1', current_date - 30, null, 'Left forearm', 'MEDICAL_TREATMENT', 'YES', now() - interval '30 days', now() - interval '6 days'),
    ('8e080000-0000-4000-a000-000000000008', 'CAT-CLM-DEMO8', 'M2001', 'Max Manager', 'CATERING', '/AIRLINE/CATERING', 'CAT', 'CC-CAT-100',
     'OFFICE_ADMIN', 'LHR', 'INJURY', 'INJURY', 'SELF_REFERRAL', 'claims-demo:8', 'CLOSED', 'CLINICAL', 'FIT',
     null, 'ROUTINE', 'nurse2', current_date - 90, null, 'Right wrist', 'FIRST_AID', 'YES', now() - interval '90 days', now() - interval '70 days'),
    ('8e080000-0000-4000-a000-000000000009', 'CAT-CLM-DEMO9', 'E1001', 'Erin Employee', 'CATERING', '/AIRLINE/CATERING', 'CAT', 'CC-CAT-100',
     'CATERING_ASSOC', 'LHR', 'INJURY', 'INJURY', 'SELF_REFERRAL', 'claims-demo:9', 'CLOSED', 'CLINICAL', 'FIT',
     null, 'ROUTINE', 'nurse1', current_date - 170, null, 'Left foot', 'FIRST_AID', 'YES', now() - interval '170 days', now() - interval '160 days'),
    ('8e080000-0000-4000-a000-000000000010', 'CAT-CLM-DEMO10', 'E1010', 'Cathy Cook', 'CATERING', '/AIRLINE/CATERING', 'CAT', 'CC-CAT-110',
     'CATERING_ASSOC', 'LHR', 'INJURY', 'INJURY', 'SELF_REFERRAL', 'claims-demo:10', 'CLOSED', 'CLINICAL', 'FIT',
     null, 'ROUTINE', 'nurse1', current_date - 140, null, 'Right hand', 'FIRST_AID', 'YES', now() - interval '140 days', now() - interval '130 days'),
    ('8e080000-0000-4000-a000-000000000011', 'ENG-CLM-DEMO11', 'E1006', 'Ellie Engineer', 'ENG', '/AIRLINE/ENG', 'ENG', 'CC-ENG-600',
     'ENGINEER', 'LHR', 'INJURY', 'INJURY', 'SELF_REFERRAL', 'claims-demo:11', 'TREATMENT', 'CLINICAL', 'FIT',
     null, 'ROUTINE', 'nurse2', current_date - 15, null, 'Left hand', 'MEDICAL_TREATMENT', 'YES', now() - interval '15 days', now() - interval '4 days'),
    ('8e080000-0000-4000-a000-000000000012', 'CGO-CLM-DEMO12', 'E1008', 'Fiona Forklift', 'CARGO', '/AIRLINE/CARGO', 'CGO', 'CC-CGO-700',
     'FORKLIFT_OP', 'LHR', 'INJURY', 'INJURY', 'SELF_REFERRAL', 'claims-demo:12', 'TREATMENT', 'CLINICAL', 'FIT',
     null, 'ROUTINE', 'nurse2', current_date - 25, null, 'Right foot', 'MEDICAL_TREATMENT', 'YES', now() - interval '25 days', now() - interval '8 days')
on conflict do nothing;

-- EHS incident behind the E1004 injury (facts used to auto-populate first reports; the narrative is written by the application)
insert into intake.ehs_incident (id, source_system, external_ref, occurred_at, location, employee_id, employee_name, org_unit_code, incident_type,
                                 severity, injury, channel, status, received_at, incident_class, body_part, lost_time, unit_code, shift)
values ('8e080000-0000-4000-8800-000000000001', 'EHS', 'INC-DEMO-8001', date_trunc('day', now()) - interval '20 days' + interval '6 hours 40 minutes',
        'LHR T5 stand 512 – baggage hold', 'E1004', 'Rami Ramp', 'GROUND', 'Manual handling', 'MEDIUM', true, 'API', 'RECEIVED',
        now() - interval '20 days', 'Strain / sprain', 'Lower back', true, 'GND', 'LATE'),
       ('8e080000-0000-4000-8800-000000000002', 'EHS', 'INC-DEMO-8002', date_trunc('day', now()) - interval '120 days' + interval '11 hours',
        'LHR catering unit – dish wash line', 'E1001', 'Erin Employee', 'CATERING', 'Equipment', 'MEDIUM', true, 'API', 'RECEIVED',
        now() - interval '120 days', 'Cut / laceration', 'Right hand', false, 'CAT', 'EARLY')
on conflict do nothing;

insert into casemgmt.case_event (id, case_id, event_type, summary, actor, occurred_at) values
    ('8e080000-0000-4000-8400-000000000001', '8e080000-0000-4000-a000-000000000001', 'CASE_OPENED', 'Case opened via EHS_INCIDENT (INJURY)', 'system', now() - interval '20 days'),
    ('8e080000-0000-4000-8400-000000000002', '8e080000-0000-4000-a000-000000000001', 'CLAIM_NOTIFIED', 'Claim CLM-' || extract(year from current_date)::int || '-900001 notified (WORKERS_COMP)', 'claims1', now() - interval '19 days'),
    ('8e080000-0000-4000-8400-000000000003', '8e080000-0000-4000-a000-000000000002', 'CASE_OPENED', 'Case opened via EHS_INCIDENT (INJURY)', 'system', now() - interval '120 days'),
    ('8e080000-0000-4000-8400-000000000004', '8e080000-0000-4000-a000-000000000002', 'CLAIM_NOTIFIED', 'Claim CLM-' || extract(year from current_date)::int || '-900002 notified (EMPLOYER_LIABILITY)', 'claims1', now() - interval '100 days'),
    ('8e080000-0000-4000-8400-000000000005', '8e080000-0000-4000-a000-000000000002', 'LEGAL_HOLD_SET', 'Litigation hold set for claim CLM-' || extract(year from current_date)::int || '-900002', 'legal1', now() - interval '10 days')
on conflict do nothing;

-- ---------- claims ----------
insert into claims.claim (id, claim_ref, case_id, case_ref, employee_id, claim_type, status, payer_code, policy_number, adjuster_name, adjuster_email,
                          adjuster_username, lost_time, lost_time_at, incident_date, notified_at, opened_at, settled_at, closed_at, nurse_username,
                          nurse_assigned_at, reserves, paid_total, paid_currency, litigation_hold, hold_reason, hold_set_by, hold_set_at, privileged,
                          privilege_basis, privilege_set_by, privilege_set_at, subrogation, created_by, created_at, updated_at)
values
    ('8e080000-0000-4000-b000-000000000001', 'CLM-' || extract(year from current_date)::int || '-900001', '8e080000-0000-4000-a000-000000000001',
     'GND-CLM-DEMO1', 'E1004', 'WORKERS_COMP', 'OPEN', 'GULFTPA', 'WC-GULF-2026-0042', 'Adam Adjuster', 'adjuster1@gulftpa.example', 'adjuster1',
     true, now() - interval '19 days', current_date - 20, now() - interval '19 days', now() - interval '18 days', null, null, 'clinician1',
     now() - interval '19 days' + interval '2 hours',
     '{"MEDICAL":{"amount":"4500.00","currency":"GBP"},"REHAB":{"amount":"1500.00","currency":"GBP"},"INDEMNITY":{"amount":"12000.00","currency":"GBP"}}',
     1250.00, 'GBP', false, null, null, null, false, null, null, null, null, 'claims1', now() - interval '19 days', now() - interval '1 day'),
    ('8e080000-0000-4000-b000-000000000002', 'CLM-' || extract(year from current_date)::int || '-900002', '8e080000-0000-4000-a000-000000000002',
     'CAT-CLM-DEMO2', 'E1001', 'EMPLOYER_LIABILITY', 'LITIGATED', 'AVIA_INSURE', 'EL-AVIA-2026-0007', 'Priya Panel', 'claims@aviasure.example', null,
     false, null, current_date - 120, now() - interval '100 days', now() - interval '98 days', null, null, null, null,
     '{"SETTLEMENT":{"amount":"15000.00","currency":"GBP"},"LEGAL":{"amount":"6000.00","currency":"GBP"}}',
     600.00, 'GBP', true, 'Claim form received from the employee''s solicitors – preserve all records', 'legal1', now() - interval '10 days', true,
     'Prepared in contemplation of litigation (litigation privilege)', 'legal1', now() - interval '10 days',
     '{"thirdPartyName":"Kitchen Equipment Services Ltd","thirdPartyType":"CONTRACTOR","thirdPartyInsurer":"Contractor Mutual","reference":"KES-2026-118","description":"Guard of the dish wash conveyor was left off after contractor maintenance","flaggedBy":"claims1"}',
     'claims1', now() - interval '100 days', now() - interval '10 days'),
    ('8e080000-0000-4000-b000-000000000003', 'CLM-' || extract(year from current_date)::int || '-900003', '8e080000-0000-4000-a000-000000000003',
     'GND-CLM-DEMO3', 'E1007', 'GROUP_MEDICAL', 'CLOSED', 'GROUPMED', 'GM-2026-3301', null, null, null,
     false, null, current_date - 60, now() - interval '58 days', now() - interval '57 days', now() - interval '20 days',
     greatest(date_trunc('month', now()), now() - interval '2 hours'), null, null, null, 980.00, 'GBP', false, null, null, null, false, null, null, null,
     null, 'claims1', now() - interval '58 days', now() - interval '2 hours'),
    ('8e080000-0000-4000-b000-000000000004', 'CLM-' || extract(year from current_date)::int || '-900004', '8e080000-0000-4000-a000-000000000004',
     'GND-CLM-DEMO4', 'E1009', 'THIRD_PARTY', 'NOTIFIED', 'GROUPMED', null, null, null, null,
     false, null, current_date - 9, now() - interval '2 days', null, null, null, null, null, null, null, null, false, null, null, null, false, null, null,
     null, null, 'claims1', now() - interval '2 days', now() - interval '2 days')
on conflict do nothing;

insert into claims.claim_history (id, claim_id, event_type, from_status, to_status, reason, actor, occurred_at) values
    ('8e080000-0000-4000-8300-000000000001', '8e080000-0000-4000-b000-000000000001', 'STATUS', null, 'NOTIFIED', 'Claim notified (WORKERS_COMP, payer GULFTPA)', 'claims1', now() - interval '19 days'),
    ('8e080000-0000-4000-8300-000000000002', '8e080000-0000-4000-b000-000000000001', 'NURSE_ASSIGNED', null, 'NOTIFIED', 'Nurse case manager clinician1', 'claims1', now() - interval '19 days' + interval '2 hours'),
    ('8e080000-0000-4000-8300-000000000003', '8e080000-0000-4000-b000-000000000001', 'STATUS', 'NOTIFIED', 'OPEN', null, 'claims1', now() - interval '18 days'),
    ('8e080000-0000-4000-8300-000000000004', '8e080000-0000-4000-b000-000000000002', 'STATUS', null, 'NOTIFIED', 'Claim notified (EMPLOYER_LIABILITY, payer AVIA_INSURE)', 'claims1', now() - interval '100 days'),
    ('8e080000-0000-4000-8300-000000000005', '8e080000-0000-4000-b000-000000000002', 'STATUS', 'NOTIFIED', 'OPEN', null, 'claims1', now() - interval '98 days'),
    ('8e080000-0000-4000-8300-000000000006', '8e080000-0000-4000-b000-000000000002', 'STATUS', 'OPEN', 'LITIGATED', 'Letter of claim received', 'legal1', now() - interval '10 days'),
    ('8e080000-0000-4000-8300-000000000007', '8e080000-0000-4000-b000-000000000002', 'LEGAL_HOLD_SET', 'LITIGATED', 'LITIGATED', 'Claim form received from the employee''s solicitors – preserve all records', 'legal1', now() - interval '10 days'),
    ('8e080000-0000-4000-8300-000000000008', '8e080000-0000-4000-b000-000000000003', 'STATUS', 'SETTLED', 'CLOSED', null, 'claims1', greatest(date_trunc('month', now()), now() - interval '2 hours')),
    ('8e080000-0000-4000-8300-000000000009', '8e080000-0000-4000-b000-000000000004', 'STATUS', null, 'NOTIFIED', 'Claim notified (THIRD_PARTY, payer GROUPMED)', 'claims1', now() - interval '2 days')
on conflict do nothing;

insert into casemgmt.case_link (id, case_id, link_type, ref, created_by, created_at) values
    ('8e080000-0000-4000-8500-000000000001', '8e080000-0000-4000-a000-000000000001', 'CLAIM', 'CLM-' || extract(year from current_date)::int || '-900001', 'claims1', now() - interval '19 days'),
    ('8e080000-0000-4000-8500-000000000002', '8e080000-0000-4000-a000-000000000002', 'CLAIM', 'CLM-' || extract(year from current_date)::int || '-900002', 'claims1', now() - interval '100 days')
on conflict do nothing;

-- ---------- RTW plan of the lost-time case (status and functional milestones visible to the adjuster) ----------
insert into fitness.rtw_plan (id, case_id, case_ref, employee_id, status, start_date, goal, phases, review_dates, created_by, created_at, activated_at)
values ('8e080000-0000-4000-8600-000000000001', '8e080000-0000-4000-a000-000000000001', 'GND-CLM-DEMO1', 'E1004', 'ACTIVE', current_date - 3,
        'Return to full ramp agent duties within four weeks',
        '[{"fromWeek":1,"toWeek":2,"hoursPerDay":4,"duties":"Baggage reconciliation desk, no lifting above 10 kg","restrictions":[]},'
        || '{"fromWeek":3,"toWeek":4,"hoursPerDay":6,"duties":"Ramp coordination, lifting up to 15 kg with a team lift","restrictions":[]}]',
        null, 'clinician1', now() - interval '4 days', now() - interval '3 days')
on conflict do nothing;

insert into fitness.rtw_review (id, plan_id, due_date) values
    ('8e080000-0000-4000-8700-000000000001', '8e080000-0000-4000-8600-000000000001', current_date + 11)
on conflict do nothing;

-- ---------- first reports ----------
insert into claims.first_report (id, report_ref, claim_id, form_code, recipient_type, recipient_code, status, due_at, specified_injury, channel,
                                 message_id, submitted_at, submitted_by, ack_ref, acknowledged_at, created_by, created_at, updated_at)
values
    ('8e080000-0000-4000-d000-000000000001', 'FROI-' || extract(year from current_date)::int || '-900001', '8e080000-0000-4000-b000-000000000001',
     'GB_OSH_F2508', 'AUTHORITY', 'AUTH_OSH', 'DRAFT', now() + interval '20 hours', false, null, null, null, null, null, null,
     'claims1', now() - interval '6 days', now() - interval '6 days'),
    ('8e080000-0000-4000-d000-000000000002', 'FROI-' || extract(year from current_date)::int || '-900002', '8e080000-0000-4000-b000-000000000001',
     'INSURER_FNOL', 'INSURER', 'GULFTPA', 'ACKNOWLEDGED', now() - interval '16 days', false, 'API', '8e080000-0000-4000-8900-000000000001',
     now() - interval '17 days', 'claims1', 'GULF-ACK-55120', now() - interval '16 days' - interval '3 hours', 'claims1', now() - interval '18 days',
     now() - interval '16 days')
on conflict do nothing;

-- ---------- collaboration: shared tasks ----------
insert into claims.claim_task (id, claim_id, title, due_date, assignee_role, status, created_by, created_at, completed_at, completed_by) values
    ('8e080000-0000-4000-f000-000000000001', '8e080000-0000-4000-b000-000000000001', 'Confirm the phase 2 start date of the return-to-work plan',
     current_date + 5, 'NURSE_CM', 'OPEN', 'adjuster1', now() - interval '2 days', null, null),
    ('8e080000-0000-4000-f000-000000000002', '8e080000-0000-4000-b000-000000000001', 'Send wage statement for the indemnity calculation',
     current_date + 7, 'ADJUSTER', 'OPEN', 'claims1', now() - interval '1 day', null, null),
    ('8e080000-0000-4000-f000-000000000003', '8e080000-0000-4000-b000-000000000001', 'Upload the payroll extract for weeks 1-3',
     current_date - 2, 'CLAIMS', 'DONE', 'adjuster1', now() - interval '9 days', now() - interval '3 days', 'claims1')
on conflict do nothing;

-- ---------- consent-gated disclosure (documents are added by the demo seeder) ----------
insert into claims.disclosure_set (id, claim_id, recipient_code, purpose, status, created_by, created_at) values
    ('8e080000-0000-4000-e000-000000000001', '8e080000-0000-4000-b000-000000000001', 'GULFTPA',
     'Claim assessment – first report and return-to-work status for the adjuster', 'DRAFT', 'claims1', now() - interval '1 day')
on conflict do nothing;

insert into platform.consent (id, employee_id, purpose, scope, status, notice_version, granted_at, captured_by) values
    ('8e080000-0000-4000-c000-000000000001', 'E1004', 'INSURER_SHARING', 'Workers'' compensation claim with Gulf Claims Administrators (TPA)',
     'GRANTED', '2026.1', now() - interval '19 days', 'employee:E1004')
on conflict do nothing;

-- ---------- cost ledger (incurred costs across categories and units; GND and CAT have five cases each) ----------
insert into claims.cost_entry (id, claim_id, case_id, employee_id, category, kind, amount, currency, incurred_on, provider_code, description,
                               invoice_number, source_type, source_ref, subsidiary_code, department_code, cost_centre, created_by, created_at, updated_at)
select v.id::uuid, v.claim_id::uuid, v.case_id::uuid, v.emp, v.cat, v.kind, v.amount, 'GBP', v.d, v.provider, v.descr, v.inv, v.src, v.ref,
       v.sub, v.dept, v.cc, 'demo', now(), now()
  from (values
    ('8e080000-0000-4000-8100-000000000001', '8e080000-0000-4000-b000-000000000001', '8e080000-0000-4000-a000-000000000001', 'E1004', 'MEDICAL', 'ESTIMATE', 1200.00,
        greatest(date_trunc('year', current_date)::date, current_date - 15), 'PHYSIOPLUS', 'Pre-authorised physiotherapy course', null, 'PREAUTH', 'demo-pa-1', 'GND', 'GROUND', 'CC-GND-400'),
    ('8e080000-0000-4000-8100-000000000002', '8e080000-0000-4000-b000-000000000001', '8e080000-0000-4000-a000-000000000001', 'E1004', 'MEDICAL', 'INVOICE', 850.00,
        greatest(date_trunc('year', current_date)::date, current_date - 10), 'PHYSIOPLUS', 'Provider invoice', 'PP-77812', 'PROVIDER_INVOICE', 'demo-inv-1', 'GND', 'GROUND', 'CC-GND-400'),
    ('8e080000-0000-4000-8100-000000000003', '8e080000-0000-4000-b000-000000000001', '8e080000-0000-4000-a000-000000000001', 'E1004', 'REHAB', 'INVOICE', 400.00,
        greatest(date_trunc('year', current_date)::date, current_date - 5), 'REHAB_HUB', 'Work conditioning sessions', 'RH-2201', 'CLAIM', null, 'GND', 'GROUND', 'CC-GND-400'),
    ('8e080000-0000-4000-8100-000000000004', '8e080000-0000-4000-b000-000000000001', '8e080000-0000-4000-a000-000000000001', 'E1004', 'INDEMNITY', 'PAYMENT', 1250.00,
        greatest(date_trunc('year', current_date)::date, current_date - 4), null, 'Temporary disability benefit weeks 1-2', null, 'CLAIM', null, 'GND', 'GROUND', 'CC-GND-400'),
    ('8e080000-0000-4000-8100-000000000005', '8e080000-0000-4000-b000-000000000001', '8e080000-0000-4000-a000-000000000001', 'E1004', 'ADMIN', 'INVOICE', 75.00,
        greatest(date_trunc('year', current_date)::date, current_date - 4), null, 'Claim handling fee', 'GT-5531', 'CLAIM', null, 'GND', 'GROUND', 'CC-GND-400'),
    ('8e080000-0000-4000-8100-000000000006', '8e080000-0000-4000-b000-000000000002', '8e080000-0000-4000-a000-000000000002', 'E1001', 'LEGAL', 'INVOICE', 3200.00,
        greatest(date_trunc('year', current_date)::date, current_date - 8), null, 'Panel solicitors – pre-action review', 'LAW-0912', 'CLAIM', null, 'CAT', 'CATERING', 'CC-CAT-100'),
    ('8e080000-0000-4000-8100-000000000007', '8e080000-0000-4000-b000-000000000002', '8e080000-0000-4000-a000-000000000002', 'E1001', 'MEDICAL', 'INVOICE', 600.00,
        greatest(date_trunc('year', current_date)::date, current_date - 110), 'CITYHOSP', 'Emergency department attendance', 'CH-44120', 'PROVIDER_INVOICE', 'demo-inv-2', 'CAT', 'CATERING', 'CC-CAT-100'),
    ('8e080000-0000-4000-8100-000000000008', '8e080000-0000-4000-b000-000000000002', '8e080000-0000-4000-a000-000000000002', 'E1001', 'MEDICAL', 'PAYMENT', 600.00,
        greatest(date_trunc('year', current_date)::date, current_date - 95), null, 'Insurer reimbursement', null, 'CLAIM', null, 'CAT', 'CATERING', 'CC-CAT-100'),
    ('8e080000-0000-4000-8100-000000000009', '8e080000-0000-4000-b000-000000000002', '8e080000-0000-4000-a000-000000000002', 'E1001', 'SETTLEMENT', 'RESERVE', 15000.00,
        greatest(date_trunc('year', current_date)::date, current_date - 10), null, 'Settlement reserve', null, 'CLAIM', null, 'CAT', 'CATERING', 'CC-CAT-100'),
    ('8e080000-0000-4000-8100-000000000010', '8e080000-0000-4000-b000-000000000003', '8e080000-0000-4000-a000-000000000003', 'E1007', 'MEDICAL', 'INVOICE', 980.00,
        greatest(date_trunc('year', current_date)::date, current_date - 50), 'LDN_IMAGING', 'Imaging invoice', 'LI-3381', 'PROVIDER_INVOICE', 'demo-inv-3', 'GND', 'GROUND', 'CC-GND-410'),
    ('8e080000-0000-4000-8100-000000000011', '8e080000-0000-4000-b000-000000000003', '8e080000-0000-4000-a000-000000000003', 'E1007', 'MEDICAL', 'PAYMENT', 980.00,
        greatest(date_trunc('year', current_date)::date, current_date - 30), null, 'Paid by GroupMed', null, 'CLAIM', null, 'GND', 'GROUND', 'CC-GND-410'),
    ('8e080000-0000-4000-8100-000000000012', '8e080000-0000-4000-b000-000000000004', '8e080000-0000-4000-a000-000000000004', 'E1009', 'MEDICAL', 'INVOICE', 240.00,
        greatest(date_trunc('year', current_date)::date, current_date - 6), 'HEATHROWMED', 'Clinic attendance', 'HM-1022', 'PROVIDER_INVOICE', 'demo-inv-4', 'GND', 'GROUND', 'CC-GND-420'),
    ('8e080000-0000-4000-8100-000000000013', null, '8e080000-0000-4000-a000-000000000005', 'E1004', 'MEDICAL', 'INVOICE', 180.00,
        greatest(date_trunc('year', current_date)::date, current_date - 190), 'HEATHROWMED', 'Clinic attendance', 'HM-0811', 'PROVIDER_INVOICE', 'demo-inv-5', 'GND', 'GROUND', 'CC-GND-400'),
    ('8e080000-0000-4000-8100-000000000014', null, '8e080000-0000-4000-a000-000000000006', 'E1007', 'REHAB', 'INVOICE', 320.00,
        greatest(date_trunc('year', current_date)::date, current_date - 140), 'PHYSIOPLUS', 'Physiotherapy sessions', 'PP-70110', 'PROVIDER_INVOICE', 'demo-inv-6', 'GND', 'GROUND', 'CC-GND-410'),
    ('8e080000-0000-4000-8100-000000000015', null, '8e080000-0000-4000-a000-000000000007', 'E1010', 'MEDICAL', 'INVOICE', 210.00,
        greatest(date_trunc('year', current_date)::date, current_date - 28), 'HEATHROWMED', 'Clinic attendance', 'HM-1201', 'PROVIDER_INVOICE', 'demo-inv-7', 'CAT', 'CATERING', 'CC-CAT-110'),
    ('8e080000-0000-4000-8100-000000000016', null, '8e080000-0000-4000-a000-000000000008', 'M2001', 'ADMIN', 'INVOICE', 60.00,
        greatest(date_trunc('year', current_date)::date, current_date - 85), null, 'Occupational health admin fee', 'OH-551', 'CLAIM', null, 'CAT', 'CATERING', 'CC-CAT-100'),
    ('8e080000-0000-4000-8100-000000000017', null, '8e080000-0000-4000-a000-000000000009', 'E1001', 'MEDICAL', 'INVOICE', 150.00,
        greatest(date_trunc('year', current_date)::date, current_date - 165), 'HEATHROWMED', 'Clinic attendance', 'HM-0702', 'PROVIDER_INVOICE', 'demo-inv-9', 'CAT', 'CATERING', 'CC-CAT-100'),
    ('8e080000-0000-4000-8100-000000000018', null, '8e080000-0000-4000-a000-000000000010', 'E1010', 'REHAB', 'INVOICE', 275.00,
        greatest(date_trunc('year', current_date)::date, current_date - 135), 'PHYSIOPLUS', 'Physiotherapy sessions', 'PP-70450', 'PROVIDER_INVOICE', 'demo-inv-10', 'CAT', 'CATERING', 'CC-CAT-110'),
    ('8e080000-0000-4000-8100-000000000019', null, '8e080000-0000-4000-a000-000000000011', 'E1006', 'MEDICAL', 'INVOICE', 330.00,
        greatest(date_trunc('year', current_date)::date, current_date - 12), 'CITYHOSP', 'Hospital attendance', 'CH-45001', 'PROVIDER_INVOICE', 'demo-inv-11', 'ENG', 'ENG', 'CC-ENG-600'),
    ('8e080000-0000-4000-8100-000000000020', null, '8e080000-0000-4000-a000-000000000012', 'E1008', 'MEDICAL', 'INVOICE', 190.00,
        greatest(date_trunc('year', current_date)::date, current_date - 20), 'HEATHROWMED', 'Clinic attendance', 'HM-1150', 'PROVIDER_INVOICE', 'demo-inv-12', 'CGO', 'CARGO', 'CC-CGO-700'),
    -- last month (the ERP export below)
    ('8e080000-0000-4000-8100-000000000021', null, '8e080000-0000-4000-a000-000000000007', 'E1010', 'MEDICAL', 'INVOICE', 120.00,
        (date_trunc('month', current_date) - interval '1 month')::date + 4, 'HEATHROWMED', 'Clinic attendance', 'HM-1188', 'PROVIDER_INVOICE', 'demo-inv-13', 'CAT', 'CATERING', 'CC-CAT-110'),
    ('8e080000-0000-4000-8100-000000000022', '8e080000-0000-4000-b000-000000000001', '8e080000-0000-4000-a000-000000000001', 'E1004', 'MEDICAL', 'INVOICE', 95.00,
        (date_trunc('month', current_date) - interval '1 month')::date + 20, 'HEATHROWMED', 'Clinic attendance', 'HM-1203', 'PROVIDER_INVOICE', 'demo-inv-14', 'GND', 'GROUND', 'CC-GND-400')
  ) as v(id, claim_id, case_id, emp, cat, kind, amount, d, provider, descr, inv, src, ref, sub, dept, cc)
on conflict do nothing;

-- ---------- budgets for this year (subsidiaries and departments) ----------
insert into claims.budget (budget_year, unit_code, amount, currency, updated_by, updated_at) values
    (extract(year from current_date)::int, 'GND', 25000.00, 'GBP', 'finance1', now()),
    (extract(year from current_date)::int, 'CAT', 18000.00, 'GBP', 'finance1', now()),
    (extract(year from current_date)::int, 'ENG', 6000.00, 'GBP', 'finance1', now()),
    (extract(year from current_date)::int, 'CGO', 4000.00, 'GBP', 'finance1', now()),
    (extract(year from current_date)::int, 'GROUND', 25000.00, 'GBP', 'finance1', now()),
    (extract(year from current_date)::int, 'CATERING', 18000.00, 'GBP', 'finance1', now())
on conflict do nothing;

-- ---------- ERP export of last month (GENERATED; checksum = SHA-256 of the CSV) ----------
insert into claims.cost_export (id, export_month, batch_id, row_count, total, currency, checksum, status, content, created_by, created_at)
select '8e080000-0000-4000-8200-000000000001', x.m, 'ERP-' || x.m || '-DEMO0001', x.n, x.total, 'GBP', encode(sha256(convert_to(x.csv, 'UTF8')), 'hex'),
       'GENERATED', x.csv, 'finance1', now() - interval '2 days'
  from (select to_char(date_trunc('month', current_date) - interval '1 month', 'YYYY-MM') as m,
               count(*)::int as n,
               coalesce(sum(r.amount), 0)::numeric(14,2) as total,
               'month,cost_centre,subsidiary,category,currency,amount,entries' || chr(10)
                   || coalesce(string_agg(to_char(date_trunc('month', current_date) - interval '1 month', 'YYYY-MM') || ',' || r.cost_centre || ',' || r.subsidiary_code
                                          || ',' || r.category || ',GBP,' || r.amount::text || ',' || r.entries || chr(10),
                                          '' order by r.cost_centre collate "C", r.subsidiary_code collate "C", r.category collate "C"), '') as csv
          from (select e.cost_centre, e.subsidiary_code, e.category, sum(e.amount)::numeric(14,2) as amount, count(*) as entries
                  from claims.cost_entry e
                 where e.incurred_on >= (date_trunc('month', current_date) - interval '1 month')::date
                   and e.incurred_on < date_trunc('month', current_date)::date
                   and e.kind in ('INVOICE', 'PAYMENT')
                 group by e.cost_centre, e.subsidiary_code, e.category) r) x
on conflict do nothing;
