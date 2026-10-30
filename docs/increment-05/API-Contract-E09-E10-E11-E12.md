# Increment 5 – E09 Mental Health & Peer Support, E10 Drug & Alcohol Testing, E11 Travel Health & Communicable Disease, E12 Health Promotion: API and Domain Contract

Source of truth shared by the backend (`modules/wz-e09-mentalhealth`, `modules/wz-e10-substance`, `modules/wz-e11-travelhealth`,
`modules/wz-e12-promotion`, platform / kernel extensions) and the UIs (`web/oh-workspace`, `web/employee-app`,
`web/manager-workspace`, `web/partner-portal`). Conventions unchanged from increments 1–4: paths relative to `/api/v1`
(browsers call `/bff/api/...`), RFC 9457 problems `{type,title,status,detail,code,correlationId,field?}`, ISO-8601 UTC
timestamps, `YYYY-MM-DD` dates, plain JSON arrays unless stated, null fields omitted, denied record access → `404`,
business-rule violations → `422` with `code`, conflicts → `409`. Free text shown outside the owning clinical team is screened
with `ClinicalContentException.screen(field, value)` (422 `clinical_content_not_allowed` + `field`). Money `{amount:"12.50", currency:"GBP"}`.
Machine clients: internal systems (rostering, campaign app, wearable aggregator) carry **no** partner organisation;
external systems (laboratory, EAP) carry `org_scope /PARTNERS/<CODE>` and may only touch their own records (`CurrentUser.partnerOrganisation()`).

**Confidentiality rules (restricted classes).**
1. Mental-health (E09) and substance-programme (E10) records are **restricted class** (`DataClassification.RESTRICTED_CLINICAL`,
   restricted kernel cases). Only named mental-health clinicians (`PSYCH_CLINICIAN`, optionally limited by config
   `mentalhealth.named-clinicians`) read E09 clinical content; only `MRO` (and `OH_PHYSICIAN` acting as MRO) read E10 results.
   General OH users see a **"restricted case" placeholder** (count only); every access attempt is audited; break-glass (increment 2) applies.
2. Managers / HR / crew scheduling see **duty status only** (available / removed from safety-sensitive duty / return pending) –
   never screening scores, conditions, test reasons, results or counselling content. For E09 the employee chooses what
   (if anything) the manager sees: `NOTHING | FITNESS_ONLY | FITNESS_AND_ADJUSTMENTS`, revocable.
3. Peer-support contact logs contain **no operator-identifiable data** (pseudonymous contact codes; aggregated counts only for the operator / SMS).
4. Programme statistics (E09 SMS report, E10 statistics, E11 uptake, E12 participation) are aggregated with small-cell suppression
   (`wellnessz.analytics.min-group-size`, default 5).
5. Every disclosure outside the owning team is written to the disclosure log.

## 1. Roles and demo users (realm, `Roles.java`, scope mappings – done)

| Role | Purpose | Demo user (employeeId, org_scope) |
|---|---|---|
| `PEER_SUPPORT_VOLUNTEER` | crew peer volunteer – own pseudonymous contact log | `peer1` (S8801) |
| `PEER_SUPPORT_COORDINATOR` | volunteer register, training, availability, aggregated programme figures | `peercoord1` (S8802) |
| `SAFETY_MANAGER` | SMS reports, CISM events (counts), post-incident test requirements | `safety1` (S8803) |
| `DA_PROGRAMME_MANAGER` | testing policies, pools, random selections, coverage – no results | `damanager1` (S8804) |
| `MRO` | medical review officer – verification of laboratory results | `mro1` (S8805) |
| `COLLECTOR` | specimen collection, electronic chain of custody – no results | `collector1` (S8806) |
| `HEALTH_PROMOTION_LEAD` | campaigns, screening events, anonymised analytics | `promo1` (S8807) |
| `ERGONOMIST` | ergonomic assessments and actions | `ergo1` (S8808) |
| `PARTNER_PROVIDER` (existing) | EAP / counselling partner in the partner portal | `eap1` (X8809, /PARTNERS/EAP_WELLBEING) |
| existing | `PSYCH_CLINICIAN` (`psych1`) named mental-health clinician; `AME`, `OH_PHYSICIAN`, `CMO`, `NURSING_HEAD`, `OH_CLINICIAN`, `HSE_OFFICER`, `HR`, `MANAGER`, `CREW_SCHEDULING`, `HYGIENE_MANAGER`, `DPO` | |

## 2. Platform additions (done – do not change signatures)

* **Duty assignments** (`masterdata.duty_assignment`, V16): crew rosters, shifts, facility access.
  `POST /integration/duty-assignments` (INTEGRATION, internal clients only) `{source CREW_ROSTER|SHIFT|FACILITY, duties:[{sourceRef, dutyRef, dutyDate, startsAt, endsAt, employeeRef (id or badge), dutyRole?, locationCode?, destinationCode?, proximityGroup?}]}` → `{received, upserted, unmatched[]}`;
  `GET /masterdata/duty-assignments?employeeId=&from=&to=` (OH roles, SAFETY_MANAGER, CREW_SCHEDULING; ≤ 62 days).
  Java: `DutyAssignmentRepository.findForEmployee / findOnDuties / findInProximityGroups / findToDestination`. Demo rosters: `R__demo_reference_duties.sql`
  (WZ101 LHR→SIN two days ago with E1002/E1003/E7001 on aircraft G-WZAA, WZ102 back yesterday, WZ205 LHR→DXB in 3 days, ramp shift RAMP-EARLY at LHR-STAND-5 yesterday with E1004/E1007/E1009, catering CAT-LINE-2 with E1001/E1010/M2001, crew room LHR-CREW-ROOM).
