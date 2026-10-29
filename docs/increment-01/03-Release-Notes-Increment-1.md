# WellnessZ OHCM – Release Notes, Increment 1 (E01 Intake + E02 Triage)

| | |
|---|---|
| **Document** | Increment 1 – 03 Release Notes |
| **Release content** | E01 Case Intake & Incident-Triggered Case Initiation · E02 Clinical Triage, Risk Stratification & Case Assignment |
| **Audience** | CMO, Nursing Head, Cyber GRC, CIO, project sponsor, platform operations |
| **Database** | Flyway V3, V4, V5 (+ repeatable demo data for the `local` profile only) |
| **New application** | `web/oh-workspace` – OH clinical workspace |
| **Companion documents** | 01 – Solution Design E01/E02 · 02 – Requirements Traceability Matrix · API contract `API-Contract-E01-E02.md` |

---

## 1. Highlights

* One front door for Occupational Health: EHS incidents, self / manager / HR referrals, clinic walk-ins and first-aid
  emergencies, and system triggers all open numbered cases (`CAT-2026-000042`) linked to the HRIS record.
* Configurable incident rules decide what becomes a case automatically, what a nurse reviews, and what is ignored – and
  HSE can see the fate of every incident.
* 13 nurse-triage protocols with red-flag hard stops, SLA clocks with amber and breach alerts, automatic routing of
  safety-critical roles to physician and AME queues, rule-based assignment with caseload caps and leave cover.
* Status-only by design: managers, HR, HSE, crew scheduling and the catering hygiene manager never receive diagnosis or
  clinical notes – not on screen, not in notifications, not in outbound feeds.

Requirement coverage: 74 workbook items traced – 52 implemented, 22 partially implemented (7 of them gated mainly by
business sign-off), none deferred entirely. See document 02.

---

## 2. What is new, by persona

### OH nurse / nurse case manager (`OH_CLINICIAN`)

* **OH Workspace** with work queues *My cases, Team, Unassigned, Overdue, Awaiting external, Awaiting employee,
  Escalated* – counts, filters, saved personal views, SLA symbols (on track ●, at risk ▲, breached ■), bulk *send
  reminder* and *close duplicates*.
* **Case view** with a header showing urgency, SLA clock, safety-critical badge, owner, queue, complexity score, open
  tasks and the next action; tabs for overview, triage, recordability, checklists, assignment, escalation,
  complexity, duplicates, tasks, timeline, fitness and status.
* **Triage** with branching protocols (in person, telephone with consent and call-back task, digital); computed urgency
  and disposition; override with reason; red flags cannot be downgraded.
* **Recordability wizard** – mandatory before injury, illness or exposure cases leave Intake; changes need a reason.
* **Incident suggestions** to accept or dismiss with a reason; **duplicate** candidates with merge or link;
  **escalation** to physician, CMO, external specialist or emergency services; **shift handover** with acknowledgement;
  **advice release** to the employee portal; employee **timeline**.
* **Walk-in conversion** – one click turns a clinic or first-aid encounter into a case.

### OH physician (`OH_PHYSICIAN`) and aeromedical examiner (`AME`)

* Dedicated queues: *Food safety – physician*, *OH physician*, *Aeromedical (AME)*. Safety-critical cases arrive with
  urgency at least semi-urgent.
* Acknowledge and decide escalations within a response SLA; record fitness decisions; complete food-handler, driving and
  working-at-height checklists – safety-critical cases cannot close without them.

### Nursing Head (`NURSING_HEAD`)

* **Caseload board** with drag-and-drop (and keyboard "Move to…") re-assignment, caps, utilisation, urgent counts and
  out-of-office / cover settings; bulk re-assignment.
* Configuration screens for **assignment rules** (with test mode), **SLA policies**, **intake rules** (with dry run).
* **Intake dashboard** (cases by source, open / overdue by unit, SLA compliance, backlog, trend) and **triage quality
  audit** (random 10 % sample, reviewer agreement, no self-review).
* SLA breach alerts by e-mail.

### Chief Medical Officer (`CMO`)

* **Protocol approval** – the 13 protocols are delivered as DRAFT; approving a version retires the previous one.
* CMO escalations, case conferences with role-limited minutes, complexity calibration report, recordability report.

### Psychological health clinician (`PSYCH_CLINICIAN`)

* Psychological and drug-and-alcohol cases are routed to the restricted *Psychological health* queue and are invisible to
  other roles' queues and timelines.

### HSE officer (`HSE_OFFICER`)

* **Reconciliation** of every incident with its outcome (auto-created, suggested, accepted, dismissed, ignored, no rule
  matched, pending), CSV export, exception report, multi-casualty view (status only), recordability report, read access
  to the intake rules. Content-free notification when an incident produces a case.

