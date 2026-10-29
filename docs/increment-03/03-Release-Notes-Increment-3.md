# WellnessZ OHCM – Release Notes, Increment 3 (E06 Absence + E07 Surveillance + E14 Scheduling)

| | |
|---|---|
| **Document** | Increment 3 – 03 Release Notes |
| **Release content** | E06 Absence & Sickness Management · E07 Medical Surveillance, Periodic & Statutory Medicals · E14 Scheduling, Clinic Operations & Resource Management · seven new kernel SPIs |
| **Audience** | CMO, Nursing Head, DPO, HR, Crew Licensing, Biomedical Engineering, Cyber GRC, CIO, project sponsor, platform operations |
| **Database** | Flyway V9, V10, V11, V12 (+ repeatable demo data `R__demo_e06_absence.sql`, `R__demo_e07_surveillance.sql`, `R__demo_e14_scheduling.sql` for the `local` / test profiles only) |
| **New application** | None – three new backend modules inside `wz-core-app` and new screens in `web/oh-workspace`, `web/employee-app` and `web/manager-workspace`; no new image or Helm component |
| **Companion documents** | 01 – Solution Design E06/E07/E14 · 02 – Requirements Traceability Matrix · API contract `API-Contract-E06-E07-E14.md` · OpenAPI `docs/api/openapi.json` (391 paths) |

---

## 1. Highlights

* **Absence connected to OH** – absence episodes from HRIS, time-and-attendance and crew systems are reconciled to the
  employee master and evaluated against configurable trigger rules; early-intervention, long-term and AME-review cases
  are raised automatically and the manager and HR are told only that "OH is engaged".
* **Sick notes without chasing** – employees photograph their certificate in the app; OCR extraction, duplicate and
  alteration checks, a validation checklist and a verification call log follow; HR sees VALID / INVALID / PENDING only.
* **Structured long-term absence** – milestones at weeks 2, 4, 8, 12 and 26, fortnightly contacts, case conferences, HR
  policy stages and the link to the RTW plan; overdue milestones are flagged every night.
* **Advice without diagnosis** – managers and HR ask library questions; OH answers in a functional report that is
  screened for clinical content, reviewed and consented to by the employee, then released as a signed PDF.
* **Every medical as a programme** – thirteen versioned surveillance programmes with eligibility rules, test panels,
  frequencies and population simulation; nightly due lists; bulk invitation and booking of up to 5,000 people per call;
  re-invitation and manager escalation after two ignored invitations.
* **From offer to clearance** – pre-employment medicals triggered by the HRIS offer, a candidate portal (questionnaire,
  consent, documents, booking) and a FIT / UNFIT / PENDING status for HR – nothing else.
* **No crew beyond expiry** – crew medical certificate register with 90 / 60 / 30-day alerts, external AME import and an
  UNFIT roster status on expiry.
* **Closed-loop surveillance** – abnormal audiograms, spirometry declines and biomonitoring results open follow-up cases
  with recheck and regulatory tasks at once; a return-after-illness medical now blocks fitness clearance of safety-critical
  staff until it is done; IH measurements above the action level enrol whole exposure groups automatically.
* **Running the clinics** – service catalogue, diary templates, conflict-checked booking, waitlists, reminders,
  self-booking, campaign days for hundreds of people, badge check-in with a queue board **without names**, call-next with
  hand-off to the chart, equipment calibration that **blocks result capture on out-of-calibration devices**, vaccine-fridge
  excursion alerts and capacity forecasts per site.

Requirement scope: E06 F01–F06 (14 backlog items), E07 F01–F08 (20), E14 F01–F05 (10). Coverage per item: see document 02.

---

## 2. What is new, by epic and feature

### 2.1 E06 – Absence & Sickness Management

