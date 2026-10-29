# Increment 3 – E06 Absence & Sickness, E07 Medical Surveillance, E14 Scheduling & Clinic Operations: API and Domain Contract

Source of truth shared by the backend (`modules/wz-e06-absence`, `modules/wz-e07-surveillance`, `modules/wz-e14-scheduling`,
platform / kernel extensions) and the UIs (`web/oh-workspace`, `web/employee-app`, `web/manager-workspace`). Conventions
are unchanged from increments 1–2: paths relative to `/api/v1` (browser apps call `/bff/api/...`), RFC 9457 problem
details `{type,title,status,detail,code,correlationId}`, ISO-8601 UTC timestamps, `YYYY-MM-DD` dates, plain JSON arrays
unless a page object is stated, null fields omitted, denied record access reported as `404`, `422` for business-rule
violations (with `code`), `409` for conflicts. `/api/v1/integration/**` is reserved for machine clients (INTEGRATION).

**Confidentiality rule (all three epics).** Managers, HR, crew scheduling / licensing, catering hygiene, clinic schedulers,
reception and biomedical staff see **status, dates and functional advice only** – never absence reasons, diagnoses,
certificate content, questionnaire answers, results, exposures or encounter content. Free text that will be shown to
non-OH roles is screened with `ClinicalContentException.screen(field, value)` (422 `clinical_content`). Every notification
uses an approved content-free template (`NotificationTemplates`, see §7). Small-group aggregates are suppressed below
`analytics.min-group-size` (default 5) – a suppressed row carries `suppressed:true` and no counts.

## 1. Roles

New roles (realm, `Roles.java`, Keycloak `scopeMappings` of `wellnessz-bff` and `wellnessz-mobile` – already added):

| Role | Purpose | Demo user (employeeId) |
|---|---|---|
| `CLINIC_SCHEDULER` | diaries, bookings, campaigns, waitlists, due-list invitations; names + appointment type only | `scheduler1` (S8601) |
| `BIOMEDICAL` | equipment register, calibration, cold-chain logs | `biomed1` (S8602) |
| `CREW_LICENSING` | crew medical certificate validity / expiry (status only) | `crewlicensing1` (S8603) |
| `CANDIDATE` | pre-employment candidate: own questionnaire, consent, documents, booking | `candidate1` (**C9001**, not in the HRIS master) |

Existing roles reused: `CLINIC_RECEPTION` (check-in, kiosk, queue board, certificate scanning), `HR` (advice requests,
LTA HR stages, onboarding), `MANAGER`, `HSE_OFFICER` (IH / exposure registers), `CREW_SCHEDULING`, `HYGIENE_MANAGER`,
`AUDITOR` (anonymised audit packs), `DPO`, `OH_ADMIN`. "OH roles", "Clinicians", "Physicians" as in increment 2
(`Roles.OH_TEAM`, `Roles.CLINICIANS`, `Roles.PHYSICIANS`). `MeController` reports `STATUS_ONLY` for CLINIC_SCHEDULER /
CREW_LICENSING and `SELF` for CANDIDATE.

## 2. Platform additions (done before module build – do not change their signatures)

### 2.1 Kernel SPIs (`platform/wz-case-kernel/.../casekernel/support`)
| SPI | Implemented by | Called by | Purpose |
|---|---|---|---|
| `AppointmentBooking` | E14 | E07 (invitations, pre-employment, immunisation), E06 (LTA contacts) | `bookNext(actor, BookingRequest)` → `Optional<Booking>`; `bookingsFor(sourceType, sourceRef)` |
| `AppointmentStatusListener` | E07, E06 | E14 | `appointmentStatusChanged(appointmentId, employeeId, sourceType, sourceRef, Status)`, `supports(sourceType)` – BOOKED, CHECKED_IN, ATTENDED, NO_SHOW, CANCELLED |
| `DeviceCalibrationCheck` | E14 | E03 `ResultService.device` (wired) | VALID / UNKNOWN / OUT_OF_CALIBRATION / OUT_OF_SERVICE → 422 `device_out_of_calibration` / `device_out_of_service` (UNKNOWN allowed unless `wellnessz.record.require-registered-devices=true`) |
| `ResultFollowUpListener` | E07 | E03 `ResultService` LIS + device (wired) | synchronous `resultRecorded(RecordedResult)` with ids / codes / flag only |
| `FitnessClearanceGuard` | E07 | E05 `FitnessDecisionService.decide` for FIT / FIT_WITH_LIMITATIONS (wired) | `Optional<String> blockingReason(employeeId, outcome)` → 422 `clearance_required` |
| `AbsenceSignalListener` | E07 | E06 | `longTermAbsence(employeeId, caseId, startDate, days)` once when the LTA threshold is crossed |
| `CapacityDemandSource` | E07, E06 | E14 capacity | `demand(from, to)` → `[Demand(siteCode, serviceCode, YearMonth, appointments, source)]` – counts only |

`sourceType` values: `SURVEILLANCE_INVITATION`, `PRE_EMPLOYMENT`, `IMMUNISATION`, `CREW_RENEWAL`, `ABSENCE_CONTACT`, `RTW_REVIEW`, `MANUAL`.

### 2.2 Master data
* `GET /masterdata/clinic-sites` (authenticated) → `[ClinicSite]`; `POST /masterdata/clinic-sites` (OH_ADMIN, PLATFORM_ADMIN, INTEGRATION) upsert.
  `ClinicSite`: `{code:"HQ-MC", name, siteType: HQ_MEDICAL_CENTRE|AIRSIDE_POST|CATERING_ROOM|CARGO_ENG_CLINIC|OUTSTATION|MOBILE, locationCode:"HQ", timeZone:"Europe/London", address, active}`.
  Seeded: `HQ-MC` (HQ), `LHR-AIRSIDE`, `LHR-CAT`, `LHR-CARGO` (LHR), `DXB-OUT` (DXB), `MOBILE-1` (LHR). An employee's home site = first active site whose `locationCode` = employee `locationCode` (HQ → HQ-MC, LHR → LHR-AIRSIDE unless the appointment type needs another room type).
* V9 adds hazards `DUST_RESP, SOLVENT, BBP, VIBRATION, HEAT, NIGHT_WORK, HEIGHT, DGR, VDU` and job codes `SECURITY_SCREENER, CLEANER, FORKLIFT_OP, CLINICAL_STAFF, ENG_HEIGHT`; demo employees E1006 Ellie Engineer (ENGINEER), E1007 Sam Screener (SECURITY_SCREENER), E1008 Fiona Forklift (FORKLIFT_OP, subsidiary CGO), E1009 Cole Cleaner (CLEANER), E1010 Cathy Cook (CATERING_ASSOC, manager M2001).

### 2.3 Documents (`DocumentService`, module-level API – the caller has already authorised the actor)
* `storeUpload(actor, employeeId, caseId, documentType, title, bytes, classification)` → `Stored(document, extractedText, sha256)`; types `SICK_CERTIFICATE, PRE_EMPLOYMENT_DOCUMENT, VACCINATION_RECORD, EXTERNAL_AEROMEDICAL_REPORT, EXTERNAL_REPORT`; PDF/JPEG/PNG ≤ 10 MB; text from PDF text layer or OCR. `employeeId` may be a candidate id.
* `generateSigned(actor, templateCode, employeeId, caseId, fields)` → signed `DocumentView` (new templates `OH_ADVICE_REPORT` (non-clinical, screened), `VACCINATION_CERTIFICATE` (clinical, employee only), `PRE_EMPLOYMENT_OUTCOME` (non-clinical, HR)).
* `shareAsSystem(actor, documentId, EMPLOYEE|MANAGER|HR)` – same non-clinical rule and disclosure log as `share`.
* `systemContent(actor, documentId)` – bytes for a module endpoint that authorised the reader.
* Retention classes added: `ABSENCE_RECORD` (6 y), `PRE_EMPLOYMENT` (1 y for candidates not hired).

### 2.4 BFF
`/bff/api/workspace/{absence|advice-requests|surveillance|pre-employment}/**` keep their `/workspace` prefix (core
endpoints live at `/api/v1/workspace/...`); other `/workspace/X` paths remain legacy aliases of `/api/v1/X`.

