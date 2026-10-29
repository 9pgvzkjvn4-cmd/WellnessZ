-- Demo data for E06 absence & sickness management – local and test profiles only (flyway location classpath:db/demo).
-- Idempotent (fixed ids, "on conflict do nothing"); dates are relative to current_date at first load.
-- Encrypted columns (contact notes, advice context, OCR fields …) are left empty: they are written by the application only.
--
--   E1001 Erin Employee (catering)  – several short absences incl. a Monday / Friday pattern, early-intervention item (NEW),
--                                     advice request ADV-<year>-900001 from manager1 with a signed report AWAITING_EMPLOYEE
--   E1004 Rami Ramp (ramp agent)    – long-term absence (~6 weeks) on case GND-ABS-DEMO1 with the LTA milestone plan
--   E1003 Cara Crew (cabin crew)    – crew sickness, AME review queue item on FITNESS case CAB-AME-DEMO1 (UNFIT_PENDING)
--   E1010 Cathy Cook (catering)     – open absence, certificate RECEIVED, advice request SUBMITTED
--   feed runs with a reconciliation mismatch (unknown badge B-9999, unknown crew id E9999)

-- ---------- kernel cases ----------
insert into casemgmt.oh_case (id, case_ref, employee_id, employee_display_name, org_unit_code, org_unit_path, subsidiary_code, cost_centre,
                              job_code, location_code, category, case_type, source, source_ref, status, classification, fitness_status,
                              urgency, safety_category, owner_username, early_intervention, occurred_on, created_at, updated_at)
values
    ('6e060000-0000-4000-a000-000000000001', 'CAT-ABS-DEMO1', 'E1001', 'Erin Employee', 'CATERING', '/AIRLINE/CATERING', 'CAT', 'CC-CAT-100',
     'CATERING_ASSOC', 'LHR', 'OTHER', 'ABSENCE', 'MANAGER_REFERRAL', 'advice:ADV-DEMO-900001', 'TRIAGE', 'CLINICAL', 'NOT_ASSESSED',
     'ROUTINE', 'FOOD_HANDLER', 'clinician1', false, current_date - 12, now() - interval '3 days', now() - interval '3 days'),
    ('6e060000-0000-4000-a000-000000000002', 'GND-ABS-DEMO1', 'E1004', 'Rami Ramp', 'GROUND', '/AIRLINE/OPS/GROUND', 'GND', 'CC-GND-400',
     'RAMP_AGENT', 'LHR', 'OTHER', 'ABSENCE', 'ABSENCE_TRIGGER', 'absence-lta:6e060000-0000-4000-b000-000000000041', 'TRIAGE', 'CLINICAL',
     'NOT_ASSESSED', 'ROUTINE', 'AIRSIDE_DRIVER', 'clinician1', true, current_date - 42, now() - interval '14 days', now() - interval '14 days'),
    ('6e060000-0000-4000-a000-000000000003', 'CAB-AME-DEMO1', 'E1003', 'Cara Crew', 'CABIN', '/AIRLINE/OPS/CABIN', 'CAB', 'CC-CAB-300',
     'CABIN_CREW', 'LHR', 'DECREASE_IN_FITNESS', 'FITNESS', 'ABSENCE_TRIGGER', 'absence-ame:6e060000-0000-4000-b000-000000000031', 'INTAKE',
     'CLINICAL', 'UNFIT_PENDING', 'URGENT', 'CABIN_CREW', null, false, current_date - 9, now() - interval '1 day', now() - interval '1 day')
on conflict do nothing;

insert into casemgmt.case_event (id, case_id, event_type, summary, actor, occurred_at) values
    ('6e060000-0000-4000-a100-000000000001', '6e060000-0000-4000-a000-000000000001', 'CASE_OPENED', 'Case opened via MANAGER_REFERRAL (ABSENCE)', 'system', now() - interval '3 days'),
    ('6e060000-0000-4000-a100-000000000002', '6e060000-0000-4000-a000-000000000002', 'CASE_OPENED', 'Case opened via ABSENCE_TRIGGER (ABSENCE)', 'system', now() - interval '14 days'),
    ('6e060000-0000-4000-a100-000000000003', '6e060000-0000-4000-a000-000000000002', 'LTA_STARTED', 'Long-term absence pathway started (28 days absent)', 'system', now() - interval '14 days'),
    ('6e060000-0000-4000-a100-000000000004', '6e060000-0000-4000-a000-000000000003', 'CASE_OPENED', 'Case opened via ABSENCE_TRIGGER (FITNESS)', 'system', now() - interval '1 day'),
    ('6e060000-0000-4000-a100-000000000005', '6e060000-0000-4000-a000-000000000003', 'AME_REVIEW_REQUIRED', 'Crew sickness absence – AME review required before return to flying duties', 'system', now() - interval '1 day')
