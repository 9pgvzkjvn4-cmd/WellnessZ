-- Demo data for E09 mental health, psychosocial risk & peer support – local and test profiles only (flyway location classpath:db/demo).
-- Idempotent (fixed ids e09de000-…, "on conflict do nothing"); dates are relative to current_date at first load. Encrypted columns
-- (contact phones, self-referral message, notes, callback phone) are written by MentalHealthDemoSeeder (local / test profiles).
--
--   SUP-<year>-900001  E1005 Olivia Office – self-referral (work stress), PHQ-9 11 = MODERATE, EAP referral EAP-<year>-900001 SENT to
--                      EAP_WELLBEING (eap1 sees it in the partner portal and gives attendance / closure feedback)
--   SUP-<year>-900002  E1010 Cathy Cook – self-screen with PHQ-9 item 9 > 0 → URGENT, open urgent item (call back in 3 h) with the kernel
--                      CALL_BACK task for psych1; shares FITNESS_ONLY with her manager (manager1 / CATERING)
--   SUP-<year>-900003  E7001 Fred Flyer – referred from the CISM event, relieved from flight duties (roster UNFIT, AME ame1 decides return)
--   Peer support: volunteers peer1 (Pete Peer, LHR, available, trained), crewpeer2 (Anna, LHR, available, trained), crewpeer3 (Sam, MAN,
--                 training expired); contact request PS-DEMQ-7K2R ("Blue Jay") routed to peer1; 9 logged contacts last quarter
--   CISM-<year>-900001 fume event on WZ101 two days ago – affected E1002 / E1003 / E7001 from the crew roster; offers sent to E1002
--                      (accepted) and E1003, E7001 not yet offered (72-h SLA still running)
--   Psychosocial: CATERING (RED, one overdue action), GROUND (AMBER, review overdue)

-- ---------- consent for the EAP referral ----------
insert into platform.consent (id, employee_id, purpose, scope, status, notice_version, granted_at, captured_by) values
    ('e09de000-0000-4000-d100-000000000001', 'E1005', 'EAP_REFERRAL', 'Referral to the EAP counselling partner', 'GRANTED', '2026.1',
     now() - interval '2 days', 'psych1')
on conflict do nothing;

-- ---------- restricted kernel cases (category PSYCHOLOGICAL, classification RESTRICTED_CLINICAL) ----------
insert into casemgmt.oh_case (id, case_ref, employee_id, employee_display_name, org_unit_code, org_unit_path, subsidiary_code, cost_centre,
                              job_code, location_code, category, case_type, source, source_ref, status, classification, fitness_status,
                              restriction_until, urgency, safety_category, owner_username, early_intervention, created_at, updated_at)
values
    ('e09de000-0000-4000-8000-000000000001', 'CORP-MH-DEMO1', 'E1005', 'Olivia Office', 'CORP', '/AIRLINE/CORP', 'CORP', 'CC-COR-500',
     'OFFICE_ADMIN', 'HQ', 'PSYCHOLOGICAL', 'MENTAL_HEALTH', 'SELF_REFERRAL', 'demo:e09-sup-1', 'ASSESSMENT', 'RESTRICTED_CLINICAL',
     'NOT_ASSESSED', null, 'ROUTINE', null, 'psych1', false, now() - interval '6 days', now() - interval '1 day'),
    ('e09de000-0000-4000-8000-000000000002', 'CAT-MH-DEMO2', 'E1010', 'Cathy Cook', 'CATERING', '/AIRLINE/CATERING', 'CAT', 'CC-CAT-110',
     'CATERING_ASSOC', 'LHR', 'PSYCHOLOGICAL', 'MENTAL_HEALTH', 'SELF_REFERRAL', 'demo:e09-sup-2', 'INTAKE', 'RESTRICTED_CLINICAL',
     'NOT_ASSESSED', null, 'URGENT', 'FOOD_HANDLER', 'psych1', false, now() - interval '1 hour', now() - interval '1 hour'),
    ('e09de000-0000-4000-8000-000000000003', 'FLT-MH-DEMO3', 'E7001', 'Fred Flyer', 'FLIGHTOPS', '/AIRLINE/OPS/FLIGHTOPS', 'FLT', 'CC-FLT-200',
     'PILOT', 'LHR', 'PSYCHOLOGICAL', 'MENTAL_HEALTH', 'SYSTEM_TRIGGER', 'demo:e09-sup-3', 'ASSESSMENT', 'RESTRICTED_CLINICAL',
     'TEMPORARILY_UNFIT', current_date + 14, 'ROUTINE', 'FLIGHT_CREW', 'psych1', false, now() - interval '1 day', now() - interval '1 day')
on conflict do nothing;

