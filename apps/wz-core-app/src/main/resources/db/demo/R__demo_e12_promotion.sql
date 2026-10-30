-- Demo data for E12 health promotion – local and test profiles ONLY (flyway location classpath:db/demo).
-- Idempotent (fixed ids e12de000-…, on conflict do nothing); dates relative to current_date. Encrypted columns (screening
-- measures / flags, wearable values) are written by PromotionDemoSeeder (local / test profiles), which also opens the
-- follow-up case of the past abnormal screening result.
--
--   MSK campaign (portfolio)          e12c0000-0000-4000-8000-000000000001  ACTIVE for GROUND / CARGO / CATERING, 7 invited, 5 participants
--   screening event tomorrow          e12de000-0000-4000-8000-000000000101  BIOMETRIC at HQ-MC, participants E1001, E1005, M2001 (results open)
--   past screening event              e12de000-0000-4000-8000-000000000102  MSK at LHR-CAT 20 days ago, 4 results, E1004 abnormal → follow-up case
--   ergonomic assessment (catering)   e12de000-0000-4000-8000-000000000301  MANUAL_HANDLING trolley loading, E1010, RED, one action overdue
--   ergonomic assessment (office)     e12de000-0000-4000-8000-000000000302  DSE_WORKSTATION, E1005, AMBER
--   WEARABLE_DATA consent / enrolment e12de000-0000-4000-8000-000000000501 / …0502  E1001, FITBIT, 14 days of steps (seeder)

-- ---------- MSK campaign goes live (portfolio item from V20) ----------
update promotion.campaign
   set status = 'ACTIVE', start_date = current_date - 60, end_date = current_date + 300, published_at = now() - interval '60 days',
       published_by = 'promo1', updated_by = 'demo', updated_at = now()
 where id = 'e12c0000-0000-4000-8000-000000000001' and status = 'PLANNED' and published_at is null;

insert into promotion.campaign_invitation (id, campaign_id, employee_id, unit_code, invited_at) values
 ('e12de000-0000-4000-8000-000000000011', 'e12c0000-0000-4000-8000-000000000001', 'E1001', 'CATERING', now() - interval '60 days'),
 ('e12de000-0000-4000-8000-000000000012', 'e12c0000-0000-4000-8000-000000000001', 'E1010', 'CATERING', now() - interval '60 days'),
 ('e12de000-0000-4000-8000-000000000013', 'e12c0000-0000-4000-8000-000000000001', 'M2001', 'CATERING', now() - interval '60 days'),
 ('e12de000-0000-4000-8000-000000000014', 'e12c0000-0000-4000-8000-000000000001', 'E1004', 'GROUND',   now() - interval '60 days'),
 ('e12de000-0000-4000-8000-000000000015', 'e12c0000-0000-4000-8000-000000000001', 'E1007', 'GROUND',   now() - interval '60 days'),
 ('e12de000-0000-4000-8000-000000000016', 'e12c0000-0000-4000-8000-000000000001', 'E1009', 'GROUND',   now() - interval '60 days'),
 ('e12de000-0000-4000-8000-000000000017', 'e12c0000-0000-4000-8000-000000000001', 'E1008', 'CARGO',    now() - interval '60 days')
on conflict do nothing;