| Feature | What is new | Main users |
|---|---|---|
| **E06-F01** Absence feed & history | Push endpoint `/integration/absence/episodes` and daily pull SPI (`AbsenceFeedConnector`, default no-op); reconciliation by employee ID then badge ID; mismatch list with resolve / ignore; data-quality dashboard with match rate per run; episode timeline on the chart (*Absence* tab); Bradford factor and patterns (Monday / Friday, post-holiday, frequent short); export of an episode to a case | OH nurse, HR data stewards, OH admin |
| **E06-F02** Trigger rules & early-intervention queue | Rule builder (duration, frequency, Monday / Friday pattern, post-incident, safety-critical role, crew sickness) with versions, audit and **test mode**; subsidiary rules override defaults of the same type and action; 8 seeded rules; queue with absence history and SLA; accept opens the ABSENCE case and sends `absence.oh-engaged` to the manager and HR; contact log with the trigger-to-first-contact KPI; crew sickness opens an AME review with a PENDING roster status | OH nurse, AME, OH admin, manager, HR |
| **E06-F03** Medical certificates | Upload / photo from the employee app; clinic scan by reception; electronic partner certificates; OCR field extraction; duplicate detection (hash, number, dates); alteration flags; validation checklist; verification call log; verification with HR escalation; HR and managers see validity only | Employee, reception, OH nurse, HR |
| **E06-F04** Long-term absence pathway | Pathway on the ABSENCE case with milestones at weeks 2 / 4 / 8+8 / 12+12 / 26, fortnightly contact schedule, case conferences, HR stages (informal review → capability hearing), RTW review milestone and E05 RTW plan link, nightly overdue flags and alerts | OH nurse, case manager, HR |
| **E06-F05** Advice request & OH advice report | Request form with an 8-question library and 5-working-day SLA; OH report limited to fitness, adjustments, restrictions, prognosis, dates and answers (every text screened); employee review with consent or decline; release as signed `OH_ADVICE_REPORT` to the line manager or HR with disclosure log; new versions after a decline | Manager, HR, OH physician, employee |
| **E06-F06** Absence analytics | Dashboard by subsidiary, department or job family (rate, frequency, duration, Bradford, categories for OH only) with small-group suppression; KPIs (LTA with OH case %, median days trigger → contact, certificates verified %) with monthly trend; monthly CSV pack | CMO, Nursing Head, HR |

### 2.2 E07 – Medical Surveillance, Periodic & Statutory Medicals

| Feature | What is new | Main users |
|---|---|---|
| **E07-F01** Programme & protocol builder | Versioned programmes (DRAFT → ACTIVE → RETIRED) with eligibility (job, department, subsidiary, hazard, exposure group, licence, age), panel, frequency (with over-40 variant), standard, responsible role and service; population simulation without writes; activation recalculates enrolment; 13 programmes seeded (pilot class 1, cabin crew, food handler, hearing conservation, respiratory, hazmat / DGR, airside driver, forklift, working at height, night worker, security screener, clinical BBP, VDU) | OH admin, CMO, Nursing Head |
| **E07-F02** Due list, invitations, escalation | Nightly due-list engine; due list by programme / unit / status; bulk invitation with booking through the clinic diary (≤ 5,000 per call); invitation log; completion and exemption; re-invitation after a no-show or an ignored invitation and manager escalation after the second; team compliance view for managers; *My surveillance* for employees | Clinic scheduler, OH nurse, manager, employee |
| **E07-F03** Pre-employment | HRIS offer trigger (idempotent), job-based panel, candidate portal (10-question questionnaire, consent, up to 10 documents, booking), outcome with screened onboarding adjustments, `preemployment.status.v1` to `hr-systems`, signed `PRE_EMPLOYMENT_OUTCOME`, government / visa medicals, withdrawal with 12-month retention and nightly purge | HR onboarding, candidate, OH nurse / physician |
| **E07-F04** Crew aeromedical & certificates | CAA-style examination templates (Class 1, Class 2, cabin crew – draft), examinations with medical report and roster status, external AME / AeMC import, certificate register (VALID / EXPIRING / EXPIRED / SUSPENDED) with operational limitation codes for crew roles, crew without a valid medical, 90 / 60 / 30-day alerts, UNFIT on expiry, authority report CSV | AME, crew licensing, crew scheduling |
| **E07-F05** Immunisation & titres | Role / hazard requirement matrix; status COMPLETE / DUE / OVERDUE / TITRE_NEEDED; due list; fast entry by badge and **GS1 barcode** with consent; batch campaign entry; titres; mirroring into the E03 record; vaccination certificate download; monthly-at-most reminders | OH nurse, employee, hygiene manager |
| **E07-F06** Exposure registers & IH linkage | Similar exposure groups with action level and limit; IH measurement import (noise dosimetry, air sampling, biological, vibration) that enrols the group into the hazard's programme above the action level; cosmic dose per crew member and year with AME alert above 6 mSv; crew pregnancy declaration; personal exposure history | HSE officer, AME, OH physician |
| **E07-F07** Result evaluation & follow-up | Rules for standard threshold shift, spirometry decline, blood lead, vision and abnormal flags; follow-up record, `SURVEILLANCE_FOLLOW_UP` case, recheck task and regulatory-notification task created with the result; physician review; **return-after-illness** items (from long-term absence in safety-critical jobs or manual) that block FIT decisions in E05 | OH physician, OH nurse |
| **E07-F08** Compliance & audit packs | Compliance by programme / subsidiary / department from a nightly snapshot with suppression, trend and overdue drill-down; audit packs (IOSA, CAA, food safety, labour inspectorate) as PDF + CSV, anonymised for everyone except the CMO and Nursing Head | Nursing Head, CMO, HSE, auditors |