### 2.5 Outbound allow-list (`application.yml`)
`hr-systems` gains `offerRef, candidateId, preEmploymentStatus, ohEngaged, certificateValidity`. Crew roster messages
keep the increment-2 fields (`rosterStatus` FIT / UNFIT / LIMITED / PENDING, `validUntil`, `operationalLimitations`).

### 2.6 Database
| Migration | Schema | Owner |
|---|---|---|
| V9 `increment3_platform` | masterdata / docs | platform (done) |
| V10 `e06_absence` | `absence` | E06 – must start with `create schema if not exists absence;` |
| V11 `e07_surveillance` | `surveillance` | E07 – `create schema if not exists surveillance;` |
| V12 `e14_scheduling` | `scheduling` | E14 – `create schema if not exists scheduling;` |

Demo data in repeatable `db/demo/R__demo_e06_absence.sql`, `R__demo_e07_surveillance.sql`, `R__demo_e14_scheduling.sql`
(idempotent – `on conflict do nothing` / `where not exists`; dates relative to `current_date`). Grants to `${app_role}` at the end
of each versioned migration. Encrypted columns use `EncryptedStringConverter` (`text`).

## 3. E06 – Absence & Sickness Management (`com.wellnessz.e06`, schema `absence`)

Enumerations: `AbsenceSource` HRIS | TNA | CREW | MANUAL · `AbsenceType` SICKNESS | INJURY_WORK | INJURY_NON_WORK | MATERNITY_RELATED | OTHER_AUTHORISED ·
`ReasonCategory` (OH only; analytics at category level) MSK | MENTAL_HEALTH | RESPIRATORY | GASTRO | INJURY | SURGERY | INFECTION | OTHER | UNDISCLOSED ·
`RuleType` DURATION | FREQUENCY | PATTERN | POST_INCIDENT | SAFETY_CRITICAL | CREW_SICKNESS · `RuleAction` EARLY_INTERVENTION | LONG_TERM | AME_REVIEW ·
`QueueStatus` NEW | ACCEPTED | CONTACTED | CLOSED · `CertificateStatus` RECEIVED | VALID | INVALID | SUSPICIOUS | VERIFIED_GENUINE | VERIFIED_FRAUDULENT ·
`CertificateValidity` (HR view) PENDING | VALID | INVALID · `MilestoneType` CONTACT | ADVICE_REPORT | CASE_CONFERENCE | HR_REVIEW | RTW_REVIEW | IHR_CONSIDERATION ·
`MilestoneStatus` PENDING | DONE | OVERDUE | SKIPPED · `HrStage` NONE | INFORMAL_REVIEW | FORMAL_REVIEW_1 | FORMAL_REVIEW_2 | CAPABILITY_HEARING | CLOSED ·
`AdviceStatus` SUBMITTED | ACCEPTED | REPORT_DRAFTED | AWAITING_EMPLOYEE | RELEASED | WITHHELD | WITHDRAWN ·
`AdviceFitness` FIT | FIT_WITH_ADJUSTMENTS | TEMPORARILY_UNFIT | UNFIT_LONG_TERM · `Prognosis` RTW_WITHIN_2_WEEKS | RTW_WITHIN_4_WEEKS | RTW_WITHIN_12_WEEKS | UNCERTAIN | RTW_UNLIKELY.

### 3.1 Feed & history (E06-F01)
| Method | Path | Roles | Notes |
|---|---|---|---|
| POST | `/integration/absence/episodes` | INTEGRATION | `{sourceSystem, episodes:[{sourceRef, employeeRef, startDate, endDate?, absenceType, reasonCategory?, certified}]}` → `{runId, received, matched, unmatched, updated, triggered}`. `employeeRef` reconciles against employee id, then badge id; unmatched rows go to the mismatch list. Idempotent on (sourceSystem, sourceRef); updates end date / certified. |
| POST | `/absence/feed/runs` | OH_ADMIN | runs the daily pull now (`AbsenceFeedConnector` SPI inside E06; default connector no-op) → FeedRun |
| GET | `/absence/feed/runs?limit=` | OH_ADMIN, HR, NURSING_HEAD | data-quality dashboard `[FeedRun {id, sourceSystem, startedAt, received, matched, unmatched, matchRatePct, errors}]` |
| GET | `/absence/feed/mismatches?status=OPEN` | HR, OH_ADMIN | `[{id, sourceSystem, sourceRef, employeeRef, startDate, endDate, reason, status OPEN|RESOLVED|IGNORED}]` |
| POST | `/absence/feed/mismatches/{id}/resolve` | HR, OH_ADMIN | `{employeeId}` or `{ignore:true, reason}` → re-ingests the episode |
| GET | `/absence/employees/{employeeId}/episodes?from=&to=` | OH roles (full) · HR / MANAGER in scope (status: no `reasonCategory`) | `[Episode {id, sourceSystem, startDate, endDate, days, absenceType, reasonCategory?, certified, certificateValidity, caseId?, open}]` |
| GET | `/absence/employees/{employeeId}/summary` | OH roles, HR (in scope) | `{episodes12m, daysLost12m, bradfordFactor, longestDays, openEpisode?, patterns:["MONDAY_FRIDAY"|"POST_HOLIDAY"|"FREQUENT_SHORT"], activeLtaCaseId?}` |
| POST | `/absence/episodes/{id}/link-case` | OH roles | `{caseId}` – export to case (case link type ABSENCE) |
| GET | `/me/absence/episodes` | EMPLOYEE | own episodes (no reason category) |
| GET | `/workspace/absence/team` | MANAGER, HR | team members in scope `[{employeeId, displayName, openEpisode:{startDate, days}?, episodes12m, ohEngaged, certificateValidity}]` |

### 3.2 Trigger rules & early-intervention queue (E06-F02)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/absence/rules` | OH roles, OH_ADMIN, HR | `[Rule {id, code, name, subsidiaryCode?, ruleType, params, action, active, version, updatedBy, updatedAt}]` |
| POST | `/absence/rules` | OH_ADMIN | create; `params` per type: DURATION `{minDays}`, FREQUENCY `{episodes, windowDays}`, PATTERN `{pattern:"MONDAY_FRIDAY", minOccurrences, windowDays}`, POST_INCIDENT `{withinDays}`, SAFETY_CRITICAL `{minDays}`, CREW_SICKNESS `{minDays, episodes, windowDays}` |
| PUT | `/absence/rules/{id}` | OH_ADMIN | new version (audit) · DELETE deactivates |
| POST | `/absence/rules/test` | OH_ADMIN | test mode: `{rule (unsaved Rule body) | ruleId, subsidiaryCode?}` → `{matches:[{employeeId, displayName, subsidiaryCode, detail}], count}` – no cases created |
| GET | `/absence/queue?status=&action=` | OH roles | `[QueueItem {id, employeeId, displayName, unit, ruleCode, ruleName, action, raisedAt, slaDueAt, status, caseId, caseRef, firstContactAt?, history:[Episode]}]`; AME_REVIEW items are the AME queue |
| POST | `/absence/queue/{id}/accept` | OH roles | assigns to me, opens / links the kernel case (ABSENCE, earlyIntervention) and – when `absence.notify-hr-on-engagement` (default `true`) – sends `absence.oh-engaged` to the line manager and HR |
| POST | `/absence/queue/{id}/contact` | OH roles | `{channel PHONE|VIDEO|IN_PERSON|EMAIL, outcome REACHED|NO_ANSWER|DECLINED, note?}`; the first REACHED contact sets `firstContactAt` (KPI) |
| POST | `/absence/queue/{id}/close` | OH roles | `{reason}` |

