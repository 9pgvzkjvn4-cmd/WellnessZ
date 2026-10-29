# WellnessZ OHCM – Increment 3 Requirements Traceability Matrix (E06 + E07 + E14)

| | |
|---|---|
| **Document** | Increment 3 – 02 Requirements Traceability Matrix |
| **Sources** | E06, E07 and E14 requirement workbooks, v1.0 – Draft for CMO & Nursing Head review (27 October 2026): sheets *High-Level Requirements*, *Feature List*, *Implementation Backlog* |
| **Audience** | CMO, Nursing Head, DPO, Cyber GRC, CIO, HR, Crew Licensing, project sponsor |
| **Companion documents** | 01 – Solution Design E06/E07/E14 · 03 – Release Notes Increment 3 · API contract `API-Contract-E06-E07-E14.md` |
| **Verified on** | 29 October 2026 |

> **Method.** Every row was checked against the code: the endpoint, class, migration table or screen named in the
> *Implementation* and *UI screen* columns was located in the repository, and the *Verification* column names the
> automated test, end-to-end (E2E) check or screenshot that exercises it. Where an acceptance criterion is only partly
> met – because it depends on an external system that is not yet connected (HRIS / time-and-attendance / crew feeds,
> the HRIS offer event, an IoT gateway, the E04 telehealth platform), on an optional provider (Tesseract OCR), or on a
> business sign-off (CMO / DPO / CAA) – the item is marked **Partial** and the gap is stated. Volume and time targets in
> acceptance criteria (for example "500 employees in one campaign", "average wait ≤ 15 min") are reported in *Notes*:
> where the system provides the function and measures the value, the item is counted as implemented and the operational
> target is left for the pilot. All API paths are relative to `/api/v1`; the BFF keeps the `/workspace` prefix for
> absence, advice-request, surveillance and pre-employment resources.

## 1. Coverage summary

| Level | Items | Implemented | Partial | Deferred |
|---|---|---|---|---|
| E06 high-level requirements | 6 | 5 | 1 | 0 |
| E06 features | 6 | 4 | 2 | 0 |
| E06 backlog items | 14 | 11 | 3 | 0 |
| E07 high-level requirements | 8 | 7 | 1 | 0 |
| E07 features | 8 | 7 | 1 | 0 |
| E07 backlog items | 20 | 17 | 3 | 0 |
| E14 high-level requirements | 5 | 5 | 0 | 0 |
| E14 features | 5 | 5 | 0 | 0 |
| E14 backlog items | 10 | 8 | 2 | 0 |
| **Total** | **82** | **69 (84 %)** | **13 (16 %)** | **0 (0 %)** |

Per epic: **E06** 26 items – 20 implemented, 6 partial · **E07** 36 items – 31 implemented, 5 partial · **E14** 20
items – 18 implemented, 2 partial.

The 13 partial items fall into four groups:

| Cause | Items |
|---|---|
| External source system not yet connected (E15) – ingestion API and no-op connector SPI only | E06-HLR-01, E06-F01, E06-BL-001 (HRIS / T&A / crew absence feed); E07-BL-006 (HRIS offer event) |
| Optional provider – image OCR needs the Tesseract provider | E06-F03, E06-BL-006 |
| Business sign-off pending | E06-BL-005 (DPO approval of the "OH engaged" template); E07-BL-002 (CMO validation of 13 programmes); E07-HLR-04, E07-F04, E07-BL-009 (CAA exam / report format confirmation) |
| Integration with a later epic / external device | E14-BL-004 (telehealth video – link only until E04); E14-BL-008 (IoT fridge gateway – ingestion API only) |

Backlog by priority (workbook *Priority* column): of the **22 P1 (critical)** backlog items – 7 in E06, 10 in E07, 5 in
E14 – all have a working implementation; **18 are fully implemented and 4 partial** (E06-BL-001, E07-BL-002,
E07-BL-006, E07-BL-009). No backlog item is deferred: every P2 and P3 item (including the P3 items E06-BL-007,
E07-BL-008, -011, -015, -019, E14-BL-004, -009, -010) was built in this increment.

## 2. Status legend

| Status | Meaning |
|---|---|
| **Implemented** | All acceptance criteria are met in code and covered by at least one automated test, E2E check or screenshot. Minor deviations or interpretations are stated in *Notes*. |
| **Partial** | The software function exists and is tested, but part of the acceptance criteria depends on an external system, an optional provider or a business sign-off that is not yet in place; the gap is stated in *Notes*. |
| **Deferred** | Not built in Increment 3; target increment stated. (No Increment 3 item is deferred.) |

## 3. Verification legend

### 3.1 Integration tests (Spring Boot + PostgreSQL 16, `apps/wz-core-app/src/test/java/com/wellnessz/app`)

| Ref | Test method |
|---|---|
| IT-A1 | `Increment3AbsenceIntegrationTest.f01FeedReconciliationMismatchResolveAndStatusOnlyHistory` |
| IT-A2 | `Increment3AbsenceIntegrationTest.f02TriggerQueueAcceptNotifiesManagerAndHrAndFirstContactKpi` |
| IT-A3 | `Increment3AbsenceIntegrationTest.f02RuleConfigurationVersioningAndTestMode` |
| IT-A4 | `Increment3AbsenceIntegrationTest.f02CrewSicknessOpensAmeReviewWithPendingRosterStatus` |
| IT-A5 | `Increment3AbsenceIntegrationTest.f03CertificatesUploadChecksValidationVerificationAndHrValidityOnly` |
| IT-A6 | `Increment3AbsenceIntegrationTest.f04LongTermAbsencePathwayMilestonesHrStageAndReturnToWork` |
| IT-A7 | `Increment3AbsenceIntegrationTest.f05AdviceRequestReportScreeningConsentReleaseAndDecline` |
| IT-A8 | `Increment3AbsenceIntegrationTest.f05DemoAdviceReportAwaitsEmployee1` |
| IT-A9 | `Increment3AbsenceIntegrationTest.f06AnalyticsSuppressionCategoriesAndExport` |
| IT-A10 | `Increment3AbsenceIntegrationTest.schedulerAndOtherNonOhRolesCannotReadAbsenceData` |
| IT-S1 | `Increment3SurveillanceIntegrationTest.f01ProgrammesBuilderSimulationAndActivation` |
| IT-S2 | `Increment3SurveillanceIntegrationTest.f02DueListInvitationsNoShowEscalationAndTeamStatus` |
| IT-S3 | `Increment3SurveillanceIntegrationTest.f03PreEmploymentCandidatePortalOutcomeAndHrStatusOnly` |
| IT-S4 | `Increment3SurveillanceIntegrationTest.f04CrewExaminationCertificatesRosterStatusAndExpiry` |
| IT-S5 | `Increment3SurveillanceIntegrationTest.f05ImmunisationFastEntryBatchTitreAndStatusViews` |
| IT-S6 | `Increment3SurveillanceIntegrationTest.f06IhImportEnrolsSegMembersAboveActionLevel` |
| IT-S7 | `Increment3SurveillanceIntegrationTest.f07DeviceStsResultRaisesFollowUpAndCase` |
| IT-S8 | `Increment3SurveillanceIntegrationTest.f07ReturnAfterIllnessBlocksFitDecisionUntilCompleted` |
| IT-S9 | `Increment3SurveillanceIntegrationTest.f08ComplianceSnapshotTrendOverdueAndAuditPacks` |
| IT-C1 | `Increment3SchedulingIntegrationTest.f01BookMoveCancelWithConflictsAndWaitlistOffer` |
| IT-C2 | `Increment3SchedulingIntegrationTest.f01EmployeeSelfBookingRules` |
| IT-C3 | `Increment3SchedulingIntegrationTest.f01RemindersAndNoShowJobsWithStatusListeners` |
| IT-C4 | `Increment3SchedulingIntegrationTest.f02CampaignGeneratesSlotsAndBooksFiveHundredInOneCall` |
| IT-C5 | `Increment3SchedulingIntegrationTest.f03CheckInByBadgeQueueBoardCallNextAndComplete` |
| IT-C6 | `Increment3SchedulingIntegrationTest.f04EquipmentCalibrationBlocksE03DeviceCapture` |
| IT-C7 | `Increment3SchedulingIntegrationTest.f04ColdChainIotReadingExcursionAcknowledgeAndReport` |
| IT-C8 | `Increment3SchedulingIntegrationTest.f05CapacityWhatIfUtilisationAndCharging` |
| IT-C9 | `Increment3SchedulingIntegrationTest.catalogueTemplatesTelehealthAndRoleDenials` |
| ARCH | `ArchitectureTest` – incl. `increment2ModulesDoNotDependOnIncrement3`, `absenceIsIndependentOfSurveillanceAndScheduling`, `surveillanceIsIndependentOfAbsenceAndScheduling`, `schedulingIsIndependentOfClinicalModules`, `documentServiceIsAPlatformService` |

### 3.2 Unit tests