on conflict do nothing;

insert into casemgmt.case_link (id, case_id, link_type, ref, created_by, created_at) values
    ('6e060000-0000-4000-a200-000000000001', '6e060000-0000-4000-a000-000000000002', 'ABSENCE', '6e060000-0000-4000-b000-000000000041', 'absence-feed', now() - interval '14 days')
on conflict do nothing;

-- ---------- feed runs & mismatches ----------
insert into absence.feed_run (id, source_system, run_type, started_at, finished_at, received, matched, unmatched, updated, triggered, errors,
                              error_detail, started_by) values
    ('6e060000-0000-4000-f000-000000000001', 'HRIS', 'PUSH', now() - interval '2 days', now() - interval '2 days' + interval '4 seconds', 12, 11, 1, 2, 1, 0, null, 'hris-system'),
    ('6e060000-0000-4000-f000-000000000002', 'TNA',  'PULL', now() - interval '1 day',  now() - interval '1 day' + interval '2 seconds', 5, 5, 0, 1, 0, 0, null, 'absence-feed'),
    ('6e060000-0000-4000-f000-000000000003', 'CREW', 'PUSH', now() - interval '1 day',  now() - interval '1 day' + interval '3 seconds', 3, 2, 1, 0, 1, 1,
     'DEMO-CREW-X02: endDate before startDate', 'crew-system')
on conflict do nothing;

insert into absence.feed_mismatch (id, run_id, source_system, source_ref, employee_ref, start_date, end_date, absence_type, reason_category,
                                   certified, reason, status, created_at) values
    ('6e060000-0000-4000-f100-000000000001', '6e060000-0000-4000-f000-000000000001', 'HRIS', 'DEMO-HRIS-X01', 'B-9999', current_date - 3, null,
     'SICKNESS', 'OTHER', false, 'UNKNOWN_EMPLOYEE', 'OPEN', now() - interval '2 days'),
    ('6e060000-0000-4000-f100-000000000002', '6e060000-0000-4000-f000-000000000003', 'CREW', 'DEMO-CREW-X01', 'E9999', current_date - 2, current_date - 1,
     'SICKNESS', null, false, 'UNKNOWN_EMPLOYEE', 'OPEN', now() - interval '1 day')
on conflict do nothing;

-- ---------- episodes ----------
-- weekday helpers: (d - (isodow(d) - 1)) is the Monday of d's week; + 4 is its Friday
insert into absence.episode (id, source_system, source_ref, employee_id, start_date, end_date, absence_type, reason_category, certified, case_id,
                             feed_run_id, lta_signalled, created_at, updated_at)
select v.id::uuid, v.src, v.ref, v.emp, v.s, v.e, v.typ, v.reason, v.cert, v.case_id::uuid, '6e060000-0000-4000-f000-000000000001'::uuid,
       v.lta, now() - interval '2 days', now() - interval '2 days'
  from (values
    ('6e060000-0000-4000-b000-000000000011', 'HRIS', 'DEMO-E1001-01', 'E1001',
        (current_date - 150) - (extract(isodow from current_date - 150)::int - 1), (current_date - 150) - (extract(isodow from current_date - 150)::int - 1),
        'SICKNESS', 'GASTRO', false, null, false),
    ('6e060000-0000-4000-b000-000000000012', 'HRIS', 'DEMO-E1001-02', 'E1001',
        (current_date - 100) - (extract(isodow from current_date - 100)::int - 1) + 4, (current_date - 100) - (extract(isodow from current_date - 100)::int - 1) + 4,
        'SICKNESS', 'OTHER', false, null, false),
    ('6e060000-0000-4000-b000-000000000013', 'HRIS', 'DEMO-E1001-03', 'E1001',
        (current_date - 60) - (extract(isodow from current_date - 60)::int - 1), (current_date - 60) - (extract(isodow from current_date - 60)::int - 1) + 1,
        'SICKNESS', 'RESPIRATORY', false, null, false),
    ('6e060000-0000-4000-b000-000000000014', 'HRIS', 'DEMO-E1001-04', 'E1001',
        (current_date - 25) - (extract(isodow from current_date - 25)::int - 1) + 4, (current_date - 25) - (extract(isodow from current_date - 25)::int - 1) + 4,
        'SICKNESS', 'UNDISCLOSED', false, null, false),
    ('6e060000-0000-4000-b000-000000000015', 'HRIS', 'DEMO-E1001-05', 'E1001',
        (current_date - 12) - (extract(isodow from current_date - 12)::int - 1), (current_date - 12) - (extract(isodow from current_date - 12)::int - 1) + 2,
        'SICKNESS', 'GASTRO', true, '6e060000-0000-4000-a000-000000000001', false),
    ('6e060000-0000-4000-b000-000000000040', 'HRIS', 'DEMO-E1004-00', 'E1004', current_date - 200, current_date - 198, 'INJURY_NON_WORK', 'INJURY', false, null, false),
    ('6e060000-0000-4000-b000-000000000041', 'HRIS', 'DEMO-E1004-01', 'E1004', current_date - 42, null, 'SICKNESS', 'MSK', true,
        '6e060000-0000-4000-a000-000000000002', true),
    ('6e060000-0000-4000-b000-000000000031', 'CREW', 'DEMO-E1003-01', 'E1003', current_date - 9, null, 'SICKNESS', 'RESPIRATORY', true,
        '6e060000-0000-4000-a000-000000000003', false),
    ('6e060000-0000-4000-b000-000000000101', 'TNA', 'DEMO-E1010-01', 'E1010', current_date - 40, current_date - 38, 'SICKNESS', 'GASTRO', false, null, false),
    ('6e060000-0000-4000-b000-000000000102', 'TNA', 'DEMO-E1010-02', 'E1010', current_date - 4, null, 'SICKNESS', 'GASTRO', true, null, false)
  ) as v(id, src, ref, emp, s, e, typ, reason, cert, case_id, lta)