### 2.3 E14 – Scheduling, Clinic Operations & Resource Management

| Feature | What is new | Main users |
|---|---|---|
| **E14-F01** Appointment scheduling | 15 appointment types with duration, clinician roles, room / device needs, telehealth and self-booking flags, charge codes; site resources; weekly diary templates in site time; diary; conflict-checked booking (`409 resource_conflict`), drag-and-drop moves (`409 stale`), cancellation with waitlist offer; attendance; T−48 h / T−2 h reminders; automatic no-show; employee self-booking, rescheduling, cancellation and app check-in; telehealth join link; internal charging report and finance CSV | Clinic scheduler, clinicians, employee, OH admin |
| **E14-F02** Campaigns & mobile clinics | Campaign set-up (site, days, slots, stations, device kit, staff, target); slot generation; cohort booking of up to 5,000 per call with waitlisting; attendance capture; performance (utilisation, attendance); device kit must be in service and calibrated | Clinic scheduler, Nursing Head |
| **E14-F03** Check-in & queue | Reception / kiosk check-in by badge, employee ID or appointment; tickets; queue board without names with wait times; clinician call-next with hand-off to the chart encounters; start / complete | Reception, clinicians, employee |
| **E14-F04** Equipment & cold chain | Equipment register with calibration history and certificates, derived calibration state, 30-day alert to biomedical engineering; **device result capture blocked** when out of calibration or out of service; vaccine-fridge readings (manual and IoT), excursion detection, alerts, acknowledgement before closure, monthly report | Biomedical, OH nurse, Nursing Head |
| **E14-F05** Capacity & utilisation | Capacity from diary templates vs demand from the E07 due lists, E06 contact schedules and a three-month run rate, per site and month; what-if (extra sessions, campaign days); utilisation, no-show rate and average wait by site or clinician | Nursing Head, CMO, scheduler |

### 2.4 Platform

* **Kernel SPIs**: `AppointmentBooking`, `AppointmentStatusListener`, `DeviceCalibrationCheck`, `ResultFollowUpListener`,
  `FitnessClearanceGuard`, `AbsenceSignalListener`, `CapacityDemandSource` (Solution Design §4.3).
* **Master data**: clinic sites (`GET/POST /masterdata/clinic-sites`; six seeded), nine hazards, five job codes.
* **Documents**: module-level API (`storeUpload`, `generateSigned`, `shareAsSystem`, `systemContent`); templates
  `OH_ADVICE_REPORT`, `VACCINATION_CERTIFICATE`, `PRE_EMPLOYMENT_OUTCOME`; retention classes `ABSENCE_RECORD` (6 y) and
  `PRE_EMPLOYMENT` (1 y), both proposed.
* **Notifications**: 23 content-free templates; the template test renders every one through the content rules.
* **BFF**: `/workspace/{absence|advice-requests|surveillance|pre-employment}` pass-through; multipart uploads streamed
  untouched (Section 8, D1).

---

## 3. New roles and demo users

### 3.1 New roles

| Role | Purpose | Disclosure |
|---|---|---|
| `CLINIC_SCHEDULER` | Diaries, bookings, campaigns, waitlists, due-list invitations, diary templates, capacity | STATUS_ONLY – names and appointment type only |
| `BIOMEDICAL` | Equipment register, calibration, cold-chain logs | No person-level data |
| `CREW_LICENSING` | Crew medical certificate validity and expiry, crew without a valid medical | STATUS_ONLY (crew) |
| `CANDIDATE` | Pre-employment candidate: own questionnaire, consent, documents, booking | SELF |

