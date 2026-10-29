# WellnessZ OHCM – Increment 2 Requirements Traceability Matrix (E03 + E05)

| | |
|---|---|
| **Document** | Increment 2 – 02 Requirements Traceability Matrix |
| **Sources** | E03 and E05 requirement workbooks, v1.0 – Draft for CMO & Nursing Head review (27 October 2026): sheets *High-Level Requirements*, *Feature List*, *Implementation Backlog* |
| **Audience** | CMO, Nursing Head, DPO, Cyber GRC, CIO, project sponsor |
| **Companion documents** | 01 – Solution Design E03/E05 · 03 – Release Notes Increment 2 · API contract `API-Contract-E03-E05.md` |

> **Method.** Every row was checked against the code: the endpoint, class or screen named in *Implementation* was
> located in the repository, and the *Verification* column names the automated test, end-to-end (E2E) check or
> screenshot that exercises it. Where an acceptance criterion is only partly met – including criteria that require a
> business sign-off, a licence, a usability test or a pilot that has not yet happened – the item is marked **Partially
> implemented** and the gap is stated. A high-level requirement is marked partial only when the requirement text itself
> names content that is still a placeholder; content gaps are otherwise reported on the backlog item. All API paths are
> relative to `/api/v1`.

## 1. Coverage summary

| Level | Items | Implemented | Partially implemented | Deferred |
|---|---|---|---|---|
| E03 high-level requirements | 8 | 5 | 3 | 0 |
| E03 features | 8 | 4 | 4 | 0 |
| E03 backlog items | 22 | 12 | 10 | 0 |
| E05 high-level requirements | 8 | 8 | 0 | 0 |
| E05 features | 7 | 7 | 0 | 0 |
| E05 backlog items | 20 | 13 | 6 | 1 |
| **Total** | **73** | **49 (67 %)** | **23 (32 %)** | **1 (1 %)** |

Of the 23 partially implemented items, **8 are essentially complete in code and are gated mainly by a business
activity** – clinical / DPO sign-off, a licence or a usability test (E03-HLR-03, E03-BL-002, E03-BL-004, E03-BL-013,
E05-BL-001, E05-BL-003, E05-BL-004, E05-BL-011). The others lack a specific sub-capability, listed in the *Status*
column – mainly OCR of image-only scans, device drivers, the AI provider and pilot, terminology server and licences,
retention triggers, and screens for the hygiene manager. The one deferred item is a training task (E05-BL-019).

Backlog by priority (workbook *Priority* column): of the 28 P1 (critical) backlog items – 13 in E03, 15 in E05 – all
have a working implementation; 19 are fully implemented and 9 partially (E03-BL-001, -002, -004, -008; E05-BL-001, -003,
-004, -011, -015).

## 2. Verification legend

