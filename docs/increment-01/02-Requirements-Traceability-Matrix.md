# WellnessZ OHCM – Increment 1 Requirements Traceability Matrix (E01 + E02)

| | |
|---|---|
| **Document** | Increment 1 – 02 Requirements Traceability Matrix |
| **Sources** | E01 and E02 requirement workbooks, v1.0 – Draft for CMO & Nursing Head review (27 October 2026): sheets *High-Level Requirements*, *Feature List*, *Implementation Backlog* |
| **Audience** | CMO, Nursing Head, Cyber GRC, CIO, project sponsor |
| **Companion documents** | 01 – Solution Design E01/E02 · 03 – Release Notes Increment 1 · API contract `API-Contract-E01-E02.md` |

> **Method.** Every row was checked against the code: the endpoint or class named in *Implementation* was located in the
> repository, and the *Verification* column names the automated test, end-to-end (E2E) check or screenshot that
> exercises it. Where an acceptance criterion is only partly met – including criteria that require a business sign-off
> which has not yet happened – the item is marked **Partially implemented** and the gap is stated.
> All API paths are relative to `/api/v1`.

## 1. Coverage summary

| Level | Items | Implemented | Partially implemented | Deferred |
|---|---|---|---|---|
| E01 high-level requirements | 8 | 7 | 1 | 0 |
| E01 features | 8 | 4 | 4 | 0 |
| E01 backlog items | 26 | 15 | 11 | 0 |
| E02 high-level requirements | 6 | 6 | 0 | 0 |
| E02 features | 7 | 6 | 1 | 0 |
| E02 backlog items | 19 | 14 | 5 | 0 |
| **Total** | **74** | **52 (70 %)** | **22 (30 %)** | **0** |

Of the 22 partially implemented items, **7 are essentially complete in code and are gated mainly by a business
activity** – clinical / legal / DPO sign-off or a usability test (E01-HLR-07, E01-BL-009, E01-BL-022, E01-BL-024,
E01-BL-025, E02-BL-001, E02-BL-018); the others lack a specific sub-capability, listed in the *Status* column (mostly
later-increment integrations, scheduled distribution, export or offline operation). No item has been deferred in its
entirety.

Backlog by priority (workbook *Priority* column): all 29 P1 (critical) backlog items – 18 in E01, 11 in E02 – have a
working implementation; 19 of them are fully implemented and 10 partially (E01-BL-001, -002, -009, -011, -018, -022,
-024, -025; E02-BL-001, -018).

## 2. Verification legend

| Ref | Verification artefact |
|---|---|
| IT-1 | `Increment1IntegrationTest.incidentWebhookAutoCreatesPrefilledCaseRoutesAndStartsSla` (PostgreSQL 16) |
| IT-2 | `Increment1IntegrationTest.suggestedIncidentsAreReviewedAndNearMissIgnored` |
| IT-3 | `Increment1IntegrationTest.selfReferralWithTemplateBodyMapConsentAndAttachment` |
| IT-4 | `Increment1IntegrationTest.managerReferralBlocksClinicalContentAndOutOfScopeEmployees` |
| IT-5 | `Increment1IntegrationTest.walkInEncounterIsIdempotentHidesClinicalFieldsFromReceptionAndConverts` |
| IT-6 | `Increment1IntegrationTest.recordabilityIsMandatoryBeforeLeavingIntakeAndChangesNeedReason` |
| IT-7 | `Increment1IntegrationTest.triageEngineRedFlagOverrideTelephoneCallBackAndSafetyClosureGuard` |
| IT-8 | `Increment1IntegrationTest.queuesAssignmentEscalationComplexityAndCrewStatusOnly` |
| IT-9 | `Increment1IntegrationTest.absenceTriggerAndFitnessDeclarationAndDuplicateDetection` |
| PERF | `QueuePerformanceTest.teamQueueAndCountsRespondWithinTwoSecondsAt5000OpenCases` (0.548 s for 3 calls) |
| ARCH | `ArchitectureTest` (ArchUnit module boundaries) |
| UT-RULE | `RuleEngineTest` (first matching rule by priority; category from incident class) |
| UT-REC | `RecordabilityClassifierTest` (OSHA-style precedence) |
| UT-PROT | `ProtocolEvaluatorTest` (red-flag hard stop, numeric rules, validation, all seeded protocols have all outcomes) |
| UT-CTD | `ClinicalTextDetectorTest` (platform/wz-common) |
| WEB-OH | `web/oh-workspace` vitest: `offlineQueue`, `dnd`, `protocolRunner`, `suppression`, `badges`, `BulkResultList`, `links`, `roles`, `screens.smoke` (151 tests) |
| WEB-EMP | `web/employee-app` vitest: `referralForm`, `BodyMap`, `attachments`, `fitnessDeclaration`, `MyAdvice`, `strings` (i18n) (81 tests) |
| WEB-MGR | `web/manager-workspace` vitest: `referrals`, `HrReferral`, `statusOnly` (55 tests) |
| E2E-nn | `tools/e2e/e2e-increment1.js` check nn (Section 6), real Keycloak + BFF + core + three web apps – all 18 PASS |
| 📷 `name` | Screenshot `docs/images/screens/increment-01/name.png` captured by the E2E run |

