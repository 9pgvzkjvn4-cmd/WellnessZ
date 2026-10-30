-- Demo data for E11 travel health, communicable disease & exposures – local and test profiles only (flyway location classpath:db/demo).
-- Idempotent (fixed ids "e11de000-…", "on conflict do nothing"); dates are relative to current_date / now() at first load.
-- Encrypted columns (consultation advice, food-handler exclusion reason, decline reason, adverse-event actions, exposure details,
-- task notes) are left empty here: the demo seeder of the travel-health module (profiles local / test) writes them through the
-- application – together with the signed travel health certificate, the recommendation snapshot and the public-health report.
--
--   travel consultation  E1003 Cara Crew  – Singapore (SG) layovers, hepatitis A + typhoid given 20 days ago, certificate shared
--   CD-<year>-900001     E1001 Erin Employee (catering food handler) – NOROVIRUS confirmed 1 h ago, EXCLUDED (status-only feed),
--                        CAT-LINE-2 shift yesterday with E1010 / M2001 → ready to trace (KPI: list within 4 h)
--   EXP-<year>-900001    E1009 Cole Cleaner – needle-stick 1 h ago: first aid + risk assessment done, PEP decision pending (≤ 2 h)
--   EXP-<year>-900002    E1003 Cara Crew – cabin fume event on WZ101 two days ago: authority report task pending
--   VC-<year>-900001     seasonal influenza campaign ACTIVE – 5 administrations (E07 registry), 1 decline, 1 adverse event
--   advisory             DXB heat advisory targeting the WZ205 crew (E1002, E1003); E1003 acknowledged
--   PHR-<year>-900001    public-health report on the norovirus case to UKHSA_LOCAL, pending (due 72 h after confirmation)

-- ---------- kernel cases ----------
insert into casemgmt.oh_case (id, case_ref, employee_id, employee_display_name, org_unit_code, org_unit_path, subsidiary_code, cost_centre,
                              job_code, location_code, category, case_type, source, source_ref, status, classification, fitness_status,
                              restriction_until, urgency, safety_category, owner_username, occurred_on, created_at, updated_at)
values
    ('e11de000-0000-4000-a000-000000000001', 'CAT-CD-DEMO1', 'E1001', 'Erin Employee', 'CATERING', '/AIRLINE/CATERING', 'CAT', 'CC-CAT-100',
     'CATERING_ASSOC', 'LHR', 'ILLNESS', 'ILLNESS', 'SYSTEM_TRIGGER', 'e11-cd:e11de000-0000-4000-8000-000000000101', 'ASSESSMENT', 'CLINICAL',
     'TEMPORARILY_UNFIT', current_date + 2, 'URGENT', 'FOOD_HANDLER', 'nurse1', current_date - 1, now() - interval '3 hours', now() - interval '50 minutes'),
    ('e11de000-0000-4000-a000-000000000002', 'GND-EXP-DEMO2', 'E1009', 'Cole Cleaner', 'GROUND', '/AIRLINE/OPS/GROUND', 'GND', 'CC-GND-420',
     'CLEANER', 'LHR', 'EXPOSURE', 'EXPOSURE', 'SYSTEM_TRIGGER', 'e11-exposure:e11de000-0000-4000-8000-000000000201', 'TRIAGE', 'CLINICAL',
     'NOT_ASSESSED', null, 'URGENT', null, 'nurse1', current_date, now() - interval '55 minutes', now() - interval '20 minutes'),
    ('e11de000-0000-4000-a000-000000000003', 'CAB-EXP-DEMO3', 'E1003', 'Cara Crew', 'CABIN', '/AIRLINE/OPS/CABIN', 'CAB', 'CC-CAB-300',
     'CABIN_CREW', 'LHR', 'EXPOSURE', 'EXPOSURE', 'SYSTEM_TRIGGER', 'e11-exposure:e11de000-0000-4000-8000-000000000202', 'ASSESSMENT', 'CLINICAL',
     'NOT_ASSESSED', null, 'URGENT', 'CABIN_CREW', 'ame1', current_date - 2, now() - interval '46 hours', now() - interval '20 hours')