| Ref | Verification artefact |
|---|---|
| IT-P1 | `Increment2PlatformIntegrationTest.certificateIsGeneratedSignedAndSharedWithoutClinicalContent` (PostgreSQL 16) |
| IT-P2 | `Increment2PlatformIntegrationTest.partnerSharingNeedsConsentAndStopsWhenConsentIsWithdrawn` |
| IT-P3 | `Increment2PlatformIntegrationTest.scannedReportsAreClassifiedAndSearchableWithoutAPlaintextIndex` |
| IT-P4 | `Increment2PlatformIntegrationTest.breakGlassOpensARestrictedRecordForFourHoursAndIsReviewedByTheDpo` |
| IT-P5 | `Increment2PlatformIntegrationTest.masterDataStandardsAreVersionedAndRetentionIsApprovedByTheDpo` |
| IT-R1 | `Increment2RecordIntegrationTest.f01ChartSummaryJobsOdhAndFhirForDemoEmployee` |
| IT-R2 | `Increment2RecordIntegrationTest.f01ProblemsAllergiesMedicationsImmunisationsAndJobsWithCds` |
| IT-R3 | `Increment2RecordIntegrationTest.f02InjuryEncounterWithBodyMapSignatureAddendumAndCaseDiagnosis` |
| IT-R4 | `Increment2RecordIntegrationTest.f02UnsignedRemindersEnteredInErrorPhotosAndTemplateApproval` |
| IT-R5 | `Increment2RecordIntegrationTest.f02MentalHealthEncountersAreRestrictedAndBreakGlassOpensThem` |
| IT-R6 | `Increment2RecordIntegrationTest.f03TerminologySearchFavouritesRecentMappingsAndReleases` |
| IT-R7 | `Increment2RecordIntegrationTest.f04LabOrderPullQueueResultsWorklistAcknowledgementAndLetter` |
| IT-R8 | `Increment2RecordIntegrationTest.f04DeviceCaptureAudiometryStsSpirometryVisionEcg` |
| IT-R9 | `Increment2RecordIntegrationTest.f05EncounterDecisionSupportFitnessStandardSurveillanceAndDuration` |
| IT-R10 | `Increment2RecordIntegrationTest.f06ResultLetterIsAClinicalDocumentNotShareableWithManagers` |
| IT-R11 | `Increment2RecordIntegrationTest.f07AiAssistantRequiresConsentAndAConfiguredProviderAndIsAudited` |
| IT-R12 | `Increment2RecordIntegrationTest.f08HrManagersEmployeesAndReceptionNeverOpenClinicalRecords` |
| IT-F1 | `Increment2FitnessIntegrationTest.f01FitnessDecisionsSignoffCertificateAndDurationComparison` |
| IT-F2 | `Increment2FitnessIntegrationTest.f02MatchingProposalsManagerDecisionAndEscalationToHr` |
| IT-F3 | `Increment2FitnessIntegrationTest.f03RtwPlanAcknowledgementsReviewsAndJobOffer` |
| IT-F4 | `Increment2FitnessIntegrationTest.f04CrewDeclarationAssessmentRosterFeedReconciliationAndDashboard` |
| IT-F5 | `Increment2FitnessIntegrationTest.f05FoodHandlerExclusionHygieneStatusOnlyAndClearance` |
| IT-F6 | `Increment2FitnessIntegrationTest.f06RestrictionExpiryAlertsLapsedListExtendCloseAndTeamView` |
| IT-F7 | `Increment2FitnessIntegrationTest.f07IllHealthRetirementCmoDecisionAppealWindowAndIndependentAppeal` |
| ARCH | `ArchitectureTest` – incl. `documentServiceIsAPlatformService`, `recordAndFitnessModulesAreIndependent`, `fitnessDoesNotDependOnRecord`, `earlierModulesDoNotDependOnLaterOnes` |
| UT-ENC | `EncounterRulesTest` (8 versioned DRAFT templates with SOAP section, mandatory fields, default fitness standard, SHA-256 hex) |
| UT-TERM | `TerminologyCatalogTest` (prefix tsquery of letters / digits only, code-prefix detection) |
| UT-HL7 | `Hl7MessagesTest` (ORM^O01 one order per test with escaping, ORU^R01 extraction, other types rejected) |
| UT-DEV | `DeviceRulesTest` (baseline, STS at 2/3/4 kHz, threshold validation, spirometry, vision, ECG) |
| UT-FLAG | `ResultFlagsTest` (interpretation codes, reference ranges, non-numeric values) |
| UT-CDS | `CdsServiceTest` (drug-class matching on whole words, tokenisation) |
| UT-AI | `AiAssistantServiceTest` (consent then configured provider; draft not saved and audited) |
| UT-DEC | `DecisionRulesTest` (validity defaults and over-40 rule, unknown age, outcome → kernel status, roster mapping and operational codes only, restriction-until, expiry status) |
| UT-MATCH | `MatchingEngineTest` (numeric and boolean conflicts, ranking) |
| UT-CREW | `CrewDashboardTest` (counts, durations, small-cell suppression, p95 nearest rank) |
| UT-CTD | `ClinicalTextDetectorTest` (platform/wz-common; vocabulary extended in Increment 2) |
| UT-BFF | `ApiProxyPathTest.increment2WorkspaceResourcesKeepTheirPrefixWhileLegacyAliasesAreStripped` |
| WEB-OH | `web/oh-workspace` vitest (305 tests): `ChartScreen`, `RecordLists`, `EntryPoints`, `Encounter`, `Results`, `Documents`, `E05Tabs`, `WorkScreens`, `Admin`, `Dpo`, `App`, `roles`, `increment2` |
| WEB-EMP | `web/employee-app` vitest (138 tests): `portal`, `MyDocuments`, `MyReturnToWork`, `JobOffers`, `Privacy`, `FitnessDeclaration`, `i18n/portal` |
| WEB-MGR | `web/manager-workspace` vitest (99 tests): `TeamRestrictions`, `DutyProposals`, `RtwPlans`, `Documents`, `JobOffers`, `IhrAssessments`, `statusOnly`, `roles` |
| E2E-nn | `tools/e2e/e2e-increment2.js` check nn (list below), real Keycloak + BFF + core + three web apps – all 25 PASS |
| SCR `name` | Screenshot `docs/images/screens/increment-02/name.png` captured by the E2E run |

E2E checks, in order: 01 employee self-referral creates the case · 02 chart summary for the ramp agent (allergy alert,
surveillance, counts) · 03 CDS blocks penicillin-class medication for a penicillin allergy · 04 terminology search ·
05 injury encounter with body map, coded diagnosis and e-signature · 06 device audiogram flags a standard threshold shift
· 07 fitness decision against the food-handler standard · 08 certificate signed and shared with manager and employee ·
09 job-demand matching suggests compatible duties · 10 modified duty proposed and RTW plan activated · 11 manager sees
functional restrictions only · 12 manager accepts the modified duty · 13 manager acknowledges the RTW plan · 14 manager
cannot open the clinical chart · 15 HR issues an electronic job offer · 16 employee acknowledges the certificate ·
17 employee agrees to the RTW plan · 18 employee accepts the job offer · 19 employee sees who received the certificate ·
20 AME crew assessment with limitation codes and medical report · 21 crew scheduling receives status, dates and
operational limitations only · 22 psychological self-referral in the restricted class · 23 break-glass reveals the
restricted case for a limited time · 24 DPO reviews the break-glass use · 25 DPO has no clinical access.

---

## 3. E03 – Occupational Health Record & Clinical Encounter Documentation

### 3.1 High-level requirements

