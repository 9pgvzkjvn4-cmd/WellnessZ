# WellnessZ OHCM – Increment 6 Requirements Traceability Matrix (E31 + E32 + E33 + E34)

**Version 1.0**

*WellnessZ OHCM platform – increment 6 (Medical Device Integration Engine)*

| | |
|---|---|
| **Document** | Increment 6 – 02 Requirements Traceability Matrix |
| **Sources** | Epic workbooks E31, E32, E33 and E34 of the *OHCM Medical Device Integration Engine Enhancement Pack* v1.0 (31 October 2026): sheets *High-Level Requirements*, *Feature List*, *Implementation Backlog*; solution document Sections 5–10 |
| **Audience** | Project sponsor, CMO, Nursing Head, device administrators, integration testers, CISO / Security Operations, Cyber GRC, DPO, QA |
| **Companion documents** | [01 – Solution Design](01-Solution-Design-E31-E34.md) · [03 – Release Notes](03-Release-Notes-Increment-6.md) · [API contract](API-Contract-E31-E34.md) · [device security pack](device-security/) |
| **Verified on** | 1 November 2026 |

> **Method.** Every row was checked against the code: the module, class, endpoint, table or screen in the
> *Implementation* and *UI* columns was located in the repository, and the *Verification* column names automated tests
> whose methods exist in the test sources (grepped). Four status values are used. **Implemented** – acceptance criteria met
> in code and covered by tests. **Implemented – needs sign-off** – the function is complete and tested, but acceptance
> depends on clinical, DPO, security, user or business approval of content or results (for example GLI-2012 coefficients,
> retention classes, a usability test or a design review). **Partially** – part of the acceptance criteria is not met; the
> reason is always stated. Most partial items depend on **hardware that was not available** (no device lab, no vendor
> demonstration units, no BLE radio, no serial ports, no DICOM modality – verification used simulators and synthetic
> golden datasets only), on functions not built in this increment, or on infrastructure / governance work outside the
> software. **Not in scope (E35)** – reserved for items that belong to the deployment epic E35; no E31–E34 item needed it.
> Volume and time targets in acceptance criteria (50 msg/s, 5 s p95, 48 h offline) are reported as *not verified* unless a
> test measured them. All API paths are relative to `/api/v1`.

## 1. Coverage summary

| Level | Items | Implemented | Implemented – needs sign-off | Partially | Not in scope (E35) |
|---|---|---|---|---|---|
| E31 high-level requirements | 14 | 7 | 1 | 6 | 0 |
| E31 features | 12 | 6 | 2 | 4 | 0 |
| E31 backlog items | 24 | 4 | 3 | 17 | 0 |
| E32 high-level requirements | 11 | 10 | 0 | 1 | 0 |
| E32 features | 11 | 11 | 0 | 0 | 0 |
| E32 backlog items | 17 | 13 | 3 | 1 | 0 |
| E33 high-level requirements | 11 | 6 | 1 | 4 | 0 |
| E33 features | 12 | 7 | 2 | 3 | 0 |
| E33 backlog items | 16 | 10 | 3 | 3 | 0 |
| E34 high-level requirements | 12 | 6 | 2 | 4 | 0 |
| E34 features | 11 | 6 | 2 | 3 | 0 |
| E34 backlog items | 14 | 4 | 2 | 8 | 0 |
| **Total** | **165** | **90 (55 %)** | **21 (13 %)** | **54 (33 %)** | **0** |

Per epic: **E31** 50 items – 17 implemented, 6 sign-off, 27 partial · **E32** 39 – 34, 3, 2 · **E33** 39 – 23, 6, 10 ·
**E34** 37 – 16, 6, 15. E31's backlog is mostly partial because its acceptance criteria name real devices and vendor
products ("validated with a spirometer, an audiometer and a breath-alcohol tester", "Benson, Tremetrics, Spirotrac …",
"≥ 40 device models certified") and load tests that could not be run in this increment.

Items not fully implemented, by cause:

| Cause | Items |
|---|---|
| **Hardware / vendor validation not possible** (no device lab or vendor units; synthetic datasets only) | E31-BL-003 (native serial / USB-CDC / HID), E31-BL-005 (real vendor exports), E31-BL-006 (BLE radio, named devices), E31-BL-007 (two real data managers), E31-BL-009 (DICOM C-STORE / MWL / Q-R), E31-BL-012 (≥ 40 certified models), E34-HLR-04, E34-BL-003 (USB VID/PID and device-class policy) |
| **Function not built in this increment** | E31-HLR-02, E31-F02 (Classic SPP, HID wedge, outbound ORM / OML / ADT, OAuth2 cloud polling, barcode); E31-BL-004 (outbound HL7, IHE test set); E31-BL-008 (OAuth2 polling); E31-BL-010 (IG validation, OCR accuracy, barcode); E31-BL-011 (SDK command line); E31-HLR-08, E31-F08, E31-BL-016, E31-BL-019 (worklist push other than GDT, start / stop from the form, historic retrieval); E31-HLR-09, E31-F09, E31-BL-020, E33-HLR-07, E33-F10, E33-BL-012 (native tablet app with on-device queue); E31-HLR-12, E31-F12, E31-BL-023 (tenant-scoped catalogue, keys, onboarding template); E33-HLR-06, E33-F09, E33-BL-011 (respirator clearance register); E33-HLR-05, E33-F08 (drug-screen readers rejected by decision); E34-BL-014 (TPM attestation verification) |
| **Performance not measured** | E31-HLR-10, E31-HLR-13, E31-BL-002, E31-BL-021, E31-BL-024 |
| **Infrastructure / governance outside the software** | E34-HLR-03, E34-F03 (CIS golden images, disk encryption, secure boot, EDR); E34-HLR-06, E34-F05, E34-BL-005 (DAST in CI, threat-model documents); E34-BL-002 (network design validated in two sites); E34-BL-010, E34-BL-011 (staging run, CI stage and quarterly schedule of the test pack); E34-HLR-12, E34-F11, E34-BL-012, E34-BL-013 (penetration test, IEC 80001-1 agreement) |
| **User studies and training** | E32-HLR-11 (WCAG audit, console localisation), E32-BL-016 (training videos), E33-HLR-11, E33-BL-016 (time-and-motion study) |
| **Sign-off of content or results** (status *Implemented – needs sign-off*) | E31-HLR-06, E31-F06, E31-BL-017 (gate rule set); E31-F04, E31-BL-013 (GLI-2012 approximations, OSHA female table); E31-BL-001 (ADR approval); E32-BL-003, E33-BL-001 (usability tests); E32-BL-004 (option-pack review by integration and security); E32-BL-017 (E14 migration report acceptance); E33-HLR-03, E33-F03, E33-F04, E33-BL-004, E33-BL-005 (STS rule sets, GLI predicted values); E34-HLR-02, E34-F02 (reference network design); E34-HLR-10, E34-F09, E34-BL-009 (DPIA, retention classes); E34-BL-006 (contract clause template) |

## 2. Status legend

| Status | Meaning |
|---|---|
| **Implemented** | All acceptance criteria met in code and covered by at least one automated test. Interpretations are stated in *Notes*. |
| **Implemented – needs sign-off** | Complete and tested; acceptance needs approval of clinical / privacy / security content, a review or a user test by the named owner. No code change expected unless the review asks for one. |
| **Partially** | Part of the acceptance criteria is not met; the reason (hardware, function not built, infrastructure / governance, user study, performance) is in *Notes*. |
| **Not in scope (E35)** | Belongs to E35 (deployment options, Kafka / NATS, Kubernetes topologies, cost model). Not used for any item of E31–E34. |

## 3. Verification legend

### 3.1 Integration tests (Spring Boot + PostgreSQL 16, `apps/wz-core-app/src/test/java/com/wellnessz/app`)