### Clinic reception (`CLINIC_RECEPTION`) and first aider (`FIRST_AIDER`)

* *Walk-in & first aid* screen: badge scan (keyboard-wedge scanners), walk-in or 5-field emergency mode, safe retry
  after a network drop. Clinical details are never shown back to these roles.

### Employee and crew member (`EMPLOYEE`)

* New **self-referral** with department templates (catering, ramp & cargo, duty free & distribution, engineering,
  corporate, crew), body map, symptom picker, photo upload, consent and acknowledgement with case number – in English,
  Arabic (right-to-left) or French.
* **Crew decrease-in-fitness declaration** – crew scheduling only ever sees "unfit – pending".
* **My advice** – self-care advice released by the OH team.

### Line manager (`MANAGER`) and HR business partner (`HR`)

* **Manager referral** limited to work-impact facts (duties affected, start date, safety concern, adjustments tried,
  questions for OH); wording that looks like a diagnosis or medical opinion is rejected with the field highlighted.
* **HR referral** for long-term absence with policy stage and advice questions (same guard).
* Referrals only for employees inside the referrer's organisational scope; team view remains status-only.

### Crew scheduling (`CREW_SCHEDULING`) and catering hygiene manager (`HYGIENE_MANAGER`)

* Status-only APIs `GET /api/v1/status/crew` and `GET /api/v1/status/food-handlers`: FIT, UNFIT,
  FIT_WITH_LIMITATIONS or PENDING only; every read audited. Outbound status feeds `crew-rostering` and
  `catering-hygiene` (field allow-list). No dedicated screen in this increment.

### Integration (`INTEGRATION`) and administrators (`OH_ADMIN`, `PLATFORM_ADMIN`)

* **EHS webhook** `POST /api/v1/intake/ehs/incidents` – `Idempotency-Key` required, per-item schema validation,
  dead-letter queue with alert; DLQ console (list, replay, discard) in the OH Workspace.
* Trigger APIs: absence episodes, surveillance results, drug-and-alcohol results.
* Notification matrix and recordability mapping maintenance.

---

## 3. Database migrations

| Migration | Schema(s) | Content |
|---|---|---|
| `V3__increment1_kernel_masterdata.sql` | masterdata, casemgmt | Job-code safety category (+ forklift, line maintenance, office job codes; corporate, duty free, distribution, OH org units); HRIS **employee** master; OH **staff directory**; case aggregate extensions (identity, routing, triage, recordability, encrypted intake details); queue indexes; `case_sequence` (UNIT-YYYY numbering), `case_link`, append-only `case_event`, `case_task`, encrypted `case_attachment` |
| `V4__e01_intake.sql` | intake, masterdata | EHS incident extensions and multi-casualty uniqueness; `incident_dlq`; `intake_rule` (14 seeded rules); `incident_evaluation`; `encounter`; `absence_episode`; `trigger_event`; append-only `recordability_assessment`; `recordability_mapping` (placeholder); `notification_rule` (10 seeded rules); configuration entries (E02 enablement, absence thresholds, complexity threshold) |
| `V5__e02_triage.sql` | triage (new) | 6 work queues; SLA policies (15 / 60 / 240 / 480 / 960 min, amber 75 %); SLA timers; 4 assignment rules; assignment history; protocols and assessments; escalations; conferences; risk flags; complexity history; 8 illustrative duration guidelines; checklists; handovers; saved views; QA samples; advice log; least-privilege grants |
| `db/demo/R__demo_reference_people.sql` (repeatable) | masterdata | Demo employees E1001–E1005 and manager M2001, eight demo OH staff, demo distribution lists for crew scheduling and hygiene. **Loaded only with the `local` profile** (`spring.flyway.locations: classpath:db/migration,classpath:db/demo`); never enable this location in shared environments – real people come from the HRIS sync and the staff directory. |

Migrations are forward-only and additive except for one constraint change: `intake.ehs_incident` replaces the unique
constraint on `(source_system, external_ref)` with a unique index on `(source_system, external_ref, employee_id)` to
allow one row per injured person. Existing `oh_case` rows receive a `case_type` derived from their category.

Protocols are not seeded by migration: on application start `ProtocolCatalog` inserts any protocol code from
`classpath:triage/protocols/*.json` that does not yet exist, always as DRAFT, and never overwrites existing versions.

---

## 4. Configuration changes