| ID | Short title | Implementation | Verification | Status |
|---|---|---|---|---|
| E03-HLR-01 | Longitudinal OH record (HRIS demographics, ODH job / exposure history, problems, allergies, medications, immunisations, results, restrictions, documents) | `ChartService`, `RecordService`, `JobHistoryService`, `ChartController` (`/employees/{id}/chart`, `/problems`, `/allergies`, `/medications`, `/immunisations`, `/jobs`, `/record-timeline`); E05 `FitnessChartContributor`; schema `ohrecord` (V7) | IT-R1, IT-R2, E2E-02, WEB-OH (`ChartScreen`, `RecordLists`), SCR `inc2-oh-01-chart-summary`, `inc2-oh-02-chart-jobs-odh` | Implemented |
| E03-HLR-02 | Structured encounter templates (8 types) with coded fields, body map, free text, clinician e-signature | `EncounterTemplates` + 8 JSON templates, `ClinicalEncounterService`, `ClinicalEncounterController` (`/encounters/{id}`, `/sign`, `/addenda`) | IT-R3, IT-R4, UT-ENC, E2E-05, WEB-OH (`Encounter`), SCR `inc2-oh-04-encounter-signed` | Implemented (template content awaits CMO approval – see E03-BL-004) |
| E03-HLR-03 | Coding with SNOMED CT, ICD-10/11, LOINC with search and favourites | `TerminologyController` (`/terminology/search`, `/favourites`, `/recent`, `/mappings`, `/concepts/import`, `/releases`), `TerminologyCatalog`, `platform.code_concept`, `platform.terminology_release` | IT-R6, UT-TERM, E2E-04 | Partially implemented – search, favourites, recent, mapping and import complete; only a starter subset (≈ 120 ICD-10, 50 SNOMED CT, 40 LOINC) is loaded; licensed national editions await the licence decision; no ICD-11 content |
| E03-HLR-04 | Orders and results for laboratory, imaging and devices with abnormal flags and acknowledgement | `OrderService`, `Hl7Messages`, `ResultService`, `OruResultListener`, `DeviceRules`, `ResultFlags`, `ResultEscalationJob`, `OrderResultController` | IT-R7, IT-R8, UT-HL7, UT-DEV, UT-FLAG, E2E-06, SCR `inc2-oh-03-chart-results`, `inc2-oh-05-results-worklist` | Implemented (partner LIS and device gateways not yet connected – E15) |
| E03-HLR-05 | CDS: allergy / medication alerts, protocol prompts, duration guidelines, surveillance reminders, fitness-standard references | `CdsService`, `EncounterCds`, `CdsController` (`/cds/statistics`); `ohrecord.drug_class`, `cds_rule`, `cds_alert_log` | IT-R2, IT-R7, IT-R9, UT-CDS, E2E-03 | Implemented (drug tables are curated starter content – see E03-BL-013) |
| E03-HLR-06 | Templated documents with versioning and e-signature; scanned documents with OCR indexing | `platform/wz-documents`: `DocumentService`, `DocumentTemplates` (10 templates), `PdfRenderer`, `OcrProvider`, `TesseractOcrProvider`, `DocumentController` | IT-P1, IT-P3, IT-R10, E2E-08, SCR `inc2-oh-10-case-documents` | Partially implemented – generation, versioning, signature and text-layer indexing complete; image-only scanned PDFs are not OCR'd and image OCR needs Tesseract to be installed |
| E03-HLR-07 | AI-assisted documentation only with consent, clinician sign-off and audit | `AiAssistantService`, `AiDocumentationProvider` SPI, `/encounters/{id}/ai-consent`, `/ai-draft`, `aiAssisted` flag; `CaseSummaryService` (rule-based) | IT-R11, UT-AI, WEB-OH (`Encounter`) | Partially implemented – consent, draft-only, flag and audit enforced; no provider is enabled, no audio capture (E03-BL-019) |
| E03-HLR-08 | OH record separated from HR and primary care; HR gets outcomes only; partners only with consent | `EmployeeRecordAccess`, schemas `ohrecord` / `docs` / `fitness`, status-only `/workspace/*` views, document share rules, `BreakGlassService`, `DisclosureLogService`, `DocumentConsentListener` | IT-R12, IT-R5, IT-P2, IT-P4, E2E-14, E2E-22..25 | Implemented |

### 3.2 Features