| Ref | Test method |
|---|---|
| IT-EN1 | `Increment6EngineIntegrationTest.hl7Pcd01AckIdempotencyVerificationAndRoutingIntoE03` |
| IT-EN2 | `Increment6EngineIntegrationTest.unknownDeviceTenantIsolationAndAuthorisationRules` |
| IT-EN3 | `Increment6EngineIntegrationTest.fhirWearableConsentGateAndPoct1aAcknowledgement` |
| IT-EN4 | `Increment6EngineIntegrationTest.fileExportsGdtWithPredictedValuesCalibrationLockoutCsvAndXmlWithPdfPairing` |
| IT-EN5 | `Increment6EngineIntegrationTest.bleWithSessionBatchStoreAndForwardAndSerialBreathAlcoholIntoE10` |
| IT-EN6 | `Increment6EngineIntegrationTest.vendorWebhookHmacDicomwebAttachmentAndOcrFallback` |
| IT-EN7 | `Increment6EngineIntegrationTest.unmatchedQueueResolutionExposureRoutingDeadLetterReplayWithoutDuplicates` |
| IT-CO1 | `Increment6ConsoleIntegrationTest.catalogueIsSeededWith133ModelsElevenFamiliesAndTheContractOptionTotals` |
| IT-CO2 | `Increment6ConsoleIntegrationTest.fullWizardWithFourEyesRefusalCertificationPublishAndLookup` |
| IT-CO3 | `Increment6ConsoleIntegrationTest.pausedDraftResumesSecretsAreReferencesAndTheEnrolmentTokenIsShownOnce` |
| IT-CO4 | `Increment6ConsoleIntegrationTest.exportImportWithDiffNeedsASecondPersonAndCreatesADraftAtStepSix` |
| IT-CO5 | `Increment6ConsoleIntegrationTest.decommissioningNeedsFourEyesRevokesTheEndpointAndRemovesTheInstance` |
| IT-CO6 | `Increment6ConsoleIntegrationTest.expiredSyringeCheckLocksTheSpirometerUntilAPassingCheck` |
| IT-CO7 | `Increment6ConsoleIntegrationTest.endpointIsolationQuarantinesInstancesAndReleaseDoesNotRestoreThem` |
| IT-CO8 | `Increment6ConsoleIntegrationTest.driverRegistryVerifiesSignaturesAndCertificationNeedsASecondPerson` |
| IT-CO9 | `Increment6ConsoleIntegrationTest.blockedPostureNeedsASecurityApprovedExceptionAndModelRequestsAreTracked` |
| IT-CA1 | `Increment6CaptureIntegrationTest.formRegistryAndStationAwareDeviceList` |
| IT-CA2 | `Increment6CaptureIntegrationTest.captureStreamPreviewAcceptFilesE03ResultWithProvenance` |
| IT-CA3 | `Increment6CaptureIntegrationTest.warningsBlocksRetestAndReject` |
| IT-CA4 | `Increment6CaptureIntegrationTest.fallbackChainAlternateDeviceOcrAndManualRecordThePath` |
| IT-CA5 | `Increment6CaptureIntegrationTest.restrictedBreathAlcoholVisibility` |
| IT-CA6 | `Increment6CaptureIntegrationTest.ecgInterpretationNeedsPhysicianSignOff` |
| IT-CA7 | `Increment6CaptureIntegrationTest.batchModeReconcilesOfflineReadingsInQueueOrder` |
| IT-CA8 | `Increment6CaptureIntegrationTest.screeningDayOfTwoHundredEmployeesReconcilesWithoutLoss` |
| IT-CA9 | `Increment6CaptureIntegrationTest.kioskSelfMeasurementWithBadgeAndConsent` |
| IT-CA10 | `Increment6CaptureIntegrationTest.employeeWearableConsentGrantAndRevoke` |
| IT-CA11 | `Increment6CaptureIntegrationTest.sessionsExpireAndKpisCountPaths` |
| IT-CA12 | `Increment6CaptureIntegrationTest.disabledModuleIsRefusedAndListenerSkips` |
| IT-SE1 | `Increment6SecurityIntegrationTest.endpointIdentityEnrolmentAndFailClosedAuthentication` |
| IT-SE2 | `Increment6SecurityIntegrationTest.expiredEnrolmentTokenAndReissue` |
| IT-SE3 | `Increment6SecurityIntegrationTest.zoningPoliciesAndConduitExport` |
| IT-SE4 | `Increment6SecurityIntegrationTest.failedAuthBurstIsolatesWithinSlaAndReleaseNeedsRecertification` |
| IT-SE5 | `Increment6SecurityIntegrationTest.driverPackageSignaturesAndKeyRotation` |
| IT-SE6 | `Increment6SecurityIntegrationTest.connectorSecretsAreNeverReturned` |
| IT-SE7 | `Increment6SecurityIntegrationTest.eventChainSiemAndItsmAllowLists` |
| IT-SE8 | `Increment6SecurityIntegrationTest.anomalyRulesAlertsAndEngineEvents` |
| IT-SE9 | `Increment6SecurityIntegrationTest.provisioningSpiForTheConsole` |
| IT-SE10 | `Increment6SecurityIntegrationTest.governanceRecordsAndGoLiveReadiness` |
| IT-SE11 | `Increment6SecurityIntegrationTest.disabledModuleFailsClosed` |
| IT-X1 | `Increment6EndToEndIntegrationTest.publishedConfigurationAuthenticatedIngestCaptureAndE03ResultThenIsolationQuarantines` |
| IT-X2 | `Increment6EndToEndIntegrationTest.e34IsolationMakesE32QuarantineTheDeviceAndE31AndE33RefuseIt` |
| IT-X3 | `Increment6EndToEndIntegrationTest.signingKeyRevokedInE34RevokesItsPackagesInTheE32Registry` |
| PLAT | `PlatformIntegrationTest.increment6DevicePlatformBase` |
| ARCH | `ArchitectureTest` (28 rules) – incl. `earlierModulesDoNotDependOnDeviceModules`, `deviceModulesDoNotDependOnBusinessModules`, `engineIsIndependentOfOtherDeviceModules`, `consoleIsIndependentOfOtherDeviceModules`, `captureIsIndependentOfOtherDeviceModules`, `securityIsIndependentOfOtherDeviceModules` |

### 3.2 Unit tests

| Ref | Test class (module) | Methods |
|---|---|---|
| UT-PAR | `ParsersTest` (E31) | `hl7Pcd01SelectorsUnitsIdentityAndDeviceTime`, `hl7SegmentModelAndEscapes`, `hl7AcknowledgementsAaAeAr`, `gdtBlocksUnitsDemographicsAndCharset`, `csvRowsQuotesAndColumnMismatch`, `xpathWithConcatAndStringJoinAndPoct1aRecords`, `xmlParserRefusesExternalEntities`, `gattProfilesBlpHtpPlxWssBcs`, `serialRegexFramesAndUnknownFrames`, `fhirBundleFhirPathAndProfileRules`, `jsonPointerDicomJsonAndForm` |
| UT-11073 | `Ieee11073Test` (E31) | `sfloatDecodesMantissaAndSignedExponent`, `sfloatSpecialValuesAreAbsent`, `floatDecodesAndSpecialValues`, `encodersRoundTrip` |
| UT-NOR | `NormaliserTest` (E31) | `everyComponentCarriesLoincUcumAndMdcWhereKnown`, `driverConversionKpaAndTableConversionsFahrenheitAndImperial`, `bmiIsComputedFromWeightAndHeight`, `spirometryGetsGliPredictedValuesLlnAndPercentPredicted`, `audiometryCarriesTheOshaAgeCorrectionRow`, `clockSkewCorrection`, `unmappedQuantityAndUnconvertibleUnitAreDeterministicFailures`, `ageFromDateOfBirth` |
| UT-CALC | `CalculationsTest` (E31) | `gliPredictsPlausibleAdultValuesWithLlnBelowPredicted`, `gliMatchesTheLmsFormulaOfTheTable`, `oshaAppendixFTablesAndAgeCorrectedSts`, `ucumConversionsAndReferenceRanges` |
| UT-GATE | `GatesTest` (E31) | `calibrationBlocksExpiredAndOutOfServiceAndWarnsUnknown`, `qcDailyLockoutAndQcLot`, `atsErsGradingFromDeviceAndFromTrials`, `audiometryRetestAndAmbientNoise`, `bloodPressureCuffAndValidation`, `fitTestExercises`, `plausibilityRangesCrossChecksAndOutliers`, `clockSkewConsentAttestationOcrAndVerifyBeforePost` |
| UT-ASSOC | `AssociationEngineTest` (E31) | `firstMethodAtThresholdWins`, `nothingBelowTheThresholdIsEverAutoFiled`, `ambiguousCandidatesAndSessionConflictsGoToTheQueue`, `restrictedCategoriesAcceptOnlyWorklistOrSession`, `areaMeasurementsHaveNoPerson`, `vendorIdNormalisationAndJaroWinkler` |
| UT-DRV | `DriverPackageTest` (E31) | `canonicalJsonSortsKeysDropsWhitespaceAndTheSignature`, `ed25519SignatureOverTheCanonicalManifestVerifiesAndDetectsTampering`, `schemaViolationsAreCollected`, `semanticVersionsAndEngineRange`, `validatorFindsSecretsUnmappedFieldsUnknownGatesAndMissingVectors`, `fallbackDriversCoverTheFormKeys` |
| UT-CONF | `BundledPackagesConformanceTest`, `RegistryPackagesCompatibilityTest` (E31) | `everySamplePackageValidatesWithFullCoverage`, `everyTestVectorPassesInTheSimulator`, `everyPackageHasTheFiveGoldenDatasetKinds`, `goldenDatasetsParseAsDeclared`, `registryManifestsRunAgainstTheGoldenDatasets` |
| UT-SUP | `SupportTest` (E31) | `observationIdsAreDeterministicAndOrderIndependent`, `masksValuesButKeepsStructure`, `timeFormats`, `boundFieldsFromComponentsParametersConstantsAndDerivedValues`, `miniFhirPath`, `webhookHmacOverTimestampAndBody` |
| UT-XMC | `CrossModuleContractTest` (E31) | `qcnSummaryCarriesTheFieldsE32Records`, `onlyTheConfiguredAssociationMethodsMayFileAResult` |
| UT-WIZ | `WizardRulesTest` (E32) | `fourEyesRefusesTheSubmitterAndAnyoneWhoChangedSteps4To6`, `changingAnEarlierStepInvalidatesTheLaterSteps`, `prerequisitesDependOnOptionFamilyAndConfidence`, `associationMethodsMustMatchTheDriverCapabilities`, `bindingsUsePackageKeysAndVerificationIsOnlyTightened`, `commonItemsCanOnlyBeTightenedAndGatesKeepVerificationLast` |
| UT-OPT | `OptionPacksTest` (E32) | `tenPacksWithSchemasGuidesAndHelpInTwoLanguages`, `serialFramingAndUsbAllowList`, `vendorDatabaseMustBeReadOnlyAndSecretsAreReferences`, `bluetoothPairingMllpTlsAndPoctQc`, `cloudDicomFhirOcrAndManualRules`, `driverProtocolBecomesWizardDefaults` |
| UT-QC | `QcRulesTest` (E32) | `dailyCheckIsValidDueOrFailed`, `qcLotsAreEvaluatedPerLotAndExpire`, `aggregatePrefersFailedThenDueThenEarliestDue`, `instanceOverridesOnlyTightenAndRelaxingNeedsApproval` |
| UT-POS | `PosturePolicyTest` (E32) | `acceptableWhenEveryMandatoryRulePasses`, `unsupportedOsNoPatchPathDirectRemoteOrMissingMds2Block`, `legacyVendorPcExceptionsNeedGatewayIsolationAndOutboundOnlyAgent` |
| UT-INV | `InventoryMappingTest` (E32) | `inventoryHas133ModelsWithTheContractOptionTotals`, `everyRowMapsToOneOfTheElevenFamiliesAndBuildsADetailRecord`, `regulatoryStatusPerMarketFollowsTheFdaColumn`, `variantsOptionNotesAndConfidence` |
| UT-DPR | `DriverPackageRulesTest` (E32) | `everyBundledPackageIsValid`, `canonicalFormSortsKeysAndExcludesTheSignature`, `schemaCoverageGatesTargetsVectorsAndSecretsAreChecked`, `versionsCompareSemantically` |
| UT-MASK | `TraceMaskerTest` (E32) | `hl7PatientSegmentAndObservationValuesAreMasked`, `jsonPersonalMembersEmailsIdsAndDatesAreMasked`, `gdtPatientFieldsAndPlainDatesAreMasked` |
| UT-E32S | `SupportTest` (E32) | `jsonDiffReportsChangedAddedAndRemovedPaths`, `docsPathsMergeAndHashing`, `workingDaysSkipWeekends` |
| UT-CSM | `CaptureStateMachineTest` (E33) | `happyPathListeningPreviewAccepted`, `retestReturnsToListeningAndRejectIsFinal`, `timeoutExpiresWaitingStatesOnly`, `expiredSessionAnswersSessionExpired`, `acceptWithoutPreviewIsNotPending`, `fallbackChainTransitions`, `sequenceKeepsPreview`, `finalStatesAcceptNothing` |
| UT-FB | `FallbackChainTest` (E33) | `chainOrderIsAlternateThenOcrThenManual`, `noOcrWithoutPackage`, `restrictedHasManualOnlyForCollector`, `manualNeedsCompleteAttestation`, `ocrNeedsThePhotoAndValues`, `alternateMustBeOffered`, `reasonCodeIsMandatory` |
| UT-VR | `VerificationRuleTest` (E33) | `clinicalFormsAreVerifiedByTheFormsRoles`, `restrictedReadingsOnlyCollectorInOwnSessionOrMro`, `blockPreventsPostingAndWarningsNeedAcknowledgement`, `signOffIsForPhysicians` |
| UT-PL | `PlainLanguageAndComparisonTest` (E33) | `bloodPressureCategoriesFollowEsh2018`, `bmiBandsAndComputation`, `summaryInPreferredLanguageWithoutDiagnosis`, `comparisonHasBaselinePreviousTrendAndThresholds` |
| UT-KPI | `CaptureKpisAndOcrTest` (E33) | `usageMinutesSavedRetestAndUnmatchedRates`, `emptyWindowGivesZeroes`, `ocrFieldsFromDisplayText`, `implausibleOrAmbiguousOcrFieldsAreLeftEmpty` |
| UT-CSU | `CaptureServiceUnitTest` (E33) | `stationDisplayNamesCarryNoTechnicalParameters`, `transitionsAreAuditedPerState`, `provenanceMarkingPerPath`, `snapshotKeepsValuesAndText`, `eventHubDeliversWithoutTransaction` |
| UT-AUTH | `EndpointAuthenticatorServiceTest` (E34) | `successCarriesThePrincipalWithItsInstances`, `unknownEndpoint`, `statusIsCheckedBeforeTheToken`, `badAndExpiredTokens`, `previousTokenIsAcceptedOnlyWithinTheGracePeriod`, `thumbprintMustMatchTheBoundValidCertificate`, `zoneAndCidrPolicy`, `pathNotAllowedForTheEndpointType`, `throttledEndpointsAreRefused`, `failsClosedWhenDisabledOrOnErrors` |
| UT-POL | `EndpointPolicyTest` (E34) | `resourceIsTheFirstSegmentAfterTheIngestPrefix`, `pathsAreAllowedPerEndpointType`, `zoneRules`, `cidrMatchingIpv4Ipv6AndMapped`, `malformedInputNeverMatchesAndHostNamesAreNotResolved` |
| UT-ANOM | `AnomalyRulesTest`, `TrafficMonitorTest` (E34) | `authFailureFeedsBurstPerEndpointAndPerAddress`, `reasonCodesMapToSpecificRules`, `edgeAndEngineEventsMapToTheirRules`, `failedAuthBurstAlertsAtTenAndIsolatesAtThirty`, `singleOccurrenceRulesRaiseOnEveryEvent`, `clinicHoursWindows`; `dynamicLimitUsesFiveTimesP95CappedByTheCeiling`, `spikeIsReportedOncePerMinuteAndSustainedSpikeThrottles`, `offHoursDataTrafficIsReportedOncePerDay` |
| UT-CRY | `CryptoPrimitivesTest` (E34) | `tokensAre256BitAndUnique`, `hashIsPepperedDeterministicAndNeverTheToken`, `canonicalFormSortsKeysDropsWhitespaceKeepsNumbersAndRemovesTopLevelSignature`, `ed25519AndEcdsaSignaturesDetectTampering`, `proofOfPossession`, `untrustedWithoutAnchorsAndInvalidPem`, `settingsClamp` |
| UT-CHAIN | `SecurityEventChainTest` (E34) | `attributesAreAllowListedSanitisedAndCapped`, `typesAndModulesAreValidated`, `chainDetectsAnyChange` |
| UT-SCAN | `SecretScannerAndPoliciesTest` (E34) | `literalSecretsAreFoundReferencesAreAccepted`, `remediationDueDates`, `enrolmentThrottleAllowsTenAttemptsPerFifteenMinutes` |
| UT-FILT | `DeviceEndpointAuthenticationFilterTest` (kernel) | `bothFactorsAuthenticateAndThePrincipalIsAvailableDownstream`, `missingClientCertificateIsRefusedWhenMtlsIsRequired`, `wrongTokenMissingCredentialsAndHostileEndpointIdsAreRejectedWithOneEventEach`, `withoutEndpointSecurityEverythingIsDenied`, `enrolmentPathsAreNotFiltered`, `thumbprintNormalisation` |
| UT-KTYP | `DeviceContractTypesTest` (kernel) | `optionCodesMapBothWays`, `anyBlockingGateBlocksTheObservation`, `defaultsAreSafe` |
| UT-E03A | `DeviceEngineAdapterTest` (E03) | `boundFieldsBecomeTheNestedMeasurementMap`, `ecgDeviceTextIsClassifiedForPhysicianReview`, `vitalsPocFitTestAndOtherCategories`, `stsUsesTheEngineAgeCorrectionWhenBothAudiogramsCarryIt` |
| GW-INT | `GatewayIntegrationTest` (gateway) | `enrolsZeroTouchAndStoresTheIdentityEncrypted`, `onlineMllpMessageIsForwardedAndTheEnginesAckRelayed`, `senderOutsideTheAllowListIsRejectedWithAr`, `outageIsBridgedByTheEncryptedQueueAndReplayedWithoutLossOrDuplication`, `vendorExportFileIsForwardedWithItsHashAsIdempotencyKey`, `heartbeatReportsStateAndExecutesRotateTokenCommand`, `statusPageIsServedOnLocalhostWithoutPhi`, `isolateCommandQuarantinesTheQueueAndStopsForwarding` |
| GW-JRN | `EncryptedJournalTest` (gateway, 13) | e.g. `recordsAreEncryptedAtRestAndAssignedIdempotencyKeys`, `tamperedRecordIsDetectedAndNeverForwarded`, `capacityLimitRefusesNewItemsInsteadOfDroppingOldOnes`, `itemsOlderThanRetentionAreDeadLetteredNotLost`, `perInstanceFifoCheck`, `quarantineStateIsDurable` |
| GW-FWD | `ForwarderTest` (gateway) | `offlineBacksOffThenReplaysTheBacklogThroughTheBatchApiWithOriginalKeys`, `lostReceiptIsResolvedByIdempotencyKeyWithoutLossOrDuplication`, `onlineSingleItemUsesTheKindSpecificPath`, `deterministicRejectionIsDeadLetteredAndDoesNotBlockTheQueue`, `isolationVerdictQuarantinesTheQueueAndStopsForwarding`, `forwardNowDefersWhenOlderItemsOfTheSameDeviceArePending` |
| GW-CON | `ConnectorsTest`, `MllpFramingTest`, `FileWatcherTest`, `VendorDbPollerTest`, `EnrolmentClientTest`, `BackoffTest` (gateway) | serial framing / simulation, BLE MAC hashing and allow-list, POCT1-A delimiting and local ACK, DICOM header and drop folder; MLLP framing (8); folder watcher (8); read-only DB poller (3); enrolment client (6); back-off (6) |