Existing roles with new rights: `CLINIC_RECEPTION` (check-in, kiosk, queue board, certificate scan and validation), `HR`
(advice requests, LTA HR stages, onboarding status), `MANAGER`, `HSE_OFFICER` (exposure registers, IH import),
`CREW_SCHEDULING`, `HYGIENE_MANAGER`, `AUDITOR` (anonymised audit packs), `OH_ADMIN` (rules, feed, catalogue).

### 3.2 Demo users (local realm `deploy/local/keycloak/wellnessz-realm.json`)

| User | Role(s) | Employee / candidate ID | Used for |
|---|---|---|---|
| `scheduler1` (new) | CLINIC_SCHEDULER | S8611 | Due list, bulk invitation, diary, campaigns |
| `biomed1` (new) | BIOMEDICAL | S8602 | Equipment register, calibration of `AUD-LHR-02` |
| `crewlicensing1` (new) | CREW_LICENSING | S8603 | Crew certificate register, crew without valid medical (E7001) |
| `candidate1` (new) | CANDIDATE | **C9001** – not in the HRIS master | Candidate portal |
| `nurse1`, `physician1`, `ame1`, `cmo1`, `nursinghead1` | OH roles | N8001, D8101, D8201, D8301, N8401 | Queue, advice reports, follow-ups, return after illness, analytics |
| `admin1` | PLATFORM_ADMIN, OH_ADMIN | X7001 | Rules, feed quality, programmes, catalogue |
| `hr1`, `manager1`, `employee1` | HR, MANAGER, EMPLOYEE | H3001, M2001, E1001 | Workspace and app flows |
| `reception1`, `hse1`, `auditor1` | CLINIC_RECEPTION, HSE_OFFICER, AUDITOR | R8801, S8501, A6001 | Check-in / queue, IH import, audit packs |

Demo data adds employees E1006 Ellie Engineer, E1007 Sam Screener, E1008 Fiona Forklift, E1009 Cole Cleaner, E1010 Cathy
Cook and crew member E7001 Fred Flyer (expired medical); absences for E1001, E1003, E1004 and E1010; LTA case for E1004;
surveillance enrolments, invitations, certificates, immunisations, an STS follow-up (E1006) and a return-after-illness
item for E1004 (demo databases only); clinic resources, templates, seven devices (`AUD-LHR-01` valid, `AUD-LHR-02`
overdue, `SPIRO-HQ-01`, `ECG-HQ-01`, `VIS-LHR-01`, `FRIDGE-HQ-01` with an open excursion, `FRIDGE-LHR-01`), a ramp
audiometry campaign and appointments.

---

## 4. API summary

The authoritative references are the contract `docs/increment-03/API-Contract-E06-E07-E14.md` and the regenerated
OpenAPI document `docs/api/openapi.json`, which grows from 240 to **391 paths** (+151 paths, 176 operations):

| Area | Paths | Operations |
|---|---|---|
| `/absence/**` (OH, OH admin, HR analytics) | 32 | 36 |
| `/surveillance/**` | 47 | 54 |
| `/scheduling/**` | 39 | 48 |
| `/workspace/{absence, advice-requests, surveillance, pre-employment}/**` (managers, HR) | 8 | 9 |
| `/me/{absence, advice-reports, surveillance, pre-employment, immunisations, appointments, pregnancy-declaration}/**` | 18 | 21 |
| `/integration/{absence, pre-employment, ih-measurements, cosmic-dose, cold-chain}/**` (machine clients) | 6 | 6 |
| `/masterdata/clinic-sites` | 1 | 2 |

**Where the code deviates from the contract** (the code is authoritative; documented in Solution Design):

