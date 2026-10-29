# WellnessZ OHCM – Increment 1 Solution Design: E01 Case Intake and E02 Clinical Triage

| | |
|---|---|
| **Document** | Increment 1 – 01 Solution Design (E01 + E02) |
| **System** | WellnessZ Airline Occupational Health Case Management (OHCM) |
| **Scope** | E01 Case Intake & Incident-Triggered Case Initiation · E02 Clinical Triage, Risk Stratification & Case Assignment |
| **Audience** | Chief Medical Officer (CMO), Nursing Head, Cyber GRC, CIO, project sponsor |
| **Baseline** | Repository working tree, Flyway migrations V1–V5, API contract `docs/increment-01/API-Contract-E01-E02.md` |
| **Status** | Draft for review – clinical content and regulatory mappings await business sign-off (Section 25) |
| **Companion documents** | 02 – Requirements Traceability Matrix · 03 – Release Notes Increment 1 · Architecture 01–03 |

> **How to read this document.** Every statement about behaviour was checked against the source code. Where a
> capability is only partly delivered, or where content (protocols, mappings, templates) is a draft awaiting clinical
> or legal sign-off, this is stated explicitly. Requirement identifiers (E01-HLR-xx, E01-F0x, E01-BL-0xx, E02-…) refer
> to the business workbooks; the full mapping is in the Requirements Traceability Matrix (document 02).

---

## 1. Executive summary

Increment 1 adds the first two clinical business modules on top of the WellnessZ base framework:

* **E01 – Case intake** gives Occupational Health (OH) a single, auditable front door. Cases are opened from the group
  EHS incident system (webhook, mailbox, Excel), from employee self-referral, manager and HR referral, from walk-in and
  emergency registration at clinics and first-aid posts, and from system triggers (absence, abnormal surveillance
  results, crew decrease-in-fitness declarations, positive or refused drug-and-alcohol tests, repeat body-part injuries).
  A configurable rule engine decides which incidents become cases; an HSE reconciliation view shows the fate of every
  incident. Every case is numbered `UNIT-YYYY-NNNNNN`, linked to the HRIS employee record, checked for duplicates and
  classified for recordability before it can leave *Intake*.
* **E02 – Clinical triage and assignment** turns every new case into the right pathway with a named owner within an
  agreed SLA: 13 branching nurse-triage protocols with red-flag hard stops, automatic routing of safety-critical roles
  (pilots, cabin crew, food handlers, airside drivers …) to physician / AME queues, rule-based assignment with
  caseload caps and out-of-office cover, SLA timers with amber and breach alerts, escalation to physician / CMO,
  a complexity score, role checklists that block closure, triage quality audit, and status-only APIs for crew
  scheduling and the catering hygiene manager.
* A new **OH Workspace** web application (`web/oh-workspace`) is the clinical console for nurses, physicians, AMEs,
  the Nursing Head and the CMO. The employee app gains the new referral form, crew fitness declaration and advice
  inbox; the manager workspace gains manager and HR referral forms.

The **status-only rule** of the workbooks – managers, HR, crew scheduling, hygiene and HSE see fitness or case status
only, never diagnosis or clinical notes – is enforced server-side in every new API, in notifications and on every
outbound feed.

Verification: 65 Java tests (including nine increment-1 acceptance tests on PostgreSQL 16 and a 5,000-open-case queue
performance test), 287 web unit tests across three apps, and 18/18 end-to-end checks against a real Keycloak, BFF,
core API and the three web apps. Coverage against the workbooks: 74 requirement items traced, 52 implemented and 22
partially implemented (mostly awaiting clinical / legal sign-off or later-increment integrations); none deferred
entirely. See document 02.

---

## 2. Scope and goals

### 2.1 Business goals (from the epic overviews)

| Epic | Goal | KPI | How the design supports it |
|---|---|---|---|
| E01 | Open every OH case from a controlled set of entry points – above all the EHS incident feed | ≥ 95 % work-related incidents with an OH case within 24 h | Near-real-time webhook; AUTO_CREATE rules; reconciliation and exception report show incidents that did not produce a case |
| E01 | | Median incident-to-case latency < 2 h in-hours | Webhook → outbox relay (2 s poll) → rule engine → case in seconds |
| E01 | | Duplicate case rate < 1 % | Source-level de-duplication of incidents, idempotent triggers, probable-duplicate detection with merge/link |
| E01 | | ≥ 98 % cases with complete mandatory intake data | HRIS identity resolved at creation; manual identity flagged `HRIS_REVIEW` |
| E02 | Right pathway, right owner, within SLA | ≥ 95 % triaged within SLA (urgent ≤ 1 h, routine ≤ 1 working day) | SLA policy table seeded with those targets; amber / breach alerts; dashboard SLA compliance |
| E02 | | Caseload within band | Caseload caps used by the assignment engine; caseload board |
| E02 | | Escalation accuracy ≥ 90 % | Escalation decision log; triage QA sampling with agreement rate |
| E02 | | Safety-critical cases flagged correctly 100 % | Flag derived from the job-code catalogue at case creation; routing and closure guard are not optional |

### 2.2 In scope

E01 features F01–F08 and E02 features F01–F07, i.e. all 26 E01 and 19 E02 backlog items, at the depth recorded in the
traceability matrix.

### 2.3 Out of scope (per workbooks and increment plan)

* Incident investigation and root-cause analysis (stays in the EHS system); insurance claim creation (E08).
* Full clinical encounter documentation (E03), treatment and referral (E04), fitness-for-duty certification workflow
  (E05 – Increment 1 records a fitness status and checklist only).
* Production connectors for HRIS, absence, surveillance, D&A and rostering transport (E15) – Increment 1 exposes
  the inbound APIs and outbound status events; the enterprise-side adapters are configuration of later increments.
* Business calendar for "working day" SLAs (E18) – currently elapsed minutes (1 working day = 480 minutes).

---

## 3. Actors and roles

Roles are canonical application roles (`com.wellnessz.common.model.Roles`). Identity providers (Keycloak, Microsoft
Entra ID, Okta) map their roles or groups to these names. Nine roles are **new in Increment 1**.

| Role | New? | Persona | Disclosure | Main capabilities in Increment 1 |
|---|---|---|---|---|
| `OH_CLINICIAN` | existing | OH nurse / nurse case manager | CLINICAL | Queues, triage, recordability, assignment to self, escalation, merge, encounter conversion, fitness status |
| `CASE_MANAGER` | existing | Case manager | CLINICAL | As OH team, excluding triage and recordability classification |
| `PSYCH_CLINICIAN` | existing | Psychological health clinician | RESTRICTED_CLINICAL | Only role that sees restricted-class cases (psychological, substance) and the `MENTAL_HEALTH` queue |
| `OH_PHYSICIAN` | **new** | OH physician | CLINICAL | Physician and food-safety queues, escalation decisions, checklists, fitness decisions |
| `AME` | **new** | Aeromedical examiner | CLINICAL | Aeromedical queue (flight crew, cabin crew, ATC), escalation decisions, fitness decisions |
| `CMO` | **new** | Chief Medical Officer | CLINICAL | Approves triage protocols, CMO escalations, case conferences, calibration report |
| `NURSING_HEAD` | **new** | Nursing Head | CLINICAL | Intake and assignment rules, SLA policies, bulk re-assignment, caseload board, intake dashboard, triage QA sampling |
| `HSE_OFFICER` | **new** | HSE / safety officer | STATUS_ONLY (incident-linked) | Incident reconciliation, export, exception report, incident → cases (status only), recordability report |
| `CREW_SCHEDULING` | **new** | Crew scheduling | STATUS_ONLY (crew) | `GET /status/crew` – fit / unfit / fit-with-limitations / pending only |
| `HYGIENE_MANAGER` | **new** | Catering hygiene manager | STATUS_ONLY (food handlers) | `GET /status/food-handlers` – exclusion status only |
| `CLINIC_RECEPTION` | **new** | Clinic reception | write encounters, no clinical read-back | Badge lookup, walk-in registration |
| `FIRST_AIDER` | **new** | First aider at an airside post | write encounters, no clinical read-back | Emergency mode registration, own encounter enrichment |
| `EMPLOYEE` | existing | Employee / crew member | SELF | Self-referral, attachments, crew fitness declaration, advice inbox |
| `MANAGER`, `HR` | existing | Line manager, HR business partner | STATUS_ONLY within org scope | Manager / HR referral forms limited to work-impact facts; employee look-up within own org scope |
| `OH_ADMIN`, `PLATFORM_ADMIN` | existing | Administrators | – | DLQ console, intake rules, notification matrix, recordability mapping |
| `INTEGRATION` | existing | Machine client | – | EHS webhook, absence, surveillance and D&A trigger APIs |
| `AUDITOR` | existing | Auditor | – | Audit trail (base framework) |

