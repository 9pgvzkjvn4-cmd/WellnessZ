# Increment 4 – E04 Injury & Illness Management, Treatment Coordination & Partner Referrals and E08 Workers' Compensation, Insurance & Claims Coordination: API and Domain Contract

Source of truth shared by the backend (`modules/wz-e04-treatment`, `modules/wz-e08-claims`, platform / kernel extensions)
and the UIs (`web/oh-workspace`, `web/employee-app`, `web/manager-workspace` and the new **`web/partner-portal`**).
Conventions are unchanged from increments 1–3: paths relative to `/api/v1` (browser apps call `/bff/api/...`), RFC 9457
problem details `{type,title,status,detail,code,correlationId,field?}`, ISO-8601 UTC timestamps, `YYYY-MM-DD` dates, plain
JSON arrays unless stated, null fields omitted, denied record access reported as `404`, `422` for business-rule violations
(with `code`), `409` for conflicts. Free text shown outside OH is screened with `ClinicalContentException.screen(field, value)`
→ 422 `clinical_content_not_allowed` with `field`. Money is `{amount: "1234.50", currency: "GBP"}` (decimal string, ISO 4217).
`/api/v1/integration/**` is reserved for machine clients (INTEGRATION). Partner-hospital and insurer / TPA clients are
bound to their organisation by a fixed `org_scope=/PARTNERS/<CODE>` claim; the referral, pre-authorisation and
first-report endpoints below answer `404` to any client that is not the provider / payer / recipient organisation
(including unbound gateway clients).

**Confidentiality rules.**
1. Partner providers see only the referrals, telehealth sessions and documents addressed to **their own organisation**
   (org code = last segment of `org_scope`, e.g. `/PARTNERS/CITYHOSP` → `CITYHOSP`), and only under the employee's active
   `TREATMENT_SHARING_PARTNER` consent.
2. TPA adjusters / insurers see only claims where their organisation is the payer, RTW **status and milestones only**, shared
   tasks and messages, and documents disclosed under the employee's active `INSURER_SHARING` consent. Never clinical notes.
3. Claims coordinators, finance, legal and procurement never read the OH clinical record. Finance sees money by unit, not by person.
4. Every disclosure outside OH (partner, insurer, authority, HR, legal) writes the disclosure log.
5. **MFA**: the core API grants `PARTNER_PROVIDER` and `TPA_ADJUSTER` only when the access token proves multi-factor
   authentication (`amr` ∈ {mfa, otp, totp, hwk, swk, sms, webauthn, fido} or `acr` / an entry of the Entra ID `acrs` array ∈ {2, 3, mfa, c1});
   `wellnessz.security.mfa-required-roles` (env `WZ_MFA_REQUIRED_ROLES`) – on by default, off in the `local` profile (the demo realm has no OTP enrolment).

## 1. Roles

New roles (realm, `Roles.java`, Keycloak scope mappings of `wellnessz-bff` / `wellnessz-mobile` – done):

| Role | Purpose | Demo user (employeeId, org_scope) |
|---|---|---|
| `CLAIMS_COORDINATOR` | claims, e-reporting, costs, disclosure sets, pre-authorisation follow-up | `claims1` (S8711, /AIRLINE) |
| `TPA_ADJUSTER` | external adjuster (partner portal) | `adjuster1` (X8702, /PARTNERS/GULFTPA) |
| `LEGAL_COUNSEL` | litigation hold, privilege, medico-legal requests | `legal1` (S8703, /AIRLINE) |
| `FINANCE` | cost roll-ups, budgets, ERP export | `finance1` (S8704, /AIRLINE) |
| `PROCUREMENT` | provider directory, contracts, rates, SLAs | `procurement1` (S8705, /AIRLINE) |
| `PARTNER_PROVIDER` (existing) | partner provider portal | `partner1` (P9101, /PARTNERS/CITYHOSP), `physio1` (X8706, /PARTNERS/PHYSIOPLUS) |

"OH roles", "Clinicians", "Physicians" as before. `CASE_MANAGER` = nurse case manager.

## 2. Platform additions (done – do not change their signatures)

### 2.1 Kernel SPIs (`platform/wz-case-kernel/.../casekernel/support`)
| SPI | Implemented by | Called by | Purpose |
|---|---|---|---|
| `CaseCostRecorder` | E08 | E04 (pre-auth estimates, provider invoices) | `record(actor, CostEntry)` → ledger id; idempotent on (sourceType, sourceRef, kind) |
| `ExternalReferralGateway` | E04 | E08 (IME / expert referrals) | `requestReferral(actor, ReferralRequest)` → `Optional<ReferralRef>` (DRAFT referral for OH to complete) |
| `ReferralStatusListener` | E08 | E04 | `referralStatusChanged(referralId, sourceType, sourceRef, Status, reportDocumentId)` for supported sourceTypes (e.g. `CLAIM_IME`) |
| `ClaimContextProvider` | E08 | E04 (pre-auth payer default), case header | `forCase(caseId)` → `ClaimContext(claimId, claimRef, claimType, status, payerCode, lostTime, litigationHold)` |
| existing: `AppointmentBooking` / `AppointmentStatusListener` (E14), `CaseTransitionGuard`, `ChartContributor`, `ResultFollowUpListener` | | | |