Seeded default rules: `DURATION_10D` (> 10 days → EARLY_INTERVENTION), `FREQ_3_IN_90` (3 episodes / 90 days), `MON_FRI_PATTERN`
(≥ 3 Monday/Friday-adjacent starts in 180 days), `LTA_28D` (DURATION ≥ 28 → LONG_TERM), `SAFETY_CRITICAL_3D` (safety-critical job > 3 days),
`POST_INCIDENT_7D` (absence starting ≤ 7 days after an incident case), `CREW_SICKNESS` (crew source > 7 days or 3 in 90 → AME_REVIEW),
`CAT_DURATION_2D` (subsidiary CAT, > 2 days – food handlers). Subsidiary rules override defaults of the same type. Rules are evaluated on every
ingested / updated episode and nightly (`absence-triggers` job via `JobRunGuard`). One open queue item per employee and action.
LONG_TERM creates the LTA pathway (§3.4) and calls `AbsenceSignalListener.longTermAbsence`. AME_REVIEW opens a FITNESS case
with `UNFIT_PENDING` and `CaseService.publishFitness` (crew roster PENDING, status only); the outcome flows through the E05 fitness decision.

### 3.3 Medical certificates (E06-F03)
| Method | Path | Roles | Notes |
|---|---|---|---|
| POST | `/me/absence/certificates` | EMPLOYEE | multipart `file` + `fromDate`, `toDate`, `episodeId?` → `Certificate` (status RECEIVED; OCR fields extracted; employee sees `{id, fromDate, toDate, status:"RECEIVED|VALID|INVALID", receivedAt}`) |
| GET | `/me/absence/certificates` | EMPLOYEE | own certificates (validity only) |
| POST | `/absence/certificates` | OH roles, CLINIC_RECEPTION | clinic scan: multipart `file`, `employeeId`, `fromDate`, `toDate`, `episodeId?` |
| POST | `/integration/absence/certificates` | INTEGRATION | partner clinic electronic certificate `{employeeRef, partnerOrg, certificateNumber, issuerName, issuerRegistration, issueDate, fromDate, toDate, fitNote?: "NOT_FIT"|"MAY_BE_FIT", pdfBase64?}` (verified source → VALID unless duplicate) |
| GET | `/absence/certificates?status=&employeeId=` | OH roles, CLINIC_RECEPTION | `[Certificate {id, employeeId, displayName, source EMPLOYEE_UPLOAD|CLINIC_SCAN|PARTNER_ELECTRONIC, documentId, fromDate, toDate, issuerName?, issuerRegistration?, issueDate?, extracted:{issuerName, issueDate, fromDate, toDate, registration}, checklist, status, duplicateOfId?, alterationFlags:[...], calls:[Call], receivedAt, verifiedBy?, verifiedAt?}]` |
| POST | `/absence/certificates/{id}/validate` | OH roles, CLINIC_RECEPTION | `{checklist:{issuerIdentified, registrationValid, datesConsistent, signaturePresent, coversAbsence}, outcome VALID|INVALID|SUSPICIOUS, note?}` |
| POST | `/absence/certificates/{id}/calls` | OH roles, CLINIC_RECEPTION | verification call log `{calledParty, outcome CONFIRMED|NOT_CONFIRMED|NO_ANSWER, note?}` |
| POST | `/absence/certificates/{id}/verify` | OH roles | `{outcome VERIFIED_GENUINE|VERIFIED_FRAUDULENT, escalateToHr}` → escalation sends `absence.certificate-escalation` to HR (status only) |
| GET | `/workspace/absence/certificates?employeeId=` | HR, MANAGER (scope) | validity only `[{id, employeeId, displayName, fromDate, toDate, validity PENDING|VALID|INVALID, escalated}]` |

Duplicate detection: same SHA-256, or same (issuer registration / certificate number), or same employee with identical dates → `duplicateOfId`.
Alteration flags: `DATES_DIFFER_FROM_OCR`, `ISSUE_AFTER_START_PLUS_7`, `PDF_MODIFIED_AFTER_CREATION`, `UNREADABLE`. Retention class ABSENCE_RECORD.

### 3.4 Long-term absence pathway (E06-F04)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/absence/lta?status=ACTIVE` | OH roles | `[Pathway {caseId, caseRef, employeeId, displayName, unit, absenceStart, weeksAbsent, status ACTIVE|CLOSED, hrStage, nextMilestone?, overdueCount, rtwPlanId?}]` |
| GET | `/absence/lta/{caseId}` | OH roles | `Pathway` + `milestones:[{id, week, type, dueDate, status, completedAt?, completedBy?, note?}]`, `contacts:[{id, at, channel, outcome, note?, by, nextContactDue?}]`, `adviceReports:[{id, status, releasedAt}]`, `rtwPlan?:{id, status}` (E05) |
| POST | `/absence/lta/{caseId}/milestones/{id}/complete` | OH roles | `{note?}` · `/skip` `{reason}` |
| POST | `/absence/lta/{caseId}/contacts` | OH roles | `{channel, outcome, note?, nextContactDue?}` |
| POST | `/absence/lta/{caseId}/conference` | OH roles | case conference `{scheduledFor, participants:["EMPLOYEE","MANAGER","HR","OH"], note?}` |
| POST | `/workspace/absence/lta/{caseId}/hr-stage` | HR | `{stage, note?}` (note screened) |
| GET | `/workspace/absence/lta` | HR, MANAGER (scope) | status only `[{caseId, caseRef, employeeId, displayName, absenceStart, weeksAbsent, hrStage, nextMilestone:{week,type,dueDate}?, adviceReleased:int}]` |

Milestones auto-created at pathway start relative to the absence start: week 2 CONTACT, week 4 ADVICE_REPORT, week 8 ADVICE_REPORT + CASE_CONFERENCE,
week 12 HR_REVIEW + ADVICE_REPORT, week 26 IHR_CONSIDERATION; contact schedule fortnightly. Nightly job `absence-lta` marks OVERDUE and sends
`absence.milestone-overdue` to the case owner. Closing the absence episode with a RTW date creates an RTW_REVIEW milestone and links the E05 RTW plan.

### 3.5 Management advice request & OH advice report (E06-F05)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/absence/advice/questions` | authenticated | question library `[{code, text}]`: FIT_FOR_ROLE, ADJUSTMENTS, EXPECTED_RTW, PHASED_RETURN, ABLE_TO_ATTEND_MEETINGS, DISABILITY_PROVISIONS_LIKELY, WORK_CONTRIBUTION, FURTHER_REVIEW |
| POST | `/workspace/advice-requests` | MANAGER, HR (scope) | `{employeeId, questionCodes:[…≥1], context ≤ 2000 (encrypted; visible to OH + requester), episodeId?}` → `AdviceRequest {id, ref "ADV-2026-000001", employeeId, displayName, status, questionCodes, requestedBy, requestedAt, slaDueAt (5 working days), caseRef?}` |
| GET | `/workspace/advice-requests` | MANAGER, HR | mine / in scope, with `report?` = released report `{fitness, adjustments, restrictions, prognosis, expectedReturn?, reviewDate?, answers:{code:text}, employeeComment?, documentId, releasedAt}` – only when RELEASED; WITHHELD shows status only |
| POST | `/workspace/advice-requests/{id}/withdraw` | requester | |
| GET | `/absence/advice-requests?status=` | OH roles | full request list incl. context |
| POST | `/absence/advice-requests/{id}/accept` | OH roles | links / opens the case (ABSENCE) |
| PUT | `/absence/advice-requests/{id}/report` | Clinicians | draft `{fitness, adjustments?, restrictions?, prognosis, expectedReturn?, reviewDate?, answers:{questionCode:text}}` – **every text screened** (no diagnosis) |
| POST | `/absence/advice-requests/{id}/report/sign` | Clinicians | → AWAITING_EMPLOYEE when `absence.advice.employee-review-required` (default `true`; sends `absence.advice-review`) else RELEASED |
| GET | `/me/advice-reports` | EMPLOYEE | `[{id, requestRef, requestedByRole, status, report, createdAt}]` |
| POST | `/me/advice-reports/{id}/decision` | EMPLOYEE | `{decision CONSENT|DECLINE, comment? ≤ 1000 (screened), declineReason?}` → CONSENT: RELEASED – `generateSigned(OH_ADVICE_REPORT)` + `shareAsSystem` to MANAGER/HR requester, `absence.advice-released`; DECLINE: WITHHELD, `absence.advice-declined`; audited; new versions possible after decline |

