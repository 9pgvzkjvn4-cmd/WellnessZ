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
| – | Design brief (contract between components) | Engineering | `00-design-brief.md` | – |
| – | OpenAPI 3 contract of the core API | Integrators | `api/openapi.json` | – |

Documents 1–9 were updated for increment 1 (E01 case intake, E02 clinical triage): new sections are doc 01
Sections 14.12–14.14 and 19, doc 02 Section 11, doc 03 Section 14, doc 04 Section 17, and increment-1 steps in each
setup guide and the CI/CD Guide.

Supporting material:

* `images/` – architecture and deployment diagrams (SVG + PNG)
* `images/screens/` – screenshots captured by the Playwright end-to-end test (`tools/e2e/e2e.js`) against a
  real Keycloak login, the BFF, the core API and both web apps
* `images/screens/increment-01/` – increment-1 screenshots captured by `tools/e2e/e2e-increment1.js` (employee
  referral template, fitness declaration and advice; manager referral and status-only team view; OH workspace
  queues, case tabs, escalations, handovers, intake operations, configuration screens and reception)
* Executive concept video (`WellnessZ-Executive-Briefing.mp4`, 6 min, 1080p, English subtitles) – shipped in
  the documents bundle rather than in Git

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
