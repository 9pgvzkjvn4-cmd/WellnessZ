# WellnessZ OHCM Base Framework – Design Brief (source of truth for all contributors)

Airline Occupational Health Case Management (OHCM). This repo is the **base framework**: platform services
(E15–E18 + notifications), EHS incident intake, a minimal case kernel and module SPI so that business modules
E01–E14 can be added incrementally. Clinical data stays inside the OH domain; ops/HR receive **status-only** outputs.

## Technology baseline
| Concern | Choice |
|---|---|
| Language/runtime | Java 21 LTS (Eclipse Temurin), Spring Boot 4.1.x, Spring Security 7, Hibernate 7 |
| Build | Maven 3.9 multi-module, Maven Wrapper |
| DB | PostgreSQL 16 (Flyway migrations), schemas `platform`, `masterdata`, `intake`, `casemgmt`, `analytics` |
| Auth | OIDC/OAuth2 – Microsoft Entra ID (Azure AD), Keycloak, or any OIDC IdP (Okta, Ping, Google). Multi-issuer JWT validation in core API |
| Web | React 19 + TypeScript + Vite. Web apps use the **BFF pattern** (tokens never in browser) |
| Mobile | Same React codebase wrapped with **Capacitor 8** for iOS/Android + installable PWA for any browser; OIDC Auth Code + PKCE (system browser); MDM via Managed AppConfig (Intune / Workspace ONE / Jamf) |
| Interop | HAPI FHIR R4 structures, HAPI HL7 v2.5, SNOMED CT / ICD-10 / LOINC code-system registry, ODH (Occupational Data for Health) profile hooks |
| Events | Transactional outbox in Postgres + relay → pluggable publisher (in-process default; Kafka / Azure Service Bus / GCP Pub/Sub adapters as extension points); idempotency keys; replay |
| Secrets | `ENC(...)` AES-256-GCM encrypted config values, master key from env/file; file-mounted secrets via Secrets Store CSI (Azure Key Vault, GCP Secret Manager, HashiCorp Vault) ; Workload Identity – no static cloud creds |
| Observability | Micrometer + Prometheus, OpenTelemetry tracing (OTLP), JSON structured logs (logstash encoder) with PHI-safe masking |
| Containers | Distroless/Temurin JRE non-root images, read-only root FS |
| Orchestration | Kubernetes (AKS / GKE / on-prem RKE2/OpenShift for hybrid) via Helm chart; Docker Compose for local |
| IaC | Terraform (azurerm, google) |
| CI/CD | GitHub Actions (build, test, CodeQL SAST, OWASP dependency-check, Trivy image scan, gitleaks, CycloneDX SBOM, cosign signing, Helm deploy with environments & approvals). Azure DevOps equivalent documented |

## Repository layout
```
pom.xml                         parent/aggregator
platform/wz-common              shared kernel: errors (RFC 9457 ProblemDetail), DomainEvent, DataClassification, Disclosure
platform/wz-secrets             ENC() decryption EnvironmentPostProcessor, SecretResolver (env/file), FieldEncryptor (JPA converter), EncryptCli
platform/wz-security            E16: multi-IdP JWT resource server, role/claim mapping, ABAC PolicyEngine, consent, immutable hash-chained audit
platform/wz-masterdata          E18: org hierarchy, job codes, hazard catalogue, configuration (feature flags, module enablement)
platform/wz-integration         E15: outbox event bus, idempotency, replay, FHIR R4 & HL7v2 endpoints, terminology, connector SPI
platform/wz-notification        notification gateway: channels push/SMS/email, content rules (no clinical content), quiet hours
platform/wz-analytics           E17: de-identified KPIs, k-anonymity minimum group size suppression, pseudonymised keys
platform/wz-ehs-intake          EHS incident feed: Excel import (Apache POI) + mailbox (IMAP or Microsoft Graph) → IncidentReceived
platform/wz-case-kernel         minimal Case aggregate, status history, status-only projection, CaseModule SPI (E01–E14 registry)
apps/wz-core-app                runnable core API (port 8080, management 8081), Flyway migrations
apps/wz-bff                     Backend-for-Frontend (port 8090): OIDC login, session cookie, token relay to core, CSRF, security headers
web/employee-app                React+Capacitor employee/crew portal & mobile app (dev 5173)
web/manager-workspace           React manager & HR workspace (dev 5174) – status-only views
deploy/local                    docker-compose: postgres, keycloak (realm import), mailpit (SMTP), greenmail (IMAP), otel-collector
deploy/docker                   Dockerfiles
deploy/helm/wellnessz           Helm chart
deploy/terraform/azure|gcp      IaC; deploy/hybrid             on-prem + cloud (Azure Arc / GKE Enterprise) guidance & values
.github/workflows               CI/CD
docs/                           architecture, deployment, setup guides, CI/CD guide
```