### 3.6 Analytics (E06-F06)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/absence/analytics/units?groupBy=SUBSIDIARY|DEPARTMENT|JOB_FAMILY&from=&to=` | CMO, NURSING_HEAD, HR, OH_ADMIN | `[{key, label, headcount, episodes, daysLost, absenceRatePct, frequencyRate, avgDurationDays, bradfordMean, categories:{MSK:n,…}?, suppressed}]` – categories only for OH roles; rows with headcount < min group size suppressed; drill-down to unit only (never person) |
| GET | `/absence/analytics/kpis?from=&to=` | CMO, NURSING_HEAD, OH_ADMIN | `{absenceRatePct, ltaWithCasePct (target 95), medianDaysTriggerToContact (target 5), certificatesVerifiedPct, openLta, overdueMilestones, trend:[{month, absenceRatePct, medianDaysTriggerToContact, ltaWithCasePct}]}` |
| GET | `/absence/analytics/export?month=YYYY-MM` | CMO, NURSING_HEAD, HR | monthly pack `text/csv` (suppression applied) |

`CapacityDemandSource`: LTA contacts and advice reports due per site / month (service `ABSENCE_REVIEW`).

## 4. E07 – Medical Surveillance, Periodic & Statutory Medicals (`com.wellnessz.e07`, schema `surveillance`)

Enumerations: `ProgrammeStatus` DRAFT | ACTIVE | RETIRED · `PanelTest` AUDIOMETRY | SPIROMETRY | VISION | ECG | QUESTIONNAIRE | PHYSICAL_EXAM |
LAB:{loinc} | BIOMONITORING:{code} | IMMUNISATION:{vaccine} · `EnrolmentReason` JOB_CODE | HAZARD | EXPOSURE_GROUP | LICENCE | MANUAL ·
`ComplianceStatus` CURRENT | DUE (≤ 30 days) | OVERDUE | SCHEDULED | EXEMPT · `InvitationStatus` INVITED | BOOKED | ATTENDED | NO_SHOW | CANCELLED | ESCALATED ·
`PreEmploymentStatus` INVITED | QUESTIONNAIRE_DONE | BOOKED | EXAMINED | OUTCOME_RECORDED | WITHDRAWN · `HrClearance` PENDING | FIT | UNFIT ·
`CertificateType` CLASS1 | CLASS2 | LAPL | CABIN_CREW · `CrewCertStatus` VALID | EXPIRING (≤ 90 d) | EXPIRED | SUSPENDED · `CertSource` INTERNAL | EXTERNAL_AME ·
`FollowUpStatus` OPEN | REVIEWED | CLOSED · `RaiReason` LONG_TERM_ABSENCE | SURGERY | CARDIAC_EVENT | OTHER_SERIOUS_ILLNESS · `RaiStatus` REQUIRED | COMPLETED | WAIVED.

### 4.1 Programme & protocol builder (E07-F01)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/surveillance/programmes` | OH roles, OH_ADMIN, HSE_OFFICER, AUDITOR | `[Programme {code, version, title, status, effectiveFrom, eligibility:{jobCodes[], departmentCodes[], subsidiaryCodes[], hazardCodes[], exposureGroups[], licenceTypes[], minAge?}, panel:[{test, standard?}], frequencyMonths, frequencyMonthsOver40?, standardCode?, responsibleRole, serviceCode, regulatoryBasis, eligibleHeadcount}]` |
| GET | `/surveillance/programmes/{code}` | same | current + `versions:[{version, status, effectiveFrom, approvedBy, approvedAt}]` |
| POST | `/surveillance/programmes` | OH_ADMIN, CMO, NURSING_HEAD | create DRAFT |
| PUT | `/surveillance/programmes/{code}` | OH_ADMIN, CMO, NURSING_HEAD | new DRAFT version |
| POST | `/surveillance/programmes/{code}/simulate` | same | `{version?}` → `{eligibleHeadcount, byUnit:[{subsidiaryCode, departmentCode, count}], newlyEnrolled, wouldBeOverdue}` (population impact, no writes) |
| POST | `/surveillance/programmes/{code}/activate` | CMO, OH_PHYSICIAN | `{effectiveFrom?}` → ACTIVE (previous version RETIRED), enrolment recalculated |
| POST | `/surveillance/programmes/{code}/retire` | CMO | |

Seeded (E07-BL-002, 13 programmes, ACTIVE): `PILOT_CLASS1` (licence FLIGHT_CREW, 12 m / 6 m over 40, AME, standard CAA_CLASS1), `CABIN_CREW` (24 m, AME),
`FOOD_HANDLER` (job CATERING_ASSOC, 12 m, questionnaire + IMMUNISATION:TYPHOID), `HEARING_CONSERVATION` (hazard NOISE, 12 m, AUDIOMETRY),
`RESPIRATORY` (hazards DUST_RESP/SOLVENT, 24 m, SPIROMETRY), `HAZMAT_DGR` (hazard DGR, 24 m), `AIRSIDE_DRIVER` (job RAMP_AGENT, 36 m, VISION),
`FORKLIFT` (job FORKLIFT_OP, 36 m, VISION), `WORKING_AT_HEIGHT` (hazard HEIGHT, 36 m), `NIGHT_WORKER` (hazard NIGHT_WORK, 12 m, QUESTIONNAIRE),
`SECURITY_SCREENER` (job SECURITY_SCREENER, 24 m, VISION), `CLINICAL_BBP` (hazard BBP, IMMUNISATION:HEP_B + titre), `VDU` (hazard VDU, 24 m, VISION).
Return-after-serious-illness rule (E07-BL-020): §4.7.

### 4.2 Due-list engine, invitations & escalation (E07-F02)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/surveillance/due?programme=&subsidiary=&department=&status=&dueBefore=` | OH roles, CLINIC_SCHEDULER | `[Enrolment {id, employeeId, displayName, subsidiaryCode, departmentCode, programmeCode, programmeTitle, reason, lastCompletedOn?, nextDueOn, status, invitations, lastInvitedAt?, appointment?:{id, startsAt, siteCode}, escalatedAt?}]` |
| POST | `/surveillance/due/recalculate` | OH_ADMIN | runs the nightly `surveillance-due` job now → `{enrolled, updated, removed}` |
| POST | `/surveillance/invitations` | OH roles, CLINIC_SCHEDULER | bulk `{enrolmentIds:[…≤ 5000], book:boolean, siteCode?, campaignId?}` → `{invited, booked, notBooked, invitationIds}`; template `surveillance.invitation`; booking via `AppointmentBooking` (service = programme `serviceCode`, sourceType SURVEILLANCE_INVITATION) |
| GET | `/surveillance/invitations?enrolmentId=` | OH roles, CLINIC_SCHEDULER | invitation log `[{id, sentAt, channel, status, appointmentId?}]` |
| POST | `/surveillance/enrolments/{id}/complete` | Clinicians | `{performedOn, outcome FIT|FIT_WITH_LIMITATIONS|UNFIT|REFER, encounterId?, resultIds?[]}` → next due computed |
| POST | `/surveillance/enrolments/{id}/exempt` | Physicians | `{reason, until?}` |
| GET | `/me/surveillance` | EMPLOYEE | `[{programmeTitle, nextDueOn, status, appointment?}]` + `crewCertificate?` + `immunisations` status |
| GET | `/workspace/surveillance/compliance` | MANAGER, HR (scope) | team status only `[{employeeId, displayName, programmeTitle, status CURRENT|DUE|OVERDUE, nextDueOn, escalated}]` |

No-show handling (`AppointmentStatusListener` for SURVEILLANCE_INVITATION): NO_SHOW → re-invite (up to `surveillance.max-invitations`, default 2);
after the 2nd ignored invitation the line manager gets `surveillance.overdue-manager` (status only) and the enrolment is flagged `escalatedAt`.
ATTENDED marks the invitation ATTENDED (completion is recorded by the clinician).