* **Incident feed**: modules subscribe in-process to `ehs.incident.received.v1` (`@EventListener` on `IntegrationEnvelope`, payload = E01 `IncidentFacts`: incidentId, externalRef, employeeId, employeeName, orgUnitCode, incidentType, severity, injury, incidentClass, bodyPart, lostTime, exposureType, unitCode, location, shift, incidentOccurredAt). Idempotent on incidentId.
* **Kernel SPI** `SupportProgrammeReferral` (impl E09, called by E10 / E11): `refer(actor, employeeId, relatedCaseId, reason, sourceType, sourceRef)` → `Optional<UUID>` support case id. Existing SPIs reused: `AppointmentBooking` (E14), `ExternalReferralGateway` (E04), `CaseCostRecorder` (E08), `FitnessClearanceGuard`, `ChartContributor`, `CaseTransitionGuard`.
* **Fitness / roster status**: use `CaseService.publishFitness` / `applyFitnessDecision` (status-only crew roster feed, increment 1–2).
* **Documents**: `generateSigned(actor, template, employeeId, caseId, fields, DataClassification.RESTRICTED_CLINICAL)` for restricted documents; new templates `AUTHORITY_FITNESS_REPORT` (E09), `CHAIN_OF_CUSTODY_FORM` (E10), `TRAVEL_HEALTH_CERTIFICATE`, `PUBLIC_HEALTH_REPORT` (E11); `RESULT_LETTER` reused (E12). Retention classes `RESTRICTED_RECORD`, `PUBLIC_HEALTH`.
* **Partner organisations** added (V16): `EAP_WELLBEING` (EAP, portal), `TOXLAB` (laboratory system), `UKHSA_LOCAL` (public-health authority).
* **Notification templates** (content-free, approved): `support.request-received`, `support.urgent-callback`, `support.partner-referral`, `cism.offer`, `cism.follow-up`,
  `testing.selection-notice`, `testing.collection-dispatch`, `testing.review-queue`, `testing.status`, `travel.consultation-due`, `tracing.contact-notice`, `tracing.check-in`,
  `exposure.timer-alert`, `advisory.published`, `public-health.report-due`, `campaign.invitation`, `screening.follow-up`, `ergonomic.action-due` (placeholders as before incl. `reference`, `site`).
* **BFF**: `/bff/api/workspace/{testing|psychosocial|campaigns|ergonomics}/**` keep their prefix.
* **Schemas / migrations**: V16 platform (done); V17 `mentalhealth` (E09), V18 `substance` (E10), V19 `travelhealth` (E11), V20 `promotion` (E12) – each starts with `create schema if not exists …;` (schema lists already updated everywhere).
* **Outbound allow-lists** (lead adds to `application.yml`): `campaign-app: [messageId, campaignId, title, risk, startDate, endDate, targetUnits]`, `sms-safety: [messageId, quarter, reportRef, category, count, base]`.

## 3. E09 – Mental health, psychosocial risk & peer support (`com.wellnessz.e09`, schema `mentalhealth`, V17)

Enums: `SupportStatus` OPEN | SCREENED | ACTIVE | REFERRED | RELIEF | CLOSED · `Risk` LOW | MODERATE | HIGH | URGENT · `Sharing` NOTHING | FITNESS_ONLY | FITNESS_AND_ADJUSTMENTS ·
`Instrument` PHQ9 | GAD7 (public-domain; licence register in governance) · `Band` MINIMAL | MILD | MODERATE | MODERATELY_SEVERE | SEVERE ·
`Concern` WORK_STRESS | LOW_MOOD | ANXIETY | SLEEP | BEREAVEMENT | TRAUMA | SUBSTANCE | RELATIONSHIP | OTHER.

### 3.1 Confidential case type (F01)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/support/cases?status=&risk=` | PSYCH_CLINICIAN (named) | `[SupportCase {id, ref "SUP-2026-000001", employeeId, displayName, status, risk, concern, source SELF|CLINICIAN|PEER|CISM|PROGRAMME_REFERRAL, openedAt, lastContactAt?, sharing, eapReferral?, relief?}]` |
| GET | `/support/cases/{id}` | PSYCH_CLINICIAN (named) | + notes, screens, contacts, timeline |
| POST | `/support/cases` | PSYCH_CLINICIAN | clinician-opened `{employeeId, concern, risk, note?}` |
| POST | `/support/cases/{id}/notes` | PSYCH_CLINICIAN | `{text}` encrypted; `/close` `{reason}` |
| GET | `/support/placeholder?employeeId=` | OH roles | `{restrictedCases: n}` – the general-OH placeholder; non-named access attempts to any `/support/**` are audited DENIED (404) |
| GET / PUT | `/me/support/sharing` | EMPLOYEE | `{caseId, choice: NOTHING|FITNESS_ONLY|FITNESS_AND_ADJUSTMENTS, adjustments? (screened)}` – revocable; every change audited |
| GET | `/workspace/psychosocial/shared-outcomes` | MANAGER (scope) | only what employees chose to share: `[{employeeId, displayName, fitness, adjustments?, sharedAt}]` (disclosure-logged) |