| Ref | Test class (module) | Covers |
|---|---|---|
| UT-RULE | `RuleEngineTest` (wz-e06-absence, 7) | duration (calendar days inclusive, minimum days), frequency window, Monday/Friday pattern, safety-critical and post-incident rules, crew sickness → AME review, subsidiary override of the same type and action, parameter validation |
| UT-PAT | `AbsencePatternsTest` (3) | Bradford factor, window clipping, summary patterns |
| UT-CERT | `CertificateChecksTest` (3) | field extraction (issuer, registration, dates), duplicate by SHA-256 / certificate number / same employee and dates, alteration flags |
| UT-MS | `MilestonePlanTest` (3) | milestone plan relative to absence start, RTW review, conference times |
| UT-AGG | `UnitAggregatorTest` (2) | small-unit suppression without counts, medians and percentages |
| UT-ELIG | `EligibilityTest` (wz-e07-surveillance, 5) | licence / job / hazard / SEG matching, filters, next due incl. over-40 frequency and grace, status precedence, population simulation without writes |
| UT-SURV | `SurveillanceRulesTest` (8) | STS, spirometry decline, biomonitoring limits, GS1 lot parsing, crew status and alert bands, no-show re-invite then single escalation, compliance small-group suppression, immunisation course / titre / booster |
| UT-SLOT | `SlotEngineTest` (wz-e14-scheduling, 7) | weekly templates, site time zone across DST, session type filter, busy clinician / room / device / employee, device calibration on the day, conflicting resource reported |
| UT-BOOK | `BookingRulesTest` (4) | self-booking rules, reminder windows, no-show detection, check-in window / tickets / wait estimate |
| UT-CAMP | `CampaignAndCapacityTest` (2) | campaign slots = days × times × stations × slots, capacity and what-if maths |
| UT-EQ | `EquipmentRulesTest` (5) | calibration state from due date, next due and due alerts, excursion detection, device verdicts, `DeviceCalibrationCheck` lookup by device id |
| UT-NOTIF | `NotificationRulesTest.everyApprovedTemplatePassesTheContentRules` (platform/wz-notification) | all templates incl. the 23 new Increment-3 templates render content-free |
| UT-BFF | `ApiProxyPathTest` (apps/wz-bff) | `/workspace/advice-requests`, `/workspace/surveillance/compliance` keep their prefix |

### 3.3 Web, E2E and screenshots

| Ref | Artefact |
|---|---|
| WEB-OH | `web/oh-workspace` vitest (463 tests): `screens/absence/Absence.test.tsx`, `screens/surveillance/Surveillance.test.tsx`, `Shell.test.tsx`, `screens/scheduling/Scheduling.test.tsx`, `domain/increment3.test.ts`, `domain/scheduling.test.ts` |
| WEB-EMP | `web/employee-app` vitest (191 tests): `screens/Occupational.test.tsx` (appointments, absence, advice review, surveillance, pre-employment views), `MyAdvice.test.tsx`, `api/occupational.test.ts` |
| WEB-MGR | `web/manager-workspace` vitest (154 tests): `Absence.test.tsx`, `AdviceRequests.test.tsx`, `Compliance.test.tsx`, `api/absence.test.ts`, `api/advice.test.ts`, `api/surveillance.test.ts` |
| E2E-nn | `tools/e2e/e2e-increment3.js` check nn (list below) – real Keycloak logins through the BFF, core API and three web apps – **32 / 32 PASS** |
| SCR `name` | Screenshot `docs/images/screens/increment-03/name.png` captured by the E2E run (37 in total) |

E2E checks, in order: **01** absence feed ingested and reconciled to the employee master (id and badge), unknown ref
listed for HR · **02** duration rule (> 10 days) raises an early-intervention item with the absence history · **03** nurse
accepts the item (case opened, manager / HR told "OH engaged") and records first contact · **04** trigger-rule test mode
shows who would be flagged without creating cases · **05** employee uploads a sick-certificate photo from the app ·
**06** OH validates the certificate; HR sees validity only · **07** long-term absence pathway has the week 2/4/8/12/26
milestones with overdue flags · **08** HR moves the LTA to a formal review; clinical wording in HR notes is refused ·
**09** manager requests OH advice with structured questions · **10** advice report refuses a diagnosis, then is signed for
employee review · **11** employee consents; manager receives fitness / adjustments / prognosis only, with the signed
report · **12** manager sees absence dates and status but never the reason category · **13** absence analytics by unit
with small-group suppression and responsiveness KPIs · **14** programme simulation shows the eligible population by unit ·
**15** scheduler sees the due list and invites + books in bulk through the clinic diary · **16** noise dosimetry above the
action level enrols the exposure group into hearing conservation · **17** out-of-calibration audiometer is blocked;
accepted after the biomedical calibration · **18** abnormal surveillance results have a follow-up case and recheck task ·
**19** FIT decision for a safety-critical role is blocked until the return-after-illness medical is done · **20** candidate
completes the health questionnaire and consent online · **21** OH records the outcome; HR onboarding sees
FIT / UNFIT / PENDING only · **22** crew licensing sees certificate validity and the crew without a valid medical ·
**23** vaccine recorded from a scanned GS1 lot barcode (lot, expiry, site, consent) · **24** compliance dashboard by
programme and an anonymised food-safety audit pack (PDF + CSV) · **25** manager sees team surveillance status only ·
**26** scheduler books a clinician + room; a double booking is refused with a conflict · **27** employee self-books (or
reschedules) a vaccination slot in the app · **28** reception checks in by badge; the queue board shows tickets, never
names · **29** physician calls the next patient and is handed off to the chart · **30** vaccine-fridge IoT reading outside
2–8 °C opens an excursion that the nurse acknowledges · **31** campaign day books a cohort in one call with performance
tracking · **32** capacity forecast compares surveillance / absence demand with clinic capacity per site.

### 3.4 Platform and migration artefacts referenced below

| Artefact | Content |
|---|---|
| V9 `V9__increment3_platform.sql` | `masterdata.clinic_site`; extra hazards and job codes for the programme set; retention classes `ABSENCE_RECORD` (6 y) and `PRE_EMPLOYMENT` (1 y) – *proposed, pending DPO / CMO approval* |
| V10 `V10__e06_absence.sql` | schema `absence`: `feed_run`, `episode`, `feed_mismatch`, `trigger_rule`, `trigger_rule_version`, `queue_item`, `contact`, `certificate`, `certificate_call`, `lta_pathway`, `lta_milestone`, `case_conference`, `advice_request`, `advice_report`; 8 default trigger rules; config `absence.notify-hr-on-engagement` |
| V11 `V11__e07_surveillance.sql` | schema `surveillance`: `programme`, `employee_profile`, `enrolment`, `completion`, `invitation`, `pre_employment`, `crew_certificate`, `immunisation_requirement`, `vaccine_administration`, `titre`, `immunisation_reminder`, `exposure_group`, `ih_measurement`, `cosmic_dose`, `pregnancy_declaration`, `evaluation_rule`, `follow_up`, `return_after_illness`, `compliance_snapshot`, `audit_pack`; 13 programmes, 6 evaluation rules |
| V12 `V12__e14_scheduling.sql` | schema `scheduling`: `appointment_type`, `resource`, `diary_template`, `campaign`, `appointment`, `campaign_slot`, `waitlist_entry`, `equipment`, `calibration`, `temperature_reading`, `excursion` |
| Kernel SPIs (`platform/wz-case-kernel/.../support`) | `AppointmentBooking` (impl E14), `AppointmentStatusListener` (impl E06, E07), `DeviceCalibrationCheck` (impl E14, called by E03 device capture), `ResultFollowUpListener` (impl E07, called by E03), `FitnessClearanceGuard` (impl E07, called by E05), `AbsenceSignalListener` (called by E06, impl E07), `CapacityDemandSource` (impl E06, E07; used by E14) |
| `DocumentService` additions | `storeUpload`, `generateSigned`, `shareAsSystem`, `systemContent`; templates `OH_ADVICE_REPORT`, `VACCINATION_CERTIFICATE`, `PRE_EMPLOYMENT_OUTCOME` |
| Notifications | 23 new content-free templates in `NotificationTemplates` (e.g. `absence.oh-engaged`, `absence.milestone-overdue`, `absence.advice-review`) |
| Scheduled jobs | E06 `AbsenceJobs` (`absence-feed` 02:40, `absence-triggers` 03:00, `absence-lta` 03:10 UTC); E07 `SurveillanceJobs` (`surveillance-due` 01:40 incl. compliance snapshot, `crew-certificates` 01:50, `pre-employment-purge` 03:10); E14 `SchedulingJobs` (`appointment-reminders` every 15 min, `appointment-no-show` every 15 min, `equipment-calibration` daily 02:40) |

---

## 4. E06 – Absence & Sickness Management