"OH team" in this document means `OH_CLINICIAN, OH_PHYSICIAN, AME, CMO, NURSING_HEAD, CASE_MANAGER, PSYCH_CLINICIAN`.

> **Deployment note.** New roles must exist in the identity provider **and** be included in the role scope mappings of
> the BFF and mobile clients; otherwise they never reach the access token. This was a real defect found during
> end-to-end verification (see Release Notes, Section 7).

---

## 4. Component view

### 4.1 Modules and boundaries

```mermaid
flowchart LR
    subgraph Web["Browser / device"]
        EA["employee-app<br/>(web, PWA, iOS/Android)"]
        MW["manager-workspace"]
        OHW["oh-workspace (new)"]
    end
    BFF["wz-bff<br/>OIDC + session cookie"]
    subgraph Core["wz-core-app (modular monolith)"]
        direction TB
        E02["modules/wz-e02-triage<br/>com.wellnessz.e02"]
        E01["modules/wz-e01-intake<br/>com.wellnessz.e01"]
        K["platform/wz-case-kernel<br/>com.wellnessz.casekernel"]
        EHS["platform/wz-ehs-intake<br/>com.wellnessz.ehsintake"]
        P["platform services: security (ABAC, audit),<br/>integration (outbox, idempotency), notification,<br/>masterdata, analytics, secrets, common"]
    end
    IdP["Keycloak / Entra ID"]
    EHSsys["Group EHS system"]
    HRIS["HRIS / absence / surveillance / D&A"]
    Ros["Crew rostering / HR / catering hygiene"]
    DB[("PostgreSQL 16<br/>schemas platform, masterdata,<br/>intake, casemgmt, triage, analytics")]

    EA & MW & OHW --> BFF --> Core
    BFF --- IdP
    EHSsys -->|"webhook / mailbox / Excel"| EHS
    HRIS -->|"INTEGRATION APIs"| E01
    E02 --> E01
    E02 --> K
    E01 --> K
    EHS -.->|"ehs.incident.received.v1"| E01
    K -.->|"fitness.status.changed.v1<br/>(allow-listed)"| Ros
    Core --> DB
```

| Component | Responsibility |
|---|---|
| `platform/wz-ehs-intake` | EHS incident ingestion on every channel (webhook – new; mailbox and Excel – base), schema validation, dead-letter queue, de-duplication, `ehs.incident.received.v1` event |
| `platform/wz-case-kernel` | Case aggregate `OhCase`, case numbering (`CaseRefGenerator`), HRIS identity resolution, ABAC projections, status transitions with module guards (`CaseTransitionGuard` SPI), timeline, tasks, encrypted attachments, links |
| `platform/wz-masterdata` | HRIS employee master (`masterdata.employee`), OH staff directory (`masterdata.staff_member` – roles, sites, languages, specialties, caseload cap, out-of-office, cover), job-code catalogue with safety category, configuration entries |
| `modules/wz-e01-intake` | Rule engine, reconciliation / suggestions / dashboard, referral templates and forms, walk-in encounters, system triggers, duplicate detection & merge, recordability, notification matrix, employee timeline |
| `modules/wz-e02-triage` | Protocols and triage, SLA timers, routing and assignment, queues and bulk actions, case header, safety checklists and status-only APIs, escalation and conferences, complexity score, triage QA |

### 4.2 Boundaries enforced by ArchUnit

`apps/wz-core-app/src/test/java/com/wellnessz/app/ArchitectureTest.java` fails the build when a boundary is crossed:

| Rule | Effect |
|---|---|
| `platformDoesNotDependOnBusinessModules` | Platform services never depend on the kernel, EHS intake, E01 or E02 |
| `kernelDoesNotDependOnBusinessModules` | Modules plug into the kernel through SPIs (`CaseModule`, `CaseTransitionGuard`, events), never the reverse |
| `intakeDoesNotDependOnTriage` | E02 depends on E01 (through the `RecipientResolver` SPI), never the reverse – no cycle |
| `intakeDoesNotCallCaseKernelDirectly` | EHS intake talks to case creation only through `ehs.incident.received.v1` |
| `commonHasNoSpringSecurity` | Common model stays framework-neutral |
| `noStandardStreams` | No `System.out` / `System.err` – structured logging only, so PHI is never printed |

Each module registers with the `ModuleRegistry` (`E01Module`, `E02Module`) and can be disabled by configuration
(`modules.e01.enabled`, `modules.e02.enabled`); when E02 is disabled, cases are still created but not routed.

### 4.3 Internal events

All cross-module communication uses the transactional outbox (at-least-once, idempotent consumers keyed per consumer).

| Event | Producer | Consumers |
|---|---|---|
| `ehs.incident.received.v1` | EHS intake | E01 rule engine (`IncidentIntakeListener`) |
| `case.opened.v1` | Kernel | E02 routing + SLA start (`AssignmentService`), E01 duplicate / repeat-body-part detection (`DuplicateService`), E01 notification matrix |
| `case.assigned.v1`, `case.triaged.v1`, `case.escalated.v1` | E02 | Notification matrix |
| `sla.at-risk.v1`, `sla.breached.v1` | E02 SLA poller | Notification matrix |
| `fitness.status.changed.v1` | Kernel | Outbound destinations `crew-rostering`, `hr-systems`, `catering-hygiene` (field allow-list) |

---

## 5. Intake channels (E01-F01 to F04)

### 5.1 EHS incident webhook (E01-BL-002)

`POST /api/v1/intake/ehs/incidents` – role `INTEGRATION` (client-credentials token).

| Control | Design |
|---|---|
| Transport idempotency | `Idempotency-Key` header **required** (8–200 chars, `[A-Za-z0-9._:-]`). First request executes; a retry with the same key and body receives the stored response with `Idempotent-Replayed: true`; the same key with a different body is rejected with 422. An advisory lock prevents two concurrent first requests from both executing. Keys are purged after 7 days. |
| Schema validation | Per item: `externalRef` and `occurredAt` (ISO-8601) mandatory; every optional field is type- and pattern-checked; `incidentClass` must be one of the 12 vocabulary values; `severity` ∈ LOW, MEDIUM, HIGH, CRITICAL; at most 500 incidents per call. |
| Dead-letter queue | Invalid items are stored (payload encrypted) in `intake.incident_dlq`; the call still returns `202 {accepted, duplicates, deadLettered, batchId}`. An `ALERT ehs-webhook dead-lettered=n` log event and a FAILURE audit record are emitted for SIEM / on-call rules. Operators (`OH_ADMIN`, `PLATFORM_ADMIN`) list, replay (re-validates) or discard via `/intake/dlq` – the payload is never returned by the API. |
| Business de-duplication | Unique per `(source_system, external_ref, employee_id)` – a re-sent incident is counted as a duplicate; a multi-casualty incident carries one row per injured person (E01-BL-020). |
| Hand-over | Persisted incidents emit `ehs.incident.received.v1` through the outbox. |

### 5.2 Mailbox and Excel (base framework, retained)

The base framework's mailbox reader (IMAP or Microsoft Graph, allowed-sender list) and the hardened `.xlsx` upload
(`POST /intake/ehs/excel`) feed the same ingestion path and therefore the same rule engine. Increment 1 added the
richer incident fields (class, body part, lost time, exposure type, unit, shift, reporter e-mail) to `intake.ehs_incident`.

### 5.3 Self, manager and HR referral (E01-F02)