| ID | Feature | Implementation | Verification | Status |
|---|---|---|---|---|
| E03-F01 | Employee OH chart (summary, tabs, ODH) – loads < 2 s, shows fitness and next surveillance due, ODH history | As E03-HLR-01; OH Workspace `#/employees/{id}/chart/{tab}` | IT-R1 (asserts < 2 s, surveillance due, ODH), E2E-02, SCR `inc2-oh-01-chart-summary` | Implemented |
| E03-F02 | Encounter templates and body map – MSK injury with body map signed in < 6 min; unsigned reminder list; crew template per local CAA format | `ClinicalEncounterService`, `/encounters/unsigned`, OH Workspace encounter editor and *Unsigned notes* | IT-R3, IT-R4, E2E-05, SCR `inc2-oh-04-encounter-signed`, `inc2-oh-19-encounter-templates` | Partially implemented – all functions present; the < 6 min documentation time has not been measured; crew aeromedical template not yet aligned to the local CAA format; templates DRAFT pending CMO approval |
| E03-F03 | Terminology services – term in ≤ 3 keystrokes via favourites; every closed case ≥ 1 coded diagnosis | As E03-HLR-03; `DiagnosisRequiredGuard` (kernel) | IT-R6, IT-R3 (`diagnosis_required` then closure succeeds) | Implemented (licensed content – see E03-BL-008) |
| E03-F04 | Orders, results and device integration – audiometry imported from device with calibration ID; abnormal shift flagged and acknowledged by physician | As E03-HLR-04; device capture `POST /employees/{id}/results/device` | IT-R8 (calibration required, STS flagged, physician acknowledgement), E2E-06 | Partially implemented – device-gateway API contract tested end to end; no device-side adapters (serial / USB / vendor) yet (E03-BL-011) |
| E03-F05 | CDS – fires on triggers, override reasons captured, alert fatigue monitored (< 10 % override without reason) | As E03-HLR-05; override reason mandatory per alert; `/cds/statistics` | IT-R2 (`overriddenWithoutReason` = 0, blank reason 400), IT-R9 | Implemented |
| E03-F06 | Document generation and management – certificate in one click, signed, stored, shared to portal; external report scanned and indexed | As E03-HLR-06; certificate generated automatically by the E05 decision | IT-P1, IT-P3, IT-F1, E2E-07, E2E-08, E2E-16 | Partially implemented – see E03-HLR-06 (OCR of image-only scans) |
| E03-F07 | AI documentation assistant – draft within 60 s, clinician edits and signs, AI usage logged | As E03-HLR-07 | IT-R11, UT-AI | Partially implemented – framework only; no provider, so the 60 s target cannot be measured |
| E03-F08 | Record segregation and disclosure control – HR cannot open clinical notes; hospital disclosure needs consent; break-glass alerts | As E03-HLR-08 | IT-R12, IT-P2, IT-P4, IT-R5, E2E-14, E2E-23..25, SCR `inc2-oh-14-chart-restricted-hidden`, `inc2-dpo-01-break-glass-reviews` | Implemented |

### 3.3 Implementation backlog

