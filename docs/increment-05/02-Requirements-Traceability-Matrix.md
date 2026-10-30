# WellnessZ OHCM – Increment 5 Requirements Traceability Matrix (E09 + E10 + E11 + E12)

**Version 1.0** · WellnessZ OHCM platform – increments 1–5

| | |
|---|---|
| **Document** | Increment 5 – 02 Requirements Traceability Matrix |
| **Sources** | E09, E10, E11 and E12 requirement workbooks, v1.0 – Draft for CMO & Nursing Head review (27 October 2026): sheets *High-Level Requirements*, *Feature List*, *Implementation Backlog* |
| **Audience** | CMO, Nursing Head, mental-health lead, MRO, DPO, Legal, HSE / Safety (SMS), Health Promotion, HR, Crew Scheduling, Cyber GRC, CIO, project sponsor |
| **Companion documents** | 03 – Release Notes Increment 5 · API contract `API-Contract-E09-E10-E11-E12.md` · OpenAPI `docs/api/openapi.json` (647 paths) · `deploy/environments/README.md` |
| **Verified on** | 30 October 2026 |

> **Method.** Every row was checked against the code: the endpoint, class, Flyway table or screen named in the
> *Implementation* and *UI screen* columns was located in the repository, and the *Verification* column names the
> automated integration test, unit test, end-to-end (E2E) check or screenshot that exercises it. Increment 5 introduces a
> third status besides *Implemented* and *Partially*: **Implemented – configuration needs sign-off** is used where the
> software is complete and tested, but the item's acceptance depends on clinical, legal or business reference data that is
> seeded as configurable content and must be approved before go-live (screening instruments and cut-offs, the urgent-risk
> protocol and signposting content, testing policies per role group, travel recommendations and protocol timers, the
> campaign portfolio, the wearable DPIA). **Partially** is used where part of the acceptance criteria depends on an
> external system that is not yet connected (toxicology laboratory LIMS, group campaign application), on a business
> activity that software cannot deliver (an awareness campaign, a tabletop exercise, a licensing decision) or where a
> described function is genuinely missing (absence data in campaign insights, vision screening thresholds, ROI metrics);
> the reason is stated. Volume and time targets in acceptance criteria (for example "contact list within 4 h", "verified
> positive removes the employee from duty within 15 min", "flu campaign for 50K staff") are reported in *Notes*: where the
> system provides the function and measures the value, the item is counted as implemented and the operational target is
> left for the pilot. All API paths are relative to `/api/v1`; the BFF keeps the `/workspace` prefix for
> `/workspace/{testing, psychosocial, campaigns, ergonomics}`.

## 1. Coverage summary

| Level | Items | Implemented | Implemented – configuration needs sign-off | Partially |
|---|---|---|---|---|
| E09 high-level requirements | 6 | 5 | 1 | 0 |
| E09 features | 6 | 5 | 1 | 0 |
| E09 backlog items | 15 | 11 | 3 | 1 |
| E10 high-level requirements | 5 | 3 | 1 | 1 |
| E10 features | 5 | 3 | 1 | 1 |
| E10 backlog items | 10 | 8 | 1 | 1 |
| E11 high-level requirements | 6 | 4 | 2 | 0 |
| E11 features | 6 | 4 | 2 | 0 |
| E11 backlog items | 13 | 9 | 2 | 2 |
| E12 high-level requirements | 5 | 2 | 0 | 3 |
| E12 features | 5 | 3 | 0 | 2 |
| E12 backlog items | 8 | 5 | 2 | 1 |
| **Total** | **90** | **62 (69 %)** | **16 (18 %)** | **12 (13 %)** |

Per epic: **E09** 27 items – 21 implemented, 5 sign-off, 1 partial · **E10** 20 items – 14 implemented, 3 sign-off,
3 partial · **E11** 25 items – 17 implemented, 6 sign-off, 2 partial · **E12** 18 items – 10 implemented, 2 sign-off,
6 partial. No item is deferred: every backlog item, including all P3 items, has a working implementation or (for the
four non-software tasks) the system support the task needs.

Items not fully "Implemented", by cause:

| Cause | Items |
|---|---|
| Clinical / legal / business reference data seeded and configurable, **sign-off pending** | E09-HLR-02, E09-F02, E09-BL-003, E09-BL-004, E09-BL-014 (PHQ-9 / GAD-7 bands and urgent rule, urgent-risk protocol script, signposting content – CMO / mental-health lead, config `mentalhealth.governance.sign-off = PENDING`); E10-HLR-01, E10-F01, E10-BL-010 (testing policies for 6 role groups seeded `PENDING` – Legal / Safety / HR / MRO); E11-HLR-01, E11-F01, E11-BL-001 (15 in-house destinations and the recommendations table – Clinical Governance); E11-HLR-03, E11-F03, E11-BL-006 (protocol timers incl. PEP decision ≤ 2 h – Clinical Governance); E12-BL-005 (wearable programme DPIA – DPO); E12-BL-008 (5-campaign portfolio seeded `PLANNED` with owners and KPIs – approval) |
| External system not yet connected | E10-HLR-03, E10-F03, E10-BL-006 (toxicology laboratory LIMS – the TOXLAB-bound interface, MRO queue and split-specimen flow are complete and tested with a simulated laboratory client); E12-HLR-04, E12-F04, E12-BL-004 (group health-campaign application – publication via the `campaign-app` outbox and the participation import endpoint are complete; no live app connected; wearable vendor aggregator also simulated) |
| Business / change activity, not software | E09-BL-015 (awareness and trust campaign, ≥ 90 % awareness survey); E11-BL-002 (choice and licence of a destination-risk content source); E11-BL-013 (outbreak playbook and tabletop exercise) |
| Function missing or narrower than described | E12-HLR-01 (absence data is not a source of campaign insights – E12 may not depend on E06); E12-HLR-02 (hearing and vision screening events support booking, but E12 result capture has no hearing / vision measures or thresholds – hearing results stay in the E03 / E07 audiometry pathway, vision has no automatic follow-up); E12-F05 (no ROI metric – budget is recorded, participation and outcome deltas are reported) |

Backlog by priority (workbook *Priority* column): of the **18 P1 (critical)** backlog items – 8 in E09 (BL-001, -002,
-003, -004, -006, -007, -008, -014), 6 in E10 (BL-001, -003, -005, -006, -007, -010), 4 in E11 (BL-003, -004, -006, -008),
none in E12 – all have a working implementation; **12 are fully implemented, 5 need configuration sign-off** (E09-BL-003,
-004, -014, E10-BL-010, E11-BL-006) **and 1 is partial** (E10-BL-006, laboratory LIMS).

## 2. Status legend

| Status | Meaning |
|---|---|
| **Implemented** | All acceptance criteria are met in code and covered by at least one automated test, E2E check or screenshot. Minor deviations or interpretations are stated in *Notes*. |
| **Implemented – configuration needs sign-off** | The function is complete and tested; the clinical, legal or business content it runs on is seeded as configurable reference data and must be approved by the named owner before go-live. No code change is expected. |
| **Partially** | The software function exists and is tested, but part of the acceptance criteria depends on an external system, a business activity, or a function that is not (yet) built; the reason is stated in *Notes*. |

## 3. Verification legend

### 3.1 Integration tests (Spring Boot + PostgreSQL 16, `apps/wz-core-app/src/test/java/com/wellnessz/app`)

| Ref | Test method |
|---|---|
| IT-MH1 | `Increment5MentalHealthIntegrationTest.restrictedCaseIsVisibleToNamedCliniciansOnlyWithPlaceholderAndBreakGlass` |
| IT-MH2 | `Increment5MentalHealthIntegrationTest.selfReferralScreensAndTheUrgentRiskProtocol` |
| IT-MH3 | `Increment5MentalHealthIntegrationTest.sharingChoiceDrivesTheManagerViewAndIsDisclosureLogged` |
| IT-MH4 | `Increment5MentalHealthIntegrationTest.eapReferralWithConsentAndPartnerFeedbackWithoutClinicalContent` |
| IT-MH5 | `Increment5MentalHealthIntegrationTest.peerSupportIsPseudonymousAndNeverExposesEmployeeIds` |
| IT-MH6 | `Increment5MentalHealthIntegrationTest.quarterlySmsReportIsAggregatedWithSmallCellsSuppressed` |
| IT-MH7 | `Increment5MentalHealthIntegrationTest.reliefFromDutiesAndSafetyCriticalFitnessFeedStatusOnly` |
| IT-MH8 | `Increment5MentalHealthIntegrationTest.cismEventFromTheIncidentFeedWithAffectedListOffersAndFollowUps` |
| IT-MH9 | `Increment5MentalHealthIntegrationTest.psychosocialAssessmentsActionsDashboardAndManagerView` |
| IT-MH10 | `Increment5MentalHealthIntegrationTest.supportProgrammeReferralSpiOpensOneRestrictedCasePerSource` |
| IT-MH11 | `Increment5MentalHealthIntegrationTest.demoDataIsReachableForTheEndToEndJourneys` |
| IT-DA1 | `Increment5SubstanceIntegrationTest.f01PoliciesPoolsSelectionVerifyAndNotify` |
| IT-DA2 | `Increment5SubstanceIntegrationTest.demoSelectionIsReproducibleAndDemoRequirementsAreInPlace` |
| IT-DA3 | `Increment5SubstanceIntegrationTest.f02ToF04PostIncidentChainOfCustodyLaboratoryMroAndPositivePathway` |
| IT-DA4 | `Increment5SubstanceIntegrationTest.f02ForCauseRequestAndF03RefusalPathway` |
| IT-DA5 | `Increment5SubstanceIntegrationTest.f03DesignatedPhysicianActsAsMro` |
| IT-DA6 | `Increment5SubstanceIntegrationTest.f05StatisticsAreSuppressedAndTheAnnualReportIsAnonymised` |
| IT-TH1 | `Increment5TravelHealthIntegrationTest.f01DestinationContentRolesAndRecommendations` |
| IT-TH2 | `Increment5TravelHealthIntegrationTest.f01ConsultationRecordsVaccinesInTheRegistryAndSharesTheCertificate` |
| IT-TH3 | `Increment5TravelHealthIntegrationTest.f01DemoTravellerSeesCertificateAndUpcomingRosterDestinations` |
| IT-TH4 | `Increment5TravelHealthIntegrationTest.f02CaseExclusionTracingNotificationCheckInsAndClearance` |
| IT-TH5 | `Increment5TravelHealthIntegrationTest.f02DemoNorovirusCaseTracesCateringLine2` |
| IT-TH6 | `Increment5TravelHealthIntegrationTest.f03NeedlestickTimersCompletionAndTimerJob` |
| IT-TH7 | `Increment5TravelHealthIntegrationTest.f03IncidentFeedStartsThePathwayOnce` |
| IT-TH8 | `Increment5TravelHealthIntegrationTest.f03DemoExposures` |
| IT-TH9 | `Increment5TravelHealthIntegrationTest.f04CampaignInviteFastEntryAdverseEventsAndSuppressedUptake` |
| IT-TH10 | `Increment5TravelHealthIntegrationTest.f04DemoCampaignIsActiveWithAdverseEvent` |
| IT-TH11 | `Increment5TravelHealthIntegrationTest.f05AdvisoryTargetsRosteredCrewAndBaseStaffWithAcknowledgement` |
| IT-TH12 | `Increment5TravelHealthIntegrationTest.f05DemoDxbAdvisory` |
| IT-TH13 | `Increment5TravelHealthIntegrationTest.f06ReportDeadlineSubmissionCorrespondenceAndJob` |
| IT-TH14 | `Increment5TravelHealthIntegrationTest.f06ExposureReportAndDemoReport` |
| IT-HP1 | `Increment5PromotionIntegrationTest.insightsAreAnonymisedAndSuppressedAndRoleRestricted` |
| IT-HP2 | `Increment5PromotionIntegrationTest.portfolioIsSeededAndCampaignsArePlannedAndPublished` |
| IT-HP3 | `Increment5PromotionIntegrationTest.screeningEventResultsOpenFollowUpWithinFortyEightHours` |
| IT-HP4 | `Increment5PromotionIntegrationTest.demoScreeningEventTomorrowAcceptsResultsAndPastAbnormalHasFollowUp` |
| IT-HP5 | `Increment5PromotionIntegrationTest.followUpSweepOpensMissingCases` |
| IT-HP6 | `Increment5PromotionIntegrationTest.hearingEducationAttendanceAndAggregatedReport` |
| IT-HP7 | `Increment5PromotionIntegrationTest.ergonomicAssessmentsScoringPhotosAndActions` |
| IT-HP8 | `Increment5PromotionIntegrationTest.managerWorkspaceShowsOwnUnitActionsAndSuppressedParticipation` |
| IT-HP9 | `Increment5PromotionIntegrationTest.ergonomicActionReminderJobNotifiesOwners` |
| IT-HP10 | `Increment5PromotionIntegrationTest.participationFromTheInternalCampaignAppOnly` |
| IT-HP11 | `Increment5PromotionIntegrationTest.mskCampaignReportShowsParticipationAndOutcomesWithSuppression` |
| IT-HP12 | `Increment5PromotionIntegrationTest.wearableDataIsConsentGatedMinimisedAndPurgedOnWithdrawal` |
| IT-HP13 | `Increment5PromotionIntegrationTest.employeeSeesInvitationsScreeningsAndEnrolments` |
| ARCH | `ArchitectureTest` (22 rules) – incl. `earlierIncrementsDoNotDependOnIncrement5`, `restrictedProgrammesAreIndependent`, `restrictedProgrammesDoNotDependOnEachOther`, `travelHealthAndPromotionAreIndependent`, `promotionDoesNotDependOnTravelHealth` |
| CMIG | `ClientMigrationsConfigTest` (5) – client-specific Flyway migrations in their own schema and history table |

### 3.2 Unit tests

| Ref | Test class (module) | Covers |
|---|---|---|
| UT-SCR | `ScreeningTest` (wz-e09-mentalhealth) | `phq9ScoreSumsAnswersWithoutUrgency`, `phq9Item9AboveZeroIsUrgentWhateverTheTotal`, `severeBandIsUrgentForBothInstruments`, `moderatelySevereWithoutItem9IsHighNotUrgent`, `gad7BandsHaveNoModeratelySevere`, `gad7HasNoItem9Rule`, `invalidAnswersAreRejected`, `urgentNextStepsNameTheEmergencyNumber`, `riskOnlyRises` |
| UT-PSC | `ContactCodesTest` (wz-e09-mentalhealth) | `codesHaveTheFormatAndUseTheUnambiguousAlphabet`, `codesAreRandomAndPracticallyUnique`, `codesCarryNothingOfTheRequester`, `employeeIdsAndFreeTextAreNotValidCodes` |
| UT-CISM | `CismRulesTest` (wz-e09-mentalhealth) | `triggersOnSeverityOrConfiguredClass`, `affectedListIsTheIncidentEmployeeAndCoWorkersOnTheSameDutyOrGroupWithinTheWindow`, `manualEventsWithoutAnIncidentEmployeeListTheDutyOnly`, `offerSlaIs72HoursFromTheEvent`, `followUpsAtOneAndFourWeeks` |
| UT-PSY | `PsychosocialScoringTest` (wz-e09-mentalhealth) | `scoreIsLikelihoodTimesSeverityWithRag`, `overallIsTheWorstFactor`, `outOfRangeInputsAreRejected`, `smallCellsAreSuppressedButZeroIsNot`, `ratesAreWithheldForSmallPopulationsOrSmallNumerators`, `workingDaysSkipWeekendsAndQuartersParse` |
| UT-SEL | `SelectionEngineTest`, `CoverageMathTest` (wz-e10-substance) | `sampleSizeIsCeilOfPoolTimesRateOverTwelve`, `sameSeedAndSnapshotReproduceTheDrawExactly`, `knownVectorMatchesTheDemoSelection`, `differentSeedsGiveDifferentDrawsAndADifferentPoolChangesTheHash`, `everyMemberHasTheSameChance`, `wholePoolDrawIsAPermutation`; `annualTargetIsDrugPlusAlcoholRateOfThePool`, `coverageIsCompletedOverTargetWithOneDecimal`, `behindPlanComparesWithTheProRataShare` |
| UT-WIN | `PostIncidentWindowsTest` (wz-e10-substance) | `ruleTableDecidesOnClassAndSeverity`, `windowsRunFromTheEvent`, `countdownAndInWindow` |
| UT-MRO | `MroStateMachineTest` (wz-e10-substance) | `negativeResultsVerifyNegative`, `prescriptionExplainsANonNegativeOnlyWhenVerified`, `positiveNeedsANonNegativeResultAndDiluteNeedsADiluteSpecimen`, `refusalNeedsAdulterationSubstitutionOrARecordedRefusal`, `verdictsOnlyInMroReview` |
| UT-RTD | `FollowUpSchedulerTest` (wz-e10-substance) | `datesAreDistinctSortedAndInsideThePlanWindow`, `datesAreRandomisedAndUnannounced`, `edgeCases` |
| UT-DSUP | `SuppressionTest` (wz-e10-substance) | `smallCellsAreSuppressedWithoutAnyCounts`, `cellsAtTheThresholdAreShownWithTheRate`, `outcomeIsNeverVisibleOutsideTheMro` |
| UT-TRC | `TracingRulesTest` (wz-e11-travelhealth) | `overlapIsClippedToBothDutiesAndTheWindow`, `riskLevels`, `cateringLineShiftGivesHighRiskContactsInTheSameWorkArea`, `includeFilterRestrictsContactTypes`, `sharedFacilityContactsAreFacilityTypeAndCapped`, `overlapsAcrossSeveralDutiesAreSummedAndTheIndexIsExcluded`, `monitoringScheduleCoversTheMonitoringDays`, `symptomRules` |
| UT-TMR | `ProtocolTimersTest` (wz-e11-travelhealth) | `dueTimesAndSerologySchedule`, `timerStatus`, `alertsOncePerLevel`, `protocolWindowKpi`, `incidentExposureTypesMapToPathways` |
| UT-UPT | `UptakeMathsTest` (wz-e11-travelhealth) | `percentages`, `groupsBelowTheMinimumAreSuppressedWithoutCounts`, `aVaccinatedPersonWhoOnceDeclinedIsNotCountedAsDeclined`, `missingKeysAreGroupedAsUnknown` |
| UT-ADV | `AdvisoryTargetingTest` (wz-e11-travelhealth) | `rosteredCrewAreTargetedOnceEachWithTheDuty`, `baseStaffWithTheRolesAreAddedButRosterWins`, `withoutRolesAllActiveBaseStaffAreTargeted`, `acknowledgementRate` |
| UT-TRV | `TravelRecommendationsTest` (wz-e11-travelhealth) | `nothingGivenMeansGiveEverything`, `statusAgainstHistory`, `longTermVaccinesStayUpToDate`, `sharedVaccinesAreMergedAcrossDestinationsAndLateConsultationsFlagged`, `unknownVaccinesFallBackToASingleLongTermDose` |
| UT-THR | `ScreeningThresholdsTest` (wz-e12-promotion) | `normalBiometricsOpenNoFollowUp`, `highBloodPressureIsAbnormalAtTheBoundary`, `lowWellbeingScoreUsesALowerBound`, `configuredThresholdOverridesDefaultAndMalformedFallsBack`, `parseRejectsGarbage` |
| UT-ERG | `ErgonomicScoringTest` (wz-e12-promotion) | `allZeroIsGreenAndAllThreeIsRed`, `weightedScoreMatchesTheDemoWorkstation`, `severeHeavilyWeightedItemLiftsGreenToAmber`, `bandBoundaries`, `incompleteUnknownOrOutOfRangeChecklistsAreRejected` |
| UT-INS | `SuppressionAndInsightsTest`, `ParticipationMathTest` (wz-e12-promotion) | `primaryAndComplementarySuppression`, `participationRateMaths`, `outcomeMaths`, `mskAndShiftClassification`, `insightsAreAggregatedPerUnitAndSuppressed`; `participationPerUnitWithSuppression`, `totalRowAndSmallParticipantCounts`, `outcomeRowsNeedAMinimumGroup`, `groupByIsNormalised`, `csvCellsAreQuotedAndNeutralised`, `ergonomicClosureKpi` |
| UT-WRB | `WearableGateTest` (wz-e12-promotion) | `acceptsDailyAggregatesWithEnrolmentAndConsent`, `consentGate`, `dataMinimisation` |
| UT-NOTIF | `NotificationRulesTest.everyApprovedTemplatePassesTheContentRules` (platform/wz-notification) | all templates incl. the new increment-5 templates render content-free (no "positive", "result", "drug", "alcohol" …) |

### 3.3 Web, E2E and screenshots

| Ref | Artefact |
|---|---|
| WEB-OH | `web/oh-workspace` vitest (769 tests): `screens/support/Support.test.tsx`, `screens/testing/Testing.test.tsx`, `screens/travel/Travel.test.tsx`, `screens/promotion/Promotion.test.tsx`, `domain/support.test.ts`, `domain/testing.test.ts`, `domain/travel.test.ts`, `domain/promotion.test.ts`, `auth/roles.test.ts` (restricted roles never reach other clinical screens; general OH roles never reach support cases or the MRO queue) |
| WEB-EMP | `web/employee-app` vitest (285 tests): `screens/Wellbeing.test.tsx` (support, peer support, travel, check-ins, campaigns, testing notices), `api/support.test.ts`, `api/prevention.test.ts`, `i18n/wellbeing.test.tsx` (no "diagnosis" wording in en / fr / ar) |
| WEB-MGR | `web/manager-workspace` vitest (188 tests): `screens/Increment5.test.tsx`, `api/increment5.test.ts` (status-only mappers for for-cause requests, duty status, shared outcomes, suppressed participation) |
| WEB-PP | `web/partner-portal` vitest (131 tests): `screens/EapReferrals.test.tsx` (incl. full jsdom flow), `api/eap.test.ts` |
| E2E-nn | `tools/e2e/e2e-increment5.js` check nn (list below) – real Keycloak logins through the BFF, core API and four web apps – **24 / 24 PASS** |
| SCR `name` | Screenshot `docs/images/screens/increment-05/name.png` captured by the E2E run (35 in total) |

E2E checks, in order: **01** employee self-refers confidentially with signposting; a PHQ-9 with any self-harm answer
escalates to a same-day call (score 11, band MODERATE, urgent) · **02** restricted class: the named mental-health
clinician sees the case and the urgent item; a general OH nurse gets 404 and only a placeholder count · **03** urgent
call-back documented; the employee shares fitness only and the manager sees exactly that – no screening or notes ·
**04** EAP partner sees minimal referral data for its own organisation and returns attendance only – free text refused
(422 `field_not_allowed`) · **05** crew peer support is pseudonymous: alias + code reach a trained peer, the contact log
holds no employee identity · **06** critical-incident support: affected crew from the WZ101 roster, offers within 72 h;
the safety manager sees counts only · **07** psychosocial risk register by department with actions and an anonymised case
rate (small groups suppressed) · **08** AME ends the temporary relief from flight duties – rostering receives the status
only · **09** auditable random selection reproducible from its stored seed; the programme manager cannot see results
(MRO queue 403) · **10** post-incident test collected inside the alcohol window with an electronic chain-of-custody form ·
**11** MRO verifies a positive: removed from safety-sensitive duty, HR sees status only, confidential support referral
opens · **12** manager raises a documented for-cause request with an observation checklist and a second observer ·
**13** laboratory results accepted only from the toxicology laboratory system – an unbound client gets 404 · **14**
programme statistics by reason and role group with small cells suppressed · **15** contact tracing from roster and shift
data within the 4-hour target (1.03 h); contacts notified without naming the case · **16** needle-stick pathway with
protocol timers – PEP decision due within hours · **17** travel consultation with destination-based recommendations and a
signed travel health certificate · **18** health advisory reaches crew rostered to the destination, with acknowledgement
tracking (small counts suppressed) · **19** notifiable-disease report submitted to the health protection team within the
statutory time · **20** influenza campaign uptake by subsidiary with small groups suppressed · **21** campaigns planned
from anonymised hotspots; participation and outcome report with suppression – no person-level data · **22** biometric
screening: an abnormal blood pressure opens a follow-up case within 48 h and a result letter · **23** ergonomic
manual-handling assessment scored with RAG and the job-demand profile (score 54, AMBER), with actions to closure ·
**24** wearable data accepted only for consented enrolments (daily totals); data without consent is rejected.

### 3.4 Platform and migration artefacts referenced below

| Artefact | Content |
|---|---|
| V16 `V16__increment5_platform.sql` | `masterdata.duty_assignment` (source `CREW_ROSTER` / `SHIFT` / `FACILITY`, duty ref, window, employee, duty role, location, destination, proximity group); partner organisations `EAP_WELLBEING` (EAP, portal), `TOXLAB` (laboratory system), `UKHSA_LOCAL` (public-health authority); retention classes `RESTRICTED_RECORD` and `PUBLIC_HEALTH` (10 y – *proposed, pending DPO approval*) |
| Duty feed | `DutyAssignmentController` (platform/wz-masterdata): `POST /integration/duty-assignments` (INTEGRATION, internal clients only – no partner organisation), `GET /masterdata/duty-assignments` (OH roles, SAFETY_MANAGER, CREW_SCHEDULING, ≤ 62 days); `DutyAssignmentRepository.findForEmployee / findOnDuties / findInProximityGroups / findToDestination`; demo rosters `R__demo_reference_duties.sql` (WZ101 LHR→SIN, WZ205 LHR→DXB, ramp shift RAMP-EARLY, catering CAT-LINE-2) |
| V17 `V17__e09_mentalhealth.sql` | schema `mentalhealth`: `support_case`, `support_note`, `support_contact`, `support_event`, `instrument`, `instrument_item`, `instrument_option`, `instrument_band`, `screen_result`, `urgent_item`, `protocol`, `signposting`, `eap_referral`, `peer_volunteer`, `peer_training`, `peer_contact_request`, `peer_contact`, `trusted_referral`, `sms_report`, `relief`, `cism_event`, `cism_affected`, `psychosocial_assessment`, `psychosocial_action`, `fitness_assessment` (+ `ref_counter`); seeds signposting, PHQ-9 / GAD-7 items, options and bands, urgent-risk protocol, config `cism.trigger-classes`, `mentalhealth.named-clinicians=psych1`, `mentalhealth.governance.sign-off=PENDING` |
| V18 `V18__e10_substance.sql` | schema `substance`: `panel`, `policy`, `pool_exclusion`, `selection`, `test`, `specimen`, `custody_event` (insert-only, trigger rejects changes), `duty_status`, `rtd_plan`, `post_incident_rule` (+ `ref_counter`); 6 role-group policies seeded **PENDING** |
| V19 `V19__e11_travelhealth.sql` | schema `travelhealth`: `destination`, `consultation`, `consultation_reminder`, `condition`, `cd_case`, `contact`, `check_in`, `pathway`, `chemical`, `exposure`, `exposure_task`, `campaign`, `campaign_invitation`, `campaign_decline`, `adverse_event`, `advisory`, `advisory_target`, `ph_report`, `ph_correspondence` (+ `ref_counter`); seeds 15 destinations (in-house), 9 conditions, 5 pathways with timers, 6 chemicals with SDS links |
| V20 `V20__e12_promotion.sql` | schema `promotion`: `campaign`, `campaign_invitation`, `participation`, `screening_event`, `screening_participant`, `screening_result`, `education_attendance`, `ergonomic_checklist`, `ergonomic_assessment`, `ergonomic_photo`, `ergonomic_action`, `wearable_enrolment`, `wearable_daily`; seeds 5 portfolio campaigns (`PLANNED`), 3 ergonomic checklists, `promotion.screening.threshold.*` |
| Kernel SPI | `SupportProgrammeReferral` (platform/wz-case-kernel; impl E09 `SupportProgrammeReferralAdapter`, called by E10 on a verified positive / refusal through an `ObjectProvider`). Reused: `AppointmentBooking` (E14), `EmployeeRecordAccess`, `CaseService.publishFitness` (status-only roster / HR feed) |
| Restricted class | Kernel case categories `PSYCHOLOGICAL` and `SUBSTANCE` → `DataClassification.RESTRICTED_CLINICAL`; ABAC `DefaultPolicies` grants restricted disclosure to `PSYCH_CLINICIAN` and break-glass; E09 `SupportAccess` (named clinicians, audited 404); E10 MRO endpoints (MRO or designated physician) |
| Documents | `DocumentService.generateSigned(…, DataClassification)`; templates `AUTHORITY_FITNESS_REPORT`, `CHAIN_OF_CUSTODY_FORM`, `TRAVEL_HEALTH_CERTIFICATE`, `PUBLIC_HEALTH_REPORT` (`RESULT_LETTER` reused); upload types `ERGONOMIC_PHOTO`, `AUTHORITY_CORRESPONDENCE` |
| Notifications | content-free templates `support.*`, `cism.*`, `testing.*`, `travel.consultation-due`, `tracing.*`, `exposure.timer-alert`, `advisory.published`, `public-health.report-due`, `campaign.invitation`, `screening.follow-up`, `ergonomic.action-due` |
| Outbound allow-lists | `campaign-app: [messageId, campaignId, title, risk, startDate, endDate, targetUnits]`, `sms-safety: [messageId, quarter, reportRef, category, count, base]` |
| Scheduled jobs (all `JobRunGuard`, UTC) | E09 `cism-follow-ups` 08:20, `peer-callback-purge` 01:40; E10 `substance-follow-ups` 06:15, `substance-windows` hourly :20; E11 `exposure-timers` every 5 min, `public-health-deadlines` hourly :07, `travelhealth-daily` 06:20; E12 `ergonomic-actions` 02:20, `screening-follow-up` hourly :15 |
| BFF | `/bff/api/workspace/{testing, psychosocial, campaigns, ergonomics}/**` keep their prefix; `WORKSPACE_ROLES` adds `SAFETY_MANAGER`, `CREW_SCHEDULING` |

---

## 4. E09 – Mental Health, Psychosocial Risk & Peer Support Programmes

Module `modules/wz-e09-mentalhealth` (package `com.wellnessz.e09`, schema `mentalhealth`, V17; depends on E05 through the
kernel). Controllers `SupportController` (`/support/**`), `MeSupportController` (`/me/support/**`, `/me/peer-support/**`,
`/me/cism/**`), `PeerSupportController` (`/peer-support/**`), `CismController` (`/cism/**`), `PsychosocialController`
(`/psychosocial/**`, `/workspace/psychosocial/**`), `EapPartnerController` (`/partner/eap-referrals/**`). OH Workspace
screens in `web/oh-workspace/src/screens/support`; employee `web/employee-app/src/screens/Support.tsx`,
`PeerSupport.tsx`; manager `web/manager-workspace/src/screens/Psychosocial.tsx`; partner
`web/partner-portal/src/screens/EapReferrals.tsx`.

### 4.1 High-level requirements

| ID | Requirement (short) | Implementation | UI screen | Verification | Status | Notes |
|---|---|---|---|---|---|---|
| E09-HLR-01 | Separate confidential psychological-health case type, restricted to named mental-health clinicians, separate audit, no manager / HR visibility beyond consented fitness outcomes | `SupportCaseService`, `SupportAccess`, `SupportViews`, `SupportController` (`/support/cases*`, `/support/placeholder`); restricted kernel case (`PSYCHOLOGICAL`, `RESTRICTED_CLINICAL`); `MeSupportController` `/me/support/sharing`; `/workspace/psychosocial/shared-outcomes`; tables `support_case`, `support_note`, `support_event` | OH `#/support/cases`, chart placeholder (`RestrictedCasePlaceholder.tsx`); employee `/support`; manager `/psychosocial` | IT-MH1, IT-MH3, WEB-OH, E2E-02, E2E-03, SCR `inc5-oh-01-support-cases`, `inc5-oh-03-chart-restricted-placeholder`, `inc5-mgr-01-psychosocial` | Implemented | Non-named access → audited `DENIED` + 404; break-glass read-only (`accessBasis: BREAK_GLASS`); named clinicians from `mentalhealth.named-clinicians` (default `psych1`) |
| E09-HLR-02 | Self-referral, signposting, validated screens under clinical governance, urgent-risk escalation, EAP referral with attendance / closure feedback only | `PathwayService`, `Screening`, `EapService`, `MeSupportController` (`/me/support/signposting`, `/self-referral`, `/screens`), `SupportController` (`/urgent`, `/protocols/urgent-risk`, `/cases/{id}/urgent-contact`, `/cases/{id}/eap-referrals`, `/governance`), `EapPartnerController`; tables `instrument*`, `screen_result`, `urgent_item`, `protocol`, `signposting`, `eap_referral` | Employee `/support`; OH `#/support/urgent`, `#/support/eap`; partner `#/eap-referrals` | IT-MH2, IT-MH4, UT-SCR, E2E-01, E2E-03, E2E-04, SCR `inc5-emp-01-support`, `inc5-oh-02-support-urgent`, `inc5-partner-01-eap-referrals` | Implemented – configuration needs sign-off | Instruments, bands, urgent rule, protocol script and signposting content seeded `PENDING_SIGN_OFF` – CMO / mental-health lead approval (E09-BL-014) |
| E09-HLR-03 | Flight-crew support programme: peer register and training, trusted professional, confidential contact log, temporary relief with status-only roster feed, anonymised SMS reporting | `PeerSupportService`, `ContactCodes`, `ReliefService`, `PeerSupportController`, `SupportController` (`/relief`, `/peer-referrals`); tables `peer_volunteer`, `peer_training`, `peer_contact_request`, `peer_contact`, `trusted_referral`, `relief`, `sms_report`; outbox `sms-safety` | OH `#/peer-support` (volunteer / coordinator views), `#/support/fitness`; employee `/peer-support` | IT-MH5, IT-MH6, IT-MH7, UT-PSC, UT-PSY, E2E-05, E2E-08, SCR `inc5-oh-04-peer-volunteer`, `inc5-oh-05-peer-coordinator`, `inc5-emp-02-peer-support`, `inc5-oh-08-support-fitness` | Implemented | Peer tables have no employee column (checked against `information_schema`); callback phones purged after 30 days |
| E09-HLR-04 | CISM: event from the incident feed, affected-person lists, offer / attendance tracking, follow-up at 1 and 4 weeks, referral | `CismIncidentListener` (`ehs.incident.received.v1`, idempotent), `CismService`, `CismRules`, `CismController`, `MentalHealthJobs.cism-follow-ups`; tables `cism_event`, `cism_affected`; duty feed V16 | OH `#/cism`, `#/cism/{id}`; employee `/support` (CISM offers) | IT-MH8, UT-CISM, E2E-06, SCR `inc5-oh-06-cism` | Implemented | Affected = incident employee + same duty / proximity group ± 2 h; SAFETY_MANAGER sees counts only |
| E09-HLR-05 | Psychosocial risk assessments per department with actions and owners, linked to aggregated case trends | `PsychosocialService`, `PsychosocialScoring`, `Suppression`, `PsychosocialController` (`/psychosocial/assessments*`, `/dashboard`, `/workspace/psychosocial`); tables `psychosocial_assessment`, `psychosocial_action` | OH `#/psychosocial`; manager `/psychosocial` | IT-MH9, UT-PSY, E2E-07, SCR `inc5-oh-07-psychosocial`, `inc5-mgr-01-psychosocial` | Implemented | Anonymised support-case rate per 100 staff, suppressed below the minimum group size |
| E09-HLR-06 | Fitness assessment for safety-critical roles with mental-health conditions or psychoactive medication, AME / physician review, structured decision, review schedule | `MhFitnessService`, `ReliefService`, `SupportController` (`/support/fitness-assessments`, `/support/relief*`); `AUTHORITY_FITNESS_REPORT` (restricted); table `fitness_assessment` | OH `#/support/fitness` | IT-MH7, E2E-08, SCR `inc5-oh-08-support-fitness` | Implemented | Decision FIT / FIT_WITH_LIMITATIONS / TEMPORARILY_UNFIT / REFER_TO_AUTHORITY; roster / HR receive the status only |

### 4.2 Features

| ID | Feature | Implementation | UI screen | Verification | Status | Notes |
|---|---|---|---|---|---|---|
| E09-F01 | Confidential psychological-health case type | As E09-HLR-01; consent-driven sharing `NOTHING / FITNESS_ONLY / FITNESS_AND_ADJUSTMENTS` (`/me/support/sharing`, adjustments screened); placeholder `{restrictedCases}` | OH chart placeholder ("1 restricted item – hidden"); employee `/support` | IT-MH1, IT-MH3, E2E-02, E2E-03 | Implemented | Access attempts logged; outcome sharing requires the employee's choice, revocable and audited |
| E09-F02 | Self-referral, screening & EAP referral | As E09-HLR-02; EAP referral `firstAppointmentDueBy` 5 working days; partner feedback limited to status, attendance and closure reason | Employee `/support`; OH `#/support/urgent`, `#/support/eap`; partner `#/eap-referrals` | IT-MH2, IT-MH4, UT-SCR, E2E-01, E2E-04 | Implemented – configuration needs sign-off | High risk → same-day kernel CALL_BACK task + `support.urgent-callback` (content-free); clinical content sign-off as E09-HLR-02 |
| E09-F03 | Flight-crew support programme (peer support) | As E09-HLR-03 | OH `#/peer-support`; employee `/peer-support` | IT-MH5, IT-MH6, IT-MH7, E2E-05, E2E-08 | Implemented | Operator views show aggregated counts only; SMS export of flat, allow-listed events |
| E09-F04 | Critical incident stress management | As E09-HLR-04 | OH `#/cism` | IT-MH8, UT-CISM, E2E-06 | Implemented | 72 h offer SLA (`offerDueBy`, `slaMet`) |
| E09-F05 | Psychosocial risk register | As E09-HLR-05 | OH `#/psychosocial`; manager `/psychosocial` | IT-MH9, UT-PSY, E2E-07 | Implemented | 8 ISO 45003 factors, likelihood × severity, RAG; manager-visible text screened |
| E09-F06 | Safety-critical mental-health fitness assessment | As E09-HLR-06 | OH `#/support/fitness` | IT-MH7, E2E-08 | Implemented | Authority report generated when `authorityReportRequired` |

### 4.3 Implementation backlog

| ID | Story / task (short) | Implementation | Verification | Status | Notes |
|---|---|---|---|---|---|
| E09-BL-001 | Restricted case type for named clinicians (P1) | `SupportAccess`, restricted kernel case, placeholder, separate audit, negative tests | IT-MH1, E2E-02, SCR `inc5-oh-03-chart-restricted-placeholder` | Implemented | General OH roles → 404 on every `/support/**` read |
| E09-BL-002 | Employee chooses what is shared with the manager (P1) | `/me/support/sharing` GET / PUT, `/workspace/psychosocial/shared-outcomes` (disclosure-logged) | IT-MH3, WEB-EMP, WEB-MGR, E2E-03 | Implemented | Nothing / fitness only / adjustments; revocation; audit log |
| E09-BL-003 | Confidential self-referral with signposting and screening (P1) | `/me/support/signposting`, `/self-referral`, `/screens/{instrument}`, `/screens`; `Screening` | IT-MH2, UT-SCR, WEB-EMP, E2E-01, SCR `inc5-emp-01-support` | Implemented – configuration needs sign-off | Signposting content and instruments pending clinical governance sign-off; emergency banner always shown first |
| E09-BL-004 | High-risk screen triggers a same-day call protocol (P1) | `PathwayService` urgent rule (PHQ-9 item 9 > 0 or SEVERE), `urgent_item`, kernel CALL_BACK task, `/support/protocols/urgent-risk`, `/support/cases/{id}/urgent-contact` (incl. `REACHED_ESCALATED_EMERGENCY`) | IT-MH2, UT-SCR, E2E-01, E2E-03, SCR `inc5-oh-02-support-urgent` | Implemented – configuration needs sign-off | Protocol script v1 pending CMO / mental-health lead sign-off |
| E09-BL-005 | Electronic EAP referral with attendance / closure feedback only | `EapService`, `/support/cases/{id}/eap-referrals`, `/partner/eap-referrals`, `/feedback` (extra fields → 422 `field_not_allowed`) | IT-MH4, WEB-PP, E2E-04, SCR `inc5-partner-01-eap-referrals` | Implemented | Partner portal is the channel (MFA, own organisation only, disclosure-logged); a direct link to the EAP provider's own system is not built |
| E09-BL-006 | Peer-volunteer register with training and availability (P1) | `PeerSupportService`, `/peer-support/volunteers*`, `/me/peer-support/peers`, routing to an available trained volunteer | IT-MH5, E2E-05, SCR `inc5-oh-05-peer-coordinator` | Implemented | Expired training → not offered to crew |
| E09-BL-007 | Contact log not identifiable to the operator (P1) | Separate tables without employee column; `ContactCodes` `PS-XXXX-XXXX`; aggregated counts only | IT-MH5, UT-PSC, E2E-05, SCR `inc5-oh-04-peer-volunteer` | Implemented | Audit record of a contact request carries no code |
| E09-BL-008 | Temporary relief from duties with status-only roster feed (P1) | `ReliefService`, `/support/relief`, `/support/relief/{id}/return`, `publishFitness` | IT-MH7, E2E-08 | Implemented | Without `expectedUntil` → UNFIT_PENDING; return review by AME |
| E09-BL-009 | Quarterly anonymised SMS programme data | `/peer-support/reports/quarterly`, `/send` → outbox `sms-safety`; table `sms_report` | IT-MH6, UT-PSY, SCR `inc5-oh-05-peer-coordinator` | Implemented | Min group size; the receiving safety-management system's import is an environment integration step |
| E09-BL-010 | CISM event from serious incidents with roster import | `CismIncidentListener`, duty feed (V16), offer tracking, 72 h SLA | IT-MH8, UT-CISM, E2E-06 | Implemented | Trigger classes configurable (`cism.trigger-classes`) |
| E09-BL-011 | Automatic follow-ups at 1 and 4 weeks | `MentalHealthJobs.cism-follow-ups` → `cism.follow-up`; `followUp1w` / `followUp4w`; referral option | IT-MH8, UT-CISM | Implemented | |
| E09-BL-012 | Psychosocial assessment per department with actions | `PsychosocialService`, dashboard | IT-MH9, E2E-07, SCR `inc5-oh-07-psychosocial` | Implemented | |
| E09-BL-013 | Structured mental-health fitness assessment for safety-critical roles | `MhFitnessService` (template, medication review, decision, review task, `AUTHORITY_FITNESS_REPORT`) | IT-MH7, SCR `inc5-oh-08-support-fitness` | Implemented | |
| E09-BL-014 | Clinical governance for instruments, protocol and signposting (P1, task) | `/support/governance` (instruments, licence status, protocol version, sign-off); config `mentalhealth.governance.sign-off` | IT-MH2, WEB-OH (governance panel on `#/support/urgent`) | Implemented – configuration needs sign-off | Governance document and the sign-off itself are owned by the CMO and mental-health lead; status `PENDING` until then |
| E09-BL-015 | Awareness and trust campaign for the confidential pathway (task) | System support only: seeded portfolio campaign `CMP-PORTFOLIO-WELL` (mental wellbeing – all staff), campaign publication, signposting pages | IT-HP2 | **Partially** | Change & training activity: campaign plan, materials, survey baseline / follow-up and the ≥ 90 % awareness measurement are not delivered by software |

---

## 5. E10 – Drug, Alcohol & Psychoactive Substance Testing Programme

Module `modules/wz-e10-substance` (package `com.wellnessz.e10`, schema `substance`, V18; depends on E05 through the
kernel). Controllers `TestingProgrammeController` (`/testing/{policies, pools, selections, coverage, statistics,
reports}`), `TestingOperationsController` (`/testing/{requirements, tests, specimens, mro, rtd-plans}`, `/me/testing`,
`/workspace/testing/**`), `LabInterfaceController` (`/integration/testing/**`). OH Workspace screens in
`web/oh-workspace/src/screens/testing`; employee `web/employee-app/src/screens/Testing.tsx`; manager
`web/manager-workspace/src/screens/Testing.tsx`.

### 5.1 High-level requirements

| ID | Requirement (short) | Implementation | UI screen | Verification | Status | Notes |
|---|---|---|---|---|---|---|
| E10-HLR-01 | Testing pools by role group with policy parameters and auditable random selection | `PolicyService`, `SelectionEngine` (`SecureRandom` seed stored, pool snapshot hashed, seed commitment), `SelectionService`, `CoverageMath`; tables `policy`, `panel`, `pool_exclusion`, `selection` | OH `#/testing/policies`, `#/testing/pools`, `#/testing/selections` | IT-DA1, IT-DA2, UT-SEL, E2E-09, SCR `inc5-oh-09-testing-selections`, `inc5-oh-10-testing-policies` | Implemented – configuration needs sign-off | Six role-group policies seeded `PENDING`; approval by Legal / Safety / HR (E10-BL-010). Selection records `policyApprovalStatus` |
| E10-HLR-02 | Post-incident and for-cause testing with policy windows, collector notification and documented observations | `IncidentTestListener`, `PostIncidentRules`, `TestWindows`, `TriggerService`, `/workspace/testing/for-cause`, `/testing/requirements`; table `post_incident_rule`; `SubstanceJobs.substance-windows` | OH `#/testing/requirements`; manager `/testing` | IT-DA3, IT-DA4, UT-WIN, E2E-10, E2E-12, SCR `inc5-mgr-03-for-cause` | Implemented | Alcohol + 8 h, drugs + 32 h from the event; `testing.collection-dispatch` to collectors |
| E10-HLR-03 | Collection with electronic chain of custody, lab integration, MRO review workflow and secure result storage | `CollectionService`, `CustodyLog`, `LabService`, `LabInterfaceController` (bound to `substance.laboratory-code` = TOXLAB), `MroService`, `MroStateMachine`; `CHAIN_OF_CUSTODY_FORM` (restricted); tables `test`, `specimen`, `custody_event` | OH `#/testing/collection`, `#/testing/mro` | IT-DA3, IT-DA4, IT-DA5, UT-MRO, E2E-10, E2E-11, E2E-13, SCR `inc5-oh-11-testing-collection`, `inc5-oh-12-testing-mro` | **Partially** | Interface, custody, MRO queue and split-specimen flow complete; **live laboratory LIMS not connected** (tested with a simulated TOXLAB client). MRO opens results and the CCF through the E10 endpoints; the generic `/documents` ABAC rule does not yet grant MRO access to restricted documents (follow-up) |
| E10-HLR-04 | Verified positive / refusal → removed from safety-sensitive duty, confidential case, support referral, RTD and follow-up plan | `PathwayService` (synchronous on the MRO decision), `RtdService`, `FollowUpScheduler`, `SupportProgrammeReferral`; tables `duty_status`, `rtd_plan` | OH `#/testing/rtd`; manager `/testing` (HR duty status) | IT-DA3, IT-DA4, UT-RTD, E2E-11, SCR `inc5-mgr-02-hr-duty-status` | Implemented | `testing.status` to HR / crew scheduling (status only, disclosure-logged); support referral audited `DA_SUPPORT_REFERRAL` |
| E10-HLR-05 | Programme statistics and regulatory reports with anonymisation and restricted access | `StatisticsService`, `Suppression`, `/testing/statistics`, `/testing/reports/annual?format=pdf|csv` | OH `#/testing/reports` | IT-DA6, UT-DSUP, E2E-14, SCR `inc5-oh-13-testing-reports` | Implemented | Small cells suppressed without counts |

### 5.2 Features

| ID | Feature | Implementation | UI screen | Verification | Status | Notes |
|---|---|---|---|---|---|---|
| E10-F01 | Policy & pool management | As E10-HLR-01; pools from HRIS job codes; exclusions (`422 not_in_pool`); selection notices (`testing.selection-notice`) with short lead time; coverage dashboard | OH `#/testing/policies`, `pools`, `selections`, `reports` | IT-DA1, IT-DA2, UT-SEL, E2E-09 | Implemented – configuration needs sign-off | Policy matrix approval pending (E10-BL-010); one selection per role group / period / test type (409) |
| E10-F02 | Post-incident & for-cause triggers | As E10-HLR-02; observation checklist, second observer, countdown timers, WINDOW_MISSED job | OH `#/testing/requirements`; manager `/testing` | IT-DA3, IT-DA4, UT-WIN, E2E-10, E2E-12 | Implemented | Collection confirmed in window (E2E-10) |
| E10-F03 | Collection, chain of custody & lab integration | As E10-HLR-03 | OH `#/testing/collection`, `#/testing/mro` | IT-DA3, IT-DA5, UT-MRO, E2E-10, E2E-11, E2E-13 | **Partially** | Live LIMS not connected (see E10-BL-006); no result is visible to HR at any time; status shown to employees / managers is masked (COLLECTED / COMPLETED / CLOSED) |
| E10-F04 | Positive / refusal pathway | As E10-HLR-04 | OH `#/testing/rtd`; manager `/testing` | IT-DA3, IT-DA4, UT-RTD, E2E-11 | Implemented | Removal is synchronous with the MRO decision (well inside the 15-minute target) |
| E10-F05 | Programme reporting | As E10-HLR-05 | OH `#/testing/reports` | IT-DA6, UT-DSUP, E2E-14 | Implemented | Access limited to DA_PROGRAMME_MANAGER, CMO, SAFETY_MANAGER (annual report CMO / programme manager) |

### 5.3 Implementation backlog

| ID | Story / task (short) | Implementation | Verification | Status | Notes |
|---|---|---|---|---|---|
| E10-BL-001 | Role-group pools from HRIS and auditable random selection (P1) | `SelectionEngine`, `SelectionService`, `/testing/pools`, `/testing/selections`, `/verify` (`reproducible`, `poolHashMatches`, `seedCommitmentMatches`), `/notify` | IT-DA1, IT-DA2, UT-SEL, E2E-09 | Implemented | Selections accepted for previous, current or future month only |
| E10-BL-002 | Coverage dashboards vs policy targets | `/testing/coverage` (`targetTests`, `coveragePct`, `behindPlan`) | IT-DA1, UT-SEL, SCR `inc5-oh-13-testing-reports` | Implemented | The "alert" is the behind-plan flag on the dashboard; no push notification |
| E10-BL-003 | Qualifying incidents create post-incident tests with windows (P1) | `IncidentTestListener` (idempotent on message and incident), `PostIncidentRules`, `TestWindows`, dispatch | IT-DA3, UT-WIN, E2E-10 | Implemented | |
| E10-BL-004 | For-cause request form with observation checklist | `/workspace/testing/for-cause` POST / GET (manager in scope, SAFETY_MANAGER) | IT-DA4, WEB-MGR, E2E-12, SCR `inc5-mgr-03-for-cause` | Implemented | Description encrypted; second observer required; own list shows status only |
| E10-BL-005 | Electronic chain-of-custody form with specimen IDs (P1) | `CollectionService`, `/testing/tests/{id}/collection`, `/testing/specimens/{specimenId}/custody`, signed restricted `CHAIN_OF_CUSTODY_FORM`, `/integration/testing/lab-orders` | IT-DA3, WEB-OH (scanner-friendly specimen field), E2E-10, SCR `inc5-oh-11-testing-collection` | Implemented | Custody events insert-only (no update / delete grant, trigger rejects changes); lab-order transmission is a pull by the bound laboratory client |
| E10-BL-006 | Lab results in an MRO review queue with verification, prescription check, split specimen (P1) | `LabService`, `/integration/testing/lab-results`, `/testing/mro/queue`, `/mro-decision`, `/split-request`, `MroStateMachine` | IT-DA3, IT-DA5, UT-MRO, E2E-11, E2E-13, SCR `inc5-oh-12-testing-mro` | **Partially** | Live toxicology LIMS not connected – onboarding needs a `TOXLAB` client with `org_scope=/PARTNERS/TOXLAB`; other organisations and unbound clients → 404 + audit |
| E10-BL-007 | Verified positive updates fitness and opens a confidential case with support referral (P1) | `PathwayService`: restricted SUBSTANCE case, `publishFitness`, `SupportProgrammeReferral.refer(… "SUBSTANCE_POSITIVE")`, draft RTD plan | IT-DA3, IT-MH10, E2E-11 | Implemented | Without E09 the referral failure is audited and the pathway continues |
| E10-BL-008 | RTD evaluations and follow-up testing tracked to completion | `RtdService`, `FollowUpScheduler`, `/testing/rtd-plans*`, `/evaluation`, `SubstanceJobs.substance-follow-ups` | IT-DA3, UT-RTD | Implemented | READY requires a VERIFIED_NEGATIVE RETURN_TO_DUTY test; follow-up dates visible to the MRO only |
| E10-BL-009 | Regulatory programme reports with anonymisation and restricted access | `/testing/reports/annual` (pdf / csv), `StatisticsService` | IT-DA6, UT-DSUP | Implemented | |
| E10-BL-010 | Confirm testing policy per jurisdiction and role group (P1, task) | Policies seeded `PENDING`; approval workflow on `PUT /testing/policies/{roleGroup}` (`approvedBy`, optimistic `version`, any parameter change → PENDING) | IT-DA1, SCR `inc5-oh-10-testing-policies` | Implemented – configuration needs sign-off | Policy matrix (panels, rates, windows, consent, cut-offs) to be approved by Legal, Safety, HR and the MRO |

---

## 6. E11 – Travel Health, Immunisation Campaigns & Communicable Disease / Exposure Management

Module `modules/wz-e11-travelhealth` (package `com.wellnessz.e11`, schema `travelhealth`, V19; builds on the E07
immunisation registry and the E05 food-handler pathway). Controllers `TravelController`, `CommunicableController`,
`ExposureController`, `CampaignController`, `AdvisoryController`, `PublicHealthController`. OH Workspace screens in
`web/oh-workspace/src/screens/travel`; employee `web/employee-app/src/screens/Travel.tsx`, `CheckIns.tsx`,
`Campaigns.tsx`.

### 6.1 High-level requirements

| ID | Requirement (short) | Implementation | UI screen | Verification | Status | Notes |
|---|---|---|---|---|---|---|
| E11-HLR-01 | Travel / posting consultations with destination risk content, vaccine recommendations, prophylaxis records and certificates | `TravelService`, `TravelRecommendations`, `TravelController` (`/travel/destinations*`, `/consultations`, `/recommendations`, `/me/travel`); vaccines recorded via E07; `TRAVEL_HEALTH_CERTIFICATE`; tables `destination`, `consultation`, `consultation_reminder` | OH `#/travel/destinations`, `#/travel/consultations`; employee `/travel` | IT-TH1, IT-TH2, IT-TH3, UT-TRV, E2E-17, SCR `inc5-oh-16-travel-consultations`, `inc5-emp-03-travel` | Implemented – configuration needs sign-off | 15 in-house destinations and the recommendations table need Clinical Governance sign-off; licensed source decision open (E11-BL-002) |
| E11-HLR-02 | Communicable-disease cases: notification, exclusion, roster-based contact tracing, notification and monitoring, return criteria, authority reporting | `CommunicableService`, `TracingRules`, `MonitoringSchedule`, `CommunicableController`; duty feed V16; tables `condition`, `cd_case`, `contact`, `check_in` | OH `#/communicable`, `#/communicable/{id}`; employee `/check-ins` | IT-TH4, IT-TH5, UT-TRC, E2E-15, SCR `inc5-oh-14-contact-tracing` | Implemented | Exclusion → fitness TEMPORARILY_UNFIT (status only); food handlers through E05; clearance needs all criteria (`422 return_criteria_not_met`) |
| E11-HLR-03 | Exposure pathways with protocol timers (needle-stick, chemical / DGR, cabin fume, heat, bites / stings) | `ExposurePathwayService`, `ProtocolTimers`, `ExposureIncidentListener`, `ExposureController`, `TravelHealthJobs.exposure-timers`; tables `pathway`, `chemical`, `exposure`, `exposure_task` | OH `#/exposures`, `#/exposures/{id}` | IT-TH6, IT-TH7, IT-TH8, UT-TMR, E2E-16, SCR `inc5-oh-15-exposure-timers` | Implemented – configuration needs sign-off | Timer offsets (risk assessment ≤ 1 h, PEP decision ≤ 2 h, biomonitoring ≤ 6 h, serology 6 w / 3 m / 6 m) are seeded reference data – Clinical Governance sign-off |
| E11-HLR-04 | Vaccination campaigns with eligibility, consent, booking, administration, adverse events and uptake by unit | `VaccinationCampaignService`, `UptakeMaths`, `CampaignController` (`/vaccination-campaigns*`, `/me/vaccination-campaigns*`); `AppointmentBooking` (VACCINATION); tables `campaign`, `campaign_invitation`, `campaign_decline`, `adverse_event` | OH `#/vaccination-campaigns`; employee `/campaigns` | IT-TH9, IT-TH10, UT-UPT, E2E-20, SCR `inc5-oh-19-vaccination-campaigns` | Implemented | Administrations recorded in E07 with the campaign id (badge / GS1 lot barcode fast entry) |
| E11-HLR-05 | Destination / layover health advisories to crew and station staff with acknowledgement | `HealthAdvisoryService`, `AdvisoryTargeting`, `AdvisoryController`; tables `advisory`, `advisory_target` | OH `#/advisories`; employee `/travel` | IT-TH11, IT-TH12, UT-ADV, E2E-18, SCR `inc5-oh-17-advisories` | Implemented | Crew rostered to the destination + base staff by job code; daily re-targeting of newly rostered crew |
| E11-HLR-06 | Public-health and authority reports within statutory timelines with correspondence | `PublicHealthReportService`, `PublicHealthController`, `TravelHealthJobs.public-health-deadlines`; `PUBLIC_HEALTH_REPORT`, `AUTHORITY_CORRESPONDENCE`; tables `ph_report`, `ph_correspondence` | OH `#/public-health` | IT-TH13, IT-TH14, E2E-19, SCR `inc5-oh-18-public-health` | Implemented | Submission recorded by channel (EMAIL / PORTAL / PHONE) with `submittedOnTime`; no electronic authority interface (not required by the acceptance criterion) |

### 6.2 Features

| ID | Feature | Implementation | UI screen | Verification | Status | Notes |
|---|---|---|---|---|---|---|
| E11-F01 | Travel & posting consultation | As E11-HLR-01; linkage to the E07 immunisation registry; certificate shared to the employee portal | OH `#/travel/*`; employee `/travel` | IT-TH1, IT-TH2, IT-TH3, UT-TRV, E2E-17 | Implemented – configuration needs sign-off | Destination content and recommendations sign-off |
| E11-F02 | Communicable-disease case & contact tracing | As E11-HLR-02; risk HIGH = same proximity group ≥ 15 min, MEDIUM = same duty / facility ≥ 15 min | OH `#/communicable/{id}` | IT-TH4, IT-TH5, UT-TRC, E2E-15 | Implemented | Contact list within the 4 h KPI (`traceWithinKpi`; E2E 1.03 h); `tracing.contact-notice` names neither condition nor case |
| E11-F03 | Exposure pathways with protocol timers | As E11-HLR-03; SDS link from the chemical register; fume event safety and authority report tasks | OH `#/exposures/{id}` (live countdown) | IT-TH6, IT-TH7, UT-TMR, E2E-16 | Implemented – configuration needs sign-off | Protocol timers sign-off |
| E11-F04 | Vaccination campaign manager | As E11-HLR-04 | OH `#/vaccination-campaigns` | IT-TH9, IT-TH10, UT-UPT, E2E-20 | Implemented | Uptake by subsidiary / department, suppressed; 50K-staff volume not load-tested |
| E11-F05 | Health advisories | As E11-HLR-05 | OH `#/advisories`; employee `/travel` | IT-TH11, IT-TH12, UT-ADV, E2E-18 | Implemented | Acknowledgement statistics suppressed below the minimum group; OH keeps the `pending[]` chase list |
| E11-F06 | Public-health reporting | As E11-HLR-06 | OH `#/public-health` | IT-TH13, IT-TH14, E2E-19 | Implemented | `dueAt` from the condition's `reportWithinHours`; exposure reports due 72 h after the exposure (configurable) |

### 6.3 Implementation backlog

| ID | Story / task (short) | Implementation | Verification | Status | Notes |
|---|---|---|---|---|---|
| E11-BL-001 | Travel consultation template with destination content and vaccine recommendations | `TravelService`, `TravelRecommendations`, certificate | IT-TH1, IT-TH2, UT-TRV, E2E-17 | Implemented – configuration needs sign-off | Recommendations table sign-off; vaccines limited to the E07 vocabulary |
| E11-BL-002 | Spike: select destination risk content source and licence it | Data model supports `source IN_HOUSE | LICENSED` and maintenance through `PUT /travel/destinations/{cc}` (OH_PHYSICIAN, CMO) | IT-TH1 | **Partially** | Decision, licence and update process are business actions not yet taken; 15 in-house destinations seeded meanwhile |
| E11-BL-003 | Communicable-disease pathway with exclusion and return criteria (P1) | `/communicable/cases`, `/confirm`, `/exclusion`, `/clear`; status feed EXCLUDED / CLEARED | IT-TH4, IT-TH5 | Implemented | `422 use_clear` when clearing through `/exclusion` |
| E11-BL-004 | Contact tracing from rosters, flight crew lists and shift data (P1) | `TracingRules`, `/communicable/cases/{id}/trace`, `/contacts`, `/contacts/notify`; duty feed `POST /integration/duty-assignments` | IT-TH4, IT-TH5, UT-TRC, E2E-15 | Implemented | The E2E feeds a shift through the duty endpoint before tracing; connecting the production rostering / shift systems to that endpoint is an environment integration step |
| E11-BL-005 | Contact monitoring tasks and symptom check-ins via the app | `/me/check-ins`, `/me/check-ins/{id}`, `MonitoringSchedule`, `tracing.check-in`, symptomatic → `exposure.timer-alert` + call-back task | IT-TH4, UT-TRC, WEB-EMP | Implemented | Missed check-ins marked MISSED by the daily job |
| E11-BL-006 | Needle-stick / body-fluid pathway with PEP countdown, source testing and serology (P1) | Pathway `NEEDLESTICK_BBP`, `ProtocolTimers`, `/exposures/{id}/tasks/{code}/complete`, serology `SEROLOGY_6W/3M/6M`, `exposure-timers` job | IT-TH6, UT-TMR, E2E-16, SCR `inc5-oh-15-exposure-timers` | Implemented – configuration needs sign-off | PEP timers sign-off; lab orders for serology are tasks (no LIMS order interface) |
| E11-BL-007 | Chemical / DGR exposure linked to SDS and monitoring | Pathway `CHEMICAL_DGR` (SDS review, decontamination, monitoring, `IH_NOTIFICATION` task), 6 chemicals with SDS links | IT-TH7, IT-TH8 | Implemented | Only http(s) SDS links are rendered |
| E11-BL-008 | Cabin fume-event pathway with reporting to safety and authority (P1) | Pathway `CABIN_FUME` (symptom protocol, biomonitoring ≤ 6 h, `SAFETY_REPORT`, `AUTHORITY_REPORT` tasks), `flightRef`; exposure report via `/public-health/reports` | IT-TH7, IT-TH8, IT-TH14 | Implemented | |
| E11-BL-009 | Heat-illness pathway with return criteria and HSE feedback | Pathway `HEAT_ILLNESS` (cooling, `RETURN_CRITERIA`, `HSE_FEEDBACK` anonymised task) | IT-TH7 | Implemented | HSE feedback is a tracked task, not an automatic message |
| E11-BL-010 | Vaccination campaign with eligibility, booking, fast entry, adverse events, uptake | `VaccinationCampaignService`, `/invite`, `/administrations`, `/declines`, `/adverse-events`, `/uptake` | IT-TH9, IT-TH10, UT-UPT, E2E-20, SCR `inc5-oh-19-vaccination-campaigns` | Implemented | Invitations never name the vaccine or disease |
| E11-BL-011 | Destination advisories to rostered crew with acknowledgement | `HealthAdvisoryService`, `/advisories`, `/acknowledgements`, `/me/advisories/{id}/acknowledge` | IT-TH11, IT-TH12, UT-ADV, E2E-18 | Implemented | |
| E11-BL-012 | Notifiable-disease and exposure reports within statutory time with correspondence | `PublicHealthReportService`, `/submit`, `/correspondence`, `/correspondence/upload` | IT-TH13, IT-TH14, E2E-19, SCR `inc5-oh-18-public-health` | Implemented | |
| E11-BL-013 | Outbreak playbook and tabletop exercise (task) | System support in place: demo NOROVIRUS case on catering line CAT-LINE-2 (tracing returns HIGH contacts), crew rosters for a respiratory scenario | IT-TH5, E2E-15 | **Partially** | Playbook and the tabletop exercise (GI outbreak in catering, respiratory outbreak in a crew base) are change & training activities not yet carried out; lessons and configuration updates follow the exercise |

---

## 7. E12 – Health Promotion, Wellbeing & Prevention Campaigns

Module `modules/wz-e12-promotion` (package `com.wellnessz.e12`, schema `promotion`, V20; builds on the E03 record).
Controllers `PromotionController` (`/promotion/insights`, `/promotion/campaigns/**`), `ScreeningController`
(`/promotion/screening-events/**`, `/promotion/education-attendance/**`), `ErgonomicsController` (`/ergonomics/**`),
`PromotionSelfServiceController` (`/me/campaigns`, `/me/wearables/**`, `/workspace/{campaigns, ergonomics}`,
`/integration/{promotion/participation, wearables/data}`). OH Workspace screens in `web/oh-workspace/src/screens/promotion`;
employee `web/employee-app/src/screens/Campaigns.tsx`; manager `web/manager-workspace/src/screens/UnitCampaigns.tsx`,
`Ergonomics.tsx`.

### 7.1 High-level requirements

| ID | Requirement (short) | Implementation | UI screen | Verification | Status | Notes |
|---|---|---|---|---|---|---|
| E12-HLR-01 | Campaign planning from anonymised OH, absence and incident intelligence with objectives, budget, timeline and owners | `PromotionAnalyticsService`, `InsightRules`, `PromotionCampaignService`, `/promotion/insights`, `/promotion/campaigns*`; table `campaign` | OH `#/promotion/insights`, `#/promotion/campaigns` | IT-HP1, IT-HP2, UT-INS, E2E-21, SCR `inc5-oh-20-promotion-insights`, `inc5-oh-21-promotion-campaigns` | **Partially** | Insights use kernel cases, intake incidents, E03 audiometry and E12 screenings – **absence (E06) data is not a source**, because E12 may not depend on E06 (needs a kernel SPI or the E17 analytics layer). Restricted-class cases are always excluded |
| E12-HLR-02 | Screening events (biometric, MSK, hearing, vision, mental wellbeing) with booking, result capture and automatic follow-up for abnormal results | `ScreeningService`, `ScreeningThresholds`, `ScreeningController`, `PromotionJobs.screening-follow-up`; `RESULT_LETTER`; tables `screening_event`, `screening_participant`, `screening_result` | OH `#/promotion/screening`; employee `/campaigns` | IT-HP3, IT-HP4, IT-HP5, UT-THR, E2E-22, SCR `inc5-oh-22-screening` | **Partially** | All five event types can be booked; result capture and thresholds cover biometric, MSK and wellbeing measures. **Hearing and vision events have no E12 measures or thresholds** – hearing results stay in the E03 / E07 audiometry pathway, vision has no automatic follow-up |
| E12-HLR-03 | Ergonomic assessments (workstation, manual handling) with findings, actions, owners and closure, linked to job-demand profiles | `ErgonomicsService`, `ErgonomicScoring`, `ErgonomicsController` (`/ergonomics/assessments*`, `/photos`, `/actions*`, `/checklists`, `/kpi`), `PromotionJobs.ergonomic-actions`; tables `ergonomic_checklist`, `ergonomic_assessment`, `ergonomic_photo`, `ergonomic_action` | OH `#/ergonomics`; manager `/ergonomics` | IT-HP7, IT-HP8, IT-HP9, UT-ERG, E2E-23, SCR `inc5-oh-23-ergonomics`, `inc5-mgr-04-ergonomics` | Implemented | Job-demand profile from masterdata attached; closure KPI ≤ 60 days vs 80 % target |
| E12-HLR-04 | Integration with the group health-campaign app (publish, participation, outcomes) and consented wearable / BYOD data | `PromotionCampaignService.publish` → outbox `campaign-app`; `/integration/promotion/participation`; `WearableService`, `WearableGate`, `WearableConsentListener`, `/me/wearables/enrolments*`, `/integration/wearables/data`; tables `participation`, `wearable_enrolment`, `wearable_daily` | OH `#/promotion/campaigns`; employee `/campaigns` | IT-HP2, IT-HP10, IT-HP12, UT-WRB, E2E-24, SCR `inc5-emp-04-campaigns` | **Partially** | System side complete; **no live campaign app or wearable vendor aggregator connected**; wearable DPIA pending (E12-BL-005). Workbook priority *Could* |
| E12-HLR-05 | Participation and outcomes per campaign and unit with anonymisation thresholds | `PromotionAnalyticsService`, `/promotion/campaigns/{id}/report?groupBy=…[&format=csv]`, `/workspace/campaigns` | OH `#/promotion/reports`; manager `/campaigns` | IT-HP8, IT-HP11, UT-INS, E2E-21, SCR `inc5-oh-24-promotion-reports`, `inc5-mgr-05-campaigns` | Implemented | Primary and complementary suppression; KPIs target vs actual |

### 7.2 Features

| ID | Feature | Implementation | UI screen | Verification | Status | Notes |
|---|---|---|---|---|---|---|
| E12-F01 | Campaign planner | As E12-HLR-01 (campaign object with objective, target units, risk basis `insightRef`, budget, timeline, owners, KPIs, status) | OH `#/promotion/campaigns` (insights link pre-fills the planner) | IT-HP1, IT-HP2, E2E-21 | Implemented | Acceptance ("created from analytics insight; status and KPIs tracked") met with the available sources; absence gap tracked under E12-HLR-01 |
| E12-F02 | Screening events & follow-up | As E12-HLR-02; booking via `AppointmentBooking`; abnormal → kernel case (SURVEILLANCE_FOLLOW_UP) with FOLLOW_UP task ≤ 48 h, `RESULT_LETTER`, `screening.follow-up`; hourly sweep | OH `#/promotion/screening` | IT-HP3, IT-HP4, IT-HP5, UT-THR, E2E-22 | Implemented | Acceptance (abnormal result opens follow-up within 48 h) met; manual result entry (no device interface); hearing / vision gap under E12-HLR-02 |
| E12-F03 | Ergonomic assessment module | As E12-HLR-03; photos (`ERGONOMIC_PHOTO`), RAG scoring | OH `#/ergonomics`; manager `/ergonomics` | IT-HP7, IT-HP8, IT-HP9, UT-ERG, E2E-23 | Implemented | Responsive screens usable on a tablet |
| E12-F04 | Campaign-app & wearable integration | As E12-HLR-04 | OH `#/promotion/campaigns`; employee `/campaigns` | IT-HP2, IT-HP10, IT-HP12, E2E-24 | **Partially** | Live campaign app / wearable aggregator not connected; wearable pilot needs the DPIA |
| E12-F05 | Participation & outcome analytics | As E12-HLR-05 | OH `#/promotion/reports` | IT-HP11, UT-INS, E2E-21 | **Partially** | Participation and outcome deltas by unit delivered (acceptance met); **ROI metrics are not computed** – the budget is recorded on the campaign, cost-per-outcome is left to E17 analytics |

### 7.3 Implementation backlog

| ID | Story / task (short) | Implementation | Verification | Status | Notes |
|---|---|---|---|---|---|
| E12-BL-001 | Plan campaigns from analytics insights | `/promotion/insights` rows carry `ref` usable as `insightRef`; `/promotion/campaigns` POST / PUT | IT-HP1, IT-HP2, E2E-21 | Implemented | Title, objective and owners screened for clinical content |
| E12-BL-002 | Screening events with booking and follow-up cases for abnormal results | `ScreeningService`, thresholds `promotion.screening.threshold.*` | IT-HP3, IT-HP4, IT-HP5, UT-THR, E2E-22 | Implemented | One result per participant (409); unregistered → `422 not_registered`; health promotion lead sees counts only |
| E12-BL-003 | Tablet-based workstation and manual-handling assessments with actions to closure | `ErgonomicsService`, `/photos`, `/actions`, `ergonomic-actions` job | IT-HP7, IT-HP9, UT-ERG, E2E-23 | Implemented | Managers may only change the status of their own unit's actions |
| E12-BL-004 | Campaigns published to the group campaign app with participation flowing back | Outbox `campaign-app` (`CampaignPublished`), `/integration/promotion/participation` (internal clients only; partner-bound clients 403) | IT-HP2, IT-HP10 | **Partially** | API side done and tested; the group's campaign application is not yet connected to the outbox relay or the import endpoint |
| E12-BL-005 | Share wearable data with explicit consent and withdraw at any time | `WearableService`, `WearableGate` (daily aggregates only), consent `WEARABLE_DATA`, `DELETE /me/wearables/enrolments/{id}` (withdraw + purge), consent-withdrawal listener | IT-HP12, UT-WRB, WEB-EMP, E2E-24 | Implemented – configuration needs sign-off | DPIA for the wearable programme to be approved by the DPO before the pilot; vendor aggregator not connected |
| E12-BL-006 | Participation and outcome reports per campaign and unit with thresholds | `/promotion/campaigns/{id}/report` (JSON / CSV) | IT-HP11, UT-INS, SCR `inc5-oh-24-promotion-reports` | Implemented | CSV cells quoted and formula-neutralised |
| E12-BL-007 | Hearing-conservation education attendance alongside audiometry | `EducationService`, `/promotion/education-attendance`, `/report` (aggregated by unit, suppressed) | IT-HP6 | Implemented | Audiometry itself remains in E03 / E07; insights use `AUDIOMETRY_ABNORMAL` / `AUDIOMETRY_STS` |
| E12-BL-008 | Define the initial campaign portfolio (task) | V20 seeds 5 `PLANNED` campaigns with owners and KPIs: MSK (ramp / cargo / catering), hearing (airside / engineering), fatigue & sleep, metabolic (corporate), mental wellbeing (all) | IT-HP2, SCR `inc5-oh-21-promotion-campaigns` | Implemented – configuration needs sign-off | Portfolio, owners and KPIs to be approved by the health promotion lead / CMO |

---

## 8. Cross-cutting requirements of increment 5

| Requirement (contract §0 / §8) | Implementation | Verification | Status |
|---|---|---|---|
| Restricted classes: E09 / E10 records `RESTRICTED_CLINICAL`; only named clinicians (E09) or MRO / designated physician (E10) read content; general OH users see a placeholder; every attempt audited; break-glass applies | Kernel categories `PSYCHOLOGICAL` / `SUBSTANCE`; `SupportAccess`; E10 MRO checks; ABAC `DefaultPolicies` | IT-MH1, IT-DA3, IT-DA5, E2E-02, E2E-09 | Implemented (ABAC follow-up for MRO via `/documents`, see E10-HLR-03) |
| Managers / HR / crew scheduling see duty status only; employee controls E09 sharing | `publishFitness`, `/workspace/testing/status`, `/workspace/psychosocial/shared-outcomes`; masked statuses | IT-MH3, IT-DA3, WEB-MGR, E2E-03, E2E-11 | Implemented |
| Peer-support logs contain no operator-identifiable data | Pseudonymous codes, no employee column | IT-MH5, UT-PSC, E2E-05 | Implemented |
| Small-cell suppression on every aggregate (`wellnessz.analytics.min-group-size`, default 5) | E09 / E10 / E12 `Suppression`, E11 `UptakeMaths`, advisory statistics | IT-MH6, IT-MH9, IT-DA6, IT-TH9, IT-TH11, IT-HP1, IT-HP8, IT-HP11, E2E-07, E2E-14, E2E-18, E2E-20, E2E-21 | Implemented |
| Every disclosure outside the owning team written to the disclosure log | E09 shared outcomes and partner reads, E10 status feed, E11 status feeds | IT-MH3, IT-MH4, IT-DA3, IT-TH4 | Implemented |
| External machine clients bound to their organisation (`org_scope`); internal clients carry none | `LabInterfaceController` (TOXLAB), `EapPartnerController`, `/integration/duty-assignments`, `/integration/promotion/participation`, `/integration/wearables/data` | IT-DA3, IT-HP10, IT-HP12, E2E-13 | Implemented |
| Content-free notifications | New templates checked by the content rules | UT-NOTIF | Implemented |
| Module independence | `ArchitectureTest` 22 rules | ARCH | Implemented |
| All jobs through `JobRunGuard`; person-level reads audited | Module `*Jobs` classes | IT-MH8, IT-TH6, IT-TH13, IT-HP5, IT-HP9 | Implemented |

## 9. Follow-up register for items not fully implemented

| Item(s) | Action | Owner |
|---|---|---|
| E09-HLR-02, E09-F02, E09-BL-003, -004, -014 | Approve instruments, bands, urgent-risk protocol and signposting content; set `mentalhealth.governance.sign-off` to the approved state | CMO, mental-health lead |
| E09-BL-015 | Plan and run the awareness and trust campaign (can be published through the E12 planner); baseline and follow-up survey | Change & Training |
| E10-HLR-01, E10-F01, E10-BL-010 | Approve the testing policy matrix per jurisdiction and role group (panels, rates, windows, consent, cut-offs) in `#/testing/policies` | Legal, Safety, HR, MRO |
| E10-HLR-03, E10-F03, E10-BL-006 | Onboard the toxicology laboratory LIMS as an `INTEGRATION` client with `org_scope=/PARTNERS/TOXLAB`; extend the restricted-class ABAC rule so the MRO can open restricted documents through `/documents` | Integration (E15), platform team |
| E11-HLR-01, E11-F01, E11-BL-001, E11-BL-002 | Approve destination content and the recommendations table; decide and licence the destination-risk source | Clinical Governance, Product Owner (OH) |
| E11-HLR-03, E11-F03, E11-BL-006 | Approve exposure protocol timers (PEP, biomonitoring, serology) | Clinical Governance |
| E11-BL-013 | Write the outbreak playbook and run the tabletop exercise | Change & Training, OH |
| E12-HLR-01 | Add absence intelligence to insights through a kernel SPI or the E17 analytics layer | Product Owner, E17 |
| E12-HLR-02 | Decide whether hearing / vision results are captured in E12 (measures and thresholds) or stay in E03 / E07 | Nursing Head |
| E12-HLR-04, E12-F04, E12-BL-004, E12-BL-005 | Connect the group campaign app to the `campaign-app` outbox and the participation endpoint; select a wearable aggregator; approve the DPIA | Integration (E15), DPO |
| E12-F05 | Define ROI metrics (cost per participant / per outcome) for E17 | Health promotion lead, Finance |
| E12-BL-008 | Approve the campaign portfolio, owners and KPIs | Health promotion lead, CMO |