insert into casemgmt.case_task (id, case_id, task_type, title, due_at, assignee, status, created_by, created_at) values
    ('e09de000-0000-4000-c100-000000000001', 'e09de000-0000-4000-8000-000000000002', 'CALL_BACK', 'Same-day confidential call back',
     now() + interval '3 hours', 'psych1', 'OPEN', 'system', now() - interval '1 hour')
on conflict do nothing;

-- ---------- support cases ----------
insert into mentalhealth.ref_counter (prefix, seq_year, last_value) values
    ('SUP', extract(year from current_date)::int, 0), ('EAP', extract(year from current_date)::int, 0),
    ('CISM', extract(year from current_date)::int, 0), ('SMS', extract(year from current_date)::int, 0)
on conflict do nothing;

insert into mentalhealth.support_case (id, case_ref, kernel_case_id, employee_id, status, risk, concern, source, source_type, source_ref,
                                       preferred_contact, best_time, next_contact_by, opened_at, opened_by, last_contact_at, sharing,
                                       sharing_changed_at, updated_at)
values
    ('e09de000-0000-4000-a000-000000000001', 'SUP-' || extract(year from current_date)::int || '-900001', 'e09de000-0000-4000-8000-000000000001',
     'E1005', 'REFERRED', 'MODERATE', 'WORK_STRESS', 'SELF', null, null, 'PHONE', 'Weekdays after 17:00', now() - interval '4 days',
     now() - interval '6 days', 'office1', now() - interval '2 days', 'NOTHING', null, now() - interval '1 day'),
    ('e09de000-0000-4000-a000-000000000002', 'SUP-' || extract(year from current_date)::int || '-900002', 'e09de000-0000-4000-8000-000000000002',
     'E1010', 'SCREENED', 'URGENT', 'LOW_MOOD', 'SELF', null, null, 'PHONE', 'Any time', null,
     now() - interval '1 hour', 'cook1', now() - interval '1 hour', 'FITNESS_ONLY', now() - interval '50 minutes', now() - interval '1 hour'),
    ('e09de000-0000-4000-a000-000000000003', 'SUP-' || extract(year from current_date)::int || '-900003', 'e09de000-0000-4000-8000-000000000003',
     'E7001', 'RELIEF', 'HIGH', 'TRAUMA', 'CISM', 'CISM_EVENT', 'e09de000-0000-4000-f000-000000000001:E7001', null, null, null,
     now() - interval '1 day', 'psych1', now() - interval '1 day', 'NOTHING', null, now() - interval '1 day')
on conflict do nothing;

insert into mentalhealth.screen_result (id, support_case_id, employee_id, instrument, answers, score, band, urgent, administered_by, created_at) values
    ('e09de000-0000-4000-b000-000000000001', 'e09de000-0000-4000-a000-000000000001', 'E1005', 'PHQ9', '1,2,1,2,1,1,2,1,0', 11, 'MODERATE', false,
     'SELF', now() - interval '6 days'),
    ('e09de000-0000-4000-b000-000000000002', 'e09de000-0000-4000-a000-000000000002', 'E1010', 'PHQ9', '2,2,1,1,1,1,1,1,1', 11, 'MODERATE', true,
     'SELF', now() - interval '1 hour')
on conflict do nothing;

insert into mentalhealth.urgent_item (id, support_case_id, screen_id, kernel_task_id, trigger_code, call_back_by, status, created_at) values
    ('e09de000-0000-4000-c000-000000000001', 'e09de000-0000-4000-a000-000000000002', 'e09de000-0000-4000-b000-000000000002',
     'e09de000-0000-4000-c100-000000000001', 'PHQ9_ITEM9', now() + interval '3 hours', 'OPEN', now() - interval '1 hour')
on conflict do nothing;

insert into mentalhealth.support_contact (id, support_case_id, contact_type, outcome, actor, occurred_at) values
    ('e09de000-0000-4000-a200-000000000001', 'e09de000-0000-4000-a000-000000000001', 'SELF_REFERRAL', null, 'office1', now() - interval '6 days'),
    ('e09de000-0000-4000-a200-000000000002', 'e09de000-0000-4000-a000-000000000001', 'PHONE_ASSESSMENT', 'COMPLETED', 'psych1', now() - interval '3 days')
on conflict do nothing;