on conflict do nothing;

-- ---------- certificates ----------
insert into absence.certificate (id, employee_id, episode_id, source, document_id, from_date, to_date, issuer_name, issuer_registration,
                                 certificate_number, partner_org, issue_date, fit_note, checklist, status, duplicate_of_id, alteration_flags,
                                 sha256, received_at, received_by, validated_by, validated_at, verified_by, verified_at, escalated)
select v.id::uuid, v.emp, v.ep::uuid, v.src, null, v.f, v.t, v.issuer, v.reg, v.num, v.partner, v.issued, v.fit, v.checklist, v.status, null,
       v.flags, null, now() - (v.age || ' days')::interval, v.received_by, v.validated_by,
       case when v.validated_by is null then null else now() - ((v.age - 1) || ' days')::interval end,
       v.verified_by, case when v.verified_by is null then null else now() - ((v.age - 2) || ' days')::interval end, v.escalated
  from (values
    ('6e060000-0000-4000-c000-000000000001', 'E1001', '6e060000-0000-4000-b000-000000000015', 'EMPLOYEE_UPLOAD',
        (current_date - 12) - (extract(isodow from current_date - 12)::int - 1), (current_date - 12) - (extract(isodow from current_date - 12)::int - 1) + 2,
        'Dr Sam Patel', 'GMC1234567', null, null, current_date - 11, null,
        '{"issuerIdentified":true,"registrationValid":true,"datesConsistent":true,"signaturePresent":true,"coversAbsence":true}', 'VALID', null, 10,
        'employee1', 'nurse1', null, false),
    ('6e060000-0000-4000-c000-000000000002', 'E1004', '6e060000-0000-4000-b000-000000000041', 'CLINIC_SCAN', current_date - 42, current_date + 14,
        'Dr Hana Lee', 'GMC7654321', null, null, current_date - 41, 'NOT_FIT',
        '{"issuerIdentified":true,"registrationValid":true,"datesConsistent":true,"signaturePresent":true,"coversAbsence":true}', 'VERIFIED_GENUINE', null, 30,
        'reception1', 'nurse1', 'clinician1', false),
    ('6e060000-0000-4000-c000-000000000003', 'E1010', '6e060000-0000-4000-b000-000000000102', 'EMPLOYEE_UPLOAD', current_date - 4, current_date + 3,
        null, null, null, null, null, null, null, 'RECEIVED', 'ISSUE_AFTER_START_PLUS_7', 1, 'employee-app', null, null, false),
    ('6e060000-0000-4000-c000-000000000004', 'E1003', '6e060000-0000-4000-b000-000000000031', 'PARTNER_ELECTRONIC', current_date - 9, current_date + 5,
        'Dr Omar Haddad', 'DHA-55821', 'DXB-2026-0412', 'DXB_CREW_CLINIC', current_date - 9, 'NOT_FIT', null, 'VALID', null, 9,
        'partner-clinic', 'partner-clinic', null, false),
    ('6e060000-0000-4000-c000-000000000005', 'E1001', '6e060000-0000-4000-b000-000000000014', 'EMPLOYEE_UPLOAD',
        (current_date - 25) - (extract(isodow from current_date - 25)::int - 1) + 4, (current_date - 25) - (extract(isodow from current_date - 25)::int - 1) + 4,
        null, null, null, null, null, null, null, 'SUSPICIOUS', 'DATES_DIFFER_FROM_OCR', 24, 'employee1', 'nurse1', null, false)
  ) as v(id, emp, ep, src, f, t, issuer, reg, num, partner, issued, fit, checklist, status, flags, age, received_by, validated_by, verified_by, escalated)