| Aspect | Design |
|---|---|
| Templates | `TemplateCatalog`: six employee templates – `CATERING` (cuts / burns / food-handler symptoms), `RAMP_CARGO` (MSK / manual handling / heat), `DUTYFREE_DISTRIBUTION`, `ENGINEERING` (chemical / noise), `CORPORATE` (ergonomic / psychosocial), `CREW` (fatigue / wellbeing) – plus `MANAGER_WORK_IMPACT` and `HR_ABSENCE`. Each has typed fields, mandatory flags, maximum lengths, option lists, a department mapping and a declared routing queue. |
| Languages | Labels, options and symptoms in English, Arabic and French (`GET /languages`, `?lang=`; fallback English); the employee app renders right-to-left for Arabic. Free text is stored as entered; the language is recorded on the case. |
| Body map | `GET /intake/body-map` – front/back regions; up to 10 regions per referral; the primary region becomes the case body part (used by duplicate detection). |
| Validation | Server validates template, symptom codes, body-map codes, option values and lengths; unknown values are rejected. |
| Consent | Consent to OH assessment is mandatory (`400 consent_required`); optional purposes (e.g. sharing with a treating partner) are recorded in the consent register. |
| Attachments | `POST /cases/{id}/attachments` – JPEG, PNG or PDF verified by magic bytes, ≤ 5 MB, at most 5 per case, stored encrypted (`v1:` key-versioned ciphertext); downloads are audited. |
| Acknowledgement | `201 {caseId, caseRef, acknowledgement}` – the text names the case reference. |
| Manager / HR | Employee must be inside the referrer's organisational scope (otherwise 404, audited as DENIED). Manager fields are limited to duties affected, start date, safety concern, adjustments tried, questions for OH; a safety concern raises urgency to SEMI_URGENT. HR captures absence summary, policy stage (INFORMAL, STAGE_1, STAGE_2, FINAL), absence days and advice questions. |
| Clinical-text guard | Every manager/HR free-text field passes `ClinicalTextDetector` (diagnosis-type terms such as "depression", "diagnosis", "fracture", "medication", ICD-10-style codes). A hit returns `422 clinical_content_not_allowed` with the offending field so the UI can highlight it. The detector deliberately errs on the side of rejecting. |

### 5.4 Walk-in and emergency encounters (E01-F03)

* **Badge lookup** – `GET /intake/employees/by-badge/{badgeId}` against the HRIS cache (`masterdata.employee.badge_id`);
  the Reception screen supports keyboard-wedge badge scanners (scan + Enter triggers the look-up).
* **Registration** – `POST /intake/encounters` with a client-generated `clientRef` (UUID). A retry with the same
  `clientRef` returns the existing encounter, so the browser's in-memory retry queue can replay safely after a network
  drop. Pending items are **never** written to browser storage (PHI); they are lost if the tab closes, and the UI warns.
* **Emergency mode** – five fields (employee, time, location, chief complaint, action taken); `enrichmentDue=true`
  until enriched (`PATCH /intake/encounters/{id}`) by the registering user or a clinician.
* **No clinical read-back** – reception and first aiders receive encounter views without chief complaint or action taken.
* **Conversion** – `POST /intake/encounters/{id}/convert` (OH team only): one click; case type defaults from the chief
  complaint (exposure, injury, mental health, illness); emergency encounters open as URGENT; an `ENCOUNTER` link keeps
  the encounter attached to the case.

### 5.5 System triggers (E01-F04)

| Trigger | API (role) | Rule | Result |
|---|---|---|---|
| Absence | `POST /intake/absence-episodes` (INTEGRATION), idempotent on `sourceRef` | Episode longer than `intake.absence.<SUB>.consecutive-days` (default 3, catering 2) **or** ≥ `…episodes-in-90-days` (default 3) episodes in 90 days | One open early-intervention `ABSENCE` watch case per employee with the absence history; later episodes are linked |
| Surveillance | `POST /intake/triggers/surveillance-result` (INTEGRATION) | `abnormal=true` as flagged by the source system | `SURVEILLANCE_FOLLOW_UP` case, routed to the physician queue by assignment rule |
| Crew fitness declaration | `POST /intake/triggers/fitness-declaration` (EMPLOYEE) | Reason ILLNESS, INJURY, MEDICATION, FATIGUE, PREGNANCY or OTHER | `FITNESS` case, URGENT, fitness status **UNFIT_PENDING** published to `crew-rostering` as status only; crew cases route to the AME queue |
| D&A test | `POST /intake/triggers/da-test` (INTEGRATION) | POSITIVE or REFUSED | `SUBSTANCE` case (restricted class → `MENTAL_HEALTH` queue, psychological-health clinicians only), fitness UNDER_REVIEW; the trigger log stores a generic code, not the result |
| Repeat body part | automatic on `case.opened.v1` | ≥ 3 cases for the same employee and body part in 365 days | `REPEAT_BODY_PART` badge, timeline entry and complexity driver |

Every trigger is logged in `intake.trigger_event` with its outcome (CASE_CREATED, CASE_EXISTS, BELOW_THRESHOLD,
NOT_ABNORMAL) and is visible to the OH team (`GET /intake/triggers`) and on the employee timeline.

---

## 6. Intake rule engine (E01-F01, E01-BL-003)

`IntakeRule` rows (`intake.intake_rule`) carry optional conditions – incident class, minimum severity, lost time,
injury flag, exposure type, unit, body part – where **null means wildcard**. Active rules are evaluated in ascending
`priority`; the **first match wins**. Each rule has an action and, for case-creating actions, a case type and urgency.

| Action | Effect |
|---|---|
| `AUTO_CREATE` | Case opened immediately, pre-filled with incident reference, employee, unit, location, shift, body part and event date. If the incident has no employee ID, the outcome is downgraded to SUGGEST – a nurse must identify the employee first. |
| `SUGGEST` | Incident parked in the nurse review list; accept (optionally supplying the employee ID) creates the case; dismiss requires a reason code (NOT_WORK_RELATED, NO_HEALTH_CONSEQUENCE, DUPLICATE, INSUFFICIENT_DATA, OTHER) and is visible in reconciliation with who decided. |
| `IGNORE` | Logged, no case. |
| `NO_MATCH` | (no rule matched) Logged, visible in reconciliation and in the exception report. |

Seeded rules (V4, editable by `OH_ADMIN` / `NURSING_HEAD`, optimistic-locking `version`):

| Prio | Rule | Action | Case type / urgency |
|---|---|---|---|
| 5 | Any lost-time incident | AUTO_CREATE | INJURY / URGENT |
| 10 | Lost-time injury | AUTO_CREATE | INJURY / URGENT |
| 20 | Injury – medical treatment | AUTO_CREATE | INJURY / SEMI_URGENT |
| 30 | Injury – first aid plus | AUTO_CREATE | INJURY / ROUTINE |
| 40–42 | Body-fluid exposure · Fume event · Heat illness | AUTO_CREATE | EXPOSURE, EXPOSURE, ILLNESS / URGENT |
| 50 | Occupational illness | AUTO_CREATE | ILLNESS / ROUTINE |
| 60 | Other exposure – nurse review | SUGGEST | EXPOSURE / ROUTINE |
| 70 | First aid only – nurse review | SUGGEST | INJURY / ADVICE |
| 80–81 | Near miss · Property damage | IGNORE | – |
| 90, 95 | Legacy feed: injury flag · medium+ severity | AUTO_CREATE · SUGGEST | for mailbox / Excel rows without an incident class |

`POST /intake/rules/test` performs a dry run for a sample incident. Every evaluation is stored in
`intake.incident_evaluation` (rule, outcome, case, reporter e-mail, decision) – the "rule hit log".

---

## 7. Reconciliation, suggestions and intake dashboard (E01-BL-004, -005, -020, -026)

| Function | Endpoint / screen | Notes |
|---|---|---|
| Incident ↔ case reconciliation | `GET /intake/reconciliation?from&to&outcome&unit` · *Reconciliation* | One row per incident with outcome (AUTO_CREATED, SUGGESTED, ACCEPTED, DISMISSED, IGNORED, NO_MATCH, PENDING), rule, case reference and status, dismiss reason, decider. No clinical text. Range ≤ 400 days, default 30. |
| CSV export | `GET /intake/reconciliation/export` | `Content-Disposition: attachment`, `Cache-Control: no-store`, cells beginning with `= + - @` are neutralised against spreadsheet formula injection; export is audited. |
| Exception report | `GET /intake/reconciliation/exceptions` | Default last 7 days: ignored / no-match / dismissed / pending incidents with injury, illness, exposure or lost time. On demand (no scheduled e-mail yet). |
| Suggestions | `GET /intake/suggestions`, accept / dismiss · *Incident suggestions* | |
| Multi-casualty view | `GET /intake/incidents/{ref}/cases` · *Incident → cases* | Status-only projection of every case linked to one incident; read is audited. |
| Intake dashboard | `GET /intake/dashboard?from&to&subsidiary` · *Intake dashboard* (Nursing Head, OH admin, CMO) | Cases by source (small cells < `wellnessz.analytics.min-group-size` suppressed), open and overdue by unit, SLA compliance (met / breached / %), backlog (unassigned, overdue, awaiting triage), daily trend. |

