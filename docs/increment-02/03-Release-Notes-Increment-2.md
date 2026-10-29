# WellnessZ OHCM – Release Notes, Increment 2 (E03 OH Record + E05 Fitness for Duty / RTW)

| | |
|---|---|
| **Document** | Increment 2 – 03 Release Notes |
| **Release content** | E03 Occupational Health Record & Clinical Encounter Documentation · E05 Fitness for Duty, Work Restrictions & Return-to-Work · new platform document service |
| **Audience** | CMO, Nursing Head, DPO, Cyber GRC, CIO, project sponsor, platform operations |
| **Database** | Flyway V6, V7, V8 (+ repeatable demo data `R__demo_e03_record.sql` for the `local` profile only) |
| **New application** | None – new screens in `web/oh-workspace`, `web/employee-app` and `web/manager-workspace`; no new image or Helm component |
| **Companion documents** | 01 – Solution Design E03/E05 · 02 – Requirements Traceability Matrix · API contract `API-Contract-E03-E05.md` · OpenAPI `docs/api/openapi.json` (240 paths) |

---

## 1. Highlights

* **One OH chart per employee** – alerts, fitness, restrictions, open cases, surveillance due, immunisations,
  problems, medications, allergies, results, documents, ODH job and exposure history, FHIR R4 and ODH export.
* **Structured, signed clinical notes** – eight encounter templates with body map, coded diagnoses, photos with
  consent, SHA-256 e-signature, signed addenda and an unsigned-notes reminder list. Clinical cases can no longer be
  closed without a coded diagnosis.
* **Closed-loop results** – HL7 v2 lab orders and results, audiometry / spirometry / vision / ECG capture with
  calibration ID and automatic flags (including the audiometric threshold shift), an abnormal-result worklist and a
  72-hour escalation to the CMO.
* **Decision support** – allergy, interaction and duplicate-test alerts that need a reason to override; protocol,
  surveillance, duration-guideline and fitness-standard prompts.
* **Documents without leakage** – ten PDF templates, e-signature, versioning, consent-based sharing with partners,
  a disclosure log the employee can read, scanned reports searchable without a plaintext index, retention schedule
  and legal hold.
* **Fitness for duty end to end** – decisions against twelve versioned role standards with AME / CMO sign-off,
  diagnosis-free certificates, restriction-to-job matching and modified-duty proposals, phased RTW plans, electronic
  job offers, crew roster feed with acknowledgement and 15-minute reconciliation, food-handler exclusion and
  clearance, restriction expiry alerts, ill-health retirement with independent appeal.
* **Privacy by design** – new DPO role, break-glass with DPO alert and review, consent withdrawal that stops partner
  access immediately, and negative tests proving that HR, managers, reception and the DPO never see clinical records.

Requirement coverage: 73 workbook items traced – 49 implemented, 23 partially implemented (8 of them gated mainly by
business sign-off) and 1 deferred (training task). See document 02.

---

## 2. What is new, by persona

### OH nurse (`OH_CLINICIAN`) and case manager (`CASE_MANAGER`)

* **Employee chart** (`#/employees/{id}/chart`) reachable from queues, the case header and a search: summary panel and
  tabs for problems, medications (with CDS), allergies, immunisations, jobs and exposures, results and orders, documents,
  encounters and timeline; FHIR and ODH download.
* **Encounters** – choose a template, document answers, SOAP, body map (front / back, several sites, mechanism) and
  coded diagnoses with a terminology picker (search, favourites, recent), add photos with consent, sign (signature hash
  shown), add addenda, mark entered in error. *Unsigned notes* lists your drafts older than one hour.
* **Results** – device entry for audiometry, spirometry, vision and ECG; results worklist with acknowledge-with-action
  and result letters.
* **Documents** – generate, sign, version, share (employee, manager, HR for non-clinical documents; partner with
  consent), revoke, scan and search.
* **Fitness and RTW tabs on the case** – fitness decision (non-licensed standards), restrictions and job matching,
  modified-duty proposals, RTW plan builder with reviews, food-handler exclusion and clearance, case summary.
* *Active restrictions* with expiring / lapsed filters and one-click extend or close; *RTW overdue reviews*.
* **Emergency access (break-glass)** to restricted items with a justification.
* Case managers read the chart and run fitness / RTW workflows but cannot write clinical entries, sign encounters or
  clear food handlers.

### OH physician (`OH_PHYSICIAN`), AME (`AME`) and psychological health clinician (`PSYCH_CLINICIAN`)

