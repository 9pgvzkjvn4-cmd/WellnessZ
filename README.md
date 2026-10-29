# WellnessZ – Airline Occupational Health Case Management (OHCM)

Base framework for an airline occupational-health platform. It delivers the **platform services** of the
logical architecture (E15 integration & API gateway, E16 identity / ABAC / consent / immutable audit,
E17 de-identified analytics, E18 master data & no-code configuration, notification gateway), the
**EHS incident intake** (mailbox or Excel), a **case kernel** with the status-only disclosure rule, and the
**employee/crew app & portal** plus the **manager & HR workspace**. Business modules E01–E14 plug in incrementally;
**increment 1** delivered E01 (case intake & incident-triggered case initiation) and E02 (clinical triage, risk
stratification, triage queue, SLA & case assignment) with the **OH clinical workspace**; **increment 2** delivered
E03 (OH record & clinical encounters) and E05 (fitness for duty, restrictions & return to work) together with the
platform **document service** (PDF generation, e-signature, consented sharing, retention and legal hold), the `DPO`
role, break-glass access and a disclosure log.

> Core rule: clinical data stays inside the Occupational Health domain; operations and HR receive
> **status-only** outputs. It is enforced in the ABAC policy engine, in the API projections, at the outbound
> integration allow-list, and by automated tests.

## Repository map

| Path | What |
|---|---|
| `platform/wz-common` | Shared kernel: RFC 9457 errors, correlation ids, classification & disclosure enums, IdP-neutral role mapping |
| `platform/wz-secrets` | `ENC(...)` AES-256-GCM config secrets, `file:` mounted secrets, field-level encryption, `EncryptCli` |
| `platform/wz-security` | E16 – multi-issuer OIDC resource server, ABAC policy engine, consent, hash-chained audit |
| `platform/wz-masterdata` | E18 – org hierarchy, job codes, hazards, versioned configuration / feature flags |
| `platform/wz-integration` | E15 – transactional outbox event bus, idempotency, replay, FHIR R4, HL7 v2, terminology, connectors |
| `platform/wz-notification` | Push / SMS / e-mail gateway with content rules and quiet hours |
| `platform/wz-analytics` | E17 – de-identified KPIs, small-cell + complementary suppression, pseudonymisation |
| `platform/wz-case-kernel` | Case aggregate, pathway state machine, status-only projection, `CaseModule` and `CaseTransitionGuard` SPIs, case numbering, timeline, tasks, attachments |
| `platform/wz-documents` | Document service (increment 2): code-defined templates, PDF rendering with Apache PDFBox, SHA-256 e-signature, versioning, sharing with consent and disclosure log, scanned documents with optional OCR, blind-index search, retention schedule and legal hold |
| `platform/wz-ehs-intake` | EHS feed: IMAP / Microsoft Graph mailbox reader and hardened Excel import |
| `modules/wz-e01-intake` | E01 – intake rule table, EHS incident rules, referrals, walk-in/emergency encounters, system triggers, duplicates, recordability, notification matrix |
| `modules/wz-e02-triage` | E02 – triage protocols (versioned JSON), SLA timers, assignment engine, work queues, escalations, complexity, status-only crew / food-handler APIs |
| `modules/wz-e03-record` | E03 (increment 2, schema `ohrecord`) – OH chart, versioned encounter templates (JSON) with body map and e-signature, terminology, lab orders / results (HL7 v2 pull queue, ORU), device capture, CDS, FHIR R4 / ODH export, AI documentation SPI (no provider) |
| `modules/wz-e05-fitness` | E05 (increment 2, schema `fitness`) – fitness decisions and sign-off, restrictions and job-demand matching, modified duty, RTW plans, job offers, crew roster feed with acknowledgement, food handlers, ill-health retirement |
| `apps/wz-core-app` | Runnable core API (8080, management 8081) + Flyway migrations (`db/migration`; demo data `db/demo` for the `local` profile only) |
| `apps/wz-bff` | Backend-for-Frontend (8090): OIDC login, encrypted JDBC sessions, CSRF, token relay |
| `web/employee-app` | React + Capacitor crew app (PWA / iOS / Android, MDM AppConfig) |
| `web/manager-workspace` | React manager & HR workspace (status-only, manager and HR referrals) |
| `web/oh-workspace` | React OH clinical workspace (dev port 5175): queues, triage, assignment, escalation, intake operations, configuration, reception |
| `deploy/` | Docker Compose (local), Dockerfiles, Helm chart, Terraform (Azure, GCP), hybrid blueprint |
| `.github/` | CI, CodeQL, container build/sign, CD workflows, Dependabot |
| `docs/` | Architecture, SOC 2 mapping, deployment architecture, setup guides, CI/CD guide, OpenAPI |
| `tools/` | `encrypt-secret.sh`; `tools/e2e/` Playwright end-to-end tests: `e2e.js` (base framework), `e2e-increment1.js` (E01/E02, 18 checks), `e2e-increment2.js` (E03/E05, 25 checks) |

## Quick start (developer laptop)

Prerequisites: JDK 21, Docker, Node 24. Full step-by-step instructions: `docs/setup/01-Local-MacBook-Pro.md`.

```bash
cp deploy/local/.env.example deploy/local/.env   # then fill in local-only values
```

```bash
./deploy/local/up.sh
```

```bash
./mvnw verify
```

Integration tests use PostgreSQL via Testcontainers, or an existing database when `WZ_TEST_DB_URL`
(+ `_USERNAME`, `_PASSWORD`, `_MIGRATOR_USERNAME`, `_MIGRATOR_PASSWORD`) is set.

## Quality gates

* `./mvnw verify` – unit, integration (real PostgreSQL), ArchUnit boundary tests, JaCoCo, CycloneDX SBOM
* `./mvnw -Psecurity verify` – SpotBugs + FindSecBugs SAST and OWASP dependency-check (needs `NVD_API_KEY`)
* `npm run lint && npm test && npm run build` in each `web/*` app (`employee-app`, `manager-workspace`, `oh-workspace`)
* `node tools/e2e/e2e-increment1.js` and `node tools/e2e/e2e-increment2.js` against the running local stack (release gate)
* CI adds CodeQL, gitleaks, Trivy image scanning, SBOM attestation and cosign signing

## Documentation

Start with `docs/README.md`. Increment 1 documents are in `docs/increment-01/`, increment 2 documents in
`docs/increment-02/`.