---

## 8. Case identity, numbering, linking and timeline (E01-F05)

* **Numbering** – `CaseRefGenerator` issues `{UNIT}-{YYYY}-{NNNNNN}` where UNIT is the employee's subsidiary code
  (fallback `OH`), using an atomic `INSERT … ON CONFLICT DO UPDATE … RETURNING` on `casemgmt.case_sequence (unit, year)`.
  The counter is gap-tolerant (a rolled-back transaction may leave a gap) and race-free across replicas.
* **HRIS identity (E01-BL-017)** – at creation the kernel copies display name, subsidiary, department and org path,
  cost centre, job code, location, shift pattern, preferred language and safety category from `masterdata.employee` and
  the job-code catalogue. If the employee is unknown to HRIS, the case is created with the supplied identity and
  `hrisOverride=true`, shown as an `HRIS_REVIEW` badge.
* **Links** – `casemgmt.case_link` types INCIDENT, CASE, CLAIM, SURVEILLANCE, ENCOUNTER, ABSENCE and MERGED_FROM; an
  INCIDENT link is created automatically for incident-sourced cases.
* **Case timeline** – `casemgmt.case_event` is append-only (the runtime role has no UPDATE/DELETE grant); summaries
  never contain clinical text (e.g. "Triaged URGENT via MSK v1 (TELEPHONE)").
* **Employee timeline (E01-BL-018)** – `GET /employees/{id}/timeline` merges cases (only those the caller may see at
  clinical level), encounters, absences, triggers and incidents in reverse chronological order; the read is audited.

---

## 9. Duplicate detection and merge (E01-F06)

On every `case.opened.v1` the `DuplicateService` compares the new case with the employee's open cases created in the
previous 30 days:

| Signal | Points |
|---|---|
| Same employee | 30 (base) |
| Event date within ±2 days | +30 |
| Same body part | +25 |
| Same incident reference | +40 |

A candidate is reported when the dates are within ±2 days and the score is ≥ 55 (score capped at 100). The new case is
flagged `duplicateSuspect` with a timeline entry. Clinicians can then:

* **Merge** (`POST /cases/{id}/merge {intoCaseId, reason}`) – same employee only, target must be open; the source case
  becomes `MERGED`, its links and attachments move to the target, a `MERGED_FROM` link and clinical summary note are
  added, both timelines record the merge and the action is audited. Both source records are therefore retained.
* **Link** instead of merge (`POST /cases/{id}/links`) – e.g. several injured people from one incident.
* **Bulk close duplicates** from the queue (only for cases flagged as probable duplicates).

---

## 10. Recordability classifier and guard (E01-F07)

* **Wizard** – `GET /intake/recordability/wizard` returns six questions (fatality, days away from work, restricted work
  or transfer, medical treatment beyond first aid, loss of consciousness, significant diagnosed condition) with help
  text, the class definitions and reason codes.
* **Classification** – the server computes the class with OSHA 29 CFR 1904-style precedence: FATALITY > LOST_TIME
  (≥ 1 day away) > RESTRICTED_DUTY > MEDICAL_TREATMENT > FIRST_AID. Work-relatedness (YES / NO / UNDER_REVIEW) is
  recorded with it. An override, or any change to an existing classification, requires a reason code
  (NEW_INFORMATION, CLINICAL_REVIEW, DATA_CORRECTION, REGULATORY_GUIDANCE, WORK_RELATEDNESS_DECISION, OTHER).
* **Versioning** – every classification is a new row in `intake.recordability_assessment` (append-only grant) with
  answers and rationale encrypted.
* **Local legal mapping (E01-BL-022)** – `intake.recordability_mapping` maps each class to a local category and a
  CAA-reportable flag; maintained by `OH_ADMIN`. **The seeded mapping is a placeholder** pending CMO and HSE Head sign-off.
* **Guard** – `RecordabilityService` implements `CaseTransitionGuard`: an INJURY, ILLNESS or EXPOSURE case cannot leave
  `INTAKE` (except to CLOSED) while unclassified – `422 recordability_required`. When triage completes on an
  unclassified case, the case stays in Intake and the timeline records why.
* **Report** – `GET /intake/recordability/report` counts work-related cases by unit × class with small-cell suppression.

---

## 11. Notification matrix (E01-F08)

`IntakeNotificationDispatcher` applies the matrix in `intake.notification_rule` to case events. Each rule names an
event, optional source and case type filters, a recipient, a channel and a template.

| Event | Recipient | Template (content-free) |
|---|---|---|
| CASE_OPENED | OH queue distribution list | `case.opened.team` – case ref, unit, urgency, next step, workspace link |
| CASE_OPENED (EHS incident) | HSE reporter, line manager | `case.opened.stakeholder` – case ref, unit, next step, OH contact |
| CASE_OPENED (manager referral) | Line manager | `case.opened.stakeholder` |
| CASE_OPENED | Crew scheduling (aeromedical safety categories only) | `case.opened.stakeholder` |
| CASE_OPENED | Hygiene manager (food handlers only) | `case.opened.stakeholder` |
| CASE_ASSIGNED | New case owner | `case.assigned` |
| SLA_AT_RISK | Case owner | `sla.alert` |
| SLA_BREACHED | Nursing Head | `sla.alert` (urgent priority) |
| ESCALATED | OH queue | `escalation.raised` |

Confidentiality is enforced in three layers: (1) only whitelisted placeholders are passed per template – case
reference, unit, urgency, next step, queue, level, contact, link; (2) templates contain no clinical fields; (3) the
notification gateway's content rules reject any rendered message that contains clinical terms or words such as
"positive", "result", "drug" or "alcohol". Quiet hours (21:00–07:00) defer non-urgent messages. E-mail is the
production channel; SMS and push are wired to logging adapters in this increment; Microsoft Teams is not yet available.

---

## 12. Triage protocols (E02-F01, F07)

### 12.1 Engine

* Protocols are versioned JSON definitions (`triage.triage_protocol`) seeded from
  `modules/wz-e02-triage/src/main/resources/triage/protocols/*.json`. Existing versions are never overwritten.
* Questions are BOOLEAN or NUMBER with branching (`onYes` / `onNo` / numeric rules) to the next question or an outcome.
  Outcomes map urgency (EMERGENCY, URGENT, SEMI_URGENT, ROUTINE, ADVICE) to a disposition text and an advice key.
* The **server re-evaluates every answer set** (`ProtocolEvaluator`); the client never decides urgency. Incomplete
  answer sets return `422 triage_incomplete`.
* **Red-flag hard stop** – a red-flag question answered positively yields EMERGENCY; any attempt to override below
  EMERGENCY returns `422 red_flag_hard_stop`.
* **Override** – a different final urgency requires a reason (stored encrypted); computed and final urgency are both kept.
* **Safety-critical floor** – for safety-critical roles the final urgency is raised to at least SEMI_URGENT.
* **Provenance** – protocol code and version, mode, answers, red flags, disposition and assessor are stored per assessment.
* **Telephone mode (E02-F07)** – requires a call log with recorded consent to remote assessment (`400 consent_required`
  otherwise); an optional call-back time creates a `CALL_BACK` task for the case owner.
* **Advice** – `POST /cases/{id}/advice` releases templated advice text (en / ar / fr) to the employee portal
  (`GET /me/advice`) and sends a content-free "advice available" e-mail.
* **Governance** – protocols are `DRAFT` until the CMO approves a version (`POST /triage/protocols/{code}/approve`); the
  previously approved version is retired. Drafts are usable for testing and the UI shows the status.

### 12.2 Protocol set (13, all DRAFT pending CMO sign-off)