* Everything above, plus lab and device orders (order sets), ill-health retirement / redeployment assessments
  (physicians).
* **AME**: decisions against licensed standards (CAA Class 1 / 2, cabin crew), crew assessment with EASA limitation codes
  and a signed pilot or cabin crew medical report, fitness sign-off queue.
* **Psychological health clinicians** are the only role that creates mental-health encounters; those encounters stay in
  the restricted class.

### Chief Medical Officer (`CMO`)

* Sign-off of permanent unfitness; IHR decisions and appeal decisions (a different CMO from the original decision).
* Approval of encounter templates; maintenance of the fitness-standards library (each change creates a new version).
* **Crew dashboard** (unfit / limited by base and fleet, weekly trend, no identifiers, small cells shown as `<5`),
  roster-feed reconciliation, break-glass monthly report, CDS statistics, restrictions dashboard by unit, results
  overdue for more than 72 hours.

### Nursing Head (`NURSING_HEAD`)

* All unsigned notes, RTW reviews overdue for more than two days (escalated to the Nursing Head), CDS statistics,
  restrictions dashboard, restriction catalogue and job-demand profile maintenance.

### Data Protection Officer (`DPO`) – new role

* Privacy area of the OH Workspace only: **break-glass reviews** (pending, overdue flag, appropriate / inappropriate with
  note), **monthly break-glass report**, **disclosure log** per employee, **retention schedule** (approve), records past
  retention, **legal hold**. E-mail alert on every break-glass use. No access to clinical records.

### OH administrator (`OH_ADMIN`)

* Fitness standards (with CMO), restriction catalogue, job-demand profiles including bulk JSON import, terminology
  concept import and release register, retention schedule (read), roster-feed reconciliation.

### Line manager (`MANAGER`)

* Manager workspace: **Team restrictions** (fitness status and functional restrictions with validity and expiring /
  lapsed badges), **Modified duty** requests (accept, or decline with a reason code), **Return to work** plans
  (acknowledge), **Documents** shared to me (certificates, restriction notes, RTW plans). Content-free e-mails for new
  proposals, RTW plans, shared documents and expiring restrictions. Still no clinical information anywhere.

### HR business partner (`HR`)

* **Alternative-duty job offers** from a case (job, duties, location, shift, hours, pay basis, restrictions respected,
  contact, respond-by); the signed offer letter goes straight to the employee portal; withdraw. **IHR** status, decision
  text and appeal on the employee's behalf. Team restrictions, RTW plans (read), duty proposals escalated to HR,
  documents shared to HR.

### Employee and crew member (`EMPLOYEE`)

* **My documents** (open PDF, acknowledge certificates and restriction notes), **My return to work** (agree to the
  plan), **Job offers** (accept or decline), **Privacy – who received my information** (disclosure log, consent
  withdrawal that immediately stops partner access).
* Crew decrease-in-fitness declaration now shows the case reference, what happens next, the AME contact and when the
  declaration will be acknowledged.

### Crew scheduling (`CREW_SCHEDULING`)

* OH Workspace *Roster feed* screen: sent / acknowledged / unacknowledged after 15 minutes / p95 latency and the recent
  status-only messages. `GET /status/crew` now includes roster status (FIT, LIMITED, UNFIT, PENDING), validity dates and
  operational limitation codes (e.g. OML). Never diagnoses or non-operational codes.

### Catering hygiene manager (`HYGIENE_MANAGER`)

* `GET /fitness/food-handlers` – status (fit to handle food, excluded, return pending clearance) and dates only, every
  read audited; status changes also flow to the `catering-hygiene` feed. No dedicated screen yet.

### Clinic reception / clinic assistant (`CLINIC_RECEPTION`)

* *Scan documents* – file an external report for an employee (PDF, JPEG, PNG ≤ 10 MB) without being able to read the
  record.

### Partner provider (`PARTNER_PROVIDER`)

* `GET /partner/documents` – documents shared to the partner organisation while the employee's consent is active (API
  only; no partner screen).

### Integration clients (`INTEGRATION`)

* Laboratory gateway: pull HL7 v2 ORM^O01 orders, acknowledge, post results as JSON or HL7 v2.5 ORU^R01.
* Device gateway: post audiometry, spirometry, vision and ECG results with device and calibration IDs.
* Rostering: pull the roster feed and acknowledge each message.

---

## 3. Database migrations

