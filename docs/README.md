# WellnessZ OHCM – Documentation Pack

Reading order for review with Cyber GRC, the CIO and the project sponsor. Rendered PDF/DOCX copies are
generated into `docs/dist/` (git-ignored) and shipped in the documents bundle.

| # | Document | Audience | Source | Rendered |
|---|---|---|---|---|
| 1 | Technology Architecture & Technology Selection | CIO, architects, GRC | `architecture/01-Technology-Architecture-and-Technology-Selection.md` | PDF + DOCX |
| 2 | Security & SOC 2 Control Mapping (incl. STRIDE threat model) | Cyber GRC, auditors | `architecture/02-Security-and-SOC2-Control-Mapping.md` | PDF + DOCX |
| 3 | Developer Guide – adding modules incrementally | Engineering | `architecture/03-Developer-Guide.md` | PDF + DOCX |
| 4 | Deployment Architecture (MacBook, Azure, GCP, Hybrid) | CIO, infrastructure, GRC | `deployment/Deployment-Architecture.md` | PDF + DOCX |
| 5 | Setup guide – Local MacBook Pro | Developers | `setup/01-Local-MacBook-Pro.md` | PDF + DOCX |
| 6 | Setup guide – Microsoft Azure | Platform / cloud team | `setup/02-Azure.md` | PDF + DOCX |
| 7 | Setup guide – Google Cloud | Platform / cloud team | `setup/03-GCP.md` | PDF + DOCX |
| 8 | Setup guide – Hybrid cloud | Platform / infrastructure | `setup/04-Hybrid-Cloud.md` | PDF + DOCX |
| 9 | CI/CD Guide | Developers, DevSecOps | `cicd/CI-CD-Guide.md` | PDF + DOCX |
| 10 | Increment 1 – Solution Design E01/E02 | Architects, OH governance, engineering | `increment-01/01-Solution-Design-E01-E02.md` | – |
| 11 | Increment 1 – Requirements Traceability Matrix | Project sponsor, QA, OH governance | `increment-01/02-Requirements-Traceability-Matrix.md` | – |
| 12 | Increment 1 – Release Notes | All | `increment-01/03-Release-Notes-Increment-1.md` | – |
| 13 | Increment 1 – API Contract E01/E02 | Engineering, integrators | `increment-01/API-Contract-E01-E02.md` | – |
| 14 | Increment 2 – Solution Design E03/E05 | Architects, OH governance, DPO, engineering | `increment-02/01-Solution-Design-E03-E05.md` | – |
| 15 | Increment 2 – Requirements Traceability Matrix | Project sponsor, QA, OH governance | `increment-02/02-Requirements-Traceability-Matrix.md` | – |
| 16 | Increment 2 – Release Notes | All | `increment-02/03-Release-Notes-Increment-2.md` | – |
| 17 | Increment 2 – API Contract E03/E05 | Engineering, integrators | `increment-02/API-Contract-E03-E05.md` | – |
| 18 | Increment 3 – Solution Design E06/E07/E14 | Architects, OH governance, DPO, engineering | `increment-03/01-Solution-Design-E06-E07-E14.md` | – |
| 19 | Increment 3 – Requirements Traceability Matrix | Project sponsor, QA, OH governance | `increment-03/02-Requirements-Traceability-Matrix.md` | – |
| 20 | Increment 3 – Release Notes | All | `increment-03/03-Release-Notes-Increment-3.md` | – |
| 21 | Increment 3 – API Contract E06/E07/E14 | Engineering, integrators | `increment-03/API-Contract-E06-E07-E14.md` | – |
| 22 | Increment 4 – Solution Design E04/E08 | Architects, OH governance, DPO, legal, engineering | `increment-04/01-Solution-Design-E04-E08.md` | – |
| 23 | Increment 4 – Requirements Traceability Matrix | Project sponsor, QA, OH governance | `increment-04/02-Requirements-Traceability-Matrix.md` | – |
| 24 | Increment 4 – Release Notes | All | `increment-04/03-Release-Notes-Increment-4.md` | – |
| 25 | Increment 4 – API Contract E04/E08 | Engineering, integrators, partner and insurer IT | `increment-04/API-Contract-E04-E08.md` | – |
| – | Design brief (contract between components) | Engineering | `00-design-brief.md` | – |
| – | OpenAPI 3 contract of the core API | Integrators | `api/openapi.json` | – |