| Code | Presentation | Case type | Questions | Red-flag questions |
|---|---|---|---|---|
| MSK | Musculoskeletal pain / injury | INJURY | 4 | 2 |
| LACERATION_BURN | Laceration or burn | INJURY | 4 | 1 |
| EYE | Eye injury or irritation | INJURY | 3 | 1 |
| HEAD_INJURY | Head injury | INJURY | 3 | 1 |
| CHEMICAL | Chemical exposure (skin / inhalation) | EXPOSURE | 3 | 1 |
| FUME_EVENT | Cabin / flight-deck fume event | EXPOSURE | 3 | 1 |
| BODY_FLUID | Body-fluid / needlestick exposure | EXPOSURE | 2 | 0 (exposure < 72 h → URGENT) |
| HEAT | Heat illness | ILLNESS | 3 | 1 |
| RESPIRATORY | Respiratory symptoms | ILLNESS | 3 | 1 |
| CARDIAC | Cardiac symptoms | ILLNESS | 3 | 2 |
| INFECTIOUS_GI | Infectious / gastrointestinal (food-handler aware) | ILLNESS | 3 | 1 |
| PSYCHOLOGICAL | Psychological distress | MENTAL_HEALTH | 4 | 1 |
| CREW_FATIGUE | Crew fatigue / fitness concern | FITNESS | 3 | 0 |

---

## 13. SLA timers (E01-BL-023, E02-F04)

| Aspect | Design |
|---|---|
| Start | On `case.opened.v1`, after routing, a TRIAGE milestone timer starts using the urgency and (optional) case-type policy. Escalations start an ESCALATION_RESPONSE timer with their own target. |
| Policies | `triage.sla_policy` (editable by Nursing Head / OH admin): EMERGENCY 15 min, URGENT 60 min, SEMI_URGENT 240 min, ROUTINE 480 min (1 working day), ADVICE 960 min; amber at 75 %; breach escalates to the Nursing Head. |
| States | ON_TRACK (green), AT_RISK (amber), BREACHED (red), PAUSED, MET, NONE – shown with colour **and** shape (circle, triangle, square) for accessibility. |
| Pause | Setting a case to AWAITING_EXTERNAL or AWAITING_EMPLOYEE pauses all running timers and stores the remaining time; returning to NONE resumes with a new due time. |
| Stop | Triage stops the TRIAGE timer (MET or BREACHED); acknowledgement or decision stops the escalation timer. A breached timer keeps its BREACHED outcome for reporting. |
| Poller | `SlaService.monitor()` runs every `wellnessz.triage.sla.poll-interval` (default 60 s) and claims due timers with `SELECT … FOR UPDATE SKIP LOCKED LIMIT 500`, so every replica can run it without double alerts. Amber publishes `sla.at-risk.v1` once; breach marks the timer, sets the case `escalated` flag, records a timeline entry and publishes `sla.breached.v1`. Timers on closed cases are cancelled. |

---

## 14. Routing and assignment engine (E02-F02, F03)

`AssignmentEngine.decide(case)` runs on every new case, in this order:

1. **Restricted class** (psychological, substance) → `MENTAL_HEALTH` queue (role `PSYCH_CLINICIAN`), least-loaded.
2. **Safety-critical role** (from `masterdata.job_code.safety_category`):
   FLIGHT_CREW, CABIN_CREW, ATC → `AME_AEROMEDICAL`; FOOD_HANDLER → `FOOD_SAFETY` (physician); CERTIFYING_ENGINEER,
   AIRSIDE_DRIVER, FORKLIFT, WORKING_AT_HEIGHT → `PHYSICIAN`. Urgency is raised to at least SEMI_URGENT. Site and
   language preferences apply; least-loaded owner.
3. **Assignment rules** (`triage.assignment_rule`, first match by priority) on subsidiary, site, language, case type
   and safety category; target queue and strategy `NONE`, `ROUND_ROBIN`, `LEAST_LOADED` or `SPECIFIC_USER`;
   optional specialty filter for the owner. Seeded: Arabic speakers → Arabic-speaking nurse, French speakers →
   French-speaking nurse, surveillance follow-up → physician, default least-loaded OH nurse.
4. No rule → `OH_GENERAL`, unassigned.

Owner selection: active staff holding the queue's required role, available today (not out of office), within the
rule's site / language / specialty filters and **below their caseload cap**. If no one matches the soft preferences
(site, language), they are relaxed rather than leaving the case unassigned. LEAST_LOADED picks the lowest open
caseload; ROUND_ROBIN the person assigned longest ago; SPECIFIC_USER falls through to the out-of-office **cover**
colleague (recursively). Every decision is written to `triage.case_assignment` with an explanation and emits
`case.assigned.v1`.

Manual operations: assign to me / to a colleague / to a queue with a reason (`POST /cases/{id}/assign`); assigning to an
out-of-office colleague is refused except for the Nursing Head; bulk re-assignment is reserved to the Nursing Head;
`POST /assignment/rules/test` dry-runs the rules for a case; `PUT /staff/{username}/availability` sets out-of-office
and cover (self or Nursing Head); structured shift **handover** (`POST /cases/{id}/handover`) reassigns the case,
creates a HANDOVER task and requires acknowledgement by the receiver.

---

## 15. Complexity / risk score (E02-F06)

Score 0–100 = sum of driver weights (capped), recalculated after triage, on risk-flag changes and on demand.

| Driver | Default weight |
|---|---|
| Safety-critical role | 10 |
| Repeat injury to the same body part (12 months) | 15 |
| Comorbidity flag | 15 |
| Positive psychosocial screen | 20 |
| Claim or litigation | 20 |
| Absence exceeds duration guideline | 20 |
| Absence more than twice the guideline | 10 |
| Lost-time recordable | 10 |
| Escalated / SLA breached | 5 |
| Emergency or urgent triage | 10 |

Weights are overridable per driver (0–50) in `triage.complexity.weights` (JSON); the threshold
`triage.complexity.intensive-threshold` (default 60) switches the case to the **INTENSIVE** case-management pathway
with a timeline entry. Expected duration comes from `triage.duration_guideline` by primary ICD-10 code (eight
illustrative in-house rows – licensed content is pluggable); actual absence from the absence feed. Each calculation is
stored with its drivers; `GET /triage/complexity/calibration?month=` reports bands vs. outcomes for monthly calibration.

---

## 16. Escalation and case conferences (E02-F05)

* `POST /cases/{id}/escalations` with level and reason code (COMPLEX_CLINICAL, MEDICO_LEGAL, CREW_FITNESS,
  SAFETY_CRITICAL, MENTAL_HEALTH_RISK, SECOND_OPINION, OTHER) and an encrypted note.
* Response SLA: PHYSICIAN 4 h, CMO 8 h (1 working day), EXTERNAL_SPECIALIST 48 h, EMERGENCY_SERVICES immediate
  (no timer). The case is flagged `escalated` and appears in the ESCALATED queue.
* Acknowledgement and decision are reserved to `OH_PHYSICIAN`, `AME` and `CMO`; the decision is captured on the
  timeline ("Escalation decided") and clears the flag when no other escalation is open.
* Case conferences: scheduled time, attendees with roles, agenda; minutes and decisions stored encrypted with visibility
  `CLINICAL_ONLY` or `ALL_ATTENDEES`.

---

## 17. Safety checklists, closure guard and status-only APIs (E02-F02)

| Checklist | Applies to | Items |
|---|---|---|
| `FOOD_HANDLER` | FOOD_HANDLER | GI symptoms, skin lesions, infections, exclusion decision |
| `DRIVING` | AIRSIDE_DRIVER, FORKLIFT | Vision, seizure history, sedating medication, fitness to drive |
| `HEIGHT` | WORKING_AT_HEIGHT | Vertigo, balance, fitness for height |

Each completion records answers (encrypted), an outcome (CLEAR, EXCLUDED, RESTRICTED), the clinician and time.
`SafetyService` implements `CaseTransitionGuard`: a safety-critical case cannot move to CLOSED while its fitness status
is NOT_ASSESSED, UNDER_REVIEW or UNFIT_PENDING (`422 fitness_required`), nor without its role checklist
(`422 checklist_required`).

Status-only APIs (every read audited, simplified vocabulary FIT / UNFIT / FIT_WITH_LIMITATIONS / PENDING, latest case per
employee, no case type, diagnosis or notes):

* `GET /status/crew?employeeIds=…` – `CREW_SCHEDULING` only, up to 500 employees per call.
* `GET /status/food-handlers?department=…` – `HYGIENE_MANAGER` only.