---

## 3. E01 – Case Intake & Incident-Triggered Case Initiation

### 3.1 High-level requirements

| ID | Short title | Implementation | Verification | Status |
|---|---|---|---|---|
| E01-HLR-01 | Near-real-time incident feed + configurable auto-create rules | `IncidentWebhookService`, `EhsIntakeController` (`POST /intake/ehs/incidents`), `IncidentIntakeListener`, `RuleEngine`, `intake.intake_rule` (14 seeded rules incl. injury, illness, exposure, first-aid-plus, lost time, fume, body fluid, heat) | IT-1, IT-2, UT-RULE, E2E-01, E2E-09, E2E-15 | Implemented |
| E01-HLR-02 | Self / manager / HR referral, web + mobile, department templates | `ReferralService`, `ReferralController`, `TemplateCatalog`; employee-app `SelfReferral` (web, PWA, Capacitor); manager-workspace `ManagerReferral`, `HrReferral` | IT-3, IT-4, WEB-EMP, WEB-MGR, E2E-02, E2E-04, E2E-05 | Implemented |
| E01-HLR-03 | Walk-in / emergency / first-aid encounters, conversion to case | `EncounterService`, `EncounterController`, OH Workspace *Reception* | IT-5, WEB-OH (`offlineQueue`), 📷 `inc1-oh-09-reception` | Implemented |
| E01-HLR-04 | System triggers: absence, surveillance, crew decrease-in-fitness, D&A, repeat body part | `TriggerService`, `TriggerController`, `DuplicateService.onCaseOpened` (repeat body part) | IT-9, WEB-EMP (`fitnessDeclaration`), 📷 `inc1-employee-05-fitness-declaration` | Implemented (surveillance and D&A paths have no automated test – see BL-015) |
| E01-HLR-05 | Unique numbering, HRIS linkage, subsidiary, department, cost centre, job code, incident, location, shift | `CaseRefGenerator` (`UNIT-YYYY-NNNNNN`), `CaseService.resolveIdentity`, `OhCase` columns (V3) | IT-1 (asserts `CAT-YYYY-NNNNNN`, subsidiary, cost centre, job code), 📷 `inc1-oh-03-case-overview` | Implemented |
| E01-HLR-06 | Probable duplicates (employee, date ±2 d, body part / incident) → merge / link | `DuplicateService`, `DuplicateController` (`/cases/{id}/duplicates`, `/merge`), `/cases/{id}/links` | IT-9 | Implemented |
| E01-HLR-07 | Minimum regulatory data set, OSHA-300-style recordability adapted to local law | `RecordabilityClassifier`, `RecordabilityService`, `intake.recordability_mapping` | IT-6, UT-REC, 📷 `inc1-oh-06-case-recordability` | Partially implemented – classifier, guard and versioning complete; the local-law / CAA mapping is a seeded placeholder awaiting CMO + HSE Head sign-off (E01-BL-022) |
| E01-HLR-08 | Notify OH team, HSE reporter, crew scheduling – confidentiality-safe | `IntakeNotificationDispatcher`, `intake.notification_rule`, `OhRecipientResolver`, notification content rules | IT-1 (bodies contain no clinical words), 📷 `inc1-oh-08-admin-notifications` | Implemented (e-mail channel; see E01-F08) |

### 3.2 Features