### 3.3 Web, end-to-end and the security test pack

| Ref | Evidence |
|---|---|
| WEB-DEV | `web/oh-workspace/src/screens/devices/Devices.test.tsx`, `src/domain/deviceWizard.test.ts`, `src/auth/deviceRoles.test.ts` (console – part of the 83 console and security tests) |
| WEB-SEC | `web/oh-workspace/src/screens/security/Security.test.tsx` (part of the 83) |
| WEB-ENG | `web/oh-workspace/src/screens/engine/Engine.test.tsx` (count &lt;WEB TESTS&gt;) |
| WEB-CAP | `web/oh-workspace/src/screens/capture/Capture.test.tsx`, `CaptureScreens.test.tsx`, `Embedding.test.tsx`, `src/domain/capture.test.ts` (count &lt;WEB TESTS&gt;) |
| WEB-EMP | `web/employee-app/src/screens/Devices.test.tsx` (results, consents, kiosk – count &lt;WEB TESTS&gt;) |
| WEB-MGR | `web/manager-workspace/src/screens/DeviceFleet.test.tsx` (count &lt;WEB TESTS&gt;) |
| E2E | `tools/e2e/e2e-increment6.js` – the 21 checks of contract §20; result &lt;E2E&gt; (to be mapped per check by the delivery lead) |
| PACK | `tools/device-security/` scripts 01–15 and `docs/increment-06/device-security/` – **delivered, not yet executed in staging** |

## 4. E31 – Medical Device Integration Engine

Module `modules/wz-e31-device-engine` (package `com.wellnessz.e31`, schema `deviceint`, V22). Controllers
`IngestController` (`/device-ingest/**`), `WebhookController` (`/device-webhooks/**`), `EngineController`
(`/device-engine/**`). Routing adapters in E03 (`ResultDeviceSink`, `OrderWorklistSource`, `InterpretationSignOffListener`),
E07 (`ExposureMeasurementSink`), E10 (`BreathAlcoholSink`), E12 (`ScreeningResultSink`, `WearableDailySink`), E14
(`AppointmentWorklistSource`). Edge: `apps/wz-device-gateway`. OH workspace screens in `web/oh-workspace/src/screens/engine`.

### 4.1 High-level requirements