on conflict do nothing;

insert into absence.certificate_call (id, certificate_id, called_party, outcome, note, called_by, called_at) values
    ('6e060000-0000-4000-c100-000000000001', '6e060000-0000-4000-c000-000000000002', 'Riverside GP practice (reception)', 'CONFIRMED', null, 'clinician1', now() - interval '29 days'),
    ('6e060000-0000-4000-c100-000000000002', '6e060000-0000-4000-c000-000000000005', 'Issuing clinic', 'NO_ANSWER', null, 'nurse1', now() - interval '20 days')
on conflict do nothing;

-- ---------- early-intervention / LTA / AME queue ----------
insert into absence.queue_item (id, employee_id, rule_id, rule_code, rule_name, action, status, raised_at, sla_due_at, episode_id, detail, case_id,
                                case_ref, assigned_to, accepted_at, first_contact_at, closed_at, closed_by, close_reason) values
    ('6e060000-0000-4000-e000-000000000001', 'E1001', '6e060000-0000-4000-8000-000000000003', 'MON_FRI_PATTERN',
     'Monday / Friday absence pattern (3 in 180 days)', 'EARLY_INTERVENTION', 'NEW', now() - interval '1 day', now() + interval '4 days',
     '6e060000-0000-4000-b000-000000000015', '4 Monday / Friday absences within 180 days (threshold 3)', null, null, null, null, null, null, null, null),
    ('6e060000-0000-4000-e000-000000000002', 'E1004', '6e060000-0000-4000-8000-000000000004', 'LTA_28D', 'Long-term absence (28 days or more)',
     'LONG_TERM', 'CONTACTED', now() - interval '14 days', now() - interval '9 days', '6e060000-0000-4000-b000-000000000041',
     'Absence of 28 days (threshold 28)', '6e060000-0000-4000-a000-000000000002', 'GND-ABS-DEMO1', 'clinician1', now() - interval '13 days',
     now() - interval '12 days', null, null, null),
    ('6e060000-0000-4000-e000-000000000003', 'E1003', '6e060000-0000-4000-8000-000000000007', 'CREW_SICKNESS',
     'Crew sickness: more than 7 days or 3 in 90 days', 'AME_REVIEW', 'NEW', now() - interval '1 day', now(), '6e060000-0000-4000-b000-000000000031',
     'Crew sickness: Absence of 8 days (threshold 8)', '6e060000-0000-4000-a000-000000000003', 'CAB-AME-DEMO1', null, null, null, null, null, null),
    ('6e060000-0000-4000-e000-000000000004', 'E1010', '6e060000-0000-4000-8000-000000000008', 'CAT_DURATION_2D',
     'Catering food handlers: absence longer than 2 days', 'EARLY_INTERVENTION', 'NEW', now() - interval '1 day', now() + interval '4 days',
     '6e060000-0000-4000-b000-000000000102', 'Absence of 4 days (threshold 3)', null, null, null, null, null, null, null, null)
on conflict do nothing;

insert into absence.contact (id, queue_item_id, case_id, employee_id, contacted_at, channel, outcome, note, contacted_by, next_contact_due) values
    ('6e060000-0000-4000-e100-000000000001', '6e060000-0000-4000-e000-000000000002', '6e060000-0000-4000-a000-000000000002', 'E1004',
     now() - interval '12 days', 'PHONE', 'REACHED', null, 'clinician1', current_date - 1),
    ('6e060000-0000-4000-e100-000000000002', null, '6e060000-0000-4000-a000-000000000002', 'E1004',
     now() - interval '1 day', 'PHONE', 'NO_ANSWER', null, 'clinician1', current_date + 3)
on conflict do nothing;