| ID | Short title | Implementation | Verification | Status |
|---|---|---|---|---|
| E01-F01 | Incident feed listener & rule engine | Webhook + mailbox + Excel → `IncidentIntakeService` → `ehs.incident.received.v1` → `IncidentIntakeListener`; AUTO_CREATE / SUGGEST / IGNORE / NO_MATCH; payload stored (description encrypted); screens *Intake rules*, *Incident suggestions*, *Reconciliation*, *Intake dashboard* | IT-1, IT-2, UT-RULE, E2E-01, E2E-09, E2E-15, E2E-16 | Partially implemented – message-queue and SFTP fallback transports not built (webhook, mailbox, Excel available); intake dashboard has no export (BL-026) |
| E01-F02 | Self-referral & manager / HR referral forms | Role-aware templates (6 employee + manager + HR), body map, symptoms, consent, attachments, en/ar/fr, clinical-text guard | IT-3, IT-4, UT-CTD, WEB-EMP, WEB-MGR, E2E-02, E2E-04..07, 📷 `inc1-employee-02-referral-template`, `inc1-employee-03-referral-filled`, `inc1-employee-04-referral-confirmation`, `inc1-manager-01-referral-form` | Implemented (template content sign-off tracked under E01-BL-009) |
| E01-F03 | Walk-in / emergency registration | Badge lookup, emergency mode, enrichment, one-click conversion, `clientRef` idempotency, in-memory retry queue | IT-5, WEB-OH (`offlineQueue`), E2E-17, 📷 `inc1-oh-09-reception` | Partially implemented – no native NFC reader support (keyboard-wedge scanners only); offline tolerance is an in-memory retry queue, not a persistent offline store with mobile sync |
| E01-F04 | System-trigger case flags | `TriggerService` (absence thresholds per subsidiary, surveillance, fitness declaration, D&A), repeat body part | IT-9 | Implemented |
| E01-F05 | Case identity & linking | `CaseRefGenerator`, HRIS identity, `case_link` (INCIDENT, CASE, CLAIM, SURVEILLANCE, ENCOUNTER, ABSENCE, MERGED_FROM), case and employee timelines | IT-1, IT-5, 📷 `inc1-oh-03-case-overview`, `inc1-oh-06-case-timeline` | Partially implemented – the incident link opens the in-app *Incident → cases* view, not the EHS record in context (no EHS deep-link configured) |
| E01-F06 | Duplicate detection & merge | `DuplicateService` scoring, merge with audit, link, bulk close duplicates | IT-9 | Implemented |
| E01-F07 | Recordability classifier | Wizard, mandatory before leaving Intake (`CaseTransitionGuard`), reason code on change, mapping maintained by OH admin | IT-6, UT-REC, 📷 `inc1-oh-06-case-recordability` | Implemented |
| E01-F08 | Intake notifications & SLA timers | Notification matrix + content-free templates; SLA timer starts at case creation (`AssignmentService.onCaseOpened` → `SlaService.start`) | IT-1 (timer RUNNING, notifications), IT-8, E2E-14 | Partially implemented – e-mail delivered; SMS and app push are logging adapters; Microsoft Teams channel not available |

### 3.3 Implementation backlog