| ID | Requirement (short) | Implementation | UI | Verification | Status | Notes |
|---|---|---|---|---|---|---|
| E31-HLR-01 | Vendor-independent canonical pipeline acquire → … → acknowledge, at-least-once, idempotent, replay, dead letters | `IngestService` (ACQUIRE), `PipelineProcessor`, `MessageStore`, `RawPayloadStore`, `Ids` (UUIDv5); tables `message`, `stage_log`, `dead_letter`, `replay_job`; `/device-engine/dead-letters`, `/replay` | OH `#/devices/dead-letters`, `#/devices/operations` | IT-EN1, IT-EN7, UT-SUP, IT-X1, WEB-ENG | Implemented | Duplicate `Idempotency-Key` → receipt `DUPLICATE`; replay from stored raw payload with current or pinned driver version, no duplicates |
| E31-HLR-02 | Adapter library: serial / USB / HID, BLE + Classic SPP, HL7 MLLP (ORU, ORM / OML, ADT), POCT1-A, vendor DB / files, cloud APIs and webhooks, DICOM (C-STORE, MWL, Q/R, waveform), FHIR, OCR + barcode, manual | Parsers in `adapter.*` (`Hl7V2Parser`, `FhirObservationParser`, `XPathParser`, `CsvParser`, `GdtParser`, `GattStructParser`, `RegexLinesParser`, `JsonPointerParser`, `DicomJsonParser`, `FormParser`); gateway connectors; option packs P1–P10 | Wizard step 2 / 4 | UT-PAR, UT-11073, GW-CON, GW-INT, IT-EN1…IT-EN6 | Partially | Built: ORU^R01 + ACK, FHIR, POCT1-A, CSV / XML / GDT / PDF, BLE GATT payloads, serial frames (serial-over-TCP / simulation), signed webhooks, DICOMweb JSON + attachments, OCR, manual. **Not built:** native serial / USB-CDC / HID wedge, BLE radio and Classic SPP, outbound ORM / OML / ADT, OAuth2 cloud polling, DICOM C-STORE / MWL / Q-R and waveform rendering, barcode reading |
| E31-HLR-03 | Driver SDK: declarative, versioned, signed packages with mappings, bindings, test vectors | `DriverPackage`, `DriverParser`, `DriverValidator`, `DriverRuntime`, `CanonicalJson`, `SemVer`, `DriverService`; `/device-engine/drivers/validate|conformance|sign`; E32 registry; E34 `PackageSignatureService` | OH `#/devices/simulators/drivers`, `#/devices/drivers` | UT-DRV, UT-CONF, UT-DPR, IT-CO8, IT-SE5, IT-X3 | Implemented | 18 packages; engine refuses packages outside `engineCompatibility`; secret scan; no code per model |
| E31-HLR-04 | Canonical observation: MDC + LOINC, UCUM, method, body site, device identity, software, calibration, operator, time, quality, raw hash-linked | `Normaliser`, `TerminologyService`, `FhirWriter`, `FhirStore`, `CanonicalDoc`; tables `observation`, `fhir_resource`, `terminology_*`; `/device-engine/observations/{id}?format=fhir`, `/archive/{id}/verify` | OH `#/devices/observations` | UT-NOR, UT-CALC, IT-EN1, IT-EN4 | Implemented | FHIR R4 JSON validated by strict parsing + rules (no IG validator – document 01 §13) |
| E31-HLR-05 | Association by worklist, session, badge, vendor id; unmatched queue; nothing auto-filed below threshold | `AssociationEngine`, `AssociationService`; SPIs `DeviceCaptureSessions`, `DeviceWorklistSource`; tables `association_candidate`, `unmatched_item`; `/device-engine/unmatched*` | OH `#/devices/unmatched` | UT-ASSOC, UT-XMC, IT-EN7, IT-EN5 | Implemented | Auto-file 0.95, suggest 0.60; fuzzy name ≤ 0.70 (no DOB in the HR master); restricted categories worklist / session only |
| E31-HLR-06 | Configurable quality gates before posting (calibration / QC, grades, validity, plausibility, verify-before-post) | `Gates`, `GateCatalogue`, `EngineGateEnvironment`; `DeviceCalibrationCheck` (E14); `qcStatus` (E32); table `gate_signoff`; `/device-engine/gates` | Capture preview flags | UT-GATE, IT-EN4, IT-CO6, IT-CA3 | Implemented – needs sign-off | 15 gates; BLOCK explains why; **clinical SME sign-off of the rule set pending** (`signOff PENDING`) |
| E31-HLR-07 | Result-to-form bindings per device model and form version | Package `bindings`, wizard step 5 overrides, E33 `FormRegistry`; `Router`; sinks | Wizard step 5 binding editor with preview | UT-WIZ (`bindingsUsePackageKeysAndVerificationIsOnlyTightened`), UT-SUP, UT-E03A, IT-CO2, IT-CA2 | Implemented | 16 forms; module logic stays in the module |
| E31-HLR-08 | Device-side workflows: demographics / order push, start / stop from the form, historic retrieval | Package `capabilities`; `/device-ingest/worklist`; `CaptureSessionListener` queues `PUSH_WORKLIST` / `START_ACQUISITION`; gateway `WorklistService` (GDT 6302) | – | UT-WIZ (`associationMethodsMustMatchTheDriverCapabilities`), IT-EN5 | Partially | Only GDT 6302 worklist files at the edge; ORM / ADT / MWL push not built; `START_ACQUISITION` / `RESEND_LAST` answered `not_supported_by_connector`; historic retrieval not built |
| E31-HLR-09 | Store-and-forward at the edge: gateways and the mobile app, reconciliation, duplicates, ordering | Gateway `EncryptedJournal`, `Forwarder`; `/device-ingest/batch` (`X-Gateway-Sequence`, `X-Replayed`) | Batch mode offline banner | GW-JRN, GW-FWD, GW-INT, IT-EN5, IT-CA7, IT-CA8 | Partially | Gateway complete; **no native mobile app** with an on-device queue (tablet readings reach the core through the gateway relay or `/device-ingest/batch`) |
| E31-HLR-10 | Simulator and golden datasets per family, used in CI for conformance, regression and performance | `SimulatorService`, `GoldenDatasets`, `DryRun`; 94 datasets; `/device-engine/simulators/{adapter}/run`, `/golden-datasets` | OH `#/devices/simulators` | UT-CONF, IT-EN7, WEB-ENG | Partially | Conformance and regression run in `./mvnw verify` (CI); **no performance stage** (profile `perf/mixed-50ps.json` unused) |
| E31-HLR-11 | Observability per connector and device; dashboards, alerts, operations API | `ConnectorMetrics` (Micrometer), `OperationsService`, `EngineJobs`; `/device-engine/ops/connectors|metrics|alerts`; heartbeat | OH `#/devices/operations`; fleet | IT-EN7, IT-CO7, GW-INT, WEB-ENG | Implemented | `STALE` 5 min / `SILENT` 30 min; PHI-free labels; Grafana dashboards not shipped – the OH workspace screen is the dashboard |
| E31-HLR-12 | Multi-tenancy: isolated catalogues, keys, queues, data; per-tenant connector limits | `tenant_code` on engine rows, tenant-partitioned polling, `tenant_quota`, `/device-engine/tenants`; endpoint tenant binding (E34) | – | IT-EN2 | Partially | Data, queues and quotas partitioned and negatively tested; **catalogue and signing keys are not tenant-scoped**; partner tenants (E20–E25) not yet built |
| E31-HLR-13 | 5 s p95, 50 msg/s with linear scaling, 50 MB streamed | Inline processing, `skip locked` workers, streamed `/device-ingest/attachments` (`device.ingest.max-payload-bytes`) | – | IT-EN6 (attachment) | Partially | **Not measured**: no load test; streaming verified functionally only |
| E31-HLR-14 | FHIR Provenance: who, which device / software, calibration, driver version | `FhirWriter` Provenance; V21 columns on `ohrecord.result`; `ResultService.deviceFromEngine` | E03 device badge | IT-CA2, IT-X1, IT-EN1 | Implemented | |

### 4.2 Features

| ID | Feature | Implementation | UI | Verification | Status | Notes |
|---|---|---|---|---|---|---|
| E31-F01 | Canonical pipeline & message bus | As E31-HLR-01; Postgres work table + outbox (deviation – document 01 §13) | `#/devices/dead-letters` | IT-EN1, IT-EN7, UT-SUP | Implemented | Kafka / NATS are E35 |
| E31-F02 | Protocol adapter library P1–P10 | As E31-HLR-02 | – | UT-PAR, GW-CON | Partially | Same gaps as E31-HLR-02 |
| E31-F03 | Driver SDK & declarative packages | As E31-HLR-03 | `#/devices/simulators/drivers` | UT-DRV, UT-CONF | Implemented | REST SDK services; no command line (E31-BL-011) |
| E31-F04 | Normalisation & terminology | `Normaliser`, `Gli2012`, `OshaAgeCorrection`, `Terminology*`; `/device-engine/terminology/*` | `#/devices/terminology` | UT-NOR, UT-CALC | Implemented – needs sign-off | GLI-2012 spline approximations and OSHA female table to be verified (E31-BL-013) |
| E31-F05 | Association engine | As E31-HLR-05 | `#/devices/unmatched` | UT-ASSOC, IT-EN7 | Implemented | |
| E31-F06 | Quality gates & clinician verification | As E31-HLR-06; accept with acknowledged flags (E33) | Capture preview | UT-GATE, UT-VR, IT-CA3 | Implemented – needs sign-off | Rule set sign-off |
| E31-F07 | Result-to-form bindings | As E31-HLR-07 | Wizard step 5 | UT-WIZ, IT-CO2 | Implemented | |
| E31-F08 | Bidirectional device workflows | As E31-HLR-08 | – | IT-EN5 | Partially | GDT worklist only |
| E31-F09 | Edge store-and-forward | As E31-HLR-09 | Gateway status page | GW-JRN, GW-FWD | Partially | Mobile app queue not built |
| E31-F10 | Device simulator & golden datasets | `SimulatorService`, `GoldenDatasets`, 94 datasets across 11 families (normal, edge, malformed, time-skew, duplicate) | `#/devices/simulators` | UT-CONF, IT-EN7 | Implemented | Simulators replay datasets into the pipeline (adapter level, not network-level device emulators) |
| E31-F11 | Connector & device observability | As E31-HLR-11 | `#/devices/operations` | IT-EN7, WEB-ENG | Implemented | |
| E31-F12 | Multi-tenant partitioning | As E31-HLR-12 | – | IT-EN2 | Partially | Catalogue / keys not tenant-scoped; no onboarding template |

### 4.3 Implementation backlog