| Item | Change |
|---|---|
| `spring.flyway.schemas` | Adds `triage` |
| `wellnessz.triage.sla.poll-interval` (`WZ_SLA_POLL_INTERVAL`) | New, default `PT60S` |
| `wellnessz.notification.oh-workspace-url` (`WZ_OH_WORKSPACE_URL`) | New – link placed in OH notifications |
| `wellnessz.notification.oh-contact` (`WZ_OH_CONTACT`) | New – contact line in stakeholder notifications |
| `wellnessz.ehs.source-system` (`WZ_EHS_SOURCE_SYSTEM`) | Default source system for webhook items |
| `wellnessz.integration.outbound-allowlist.catering-hygiene` | New destination (status fields only) |
| `WZ_CORS_ORIGINS` | Add the OH Workspace origin (`http://localhost:5175` locally) |
| Config table `masterdata.config_entry` | `modules.e02.enabled`, `intake.absence.default.consecutive-days` (3), `intake.absence.default.episodes-in-90-days` (3), `intake.absence.CAT.consecutive-days` (2), `triage.complexity.intensive-threshold` (60); optional `triage.complexity.weights` (JSON) |
| Config table – **must be set per environment** | `notifications.crew-scheduling.email`, `notifications.hygiene-manager.email` (only the demo script sets them) |
| Helm | New top-level key **`ohWorkspace`** (image `oh-workspace`, 2–4 replicas, PDB, probes `/healthz`) and ingress host **`ingress.hosts.ohWorkspace`** (e.g. `oh.wellnessz.example.com`; local `oh.localtest.me`); added to `values.schema.json` required keys |
| Local dev | OH Workspace dev server on port **5175** (employee app 5173, manager workspace 5174); Docker Compose CORS updated |

---

## 5. Identity provider changes (Keycloak / Microsoft Entra ID)

Create the following application roles (or Entra app roles / groups mapped to these names):

`OH_PHYSICIAN`, `AME`, `CMO`, `NURSING_HEAD`, `HSE_OFFICER`, `CREW_SCHEDULING`, `HYGIENE_MANAGER`, `CLINIC_RECEPTION`,
`FIRST_AIDER`.

> **Important.** Add every new role to the **role scope mappings of the BFF client (`wellnessz-bff`) and the mobile client
> (`wellnessz-mobile`)**. Keycloak only places a realm role in a client's access token when that role is in the client's
> scope; without the mapping, users holding the role are silently treated as having no access. This was a real defect
> found during end-to-end verification and is fixed in `deploy/local/keycloak/wellnessz-realm.json`. In Entra ID, assign
> the app roles on the API app registration and make sure the BFF's token request includes them (or that the group
> object-ID mapping covers the new groups).

Also register the OH Workspace in the BFF client: redirect URI `https://oh.<domain>/login/oauth2/code/wellnessz`,
web origin `https://oh.<domain>`, post-logout redirect `https://oh.<domain>/*` (locally `http://localhost:5175`).

---

## 6. Verification results

| Check | Result |
|---|---|
| Java build and tests (`./mvnw verify`) | **65 tests pass**, including the nine Increment-1 acceptance tests (`Increment1IntegrationTest`) on PostgreSQL 16, the queue performance test, ArchUnit boundaries, `RuleEngineTest`, `RecordabilityClassifierTest`, `ProtocolEvaluatorTest`, `ClinicalTextDetectorTest` |
| SAST (SpotBugs + FindSecBugs) | **0 findings**; 5 justified exclusions in `config/spotbugs-exclude.xml` (entity / record representation exposure, constructor fail-fast, stateless resource-server CSRF, validated BFF logout redirect, constant-fragment queue SQL builder) |
| Web unit tests (vitest) | oh-workspace **151**, employee-app **81**, manager-workspace **55** – all passing (lint and production build clean) |
| End-to-end (`tools/e2e/e2e-increment1.js`) | **18 / 18 PASS** against a real Keycloak, BFF, core API and the three web apps; 33 screenshots in `docs/images/screens/increment-01/` |
| Queue performance | 5,000 open cases: TEAM page + UNASSIGNED page + counts = **0.548 s** (target < 2 s) |

---

## 7. Defects found and fixed during verification

| # | Defect | Found during | Fix |
|---|---|---|---|
| D1 | New roles missing from the BFF and mobile clients' role scope mappings – users with e.g. `NURSING_HEAD` or `CLINIC_RECEPTION` received tokens without those roles and were denied | E2E | Roles added to both clients' scope mappings in the Keycloak realm; deployment step added (Section 5) |
| D2 | Intake dashboard "cases by source" rendered empty: API field was named `key`, the UI expected `bySource[].source` | E2E run | Response record renamed to `source` (`ReconciliationService.CountItem`) |
| D3 | Recordability history showed no assessor or date: UI read `decidedBy` / `decidedAt`, API returns `assessedBy` / `assessedAt` | E2E run | OH Workspace types and Recordability tab aligned with the API |
| D4 | Self-referral acknowledgement repeated the case reference (server text and local text both shown) | E2E run | Employee app shows the server acknowledgement when it already names the case reference, otherwise the local text |
| D5 | Audit hash-chain verification reported a false chain break: the hash was computed over a nanosecond timestamp, PostgreSQL stores microseconds | Verification test run | Audit timestamps truncated to milliseconds before hashing and storing |
| D6 | Queue SQL failed: the dynamic WHERE fragment and the `ORDER BY` clause were concatenated without a separator | Verification test run | Explicit line break before `ORDER BY`; builder appends only constant fragments with bound parameters (SpotBugs exclusion documented) |