-- ---------- long-term absence pathway (E1004) ----------
insert into absence.lta_pathway (case_id, case_ref, employee_id, episode_id, absence_start, status, hr_stage, hr_stage_note, hr_stage_by, hr_stage_at,
                                 rtw_date, rtw_plan_id, started_at, closed_at) values
    ('6e060000-0000-4000-a000-000000000002', 'GND-ABS-DEMO1', 'E1004', '6e060000-0000-4000-b000-000000000041', current_date - 42, 'ACTIVE',
     'INFORMAL_REVIEW', 'Welfare meeting held with the line manager', 'hr1', now() - interval '5 days', null, null, now() - interval '14 days', null)
on conflict do nothing;

insert into absence.lta_milestone (id, case_id, week, milestone_type, due_date, status, completed_at, completed_by, note, overdue_notified_at,
                                   appointment_id) values
    ('6e060000-0000-4000-a300-000000000001', '6e060000-0000-4000-a000-000000000002', 2,  'CONTACT',           current_date - 28, 'DONE',    now() - interval '12 days', 'clinician1', null, null, null),
    ('6e060000-0000-4000-a300-000000000002', '6e060000-0000-4000-a000-000000000002', 4,  'ADVICE_REPORT',     current_date - 14, 'OVERDUE', null, null, null, now() - interval '13 days', null),
    ('6e060000-0000-4000-a300-000000000003', '6e060000-0000-4000-a000-000000000002', 8,  'ADVICE_REPORT',     current_date + 14, 'PENDING', null, null, null, null, null),
    ('6e060000-0000-4000-a300-000000000004', '6e060000-0000-4000-a000-000000000002', 8,  'CASE_CONFERENCE',   current_date + 14, 'PENDING', null, null, null, null, null),
    ('6e060000-0000-4000-a300-000000000005', '6e060000-0000-4000-a000-000000000002', 12, 'HR_REVIEW',         current_date + 42, 'PENDING', null, null, null, null, null),
    ('6e060000-0000-4000-a300-000000000006', '6e060000-0000-4000-a000-000000000002', 12, 'ADVICE_REPORT',     current_date + 42, 'PENDING', null, null, null, null, null),
    ('6e060000-0000-4000-a300-000000000007', '6e060000-0000-4000-a000-000000000002', 26, 'IHR_CONSIDERATION', current_date + 140, 'PENDING', null, null, null, null, null)
on conflict do nothing;

-- ---------- management advice requests ----------
insert into absence.advice_request (id, ref, employee_id, requested_by, requester_role, requester_employee_id, question_codes, context, episode_id,
                                    status, requested_at, sla_due_at, case_id, case_ref, accepted_by, accepted_at, withdrawn_at, updated_at) values
    ('6e060000-0000-4000-d000-000000000001', 'ADV-' || extract(year from current_date)::int || '-900001', 'E1001', 'manager1', 'MANAGER', 'M2001',
     'FIT_FOR_ROLE,ADJUSTMENTS,EXPECTED_RTW', null, '6e060000-0000-4000-b000-000000000015', 'AWAITING_EMPLOYEE', now() - interval '4 days',
     now() + interval '3 days', '6e060000-0000-4000-a000-000000000001', 'CAT-ABS-DEMO1', 'clinician1', now() - interval '3 days', null,
     now() - interval '1 day'),
    ('6e060000-0000-4000-d000-000000000002', 'ADV-' || extract(year from current_date)::int || '-900002', 'E1010', 'manager1', 'MANAGER', 'M2001',
     'EXPECTED_RTW,PHASED_RETURN', null, '6e060000-0000-4000-b000-000000000102', 'SUBMITTED', now() - interval '6 hours',
     now() + interval '6 days', null, null, null, null, null, now() - interval '6 hours')
on conflict do nothing;

insert into absence.advice_report (id, request_id, version, status, fitness, adjustments, restrictions, prognosis, expected_return, review_date,
                                   answers, drafted_by, drafted_at, signed_by, signed_at, employee_decision, employee_comment, decline_reason,
                                   decided_at, document_id, released_at) values
    ('6e060000-0000-4000-d100-000000000001', '6e060000-0000-4000-d000-000000000001', 1, 'AWAITING_EMPLOYEE', 'FIT_WITH_ADJUSTMENTS',
     'Lighter trolley duties and regular short breaks for the next four weeks.', 'No lifting above 10 kg for four weeks.', 'RTW_WITHIN_2_WEEKS',
     current_date + 7, current_date + 28,
     '{"FIT_FOR_ROLE":"Yes, with the adjustments described below.","ADJUSTMENTS":"Lighter trolley duties and regular short breaks for four weeks.","EXPECTED_RTW":"Full duties are expected within two weeks."}',
     'physician1', now() - interval '2 days', 'physician1', now() - interval '1 day', null, null, null, null, null, null)
on conflict do nothing;