insert into mentalhealth.support_event (id, support_case_id, event_type, summary, actor, occurred_at) values
    ('e09de000-0000-4000-a100-000000000001', 'e09de000-0000-4000-a000-000000000001', 'OPENED', 'Support case opened (SELF)', 'office1', now() - interval '6 days'),
    ('e09de000-0000-4000-a100-000000000002', 'e09de000-0000-4000-a000-000000000001', 'SCREEN_COMPLETED', 'PHQ9 screen completed (MODERATE)', 'office1', now() - interval '6 days'),
    ('e09de000-0000-4000-a100-000000000003', 'e09de000-0000-4000-a000-000000000001', 'EAP_REFERRED', 'Referred to Wellbeing Assist EAP (counselling partner)', 'psych1', now() - interval '1 day'),
    ('e09de000-0000-4000-a100-000000000004', 'e09de000-0000-4000-a000-000000000002', 'OPENED', 'Support case opened (SELF)', 'cook1', now() - interval '1 hour'),
    ('e09de000-0000-4000-a100-000000000005', 'e09de000-0000-4000-a000-000000000002', 'URGENT_RAISED', 'Urgent item raised – same-day call back', 'cook1', now() - interval '1 hour'),
    ('e09de000-0000-4000-a100-000000000006', 'e09de000-0000-4000-a000-000000000003', 'OPENED', 'Support case opened (CISM)', 'psych1', now() - interval '1 day'),
    ('e09de000-0000-4000-a100-000000000007', 'e09de000-0000-4000-a000-000000000003', 'RELIEF_STARTED', 'Relieved from duties', 'ame1', now() - interval '1 day')
on conflict do nothing;

insert into mentalhealth.eap_referral (id, ref, support_case_id, employee_id, partner_code, sessions_authorised, urgency, consent_id, status,
                                       first_appointment_due_by, sent_at, sent_by, updated_at)
values ('e09de000-0000-4000-d000-000000000001', 'EAP-' || extract(year from current_date)::int || '-900001', 'e09de000-0000-4000-a000-000000000001',
        'E1005', 'EAP_WELLBEING', 6, 'ROUTINE', 'e09de000-0000-4000-d100-000000000001', 'SENT', current_date + 6, now() - interval '1 day',
        'psych1', now() - interval '1 day')
on conflict do nothing;

insert into mentalhealth.relief (id, support_case_id, employee_id, from_date, expected_until, status, created_by, created_at) values
    ('e09de000-0000-4000-e100-000000000001', 'e09de000-0000-4000-a000-000000000003', 'E7001', current_date - 1, current_date + 14, 'ACTIVE', 'ame1',
     now() - interval '1 day')
on conflict do nothing;

-- ---------- peer support (no employee identifiers) ----------
insert into mentalhealth.peer_volunteer (id, username, display_name, email, base, languages, available, created_at, updated_at) values
    ('e09de000-0000-4000-9000-000000000001', 'peer1',     'Pete Peer',     'peer1@wellnessz.local',     'LHR', 'en',    true,  now() - interval '200 days', now() - interval '10 days'),
    ('e09de000-0000-4000-9000-000000000002', 'crewpeer2', 'Anna Alvarez',  'crewpeer2@wellnessz.local', 'LHR', 'en,fr', true,  now() - interval '300 days', now() - interval '30 days'),
    ('e09de000-0000-4000-9000-000000000003', 'crewpeer3', 'Sam Sutherland', null,                       'MAN', 'en',    false, now() - interval '700 days', now() - interval '90 days')
on conflict do nothing;

insert into mentalhealth.peer_training (id, volunteer_id, course, completed_on, expires_on) values
    ('e09de000-0000-4000-9100-000000000001', 'e09de000-0000-4000-9000-000000000001', 'Peer support foundation (CISM / active listening)', current_date - 180, current_date + 550),
    ('e09de000-0000-4000-9100-000000000002', 'e09de000-0000-4000-9000-000000000002', 'Peer support foundation (CISM / active listening)', current_date - 290, current_date + 440),
    ('e09de000-0000-4000-9100-000000000003', 'e09de000-0000-4000-9000-000000000002', 'Refresher – fatigue and sleep', current_date - 40, null),
    ('e09de000-0000-4000-9100-000000000004', 'e09de000-0000-4000-9000-000000000003', 'Peer support foundation (CISM / active listening)', current_date - 900, current_date - 170)
on conflict do nothing;

insert into mentalhealth.peer_contact_request (id, contact_code, alias, base, language, volunteer_username, status, requested_at) values
    ('e09de000-0000-4000-9200-000000000001', 'PS-DEMQ-7K2R', 'Blue Jay', 'LHR', 'en', 'peer1', 'OPEN', now() - interval '3 hours')
on conflict do nothing;

-- nine contacts in the previous quarter: LHR/STRESS 6 (published), LHR/FATIGUE 2 and SIN/TRAUMA 1 (small cells, suppressed)
insert into mentalhealth.peer_contact (id, contact_code, volunteer_username, contact_date, duration_minutes, category, outcome, base, created_at)
select ('e09de000-0000-4000-9300-' || lpad(v.n::text, 12, '0'))::uuid, 'PS-QDEM-' || lpad(v.n::text, 4, '0'),
       case when v.n % 2 = 0 then 'peer1' else 'crewpeer2' end,
       (date_trunc('quarter', current_date)::date - (v.n * 7)), 20 + v.n * 5, v.category, 'SUPPORTED', v.base, now() - interval '30 days'
