# WellnessZ – Airline Occupational Health Case Management (OHCM)

Base framework for an airline occupational-health platform. It delivers the **platform services** of the
logical architecture (E15 integration & API gateway, E16 identity / ABAC / consent / immutable audit,
E17 de-identified analytics, E18 master data & no-code configuration, notification gateway), the
**EHS incident intake** (mailbox or Excel), a **case kernel** with the status-only disclosure rule, and the
**employee/crew app & portal** plus the **manager & HR workspace**. Business modules E01–E14 plug in incrementally.

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
| `platform/wz-case-kernel` | Case aggregate, pathway state machine, status-only projection, `CaseModule` SPI, incident-to-case rules |
| `platform/wz-ehs-intake` | EHS feed: IMAP / Microsoft Graph mailbox reader and hardened Excel import |
| `apps/wz-core-app` | Runnable core API (8080, management 8081) + Flyway migrations |
| `apps/wz-bff` | Backend-for-Frontend (8090): OIDC login, encrypted JDBC sessions, CSRF, token relay |
| `web/employee-app` | React + Capacitor crew app (PWA / iOS / Android, MDM AppConfig) |
| `web/manager-workspace` | React manager & HR workspace (status-only) |
| `deploy/` | Docker Compose (local), Dockerfiles, Helm chart, Terraform (Azure, GCP), hybrid blueprint |
| `.github/` | CI, CodeQL, container build/sign, CD workflows, Dependabot |
| `docs/` | Architecture, SOC 2 mapping, deployment architecture, setup guides, CI/CD guide, OpenAPI |
| `tools/` | `encrypt-secret.sh`, Playwright end-to-end smoke test |

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
* `npm run lint && npm test && npm run build` in each `web/*` app
* CI adds CodeQL, gitleaks, Trivy image scanning, SBOM attestation and cosign signing

## Documentation

Start with `docs/README.md`.