on conflict do nothing;

-- ---------- communicable-disease case (confirmed, excluded, ready to trace) ----------
insert into travelhealth.cd_case (id, cd_ref, case_id, case_ref, employee_id, condition_code, onset_date, confirmed_on, confirmed_at, status,
                                  notifiable, report_due_at, food_handler, exclusion_decision, exclusion_from, exclusion_until, return_criteria,
                                  created_by, created_at, updated_at)
values ('e11de000-0000-4000-8000-000000000101', 'CD-' || extract(year from current_date)::int || '-900001', 'e11de000-0000-4000-a000-000000000001',
        'CAT-CD-DEMO1', 'E1001', 'NOROVIRUS', current_date - 1, current_date, now() - interval '1 hour', 'EXCLUDED', true,
        now() - interval '1 hour' + interval '72 hours', true, 'EXCLUDED', current_date, current_date + 2, 'SYMPTOM_FREE_48H', 'nurse1',
        now() - interval '3 hours', now() - interval '50 minutes')
on conflict do nothing;

-- ---------- needle-stick exposure, PEP decision pending ----------
insert into travelhealth.exposure (id, exp_ref, case_id, case_ref, employee_id, pathway, occurred_at, source_known, created_by, created_at, updated_at)
values ('e11de000-0000-4000-8000-000000000201', 'EXP-' || extract(year from current_date)::int || '-900001', 'e11de000-0000-4000-a000-000000000002',
        'GND-EXP-DEMO2', 'E1009', 'NEEDLESTICK_BBP', now() - interval '60 minutes', true, 'nurse1', now() - interval '55 minutes', now() - interval '20 minutes'),
       ('e11de000-0000-4000-8000-000000000202', 'EXP-' || extract(year from current_date)::int || '-900002', 'e11de000-0000-4000-a000-000000000003',
        'CAB-EXP-DEMO3', 'E1003', 'CABIN_FUME', (current_date - 2) + time '15:00', null, 'ame1', now() - interval '46 hours', now() - interval '20 hours')
on conflict do nothing;
update travelhealth.exposure set flight_ref = 'WZ101' where id = 'e11de000-0000-4000-8000-000000000202' and flight_ref is null;

insert into casemgmt.case_task (id, case_id, task_type, title, due_at, assignee, status, created_by, created_at, completed_at)
select v.id::uuid, v.case_id::uuid, 'FOLLOW_UP', v.title, x.occurred_at + v.offset_min * interval '1 minute', null, v.status, 'nurse1', x.created_at,
       case when v.status = 'DONE' then x.occurred_at + v.done_min * interval '1 minute' end
from (values
    ('e11de000-0000-4000-c000-000000000211', 'e11de000-0000-4000-a000-000000000002', 'e11de000-0000-4000-8000-000000000201', 'First aid and wound care documented', 15, 'DONE', 10),
    ('e11de000-0000-4000-c000-000000000212', 'e11de000-0000-4000-a000-000000000002', 'e11de000-0000-4000-8000-000000000201', 'Exposure risk assessment', 60, 'DONE', 40),
    ('e11de000-0000-4000-c000-000000000213', 'e11de000-0000-4000-a000-000000000002', 'e11de000-0000-4000-8000-000000000201', 'Post-exposure prophylaxis decision', 120, 'OPEN', 0),
    ('e11de000-0000-4000-c000-000000000214', 'e11de000-0000-4000-a000-000000000002', 'e11de000-0000-4000-8000-000000000201', 'Source testing requested (with consent)', 1440, 'OPEN', 0),
    ('e11de000-0000-4000-c000-000000000221', 'e11de000-0000-4000-a000-000000000003', 'e11de000-0000-4000-8000-000000000202', 'Symptom protocol completed', 60, 'DONE', 50),
    ('e11de000-0000-4000-c000-000000000222', 'e11de000-0000-4000-a000-000000000003', 'e11de000-0000-4000-8000-000000000202', 'Biomonitoring sample taken', 360, 'DONE', 240),
    ('e11de000-0000-4000-c000-000000000223', 'e11de000-0000-4000-a000-000000000003', 'e11de000-0000-4000-8000-000000000202', 'Safety report linked', 1440, 'DONE', 900),
    ('e11de000-0000-4000-c000-000000000224', 'e11de000-0000-4000-a000-000000000003', 'e11de000-0000-4000-8000-000000000202', 'Authority report task', 4320, 'OPEN', 0)
) as v(id, case_id, exposure_id, title, offset_min, status, done_min)
join travelhealth.exposure x on x.id = v.exposure_id::uuid
where not exists (select 1 from casemgmt.case_task t where t.id = v.id::uuid);

