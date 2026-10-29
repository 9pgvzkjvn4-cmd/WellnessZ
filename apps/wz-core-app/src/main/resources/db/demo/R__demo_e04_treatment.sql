-- Demo data for E04 treatment coordination & partner referrals – local and test profiles only (flyway location classpath:db/demo).
-- Idempotent (fixed ids, "on conflict do nothing"); dates are relative to current_date at first load. Encrypted columns
-- (plan goals / interventions, referral reason / question / data set, notes, chat) are left empty: they are written by the
-- application only – a plan without stored goals shows its template's goals and interventions.
--
--   E1004 Rami Ramp – injury case GND-INJ-DEMO4 (low back strain, 30 days ago) with:
--       * ACTIVE treatment plan (template BACK_STRAIN, guideline M54.5 = 21 days → variance alert OPEN), 3 follow-ups
--       * referrals RFL-<year>-900001 SENT to CITYHOSP (awaiting acceptance – partner1), 900002 SCHEDULED with PHYSIOPLUS,
--         900003 SEEN at LDN_IMAGING with the report overdue (chased once), 900004 REPORT_RECEIVED from STMARYS_ORTHO
--         (last month, rated 4), 900005 DRAFT (orthopaedics, no provider yet)
--       * pre-authorisations PA-<year>-900001 SUBMITTED to GULFTPA (adjuster1 can decide), 900002 APPROVED by GULFTPA with the
--         matched invoice INV-LDI-0412 from LDN_IMAGING
--       * outcomes BASELINE and REVIEW
--   E1003 Cara Crew – telehealth TH-<year>-900001 with TELEDOC tomorrow 09:00 UTC, crew on layover in Singapore (consented)
--   Consents: E1004 TREATMENT_SHARING_PARTNER + INSURER_SHARING; E1003 TREATMENT_SHARING_PARTNER + TELEHEALTH_CONSULTATION
--   Stock: LHR-AIRSIDE PCM-2401, IBU-2311 (below reorder level), ADR-2207 (expired); HQ-MC CHX-2402, TDV-2405, PCM-2402
--   Provider scorecards for last month: CITYHOSP, PHYSIOPLUS, LDN_IMAGING, STMARYS_ORTHO

-- ---------- consents ----------
insert into platform.consent (id, employee_id, purpose, scope, status, notice_version, granted_at, captured_by) values
    ('e04de000-0000-4000-8000-000000000001', 'E1004', 'TREATMENT_SHARING_PARTNER', 'Referrals for back injury', 'GRANTED', '2026.1', now() - interval '29 days', 'ramp1'),
    ('e04de000-0000-4000-8000-000000000002', 'E1004', 'INSURER_SHARING',           'Work injury claim GULFTPA', 'GRANTED', '2026.1', now() - interval '29 days', 'ramp1'),
    ('e04de000-0000-4000-8000-000000000003', 'E1003', 'TREATMENT_SHARING_PARTNER', 'Crew layover care',         'GRANTED', '2026.1', now() - interval '3 days',  'crew1'),
    ('e04de000-0000-4000-8000-000000000004', 'E1003', 'TELEHEALTH_CONSULTATION',   'telehealth:TH-DEMO',        'GRANTED', '2026.1', now() - interval '1 day',   'crew1')
on conflict do nothing;

-- ---------- kernel case ----------
insert into casemgmt.oh_case (id, case_ref, employee_id, employee_display_name, org_unit_code, org_unit_path, subsidiary_code, cost_centre,
                              job_code, location_code, category, case_type, source, source_ref, status, classification, fitness_status,
                              urgency, safety_category, owner_username, early_intervention, body_part, occurred_on, created_at, updated_at)