-- participation flowing back from the campaign app (and the past MSK screening / wearable enrolment below)
insert into promotion.participation (id, campaign_id, employee_id, unit_code, activity, occurred_at, value, source, source_ref, received_at) values
 ('e12de000-0000-4000-8000-000000000021', 'e12c0000-0000-4000-8000-000000000001', 'E1001', 'CATERING', 'MANUAL_HANDLING_WORKSHOP', date_trunc('day', now()) - interval '50 days' + interval '10 hours', null, 'APP', 'app:demo-1', now() - interval '50 days'),
 ('e12de000-0000-4000-8000-000000000022', 'e12c0000-0000-4000-8000-000000000001', 'E1010', 'CATERING', 'MANUAL_HANDLING_WORKSHOP', date_trunc('day', now()) - interval '50 days' + interval '10 hours', null, 'APP', 'app:demo-2', now() - interval '50 days'),
 ('e12de000-0000-4000-8000-000000000023', 'e12c0000-0000-4000-8000-000000000001', 'M2001', 'CATERING', 'STRETCH_CHALLENGE',        date_trunc('day', now()) - interval '30 days' + interval '8 hours',  12,   'APP', 'app:demo-3', now() - interval '30 days'),
 ('e12de000-0000-4000-8000-000000000024', 'e12c0000-0000-4000-8000-000000000001', 'E1004', 'GROUND',   'MANUAL_HANDLING_WORKSHOP', date_trunc('day', now()) - interval '45 days' + interval '14 hours', null, 'APP', 'app:demo-4', now() - interval '45 days'),
 ('e12de000-0000-4000-8000-000000000025', 'e12c0000-0000-4000-8000-000000000001', 'E1007', 'GROUND',   'STRETCH_CHALLENGE',        date_trunc('day', now()) - interval '25 days' + interval '7 hours',  9,    'APP', 'app:demo-5', now() - interval '25 days'),
 ('e12de000-0000-4000-8000-000000000026', 'e12c0000-0000-4000-8000-000000000001', 'E1004', 'GROUND',   'SCREENING_MSK',            date_trunc('day', now()) - interval '20 days' + interval '9 hours',  null, 'SCREENING', 'e12de000-0000-4000-8000-000000000221', now() - interval '20 days'),
 ('e12de000-0000-4000-8000-000000000027', 'e12c0000-0000-4000-8000-000000000001', 'E1001', 'CATERING', 'WEARABLE_ENROLLED',        date_trunc('day', now()) - interval '15 days' + interval '9 hours',  null, 'WEARABLE', 'e12de000-0000-4000-8000-000000000502', now() - interval '15 days')
on conflict do nothing;

-- ---------- screening event tomorrow (results entered in the E2E) ----------
insert into promotion.screening_event (id, event_ref, campaign_id, event_type, site_code, event_date, status, created_by, created_at, updated_at) values
 ('e12de000-0000-4000-8000-000000000101', 'SCR-DEMO-0101', null, 'BIOMETRIC', 'HQ-MC', current_date + 1, 'PLANNED', 'promo1', now() - interval '7 days', now() - interval '7 days'),
 ('e12de000-0000-4000-8000-000000000102', 'SCR-DEMO-0102', 'e12c0000-0000-4000-8000-000000000001', 'MSK', 'LHR-CAT', current_date - 20, 'COMPLETED', 'promo1', now() - interval '35 days', now() - interval '20 days')
on conflict do nothing;

insert into promotion.screening_participant (id, event_id, employee_id, unit_code, status, added_by, added_at) values
 ('e12de000-0000-4000-8000-000000000111', 'e12de000-0000-4000-8000-000000000101', 'E1001', 'CATERING', 'INVITED', 'promo1', now() - interval '7 days'),
 ('e12de000-0000-4000-8000-000000000112', 'e12de000-0000-4000-8000-000000000101', 'E1005', 'CORP',     'INVITED', 'promo1', now() - interval '7 days'),
 ('e12de000-0000-4000-8000-000000000113', 'e12de000-0000-4000-8000-000000000101', 'M2001', 'CATERING', 'INVITED', 'promo1', now() - interval '7 days'),
 ('e12de000-0000-4000-8000-000000000211', 'e12de000-0000-4000-8000-000000000102', 'E1004', 'GROUND',   'SCREENED', 'promo1', now() - interval '35 days'),
 ('e12de000-0000-4000-8000-000000000212', 'e12de000-0000-4000-8000-000000000102', 'E1007', 'GROUND',   'SCREENED', 'promo1', now() - interval '35 days'),
 ('e12de000-0000-4000-8000-000000000213', 'e12de000-0000-4000-8000-000000000102', 'E1009', 'GROUND',   'SCREENED', 'promo1', now() - interval '35 days'),
 ('e12de000-0000-4000-8000-000000000214', 'e12de000-0000-4000-8000-000000000102', 'E1010', 'CATERING', 'SCREENED', 'promo1', now() - interval '35 days')