| ID | Short title | Implementation | Verification | Status |
|---|---|---|---|---|
| E01-BL-001 | Spike: EHS integration options & data-model mapping | Interface specified in `API-Contract-E01-E02.md` §4.1 (schema, vocabulary, idempotency, DLQ, retry by `Idempotency-Key`); mailbox and Excel retained | Document review | Partially implemented – webhook interface specified and built; the production transport decision with the EHS system owner (webhook vs. queue vs. SFTP) and field mapping to the real EHS data model are not yet recorded |
| E01-BL-002 | Ingestion endpoint with idempotency keys, schema validation, DLQ | `EhsIntakeController.webhook` + `IdempotencyService` (key required, replay, 422 on reuse), `IncidentWebhookService.toRecord`, `intake.incident_dlq`, `DeadLetterController` (list / replay / discard), alert log + audit | IT-1 (invalid item → DLQ), E2E-01, E2E-16, 📷 `inc1-oh-08-intake-dlq` | Partially implemented – functionality complete; the "99.9 % delivery success in test" criterion has not been measured with a load test |
| E01-BL-003 | Configurable incident classes auto-create a pre-filled case | `intake.intake_rule` editable by OH admin / Nursing Head (`/intake/rules`, dry run `/intake/rules/test`), `IncidentCaseFactory` pre-fills incident ref, employee, unit, location, shift, body part; rule hit logged in `incident_evaluation` | IT-1, UT-RULE, E2E-09, 📷 `inc1-oh-08-admin-intake-rules` | Implemented |
| E01-BL-004 | HSE reconciliation view: produced vs. ignored | `ReconciliationService`, `GET /intake/reconciliation` (filters from, to, outcome, unit), `/export` (CSV, formula-safe), `/exceptions` | IT-1 (HSE sees AUTO_CREATED without clinical text; CSV attachment), 📷 `inc1-oh-07-intake-reconciliation` | Partially implemented – exception report is on demand (default last 7 days); no scheduled weekly distribution |
| E01-BL-005 | Suggested incidents review list: accept / dismiss with reason | `GET /intake/suggestions`, `/accept` (optional employee ID), `/dismiss` (reason codes); dismissals visible in reconciliation (outcome, reason, decided by) | IT-2, E2E-15, 📷 `inc1-oh-07-intake-suggestions` | Implemented |
| E01-BL-006 | Mobile self-referral with body map, symptoms, photo, consent, acknowledgement | `POST /intake/referrals/self`, `GET /intake/body-map`, `POST /cases/{id}/attachments` (magic bytes, ≤ 5 MB, ≤ 5, encrypted); employee-app `SelfReferral`, `BodyMap` | IT-3, WEB-EMP (`referralForm`, `BodyMap`, `attachments`), E2E-02, 📷 `inc1-employee-03-referral-filled`, `inc1-employee-04-referral-confirmation` | Implemented (the "< 3 min" target is a usability measure; not timed) |
| E01-BL-007 | Manager referral: work-impact facts only | `ReferralService.manager` + `ClinicalTextDetector` → `422 clinical_content_not_allowed` with field; org-scope check | IT-4, UT-CTD, WEB-MGR (`referrals`), E2E-04, E2E-05, E2E-06, 📷 `inc1-manager-01-referral-form` | Implemented |
| E01-BL-008 | HR referral for long-term absence with policy context | `ReferralService.hr` (absence summary, policy stage, absence days, advice questions, clinical-text guard); HR status-only access via ABAC | WEB-MGR (`HrReferral`) | Implemented (no server-side integration test for the HR path; same guard as manager path covered by IT-4) |
| E01-BL-009 | Six department intake templates with mandatory fields and routing | `TemplateCatalog`: CATERING, RAMP_CARGO, DUTYFREE_DISTRIBUTION, ENGINEERING, CORPORATE, CREW (+ MANAGER_WORK_IMPACT, HR_ABSENCE) | IT-3 (six templates, Arabic labels), 📷 `inc1-employee-02-referral-template` | Partially implemented – templates built; Nursing Head sign-off outstanding; the template's routing queue is informational (routing is decided by safety category and assignment rules) |
| E01-BL-010 | Form in preferred language | `GET /languages`, `?lang=` (en / ar / fr, fallback en), RTL rendering, language stored on case, free text stored as entered | IT-3, WEB-EMP (`strings`), 📷 `inc1-employee-02-referral-template` | Implemented |
| E01-BL-011 | Badge scan registration < 1 min, HRIS cache, offline queue | `GET /intake/employees/by-badge/{badgeId}`, `POST /intake/encounters` (`clientRef`), Reception screen (keyboard-wedge scan), `offlineQueue.ts` | IT-5, WEB-OH (`offlineQueue`), 📷 `inc1-oh-09-reception` | Partially implemented – NFC not supported natively; offline queue is in-memory (lost on tab close, by design to keep PHI out of browser storage) |
| E01-BL-012 | First-aider emergency mode with 5 fields, later enrichment, audit | Mode `EMERGENCY`, `enrichmentDue`, `PATCH /intake/encounters/{id}`, audit `ENCOUNTER_REGISTERED` / `ENCOUNTER_ENRICHED` | IT-5 (`enrichmentDue=true`, reception sees no clinical fields) | Implemented |
| E01-BL-013 | One-click conversion; case type by chief complaint; encounter stays linked | `POST /intake/encounters/{id}/convert`, `EncounterService.typeFor`, `ENCOUNTER` link | IT-5 (reception 403, nurse converts → `CORP-…`) | Implemented |
| E01-BL-014 | Absence feed raises early-intervention watch case | `POST /intake/absence-episodes`, thresholds `intake.absence.<SUB>.*` (CAT = 2 days), one open watch case with absence history, `EARLY_INTERVENTION` badge | IT-9 | Implemented (absence feed exposed as an API; HRIS absence connector is an E15 integration) |
| E01-BL-015 | Abnormal surveillance result opens case | `POST /intake/triggers/surveillance-result` → `SURVEILLANCE_FOLLOW_UP`; assignment rule "Surveillance follow-up → physician" | Code review only | Partially implemented – relies on the source system's `abnormal` flag; per-test thresholds (audiometry shift, spirometry decline, titre) are not evaluated in WellnessZ; no automated test |
| E01-BL-016 | Crew decrease-in-fitness declaration → fitness case, scheduling sees "unfit – pending" | `POST /intake/triggers/fitness-declaration`, `initialFitness(UNFIT_PENDING)`, `CaseService.publishFitness` → `crew-rostering` (allow-listed fields); AME routing; employee-app `FitnessDeclaration` | IT-9 (outbox event to `crew-rostering`), WEB-EMP (`fitnessDeclaration`), 📷 `inc1-employee-05-fitness-declaration` | Implemented |
| E01-BL-017 | HRIS ID, subsidiary, department, cost centre, job code mandatory; manual override flagged | `CaseService.resolveIdentity`, `masterdata.employee`, `hrisOverride` → `HRIS_REVIEW` badge | IT-1 | Implemented |
| E01-BL-018 | Employee timeline (cases, encounters, surveillance, absences) | `EmployeeTimelineController` (`GET /employees/{id}/timeline`), OH Workspace *Employee timeline* | WEB-OH (`screens.smoke`) | Partially implemented – no filters; 10-year history < 2 s not performance-tested; surveillance appears via trigger log only |
| E01-BL-019 | Probable duplicates flagged at creation; merge keeps both sources | `DuplicateService.onCaseOpened` (score), merge with `MERGED_FROM` link, links / attachments moved, audit | IT-9 | Implemented |
| E01-BL-020 | Multi-casualty: one incident → N cases, status-only list | Unique index per `(source, ref, employee)`; `GET /intake/incidents/{ref}/cases` (status only, audited); *Incident → cases* screen | WEB-OH (`screens.smoke`) | Implemented |
| E01-BL-021 | Guided recordability wizard, rationale, reason on change, report by unit | `GET /intake/recordability/wizard`, `POST /cases/{id}/recordability`, history, `GET /intake/recordability/report` (small-cell suppression) | IT-6, UT-REC, 📷 `inc1-oh-06-case-recordability` | Implemented |
| E01-BL-022 | Local legal recordability and CAA mapping signed off | `intake.recordability_mapping`, `GET/PUT /intake/recordability/mapping` (OH admin), *Recordability mapping* screen | IT-6 (`caaReportable=true` for LOST_TIME) | Partially implemented – configuration mechanism complete; seeded mapping is a placeholder; CMO and HSE Head sign-off outstanding |
| E01-BL-023 | SLA timers from intake, amber / red in queue, breach escalation to Nursing Head | `SlaService` (start, pause, resume, poller with `SKIP LOCKED`), `triage.sla_policy`, queue colour + symbol, `sla.breached.v1` → Nursing Head | IT-1 (timer running), IT-8 (PAUSED), E2E-14, 📷 `inc1-oh-01-queues-team`, `inc1-oh-08-admin-sla` | Implemented |
| E01-BL-024 | Manager notification without medical content | Template `case.opened.stakeholder` (case ref, unit, next step, contact), parameter whitelist, gateway content rules | IT-1 | Partially implemented – content-free notification delivered by e-mail; DPO review of the template and per-user opt-in channels outstanding |
| E01-BL-025 | Notification matrix (who, what, when, channel) with DPO and CMO sign-off | `intake.notification_rule` (10 seeded rules), `GET/PUT /intake/notification-matrix`, *Notification matrix* screen | IT-1 (catering scenario), 📷 `inc1-oh-08-admin-notifications` | Partially implemented – matrix configured; DPO / CMO sign-off and documented crew and corporate scenario tests outstanding |
| E01-BL-026 | Intake dashboard: sources, volumes, SLA, backlog by unit; filters; export | `GET /intake/dashboard` (by source with suppression, by unit, SLA compliance, backlog, trend; filter subsidiary + date), *Intake dashboard* | 📷 `inc1-oh-08-intake-dashboard` (defect `bySource.source` found and fixed during verification) | Partially implemented – no export; no department or source filter |

