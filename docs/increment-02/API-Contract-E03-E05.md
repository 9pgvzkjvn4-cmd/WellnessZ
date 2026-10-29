# Increment 2 – E03 OH Record & E05 Fitness for Duty / RTW: API and Domain Contract

Source of truth shared by the backend (`platform/wz-documents`, kernel / security / masterdata extensions,
`modules/wz-e03-record`, `modules/wz-e05-fitness`) and the UIs (`web/oh-workspace`, `web/employee-app`,
`web/manager-workspace`). Conventions are unchanged from increment 1: paths relative to `/api/v1` (browser apps call
`/bff/api/...`), RFC 9457 problem details `{type,title,status,detail,code,correlationId}`, ISO-8601 UTC timestamps,
`YYYY-MM-DD` dates, plain JSON arrays unless a page object is stated. Denied access to a record is reported as `404`.

Confidentiality rule (both epics): **managers, HR, crew scheduling, catering hygiene and claims roles see fitness /
restriction status and functional restrictions only – never diagnoses, clinical notes, results or encounter content.**

## 1. Roles

New role: `DPO` (data protection officer – break-glass review, disclosure and retention reports; no clinical read).
"OH roles" = `OH_CLINICIAN, CASE_MANAGER, OH_PHYSICIAN, AME, CMO, NURSING_HEAD, PSYCH_CLINICIAN` (as in increment 1).
"Clinicians" = OH roles minus `CASE_MANAGER`. "Physicians" = `OH_PHYSICIAN, AME, CMO`.
`CLINIC_ASSISTANT` is **not** a new role: clinic assistants use `CLINIC_RECEPTION` for scanning (E03-BL-016).

## 2. Enumerations

* `EncounterType`: INJURY, ILLNESS, FITNESS_ASSESSMENT, SURVEILLANCE_EXAM, MENTAL_HEALTH, TRAVEL, CREW_AEROMEDICAL, FOOD_HANDLER
* `EncounterStatus`: DRAFT, SIGNED (immutable; changes only through addenda), ENTERED_IN_ERROR
* `ProblemStatus`: ACTIVE, RESOLVED, INACTIVE · `AllergyCriticality`: LOW, HIGH, UNABLE_TO_ASSESS · `AllergyCategory`: MEDICATION, FOOD, ENVIRONMENT, BIOLOGIC
* `MedicationStatus`: ACTIVE, COMPLETED, STOPPED · `MedicationSource`: REPORTED (by employee), ADMINISTERED (given in clinic)
* `CodeSystem` (existing): SNOMED_CT, ICD_10, ICD_11, LOINC, ODH
* `OrderCategory`: LAB, IMAGING, AUDIOMETRY, SPIROMETRY, VISION, ECG · `OrderStatus`: DRAFT, QUEUED, SENT, PARTIAL, RESULTED, CANCELLED
* `ResultFlag`: NORMAL, LOW, HIGH, ABNORMAL, CRITICAL · `ResultStatus`: PRELIMINARY, FINAL, AMENDED
* `DocumentType`: FITNESS_CERTIFICATE, RESTRICTION_NOTE, REFERRAL_LETTER, CABIN_CREW_MEDICAL_REPORT, CREW_MEDICAL_REPORT, FOOD_HANDLER_CLEARANCE, RESULT_LETTER, RTW_PLAN, JOB_OFFER, IHR_DECISION_LETTER, EXTERNAL_REPORT, PHOTO, OTHER
* `DocumentStatus`: DRAFT, SIGNED, SUPERSEDED, REVOKED · `ShareTarget`: EMPLOYEE, MANAGER, HR, PARTNER
* `FitnessOutcome` (E05): FIT, FIT_WITH_LIMITATIONS, TEMPORARILY_UNFIT, PERMANENTLY_UNFIT, PENDING
  → kernel `FitnessStatus` mapping: FIT→FIT, FIT_WITH_LIMITATIONS→FIT_WITH_RESTRICTIONS, TEMPORARILY_UNFIT→TEMPORARILY_UNFIT,
  PERMANENTLY_UNFIT→PERMANENTLY_UNFIT **only after CMO sign-off** (UNDER_REVIEW until then), PENDING→UNDER_REVIEW
* `DecisionStatus`: FINAL, AWAITING_SIGNOFF, SUPERSEDED, REJECTED
* `RosterStatus` (crew feed, status only): FIT, UNFIT, LIMITED, PENDING
* `FoodHandlerStatus`: FIT_TO_HANDLE_FOOD, EXCLUDED, RETURN_PENDING_CLEARANCE
* `RtwPlanStatus`: DRAFT, ACTIVE, COMPLETED, CANCELLED · `OfferStatus`: ISSUED, ACCEPTED, DECLINED, WITHDRAWN, EXPIRED
* `ProposalStatus` (modified duty): PROPOSED, ACCEPTED, DECLINED, ESCALATED_TO_HR, WITHDRAWN
* `RestrictionInstanceStatus`: ACTIVE, EXPIRING (≤ 5 days), LAPSED, CLOSED
* `IhrType`: ILL_HEALTH_RETIREMENT, REDEPLOYMENT · `IhrStatus`: DRAFT, AWAITING_CMO, DECIDED, APPEALED, APPEAL_DECIDED, CLOSED
* `BreakGlassReview`: PENDING, APPROPRIATE, INAPPROPRIATE

## 3. Platform additions (all modules)