insert into travelhealth.exposure_task (id, exposure_id, kind, code, label, position, due_at, due_on, status, completed_at, completed_by, case_task_id)
select v.id::uuid, v.exposure_id::uuid, v.kind, v.code, v.label, v.position,
       case when v.kind = 'TIMER' then x.occurred_at + v.offset_min * interval '1 minute' end,
       case when v.kind = 'SEROLOGY' then (x.occurred_at at time zone 'UTC')::date + v.offset_days end,
       v.status, case when v.status = 'DONE' then x.occurred_at + v.done_min * interval '1 minute' end,
       case when v.status = 'DONE' then v.done_by end, v.case_task_id::uuid
from (values
    ('e11de000-0000-4000-8000-000000000211', 'e11de000-0000-4000-8000-000000000201', 'TIMER', 'FIRST_AID', 'First aid and wound care documented', 0, 15, 0, 'DONE', 10, 'nurse1', 'e11de000-0000-4000-c000-000000000211'),
    ('e11de000-0000-4000-8000-000000000212', 'e11de000-0000-4000-8000-000000000201', 'TIMER', 'RISK_ASSESSMENT', 'Exposure risk assessment', 1, 60, 0, 'DONE', 40, 'nurse1', 'e11de000-0000-4000-c000-000000000212'),
    ('e11de000-0000-4000-8000-000000000213', 'e11de000-0000-4000-8000-000000000201', 'TIMER', 'PEP_DECISION', 'Post-exposure prophylaxis decision', 2, 120, 0, 'PENDING', 0, null, 'e11de000-0000-4000-c000-000000000213'),
    ('e11de000-0000-4000-8000-000000000214', 'e11de000-0000-4000-8000-000000000201', 'TIMER', 'SOURCE_TESTING', 'Source testing requested (with consent)', 3, 1440, 0, 'PENDING', 0, null, 'e11de000-0000-4000-c000-000000000214'),
    ('e11de000-0000-4000-8000-000000000215', 'e11de000-0000-4000-8000-000000000201', 'SEROLOGY', 'SEROLOGY_6W', 'Serology at 6 weeks', 4, 0, 42, 'PENDING', 0, null, null),
    ('e11de000-0000-4000-8000-000000000216', 'e11de000-0000-4000-8000-000000000201', 'SEROLOGY', 'SEROLOGY_3M', 'Serology at 3 months', 5, 0, 91, 'PENDING', 0, null, null),
    ('e11de000-0000-4000-8000-000000000217', 'e11de000-0000-4000-8000-000000000201', 'SEROLOGY', 'SEROLOGY_6M', 'Serology at 6 months', 6, 0, 182, 'PENDING', 0, null, null),
    ('e11de000-0000-4000-8000-000000000221', 'e11de000-0000-4000-8000-000000000202', 'TIMER', 'SYMPTOM_PROTOCOL', 'Symptom protocol completed', 0, 60, 0, 'DONE', 50, 'ame1', 'e11de000-0000-4000-c000-000000000221'),
    ('e11de000-0000-4000-8000-000000000222', 'e11de000-0000-4000-8000-000000000202', 'TIMER', 'BIOMONITORING_SAMPLE', 'Biomonitoring sample taken', 1, 360, 0, 'DONE', 240, 'nurse2', 'e11de000-0000-4000-c000-000000000222'),
    ('e11de000-0000-4000-8000-000000000223', 'e11de000-0000-4000-8000-000000000202', 'TIMER', 'SAFETY_REPORT', 'Safety report linked', 2, 1440, 0, 'DONE', 900, 'ame1', 'e11de000-0000-4000-c000-000000000223'),
    ('e11de000-0000-4000-8000-000000000224', 'e11de000-0000-4000-8000-000000000202', 'TIMER', 'AUTHORITY_REPORT', 'Authority report task', 3, 4320, 0, 'PENDING', 0, null, 'e11de000-0000-4000-c000-000000000224')
) as v(id, exposure_id, kind, code, label, position, offset_min, offset_days, status, done_min, done_by, case_task_id)
join travelhealth.exposure x on x.id = v.exposure_id::uuid
on conflict do nothing;