### 4.3 Pre-employment / pre-placement (E07-F03)
| Method | Path | Roles | Notes |
|---|---|---|---|
| POST | `/integration/pre-employment` | INTEGRATION (HRIS offer event) | `{offerRef, candidateId, candidateName, email, jobCode, subsidiaryCode, departmentCode, locationCode, proposedStart}` → `PreEmployment` (panel = programmes whose eligibility matches the job), idempotent on offerRef, sends `pre-employment.invitation` |
| POST | `/surveillance/pre-employment` | HR, OH roles | same body (manual trigger) |
| GET | `/surveillance/pre-employment?status=` | OH roles | `[PreEmployment {id, offerRef, candidateId, candidateName, jobCode, jobTitle, proposedStart, panel:[programmeCode], status, hrClearance, questionnaireCompleted, consentGiven, appointment?, governmentMedical?:{type VISA|GOVERNMENT|RESIDENCY, reference, status PENDING|PASSED|FAILED, expiresOn}, documents:[{id, title}], slaDueAt, retentionUntil?}]` |
| GET | `/surveillance/pre-employment/{id}` | OH roles | + questionnaire answers |
| POST | `/surveillance/pre-employment/{id}/outcome` | Clinicians | `{outcome FIT|FIT_WITH_ADJUSTMENTS|UNFIT, conditions?}` (conditions screened) → `hrClearance` FIT (adjustments → FIT) / UNFIT; HR feed `hr-systems {offerRef, candidateId, preEmploymentStatus, effectiveFrom, messageId}` within 1 day SLA; `PRE_EMPLOYMENT_OUTCOME` document shared to HR |
| POST | `/surveillance/pre-employment/{id}/government-medical` | Clinicians | `{type, reference, status, expiresOn?}` |
| POST | `/surveillance/pre-employment/{id}/withdraw` | HR, OH roles | candidate not hired → retention 12 months then purge (nightly `pre-employment-purge`) |
| GET | `/workspace/pre-employment` | HR | status only `[{id, offerRef, candidateName, jobTitle, proposedStart, hrClearance, updatedAt}]` |
| GET | `/me/pre-employment` | CANDIDATE | own record `{offerRef, jobTitle, proposedStart, status, questionnaireCompleted, consentGiven, appointment?, documents}` |
| GET | `/me/pre-employment/questionnaire` | CANDIDATE | `[{code, text, type YES_NO|TEXT|DATE, required}]` |
| PUT | `/me/pre-employment/questionnaire` | CANDIDATE | `{answers:{code:value}}` (encrypted) |
| POST | `/me/pre-employment/consent` | CANDIDATE | `{consentGiven:true, purposes:["PRE_EMPLOYMENT_ASSESSMENT","HR_STATUS_FEED"]}` |
| POST | `/me/pre-employment/documents` | CANDIDATE | multipart `file`, `title` → `storeUpload(PRE_EMPLOYMENT_DOCUMENT)` |
| POST | `/me/pre-employment/book` | CANDIDATE | `{siteCode?, earliest?}` → appointment via `AppointmentBooking` (service PRE_EMPLOYMENT) – requires questionnaire + consent |

### 4.4 Crew aeromedical & certificate tracking (E07-F04)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/surveillance/crew/exam-template?type=CLASS1|CLASS2|CABIN_CREW` | AME, CMO | CAA-format sections `[{section, items:[{code, label, type}]}]` |
| POST | `/surveillance/crew/examinations` | AME | `{employeeId, certificateType, examinedOn, answers:{code:value}, outcome FIT|FIT_WITH_LIMITATIONS|UNFIT|REFERRED_TO_AUTHORITY, limitationCodes[], validUntil?}` → certificate + `CREW_MEDICAL_REPORT` / `CABIN_CREW_MEDICAL_REPORT` PDF; roster status published (FIT / LIMITED / UNFIT) via the E05 crew feed |
| GET | `/surveillance/crew/certificates?status=&expiringWithinDays=` | AME, CMO, CREW_LICENSING, CREW_SCHEDULING | `[CrewCertificate {id, employeeId, displayName, certificateType, certificateNumber, issuingAuthority, examinedOn, validUntil, limitationCodes, status, source, aemcName?, ameName?, alertsSent:[90,60,30]}]` – CREW_* roles: no `answers`, no document |
| GET | `/surveillance/crew/without-valid-medical` | CREW_LICENSING, CREW_SCHEDULING, AME, CMO | `[{employeeId, displayName, jobCode, certificateType?, validUntil?, status:"EXPIRED|NONE|SUSPENDED"}]` |
| POST | `/surveillance/crew/certificates/import` | AME, INTEGRATION | external AME / AeMC structured import `{employeeId, certificateType, certificateNumber, issuingAuthority, aemcName, ameName?, examinedOn, validUntil, limitationCodes[], pdfBase64?}` → validated, `source=EXTERNAL_AME` |
| GET | `/surveillance/crew/authority-report?from=&to=` | AME, CMO | examinations for authority reporting (`text/csv`) |

Nightly `crew-certificates` job: alerts at 90 / 60 / 30 days (`crew.medical-expiring` to the crew member, `crew.medical-expiring-licensing` to
CREW_LICENSING config address), on expiry status EXPIRED and roster status UNFIT (status only) so no crew is rostered beyond expiry.

### 4.5 Immunisation & titre registry (E07-F05)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/surveillance/immunisation/requirements` | OH roles, OH_ADMIN | role matrix `[{id, jobCode?, hazardCode?, vaccineCode, vaccineName, doses, boosterMonths?, titreRequired, mandatory}]` |
| PUT | `/surveillance/immunisation/requirements/{id}` | OH_ADMIN, NURSING_HEAD | upsert |
| GET | `/surveillance/immunisation/due?vaccine=&subsidiary=&department=` | OH roles, CLINIC_SCHEDULER | `[{employeeId, displayName, vaccineCode, vaccineName, dosesGiven, dosesRequired, nextDueOn?, status COMPLETE|DUE|OVERDUE|TITRE_NEEDED}]` |
| GET | `/surveillance/immunisation/employees/{employeeId}` | OH roles · HYGIENE_MANAGER (food handlers: status only) | `[{vaccineCode, vaccineName, status, lastDoseOn?, nextDueOn?, titre?:{value, protective, testedOn}}]` |
| POST | `/surveillance/immunisation/administrations` | Clinicians | fast entry `{employeeId | badgeId, vaccineCode, doseNumber, lotNumber | lotBarcode (GS1 "(17)YYMMDD(10)LOT"), lotExpiry?, site LEFT_DELTOID|RIGHT_DELTOID|LEFT_THIGH|RIGHT_THIGH|ORAL, route IM|SC|ORAL|ID, consentConfirmed:true, campaignId?, administeredAt?}` → also mirrored into the E03 immunisation record |
| POST | `/surveillance/immunisation/administrations/batch` | Clinicians | campaign batch `{vaccineCode, lotNumber|lotBarcode, site, route, campaignId?, entries:[{employeeId|badgeId, consentConfirmed, doseNumber?}]}` → `{recorded, rejected:[{ref, reason}]}` |
| POST | `/surveillance/immunisation/titres` | Clinicians | `{employeeId, antigen, value, unit, protective, testedOn}` |
| GET | `/me/immunisations` | EMPLOYEE | own vaccination status |
| POST | `/me/immunisations/certificate` | EMPLOYEE | `generateSigned(VACCINATION_CERTIFICATE)` + `shareAsSystem(EMPLOYEE)` → `Document` (then downloadable from `/me/documents`) |

Seeded matrix: CATERING_ASSOC → TYPHOID, HEP_A; CLINICAL_STAFF / CLEANER / SECURITY_SCREENER → HEP_B (titre); CLINICAL_STAFF → MMR, INFLUENZA;
PILOT / CABIN_CREW → YELLOW_FEVER (travel), INFLUENZA (recommended, not mandatory).