Module `modules/wz-e06-absence` (package `com.wellnessz.e06`, schema `absence`, V10). OH Workspace screens under
`web/oh-workspace/src/screens/absence`; manager / HR in `web/manager-workspace/src/screens/Absence.tsx`,
`AdviceRequests.tsx`; employee in `web/employee-app/src/screens/MyAbsence.tsx`, `AdviceReports.tsx`, `MyAdvice.tsx`.

### 4.1 High-level requirements

| ID | Requirement (short) | Implementation | UI screen | Verification | Status | Notes |
|---|---|---|---|---|---|---|
| E06-HLR-01 | Daily ingestion of absence episodes from HRIS / T&A / crew systems, reconciliation to employee master, absence history per employee | `AbsenceFeedService`, `AbsenceFeedController` (`POST /integration/absence/episodes`, `/absence/feed/runs`, `/absence/feed/mismatches`, `/resolve`), `AbsenceFeedConnector` SPI + `NoopAbsenceFeedConnector`, `AbsenceJobs.feed`; `EpisodeService`; tables `absence.feed_run`, `episode`, `feed_mismatch` | OH `#/absence/feed`; chart tab `#/employees/{id}/chart/absence` | IT-A1, E2E-01, SCR `inc3-oh-03-absence-feed-quality`, `inc3-oh-08-chart-absence` | Partial | Push ingestion API, reconciliation (employee id and badge) and history complete; the daily pull runs through the connector SPI whose default is a no-op – real HRIS / T&A / crew-management adapters are E15 |
| E06-HLR-02 | Configurable trigger rules (duration, frequency, pattern, safety-critical) raise early-intervention / LTA cases and notify HR / manager without medical detail | `AbsenceRuleService`, `RuleEngine`, `AbsenceTriggerEngine`, `AbsenceQueueService`; `/absence/rules`, `/absence/rules/test`, `/absence/queue/*`; notification `absence.oh-engaged`; tables `trigger_rule`, `trigger_rule_version`, `queue_item` | OH `#/absence/rules`, `#/absence/queue` | IT-A2, IT-A3, IT-A4, UT-RULE, E2E-02..04, SCR `inc3-oh-01-absence-queue`, `inc3-oh-02-absence-rules` | Implemented | Rule `minDays` means "at least" (seeded `DURATION_10D` has `minDays` 11); only SICKNESS / INJURY episodes count |
| E06-HLR-03 | Certificates from employees (upload / photo) and partner clinics (electronic); completeness, duplicate / alteration detection, verification outcome | `CertificateService`, `CertificateChecks`, `AbsenceCertificateController` (`/me/absence/certificates`, `/absence/certificates`, `/integration/absence/certificates`, `/validate`, `/calls`, `/verify`, `/content`); `DocumentService.storeUpload`; tables `certificate`, `certificate_call` | Employee `#/absence`; OH `#/absence/certificates`; HR `#/absence` | IT-A5, UT-CERT, E2E-05, E2E-06, SCR `inc3-emp-02-my-absence`, `inc3-oh-04-absence-certificates` | Implemented | Partner-clinic channel is an integration API (INTEGRATION role); partner onboarding is E15 |
| E06-HLR-04 | LTA pathway with milestones (week 2, 4, 8, 12, 26), contact schedule, HR policy stages, advice reports, RTW linkage | `LtaService`, `MilestonePlan`, `AbsenceLtaController` (`/absence/lta`, `/milestones/{id}/complete`, `/skip`, `/contacts`, `/conference`, `/workspace/absence/lta/{caseId}/hr-stage`); E05 RTW plan link; tables `lta_pathway`, `lta_milestone`, `case_conference` | OH `#/absence/lta/{caseId}`; HR `#/absence` (LTA section) | IT-A6, UT-MS, E2E-07, E2E-08, SCR `inc3-oh-05-lta-pathway`, `inc3-mgr-01-hr-absence` | Implemented | RTW_REVIEW completion closes the pathway |
| E06-HLR-05 | Manager / HR advice request with structured questions; report limited to fitness, restrictions, adjustments, prognosis | `AdviceService`, `AdviceController` (`/absence/advice/questions`, `/workspace/advice-requests`, `/absence/advice-requests/{id}/accept`, `/report`, `/report/sign`, `/me/advice-reports/{id}/decision`); template `OH_ADVICE_REPORT`; tables `advice_request`, `advice_report` | Manager `#/advice-requests`; OH `#/absence/advice`; employee `#/advice` | IT-A7, IT-A8, E2E-09..11, SCR `inc3-mgr-02-advice-requests`, `inc3-oh-06-advice-report`, `inc3-emp-01-advice-review` | Implemented | Every free-text field is screened; a diagnosis returns 422 `clinical_content_not_allowed` |
| E06-HLR-06 | Absence metrics (rate, frequency, duration, category) by subsidiary / department / job family with anonymisation thresholds | `AbsenceAnalyticsService`, `UnitAggregator`, `AbsenceAnalyticsController` (`/absence/analytics/units` (groupBy SUBSIDIARY, DEPARTMENT or JOB_FAMILY), `/kpis`, `/export`) | OH `#/absence/analytics`; HR `#/absence` (analytics section) | IT-A9, UT-AGG, UT-PAT, E2E-13, SCR `inc3-oh-07-absence-analytics` | Implemented | Minimum group size 5 (`wellnessz.analytics.min-group-size`); categories only for OH roles; the small demo population means most groups are suppressed (by design) |

### 4.2 Features

| ID | Feature | Implementation | UI screen | Verification | Status | Notes |
|---|---|---|---|---|---|---|
| E06-F01 | Absence feed & history – episodes visible next day; mismatches listed for HR data stewards | As E06-HLR-01; data-quality view of runs and mismatches (HR, OH_ADMIN) | OH `#/absence/feed`, chart tab *Absence* | IT-A1, E2E-01, SCR `inc3-oh-03-absence-feed-quality` | Partial | Next-day visibility is met for pushed data; no live source system feeds the daily pull yet (E15) |
| E06-F02 | Trigger rules & early-intervention queue – rules editable by Admin; cases raised with history; notification same day | As E06-HLR-02; `AbsenceJobs.triggers` (daily) | OH `#/absence/rules`, `#/absence/queue` | IT-A2, IT-A3, UT-RULE, E2E-02..04, WEB-OH (`Absence.test`), SCR `inc3-oh-01-absence-queue`, `inc3-oh-02-absence-rules` | Implemented | Notification is sent when OH accepts the item (status-only, config `absence.notify-hr-on-engagement`) |
| E06-F03 | Medical certificate management – upload via app; fields extracted; verification outcome; HR sees validity only | As E06-HLR-03; OCR via platform `OcrProvider` | Employee `#/absence`; OH / reception `#/absence/certificates` | IT-A5, UT-CERT, E2E-05, E2E-06, WEB-EMP, SCR `inc3-oh-04-absence-certificates` | Partial | Field extraction works on PDF text layers; extraction from photos needs the optional Tesseract OCR provider (as in Increment 2) – without it the certificate is flagged unreadable and validated manually |
| E06-F04 | Long-term absence pathway – milestones auto-created; overdue contacts flagged; advice report at week 4 and 8 | As E06-HLR-04; `AbsenceJobs.lta` (overdue flags + `absence.milestone-overdue`) | OH `#/absence/lta/{caseId}` | IT-A6, UT-MS, E2E-07, WEB-OH, SCR `inc3-oh-05-lta-pathway` | Implemented | Plan: week 2 contact, week 4 and 8 advice report, week 8 case conference, week 12 HR review + report, week 26 IHR consideration |
| E06-F05 | Management advice request & OH report – released only after employee consent step (configurable); no diagnosis | As E06-HLR-05; config `absence.advice.employee-review-required` (default true) | Manager `#/advice-requests`; employee `#/advice` | IT-A7, E2E-10, E2E-11, WEB-MGR (`AdviceRequests.test`), WEB-EMP (`MyAdvice.test`) | Implemented | Release target is the line manager for manager requests, HR otherwise |
| E06-F06 | Absence analytics – dashboard by subsidiary / department; groups < threshold suppressed; monthly pack export | As E06-HLR-06; Bradford-type index, frequency, duration, KPI tiles with targets and trend; CSV monthly pack | OH `#/absence/analytics`; HR `#/absence` | IT-A9, UT-AGG, E2E-13, WEB-OH, WEB-MGR (`Absence.test`), SCR `inc3-oh-07-absence-analytics` | Implemented | "Benchmarks" are internal (unit vs group, KPI targets); no external benchmark data set is loaded |

### 4.3 Implementation backlog