| ID | Title (short) | Implementation | Verification | Status |
|---|---|---|---|---|
| E03-BL-001 | OH clinical data model aligned to FHIR R4 and ODH; FHIR mapping documented | Schema `ohrecord` (V7); `FhirExportService` (`/employees/{id}/fhir`: Patient, Condition, AllergyIntolerance, MedicationStatement, Immunization, Observation, DocumentReference, Encounter; `/odh`); mapping documented in Solution Design §7, §27 | IT-R1 (both bundles parsed as valid R4) | Partially implemented – Procedure, Consent, Task and ServiceRequest not mapped; review by clinical SME and integration lead not yet recorded |
| E03-BL-002 | Chart summary: alerts, fitness, restrictions, open cases, surveillance due; < 2 s; masking; usability ≥ 4/5 | `ChartService`, `FitnessChartContributor`, `restrictedItemsHidden`; OH Workspace chart summary | IT-R1 (< 2 s), IT-R5 (masking), E2E-02, SCR `inc2-oh-01-chart-summary` | Partially implemented – usability test (≥ 4/5) not run |
| E03-BL-003 | Job and exposure history from HRIS and job catalogue in ODH structure; manual additions; ODH export | `JobHistoryService` (HRIS sync, close previous entry on job change, hazards from job catalogue), `POST/PUT /jobs`, `/employees/{id}/odh` | IT-R1, IT-R2 (manual entry, invalid hazard 400, HRIS job change), SCR `inc2-oh-02-chart-jobs-odh` | Implemented |
| E03-BL-004 | Author eight encounter templates approved by CMO; mandatory fields; coded pick-lists | 8 JSON templates (`encounter-templates/`), `ohrecord.encounter_template` status, `POST /encounter-templates/{type}/approve` (CMO) | UT-ENC, IT-R3, IT-R4 (approval by CMO only), SCR `inc2-oh-19-encounter-templates` | Partially implemented – templates delivered as DRAFT with mandatory fields and option lists; CMO approval pending (sign-off S1) |
| E03-BL-005 | Body map front / back, multi-site, mechanism pick-list, stored as coded observations | Body map in `PUT /encounters/{id}` → `ohrecord.encounter_observation` (site, laterality, view, mechanism, code system `WZ_BODY_MAP`); `GET /body-map/mechanisms` (12) | IT-R3 (coded observation row asserted), E2E-05, WEB-OH (`Encounter`) | Implemented (local code system; SNOMED CT body-structure mapping later) |
| E03-BL-006 | E-sign notes with timestamp / user; locked after signing; addenda; unsigned reminders | `ClinicalEncounterService.sign` (SHA-256 over canonical content), `/addenda` (hashed), `encounter_locked`, `/encounters/unsigned`, `/entered-in-error` | IT-R3, IT-R4, UT-ENC, E2E-05 | Implemented (reminders are an on-screen list, no e-mail) |
| E03-BL-007 | Photo capture with consent; thumbnails in timeline; retention policy | `POST /encounters/{id}/photos` (consent required, JPEG / PNG ≤ 10 MB) → `DocumentService.storePhoto` (PHOTO, never shareable); retention class PHOTO | IT-R4 | Partially implemented – upload with consent works; no thumbnail rendering (photo listed as link on the encounter and as a document entry in the record timeline); PHOTO retention value is a proposal |
| E03-BL-008 | Terminology server integration with licence management and update process; search < 300 ms; update runbook | In-database concept store with GIN index; `/terminology/concepts/import`; release register with licence text | IT-R6 (search asserted < 1 s, import, release register) | Partially implemented – no external terminology server; licences not yet obtained (starter subset); update runbook not written; 300 ms not formally load-tested |
| E03-BL-009 | Specialty favourites and recent codes; coding completion ≥ 98 % at closure | Role and user favourites (`ohrecord.term_favourite`), recent (`term_usage`), `DiagnosisRequiredGuard` (100 % for clinical case types) | IT-R6, IT-R3, WEB-OH (`ChartScreen` terminology picker) | Implemented |
| E03-BL-010 | Lab orders out and results in (HL7 v2 ORU or FHIR); match to order; abnormal flag | `OrderService` (ORM^O01 queue), `/integration/lis/orders` + `/ack`, `/integration/lis/results`, `/integration/hl7v2` → `OruResultListener` | IT-R7 (ORM parsed, ack, JSON and ORU results, order match, wrong-employee 422, flags), UT-HL7 | Implemented (partner laboratory onboarding pending – E15) |
| E03-BL-011 | Device interface for ≥ 3 devices; calibration ID stored; manual fallback | `POST /employees/{id}/results/device` (INTEGRATION or OH roles) for audiometry, spirometry, vision, ECG; calibration required with device ID; MANUAL source | IT-R8, UT-DEV | Partially implemented – four device types supported through a gateway API contract; no serial / USB / vendor adapters built or tested with real devices |
| E03-BL-012 | Worklist of unacknowledged abnormal results; acknowledge with action; overdue escalation to CMO; audit | `/results/worklist`, `/results/{id}/acknowledge`, `ResultEscalationJob` (> 72 h, once, content-free `result.overdue`) | IT-R7 (escalated once, CMO overdue filter), IT-R8, E2E-06, SCR `inc2-oh-05-results-worklist` | Implemented |
| E03-BL-013 | Allergy and medication alerts with drug / allergy database; override reasons; statistics | `CdsService`, `ohrecord.drug_class` (≈ 55 names), `cds_rule` (1 cross-reactivity + 12 interactions), `cds_alert_log`, `/cds/statistics` | IT-R2, UT-CDS, E2E-03 | Partially implemented – engine, overrides and statistics complete; curated starter tables marked "pharmacist review pending"; no licensed drug knowledge base (sign-off S8) |
| E03-BL-014 | Applicable fitness standard linked from the encounter; version; shown in fitness template | `EncounterCds` FITNESS_STANDARD prompt, `fitnessStandardCode` on the encounter, default by template and safety category | IT-R9, UT-ENC (`defaultFitnessStandardFollowsTemplateAndSafetyCategory`) | Implemented |
| E03-BL-015 | ≥ 6 templates; merge fields; PDF; signature; share to portal; version history | `DocumentTemplates` (10), `PdfRenderer`, `/documents/generate`, `/sign`, `/new-version`, `/share`, `/me/documents` | IT-P1, IT-P2, E2E-08, E2E-16, WEB-EMP (`MyDocuments`), SCR `inc2-emp-01-documents` | Implemented (wording awaits DPO / CMO approval – S5) |
| E03-BL-016 | Scan / upload external reports; OCR text index; type classification; link to case | `POST /documents/scan` (magic bytes, ≤ 10 MB, `caseId`), `PdfRenderer.extractText`, `OcrProvider` / `TesseractOcrProvider`, keyword classification, blind-index search | IT-P3 (classified as discharge summary, searchable, no plaintext index) | Partially implemented – text-layer PDFs indexed; image-only PDFs not OCR'd; image OCR only when Tesseract is configured; classification is keyword-based |
| E03-BL-017 | Retention table approved by DPO / CMO; automated flagging; legal hold | `docs.retention_schedule`, `RetentionService` (daily flag, `/documents/retention/due`), DPO-only update, `/legal-hold` | IT-P5 (DPO approval recorded, legal-hold document excluded), SCR `inc2-dpo-03-retention` | Partially implemented – values are proposals awaiting DPO / CMO approval; only the document-date trigger is evaluated (LAST_ENCOUNTER, EMPLOYMENT_END not); structured records not flagged |
| E03-BL-018 | Spike: evaluate AI assistant options against privacy, accuracy, clinician acceptance – report with pilot recommendation; DPIA draft | Solution Design §15 (options, DPIA draft, accuracy and acceptance criteria, 5-clinician pilot recommendation) | Document review | Implemented (DPIA to be completed and approved by the DPO – S11) |
| E03-BL-019 | AI-drafted note from consented recording: consent step, draft < 60 s, edit / sign, AI flag in audit, 5-clinician pilot | `AiAssistantService` (consent `AI_DOCUMENTATION`, draft-only, audit), `aiAssisted` flag, OH Workspace AI panel | IT-R11, UT-AI, WEB-OH (`Encounter`) | Partially implemented – consent, draft-only, flag and audit done; no provider enabled, no recording / transcription front-end, pilot not run |
| E03-BL-020 | HR restricted to fitness / restriction outcomes; role matrix; negative tests; audit of HR reads | `@PreAuthorize` on all record endpoints, `EmployeeRecordAccess` (404 + DENIED audit), status-only `/workspace/*`, BFF role gating | IT-R12, IT-P1, IT-F1..F7 (manager / HR negative assertions), E2E-11, E2E-14, WEB-MGR (`statusOnly`) | Implemented |
| E03-BL-021 | Break-glass with justification; 24-h review task to DPO; monthly report | `BreakGlassService` (4 h grant, reason ≥ 20, 24 h review due), `SecurityAlertListener` (`break-glass.alert`), `/break-glass`, `/review`, `/report` | IT-P4, IT-R5, E2E-23, E2E-24, SCR `inc2-dpo-01-break-glass-reviews` | Implemented |
| E03-BL-022 | Disclosure log in portal; consent withdrawal workflow; effect logged | `DisclosureLogService`, `/me/disclosures`, `/disclosures`, `ConsentService.withdraw` → `DocumentConsentListener` + `revokeByConsent` | IT-P2, IT-P1, E2E-19, WEB-EMP (`Privacy`), SCR `inc2-emp-04-privacy`, `inc2-dpo-02-disclosure-log` | Implemented |