### 4.6 Exposure registers & IH linkage (E07-F06)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/surveillance/exposure-groups` | OH roles, HSE_OFFICER, OH_ADMIN | `[Seg {code, name, locationCode, jobCodes[], hazardCode, unit, actionLevel, limitValue, latestValue?, latestMeasuredOn?, aboveActionLevel, enrolledProgrammes[]}]` |
| POST | `/surveillance/exposure-groups` | HSE_OFFICER, OH_ADMIN | upsert SEG |
| POST | `/surveillance/ih-measurements/import` | HSE_OFFICER | `[{sourceRef, segCode, hazardCode, measurementType NOISE_DOSIMETRY|AIR_SAMPLING|BIOLOGICAL|VIBRATION, value, unit, measuredOn}]` → `{imported, duplicates, segsAboveActionLevel, employeesEnrolled}`; above action level enrols the SEG's job codes into the hazard's programme (NOISE ≥ 80 dB(A) → HEARING_CONSERVATION); audited |
| POST | `/integration/ih-measurements` | INTEGRATION | same |
| GET | `/surveillance/ih-measurements?segCode=` | OH roles, HSE_OFFICER | |
| POST | `/surveillance/cosmic-dose/import` | AME, INTEGRATION (also `/integration/cosmic-dose`) | `[{employeeId, year, doseMsv, source}]` → alert `crew.dose-review` above `surveillance.cosmic-dose-alert-msv` (default 6.0) |
| GET | `/surveillance/cosmic-dose?year=&aboveThreshold=` | AME, CMO | `[{employeeId, displayName, year, doseMsv, aboveThreshold, pregnancyDeclared}]` |
| POST | `/me/pregnancy-declaration` | EMPLOYEE (crew) | `{declaredOn}` → AME review case; remaining-pregnancy dose limit 1 mSv flagged (restricted to AME / CMO) |
| GET | `/surveillance/employees/{employeeId}/exposure-history` | OH roles | ODH personal exposure history `[{segCode, hazardCode, from, to, latestValue?, unit}]` + cosmic dose by year |

### 4.7 Result evaluation & auto-follow-up (E07-F07) and return-after-illness (E07-BL-020)
* `ResultFollowUpListener`: for each recorded result of an employee enrolled in an active programme whose panel contains the test, evaluate rules from
  `surveillance.evaluation_rule` – `STS` (E03 device flag ABNORMAL with `sts` detail; programme HEARING_CONSERVATION), `SPIRO_DECLINE` (FEV1 drop
  ≥ 15 % vs previous or FEV1/FVC < 0.70), `BM_LIMIT` (LOINC biomonitoring codes vs limit, e.g. blood lead 5671-3 ≥ 30 µg/dL), `VISION` (flag ABNORMAL),
  `FLAG_ABNORMAL` (any non-NORMAL flag). Abnormal → follow-up record + kernel case `SURVEILLANCE_FOLLOW_UP` (`CaseService.openIdempotent`, sourceRef
  `surveillance:{resultId}`) + recheck task (STS: 30 days) + regulatory-notification task when the rule requires it; `surveillance.follow-up` to the
  programme's responsible role. Target: follow-up created within 48 h (synchronously in practice).

| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/surveillance/follow-ups?status=` | OH roles | `[FollowUp {id, employeeId, displayName, programmeCode, ruleCode, resultId, caseId, caseRef, recheckDueOn?, regulatoryNotificationRequired, status, createdAt, reviewedBy?, reviewDecision?}]` |
| POST | `/surveillance/follow-ups/{id}/review` | Physicians | `{decision CONFIRMED|NOT_CONFIRMED|WORK_RELATED, note?, regulatoryNotificationRequired}` |
| GET / PUT | `/surveillance/evaluation-rules[/{code}]` | OH roles read; OH_ADMIN, CMO write | `[{code, test, ruleType, params, followUpDays, regulatoryNotification, active}]` |
| GET | `/surveillance/return-after-illness?status=` | OH roles | `[Rai {id, employeeId, displayName, reason, source E06|MANUAL, caseId?, status, createdAt, completedAt?}]` |
| POST | `/surveillance/return-after-illness` | Clinicians | `{employeeId, reason, caseId?}` |
| POST | `/surveillance/return-after-illness/{id}/complete` | Clinicians | `{encounterId?, note?}` · `/waive` (Physicians) `{reason}` |

`AbsenceSignalListener` → RAI REQUIRED when the employee's job code is safety-critical. `FitnessClearanceGuard` → "A return-after-illness medical
is required before this employee returns to a safety-critical role" while a REQUIRED item exists.

### 4.8 Compliance dashboards & audit packs (E07-F08)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/surveillance/compliance?groupBy=PROGRAMME|SUBSIDIARY|DEPARTMENT&programme=` | NURSING_HEAD, CMO, OH roles, HSE_OFFICER, AUDITOR, OH_ADMIN | `[{key, label, enrolled, current, due, overdue, compliancePct, suppressed}]` + `refreshedAt` (nightly snapshot `surveillance.compliance_snapshot`, live fallback) |
| GET | `/surveillance/compliance/trend?programme=` | same | `[{date, compliancePct}]` |
| GET | `/surveillance/compliance/overdue?programme=&department=` | OH roles, NURSING_HEAD | drill-down to people (OH only) |
| GET | `/surveillance/audit-packs/templates` | CMO, NURSING_HEAD, AUDITOR, HSE_OFFICER | `[{code IOSA|CAA|FOOD_SAFETY|LABOUR_INSPECTORATE, title, sections[]}]` |
| POST | `/surveillance/audit-packs` | same | `{template, programmeCode, from, to, anonymise}` (AUDITOR / HSE always anonymised) → `{id, generatedAt, programmeCode, template, anonymised, files:[{format:"pdf"|"csv", path}]}` (< 5 min) |
| GET | `/surveillance/audit-packs/{id}/content?format=pdf|csv` | same | |

`CapacityDemandSource`: enrolments DUE / OVERDUE by home site × month × programme `serviceCode`.

## 5. E14 – Scheduling, Clinic Operations & Resource Management (`com.wellnessz.e14`, schema `scheduling`)

Enumerations: `AppointmentStatus` BOOKED | CHECKED_IN | IN_PROGRESS | ATTENDED | NO_SHOW | CANCELLED · `ResourceType` CLINICIAN | ROOM | DEVICE ·
`RoomType` CONSULT | AUDIO_BOOTH | TREATMENT | VACCINATION | TELEHEALTH · `CampaignType` SURVEILLANCE | VACCINATION | MOBILE_CLINIC ·
`CampaignStatus` PLANNED | OPEN | IN_PROGRESS | CLOSED · `EquipmentCategory` AUDIOMETER | SPIROMETER | ECG | VISION_SCREENER | VACCINE_FRIDGE | OTHER ·
`EquipmentStatus` IN_SERVICE | OUT_OF_SERVICE | RETIRED · `CalibrationState` (derived) VALID | DUE_SOON (≤ 30 d) | OVERDUE · `ExcursionStatus` OPEN | ACKNOWLEDGED | CLOSED ·
`WaitlistStatus` WAITING | OFFERED | BOOKED | CANCELLED.

### 5.1 Appointment types, service catalogue, sites (E14-F01, BL-010)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/scheduling/appointment-types` | authenticated | `[AppointmentType {code, name, durationMinutes, clinicianRoles[], roomType?, deviceCategory?, telehealth, selfBookable, chargeCode?, internalPrice?, active}]` |
| PUT | `/scheduling/appointment-types/{code}` | OH_ADMIN, NURSING_HEAD | upsert |
| GET | `/scheduling/sites/{siteCode}/resources` | OH roles, CLINIC_SCHEDULER, CLINIC_RECEPTION, BIOMEDICAL, OH_ADMIN | `[Resource {id, siteCode, type, code, name, username?, roomType?, equipmentId?, active}]` · POST (OH_ADMIN, NURSING_HEAD) upsert |
| GET / PUT | `/scheduling/sites/{siteCode}/templates` | read: as above · write: OH_ADMIN, NURSING_HEAD, CLINIC_SCHEDULER | weekly diary templates `[{weekday 1-7, start "08:00", end "16:00", clinicianUsername, roomCode?, appointmentTypes[], telehealth}]` |
| GET | `/scheduling/service-catalogue` | authenticated | types with charge codes |
| GET | `/scheduling/charging/usage?month=YYYY-MM` | OH_ADMIN, CMO | `[{subsidiaryCode, chargeCode, service, appointments, amount}]` · `/scheduling/charging/export?month=` → `text/csv` (finance export) |