values
    ('e04de000-0000-4000-a000-000000000001', 'GND-INJ-DEMO4', 'E1004', 'Rami Ramp', 'GROUND', '/AIRLINE/OPS/GROUND', 'GND', 'CC-GND-400',
     'RAMP_AGENT', 'LHR', 'INJURY', 'INJURY', 'EHS_INCIDENT', 'demo:e04-back-injury', 'TREATMENT', 'CLINICAL', 'NOT_ASSESSED',
     'ROUTINE', 'AIRSIDE_DRIVER', 'clinician1', false, 'LOWER_BACK', current_date - 30, now() - interval '30 days', now() - interval '1 day')
on conflict do nothing;

insert into casemgmt.case_event (id, case_id, event_type, summary, actor, occurred_at) values
    ('e04de000-0000-4000-a100-000000000001', 'e04de000-0000-4000-a000-000000000001', 'CASE_OPENED', 'Case opened via EHS_INCIDENT (INJURY)', 'system', now() - interval '30 days'),
    ('e04de000-0000-4000-a100-000000000002', 'e04de000-0000-4000-a000-000000000001', 'TREATMENT_PLAN_CREATED', 'Treatment plan created (version 1)', 'clinician1', now() - interval '28 days'),
    ('e04de000-0000-4000-a100-000000000003', 'e04de000-0000-4000-a000-000000000001', 'REFERRAL_SENT', 'Referral sent to the provider', 'clinician1', now() - interval '1 day')
on conflict do nothing;

-- ---------- treatment plan & follow-ups ----------
insert into treatment.treatment_plan (id, series_id, version, case_id, employee_id, status, template_code, goals, interventions, start_date,
                                      expected_recovery_days, guideline_code, variance_threshold_pct, responsible_clinician, created_by,
                                      created_at, updated_at)
values ('e04de000-0000-4000-b000-000000000001', 'e04de000-0000-4000-b000-000000000001', 1, 'e04de000-0000-4000-a000-000000000001', 'E1004',
        'ACTIVE', 'BACK_STRAIN', null, null, current_date - 28, null, 'M54.5', null, 'clinician1', 'clinician1',
        now() - interval '28 days', now() - interval '28 days')
on conflict do nothing;

insert into treatment.follow_up (id, plan_id, case_id, employee_id, due_date, follow_up_type, site_code, status, employee_response,
                                 book_requested, created_at, updated_at)
values
    ('e04de000-0000-4000-b010-000000000001', 'e04de000-0000-4000-b000-000000000001', 'e04de000-0000-4000-a000-000000000001', 'E1004',
     current_date - 14, 'OH_REVIEW', 'LHR-AIRSIDE', 'DONE', 'CONFIRM', false, now() - interval '28 days', now() - interval '14 days'),
    ('e04de000-0000-4000-b010-000000000002', 'e04de000-0000-4000-b000-000000000001', 'e04de000-0000-4000-a000-000000000001', 'E1004',
     current_date + 2, 'OH_REVIEW', 'LHR-AIRSIDE', 'PLANNED', null, false, now() - interval '28 days', now() - interval '28 days'),
    ('e04de000-0000-4000-b010-000000000003', 'e04de000-0000-4000-b000-000000000001', 'e04de000-0000-4000-a000-000000000001', 'E1004',
     current_date + 9, 'PHONE', null, 'PLANNED', null, false, now() - interval '7 days', now() - interval '7 days')
on conflict do nothing;

insert into treatment.variance_alert (id, plan_id, case_id, employee_id, expected_days, actual_days, pct_over, status, raised_at) values
    ('e04de000-0000-4000-b020-000000000001', 'e04de000-0000-4000-b000-000000000001', 'e04de000-0000-4000-a000-000000000001', 'E1004',
     21, 30, 42.9, 'OPEN', now() - interval '2 hours')
on conflict do nothing;

-- ---------- outcomes ----------
insert into treatment.outcome (id, case_id, employee_id, milestone, pain_score, function_scale, function_score, rtw_date, residual_impairment_pct,
                               recorded_by, recorded_at) values
    ('e04de000-0000-4000-b030-000000000001', 'e04de000-0000-4000-a000-000000000001', 'E1004', 'BASELINE', 7, 'ODI', 46, null, null, 'clinician1', now() - interval '28 days'),
    ('e04de000-0000-4000-b030-000000000002', 'e04de000-0000-4000-a000-000000000001', 'E1004', 'REVIEW',   4, 'ODI', 30, null, null, 'clinician1', now() - interval '14 days')