| ID | Story / task (short) | Implementation | Verification | Status | Notes |
|---|---|---|---|---|---|
| E31-BL-001 | Canonical model, stages, event contracts as ADRs | API contract §4, §8; ADR-28…ADR-35 (Architecture 01 §24); `CrossModuleContractTest` | UT-XMC, UT-KTYP, PLAT | Implemented – needs sign-off | Architecture board approval of the ADRs pending |
| E31-BL-002 | Pipeline services with DLQ and triage UI; 50 msg/s p95 ≤ 5 s | `PipelineProcessor`, dead-letter API and screen | IT-EN7, WEB-ENG | Partially | **Load test not run** |
| E31-BL-003 | Serial / USB / HID adapter for the Device Host agent; validated with three devices | Gateway `SerialConnector`, `SerialFramer`, `SerialSource` (TCP / file / stdin); `RegexLinesParser`; P1 schema | GW-CON, UT-PAR, IT-EN5 | Partially | **No native serial / USB-CDC, no HID wedge, no port discovery; no real device** |
| E31-BL-004 | HL7 MLLP adapter (ORU, ORM / OML, ADT, ACK / NAK, TLS), IHE conformance | Gateway `MllpListener`, `MllpFraming`, `Hl7Ack`; `Hl7V2Parser`; TLS listeners | GW-INT, GW-CON, UT-PAR, IT-EN1 | Partially | ORU + ACK + TLS done; outbound ORM / OML / ADT not built; IHE test set not run (own PCD-01 golden datasets) |
| E31-BL-005 | Vendor DB / file watcher; validated with Benson, Tremetrics, Spirotrac, EasyOne Connect, FitPro | Gateway `FileWatcher`, `VendorDbPoller`; parsers; packages `spiro.ndd.easyone-connect-gdt`, `audio.benson.cca200-db` | GW-CON, GW-INT, IT-EN4 | Partially | **Not validated with real vendor exports** (synthetic) |
| E31-BL-006 | BLE adapter (BLP / HTP / PLX / WSS / GLS / BCS, vendor services) with LE Secure Connections; Nonin, Omron / A&D validated | `GattStructParser`, `Ieee11073`; gateway `BleIntakeServer`; P3 rules | UT-11073, UT-PAR, GW-CON | Partially | **No BLE radio / pairing in the agent; no device validated**; no GLS package |
| E31-BL-007 | POCT1-A2 adapter + data-manager HL7 bridge; two data managers validated | `XPathParser`, `Poct1aAck`, gateway `PoctListener`; QC → E32 | UT-PAR, GW-CON, IT-EN3, UT-XMC | Partially | **No data manager validated** |
| E31-BL-008 | Cloud API / webhook framework (OAuth2, polling, webhooks, rate limits, consent) | `WebhookController` (HMAC, nonce, 600 / min), P6 option rules, consent gate | UT-SUP, IT-EN6, IT-EN3 | Partially | **OAuth2 client-credentials polling not built**; no vendor cloud validated |
| E31-BL-009 | DICOM adapter (C-STORE, MWL, Q/R, ECG waveform), PACS routing, ILO worklist | `DicomJsonParser`, `/device-ingest/dicomweb`, `/attachments`; gateway `DicomDropConnector` | UT-PAR, GW-CON, IT-EN6 | Partially | **No C-STORE / MWL / Q-R, no PACS routing, no ILO worklist, no waveform rendering** |
| E31-BL-010 | FHIR inbound (PoCD, PCHA) + OCR / barcode fallback; IG validation; OCR ≥ 98 % | `FhirObservationParser`; E33 OCR path | UT-PAR, IT-EN3, IT-EN6, IT-CA4, UT-KPI | Partially | No IG validator; OCR accuracy not measured; no barcode |
| E31-BL-011 | Package format, signing, compatibility; SDK CLI; developer documentation | Format and signing (E31 / E34), `engineCompatibility`, REST SDK services; Architecture 03 §19 | UT-DRV, UT-CONF | Partially | **No command-line SDK** (scaffold / test / sign / publish via REST) |
| E31-BL-012 | Driver packages for the sprint families; ≥ 40 models certified across 11 families | 18 packages, 17 models, 11 families, P1–P10 | UT-CONF, UT-DPR | Partially | 17 models packaged, none certified against a real device |
| E31-BL-013 | Terminology service, GLI-2012, OSHA tables, reference ranges; FHIR writer, raw archive | `TerminologyService`, `Gli2012`, `OshaAgeCorrection`, `FhirWriter`, `RawPayloadStore` | UT-NOR, UT-CALC, UT-CONF | Implemented – needs sign-off | Mapping coverage 100 % for bundled packages; calculations to be verified against the reference (GLI approximations, OSHA female table) |
| E31-BL-014 | Session binding of the selected employee | `DeviceCaptureSessions` → `AssociationEngine` (`SESSION`, 900 s window, conflict handling) | UT-ASSOC, IT-CA2, IT-EN5 | Implemented | Timeout, conflict → unmatched, audit |
| E31-BL-015 | Unmatched queue with suggestions | `unmatched_item`, `/device-engine/unmatched*`, reason codes, SLA 8 h | IT-EN7, WEB-ENG | Implemented | Console roles see counts only |
| E31-BL-016 | Worklist push (ADT / ORM / MWL / vendor import) and badge / NFC association | `DeviceWorklistSource` (E03, E14), `/device-ingest/worklist`, gateway GDT 6302; `BADGE` method; kiosk badge | UT-ASSOC, IT-CA9 | Partially | Only GDT; no vendor-software validation; NFC reader hardware not tested |
| E31-BL-017 | Quality gates configurable, blocked postings explain why; SME sign-off | `Gates`, `GateCatalogue`, `gate_signoff` | UT-GATE, IT-CA3 | Implemented – needs sign-off | SME sign-off pending |
| E31-BL-018 | Binding editor per form version with preview | Wizard step 5 (`Wizard.tsx`), `POST …/steps/5/preview`, versioned configurations | UT-WIZ, IT-CO2, WEB-DEV | Implemented | Bindings exist for 16 forms (the workbook names 14 families) |
| E31-BL-019 | Start spirometry / ECG from the form with details on the device | Session open → `PUSH_WORKLIST` / `START_ACQUISITION`; gateway GDT worklist | IT-EN5 | Partially | `START_ACQUISITION` not supported by any connector; fallback = GDT worklist / manual start |
| E31-BL-020 | Encrypted store-and-forward on gateway and mobile app; 48-h test; capacity alert; no duplicates | `EncryptedJournal`, `Forwarder`, heartbeat `capacityPct`, `device.gateway-backlog` | GW-JRN, GW-FWD, GW-INT | Partially | No 48-hour wall-clock test; no mobile app queue |
| E31-BL-021 | Simulators per family + CI conformance and performance stages | `SimulatorService`, golden datasets, conformance tests in `verify` | UT-CONF, IT-EN7 | Partially | No performance stage |
| E31-BL-022 | OpenTelemetry instrumentation, dashboards, alert rules, operations API | `ConnectorMetrics`, ops API, alerts → `device.connector-alert` | IT-EN7, WEB-ENG | Implemented | Runbook entries in documents 04 / 05 |
| E31-BL-023 | Tenant partitioning with quotas and onboarding template | `tenant_code`, `tenant_quota`, `/device-engine/tenants` | IT-EN2 | Partially | No onboarding template; catalogue / keys not tenant-scoped |
| E31-BL-024 | Performance and soak at 2× peak; capacity model | Profile `perf/mixed-50ps.json` | – | Partially | **Not executed** |

## 5. E32 – Device Catalogue, Administration & Connectivity Provisioning Console

Module `modules/wz-e32-device-console` (package `com.wellnessz.e32`, schema `devicecat`, V23). Controllers
`CatalogueController`, `ConfigurationController`, `OperationsController`. E14 adapter `EquipmentRegisterAdapter`. OH
workspace screens `web/oh-workspace/src/screens/devices` (`Catalogue`, `Wizard`, `Approvals`, `Registry`, `Operations`).

### 5.1 High-level requirements

| ID | Requirement (short) | Implementation | UI | Verification | Status | Notes |
|---|---|---|---|---|---|---|
| E32-HLR-01 | Governed catalogue with UDI / GUDID / EUDAMED, regulatory status, options, drivers, posture, lifecycle | `CatalogueSeedLoader`, `InventoryMapping`, `CatalogueService`, `ModelStore`; tables `model`, `seed_state`; `/device-catalogue/models*`, `/udi`, `/lifecycle`, `/compare` | `#/devices/catalogue` | UT-INV, IT-CO1, WEB-DEV | Implemented | 133 models, 11 families, option totals as contract; UDI by import |
| E32-HLR-02 | Eight-step wizard with pause / resume / clone | `ConfigurationService`, `WizardRules`, `ConfigurationStore`; `/device-configurations/*` | `#/devices/wizard` | UT-WIZ, IT-CO2, IT-CO3, WEB-DEV | Implemented | |
| E32-HLR-03 | Parameters per option per accepted practice | `OptionPacks`, `OptionRules`, `ParameterValidator`; `e32/option-packs.json` | Wizard step 4 | UT-OPT | Implemented | JSON Schema 2020-12 + semantic rules |
| E32-HLR-04 | Fleet: inventory, assignment, calibration / QC logs with automatic lockout | `FleetService`, `CalibrationService`, `QcRules`; tables `qc_check`, `lockout_event`, `fleet_alert`, `consumable`, `on_duty` | `#/devices/fleet`, `#/devices/calibration` | UT-QC, IT-CO6, IT-CO7 | Implemented | Annual calibration stays in E14 |
| E32-HLR-05 | RBAC, four-eyes, history, export / import, templates and batch | `GovernanceService`, `ApprovalStore`, `JsonDiff`; `/device-governance/*` | `#/devices/governance`, `#/devices/approvals` | UT-WIZ, UT-E32S, IT-CO2, IT-CO4 | Implemented | |
| E32-HLR-06 | Vendor onboarding (MDS2, SBOM, contracts); block on failed posture | `PosturePolicy`; `/models/{code}/posture*`; `posture_exception` | Catalogue model detail | UT-POS, IT-CO9 | Implemented | Exceptions approved by SECURITY_OPS only |
| E32-HLR-07 | Request-a-new-model with scoping, estimate, sprint | `ModelRequestService`; `/device-model-requests*` incl. `/kpi` | `#/devices/model-requests` | IT-CO9, UT-E32S | Implemented | |
| E32-HLR-08 | Decommissioning: revoke, remove bindings, archive, retain, disposal record | `ConfigurationService` decommission + approve; `DeviceEndpointProvisioning.revoke`; `DeviceSecretStore.destroy` | Wizard / fleet | IT-CO5 | Implemented | Post after decommission → 401 / 404 |
| E32-HLR-09 | Diagnostics without clinical access: masked trace, payloads, mapping, offset, pairing, re-send | `DiagnosticsService`, `TraceMasker`; `/device-diagnostics/{id}/trace|health|resend` | `#/devices/diagnostics/{id}` | UT-MASK, UT-SUP | Implemented | |
| E32-HLR-10 | Data-flow summary and certification PDF | `CertificationService`; `certification`; `/data-flow`, `/certification`, `/certificate.pdf` | Fleet instance | IT-CO2 | Implemented | `PdfRenderer`, SHA-256 + approval hash |
| E32-HLR-11 | Multilingual administrator UI and WCAG 2.2 AA | Option-pack help EN + AR; Arabic guidance shown right-to-left in the wizard | Wizard | UT-OPT, WEB-DEV | Partially | Console UI itself is English; **WCAG 2.2 AA audit not performed** |

### 5.2 Features