In addition, `fitness.status.changed.v1` is published to `hr-systems`, to `crew-rostering` (crew or unclassified roles)
and to `catering-hygiene` (food handlers) through the outbound field allow-list.

---

## 18. Work queues, case header and triage quality audit

### 18.1 Queues and SLA board (E02-F04)

| Queue | Definition |
|---|---|
| MY | Owner = me |
| TEAM | Cases in queues whose required role I hold (Nursing Head and CMO see all) |
| UNASSIGNED | No owner |
| OVERDUE | SLA breached or past due |
| AWAITING_EXTERNAL / AWAITING_EMPLOYEE | Waiting state set (SLA paused) |
| ESCALATED | Escalated or SLA breached |

One indexed SQL statement per page joins case, owner name, current SLA timer (lateral join) and open-task count, sorted by
SLA due time then urgency. Filters: urgency, subsidiary, case type, safety-critical, free text (case reference, name,
employee ID). Restricted-class cases are excluded unless the caller is a psychological-health clinician. Saved
personal views, counts per queue and bulk actions (REASSIGN – Nursing Head only, REMIND – creates a reminder task,
CLOSE_DUPLICATES) with per-case success / failure results. Partial indexes on open cases (owner, queue, unassigned)
keep queries fast: three queue calls at 5,000 open cases complete in 0.548 s in the performance test.

### 18.2 Case header (E02-BL-018)

`GET /cases/{id}/header`: case reference, status, urgency, SLA state and remaining time, safety-critical category,
badges (SAFETY_CRITICAL, DUPLICATE_SUSPECT, EARLY_INTERVENTION, ESCALATED, HRIS_REVIEW, REPEAT_BODY_PART, INTENSIVE),
open tasks, owner, queue, intensity, complexity score and a computed **next action** (e.g. "Assign a case owner",
"Triage and classify recordability", "Record a fitness-for-duty decision").

### 18.3 Triage quality audit (E02-BL-016)

The Nursing Head draws a random sample (default 10 %, cryptographically random) of assessments in a date range that have
not been sampled before; reviewers record agree / disagree with a comment (stored encrypted) – **self-review is
blocked**; the report returns reviewed count and agreement rate.

---

## 19. OH Workspace – screens

The OH Workspace (`web/oh-workspace`, React 19, same-origin BFF login, hash routing) shows only the navigation entries
a role may use; the server enforces the same rules independently. Screenshots below were captured by the end-to-end run
against a real Keycloak login.

**Work queues** – queue tabs with counts, filters, saved views, bulk actions and SLA symbols.

![Team queue](../images/screens/increment-01/inc1-oh-01-queues-team.png)

![My cases queue](../images/screens/increment-01/inc1-oh-02-queues-my.png)

**Case view** – header with badges, SLA clock, owner, queue, complexity and next action; tabs for overview, triage,
recordability, checklists, assignment, escalation, complexity, duplicates, tasks, timeline, fitness and status.

![Case overview](../images/screens/increment-01/inc1-oh-03-case-overview.png)

![Triage protocol](../images/screens/increment-01/inc1-oh-04-case-triage.png)

![Triage result and history](../images/screens/increment-01/inc1-oh-05-case-triage-result.png)

![Recordability wizard](../images/screens/increment-01/inc1-oh-06-case-recordability.png)

![Safety checklists](../images/screens/increment-01/inc1-oh-06-case-checklists.png)

![Assignment](../images/screens/increment-01/inc1-oh-06-case-assignment.png)

![Escalation](../images/screens/increment-01/inc1-oh-06-case-escalation.png)

![Complexity score with drivers](../images/screens/increment-01/inc1-oh-06-case-complexity.png)

![Case timeline](../images/screens/increment-01/inc1-oh-06-case-timeline.png)

**Team management** – escalations inbox, handovers, caseload board with drag-and-drop and out-of-office cover,
triage QA.

![Escalations](../images/screens/increment-01/inc1-oh-07-escalations.png)

![Handovers](../images/screens/increment-01/inc1-oh-07-handovers.png)

![Caseload board](../images/screens/increment-01/inc1-oh-08-caseload.png)

![Triage audit](../images/screens/increment-01/inc1-oh-08-triage-audit.png)

**Intake operations** – suggestions, reconciliation, dashboard, dead-letter queue, walk-in registration.

![Incident suggestions](../images/screens/increment-01/inc1-oh-07-intake-suggestions.png)

![Reconciliation](../images/screens/increment-01/inc1-oh-07-intake-reconciliation.png)

![Intake dashboard](../images/screens/increment-01/inc1-oh-08-intake-dashboard.png)

![Dead-letter queue](../images/screens/increment-01/inc1-oh-08-intake-dlq.png)

![Walk-in and first aid](../images/screens/increment-01/inc1-oh-09-reception.png)

**Configuration** – triage protocols (approval by CMO), assignment rules, SLA policies, intake rules, notification matrix.

![Protocols](../images/screens/increment-01/inc1-oh-08-admin-protocols.png)

![Assignment rules](../images/screens/increment-01/inc1-oh-08-admin-assignment-rules.png)

![SLA policies](../images/screens/increment-01/inc1-oh-08-admin-sla.png)

![Intake rules](../images/screens/increment-01/inc1-oh-08-admin-intake-rules.png)

![Notification matrix](../images/screens/increment-01/inc1-oh-08-admin-notifications.png)

**Employee app and manager workspace**

![Employee home](../images/screens/increment-01/inc1-employee-01-home.png)

![Self-referral template and body map](../images/screens/increment-01/inc1-employee-03-referral-filled.png)

![Referral acknowledgement with case number](../images/screens/increment-01/inc1-employee-04-referral-confirmation.png)

![Crew fitness declaration](../images/screens/increment-01/inc1-employee-05-fitness-declaration.png)

![Manager referral – work impact only](../images/screens/increment-01/inc1-manager-01-referral-form.png)

![Manager team view – status only](../images/screens/increment-01/inc1-manager-02-team-status-only.png)

---

## 20. Data model

### 20.1 Schemas

| Schema | Owner | Increment-1 content |
|---|---|---|
| `casemgmt` | Case kernel | `oh_case` extended (subsidiary, cost centre, job code, location, shift, HRIS override, case type, incident ref, body part, event date, language, template, urgency, safety category, owner, queue, waiting state, intensity, complexity score, flags, merged-into, recordability, work-related, encrypted intake details); new `case_sequence`, `case_link`, `case_event` (append-only), `case_task`, `case_attachment` (encrypted) |
| `intake` | EHS intake + E01 | `ehs_incident` extended (class, body part, lost time, exposure type, unit, shift, reporter e-mail; multi-casualty uniqueness); new `incident_dlq`, `intake_rule`, `incident_evaluation`, `encounter`, `absence_episode`, `trigger_event`, `recordability_assessment` (append-only), `recordability_mapping`, `notification_rule` |
| `masterdata` | Master data | `job_code.safety_category` + new job codes (forklift, line maintenance, office); new org units; new `employee` (HRIS master incl. badge, manager, cost centre, preferred language) and `staff_member` (OH staff directory) |
| `triage` | E02 (new schema) | `work_queue` (6 seeded), `sla_policy`, `sla_timer`, `assignment_rule`, `case_assignment`, `triage_protocol`, `triage_assessment`, `escalation`, `case_conference`, `case_risk_flags`, `complexity_assessment`, `duration_guideline`, `case_checklist`, `handover`, `saved_view`, `triage_audit_sample`, `case_advice` |