| Area | Deviation |
|---|---|
| E06 rules | `minDays` means "at least" – the "> 10 days" rule is seeded as `minDays: 11` (likewise 4, 8, 3 for the other duration rules) |
| E06 rules | A subsidiary rule overrides defaults of the same **type and action**; only SICKNESS / INJURY types trigger; rules look at episodes open or ended within 30 days |
| E06 certificates | Extra endpoint `GET /absence/certificates/{id}/content`; SUSPICIOUS is shown to HR as PENDING |
| E06 advice | Answers capped at 400 characters; release goes to the line manager for manager requests and to HR otherwise; HR notifications go to the `absence.hr-email` mailbox |
| E06 LTA | Completing the RTW_REVIEW milestone closes the pathway |
| E06 events | Additional `absence.hr-status.v1` to `hr-systems` (OH engaged / certificate validity) |
| E07 views | `GET /me/surveillance`, `GET /surveillance/compliance` and `GET /surveillance/employees/{id}/exposure-history` return wrapper objects, not plain arrays |
| E07 pre-employment | Outcome document shared to HR only when the candidate is already in the employee master |
| E07 audit packs | Anonymised by default for everyone except the CMO and the Nursing Head |
| E14 views | Extra display fields (`clinicianName`, `siteName`, `canCheckIn`, …) |
| E14 campaigns | `POST /scheduling/campaigns/{id}/book` returns `{booked, waitlisted, alreadyBooked, rejected}` |
| E14 booking | Outside published sessions → `422 no_session`, unless a clinician is named (administrative override, still conflict-checked) |
| E14 waitlist | Cancellations **offer** the slot (`appointment.waitlist-offer`); there is no automatic re-booking |
| E14 queue | Call-next on an empty queue → `404 queue_empty` |
| E14 check-in | Window from 60 minutes before the start until the end of the appointment |
| E14 cold chain | Closing an excursion requires a prior acknowledgement |
| E14 calibration | Certificates stored in the `scheduling` schema (bytes + SHA-256), not in the document service |
| Errors | Screened clinical text returns `422 clinical_content_not_allowed` with the offending `field` |

---

## 5. Database migrations

| Migration | Schema(s) | Content |
|---|---|---|
| `V9__increment3_platform.sql` | masterdata, docs | `clinic_site` (6 sites); hazards `DUST_RESP, SOLVENT, BBP, VIBRATION, HEAT, NIGHT_WORK, HEIGHT, DGR, VDU`; job codes `SECURITY_SCREENER, CLEANER, FORKLIFT_OP, CLINICAL_STAFF, ENG_HEIGHT`; hazard re-mapping of ENGINEER, RAMP_AGENT, OFFICE_ADMIN; retention classes `ABSENCE_RECORD` (6 y) and `PRE_EMPLOYMENT` (1 y), **proposed** |
| `V10__e06_absence.sql` | absence (new) | 14 tables (feed runs, episodes, mismatches, rules and versions, queue, contacts, certificates and calls, LTA pathway / milestones / conferences, advice requests and reports), sequence `advice_ref_seq`, 8 seeded trigger rules with version snapshots, 9 configuration entries; grants without DELETE |
| `V11__e07_surveillance.sql` | surveillance (new) | 20 tables (programmes, profiles, enrolments, completions, invitations, pre-employment, crew certificates, immunisation matrix / administrations / titres / reminders, SEGs, IH measurements, cosmic dose, pregnancy declarations, evaluation rules, follow-ups, return after illness, compliance snapshots, audit packs); 13 programmes, 6 evaluation rules, 11 immunisation requirements, 4 SEGs, 7 configuration entries; DELETE only on reminders and snapshots |
| `V12__e14_scheduling.sql` | scheduling (new) | 11 tables (appointment types, resources, diary templates, campaigns, appointments, campaign slots, waitlist, equipment, calibrations, temperature readings, excursions); 15 appointment types with charge codes; 8 configuration entries; DELETE only on diary templates |
| `db/demo/R__demo_e06_absence.sql`, `R__demo_e07_surveillance.sql`, `R__demo_e14_scheduling.sql` (repeatable) | absence, surveillance, scheduling, casemgmt, masterdata | Demo population (Section 3.2). **Loaded only with the `local` / test profiles**; idempotent; dates relative to `current_date`; encrypted columns left empty. Never enable `classpath:db/demo` in shared environments |

Migrations are additive (three new schemas, new tables, seed rows inserted once). Seed rows are not overwritten by
later runs, so edits made through the APIs survive.

**Rollback.** Migrations are forward-only and ship without down scripts. Running the Increment-2 application against
the V12 schema is not supported: absence cases, surveillance follow-ups, roster statuses and blocked fitness decisions
created after the upgrade would no longer be explained by any module. Rolling back therefore means restoring the
pre-upgrade backup together with the previous images; anything recorded after the upgrade is lost, so agree a rollback
window before go-live.

---

## 6. Configuration