| ID | Title (short) | Implementation | UI screen | Verification | Status | Notes |
|---|---|---|---|---|---|---|
| E06-BL-001 | Absence ingestion from HRIS / T&A / crew with reconciliation; daily job; data-quality dashboard; 99 % match rate (P1) | `AbsenceFeedService`, `AbsenceFeedConnector` SPI (`NoopAbsenceFeedConnector`), `AbsenceJobs.feed`, `/integration/absence/episodes`, `/absence/feed/runs`, `/mismatches`; `absence.feed_run`, `feed_mismatch` | OH `#/absence/feed` | IT-A1, E2E-01 (received 3, matched 2, unmatched 1), SCR `inc3-oh-03-absence-feed-quality` | Partial | Reconciliation, run log and mismatch list complete; no real source adapter (E15), so the 99 % match rate cannot be measured |
| E06-BL-002 | Employee absence timeline with episode types; filters; export to case; access by role (P1) | `EpisodeService`, `EpisodeViews`, `/absence/employees/{id}/episodes`, `/summary`, `/absence/episodes/{id}/link-case`, `/me/absence/episodes`, `/workspace/absence/team` | Chart tab `#/employees/{id}/chart/absence` (`AbsenceChartTab`); manager `#/absence`; employee `#/absence` | IT-A1, IT-A10, UT-PAT, E2E-12, WEB-OH (timeline filters), SCR `inc3-oh-08-chart-absence`, `inc3-mgr-03-team-absence` | Implemented | "Export to case" = link episode to the case; managers see dates and status but never the reason category |
| E06-BL-003 | Admin configures trigger rules per subsidiary; rule builder; test mode; audit; defaults (P1) | `AbsenceRuleService` (versioned in `trigger_rule_version`, audit `ABSENCE_RULE_CREATE/UPDATE/DEACTIVATE/TEST`), `/absence/rules` (OH_ADMIN), `/absence/rules/test` | OH `#/absence/rules` (`Rules.tsx`) | IT-A3, UT-RULE, E2E-04, WEB-OH (PATTERN rule built and tested), SCR `inc3-oh-02-absence-rules` | Implemented | 8 default rules seeded; a subsidiary rule overrides the default of the same type and action; POST_HOLIDAY pattern uses config `absence.public-holidays` |
| E06-BL-004 | Early-intervention queue with history attached; accept / close; SLA (P1) | `AbsenceQueueService`, `/absence/queue`, `/accept` (opens / links kernel ABSENCE case), `/contact`, `/close`; `slaDueAt`, `slaBreached` | OH `#/absence/queue` (`Queue.tsx`) | IT-A2, E2E-02, E2E-03, WEB-OH, SCR `inc3-oh-01-absence-queue` | Implemented | |
| E06-BL-005 | HR told OH is engaged (no medical detail); template DPO-approved; opt-in | Template `absence.oh-engaged` (content-free), `AbsenceSupport.notifyHr` (mailbox `absence.hr-email`) + line manager; switch `absence.notify-hr-on-engagement` | Manager / HR inbox, `#/absence` "OH engaged" flag | IT-A2, UT-NOTIF, E2E-03 | Partial | Function and opt-in switch delivered; formal DPO approval of the template wording not yet recorded |
| E06-BL-006 | Employee uploads sick certificate from phone; OCR; confirmation; HR sees valid / invalid only | `POST /me/absence/certificates` (multipart, magic bytes, ≤ 10 MB) → `DocumentService.storeUpload` + `CertificateChecks.extract`; `/workspace/absence/certificates` (validity only) | Employee `#/absence` (camera capture / file) | IT-A5, E2E-05, E2E-06, WEB-EMP (`Occupational.test` absence view), SCR `inc3-emp-02-my-absence` | Partial | OCR of photos depends on the optional Tesseract provider; PDF text layers are extracted without it. BFF multipart defect found and fixed in E2E (`spring.servlet.multipart.enabled=false`) |
| E06-BL-007 | Verification checklist and call log for suspicious certificates; duplicate detection; outcome; escalation to HR (P3) | `CertificateService.validate`, `/calls`, `/verify` (with `escalateToHr` → `absence.certificate-escalation`); `absence.certificate_call` | OH / reception `#/absence/certificates` (`Certificates.tsx`) | IT-A5, UT-CERT, WEB-OH (reception validates and logs calls but cannot open content or verify; OH verifies as fraudulent and escalates) | Implemented | SUSPICIOUS shows as PENDING to HR |
| E06-BL-008 | LTA pathway with milestones and contact schedule; contacts logged; overdue flags; conference option (P1) | `LtaService`, `MilestonePlan`, `/absence/lta/{caseId}/contacts`, `/conference`, `/milestones/{id}/complete`, `/skip`, `AbsenceJobs.lta` | OH `#/absence/lta/{caseId}` (`Lta.tsx`) | IT-A6, UT-MS, E2E-07, WEB-OH (overdue milestones), SCR `inc3-oh-05-lta-pathway` | Implemented | |
| E06-BL-009 | Physician issues OH advice reports at milestones; template; consent step; release to HR; version (P1) | `AdviceService` (report versions, sign → `OH_ADVICE_REPORT` via `DocumentService.generateSigned`, release with disclosure log); LTA advice milestones | OH `#/absence/advice` (`Advice.tsx`) | IT-A6, IT-A7, E2E-10, E2E-11, SCR `inc3-oh-06-advice-report` | Implemented | Template wording follows the Increment-2 document template approval process (CMO / DPO) |
| E06-BL-010 | Manager requests OH advice with structured questions; question library; SLA; response in portal | `GET /absence/advice/questions`, `POST /workspace/advice-requests`, SLA in working days (`absence.advice.sla-working-days`, default 5), `/withdraw` | Manager `#/advice-requests` (`AdviceRequests.tsx`) | IT-A7, E2E-09, WEB-MGR (`AdviceRequests.test`), SCR `inc3-mgr-02-advice-requests` | Implemented | Answers capped at 400 characters and screened |
| E06-BL-011 | Employee sees advice report before release; comment; consent / decline with reason; audit | `/me/advice-reports`, `/me/advice-reports/{id}/decision` (consent → RELEASED, decline → WITHHELD with reason seen by OH only) | Employee `#/advice` (`AdviceReports.tsx`, `MyAdvice.tsx`) | IT-A7, IT-A8, E2E-11, WEB-EMP (`Occupational.test` advice review, `MyAdvice.test`), SCR `inc3-emp-01-advice-review` | Implemented | |
| E06-BL-012 | CMO absence dashboards by subsidiary / department with thresholds; drill-down to unit only; export | `/absence/analytics/units`, `/export` (CMO, Nursing Head, HR – CSV monthly pack); audit `ABSENCE_ANALYTICS_READ` | OH `#/absence/analytics`; HR `#/absence` | IT-A9, E2E-13, WEB-OH (suppression, CMO export, OH_ADMIN no export), WEB-MGR, SCR `inc3-oh-07-absence-analytics` | Implemented | |
| E06-BL-013 | Days from trigger to first OH contact; target; trend | `/absence/analytics/kpis` (`medianDaysTriggerToContact`, target 5, monthly trend; first REACHED contact) | OH `#/absence/analytics` KPI tiles | IT-A2, IT-A9, E2E-13 (median 1 day; LTA with case 100 %) | Implemented | |
| E06-BL-014 | Crew sickness triggers a fitness review; crew rule set; AME queue; status feed to rostering on outcome (P1) | Crew rule set (action AME_REVIEW) in `RuleEngine`; `AbsenceTriggerEngine` opens case with `UNFIT_PENDING` and `CaseService.publishFitness` (status-only roster feed); AME queue filter; outcome via E05 crew assessment | OH `#/absence/queue` (AME filter) | IT-A4, UT-RULE (`crewSicknessByDurationOrFrequencyRaisesAmeReview`), WEB-OH (AME queue filter) | Implemented | Crew sickness source system is part of the E15 feed (see E06-BL-001) |

---

## 5. E07 – Health Surveillance & Medical Programmes

Module `modules/wz-e07-surveillance` (package `com.wellnessz.e07`, schema `surveillance`, V11). OH Workspace screens under
`web/oh-workspace/src/screens/surveillance`; manager / HR `Compliance.tsx`, `PreEmployment.tsx`; employee / candidate
`MySurveillance.tsx`, `PreEmployment.tsx`.

### 5.1 High-level requirements