on conflict do nothing;

-- ---------- referrals ----------
insert into treatment.referral (id, ref, case_id, employee_id, service_code, provider_code, urgency, status, channel, consent_id, created_by,
                                created_at, updated_at, sent_by, sent_at, accepted_at, appointment_at, appointment_location, seen_at,
                                report_due_at, report_received_at, closed_at, chase_count, last_chased_at, escalated, rating, rated_by)
select v.id::uuid, 'RFL-' || extract(year from current_date)::int || '-' || v.seq, 'e04de000-0000-4000-a000-000000000001'::uuid, 'E1004',
       v.svc, v.provider, v.urgency, v.status, 'PORTAL', 'e04de000-0000-4000-8000-000000000001'::uuid, 'clinician1',
       v.created, v.created, case when v.sent is null then null else 'clinician1' end, v.sent, v.accepted, v.appt, v.loc, v.seen, v.due,
       v.received, null, v.chases, v.chased, false, v.rating, case when v.rating is null then null else 'clinician1' end
  from (values
    ('e04de000-0000-4000-b100-000000000001', '900001', 'ORTHOPAEDICS',  'CITYHOSP',      'URGENT',  'SENT',
        now() - interval '1 day 2 hours', now() - interval '1 day', null::timestamptz, null::timestamptz, null::varchar, null::timestamptz,
        now() + interval '13 days', null::timestamptz, 0, null::timestamptz, null::int),
    ('e04de000-0000-4000-b100-000000000002', '900002', 'PHYSIOTHERAPY', 'PHYSIOPLUS',    'ROUTINE', 'SCHEDULED',
        now() - interval '6 days', now() - interval '6 days', now() - interval '5 days', (current_date + 2) + time '10:00', 'PhysioPlus Hounslow, room 2',
        null, now() + interval '15 days', null, 0, null, null),
    ('e04de000-0000-4000-b100-000000000003', '900003', 'IMAGING_MRI',   'LDN_IMAGING',   'ROUTINE', 'SEEN',
        now() - interval '20 days', now() - interval '20 days', now() - interval '19 days', now() - interval '12 days', 'London Diagnostic Imaging, Wimpole St',
        now() - interval '12 days', now() - interval '13 days', null, 1, now() - interval '2 days', null),
    ('e04de000-0000-4000-b100-000000000004', '900004', 'ORTHOPAEDICS',  'STMARYS_ORTHO', 'ROUTINE', 'REPORT_RECEIVED',
        date_trunc('month', current_date) - interval '25 days', date_trunc('month', current_date) - interval '25 days',
        date_trunc('month', current_date) - interval '24 days', date_trunc('month', current_date) - interval '18 days', 'St Mary''s outpatients',
        date_trunc('month', current_date) - interval '18 days', date_trunc('month', current_date) - interval '11 days',
        date_trunc('month', current_date) - interval '14 days', 0, null, 4),
    ('e04de000-0000-4000-b100-000000000005', '900005', 'ORTHOPAEDICS',  null,            'ROUTINE', 'DRAFT',
        now() - interval '2 hours', null, null, null, null, null, null, null, 0, null, null)
  ) as v(id, seq, svc, provider, urgency, status, created, sent, accepted, appt, loc, seen, due, received, chases, chased, rating)
on conflict do nothing;