## Roles (realm/app roles, case-insensitive, mapped to `ROLE_<NAME>`)
EMPLOYEE, MANAGER, HR, OH_CLINICIAN, OH_ADMIN, CASE_MANAGER, PSYCH_CLINICIAN (restricted class E09), AUDITOR,
PLATFORM_ADMIN, INTEGRATION (machine clients), PARTNER_PROVIDER.
Claim sources: Keycloak `realm_access.roles`, Entra ID `roles` (app roles), fallback `groups`; configurable via
`wellnessz.security.role-claims`.

## Core API (apps/wz-core-app, base path /api/v1, bearer JWT)
- `GET /me` – subject, roles, disclosure level
- `GET /cases` – OH roles: full list; MANAGER/HR: status-only projection of their scope; EMPLOYEE: own cases
- `GET /cases/{id}`, `GET /cases/{id}/status` (status-only), `POST /cases/self-referral` (EMPLOYEE)
- `GET /modules` – registered modules E01–E18 with enablement
- `POST /consents`, `GET /consents/me`, `DELETE /consents/{id}` (withdraw)
- `GET /audit?from&to` (AUDITOR), `GET /audit/verify` – hash chain verification
- `GET|POST /masterdata/org-units`, `/masterdata/job-codes`, `/masterdata/hazards`, `GET|PUT /masterdata/config/{key}`
- `POST /integration/fhir/{resourceType}` (application/fhir+json), `POST /integration/hl7v2` (ER7 text → ACK),
  `POST /integration/inbound/{source}` (requires `Idempotency-Key`), `GET /integration/events?status=`, `POST /integration/events/{id}/replay`
- `POST /intake/ehs/excel` (multipart file), `POST /intake/ehs/mailbox/poll`, `GET /intake/ehs/incidents`
- `GET /analytics/kpis/cases-by-status?groupBy=orgUnit` – suppressed when group n < k (default 5)
- `POST /notifications/test` (PLATFORM_ADMIN)
- OpenAPI: `/v3/api-docs`, Swagger UI `/swagger-ui.html` (disabled in prod profile)
- Health: management port 8081 `/actuator/health/liveness|readiness`, `/actuator/prometheus`

## BFF (apps/wz-bff, port 8090)
- `/oauth2/authorization/wellnessz` login, `/login/oauth2/code/wellnessz` callback, `POST /logout`
- `GET /bff/session` → `{authenticated, name, roles}`; `/bff/api/**` → proxied to core `/api/v1/**` with access token relay
- `/bff/api/workspace/**` restricted to MANAGER/HR/OH roles. CSRF: cookie `XSRF-TOKEN`, header `X-XSRF-TOKEN`
- Session cookie `WZSESSION` HttpOnly, Secure, SameSite=Lax (Lax is required for the OIDC redirect back from the IdP; CSRF tokens protect state-changing calls)

## Environment variables
Core: `WZ_DB_URL`, `WZ_DB_USERNAME`, `WZ_DB_PASSWORD` (plain, `ENC(...)`, or `file:/path`), `WZ_MASTER_KEY` or `WZ_MASTER_KEY_FILE`,
`WZ_OIDC_ISSUERS` (comma-separated allowed issuer URIs), `WZ_OIDC_AUDIENCE`, `WZ_CORS_ORIGINS`, `WZ_ANALYTICS_MIN_GROUP`,
`WZ_PSEUDONYM_KEY`, `WZ_EHS_MAILBOX_MODE` (none|imap|graph), `WZ_IMAP_HOST/PORT/USERNAME/PASSWORD`, `WZ_GRAPH_TENANT_ID/CLIENT_ID/CLIENT_SECRET/MAILBOX`,
`WZ_SMTP_HOST/PORT`, `OTEL_EXPORTER_OTLP_ENDPOINT`, `SPRING_PROFILES_ACTIVE` (local|azure|gcp|hybrid|prod)
BFF: `WZ_BFF_ISSUER_URI`, `WZ_BFF_CLIENT_ID`, `WZ_BFF_CLIENT_SECRET`, `WZ_CORE_API_URL`, `WZ_BFF_POST_LOGOUT_URI`
Mobile (MDM AppConfig keys): `wz.apiBaseUrl`, `wz.oidcIssuer`, `wz.oidcClientId`, `wz.redirectUri`, `wz.requireBiometric`

## Local Keycloak (deploy/local)
Realm `wellnessz`, clients: `wellnessz-bff` (confidential, redirect `http://localhost:8090/login/oauth2/code/wellnessz`),
`wellnessz-mobile` (public, PKCE, redirect `com.wellnessz.app://callback` and `http://localhost:5173/*`), `wellnessz-api` (audience),
`wellnessz-integration` (service account, role INTEGRATION). Test users (password `ChangeMe!123`, temporary):
`employee1` EMPLOYEE, `manager1` MANAGER, `hr1` HR, `clinician1` OH_CLINICIAN+CASE_MANAGER, `psych1` PSYCH_CLINICIAN, `auditor1` AUDITOR, `admin1` PLATFORM_ADMIN+OH_ADMIN.
Ports: postgres 5432, keycloak 8180, mailpit UI 8025 / SMTP 1025, greenmail IMAP 3143, core 8080/8081, bff 8090.