```mermaid
erDiagram
    EMPLOYEE ||--o{ OH_CASE : "employee_id"
    JOB_CODE ||--o{ EMPLOYEE : "job_code (safety_category)"
    EHS_INCIDENT ||--o| INCIDENT_EVALUATION : "rule outcome"
    INCIDENT_EVALUATION }o--o| OH_CASE : "case_id"
    INTAKE_RULE ||--o{ INCIDENT_EVALUATION : "rule_id"
    ENCOUNTER }o--o| OH_CASE : "converted to"
    OH_CASE ||--o{ CASE_LINK : ""
    OH_CASE ||--o{ CASE_EVENT : "timeline"
    OH_CASE ||--o{ CASE_TASK : ""
    OH_CASE ||--o{ CASE_ATTACHMENT : ""
    OH_CASE ||--o{ RECORDABILITY_ASSESSMENT : "versions"
    OH_CASE ||--o{ SLA_TIMER : "milestones"
    OH_CASE ||--o{ TRIAGE_ASSESSMENT : ""
    OH_CASE ||--o{ CASE_ASSIGNMENT : "history"
    OH_CASE ||--o{ ESCALATION : ""
    OH_CASE ||--o{ CASE_CHECKLIST : ""
    OH_CASE ||--o{ COMPLEXITY_ASSESSMENT : ""
    WORK_QUEUE ||--o{ OH_CASE : "queue_code"
    STAFF_MEMBER ||--o{ OH_CASE : "owner_username"
    TRIAGE_PROTOCOL ||--o{ TRIAGE_ASSESSMENT : "code + version"
```

### 20.2 Data protection in the model

* Field-level AES-256-GCM encryption (key-versioned) for all clinical free text: intake details, incident description,
  DLQ payload, encounter complaint / action / notes, recordability answers and rationale, triage answers, override
  reason and call log, escalation note and decision, conference agenda / minutes / decisions, checklist answers,
  handover summary and actions, QA comments, attachments.
* Least-privilege grants: append-only for `case_event`, `recordability_assessment`, `case_assignment`,
  `triage_assessment`, `complexity_assessment`, `case_checklist`, `case_advice`; no DELETE on attachments; reference
  tables (`work_queue`, `duration_guideline`) read-only for the runtime role. Migrations run under a separate owner role.

---

## 21. Security and privacy design

| Control | Implementation |
|---|---|
| Authentication | Browser apps via the BFF (OIDC Authorization Code, HttpOnly session cookie, CSRF tokens – no tokens in the browser); machine clients via client-credentials (`INTEGRATION`) |
| Role-based access | `@PreAuthorize` on every endpoint with the role lists in the API contract; screens hidden per role in the UI (defence in depth, not the control) |
| ABAC disclosure levels | Every case read goes through the policy engine (deny-overrides): CLINICAL (OH team), RESTRICTED_CLINICAL (psychological-health clinicians only – restricted class), SELF (data subject), STATUS_ONLY (managers / HR within org scope). Denied reads return 404 so existence is not confirmed. Clinical E01/E02 endpoints call `CaseAccess.clinical(...)`, which requires clinical disclosure. |
| Status-only rule | (1) Projections that structurally have no clinical fields (`CaseViews.StatusOnly`); (2) dedicated status-only APIs for crew scheduling, hygiene and HSE (incident → cases); (3) content-free notifications with gateway content rules; (4) outbound allow-list per destination: `crew-rostering`, `hr-systems`, `catering-hygiene` may only receive `caseRef, employeeId, fitnessStatus, restrictionUntil, effectiveFrom` (default deny); (5) clinical-text guard on manager / HR input; (6) reconciliation, trigger log and timeline summaries never carry clinical text. |
| Organisational scope | Manager / HR referral and employee look-up limited to the caller's org path; out-of-scope attempts are audited as DENIED |
| Restricted class | Psychological and substance cases routed to `MENTAL_HEALTH`, excluded from other roles' queues and timelines; D&A trigger log stores a generic code |
| Audit | Tamper-evident, hash-chained audit trail (base framework). New audited actions include referrals, scope denials, badge / employee look-ups, encounter registration / enrichment / conversion, suggestion accept / dismiss, reconciliation export, incident-cases read, recordability, triage, assignment, handover, escalation, checklists, risk flags, merges, fitness, timeline reads, crew and hygiene status reads, DLQ replay / discard, protocol approval, QA sampling. |
| Input hardening | Bean validation and regex patterns on all inputs; attachment type by magic bytes; CSV formula-injection neutralisation; webhook batch limits; SQL fully parameterised (the queue builder appends only constant fragments; SpotBugs exclusion justified and reviewed) |
| Small-cell suppression | Dashboard and recordability report suppress counts below `wellnessz.analytics.min-group-size` (default 5) |
| Browser storage | The walk-in offline queue and all screens keep PHI in memory only; idle time-out in the OH Workspace |

---

## 22. Sequence diagrams

### 22.1 EHS incident → case

```mermaid
sequenceDiagram
    autonumber
    participant EHS as EHS system
    participant API as Core API (/intake/ehs/incidents)
    participant IDEM as IdempotencyService
    participant WH as IncidentWebhookService
    participant ING as IncidentIntakeService
    participant OB as Outbox relay
    participant RL as IncidentIntakeListener + RuleEngine
    participant K as CaseService (kernel)
    participant E02 as AssignmentService / SlaService
    participant DUP as DuplicateService
    participant NOT as IntakeNotificationDispatcher
    EHS->>API: POST incidents (Bearer INTEGRATION, Idempotency-Key)
    API->>IDEM: execute(scope, key, body)
    alt key seen with same body
        IDEM-->>EHS: stored 202 (Idempotent-Replayed)
    else first delivery
        IDEM->>WH: receive(body)
        WH->>WH: validate each item
        WH-->>WH: invalid item → incident_dlq + ALERT log + audit
        WH->>ING: importRecords(valid)
        ING->>ING: de-duplicate (source, ref, employee), persist
        ING->>OB: ehs.incident.received.v1
        WH-->>EHS: 202 {accepted, duplicates, deadLettered}
    end
    OB->>RL: deliver event (idempotent consumer)
    RL->>RL: first matching active rule by priority
    alt AUTO_CREATE and employee identified
        RL->>K: openIdempotent(pre-filled command)
        K->>K: resolve HRIS identity, CaseRefGenerator → CAT-2026-000042
        K->>OB: case.opened.v1
        RL->>RL: incident_evaluation = AUTO_CREATED
    else SUGGEST / IGNORE / NO_MATCH
        RL->>RL: incident_evaluation (visible in suggestions / reconciliation)
    end
    OB->>E02: case.opened.v1 → route (safety / rules), owner, start TRIAGE SLA
    OB->>DUP: case.opened.v1 → duplicate + repeat body part check
    OB->>NOT: case.opened.v1 → matrix → content-free e-mails (OH queue, HSE reporter, manager, hygiene / crew scheduling)
```

### 22.2 Employee self-referral

```mermaid
sequenceDiagram
    autonumber
    actor Emp as Employee
    participant App as employee-app
    participant BFF as BFF (session cookie)
    participant API as Core API
    participant RS as ReferralService
    participant K as CaseService
    Emp->>App: open "Refer myself"
    App->>BFF: GET /languages, /intake/templates?audience=EMPLOYEE&lang=ar, /intake/body-map
    BFF->>API: forward with access token
    API-->>App: templates (labels in chosen language), body-map regions
    Emp->>App: template, body map, symptoms, answers, description, consent, photo
    App->>BFF: POST /intake/referrals/self
    BFF->>API: forward (CSRF checked)
    API->>RS: self(user, referral)
    RS->>RS: consent required · validate template, symptoms, regions, options, lengths
    RS->>K: open(SELF_REFERRAL, language, template, encrypted details)
    K-->>RS: case CAT-2026-000043 (case.opened.v1 via outbox)
    RS->>RS: grant optional consent purposes · audit REFERRAL_SELF
    API-->>App: 201 {caseId, caseRef, acknowledgement}
    App->>BFF: POST /cases/{id}/attachments (photo)
    BFF->>API: magic-byte check, ≤ 5 MB, encrypt, audit
    App-->>Emp: acknowledgement with case number
```

### 22.3 Triage and SLA