insert into treatment.referral_history (id, referral_id, status, at, by_actor)
select v.id::uuid, v.rid::uuid, v.status, v.at, v.actor
  from (values
    ('e04de000-0000-4000-b110-000000000001', 'e04de000-0000-4000-b100-000000000001', 'DRAFT',     now() - interval '1 day 2 hours', 'clinician1'),
    ('e04de000-0000-4000-b110-000000000002', 'e04de000-0000-4000-b100-000000000001', 'SENT',      now() - interval '1 day', 'clinician1'),
    ('e04de000-0000-4000-b110-000000000003', 'e04de000-0000-4000-b100-000000000002', 'SENT',      now() - interval '6 days', 'clinician1'),
    ('e04de000-0000-4000-b110-000000000004', 'e04de000-0000-4000-b100-000000000002', 'ACCEPTED',  now() - interval '5 days', 'physio1'),
    ('e04de000-0000-4000-b110-000000000005', 'e04de000-0000-4000-b100-000000000002', 'SCHEDULED', now() - interval '5 days', 'physio1'),
    ('e04de000-0000-4000-b110-000000000006', 'e04de000-0000-4000-b100-000000000003', 'SENT',      now() - interval '20 days', 'clinician1'),
    ('e04de000-0000-4000-b110-000000000007', 'e04de000-0000-4000-b100-000000000003', 'SCHEDULED', now() - interval '19 days', 'ldnimaging'),
    ('e04de000-0000-4000-b110-000000000008', 'e04de000-0000-4000-b100-000000000003', 'SEEN',      now() - interval '12 days', 'ldnimaging'),
    ('e04de000-0000-4000-b110-000000000009', 'e04de000-0000-4000-b100-000000000004', 'SENT',      date_trunc('month', current_date) - interval '25 days', 'clinician1'),
    ('e04de000-0000-4000-b110-000000000010', 'e04de000-0000-4000-b100-000000000004', 'REPORT_RECEIVED', date_trunc('month', current_date) - interval '14 days', 'stmarys'),
    ('e04de000-0000-4000-b110-000000000011', 'e04de000-0000-4000-b100-000000000005', 'DRAFT',     now() - interval '2 hours', 'clinician1')
  ) as v(id, rid, status, at, actor)
on conflict do nothing;

-- ---------- pre-authorisations & invoice ----------
insert into treatment.preauth (id, ref, case_id, employee_id, payer_code, service_code, provider_code, referral_id, estimate_amount, currency,
                               consent_id, status, requested_by, created_at, updated_at, submitted_at, sla_due_at, decided_at, decided_by,
                               approval_ref, approved_amount, valid_until)
values
    ('e04de000-0000-4000-b200-000000000001', 'PA-' || extract(year from current_date)::int || '-900001', 'e04de000-0000-4000-a000-000000000001',
     'E1004', 'GULFTPA', 'PHYSIOTHERAPY', 'PHYSIOPLUS', 'e04de000-0000-4000-b100-000000000002', 360.00, 'GBP',
     'e04de000-0000-4000-8000-000000000002', 'SUBMITTED', 'clinician1', now() - interval '1 day', now() - interval '1 day',
     now() - interval '1 day', now() + interval '23 hours', null, null, null, null, null),
    ('e04de000-0000-4000-b200-000000000002', 'PA-' || extract(year from current_date)::int || '-900002', 'e04de000-0000-4000-a000-000000000001',
     'E1004', 'GULFTPA', 'IMAGING_MRI', 'LDN_IMAGING', 'e04de000-0000-4000-b100-000000000003', 450.00, 'GBP',
     'e04de000-0000-4000-8000-000000000002', 'APPROVED', 'clinician1', now() - interval '21 days', now() - interval '20 days',
     now() - interval '21 days', now() - interval '19 days', now() - interval '20 days', 'adjuster1', 'GT-APR-5521', 420.00, current_date + 60)
on conflict do nothing;

insert into treatment.invoice (id, case_id, employee_id, provider_code, invoice_number, amount, currency, service_date, referral_id, preauth_id,
                               status, matched_preauth_id, variance, created_by, created_at, updated_at)
values ('e04de000-0000-4000-b300-000000000001', 'e04de000-0000-4000-a000-000000000001', 'E1004', 'LDN_IMAGING', 'INV-LDI-0412', 430.00, 'GBP',
        current_date - 12, 'e04de000-0000-4000-b100-000000000003', 'e04de000-0000-4000-b200-000000000002', 'MATCHED',
        'e04de000-0000-4000-b200-000000000002', 10.00, 'claims1', now() - interval '10 days', now() - interval '10 days')