---

## 4. E05 – Fitness for Duty, Work Restrictions & Return-to-Work

### 4.1 High-level requirements

| ID | Short title | Implementation | Verification | Status |
|---|---|---|---|---|
| E05-HLR-01 | Fitness decisions against role-specific standards with limitations, validity and review dates | `FitnessDecisionService`, `DecisionRules`, `FitnessDecisionController`; `masterdata.fitness_standard` (12 versioned standards) | IT-F1, IT-F4, IT-P5, UT-DEC, E2E-07, SCR `inc2-oh-07-case-fitness-decision`, `inc2-oh-17-admin-fitness-standards` | Implemented (standards content awaits CMO – E05-BL-001) |
| E05-HLR-02 | Restrictions catalogue matched against job-demand profiles to propose modified duties | `masterdata.restriction` (16), `masterdata.job_demand_profile` (28), `MatchingEngine`, `DutyService`, `/fitness/match`, `/duty-proposals` | IT-F2, UT-MATCH, E2E-09, E2E-10, SCR `inc2-oh-08-case-restrictions-duties` | Implemented (job-demand library incomplete – E05-BL-004) |
| E05-HLR-03 | RTW plans with phases, adjustments, reviews, employee agreement, manager acknowledgement, electronic job offers | `RtwService`, `JobOfferService`, `RtwController` | IT-F3, E2E-10, E2E-13, E2E-15, E2E-17, E2E-18 | Implemented |
| E05-HLR-04 | Crew: declarations, AME assessments, limitation codes, status-only roster feed | `TriggerService` (E01, next steps), `CrewService`, `LimitationCodes`, `RosterFeedLog`, `FitnessFeed`, `CrewStatusEnricher` | IT-F4, UT-DEC, E2E-20, E2E-21, SCR `inc2-oh-12-case-crew`, `inc2-oh-13-roster-feed` | Implemented (production rostering adapter – E15) |
| E05-HLR-05 | Food-handler exclusion / return criteria; status visible to hygiene management | `FoodHandlerService`, `FoodHandlerIhrController` (`/food-handler/exclude`, `/return-request`, `/clear`, `/fitness/food-handlers`) | IT-F5 | Implemented |
| E05-HLR-06 | Fitness outcomes to managers / HR via diagnosis-free certificates with employee visibility and acknowledgement | FITNESS_CERTIFICATE template (non-clinical, guarded), share rules, `/workspace/documents`, `/me/documents/{id}/acknowledge` | IT-P1, IT-F1, E2E-08, E2E-16, SCR `inc2-mgr-04-documents`, `inc2-emp-01-documents` | Implemented |
| E05-HLR-07 | Restriction expiry and review tracking with alerts to case owner, manager and employee | `RestrictionService.processExpiry`, `FitnessJobs`, `/restrictions/active`, `/extend`, `/close`, `/workspace/team-restrictions` | IT-F6, UT-DEC, E2E-11, SCR `inc2-oh-11-active-restrictions`, `inc2-mgr-01-team-restrictions` | Implemented |
| E05-HLR-08 | Ill-health retirement / redeployment and permanent unfitness with CMO sign-off and appeal | `IhrService`, `/ihr-assessments`, `/cmo-decision`, `/appeal`, `/appeal-decision`; permanent-unfitness sign-off in `FitnessDecisionService` | IT-F7, IT-F1 | Implemented |

### 4.2 Features

