# Increment 1 – E01 Intake & E02 Triage: API and Domain Contract

Source of truth shared by backend (`modules/wz-e01-intake`, `modules/wz-e02-triage`, kernel changes) and the UIs
(`web/oh-workspace` – new clinical console, `web/employee-app`, `web/manager-workspace`).
All paths are relative to the core API base `/api/v1`; browser apps call them through the BFF as `/bff/api/...`.
Errors are RFC 9457 problem details `{type, title, status, detail, code, correlationId}`. Timestamps ISO-8601 UTC,
dates `YYYY-MM-DD`. Lists are plain JSON arrays unless stated.

## 1. Roles (added in this increment)

| Role | Persona | Disclosure |
|---|---|---|
| `OH_CLINICIAN` | OH nurse / nurse case manager | CLINICAL |
| `OH_PHYSICIAN` | OH physician | CLINICAL |
| `AME` | Aeromedical examiner | CLINICAL |
| `CMO` | Chief medical officer | CLINICAL (+ restricted on escalation) |
| `NURSING_HEAD` | Nursing Head – queues, caseload, rules | CLINICAL |
| `PSYCH_CLINICIAN` | Psychological health (restricted class) | RESTRICTED_CLINICAL |
| `HSE_OFFICER` | Safety officer – incident reconciliation | STATUS_ONLY (incident-linked cases) |
| `CREW_SCHEDULING` | Crew scheduling – fitness status API | STATUS_ONLY (crew) |
| `HYGIENE_MANAGER` | Catering hygiene manager – food-handler exclusion status | STATUS_ONLY (food handlers) |
| `CLINIC_RECEPTION`, `FIRST_AIDER` | Walk-in / emergency registration | write encounters, no case read |
| `MANAGER`, `HR`, `EMPLOYEE`, `OH_ADMIN`, `PLATFORM_ADMIN`, `AUDITOR` | as before | as before |

"OH roles" below = `OH_CLINICIAN, OH_PHYSICIAN, AME, CMO, NURSING_HEAD, CASE_MANAGER, PSYCH_CLINICIAN`.

## 2. Enumerations

* `CaseCategory`: INJURY, ILLNESS, DECREASE_IN_FITNESS, PSYCHOLOGICAL, SUBSTANCE, EXPOSURE, OTHER
* `CaseType` (pathway): INJURY, ILLNESS, EXPOSURE, FITNESS, SURVEILLANCE_FOLLOW_UP, MENTAL_HEALTH, ABSENCE, CLAIM, SUBSTANCE, OTHER
* `CaseSource`: EHS_INCIDENT, SELF_REFERRAL, MANAGER_REFERRAL, HR_REFERRAL, WALK_IN, EMERGENCY, ABSENCE_TRIGGER, SURVEILLANCE_TRIGGER, FITNESS_DECLARATION, DA_TEST, SYSTEM_TRIGGER, PARTNER_REFERRAL
* `CaseStatus`: INTAKE, TRIAGE, ASSESSMENT, FITNESS_REVIEW, TREATMENT, CLAIMS, MONITORING, CLOSED, MERGED
* `Urgency` (E02-HLR-01): EMERGENCY, URGENT, SEMI_URGENT, ROUTINE, ADVICE
* `SafetyCategory` (E02-HLR-02): FLIGHT_CREW, CABIN_CREW, CERTIFYING_ENGINEER, ATC, AIRSIDE_DRIVER, FORKLIFT, FOOD_HANDLER, WORKING_AT_HEIGHT
* `WaitingState`: NONE, AWAITING_EXTERNAL, AWAITING_EMPLOYEE
* `Recordability` (E01-HLR-07): NOT_CLASSIFIED, FIRST_AID, MEDICAL_TREATMENT, RESTRICTED_DUTY, LOST_TIME, FATALITY
* `WorkRelated`: YES, NO, UNDER_REVIEW
* `SlaState`: ON_TRACK (green), AT_RISK (amber), BREACHED (red), MET, NONE
* `Intensity`: STANDARD, INTENSIVE
* `FitnessStatus`: FIT, FIT_WITH_RESTRICTIONS, TEMPORARILY_UNFIT, PERMANENTLY_UNFIT, UNDER_REVIEW, NOT_ASSESSED, UNFIT_PENDING