---

## 4. E02 – Clinical Triage, Risk Stratification & Case Assignment

### 4.1 High-level requirements

| ID | Short title | Implementation | Verification | Status |
|---|---|---|---|---|
| E02-HLR-01 | Structured nurse-triage protocols with urgency grading and disposition | `ProtocolEvaluator`, `TriageService`, `ProtocolCatalog`, 13 JSON protocols, CMO approval endpoint | IT-7, UT-PROT, WEB-OH (`protocolRunner`), E2E-11, E2E-12 | Implemented (protocol content DRAFT – see E02-BL-001) |
| E02-HLR-02 | Flag safety-critical / licensed roles; route to physician / AME with elevated priority | `masterdata.job_code.safety_category`, `AssignmentEngine` (AME_AEROMEDICAL, FOOD_SAFETY, PHYSICIAN; min SEMI_URGENT), `SafetyService` closure guard | IT-1 (food handler → FOOD_SAFETY, physician1, SEMI_URGENT), IT-7 | Implemented |
| E02-HLR-03 | Rule-based assignment (unit, location, language, specialty, caseload), manual override, history | `AssignmentEngine`, `AssignmentService`, `triage.assignment_rule`, `triage.case_assignment` | IT-8, E2E-10 | Implemented |
| E02-HLR-04 | Work queues with SLA colour coding and bulk actions | `QueueService`, `WorkController` (`/queues/*`, `/queues/bulk`) | IT-8, PERF, E2E-08, 📷 `inc1-oh-01-queues-team`, `inc1-oh-02-queues-my` | Implemented |
| E02-HLR-05 | Escalation paths with reasons and response-time tracking | `EscalationService` (PHYSICIAN, CMO, EXTERNAL_SPECIALIST, EMERGENCY_SERVICES; response SLA) | IT-8, E2E-13, 📷 `inc1-oh-07-escalations` | Implemented |
| E02-HLR-06 | Complexity / risk score | `ComplexityService` (10 drivers, configurable weights, intensive threshold) | IT-8, 📷 `inc1-oh-06-case-complexity` | Implemented |