-- ---------- travel consultation (E1003, Singapore layovers) – vaccines recorded in the E07 registry ----------
insert into surveillance.vaccine_administration (id, employee_id, vaccine_code, dose_number, lot_number, lot_expiry, body_site, route,
                                                 administered_at, consent_confirmed, record_id, recorded_by, recorded_at) values
 ('e11de000-0000-4000-8000-000000000311', 'E1003', 'HEP_A',   1, 'HA-DEMO-2611', current_date + 300, 'LEFT_DELTOID',  'IM', now() - interval '20 days', true, 'e11de000-0000-4000-8000-000000000321', 'nurse1', now() - interval '20 days'),
 ('e11de000-0000-4000-8000-000000000312', 'E1003', 'TYPHOID', 1, 'TY-DEMO-2612', current_date + 300, 'RIGHT_DELTOID', 'IM', now() - interval '20 days', true, 'e11de000-0000-4000-8000-000000000322', 'nurse1', now() - interval '20 days')
on conflict do nothing;
insert into ohrecord.immunisation (id, employee_id, vaccine, code_system, code, administered_on, dose_number, lot_number, status, next_due_date,
                                   recorded_by, recorded_at) values
 ('e11de000-0000-4000-8000-000000000321', 'E1003', 'Hepatitis A', 'LOCAL', 'HEP_A',   current_date - 20, 1, 'HA-DEMO-2611', 'PARTIAL',  current_date + 160, 'nurse1', now() - interval '20 days'),
 ('e11de000-0000-4000-8000-000000000322', 'E1003', 'Typhoid',     'LOCAL', 'TYPHOID', current_date - 20, 1, 'TY-DEMO-2612', 'COMPLETE', null,              'nurse1', now() - interval '20 days')
on conflict do nothing;
insert into travelhealth.consultation (id, employee_id, destinations, depart_date, return_date, purpose, vaccines_given, consulted_by, created_at)
values ('e11de000-0000-4000-8000-000000000301', 'E1003', 'SG', current_date - 2, current_date + 180, 'LAYOVER',
        '[{"vaccineCode":"HEP_A","vaccineName":"Hepatitis A","doseNumber":1,"lotNumber":"HA-DEMO-2611","site":"LEFT_DELTOID","route":"IM","administrationId":"e11de000-0000-4000-8000-000000000311"},'
        || '{"vaccineCode":"TYPHOID","vaccineName":"Typhoid","doseNumber":1,"lotNumber":"TY-DEMO-2612","site":"RIGHT_DELTOID","route":"IM","administrationId":"e11de000-0000-4000-8000-000000000312"}]',
        'nurse1', now() - interval '20 days')
on conflict do nothing;

-- ---------- seasonal influenza campaign (ACTIVE) ----------
insert into travelhealth.campaign (id, campaign_ref, name, vaccine_code, eligibility, start_date, end_date, site_codes, target_uptake_pct,
                                   created_by, created_at, updated_at)