| Item | Change |
|---|---|
| `spring.flyway.schemas` (core) | Adds `absence, surveillance, scheduling` |
| `spring.servlet.multipart.enabled` (BFF) | **`false`** – the BFF must stream uploads to the core untouched |
| `WZ_ANALYTICS_MIN_GROUP` (`wellnessz.analytics.min-group-size`) | Default 5 (E06 never below 3) – small-cell suppression for absence analytics and surveillance compliance |
| `wellnessz.integration.outbound-allowlist.hr-systems` | Adds `offerRef, candidateId, preEmploymentStatus, ohEngaged, certificateValidity` |
| `wellnessz.absence.feed-cron` / `triggers-cron` / `lta-cron` | `0 40 2 * * *` / `0 0 3 * * *` / `0 10 3 * * *` UTC |
| `wellnessz.surveillance.due-job-cron` / `crew-job-cron` / `purge-job-cron` | `0 40 1 * * *` / `0 50 1 * * *` / `0 10 3 * * *` UTC |
| `wellnessz.scheduling.reminder-cron` / `no-show-cron` / `calibration-cron` | Every 15 min / every 15 min (offset 5) / `0 40 2 * * *` UTC |
| `wellnessz.scheduling.telehealth-base-url` | Fallback join-URL prefix (the config entry wins) |
| `wellnessz.record.require-registered-devices` | Default `false`; `true` rejects device results from devices not in the register |
| Mailboxes (`masterdata.config_entry`) | **Set per environment:** `absence.hr-email`, `notifications.crew-licensing.email`, `notifications.hr-onboarding.email`, `notifications.biomedical.email` |
| E06 business settings | `absence.notify-hr-on-engagement` (true), `absence.advice.employee-review-required` (true), `absence.queue.sla-days` (5), `absence.queue.ame-sla-days` (1), `absence.advice.sla-working-days` (5), `absence.lta.contact-interval-days` (14), `absence.public-holidays` (**2026–2027 list – maintain yearly**) |
| E07 business settings | `surveillance.max-invitations` (2), `surveillance.cosmic-dose-alert-msv` (6.0), `surveillance.new-enrolment-grace-days` (30), `surveillance.pre-employment-sla-days` (10), `surveillance.crew.issuing-authority` ("UK CAA") |
| E14 business settings | `scheduling.self-booking.min-notice-hours` (24), `…max-days-ahead` (60), `…one-open-per-type` (true), `scheduling.check-in.window-minutes` (60), `scheduling.no-show.grace-minutes` (30), `scheduling.telehealth-base-url`, `scheduling.calibration.due-alert-days` (30) |
| Business content | Trigger rules, programmes, evaluation rules, immunisation matrix, SEGs, appointment types, clinic sites – seeded by V9–V12, maintained in the OH Workspace |

---

## 7. Verification results (29 October 2026)

| Check | Result |
|---|---|
| Java build and tests (`./mvnw verify`) | **210 tests, 0 failures** – `wz-core-app` 89 (including `Increment3AbsenceIntegrationTest` 10, `Increment3SurveillanceIntegrationTest` 9, `Increment3SchedulingIntegrationTest` 9 on PostgreSQL 16, and `ArchitectureTest` with the new E06 / E07 / E14 boundary rules); new module unit tests E06 18, E07 13, E14 18 |
| SAST (SpotBugs + FindSecBugs, `-Psecurity`) | **0 findings**, no new exclusions |
| Web unit tests (vitest) | oh-workspace **463**, employee-app **191**, manager-workspace **154** – all passing; lint 0 warnings; production builds clean |
| End-to-end, real Keycloak logins through the BFF | Increment 1 **18 / 18**, Increment 2 **25 / 25**, Increment 3 **32 / 32** (`tools/e2e/e2e-increment3.js`); 37 screenshots in `docs/images/screens/increment-03/` (`inc3-oh-01..27`, `inc3-emp-01..05`, `inc3-mgr-01..05`) |
| API contract | `docs/api/openapi.json` regenerated – **391 paths** (was 240) |

Selected end-to-end evidence (Increment 3):