| Migration | Schema(s) | Content |
|---|---|---|
| `V6__increment2_platform.sql` | masterdata, platform, docs (new), casemgmt | `fitness_standard` (12 standards, versioned), `restriction` (16), `job_demand_profile` (28 profiles); `break_glass_grant`, `disclosure_log`, `scheduled_job_run`; `docs.document`, `document_share`, `document_sequence`, `retention_schedule` (7 **proposed** rules); `oh_case.primary_diagnosis_*` columns (nullable); grants |
| `V7__e03_record.sql` | ohrecord (new), platform | Chart tables (problem, allergy, medication, immunisation, job_history), encounter tables (encounter, diagnosis, observation, addendum, photo, template status – 8 DRAFT), orders and results, CDS (drug classes, 13 rules, alert log), terminology (favourites incl. role starter sets, usage, ≈ 70 mapping prefixes), `platform.terminology_release`, starter concepts in `platform.code_concept` (≈ 120 ICD-10, 50 SNOMED CT, 40 LOINC) with a display index; least-privilege grants (no DELETE on clinical tables) |
| `V8__e05_fitness.sql` | fitness (new) | fitness_decision, restriction_instance, duty_proposal, rtw_plan, rtw_review, job_offer, roster_feed_message, crew_profile, food_handler_episode, ihr_assessment; grants |
| `db/demo/R__demo_e03_record.sql` (repeatable) | ohrecord | Demo chart for E1004 (penicillin allergy, low back pain, Hepatitis B, baseline audiogram, earlier job). **Loaded only with the `local` profile**; never enable `classpath:db/demo` in shared environments |

Migrations are additive: three new schemas, new tables and three nullable columns on `casemgmt.oh_case`. Seed rows are
inserted once; edits made later through the APIs are not overwritten.

**Rollback.** Migrations are forward-only and ship without down scripts. Running the previous application against the
V8 schema is not supported: records created after the upgrade (encounters, decisions, documents, disclosures,
break-glass grants) would be invisible to it, and fitness statuses set by E05 would no longer be explained by any
decision. Rolling back therefore means **restoring the database from the backup taken before the upgrade** (upgrade
step 1) together with the previous application images; anything recorded after the upgrade is lost, so agree a
rollback decision window (for example the first business day) before go-live.

---

## 4. New configuration

| Item | Change |
|---|---|
| `spring.flyway.schemas` | Adds `docs, ohrecord, fitness` |
| `WZ_DPO_EMAIL` (`wellnessz.notification.dpo-email`) | **Set per environment** – DPO mailbox for break-glass alerts |
| `WZ_EMPLOYEE_PORTAL_URL`, `WZ_MANAGER_WORKSPACE_URL` | Links in employee / manager notifications |
| `WZ_DOCUMENTS_FONT_PATH` (`wellnessz.documents.font-path`) | Optional Unicode TrueType font for Arabic and other scripts in PDFs; empty = Helvetica (Latin only) |
| `wellnessz.documents.ocr.tesseract-path`, `wellnessz.documents.ocr.languages` | Optional local OCR; allowed paths `/usr/bin/tesseract`, `/usr/local/bin/tesseract`, `/opt/homebrew/bin/tesseract`; languages `eng`, `ara`, `fra`, `eng+ara`, `eng+fra`, `eng+ara+fra` |
| `WZ_DOCUMENTS_RETENTION_CRON` | Default `0 30 2 * * *` UTC |
| `WZ_DIAGNOSIS_REQUIRED_ON_CLOSE` | Default `true` – keep `true` outside local experiments |
| `wellnessz.record.*` | `unsigned-after` PT1H, `result-overdue-after` PT72H, `overdue-cron` hourly at :15, `restricted-result-codes` `5643-2,19659-3`, `ai.provider` `none` |
| `wellnessz.fitness.*` | `ame-contact`, `declaration-ack-hours` 24, `daily-job-cron` 02:20 UTC, `roster-reconciliation-interval` PT5M, `ihr-appeal-window-days` 28, `ihr-appeal-decision-days` 30 |
| `wellnessz.integration.outbound-allowlist` | `crew-rostering` adds `messageId, rosterStatus, validFrom, validUntil, operationalLimitations`; `hr-systems` adds `messageId, offerId, accepted, respondedAt`; `catering-hygiene` adds `foodHandlerStatus, excludedFrom, expectedReturn` |
| BFF | `/workspace/team-restrictions`, `/workspace/duty-proposals`, `/workspace/rtw-plans`, `/workspace/documents` pass through to the core unchanged (built in, no setting) |
| Business content | Fitness standards, restriction catalogue, job demands, retention schedule, encounter templates, terminology, CDS tables – seeded by V6 / V7; maintained in the OH Workspace |