Documents 1–9 were updated for increment 1 (E01 case intake, E02 clinical triage): new sections are doc 01
Sections 14.12–14.14 and 19, doc 02 Section 11, doc 03 Section 14, doc 04 Section 17, and increment-1 steps in each
setup guide and the CI/CD Guide.

Documents 1–9 were updated again for increment 2 (E03 OH record & clinical encounters, E05 fitness for duty,
restrictions & return to work): doc 01 Sections 14.15–14.21 and 20 (plus module status, schemas, licensing, risks and
open items), doc 02 Section 12, doc 03 Section 15, doc 04 Section 18, increment-2 steps in each setup guide and
Section 3.2 and the checklists of the CI/CD Guide.

Documents 1–9 were updated again for increment 3 (E06 absence & sickness management, E07 medical surveillance,
statutory medicals & immunisation, E14 scheduling, clinic operations & resource management): doc 01 Sections
14.22–14.25 and 21 (plus module status, schemas, roles, SPIs, risks and open items), doc 02 Section 13, doc 03
Section 16, doc 04 Section 19, increment-3 steps in each setup guide and Sections 3.1, 3.3 and the checklists of the
CI/CD Guide.

Documents 1–9 were updated again for increment 4 (E04 injury & illness management, treatment coordination & partner
referrals, E08 workers' compensation, insurance & claims coordination, partner portal): doc 01 Sections 14.26–14.30
and 22 (plus module status, schemas, roles, SPIs, MFA for external users, FHIR partner exchange, gateways, risks,
decisions and open items), doc 02 Section 14, doc 03 Section 17, doc 04 Section 20, increment-4 steps in each setup
guide (partner portal, MFA for partner and adjuster users, new schemas and roles) and Sections 3.1, 3.4, 10.3 and the
checklists of the CI/CD Guide. Rendered copies in `docs/dist/` must be regenerated.

Supporting material:

* `images/` – architecture and deployment diagrams (SVG + PNG)
* `images/screens/` – screenshots captured by the Playwright end-to-end test (`tools/e2e/e2e.js`) against a
  real Keycloak login, the BFF, the core API and both web apps
* `images/screens/increment-01/` – increment-1 screenshots captured by `tools/e2e/e2e-increment1.js` (employee
  referral template, fitness declaration and advice; manager referral and status-only team view; OH workspace
  queues, case tabs, escalations, handovers, intake operations, configuration screens and reception)
* `images/screens/increment-02/` – increment-2 screenshots captured by `tools/e2e/e2e-increment2.js` (OH chart,
  signed encounter, results worklist, fitness decision, restrictions and duties, RTW plan, documents, crew roster feed
  and dashboard, restricted record hidden, fitness sign-off, admin screens for fitness standards, job demands,
  encounter templates and the restriction catalogue; DPO break-glass reviews, disclosure log and retention; manager
  team restrictions, duty proposals, RTW plans and documents; HR job offers; employee documents, RTW plan, job offer
  and privacy)
* `images/screens/increment-03/` – increment-3 screenshots captured by `tools/e2e/e2e-increment3.js` (37 images: OH
  absence queue, trigger rules, feed quality, certificates, LTA pathway, advice report, absence analytics, programmes
  and programme builder, due list, exposure registers, equipment, follow-ups, return after illness, pre-employment,
  crew certificates, immunisation, compliance, chart tabs, diary, reception kiosk, queue board, campaigns, capacity and
  the service catalogue; employee advice review, absence, candidate portal, surveillance and appointments; manager / HR
  absence, advice requests, team absence, pre-employment and compliance)
* `images/screens/increment-04/` – increment-4 screenshots captured by `tools/e2e/e2e-increment4.js` (26 images: OH
  case treatment plan, referral worklist, provider directory and scorecards, medications, case outcomes, telehealth,
  recovery variance, chart treatment tab, claims, claim reports, claim disclosure sets, case claim tab, nurse
  assignment, finance costs, legal, DPO claim disclosures; partner portal referral inbox and detail,
  pre-authorisations, adjuster claims, claim collaboration and shared documents; employee treatment plan and
  telehealth; HR claim status)
* Executive videos for C-level stakeholders and the sponsor (1080p, English subtitles), shipped in the documents
  bundle (`05-Video/`) rather than in Git:
  * `WellnessZ-Executive-Briefing.mp4` – base framework concept (6 min)
  * `WellnessZ-Increment1-Executive-Briefing.mp4` – increment 1: intake rules, triage queues & SLAs (6 min)
  * `WellnessZ-Increment2-Executive-Briefing.mp4` – increment 2: health record, fitness for duty & return to work (6 min)
  * `WellnessZ-Increment3-Executive-Briefing.mp4` – increment 3: absence & sickness, medical surveillance, scheduling & clinic operations (6 min)
  * `WellnessZ-Increment4-Executive-Briefing.mp4` – increment 4: treatment coordination & partner referrals, claims coordination and the partner portal (6 min)

## Verification summary (base framework)

| Check | Result |
|---|---|
| Java build & tests (`./mvnw verify`) | 43 tests – unit, integration on PostgreSQL 16, ArchUnit boundaries, BFF security – all passing |
| SAST (SpotBugs + FindSecBugs, effort Max) | 0 findings; 4 justified exclusions in `config/spotbugs-exclude.xml` |
| Web apps (`npm run lint && npm test && npm run build`) | employee-app 30 tests, manager-workspace 35 tests – passing |
| End-to-end (Playwright, Keycloak 26.8.1) | OIDC login via BFF, HttpOnly session cookie, no tokens in browser storage, self-referral → manager sees status-only row without clinical text, employee denied on workspace API |
| Infrastructure as code | `helm lint` / `helm template` + kubeconform for all values files; `terraform validate` (Azure, GCP); Trivy config scan 0 HIGH/CRITICAL; actionlint clean |
| Not yet exercised | `terraform apply` in real subscriptions, AKS/GKE/OpenShift installs, native iOS/Android builds, OWASP dependency-check (needs `NVD_API_KEY` in CI) |

## Verification summary (increment 1 – E01, E02)

| Check | Result |
|---|---|
| Java build & tests (`./mvnw verify`) | 65 tests – adds `Increment1IntegrationTest`, `QueuePerformanceTest` (5,000 open cases, three queue calls < 2 s), `RuleEngineTest`, `RecordabilityClassifierTest`, `ProtocolEvaluatorTest`, `ClinicalTextDetectorTest`; 6 ArchUnit rules incl. kernel → module and E01 → E02 bans – all passing |
| SAST (SpotBugs + FindSecBugs, effort Max) | 0 findings; 5 justified exclusions in `config/spotbugs-exclude.xml` (new: constant-fragment queue SQL in `QueueService`) |
| Web apps (`npm run lint && npm test && npm run build`) | oh-workspace 151 tests, employee-app 81, manager-workspace 55 – passing |
| End-to-end (`tools/e2e/e2e-increment1.js`, real Keycloak login via the BFF) | 18/18 checks: EHS webhook → auto-created, routed case; self-referral via UI; manager referral clinical-text guard and org scope; status-only manager view; queues; reassignment; red-flag hard stop; triage; escalation; SLA clock; first-aid suggestion; DLQ; reception and crew-scheduling restrictions |
| Defect found by E2E and fixed | Keycloak clients `wellnessz-bff` / `wellnessz-mobile` (`fullScopeAllowed=false`) lacked scope mappings for the new roles |
| Not yet exercised | Increment-1 changes on AKS/GKE/hybrid; Entra ID app roles for the nine new roles; clinical approval of the 13 draft triage protocols |

## Verification summary (increment 2 – E03, E05)

| Check | Result |
|---|---|
| Java build & tests (`./mvnw verify`) | 128 tests – adds `Increment2RecordIntegrationTest`, `Increment2FitnessIntegrationTest`, `Increment2PlatformIntegrationTest`, HL7 message, device-rule, result-flag, CDS, terminology, decision-rule, matching-engine and crew-dashboard unit tests, BFF `ApiProxyPathTest`; 10 ArchUnit rules (new: document service independent of business modules, E03 ↔ E05 independence, earlier modules independent of later ones) – all passing |
| SAST (SpotBugs + FindSecBugs, effort Max) | 0 findings; 6 justified exclusions in `config/spotbugs-exclude.xml` (new: allow-listed Tesseract command line in `TesseractOcrProvider`) |
| Web apps (`npm run lint && npm test && npm run build`) | oh-workspace 305 tests, employee-app 138, manager-workspace 99 – passing |
| End-to-end (`tools/e2e/e2e-increment2.js`, real Keycloak login via the BFF) | 25/25 checks: chart summary, CDS block, terminology search, signed injury encounter, device audiogram with threshold shift, fitness decision, certificate signed and shared, job-demand matching, modified duty and RTW plan, manager functional restrictions only and no chart access, HR job offer, employee acknowledgements, disclosure log, AME crew assessment, status-only roster feed, restricted psychological case, break-glass, DPO review, DPO without clinical access |
| API contract | `api/openapi.json` regenerated – 240 paths |
| New dependency | Apache PDFBox 3.0.8 (Apache-2.0) – document generation and PDF text extraction |
| Not yet exercised | Increment-2 changes on AKS/GKE/hybrid; Entra `DPO` app role and partner `org_scope` claims; real laboratory, device and rostering gateways; Tesseract or cloud OCR; clinical / DPO approval of templates, standards, CDS rules and the retention schedule |

## Verification summary (increment 3 – E06, E07, E14)

| Check | Result |
|---|---|
| Java build & tests (`./mvnw verify`) | 210 tests – wz-core-app 89 (adds `Increment3AbsenceIntegrationTest` 10, `Increment3SurveillanceIntegrationTest` 9, `Increment3SchedulingIntegrationTest` 9), module unit tests E06 18, E07 13, E14 18, the notification content-rule test over every template; 14 ArchUnit rules (new: E03/E05 independent of increment 3, E06 / E07 / E14 mutually independent and E14 independent of the clinical modules) – all passing. Recreate the test database before each run when `WZ_TEST_DB_URL` points at a persistent database (encryption keys are generated per test JVM) |
| SAST (SpotBugs + FindSecBugs, effort Max) | 0 findings; still 6 justified exclusions in `config/spotbugs-exclude.xml` (none added) |
| Web apps (`npm run lint && npm test && npm run build`) | oh-workspace 463 tests, employee-app 191, manager-workspace 154 – passing, lint 0 warnings |
| End-to-end (`tools/e2e/e2e-increment3.js`, real Keycloak login via the BFF) | 32/32 checks (increment 1 18/18 and increment 2 25/25 re-run): absence feed with reconciliation, trigger rules and early-intervention queue, certificate upload and validation (HR sees validity only), LTA milestones, clinical-text guard on HR stages and advice reports, employee consent before advice release, absence analytics with suppression; surveillance simulation, scheduler booking, IH import above the action level, calibration block (`422`) then acceptance, follow-up cases, return-after-illness clearance guard (`422 clearance_required`), candidate portal and HR clearance status, crew certificate expiry, GS1 vaccine entry, anonymised audit pack; double-booking `409`, self-booking and reschedule, badge check-in and queue board without names, call-next, cold-chain excursion, campaign cohort booking and capacity forecast |
| Defect found by E2E and fixed | The BFF parsed browser multipart uploads itself and forwarded an empty body (`400`); `spring.servlet.multipart.enabled=false` in `apps/wz-bff` now streams uploads untouched |
| API contract | `api/openapi.json` regenerated – 391 paths (was 240) |
| New dependency | none |
| Not yet exercised | Increment-3 changes on AKS/GKE/hybrid; Entra app roles `CLINIC_SCHEDULER`, `BIOMEDICAL`, `CREW_LICENSING`, `CANDIDATE` and a candidate identity solution; real HRIS / time & attendance / crew absence connectors (`AbsenceFeedConnector` default no-op), IoT fridge gateway, telehealth video; CMO validation of the 13 surveillance programmes and CAA confirmation of the crew examination formats |

## Verification summary (increment 4 – E04, E08, partner portal)

| Check | Result |
|---|---|
| Java build & tests (`./mvnw verify`) | 275 tests – wz-core-app 112 (adds `Increment4TreatmentIntegrationTest` 10, `Increment4ClaimsIntegrationTest` 10), module unit tests E04 11 (`TreatmentRulesTest`), E08 28, `wz-security` 7 including `MfaRoleGateTest` (3); 17 ArchUnit rules (new: earlier increments independent of E04 / E08, E04 and E08 mutually independent, E08 never depends on the clinical record E03) – all passing. Recreate the test database before each run when `WZ_TEST_DB_URL` points at a persistent database |
| SAST (SpotBugs + FindSecBugs, effort Max) | 0 findings; still 6 justified exclusions in `config/spotbugs-exclude.xml` (none added) |
| Web apps (`npm run lint && npm test && npm run build`) | oh-workspace 570 tests, employee-app 223, manager-workspace 164, partner-portal 119 (new) – 1,076, passing, lint 0 warnings, all builds clean |
| End-to-end (`tools/e2e/e2e-increment4.js`, real Keycloak logins via the BFF, four apps) | 22/22 checks (increment 1 18/18, increment 2 25/25 and increment 3 32/32 re-run – 97 checks): injury report with partner and insurer consent, treatment plan and employee view, referral data-set and consent checks with letter and FHIR payload, partner accept / appointment / seen / report / invoice, other organisation `404`, report rating and overdue worklist, provider directory and scorecards, pre-authorisation approved by the TPA adjuster, expired medication batch refused, outcomes, telehealth consent and secure chat, treatment KPIs; claim with automatic nurse case manager and case badge, first report of injury with statutory deadline, consent-gated disclosure set visible to the adjuster with disclosure log, adjuster collaboration with clinical wording refused and providers without claim access, nurse assignment KPI, finance roll-up and reconciled ERP export without claim detail, legal hold and medico-legal report, HR status only, DPO disclosure report |
| Helm | New component `partnerPortal` (host `partners.<domain>`) – `helm lint`, `helm template` and kubeconform clean for all values files |
| API contract | `api/openapi.json` regenerated – 509 paths (was 391) |
| New dependency | none (new web app with the same pinned toolchain as the other apps) |
| Not yet exercised | Increment-4 changes on AKS/GKE/hybrid; MFA enrolment in the production IdP (Entra ID Conditional Access for B2B guests or Keycloak OTP / WebAuthn) and the `amr` / `acr` / `acrs` claims it produces; the fixed `org_scope` claim on partner / insurer machine clients in the production IdP (organisation binding is enforced by the core and covered by the integration tests); real insurer, authority and ERP connectors (`ClaimsSubmissionGateway` default = outbox + e-mail); a live FHIR exchange with a partner hospital (tested with a simulated partner); a telehealth video platform (link provider + token redemption ready); e-prescribing (decision memo pending); Legal / HSE sign-off of the report forms; procurement's contracted provider network |