## 3. Case (kernel, extended)

Case reference format **`{UNIT}-{YYYY}-{NNNNNN}`** – unit = subsidiary code of the employee (e.g. `CAT-2026-000042`),
falling back to `OH`.

`CaseSummary` (queue row, clinical roles only):
```json
{ "id":"uuid","caseRef":"CAT-2026-000042","employeeId":"E1001","employeeName":"Erin Employee",
  "subsidiaryCode":"CAT","departmentCode":"CATERING","jobCode":"CATERING_ASSOC","locationCode":"LHR",
  "category":"INJURY","caseType":"INJURY","source":"EHS_INCIDENT","status":"TRIAGE",
  "urgency":"URGENT","safetyCritical":true,"safetyCategory":"FOOD_HANDLER",
  "ownerUsername":"nurse1","ownerName":"Nina Nurse","queueCode":"OH_GENERAL",
  "waitingState":"NONE","sla":{"milestone":"TRIAGE","state":"AT_RISK","dueAt":"…","minutesRemaining":12},
  "intensity":"STANDARD","complexityScore":35,"duplicateSuspect":false,"earlyIntervention":false,
  "recordability":"NOT_CLASSIFIED","openTasks":2,"createdAt":"…","updatedAt":"…" }
```
`CaseDetail` (OH roles) = CaseSummary + `costCentre, shift, incidentRef, bodyPart, occurredOn, language, templateCode,
hrisOverride (bool, flagged for review), clinicalSummary, intakeDetails (object, template answers), workRelated,
fitnessStatus, restrictionUntil, mergedIntoCaseId, links:[{type,ref,createdAt}], attachments:[{id,fileName,contentType,size,uploadedAt}],
disclosure`.
Status-only view (managers/HR/HSE/crew scheduling/hygiene) unchanged: `{id, caseRef, employeeDisplayName, status, fitnessStatus, restrictionUntil, updatedAt, disclosure}`.

| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/cases/{id}` | ABAC | detail or status-only by disclosure |
| GET | `/cases/{id}/header` | OH roles | E02-BL-018: `{caseRef, status, urgency, sla:{…}, safetyCritical, safetyCategory, badges:[…], openTasks:[Task], nextAction:"text", owner, queueCode, intensity, complexityScore}` |
| GET | `/cases/{id}/timeline` | OH roles | case events `[{at, type, summary, actor}]` (no clinical text) |
| GET | `/employees/{employeeId}/timeline` | OH roles | E01-BL-018: `{employee:{…}, items:[{at, kind: CASE|ENCOUNTER|ABSENCE|TRIGGER|INCIDENT, ref, title, status}]}` |
| POST | `/cases/{id}/transition` | OH roles | `{status}` – leaving INTAKE requires recordability classified (E01-F07) for INJURY/EXPOSURE/ILLNESS cases from incidents; closing a safety-critical case requires a fitness status (E02-F02) |
| POST | `/cases/{id}/fitness` | OH_CLINICIAN, OH_PHYSICIAN, AME, CMO, PSYCH_CLINICIAN | `{fitnessStatus, restrictionUntil?}` |
| POST | `/cases/{id}/attachments` | owner employee, OH roles | multipart `file` (JPEG/PNG/PDF, ≤ 5 MB, max 5 per case); stored encrypted |
| GET | `/cases/{id}/attachments/{attId}` | OH roles | binary download (audited) |
| POST | `/cases/{id}/links` | OH roles | `{type: INCIDENT|CASE|CLAIM|SURVEILLANCE|ENCOUNTER, ref}` |
| GET | `/cases/{id}/tasks` · POST `/cases/{id}/tasks` · POST `/tasks/{taskId}/complete` | OH roles | Task `{id, caseId, type: CALL_BACK|FOLLOW_UP|HANDOVER|REMINDER|CHECKLIST, title, dueAt, assignee, status: OPEN|DONE|CANCELLED}` |

## 4. E01 – Intake

### 4.1 Incident feed & rule engine (E01-F01)
| Method | Path | Roles | Body / result |
|---|---|---|---|
| POST | `/intake/ehs/incidents` | INTEGRATION | Webhook. Header `Idempotency-Key` required. Body `Incident` (below) or `{incidents:[Incident…]}`. Schema-invalid payloads → 202 with `{accepted:n, deadLettered:m}` and land in the DLQ with an alert. |
| POST | `/intake/ehs/excel` | OH_ADMIN, CASE_MANAGER, NURSING_HEAD, INTEGRATION | multipart (existing) |
| GET | `/intake/dlq` · POST `/intake/dlq/{id}/replay` · POST `/intake/dlq/{id}/discard` | OH_ADMIN, PLATFORM_ADMIN | DLQ items `{id, receivedAt, source, reason, status}` |
| GET | `/intake/rules` · POST `/intake/rules` · PUT `/intake/rules/{id}` | read: OH roles, HSE_OFFICER; write: OH_ADMIN, NURSING_HEAD | `IntakeRule` |
| POST | `/intake/rules/test` | OH_ADMIN, NURSING_HEAD | dry run: `{incident: Incident}` → `{ruleId, ruleName, action, caseType, urgency}` |
| GET | `/intake/suggestions` | OH roles | E01-BL-005: incidents whose rule action = SUGGEST and undecided `[ReconciliationRow]` |
| POST | `/intake/suggestions/{incidentId}/accept` | OH roles | → `{caseId, caseRef}` |
| POST | `/intake/suggestions/{incidentId}/dismiss` | OH roles | `{reasonCode: NOT_WORK_RELATED|NO_HEALTH_CONSEQUENCE|DUPLICATE|INSUFFICIENT_DATA|OTHER, note?}` |
| GET | `/intake/reconciliation?from&to&outcome&unit` | HSE_OFFICER, OH roles | E01-BL-004 `[ReconciliationRow]` |
| GET | `/intake/reconciliation/export?from&to` | HSE_OFFICER, OH roles | `text/csv` (formula-injection safe) |
| GET | `/intake/reconciliation/exceptions?from&to` | HSE_OFFICER, NURSING_HEAD | weekly exception report: ignored/no-match incidents with injury or lost time |
| GET | `/intake/dashboard?from&to&subsidiary` | NURSING_HEAD, OH_ADMIN, CMO | E01-BL-026 `{bySource:[{source,count}], byUnit:[{unit,open,overdue}], slaCompliance:{met, breached, pct}, backlog:{unassigned, overdue, awaitingTriage}, trend:[{date,count}]}` (small cells < k suppressed) |

`Incident` (webhook schema; `externalRef` and `occurredAt` mandatory):
```json
{ "externalRef":"INC-2026-0042","sourceSystem":"EHS","occurredAt":"2026-10-28T14:05:00Z","reportedAt":"…",
  "incidentClass":"INJURY_MEDICAL_TREATMENT","severity":"MEDIUM","lostTime":false,"exposureType":null,
  "bodyPart":"HAND","location":"LHR-T5","unitCode":"CAT","shift":"EARLY",
  "employeeId":"E1001","employeeName":"Erin Employee","orgUnitCode":"CATERING",
  "description":"free text (clinical-grade, encrypted)", "reporterEmail":"hse.officer@…" }
```
`incidentClass` vocabulary: INJURY_FIRST_AID, INJURY_FIRST_AID_PLUS, INJURY_MEDICAL_TREATMENT, LOST_TIME_INJURY,
OCCUPATIONAL_ILLNESS, EXPOSURE, BODY_FLUID_EXPOSURE, FUME_EVENT, HEAT_ILLNESS, NEAR_MISS, PROPERTY_DAMAGE, OTHER.

`IntakeRule`:
```json
{ "id":1,"name":"Lost-time injuries","priority":10,"active":true,
  "incidentClass":"LOST_TIME_INJURY","minSeverity":null,"lostTime":null,"exposureType":null,"unitCode":null,"bodyPart":null,
  "action":"AUTO_CREATE","caseType":"INJURY","urgency":"URGENT","version":3,"updatedBy":"…","updatedAt":"…" }