### 2.2 Master data – partner organisations
`GET /masterdata/partner-organisations?types=HOSPITAL,CLINIC,...` (OH roles, OH_ADMIN, CLAIMS_COORDINATOR, PROCUREMENT, FINANCE, LEGAL_COUNSEL, HSE_OFFICER, CLINIC_SCHEDULER) →
`[{code, name, orgType, countryCode, city, contactEmail, phone, portalEnabled, active}]`; `POST` upsert (PROCUREMENT, OH_ADMIN, PLATFORM_ADMIN, INTEGRATION).
`orgType`: HOSPITAL, CLINIC, SPECIALIST, PHYSIOTHERAPY, IMAGING, LABORATORY, MENTAL_HEALTH, TELEHEALTH, ASSISTANCE, INSURER, TPA, AUTHORITY.
Seeded (V13): 20 providers (CITYHOSP, HEATHROWMED, STMARYS_ORTHO, CARDIO_LDN, EYE_LDN, ENT_AUDIO, DERM_LDN, PHYSIOPLUS, REHAB_HUB, LDN_IMAGING, PATHLAB, MINDCARE,
OCC_PSYCH, TELEDOC, GLOBALASSIST, DXB_AMERICAN, DXB_PHYSIO, JFK_URGENT, SIN_MEDICAL, HKG_CLINIC), payers GULFTPA (TPA), AVIA_INSURE, GROUPMED (INSURER), authorities AUTH_OSH, AUTH_LABOUR_AE.