### 3.1 Master data (E18) – fitness standards, restrictions, job demands
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/masterdata/fitness-standards` | authenticated (OH roles see all fields) | `[FitnessStandard]` (active versions) |
| PUT | `/masterdata/fitness-standards/{code}` | OH_ADMIN, CMO | creates a new version; body = FitnessStandard minus `version` |
| GET | `/masterdata/restrictions` | authenticated | `[Restriction]` – restriction catalogue (functional wording, no diagnosis) |
| PUT | `/masterdata/restrictions/{code}` | OH_ADMIN, NURSING_HEAD | upsert |
| GET | `/masterdata/job-demands?unit=` | OH roles, MANAGER, HR | `[JobDemandProfile]` |
| PUT | `/masterdata/job-demands/{jobCode}` | OH_ADMIN, NURSING_HEAD | upsert one profile |
| POST | `/masterdata/job-demands/import` | OH_ADMIN | JSON array of profiles (bulk load of the "top 150 job codes") → `{upserted}` |

`FitnessStandard`: `{code:"CAA_CLASS1", title:"Pilot – Part-MED Class 1", roleCategory:"FLIGHT_CREW", source:"EASA Part-MED MED.B / local CAA", referenceUrl, validityMonths:12, validityMonthsOver40:6, requiresSignoffRole:"AME", limitationCodes:["TML","VDL",…], version:1, status:"ACTIVE", updatedBy, updatedAt}` – `requiresSignoffRole` ∈ AME | CMO | null.
Seeded codes: CAA_CLASS1, CAA_CLASS2, CABIN_CREW, AIRSIDE_DRIVING, FORKLIFT, WORKING_AT_HEIGHT, FOOD_HANDLER, RESPIRATOR, NIGHT_WORK, VDU, SECURITY_SCREENING, GENERAL_DUTIES.
`Restriction`: `{code:"LIFT_MAX_KG", label:"No lifting above {value} kg", dimension:"LIFT_KG", valueRequired:true, unit:"kg", active:true}` – dimensions: LIFT_KG, PUSH_PULL_KG, CLIMB, AIRSIDE_DRIVE, NIGHT, KNIFE, CHEMICAL, RESPIRATOR, NOISE, HEAT, HEIGHT, FLIGHT_DUTY, FOOD_HANDLING, HOURS_PER_DAY, SCREEN_HOURS, SAFETY_CRITICAL.
`JobDemandProfile`: `{jobCode, title, unit:"GND", demands:{LIFT_KG:23, PUSH_PULL_KG:30, CLIMB:true, AIRSIDE_DRIVE:true, NIGHT:true, KNIFE:false, CHEMICAL:false, RESPIRATOR:false, NOISE:true, HEAT:true, HEIGHT:false, FLIGHT_DUTY:false, FOOD_HANDLING:false, HOURS_PER_DAY:10, SCREEN_HOURS:1, SAFETY_CRITICAL:true}, sitting:"LOW|MEDIUM|HIGH", validatedBy, validatedAt}`.

### 3.2 Documents (`platform/wz-documents`, E03-F06, used by E03 and E05)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/documents/templates` | OH roles, HR | `[{code, type, title, mergeFields:[…], audience, containsClinical:boolean}]` |
| POST | `/documents/generate` | OH roles (HR for JOB_OFFER only via E05) | `{templateCode, employeeId, caseId?, fields:{…}}` → `Document` (DRAFT, PDF rendered) |
| POST | `/documents/{id}/sign` | Clinicians (any signer allowed by template) | e-signature: `{}` → `Document` (SIGNED, SHA-256 of PDF recorded with signer + timestamp; later changes create a new version) |
| POST | `/documents/{id}/new-version` | OH roles | `{fields}` → new DRAFT version, previous becomes SUPERSEDED when the new one is signed |
| POST | `/documents/{id}/share` | OH roles | `{target: EMPLOYEE|MANAGER|HR|PARTNER, partnerOrg?, consentId? (required for PARTNER)}` → `Share`. Only templates with `containsClinical=false` may be shared with MANAGER/HR. Every share writes the disclosure log. |
| DELETE | `/documents/{id}/shares/{shareId}` | OH roles | revoke |
| GET | `/documents?employeeId=&caseId=&type=` | OH roles | `[Document]` (metadata) |
| GET | `/documents/search?employeeId=&q=` | OH roles | full-text over generated and scanned (OCR/text-layer) content → `[{document, snippet}]` |
| GET | `/documents/{id}` | OH roles; employee/manager/HR/partner only if shared to them | metadata |
| GET | `/documents/{id}/content` | same | `application/pdf` (or original image); audited download |
| POST | `/documents/scan` | OH roles, CLINIC_RECEPTION | multipart `file` (PDF/JPEG/PNG ≤ 10 MB) + `employeeId`, `caseId?`, `documentType?` → `Document` (EXTERNAL_REPORT, text extracted, type auto-classified when omitted) |
| GET | `/me/documents` | EMPLOYEE | documents shared to me `[Document]` |
| POST | `/me/documents/{id}/acknowledge` | EMPLOYEE | acknowledgement of a certificate / RTW plan / restriction note |
| GET | `/workspace/documents` | MANAGER, HR | documents shared to me (scope-checked) `[Document]` |
| GET | `/partner/documents` | PARTNER_PROVIDER | documents shared to my partner org **while the employee's consent is active** |
| GET | `/documents/retention` · PUT `/documents/retention/{recordClass}` | DPO, OH_ADMIN (PUT: DPO) | retention schedule `[{recordClass, retentionYears, trigger: LAST_ENCOUNTER|DOCUMENT_DATE|EMPLOYMENT_END, legalBasis, approvedBy, approvedAt}]` |
| GET | `/documents/retention/due` | DPO | records past retention and not on legal hold |
| POST | `/documents/{id}/legal-hold` · DELETE `/documents/{id}/legal-hold` | DPO | `{reason}` |