| ID | Feature | Implementation | UI | Verification | Status | Notes |
|---|---|---|---|---|---|---|
| E32-F01 | Catalogue & regulatory register | As E32-HLR-01 | `#/devices/catalogue` | UT-INV, IT-CO1 | Implemented | |
| E32-F02 | Provisioning wizard (8 steps) | As E32-HLR-02 | `#/devices/wizard` | UT-WIZ, IT-CO2 | Implemented | |
| E32-F03 | Option packs P1–P10 | As E32-HLR-03 | Wizard steps 2–4 | UT-OPT | Implemented | Help in two languages |
| E32-F04 | Calibration, QC & lockout | As E32-HLR-04; gates `QC_DAILY`, `QC_LOT` | `#/devices/calibration` | UT-QC, UT-GATE, IT-CO6 | Implemented | |
| E32-F05 | Fleet dashboard & alerts | `FleetService`, ITSM outbox `itsm-tickets` | `#/devices/fleet`; manager `/device-fleet` | IT-CO7, WEB-MGR | Implemented | |
| E32-F06 | Governance & environment promotion | As E32-HLR-05 | `#/devices/governance` | IT-CO4 | Implemented | |
| E32-F07 | Vendor security onboarding | As E32-HLR-06 | Catalogue model detail | UT-POS, IT-CO9 | Implemented | |
| E32-F08 | Request-a-new-model | As E32-HLR-07 | `#/devices/model-requests` | IT-CO9 | Implemented | |
| E32-F09 | Diagnostics & masked trace | As E32-HLR-09 | `#/devices/diagnostics/{id}` | UT-MASK | Implemented | |
| E32-F10 | Certification record & data flow | As E32-HLR-10 | Fleet instance | IT-CO2 | Implemented | |
| E32-F11 | Decommissioning & key revocation | As E32-HLR-08 | Wizard | IT-CO5 | Implemented | |

### 5.3 Implementation backlog

| ID | Story / task (short) | Implementation | Verification | Status | Notes |
|---|---|---|---|---|---|
| E32-BL-001 | Catalogue model and admin UI, seeded with the inventory and FDA / EUDAMED references | `CatalogueSeedLoader`, `InventoryMapping`, `CatalogueController` | UT-INV, IT-CO1, WEB-DEV | Implemented | Lifecycle states and search |
| E32-BL-002 | UDI / GUDID / EUDAMED look-up | `POST /device-catalogue/models/{code}/udi` (source, date) | IT-CO1 | Implemented | Import, no outbound call |
| E32-BL-003 | Eight-step wizard with pause / resume / clone; usability test with 3 administrators | As E32-HLR-02 | UT-WIZ, IT-CO2, IT-CO3 | Implemented – needs sign-off | Usability test with three administrators outstanding |
| E32-BL-004 | Ten option packs (schemas, guides, diagrams, rules, defaults); reviewed by integration and security; help in two languages | `e32/option-packs.json`, `OptionPacks`, `OptionRules` | UT-OPT | Implemented – needs sign-off | Review by integration and security pending; diagrams referenced in the administrator guide |
| E32-BL-005 | Connection and end-to-end test before publish | Step 6 `SIMULATOR` / `LIVE` via `DeviceDiagnostics.runTest`; evidence SHA-256; publish blocked | IT-CO2, IT-X1 | Implemented | LIVE passes on connector or endpoint heartbeat within 5 min |
| E32-BL-006 | Calibration / QC schedules with lockout and release | `CalibrationService`, `QcRules`, `calibration_schedule` | UT-QC, IT-CO6 | Implemented | Schedules proposed – biomedical sign-off advised |
| E32-BL-007 | Daily biological / 3-L syringe check in two taps | `POST /device-calibration/checks`; status badge | IT-CO6, WEB-DEV | Implemented | |
| E32-BL-008 | Fleet dashboard, alerts to the on-duty administrator, ticket link | `FleetService`, `on_duty`, `itsm-tickets` | IT-CO7, IT-SE7 | Implemented | |
| E32-BL-009 | Roles, four-eyes, change history, export / import with diff | `GovernanceService`, `change_history`, `approval` | UT-WIZ, IT-CO4 | Implemented | Promotion tested within one environment (no real DEV → PROD run) |
| E32-BL-010 | Site and family templates, batch provisioning | `/device-governance/templates*`, `/batch`, `/batches/{id}` | IT-CO4 | Implemented | |
| E32-BL-011 | MDS2 / SBOM intake and policy gate with exceptions | `PosturePolicy`, posture exceptions | UT-POS, IT-CO9 | Implemented | |
| E32-BL-012 | Request a new model with scoping, estimate, sprint | `ModelRequestService` | IT-CO9 | Implemented | Notification `device.model-request-update` |
| E32-BL-013 | Masked traces and re-send without health data | `TraceMasker`, `/device-diagnostics/*` | UT-MASK | Implemented | Re-send audited `DEVICE_RESEND` |
| E32-BL-014 | Certification PDF with snapshot, tests, approvals | `CertificationService` | IT-CO2 | Implemented | Sample audit by the auditor still to run |
| E32-BL-015 | Decommissioning in one action | Decommission + approve | IT-CO5 | Implemented | Negative test included |
| E32-BL-016 | Training material and job aids per option (video, one-page guides) | Document 04 (Device Administrator Guide) with a worked example per main option | – | Partially | No videos; one-page job aids per option still to produce |
| E32-BL-017 | Migrate the E14 equipment register; data-quality report | `EquipmentRegisterAdapter`, `/device-calibration/migration`, `/migration-report` | IT-CO6 | Implemented – needs sign-off | Report acceptance by biomedical engineering |

## 6. E33 – Module-Embedded Device Capture

Module `modules/wz-e33-device-capture` (package `com.wellnessz.e33`, schema `devicecap`, V24). Controllers
`CaptureController` (`/device-capture/**`), `KioskController` (`/device-ingest/kiosk/**`), `MeDeviceController`
(`/me/device-*`). Screens: `web/oh-workspace/src/screens/capture` (embedded in the E03 results tab, surveillance due
list, E10 collection and E12 screening), employee `screens/DeviceScreens.tsx`, `screens/Kiosk.tsx`.

### 6.1 High-level requirements

| ID | Requirement (short) | Implementation | UI | Verification | Status | Notes |
|---|---|---|---|---|---|---|
| E33-HLR-01 | Capture control listing certified, in-calibration devices at the station, no technical parameters, context push | `FormRegistry`, `DeviceListService`, `CaptureSessionService`; `/device-capture/forms`, `/devices`, `/sessions` | `EmbeddedCapture`, `CaptureFromDevice`, `StationPicker` | IT-CA1, UT-CSU, WEB-CAP | Implemented | |
| E33-HLR-02 | Progress, preview with units, flags and raw report; accept / reject / re-test; provenance marking | `CaptureTransitions`, `CaptureEventHub` (SSE), `VerificationRule`, `CaptureViews` | `CapturePreview` | UT-CSM, UT-VR, UT-CSU, IT-CA2, IT-CA3 | Implemented | SSE without values + 2 s polling |
| E33-HLR-03 | Audiometry, spirometry, vision, ECG specifics (STS, ATS/ERS, GLI, templates) | E03 `DeviceRules` via `E03.RESULT`; E31 normalisation and gates; vision template field groups | Capture preview, E03 chart | UT-E03A, UT-NOR, UT-GATE, IT-EN4, IT-CA6 | Implemented – needs sign-off | GLI / OSHA tables and STS rule sets need clinical sign-off |
| E33-HLR-04 | Vitals, body composition, ABPM / Holter | Forms `VITALS`, `BODY_COMPOSITION`, `ABPM_HOLTER`; BLE and HL7 packages; BMI computed | Capture, batch | UT-NOR, IT-EN1, IT-EN5, IT-CA2 | Implemented | No ABPM / Holter driver package yet (authored by configuration) |
| E33-HLR-05 | Breath-alcohol and drug-screen capture with custody and consent | E10 `BreathAlcoholSink`; restricted rule; `E10.BREATH_ALCOHOL` form | E10 collection screen | IT-EN5, IT-CA5, UT-VR | Partially | Breath alcohol complete; **instrumented drug-screen readers rejected by decision** |
| E33-HLR-06 | POC with QC lots and abnormal routing; fit test updating the respirator clearance record | E03 POC / FIT_TEST results, gates `QC_LOT`, `FIT_EXERCISES_COMPLETE`; abnormal worklist | Capture | UT-GATE, UT-E03A, IT-EN3, IT-EN6 | Partially | **No respirator clearance register exists to update** – the fit test files an E03 result (pass / fail, abnormal → worklist) |
| E33-HLR-07 | Mobile (tablet BLE, batch) and kiosk (badge, consent), offline, reconciliation | `BatchService`, `KioskService`; `/device-capture/batches*`, `/device-ingest/kiosk/*`, `/device-ingest/batch` | `#/devices/batch`; employee `#/kiosk` | IT-CA7, IT-CA8, IT-CA9, WEB-CAP, WEB-EMP | Partially | Server side, batch screens and kiosk complete (200 employees tested); **no native tablet app with BLE radio and on-device queue** |
| E33-HLR-08 | Comparison, trends, annotations, sign-off, module actions | `Comparison`, `/employees/{id}/summary`, `/annotations`, `/sign-off` | `#/devices/sign-off`, capture comparison panel | UT-PL, IT-CA6 | Implemented | Module actions call existing endpoints from the UI |
| E33-HLR-09 | Employee summary in preferred language; education nudge; wearable consent | `PlainLanguage`, `SummaryReleaser`, `MeDeviceController`, `WearableConsentRevocationListener` | Employee `/health-checks` | UT-PL, IT-CA10, WEB-EMP | Implemented | EN / AR / FR; education link optional (E29 / E30 not built) |
| E33-HLR-10 | Graceful degradation, path recorded | `FallbackChain`, `/sessions/{id}/fallback`, `/ocr`; `capture_method` | `FallbackChain.tsx` | UT-FB, IT-CA4 | Implemented | |
| E33-HLR-11 | WCAG 2.2 AA, gloves on tablets, time-and-motion study | 44 × 44 px targets, no technical settings | Capture screens | UT-CSU, WEB-CAP | Partially | WCAG audit and time-and-motion study outstanding |

### 6.2 Features