### 2.3 Documents (`DocumentService`)
* New share target **`INSURER`** (recipient = insurer / TPA org code, requires the employee's active `INSURER_SHARING` consent, disclosure purpose `CLAIM`); TPA adjusters read via `GET /partner/documents` (now PARTNER_PROVIDER or TPA_ADJUSTER).
* `shareAsSystem(actor, documentId, target, organisation, consentId)` – workflow share to PARTNER (consent TREATMENT_SHARING_PARTNER) or INSURER (consent INSURER_SHARING).
* `legalHoldForCase(actor, caseId, hold, reason)` – every document of the case on / off legal hold (suspends retention).
* Upload types added: `PROVIDER_REPORT`, `PROVIDER_INVOICE`, `INSURER_ACKNOWLEDGEMENT`, `REDACTED_COPY`.
* Templates added: `TREATMENT_PLAN` (employee, clinical), `PREAUTH_REQUEST` (insurer, clinical, consent), `FIRST_REPORT_OF_INJURY` (insurer / authority; generators include CLAIMS_COORDINATOR), `MEDICO_LEGAL_REPORT` (legal access set). Existing `REFERRAL_LETTER` (partner).
* Retention class `CLAIM_RECORD` (10 y, suspended by litigation hold).
* Consent purpose added: `TELEHEALTH_CONSULTATION`.

### 2.4 Notification templates (content-free, in `NotificationTemplates`; placeholder `reference` added)
`referral.sent`, `referral.chase`, `referral.overdue-team`, `referral.appointment`, `treatment.follow-up`, `treatment.variance`, `preauth.status`, `telehealth.invite`,
`claim.assigned`, `claim.report-due`, `claim.task`, `claim.message`, `legal.request`. Partner portal links: `https://partners.<domain>/#/referrals/{id}` (config `wellnessz.notification.partner-portal-url`, default `https://partners.wellnessz.local`).

### 2.5 BFF & deployment
* `/bff/api/workspace/claims/**` keeps its prefix (HR claim status). Partner portal on port **5176** locally (`partners.<domain>` via Helm `partnerPortal`), same-origin BFF login; Keycloak redirect URIs and CORS include 5176.
* Schemas `treatment` (V14, E04) and `claims` (V15, E08) – each migration starts with `create schema if not exists …;` (lists in application.yml, Helm, local init, managed bootstrap, CNPG already updated).
* Outbound allow-lists: add to `application.yml` `wellnessz.integration.outbound-allowlist` (lead adds): `insurer-gateway: [messageId, claimRef, reportRef, formCode, payerCode, employeeId, incidentDate, submittedAt, deadlineAt]`,
  `authority-gateway: [messageId, reportRef, formCode, jurisdiction, employerRef, incidentDate, submittedAt, deadlineAt]`, `erp-finance: [messageId, batchId, month, costCentre, subsidiaryCode, category, amount, currency, entryCount, checksum]`.
  Payloads carry references; documents travel only through consent-checked shares.

## 3. E04 – Treatment coordination & partner referrals (`com.wellnessz.e04`, schema `treatment`, V14)

Enumerations: `PlanStatus` DRAFT | ACTIVE | COMPLETED | CANCELLED · `InterventionType` PHYSIOTHERAPY | MEDICATION | SPECIALIST_REVIEW | IMAGING | SELF_CARE | PSYCHOLOGICAL_THERAPY | OH_REVIEW | OTHER ·
`FollowUpType` OH_REVIEW | PHONE | PHYSIOTHERAPY | SPECIALIST | TELEHEALTH · `FollowUpStatus` PLANNED | BOOKED | CONFIRMED | DECLINED | DONE | MISSED | CANCELLED ·
`ReferralStatus` DRAFT | SENT | ACCEPTED | DECLINED | SCHEDULED | SEEN | REPORT_RECEIVED | CLOSED | CANCELLED · `Urgency` ROUTINE | URGENT ·
`Channel` PORTAL | FHIR | SECURE_EMAIL · `PreauthStatus` DRAFT | SUBMITTED | APPROVED | PARTIALLY_APPROVED | DECLINED | EXPIRED | CANCELLED ·
`InvoiceStatus` RECEIVED | MATCHED | DISPUTED | APPROVED | PAID · `OutcomeMilestone` BASELINE | REVIEW | CLOSURE · `TelehealthStatus` SCHEDULED | IN_PROGRESS | COMPLETED | CANCELLED | NO_SHOW.

### 3.1 Treatment plan & follow-up (E04-F01)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/treatment/plan-templates` | OH roles | `[{code, injuryType, title, goals[], interventions[{type, description, frequency, weeks}], followUpDays[], guidelineCode?, expectedDays?}]` – seeded: BACK_STRAIN, SHOULDER_STRAIN, WRIST_SPRAIN, KNEE_SPRAIN, HAND_LACERATION, CONCUSSION_MILD, WORK_STRESS, NOISE_HEARING |
| POST | `/cases/{caseId}/treatment-plans` | Clinicians | `{templateCode?, goals[≥1], interventions[{type, description, frequency?, startDate?, endDate?, providerCode?}], followUps[{dueDate, type, siteCode?, book?}], expectedRecoveryDays?, guidelineCode?, varianceThresholdPct?}` → `TreatmentPlan` (ACTIVE when `activate=true` query param, else DRAFT). Follow-ups create kernel case tasks; `book:true` books through `AppointmentBooking` (sourceType `TREATMENT_FOLLOW_UP`); employee reminder `treatment.follow-up` |
| GET | `/cases/{caseId}/treatment-plans` | OH roles | `[TreatmentPlan {id, caseId, caseRef, employeeId, status, version, goals, interventions, followUps[{id, dueDate, type, status, appointmentId?, employeeResponse?}], expectedRecoveryDate, guideline:{code, title, expectedDays}?, variance:{actualDays, expectedDays, pctOver, alert}, responsibleClinician, documentId?, createdAt}]` |
| PUT | `/treatment-plans/{id}` | Clinicians | new version (previous SUPERSEDED in history) |
| POST | `/treatment-plans/{id}/status` | Clinicians | `{status ACTIVE|COMPLETED|CANCELLED, note?}` |
| POST | `/treatment-plans/{id}/follow-ups/{followUpId}` | Clinicians | `{status DONE|MISSED|CANCELLED, note?}` |
| POST | `/treatment-plans/{id}/print` | Clinicians | `TREATMENT_PLAN` generated, signed and shared to the employee → `Document` |
| GET | `/treatment/variance-alerts?status=OPEN` | OH roles | `[{id, caseId, caseRef, employeeId, displayName, planId, expectedDays, actualDays, pctOver, status OPEN|ACKNOWLEDGED|ESCALATED, raisedAt}]` – nightly `treatment-variance` job; threshold `treatment.variance.threshold-pct` (20) or plan override; `treatment.variance` to the responsible clinician |
| POST | `/treatment/variance-alerts/{id}/acknowledge` · `/escalate` | OH roles | `{note?}`; escalate notifies the Nursing Head / CMO |
| GET | `/me/treatment-plans` | EMPLOYEE | plain-language plan: goals, interventions, follow-ups, external appointments |
| POST | `/me/treatment-plans/{id}/follow-ups/{followUpId}/response` | EMPLOYEE | `{response CONFIRM|DECLINE, reason? (screened)}`; a NO_SHOW of the booked appointment marks the follow-up MISSED |

### 3.2 e-Referral management (E04-F02)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/referrals/services` | OH roles, CLAIMS_COORDINATOR | `[{code, name, providerTypes[], requiredFields[{code, label, type TEXT|DATE|CODE|LIST}], reportDueDays, urgentAppointmentHours, routineAppointmentDays}]` – ORTHOPAEDICS, PHYSIOTHERAPY, IMAGING_MRI, IMAGING_XRAY, CARDIOLOGY, OPHTHALMOLOGY, ENT_AUDIOLOGY, DERMATOLOGY, MENTAL_HEALTH_THERAPY, PSYCHIATRY, LAB_TESTS, TELEHEALTH_SPECIALIST, ASSISTANCE_LAYOVER, IME |
| POST | `/cases/{caseId}/referrals` | Clinicians | wizard `{serviceCode, urgency, providerCode, reason, question, dataSet:{fieldCode:value}, consentId?, channel, attachDocumentIds[]}` → `Referral` DRAFT |
| PUT | `/referrals/{id}` | Clinicians | edit while DRAFT |
| POST | `/referrals/{id}/send` | Clinicians | validates required data set and active `TREATMENT_SHARING_PARTNER` consent (422 `consent_required`), generates + signs `REFERRAL_LETTER` and the structured payload (FHIR R4 `ServiceRequest`), shares letter + attachments to the provider (PARTNER), sets `reportDueAt`, notifies the provider contact (`referral.sent`) → SENT |
| GET | `/referrals?status=&overdue=&mine=&serviceCode=&providerCode=` | OH roles | worklist `[Referral {id, ref "RFL-2026-000001", caseId, caseRef, employeeId, displayName, serviceCode, serviceName, providerCode, providerName, contracted, preferred, urgency, status, channel, createdAt, sentAt?, acceptedAt?, appointmentAt?, seenAt?, reportDueAt?, reportReceivedAt?, reportDocumentId?, overdue, chaseCount, escalated, rating?}]` |
| GET | `/referrals/{id}` | OH roles | + `reason, question, dataSet, letterDocumentId, attachments, history:[{status, at, by}], fhir?` |
| POST | `/referrals/remind` | OH roles | bulk `{referralIds[≤200]}` → `{reminded}` |
| POST | `/referrals/{id}/cancel` | Clinicians | `{reason}` |
| POST | `/referrals/{id}/rating` | Clinicians | report-quality rating `{rating 1..5, comment?}` (E04-BL-015) |
| GET | `/cases/{caseId}/referrals` | OH roles | case tab |
| GET | `/partner/referrals?status=` | PARTNER_PROVIDER | own organisation only: `[{id, ref, serviceName, urgency, status, sentAt, appointmentAt?, reportDueAt, patient:{displayName, employeeId, phone?}, reason, question, dataSet, letterDocumentId, attachments:[{documentId,title}]}]` – first read writes the disclosure log |
| POST | `/partner/referrals/{id}/accept` · `/decline` | PARTNER_PROVIDER | decline `{reason}` |
| POST | `/partner/referrals/{id}/appointment` | PARTNER_PROVIDER | `{appointmentAt, location?, instructions? (screened)}` → SCHEDULED; employee `referral.appointment` |
| POST | `/partner/referrals/{id}/seen` | PARTNER_PROVIDER | → SEEN |
| POST | `/partner/referrals/{id}/report` | PARTNER_PROVIDER | multipart `file` (+ `summary?`) → `PROVIDER_REPORT` stored via `storeUpload` and attached to the case → REPORT_RECEIVED (shown on the referral worklist; no notification) |
| POST | `/partner/referrals/{id}/invoice` | PARTNER_PROVIDER | multipart `file` + `invoiceNumber, amount, currency, serviceDate` → invoice (§3.4) |
| GET | `/integration/referrals/{id}/fhir` | INTEGRATION (referral's provider: `org_scope=/PARTNERS/<providerCode>`) | FHIR R4 `ServiceRequest` (partner hospital systems); other clients `404` |
| POST | `/integration/referrals/fhir/document-reference` | INTEGRATION (referral's provider) | FHIR R4 `DocumentReference` with `context.related` = ServiceRequest id and base64 attachment → report received; other clients `404` |

Jobs: `referral-chase` (daily): report overdue → `referral.chase` to the provider (chaseCount+1); after `treatment.referral.max-chases` (2) → `escalated=true` and `referral.overdue-team` to the referring clinician.
`ExternalReferralGateway`: creates a DRAFT referral with sourceType/sourceRef; every status change calls the `ReferralStatusListener`s that support the sourceType.
KPIs on `GET /treatment/kpis?from=&to=` (CMO, NURSING_HEAD): `{medianWorkingDaysReferralToAppointment, urgentWithin48hPct, reportWithin7DaysPct, missedAppointmentPct, avgMedicalCostPerCase}`.

### 3.3 Provider network directory & scorecards (E04-F03)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/providers?serviceCode=&specialty=&city=&countryCode=&contractStatus=&q=&preferredOnly=` | OH roles, PROCUREMENT, CLAIMS_COORDINATOR, CMO | `[Provider {code, name, orgType, city, countryCode, specialties[], services[], languages[], accreditation[{body, validUntil}], coverage HUB|OUTSTATION|LAYOVER, latitude?, longitude?, preferred, portalEnabled, contract:{ref, status ACTIVE|EXPIRING|EXPIRED|NONE, start, end}?, rates[{serviceCode, price}], sla:{appointmentDays, urgentHours, reportDays}?, scorecard:{month, overall, timeliness, reportQuality, outcome, cost}?}]` |
| GET | `/providers/{code}` | same | + contract history |
| PUT | `/providers/{code}` | PROCUREMENT, OH_ADMIN | profile `{specialties, services, languages, accreditation, coverage, preferred, latitude, longitude}` (organisation itself via master data) |
| POST | `/providers/{code}/contracts` | PROCUREMENT | `{ref, start, end, rates[{serviceCode, price}], sla{appointmentDays, urgentHours, reportDays}}` – new version, audit |
| GET | `/providers/network-gaps` | PROCUREMENT, OH_ADMIN, CMO | `[{serviceCode, city, reason}]` services × hub/outstation/layover cities without an ACTIVE contract |
| GET | `/providers/scorecards?month=YYYY-MM` | CMO, NURSING_HEAD, PROCUREMENT | `[{providerCode, providerName, month, referrals, medianDaysToAppointment, reportWithin7DaysPct, avgReportQuality, outcomeImprovedPct, avgInvoice, overall}]` (monthly job `provider-scorecards`, 1st of month; `POST /providers/scorecards/recompute` on demand) |
| GET | `/providers/scorecards/export?month=` | same | `text/csv` review pack |

Seed (V14): profiles, contracts, rate cards and SLAs for the 20 providers (E04-BL-019); demo contract EXPIRING for one, EXPIRED for one.

### 3.4 Pre-authorisation & cost tracking (E04-F04)
| Method | Path | Roles | Notes |
|---|---|---|---|
| POST | `/cases/{caseId}/preauthorisations` | Clinicians, CLAIMS_COORDINATOR | `{payerCode?, serviceCode, providerCode, referralId?, estimate{amount,currency}, justification (clinical – OH roles only; required when an OH role submits), consentId}` → `Preauth` DRAFT; payer defaults from `ClaimContextProvider` |
| POST | `/preauthorisations/{id}/submit` | Clinicians, CLAIMS_COORDINATOR | `PREAUTH_REQUEST` generated + signed and shared `INSURER` (consent INSURER_SHARING, 422 `consent_required`), SLA `treatment.preauth.sla-hours` (48) → SUBMITTED; `CaseCostRecorder` ESTIMATE/MEDICAL |
| POST | `/preauthorisations/{id}/decision` | CLAIMS_COORDINATOR | `{status APPROVED|PARTIALLY_APPROVED|DECLINED, approvalRef?, approvedAmount?, validUntil?, note?}` → `preauth.status` to the requester |
| POST | `/partner/preauthorisations/{id}/decision` | TPA_ADJUSTER (payer org) | same body |
| POST | `/integration/preauthorisations/{ref}/decision` | INTEGRATION (payer: `org_scope=/PARTNERS/<payerCode>`) | same body (insurer system); other clients `404` |
| GET | `/preauthorisations?status=` | OH roles, CLAIMS_COORDINATOR | `[Preauth {id, ref "PA-2026-000001", caseId, caseRef, employeeId, displayName, payerCode, payerName, serviceCode, providerCode, estimate, status, submittedAt?, slaDueAt?, decidedAt?, approvalRef?, approvedAmount?, validUntil?, documentId?}]` |
| GET | `/partner/preauthorisations?status=` | TPA_ADJUSTER | own payer only; `justification` only via the shared document |
| GET | `/cases/{caseId}/preauthorisations` · `/cases/{caseId}/invoices` | OH roles, CLAIMS_COORDINATOR | case tab |
| POST | `/cases/{caseId}/invoices` | CLAIMS_COORDINATOR, FINANCE | `{providerCode, invoiceNumber, amount, currency, serviceDate, referralId?, preauthId?, documentId?}` → `Invoice {id, ..., status, matchedPreauthId?, variance?}`; matching vs approved pre-auth (tolerance `treatment.invoice.tolerance-pct` 5) → MATCHED / DISPUTED; `CaseCostRecorder` INVOICE/MEDICAL |
| POST | `/invoices/{id}/status` | CLAIMS_COORDINATOR, FINANCE | `{status APPROVED|DISPUTED|PAID, note?}` |
| GET | `/treatment/costs?groupBy=SUBSIDIARY|DEPARTMENT|COST_CENTRE&from=&to=` | FINANCE, CMO, CLAIMS_COORDINATOR | `[{key, label, cases, estimated, invoiced, avgPerCase, suppressed}]` + `/treatment/costs/export` (CSV) |

### 3.5 Clinic medication administration & e-prescribing (E04-F05)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET / PUT | `/treatment/formulary[/{code}]` | read OH roles; write OH_ADMIN, NURSING_HEAD | `[{code, name, form, strength, unit, route[], snomedCode?, stockTracked, reorderLevel, active}]` – seeded clinic formulary (paracetamol, ibuprofen, cetirizine, hydrocortisone cream, chlorhexidine, adrenaline auto-injector, salbutamol, oral rehydration salts, tetanus/diphtheria vaccine, lidocaine 1 %) |
| POST | `/treatment/stock` | NURSING_HEAD, OH_CLINICIAN | receive batch `{itemCode, batchNumber, expiry, quantity, siteCode}` |
| GET | `/treatment/stock?siteCode=` | OH roles | `[{itemCode, name, batchNumber, expiry, quantity, siteCode, belowReorder, expired}]` |
| POST | `/cases/{caseId}/medications/administer` | Clinicians | `{itemCode, dose, route, batchNumber, siteCode, overrideAllergyReason?}` → allergy check against the E03 record (422 `allergy_alert` with the alert text unless `overrideAllergyReason`), expired / unknown batch 422 `batch_expired` / `batch_unknown`, stock decrement, mirrored into the E03 medication list (source ADMINISTERED) → `Administration {id, itemCode, name, dose, route, batchNumber, expiry, administeredBy, administeredAt, allergyOverride?}` |
| GET | `/cases/{caseId}/medications/administrations` | OH roles | |
| GET | `/treatment/medication-usage?month=` | NURSING_HEAD, CMO | monthly usage by item and site |
| GET | `/treatment/eprescribing/status` | OH roles | `{enabled:false, route, decisionMemo}` – `EPrescribingGateway` SPI with a no-op default until the legal route is confirmed (E04-BL-013 spike) |

### 3.6 Outcomes & provider performance (E04-F06)
| Method | Path | Roles | Notes |
|---|---|---|---|
| POST | `/cases/{caseId}/outcomes` | Clinicians | `{milestone BASELINE|REVIEW|CLOSURE, painScore 0..10 (NPRS), functionScale NONE|ODI|QUICKDASH|PSFS|LEFS, functionScore 0..100, rtwDate?, residualImpairmentPct 0..100?, note?}` |
| GET | `/cases/{caseId}/outcomes` | OH roles | `[Outcome]` + `{improvement:{painDelta, functionDelta}}` |

`CaseTransitionGuard`: a case with a treatment plan or a referral cannot move to CLOSED without a CLOSURE outcome (422 `outcome_required`).

### 3.7 Telehealth consultation (E04-F07)
| Method | Path | Roles | Notes |
|---|---|---|---|
| POST | `/telehealth/sessions` | Clinicians | `{employeeId, caseId?, scheduledAt, durationMinutes 10..90, mode VIDEO|CHAT, clinicianUsername?, providerCode? (partner specialist, e.g. TELEDOC), crewLayover:boolean, layoverCity?}` → `Session {id, ref, employeeId, displayName, caseId?, scheduledAt, durationMinutes, mode, status, clinicianUsername, providerCode?, crewLayover, layoverCity?, consentGiven, encounterId?}`; `telehealth.invite` to the employee; booking via `AppointmentBooking` (TELEHEALTH_CONSULT) when the clinician has a diary |
| GET | `/telehealth/sessions?from=&to=&status=` | OH roles | |
| GET | `/partner/telehealth/sessions` | PARTNER_PROVIDER | own organisation's sessions |
| GET | `/me/telehealth` | EMPLOYEE | own sessions |
| POST | `/me/telehealth/{id}/consent` | EMPLOYEE | records `TELEHEALTH_CONSULTATION` consent for the session |
| POST | `/telehealth/sessions/{id}/join` · `/partner/telehealth/sessions/{id}/join` · `/me/telehealth/{id}/join` | participant | `{joinUrl, expiresAt}` – single-use participant token (stored hashed, valid from 15 min before start to the end); employee must have consented (422 `consent_required`); provider-agnostic `TelehealthProvider` SPI, default link provider (`treatment.telehealth.base-url`, default `https://telehealth.wellnessz.local/room/`) |
| GET / POST | `/telehealth/sessions/{id}/messages` (also `/partner/...`, `/me/...`) | participants | secure chat `{text ≤ 2000}`, encrypted at rest → `[{id, sender, senderRole, text, sentAt}]` |
| POST | `/telehealth/sessions/{id}/complete` | Clinicians | `{note (OH clinical note), followUpDate?}` → creates a DRAFT E03 encounter (ILLNESS) with the note for signature, optional follow-up on the treatment plan → COMPLETED (`encounterId`) |
| POST | `/telehealth/sessions/{id}/cancel` | Clinicians | `{reason}` |

`ChartContributor`: treatment section (active plan, open referrals, outcomes). Chart tab `#/employees/{id}/chart/treatment`.

## 4. E08 – Claims coordination (`com.wellnessz.e08`, schema `claims`, V15)

Enumerations: `ClaimType` WORKERS_COMP | EMPLOYER_LIABILITY | GROUP_MEDICAL | THIRD_PARTY · `ClaimStatus` NOTIFIED → OPEN → UNDER_REVIEW → SETTLED → CLOSED; LITIGATED from OPEN / UNDER_REVIEW; CLOSED → OPEN (reopen with reason) ·
`CostCategory` MEDICAL | REHAB | INDEMNITY | ADMIN | SETTLEMENT | LEGAL · `CostKind` ESTIMATE | INVOICE | PAYMENT | RESERVE · `ReportStatus` DRAFT | SUBMITTED | ACKNOWLEDGED | REJECTED | LATE ·
`SubmissionChannel` API | EMAIL | PORTAL · `DisclosureSetStatus` DRAFT | SENT | REVOKED · `LegalRequestType` MEDICO_LEGAL_REPORT | EXPERT_OPINION | IME · `LegalRequestStatus` OPEN | IN_PROGRESS | REPORT_READY | CLOSED.

### 4.1 Claim record & lifecycle (E08-F01)
| Method | Path | Roles | Notes |
|---|---|---|---|
| POST | `/cases/{caseId}/claims` | CLAIMS_COORDINATOR, CASE_MANAGER | `{claimType, payerCode, policyNumber?, adjusterName?, adjusterEmail?, adjusterUsername?, lostTime, incidentDate?, notes? (screened)}` → `Claim` NOTIFIED (one per case and type – 409 `claim_exists`); lost-time → nurse assignment (§4.4) |
| GET | `/claims?status=&type=&lostTime=&payerCode=&q=` | CLAIMS_COORDINATOR, NURSING_HEAD, LEGAL_COUNSEL (litigated / held only), CMO | `[Claim {id, claimRef "CLM-2026-000001", caseId, caseRef, employeeId, displayName, unit, claimType, status, payerCode, payerName, policyNumber?, adjuster:{name, email, username}?, lostTime, incidentDate, notifiedAt, openedAt?, settledAt?, closedAt?, nurseCaseManager?, nurseAssignedAt?, reserves:{category: amount}?, paidTotal?, subrogation?:{…}, litigationHold, privileged, openTasks, reportStatus?}]` – money only for CLAIMS_COORDINATOR / FINANCE / CMO |
| GET | `/claims/{id}` | same | + `history`, `reports`, `disclosureSets`, `legalRequests` summary |
| PUT | `/claims/{id}` | CLAIMS_COORDINATOR | payer, policy, adjuster, lostTime, reserves `{MEDICAL:{amount,currency},…}` |
| POST | `/claims/{id}/status` | CLAIMS_COORDINATOR (LITIGATED also LEGAL_COUNSEL) | `{status, reason?}` – invalid transition 422 `invalid_transition` |
| GET | `/cases/{caseId}/claim-status` | OH roles, CLAIMS_COORDINATOR | case header badge `{claimId, claimRef, claimType, status, lostTime, litigationHold}` (+ `paidTotal` for CLAIMS_COORDINATOR) – or 204 when no claim |
| POST | `/claims/{id}/subrogation` | CLAIMS_COORDINATOR | `{thirdPartyName, thirdPartyType CONTRACTOR|VEHICLE_OWNER|OTHER, thirdPartyInsurer?, reference?, description (screened)}` → flag + legal task (`legal.request` to `claims.legal-email`) |
| GET | `/claims/reconciliation?month=` | CLAIMS_COORDINATOR, FINANCE | closed claims of the month: `[{claimRef, paidTotal, ledgerTotal, difference, reconciled}]` |
| GET | `/workspace/claims` | HR (scope) | status only `[{claimRef, employeeId, displayName, claimType, status, lostTime, incidentDate}]` – disclosure-logged |

### 4.2 Statutory & insurer e-reporting (E08-F02)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/claims/report-forms` | CLAIMS_COORDINATOR, HSE_OFFICER | `[{code, jurisdiction, recipientType AUTHORITY|INSURER, recipientCode, title, deadlineHours, trigger ANY_CLAIM|LOST_TIME_OVER_7_DAYS|SPECIFIED_INJURY|FATALITY, fields[{code, label, source}]}]` – seeded: `GB_OSH_F2508` (AUTH_OSH, 10 days specified injury / 15 days over-7-day), `AE_LABOUR_INJURY` (AUTH_LABOUR_AE, 48 h), `INSURER_FNOL` (payer, 72 h) – **pending Legal / HSE sign-off (E08-BL-003)** |
| POST | `/claims/{id}/reports` | CLAIMS_COORDINATOR, HSE_OFFICER | `{formCode}` → `FirstReport {id, ref "FROI-2026-000001", claimId, formCode, recipientCode, status DRAFT, dueAt, fields:{…auto-populated from incident / case / HRIS}}` |
| PUT | `/claims/reports/{reportId}` | same | edit fields while DRAFT (description screened for diagnoses beyond the nature of injury) |
| POST | `/claims/reports/{reportId}/submit` | same | `{channel}` → `FIRST_REPORT_OF_INJURY` generated + signed; INSURER recipients: shared `INSURER` (consent INSURER_SHARING, else 422 `consent_required` – statutory AUTHORITY reports are a legal obligation: no consent, disclosure log with legal basis); `ClaimsSubmissionGateway` SPI (default: outbox event to `insurer-gateway` / `authority-gateway` + content-free e-mail) → SUBMITTED |
| POST | `/claims/reports/{reportId}/acknowledgement` | CLAIMS_COORDINATOR | multipart `file?` + `ackRef, receivedAt` → ACKNOWLEDGED (`INSURER_ACKNOWLEDGEMENT` stored) |
| POST | `/integration/claims/reports/{ref}/acknowledgement` | INTEGRATION (report recipient: `org_scope=/PARTNERS/<recipientCode>`) | `{ackRef, receivedAt, status ACKNOWLEDGED|REJECTED, reason?}`; other clients `404` |
| GET | `/claims/reports?status=&dueWithinHours=` | CLAIMS_COORDINATOR, HSE_OFFICER | deadline dashboard `[{id, ref, claimRef, formCode, recipientCode, status, dueAt, hoursLeft, submittedAt?, acknowledgedAt?}]` |

Job `claims-report-deadlines` (hourly): due within 24 h and not submitted → `claim.report-due`; past due → LATE + escalation to the claims mailbox; KPI `onTimeSubmissionPct`.

### 4.3 Consent-gated document exchange (E08-F03)
| Method | Path | Roles | Notes |
|---|---|---|---|
| POST | `/claims/{id}/disclosure-sets` | CLAIMS_COORDINATOR, OH roles | `{recipientCode, purpose, documentIds[]}` – documents of the claim's employee; allowed types: FITNESS_CERTIFICATE, RESTRICTION_NOTE, RTW_PLAN, PREAUTH_REQUEST, FIRST_REPORT_OF_INJURY, PROVIDER_INVOICE, REDACTED_COPY, and – **added by an OH role only** – PROVIDER_REPORT, EXTERNAL_REPORT; never MEDICO_LEGAL_REPORT, PHOTO, restricted-class documents or privileged documents (422 `document_not_disclosable`) |
| GET | `/claims/{id}/disclosure-sets` | CLAIMS_COORDINATOR, OH roles, DPO | `[DisclosureSet {id, recipientCode, purpose, status, documents[{documentId, title, type, redactedFromId?}], consentId?, sentAt?, sentBy?, revokedAt?}]` |
| POST | `/claims/disclosure-sets/{setId}/redactions` | OH roles | `{documentId, phrases[≤50]}` → `REDACTED_COPY` (text re-rendered with the phrases masked) replaces the document in the set |
| POST | `/claims/disclosure-sets/{setId}/send` | CLAIMS_COORDINATOR, OH roles | `{consentId}` → consent check (active `INSURER_SHARING` for the employee, else 422 `consent_required`) → `shareAsSystem(INSURER)` per document → disclosure log → SENT |
| POST | `/claims/disclosure-sets/{setId}/revoke` | CLAIMS_COORDINATOR, DPO | revokes the shares |
| GET | `/claims/disclosure-report?from=&to=` | DPO | every claim disclosure (recipient, documents, consent, by, at) |

### 4.4 Nurse case manager ↔ adjuster collaboration (E08-F04)
* Lost-time claim (create or flag) → automatic nurse case manager: the case's current owner when it holds CASE_MANAGER / OH_CLINICIAN, else the least-loaded active `CASE_MANAGER` staff member within caseload cap; assignment through the E02 assignment service (system actor), `claim.assigned`; SLA 48 h.
* `GET /claims/nurse-assignment?from=&to=` (NURSING_HEAD, CLAIMS_COORDINATOR) → `{lostTimeClaims, assignedWithin48h, pct, unassigned:[{claimRef, notifiedAt}]}`.

| Method | Path | Roles | Notes |
|---|---|---|---|
| GET / POST | `/claims/{id}/tasks` | CLAIMS_COORDINATOR, CASE_MANAGER / assigned nurse, TPA_ADJUSTER (payer org + assigned adjuster) | `{title ≤ 200 (screened), dueDate, assigneeRole CLAIMS|NURSE_CM|ADJUSTER}` → `[{id, title, dueDate, assigneeRole, status OPEN|DONE, createdBy, createdAt, completedAt?}]`; `claim.task` to the assignee |
| POST | `/claims/tasks/{taskId}/complete` | assignee side | |
| GET / POST | `/claims/{id}/messages` | same participants | secure messaging `{text ≤ 2000}` – screened for clinical content, encrypted; `claim.message` (content-free) |
| GET | `/claims/{id}/rtw` | CLAIMS_COORDINATOR, TPA_ADJUSTER, CASE_MANAGER | status only from E05: `{fitnessStatus, restrictionUntil?, rtwPlan?:{status, plannedStart, phases[{from, to, hoursPerDay, dutiesSummary? (functional)}], nextReview?}}` |
| GET | `/partner/claims?status=` | TPA_ADJUSTER | claims where payer = own org: `[{id, claimRef, claimType, status, displayName, incidentDate, lostTime, openTasks, unreadMessages}]` |
| GET | `/partner/claims/{id}` | TPA_ADJUSTER | + `rtw`, `tasks`, `documents` (consented INSURER shares of this claim) |

### 4.5 Cost tracking & finance export (E08-F05)
| Method | Path | Roles | Notes |
|---|---|---|---|
| POST | `/claims/{id}/costs` | CLAIMS_COORDINATOR, FINANCE | `{category, kind, amount, currency, incurredOn, description? (screened), invoiceNumber?}` |
| GET | `/claims/{id}/costs` | CLAIMS_COORDINATOR, FINANCE | ledger incl. E04 entries (`CaseCostRecorder` – entries for a case without a claim stay case-level and roll up too) |
| GET | `/claims/costs/rollup?groupBy=SUBSIDIARY|DEPARTMENT|COST_CENTRE&from=&to=` | FINANCE, CMO, CLAIMS_COORDINATOR | `[{key, label, cases, MEDICAL, REHAB, INDEMNITY, ADMIN, SETTLEMENT, LEGAL, total, costPerEmployee, suppressed}]` (groups with fewer cases than the minimum group size suppressed) |
| PUT | `/claims/budgets/{year}/{unitCode}` | FINANCE | `{amount, currency}` |
| GET | `/claims/costs/budget-vs-actual?year=&groupBy=` | FINANCE, CMO | `[{unitCode, label, budget, actual, variance, variancePct, trend:[{month, actual}]}]` |
| POST | `/claims/costs/exports` | FINANCE | `{month}` → `Export {id, month, rows, total, checksum (SHA-256), status GENERATED}`; event to `erp-finance` |
| GET | `/claims/costs/exports` · `/{id}/content` | FINANCE | list; CSV (cost centre, category, amounts – no names) |
| POST | `/claims/costs/exports/{id}/reconcile` | FINANCE | `{erpTotal, erpReference}` → RECONCILED or MISMATCH |

### 4.6 Medico-legal & litigation support (E08-F06)
| Method | Path | Roles | Notes |
|---|---|---|---|
| POST · DELETE | `/claims/{id}/legal-hold` | LEGAL_COUNSEL | `{reason}` → `litigationHold`; `DocumentService.legalHoldForCase` (retention suspended) |
| POST | `/claims/{id}/privilege` | LEGAL_COUNSEL | `{privileged, basis}` – privileged material is excluded from disclosure sets |
| POST | `/claims/{id}/legal-requests` | LEGAL_COUNSEL, CLAIMS_COORDINATOR | `{type, questions ≤ 4000, dueDate, providerCode? (EXPERT_OPINION / IME)}` → `LegalRequest {id, ref "LEG-2026-000001", type, status, questions, dueDate, providerCode?, referralId?, reports:[{version, documentId, createdAt, author}]}`; MEDICO_LEGAL_REPORT → `legal.request` to physicians / CMO; IME → `ExternalReferralGateway.requestReferral(serviceCode IME, sourceType CLAIM_IME)` and status updates via `ReferralStatusListener` |
| GET | `/legal-requests?status=` | LEGAL_COUNSEL, CMO, Physicians | |
| POST | `/legal-requests/{id}/report` | Physicians | `{opinion}` → `MEDICO_LEGAL_REPORT` (new version each time, signed) → REPORT_READY |
| GET | `/legal-requests/{id}/reports/{version}/content` | LEGAL_COUNSEL, Physicians | PDF (legal access set; audited, disclosure-logged for legal) |
| POST | `/legal-requests/{id}/close` | LEGAL_COUNSEL | |

## 5. Events (outbox – identifiers and status only)
`referral.status.v1 {referralId, caseId, status}` · `preauth.status.v1 {preauthId, caseId, status}` · `treatment.variance.v1 {planId, caseId}` ·
`claim.status.v1 {claimId, caseId, status}` · `claim.report.submitted.v1` (to `insurer-gateway` / `authority-gateway` with the allow-listed fields) ·
`claim.cost.export.v1` (to `erp-finance`) · `claim.nurse.assigned.v1 {claimId, username}`.

## 6. UI routes

* **oh-workspace** (nav by role in `src/auth/roles.ts`):
  * case tabs `#/cases/{id}/treatment` (plan, follow-ups, variance), `#/cases/{id}/referrals`, `#/cases/{id}/costs` (pre-auth, invoices), `#/cases/{id}/outcomes`, `#/cases/{id}/claim`; **claim badge in the case header** (`/cases/{id}/claim-status`);
  * `#/referrals` (worklist with status badges, overdue filter, bulk remind), `#/referrals/{id}`, `#/treatment/variance`, `#/providers` (search by service / city / contract, preferred, scorecard) + `#/providers/{code}` (procurement edit, contracts), `#/providers/scorecards`, `#/treatment/medications` (formulary, stock, usage, administration form inside the case), `#/telehealth` (sessions, join, chat, complete);
  * `#/claims` (CLAIMS_COORDINATOR), `#/claims/{id}` (overview, reports with deadline timers, disclosure sets + redaction, tasks & messages, costs, legal), `#/claims/reports` (deadline dashboard), `#/claims/costs` (FINANCE: roll-ups, budget vs actual, ERP exports + reconcile), `#/claims/nurse-assignment` (NURSING_HEAD), `#/legal` (LEGAL_COUNSEL: litigated / held claims, legal requests, reports), `#/claims/disclosures` (DPO report);
  * chart tab `#/employees/{id}/chart/treatment`.
* **employee-app**: `/treatment` (my plan, follow-ups confirm / decline, external appointments), `/telehealth` (sessions, consent, join, chat; crew-on-layover friendly); consent screen lists `TELEHEALTH_CONSULTATION`.
* **manager-workspace**: `/claims` (HR only: claim status of employees in scope).
* **partner-portal** (new, port 5176, React 19 + Vite + TS like the other apps, same-origin BFF login, MFA notice): PARTNER_PROVIDER → `/referrals` (inbox, accept / decline, appointment, seen, upload report, upload invoice), `/telehealth` (sessions, join, chat), `/documents`; TPA_ADJUSTER → `/claims` (status, RTW milestones, tasks, messages, consented documents), `/preauthorisations` (decide). Any other role → "not available".

## 7. Tests & non-functional
Unit tests for: plan template expansion and variance maths, referral data-set validation and chase rules, FHIR ServiceRequest mapping, scorecard maths, invoice matching, allergy / batch checks, outcome closure guard, telehealth token window;
claim workflow transitions, deadline calculation per form, disclosure-set rules (allowed types, privilege, consent), redaction, nurse assignment choice, cost roll-up / suppression, ERP export checksum, legal hold.
Integration tests `Increment4TreatmentIntegrationTest`, `Increment4ClaimsIntegrationTest` (own test DBs `wellnessz_test_e04`, `wellnessz_test_e08`; **recreate the DB before every run** – encrypted columns are keyed per test JVM) covering role denials: partner of another organisation gets 404, adjuster never sees clinical content, finance sees no names, consent missing → 422.
All jobs use `JobRunGuard`. Person-level reads audited; every disclosure logged.