```
Null condition = wildcard; lowest `priority` wins; `action` ∈ AUTO_CREATE | SUGGEST | IGNORE; no match → NO_MATCH (visible in reconciliation).

`ReconciliationRow`: `{incidentId, externalRef, occurredAt, incidentClass, severity, lostTime, unitCode, location, outcome: AUTO_CREATED|SUGGESTED|ACCEPTED|DISMISSED|IGNORED|NO_MATCH|PENDING, ruleName, caseId, caseRef, caseStatus, dismissReason, decidedBy}` – no clinical text.
`GET /intake/incidents/{incidentId}/cases` (HSE_OFFICER, OH roles) – E01-BL-020 multi-casualty: `[{caseRef, employeeDisplayName, status, fitnessStatus}]` status only.

### 4.2 Referral forms & templates (E01-F02)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/intake/templates?audience=EMPLOYEE|MANAGER|HR&lang=en` | authenticated | `[Template]` |
| GET | `/intake/body-map` | authenticated | `{regions:[{code,label,side:FRONT|BACK}]}` |
| POST | `/intake/referrals/self` | EMPLOYEE | `SelfReferral` → `{caseId, caseRef, acknowledgement}` |
| POST | `/intake/referrals/manager` | MANAGER | `ManagerReferral` (employee must be in manager's org scope) → `{caseRef}` |
| POST | `/intake/referrals/hr` | HR | `HrReferral` → `{caseRef}` |
| GET | `/intake/employees/lookup?q=` | MANAGER, HR, OH roles, CLINIC_RECEPTION, FIRST_AIDER | `[{employeeId, displayName, departmentCode, jobCode}]` (manager/HR: own scope only; max 20) |
| GET | `/languages` | authenticated | `[{code:"en",label:"English"},{code:"ar",label:"العربية"},{code:"fr",label:"Français"}]` |

`Template`: `{code:"CATERING", title, audience, departments:["CATERING"], fields:[{key, label, type: TEXT|TEXTAREA|SELECT|MULTISELECT|BOOLEAN|DATE|BODY_MAP|SYMPTOMS, required, options:[{value,label}], maxLength}], routingQueue, symptomOptions:[{value,label}]}`.
Seeded templates (E01-BL-009): CATERING, RAMP_CARGO, DUTYFREE_DISTRIBUTION, ENGINEERING, CORPORATE, CREW, plus MANAGER_WORK_IMPACT, HR_ABSENCE.
Labels returned in the requested language (`en`, `ar`, `fr`; fallback `en`).

`SelfReferral`: `{templateCode, category: CaseCategory, description (≤4000), bodyParts:["HAND_R",…], symptoms:["PAIN",…], answers:{key:value}, preferredContact, language, consent:{assessment:true, purposes:["TREATMENT_SHARING_PARTNER"]}}` – then `POST /cases/{id}/attachments` for photos.
`ManagerReferral`: `{employeeId, dutiesAffected (≤1000), startDate, safetyConcern: boolean, safetyConcernDetail (≤1000), adjustmentsTried (≤1000), questionsForOh (≤1000)}` – **work-impact only**: any field that looks like a diagnosis / clinical opinion is rejected with `422 code=clinical_content_not_allowed`.
`HrReferral`: `{employeeId, absenceSummary, policyStage: INFORMAL|STAGE_1|STAGE_2|FINAL, absenceDaysLast12Months, adviceQuestions:[…]}` (same clinical-content guard).

### 4.3 Walk-in / emergency (E01-F03)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/intake/employees/by-badge/{badgeId}` | CLINIC_RECEPTION, FIRST_AIDER, OH roles | `{employeeId, displayName, departmentCode, jobCode, safetyCritical}` |
| POST | `/intake/encounters` | CLINIC_RECEPTION, FIRST_AIDER, OH roles | `{clientRef (uuid, idempotency for offline retry), mode: WALK_IN|EMERGENCY, employeeId, occurredAt, siteCode, location, chiefComplaint, actionTaken}` → Encounter |
| PATCH | `/intake/encounters/{id}` | same | enrichment `{chiefComplaint?, actionTaken?, vitals?, notes?}` |
| GET | `/intake/encounters?status=OPEN|CONVERTED&site=` | OH roles, CLINIC_RECEPTION | `[Encounter]` (reception sees no clinical fields) |
| POST | `/intake/encounters/{id}/convert` | OH roles | → `{caseId, caseRef}`; case type defaults by chief complaint |
`Encounter`: `{id, clientRef, mode, employeeId, employeeName, occurredAt, siteCode, location, chiefComplaint, actionTaken, status: OPEN|ENRICHED|CONVERTED|CLOSED, enrichmentDue (bool, emergency mode), caseId, caseRef, createdBy}`.

### 4.4 System triggers (E01-F04)
| Method | Path | Roles | Notes |
|---|---|---|---|
| POST | `/intake/absence-episodes` | INTEGRATION | `{sourceRef, employeeId, startDate, endDate, reasonCategory}` (Idempotency via sourceRef). Threshold per subsidiary: config `intake.absence.<SUB>.consecutive-days` (default 3, i.e. > 3 days) and `…episodes-in-90-days` (default 3) → watch case `ABSENCE`, `earlyIntervention=true`, history linked |
| POST | `/intake/triggers/surveillance-result` | INTEGRATION | `{sourceRef, employeeId, testCode: AUDIOMETRY|SPIROMETRY|TITRE|…, abnormal: bool, value?, unit?}` → SURVEILLANCE_FOLLOW_UP case to physician queue |
| POST | `/intake/triggers/fitness-declaration` | EMPLOYEE (crew) | `{reason: ILLNESS|INJURY|MEDICATION|FATIGUE|PREGNANCY|OTHER, unfitFrom, expectedUntil?, details?}` → FITNESS case, fitness = UNFIT_PENDING, crew-scheduling status feed, AME queue |
| POST | `/intake/triggers/da-test` | INTEGRATION | `{sourceRef, employeeId, result: POSITIVE|REFUSED|NEGATIVE}` → SUBSTANCE (restricted) case for POSITIVE/REFUSED |
| GET | `/intake/triggers?type=&from&to` | OH roles | trigger log |
Repeat body-part visits: ≥ 3 cases with the same body part for the employee in 365 days → badge `REPEAT_BODY_PART` + complexity driver.

### 4.5 Duplicates & linking (E01-F06)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/cases/{id}/duplicates` | OH roles | candidates `[{caseId, caseRef, status, createdAt, score, reasons:["SAME_EMPLOYEE","DATE_WITHIN_2_DAYS","SAME_BODY_PART","SAME_INCIDENT"]}]` |
| POST | `/cases/{id}/merge` | OH roles | `{intoCaseId, reason}` – source case → MERGED, sources/links/attachments retained on target, audit |
| POST | `/cases/{id}/links` | OH roles | link instead of merge (see §3) |

### 4.6 Recordability (E01-F07)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/intake/recordability/wizard` | OH roles, HSE_OFFICER | `{questions:[{key, text, help, type:BOOLEAN|NUMBER}], definitions:{FIRST_AID:"…",…}, reasonCodes:[…]}` |
| POST | `/cases/{id}/recordability` | OH_CLINICIAN, OH_PHYSICIAN, AME, CMO, NURSING_HEAD | `{answers:{fatality, daysAwayFromWork, restrictedWorkOrTransfer, medicalTreatmentBeyondFirstAid, lossOfConsciousness, significantDiagnosedCondition}, workRelated, override?:Recordability, reasonCode? (required when changing an existing classification or overriding), rationale}` → `{recordability, computed, workRelated, localCategory, caaReportable, version}` |
| GET | `/cases/{id}/recordability/history` | OH roles | versions |
| GET | `/intake/recordability/report?from&to` | NURSING_HEAD, CMO, HSE_OFFICER | counts by unit × class (k-suppressed) |
| GET/PUT | `/intake/recordability/mapping` | read OH roles; write OH_ADMIN | E01-BL-022 local legal mapping `[{recordability, localCategory, caaReportable, description}]` |

### 4.7 Notifications & SLA (E01-F08)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET/PUT | `/intake/notification-matrix` | read OH_ADMIN, NURSING_HEAD; write OH_ADMIN | `[{id, event: CASE_OPENED|CASE_ASSIGNED|SLA_AT_RISK|SLA_BREACHED|ESCALATED, source?, caseType?, recipient: OH_QUEUE|CASE_OWNER|HSE_REPORTER|LINE_MANAGER|CREW_SCHEDULING|NURSING_HEAD|EMPLOYEE|HYGIENE_MANAGER, channel: EMAIL|SMS|PUSH, templateKey, active}]` |
All notification texts are content-free (case number, unit, urgency, next step, contact) – enforced by the notification content rules.

## 5. E02 – Triage, queues, assignment, SLA

### 5.1 Protocols & triage (E02-F01, F07)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/triage/protocols` | OH roles | `[{code, version, title, presentation, status}]` (≥ 12 protocols, DRAFT until CMO approves) |
| GET | `/triage/protocols/{code}` | OH roles | `Protocol` (latest approved, else latest draft) |
| POST | `/triage/protocols/{code}/approve` | CMO | approve a version |
| POST | `/cases/{id}/triage` | OH_CLINICIAN, OH_PHYSICIAN, AME, CMO, NURSING_HEAD | `TriageRequest` → `TriageResult` |
| GET | `/cases/{id}/triage` | OH roles | assessments history |
| GET | `/triage/advice/{adviceKey}?lang=` | OH roles | advice text |
| POST | `/cases/{id}/advice` | OH roles | `{adviceKey}` → sends content-free "advice available" notification; advice visible in employee portal `GET /me/advice` (EMPLOYEE) |

`Protocol`:
```json
{ "code":"MSK","version":1,"title":"Musculoskeletal pain / injury","presentation":"MSK","status":"DRAFT",
  "start":"q1",
  "questions":[
    {"id":"q1","text":"Is there obvious deformity, loss of pulse or numbness below the injury?","type":"BOOLEAN","redFlag":true,
     "onYes":{"outcome":"EMERGENCY"}, "onNo":{"next":"q2"}},
    {"id":"q2","text":"Pain score 0–10","type":"NUMBER","min":0,"max":10,
     "rules":[{"if":">=8","outcome":"URGENT"},{"if":">=4","next":"q3"},{"else":true,"next":"q3"}]},
    {"id":"q3","text":"Able to perform normal duties?","type":"BOOLEAN","onYes":{"outcome":"ROUTINE"},"onNo":{"outcome":"SEMI_URGENT"}}],
  "outcomes":{"EMERGENCY":{"disposition":"Call emergency services / airside medical response now","adviceKey":"EMERGENCY_GENERIC"},
              "URGENT":{"disposition":"Same-day physician review","adviceKey":"MSK_URGENT"}, "SEMI_URGENT":{…}, "ROUTINE":{…}, "ADVICE":{…}} }
```
`TriageRequest`: `{protocolCode, protocolVersion, mode: IN_PERSON|TELEPHONE|DIGITAL, answers:{q1:false,q2:6,…}, overrideUrgency?, overrideReason? (required with override), call?:{caller, phone, startedAt, endedAt, consentToRemoteAssessment:true, callBackAt?}}`
`TriageResult`: `{assessmentId, computedUrgency, finalUrgency, redFlagsTriggered:[qid], disposition, adviceKey, protocolVersion, caseType, queueCode, ownerUsername, sla:{…}, callBackTaskId?}`
Server re-evaluates the protocol (client answers only) – the client never decides urgency. Red-flag hard stop = EMERGENCY.

### 5.2 Safety-critical routing (E02-F02)
* Job code → `SafetyCategory` from `masterdata.job_code.safety_category`; flag set at case creation; FLIGHT_CREW/CABIN_CREW → `AME_AEROMEDICAL` queue, FOOD_HANDLER → `FOOD_SAFETY` queue (physician), others → `PHYSICIAN` queue; urgency raised at least to SEMI_URGENT.
* `GET /cases/{id}/checklists` / `POST /cases/{id}/checklists/{code}` – FOOD_HANDLER (`GI_SYMPTOMS, SKIN_LESIONS, INFECTIONS, EXCLUSION_DECISION`), DRIVING (`VISION, SEIZURE_HISTORY, SEDATING_MEDICATION, FITNESS_TO_DRIVE`), HEIGHT (`VERTIGO, BALANCE, FITNESS_FOR_HEIGHT`) – `{answers:{…}, outcome: CLEAR|EXCLUDED|RESTRICTED}`. Checklist mandatory before closure for the category.
* `GET /status/crew?employeeIds=E1,E2` (CREW_SCHEDULING) and `GET /status/food-handlers?department=CATERING` (HYGIENE_MANAGER): `[{employeeId, displayName, fitnessStatus: FIT|UNFIT|FIT_WITH_LIMITATIONS|PENDING, restrictionUntil, updatedAt}]` – every read audited.

### 5.3 Queues & SLA board (E02-F04)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/queues/counts` | OH roles | `{MY:3, TEAM:12, UNASSIGNED:4, OVERDUE:2, AWAITING_EXTERNAL:1, AWAITING_EMPLOYEE:0, ESCALATED:1}` |
| GET | `/queues/{queue}?page=0&size=50&urgency=&subsidiary=&caseType=&safetyCritical=&q=` | OH roles | `{items:[CaseSummary], total, page, size}`; queue ∈ MY, TEAM, UNASSIGNED, OVERDUE, AWAITING_EXTERNAL, AWAITING_EMPLOYEE, ESCALATED; sorted by SLA due |
| GET/POST/DELETE | `/queues/views`, `/queues/views/{id}` | OH roles | saved personal views `{id, name, queue, filters:{…}}` |
| POST | `/queues/bulk` | OH roles (reassign: NURSING_HEAD) | `{action: REASSIGN|REMIND|CLOSE_DUPLICATES, caseIds:[…], toUsername?}` → `{succeeded:[…], failed:[{caseId, reason}]}` |
| POST | `/cases/{id}/waiting-state` | OH roles | `{waitingState, note?}` (SLA pauses while AWAITING_*) |
| GET | `/sla/policies` · PUT `/sla/policies/{id}` | read OH roles; write NURSING_HEAD, OH_ADMIN | `{id, caseType?, urgency, milestone: TRIAGE|FIRST_CONTACT|ESCALATION_RESPONSE, targetMinutes, amberPercent, escalateToRole}` |
Default SLA (E02 KPI): triage EMERGENCY 15 min, URGENT 60 min, SEMI_URGENT 4 h, ROUTINE 1 working day (8 h), ADVICE 2 working days; amber at 75 %; breach → notify Nursing Head + `ESCALATED` flag.

### 5.4 Assignment & caseload (E02-F03)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET/POST/PUT | `/assignment/rules[/{id}]` | read OH roles; write NURSING_HEAD, OH_ADMIN | `{id, name, priority, active, subsidiaryCode?, siteCode?, language?, caseType?, safetyCategory?, specialty?, targetQueue, strategy: NONE|ROUND_ROBIN|LEAST_LOADED|SPECIFIC_USER, targetUsername?}` |
| POST | `/assignment/rules/test` | NURSING_HEAD, OH_ADMIN | `{caseId}` → dry run `{ruleId, queue, username, explanation}` |
| GET | `/assignment/caseload` | NURSING_HEAD, OH roles | `[{username, displayName, roles, caseloadCap, openCases, urgentCases, outOfOfficeUntil, coverUsername, utilisationPct}]` |
| POST | `/cases/{id}/assign` | NURSING_HEAD (any), OH roles (self-assign / to colleague) | `{toUsername?, queueCode?, reason}` – notification to new owner, history kept |
| GET | `/cases/{id}/assignments` | OH roles | history |
| PUT | `/staff/{username}/availability` | self or NURSING_HEAD | `{outOfOfficeUntil, coverUsername}` |
| GET | `/staff` | OH roles | staff directory `[{username, displayName, roles, sites, languages, specialties, caseloadCap, active}]` |
| POST | `/cases/{id}/handover` | OH roles | E02-BL-019 `{toUsername, summary, outstandingActions, nextContactAt}` → HANDOVER task for receiver |
| POST | `/handovers/{id}/acknowledge` | receiving user | |
| GET | `/handovers/pending` | OH roles | mine |

### 5.5 Escalation (E02-F05)
| Method | Path | Roles | Notes |
|---|---|---|---|
| POST | `/cases/{id}/escalations` | OH roles | `{level: PHYSICIAN|CMO|EXTERNAL_SPECIALIST|EMERGENCY_SERVICES, reasonCode: COMPLEX_CLINICAL|MEDICO_LEGAL|CREW_FITNESS|SAFETY_CRITICAL|MENTAL_HEALTH_RISK|SECOND_OPINION|OTHER, note}` → Escalation with response SLA (PHYSICIAN 4 h, CMO 1 working day, EMERGENCY immediate) |
| GET | `/escalations?status=OPEN&mine=true` | OH roles | |
| POST | `/escalations/{id}/acknowledge` | OH_PHYSICIAN, AME, CMO | |
| POST | `/escalations/{id}/decision` | OH_PHYSICIAN, AME, CMO | `{decision}` (captured on timeline as "Escalation decided") |
| POST | `/cases/{id}/conferences` · GET | OH roles (CMO schedules) | `{scheduledAt, attendees:[{name, role}], agenda}`; `POST /conferences/{id}/minutes` `{minutes, decisions, visibility: CLINICAL_ONLY|ALL_ATTENDEES}` |

### 5.6 Complexity / risk score (E02-F06)
* `GET /cases/{id}/complexity` → `{score 0–100, intensity, drivers:[{code, label, points}], calculatedAt, expectedDurationDays?, actualAbsenceDays?}`; `POST /cases/{id}/complexity/recalculate`; inputs `PUT /cases/{id}/risk-flags` `{comorbidity, psychosocialScreenPositive, claimOrLitigation, primaryIcd10?}`.
* Weights in config `triage.complexity.weights` (JSON); threshold `triage.complexity.intensive-threshold` (default 60) → intensity INTENSIVE + case event.
* `GET /triage/complexity/calibration?month=2026-10` (NURSING_HEAD) → distribution per band vs. outcomes.
* Duration guideline reference: `GET /triage/duration-guidelines?icd=` (in-house table; licensed content pluggable).

### 5.7 Triage quality audit (E02-BL-016)
* `POST /triage/audit/sample?from&to&percent=10` (NURSING_HEAD) → creates samples; `GET /triage/audit/samples`; `POST /triage/audit/samples/{id}/review` `{agree: bool, comment}`; `GET /triage/audit/report` → `{reviewed, agreementRate}`.

## 6. Events (outbox, in-process)
`case.opened.v1` (kernel) → E02 routing/assignment/SLA start, E01 notification matrix · `case.assigned.v1` · `case.triaged.v1` ·
`sla.at-risk.v1` · `sla.breached.v1` · `case.escalated.v1` · `fitness.status.changed.v1` → `crew-rostering`, `hr-systems`,
`catering-hygiene` (status only, allow-listed).

## 7. Seed / demo data (local profile)
Employees E1001 Erin Employee (CAT, CATERING, CATERING_ASSOC – food handler, badge B-1001), E1002 Paul Pilot (FLT, FLIGHTOPS, PILOT), E1003 Cara Crew (CAB, CABIN, CABIN_CREW), E1004 Rami Ramp (GND, GROUND, RAMP_AGENT – airside driver), E1005 Olivia Office (CORP, …).
Staff: nurse1 (OH_CLINICIAN, en/ar), nurse2 (OH_CLINICIAN, en/fr), physician1 (OH_PHYSICIAN), ame1 (AME), cmo1 (CMO), nursinghead1 (NURSING_HEAD).