| ID | Feature | Implementation | UI | Verification | Status | Notes |
|---|---|---|---|---|---|---|
| E33-F01 | Capture control & station awareness | As E33-HLR-01 | Embedded capture | IT-CA1 | Implemented | |
| E33-F02 | Preview, flags & verification | As E33-HLR-02 | `CapturePreview` | UT-VR, IT-CA2 | Implemented | |
| E33-F03 | Audiometry & STS | E03 `DeviceRules`, OSHA App. F row from E31 | Capture, E03 chart | UT-E03A, UT-CALC | Implemented – needs sign-off | STS rule sets / OSHA table |
| E33-F04 | Spirometry & ATS/ERS | Gates `ATS_ERS_GRADE`, `PREDICTED_VALUES` | Capture | UT-GATE, UT-NOR, IT-EN4 | Implemented – needs sign-off | GLI verification |
| E33-F05 | Vision templates | `template-DOT`, `template-FAA_EASA`, `template-DSE`, `template-AIRSIDE_DRIVING` field groups; Snellen package | Capture | IT-CA1, IT-CA4 | Implemented | |
| E33-F06 | ECG, ABPM & Holter with sign-off | `/sign-off`; E03 `InterpretationSignOffListener` | `#/devices/sign-off` | IT-CA6, UT-E03A | Implemented | |
| E33-F07 | Vitals & body composition | As E33-HLR-04 | Capture | IT-CA2 | Implemented | |
| E33-F08 | Breath alcohol & drug screen | As E33-HLR-05 | E10 collection | IT-CA5 | Partially | Drug-screen readers rejected |
| E33-F09 | POC & fit test | As E33-HLR-06 | Capture | IT-EN3, IT-EN6 | Partially | No clearance register |
| E33-F10 | Mobile batch & kiosk | As E33-HLR-07 | `#/devices/batch`, `#/kiosk` | IT-CA7, IT-CA9 | Partially | No native tablet app |
| E33-F11 | Comparison & clinician actions | As E33-HLR-08 | Capture | UT-PL, IT-CA6 | Implemented | |
| E33-F12 | Employee summary & consent | As E33-HLR-09 | `/health-checks` | IT-CA10, WEB-EMP | Implemented | |

### 6.3 Implementation backlog

| ID | Story / task (short) | Implementation | Verification | Status | Notes |
|---|---|---|---|---|---|
| E33-BL-001 | Capture button in audiogram, spirometry, vision and vitals forms; usability test | `EmbeddedCapture` in the E03 results tab, surveillance due list, E10 collection, E12 screening | IT-CA1, WEB-CAP | Implemented – needs sign-off | Usability test with nurses outstanding |
| E33-BL-002 | Next option when a device is unavailable; path recorded | `FallbackChain` | UT-FB, IT-CA4 | Implemented | |
| E33-BL-003 | Preview with flags and raw report before acceptance | `CapturePreview`, `DeviceObservationGateway.preview` | IT-CA2, IT-CA3 | Implemented | |
| E33-BL-004 | Thresholds vs baseline, STS / age correction per rule set | E03 `DeviceRules`, E31 OSHA row | UT-E03A, UT-CALC | Implemented – needs sign-off | OSHA / HSE / aviation rule sets configured in E03 / E07 – clinical sign-off |
| E33-BL-005 | Spirometry indices, grades, % predicted, curves | Gates + GLI; curve image as attachment | UT-NOR, UT-GATE, IT-EN4 | Implemented – needs sign-off | GLI verification |
| E33-BL-006 | Vision results into DOT / FAA-EASA / DSE / airside templates | Vision form template groups | IT-CA1 | Implemented | |
| E33-BL-007 | 12-lead ECG PDF / waveform, measurements, unverified interpretation until sign-off | ECG packages, attachments, `/sign-off` | IT-CA6, UT-E03A | Implemented | Waveform as stored attachment (no viewer) |
| E33-BL-008 | Full vitals set in one sequence | Sequence sessions (`sequenceKeepsPreview`), HL7 / BLE packages | UT-CSM, IT-CA2 | Implemented | |
| E33-BL-009 | Breath alcohol with serial, calibration date, custody, feeding the programme | `BreathAlcoholSink`, restricted rule | IT-EN5, IT-CA5 | Implemented | E26 (incident) feed not built – future epic |
| E33-BL-010 | POC analytes with lots and ranges; abnormal → clinician queue | `QC_LOT`, E03 POC abnormal worklist | IT-EN3, UT-GATE | Implemented | |
| E33-BL-011 | Fit-test results update the clearance record automatically | E03 FIT_TEST result, `FIT_EXERCISES_COMPLETE` | IT-EN6, UT-GATE | Partially | No respirator clearance register to update |
| E33-BL-012 | Tablet batch mode with BLE devices and offline queueing; 200 employees | `BatchService`, `/device-ingest/batch` | IT-CA7, IT-CA8 | Partially | 200 employees reconciled server-side; tablet BLE radio and on-device queue not built |
| E33-BL-013 | Kiosk self-measurement with badge, consent, own-language result, privacy screen | `KioskService`, employee `Kiosk.tsx` | IT-CA9, WEB-EMP | Implemented | 3-minute idle end |
| E33-BL-014 | Trend views, sign-off, fitness actions from the capture screen | `Comparison`, sign-off, annotations | UT-PL, IT-CA6 | Implemented | Trends for 4 families |
| E33-BL-015 | Granular wearable consent, plain-language summary, education nudge | `MeDeviceController`, `PlainLanguage` | IT-CA10, UT-PL | Implemented | |
| E33-BL-016 | Time-and-motion study and UX audit in two clinics | KPI `minutesSaved` available | UT-KPI | Partially | **Study not performed** |

## 7. E34 – Endpoint & Integration Security

Module `modules/wz-e34-device-security` (package `com.wellnessz.e34`, schema `devicesec`, V25). Controllers
`EndpointController`, `EnrolmentController`, `ProtectionController`, `SecurityOperationsController`, `GovernanceController`.
Kernel `DeviceIngestSecurityConfig`, `DeviceEndpointAuthenticationFilter`. Screens `web/oh-workspace/src/screens/security`.
Test pack `tools/device-security/`, documents `docs/increment-06/device-security/`.

### 7.1 High-level requirements

| ID | Requirement (short) | Implementation | UI | Verification | Status | Notes |
|---|---|---|---|---|---|---|
| E34-HLR-01 | Unique cryptographic identity per gateway / agent / app / data manager; mTLS; plaintext refused | `EndpointService`, `EnrolmentService`, `CertificateVerifier`, `TokenHasher`, `EndpointAuthenticatorService`; kernel filter (`mtls_required`) | `#/security/devices` | IT-SE1, IT-SE2, UT-AUTH, UT-CRY, UT-FILT, GW-INT, IT-X1 | Implemented | mTLS terminated at the ingress; EST / SCEP and CRL / OCSP with the PKI / ingress |
| E34-HLR-02 | Segmented networks, deny-by-default, conduits, brokered vendor access | `ZoneService`, `EndpointPolicy`; `zone`, `conduit`, `zone_cidr`; `/device-security/zones|conduits|zone-cidrs` | `#/security/devices/zones` | IT-SE3, UT-POL, UT-AUTH | Implemented – needs sign-off | Reference network design and site validation pending (E34-BL-002); vendor broker documented as a pattern |
| E34-HLR-03 | Hardened minimal images, disk encryption, secure boot, patching, EDR, logging, no persistent PHI | Gateway `HardeningCheck`, systemd unit hardening, non-root container, encrypted journal; heartbeat `hardening` → `HARDENING_DRIFT` | Gateway status page | GW-INT, IT-SE8 | Partially | **CIS golden images, disk encryption, secure boot, EDR and patching not built** (infrastructure) |
| E34-HLR-04 | BLE LE Secure Connections, approved lists, review; USB VID/PID and class policies; authenticated serial servers | P3 / P1 option rules; BLE allow-list and MAC hashing; serial plaintext exception flag | Wizard step 4 | UT-OPT, GW-CON | Partially | No BLE radio or native USB in the agent – pairing and USB policy enforced by the OS / image; serial servers not authenticated by the agent (plaintext only under the exception flag) |
| E34-HLR-05 | Secrets in vault / KMS, runtime injection, rotation, audit | `SecretStoreService` (`devsec:`, `vault:`), `SecretScanner`; `/device-ingest/secrets/{ref}`; gitleaks in CI | `#/security/devices/secrets` | IT-SE6, UT-SCAN, IT-CO3 | Implemented | |
| E34-HLR-06 | Secure life-cycle: threat models, SAST / DAST / SCA, signed releases with SBOM, vulnerability management | CI: SpotBugs / FindSecBugs, CycloneDX, gitleaks, signed images; `/device-security/sdlc`; package signatures | `#/security/devices/assurance` | IT-SE5, IT-SE10, UT-SCAN | Partially | DAST not in CI (ZAP script only); threat-model documents to be written (records only) |
| E34-HLR-07 | MDS2 / SBOM gate; legacy PCs isolated with compensating controls | E32 `PosturePolicy`; `compensating_control`; outbound-only agent pattern | Catalogue, assurance | UT-POS, IT-CO9, IT-SE10 | Implemented | |
| E34-HLR-08 | Tamper-evident audit, SIEM, PHI masking | `SecurityEventService`, `SecurityEventChain`; outbox `siem-device-security`; `/events/verify`; `TraceMasker` | `#/security/devices/events` | IT-SE7, UT-CHAIN, UT-MASK | Implemented | SIEM parser acceptance by Security Operations pending |
| E34-HLR-09 | Anomaly rules, alerting, isolation playbook, re-certification | `AnomalyEngine`, `TrafficMonitor`, `AlertService`, `IncidentService`, `DeviceSecurityJobs` (30 s sweep) | `#/security/devices/alerts` | IT-SE4, IT-SE8, IT-X2, UT-ANOM, IT-CO7 | Implemented | Isolation at identity level + gateway quarantine (no switch / VLAN automation) |
| E34-HLR-10 | Privacy by design: minimisation, purpose binding, wearable consent, retention, pseudonymised telemetry, DPIA | BLE address hashing, minimised worklist, consent gates, retention classes and jobs, PHI-free labels, `/device-security/privacy/dpia` | – | IT-CA10, IT-EN3, UT-MASK, IT-SE10 | Implemented – needs sign-off | DPIA approval and retention classes (DPO) |
| E34-HLR-11 | Security test pack (TLS / mTLS, segmentation, MLLP / FHIR robustness, hardening, BLE, SBOM, secrets, audit, …) | `tools/device-security/` (15 scripts + report builder, `run-all.sh`), test plan, evidence template, control mapping; `/device-security/test-pack/runs` | Assurance | IT-SE10, PACK | Implemented | Delivered; execution in staging is E34-BL-010 |
| E34-HLR-12 | Independent pen test before go-live and annually; IEC 80001-1 agreement | `/device-security/pentest/findings`, `/responsibility-agreement`, `/go-live-readiness` | Assurance | IT-SE10 | Partially | Pen test not commissioned; agreement not signed |

### 7.2 Features

