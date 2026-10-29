# WellnessZ OHCM – Documentation Pack

Reading order for review with Cyber GRC, the CIO and the project sponsor.

| # | Document | Audience | Source | Rendered |
|---|---|---|---|---|
| 1 | Technology Architecture & Technology Selection | CIO, architects, GRC | `architecture/01-Technology-Architecture-and-Technology-Selection.md` | `dist/*.pdf`, `dist/*.docx` |
| 2 | Security & SOC 2 Control Mapping (incl. STRIDE threat model) | Cyber GRC, auditors | `architecture/02-Security-and-SOC2-Control-Mapping.md` | `dist/` |
| 3 | Developer Guide – adding modules incrementally | Engineering | `architecture/03-Developer-Guide.md` | `dist/` |
| 4 | Deployment Architecture (MacBook, Azure, GCP, Hybrid) | CIO, infrastructure, GRC | `deployment/Deployment-Architecture.md` | `dist/` |
| 5 | Setup guide – Local MacBook Pro | Developers | `setup/01-Local-MacBook-Pro.md` | `dist/` |
| 6 | Setup guide – Microsoft Azure | Platform / cloud team | `setup/02-Azure.md` | `dist/` |
| 7 | Setup guide – Google Cloud | Platform / cloud team | `setup/03-GCP.md` | `dist/` |
| 8 | Setup guide – Hybrid cloud | Platform / infrastructure | `setup/04-Hybrid-Cloud.md` | `dist/` |
| 9 | CI/CD Guide | Developers, DevSecOps | `cicd/CI-CD-Guide.md` | `dist/` |
| – | Design brief (contract between components) | Engineering | `00-design-brief.md` | – |
| – | OpenAPI 3 contract of the core API | Integrators | `api/openapi.json` | – |

Supporting material:

* `images/` – architecture and deployment diagrams (SVG + PNG)
* `images/screens/` – screenshots captured by the Playwright end-to-end test (`tools/e2e/e2e.js`) against a
  real Keycloak login, the BFF, the core API and both web apps
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