`Document`: `{id, documentNumber:"DOC-2026-000123", type, templateCode, title, employeeId, caseId, version, status, contentType, size, sha256, signedBy, signedAt, createdBy, createdAt, legalHold, shares:[{id,target,partnerOrg,sharedAt,sharedBy,revokedAt}], acknowledgedAt}` – for EMPLOYEE/MANAGER/HR callers `shares`, `createdBy` are omitted.
Seeded templates: FITNESS_CERTIFICATE, RESTRICTION_NOTE, REFERRAL_LETTER, CABIN_CREW_MEDICAL_REPORT, CREW_MEDICAL_REPORT, FOOD_HANDLER_CLEARANCE, RESULT_LETTER, RTW_PLAN, JOB_OFFER, IHR_DECISION_LETTER. Manager/HR-shareable (no clinical content, merge fields screened by the clinical-text guard): FITNESS_CERTIFICATE, RESTRICTION_NOTE, FOOD_HANDLER_CLEARANCE, RTW_PLAN, JOB_OFFER.

### 3.3 Segregation, break-glass and disclosure log (E03-F08, platform security)
| Method | Path | Roles | Notes |
|---|---|---|---|
| POST | `/break-glass` | Clinicians | `{employeeId, reason (≥ 20 chars)}` → `{id, employeeId, grantedTo, expiresAt (now+4h), reason}`; alerts DPO, creates a 24-h review task |
| GET | `/break-glass?status=PENDING` | DPO | `[{id, employeeId, grantedTo, reason, grantedAt, expiresAt, review, reviewedBy, reviewedAt, reviewDueAt}]` |
| POST | `/break-glass/{id}/review` | DPO | `{review: APPROPRIATE|INAPPROPRIATE, note}` |
| GET | `/break-glass/report?month=2026-10` | DPO, CMO | `{month, total, appropriate, inappropriate, pending, overdueReviews, byUser:[{username,count}]}` |
| GET | `/me/disclosures` | EMPLOYEE | `[{at, recipientType, recipientName, documentTitle, purpose, consentId, status: ACTIVE|REVOKED}]` |
| GET | `/disclosures?employeeId=` | DPO, OH roles | same rows + `disclosedBy` |
| DELETE | `/consents/{id}` | EMPLOYEE (existing) | withdrawal now also revokes partner shares made under that consent; the effect is written to the disclosure log |

Restricted-class records (mental-health encounters, psychological cases) remain visible only to PSYCH_CLINICIAN and
the data subject, **unless** the caller holds an active break-glass grant for that employee. HR and managers can never
open the chart, encounters, results or clinical documents (negative tests, E03-BL-020); every HR read of status data
is audited.

### 3.4 Kernel additions
* `OhCase.primaryDiagnosis` `{system, code, display}` – set by E03 when a diagnosis is coded with `primary=true`.
* Closing a clinical case requires at least one coded diagnosis (`422 code=diagnosis_required`, E03-F03) – applies to
  case types INJURY, ILLNESS, EXPOSURE, FITNESS, MENTAL_HEALTH, SURVEILLANCE_FOLLOW_UP.
* SPI `ChartContributor` – modules contribute sections to the chart summary (E05 contributes fitness and restrictions).
* `CaseEvents.FitnessStatusChanged` gains `validFrom`, `reviewDate`, `operationalLimitations` (crew only), `messageId`.

## 4. E03 – Occupational health record & clinical encounters (`modules/wz-e03-record`, schema `ohrecord`)

### 4.1 Chart (E03-F01)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/employees/{employeeId}/chart` | OH roles (ABAC; restricted items hidden unless allowed) | `ChartSummary` (< 2 s) |
| GET · POST | `/employees/{employeeId}/problems` · PUT `/problems/{id}` | read OH roles; write Clinicians | `Problem` |
| GET · POST | `/employees/{employeeId}/allergies` · PUT `/allergies/{id}` | same | `Allergy` |
| GET · POST | `/employees/{employeeId}/medications` · PUT `/medications/{id}` | same | `Medication`; POST runs CDS (§4.5) |
| GET · POST | `/employees/{employeeId}/immunisations` | same | `Immunisation` |
| GET | `/employees/{employeeId}/jobs` | OH roles | ODH job history; current job auto-populated from HRIS + exposures from the job catalogue hazards |
| POST | `/employees/{employeeId}/jobs` · PUT `/jobs/{id}` | Clinicians | manual additions / corrections |
| GET | `/employees/{employeeId}/odh` | OH roles | FHIR R4 `Bundle` (collection) of ODH `Observation`s (PastOrPresentJob, UsualWork, exposures) |
| GET | `/employees/{employeeId}/fhir` | OH roles | FHIR R4 `Bundle` of Patient, Condition, AllergyIntolerance, MedicationStatement, Immunization, Observation, DocumentReference (FHIR mapping of the OH data model) |
| GET | `/employees/{employeeId}/record-timeline` | OH roles | `[{at, kind: ENCOUNTER|RESULT|DOCUMENT|PROBLEM|FITNESS|CASE, ref, title}]` |