| ID | Feature | Implementation | UI | Verification | Status | Notes |
|---|---|---|---|---|---|---|
| E34-F01 | Endpoint identity & PKI | As E34-HLR-01; zero-touch enrolment; rotation with 15-min grace; ≤ 90-day tokens | `#/security/devices` | IT-SE1, IT-SE2, UT-CRY | Implemented | EST / SCEP outside the platform |
| E34-F02 | Zoning & policy templates | As E34-HLR-02; CSV / JSON export | Zones screen | IT-SE3 | Implemented – needs sign-off | Network / Security approval |
| E34-F03 | Hardened gateway & Device Host images | As E34-HLR-03 | Status page | GW-INT | Partially | Images not built |
| E34-F04 | Secrets & key management | As E34-HLR-05; signing keys encrypted | Secrets, keys screens | IT-SE5, IT-SE6 | Implemented | |
| E34-F05 | SDLC & SBOM | As E34-HLR-06 | Assurance | IT-SE10 | Partially | DAST, threat models |
| E34-F06 | Vendor intake & compensating controls | As E34-HLR-07 | Assurance | UT-POS, IT-SE10 | Implemented | |
| E34-F07 | Tamper-evident audit & SIEM | As E34-HLR-08 | Events screen | IT-SE7, UT-CHAIN | Implemented | |
| E34-F08 | Anomaly detection & playbook | As E34-HLR-09 | Alerts / incidents | IT-SE4, IT-SE8 | Implemented | |
| E34-F09 | Privacy controls | As E34-HLR-10 | – | IT-CA10, IT-SE10 | Implemented – needs sign-off | DPIA |
| E34-F10 | Security test pack | As E34-HLR-11 | Assurance | PACK, IT-SE10 | Implemented | |
| E34-F11 | Pen test & IEC 80001-1 | As E34-HLR-12 | Assurance | IT-SE10 | Partially | External activities |

### 7.3 Implementation backlog

| ID | Story / task (short) | Implementation | Verification | Status | Notes |
|---|---|---|---|---|---|
| E34-BL-001 | Group PKI enrolment, rotation, revocation; mTLS in MLLP / FHIR / API; plaintext refused; exception register | As E34-HLR-01; TLS listeners at the gateway; plaintext exceptions reported as hardening findings | IT-SE1, IT-SE4, GW-INT, UT-FILT | Implemented | Revocation effective on the next request (no cache) |
| E34-BL-002 | Reference network design; validated in the first two sites | Zones, conduits, CIDRs, firewall template export; documented in Deployment Architecture §22 | IT-SE3 | Partially | Design approval and two-site validation outstanding |
| E34-BL-003 | Hardened Linux gateway and Windows Device Host images (CIS, encryption, EDR, logging, USB allow-list, BLE policy, drift detection) | Gateway self-check, systemd / WinSW, `Dockerfile.gateway`; test scripts 05 / 05b | GW-INT, PACK | Partially | Images not built; drift reported by the gateway, not by an image pipeline |
| E34-BL-004 | Vault / KMS for engine and connector secrets; rotation; audit; secret scanning in CI | `SecretStoreService`, `SecretScanner`, gitleaks job | IT-SE6, UT-SCAN | Implemented | |
| E34-BL-005 | SDLC: threat models, SAST / DAST / SCA, signed releases with SBOM, advisories, SLAs | CI SAST / SCA / SBOM / signing; `/device-security/sdlc` (due dates 7 / 30 days) | IT-SE10, UT-SCAN | Partially | DAST and threat-model documents outstanding; SLA dashboard = assurance screen |
| E34-BL-006 | MDS2 / SBOM intake, posture policy, isolation of legacy PCs, exceptions, contract clauses | E32 posture + E34 compensating controls | UT-POS, IT-CO9, IT-SE10 | Implemented – needs sign-off | Contract clause template to be supplied by Procurement / Legal |
| E34-BL-007 | Tamper-evident store, SIEM forwarding, PHI masking in traces | As E34-HLR-08 | IT-SE7, UT-CHAIN, UT-MASK | Implemented | |
| E34-BL-008 | Anomaly alerts, one-click isolation, drill < 15 min, re-certification gate | As E34-HLR-09 | IT-SE4, IT-X2, UT-ANOM | Implemented | Drill measured in the integration test |
| E34-BL-009 | DPIA update; minimisation, retention, consent for wearables | As E34-HLR-10 | IT-CA10, IT-SE10 | Implemented – needs sign-off | DPO approval |
| E34-BL-010 | Deliver the test pack and run it in staging with the Cyber team | Pack delivered | PACK | Partially | **Staging run not yet done** |
| E34-BL-011 | Automate the pack in CI / CD and schedule quarterly runs with evidence archival | `run-all.sh`, `15_report_builder.mjs --submit`, `/device-security/test-pack/runs` | IT-SE10 | Partially | No CI stage or schedule yet |
| E34-BL-012 | Independent penetration test before go-live; findings to closure | Findings register, go-live gate | IT-SE10 | Partially | Not commissioned |
| E34-BL-013 | IEC 80001-1 responsibility agreement | Agreement record | IT-SE10 | Partially | Not signed |
| E34-BL-014 | Zero-touch enrolment with hardware-backed identity; hardening status in the fleet | `EnrolmentService`, gateway `EnrolmentClient`, heartbeat hardening; endpoint `attested` flag | GW-INT, IT-SE1 | Partially | TPM attestation recorded as present, **not verified**; keys from `WZ_MASTER_KEY` / key file, not hardware-sealed by the agent |

## 8. Cross-cutting requirements of increment 6

| Requirement (contract / brief) | Implementation | Verification | Status |
|---|---|---|---|
| Device console roles never see person data | `/me` disclosure `NONE`; masked traces; counts only | UT-MASK, IT-SE1, WEB-DEV | Implemented |
| Person tokens refused on device paths; device tokens refused elsewhere | Separate filter chain; `DeviceEndpointPrincipal` | UT-FILT, IT-EN2, IT-SE1 | Implemented |
| Restricted categories visible to collector (own session) and MRO only | `VerificationRule`, E31 read paths | UT-VR, IT-CA5 | Implemented |
| Values immutable once captured | No edit endpoint; re-measure replaces the set | IT-CA3 | Implemented |
| Content-free notifications (13 `device.*` templates) | `NotificationTemplates` content rules | Notification rules test | Implemented |
| No PHI in logs, metric labels, security events | PHI-free labels, allow-listed event attributes | UT-CHAIN, GW-INT (`statusPageIsServedOnLocalhostWithoutPhi`) | Implemented |
| Module independence | 28 ArchUnit rules | ARCH | Implemented |
| Module flags `modules.e31..e34.enabled` (503 `module_disabled`) | `requireEnabled` in all four modules | IT-CA12, IT-SE11 | Implemented |
| Synthetic data only | Golden datasets, demo seeders (local / test) | UT-CONF | Implemented |

## 9. Follow-up register for items not fully implemented

| Item(s) | Action | Owner |
|---|---|---|
| E31-HLR-06, E31-F04, E31-F06, E31-BL-013, E31-BL-017, E33-HLR-03, E33-F03, E33-F04, E33-BL-004, E33-BL-005 | Verify GLI-2012 coefficients / approximations and the OSHA female table; approve plausibility ranges, gate rule set and STS rule sets; set `gate_signoff` to APPROVED | CMO, Nursing Head, Clinical Governance |
| E31-BL-001 | Approve ADR-28…ADR-35 | Architecture board |
| E31-HLR-02, E31-F02, E31-BL-003…-010, E31-BL-012 | Set up the device lab (one unit per certified family); validate packages against real devices and vendor exports; add native serial / USB, DICOM C-STORE / MWL, OAuth2 polling and barcode as follow-on stories | Integration lead, procurement |
| E31-HLR-08, E31-F08, E31-BL-016, E31-BL-019 | HL7 ORM / ADT and DICOM MWL worklist push; start-from-form where vendors support it | Integration lead |
| E31-HLR-09, E31-F09, E31-BL-020, E33-HLR-07, E33-F10, E33-BL-012 | Native tablet capture (BLE radio, encrypted on-device queue) in the mobile app; 48-hour offline soak test | Mobile team, QA |
| E31-HLR-10, E31-HLR-13, E31-BL-002, E31-BL-021, E31-BL-024 | Write `DevicePipelinePerformanceTest`, add a CI performance stage, run a 2× peak soak and document the capacity model | Platform team, QA |
| E31-HLR-12, E31-F12, E31-BL-023 | Tenant-scoped catalogue and keys, onboarding template (with E20–E25) | Platform team |
| E31-BL-011 | SDK command line (scaffold, test, sign, publish) | Integration team |
| E32-HLR-11, E33-HLR-11, E33-BL-016 | WCAG 2.2 AA audit; console localisation; time-and-motion study in two clinics | UX lead, Nursing Head |
| E32-BL-003, E33-BL-001 | Usability tests (3 administrators; nurses) | UX lead |
| E32-BL-004 | Review of the option packs by integration and security | Integration lead, CISO |
| E32-BL-016 | Videos and one-page job aids per option | Change & Training |
| E32-BL-017 | Accept the E14 migration data-quality report | Biomedical engineering |
| E33-HLR-05, E33-F08 | Confirm the decision on instrumented drug-screen readers (MRO, Legal) | MRO, Legal |
| E33-HLR-06, E33-F09, E33-BL-011 | Decide where the respirator clearance record lives (E07 programme or new register) | Respirator programme administrator, Product Owner |
| E34-HLR-02, E34-F02, E34-BL-002 | Approve the reference network design; validate in two sites; **configure production zone CIDRs** | Network / Security, SECURITY_OPS |
| E34-HLR-03, E34-F03, E34-BL-003 | Build CIS golden images (Debian / Ubuntu Core gateway, Windows Device Host) with disk encryption, secure boot, EDR, patching | Infrastructure, Security |
| E34-HLR-04 | OS-level BLE pairing policy and USB device-class / VID-PID policy in the images; authenticated serial servers | Infrastructure, Security |
| E34-HLR-06, E34-F05, E34-BL-005 | DAST in CI; threat-model documents per adapter and deployment option | DevSecOps, Security |
| E34-HLR-10, E34-F09, E34-BL-009 | Approve the DPIA and the five retention classes | DPO, CMO |
| E34-BL-006 | Contract clause template for device purchases | Procurement, Legal |
| E34-BL-010, E34-BL-011 | Run the test pack in staging with the Cyber team; add the CI stage and quarterly schedule | CISO, DevSecOps |
| E34-HLR-12, E34-F11, E34-BL-012, E34-BL-013 | Commission the penetration test; sign the IEC 80001-1 agreement | CISO, OH, group IT, vendors |
| E34-BL-014 | TPM-backed identity and attestation verification | Security, infrastructure |