```mermaid
sequenceDiagram
    autonumber
    participant AS as AssignmentService
    participant SLA as SlaService
    participant POLL as SLA poller (every 60 s, SKIP LOCKED)
    participant NOT as Notification matrix
    actor Nurse as OH nurse
    participant TS as TriageService
    participant PE as ProtocolEvaluator
    AS->>SLA: start(case, TRIAGE) – due = now + policy(urgency), amber at 75 %
    loop every poll
        POLL->>SLA: claimDue(now)
        alt amber reached
            SLA->>NOT: sla.at-risk.v1 → case owner
        else due passed
            SLA->>SLA: timer BREACHED, case.escalated = true, timeline
            SLA->>NOT: sla.breached.v1 → Nursing Head
        end
    end
    Nurse->>TS: POST /cases/{id}/triage {protocol, mode, answers, override?, call?}
    TS->>PE: evaluate(definition, answers)
    PE-->>TS: computed urgency, red flags
    alt red flag and override below EMERGENCY
        TS-->>Nurse: 422 red_flag_hard_stop
    else
        TS->>TS: override reason check · safety-critical floor SEMI_URGENT
        TS->>TS: store assessment (protocol version, encrypted answers / call log)
        TS->>SLA: stop(TRIAGE) → MET or BREACHED
        TS->>TS: call-back task (telephone) · INTAKE→TRIAGE if guards pass (recordability)
        TS->>TS: recalculate complexity · case.triaged.v1
        TS-->>Nurse: TriageResult (urgency, disposition, advice key, SLA)
    end
    Nurse->>SLA: waiting state AWAITING_EMPLOYEE → pause / NONE → resume
```

### 22.4 Assignment

```mermaid
sequenceDiagram
    autonumber
    participant OB as Outbox (case.opened.v1)
    participant AS as AssignmentService
    participant AE as AssignmentEngine
    participant SD as Staff directory + open caseload
    participant SLA as SlaService
    actor NH as Nursing Head
    OB->>AS: case opened
    AS->>AE: decide(case)
    alt restricted class
        AE-->>AS: MENTAL_HEALTH, least-loaded psychological clinician
    else safety-critical role
        AE->>SD: holders of AME / OH_PHYSICIAN, available, below cap, site + language
        AE-->>AS: AME_AEROMEDICAL / FOOD_SAFETY / PHYSICIAN, owner, min urgency SEMI_URGENT
    else first matching assignment rule
        AE->>SD: pool by required role, site, language, specialty, cap (relax site / language if empty)
        AE-->>AS: queue + owner (LEAST_LOADED / ROUND_ROBIN / SPECIFIC_USER → cover if out of office)
    else no rule
        AE-->>AS: OH_GENERAL, unassigned
    end
    AS->>AS: raise urgency · case_assignment history · timeline · case.assigned.v1 (owner notified)
    AS->>SLA: start TRIAGE timer
    NH->>AS: drag-and-drop / POST /cases/{id}/assign {toUsername, reason}
    AS->>AS: availability check · history · case.assigned.v1
```

---

## 23. Key configuration items

| Key / location | Default | Purpose |
|---|---|---|
| `modules.e01.enabled`, `modules.e02.enabled` (config table) | true | Module enablement (E02 seeded by V4) |
| `intake.absence.default.consecutive-days` | 3 | Watch case when an absence exceeds N days |
| `intake.absence.default.episodes-in-90-days` | 3 | Watch case after N episodes in 90 days |
| `intake.absence.<SUBSIDIARY>.…` | CAT = 2 days | Per-subsidiary override |
| `triage.complexity.intensive-threshold` | 60 | Score at which INTENSIVE starts |
| `triage.complexity.weights` | built-in defaults | JSON overrides per driver (0–50) |
| `notifications.crew-scheduling.email`, `notifications.hygiene-manager.email` | (demo values only) | Distribution lists for stakeholder notifications |
| `wellnessz.triage.sla.poll-interval` / `WZ_SLA_POLL_INTERVAL` | PT60S | SLA poller frequency |
| `wellnessz.notification.oh-workspace-url` / `WZ_OH_WORKSPACE_URL` | https://oh.wellnessz.local | Link in notifications |
| `wellnessz.notification.oh-contact` / `WZ_OH_CONTACT` | "Occupational Health service desk" | Contact line in stakeholder notifications |
| `wellnessz.ehs.source-system` | EHS | Default source system for webhook items |
| `wellnessz.integration.outbound-allowlist.catering-hygiene` | status fields | New outbound destination for food-handler status |
| `wellnessz.analytics.min-group-size` | 5 | Small-cell suppression in dashboard and reports |
| `spring.flyway.schemas` | adds `triage` | New schema |
| Intake rules, assignment rules, SLA policies, notification matrix, recordability mapping, work queues | seeded by V4 / V5 | Business configuration maintained in the OH Workspace |
| Triage protocols | `triage/protocols/*.json` seeded as DRAFT | CMO approval required for clinical use |
| `WZ_CORS_ORIGINS`, Keycloak / Entra redirect URIs | add `oh.*` host / `localhost:5175` | New OH Workspace origin |

---

## 24. Non-functional design notes

* **Performance** – partial indexes on open cases, lateral join for the current SLA timer, paging ≤ 200 rows; 5,000 open
  cases: TEAM page + UNASSIGNED page + counts in 0.548 s (target < 2 s).
* **Scalability** – stateless API; SLA poller and outbox relay are replica-safe (`SKIP LOCKED`, advisory locks).
* **Resilience** – at-least-once events with idempotent consumers; webhook idempotency; DLQ with replay; offline-tolerant
  encounter retry by `clientRef`.
* **Accessibility** – SLA state shown with symbols as well as colour; keyboard alternative ("Move to…") for drag-and-drop;
  RTL rendering for Arabic in the employee app.

---

## 25. Known limitations and items for business sign-off

### 25.1 Decisions and sign-offs requested

| # | Item | Owner | Current state |
|---|---|---|---|
| S1 | Approve the 13 triage protocols, urgency matrix and dispositions (E02-BL-001); note BODY_FLUID and CREW_FATIGUE have no red-flag question | CMO | All DRAFT; approval workflow in place |
| S2 | Sign off the six department intake templates, mandatory fields and routing (E01-BL-009) | Nursing Head | Implemented as drafts in `TemplateCatalog` |
| S3 | Local legal recordability mapping and CAA reportability (E01-BL-022) | CMO + HSE Head | Placeholder mapping seeded |
| S4 | Notification matrix and template wording (E01-BL-024/025) | DPO + CMO | Seeded matrix, content-free templates |
| S5 | Intake rule table (which incident classes auto-create vs. suggest vs. ignore) | Nursing Head + HSE | Seeded rules (Section 6) |
| S6 | SLA targets, amber threshold and "working day" definition | Nursing Head | 15 / 60 / 240 / 480 / 960 min, amber 75 %, elapsed time |
| S7 | Complexity weights and intensive threshold; duration-guideline source and licence (E02-BL-014) | Nursing Head + CMO | Defaults; 8 illustrative in-house rows |
| S8 | Absence thresholds per subsidiary | HR + Nursing Head | Default 3 days / 3 episodes; catering 2 days |
| S9 | Clinical-text guard vocabulary (may need Arabic / French terms) | DPO + Nursing Head | English term list + ICD-10 pattern |

### 25.2 Known limitations

| Area | Limitation |
|---|---|
| EHS transport | Webhook, mailbox and Excel only – message-queue and SFTP transports are not built; the production EHS interface decision (E01-BL-001) is to be recorded with the EHS owner. |
| Delivery target | The "99.9 % delivery success" acceptance criterion has not been measured under load. |
| Reconciliation | Weekly exception report is on demand; no scheduled distribution. |
| Intake dashboard | No export, no department or source filter (subsidiary and date range only). |
| Walk-in | Badge read via keyboard-wedge scanner; no native NFC; offline retry is in-memory only (by design – no PHI in browser storage). |
| Surveillance trigger | Relies on the source system's `abnormal` flag; per-test thresholds are not evaluated in WellnessZ. |
| Employee timeline | No filters; 10-year history load (< 2 s) not performance-tested. |
| Incident link | Opens the in-app incident → cases view; no deep link into the EHS system yet. |
| Template routing | The template's declared routing queue is informational; routing is decided by safety category and assignment rules. |
| Channels | E-mail only in production; SMS and push use logging adapters; Teams not available; no per-user channel opt-in. |
| Advice | Advice release does not check a consent purpose; SMS delivery not available. |
| Telephone triage | Not offline-capable. |
| SLA | Elapsed minutes, no business calendar or public holidays. |
| Case header | The usability test (≥ 4/5 satisfaction) has not been run. |
| Performance | Queue test covered 5,000 open cases but not a 50,000-employee master. |
| Integrations | HRIS, absence, surveillance and D&A feeds are exposed as APIs; enterprise adapters (E15) are not yet connected. Status-only APIs exist for crew scheduling and hygiene, but there is no dedicated UI for those roles. |
| Fitness declaration | Server accepts declarations from any employee; the UI steers non-crew users to self-referral. |