Seeded types: GENERAL_OH (30), NURSE_REVIEW (20), PHYSICIAN_REVIEW (30, OH_PHYSICIAN), AUDIOMETRY (20, AUDIO_BOOTH, AUDIOMETER), SPIROMETRY (20, SPIROMETER),
VISION (15, VISION_SCREENER), ECG (20, ECG), VACCINATION (10, VACCINATION room, self-bookable), PRE_EMPLOYMENT (45), CREW_AEROMEDICAL (60, AME),
FOOD_HANDLER_CLEARANCE (20), RTW_REVIEW (30), ABSENCE_REVIEW (30), SURVEILLANCE_PANEL (40, AUDIO_BOOTH), TELEHEALTH_CONSULT (20, telehealth).

### 5.2 Diary & booking (E14-F01)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/scheduling/diary?siteCode=&date=&clinician=` | OH roles, CLINIC_SCHEDULER, CLINIC_RECEPTION | `{siteCode, date, sessions:[{clinicianUsername, clinicianName, roomCode?, start, end, appointmentTypes[], telehealth}], appointments:[Appointment]}` |
| GET | `/scheduling/slots?type=&siteCode=&from=&to=&clinician=` | OH roles, CLINIC_SCHEDULER | `[{startsAt, endsAt, clinicianUsername, roomCode?, deviceId?}]` (free, conflict-checked) |
| POST | `/scheduling/appointments` | OH roles, CLINIC_SCHEDULER | `{employeeId, appointmentType, siteCode, startsAt, clinicianUsername?, roomCode?, deviceId?, caseId?, adminNote? (screened), sourceType?, sourceRef?}` → `Appointment`; 409 `resource_conflict` (clinician / room / device / employee overlap) ; `appointment.booked` to the employee |
| GET | `/scheduling/appointments?siteCode=&date=&employeeId=&status=` | OH roles, CLINIC_SCHEDULER, CLINIC_RECEPTION | `[Appointment {id, employeeId, displayName, appointmentType, appointmentTypeName, siteCode, startsAt, endsAt, status, clinicianUsername?, roomCode?, deviceId?, campaignId?, caseId?, sourceType?, ticket?, checkedInAt?, calledAt?, telehealth, version}]` |
| PUT | `/scheduling/appointments/{id}/move` | OH roles, CLINIC_SCHEDULER | `{startsAt, clinicianUsername?, roomCode?, version}` (drag-and-drop; optimistic locking 409 `stale`) |
| POST | `/scheduling/appointments/{id}/cancel` | OH roles, CLINIC_SCHEDULER | `{reason}` → waitlist offer |
| POST | `/scheduling/appointments/{id}/attendance` | OH roles, CLINIC_SCHEDULER, CLINIC_RECEPTION | `{status ATTENDED|NO_SHOW}` |
| POST | `/scheduling/appointments/{id}/telehealth` | clinician of the appointment, the employee | `{joinUrl, documentationLink}` (`wellnessz.scheduling.telehealth-base-url` + random room id; video by E04 later) |
| GET / POST | `/scheduling/waitlist` | OH roles, CLINIC_SCHEDULER | `{employeeId, appointmentType, siteCode, earliest, latest, priority}`; cancellation offers the slot (`appointment.waitlist-offer`) |
| GET | `/me/appointments` | EMPLOYEE, CANDIDATE | own appointments |
| GET | `/me/appointments/slots?type=&siteCode=&from=&to=` | EMPLOYEE | self-bookable types only |
| POST | `/me/appointments` | EMPLOYEE | `{appointmentType, siteCode, startsAt}` – self-booking rules: type `selfBookable`, ≥ `scheduling.self-booking.min-notice-hours` (24) ahead, ≤ 60 days, one open booking per type |
| POST | `/me/appointments/{id}/reschedule` | EMPLOYEE, CANDIDATE | `{startsAt}` · `/cancel` `{reason?}` · `/check-in` (app check-in on the day, within 60 min of start) |

Jobs: `appointment-reminders` (T−48 h and T−2 h, `appointment.reminder`, link to the employee-app appointments page), `appointment-no-show`
(BOOKED / CHECKED_IN not started 30 min after end → NO_SHOW, listeners notified). Every status change calls the `AppointmentStatusListener`s whose
`supports(sourceType)` is true.

### 5.3 Campaign & mobile-clinic scheduling (E14-F02)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET / POST | `/scheduling/campaigns` | CLINIC_SCHEDULER, NURSING_HEAD, OH_ADMIN (read: OH roles) | `{name, campaignType, siteCode, appointmentType, days:[date], dayStart, dayEnd, slotMinutes, stations, deviceKit:[equipmentId], staff:[username], targetCount}` → `Campaign` |
| GET | `/scheduling/campaigns/{id}` | same | + `performance:{slots, booked, attended, noShow, utilisationPct, attendancePct}` |
| POST | `/scheduling/campaigns/{id}/generate-slots` | CLINIC_SCHEDULER, NURSING_HEAD | → `{slots}` (days × stations × slots) |
| POST | `/scheduling/campaigns/{id}/book` | CLINIC_SCHEDULER, NURSING_HEAD | `{employeeIds:[…≤ 5000]}` → `{booked, waitlisted}` (500-person audiometry day in one call) |
| POST | `/scheduling/campaigns/{id}/status` | same | `{status OPEN|IN_PROGRESS|CLOSED}` |
| POST | `/scheduling/campaigns/{id}/attendance` | OH roles, CLINIC_SCHEDULER, CLINIC_RECEPTION | `[{appointmentId, status ATTENDED|NO_SHOW}]` |

Device kits must be IN_SERVICE and in calibration on every campaign day (422 `device_out_of_calibration`).

### 5.4 Check-in & queue (E14-F03)
| Method | Path | Roles | Notes |
|---|---|---|---|
| POST | `/scheduling/check-in` | CLINIC_RECEPTION, CLINIC_SCHEDULER, OH roles (kiosk signs in as reception) | `{siteCode, badgeId | employeeId | appointmentId}` → `{appointmentId, ticket "A012", appointmentTypeName, position, estimatedWaitMinutes}` – today's appointment at the site |
| GET | `/scheduling/queue?siteCode=` | CLINIC_RECEPTION, CLINIC_SCHEDULER, OH roles | queue board (no names): `{siteCode, averageWaitMinutesToday, entries:[{ticket, appointmentTypeName, status WAITING|CALLED|IN_PROGRESS, checkedInAt, waitMinutes, roomCode?}]}` |
| POST | `/scheduling/queue/call-next` | Clinicians | `{siteCode, roomCode?}` → next waiting appointment matching the clinician's role / types: `{appointmentId, ticket, employeeId, displayName, appointmentType, caseId?, encounterLink:"#/employees/{id}/chart/encounters"}` (encounter hand-off) |
| POST | `/scheduling/appointments/{id}/start` · `/complete` | Clinicians | IN_PROGRESS → ATTENDED |

### 5.5 Equipment & cold-chain register (E14-F04)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/scheduling/equipment?siteCode=&category=` | BIOMEDICAL, OH_ADMIN, OH roles, CLINIC_SCHEDULER | `[Equipment {id, assetTag, deviceId, category, model, serialNumber, siteCode, status, calibrationIntervalMonths, lastCalibratedOn?, calibrationDueOn?, calibrationState, lastCertificateNumber?, minTempC?, maxTempC?, lastReading?:{temperatureC, recordedAt}, openExcursions}]` |
| POST | `/scheduling/equipment` · PUT `/scheduling/equipment/{id}` | BIOMEDICAL, OH_ADMIN | register / update |
| POST | `/scheduling/equipment/{id}/calibrations` | BIOMEDICAL | `{performedOn, vendor, certificateNumber, result PASS|FAIL, nextDueOn?, documentBase64?}` → FAIL sets OUT_OF_SERVICE |
| GET | `/scheduling/equipment/{id}/calibrations` | as GET equipment | history |
| POST | `/scheduling/equipment/{id}/status` | BIOMEDICAL | `{status, reason}` |
| GET | `/scheduling/equipment/calibration-due?withinDays=30` | BIOMEDICAL, OH_ADMIN, NURSING_HEAD | |
| POST | `/scheduling/equipment/{id}/temperatures` | Clinicians, BIOMEDICAL | manual reading `{temperatureC, recordedAt?}` |
| POST | `/integration/cold-chain/readings` | INTEGRATION | IoT `[{deviceId, temperatureC, recordedAt}]` |
| GET | `/scheduling/equipment/{id}/temperatures?from=&to=` | as GET equipment | `[{recordedAt, temperatureC, source MANUAL|IOT, excursion}]` |
| GET | `/scheduling/excursions?status=` | Clinicians, BIOMEDICAL, NURSING_HEAD | `[{id, equipmentId, assetTag, siteCode, startedAt, endedAt?, minC, maxC, status, action?, acknowledgedBy?}]` |
| POST | `/scheduling/excursions/{id}/acknowledge` | Clinicians, NURSING_HEAD | `{action}` · `/close` |
| GET | `/scheduling/cold-chain/report?equipmentId=&month=` | Clinicians, BIOMEDICAL, NURSING_HEAD | daily min/max/excursions |