### 3.2 Self-referral, screening & EAP (F02)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/me/support/signposting` | EMPLOYEE | `[{code, title, summary, url?, phone?, available24h}]` + `emergency:{text, phone}` |
| POST | `/me/support/self-referral` | EMPLOYEE | `{preferredContact PHONE|APP_CHAT|VIDEO, phone?, bestTime?, concern, message? (≤ 2000, encrypted)}` → `{caseRef, nextContactBy}`; `support.request-received` |
| GET | `/me/support/screens/{instrument}` | EMPLOYEE | `{instrument, title, questions[{code, text}], options[{value, label}]}` |
| POST | `/me/support/screens` | EMPLOYEE | `{instrument, answers[int]}` → `{score, band, urgent:boolean, nextSteps[]}`; PHQ9 item 9 > 0 or band SEVERE → URGENT: same-day kernel task + `support.urgent-callback` to named clinicians (content-free) |
| POST | `/support/cases/{id}/screens` | PSYCH_CLINICIAN | clinician-administered screen |
| GET | `/support/urgent` | PSYCH_CLINICIAN | open urgent items with `callBackBy` |
| GET | `/support/protocols/urgent-risk` | PSYCH_CLINICIAN | protocol script `{version, steps[]}` |
| POST | `/support/cases/{id}/urgent-contact` | PSYCH_CLINICIAN | `{outcome REACHED_SAFE|REACHED_ESCALATED_EMERGENCY|NOT_REACHED_RETRY|NOT_REACHED_WELFARE_CHECK, note}` |
| POST | `/support/cases/{id}/eap-referrals` | PSYCH_CLINICIAN | `{partnerCode, sessionsAuthorised 1..12, urgency ROUTINE|PRIORITY, consentId (TREATMENT_SHARING_PARTNER)}` → `EapReferral {id, ref "EAP-2026-000001", status SENT, firstAppointmentDueBy (5 working days)}`; `support.partner-referral` |
| GET | `/partner/eap-referrals?status=` | PARTNER_PROVIDER (own org) | minimal data `[{id, ref, displayName, contactPhone?, preferredContact, sessionsAuthorised, urgency, status, sentAt}]` (disclosure-logged) |
| POST | `/partner/eap-referrals/{id}/feedback` | PARTNER_PROVIDER | `{status ACCEPTED|IN_PROGRESS|CLOSED, attendance[{date, attended}], closureReason? COMPLETED|DISENGAGED|REFERRED_ON}` – **no clinical content** (no free text) |
| GET | `/support/governance` | PSYCH_CLINICIAN, CMO | instruments, licence status, protocol version, sign-off (pending CMO / mental-health lead – E09-BL-014) |

### 3.3 Flight-crew peer support (F03)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET / POST / PUT | `/peer-support/volunteers[/{id}]` | PEER_SUPPORT_COORDINATOR | `{username, displayName, base, languages[], available, trainingRecords[{course, completedOn, expiresOn}]}` |
| GET | `/me/peer-support/peers` | EMPLOYEE (crew) | available trained peers `[{alias, base, languages}]` (no surnames) |
| POST | `/me/peer-support/contact-request` | EMPLOYEE | `{alias (chosen by the requester), callbackPhone (encrypted, purged after 30 days), base?, language?}` → `{contactCode}` routed to an available volunteer (content-free notification) |
| GET | `/peer-support/my-requests` | PEER_SUPPORT_VOLUNTEER | requests routed to me `[{contactCode, alias, callbackPhone, language, requestedAt}]` |
| POST | `/peer-support/contacts` | PEER_SUPPORT_VOLUNTEER | contact log `{contactCode, date, durationMinutes, category STRESS|FATIGUE|RELATIONSHIP|BEREAVEMENT|SUBSTANCE|TRAUMA|OTHER, outcome SUPPORTED|SIGNPOSTED|REFERRED_TO_TRUSTED_PROFESSIONAL|EMERGENCY}` – no names, no employee id |
| POST | `/peer-support/contacts/{id}/refer` | PEER_SUPPORT_VOLUNTEER | to the trusted mental-health professional (PSYCH_CLINICIAN queue) with the pseudonymous code |
| POST | `/support/relief` | AME, PSYCH_CLINICIAN | temporary relief from duties `{employeeId, from, expectedUntil?}` → roster status UNFIT / PENDING (status only) |
| POST | `/support/relief/{id}/return` | AME | `{decision FIT|EXTEND, until?}` |
| GET | `/peer-support/reports/quarterly?quarter=2026-Q3` | SAFETY_MANAGER, PEER_SUPPORT_COORDINATOR, CMO | aggregated `{quarter, rows[{base, category, contacts, suppressed}], totals}`; `POST …/send` publishes to `sms-safety` |