### 4.2 Features

| ID | Short title | Implementation | Verification | Status |
|---|---|---|---|---|
| E02-F01 | Triage protocol engine | Branching questions, red-flag hard stop, urgency, advice text, override with reason, protocol version stored | IT-7, UT-PROT, WEB-OH (`protocolRunner`), E2E-11, E2E-12, 📷 `inc1-oh-04-case-triage`, `inc1-oh-05-case-triage-result`, `inc1-oh-08-admin-protocols` | Implemented |
| E02-F02 | Safety-critical role routing | Job-code flags, auto-route, badge, closure blocked without fitness status and checklist | IT-1, IT-7, 📷 `inc1-oh-06-case-checklists`, `inc1-oh-05-case-triage-result` (badge) | Implemented |
| E02-F03 | Assignment rules & caseload balancing | Rule builder with test mode, caps, round-robin / least-loaded, manual (re)assign, drag-and-drop caseload board, out-of-office cover, history | IT-8, WEB-OH (`dnd`), E2E-10, 📷 `inc1-oh-08-caseload`, `inc1-oh-08-admin-assignment-rules`, `inc1-oh-06-case-assignment` | Implemented |
| E02-F04 | Work queues & SLA board | Role-based queues, filters, saved views, colour-coded SLA, bulk reassign / remind / close duplicates; < 2 s at 5,000 open cases | IT-8, PERF, WEB-OH (`BulkResultList`), E2E-08, E2E-14 | Implemented |
| E02-F05 | Escalation & second opinion | Escalate with reason and response SLA, acknowledgement, decision log on timeline, case conferences | IT-8, E2E-13, 📷 `inc1-oh-06-case-escalation`, `inc1-oh-07-escalations` | Implemented |
| E02-F06 | Complexity / risk scoring | Score with drivers, threshold → INTENSIVE pathway, calibration report, duration guideline | IT-8, 📷 `inc1-oh-06-case-complexity` | Implemented |
| E02-F07 | Telephone / remote triage mode | Mode TELEPHONE, call log with consent (enforced), protocol questions as script, outcome, call-back task | IT-7 | Partially implemented – not offline-capable |

### 4.3 Implementation backlog