| ID | Feature | Implementation | Verification | Status |
|---|---|---|---|---|
| E05-F01 | Fitness decision module – standard reference and validity recorded; certificate generated; safety-critical case cannot close without decision | As E05-HLR-01; certificate draft generated per decision; Increment-1 `SafetyService` closure guard | IT-F1, E2E-07, E2E-08, SCR `inc2-oh-06-case-fitness-tab`, `inc2-oh-16-fitness-signoff` | Implemented |
| E05-F02 | Restrictions catalogue and job-demand matching – compatible duties suggested; manager accepts / declines with reason | As E05-HLR-02; manager workspace *Modified duty* | IT-F2, E2E-09, E2E-12, WEB-MGR (`DutyProposals`), SCR `inc2-mgr-02-duty-proposals`, `inc2-oh-20-admin-restriction-catalogue`, `inc2-oh-18-admin-job-demands` | Implemented |
| E05-F03 | RTW plan and electronic job offer – plan acknowledged by employee and manager; offer issued electronically; reviews tracked | As E05-HLR-03; OH *RTW plan* tab, manager *Return to work*, employee *My return to work* and *Job offers*, HR *Job offers* | IT-F3, E2E-10, E2E-13, E2E-15, E2E-17, E2E-18, SCR `inc2-oh-09-case-rtw-plan`, `inc2-mgr-03-rtw-plans`, `inc2-emp-02-rtw-plan`, `inc2-emp-03-job-offer`, `inc2-hr-01-job-offers` | Implemented |
| E05-F04 | Crew fitness and roster-status feed – status to rostering within 15 min; no clinical data; declaration acknowledged within SLA | As E05-HLR-04; reconciliation with p95 and 15-min flag; `acknowledgementDueBy` (+24 h) | IT-F4, E2E-20, E2E-21, SCR `inc2-oh-13-roster-feed`, `inc2-oh-15-crew-dashboard` | Implemented (15-min delivery to the real rostering system measurable once connected) |
| E05-F05 | Food-handler fitness control – GI case sets excluded; hygiene sees status only; return needs nurse clearance | As E05-HLR-05; periodic review via FOOD_HYGIENE surveillance (12 months) and FOOD_HANDLER standard validity | IT-F5 | Implemented |
| E05-F06 | Restriction expiry and review alerts – alert 5 days before expiry; expired listed; manager unit view (status only) | As E05-HLR-07; `/restrictions/dashboard` | IT-F6, E2E-11, SCR `inc2-oh-11-active-restrictions`, `inc2-mgr-01-team-restrictions` | Implemented |
| E05-F07 | Ill-health retirement / redeployment and appeals – CMO sign-off; appeal workflow with timelines | As E05-HLR-08 | IT-F7, WEB-MGR (`IhrAssessments`), WEB-OH (`E05Tabs`) | Implemented |

### 4.3 Implementation backlog