### 3.4 Critical incident stress management (F04)
* Listener: incidents with severity HIGH / CRITICAL or `incidentClass` ∈ config `cism.trigger-classes` (FATALITY, ASSAULT, FUME_EVENT, AIRCRAFT_ACCIDENT, SERIOUS_INJURY, EMERGENCY_LANDING) → CISM event; affected list = the incident employee + everyone on the same duty / proximity group within ±2 h (DutyAssignment); 72-h offer SLA.

| Method | Path | Roles | Notes |
|---|---|---|---|
| POST | `/cism/events` | PSYCH_CLINICIAN, OH_CLINICIAN, SAFETY_MANAGER | manual `{title, category, occurredAt, location, dutyRefs[]}` |
| GET | `/cism/events` | PSYCH_CLINICIAN, OH_CLINICIAN (names); SAFETY_MANAGER (counts only) | `[{id, ref, title, category, occurredAt, affected, offered, accepted, offerDueBy, slaMet}]` |
| GET | `/cism/events/{id}` | PSYCH_CLINICIAN, OH_CLINICIAN | `affected[{employeeId, displayName, dutyRole, offerSentAt?, response?, sessionType?, attendedAt?, followUp1w:{dueOn, done}, followUp4w:{dueOn, done}, referred}]` |
| POST | `/cism/events/{id}/offers` | same | sends `cism.offer` to all not yet offered |
| PUT | `/cism/events/{id}/affected/{employeeId}` | same | `{response?, sessionType? GROUP|INDIVIDUAL, attendedAt?, followUp1wDone?, followUp4wDone?, referToSupport?}` |
| GET / POST | `/me/cism/offers` · `/me/cism/offers/{id}/response` | EMPLOYEE | `{response ACCEPTED|DECLINED, preferredContact?}` |

Job `cism-follow-ups` (daily): 1- and 4-week follow-ups → `cism.follow-up`.

### 3.5 Psychosocial risk register (F05)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET / POST | `/psychosocial/assessments[?unitCode=]` | HSE_OFFICER, CMO, NURSING_HEAD, HR | `{unitCode, assessedOn, factors[{factor WORKLOAD|SHIFT_FATIGUE|VIOLENCE_AGGRESSION|ISOLATION|CHANGE|ROLE_CLARITY|SUPPORT|RECOGNITION, likelihood 1..5, severity 1..5, controls}], reviewDate}` → score per factor and overall, RAG |
| POST / PUT | `/psychosocial/assessments/{id}/actions[/{actionId}]` | same | `{title, owner, dueDate, status OPEN|DONE}` |
| GET | `/psychosocial/dashboard` | same | per unit: current assessment, overdue review, open / overdue actions, anonymised support-case rate per 100 staff (suppressed) |
| GET | `/workspace/psychosocial` | MANAGER (own unit) | the unit's assessment and actions |

### 3.6 Safety-critical mental-health fitness (F06)
| Method | Path | Roles | Notes |
|---|---|---|---|
| POST | `/support/fitness-assessments` | AME, OH_PHYSICIAN (PSYCH_CLINICIAN contributes) | `{employeeId, supportCaseId?, standardCode, medications[{name, drugClass, dose, startDate}], findings (encrypted), decision FIT|FIT_WITH_LIMITATIONS|TEMPORARILY_UNFIT|REFER_TO_AUTHORITY, limitations?, reviewDate, authorityReportRequired}` → roster / fitness status (status only), review task; `AUTHORITY_FITNESS_REPORT` (restricted) when required |
| GET | `/support/fitness-assessments?employeeId=` | AME, OH_PHYSICIAN, PSYCH_CLINICIAN | history |

`SupportProgrammeReferral` implementation: opens a restricted support case with source PROGRAMME_REFERRAL and notifies named clinicians (`support.urgent-callback` level "programme referral").

## 4. E10 – Drug & alcohol testing (`com.wellnessz.e10`, schema `substance`, V18)

Enums: `RoleGroup` FLIGHT_CREW | CABIN_CREW | AIRSIDE_DRIVER | ENGINEER | FORKLIFT | SECURITY · `Reason` PRE_EMPLOYMENT | RANDOM | POST_INCIDENT | FOR_CAUSE | RETURN_TO_DUTY | FOLLOW_UP ·
`Panel` DRUGS_5 | DRUGS_10 | ALCOHOL_BREATH · `TestStatus` REQUIRED | NOTIFIED | DISPATCHED | COLLECTED | IN_LAB | MRO_REVIEW | VERIFIED_NEGATIVE | VERIFIED_POSITIVE | REFUSAL | CANCELLED | WINDOW_MISSED ·
`Verdict` NEGATIVE | NEGATIVE_LEGITIMATE_PRESCRIPTION | POSITIVE | POSITIVE_DILUTE | REFUSAL_ADULTERATED | REFUSAL_SUBSTITUTED | CANCELLED · `DutyStatus` AVAILABLE | REMOVED | RETURN_PENDING.