`ChartSummary`:
```json
{ "employee": {"employeeId":"E1004","displayName":"Rami Ramp","dateOfBirth":null,"subsidiaryCode":"GND","departmentCode":"GROUND",
               "jobCode":"RAMP_AGENT","jobTitle":"Ramp agent","locationCode":"LHR","safetyCategory":"AIRSIDE_DRIVER"},
  "alerts": [{"kind":"ALLERGY","severity":"HIGH","text":"Penicillin – anaphylaxis"}, {"kind":"OVERDUE_SURVEILLANCE","severity":"MEDIUM","text":"Audiometry overdue since 2026-09-01"}],
  "fitness": {"status":"FIT_WITH_RESTRICTIONS","standardCode":"AIRSIDE_DRIVING","validUntil":"2026-12-01","reviewDate":"2026-11-15","decidedBy":"physician1"},
  "restrictions": [{"code":"LIFT_MAX_KG","label":"No lifting above 10 kg","validUntil":"2026-11-30","status":"ACTIVE"}],
  "openCases": [{"id":"uuid","caseRef":"GND-2026-000001","status":"ASSESSMENT","caseType":"INJURY"}],
  "surveillanceDue": [{"hazardCode":"NOISE","programme":"Audiometry","dueDate":"2026-09-01","overdue":true}],
  "immunisations": [{"vaccine":"Hepatitis B","date":"2024-03-01","status":"COMPLETE"}],
  "counts": {"problems":2,"medications":1,"allergies":1,"results":4,"unacknowledgedAbnormal":1,"documents":3,"unsignedEncounters":0},
  "restrictedItemsHidden": 0 }
```
`Problem`: `{id, employeeId, code:{system,code,display}, status, onsetDate, resolvedDate, workRelated: YES|NO|UNDER_REVIEW, caseId, note, recordedBy, recordedAt}`.
`Allergy`: `{id, employeeId, substance:{system,code,display}, category, criticality, reaction, recordedBy, recordedAt}`.
`Medication`: `{id, employeeId, name, code?:{system,code,display}, dose, route, frequency, source, status, startDate, endDate, recordedBy, recordedAt, overrides:[{alertCode, reason}]}`.
`Immunisation`: `{id, employeeId, vaccine, code?, date, doseNumber, lotNumber, status: COMPLETE|PARTIAL|DUE, nextDueDate}`.
`JobEntry` (ODH): `{id, employeeId, jobCode, jobTitle, employer:"Group / subsidiary", industry, startDate, endDate, current:boolean, source: HRIS|MANUAL, hazards:[{code,name,category}], duties, weeklyHours}`.