| ID | Short title | Implementation | Verification | Status |
|---|---|---|---|---|
| E02-BL-001 | Agree protocol set (≥ 12) and urgency matrix with CMO | 13 protocols in `triage/protocols/*.json` (MSK, LACERATION_BURN, EYE, HEAD_INJURY, CHEMICAL, FUME_EVENT, BODY_FLUID, HEAT, RESPIRATORY, CARDIAC, INFECTIOUS_GI, PSYCHOLOGICAL, CREW_FATIGUE), urgency outcomes and dispositions, CMO approval endpoint | IT-7 (≥ 12 protocols), UT-PROT (`allSeededProtocolsHaveAllOutcomes`) | Partially implemented – all protocols DRAFT pending CMO approval; BODY_FLUID and CREW_FATIGUE have no red-flag question |
| E02-BL-002 | Branching engine, red-flag hard stops, override with reason, version stored | `ProtocolEvaluator`, `TriageService.triage` (server-side evaluation, `422 red_flag_hard_stop`, `override_reason_required`), `triage.triage_assessment` | IT-7, UT-PROT, E2E-11, E2E-12 | Implemented |
| E02-BL-003 | Advice text / self-care instructions sent to employee | `POST /cases/{id}/advice`, `triage/advice.json` (en / ar / fr), `GET /me/advice`, content-free e-mail; employee-app *My advice* | WEB-EMP (`MyAdvice`), 📷 `inc1-employee-06-advice` | Partially implemented – portal and e-mail delivered; SMS is a logging adapter; no explicit consent-purpose check before release |
| E02-BL-004 | Flight / cabin crew routed to AME with badge; closure blocked without fitness status | `AssignmentEngine` → `AME_AEROMEDICAL`, `SAFETY_CRITICAL` badge, `SafetyService.check` (`fitness_required`), audit | IT-7 (closure guard), IT-8 (cabin crew case, AME fitness) | Implemented |
| E02-BL-005 | Food handlers flagged with exclusion checklist; hygiene manager sees status only | FOOD_HANDLER → `FOOD_SAFETY` queue; `FOOD_HANDLER` checklist mandatory before closure; `GET /status/food-handlers` (HYGIENE_MANAGER, audited); `catering-hygiene` outbound feed | IT-1 (food handler routing + hygiene notification) | Implemented (status API only – no hygiene-manager screen) |
| E02-BL-006 | Airside driver, forklift, working-at-height flags and checklists; status to manager | `DRIVING` and `HEIGHT` checklists, closure guard, manager status-only view (ABAC) | IT-7 (`checklist_required`, DRIVING completed), 📷 `inc1-oh-06-case-checklists`, `inc1-manager-02-team-status-only` | Implemented |
| E02-BL-007 | Auto-assign rules by subsidiary, site, language; priorities; test mode; logged | `triage.assignment_rule`, `/assignment/rules` (Nursing Head / OH admin), `/assignment/rules/test`, `case_assignment` | IT-1 (routing), IT-8, 📷 `inc1-oh-08-admin-assignment-rules` | Implemented |
| E02-BL-008 | Caseload view, drag-and-drop re-assignment, leave cover, notification to new owner | `GET /assignment/caseload`, *Caseload* board (`dnd.ts` + keyboard "Move to…"), `PUT /staff/{u}/availability`, CASE_ASSIGNED → CASE_OWNER e-mail | IT-8, WEB-OH (`dnd`), E2E-10, 📷 `inc1-oh-08-caseload` | Implemented |
| E02-BL-009 | Queues My / Unassigned / Overdue / Awaiting external / Awaiting employee; saved views; counts; bulk | 7 queues (also TEAM, ESCALATED), `/queues/counts`, `/queues/views`, `/queues/bulk` | IT-8, E2E-08, 📷 `inc1-oh-01-queues-team`, `inc1-oh-02-queues-my` | Implemented |
| E02-BL-010 | Index queue queries for 5,000+ open cases (< 2 s) | Partial indexes `ix_case_open_owner`, `ix_case_open_queue`, `ix_case_open_unassigned`, `ix_sla_timer_*`; single paged SQL with lateral join | PERF (0.548 s for three calls) | Implemented (tested with 5,000 open cases; a 50,000-employee master was not loaded – queue queries do not join the employee master) |
| E02-BL-011 | Escalate to physician / CMO with reason, response SLA, acknowledgement, decision | `POST /cases/{id}/escalations`, `/escalations/{id}/acknowledge`, `/decision` (physician, AME, CMO only), ESCALATION_RESPONSE timer | IT-8 (nurse 403 on decision, physician DECIDED), E2E-13 | Implemented |
| E02-BL-012 | Case conference with attendees, agenda, role-limited minutes, decisions logged | `POST/GET /cases/{id}/conferences`, `POST /conferences/{id}/minutes` (visibility CLINICAL_ONLY / ALL_ATTENDEES) | Code review; 📷 `inc1-oh-06-case-escalation` | Implemented (no automated test; no calendar invitation – E14) |
| E02-BL-013 | Complexity score with drivers, threshold → pathway, monthly calibration | `ComplexityService`, `GET /triage/complexity/calibration`, config weights / threshold | IT-8 (score ≥ 55 with driver labels), 📷 `inc1-oh-06-case-complexity` | Implemented |
| E02-BL-014 | Load disability-duration reference content mapped to ICD | `triage.duration_guideline` (8 in-house rows), `GET /triage/duration-guidelines`, expected vs. actual days on complexity | Code review | Partially implemented – illustrative in-house table only; licensed guideline content and licence terms not confirmed |
| E02-BL-015 | Telephone triage screen with call log, script, call-back task; offline-capable | TELEPHONE mode, call log (caller, phone, times, consent, call-back), `CALL_BACK` task | IT-7 (consent required; call-back task created) | Partially implemented – not offline-capable |
| E02-BL-016 | Triage QA sample (10 %) with reviewer comments and agreement rate | `TriageAuditService` (`/triage/audit/sample`, `/samples`, `/review`, `/report`), self-review blocked | 📷 `inc1-oh-08-triage-audit` | Implemented (delivered ahead of its P3 phase; no automated server test) |
| E02-BL-017 | Crew scheduling sees fit / unfit / fit-with-limitations / pending only; reads audited | `GET /status/crew` (CREW_SCHEDULING only), `SafetyService.simple`, audit `CREW_STATUS_READ`, `crew-rostering` allow-list | IT-8 (UNFIT, no clinical words, nurse 403), E2E-18 | Implemented |
| E02-BL-018 | Case header: SLA, safety flag, open tasks, next action | `GET /cases/{id}/header` (badges, SLA, tasks, next action, owner, queue, complexity) | IT-8 (`sla.state=PAUSED`), E2E-14, 📷 `inc1-oh-03-case-overview`, `inc1-oh-05-case-triage-result` | Partially implemented – header delivered; the nurse usability test (≥ 4/5 satisfaction) has not been run |
| E02-BL-019 | Structured shift handover, receiving nurse acknowledges | `POST /cases/{id}/handover` (summary, outstanding actions, next contact), HANDOVER task, `/handovers/{id}/acknowledge`, `/handovers/pending` | 📷 `inc1-oh-07-handovers` | Implemented (no automated server test) |