| ID | Requirement (short) | Implementation | UI screen | Verification | Status | Notes |
|---|---|---|---|---|---|---|
| E07-HLR-01 | Programmes as rules (eligibility by job, department, exposure, age, licence; panel; frequency; standard; clinician); automatic due dates | `ProgrammeService`, `ProgrammeCatalog`, `Eligibility`, `EnrolmentEngine`, `Population`, `ProgrammeDueController` (`/surveillance/programmes`, `/simulate`, `/activate`, `/retire`, `/due/recalculate`); `surveillance.programme`, `enrolment`, `employee_profile` | OH `#/surveillance/programmes`, `#/surveillance/programmes/{code}` | IT-S1, UT-ELIG, E2E-14, SCR `inc3-oh-09-programmes`, `inc3-oh-10-programme-builder` | Implemented | Age rule (over-40 frequency) uses `employee_profile.date_of_birth` |
| E07-HLR-02 | Due / overdue lists, invitations, bulk and campaign scheduling, no-shows with re-invitation | `DueService`, `NoShowPolicy`, `/surveillance/due`, `/surveillance/invitations`, `Booker` → kernel `AppointmentBooking` (E14); campaigns in E14 | OH `#/surveillance/due`; `#/scheduling/campaigns` | IT-S2, IT-C4, UT-SURV, E2E-15, E2E-31, SCR `inc3-oh-11-due-list`, `inc3-oh-24-campaigns` | Implemented | |
| E07-HLR-03 | Pre-employment medicals with job-specific panels, fitness outcome and HR status feed (fit / unfit / pending) | `PreEmploymentService`, `Questionnaire`, `PreEmploymentController` (`/integration/pre-employment`, `/surveillance/pre-employment`, `/outcome`, `/workspace/pre-employment`, `/me/pre-employment/*`); `surveillance.pre_employment`; template `PRE_EMPLOYMENT_OUTCOME`; `hr-systems` allow-list | OH `#/surveillance/pre-employment`; HR `#/pre-employment`; candidate `#/pre-employment` | IT-S3, E2E-20, E2E-21, SCR `inc3-oh-16-pre-employment`, `inc3-mgr-04-pre-employment`, `inc3-emp-03-candidate-portal` | Implemented | New role CANDIDATE |
| E07-HLR-04 | Crew aeromedical exams (class 1/2, cabin crew), certificate validity, limitations, AME records per national requirements, expiry alerts | `CrewCertificateService`, `CrewAlerts`, `CrewImmunisationController` (`/surveillance/crew/exam-template`, `/examinations`, `/certificates`, `/without-valid-medical`, `/certificates/import`, `/authority-report`); `surveillance.crew_certificate`; `CREW_MEDICAL_REPORT` / `CABIN_CREW_MEDICAL_REPORT` | OH `#/surveillance/crew` | IT-S4, UT-SURV, E2E-22, SCR `inc3-oh-17-crew-certificates` | Partial | Register, limitations, alerts and roster status complete; exam / report layout follows a draft CAA format pending confirmation by the national CAA |
| E07-HLR-05 | Immunisations and titres against role requirements; due lists and campaigns | `ImmunisationService`, `ImmunisationRules`, `Gs1Lot`; `/surveillance/immunisation/requirements`, `/due`, `/administrations`, `/administrations/batch`, `/titres`, `/me/immunisations`; tables `immunisation_requirement`, `vaccine_administration`, `titre`, `immunisation_reminder` | OH `#/surveillance/immunisation` | IT-S5, UT-SURV, E2E-23, SCR `inc3-oh-18-immunisation` | Implemented | |
| E07-HLR-06 | Exposure registers linked to job codes and IH results, driving eligibility (incl. cosmic dose for crew) | `ExposureService`, `ExposureFollowUpController` (`/surveillance/exposure-groups`, `/ih-measurements/import`, `/integration/ih-measurements`, `/cosmic-dose/import`, `/integration/cosmic-dose`, `/employees/{id}/exposure-history`); tables `exposure_group`, `ih_measurement`, `cosmic_dose`, `pregnancy_declaration` | OH `#/surveillance/exposure` | IT-S6, E2E-16, SCR `inc3-oh-12-exposure` | Implemented | Hazards and job codes extended in V9 |
| E07-HLR-07 | Results evaluated against standards (STS, spirometry, biomonitoring); automatic follow-up cases and regulatory notifications | `EvaluationRules`, `FollowUpService` (kernel `ResultFollowUpListener`, called by E03 for LIS and device results), `/surveillance/follow-ups`, `/review`, `/evaluation-rules`; tables `evaluation_rule`, `follow_up` | OH `#/surveillance/follow-ups` | IT-S7, UT-SURV, E2E-18, SCR `inc3-oh-14-follow-ups` | Implemented | |
| E07-HLR-08 | Compliance dashboards by subsidiary / department / programme; audit evidence packs (CAA, IOSA, food safety, labour inspectorate) | `ComplianceService`, `ComplianceMaths`, `AuditPackService`, `ComplianceController` (`/surveillance/compliance`, `/trend`, `/overdue`, `/audit-packs/templates`, `/audit-packs`, `/content`); tables `compliance_snapshot`, `audit_pack` | OH `#/surveillance/compliance`; manager `#/compliance` | IT-S9, UT-SURV, E2E-24, E2E-25, SCR `inc3-oh-19-compliance`, `inc3-mgr-05-compliance` | Implemented | |

### 5.2 Features

| ID | Feature | Implementation | UI screen | Verification | Status | Notes |
|---|---|---|---|---|---|---|
| E07-F01 | Programme & protocol builder – programme defined without code; simulation by unit; version history | As E07-HLR-01; versions with `effective_from`, DRAFT → ACTIVE (CMO / OH physician) → RETIRED (CMO) | OH `#/surveillance/programmes/{code}` | IT-S1, UT-ELIG, E2E-14, WEB-OH (simulate and save new draft version), SCR `inc3-oh-10-programme-builder` | Implemented | |
| E07-F02 | Due-list engine & campaign scheduling – nightly due list by unit; 500 invited and booked in one campaign; overdue escalation to manager after 2 invitations | `SurveillanceJobs.dueJob` (01:40 UTC), `DueService`, `NoShowPolicy`; E14 `CampaignService` | OH `#/surveillance/due`, `#/scheduling/campaigns/{id}` | IT-S2, IT-C4 (500 booked in one call), UT-SURV, E2E-15, E2E-31, SCR `inc3-oh-11-due-list`, `inc3-oh-25-campaign-day` | Implemented | |
| E07-F03 | Pre-employment medicals – questionnaire online; exam booked; outcome to HR as status only within 1 day | As E07-HLR-03; government / visa medical fields | Candidate `#/pre-employment`; OH `#/surveillance/pre-employment`; HR `#/pre-employment` | IT-S3, E2E-20, E2E-21, WEB-EMP (pre-employment view), WEB-MGR, SCR `inc3-emp-03-candidate-portal` | Implemented | Status reaches HR immediately; the outcome document is shared to HR only when the candidate is already in the employee master (status view and disclosure log otherwise) |
| E07-F04 | Crew aeromedical & certificate tracking – validity per crew member; alerts fire; no crew rostered beyond expiry | As E07-HLR-04; `SurveillanceJobs.crewJob`; roster status via `CaseService.publishFitness` | OH `#/surveillance/crew` | IT-S4, UT-SURV, E2E-22, WEB-OH (CREW_LICENSING status only; AME fills CAA template), SCR `inc3-oh-17-crew-certificates` | Partial | CAA exam template formats pending CAA confirmation; authority report is a CSV draft |
| E07-F05 | Immunisation & titre registry – food-handler typhoid / hep A visible; flu campaign books 5,000 doses; certificate in portal | As E07-HLR-05; `VACCINATION_CERTIFICATE` via `/me/immunisations/certificate`; campaign bulk book limit 5,000 per call | OH `#/surveillance/immunisation`; employee `#/surveillance` | IT-S5, IT-C4, E2E-23, SCR `inc3-oh-18-immunisation`, `inc3-emp-04-my-surveillance` | Implemented | Bulk booking tested with 500 in one call; 5,000 is the per-call limit (`CampaignService.MAX_BULK`), not load-tested |
| E07-F06 | Exposure registers & IH linkage – noise SEG above action level enrols job codes into hearing conservation; crew cosmic dose per year | As E07-HLR-06; eligibility recalculation on import | OH `#/surveillance/exposure` | IT-S6, E2E-16 (86.5 dB(A)), WEB-OH (HSE CSV import with row errors), SCR `inc3-oh-12-exposure` | Implemented | |
| E07-F07 | Result evaluation & auto-follow-up – STS creates follow-up case and recheck task within 48 h | As E07-HLR-07 | OH `#/surveillance/follow-ups` | IT-S7, UT-SURV, E2E-18, WEB-OH (physician review), SCR `inc3-oh-14-follow-ups` | Implemented | |
| E07-F08 | Compliance dashboards & audit packs – pack generated in < 5 min; dashboard refreshed nightly | As E07-HLR-08; nightly `compliance_snapshot` in the due job | OH `#/surveillance/compliance` | IT-S9, E2E-24, WEB-OH (AUDITOR anonymised, Nursing Head drill-down), SCR `inc3-oh-19-compliance` | Implemented | Pack generation is synchronous (seconds on demo data) |

### 5.3 Implementation backlog