---

## 5. Identity provider changes (Keycloak / Microsoft Entra ID)

* Create the application role **`DPO`** (Entra: app role on the API registration and group `sg-wellnessz-dpo`) and assign
  it to the DPO and deputy.
* **Add `DPO` to the role scope mappings of the BFF client (`wellnessz-bff`) and the mobile client
  (`wellnessz-mobile`).** Both clients have `fullScopeAllowed=false`; without the mapping DPO users are silently treated
  as having no access (the Increment-1 lesson). Done in `deploy/local/keycloak/wellnessz-realm.json`.
* **Partner provider users**: role `PARTNER_PROVIDER` plus the user attribute / claim **`org_scope = /PARTNERS/<CODE>`**
  (e.g. `/PARTNERS/CITYHOSP`). The last path segment (`[A-Z0-9_]{2,40}`) must equal the partner code OH uses when
  sharing. In Entra ID provide `org_scope` through a directory extension / optional claim that only administrators can
  change.
* Laboratory, device and rostering gateways are confidential clients with client credentials and the `INTEGRATION` role
  (one client per gateway is recommended for audit separation).
* Local realm users added: `dpo1` (DPO) and `partner1` (PARTNER_PROVIDER, `/PARTNERS/CITYHOSP`). A realm imported before
  Increment 2 must be re-imported (`./deploy/local/down.sh --purge` then `up.sh`).

---

## 6. Deployment notes