values ('e11de000-0000-4000-8000-000000000401', 'VC-' || extract(year from current_date)::int || '-900001', 'Seasonal influenza vaccination',
        'INFLUENZA', '{"all":true,"jobCodes":[],"subsidiaries":[]}', current_date - 14, current_date + 45, 'LHR-CAT,LHR-CARGO,HQ-MC', 75.0,
        'nursinghead1', now() - interval '20 days', now() - interval '20 days')
on conflict do nothing;

insert into travelhealth.campaign_invitation (id, campaign_id, employee_id, invited_at, response, responded_at)
select ('e11de000-0000-4000-8000-0000000004' || lpad(v.n::text, 2, '0'))::uuid, 'e11de000-0000-4000-8000-000000000401', v.emp,
       now() - interval '13 days', v.resp, case when v.resp is not null then now() - interval '12 days' end
from (values (11, 'E1001', null), (12, 'E1002', 'ACCEPT'), (13, 'E1003', null), (14, 'E1004', 'ACCEPT'), (15, 'E1005', null),
             (16, 'M2001', 'ACCEPT'), (17, 'E1006', 'ACCEPT'), (18, 'E1007', 'ACCEPT'), (19, 'E1008', 'DECLINE'), (20, 'E1009', null),
             (21, 'E1010', 'ACCEPT')) as v(n, emp, resp)
on conflict do nothing;

insert into surveillance.vaccine_administration (id, employee_id, vaccine_code, dose_number, lot_number, lot_expiry, body_site, route, campaign_id,
                                                 administered_at, consent_confirmed, record_id, recorded_by, recorded_at) values
 ('e11de000-0000-4000-8000-000000000431', 'E1004', 'INFLUENZA', 1, 'FLU-DEMO-26A', current_date + 150, 'LEFT_DELTOID', 'IM', 'e11de000-0000-4000-8000-000000000401', now() - interval '10 days', true, 'e11de000-0000-4000-8000-000000000441', 'nurse1', now() - interval '10 days'),
 ('e11de000-0000-4000-8000-000000000432', 'E1007', 'INFLUENZA', 1, 'FLU-DEMO-26A', current_date + 150, 'LEFT_DELTOID', 'IM', 'e11de000-0000-4000-8000-000000000401', now() - interval '10 days', true, 'e11de000-0000-4000-8000-000000000442', 'nurse1', now() - interval '10 days'),
 ('e11de000-0000-4000-8000-000000000433', 'E1010', 'INFLUENZA', 1, 'FLU-DEMO-26A', current_date + 150, 'RIGHT_DELTOID', 'IM', 'e11de000-0000-4000-8000-000000000401', now() - interval '8 days', true, 'e11de000-0000-4000-8000-000000000443', 'nurse2', now() - interval '8 days'),
 ('e11de000-0000-4000-8000-000000000434', 'E1006', 'INFLUENZA', 1, 'FLU-DEMO-26B', current_date + 150, 'LEFT_DELTOID', 'IM', 'e11de000-0000-4000-8000-000000000401', now() - interval '5 days', true, 'e11de000-0000-4000-8000-000000000444', 'nurse2', now() - interval '5 days'),
 ('e11de000-0000-4000-8000-000000000435', 'M2001', 'INFLUENZA', 1, 'FLU-DEMO-26B', current_date + 150, 'LEFT_DELTOID', 'IM', 'e11de000-0000-4000-8000-000000000401', now() - interval '5 days', true, 'e11de000-0000-4000-8000-000000000445', 'nurse2', now() - interval '5 days')