---

## 8. Known limitations

| Area | Limitation | Planned handling |
|---|---|---|
| Clinical content | 13 triage protocols are DRAFT; BODY_FLUID and CREW_FATIGUE have no red-flag question | CMO review and approval before clinical use |
| Regulatory | Recordability local-law / CAA mapping is a placeholder | CMO + HSE Head sign-off (E01-BL-022) |
| Templates / notifications | Intake templates and notification matrix await Nursing Head, DPO and CMO sign-off | Business sign-off |
| EHS transport | Webhook, mailbox and Excel only; no message-queue or SFTP transport; 99.9 % delivery not load-tested | E15 integration increment |
| Enterprise feeds | HRIS, absence, surveillance and D&A adapters not connected (APIs ready) | E15 |
| Staff directory | `masterdata.staff_member` and `masterdata.employee` are populated by demo data only; in shared environments routing leaves cases unassigned until the directory and HRIS sync are loaded | Load before go-live (upgrade step 5) |
| Channels | E-mail only; SMS and push are logging adapters; no Teams; no per-user channel opt-in | Later increment |
| SLA | Elapsed minutes – no business calendar or holidays ("1 working day" = 480 min) | E18 |
| Walk-in | No native NFC; offline retry is in-memory only | Mobile / device increment |
| Reporting | Exception report on demand only; intake dashboard has no export and no department / source filter | Later increment (E17) |
| Employee timeline | No filters; 10-year history performance not tested | Later increment |
| Duration guidelines | 8 illustrative in-house rows; licensed content not loaded | Licence decision (E02-BL-014) |
| Telephone triage | Not offline-capable | Later increment |
| Status-only consumers | Crew scheduling and hygiene manager have APIs but no screens | Later increment |
| Test coverage | HR referral server path, surveillance / D&A triggers, conferences, QA sampling, handover and advice have no automated server tests | Next increment |

---

## 9. Upgrade steps

1. **Back up** the database (point-in-time recovery enabled) and note the current Flyway version (expected V2).
2. **Identity provider** – create the nine new roles; add them to the `wellnessz-bff` and `wellnessz-mobile` role scope
   mappings (Keycloak) or app-role assignments (Entra); register the OH Workspace redirect URI, web origin and
   post-logout URI; assign roles to the pilot users.
3. **Configuration** – set `WZ_OH_WORKSPACE_URL`, `WZ_OH_CONTACT`, optionally `WZ_SLA_POLL_INTERVAL`; add the OH
   Workspace origin to `WZ_CORS_ORIGINS`; add `ohWorkspace` image and `ingress.hosts.ohWorkspace` to the Helm values;
   create DNS and TLS for `oh.<domain>`. Make sure `WZ_FLYWAY_LOCATIONS` is **not** set to include `classpath:db/demo`.
4. **Migrate** – run the Flyway pre-deploy job (or app-start migration where enabled) with the migrator role; confirm V3,
   V4 and V5 applied and that schema `triage` exists with the runtime-role grants.
5. **Reference data** – load `masterdata.employee` from HRIS (including badge IDs, manager and cost centre) and
   `masterdata.staff_member` (OH staff roles, sites, languages, specialties, caseload caps); review job-code safety
   categories; set `notifications.crew-scheduling.email` and `notifications.hygiene-manager.email`; review work-queue
   e-mail addresses.
6. **Deploy** core API, BFF, employee app, manager workspace and the new OH Workspace (Helm upgrade).
7. **Smoke test** – run `tools/e2e/e2e-increment1.js` against the environment (or the equivalent manual checks): webhook
   with `Idempotency-Key`, self-referral, manager referral guard, queue counts, triage, crew status API.
8. **Business configuration** – Nursing Head reviews intake rules, assignment rules, SLA policies; CMO approves the
   protocols to be used; OH admin replaces the recordability mapping once signed off.
9. **Monitoring** – add a SIEM / on-call rule on the log event `ALERT ehs-webhook dead-lettered` and the
   `EHS_WEBHOOK_DLQ` audit action; watch the SLA poller and outbox relay metrics.

Rollback: migrations are forward-only. V3 adds mandatory columns that the previous application version does not
populate (for example `casemgmt.oh_case.case_type NOT NULL`), and V4 replaces the incident unique constraint with the
multi-casualty index. Rolling back the application therefore requires restoring the database from the backup taken in
step 1; plan the upgrade window accordingly.