### 4.1 Policies, pools, random selection (F01)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET / PUT | `/testing/policies[/{roleGroup}]` | DA_PROGRAMME_MANAGER (CMO, SAFETY_MANAGER read) | `{roleGroup, jobCodes[], panels[], randomDrugRatePct, randomAlcoholRatePct, alcoholWindowHours (8), drugWindowHours (32), leadTimeMinutes, jurisdiction, consentRequired, approvalStatus PENDING|APPROVED, approvedBy?, version}` – seeded **pending Legal / Safety / HR approval (E10-BL-010)** |
| GET | `/testing/pools[?roleGroup=]` | DA_PROGRAMME_MANAGER | `[{roleGroup, size, members[{employeeId, displayName, jobCode}], exclusions[{employeeId, reason, until}]}]` (from HRIS job codes) |
| POST | `/testing/pools/{roleGroup}/exclusions` | DA_PROGRAMME_MANAGER | `{employeeId, reason LEAVE|LONG_TERM_ABSENCE|NOT_SAFETY_SENSITIVE, until}` |
| POST | `/testing/selections` | DA_PROGRAMME_MANAGER | `{roleGroup, period YYYY-MM, testType DRUGS|ALCOHOL}` → `Selection {id, ref, roleGroup, period, poolSize, poolHash, seedCommitment, selectedCount, selected[{employeeId, displayName}], createdAt}`; n = ceil(poolSize × rate / 12); `SecureRandom` seed stored, pool snapshot hashed; one selection per (roleGroup, period, testType) (409) |
| GET | `/testing/selections?period=` · `/testing/selections/{id}/verify` | DA_PROGRAMME_MANAGER, AUDITOR | verify recomputes from seed + snapshot → `{reproducible:true}` |
| POST | `/testing/selections/{id}/notify` | DA_PROGRAMME_MANAGER | creates tests REQUIRED → NOTIFIED with short lead time: `testing.selection-notice` to the employee, `testing.collection-dispatch` to collectors |
| GET | `/testing/coverage?year=` | DA_PROGRAMME_MANAGER, CMO | `[{roleGroup, poolSize, targetTests, completed, coveragePct, behindPlan}]` |

### 4.2 Post-incident & for-cause (F02)
* Listener `ehs.incident.received.v1`: qualifying incidents (config rule table `substance.post_incident_rule`: incidentClass / severity) for employees in a pool → POST_INCIDENT tests with `alcoholDueAt` (+8 h) and `drugDueAt` (+32 h) from `occurredAt`; urgent dispatch.

| Method | Path | Roles | Notes |
|---|---|---|---|
| POST | `/workspace/testing/for-cause` | MANAGER (scope), SAFETY_MANAGER | `{employeeId, observedAt, location, observations[ODOUR_OF_ALCOHOL|SLURRED_SPEECH|UNSTEADY_GAIT|ERRATIC_BEHAVIOUR|DROWSINESS|DILATED_PUPILS|ADMISSION|OTHER ≥ 1], description ≤ 2000 (encrypted), secondObserverName}` → requirement + dispatch |
| GET | `/workspace/testing/for-cause` | MANAGER, SAFETY_MANAGER | own requests (status only) |
| GET | `/testing/requirements?status=` | DA_PROGRAMME_MANAGER, COLLECTOR, MRO | `[{id, ref "DAT-2026-000001", employeeId, displayName, reason, panels, status, dueAt, hoursLeft, siteCode?, dispatchedAt?}]` (collectors: no observations) |

### 4.3 Collection, chain of custody, laboratory, MRO (F03)
| Method | Path | Roles | Notes |
|---|---|---|---|
| POST | `/testing/tests/{id}/collection` | COLLECTOR | `{specimenId, specimenType URINE|ORAL_FLUID|BREATH, collectedAt, siteCode, temperatureInRange?, sealIntact, donorIdentityChecked, splitSpecimen, breathScreenResult? (alcohol, numeric g/210L), remarks?}` → COLLECTED; `CHAIN_OF_CUSTODY_FORM` (restricted, signed) |
| POST | `/testing/specimens/{specimenId}/custody` | COLLECTOR, INTEGRATION (lab) | `{event SEALED|COURIER_PICKUP|LAB_RECEIVED, at, by}` |
| POST | `/testing/tests/{id}/refusal` | COLLECTOR | `{reason NO_SHOW|REFUSED|ADULTERATION_ATTEMPT|SUBSTITUTION_SUSPECTED, observations}` → MRO_REVIEW |
| GET | `/integration/testing/lab-orders` | INTEGRATION (org TOXLAB) | expected specimens for the laboratory |
| POST | `/integration/testing/lab-results` | INTEGRATION (org TOXLAB) | `{specimenId, labRef, receivedAt, validity VALID|DILUTE|ADULTERATED|SUBSTITUTED|INVALID, results[{analyte, cutoff, value, unit, outcome NEGATIVE|NON_NEGATIVE}]}` → MRO_REVIEW, `testing.review-queue` |
| GET | `/testing/mro/queue` | MRO | `[{testId, ref, employeeId, displayName, reason, specimenId, validity, results, receivedAt}]` |
| POST | `/testing/tests/{id}/mro-decision` | MRO | `{verdict, prescriptionVerified?, interviewNote? (encrypted), splitSpecimenRequested?}` |
| POST | `/testing/tests/{id}/split-request` | MRO | new lab order for the split specimen |
| GET | `/me/testing` | EMPLOYEE | my notices `[{ref, status, reportBy, siteCode}]` – no results |

### 4.4 Positive / refusal pathway (F04)
On VERIFIED_POSITIVE / REFUSAL (synchronous, < 15 min): restricted SUBSTANCE kernel case, duty status REMOVED via `publishFitness` (status only to rostering / HR – `testing.status`), `SupportProgrammeReferral.refer(... "SUBSTANCE_POSITIVE"|"SUBSTANCE_REFUSAL")`, return-to-duty plan draft.