| Topic | Note |
|---|---|
| Images and Helm | No new image or Helm component; redeploy core API, BFF and the three web apps |
| PDF font (optional) | Needed only for Arabic or other non-Latin text in generated PDFs. The Helm chart has no extra-volume hook, so build a derived `wz-core-app` image with the font (scanned and signed like the base image) or add a `core.extraVolumes` value, then set `WZ_DOCUMENTS_FONT_PATH` |
| Tesseract OCR (optional) | Needed only for OCR of JPEG / PNG scans (image-only PDFs are not OCR'd in this release). Install Tesseract and the `eng` / `ara` / `fra` language packs in the derived core image at one of the allowed paths; OCR runs locally, data does not leave the host |
| Laboratory gateway | Client-credentials `INTEGRATION` client; polls `GET /integration/lis/orders?status=QUEUED`, sends the ORM^O01 to the laboratory, acknowledges with `POST …/{id}/ack`; returns results through `POST /integration/hl7v2` (HL7 v2.5 ORU^R01, `text/plain`) or `POST /integration/lis/results` (JSON) |
| Device gateway | Device middleware posts to `POST /employees/{id}/results/device` with device ID, model, calibration ID and date |
| Rostering | Rostering pulls `GET /integration/roster-feed?since=` and acknowledges each message (`POST …/{messageId}/ack`); the outbox destination `crew-rostering` additionally carries the allow-listed event – until the E15 adapter is configured the external publisher is a logging adapter. Monitor the audit action `ROSTER_FEED_UNACKNOWLEDGED` |
| Scheduled jobs | Retention flag (02:30 UTC), E05 daily job (02:20 UTC), result escalation (hourly) – each runs once across replicas through `platform.scheduled_job_run` |
| Crew dashboard data | Load `fitness.crew_profile` (employee, fleet, base) from the crew system by a DBA / ETL job; no API loader exists yet |
| Monitoring | Add SIEM rules for `BREAK_GLASS`, `ROSTER_FEED_UNACKNOWLEDGED`, denied `CHART_READ` / `ENCOUNTER_READ` / `DOCUMENT_READ` audit events, and for `AI_DRAFT` once a provider is enabled |

---

## 7. Verification results

| Check | Result |
|---|---|
| Java build and tests (`./mvnw verify`) | **128 tests pass** – unit tests, integration tests on PostgreSQL 16 (including the 24 Increment-2 acceptance tests: 5 platform, 12 record, 7 fitness), ArchUnit boundaries (new E03 / E05 / document-service rules) and BFF tests |
| SAST (SpotBugs + FindSecBugs) | **0 findings**; 6 justified exclusions in `config/spotbugs-exclude.xml` – one new: `TesseractOcrProvider` `COMMAND_INJECTION` (binary restricted to three fixed install paths, languages to six fixed values, private temp file, 60 s timeout) |
| Web unit tests (vitest) | oh-workspace **305**, employee-app **138**, manager-workspace **99** – all passing; lint and production build clean |
| End-to-end (`tools/e2e/e2e-increment2.js`) | **25 / 25 PASS** against a real Keycloak, BFF, core API and the three web apps; 32 screenshots in `docs/images/screens/increment-02/` |
| API contract | `docs/api/openapi.json` regenerated – **240 paths** |
| Performance (integration tests) | Chart summary < 2 s; terminology search < 1 s (no production-volume load test yet) |

---

## 8. Defects found and fixed during integration and end-to-end verification

| # | Defect | Found during | Fix |
|---|---|---|---|
| D1 | The BFF's Increment-1 `/workspace/` alias stripped the prefix of the new core `/workspace/*` endpoints, so manager and HR screens got 404 | E2E | Pass-through list in `ApiProxyController` for `/workspace/team-restrictions`, `/duty-proposals`, `/rtw-plans`, `/documents`; test `ApiProxyPathTest` |
| D2 | The OH Workspace saved the SOAP template section inside `answers`, which the server rejected (400 unknown field) | Integration | The SOAP section is sent as `soap` |
| D3 | Body map laterality and view did not match the API model | Integration | UI maps the view (front / back) to `view` and keeps laterality in the region code |
| D4 | Retention due list and food-handler clearance used different field names in UI and API | Integration | OH Workspace aligned with the API fields |
| D5 | Case-scoped document list required an `employeeId` parameter, so the case *Documents* tab failed | E2E | The employee is resolved from the case (`DocumentService.employeeOfCase`) |
| D6 | Result notifications were blocked by the gateway content rule (the word "result") | Integration | Dedicated content-free templates `result.abnormal` / `result.overdue` reworded ("a new item … needs your acknowledgement") |
| D7 | HR could not sign or share the job-offer letter, so offers never reached the employee portal | Integration | HR may generate, sign and issue non-clinical HR templates (job offer) to the employee only, within its org scope |
| D8 | Crew scheduling and the CMO were blocked by the platform filter on `/integration/**` | E2E | People use `/crew/roster-feed` and `/crew/roster-feed/reconciliation` aliases; `/integration/**` stays machine-only |
| D9 | The clinical-text guard missed common diagnoses (e.g. "disc prolapse") in manager-facing text | Integration test | Vocabulary extended (prolapse, hernia, sciatica, tendinitis, bursitis, carpal tunnel, dermatitis, eczema, psoriasis, infection …); test asserts the certificate rejection |

---

## 9. Known limitations

| Area | Limitation | Planned handling |
|---|---|---|
| Clinical content | Encounter templates, fitness standards, restriction catalogue, document wording, mappings are drafts | Sign-offs (Section 10) |
| Terminology | Starter subset only; no external terminology server; no update runbook; 300 ms target not load-tested | Licences and runbook (S7) |
| CDS | Curated starter drug / allergy / interaction tables | Pharmacist review; licensed knowledge base decision |
| OCR | Image-only PDFs are not OCR'd; image OCR needs Tesseract | Rasterise-and-OCR in a later increment |
| Search | Whole-word matching only (blind index) | By design; revisit with the DPO if prefix search is needed |
| Retention | Only the document-date trigger evaluated; structured records not flagged; no destruction workflow | After schedule approval |
| Devices | API contract only; no device drivers | E15 / site integration |
| LIS, rostering, HRIS, hygiene | Interfaces built and tested; production connections not configured; external publisher is a logging adapter | E15 |
| FHIR | Procedure, Consent, Task, ServiceRequest not mapped; no subscriptions | Later increment |
| Photos | No thumbnails | Later increment |
| Unsigned notes | On-screen list only, no e-mail reminder | Later increment |
| AI assistant | No provider enabled; no audio capture front-end; pilot not run | E03-BL-019 pilot after DPIA |
| Crew dashboard | `fitness.crew_profile` has no loader (all crew under base UNKNOWN) | Crew-system feed (E15) |
| Screens | No screen for the hygiene manager or partner providers (APIs only) | Later increment |
| Over-40 validity | Implemented but inactive – HRIS feed has no date of birth | HRIS feed extension |
| Job demands | 28 of the top-150 profiles, none validated | E05-BL-004 |
| KPIs | RTW-within-5-days, matched-duty rate, documentation time not reported | E17 analytics |
| Usability | Chart usability (≥ 4/5) and "< 6 min MSK note" not measured | Usability sessions with pilot clinicians |

---

## 10. Pending business and clinical sign-offs

| # | Item | Owner | State |
|---|---|---|---|
| S1 | Encounter templates and protocol prompts (8, DRAFT); crew aeromedical template aligned to the local CAA format | CMO | Approval workflow in the OH Workspace |
| S2 | **Fitness standards library** – sources, validity (incl. over-40), sign-off roles, limitation codes | CMO | 12 standards, version 1 |
| S3 | **Top-150 job-demand profiles**, validated with unit managers (28 seeded) | Nursing Head + unit managers | Bulk import ready |
| S4 | Restriction catalogue wording | Nursing Head | 16 restrictions |
| S5 | Certificate and other document template wording (no diagnosis) | DPO + CMO | Drafts |
| S6 | **Retention schedule** (proposed values: OH record 40 y, exposure 40 y, aeromedical 10 y, certificates 6 y, correspondence 6 y, photos 6 y, external reports 10 y) | DPO + CMO (legal) | Proposed, not approved |
| S7 | **Terminology licences** (SNOMED CT, ICD-10 / ICD-11, LOINC) and annual update runbook | CIO + CMO | Starter subset |
| S8 | **Drug class, allergy cross-reactivity and interaction tables** – pharmacist review | Pharmacist + CMO | 13 rules marked draft |
| S9 | **CAA report layouts** (pilot, cabin crew) and **limitation-code labels** | AME lead + CMO | Draft layout and labels |
| S10 | Local reporting category mappings for codes | CMO | ≈ 70 draft prefixes |
| S11 | **AI documentation provider and DPIA** (5-clinician pilot recommended in Solution Design §15) | DPO + CMO + Cyber GRC | Framework ready, no provider |
| S12 | **RTW training** of RTW coordinators and unit managers (E05-BL-019) | Change & Training | Not started (deferred item) |
| S13 | Duration-guideline source and licence (shared with E02-BL-014) | CMO | 8 illustrative rows |
| S14 | Clinical-text guard vocabulary in Arabic and French | DPO + Nursing Head | English list |

---

## 11. Upgrade steps

1. **Back up** the database (point-in-time recovery enabled) and note the current Flyway version (expected V5). Keep the
   previous application images available for the rollback window.
2. **Identity provider** – create `DPO`; add it to the `wellnessz-bff` and `wellnessz-mobile` role scope mappings
   (Keycloak) or app-role assignments (Entra); create partner users with `PARTNER_PROVIDER` and `org_scope =
   /PARTNERS/<CODE>`; create client-credentials clients with `INTEGRATION` for the laboratory, device and rostering
   gateways; assign roles to pilot users.
3. **Configuration** – set `WZ_DPO_EMAIL`, `WZ_EMPLOYEE_PORTAL_URL`, `WZ_MANAGER_WORKSPACE_URL`; keep
   `WZ_DIAGNOSIS_REQUIRED_ON_CLOSE=true`; optionally build a derived core image with a Unicode font and / or Tesseract
   and set `WZ_DOCUMENTS_FONT_PATH`, `wellnessz.documents.ocr.tesseract-path` and `…ocr.languages`. Make sure
   `WZ_FLYWAY_LOCATIONS` does **not** include `classpath:db/demo`.
4. **Migrate** – run the Flyway job with the migrator role; confirm V6, V7 and V8 applied and that schemas `docs`,
   `ohrecord` and `fitness` exist with the runtime-role grants (no DELETE on clinical tables).
5. **Deploy** core API, BFF, employee app, manager workspace and OH Workspace (Helm upgrade).
6. **Reference data** – review fitness standards, restriction catalogue and job demands (import the validated top-150
   profiles when ready); load `fitness.crew_profile` for the crew dashboard; import the licensed terminology releases
   when the licences are in place; the DPO reviews and approves the retention schedule.
7. **Smoke test** – run `tools/e2e/e2e-increment2.js` against the environment (or the equivalent manual checks): chart,
   encounter sign, CDS block, device audiogram, fitness decision → certificate → manager and employee, job matching, RTW
   plan, job offer, crew assessment → roster feed, break-glass → DPO review; plus the gateway smoke calls (LIS order queue,
   roster feed) with an `INTEGRATION` token.
8. **Business configuration and governance** – CMO approves the encounter templates to be used and the standards; DPO
   approves certificate wording and retention; pharmacist reviews the CDS tables; AME lead confirms CAA layouts.
9. **Monitoring** – SIEM rules for `BREAK_GLASS`, `ROSTER_FEED_UNACKNOWLEDGED` and denied record reads; watch the outbox
   relay and the three scheduled jobs; confirm the DPO mailbox receives the break-glass alert.