| ID | Title (short) | Implementation | UI screen | Verification | Status | Notes |
|---|---|---|---|---|---|---|
| E07-BL-001 | Define programme with eligibility rules, panel, frequency without code; version; effective date; simulation (P1) | `ProgrammeService` (`POST/PUT /surveillance/programmes`, `/simulate`, `/activate`), `Eligibility.simulate` (no writes) | OH `#/surveillance/programmes/{code}` (`Programmes.tsx`) | IT-S1, UT-ELIG, E2E-14, WEB-OH, SCR `inc3-oh-10-programme-builder` | Implemented | |
| E07-BL-002 | Configure initial 13-programme set; validated by CMO; eligible counts reconciled with HR headcount (P1) | V11 seeds PILOT_CLASS1, CABIN_CREW, FOOD_HANDLER, HEARING_CONSERVATION, RESPIRATORY, HAZMAT_DGR, AIRSIDE_DRIVER, FORKLIFT, WORKING_AT_HEIGHT, NIGHT_WORKER, SECURITY_SCREENER, CLINICAL_BBP, VDU; V9 hazards / job codes | OH `#/surveillance/programmes` | IT-S1, E2E-14, SCR `inc3-oh-09-programmes` | Partial | All 13 configured; content validation by the CMO and reconciliation against real HR headcount (needs the HRIS feed) pending |
| E07-BL-003 | Nightly due lists by unit; filters; bulk invite; templates; invitation log (P1) | `SurveillanceJobs.dueJob`, `/surveillance/due` (filters), `POST /surveillance/invitations` (bulk invite + book), `GET /surveillance/invitations`; `surveillance.invitation` | OH `#/surveillance/due` (`Due.tsx`) | IT-S2, E2E-15, WEB-OH (multi-select bulk invite + book, invitation log), SCR `inc3-oh-11-due-list` | Implemented | New role CLINIC_SCHEDULER |
| E07-BL-004 | Campaign mode (site, dates, slots, device kits); attendance capture | E14 `CampaignService` (`/scheduling/campaigns`, `/generate-slots`, `/book`, `/attendance`), `CampaignPlanner` | OH `#/scheduling/campaigns/{id}` | IT-C4, UT-CAMP, E2E-31, SCR `inc3-oh-25-campaign-day` | Implemented | Built in E14 (shared by E07) |
| E07-BL-005 | Manager escalated after two ignored invitations; status only; report | `NoShowPolicy` (re-invite, then single escalation), `/workspace/surveillance/compliance` | Manager `#/compliance` (`Compliance.tsx`) | IT-S2, UT-SURV (`noShowReinvitesThenEscalatesOnce`), E2E-25, WEB-MGR (`Compliance.test`), SCR `inc3-mgr-05-compliance` | Implemented | |
| E07-BL-006 | Trigger pre-employment medical from HRIS offer event; panel auto-selected; status feed back; SLA (P1) | `POST /integration/pre-employment` (INTEGRATION), `POST /surveillance/pre-employment` (HR manual trigger), panel from job code, `slaDueAt`, outbox to `hr-systems` (allow-list `offerRef`, `candidateId`, `preEmploymentStatus`, `ohEngaged`, `certificateValidity`) | HR `#/pre-employment` (manual trigger form) | IT-S3, E2E-21, WEB-MGR (manual trigger form, 422 mapping) | Partial | Event API and status feed complete; the HRIS offer-event producer and consumer adapters are E15 |
| E07-BL-007 | Candidate completes questionnaire and consent online; documents; retention if not hired (P1) | `/me/pre-employment`, `/questionnaire`, `/consent`, `/documents`, `/book` (CANDIDATE); `SurveillanceJobs.purgeJob`; retention class `PRE_EMPLOYMENT` (1 y) | Candidate `#/pre-employment` (employee-app, `PreEmployment.tsx`) | IT-S3, E2E-20, WEB-EMP (questions, consent, booking waits), SCR `inc3-emp-03-candidate-portal` | Implemented | Retention value proposed, pending DPO / CMO approval |
| E07-BL-008 | Government / visa medical tracked alongside company medical; status; document; expiry (P3) | `POST /surveillance/pre-employment/{id}/government-medical` (type, reference, status, `expiresOn`) | OH `#/surveillance/pre-employment` | IT-S3 | Implemented | |
| E07-BL-009 | CAA-format crew exam templates; certificate register; limitation codes; PDF; retention (P1) | `/surveillance/crew/exam-template`, `/examinations` (AME) → signed `CREW_MEDICAL_REPORT` / `CABIN_CREW_MEDICAL_REPORT` PDF; `crew_certificate` | OH `#/surveillance/crew` (`Crew.tsx`) | IT-S4, WEB-OH (AME fills CAA exam template), SCR `inc3-oh-17-crew-certificates` | Partial | Template and report layout await CAA confirmation; limitation codes reuse E05 list (AME / CMO confirmation pending since Increment 2) |
| E07-BL-010 | Expiry alerts 90 / 60 / 30 days; report of crew without valid medical; status feed to rostering (P1) | `CrewAlerts` (one alert per band), `SurveillanceJobs.crewJob`, `/surveillance/crew/without-valid-medical`, roster feed via `CaseService.publishFitness` | OH `#/surveillance/crew` | IT-S4, UT-SURV (`crewStatusAndAlertBands`), E2E-22 | Implemented | New role CREW_LICENSING sees status and expiry bands only |
| E07-BL-011 | Import results from external AMEs / AeMCs; validation; source tagged (P3) | `POST /surveillance/crew/certificates/import` (AME, INTEGRATION; source `EXTERNAL_AME`, optional PDF stored via `DocumentService`) | OH `#/surveillance/crew` | IT-S4 | Implemented | |
| E07-BL-012 | Role-based immunisation matrix; due list; campaign booking; certificate (P1) | `/surveillance/immunisation/requirements` (matrix per job code), `/due`, `ImmunisationRules`, `/me/immunisations/certificate` (`VACCINATION_CERTIFICATE`) | OH `#/surveillance/immunisation`; employee `#/surveillance` | IT-S5, UT-SURV, WEB-OH (OH_ADMIN edits matrix), SCR `inc3-oh-18-immunisation`, `inc3-emp-04-my-surveillance` | Implemented | |
| E07-BL-013 | Vaccine administration in < 1 min: fast entry, barcode lot scan, consent, batch mode | `/surveillance/immunisation/administrations`, `/batch`, `Gs1Lot` (GS1 human-readable and raw) | OH `#/surveillance/immunisation` (`Immunisation.tsx` fast entry) | IT-S5, UT-SURV (`gs1HumanReadableAndRawForms`), E2E-23, WEB-OH (scan + Enter, settings kept, expired lot refused, batch consent) | Implemented | Fast entry keeps vaccine / lot / site / route between people; the 1-minute target is not formally timed |
| E07-BL-014 | Import noise dosimetry / air sampling against SEGs; eligibility updates automatically; audit | `/surveillance/ih-measurements/import` (HSE_OFFICER CSV), `/integration/ih-measurements`, SEG mapping, recalculation | OH `#/surveillance/exposure` (`Exposure.tsx`) | IT-S6, E2E-16, WEB-OH (CSV row errors then import), SCR `inc3-oh-12-exposure` | Implemented | |
| E07-BL-015 | Annual cosmic dose per crew member; threshold alert; pregnancy-declaration workflow (P3) | `/surveillance/cosmic-dose/import`, `/integration/cosmic-dose`, `/surveillance/cosmic-dose` (AME, CMO; `aboveThreshold`), `/me/pregnancy-declaration` (1 mSv limit) | Employee `#/surveillance` (crew pregnancy form) | IT-S6, WEB-EMP (`Occupational.test` surveillance view) | Implemented | Dose data imported by file / API; no dose-calculation provider connected |
| E07-BL-016 | Audiometry STS vs baseline; flag; case; recheck task; regulatory note (P1) | `EvaluationRules` (`STS_HEARING`), `FollowUpService` (case, recheck task, regulatory-notification task) via `ResultFollowUpListener` | OH `#/surveillance/follow-ups` | IT-S7, UT-SURV (`stsNeedsAbnormalFlagAndShiftInEitherEar`), E2E-18 | Implemented | |
| E07-BL-017 | Spirometry decline and biological monitoring limits evaluated automatically | Rules `SPIRO_DECLINE`, `BM_BLOOD_LEAD`, `VISION_ABN`, `ECG_ABN`, `ANY_ABNORMAL`; `PUT /surveillance/evaluation-rules/{code}` (OH_ADMIN, CMO) | OH `#/surveillance/follow-ups` | IT-S7, UT-SURV (`spirometryDeclineAgainstPreviousOrRatio`, `biomonitoringLimitAndTestMapping`) | Implemented | |
| E07-BL-018 | Compliance dashboards by programme and unit; overdue drill-down; export; nightly refresh (P1) | `/surveillance/compliance`, `/trend`, `/overdue`, nightly `compliance_snapshot`; small-cell suppression | OH `#/surveillance/compliance` (`Compliance.tsx`) | IT-S9, UT-SURV, E2E-24, WEB-OH, SCR `inc3-oh-19-compliance` | Implemented | |
| E07-BL-019 | Audit evidence pack per programme (IOSA / CAA / food safety / labour inspectorate); anonymisation; PDF / XLSX (P3) | `AuditPackService` templates IOSA, CAA, FOOD_SAFETY, LABOUR_INSPECTORATE; PDF + CSV; anonymised by default | OH `#/surveillance/compliance` | IT-S9, E2E-24, WEB-OH (AUDITOR always anonymised) | Implemented | Spreadsheet output is CSV rather than native XLSX; identifiable packs only for CMO and Nursing Head |
| E07-BL-020 | Return-after-serious-illness medical required before RTW for safety-critical roles; task; blocks fitness status | `RaiService` implements `AbsenceSignalListener` (signal from E06) and `FitnessClearanceGuard` (called by E05 → 422 `clearance_required`); `/surveillance/return-after-illness`, `/complete`, `/waive` | OH `#/surveillance/rai` (`FollowUps.tsx`) | IT-S8, E2E-19, WEB-OH (nurse completes, cannot waive), SCR `inc3-oh-15-return-after-illness` | Implemented | Demo RAI for E1004 is skipped on `*_test` databases |