| Method | Path | Roles | Notes |
|---|---|---|---|
| GET / POST | `/testing/rtd-plans[?status=]` | MRO, OH_PHYSICIAN | `{employeeId, caseId, evaluator, treatmentProvider?, followUpTests (n), followUpMonths}` → plan; follow-up tests scheduled on randomised unannounced dates |
| POST | `/testing/rtd-plans/{id}/evaluation` | MRO, OH_PHYSICIAN | `{outcome READY|NOT_READY, note?}` → READY requires a VERIFIED_NEGATIVE RETURN_TO_DUTY test before duty status AVAILABLE |
| GET | `/workspace/testing/status` | HR, CREW_SCHEDULING (scope) | `[{employeeId, displayName, dutyStatus, since}]` – status only, disclosure-logged |

### 4.5 Programme reporting (F05)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/testing/statistics?year=` | DA_PROGRAMME_MANAGER, CMO, SAFETY_MANAGER | by reason × roleGroup `{tests, verifiedPositive, refusals, positiveRatePct, suppressed}` |
| GET | `/testing/reports/annual?year=&format=pdf|csv` | CMO, DA_PROGRAMME_MANAGER | anonymised regulatory template |

## 5. E11 – Travel health, communicable disease & exposures (`com.wellnessz.e11`, schema `travelhealth`, V19; builds on E07 immunisation registry)

### 5.1 Travel & posting consultation (F01)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET / PUT | `/travel/destinations[/{countryCode}]` | read OH roles, EMPLOYEE (advice only); write OH_PHYSICIAN, CMO | `{countryCode, name, risks[], recommendedVaccines[], prophylaxis[], advice, source IN_HOUSE|LICENSED, reviewedOn}` – seeded ~15 destinations (in-house; licensed source decision E11-BL-002 pending) |
| POST | `/travel/consultations` | Clinicians | `{employeeId, trip{destinations[], departDate, returnDate, purpose LAYOVER|POSTING|BUSINESS}, vaccinesGiven[{vaccineCode, doseNumber, lotNumber, site, route}], prophylaxis[{drug, regimen, startDate}], advice?}` → recommendations from destinations vs the E07 immunisation status; vaccines recorded in E07; `TRAVEL_HEALTH_CERTIFICATE` signed + shared to the employee |
| GET | `/travel/consultations?employeeId=` · `/travel/recommendations?employeeId=&destinations=` | Clinicians | |
| GET | `/me/travel` | EMPLOYEE | my consultations, certificates, upcoming roster destinations with advice |

### 5.2 Communicable disease & contact tracing (F02)
| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/communicable/conditions` | OH roles | `[{code, name, notifiable, reportWithinHours, exclusionDays?, foodHandlerExclusion, monitoringDays}]` – seeded NOROVIRUS, SALMONELLA, HEP_A, INFLUENZA, COVID19, MEASLES, TB, CHICKENPOX, MENINGOCOCCAL |
| POST | `/communicable/cases` | Clinicians | `{employeeId, conditionCode, onsetDate, confirmedOn?}` → `CdCase {id, ref "CD-2026-000001", status, notifiable, reportDueAt?}` |
| POST | `/communicable/cases/{id}/exclusion` | Clinicians | `{decision EXCLUDED|ISOLATING|CLEARED, from, until?, returnCriteria[]}` → status feed (fitness TEMPORARILY_UNFIT; food handlers to catering hygiene – status only) |
| POST | `/communicable/cases/{id}/trace` | Clinicians | `{windowFrom, windowTo, include[SAME_DUTY|PROXIMITY_GROUP|FACILITY]}` → `{generatedAt, hoursSinceConfirmation, contacts[{id, employeeId, displayName, contactType, dutyRef, overlapMinutes, riskLevel HIGH|MEDIUM|LOW}]}` (HIGH = same proximity group ≥ 15 min) |
| GET | `/communicable/cases/{id}/contacts` | Clinicians | + `notifiedAt?, monitoringUntil?, lastCheckIn?, symptomatic` |
| POST | `/communicable/cases/{id}/contacts/notify` | Clinicians | `{contactIds[]}` → `tracing.contact-notice`, daily check-ins for the condition's monitoring days |
| POST | `/communicable/cases/{id}/clear` | Clinicians | `{criteriaMet[]}` |
| GET | `/me/check-ins` · POST `/me/check-ins/{id}` | EMPLOYEE | `{symptoms[FEVER|COUGH|VOMITING|DIARRHOEA|RASH|SORE_THROAT|NONE], temperatureC?}` → symptomatic alerts the nurse (`exposure.timer-alert` level "symptomatic contact") |

### 5.3 Exposure pathways with protocol timers (F03)
Pathways (seeded templates): `NEEDLESTICK_BBP` (first-aid; risk assessment ≤ 1 h; PEP decision ≤ 2 h; source testing; serology at 6 weeks, 3 and 6 months), `CHEMICAL_DGR` (SDS link, decontamination, monitoring; IH notification), `CABIN_FUME` (symptom protocol, biomonitoring sample ≤ 6 h, safety report, authority report task), `HEAT_ILLNESS` (cooling, return criteria, anonymised HSE feedback), `BITE_STING`.
Listener: incidents with `exposureType` NEEDLESTICK / CHEMICAL / FUME / HEAT create the pathway automatically.

| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/exposures/pathways` · `/exposures/chemicals` | OH roles | templates; chemical register `{code, name, unNumber?, sdsUrl, hazards}` (seeded) |
| POST | `/exposures` | Clinicians | `{employeeId, pathway, occurredAt, details? (encrypted), chemicalCode?, flightRef?, sourceKnown?}` → `Exposure {id, ref "EXP-2026-000001", caseId, timers[{code, label, dueAt, status PENDING|DONE|OVERDUE}], serology[{dueOn, done}]}` |
| GET | `/exposures?status=` · `/exposures/{id}` | Clinicians | |
| POST | `/exposures/{id}/tasks/{code}/complete` | Clinicians | `{note?}` |