on conflict do nothing;

-- past MSK screening results: measures / flags are encrypted and written by the seeder; E1004 is abnormal (MSK score 8)
insert into promotion.screening_result (id, event_id, participant_id, employee_id, unit_code, outcome, recorded_by, recorded_at) values
 ('e12de000-0000-4000-8000-000000000221', 'e12de000-0000-4000-8000-000000000102', 'e12de000-0000-4000-8000-000000000211', 'E1004', 'GROUND',   'ABNORMAL', 'nurse1', now() - interval '20 days'),
 ('e12de000-0000-4000-8000-000000000222', 'e12de000-0000-4000-8000-000000000102', 'e12de000-0000-4000-8000-000000000212', 'E1007', 'GROUND',   'NORMAL',   'nurse1', now() - interval '20 days'),
 ('e12de000-0000-4000-8000-000000000223', 'e12de000-0000-4000-8000-000000000102', 'e12de000-0000-4000-8000-000000000213', 'E1009', 'GROUND',   'NORMAL',   'nurse1', now() - interval '20 days'),
 ('e12de000-0000-4000-8000-000000000224', 'e12de000-0000-4000-8000-000000000102', 'e12de000-0000-4000-8000-000000000214', 'E1010', 'CATERING', 'NORMAL',   'nurse1', now() - interval '20 days')
on conflict do nothing;

-- ---------- ergonomic assessments ----------
insert into promotion.ergonomic_assessment (id, assessment_ref, assessment_type, unit_code, job_code, employee_id, location, checklist, findings,
                                            risk_score, rag, job_demand, assessed_by, assessed_at, updated_at) values
 ('e12de000-0000-4000-8000-000000000301', 'ERG-DEMO-0301', 'MANUAL_HANDLING', 'CATERING', 'CATERING_ASSOC', 'E1010', 'LHR catering unit – trolley loading bay',
  '{"CARRY_DISTANCE":1,"CONFINED_POSTURE":2,"ENVIRONMENT":1,"EQUIPMENT":2,"FLOOR":1,"FREQUENCY":2,"LOAD_DISTANCE":2,"LOAD_WEIGHT":2,"PUSH_PULL":3,"REACH_HIGH":3,"TRAINING":1,"TWIST_STOOP":2}',
  'Full meal trolleys are pushed up the loading ramp by one person; top-shelf drawers are loaded above shoulder height.',
  67, 'RED',
  '{"jobCode":"CATERING_ASSOC","title":"Catering associate","unit":"CAT","demands":{"LIFT_KG":20,"PUSH_PULL_KG":40,"CLIMB":false,"AIRSIDE_DRIVE":false,"NIGHT":true,"KNIFE":true,"CHEMICAL":true,"RESPIRATOR":false,"NOISE":false,"HEAT":true,"HEIGHT":false,"FLIGHT_DUTY":false,"FOOD_HANDLING":true,"HOURS_PER_DAY":10,"SCREEN_HOURS":0,"SAFETY_CRITICAL":false},"sitting":"LOW"}',
  'ergo1', now() - interval '45 days', now() - interval '45 days'),
 ('e12de000-0000-4000-8000-000000000302', 'ERG-DEMO-0302', 'DSE_WORKSTATION', 'CORP', 'OFFICE_ADMIN', 'E1005', 'HQ level 3 – open-plan desk 3.14',
  '{"BREAKS":1,"CHAIR":2,"DESK_SPACE":0,"DISCOMFORT":1,"KEYBOARD_MOUSE":1,"LIGHTING_GLARE":1,"POSTURE":1,"SCREEN_POSITION":2,"SCREEN_TIME":2}',
  'Laptop used without a separate screen; chair backrest not adjusted.',
  42, 'AMBER',
  '{"jobCode":"OFFICE_ADMIN","title":"Office administrator","unit":"CORP","demands":{"LIFT_KG":5,"PUSH_PULL_KG":5,"CLIMB":false,"AIRSIDE_DRIVE":false,"NIGHT":false,"KNIFE":false,"CHEMICAL":false,"RESPIRATOR":false,"NOISE":false,"HEAT":false,"HEIGHT":false,"FLIGHT_DUTY":false,"FOOD_HANDLING":false,"HOURS_PER_DAY":8,"SCREEN_HOURS":7,"SAFETY_CRITICAL":false},"sitting":"HIGH"}',
  'ergo1', now() - interval '10 days', now() - interval '10 days')