---

## 6. E14 – Clinic Operations, Scheduling & Resources

Module `modules/wz-e14-scheduling` (package `com.wellnessz.e14`, schema `scheduling`, V12). OH Workspace screens under
`web/oh-workspace/src/screens/scheduling`; employee `web/employee-app/src/screens/MyAppointments.tsx`.

### 6.1 High-level requirements

| ID | Requirement (short) | Implementation | UI screen | Verification | Status | Notes |
|---|---|---|---|---|---|---|
| E14-HLR-01 | Appointment types, durations, resources per site; diaries; templates; self-booking rules; waitlists; reminders; no-shows | `CatalogueService`, `AppointmentService`, `SlotEngine`, `BookingRules`, `CatalogueController`, `AppointmentController`, `SchedulingJobs`; tables `appointment_type`, `resource`, `diary_template`, `appointment`, `waitlist_entry` | OH `#/scheduling/diary`, `#/scheduling/catalogue`; employee `#/appointments` | IT-C1, IT-C2, IT-C3, IT-C9, UT-SLOT, UT-BOOK, E2E-26, E2E-27, SCR `inc3-oh-21-diary`, `inc3-oh-27-catalogue`, `inc3-emp-05-appointments` | Implemented | |
| E14-HLR-02 | Bulk / campaign scheduling and mobile-clinic days with slot generation, device kits, attendance capture | `CampaignService`, `CampaignPlanner`, `OperationsController` (`/scheduling/campaigns/*`); tables `campaign`, `campaign_slot` | OH `#/scheduling/campaigns`, `#/scheduling/campaigns/{id}` | IT-C4, UT-CAMP, E2E-31, SCR `inc3-oh-24-campaigns`, `inc3-oh-25-campaign-day` | Implemented | |
| E14-HLR-03 | Check-in (kiosk / badge / app), queue management, wait-time display, encounter hand-off | `ClinicQueueService`, `/scheduling/check-in`, `/scheduling/queue`, `/queue/call-next`, `/me/appointments/{id}/check-in` | OH `#/scheduling/reception`, `#/scheduling/queue`; employee `#/appointments` | IT-C5, UT-BOOK, E2E-28, E2E-29, SCR `inc3-oh-22-reception-kiosk`, `inc3-oh-23-queue-board` | Implemented | |
| E14-HLR-04 | Equipment register with calibration / maintenance, certificates, cold-chain logs; blocks out-of-calibration devices in results capture | `EquipmentService`, `EquipmentRules`, `EquipmentCalibrationCheck` (kernel `DeviceCalibrationCheck`, called by E03 `ResultService` → 422 `device_out_of_calibration` / `out_of_service`), `EquipmentController`; tables `equipment`, `calibration`, `temperature_reading`, `excursion` | OH `#/scheduling/equipment` | IT-C6, IT-C7, UT-EQ, E2E-17, E2E-30, SCR `inc3-oh-13-equipment` | Implemented | New role BIOMEDICAL |
| E14-HLR-05 | Capacity planning (demand from due lists, cases, campaigns vs capacity) and utilisation by site | `CapacityService`, `CapacityMath`, kernel `CapacityDemandSource` (E06, E07), `/scheduling/capacity`, `/capacity/what-if`, `/utilisation` | OH `#/scheduling/capacity` | IT-C8, UT-CAMP, E2E-32, SCR `inc3-oh-26-capacity` | Implemented | |

### 6.2 Features

| ID | Feature | Implementation | UI screen | Verification | Status | Notes |
|---|---|---|---|---|---|---|
| E14-F01 | Appointment scheduling – complex appointment (physician + audiometry room) booked in < 1 min; reminders reduce no-shows | As E14-HLR-01; conflict check across clinician, room, device and employee; reminders and no-show jobs | OH `#/scheduling/diary` | IT-C1, IT-C3, UT-SLOT, E2E-26, WEB-OH (`Scheduling.test` Diary), SCR `inc3-oh-21-diary` | Implemented | Telehealth slots are link-only (see E14-BL-004); no-show reduction is an operational outcome to measure in the pilot |
| E14-F02 | Campaign & mobile-clinic scheduling – 500-person audiometry day scheduled and executed with attendance capture | As E14-HLR-02; performance view | OH `#/scheduling/campaigns/{id}` | IT-C4 (500 in one call), E2E-31, WEB-OH (bulk booking from pasted ids, attendance grid), SCR `inc3-oh-25-campaign-day` | Implemented | Book returns `{booked, waitlisted, alreadyBooked, rejected}` |
| E14-F03 | Check-in & queue management – average wait ≤ 15 min; queue visible on display | As E14-HLR-03; queue board with tickets and average wait, never names | OH `#/scheduling/queue` | IT-C5, E2E-28, E2E-29, WEB-OH (queue board, call-next hand-off), SCR `inc3-oh-23-queue-board` | Implemented | Wait time measured and displayed; the ≤ 15 min target is an operational outcome |
| E14-F04 | Equipment & cold-chain register – out-of-calibration audiometer blocked; fridge excursion alerts nurse | As E14-HLR-04; manual and IoT readings, excursion alerts | OH `#/scheduling/equipment` | IT-C6, IT-C7, E2E-17, E2E-30, WEB-OH (`Scheduling.test` Equipment), SCR `inc3-oh-13-equipment` | Implemented | IoT readings arrive through `/integration/cold-chain/readings`; physical gateway not yet connected (see E14-BL-008) |
| E14-F05 | Capacity planning & utilisation – monthly report with forecast vs capacity per site | As E14-HLR-05; monthly rows per site, what-if | OH `#/scheduling/capacity` | IT-C8, E2E-32, WEB-OH (gap colouring, what-if), SCR `inc3-oh-26-capacity` | Implemented | |

### 6.3 Implementation backlog