Job `exposure-timers` (every 5 min): due within 30 min or overdue → `exposure.timer-alert` (once per task and level).

### 5.4 Vaccination campaigns (F04)
| Method | Path | Roles | Notes |
|---|---|---|---|
| POST / GET | `/vaccination-campaigns` | NURSING_HEAD, OH_CLINICIAN | `{name, vaccineCode, eligibility{all|jobCodes[]|subsidiaries[]}, startDate, endDate, siteCodes[], targetUptakePct}` |
| POST | `/vaccination-campaigns/{id}/invite` | NURSING_HEAD | `campaign.invitation`; booking through `AppointmentBooking` (VACCINATION) |
| POST | `/vaccination-campaigns/{id}/administrations` | Clinicians | fast entry recorded in the E07 registry with the campaign id `{employeeId|badgeId, lotNumber|lotBarcode, site, route, consentConfirmed}` |
| POST | `/vaccination-campaigns/{id}/declines` · `/adverse-events` | Clinicians | decline `{employeeId, reason}`; AE `{employeeId, event LOCAL_REACTION|FEVER|SYNCOPE|ANAPHYLAXIS|OTHER, severity MILD|MODERATE|SEVERE, onsetAt, actions}` |
| GET | `/vaccination-campaigns/{id}/uptake?groupBy=SUBSIDIARY|DEPARTMENT` | NURSING_HEAD, CMO | `[{key, eligible, vaccinated, declined, uptakePct, suppressed}]` |
| GET / POST | `/me/vaccination-campaigns` · `/me/vaccination-campaigns/{id}/response` | EMPLOYEE | `{response ACCEPT|DECLINE}` |

### 5.5 Health advisories (F05)
| Method | Path | Roles | Notes |
|---|---|---|---|
| POST | `/advisories` | OH_PHYSICIAN, CMO, AME | `{title, destinationCode?, baseCode?, roles[], severity INFO|WARNING|CRITICAL, body ≤ 4000, validFrom, validUntil}` → targets: crew rostered to the destination in the window (DutyAssignment) + base staff with the roles; `advisory.published` |
| GET | `/advisories` · `/advisories/{id}/acknowledgements` | OH roles | `{targeted, acknowledged, ratePct, pending[]}` |
| GET / POST | `/me/advisories` · `/me/advisories/{id}/acknowledge` | EMPLOYEE | |

### 5.6 Public-health reporting (F06)
| Method | Path | Roles | Notes |
|---|---|---|---|
| POST | `/public-health/reports` | OH_PHYSICIAN, CMO | `{communicableCaseId?|exposureId?, authorityCode}` → `PUBLIC_HEALTH_REPORT` generated; `dueAt` from the condition's `reportWithinHours` |
| POST | `/public-health/reports/{id}/submit` | same | `{channel EMAIL|PORTAL|PHONE, submittedAt, reference?}` |
| POST | `/public-health/reports/{id}/correspondence` | same | `{direction IN|OUT, summary, documentId?}` |
| GET | `/public-health/reports?status=` | OH roles | with `hoursLeft`; job `public-health-deadlines` → `public-health.report-due` |

Outbreak playbook + tabletop exercise (E11-BL-013): documented in the solution design; configuration-only.

## 6. E12 – Health promotion & prevention (`com.wellnessz.e12`, schema `promotion`, V20; builds on the E03 record)

| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/promotion/insights` | HEALTH_PROMOTION_LEAD, CMO, NURSING_HEAD | anonymised hotspots `[{unitCode, risk MSK|HEARING|METABOLIC|FATIGUE|MENTAL_WELLBEING, indicator, value, period, suppressed}]` from kernel cases, incidents and E03 results |
| GET / POST / PUT | `/promotion/campaigns[/{id}]` | HEALTH_PROMOTION_LEAD | `{title, risk, objective, targetUnits[], insightRef?, budget{amount,currency}, startDate, endDate, owners[], kpis[{metric, target}], status PLANNED|ACTIVE|COMPLETED|CANCELLED}` – portfolio seeded (E12-BL-008) |
| POST | `/promotion/campaigns/{id}/publish` | HEALTH_PROMOTION_LEAD | outbox `campaign-app` + `campaign.invitation` to targeted staff |
| POST / GET | `/promotion/screening-events[/{id}]` | HEALTH_PROMOTION_LEAD, OH_CLINICIAN | `{campaignId?, type BIOMETRIC|MSK|HEARING|VISION|MENTAL_WELLBEING, siteCode, date}`; participants `POST …/participants {employeeIds[]}` |
| POST | `/promotion/screening-events/{id}/results` | Clinicians | `{employeeId, measures{systolic?, diastolic?, bmi?, glucoseMmol?, cholesterolMmol?, waistCm?, mskScore?, wellbeingScore?}}` → thresholds (config) → abnormal: follow-up kernel case (≤ 48 h), `RESULT_LETTER`, `screening.follow-up` |
| POST / GET | `/promotion/education-attendance` · `/promotion/education-attendance/report` | Clinicians, HEALTH_PROMOTION_LEAD | hearing-conservation education `{employeeId, programme, sessionDate}` |
| POST / GET | `/ergonomics/assessments[/{id}]` | ERGONOMIST, HSE_OFFICER | `{type DSE_WORKSTATION|MANUAL_HANDLING|TASK, unitCode, jobCode, employeeId?, location, checklist{item: 0..3}, findings (screened)}` → riskScore, RAG, job-demand profile (masterdata) attached |
| POST | `/ergonomics/assessments/{id}/photos` | ERGONOMIST | multipart image (stored via `storeUpload`, non-clinical) |
| POST / PUT | `/ergonomics/assessments/{id}/actions[/{actionId}]` | ERGONOMIST, HSE_OFFICER, MANAGER (own unit, status) | `{title, owner, dueDate, status OPEN|DONE}`; job → `ergonomic.action-due`; KPI closed ≤ 60 days |
| GET | `/workspace/ergonomics` · `/workspace/campaigns` | MANAGER (own unit) | unit actions; unit participation % (suppressed) |
| POST | `/integration/promotion/participation` | INTEGRATION (internal campaign app) | `[{campaignId, employeeRef, activity, occurredAt, value?}]` |
| POST / DELETE | `/me/wearables/enrolments[/{id}]` | EMPLOYEE | `{campaignId, deviceType, consentId (WEARABLE_DATA)}`; delete = withdraw + purge |
| POST | `/integration/wearables/data` | INTEGRATION (internal aggregator) | daily aggregates `[{employeeRef, campaignId, metric STEPS|SLEEP_MINUTES|ACTIVE_MINUTES, date, value}]` – accepted only with an active enrolment + consent (data minimisation) |
| GET | `/me/campaigns` | EMPLOYEE | invitations, screening bookings, enrolments |
| GET | `/promotion/campaigns/{id}/report?groupBy=` | HEALTH_PROMOTION_LEAD, CMO | `{participation[{key, invited, participated, pct, suppressed}], outcomes[{metric, baseline, current, delta}]}`; CSV export |

## 7. UI routes
* **oh-workspace**: `#/support/cases` (+ `/{id}`), `#/support/urgent`, `#/support/eap`, `#/support/fitness`, `#/peer-support` (coordinator: volunteers, quarterly SMS report; volunteer: my requests + contact log), `#/cism` (+ `/{id}`), `#/psychosocial`; `#/testing/policies`, `#/testing/pools`, `#/testing/selections`, `#/testing/requirements`, `#/testing/collection`, `#/testing/mro`, `#/testing/rtd`, `#/testing/reports`; `#/travel/destinations`, `#/travel/consultations`, `#/communicable` (+ `/{id}` tracing), `#/exposures` (+ `/{id}` timers), `#/vaccination-campaigns`, `#/advisories`, `#/public-health`; `#/promotion/insights`, `#/promotion/campaigns`, `#/promotion/screening`, `#/ergonomics`, `#/promotion/reports`. General OH users see a "restricted case" placeholder for E09/E10.
* **employee-app**: `/support` (signposting, emergency, self-referral, screening, sharing choice, CISM offers), `/peer-support` (pseudonymous contact request), `/travel` (consultations, certificates, advisories + acknowledge), `/check-ins`, `/campaigns` (invitations, vaccination response, wearable consent), `/testing` (my notices: where and when to report).
* **manager-workspace**: `/testing` (for-cause request with observation checklist; HR/crew scheduling duty-status list), `/psychosocial` (unit assessment + shared outcomes), `/campaigns` (unit participation), `/ergonomics` (unit actions).
* **partner-portal**: `/eap-referrals` for the EAP partner (attendance / closure feedback only).

## 8. Tests & non-functional
Unit: screening scoring + urgent rule, pseudonymous contact codes, CISM affected-list and SLA, psychosocial scoring; random-selection reproducibility + coverage maths, post-incident windows, MRO state machine, RTD scheduling, suppression; contact-tracing rules (overlap / proximity), exposure timers, uptake maths, advisory targeting; screening thresholds, ergonomic scoring, wearable consent gate.
Integration: `Increment5MentalHealthIntegrationTest`, `Increment5SubstanceIntegrationTest`, `Increment5TravelHealthIntegrationTest`, `Increment5PromotionIntegrationTest` – including negative tests: general OH / manager / HR never see restricted content; lab system of another organisation → 404; volunteer views contain no employee id; small cells suppressed. Recreate the test DB before every run. All jobs use `JobRunGuard`; person-level reads audited; disclosures logged.