from (values (1, 'STRESS', 'LHR'), (2, 'STRESS', 'LHR'), (3, 'STRESS', 'LHR'), (4, 'STRESS', 'LHR'), (5, 'STRESS', 'LHR'), (6, 'STRESS', 'LHR'),
             (7, 'FATIGUE', 'LHR'), (8, 'FATIGUE', 'LHR'), (9, 'TRAUMA', 'SIN')) as v(n, category, base)
on conflict do nothing;

-- ---------- CISM: fume event on WZ101 two days ago (crew roster from R__demo_reference_duties.sql) ----------
insert into mentalhealth.cism_event (id, ref, title, category, occurred_at, location, duty_refs, source, incident_id, offer_due_by, created_by, created_at)
values ('e09de000-0000-4000-f000-000000000001', 'CISM-' || extract(year from current_date)::int || '-900001', 'Critical incident – fume event (WZ101)',
        'FUME_EVENT', (current_date - 2) + time '15:00', 'WZ101 LHR–SIN, aircraft G-WZAA', 'WZ101', 'INCIDENT', 'e09de000-0000-4000-f0f0-000000000001',
        (current_date - 2) + time '15:00' + interval '72 hours', 'system', (current_date - 2) + time '18:00')
on conflict do nothing;

insert into mentalhealth.cism_affected (id, event_id, employee_id, duty_role, offer_sent_at, response, responded_at, session_type,
                                        follow_up_1w_due, follow_up_4w_due, referred, support_case_id, updated_at)
values
    ('e09de000-0000-4000-f100-000000000001', 'e09de000-0000-4000-f000-000000000001', 'E1002', 'FIRST_OFFICER', now() - interval '1 day',
     'ACCEPTED', now() - interval '20 hours', 'INDIVIDUAL', current_date + 5, current_date + 26, false, null, now() - interval '20 hours'),
    ('e09de000-0000-4000-f100-000000000002', 'e09de000-0000-4000-f000-000000000001', 'E1003', 'CABIN_CREW', now() - interval '1 day',
     null, null, null, current_date + 5, current_date + 26, false, null, now() - interval '1 day'),
    ('e09de000-0000-4000-f100-000000000003', 'e09de000-0000-4000-f000-000000000001', 'E7001', 'CAPTAIN', null,
     null, null, null, current_date + 5, current_date + 26, true, 'e09de000-0000-4000-a000-000000000003', now() - interval '1 day')
on conflict do nothing;

-- ---------- psychosocial risk register ----------
insert into mentalhealth.psychosocial_assessment (id, unit_code, assessed_on, review_date, factors, overall_score, rag, assessed_by, created_at) values
    ('e09de000-0000-4000-7000-000000000001', 'CATERING', current_date - 60, current_date + 120,
     '[{"factor":"WORKLOAD","likelihood":4,"severity":4,"controls":"Peak-day staffing plan; overtime cap"},'
     || '{"factor":"SHIFT_FATIGUE","likelihood":3,"severity":3,"controls":"Early-shift rotation reviewed quarterly"},'
     || '{"factor":"VIOLENCE_AGGRESSION","likelihood":2,"severity":2,"controls":"Incident reporting; supervisor escalation"}]',
     16, 'RED', 'hse1', now() - interval '60 days'),
    ('e09de000-0000-4000-7000-000000000002', 'GROUND', current_date - 400, current_date - 35,
     '[{"factor":"VIOLENCE_AGGRESSION","likelihood":3,"severity":4,"controls":"Gate-agent de-escalation briefing"},'
     || '{"factor":"ISOLATION","likelihood":2,"severity":3,"controls":"Buddy system on night shifts"}]',
     12, 'AMBER', 'hse1', now() - interval '400 days')
on conflict do nothing;

insert into mentalhealth.psychosocial_action (id, assessment_id, title, owner, due_date, status, created_by, created_at, updated_at, completed_at) values
    ('e09de000-0000-4000-7100-000000000001', 'e09de000-0000-4000-7000-000000000001', 'Review staffing of early shifts on line 2',
     'Catering operations manager', current_date - 5, 'OPEN', 'hse1', now() - interval '60 days', now() - interval '60 days', null),
    ('e09de000-0000-4000-7100-000000000002', 'e09de000-0000-4000-7000-000000000001', 'Introduce a fixed rest-break schedule',
     'Catering shift lead', current_date - 20, 'DONE', 'hse1', now() - interval '60 days', now() - interval '25 days', now() - interval '25 days'),
    ('e09de000-0000-4000-7100-000000000003', 'e09de000-0000-4000-7000-000000000002', 'De-escalation training for ramp and gate staff',
     'Ground training lead', current_date + 30, 'OPEN', 'hse1', now() - interval '400 days', now() - interval '30 days', null)
on conflict do nothing;