| Flow | Observed |
|---|---|
| Absence feed | 3 received, 2 matched (employee ID and badge), 1 unmatched listed for HR |
| Trigger and queue | `DURATION_10D` early-intervention item; case opened and first contact logged; 8 trigger rules; test mode without cases |
| Certificates | Employee photo upload; OH validates; HR sees validity only |
| LTA | Milestones at weeks 2, 4, 8, 12, 26 with overdue flags; HR stage with a clinical note → 422 |
| Advice | Report naming a diagnosis → 422 (field reported); signed → AWAITING_EMPLOYEE; employee consent → RELEASED; manager sees fitness, adjustments and prognosis only; manager never sees `reasonCategory` |
| Analytics | Suppressed units; KPIs LTA with case 100 %, median trigger → contact 1 day |
| Programmes and due list | HEARING_CONSERVATION simulation by unit; scheduler invites and books |
| Exposure | Noise 86.5 dB(A) above the 80 dB(A) action level enrols the ramp SEG |
| Calibration | `AUD-LHR-02` blocked with `422 device_out_of_calibration`, accepted after the biomedical calibration |
| Follow-up and return after illness | STS follow-up cases; FIT decision for E1004 → `422 clearance_required` |
| Pre-employment | Candidate questionnaire and consent; HR sees clearance FIT only |
| Crew | Crew licensing sees EXPIRING and E7001 without a valid medical |
| Immunisation | Vaccine recorded from a GS1 barcode (lot, expiry, site, consent) |
| Compliance | Dashboard by programme; anonymised FOOD_SAFETY audit pack (PDF + CSV) |
| Scheduling | Double booking `409 resource_conflict`; employee self-booking / rescheduling; badge check-in ticket; queue board without names; call-next hand-off to the chart |
| Cold chain | IoT reading 10.4 °C opens an excursion; nurse acknowledges |
| Campaign and capacity | Cohort booked in one call with performance; capacity forecast per site |

---

## 8. Defects found and fixed during integration and end-to-end verification

| # | Defect | Found during | Fix |
|---|---|---|---|
| D1 | **Browser uploads failed with 400**: the BFF parsed multipart bodies with Spring's servlet multipart support and forwarded an empty body to the core (sick certificates, scans, photos, candidate documents) | E2E | `spring.servlet.multipart.enabled=false` in `apps/wz-bff/src/main/resources/application.yml`; the BFF streams the body untouched and the core enforces the 10 MB / 11 MB limits |

Adjustments made while integrating (not defects in released behaviour):

* The demo return-after-illness item for E1004 is skipped on `*_test` databases so that the Increment-2 fitness
  integration tests, which record FIT decisions for E1004, keep passing.
* The surveillance integration test accepts that a re-invitation after a no-show is booked when scheduling is deployed
  (`AppointmentBooking` available), as the no-show policy intends.

---

## 9. Known limitations and deviations

| Area | Limitation | Planned handling |
|---|---|---|
| Absence feed | `AbsenceFeedConnector` default is a no-op; HRIS / T&A / crew pull connectors not built (push endpoint ready) | E15 |
| Telehealth | Join link and documentation link only – no video | E04 |
| Cold-chain IoT | Contract `/integration/cold-chain/readings` only; no gateway connected | Site integration / E15 |
| Crew examinations | CAA examination template formats pending CAA confirmation | AME lead sign-off |
| Programmes | Content of the 13 programmes pending CMO validation; headcounts not yet reconciled with HR | E07-BL-002 sign-off |
| Pre-employment | Outcome document shared to HR only when the candidate is already in the employee master; otherwise HR status view + disclosure log | Candidate document sharing in a later increment |
| Pre-employment purge | Personal fields cleared after 12 months; document bytes follow the platform retention review (no automatic deletion) | After retention approval |
| Analytics | The small demo population means most analytics groups are suppressed – by design | – |
| Demo data | Return-after-illness demo item for E1004 skipped on `*_test` databases | By design |
| Patterns | Only MONDAY_FRIDAY is a configurable trigger pattern; POST_HOLIDAY and FREQUENT_SHORT are summary flags; the public-holiday list must be maintained yearly | Later increment |
| Calibration certificates | Held in the scheduling schema, outside the document retention schedule | Review with the DPO |
| Load | Bulk invitation (5,000), campaign booking and capacity not load-tested at production volume | Performance test before go-live |
| Sign-offs | Trigger rules, advice wording, questionnaire, immunisation matrix, evaluation rules, retention classes, appointment catalogue and prices (Solution Design §32.1) | Business review |

---

## 10. Upgrade steps

1. **Back up** the database (point-in-time recovery enabled) and note the Flyway version (expected V8). Keep the previous
   application images available for the rollback window.