on conflict do nothing;
insert into ohrecord.immunisation (id, employee_id, vaccine, code_system, code, administered_on, dose_number, lot_number, status, next_due_date,
                                   recorded_by, recorded_at) values
 ('e11de000-0000-4000-8000-000000000441', 'E1004', 'Influenza', 'LOCAL', 'INFLUENZA', current_date - 10, 1, 'FLU-DEMO-26A', 'COMPLETE', null, 'nurse1', now() - interval '10 days'),
 ('e11de000-0000-4000-8000-000000000442', 'E1007', 'Influenza', 'LOCAL', 'INFLUENZA', current_date - 10, 1, 'FLU-DEMO-26A', 'COMPLETE', null, 'nurse1', now() - interval '10 days'),
 ('e11de000-0000-4000-8000-000000000443', 'E1010', 'Influenza', 'LOCAL', 'INFLUENZA', current_date - 8,  1, 'FLU-DEMO-26A', 'COMPLETE', null, 'nurse2', now() - interval '8 days'),
 ('e11de000-0000-4000-8000-000000000444', 'E1006', 'Influenza', 'LOCAL', 'INFLUENZA', current_date - 5,  1, 'FLU-DEMO-26B', 'COMPLETE', null, 'nurse2', now() - interval '5 days'),
 ('e11de000-0000-4000-8000-000000000445', 'M2001', 'Influenza', 'LOCAL', 'INFLUENZA', current_date - 5,  1, 'FLU-DEMO-26B', 'COMPLETE', null, 'nurse2', now() - interval '5 days')
on conflict do nothing;
insert into travelhealth.campaign_decline (id, campaign_id, employee_id, recorded_by, recorded_at)
values ('e11de000-0000-4000-8000-000000000451', 'e11de000-0000-4000-8000-000000000401', 'E1008', 'nurse1', now() - interval '9 days')
on conflict do nothing;
insert into travelhealth.adverse_event (id, campaign_id, employee_id, administration_id, event, severity, onset_at, recorded_by, recorded_at)
values ('e11de000-0000-4000-8000-000000000461', 'e11de000-0000-4000-8000-000000000401', 'E1004', 'e11de000-0000-4000-8000-000000000431',
        'LOCAL_REACTION', 'MILD', now() - interval '10 days' + interval '6 hours', 'nurse1', now() - interval '9 days')
on conflict do nothing;

-- ---------- DXB layover advisory targeting the WZ205 crew ----------
insert into travelhealth.advisory (id, title, destination_code, base_code, roles, severity, body, valid_from, valid_until, published_by, published_at)
values ('e11de000-0000-4000-8000-000000000501', 'Extreme heat at Dubai layovers', 'DXB', null, null, 'WARNING',
        'Daytime temperatures in Dubai are above 40 °C this week. Avoid outdoor activity between 11:00 and 16:00, carry water at all times, '
        || 'use the hotel shuttle rather than walking and contact the OH duty line if you feel unwell after your layover.',
        current_date - 1, current_date + 14, 'physician1', now() - interval '1 day')
on conflict do nothing;
insert into travelhealth.advisory_target (id, advisory_id, employee_id, reason, duty_ref, notified_at, acknowledged_at) values
 ('e11de000-0000-4000-8000-000000000511', 'e11de000-0000-4000-8000-000000000501', 'E1002', 'ROSTERED', 'WZ205', now() - interval '1 day', null),
 ('e11de000-0000-4000-8000-000000000512', 'e11de000-0000-4000-8000-000000000501', 'E1003', 'ROSTERED', 'WZ205', now() - interval '1 day', now() - interval '2 hours')
on conflict do nothing;

-- ---------- public-health report on the norovirus case (pending) ----------
insert into travelhealth.ph_report (id, report_ref, cd_case_id, case_id, employee_id, authority_code, subject, status, due_at, created_by, created_at, updated_at)
values ('e11de000-0000-4000-8000-000000000601', 'PHR-' || extract(year from current_date)::int || '-900001', 'e11de000-0000-4000-8000-000000000101',
        'e11de000-0000-4000-a000-000000000001', 'E1001', 'UKHSA_LOCAL', 'Norovirus gastroenteritis', 'PENDING',
        now() - interval '1 hour' + interval '72 hours', 'physician1', now() - interval '40 minutes', now() - interval '40 minutes')
on conflict do nothing;