---

## 5. Cross-cutting requirements

| Requirement (workbook notes) | Implementation | Verification | Status |
|---|---|---|---|
| Confidentiality rule: managers, HR, crew scheduling, hygiene, claims see status only | ABAC `statusOnlyPolicy`, `CaseViews.StatusOnly`, status APIs, outbound allow-list, notification content rules, clinical-text guard | IT-1, IT-4, IT-8, E2E-03, E2E-07, E2E-17, E2E-18, 📷 `inc1-manager-02-team-status-only` | Implemented |
| Modular boundaries (E01 / E02 extractable) | ArchUnit rules | ARCH | Implemented |
| Audit of access to health data | `AuditService` hash chain; new audited actions listed in document 01 §21 | IT suites (audit written on every path); audit timestamp precision defect fixed | Implemented |

---

## 6. End-to-end checks (tools/e2e/e2e-increment1.js)

| # | Check | Result |
|---|---|---|
| E2E-01 | EHS webhook accepted | PASS |
| E2E-02 | Employee self-referral submitted through the UI | PASS |
| E2E-03 | Employee cannot open OH queues | PASS |
| E2E-04 | Manager referral with clinical wording is rejected | PASS |
| E2E-05 | Manager referral accepted | PASS |
| E2E-06 | Manager cannot refer an employee outside their org scope | PASS |
| E2E-07 | Manager team view carries no clinical text | PASS |
| E2E-08 | Queue counts | PASS |
| E2E-09 | EHS incident auto-created and routed a case | PASS |
| E2E-10 | Nursing Head reassigns the case to nurse1 | PASS |
| E2E-11 | Red-flag answer cannot be downgraded (hard stop) | PASS |
| E2E-12 | Triage protocol recorded | PASS |
| E2E-13 | Escalation raised to physician | PASS |
| E2E-14 | Case header carries the SLA clock | PASS |
| E2E-15 | First-aid incident waits as a suggestion for nurse review | PASS |
| E2E-16 | Invalid EHS record is held in the dead-letter queue | PASS |
| E2E-17 | Reception cannot read clinical queues | PASS |
| E2E-18 | Crew scheduling sees fitness status only | PASS |

## 7. Items without automated verification

The following implemented paths are verified by code review and screenshots only and should receive automated tests in
the next increment: HR referral server path (E01-BL-008), surveillance and D&A triggers (E01-BL-015, E01-HLR-04),
employee timeline (E01-BL-018), multi-casualty view (E01-BL-020), case conferences (E02-BL-012), triage QA sampling
(E02-BL-016), shift handover (E02-BL-019), advice release (E02-BL-003), intake dashboard (E01-BL-026), DLQ replay.