2. **Schema list everywhere** – add `absence, surveillance, scheduling` (before `analytics`) to every place that lists
   schemas: core `spring.flyway.schemas` (built in), Helm `values.yaml` (`flyway … schemas`), the local Postgres init
   script `deploy/local/postgres/01-init-wellnessz.sh`, the managed-database bootstrap `deploy/sql/bootstrap-managed-postgres.sql`
   (search paths and schema grants for `wz_app` and `wz_migrator`) and the CloudNativePG cluster
   `deploy/hybrid/cnpg/03-cluster.yaml`. The CNPG `initdb` statements only run for new clusters – on an existing cluster
   create the three schemas owned by `wz_migrator` and update the `wz_app` / `wz_migrator` search paths manually.
3. **Identity provider** – create the roles `CLINIC_SCHEDULER`, `BIOMEDICAL`, `CREW_LICENSING` and `CANDIDATE` (Entra:
   app roles and groups) and **add all four to the role scope mappings of `wellnessz-bff` and `wellnessz-mobile`**
   (`fullScopeAllowed=false` – without the mapping the role is silently dropped). Candidate identities need an
   `employee_id` claim equal to the candidate ID used in the HRIS offer. Locally, the realm file already contains the roles,
   scope mappings and users `scheduler1`, `biomed1`, `crewlicensing1`, `candidate1`: **re-import the realm**
   (`./deploy/local/down.sh --purge` then `./deploy/local/up.sh`).
4. **BFF** – deploy the BFF with `spring.servlet.multipart.enabled=false` (in the shipped `application.yml`); make sure no
   environment override re-enables it, otherwise every upload fails with 400.
5. **Configuration** – set the mailboxes `absence.hr-email`, `notifications.crew-licensing.email`,
   `notifications.hr-onboarding.email`, `notifications.biomedical.email`; review `absence.public-holidays`,
   `surveillance.crew.issuing-authority` and `scheduling.telehealth-base-url`; add the extended `hr-systems` allow-list if
   overridden per environment; keep `WZ_FLYWAY_LOCATIONS` **without** `classpath:db/demo`.
6. **Migrate** – run the Flyway job with the migrator role; confirm V9–V12 applied and that schemas `absence`,
   `surveillance` and `scheduling` exist with runtime grants (no DELETE on clinical tables).
7. **Deploy** core API, BFF, employee app, manager workspace and OH Workspace (Helm upgrade).
8. **Reference data** – maintain clinic sites, resources and diary templates per site; register equipment with current
   calibration dates **before** device capture goes live (or keep `require-registered-devices=false`); review trigger rules,
   programmes, immunisation matrix and SEGs; load `surveillance.employee_profile` (dates of birth, licences) where the over-40
   frequency matters.
9. **Integration clients** – client-credentials `INTEGRATION` clients for the absence feed, partner certificates, HRIS
   offers, IH import, cosmic dose and the cold-chain gateway (one client per source recommended for audit separation).
10. **Smoke test** – run `tools/e2e/e2e-increment3.js` (and the Increment 1 / 2 scripts) against the environment, or the
    equivalent manual checks: feed → trigger → accept; certificate upload; advice release; due list → invite and book;
    calibration block; follow-up; check-in → queue → call-next; cold-chain excursion.
11. **Monitoring** – watch the nine new scheduled jobs in `platform.scheduled_job_run`, the outbox relay for `hr-systems`
    and `crew-rostering`, and audit events `ABSENCE_TRIGGER_RAISED`, `SURVEILLANCE_ESCALATED`, `PRE_EMPLOYMENT_PURGE` and
    denied absence / surveillance reads.

---

## 11. Next steps

* **E15 integration** – production connectors for HRIS, time-and-attendance and crew-management absence feeds
  (`AbsenceFeedConnector` beans), the HRIS offer event, the IH / EHS system, the cosmic-dose provider and the cold-chain
  IoT gateway; real external publisher for `hr-systems` and `crew-rostering`.
* **E04 telehealth** – video consultation behind the existing telehealth slots and join links.
* **Business sign-offs** – CMO validation of the 13 programmes and evaluation rules; CAA confirmation of the examination
  formats; DPO approval of retention classes, notification wording and the advice-report template; Nursing Head / HR
  agreement on trigger thresholds per subsidiary.
* **Candidate document sharing** – share the pre-employment outcome document with HR for candidates not yet in the
  employee master.
* **E17 analytics** – absence and surveillance KPIs into the enterprise analytics layer; load testing of bulk
  invitation, campaign booking and capacity at production volume.