on conflict do nothing;

-- ---------- clinic stock ----------
insert into treatment.stock_batch (id, item_code, batch_number, expiry, quantity, site_code, received_by, received_at) values
    ('e04de000-0000-4000-b400-000000000001', 'PARACETAMOL_500',   'PCM-2401', current_date + 300, 200, 'LHR-AIRSIDE', 'nursinghead1', now() - interval '40 days'),
    ('e04de000-0000-4000-b400-000000000002', 'IBUPROFEN_400',     'IBU-2311', current_date + 200, 40,  'LHR-AIRSIDE', 'nursinghead1', now() - interval '40 days'),
    ('e04de000-0000-4000-b400-000000000003', 'ADRENALINE_AI_300', 'ADR-2207', current_date - 10,  3,   'LHR-AIRSIDE', 'nursinghead1', now() - interval '400 days'),
    ('e04de000-0000-4000-b400-000000000004', 'CHLORHEXIDINE_05',  'CHX-2402', current_date + 400, 10,  'HQ-MC',       'nursinghead1', now() - interval '20 days'),
    ('e04de000-0000-4000-b400-000000000005', 'TD_VACCINE',        'TDV-2405', current_date + 150, 20,  'HQ-MC',       'nursinghead1', now() - interval '20 days'),
    ('e04de000-0000-4000-b400-000000000006', 'PARACETAMOL_500',   'PCM-2402', current_date + 330, 150, 'HQ-MC',       'nursinghead1', now() - interval '20 days')
on conflict do nothing;

-- ---------- telehealth ----------
insert into treatment.telehealth_session (id, ref, employee_id, case_id, scheduled_at, duration_minutes, mode, status, clinician_username,
                                          provider_code, crew_layover, layover_city, consent_id, created_by, created_at, updated_at)
values ('e04de000-0000-4000-b500-000000000001', 'TH-' || extract(year from current_date)::int || '-900001', 'E1003', null,
        (current_date + 1) + time '09:00', 30, 'VIDEO', 'SCHEDULED', 'physician1', 'TELEDOC', true, 'Singapore',
        'e04de000-0000-4000-8000-000000000004', 'physician1', now() - interval '1 day', now() - interval '1 day')
on conflict do nothing;

-- ---------- provider scorecards (last month) ----------
insert into treatment.provider_scorecard (id, provider_code, month, referrals, median_days_to_appointment, report_within7_pct, avg_report_quality,
                                          outcome_improved_pct, avg_invoice, currency, timeliness, report_quality, outcome_score, cost_score,
                                          overall, computed_at)
select v.id::uuid, v.code, to_char(current_date - interval '1 month', 'YYYY-MM'), v.n, v.median, v.r7, v.q, v.o, v.inv, 'GBP', v.t, v.rq, v.os, v.c,
       v.overall, now() - interval '20 days'
  from (values
    ('e04de000-0000-4000-b600-000000000001', 'CITYHOSP',      6, 4.0, 83.3, 4.2, 75.0, 300.00, 91.7, 84.0, 75.0, 93.3, 86.2),
    ('e04de000-0000-4000-b600-000000000002', 'PHYSIOPLUS',    9, 2.0, 88.9, 4.5, 85.7, 58.00,  94.5, 90.0, 85.7, 100.0, 92.1),
    ('e04de000-0000-4000-b600-000000000003', 'LDN_IMAGING',   5, 3.0, 60.0, 3.8, null, 430.00, 80.0, 76.0, null, 97.7, 83.6),
    ('e04de000-0000-4000-b600-000000000004', 'STMARYS_ORTHO', 3, 6.0, 66.7, 4.0, 66.7, 270.00, 83.4, 80.0, 66.7, 96.3, 81.2)
  ) as v(id, code, n, median, r7, q, o, inv, t, rq, os, c, overall)
on conflict do nothing;