### 4.2 Encounters (E03-F02)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/encounter-templates` | OH roles | `[EncounterTemplate]` (8 templates, versioned) |
| POST | `/employees/{employeeId}/encounters` | Clinicians | `{type, caseId?, templateVersion?}` → `Encounter` (DRAFT). MENTAL_HEALTH encounters are RESTRICTED_CLINICAL and may only be created by PSYCH_CLINICIAN |
| GET | `/employees/{employeeId}/encounters` | OH roles | `[EncounterSummary]` (restricted items hidden / counted) |
| GET | `/encounters/{id}` | OH roles (ABAC) | `Encounter` |
| PUT | `/encounters/{id}` | author while DRAFT | `{answers, soap:{subjective,objective,assessment,plan}, bodyMap:[{region, side, mechanism}], diagnoses:[{system,code,display,primary}], fitnessStandardCode?}` |
| POST | `/encounters/{id}/sign` | author (Clinicians) | validates mandatory fields + ≥ 1 coded diagnosis for INJURY/ILLNESS/MENTAL_HEALTH → SIGNED (locked; `signature:{signedBy, signedAt, sha256}`) |
| POST | `/encounters/{id}/addenda` | Clinicians | `{text}` – only on SIGNED encounters; addenda are themselves signed and immutable |
| POST | `/encounters/{id}/entered-in-error` | author or CMO | `{reason}` |
| POST | `/encounters/{id}/photos` | Clinicians | multipart `file` (JPEG/PNG ≤ 10 MB) + `consent=true` (required) + `bodyRegion?` → `{documentId}` (DocumentType PHOTO, thumbnail in timeline) |
| GET | `/encounters/unsigned?mine=true` | Clinicians | reminder list `[EncounterSummary]` of DRAFT encounters older than 1 h (author's or all for NURSING_HEAD/CMO) |
| GET | `/body-map/mechanisms` | OH roles | `[{code,label}]` mechanism pick-list (FALL, STRUCK_BY, CAUGHT_BETWEEN, LIFTING, PUSH_PULL, SLIP_TRIP, CUT, BURN_HEAT, BURN_CHEMICAL, REPETITIVE, VEHICLE, OTHER) |

`EncounterTemplate`: `{type, version, title, sections:[{key, title, fields:[{key, label, type: TEXT|TEXTAREA|NUMBER|SELECT|MULTISELECT|BOOLEAN|DATE|CODE, required, options:[{value,label}], codeSystem?, unit?}]}], bodyMap:boolean, requiresDiagnosis:boolean, fitnessStandardPrompt:boolean, approvedBy, status: DRAFT|APPROVED}` – the eight templates ship as DRAFT pending CMO approval (`POST /encounter-templates/{type}/approve`, CMO).
`EncounterSummary`: `{id, employeeId, caseId, type, status, title, author, createdAt, signedAt, primaryDiagnosis, aiAssisted}`.
`Encounter` = summary + `answers, soap, bodyMap, diagnoses, addenda:[{id,text,author,signedAt}], photos:[{documentId, bodyRegion, takenAt}], signature, classification, fitnessStandard:{code,title,referenceUrl}?, cdsAlerts:[CdsAlert]`.

### 4.3 Terminology (E03-F03)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/terminology/search?system=ICD_10&q=ankle&limit=20` | OH roles | word-prefix search on display + code prefix; `[{system, code, display}]`; target < 300 ms |
| GET · POST · DELETE | `/terminology/favourites` (`{system, code}`) | OH roles | per-user favourites; GET returns user favourites + role favourites |
| GET | `/terminology/recent` | OH roles | last 20 codes used by the user |
| GET | `/terminology/mappings?system=&code=` | OH roles | local reporting category for a code |
| POST | `/terminology/concepts/import` | OH_ADMIN | bulk upsert `[{system, code, display, active}]` (annual release update) → `{upserted, deactivated}` |
| GET | `/terminology/releases` | OH_ADMIN | loaded releases `[{system, version, loadedAt, loadedBy, licence}]` |

### 4.4 Orders, results, devices (E03-F04)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/order-sets` | OH roles | `[{code, title, items:[{category, code:{system,code,display}}]}]` (e.g. NOISE_SURVEILLANCE, FOOD_HANDLER_SCREEN, PRE_PLACEMENT_BLOODS, SPIROMETRY_BASELINE) |
| POST | `/employees/{employeeId}/orders` | Physicians, OH_CLINICIAN | `{caseId?, category, items:[{system,code,display}] | orderSetCode, priority: ROUTINE|URGENT, clinicalNote?}` → `Order` (LAB/IMAGING: HL7 v2 ORM^O01 message queued for the lab gateway) |
| GET | `/employees/{employeeId}/orders` | OH roles | `[Order]` |
| POST | `/orders/{id}/cancel` | ordering clinician | |
| GET | `/integration/lis/orders?status=QUEUED` | INTEGRATION | pull queue for the lab gateway: `[{orderId, placerOrderNumber, hl7}]` |
| POST | `/integration/lis/orders/{id}/ack` | INTEGRATION | marks SENT |
| POST | `/integration/lis/results` | INTEGRATION | FHIR-like JSON `{placerOrderNumber?, employeeId, observations:[{code:{system,code,display}, value, unit, referenceRange, interpretation?, status, effectiveAt}]}`; HL7 v2 ORU^R01 via the existing `/integration/hl7v2` endpoint is processed the same way |
| POST | `/employees/{employeeId}/results/device` | OH roles, INTEGRATION | device capture (or manual entry with `deviceId=null`): `DeviceResult` |
| GET | `/employees/{employeeId}/results?category=` | OH roles | `[Result]` |
| GET | `/results/worklist?overdue=` | Physicians, OH_CLINICIAN | unacknowledged abnormal/critical results `[Result]` with `ageHours`, `overdue` (> 72 h, escalated to CMO) |
| POST | `/results/{id}/acknowledge` | Physicians, OH_CLINICIAN | `{action: NO_ACTION|REPEAT_TEST|REFER|FOLLOW_UP_APPOINTMENT|FITNESS_REVIEW, note}` |
| POST | `/results/{id}/letter` | OH roles | generate RESULT_LETTER document → `Document` |

`Order`: `{id, placerOrderNumber:"ORD-2026-000045", employeeId, caseId, category, items:[…], priority, status, orderedBy, orderedAt, resultCount}`.
`Result`: `{id, orderId, employeeId, category, code:{system,code,display}, value, unit, referenceRange, flag, status, effectiveAt, receivedAt, source: LIS|DEVICE|MANUAL, deviceId, calibrationId, acknowledgedBy, acknowledgedAt, ackAction, ageHours, overdue, detail?}`.
`DeviceResult`: `{category: AUDIOMETRY|SPIROMETRY|VISION|ECG, deviceId, deviceModel, calibrationId, calibrationDate, performedAt, caseId?, measurements}` with
* AUDIOMETRY `measurements = {left:{"500":10,"1000":10,"2000":15,"3000":25,"4000":35,"6000":40,"8000":30}, right:{…}}` (dB HL). Standard threshold shift (average change ≥ 10 dB at 2, 3 and 4 kHz in either ear versus the baseline audiogram) → flag ABNORMAL with `detail:{sts:{left:true,right:false}, baselineId}`; the first audiogram becomes the baseline.
* SPIROMETRY `{fev1L, fvcL, fev1PredictedL?, fvcPredictedL?}` → FEV1/FVC < 0.70 or FEV1 < 80 % predicted → ABNORMAL.
* VISION `{acuityLeft:"6/6", acuityRight:"6/9", binocular:"6/6", colourPlatesCorrect:14, colourPlatesTotal:15, corrected:false}` → worse than 6/12 binocular or colour plates < 12/15 → ABNORMAL.
* ECG `{rhythm:"SINUS", heartRate:72, interpretation:"NORMAL|ABNORMAL|BORDERLINE", qtcMs?}`.
Calibration ID is mandatory for device capture (not for manual entry).

### 4.5 Clinical decision support (E03-F05)
* Medication recording (`POST /employees/{id}/medications`) and medication administered in clinic are checked against
  recorded allergies (drug-class map, e.g. amoxicillin → penicillin class) and a curated interaction table; ordering
  checks duplicate tests (same code within 30 days). Response `409 code=cds_alert` with `{alerts:[CdsAlert]}` unless the
  request carries `overrides:[{alertCode, reason}]` for every alert (reason required).
* `CdsAlert`: `{code:"ALLERGY_CLASS"|"INTERACTION"|"DUPLICATE_TEST"|"SURVEILLANCE_DUE"|"DURATION_EXCEEDED", severity: INFO|WARNING|CRITICAL, message, reference}`.
* `GET /cds/statistics?from&to` (CMO, NURSING_HEAD) → `{fired, overridden, overriddenWithoutReason, overrideRate, byCode:[…]}` (target < 10 % override without reason – always 0 because a reason is mandatory).
* Encounter `cdsAlerts` also include protocol prompts and the applicable fitness standard (E03-BL-014) for FITNESS_ASSESSMENT / CREW_AEROMEDICAL / FOOD_HANDLER templates.

### 4.6 AI documentation assistant (E03-F07 – Could / P3)
* `POST /encounters/{id}/ai-consent` (Clinicians) `{verbal:true, statement}` – records employee consent (purpose `AI_DOCUMENTATION`) captured by the clinician.
* `POST /encounters/{id}/ai-draft` (Clinicians) `{transcript}` → `{draft:{soap:{…}, answers:{…}}, provider, generatedAt}`; requires consent and a configured provider (`503 code=ai_not_configured` otherwise). Drafts are never saved automatically; when the clinician saves any part the encounter is flagged `aiAssisted=true` and the AI involvement is audited.
* `GET /cases/{id}/summary` (OH roles) – rule-based (non-AI) structured case summary for handover: key dates, diagnoses, fitness, restrictions, open tasks, last encounter.
* Provider SPI `AiDocumentationProvider`; no provider is enabled by default (evaluation spike E03-BL-018 documented in the solution design).

## 5. E05 – Fitness for duty, restrictions & RTW (`modules/wz-e05-fitness`, schema `fitness`)

### 5.1 Fitness decisions (E05-F01)
| Method | Path | Roles | Notes |
|---|---|---|---|
| POST | `/cases/{id}/fitness-decisions` | Physicians, OH_CLINICIAN (non-licensed standards only) | `FitnessDecisionRequest` → `FitnessDecision` |
| GET | `/cases/{id}/fitness-decisions` | OH roles | history `[FitnessDecision]` |
| POST | `/fitness-decisions/{id}/signoff` | role required by the standard (AME/CMO); CMO for PERMANENTLY_UNFIT | `{approve:true|false, note}` |
| GET | `/fitness-decisions/awaiting-signoff` | AME, CMO | `[FitnessDecision]` |
| GET | `/cases/{id}/duration-comparison` | OH roles | E05-BL-020 `{icdCode, guidelineTitle, expectedDays, actualDays, varianceDays, exceeded, source}` (null guideline → `{icdCode, expectedDays:null}`) |
| GET | `/employees/{employeeId}/fitness` | OH roles | current decisions per standard `[FitnessDecision]` |

`FitnessDecisionRequest`: `{standardCode, outcome: FitnessOutcome, limitationCodes:["VDL"], restrictions:[{code:"LIFT_MAX_KG", value:10}], validFrom, validUntil?, reviewDate?, rationale (clinical, OH-only, ≤ 4000), durationOverrideReason?, encounterId?}`.
Rules: licensed standards (`requiresSignoffRole=AME`) can only be decided by AME or CMO; PERMANENTLY_UNFIT → `AWAITING_SIGNOFF` until a CMO approves; `validUntil` defaults from the standard's validity; a restriction from the catalogue creates a `RestrictionInstance`; FIT_WITH_LIMITATIONS requires ≥ 1 restriction or limitation code. A FINAL decision updates the case fitness status (kernel) → status feeds (HR, rostering for crew, hygiene for food handlers) and generates a draft FITNESS_CERTIFICATE (no diagnosis) unless `generateCertificate=false`.
`FitnessDecision`: `{id, caseId, caseRef, employeeId, standardCode, standardTitle, standardVersion, outcome, kernelStatus, limitationCodes, restrictions:[{code,label,value}], validFrom, validUntil, reviewDate, rationale (OH only), status, decidedBy, decidedAt, signoffBy, signoffAt, signoffNote, certificateDocumentId}`.

### 5.2 Restrictions, job-demand matching, modified duties (E05-F02)
| Method | Path | Roles | Notes |
|---|---|---|---|
| POST | `/fitness/match` | OH roles | `{employeeId, restrictions:[{code,value}], unit?}` → `[{jobCode, title, unit, compatible:boolean, conflicts:[{dimension, demand, restriction}], score, sameDepartment}]` ranked (compatible first, same department, fewest demands removed) |
| POST | `/cases/{id}/duty-proposals` | OH roles | `{jobCode, duties (≤1000, functional wording – clinical text rejected), hoursPerDay?, startDate, endDate?}` → `DutyProposal` (manager task) |
| GET | `/cases/{id}/duty-proposals` | OH roles | `[DutyProposal]` |
| GET | `/workspace/duty-proposals?status=PROPOSED` | MANAGER (own scope), HR | `[DutyProposal]` (no clinical content) |
| POST | `/duty-proposals/{id}/decision` | MANAGER (own scope) | `{accept:boolean, reasonCode: NO_SUITABLE_WORK|OPERATIONAL|SAFETY|COST|OTHER (required when declining), note?}`; when every proposal of a case is declined → `ESCALATED_TO_HR` + HR task |

`DutyProposal`: `{id, caseId, caseRef, employeeId, employeeName, jobCode, jobTitle, duties, restrictions:[{code,label,value}], hoursPerDay, startDate, endDate, status, proposedBy, proposedAt, decidedBy, decidedAt, reasonCode, note}`.

### 5.3 RTW plans & job offers (E05-F03)
| Method | Path | Roles | Notes |
|---|---|---|---|
| POST | `/cases/{id}/rtw-plans` | OH roles | `{startDate, phases:[{fromWeek, toWeek, hoursPerDay, duties, restrictions:[{code,value}]}], reviewDates:["2026-11-10",…], goal}` → `RtwPlan` (DRAFT) |
| PUT | `/rtw-plans/{id}` | OH roles | while DRAFT |
| POST | `/rtw-plans/{id}/activate` | OH roles | ACTIVE; employee + manager acknowledgement tasks; RTW_PLAN document generated |
| GET | `/cases/{id}/rtw-plans` | OH roles | `[RtwPlan]` |
| GET | `/me/rtw-plans` | EMPLOYEE | my active plans (functional content only) |
| POST | `/me/rtw-plans/{id}/acknowledge` | EMPLOYEE | `{agree:true, comment?}` |
| GET | `/workspace/rtw-plans` | MANAGER (scope), HR | plans for my team (functional content only) |
| POST | `/rtw-plans/{id}/manager-acknowledge` | MANAGER (scope) | `{comment?}` |
| POST | `/rtw-plans/{id}/reviews/{reviewId}/complete` | OH roles | `{outcome: ON_TRACK|ADJUST|EXTEND|COMPLETE, note}` |
| GET | `/rtw/reviews/overdue` | NURSING_HEAD, OH roles | overdue reviews `[{planId, caseRef, employeeName, reviewDate, daysOverdue, owner}]` (escalated to Nursing Head after 2 days) |
| POST | `/cases/{id}/job-offers` | HR | `JobOfferRequest` → `JobOffer` (ISSUED; JOB_OFFER document; employee notified) |
| GET | `/cases/{id}/job-offers` | OH roles, HR | `[JobOffer]` |
| GET | `/me/job-offers` | EMPLOYEE | `[JobOffer]` |
| POST | `/me/job-offers/{id}/respond` | EMPLOYEE | `{accept:boolean, comment?}` → HRIS note event `JobOfferResponded` (status only) |
| POST | `/job-offers/{id}/withdraw` | HR | |

`RtwPlan`: `{id, caseId, caseRef, employeeId, employeeName, status, startDate, goal, phases:[{fromWeek,toWeek,hoursPerDay,duties,restrictions:[{code,label,value}]}], reviews:[{id, dueDate, completedAt, outcome, note, completedBy}], employeeAck:{at, agree, comment}, managerAck:{at, by, comment}, documentId, createdBy, createdAt, nextReviewDate}` – `note`/`goal` must be functional (clinical text rejected).
`JobOfferRequest`: `{jobCode, jobTitle, duties, location, shift, hoursPerWeek, payBasis: SAME_PAY|PROTECTED_PAY|ADJUSTED_PAY, payNote, restrictions:[{code,value}], startDate, endDate?, contactName, contactEmail, respondBy}`.
`JobOffer`: request fields + `{id, caseId, employeeId, employeeName, status, issuedBy, issuedAt, respondedAt, comment, documentId}`.

### 5.4 Crew fitness & roster-status feed (E05-F04)
| Method | Path | Roles | Notes |
|---|---|---|---|
| POST | `/intake/triggers/fitness-declaration` | EMPLOYEE (existing, E01) | response now includes `nextSteps:[…]`, `ameContact`, `acknowledgementDueBy` (declaration acknowledged within 24 h SLA) |
| POST | `/cases/{id}/crew-assessment` | AME, CMO | `{standardCode: CAA_CLASS1|CAA_CLASS2|CABIN_CREW, outcome, limitationCodes, validFrom, validUntil, reviewDate, examinationDate, rationale}` → `FitnessDecision` + CREW_MEDICAL_REPORT / CABIN_CREW_MEDICAL_REPORT document (local CAA layout) |
| GET | `/crew/limitation-codes` | OH roles, CREW_SCHEDULING | `[{code:"OML", label:"Valid only as or with qualified co-pilot", appliesTo:["CAA_CLASS1"], operational:true}]` |
| GET | `/status/crew` | CREW_SCHEDULING (existing) | adds `rosterStatus`, `validFrom`, `validUntil`, `operationalLimitations` (operational codes only, e.g. OML) |
| GET | `/integration/roster-feed?since=` | INTEGRATION, CREW_SCHEDULING | messages `[{messageId, employeeId, rosterStatus, validFrom, validUntil, operationalLimitations, sentAt, ackAt}]` |
| POST | `/integration/roster-feed/{messageId}/ack` | INTEGRATION | rostering acknowledgement |
| GET | `/integration/roster-feed/reconciliation` | CREW_SCHEDULING, CMO, OH_ADMIN | `{sent, acknowledged, unacknowledgedOver15Min:[…], p95LatencySeconds}` |
| GET | `/crew/dashboard?from&to` | CMO | E05-BL-013 `{unfitByBase:[{base, count, avgDays}], limitedByBase:[…], byFleet:[…], trend:[{week, unfit, limited}]}` – no identifiers, cells < 5 suppressed |

Roster-status mapping: FIT→FIT, FIT_WITH_RESTRICTIONS→LIMITED, TEMPORARILY_UNFIT/PERMANENTLY_UNFIT→UNFIT, UNFIT_PENDING/UNDER_REVIEW/NOT_ASSESSED→PENDING.
Rostering payload (`CaseEvents.RosterStatusChanged`, allow-list): `messageId, employeeId, fitnessStatus, rosterStatus, restrictionUntil, validFrom, validUntil, operationalLimitations (comma-separated), effectiveFrom`.

### 5.5 Food handlers (E05-F05)
| Method | Path | Roles | Notes |
|---|---|---|---|
| POST | `/cases/{id}/food-handler/exclude` | OH roles | `{reasonCategory: GI_ILLNESS|SKIN_INFECTION|TYPHOID_PARATYPHOID|HEPATITIS_A|STEC|OTHER (OH-only), excludedFrom, expectedReturn?, checklistAnswers:{…}}` → status EXCLUDED; hygiene notified (status only) |
| POST | `/cases/{id}/food-handler/return-request` | OH roles | → RETURN_PENDING_CLEARANCE |
| POST | `/cases/{id}/food-handler/clear` | OH_CLINICIAN, Physicians | `{criteria:{symptomFree48h:true, clearanceSamples?:…}, note}` → FIT_TO_HANDLE_FOOD + FOOD_HANDLER_CLEARANCE document |
| GET | `/fitness/food-handlers?department=CATERING` | HYGIENE_MANAGER, OH roles | `[{employeeId, displayName, department, status, excludedFrom, expectedReturn, clearedAt}]` – status & dates only, audited |

### 5.6 Restriction expiry & manager view (E05-F06)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/restrictions/active?unit=&status=` | OH roles | `[RestrictionInstance]` incl. EXPIRING / LAPSED |
| POST | `/restrictions/{id}/extend` | OH roles | `{validUntil, reason}` |
| POST | `/restrictions/{id}/close` | OH roles | `{reason}` |
| GET | `/workspace/team-restrictions` | MANAGER (scope), HR (scope) | `[{employeeId, employeeName, fitnessStatus, restrictions:[{label, validUntil, status}]}]` – functional only, access audited |
| GET | `/restrictions/dashboard` | NURSING_HEAD, CMO | `{byUnit:[{unit, active, expiring, lapsed}]}` |

`RestrictionInstance`: `{id, employeeId, employeeName, caseId, caseRef, decisionId, code, label, value, validFrom, validUntil, status, alertedAt, closedAt, closeReason}`. A daily job alerts the case owner, the manager and the employee 5 days before expiry (content-free notification) and marks lapsed ones.

### 5.7 Ill-health retirement / redeployment & appeals (E05-F07)
| Method | Path | Roles | Notes |
|---|---|---|---|
| POST | `/cases/{id}/ihr-assessments` | Physicians | `{type, findings (OH only), recommendation, policyReference}` → `IhrAssessment` (AWAITING_CMO) |
| POST | `/ihr-assessments/{id}/cmo-decision` | CMO | `{approve, decisionText (functional, shared), note}` → DECIDED + IHR_DECISION_LETTER; appeal window 28 days |
| POST | `/ihr-assessments/{id}/appeal` | HR, OH roles (on behalf of employee) | `{grounds, secondOpinionBy?}` → APPEALED; appeal decision due in 30 days |
| POST | `/ihr-assessments/{id}/appeal-decision` | CMO (not the original physician) | `{upheld:boolean, decisionText}` |
| GET | `/cases/{id}/ihr-assessments` | OH roles; HR sees `{type, status, decisionText, dates}` only | |

## 6. Events (outbox)
`EncounterSigned{encounterId, employeeId, caseId, type}`, `ResultReceived{resultId, employeeId, flag}`,
`FitnessDecisionMade{decisionId, caseId, employeeId, outcome, standardCode}` (in-process only),
`FitnessStatusChanged` (status feeds, extended), `RestrictionExpiring{restrictionId, employeeId, validUntil}`,
`JobOfferResponded{offerId, employeeId, accepted}` → `hr-systems` (allow-list: `messageId, employeeId, offerId, accepted, respondedAt`),
`FoodHandlerStatusChanged{employeeId, status, excludedFrom, expectedReturn}` → `catering-hygiene`.

## 7. Notifications (content-free templates)
`document.shared`, `certificate.available`, `rtw.plan-ack`, `rtw.review-due`, `restriction.expiring`, `result.abnormal`,
`result.overdue`, `duty.proposal`, `job-offer.issued`, `break-glass.alert`, `fitness.signoff-required`.

## 8. Seed / demo data (local profile)
Job-demand profiles for the demo job codes and ~25 representative job codes across GND, CGO, CAT, DF, DIST, ENG, SEC,
CORP, FLT, CAB; terminology starter subset (ICD-10, LOINC, SNOMED CT sample – production loads the licensed national
editions); fitness standards; restriction catalogue; encounter templates; order sets; demo chart for E1004 (Rami Ramp):
allergy, problem, baseline audiogram, job history.