| ID | Title (short) | Implementation | UI screen | Verification | Status | Notes |
|---|---|---|---|---|---|---|
| E14-BL-001 | Appointment types with durations and resources per site; site templates; diaries (P1) | `/scheduling/appointment-types`, `/sites/{siteCode}/resources`, `/sites/{siteCode}/templates`, `/scheduling/diary`; `masterdata.clinic_site` (V9) | OH `#/scheduling/catalogue`, `#/scheduling/diary` | IT-C9, UT-SLOT, WEB-OH (validated site templates), SCR `inc3-oh-27-catalogue` | Implemented | Slots follow the site time zone across DST |
| E14-BL-002 | Book, move, cancel with resource conflict checks; drag-and-drop diary; waitlist; audit (P1) | `/scheduling/appointments`, `/move` (optimistic locking), `/cancel`, `/scheduling/waitlist`; 409 `resource_conflict`; audit `APPOINTMENT_BOOK`, `DIARY_READ` | OH `#/scheduling/diary` (`Diary.tsx`, `AppointmentDialogs.tsx`) | IT-C1, UT-SLOT, E2E-26, WEB-OH (drag-and-drop move; Alt + arrow keyboard move; stale 409), SCR `inc3-oh-21-diary` | Implemented | Drag-and-drop and an equivalent keyboard / dialog move; waitlist makes offers (no auto-book); booking outside sessions 422 `no_session` unless a clinician is named (admin override) |
| E14-BL-003 | Reminders T−48 h / T−2 h; reschedule link; no-show tracking (P1) | `SchedulingJobs` (`appointment-reminders`, `appointment-no-show` after 15 min), `/me/appointments/{id}/reschedule`, `/cancel`; `AppointmentStatusListener` to E06 / E07 | Employee `#/appointments` (`MyAppointments.tsx`) | IT-C2, IT-C3, UT-BOOK (`reminderWindows`, `noShowDetection`), E2E-27, WEB-EMP, SCR `inc3-emp-05-appointments` | Implemented | |
| E14-BL-004 | Telehealth slots in the diary that launch the video consult; documentation link (P3) | Telehealth flag on types / appointments, `POST /scheduling/appointments/{id}/telehealth` returns a room link (`wellnessz.scheduling.telehealth-base-url`) | OH diary appointment dialog; employee `#/appointments` (join enabled 15 min before) | IT-C9, WEB-OH, WEB-EMP (`Occupational.test` telehealth) | Partial | Link only – no video platform integration until E04 |
| E14-BL-005 | Campaign day with slots, device kits, staffing; attendance; performance view (P1) | `CampaignService` (`device_kit`, `staff`, slot generation, bulk book ≤ 5,000, attendance, performance); audit `CAMPAIGN_*` | OH `#/scheduling/campaigns/{id}` (`Campaigns.tsx`) | IT-C4, UT-CAMP, E2E-31, WEB-OH, SCR `inc3-oh-24-campaigns`, `inc3-oh-25-campaign-day` | Implemented | |
| E14-BL-006 | Kiosk / badge check-in; queue board; call-next; wait time | `/scheduling/check-in` (badge or employee id, window 60 min before start to end), `/scheduling/queue`, `/queue/call-next` (404 `queue_empty`) → encounter link | OH `#/scheduling/reception` (`ClinicFlow.tsx`), `#/scheduling/queue` | IT-C5, UT-BOOK (`checkInWindowTicketsAndWaitEstimate`), E2E-28, E2E-29, WEB-OH, SCR `inc3-oh-22-reception-kiosk`, `inc3-oh-23-queue-board` | Implemented | |
| E14-BL-007 | Equipment register with calibration schedules; certificates; block rule; alerts (P1) | `/scheduling/equipment`, `/calibrations` (BIOMEDICAL), `/status`, `/calibration-due`; `SchedulingJobs` `equipment-calibration` (daily alerts); `EquipmentCalibrationCheck` | OH `#/scheduling/equipment` (`Equipment.tsx`) | IT-C6, UT-EQ, E2E-17 (AUD-LHR-02 blocked 422, accepted after calibration), WEB-OH, SCR `inc3-oh-13-equipment` | Implemented | Calibration certificates are stored in the `scheduling` schema (bytea + SHA-256), not in `DocumentService` (which is employee-scoped) |
| E14-BL-008 | Vaccine fridge temperatures logged (manual / IoT); thresholds; excursion alerts; report | `/scheduling/equipment/{id}/temperatures` (manual), `/integration/cold-chain/readings` (IoT), `/scheduling/excursions`, `/acknowledge`, `/close`, `/scheduling/cold-chain/report` | OH `#/scheduling/equipment` (cold-chain view) | IT-C7, UT-EQ (`excursionDetection`), E2E-30 (10.4 °C excursion acknowledged), WEB-OH | Partial | Manual logging, thresholds, alerts and report complete; IoT readings enter through the integration API only – no fridge gateway is connected yet |
| E14-BL-009 | Demand vs capacity forecasts per site; utilisation; what-if (P3) | `CapacityService` (demand from `CapacityDemandSource` of E06 and E07 plus a three-month run rate), `/capacity`, `/capacity/what-if`, `/utilisation` | OH `#/scheduling/capacity` (`Capacity.tsx`) | IT-C8, UT-CAMP, E2E-32, WEB-OH, SCR `inc3-oh-26-capacity` | Implemented | |
| E14-BL-010 | Service catalogue with internal charging codes; usage report; finance export (P3) | `/scheduling/service-catalogue`, `/charging/usage`, `/charging/export` (CSV) | OH `#/scheduling/catalogue` (`Catalogue.tsx`) | IT-C8, WEB-OH (charge codes, finance export link; scheduler cannot see charging), SCR `inc3-oh-27-catalogue` | Implemented | Export is a CSV file; a direct finance-system interface belongs to E08 |

---

## 7. Non-functional requirements and controls touched

| Area | What Increment 3 does | Evidence |
|---|---|---|
| Access control (security) | Every new endpoint carries `@PreAuthorize`; new roles CLINIC_SCHEDULER, BIOMEDICAL, CREW_LICENSING, CANDIDATE with Keycloak scope mappings for `wellnessz-bff` and `wellnessz-mobile`; reception can scan and validate certificates but not open them or verify; candidates see only pre-employment | IT-A10, IT-C9 (role denials), IT-S3, WEB-OH / WEB-MGR role tests, `BffSecurityTest` |
| Confidentiality (status-only views) | Managers, HR, crew licensing and crew scheduling see status, validity and dates only: no absence reason category, certificate content, diagnosis or exam detail; roster feed status-only | IT-A1, IT-A5, IT-S3, IT-S4, E2E-06, -11, -12, -21, -22, -25 |
| Clinical-text screening | All free text written by HR, managers and in advice reports goes through `ClinicalContentException.screen` (platform `ClinicalTextDetector`); violations return 422 `clinical_content_not_allowed` with the field in the problem body | IT-A6, IT-A7, E2E-08, E2E-10, WEB-MGR (inline 422) |
| Privacy by design | Employee consent before advice-report release (configurable); disclosure log on release and pre-employment sharing; small-cell suppression (min 5) in absence analytics and compliance; audit packs anonymised by default (identifiable only for CMO, Nursing Head); candidate data purged after 12 months if not hired; content-free notifications | IT-A7, IT-A9, IT-S9, UT-AGG, UT-SURV, UT-NOTIF |
| Retention | Classes `ABSENCE_RECORD` (6 y) and `PRE_EMPLOYMENT` (1 y) added in V9 – *proposed, pending DPO / CMO approval* | V9, `SurveillanceJobs.purgeJob` |
| Audit | Reads and changes of rules, analytics, advice reports, campaigns, bookings and diaries are audit-logged (e.g. `ABSENCE_RULE_*`, `ADVICE_REPORT_DRAFT/SIGN`, `ABSENCE_ANALYTICS_READ`, `ABSENCE_KPIS_READ`, `CAMPAIGN_*`, `APPOINTMENT_BOOK`, `DIARY_READ`); rule and programme versions are kept | IT-A3, IT-S1 |
| Integrity | Certificates hashed (SHA-256) for duplicate detection and alteration flags; calibration certificates SHA-256; optimistic locking on diary moves (409 stale); calibration gate on device results | UT-CERT, UT-EQ, IT-C1, IT-C6 |
| Upload handling | Multipart uploads checked by magic bytes and size (≤ 10 MB); BFF streams multipart untouched (defect found and fixed in E2E: `spring.servlet.multipart.enabled=false`) | IT-A5, E2E-05 |
| Modularity | E06 / E07 / E14 mutually independent; E03 / E05 do not depend on Increment-3 modules; cross-module calls only through kernel SPIs | ARCH |
| SAST | SpotBugs + FindSecBugs (`-Psecurity`): 0 findings | build log |

**SOC 2 trust-services criteria touched:** CC6.1 (logical access – role-gated endpoints, new roles), CC6.2 / CC6.3
(role provisioning and scope mappings in Keycloak), CC7.2 (monitoring – audit events, overdue / excursion / calibration
alerts), CC8.1 (change management – Flyway V9–V12, automated tests, ArchUnit rules), PI1.3 (processing integrity – feed
reconciliation, versioned rules and programmes, calibration gate), C1.1 (confidentiality – status-only views,
anonymised packs, suppression) and P-series privacy criteria (consent before release, disclosure log, retention and
purge).

## 8. Cross-cutting verification

| Area | Evidence |
|---|---|
| Java tests | 210 pass, 0 failures (wz-core-app 89 incl. Increment-3 integration tests 10 + 9 + 9 and `ArchitectureTest`; module unit tests E06 18, E07 13, E14 18) |
| Web tests | oh-workspace 463, employee-app 191, manager-workspace 154 – lint 0 warnings, production builds clean |
| E2E | Increment 3: 32 / 32 PASS (`tools/e2e/e2e-increment3.js`); regression: Increment 1 18 / 18, Increment 2 25 / 25 |
| Screenshots | 37 in `docs/images/screens/increment-03/` (`inc3-oh-01..27`, `inc3-emp-01..05`, `inc3-mgr-01..05`) |
| API contract | `docs/api/openapi.json` regenerated – 391 paths (was 240); `docs/increment-03/API-Contract-E06-E07-E14.md` |

## 9. Known limitations carried forward

| Limitation | Affected items | Target |
|---|---|---|
| Real HRIS / T&A / crew-management absence connectors (default `NoopAbsenceFeedConnector`) and HRIS offer events | E06-HLR-01, E06-F01, E06-BL-001, E07-BL-002 (headcount reconciliation), E07-BL-006 | E15 Integration |
| Telehealth video launch – link only | E14-BL-004 | E04 |
| IoT fridge gateway – readings via `/integration/cold-chain/readings` only | E14-BL-008 | E15 / site rollout |
| CAA exam template and report format pending CAA confirmation | E07-HLR-04, E07-F04, E07-BL-009 | CAA / AME sign-off |
| Programme content pending CMO validation (13 programmes seeded) | E07-BL-002 | CMO sign-off |
| "OH engaged" notification wording pending DPO approval | E06-BL-005 | DPO sign-off |
| Photo OCR needs the optional Tesseract provider | E06-F03, E06-BL-006 | Platform configuration |
| Pre-employment outcome document shared to HR only when the candidate is already in the employee master | E07-F03 (noted) | E15 (candidate → employee onboarding) |
| Small demo population: most analytics groups are suppressed (by design) | E06-HLR-06, E07-HLR-08 (noted) | Pilot data |
