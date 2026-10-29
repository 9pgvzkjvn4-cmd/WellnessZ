# WellnessZ OHCM – Increment 4 Requirements Traceability Matrix (E04 + E08)

| | |
|---|---|
| **Document** | Increment 4 – 02 Requirements Traceability Matrix |
| **Sources** | E04 and E08 requirement workbooks, v1.0 – Draft for CMO & Nursing Head review (27 October 2026): sheets *High-Level Requirements*, *Feature List*, *Implementation Backlog* |
| **Audience** | CMO, Nursing Head, DPO, Legal, Finance, Procurement, Cyber GRC, CIO, HR, project sponsor |
| **Companion documents** | 01 – Solution Design E04/E08 · 03 – Release Notes Increment 4 · API contract `API-Contract-E04-E08.md` |
| **Verified on** | 29 October 2026 |

> **Method.** Every row was checked against the code: the endpoint, class, migration table or screen named in the
> *Implementation* and *UI screen* columns was located in the repository, and the *Verification* column names the
> automated test, end-to-end (E2E) check or screenshot that exercises it. Where an acceptance criterion is only partly
> met – because it depends on an external system that is not yet connected (a live FHIR partner hospital, insurer /
> authority / ERP connectors, a procured telehealth video platform), on identity-provider configuration in production
> (MFA enrolment), on master data that procurement still has to supply (the contracted provider network) or on a
> business sign-off (Legal / HSE form mapping, the e-prescribing legal route) – the item is marked **Partial** and the gap
> is stated. Volume and time targets in acceptance criteria (for example "plan created in ≤ 5 min", "0 late
> submissions", "median referral-to-appointment < 5 working days") are reported in *Notes*: where the system provides
> the function and measures the value, the item is counted as implemented and the operational target is left for the
> pilot. All API paths are relative to `/api/v1`; the BFF keeps the `/workspace` prefix for the HR claim-status
> resource (`/workspace/claims`).

## 1. Coverage summary

| Level | Items | Implemented | Partial | Deferred |
|---|---|---|---|---|
| E04 high-level requirements | 7 | 5 | 2 | 0 |
| E04 features | 7 | 5 | 2 | 0 |
| E04 backlog items | 19 | 14 | 5 | 0 |
| E08 high-level requirements | 6 | 5 | 1 | 0 |
| E08 features | 6 | 5 | 1 | 0 |
| E08 backlog items | 12 | 9 | 3 | 0 |
| **Total** | **57** | **43 (75 %)** | **14 (25 %)** | **0 (0 %)** |

Per epic: **E04** 33 items – 24 implemented, 9 partial · **E08** 24 items – 19 implemented, 5 partial.

The 14 partial items fall into five groups:

| Cause | Items |
|---|---|
| External system not yet connected – live FHIR partner hospital; insurer / authority / ERP connectors (default `ClaimsSubmissionGateway` = outbox event + content-free e-mail; ERP export = CSV file + outbox event) | E04-BL-006 (FHIR exchange tested with a simulated partner only); E08-HLR-02, E08-F02, E08-BL-004 (statutory / insurer submission); E08-BL-008 (ERP API) |
| Telehealth video platform to be procured – link provider and token-redemption endpoint ready | E04-HLR-07, E04-F07, E04-BL-016 |
| Business / legal sign-off pending | E04-HLR-05, E04-F05, E04-BL-013 (e-prescribing legal route and decision memo – no-op `EPrescribingGateway`); E08-BL-003 (jurisdiction form mapping – Legal / HSE sign-off) |
| Production identity-provider configuration | E04-BL-004 (MFA gate enforced in code; OTP / conditional-access enrolment for partner users in the production IdP outstanding) |
| Master data to be supplied by procurement | E04-BL-019 (20 illustrative providers with contracts loaded; to be replaced by the contracted network) |

Backlog by priority (workbook *Priority* column): of the **12 P1 (critical)** backlog items – 8 in E04 (BL-001, -003,
-004, -007, -008, -014, -017, -019), 4 in E08 (BL-001, -003, -004, -005) – all have a working implementation; **8 are
fully implemented and 4 partial** (E04-BL-004, E04-BL-019, E08-BL-003, E08-BL-004). No backlog item is deferred: every
P2 and P3 item (including the P3 items E04-BL-006, -013, -016 and E08-BL-007, -009, -010, -011, -012) was built in this
increment.

## 2. Status legend

| Status | Meaning |
|---|---|
| **Implemented** | All acceptance criteria are met in code and covered by at least one automated test, E2E check or screenshot. Minor deviations or interpretations are stated in *Notes*. |
| **Partial** | The software function exists and is tested, but part of the acceptance criteria depends on an external system, production IdP configuration, procurement data or a business / legal sign-off that is not yet in place; the gap is stated in *Notes*. |
| **Deferred** | Not built in Increment 4; target increment stated. (No Increment 4 item is deferred.) |

## 3. Verification legend

### 3.1 Integration tests (Spring Boot + PostgreSQL 16, `apps/wz-core-app/src/test/java/com/wellnessz/app`)

| Ref | Test method |
|---|---|
| IT-T1 | `Increment4TreatmentIntegrationTest.f01TreatmentPlanFollowUpsVarianceAndEmployeeView` |
| IT-T2 | `Increment4TreatmentIntegrationTest.f02ReferralSendPartnerPortalFlowAndDenials` |
| IT-T3 | `Increment4TreatmentIntegrationTest.f02ChaseEscalationRemindCancelAndFhirReport` |
| IT-T4 | `Increment4TreatmentIntegrationTest.imeThroughExternalReferralGatewayNotifiesListeners` |
| IT-T5 | `Increment4TreatmentIntegrationTest.f03ProviderDirectoryContractsGapsAndScorecards` |
| IT-T6 | `Increment4TreatmentIntegrationTest.f04PreauthAdjusterDecisionInvoicesAndCosts` |
| IT-T7 | `Increment4TreatmentIntegrationTest.f05FormularyStockAdministrationAllergyAndBatchChecks` |
| IT-T8 | `Increment4TreatmentIntegrationTest.f06OutcomesAndClosureGuard` |
| IT-T9 | `Increment4TreatmentIntegrationTest.f07TelehealthConsentJoinTokensChatAndCompletion` |
| IT-T10 | `Increment4TreatmentIntegrationTest.demoDataIsUsableByThePartnerPortal` |
| IT-CL1 | `Increment4ClaimsIntegrationTest.claimLifecycleRolesAndCaseBadge` |
| IT-CL2 | `Increment4ClaimsIntegrationTest.nurseAssignmentFallsBackToLeastLoadedCaseManagerThroughE02` |
| IT-CL3 | `Increment4ClaimsIntegrationTest.adjusterSeesOwnPayerClaimsOnlyWithRtwMilestonesTasksAndMessages` |
| IT-CL4 | `Increment4ClaimsIntegrationTest.disclosureSetsAreConsentGatedRedactableAndRevocable` |
| IT-CL5 | `Increment4ClaimsIntegrationTest.privilegedAndNeverDisclosedMaterialIsExcluded` |
| IT-CL6 | `Increment4ClaimsIntegrationTest.insurerAndStatutoryReportsWithConsentLegalBasisAcknowledgementAndDeadlines` |
| IT-CL7 | `Increment4ClaimsIntegrationTest.costLedgerIncludesE04EntriesAndFinanceSeesUnitsWithoutNames` |
| IT-CL8 | `Increment4ClaimsIntegrationTest.litigationHoldPutsCaseDocumentsOnHoldAndMedicoLegalReportsStayInTheLegalAccessSet` |
| IT-CL9 | `Increment4ClaimsIntegrationTest.hrSeesClaimStatusOnlyInsideScopeAndEveryDisclosureIsLogged` |
| IT-CL10 | `Increment4ClaimsIntegrationTest.demoDataIsUsableByTheUis` |
| ARCH | `ArchitectureTest` (17 rules) – incl. `earlierIncrementsDoNotDependOnIncrement4`, `treatmentAndClaimsAreIndependent`, `claimsDoesNotDependOnTreatmentOrClinicalRecord` |

### 3.2 Unit tests

| Ref | Test class (module) | Covers |
|---|---|---|
| UT-TR | `TreatmentRulesTest` (wz-e04-treatment, 11) | `templateExpansionDatesInterventionsAndFollowUps`, `varianceMaths`, `dataSetValidation`, `chaseRules`, `fhirServiceRequestMapping`, `scorecardMaths`, `contractStatus`, `invoiceMatching`, `allergyAndBatchChecks`, `telehealthTokenWindow`, `outcomeClosureGuard` |
| UT-CW | `ClaimRulesTest` (wz-e08-claims, 8) | claim workflow (`forwardPathIsAllowed`, `litigationOnlyFromOpenOrUnderReview`, `skippingStepsIsInvalid`, `reopenNeedsAReason`) and nurse choice (`caseOwnerWithNursingRoleKeepsTheClaim`, `ownerWithoutNursingRoleFallsBackToLeastLoadedCaseManagerWithinCap`, `unavailableOwnerAndTiesAreHandledDeterministically`, `nobodyWithinCapLeavesTheClaimUnassigned`) |
| UT-RD | `ReportDeadlinesTest` (wz-e08-claims, 7) | UK over-7-day 15 days / specified injury 10 days, UAE 48 h, insurer FNOL from notification, fallback without incident time, reminder window and late, on-time percentage |
| UT-DR | `DisclosureRulesTest` (wz-e08-claims, 7) | allowed types for the claims team, provider / external reports by OH only, never-disclosed types, privileged / restricted / unsigned / foreign documents refused, redaction masking and phrase validation |
| UT-CM | `CostMathTest` (wz-e08-claims, 6) | FX rates, roll-up with small-group suppression, unassigned unit, reconciliation and variance, ERP export grouping with SHA-256 over the exact CSV, empty export and formula-injection guard |
| UT-MFA | `MfaRoleGateTest` (platform/wz-security, 3) | `partnerRoleIsDroppedWithoutMfaProof`, `partnerRoleIsGrantedWithAmrOrAcrProof` (incl. Entra ID `acrs` `c1`), `gateCanBeSwitchedOffPerEnvironment` |
| UT-NOTIF | `NotificationRulesTest.everyApprovedTemplatePassesTheContentRules` (platform/wz-notification) | all templates incl. the 13 new Increment-4 templates render content-free |
| UT-BFF | `ApiProxyPathTest` (apps/wz-bff) | `/workspace/claims` keeps its prefix |

### 3.3 Web, E2E and screenshots

| Ref | Artefact |
|---|---|
| WEB-OH | `web/oh-workspace` vitest (570 tests): `screens/treatment/Treatment.test.tsx`, `screens/claims/Claims.test.tsx`, `domain/increment4.test.ts`, `auth/roles.test.ts` (claims, finance, legal and procurement roles never reach clinical screens), `screens/screens.smoke.test.tsx` |
| WEB-EMP | `web/employee-app` vitest (223 tests): `screens/Care.test.tsx` (treatment plan view, telehealth view), `api/care.test.ts` |
| WEB-MGR | `web/manager-workspace` vitest (164 tests): `screens/Claims.test.tsx`, `api/claims.test.ts` |
| WEB-PP | `web/partner-portal` vitest (119 tests, new app): `App.test.tsx` (role-based access, MFA / no-access page), `screens/Referrals.test.tsx`, `ReferralDetail.test.tsx`, `Preauthorisations.test.tsx`, `ClaimDetail.test.tsx`, `Telehealth.test.tsx`, `api/*.test.ts` |
| E2E-nn | `tools/e2e/e2e-increment4.js` check nn (list below) – real Keycloak logins through the BFF, core API and four web apps – **22 / 22 PASS** |
| SCR `name` | Screenshot `docs/images/screens/increment-04/name.png` captured by the E2E run (26 in total) |

E2E checks, in order: **01** employee reports an injury and grants partner + insurer consent in the app · **02** nurse
builds an active treatment plan from the wrist-sprain template with follow-ups · **03** employee sees the plan in plain
language (no OH-only fields), gets the printable plan and confirms a follow-up · **04** referral needs the full data set
and active consent; then the letter + FHIR payload are sent to the contracted provider · **05** partner provider sees
the referral in its portal; another organisation gets 404 · **06** provider accepts, books the appointment, marks seen,
uploads the report and invoices through the portal · **07** report attaches to the case, OH rates it; overdue reports
are on the worklist with chase count · **08** procurement searches contracted providers by service; CMO sees monthly
vendor scorecards; network gaps listed · **09** pre-authorisation is shared with the TPA under consent and approved by
the adjuster in the partner portal · **10** clinic medication recorded with batch and expiry; an expired batch is
refused · **11** outcomes captured at milestones with validated scales and improvement · **12** telehealth: join needs
consent; secure chat; completion creates a draft consultation note · **13** treatment KPIs: referral-to-appointment,
report within 7 days, missed appointments, cost per case · **14** claims coordinator opens a lost-time claim from the
case; a nurse case manager is assigned automatically; badge on the case header · **15** first report of injury
auto-populated with a statutory deadline timer and submitted under consent · **16** consent-gated disclosure set: only
claim-relevant documents, sent to the TPA and visible in its portal; disclosure logged · **17** adjuster collaborates in
the portal: own claims, RTW status only, shared task, secure message; clinical wording refused; providers have no claim
access · **18** every lost-time claim has a nurse case manager within 48 hours · **19** finance: costs by category rolled
up by subsidiary (small groups suppressed, no names), budget vs actual, ERP export reconciled; no claim detail ·
**20** legal hold suspends retention of the case documents; medico-legal report stays in the legal access set · **21** HR
sees claim status only (no costs, payer, adjuster or clinical detail) · **22** DPO disclosure report lists every insurer
disclosure with consent.

### 3.4 Platform and migration artefacts referenced below

| Artefact | Content |
|---|---|
| V13 `V13__increment4_platform.sql` | `masterdata.partner_organisation` with 20 providers (hub hospitals, specialists, physiotherapy, imaging, laboratory, mental health, telehealth `TELEDOC`, travel-assistance partner `GLOBALASSIST`, outstation / layover-city clinics in DXB, JFK, SIN, HKG), payers `GULFTPA` (TPA), `AVIA_INSURE`, `GROUPMED` (insurers), authorities `AUTH_OSH`, `AUTH_LABOUR_AE`; retention class `CLAIM_RECORD` (10 y, suspended by litigation hold) – *proposed, pending DPO approval* |
| V14 `V14__e04_treatment.sql` | schema `treatment`: `plan_template`, `treatment_plan`, `follow_up`, `variance_alert`, `service_catalogue`, `referral`, `referral_history`, `provider_profile`, `provider_contract`, `provider_scorecard`, `preauth`, `invoice`, `formulary_item`, `stock_batch`, `administration`, `outcome`, `telehealth_session`, `telehealth_token`, `telehealth_message`; seeds 8 plan templates, 14 referral services, provider profiles / contracts / rate cards / SLAs, 10-item clinic formulary |
| V15 `V15__e08_claims.sql` | schema `claims`: `claim`, `claim_history`, `report_form`, `first_report`, `disclosure_set`, `disclosure_set_document`, `claim_task`, `claim_message`, `message_read`, `cost_entry`, `budget`, `cost_export`, `legal_request`, `legal_report`; report forms `GB_OSH_F2508`, `AE_LABOUR_INJURY`, `INSURER_FNOL` |
| Kernel SPIs (`platform/wz-case-kernel/.../support`) | `CaseCostRecorder` (impl E08 `CostService`, called by E04 on pre-auth estimate / approval and invoices), `ExternalReferralGateway` (impl E04, called by E08 for IME), `ReferralStatusListener` (impl E08 `LegalService` for `CLAIM_IME`), `ClaimContextProvider` (impl E08 `ClaimService`; E04 pre-auth payer default, case header badge). E04 also implements `CaseTransitionGuard` (`OutcomeClosureGuard`), `ChartContributor` (`TreatmentChartContributor`), `AppointmentStatusListener` (`TreatmentAppointmentListener`: `TREATMENT_FOLLOW_UP`, `TELEHEALTH_SESSION`) |
| Module SPIs | E04 `TelehealthProvider` (default link provider, single-use participant tokens stored hashed, window 15 min before start to end; `POST /integration/telehealth/tokens/redeem` for the video platform), `EPrescribingGateway` (no-op until the legal route is confirmed); E08 `ClaimsSubmissionGateway` (default `DefaultClaimsSubmissionGateway`: outbox event to `insurer-gateway` / `authority-gateway` + content-free e-mail; replace with a `@Primary` bean) |
| `DocumentService` additions | share target `INSURER` (consent `INSURER_SHARING`, disclosure purpose `CLAIM`), `GET /partner/documents` for TPA adjusters, `shareAsSystem(actor, id, target, org, consentId)`, `legalHoldForCase`; upload types `PROVIDER_REPORT`, `PROVIDER_INVOICE`, `INSURER_ACKNOWLEDGEMENT`, `REDACTED_COPY`; templates `TREATMENT_PLAN`, `PREAUTH_REQUEST`, `FIRST_REPORT_OF_INJURY`, `MEDICO_LEGAL_REPORT` |
| Security | MFA gate in `WellnessZJwtConverter`: `PARTNER_PROVIDER` / `TPA_ADJUSTER` granted only when the token proves MFA (`amr` in mfa, otp, totp, hwk, swk, sms, webauthn, fido, or `acr` / Entra ID `acrs` entry in 2, 3, mfa, c1); partner / insurer `INTEGRATION` clients bound to their organisation by a fixed `org_scope=/PARTNERS/<CODE>` claim (`CurrentUser.partnerOrganisation()`; FHIR endpoints → referral's provider, pre-authorisation decision → payer, report acknowledgement → recipient, otherwise 404 + `DENIED` audit; fixed in increment 4 after review); `wellnessz.security.mfa-required-roles` (`WZ_MFA_REQUIRED_ROLES`), default on, off in the local profile; new roles `CLAIMS_COORDINATOR`, `TPA_ADJUSTER`, `LEGAL_COUNSEL`, `FINANCE`, `PROCUREMENT`; consent purpose `TELEHEALTH_CONSULTATION` |
| Notifications | 13 new content-free templates (`referral.sent`, `referral.chase`, `referral.overdue-team`, `referral.appointment`, `treatment.follow-up`, `treatment.variance`, `preauth.status`, `telehealth.invite`, `claim.assigned`, `claim.report-due`, `claim.task`, `claim.message`, `legal.request`); placeholder `reference`; property `partner-portal-url` |
| Outbound allow-lists | `insurer-gateway`, `authority-gateway`, `erp-finance` – references only; documents travel only through consent-checked shares |
| Scheduled jobs | E04 `TreatmentJobs` (`treatment-variance` daily 02:20, `referral-chase` daily 06:40, `provider-scorecards` 1st of month 03:15 UTC); E08 `ClaimsJobs` (`claims-report-deadlines` hourly at :05); all through `JobRunGuard` |
| Partner portal | new web app `web/partner-portal` (port 5176, MFA sign-in page) wired into Keycloak redirect URIs, CORS (local), Helm component `partnerPortal` (host `partners.<domain>`), CI web matrix, container build and dependabot |

---

## 4. E04 – Injury & Illness Management, Treatment Coordination & Partner Referrals

Module `modules/wz-e04-treatment` (package `com.wellnessz.e04`, schema `treatment`, V14; depends on E03 and E05 through
the kernel only). OH Workspace screens under `web/oh-workspace/src/screens/treatment` (case tabs `#/cases/{id}/treatment`,
`/referrals`, `/costs`, `/outcomes`, `/claim`); partner provider / adjuster in `web/partner-portal/src/screens`; employee
in `web/employee-app/src/screens/MyTreatment.tsx`, `MyTelehealth.tsx`.

### 4.1 High-level requirements

| ID | Requirement (short) | Implementation | UI screen | Verification | Status | Notes |
|---|---|---|---|---|---|---|
| E04-HLR-01 | Treatment plans with goals, interventions, follow-up schedule, responsible clinician, expected recovery (duration guideline), reminders and variance alerts | `PlanService`, `PlanMaths`, `TreatmentPlanController` (`/treatment/plan-templates`, `/cases/{caseId}/treatment-plans`, `/treatment-plans/{id}`, `/status`, `/follow-ups/{followUpId}`, `/print`, `/treatment/variance-alerts`, `/me/treatment-plans`); `TreatmentJobs.varianceJob`; tables `plan_template`, `treatment_plan`, `follow_up`, `variance_alert` | OH `#/cases/{id}/treatment`, `#/treatment/variance`; employee `#/treatment` | IT-T1, UT-TR, E2E-02, E2E-03, SCR `inc4-oh-01-case-treatment-plan`, `inc4-oh-08-recovery-variance`, `inc4-emp-01-my-treatment` | Implemented | 8 injury-type templates; versioned plans (409 `active_plan_exists` for a second active plan) |
| E04-HLR-02 | Electronic referrals to external providers (letter, required data set, consent, urgency, service, contracted provider), status tracking, returned report | `EReferralService`, `DataSetValidator`, `FhirMapper`, `PartnerReferralService`, `ReferralCore`, `EReferralController`, `PartnerPortalController` (`/partner/referrals/*`); `REFERRAL_LETTER` + FHIR R4 `ServiceRequest`; tables `service_catalogue`, `referral`, `referral_history` | OH referral wizard in `#/cases/{id}/referrals`, `#/referrals`, `#/referrals/{id}`; partner portal `#/referrals` | IT-T2, IT-T3, UT-TR, E2E-04..07, SCR `inc4-oh-02-referral-worklist`, `inc4-partner-01-referral-inbox`, `inc4-partner-02-referral-detail` | Implemented | Portal channel live end-to-end; FHIR endpoints serve only the referral's provider system (`org_scope`; payer, other-provider and unbound clients → 404, IT-T2, IT-T3); FHIR exchange with a live partner hospital is tracked under E04-BL-006 |
| E04-HLR-03 | Provider network directory (facility, specialty, location, contract, rates, accreditation, languages, SLA) with performance ratings | `ProviderDirectoryService`, `ContractStatus`, `ScorecardMaths`, `ProviderController` (`/providers`, `/providers/{code}`, `/contracts`, `/network-gaps`, `/scorecards`, `/recompute`, `/export`); `masterdata.partner_organisation` (V13); tables `provider_profile`, `provider_contract`, `provider_scorecard` | OH `#/providers`, `#/providers/{code}`, `#/providers/scorecards`, `#/providers/gaps` | IT-T5, UT-TR, E2E-08, SCR `inc4-oh-03-provider-directory`, `inc4-oh-04-provider-scorecards` | Implemented | Seeded network is illustrative (see E04-BL-019) |
| E04-HLR-04 | Pre-authorisation / guarantee of payment to insurers or TPAs, approval tracking, cost estimates and invoices against the case | `PreauthService`, `InvoiceMatcher`, `PreauthInvoiceController` (`/cases/{caseId}/preauthorisations`, `/submit`, `/decision`, `/integration/preauthorisations/{ref}/decision`, `/cases/{caseId}/invoices`, `/invoices/{id}/status`, `/treatment/costs`); `/partner/preauthorisations/*`; `PREAUTH_REQUEST` shared `INSURER`; `CaseCostRecorder` → E08 ledger; tables `preauth`, `invoice` | OH `#/cases/{id}/costs`, `#/claims/preauthorisations`; partner portal `#/preauthorisations` | IT-T6, IT-CL7, UT-TR, E2E-09, SCR `inc4-partner-03-preauthorisations` | Implemented | TPA decides in the portal; insurers with their own system use the INTEGRATION decision endpoint, bound to the payer organisation by `org_scope` (a provider system → 404, IT-T6; no insurer system connected yet) |
| E04-HLR-05 | Medications administered / dispensed in clinic; e-prescribing where permitted; optional stock linkage | `MedicationAdminService`, `MedicationChecks`, `MedicationController` (`/treatment/formulary`, `/treatment/stock`, `/cases/{caseId}/medications/administer`, `/administrations`, `/treatment/medication-usage`, `/treatment/eprescribing/status`); `EPrescribingGateway` (no-op); tables `formulary_item`, `stock_batch`, `administration` | OH `#/treatment/medications`; administration panel in `#/cases/{id}/treatment` | IT-T7, UT-TR, E2E-10, SCR `inc4-oh-05-medications` | Partial | Administration, allergy check, batch / expiry and stock linkage complete; e-prescribing disabled until Legal confirms the route (E04-BL-013) |
| E04-HLR-06 | Clinical outcomes (function, pain, RTW date, residual impairment) and partner report quality for every referred case | `OutcomeService`, `OutcomeClosureGuard` (kernel `CaseTransitionGuard` → 422 `outcome_required`), `OutcomeTelehealthController` (`/cases/{caseId}/outcomes`), `/referrals/{id}/rating`; table `outcome` | OH `#/cases/{id}/outcomes`; rating on `#/referrals/{id}` | IT-T2, IT-T8, UT-TR (`outcomeClosureGuard`), E2E-07, E2E-11, SCR `inc4-oh-06-case-outcomes` | Implemented | |
| E04-HLR-07 | Telehealth (video, secure chat) with partner specialists and OH clinicians for staff at outstations, on layover or on shift | `TelehealthService`, `TelehealthProvider` SPI + `LinkTelehealthProviderConfig`, `TokenWindow`, `/telehealth/sessions/*`, `/me/telehealth/*`, `/partner/telehealth/sessions/*`, `/integration/telehealth/tokens/redeem`; tables `telehealth_session`, `telehealth_token`, `telehealth_message` | OH `#/telehealth`; employee `#/telehealth`; partner portal `#/telehealth` | IT-T9, UT-TR (`telehealthTokenWindow`), E2E-12, SCR `inc4-oh-07-telehealth`, `inc4-emp-02-my-telehealth` | Partial | Scheduling, consent, join tokens, secure chat and documentation complete; the video itself needs the telehealth platform to be procured (default provider issues a room link) |

### 4.2 Features

| ID | Feature | Implementation | UI screen | Verification | Status | Notes |
|---|---|---|---|---|---|---|
| E04-F01 | Treatment plan & follow-up scheduler – plan in ≤ 5 min; follow-up tasks auto-generated; variance alert above a configurable % | As E04-HLR-01; template expansion; follow-ups create kernel case tasks and optionally book through `AppointmentBooking` (`TREATMENT_FOLLOW_UP`); threshold `treatment.variance.threshold-pct` (20) or per-plan override | OH `#/cases/{id}/treatment`, `#/treatment/variance` | IT-T1, UT-TR (`templateExpansionDatesInterventionsAndFollowUps`, `varianceMaths`), E2E-02, WEB-OH (`Treatment.test`), SCR `inc4-oh-01-case-treatment-plan`, `inc4-oh-08-recovery-variance` | Implemented | Template-based plan takes one form; the 5-minute target is not formally timed |
| E04-F02 | e-Referral management – sent electronically; provider portal shows it; provider report attaches to case; overdue report chased automatically | As E04-HLR-02; `ChaseRules`, `TreatmentJobs.chaseJob` (`referral-chase`), notifications `referral.chase`, `referral.overdue-team` | OH `#/referrals`; partner portal `#/referrals`, `#/referrals/{id}` | IT-T2, IT-T3, UT-TR (`chaseRules`), E2E-04..07, WEB-OH, WEB-PP, SCR `inc4-oh-02-referral-worklist`, `inc4-partner-02-referral-detail` | Implemented | Status flow DRAFT → SENT → ACCEPTED → SCHEDULED → SEEN → REPORT_RECEIVED → CLOSED; invalid moves 422 `invalid_transition` |
| E04-F03 | Provider network directory – select by specialty + location + contract; scorecard visible; procurement maintains contracts | As E04-HLR-03; coverage HUB / OUTSTATION / LAYOVER; `TreatmentJobs.scorecardJob` (`provider-scorecards`) | OH `#/providers`, `#/providers/{code}`, `#/providers/scorecards` | IT-T5, UT-TR (`scorecardMaths`, `contractStatus`), E2E-08, WEB-OH, SCR `inc4-oh-03-provider-directory`, `inc4-oh-04-provider-scorecards` | Implemented | Layover cities covered by `GLOBALASSIST` and outstation clinics; network gaps listed for procurement (47 in the E2E run) |
| E04-F04 | Pre-authorisation & cost tracking – pre-auth status on case; invoice matched to case; unit cost report | As E04-HLR-04; invoice matching against the approved amount (tolerance `treatment.invoice.tolerance-pct` 5 → MATCHED / DISPUTED); `/treatment/costs` by SUBSIDIARY / DEPARTMENT / COST_CENTRE + CSV | OH `#/cases/{id}/costs`, `#/claims/preauthorisations`, `#/claims/costs` | IT-T6, UT-TR (`invoiceMatching`), E2E-06, E2E-09, WEB-OH, SCR `inc4-partner-03-preauthorisations`, `inc4-oh-15-finance-costs` | Implemented | Figures summed in the base currency (`treatment.base-currency` GBP) only |
| E04-F05 | Clinic medication administration & e-prescribing – administration complete with batch; allergy alert fires; monthly usage report | As E04-HLR-05; allergy check against the E03 record (422 `allergy_alert` unless an override reason is given); batch 422 `batch_expired` / `batch_unknown`; stock decrement; mirrored into the E03 medication list | OH `#/treatment/medications`, case administration panel | IT-T7, UT-TR (`allergyAndBatchChecks`), E2E-10, WEB-OH, SCR `inc4-oh-05-medications` | Partial | All acceptance criteria except e-prescription integration, which waits for the legal route (no-op gateway reports `enabled:false`) |
| E04-F06 | Outcome & provider performance capture – outcome at closure for 100 % of treated cases; vendor scorecard monthly | As E04-HLR-06; closure guard blocks CLOSE of a case with a plan or referral and no CLOSURE outcome; monthly scorecard job | OH `#/cases/{id}/outcomes`, `#/providers/scorecards` | IT-T5, IT-T8, UT-TR, E2E-08, E2E-11, WEB-OH, SCR `inc4-oh-06-case-outcomes`, `inc4-oh-04-provider-scorecards` | Implemented | 100 % is enforced by the guard rather than measured |
| E04-F07 | Telehealth consultation – employee on layover completes a video consult; note signed; follow-up scheduled | As E04-HLR-07; crew-layover mode (`crewLayover`, `layoverCity`); completion creates a DRAFT E03 encounter for signature and an optional plan follow-up | OH `#/telehealth`; employee `#/telehealth`; partner portal `#/telehealth` | IT-T9, E2E-12, WEB-OH, WEB-EMP (`Care.test` telehealth view), WEB-PP (`Telehealth.test`), SCR `inc4-oh-07-telehealth`, `inc4-emp-02-my-telehealth` | Partial | Video platform to be procured; token redemption endpoint is ready for it. Employee consent text says video and sound are not stored – to be confirmed with the chosen platform |

### 4.3 Implementation backlog

| ID | Title (short) | Implementation | UI screen | Verification | Status | Notes |
|---|---|---|---|---|---|---|
| E04-BL-001 | Plan builder with goals, interventions, review dates; templates per injury type; follow-up tasks; reminders; printable plan (P1) | `PlanService` (templates BACK_STRAIN, SHOULDER_STRAIN, WRIST_SPRAIN, KNEE_SPRAIN, HAND_LACERATION, CONCUSSION_MILD, WORK_STRESS, NOISE_HEARING), kernel case tasks, `treatment.follow-up` reminder, `POST /treatment-plans/{id}/print` → signed `TREATMENT_PLAN` shared to the employee | OH `#/cases/{id}/treatment` (`TreatmentTab.tsx`) | IT-T1, UT-TR, E2E-02, E2E-03 (print 200), WEB-OH, SCR `inc4-oh-01-case-treatment-plan` | Implemented | Plan history kept as versions; demo plan shows template content when goals are empty (demo data cannot hold encrypted text) |
| E04-BL-002 | Alert when a case exceeds its expected duration; configurable rule; alert in queue; escalation | `PlanMaths.variance`, `TreatmentJobs.varianceJob` (`treatment-variance`), `/treatment/variance-alerts`, `/acknowledge`, `/escalate` (Nursing Head / CMO notified); `treatment.variance` | OH `#/treatment/variance` | IT-T1, UT-TR (`varianceMaths`), WEB-OH, SCR `inc4-oh-08-recovery-variance` | Implemented | Rule is a percentage over the expected duration (global or per plan); a days-based variant is expressed through the plan's expected recovery days |
| E04-BL-003 | Electronic referral with required data set and consent; PDF + structured payload; audit (P1) | `EReferralService` (wizard create / edit / send), `DataSetValidator` (422 `data_set_incomplete` with field), consent `TREATMENT_SHARING_PARTNER` (422 `consent_required`), signed `REFERRAL_LETTER` + FHIR `ServiceRequest`, audit `REFERRAL_CREATE` / `REFERRAL_SEND`, `referral_history` | OH referral wizard (`ReferralWizard.tsx`) in `#/cases/{id}/referrals` | IT-T2, UT-TR (`dataSetValidation`, `fhirServiceRequestMapping`), E2E-04 (incomplete → 422, sent RFL-2026-000001), WEB-OH | Implemented | Consent pickers only – no free-text consent ids |
| E04-BL-004 | Secure provider portal with MFA: referral list, status updates, report upload, own referrals only (P1) | `web/partner-portal` (new app); `PartnerPortalController` (`/partner/referrals`, `/accept`, `/decline`, `/appointment`, `/seen`, `/report`, `/invoice`); org scope `/PARTNERS/<code>` (other organisation → 404); first read disclosure-logged; MFA gate in `WellnessZJwtConverter` | Partner portal `#/referrals`, `#/referrals/{id}`, `#/documents`; MFA sign-in page | IT-T2, IT-T10, UT-MFA, E2E-05, E2E-06, WEB-PP, SCR `inc4-partner-01-referral-inbox`, `inc4-partner-02-referral-detail`, `inc4-partner-06-shared-documents` | Partial | Portal and MFA gate complete (roles dropped without `amr` / `acr` MFA proof); gate is off in the local profile because the demo realm has no OTP enrolment – MFA enrolment for partner users in the production IdP is outstanding |
| E04-BL-005 | Overdue provider reports chased automatically; reminder; escalation list | `ChaseRules`, `referral-chase` job (`referral.chase` to the provider; after `treatment.referral.max-chases` (2) → escalated + `referral.overdue-team`) | OH `#/referrals` (overdue filter, chase count) | IT-T3, UT-TR (`chaseRules`), E2E-07 (RFL-2026-900003 × 1), WEB-OH | Implemented | |
| E04-BL-006 | FHIR ServiceRequest / DocumentReference exchange with partner hospitals; secure e-mail fallback (P3) | `FhirMapper`, `GET /integration/referrals/{id}/fhir`, `POST /integration/referrals/fhir/document-reference` (INTEGRATION; report → REPORT_RECEIVED, disclosure-logged); channel `PORTAL` / `FHIR` / `SECURE_EMAIL` | – (system interface) | IT-T3 (FHIR report), UT-TR (`fhirServiceRequestMapping`), E2E-04 (FHIR payload) | Partial | Endpoints tested with a simulated partner; no live partner hospital yet (criterion "tested with ≥ 1 partner" open). Fallback = consented portal share + content-free `referral.sent` e-mail to the provider contact |
| E04-BL-007 | Procurement maintains directory with contracts, rates, SLAs; audit (P1) | `PUT /providers/{code}` (PROCUREMENT, OH_ADMIN), `POST /providers/{code}/contracts` (PROCUREMENT – new version, rates, SLA); organisations via `/masterdata/partner-organisations`; audit `PROVIDER_PROFILE_UPDATE`, `PROVIDER_CONTRACT_CREATE` | OH `#/providers/{code}` (procurement edit) | IT-T5, UT-TR (`contractStatus`), WEB-OH | Implemented | Contract status ACTIVE / EXPIRING / EXPIRED / NONE derived from dates |
| E04-BL-008 | Provider search by specialty, distance and contract status; preferred providers; selection logged (P1) | `GET /providers?serviceCode=&specialty=&city=&countryCode=&contractStatus=&q=&preferredOnly=`; provider chosen in the referral wizard is recorded on the referral and in audit `REFERRAL_CREATE` | OH `#/providers` (`Providers.tsx`), referral wizard | IT-T5, E2E-08, WEB-OH, SCR `inc4-oh-03-provider-directory` | Implemented | "Distance" is filtered by city / country and HUB / OUTSTATION / LAYOVER coverage; coordinates are stored, the optional map view is not built |
| E04-BL-009 | CMO vendor scorecard (timeliness, report quality, outcomes, cost) monthly; export; review pack | `ScorecardMaths`, `provider-scorecards` job, `/providers/scorecards`, `/recompute`, `/export` (CSV) | OH `#/providers/scorecards` | IT-T5, UT-TR (`scorecardMaths`), E2E-08 (4 scorecards), WEB-OH, SCR `inc4-oh-04-provider-scorecards` | Implemented | Review pack is the CSV export |
| E04-BL-010 | Insurance coordinator raises pre-authorisation from the case; attachments; status; approval reference; SLA | `PreauthService` (payer default from `ClaimContextProvider`; justification required from OH roles, refused from claims coordinators), `/submit` (signed `PREAUTH_REQUEST` shared `INSURER`, SLA `treatment.preauth.sla-hours` 48), decision by claims coordinator, TPA adjuster or INTEGRATION; `preauth.status` | OH `#/cases/{id}/costs`, `#/claims/preauthorisations`; partner portal `#/preauthorisations` | IT-T6, E2E-09 (PA-2026-000001 → APPROVED), WEB-OH, WEB-PP (`Preauthorisations.test`), SCR `inc4-partner-03-preauthorisations` | Implemented | |
| E04-BL-011 | Case costs (estimates, invoices) rolled up by unit and cost centre; export to finance | `CaseCostRecorder` (ESTIMATE / INVOICE, MEDICAL), `/treatment/costs` (groupBy SUBSIDIARY / DEPARTMENT / COST_CENTRE, small-group suppression), `/treatment/costs/export` (CSV); E08 roll-up and ERP export (E08-BL-008) | OH `#/claims/costs` | IT-T6, IT-CL7, E2E-13 (cost per case), E2E-19, SCR `inc4-oh-15-finance-costs` | Implemented | |
| E04-BL-012 | Record medication administered with batch and expiry; allergy check; report | `/cases/{caseId}/medications/administer`, `/treatment/stock`, `/treatment/formulary`, `/treatment/medication-usage` (monthly by item and site); audit `MEDICATION_ADMINISTER` | OH case administration panel (`AdministerPanel.tsx`), `#/treatment/medications` | IT-T7, UT-TR (`allergyAndBatchChecks`), E2E-10 (expired → 422; PCM-2401 given), WEB-OH, SCR `inc4-oh-05-medications` | Implemented | |
| E04-BL-013 | Spike: legal basis and integration route for e-prescribing; decision memo (P3) | `EPrescribingGateway` SPI, `EPrescribingConfig` (no-op default with interim position), `GET /treatment/eprescribing/status` (`{enabled:false, route, decisionMemo}`); config `treatment.eprescribing.route` | – | IT-T7 (status endpoint) | Partial | Interim position recorded: no e-prescribing; clinic medicines from the formulary, prescriptions on paper or by the employee's own doctor. The decision memo with regulatory constraints and the chosen route (national e-prescription service vs partner pharmacy) awaits Legal; interim position documented in the Solution Design §10 (01-Solution-Design-E04-E08.md) |
| E04-BL-014 | Outcome fields at milestones and closure; validated scales; completion mandatory at closure (P1) | `POST /cases/{caseId}/outcomes` (BASELINE / REVIEW / CLOSURE; NPRS 0–10; ODI, QuickDASH, PSFS, LEFS 0–100; RTW date; residual impairment %), improvement deltas; `OutcomeClosureGuard` (422 `outcome_required`) | OH `#/cases/{id}/outcomes` (`OutcomesTab.tsx`) | IT-T8, UT-TR (`outcomeClosureGuard`), E2E-11 (pain Δ −4, function Δ −30), WEB-OH, SCR `inc4-oh-06-case-outcomes` | Implemented | Milestone prompt is the next-milestone default in the form plus the closure guard |
| E04-BL-015 | Physician rates external report quality 1–5 + comment; aggregated per provider | `POST /referrals/{id}/rating`; `avgReportQuality` in scorecards | OH `#/referrals/{id}` | IT-T2, IT-T5, E2E-07, WEB-OH | Implemented | |
| E04-BL-016 | Employee on layover: secure video consultation with OH clinician or partner specialist; consent; scheduling; note; crew app (P3) | `TelehealthService` (sessions with `providerCode` e.g. `TELEDOC`, crew-layover mode, `AppointmentBooking` when the clinician has a diary, `telehealth.invite`), consent `TELEHEALTH_CONSULTATION` (422 `consent_required`), single-use hashed tokens, encrypted chat, completion → DRAFT E03 encounter | Employee `#/telehealth` (`MyTelehealth.tsx`); OH `#/telehealth`; partner portal `#/telehealth` | IT-T9, UT-TR, E2E-12, WEB-EMP, WEB-PP, SCR `inc4-oh-07-telehealth`, `inc4-emp-02-my-telehealth` | Partial | Secure video depends on the platform to be procured (link provider default). Booking takes the first free slot on or after the scheduled date |
| E04-BL-017 | Worklist of all open referrals with status; filters; status badges; bulk reminder (P1) | `GET /referrals?status=&overdue=&mine=&serviceCode=&providerCode=`, `POST /referrals/remind` (≤ 200) | OH `#/referrals` (`Referrals.tsx`) | IT-T3, E2E-07, WEB-OH, SCR `inc4-oh-02-referral-worklist` | Implemented | `slaBreached` flag added |
| E04-BL-018 | Employee sees plan and appointments in the portal; reminders (SMS / app); confirm / decline; missed appointment flag | `/me/treatment-plans` (plain language, no OH-only fields), `/me/treatment-plans/{id}/follow-ups/{followUpId}/response` (CONFIRM / DECLINE, reason screened); `TreatmentAppointmentListener` marks NO_SHOW as MISSED; `treatment.follow-up`, `referral.appointment` via the notification channels (push, SMS, e-mail) | Employee `#/treatment` (`MyTreatment.tsx`) | IT-T1, E2E-03 (confirm 200), WEB-EMP (`Care.test` treatment plan view), SCR `inc4-emp-01-my-treatment` | Implemented | |
| E04-BL-019 | Initial network: hub hospitals, specialists, physio, imaging, mental health, travel / assistance partner; ≥ 20 providers with contracts; gaps list (P1) | V13 `partner_organisation` (20 providers + payers + authorities); V14 profiles, contracts, rate cards, SLAs (one EXPIRING, one EXPIRED); `/providers/network-gaps` | OH `#/providers`, `#/providers/gaps` | IT-T5, E2E-08 (gaps 47), SCR `inc4-oh-03-provider-directory` | Partial | Mechanics and ≥ 20 loaded providers met; the data are illustrative and must be replaced by procurement's contracted network before go-live |

---

## 5. E08 – Workers' Compensation, Insurance & Claims Coordination

Module `modules/wz-e08-claims` (package `com.wellnessz.e08`, schema `claims`, V15; depends on E05 through the kernel
and never on E03 – no clinical-record access). OH Workspace screens under `web/oh-workspace/src/screens/claims`
(`#/claims`, `#/claims/{id}` with tabs overview / reports / disclosures / collaboration / costs / legal); adjuster in
`web/partner-portal/src/screens/Claims.tsx`, `ClaimDetail.tsx`; HR in `web/manager-workspace/src/screens/Claims.tsx`.

### 5.1 High-level requirements

| ID | Requirement (short) | Implementation | UI screen | Verification | Status | Notes |
|---|---|---|---|---|---|---|
| E08-HLR-01 | Claim records linked to cases with type, insurer / TPA, adjuster, status, key dates, reserves and paid costs | `ClaimService`, `ClaimWorkflow`, `ClaimViews`, `ClaimsController` (`/cases/{caseId}/claims`, `/claims`, `/claims/{id}`, `/status`, `/subrogation`, `/reconciliation`, `/cases/{caseId}/claim-status`); `ClaimContextProvider`; tables `claim`, `claim_history` | OH `#/claims`, `#/claims/{id}`, case tab `#/cases/{id}/claim`, case header badge | IT-CL1, UT-CW, E2E-14, SCR `inc4-oh-10-claims`, `inc4-oh-13-case-claim` | Implemented | Types WORKERS_COMP, EMPLOYER_LIABILITY, GROUP_MEDICAL, THIRD_PARTY; money only for claims coordinator / finance / CMO |
| E08-HLR-02 | Statutory first reports and insurer notifications generated and submitted electronically within statutory timelines, with status tracking | `ReportService`, `ReportDeadlines`, `ClaimsSubmissionGateway` + `DefaultClaimsSubmissionGateway`, `ClaimReportsController`; `FIRST_REPORT_OF_INJURY`; `ClaimsJobs.reportDeadlines`; tables `report_form`, `first_report` | OH `#/claims/{id}/reports`, `#/claims/reports` | IT-CL6, UT-RD, E2E-15, SCR `inc4-oh-11-claim-reports` | Partial | Generation, timers, status and acknowledgement complete; submission leaves as an outbox event + content-free e-mail – no insurer / authority connector yet; form mapping awaits Legal / HSE sign-off (E08-BL-003) |
| E08-HLR-03 | Share only consented, claim-relevant documents with TPAs / insurers; log every disclosure | `DisclosureSetService`, `DisclosureRules`, `Redaction`, `ClaimDisclosureController`; `DocumentService` `INSURER` target + `shareAsSystem`; tables `disclosure_set`, `disclosure_set_document` | OH `#/claims/{id}/disclosures`, `#/claims/disclosures` (DPO); partner portal `#/documents` | IT-CL4, IT-CL5, UT-DR, E2E-16, E2E-22, SCR `inc4-oh-12-claim-disclosures`, `inc4-oh-17-dpo-claim-disclosures`, `inc4-partner-06-shared-documents` | Implemented | |
| E08-HLR-04 | Nurse case manager – adjuster collaboration (shared tasks, RTW visibility, secure messaging) and automatic nurse assignment on every lost-time claim | `NurseAssignmentService`, `NurseAssignment`, `CollaborationService`, `ClaimCollaborationController` (`/claims/{id}/tasks`, `/messages`, `/rtw`, `/partner/claims`); E02 assignment service; tables `claim_task`, `claim_message`, `message_read` | OH `#/claims/{id}/collaboration`, `#/claims/nurse-assignment`; partner portal `#/claims`, `#/claims/{id}` | IT-CL2, IT-CL3, UT-CW, E2E-14, E2E-17, E2E-18, SCR `inc4-oh-14-nurse-assignment`, `inc4-partner-04-adjuster-claims`, `inc4-partner-05-claim-collaboration` | Implemented | |
| E08-HLR-05 | All case-related costs by category, rolled up by subsidiary / department / cost centre, exported to finance / ERP | `CostService`, `CostMath`, `ErpExport`, `ClaimCostController` (`/claims/{id}/costs`, `/costs/rollup`, `/budgets`, `/budget-vs-actual`, `/costs/exports`); `CaseCostRecorder` (E04 entries); tables `cost_entry`, `budget`, `cost_export` | OH `#/claims/costs`, `#/claims/{id}/costs` | IT-CL7, UT-CM, E2E-19, SCR `inc4-oh-15-finance-costs` | Implemented | Export is a CSV file with checksum plus an `erp-finance` event; live ERP interface under E08-BL-008 |
| E08-HLR-06 | Medico-legal requests (reports, IMEs, expert opinions), litigation holds and privilege flags with legal workflow | `LegalService` (also `ReferralStatusListener` for `CLAIM_IME`), `LegalController` (`/claims/{id}/legal-hold`, `/privilege`, `/legal-requests`, `/legal-requests/{id}/report`, `/content`, `/close`); `DocumentService.legalHoldForCase`; `MEDICO_LEGAL_REPORT`; tables `legal_request`, `legal_report` | OH `#/legal`, `#/legal/requests`, `#/claims/{id}/legal` | IT-CL5, IT-CL8, IT-T4, E2E-20, SCR `inc4-oh-16-legal` | Implemented | |

### 5.2 Features

| ID | Feature | Implementation | UI screen | Verification | Status | Notes |
|---|---|---|---|---|---|---|
| E08-F01 | Claim record & lifecycle – claim from case in one click; status on case header; closed claims reconciled monthly | As E08-HLR-01; workflow NOTIFIED → OPEN → UNDER_REVIEW → SETTLED → CLOSED, LITIGATED from OPEN / UNDER_REVIEW, reopen with reason; `/claims/reconciliation?month=` (paid total vs ledger) | OH case tab `#/cases/{id}/claim` (`OpenClaimForm.tsx`), header `ClaimBadge.tsx`, `#/claims` | IT-CL1, IT-CL7, UT-CW, E2E-14 (CLM-2026-000001, badge NOTIFIED), WEB-OH (`Claims.test`), SCR `inc4-oh-13-case-claim` | Implemented | Extra transitions LITIGATED → SETTLED / CLOSED and NOTIFIED / UNDER_REVIEW → CLOSED (reason required, 422 `reason_required`); legal counsel may only set LITIGATED |
| E08-F02 | Statutory & insurer e-reporting – report within minutes; deadline timer; submission tracked; 0 late | As E08-HLR-02; auto-populated fields, channel API / EMAIL / PORTAL, acknowledgement store (`INSURER_ACKNOWLEDGEMENT`), hourly deadline job (`claim.report-due`, LATE + escalation), KPI `onTimeSubmissionPct` | OH `#/claims/{id}/reports`, `#/claims/reports` | IT-CL6, UT-RD, E2E-15 (FROI-2026-000001 → SUBMITTED), WEB-OH, SCR `inc4-oh-11-claim-reports` | Partial | Electronic delivery depends on insurer / authority connectors (default outbox + e-mail); "0 late submissions" is measured by the KPI and left for the pilot |
| E08-F03 | Consent-gated document exchange – no document to TPA without consent; complete disclosure log | As E08-HLR-03; allowed-type rules, OH-only provider reports, privileged / restricted / medico-legal never disclosed (422 `document_not_disclosable`), redaction to `REDACTED_COPY`, send → 422 `consent_required` without active `INSURER_SHARING`, revoke, DPO report | OH `#/claims/{id}/disclosures`, `#/claims/disclosures` | IT-CL4, IT-CL5, UT-DR, E2E-16 (3 disclosable of 6; invalid consent → 422), E2E-22 (14 disclosures), WEB-OH, SCR `inc4-oh-12-claim-disclosures`, `inc4-oh-17-dpo-claim-disclosures` | Implemented | Document chooser shows non-disclosable documents disabled with the reason |
| E08-F04 | Nurse CM ↔ adjuster collaboration – lost-time claim triggers nurse assignment within 48 h; adjuster sees RTW milestones | As E08-HLR-04; assignment on create or lost-time flag (case owner with nursing role, else least-loaded CASE_MANAGER within cap), `claim.assigned`, SLA `claims.nurse-sla-hours` (48); RTW status-only view from E05 | OH `#/claims/nurse-assignment`, `#/claims/{id}/collaboration`; partner portal `#/claims/{id}` | IT-CL2, IT-CL3, UT-CW, E2E-14, E2E-17, E2E-18 (2/2 = 100 %), WEB-OH, WEB-PP (`ClaimDetail.test`), SCR `inc4-oh-14-nurse-assignment`, `inc4-partner-05-claim-collaboration` | Implemented | Assignment is immediate; the dashboard reports the within-48-h share |
| E08-F05 | Cost tracking & finance export – monthly cost report by subsidiary; export reconciled to ERP | As E08-HLR-05; categories MEDICAL, REHAB, INDEMNITY, ADMIN, SETTLEMENT, LEGAL; roll-up counts INVOICE + PAYMENT (ESTIMATE / RESERVE are exposure on the claim); FX to `claims.base-currency` (422 `currency_not_supported`); reconcile → RECONCILED / MISMATCH | OH `#/claims/costs` (`ClaimCosts.tsx`) | IT-CL7, UT-CM, E2E-19 (export 10 rows → RECONCILED), WEB-OH, SCR `inc4-oh-15-finance-costs` | Implemented | Reconciliation compares the ERP total entered by finance; one export per month (409 `export_exists`) unless MISMATCH |
| E08-F06 | Medico-legal & litigation support – legal hold prevents deletion; IME coordinated with external provider; report versions tracked | As E08-HLR-06; hold suspends retention of every case document; IME through `ExternalReferralGateway` (E04 DRAFT referral) with status via `ReferralStatusListener`; report versions in `legal_report` | OH `#/legal`, `#/legal/requests`, `#/claims/{id}/legal` | IT-CL5, IT-CL8, IT-T4, E2E-20 (hold true; report v1; legal 200, claims coordinator 403), WEB-OH, SCR `inc4-oh-16-legal` | Implemented | Without E04 deployed the IME request shows `referralStatus` NOT_AVAILABLE and raises an OH task |

### 5.3 Implementation backlog

| ID | Title (short) | Implementation | UI screen | Verification | Status | Notes |
|---|---|---|---|---|---|---|
| E08-BL-001 | Open a claim from a case with insurer, adjuster and status workflow; dates; link; reporting (P1) | `POST /cases/{caseId}/claims` (CLAIMS_COORDINATOR, CASE_MANAGER; one per case and type – 409 `claim_exists`), `PUT /claims/{id}`, `/status` (422 `invalid_transition`), `GET /claims` filters; `claim.status.v1` event; audit `CLAIM_CREATE`, `CLAIM_STATUS` | OH `#/cases/{id}/claim`, `#/claims`, `#/claims/{id}` | IT-CL1, UT-CW, E2E-14, WEB-OH, SCR `inc4-oh-10-claims` | Implemented | `paidTotal` = insurer-reported (PUT), `ledgerTotal` = PAYMENT entries |
| E08-BL-002 | Claim status on the case header; permissions; no cost detail for clinicians unless role allows | `GET /cases/{caseId}/claim-status` (204 without claim; `paidTotal` only for CLAIMS_COORDINATOR) via `ClaimContextProvider` | OH case header `ClaimBadge.tsx` | IT-CL1, E2E-14 (badge NOTIFIED), WEB-OH, SCR `inc4-oh-13-case-claim` | Implemented | |
| E08-BL-003 | Map statutory injury-report forms and insurer formats per jurisdiction; field mappings; deadlines table; Legal / HSE sign-off (P1) | `claims.report_form` seeded `GB_OSH_F2508` (AUTH_OSH; 10 days specified injury, 15 days over-7-day), `AE_LABOUR_INJURY` (AUTH_LABOUR_AE, 48 h), `INSURER_FNOL` (payer, 72 h) with field sources; `ReportDeadlines`; `GET /claims/report-forms` | OH `#/claims/{id}/reports` | IT-CL6, UT-RD | Partial | Inventory covers the UK and UAE forms and the insurer FNOL; further jurisdictions where the group employs staff and the formal Legal / HSE sign-off are outstanding |
| E08-BL-004 | Statutory first reports auto-populated and submitted with deadline timers; submission channel; acknowledgement store (P1) | `POST /claims/{id}/reports` (one per form per claim – 409 `report_exists`), `PUT /claims/reports/{id}` (422 `report_incomplete` with field), `/submit` (signed `FIRST_REPORT_OF_INJURY`; insurer → `INSURER` share under consent, authority → legal-basis disclosure log), `/acknowledgement` (multipart / JSON), `/integration/claims/reports/{ref}/acknowledgement`; `claims-report-deadlines` job | OH `#/claims/{id}/reports`, `#/claims/reports` (deadline dashboard) | IT-CL6, UT-RD, E2E-15 (due 2026-11-01T19:19 → SUBMITTED), WEB-OH, SCR `inc4-oh-11-claim-reports` | Partial | Integration acknowledgement accepted only from the recipient's system (`org_scope`; another insurer → 404, IT-CL6). Submission channel is the default `ClaimsSubmissionGateway` (outbox event to `insurer-gateway` / `authority-gateway` + content-free e-mail); real insurer / authority connectors replace it as a `@Primary` bean |
| E08-BL-005 | DPO: disclosure to TPAs blocked without consent; every disclosure logged; redaction tool; audit report (P1) | `DisclosureSetService.send` (consent check → 422 `consent_required`), `DocumentService.shareAsSystem(INSURER)`, disclosure log, `/claims/disclosure-sets/{setId}/redactions` (`Redaction`, ≤ 50 phrases), `/revoke`, `GET /claims/disclosure-report` (DPO); audit `CLAIM_DISCLOSURE_*` | OH `#/claims/{id}/disclosures`, `#/claims/disclosures` | IT-CL4, IT-CL9, UT-DR, E2E-16, E2E-22, WEB-OH, SCR `inc4-oh-17-dpo-claim-disclosures` | Implemented | Redaction re-renders the text with phrases masked (OH roles only) |
| E08-BL-006 | Every lost-time claim auto-assigns a nurse case manager within 48 h; rule; SLA; dashboard | `NurseAssignmentService` (E02 assignment service, system actor; `claim.assigned`; `claim.nurse.assigned.v1`), `GET /claims/nurse-assignment` (`{lostTimeClaims, assignedWithin48h, pct, unassigned}`) | OH `#/claims/nurse-assignment` | IT-CL2, UT-CW, E2E-18 (2/2 = 100 %), WEB-OH, SCR `inc4-oh-14-nurse-assignment` | Implemented | |
| E08-BL-007 | TPA adjuster portal: RTW milestones and shared tasks (no clinical notes); messaging; audit (P3) | `/partner/claims`, `/partner/claims/{id}` (payer = own organisation), `/claims/{id}/rtw` (status only from E05), `/tasks`, `/messages` (screened for clinical content, encrypted, `claim.message`); audit `PARTNER_CLAIMS_READ`, `CLAIM_RTW_READ`; adjuster RTW reads disclosure-logged | Partner portal `#/claims`, `#/claims/{id}` | IT-CL3, E2E-17 (claims 2; RTW FIT_WITH_RESTRICTIONS; clinical → 422; provider → 403), WEB-PP (`ClaimDetail.test`), SCR `inc4-partner-04-adjuster-claims`, `inc4-partner-05-claim-collaboration` | Implemented | Portal access sits behind the same MFA gate as E04-BL-004 (production enrolment outstanding) |
| E08-BL-008 | Case costs by category rolled up by subsidiary and exported to the ERP monthly; export file / API; reconciliation | `GET /claims/costs/rollup` (no names; small groups suppressed), `POST /claims/costs/exports` (CSV with SHA-256, `claim.cost.export.v1` to `erp-finance`), `/content`, `/reconcile` | OH `#/claims/costs` | IT-CL7, UT-CM, E2E-19 (units 4, suppressed 2; RECONCILED; claim detail → 403), WEB-OH, SCR `inc4-oh-15-finance-costs` | Partial | Categories, roll-up, export file and reconciliation complete; the ERP API connector consuming the `erp-finance` event is not yet built – finance imports the file |
| E08-BL-009 | CMO budget vs actual injury cost by unit; trend; cost per employee; export (P3) | `PUT /claims/budgets/{year}/{unitCode}` (FINANCE), `GET /claims/costs/budget-vs-actual` (variance, monthly trend), `costPerEmployee` in the roll-up | OH `#/claims/costs` | IT-CL7, UT-CM, E2E-19, WEB-OH, SCR `inc4-oh-15-finance-costs` | Implemented | Budget vs actual is not suppressed (unit-level totals); export is the roll-up / ERP CSV |
| E08-BL-010 | Legal counsel places legal hold and requests a medico-legal report; report versions; privilege (P3) | `POST / DELETE /claims/{id}/legal-hold` → `DocumentService.legalHoldForCase` (retention suspended; `CLAIM_RECORD`), `/claims/{id}/privilege`, `/claims/{id}/legal-requests` (`legal.request` to physicians / CMO), `/legal-requests/{id}/report` (signed `MEDICO_LEGAL_REPORT`, new version each time), `/reports/{version}/content` (legal access set) | OH `#/legal`, `#/legal/requests` (`LegalRequests.tsx`) | IT-CL5, IT-CL8, E2E-20, WEB-OH, SCR `inc4-oh-16-legal` | Implemented | Privileged claims' medico-legal and IME reports are excluded from disclosure sets |
| E08-BL-011 | Claims coordinator schedules an IME with an external provider and tracks the report (P3) | `/claims/{id}/legal-requests` type IME → `ExternalReferralGateway.requestReferral` (service `IME`, sourceType `CLAIM_IME`) → E04 referral flow (provider appointment, report); `ReferralStatusListener` updates the request | OH `#/claims/{id}/legal`; E04 `#/referrals/{id}` | IT-T4, IT-CL5, IT-CL8 | Implemented | The DRAFT referral is completed and sent by OH (consent and data set apply) |
| E08-BL-012 | Subrogation / third-party liability flags; third-party details; task to legal (P3) | `POST /claims/{id}/subrogation` (`thirdPartyType` CONTRACTOR / VEHICLE_OWNER / OTHER, description screened) → flag + legal task, `legal.request` to `claims.legal-email` | OH `#/claims/{id}` (overview) | IT-CL1, WEB-OH | Implemented | |

---

## 6. Non-functional requirements and controls touched

| Area | What Increment 4 does | Evidence |
|---|---|---|
| Access control (security) | Every new endpoint carries `@PreAuthorize`; new roles CLAIMS_COORDINATOR, TPA_ADJUSTER, LEGAL_COUNSEL, FINANCE, PROCUREMENT with Keycloak scope mappings; partners scoped by `/PARTNERS/<code>` (other organisation → 404, providers → 403 on claims); claims, finance, legal and procurement roles never reach clinical screens | IT-T2, IT-CL1, IT-CL3, IT-CL7, E2E-05, E2E-17, E2E-19, WEB-OH (`roles.test`), WEB-PP |
| Strong authentication for external users | MFA gate drops PARTNER_PROVIDER / TPA_ADJUSTER without `amr` / `acr` MFA proof; partner portal shows an MFA / no-access page; switch `WZ_MFA_REQUIRED_ROLES` (off only in the local profile) | UT-MFA, WEB-PP (`App.test`) – production IdP enrolment outstanding (E04-BL-004) |
| Confidentiality (status-only views) | HR sees claim status only (no costs, payer, adjuster or clinical detail); adjusters see RTW status and milestones, never clinical notes; finance sees units without names; E08 has no dependency on the E03 clinical record; pre-auth justification reaches the payer only through the consented document | IT-CL3, IT-CL7, IT-CL9, ARCH, E2E-17, E2E-19, E2E-21 |
| Clinical-text screening | Claim notes, task titles, messages, subrogation descriptions, FROI descriptions and employee follow-up reasons are screened; violations return 422 `clinical_content_not_allowed` | IT-CL3, E2E-17 |
| Privacy by design | Consent before every external share (`TREATMENT_SHARING_PARTNER`, `INSURER_SHARING`, `TELEHEALTH_CONSULTATION`); disclosure log on partner reads, insurer shares, HR and adjuster RTW reads; statutory authority reports logged with legal basis; redaction tool; small-cell suppression in cost roll-ups; content-free notifications; outbound events carry references only (allow-lists) | IT-CL4, IT-CL6, IT-CL9, UT-DR, UT-CM, UT-NOTIF, E2E-16, E2E-22 |
| Retention and legal hold | Class `CLAIM_RECORD` (10 y) added in V13 – *proposed, pending DPO approval*; litigation hold suspends retention for every document of the case | V13, IT-CL8, E2E-20 |
| Audit | Person-level reads and all changes audited (e.g. `REFERRAL_*`, `PARTNER_REFERRAL_*`, `PREAUTH_*`, `MEDICATION_ADMINISTER`, `TELEHEALTH_*`, `CLAIM_*`, `CLAIM_DISCLOSURE_*`, `LEGAL_*`); plan, contract and medico-legal report versions kept | IT-T2, IT-CL4, IT-CL8 |
| Integrity | Single-use telehealth tokens stored hashed; ERP export SHA-256 over the exact CSV with formula-injection guard; idempotent cost recording on (sourceType, sourceRef, kind); encrypted chat and claim messages | UT-TR, UT-CM, IT-T9 |
| Modularity | E04 and E08 independent of each other and of E06 / E07 / E14; earlier modules never depend on E04 / E08; cross-module calls only through kernel SPIs (17 ArchUnit rules) | ARCH |
| SAST | SpotBugs + FindSecBugs: 0 findings | build log |

**SOC 2 trust-services criteria touched:** CC6.1 (logical access – role-gated endpoints, organisation-scoped partner
access), CC6.2 / CC6.3 (provisioning of five new roles and partner users in Keycloak), CC6.6 (external access – MFA gate
for partner and adjuster users, separate partner portal), CC6.7 (transmission – consent-gated shares, reference-only
outbound events), CC7.2 (monitoring – audit events, chase / variance / deadline alerts), CC8.1 (change management –
Flyway V13–V15, automated tests, ArchUnit rules), PI1.3 (processing integrity – claim workflow, deadline calculation,
invoice matching, export checksum and reconciliation), C1.1 / C1.2 (confidentiality – status-only views, privilege,
legal hold) and P-series privacy criteria (consent before disclosure, disclosure log and DPO report, retention).

## 7. Cross-cutting verification

| Area | Evidence |
|---|---|
| Java tests | 275 pass, 0 failures (wz-core-app 112 incl. `Increment4TreatmentIntegrationTest` 10, `Increment4ClaimsIntegrationTest` 10 and `ArchitectureTest` 17; module unit tests E04 11, E08 28; security 7 incl. the MFA gate) |
| SAST | SpotBugs + FindSecBugs: 0 findings |
| Web tests | oh-workspace 570, employee-app 223, manager-workspace 164, partner-portal 119 (new) = 1,076 – lint 0 warnings, production builds clean |
| E2E | Increment 4: 22 / 22 PASS (`tools/e2e/e2e-increment4.js`); regression: Increment 1 18 / 18, Increment 2 25 / 25, Increment 3 32 / 32 – 97 checks in total |
| Screenshots | 26 in `docs/images/screens/increment-04/` (`inc4-oh-01..17`, `inc4-partner-01..06`, `inc4-emp-01..02`, `inc4-mgr-01`) |
| Deployment | Helm component `partnerPortal` (host `partners.<domain>`, values for local / azure / gcp / hybrid; helm lint, template and kubeconform clean); CI web matrix, container build, deploy image list, dependabot |
| API contract | `docs/api/openapi.json` regenerated – 509 paths (was 391); `docs/increment-04/API-Contract-E04-E08.md` |

## 8. Known limitations carried forward

| Limitation | Affected items | Target |
|---|---|---|
| Insurer and authority submission connectors – default `ClaimsSubmissionGateway` sends an outbox event + content-free e-mail | E08-HLR-02, E08-F02, E08-BL-004 | E15 Integration |
| ERP finance interface – CSV export + `erp-finance` event only | E08-BL-008 | E15 Integration / Finance |
| FHIR exchange tested with a simulated partner; no live partner hospital | E04-BL-006 | E15 / partner onboarding |
| Telehealth video platform to be procured (link provider and token redemption ready) | E04-HLR-07, E04-F07, E04-BL-016 | Procurement + E15 |
| E-prescribing legal route and decision memo (no-op gateway) | E04-HLR-05, E04-F05, E04-BL-013 | Legal / Product Owner (OH) |
| Jurisdiction form mapping (further jurisdictions, Legal / HSE sign-off) | E08-BL-003 (and the forms used by E08-BL-004) | Legal / HSE sign-off |
| MFA enrolment for partner and adjuster users in the production IdP (Entra ID conditional access / Keycloak OTP) | E04-BL-004 (also applies to E08-BL-007) | Platform / Cyber GRC |
| Provider network data illustrative – to be replaced by procurement's contracted network | E04-BL-019 | Procurement |
| Retention class `CLAIM_RECORD` (10 y) proposed | E08-BL-010 (noted) | DPO approval |
| Volume / time targets (plan ≤ 5 min, referral-to-appointment < 5 working days, report ≤ 7 days ≥ 90 %, 0 late submissions, −15 % claim cost) measured by the KPIs but not yet against real volumes | E04-F01, E04-F02, E08-F02 (noted) | Pilot |
| Demo data cannot contain encrypted narrative text (reasons, data sets, justifications, chat); `ClaimsDemoSeeder` fills E08 demo fields in local / test profiles only | Demo / screenshots (noted) | – |