Fridge thresholds default 2–8 °C (per equipment). A reading outside → OPEN excursion + `coldchain.excursion` to clinicians of the site (staff
`site_codes`) and the nursing head. Daily `equipment-calibration` job: `equipment.calibration-due` to BIOMEDICAL (config `notifications.biomedical.email`) at 30 days.
`DeviceCalibrationCheck`: equipment by `deviceId` – none → UNKNOWN; OUT_OF_SERVICE / RETIRED → OUT_OF_SERVICE; `calibrationDueOn < date` → OUT_OF_CALIBRATION; else VALID.
Demo: `AUD-LHR-01` (valid), `AUD-LHR-02` (overdue – blocked), `SPIRO-HQ-01`, `ECG-HQ-01`, `VIS-LHR-01`, `FRIDGE-HQ-01` (readings incl. one excursion), `FRIDGE-LHR-01`.
**The increment-2 E2E device id must stay accepted** (unknown or valid).

### 5.6 Capacity planning & utilisation (E14-F05)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/scheduling/capacity?from=&to=&siteCode=` | NURSING_HEAD, CMO, OH_ADMIN, CLINIC_SCHEDULER | `[{siteCode, siteName, month, capacityAppointments, bookedAppointments, forecastDemand, demandBySource:{E07:n, E06:n, RUN_RATE:n}, gap, utilisationPct}]` – capacity from diary templates (minutes / mean type duration), demand from `CapacityDemandSource`s + 3-month run rate |
| POST | `/scheduling/capacity/what-if` | same | `{siteCode, extraClinicianSessionsPerWeek, extraCampaignDays, from, to}` → same rows recalculated |
| GET | `/scheduling/utilisation?from=&to=&groupBy=SITE|CLINICIAN` | same | `[{key, label, sessionsMinutes, bookedMinutes, utilisationPct, noShowRatePct, averageWaitMinutes}]` |

## 6. Events (outbox) – identifiers and status only

`absence.trigger.raised.v1 {queueItemId, employeeId, action}` · `absence.lta.started.v1 {caseId, employeeId}` ·
`absence.advice.released.v1 {requestId, employeeId, documentId}` · `surveillance.enrolment.due.v1 {enrolmentId, employeeId, programmeCode, nextDueOn}` ·
`surveillance.followup.created.v1 {followUpId, employeeId, caseId}` · `preemployment.status.v1` (destination `hr-systems`: `{messageId, offerRef, candidateId, preEmploymentStatus, effectiveFrom}`) ·
`crew.certificate.status.v1 {employeeId, certificateType, validUntil, status}` · `appointment.status.v1 {appointmentId, employeeId, status}` ·
`equipment.calibration.v1 {equipmentId, calibrationState}` · `coldchain.excursion.v1 {equipmentId, excursionId}`.

## 7. Notification templates (approved, content-free – already in `NotificationTemplates`)

`absence.oh-engaged`, `absence.milestone-overdue`, `absence.advice-requested`, `absence.advice-review`, `absence.advice-released`, `absence.advice-declined`,
`absence.certificate-received`, `absence.certificate-escalation`, `surveillance.invitation`, `surveillance.overdue-manager`, `surveillance.follow-up`,
`crew.medical-expiring`, `crew.medical-expiring-licensing`, `crew.dose-review`, `pre-employment.invitation`, `pre-employment.status`, `immunisation.due`,
`appointment.booked`, `appointment.cancelled`, `appointment.waitlist-offer`, `appointment.reminder` (existing), `coldchain.excursion`, `equipment.calibration-due`.
Placeholders: `firstName, appointmentDate, appointmentTime, caseRef, portalLink, dueDate, unit, urgency, nextStep, contact, queue, level, documentNumber, employeeName, site`.
Portal links: employee app `#/appointments`, `#/absence`, `#/surveillance`, `#/advice`; manager workspace `#/absence`, `#/advice-requests`, `#/compliance`; OH workspace `#/absence/queue`, `#/surveillance/follow-ups`, `#/scheduling/equipment`.

## 8. UI routes (hash routing)

* **oh-workspace** (nav in `src/auth/roles.ts`): `#/absence/queue`, `#/absence/lta` (+ `/{caseId}`), `#/absence/certificates`, `#/absence/advice`, `#/absence/rules`, `#/absence/analytics`, `#/absence/feed`;
  `#/surveillance/programmes` (+ builder `/{code}`), `#/surveillance/due`, `#/surveillance/follow-ups`, `#/surveillance/pre-employment`, `#/surveillance/crew`, `#/surveillance/immunisation` (fast entry + batch), `#/surveillance/exposure`, `#/surveillance/compliance` (+ audit packs), `#/surveillance/rai`;
  `#/scheduling/diary`, `#/scheduling/campaigns` (+ `/{id}`), `#/scheduling/queue` (board) and `#/scheduling/reception` (check-in kiosk), `#/scheduling/equipment` (+ cold chain), `#/scheduling/capacity`, `#/scheduling/catalogue`.
  Chart tab `#/employees/{id}/chart/absence` and `/surveillance` (absence timeline, programmes, immunisations, exposure history).
  Role visibility: CLINIC_SCHEDULER → diary, campaigns, due list, catalogue; CLINIC_RECEPTION → reception, queue board, certificates (scan / validate); BIOMEDICAL → equipment; CREW_LICENSING → crew certificates; HSE_OFFICER → exposure, compliance; AUDITOR → compliance / audit packs.
* **employee-app**: `/appointments` (book / reschedule / cancel / check-in), `/absence` (episodes, upload certificate with camera), `/advice` (review advice report: consent / decline with comment), `/surveillance` (my programmes, immunisations, certificate download, pregnancy declaration for crew), `/pre-employment` (CANDIDATE: questionnaire, consent, documents, booking). en / ar / fr strings.
* **manager-workspace**: `/absence` (team absence status, OH engaged, certificate validity, LTA status / HR stage for HR), `/advice-requests` (request form with question library, list with released reports), `/compliance` (team surveillance compliance), `/pre-employment` (HR onboarding status).

## 9. Tests & non-functional

Per module: unit tests for rule engines (trigger rules incl. Monday/Friday pattern, Bradford factor, certificate duplicate / alteration checks, milestone plan;
eligibility & due-date calculation, STS / spirometry decline, GS1 lot parsing, crew expiry alert bands; slot generation, conflict detection, excursion detection, capacity maths).
Integration tests in `apps/wz-core-app/src/test/java/com/wellnessz/app/Increment3{Absence|Surveillance|Scheduling}IntegrationTest.java` extending `IntegrationTestBase`
(own test DB while building: `wellnessz_test_e06`, `_e07`, `_e14`), covering role denials (manager never sees reason category / certificate content / results;
scheduler never sees clinical data), the 422 content screens and the SPI interplay (device calibration block in E03, clearance guard in E05).
All new scheduled jobs use `JobRunGuard.tryStart(job, runKey)`. Every read of person-level data writes the audit trail; every disclosure to a manager / HR writes the disclosure log.