on conflict do nothing;

insert into promotion.ergonomic_action (id, assessment_id, action_ref, title, owner, due_date, status, unit_code, created_by, created_at, updated_by,
                                        updated_at, closed_at) values
 ('e12de000-0000-4000-8000-000000000311', 'e12de000-0000-4000-8000-000000000301', 'ERG-DEMO-0301-A1', 'Introduce a powered trolley tug for the loading ramp',
  'M2001', current_date - 5, 'OPEN', 'CATERING', 'ergo1', now() - interval '45 days', 'ergo1', now() - interval '45 days', null),
 ('e12de000-0000-4000-8000-000000000312', 'e12de000-0000-4000-8000-000000000301', 'ERG-DEMO-0301-A2', 'Lower the top storage shelf of the trolley racks',
  'ergo1', current_date - 25, 'DONE', 'CATERING', 'ergo1', now() - interval '45 days', 'ergo1', now() - interval '22 days', now() - interval '22 days'),
 ('e12de000-0000-4000-8000-000000000313', 'e12de000-0000-4000-8000-000000000302', 'ERG-DEMO-0302-A1', 'Provide a separate monitor on an adjustable arm',
  'ergo1', current_date + 14, 'OPEN', 'CORP', 'ergo1', now() - interval '10 days', 'ergo1', now() - interval '10 days', null)
on conflict do nothing;

-- ---------- hearing-conservation education attendance (alongside audiometry) ----------
insert into promotion.education_attendance (id, employee_id, unit_code, programme, session_date, campaign_id, recorded_by, recorded_at) values
 ('e12de000-0000-4000-8000-000000000401', 'E1004', 'GROUND', 'HEARING_CONSERVATION', current_date - 30, null, 'nurse1', now() - interval '30 days'),
 ('e12de000-0000-4000-8000-000000000402', 'E1006', 'ENG',    'HEARING_CONSERVATION', current_date - 45, null, 'nurse1', now() - interval '45 days'),
 ('e12de000-0000-4000-8000-000000000403', 'E1008', 'CARGO',  'HEARING_CONSERVATION', current_date - 10, null, 'nurse2', now() - interval '10 days')
on conflict do nothing;

-- ---------- wearable programme: E1001 shares daily steps for the MSK campaign (values written by the seeder) ----------
insert into platform.consent (id, employee_id, purpose, scope, status, notice_version, granted_at, captured_by) values
 ('e12de000-0000-4000-8000-000000000501', 'E1001', 'WEARABLE_DATA', 'campaign:e12c0000-0000-4000-8000-000000000001', 'GRANTED', '2026.1',
  now() - interval '15 days', 'employee1')
on conflict do nothing;

insert into promotion.wearable_enrolment (id, employee_id, campaign_id, device_type, consent_id, status, enrolled_at) values
 ('e12de000-0000-4000-8000-000000000502', 'E1001', 'e12c0000-0000-4000-8000-000000000001', 'FITBIT', 'e12de000-0000-4000-8000-000000000501', 'ACTIVE',
  now() - interval '15 days')
on conflict do nothing;