| ID | Title (short) | Implementation | Verification | Status |
|---|---|---|---|---|
| E05-BL-001 | Role fitness standards library with CMO (source, validity rules, versioned) | 12 standards (V6) with source, reference, validity, over-40 validity, sign-off role, limitation codes; versioned PUT (OH_ADMIN, CMO) | IT-P5 (new version on PUT, nurse 403), SCR `inc2-oh-17-admin-fitness-standards` | Partially implemented – library and versioning complete; content not yet compiled / approved with the CMO (S2) |
| E05-BL-002 | Record fitness decision with limitation codes, validity, standard link, sign-off rules for licensed roles | `FitnessDecisionService`, `/fitness-decisions`, `/signoff`, `/awaiting-signoff` | IT-F1 (licensed standard 403 for nurse / physician, limitation and value rules, validity default, CMO-only permanent unfitness), UT-DEC, E2E-07 | Implemented |
| E05-BL-003 | Diagnosis-free certificate; DPO-approved content; share to portal and manager; acknowledgement | FITNESS_CERTIFICATE template with clinical-text guard; auto-draft per decision; share rules; acknowledgement | IT-P1, IT-F1, E2E-08, E2E-16, E2E-19 | Partially implemented – all functions work; certificate wording not yet approved by the DPO (S5) |
| E05-BL-004 | Job-demand profiles for the top 150 job codes, validated with unit managers | `masterdata.job_demand_profile`: 28 representative profiles across 12 units; `PUT /masterdata/job-demands/{jobCode}`, bulk `POST /import` with dimension validation | IT-P5 (import, invalid dimension 400), SCR `inc2-oh-18-admin-job-demands` | Partially implemented – 28 of 150 profiles, none validated by unit managers (`validatedBy` empty) |
| E05-BL-005 | Select restrictions from catalogue; compatible duties suggested and ranked; manager confirmation step | `MatchingEngine`, `/fitness/match`, `/cases/{id}/duty-proposals` | IT-F2, UT-MATCH, E2E-09, E2E-10 | Implemented |
| E05-BL-006 | Manager confirms / declines with reason codes; escalation to HR if none available | `/workspace/duty-proposals`, `/duty-proposals/{id}/decision`, HR task on all-declined | IT-F2 (reason required, other scope 404, HR 403 on decision, ESCALATED_TO_HR + HR task), E2E-12, WEB-MGR (`DutyProposals`) | Implemented |
| E05-BL-007 | Phased RTW plan; employee and manager acknowledge; reminders; printable | `RtwService` (create, edit draft, activate → signed RTW_PLAN PDF shared to employee and manager, `rtw.plan-ack`), `/me/rtw-plans/{id}/acknowledge`, `/manager-acknowledge` | IT-F3, E2E-10, E2E-13, E2E-17, WEB-MGR (`RtwPlans`), WEB-EMP (`MyReturnToWork`) | Implemented |
| E05-BL-008 | Electronic job offer (pay, shift, restrictions, contact); employee e-acceptance; stored on case; HRIS note | `JobOfferService`, `/cases/{id}/job-offers` (HR), `/me/job-offers/{id}/respond`, `/withdraw`, JOB_OFFER document signed by HR, `fitness.job-offer.responded.v1` → `hr-systems` | IT-F3 (scope 404, guard, signed letter in portal, single response, allow-listed payload, withdraw), E2E-15, E2E-18, WEB-MGR (`JobOffers`), WEB-EMP (`JobOffers`) | Implemented (HRIS adapter to consume the event – E15) |
| E05-BL-009 | RTW review tasks; overdue list; escalation | `RtwService.processReviews` (reminders, escalation > 2 days to Nursing Head), `/rtw/reviews/overdue`, `/reviews/{id}/complete` | IT-F3 (5 days overdue listed, escalated once, `rtw.review-due`) | Implemented |
| E05-BL-010 | Crew declaration with acknowledgement, case, AME contact, "unfit – pending" to scheduling | `TriggerService` (`nextSteps`, `ameContact`, `acknowledgementDueBy`), PENDING roster message | IT-F4, WEB-EMP (`FitnessDeclaration`) | Implemented |
| E05-BL-011 | AME crew template with limitation codes; report PDF per CAA format; validity | `/cases/{id}/crew-assessment` (AME, CMO), 15 limitation codes, CREW_MEDICAL_REPORT / CABIN_CREW_MEDICAL_REPORT (signed), validity from standard | IT-F4, E2E-20, SCR `inc2-oh-12-case-crew` | Partially implemented – report layout is a draft "for CAA format confirmation" and limitation-code labels await AME / CMO confirmation against the local CAA list (S9) |
| E05-BL-012 | Status pushed to rostering within 15 min; status-only payload; retry; reconciliation report | Roster message logged in-transaction, outbox `crew.roster-status.changed.v1` (allow-listed, retried), `/integration/roster-feed` + `/ack`, `/crew/roster-feed/reconciliation` (p95, > 15 min), 5-minute flag job | IT-F4 (payload field subset, no VDL / rationale, ack, stale message listed, CMO via alias), UT-CREW, E2E-21 | Implemented (external publisher is a logging adapter until the rostering adapter is configured – E15) |
| E05-BL-013 | CMO dashboard of unfit / limited counts and durations by fleet / base; no identifiers; trend | `CrewDashboard`, `/crew/dashboard` (CMO), suppression < 5 | IT-F4 (no identifiers, suppression, scheduling 403), UT-CREW, SCR `inc2-oh-15-crew-dashboard` | Partially implemented – fleet and base come from `fitness.crew_profile`, which has no loader; all crew report under base UNKNOWN until it is populated |
| E05-BL-014 | Food-handler exclusion checklist sets "excluded" and notifies hygiene (status only); return clearance; audit | `FoodHandlerService` (checklist also recorded as Increment-1 FOOD_HANDLER checklist, `fitness.food-handler.status.changed.v1` → `catering-hygiene`, clearance with 48 h criterion, FOOD_HANDLER_CLEARANCE document) | IT-F5 | Implemented |
| E05-BL-015 | Hygiene manager view of excluded food handlers (status and dates only); access audited | `GET /fitness/food-handlers?department=` (HYGIENE_MANAGER, OH roles), audit `HYGIENE_FOOD_HANDLERS_READ` | IT-F5 (no reason category, audited, manager 403) | Partially implemented – status-only, audited API delivered; no screen for the hygiene manager (OH Workspace shows "Access restricted" to this role) |
| E05-BL-016 | Alerts at T−5 days; lapsed list; one-click extend / close with reason | `RestrictionService.processExpiry` (once, owner + manager + employee), `/restrictions/active?status=EXPIRING|LAPSED`, `/extend`, `/close` | IT-F6 (single alert, idempotent rerun, lapsed, clinical reason 422, extend, close, double close 409), UT-DEC | Implemented |
| E05-BL-017 | Manager view of team active restrictions (status only); no diagnosis; access logged | `/workspace/team-restrictions` (MANAGER, HR in scope), audit `TEAM_RESTRICTIONS_READ`; manager workspace *Team restrictions* | IT-F6, E2E-11, WEB-MGR (`TeamRestrictions`), SCR `inc2-mgr-01-team-restrictions` | Implemented |
| E05-BL-018 | IHR / redeployment assessment with CMO sign-off, appeal with timelines, decision letters | `IhrService` (AWAITING_CMO, DECIDED + IHR_DECISION_LETTER, 28-day window, 30-day appeal decision, independent CMO) | IT-F7, WEB-MGR (`IhrAssessments`) | Implemented |
| E05-BL-019 | Train RTW specialists and unit managers on job-demand matching and modified-duty offers | – (change and training activity) | – | Deferred – a P2 training task owned by Change & Training, not a software deliverable; to be planned once job-demand profiles are validated (S12) |
| E05-BL-020 | Compare actual absence duration to guideline; variance flag; override reason | `/cases/{id}/duration-comparison`, `durationOverrideReason` required when exceeded (`422 duration_override_required`), `DURATION_EXCEEDED` CDS alert | IT-F1, IT-R9 | Implemented (guideline content: 8 illustrative rows shared with E02 – S13) |

---

## 5. Cross-cutting verification

| Area | Evidence |
|---|---|
| Module boundaries | ARCH – E03 / E05 independent; document service and kernel independent of business modules; E01 / E02 independent of E03 / E05 |
| BFF pass-through | UT-BFF – `/workspace/team-restrictions`, `/duty-proposals`, `/rtw-plans`, `/documents` keep their prefix (defect D1 in the Release Notes) |
| SAST | SpotBugs + FindSecBugs: 0 findings; 6 justified exclusions (one new: `TesseractOcrProvider` COMMAND_INJECTION with allow-listed binary and languages) |
| Java tests | 128 pass (unit, integration on PostgreSQL 16, ArchUnit, BFF) |
| Web tests | oh-workspace 305, employee-app 138, manager-workspace 99 – lint and production build clean |
| E2E | 25 / 25 PASS (`tools/e2e/e2e-increment2.js`); 32 screenshots in `docs/images/screens/increment-02/` |
| API contract | `docs/api/openapi.json` regenerated – 240 paths |
