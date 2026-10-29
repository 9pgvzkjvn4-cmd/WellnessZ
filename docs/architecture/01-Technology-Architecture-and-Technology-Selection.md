# WellnessZ OHCM – Technology Architecture and Technology Selection

| | |
|---|---|
| **Document** | 01 – Technology Architecture and Technology Selection |
| **System** | WellnessZ Airline Occupational Health Case Management (OHCM) – base framework |
| **Audience** | Chief Information Officer, Cyber Governance, Risk & Compliance (GRC) team, architecture board |
| **Baseline** | Repository working tree, Maven version `0.1.0-SNAPSHOT`, Helm chart `0.1.0` |
| **Status** | Draft for steering-committee review |
| **Companion documents** | 02 – Security and SOC 2 Control Mapping · 03 – Developer Guide |

> **How to read this document.** Every statement about the platform was checked against the source code in the
> repository. Capabilities are labelled consistently:
> **Implemented** – present in the code and covered by tests where noted;
> **Extension point** – an interface/SPI, endpoint or configuration hook exists, but the production adapter, mapping or
> content is delivered in a later increment;
> **Planned** – catalogued in the architecture (for example in `ModuleRegistry` or `ConnectorRegistry`) but not yet built.
> Target figures (RPO/RTO, retention) are proposals for decision, not commitments.

## 1. Executive summary

WellnessZ is the airline's Occupational Health (OH) case-management platform. This repository contains its **base
framework**: the platform services that every business module depends on, the primary intake feed from the EHS
incident-management system, a minimal case kernel, two web front-ends and a mobile app shell. Business modules E01–E14
are added incrementally on top of this framework through a defined module SPI.

**What exists today (implemented):**

- A **modular monolith** core API (`apps/wz-core-app`) on Java 21 and Spring Boot 4.1.2, composed of nine Maven modules
  whose boundaries are enforced by ArchUnit tests.
- **Identity, access control and audit (E16):** OAuth2 resource server that accepts tokens from several trusted OIDC
  issuers at once (Microsoft Entra ID, Keycloak, Okta and others), vendor-neutral role-claim mapping including Entra ID
  group object-IDs, an attribute-based access-control (ABAC) policy engine with *deny-overrides* and four disclosure
  levels, a consent register, and a **tamper-evident, hash-chained, append-only audit trail** with a verification API.
- **The status-only disclosure rule** enforced in three independent places: ABAC projection on every case read
  (managers/HR receive a data type that structurally has no clinical fields), an outbound field allow-list on every
  enterprise destination (default deny), and content rules on notifications.
- **Integration (E15):** transactional-outbox event bus with at-least-once delivery, exponential back-off,
  dead-lettering and operator replay; idempotent consumers; IETF `Idempotency-Key` handling on inbound APIs; HL7 FHIR R4
  and HL7 v2.5 inbound endpoints; a terminology registry for SNOMED CT, ICD-10/11, LOINC and ODH.
- **EHS incident intake** – the primary trigger feed – from a mailbox (IMAP or Microsoft Graph) or Excel upload,
  hardened against malicious workbooks and spoofed senders, de-duplicated, and turned into OH cases by a rule SPI.
- **Data protection:** AES-256-GCM field-level encryption of clinical free text with versioned, HKDF-derived keys;
  encrypted configuration secrets (`ENC(...)`); file-mounted secrets via the Kubernetes Secrets Store CSI driver and
  workload identity; encrypted BFF session store.
- **Analytics (E17):** de-identified database view, KPI API with k-anonymity small-cell suppression (minimum group
  size default 5, floor 3) and complementary suppression; HMAC pseudonymisation utility.
- **Front-ends:** React 19 employee/crew portal delivered as web, installable PWA and Capacitor 8 iOS/Android app
  (OIDC Authorization Code + PKCE, MDM managed app configuration); React 19 manager/HR workspace (status-only).
  Browser apps use a **Backend-for-Frontend** (`apps/wz-bff`) so that tokens never reach the browser.
- **Deployment assets** for Azure (AKS), Google Cloud (GKE) and a hybrid on-premises option (RKE2/OpenShift with
  CloudNativePG): hardened Helm chart, Terraform, Dockerfiles, local Docker Compose stack.

**What is deliberately not yet built:** the clinical business modules E02–E04, E06–E12 and E14 (E01, E05 and E13 exist
only as kernel seeds), production broker adapters (Kafka / Azure Service Bus / Google Pub/Sub), enterprise connector
mappings (HRIS, rostering transport, ERP, insurers), the partner provider portal, the data-warehouse feed and predictive
models, data-retention jobs, break-glass access and maker-checker approval of configuration changes. Section 17 lists
the increments; Section 16 lists the decisions the steering committee is asked to take.

**Assessment for the CIO.** The framework establishes the controls that are hardest to retrofit – identity federation,
ABAC with disclosure levels, tamper-evident audit, encryption and key derivation, the outbox/idempotency backbone and the
status-only boundary – before any clinical module is written. The principal residual risks are operational (key
management and rotation procedure, retention, broker selection, CI/CD pipeline completion) rather than structural. The
technology choices are mainstream, long-term-supported and portable across Azure, Google Cloud and on-premises
Kubernetes.

## 2. Scope, context and assumptions

**In scope** of this document: the repository content under `platform/`, `apps/`, `web/`, `deploy/`, `config/` and
the CI/CD design. **Out of scope:** the clinical content of modules E01–E14 (forms, pathways, rules), organisational
policies, and the operating model of the airline's IT organisation, which are referenced where they are a dependency.

**Assumptions** (to be confirmed – see Section 16):

| # | Assumption |
|---|---|
| A1 | The airline uses Microsoft Entra ID as the workforce identity provider; Keycloak or another OIDC provider may be used for partners and for local development. |
| A2 | The primary hosting target is a managed Kubernetes service (AKS or GKE) in an EU region; a hybrid variant keeps PHI on-premises. |
| A3 | PostgreSQL 16 is acceptable as the system of record (managed: Azure Database for PostgreSQL Flexible Server or Cloud SQL; on-premises: CloudNativePG). |
| A4 | The EHS incident-management system can deliver incidents as e-mail alerts and/or Excel exports; an API feed may follow. |
| A5 | Occupational-health data is special-category data under GDPR Art. 9 and is treated as PHI-equivalent; HIPAA is used as a reference framework, not necessarily as a legal obligation. |
| A6 | The organisation intends to obtain a SOC 2 Type 2 report for the operated service. |

## 3. Architectural principles

| # | Principle | How the code realises it |
|---|---|---|
| P1 | **Clinical data stays in the OH domain; operations and HR receive status-only outputs.** | `CaseViews.StatusOnly` record has no clinical fields; `PolicyEngine` grants `STATUS_ONLY` to MANAGER/HR within org scope; `OutboundPayloadGuard` allow-lists fields per destination; notification `ContentRules`. |
| P2 | **Default deny.** | Core API `anyRequest().denyAll()`; ABAC returns *deny* when no policy applies; outbound destinations without allow-list are refused; Kubernetes NetworkPolicies default-deny. |
| P3 | **Least privilege at every layer.** | Separate DB roles (migrator owner vs. runtime DML-only with `${app_role}` grants); restricted class E09/E10 visible only to `PSYCH_CLINICIAN`; non-root containers with dropped capabilities. |
| P4 | **Tamper-evidence and accountability.** | Hash-chained `platform.audit_event`, append-only trigger plus grants, `/api/v1/audit/verify`; correlation id on every request and event. |
| P5 | **Security by construction rather than by convention.** | Projection types instead of field filtering, allow-listed SQL dimensions, typed configuration, ArchUnit rules, ESLint bans on `console`/`localStorage`. |
| P6 | **Modular monolith, extractable modules.** | One deployable, nine Maven modules, communication across business modules via events only; any module can later become a service. |
| P7 | **Reliability through idempotency.** | Transactional outbox, idempotent consumers, `Idempotency-Key`, de-duplication of incidents by `(source_system, external_ref)`, cases by `(source, source_ref)`. |
| P8 | **Cloud-portable, vendor-neutral.** | Standard OIDC, PostgreSQL, Kubernetes/Helm, OpenTelemetry, pluggable publisher and channel SPIs; Azure, GCP and on-premises values files. |
| P9 | **Secrets never in source, images or Terraform state.** | `ENC(...)`/`file:` resolution, CSI-mounted files, workload identity, Terraform write-only arguments (`>= 1.11`). |
| P10 | **Incremental delivery.** | `CaseModule` SPI, `ModuleRegistry` catalogue E01–E18 with configuration flags, `ConnectorRegistry` catalogue. |

## 4. Logical-to-technical mapping

The client's logical architecture has four tiers: entry points → OH case-management core (E01–E14) → platform services
(E15–E18, notifications) → enterprise and external systems. The tables below map **every box** to the concrete
component and give an honest delivery status.

![C4 system context](../images/01-c4-context.png)

*Figure 1 – System context (source: `docs/images/01-c4-context.svg`).*

### 4.1 Entry points

| Logical entry point | Technical realisation | Status |
|---|---|---|
| EHS incident-management system (primary feed) – mailbox | `wz-ehs-intake`: `MailboxIngestionJob` with `ImapMailboxReader` (IMAPS, server-identity check) or `GraphMailboxReader` (Microsoft Graph, client-credentials, `Mail.ReadWrite` scoped to one mailbox); `MailBodyParser` for `Key: Value` alert bodies; `.xlsx` attachments routed to the Excel parser. Mode selected by `WZ_EHS_MAILBOX_MODE` (`none`/`imap`/`graph`). | Implemented |
| EHS incident-management system – Excel import | `POST /api/v1/intake/ehs/excel` → `ExcelIncidentParser` (Apache POI 5.5.1, hardened) → `IncidentIntakeService`. | Implemented |
| EHS → case | `ehs.incident.received.v1` event → `IncidentListener` → `IncidentToCaseRule` beans (`DefaultIncidentRules`). | Implemented (code-based rule seed of E01) |
| Employee / crew mobile app & portal | `web/employee-app` (React 19.3.1, Capacitor 8.6.0): my cases, self-referral, consents, profile; web/PWA via BFF; iOS/Android with OIDC + PKCE and MDM AppConfig. | Implemented (E13 seed); native projects generated on demand (`npx cap add`) |
| Manager & HR workspace (status-only) | `web/manager-workspace`: team case status, case status detail, KPI tile with suppression; management-referral form is a disabled placeholder. | Implemented (referral planned with E01) |
| Group clinics | Clinic devices can send results via HL7 v2 / FHIR R4 inbound; clinic scheduling and operations are module E14. | Endpoints implemented; E14 planned |
| Partner provider / TPA portal | Role `PARTNER_PROVIDER` defined; no policy grants it access yet (default deny); FHIR R4 inbound available for partner systems. | Planned |
| System triggers | `CaseSource.SYSTEM_TRIGGER` and `CaseService.openFromTrigger(...)` (idempotent) exist; scheduled triggers (e.g. surveillance recall) arrive with E06/E07. | Extension point |

### 4.2 OH case-management core (E01–E14)

Status per module is taken from `ModuleRegistry` (`platform/wz-case-kernel/.../module/ModuleRegistry.java`), exposed
at `GET /api/v1/modules`. `KERNEL` means a minimal seed exists in the case kernel; `PLANNED` means not built;
`IMPLEMENTED` is reported automatically once a `CaseModule` bean with that code is deployed.

| Code | Module | Registry status | What exists in the base framework |
|---|---|---|---|
| E01 | Intake & incident-to-case rule engine | KERNEL | `IncidentToCaseRule` SPI, `DefaultIncidentRules` (exposure → EXPOSURE; injury → INJURY; severity ≥ MEDIUM → OTHER), idempotent case opening, case reference sequence `OH-<year>-<6 digits>`. Configurable (no-code) rule engine: planned. |
| E02 | Triage, routing, work queues & SLAs | PLANNED | Case state machine (`CaseStatus`) with allowed transitions; `POST /cases/{id}/transition`. |
| E03 | OH record & clinical encounters | PLANNED | Encrypted `clinical_summary` on the case aggregate; FHIR inbound store. |
| E04 | Treatment coordination, referrals & provider network | PLANNED | Consent purpose `TREATMENT_SHARING_PARTNER`; partner connector catalogued. |
| E05 | Fitness for duty, restrictions & return to work | KERNEL | `FitnessStatus`, `restrictionUntil`, `POST /cases/{id}/fitness` (OH clinicians), status-only `fitness.status.changed.v1` to `crew-rostering`. |
| E06 | Absence & early intervention | PLANNED | Time & attendance connector catalogued. |
| E07 | Surveillance, statutory medicals & immunisation | PLANNED | Hazard catalogue with surveillance intervals; job codes with hazard profiles; FHIR `Immunization` accepted inbound. |
| E08 | Claims & insurance coordination | PLANNED | `CaseStatus.CLAIMS`; consent purpose `INSURER_SHARING`; FHIR `Claim` accepted inbound. |
| E09 | Psychological health & crew peer support – restricted class | PLANNED | Category `PSYCHOLOGICAL` → `RESTRICTED_CLINICAL`; restricted-class deny policy; role `PSYCH_CLINICIAN`; excluded from analytics view. |
| E10 | Substance-testing programme – restricted class | PLANNED | Category `SUBSTANCE` → `RESTRICTED_CLINICAL` with the same controls as E09. |
| E11 | Travel health, exposures & communicable disease | PLANNED | Category `EXPOSURE`; EHS exposure rule. |
| E12 | Health promotion & campaigns | PLANNED | Consent purposes `HEALTH_CAMPAIGNS`, `WEARABLE_DATA`; connector catalogued. |
| E13 | Employee & manager self-service | KERNEL | Self-referral, my cases, consents, profile (employee app); status-only team view (workspace). |
| E14 | Scheduling & clinic operations | PLANNED | Notification templates `appointment.reminder`; quiet hours. |

> Note: the module enablement flag `modules.eXX.enabled` is stored in E18 configuration and reported by
> `GET /modules`, but in the current code no endpoint consults it (`ModuleRegistry.isEnabled` has no callers). New
> modules must check it (see the Developer Guide).

### 4.3 Platform services

| Logical service | Technical realisation | Status |
|---|---|---|
| **E15 Integration & API gateway** – HL7 FHIR R4 | `FhirController`: `POST /api/v1/integration/fhir/{type}` (JSON only, 11 allow-listed resource types, strict parser), `GET .../metadata` CapabilityStatement; HAPI FHIR structures R4 8.12.1. Raw resources stored encrypted in `platform.inbound_message`; `integration.inbound.received.v1` emitted. | Implemented (inbound store); mapping into clinical records planned with E03/E07 |
| E15 – HL7 v2 | `Hl7v2Controller`: ER7 over HTTPS, HAPI HL7 v2 2.6.0 (`hapi-structures-v25`), ACK `AA`/`AR`. MLLP is terminated by an integration engine/gateway. | Implemented |
| E15 – ODH / SNOMED / ICD / LOINC | `CodeSystem` enum with canonical URIs; `TerminologyService` over `platform.code_concept` (7 illustrative seed concepts). Licensed releases or terminology server: planned. | Extension point |
| E15 – OAuth2 | All integration endpoints require role `INTEGRATION`, `PLATFORM_ADMIN` or `OH_ADMIN` via client-credentials tokens from a trusted issuer. | Implemented |
| E15 – Event bus with idempotency & replay | `EventBus` → `platform.outbox_event` → `OutboxRelay` → `EventPublisher` SPI; `IdempotentConsumer`; `IdempotencyService` (`Idempotency-Key`); `GET /integration/events`, `POST /integration/events/{id}/replay`. | Implemented (in-process); broker adapters extension point |
| E15 – API gateway | Kubernetes ingress plus WAF (Azure Front Door/App Gateway WAF_v2, GCP Cloud Armor) in Terraform; no dedicated API-management product. | Implemented (infra); APIM optional |
| **E16 Identity, ABAC, consent, immutable audit** | `wz-security`: multi-issuer resource server, `WellnessZJwtConverter`, `PolicyEngine` + `DefaultPolicies`, `ConsentService`, `AuditService` (hash chain), `SecurityEventAuditor`. | Implemented |
| **E17 Analytics, KPIs, regulatory reporting, predictive models** | `wz-analytics`: `analytics.v_case_facts`, `KpiService` (one KPI: cases by status), `SmallCellSuppression`, `Pseudonymizer`. | KPI + suppression implemented; regulatory reports, warehouse feed and models planned |
| **E18 Master data & no-code configuration** | `wz-masterdata`: org units (materialised path), job codes, hazards, `ConfigService` key/value configuration (JSON values allowed, versioned, audited). | Implemented (data model + API); HRIS sync and configuration UI planned |
| **Notification gateways** with content rules & quiet hours | `wz-notification`: approved templates with allow-listed placeholders, `ContentRules` (clinical-term and ICD-code detector), `QuietHours` (recipient time zone, URGENT bypass), SMTP channel; SMS/push channel SPI with logging stand-ins. | Implemented (e-mail); SMS/push adapters extension point; event-driven triggering planned |

### 4.4 Enterprise and external systems

Catalogued in `ConnectorRegistry` (`GET /api/v1/integration/connectors`). The *data contract* column is the agreed
boundary content.

| System | Direction | Data contract | Status in the base framework |
|---|---|---|---|
| HRIS / HCM | in | employees, org hierarchy, job codes, lifecycle events | Generic idempotent `POST /integration/inbound/hris` and master-data upsert APIs exist; mapping job planned |
| Time & attendance / crew sickness | in | absence episodes | Planned (E06) |
| Crew rostering | out | `fitnessStatus`, `restrictionUntil`, `effectiveFrom` (+ `caseRef`, `employeeId` keys) | Event produced and guarded; transport adapter is an extension point (logging publisher today) |
| HR systems / catering hygiene | out | fitness & restriction status only | Allow-list configured (`hr-systems`); producer planned |
| Laboratories & clinic devices | both | orders / results | Inbound HL7 v2 / FHIR implemented; outbound orders planned |
| Partner hospitals, specialists, physio, EAP | both | referrals / reports, consent required | Inbound FHIR implemented; consent gate and outbound planned (E04) |
| Insurers / TPAs | both | notifications, pre-authorisation, documents, consent required | Planned (E08) |
| ERP / finance | out | case cost centre, amount, currency | Planned |
| SMS / EHS dashboards | out | anonymised OH metrics | KPI API with suppression implemented; push feed planned |
| Health-campaign app & wearables | both | campaigns, participation, consent required | Planned (E12) |

## 5. C4 views

### 5.1 Container view

![C4 container view](../images/02-c4-container.png)

*Figure 2 – Containers (source: `docs/images/02-c4-container.svg`).*

| Container | Technology (pinned in repo) | Responsibility | Exposure |
|---|---|---|---|
| `employee-app` | React 19.3.1, TypeScript 6.0.3, Vite 8.3.5, Capacitor 8.6.0; image `nginxinc/nginx-unprivileged:1.30.6-alpine3.24` | Employee/crew portal, PWA shell, native app source | Static files on `:8080`; `/bff/**` routed to BFF by ingress |
| `manager-workspace` | React 19.3.1, same toolchain, same nginx image | Status-only workspace for managers, HR, OH | Static files on `:8080` |
| `wz-bff` | Spring Boot 4.1.2, Spring Security OAuth2 client, Spring Session JDBC; `eclipse-temurin:21.0.12_8-jre-noble` | OIDC login, session, CSRF, token relay | `:8090`, management `:8091` |
| `wz-core-app` | Spring Boot 4.1.2 modular monolith, Java 21 (virtual threads enabled) | All APIs, business logic, jobs (outbox relay, mailbox poll, notification dispatch, idempotency purge) | `:8080` API, `:8081` management (health, Prometheus) |
| Flyway migrations | Flyway via Spring Boot starter or `flyway/flyway:11.20.3-alpine` Job | Schema migration with owner role | Job / start-up |
| PostgreSQL 16 | Managed service or CloudNativePG (`postgresql:16.15`) | System of record | Private endpoint, TLS |
| Identity provider | Entra ID / Keycloak 26.8.1 (local) / Okta etc. | Authentication, MFA, tokens | External |

### 5.2 Component view (wz-core-app)

![Component view](../images/03-component-view.png)

*Figure 3 – Components of the core API (source: `docs/images/03-component-view.svg`).*

| Maven module | Package root | Depends on | Key components |
|---|---|---|---|
| `wz-common` | `com.wellnessz.common` | – (no Spring Security, ArchUnit-enforced) | `ApiException`, `GlobalExceptionHandler` (RFC 9457), `DomainEvent`, `IntegrationEnvelope`, `DataClassification`, `DisclosureLevel`, `Roles`, `RoleClaims`, `CorrelationIdFilter`, `LogSanitizer`, UTC `Clock` |
| `wz-secrets` | `com.wellnessz.secrets` | common | `SecretsEnvironmentPostProcessor`, `SecretValueResolver`, `MasterKey`, `Hkdf`, `AesGcmCipher`, `FieldEncryptor`, `EncryptedStringConverter`, `EncryptCli` |
| `wz-security` (E16) | `com.wellnessz.security` | common | `ResourceServerSecurityConfig`, `WellnessZJwtConverter`, `CurrentUserProvider`, `PolicyEngine`, `AccessPolicy`, `DefaultPolicies`, `ConsentService`, `AuditService`, `SecurityEventAuditor` |
| `wz-masterdata` (E18) | `com.wellnessz.masterdata` | security | `OrgUnit`, `JobCode`, `Hazard`, `ConfigService`, `MasterDataController` |
| `wz-integration` (E15) | `com.wellnessz.integration` | security, secrets | `EventBus`, `OutboxRelay`, `EventPublisher`, `OutboundPayloadGuard`, `IdempotentConsumer`, `IdempotencyService`, `FhirController`, `Hl7v2Controller`, `TerminologyService`, `ConnectorRegistry` |
| `wz-notification` | `com.wellnessz.notification` | common | `NotificationService`, `NotificationTemplates`, `ContentRules`, `QuietHours`, `NotificationChannel` |
| `wz-analytics` (E17) | `com.wellnessz.analytics` | security | `KpiService`, `SmallCellSuppression`, `Pseudonymizer`, `AnalyticsController` |
| `wz-case-kernel` | `com.wellnessz.casekernel` | masterdata, integration, security, secrets | `OhCase`, `CaseService`, `CaseViews`, `IncidentListener`, `IncidentToCaseRule`, `CaseModule`, `ModuleRegistry` |
| `wz-ehs-intake` | `com.wellnessz.ehsintake` | integration, security, secrets | `ExcelIncidentParser`, `MailboxIngestionJob`, `ImapMailboxReader`, `GraphMailboxReader`, `IncidentIntakeService` |

ArchUnit rules (`apps/wz-core-app/src/test/java/com/wellnessz/app/ArchitectureTest.java`):

1. Platform packages must not depend on `casekernel` or `ehsintake`.
2. `ehsintake` must not depend on `casekernel` – the two communicate only via `ehs.incident.received.v1`.
3. `common` must not depend on Spring Security.
4. No code outside `com.wellnessz.secrets` (the CLI) may use `System.out`/`System.err`.

### 5.3 Deployment view

![Deployment reference](../images/07-deployment-reference.png)

*Figure 4 – Deployment reference (source: `docs/images/07-deployment-reference.svg`). The deployment assets are
maintained under `deploy/` and are still being finalised; this view reflects their current content.*

## 6. Data architecture

### 6.1 Schemas and stores

All schemas live in one PostgreSQL 16 database; the BFF uses its own schema and migration history.

| Schema | Tables / views (migration) | Content | Classification (highest) |
|---|---|---|---|
| `platform` | `audit_event`, `consent`, `outbox_event`, `processed_message`, `idempotency_record`, `inbound_message`, `code_concept`, `notification` (V1) | Cross-cutting platform state | CLINICAL (inbound payloads – encrypted; outbox payloads – see 6.4) |
| `masterdata` | `org_unit`, `job_code`, `hazard`, `config_entry` (V2) | Organisation, job/hazard catalogue, configuration | INTERNAL |
| `intake` | `import_batch`, `ehs_incident` (V2) | EHS incidents and import provenance | CONFIDENTIAL; `description` CLINICAL (encrypted) |
| `casemgmt` | `oh_case`, sequence `case_ref_seq` (V2) | Case aggregate | CLINICAL / RESTRICTED_CLINICAL (`clinical_summary` encrypted) |
| `analytics` | view `v_case_facts` (V2) | De-identified case facts | INTERNAL (aggregate) |
| `bff` | `spring_session`, `spring_session_attributes` (`db/bff/V1`) | Browser sessions including OAuth2 tokens | CONFIDENTIAL (attributes encrypted) |

Flyway migrations (`apps/wz-core-app/src/main/resources/db/migration`) run as an owner role (`WZ_DB_MIGRATOR_USERNAME`,
`wz_migrator` in Terraform/CNPG). Each migration grants the runtime role (`${app_role}`, the value of
`WZ_DB_USERNAME`) the minimum DML it needs; for example `audit_event` receives `SELECT, INSERT` only and `consent` has
no `DELETE`. Hibernate runs with `ddl-auto: validate`, so the application never changes the schema at runtime.

### 6.2 Data classification scheme

`com.wellnessz.common.model.DataClassification` defines five levels, driving encryption, disclosure and (in future)
retention:

| Level | Definition (from code) | Examples | Controls |
|---|---|---|---|
| `PUBLIC` | Publicly releasable | – | none specific |
| `INTERNAL` | Internal operational data | org hierarchy, hazard catalogue, suppressed aggregates | authenticated access |
| `CONFIDENTIAL` | Personal data that is not health data | employee id, name, notification address, consent records | authenticated, role-based, TLS, storage encryption |
| `CLINICAL` | OH clinical data; never leaves the OH domain | case clinical summary, incident narrative, lab results | + field-level AES-256-GCM, ABAC `CLINICAL`, status-only projection for non-OH roles, outbound allow-list |
| `RESTRICTED_CLINICAL` | Special-category clinical data (psychological health, substance testing) | cases of category `PSYCHOLOGICAL`, `SUBSTANCE` | + visible only to `PSYCH_CLINICIAN` and the data subject; excluded from analytics view |

The case classification is derived from the category (`CaseCategory.classification()`), so it cannot be set
inconsistently by a client.

### 6.3 Field-level encryption, key derivation and blind index

Field-level encryption complements storage encryption (Azure customer-managed keys, Cloud SQL CMEK, CNPG volume
encryption), so that database dumps, replicas, backups or DBA access do not reveal clinical text.

| Aspect | Implementation |
|---|---|
| Algorithm | AES-256-GCM (`AesGcmCipher`), random 96-bit IV per value, 128-bit tag; wire format `base64(iv‖ciphertext‖tag)`. |
| Associated data | Context string bound into the tag (`wellnessz-field` for columns, `wellnessz-config` for configuration, `bff-session` for sessions), so ciphertexts cannot be moved between purposes. |
| Master key | 256-bit, from `WZ_MASTER_KEY` (base64) or `WZ_MASTER_KEY_FILE` (CSI-mounted file); the application refuses to start without it. |
| Key derivation | HKDF-SHA256 (RFC 5869) with purpose labels: `wellnessz/field/<version>`, `wellnessz/config/v1`, `wellnessz/bff-session/v1`, `wellnessz/blind-index/v1`. |
| Versioning | Stored values are prefixed with the key version (`v1:...`). New writes use `wellnessz.crypto.active-key-version` (`WZ_FIELD_KEY_VERSION`); old versions remain readable. |
| JPA integration | `@Convert(converter = EncryptedStringConverter.class)` – used today on `oh_case.clinical_summary`, `ehs_incident.description`, `inbound_message.payload`; verified by the integration test (`stored value starts with v1: and does not contain the plaintext`). |
| Blind index | `FieldEncryptor.blindIndex()` – keyed HMAC-SHA256 for equality search over encrypted columns. Available; no column uses it yet. |

**Key-rotation properties (important for GRC).** Changing the *active version* rotates the data-encryption sub-key for
new writes without downtime. Because all versions are derived from the same master key, this does **not** protect
against compromise of the master key itself. Rotating the master key requires re-encrypting existing values; the
re-encryption job referred to in `FieldEncryptor`'s documentation is **planned, not implemented**. The blind-index key
is not versioned. Recommendation (decision D5): adopt envelope encryption with the master key wrapped by Azure Key
Vault / Cloud KMS / Vault Transit, and deliver the re-encryption job before go-live.

### 6.4 Data that is not field-encrypted

To set expectations precisely: the following columns rely on storage encryption and access control only.

| Column(s) | Content | Mitigation / recommendation |
|---|---|---|
| `oh_case.employee_id`, `employee_display_name`, `org_unit_*`, `category`, `status`, `fitness_status` | Identifiers and status needed for queries and status-only views | ABAC on every read; `category` reveals the restricted class – consider encrypting category for RESTRICTED rows in E09/E10 |
| `ehs_incident.employee_name`, `location`, `incident_type`, `severity`, `injury` | Incident metadata | Description (free text) is encrypted and not exposed in list API |
| `outbox_event.payload` | Event payloads (e.g. incident employee name, fitness status) | **Not encrypted, no retention purge.** Recommend encrypting payloads or purging `PUBLISHED` rows after N days (decision D7) |
| `idempotency_record.response_body` | Stored API responses | Purged after 7 days by `IdempotencyService.purge()` |
| `notification.address`, `body` | Recipient address and content-free message | Body is content-free by construction |
| `audit_event` | Actor, action, resource id, reason, client IP | Reasons are sanitised; must not contain PHI by coding standard |

### 6.5 De-identified analytics view

`analytics.v_case_facts` exposes only `org_unit_code`, `category`, `source`, `status`, `fitness_status` and the month of
opening; it has no identifiers, names or free text and excludes `RESTRICTED_CLINICAL` cases entirely. The runtime role
has `SELECT` on the view only within the `analytics` schema (it still has table access in `casemgmt`, so a separate
read-only reporting role is recommended for warehouse extraction).

### 6.6 Retention and deletion – to be defined

The code currently implements **no retention or deletion jobs** other than the 7-day purge of idempotency records and
Spring Session expiry. Retention periods for OH records are governed by national occupational-health and
aviation-medicine rules (often decades for exposure records) and must be set by the Data Protection Officer and OH
governance. Proposed approach (decision D6): a retention schedule per `DataClassification` and record type in E18
configuration, a scheduled purge/anonymisation job per module, legal-hold flags, and audit events for every purge.
Audit records must be retained at least as long as the records they describe.

## 7. Integration architecture

![Data flow EHS intake to status-only feed](../images/04-dataflow-ehs-intake.png)

*Figure 5 – EHS incident → OH case → status-only outputs (source: `docs/images/04-dataflow-ehs-intake.svg`).*

### 7.1 Transactional outbox event bus

| Concern | Implementation |
|---|---|
| Publishing | `EventBus.publish(event[, destination])` requires an active transaction (`Propagation.MANDATORY`) and inserts into `platform.outbox_event` in the **same transaction** as the business change – no dual-write problem. |
| Envelope | `IntegrationEnvelope(messageId, eventType, aggregateType, aggregateId, payload, headers{correlationId}, occurredAt)`; event types are versioned (`ehs.incident.received.v1`, `case.opened.v1`, `fitness.status.changed.v1`, `integration.inbound.received.v1`). |
| Relay | `OutboxRelay` every `PT2S` on every replica; claims due rows with `FOR UPDATE SKIP LOCKED` (batch 50) so replicas never deliver the same row concurrently. |
| Delivery semantics | At-least-once. Failures increment `attempts`, back-off `2^attempts` seconds capped at one hour; after `max-attempts` (10) the row becomes `DEAD`. |
| Destinations | `internal` → `InProcessEventPublisher` (Spring application event); any other destination → an `EventPublisher` adapter. Today `LoggingExternalPublisher` (metadata only) stands in. Kafka / Azure Service Bus / Google Pub/Sub adapters are **extension points**. |
| Idempotent consumers | `IdempotentConsumer.handleOnce(consumer, envelope, handler)` inserts `(consumer, messageId)` into `platform.processed_message` in a new transaction together with the handler's effects; duplicates are skipped. |
| Monitoring & replay | `GET /api/v1/integration/events?status=DEAD` (metadata only, never payloads); `POST /api/v1/integration/events/{id}/replay` (PLATFORM_ADMIN/OH_ADMIN, audited `EVENT_REPLAY`). Replay re-sends the same `messageId`, so consumers that already processed the message ignore it – replay is intended for `FAILED`/`DEAD` events. |

### 7.2 Inbound APIs

| Endpoint | Behaviour |
|---|---|
| `POST /api/v1/integration/inbound/{source}` | JSON only; **requires** `Idempotency-Key` (8–200 chars, `[A-Za-z0-9._:-]`). First request executes; retry with same key and payload returns the stored response with `Idempotent-Replayed: true`; same key with different payload → `422`. Advisory lock prevents concurrent first-execution. Records purged after 7 days. |
| `POST /api/v1/integration/fhir/{resourceType}` | `application/fhir+json` only (no XML parser – no XXE surface); allow-list: Patient, Observation, DiagnosticReport, ServiceRequest, Condition, Encounter, Immunization, DocumentReference, Bundle, Appointment, Claim; strict parser; URL type must match body type; responds with `OperationOutcome` and `Location: urn:uuid:<id>`. |
| `GET /api/v1/integration/fhir/metadata` | FHIR 4.0.1 CapabilityStatement listing the supported `create` interactions. |
| `POST /api/v1/integration/hl7v2` | ER7 over HTTPS; HAPI default validation; `MSA\|AA` on success, `AR` + HTTP 400 when unparseable. |

All inbound messages are stored encrypted in `platform.inbound_message` with the authenticated client as `received_by`
and audited (`INBOUND_<format>`). FHIR profile validation (e.g. ODH profiles), patient matching and mapping to clinical
records are **planned** with E03/E07.

### 7.3 Outbound disclosure allow-list guard

`OutboundPayloadGuard` is invoked by `EventBus.publish(event, destination)` before the outbox row is written. For each
external destination a list of permitted top-level JSON fields is configured; any other field aborts the business
transaction (`disclosure_violation`), and a destination without a list is refused (`destination_not_allowed`).

```yaml
wellnessz.integration.outbound-allowlist:
  crew-rostering: [caseRef, employeeId, fitnessStatus, restrictionUntil, effectiveFrom]
  hr-systems:     [caseRef, employeeId, fitnessStatus, restrictionUntil, effectiveFrom]
```

The integration test `fitnessOutcomeIsPublishedToRosteringAsStatusOnly` verifies that a fitness decision produces a
rostering payload without the clinical narrative or case category. **Limitation:** the guard checks top-level field
names, not nested structures or values; status-only events must therefore be flat records (coding standard in the
Developer Guide), and each new destination's allow-list is a change subject to privacy review.

### 7.4 EHS mailbox and Excel intake hardening

| Threat | Control |
|---|---|
| Spoofed or unsolicited e-mail | `wellnessz.ehs.mailbox.allowed-senders` (addresses or `@domain`); others rejected and audited `EHS_MAIL_REJECTED`. Recommend DMARC enforcement on the sending domain. |
| Over-privileged mailbox access | Graph: client-credentials with `Mail.ReadWrite` **scoped to the single feed mailbox** via Exchange RBAC for Applications / application access policy (documented in code). IMAP: IMAPS with server-identity check; plain IMAP only for the local GreenMail. |
| Oversized content | Attachments ≤ 10 MB, ≤ 25 messages per poll, upload limit 10 MB (`spring.servlet.multipart`). |
| Malicious workbook | OOXML magic-byte check (`PK\3\4`), POI `ZipSecureFile` inflate-ratio 0.01 and 100 MB entry limit (zip bombs), ≤ 10,000 rows, formulas never evaluated (cached values only), POI XML parsers with external entities disabled, `.xlsx` only. |
| Injection into downstream systems | Control characters stripped, lengths capped, identifiers validated by regex (`[A-Za-z0-9._/-]{1,100}` for incident ids). |
| Duplicates / replays | Unique `(source_system, external_ref)`; mail marked read only after commit; re-processing is safe. |
| Traceability | `import_batch` with SHA-256 of the file, counts and row-level errors; audit `EHS_IMPORT`. |
| Clinical free text exposure | Description encrypted; list API omits description. |

The mailbox job runs on every replica (in-JVM `synchronized` only); duplicate processing across replicas is harmless
because of the unique constraint, but a scheduler lock (e.g. ShedLock) is recommended for efficiency.

### 7.5 Connector catalogue

See Section 4.4. Each connector, when implemented, follows the same pattern: inbound through an idempotent endpoint or
a scheduled reader → encrypted inbound store → event → idempotent consumer; outbound through `EventBus.publish(event,
destination)` → allow-list → outbox → broker adapter. The broker choice is decision D3 (Section 15.4).

## 8. Identity and access management

![Identity and BFF flow](../images/05-identity-bff-flow.png)

*Figure 6 – Browser login and API call through the BFF (source: `docs/images/05-identity-bff-flow.svg`).*

### 8.1 Multi-issuer OIDC

The core API is a stateless OAuth2 resource server. `JwtIssuerAuthenticationManagerResolver` accepts a token only if
its `iss` claim exactly matches one of `WZ_OIDC_ISSUERS` (comma-separated); untrusted issuers are rejected **before any
network call**, so the API cannot be induced to fetch arbitrary JWKS URLs. For trusted issuers the decoder is built from
OIDC discovery and validates signature (algorithms constrained by the JWKS), `exp`, `nbf`, `iss` and the audience
`WZ_OIDC_AUDIENCE` (default `wellnessz-api`).

| IdP | Typical use | Notes |
|---|---|---|
| Microsoft Entra ID | Workforce (employees, crew, managers, OH staff) | App roles in `roles`; security groups in `groups` mapped by object id; v2.0 issuer `https://login.microsoftonline.com/<tenant>/v2.0`. Conditional Access and MFA are configured in Entra ID. |
| Keycloak | Local development (realm import in `deploy/local/keycloak`), optionally partners | Realm roles in `realm_access.roles`. |
| Okta, Ping, Google, others | Alternatives | Any claim path configurable. |

### 8.2 Role claim mapping

`RoleClaims.extract` reads the configured claim paths (default `realm_access.roles`, `roles`, `groups`), maps raw values
through `wellnessz.security.group-role-mapping` (used for **Entra ID group GUIDs**, e.g.
`"6f1c…": OH_CLINICIAN`), normalises to upper case, and accepts only names matching `[A-Z][A-Z0-9_]{0,63}` (defence
against claim injection). Roles become `ROLE_<NAME>` authorities. Employee id and org scope come from configurable
claims (`employee_id`, `org_scope`), which the IdP must populate (e.g. Entra ID directory extension or claims mapping
policy fed from HRIS).

Canonical roles (`wz-common Roles`): `EMPLOYEE`, `MANAGER`, `HR`, `OH_CLINICIAN`, `OH_ADMIN`, `CASE_MANAGER`,
`PSYCH_CLINICIAN`, `AUDITOR`, `PLATFORM_ADMIN`, `INTEGRATION`, `PARTNER_PROVIDER`.

> The BFF maps roles from the ID and access token for **UI gating only** using fixed claim paths and without group
> mapping; authoritative decisions are always taken by the core API. If Entra group GUIDs are used instead of app roles,
> the workspace role gate in the BFF needs the same mapping (open item O4).

### 8.3 ABAC policy engine and disclosure levels

`PolicyEngine.decide(user, action, resource)` evaluates all `AccessPolicy` beans with **deny-overrides**: any
applicable deny policy wins; otherwise the permit with the highest disclosure level wins; otherwise **default deny**.
Resource attributes are resource type, owner employee id, org-unit path and classification. Business modules contribute
their own policies as Spring beans.

| Disclosure level | Meaning | Granted by (DefaultPolicies) |
|---|---|---|
| `NONE` | no access | default |
| `STATUS_ONLY` | case status, fitness status, dates – no clinical content | `statusOnlyPolicy`: MANAGER/HR, READ, resource org path within the user's `org_scope` (otherwise explicit deny) |
| `SELF` | data subject's own record | `selfAccessPolicy`: employee id matches, READ |
| `CLINICAL` | standard OH clinical access | `clinicianPolicy`: OH_CLINICIAN / CASE_MANAGER, not restricted class |
| `RESTRICTED_CLINICAL` | includes psychological health / substance testing | `clinicianPolicy`: PSYCH_CLINICIAN |
| (deny) | restricted class for anyone else | `restrictedClassPolicy` (deny policy): RESTRICTED_CLINICAL unless PSYCH_CLINICIAN, data subject, or MANAGER/HR (who still only get status) |

`CaseService` applies the decision on **every** read and returns a projection chosen by the disclosure level:
`CaseViews.StatusOnly` (no clinical fields exist in the type) or `CaseViews.Clinical`. Denied reads return **404**, not
403, so that existence is not disclosed; every read, list and denial is audited. Integration tests verify: manager in
scope receives status-only without the narrative; manager outside scope receives 404; general clinician cannot see a
psychological case; psychological-health clinician can.

Coarse-grained RBAC complements ABAC: URL rules (`/api/v1/integration/**` → INTEGRATION/PLATFORM_ADMIN/OH_ADMIN;
`/api/v1/audit/**` → AUDITOR/PLATFORM_ADMIN) and `@PreAuthorize` on controllers.

**Not yet implemented:** break-glass (emergency) access with justification and alerting; delegation; time-bound access;
an external policy decision point (OPA/Cedar) – the in-code engine is adequate for the current rule set (decision D8).

### 8.4 Consent model

`platform.consent` records employee consent per purpose – `TREATMENT_SHARING_PARTNER`, `EAP_REFERRAL`,
`INSURER_SHARING`, `WEARABLE_DATA`, `HEALTH_CAMPAIGNS`, `RESEARCH_DEIDENTIFIED` – with optional scope, privacy-notice
version (`wellnessz.consent.notice-version`, currently `2026.1`), capture time and actor. Withdrawal is a state change
(the runtime role has no `DELETE` grant), preserving evidence. Only the data subject can withdraw; others receive 404.
Grant and withdrawal are audited. `ConsentService.hasActiveConsent(employeeId, purpose)` is the gate that sharing
modules (E04, E08, E12) must call – **it is not yet called anywhere**, because no consent-dependent outbound flow exists
yet. Consent is only one lawful basis; the DPO should confirm where GDPR Art. 9(2)(h) (occupational medicine) or (b)
(employment law) applies instead, so that consent is not relied on where it cannot be freely given.

## 9. Security architecture

This section summarises the technical controls; document 02 maps them to SOC 2 criteria and other frameworks.

### 9.1 Secrets management

| Mechanism | Detail |
|---|---|
| `ENC(...)` configuration values | AES-256-GCM with HKDF sub-key `wellnessz/config/v1`; produced by `EncryptCli` (reads plaintext from console without echo or stdin – never from arguments). Decrypted by `SecretsEnvironmentPostProcessor` into an in-memory, highest-precedence property source; original sources untouched; values never logged. Used for e.g. database passwords in non-Kubernetes deployments. |
| `file:` references | For property names containing password/secret/key/token/credential, `file:/mnt/secrets/<name>` is read from a file mounted by the **Secrets Store CSI driver** (Azure Key Vault provider, GCP Secret Manager provider, HashiCorp Vault provider). Helm values map `wz-db-password`, `wz-master-key`, `wz-pseudonym-key`, BFF client secret and migrator password. |
| Workload identity | AKS workload identity (user-assigned identity + federated credential), GKE Workload Identity (KSA→GSA), Vault Kubernetes auth on-premises; no static cloud credentials in pods. CI uses GitHub OIDC federation (Terraform `github_deployer`, `github_wif`). |
| Terraform state | Write-only arguments (`>= 1.11`) for database passwords so secrets are not stored in state; remote state with Entra ID auth (Azure) or CMEK-encrypted GCS bucket. |
| Master key | Single 256-bit key per environment (see 6.3); stored in Key Vault / Secret Manager / Vault, mounted as file. |

### 9.2 Transport security

| Hop | Control |
|---|---|
| Client → edge | TLS 1.2+ (Azure Front Door/App Gateway policy, GCP `ssl_policy` "modern"), HSTS (1 year, includeSubDomains) sent by APIs, BFF and nginx. |
| Ingress → pods | TLS at ingress (`wellnessz-tls`); in-cluster traffic restricted by NetworkPolicies. A service mesh for in-cluster mTLS is optional (open item O6). |
| Apps → PostgreSQL | `sslmode=verify-full` by default (local profile relaxes to `prefer`); Azure `ssl_min_protocol_version TLSv1.2`; CNPG `TLSv1.3`, `hostssl` + SCRAM-SHA-256 only. |
| Mailbox | IMAPS with server-identity check; Graph over HTTPS. |
| SMTP | STARTTLS **required** outside the local profile. |
| HL7 v2 | over HTTPS instead of plaintext MLLP. |

### 9.3 BFF pattern, CSRF and security headers

The BFF implements the *OAuth 2.0 for Browser-Based Apps* recommendation: the BFF is a confidential client; the browser
holds only the session cookie `WZSESSION` (`HttpOnly`, `Secure`, **`SameSite=Lax`** – required for the top-level
redirect back from the IdP; note that `web/*/README.md` and the design brief state `Strict`, which is inconsistent with
`apps/wz-bff/src/main/resources/application.yml` – open item O3). Session attributes, including access and refresh
tokens, are AES-256-GCM-encrypted before Spring Session writes them to PostgreSQL. Sessions expire after 15 minutes of
inactivity; at most three concurrent sessions per user; session id rotated on login.

CSRF uses Spring Security's SPA mode (`XSRF-TOKEN` cookie, `X-XSRF-TOKEN` header) on every state-changing request
including logout. The proxy forwards only `content-type`, `accept` and `idempotency-key` upstream (cookies are never
forwarded) and returns only `content-type`, `location`, `x-correlation-id`, `idempotent-replayed`; paths containing `..`
or `//` are rejected; bodies are capped at 11 MB. `/bff/api/workspace/**` is role-gated for workspace roles, and the
core API enforces ABAC again.

| Header | Core API | BFF | nginx (web apps) |
|---|---|---|---|
| Content-Security-Policy | `default-src 'none'; frame-ancestors 'none'` | same | `default-src 'self'`, no inline script/style, `object-src 'none'`, `frame-ancestors 'none'` (+ build-time CSP meta) |
| Strict-Transport-Security | 1 year, subdomains | 1 year, subdomains | 1 year, subdomains |
| X-Frame-Options | DENY | DENY | DENY |
| Referrer-Policy | no-referrer | same-origin | no-referrer |
| Others | – | – | `X-Content-Type-Options: nosniff`, `Permissions-Policy`, COOP, CORP |

CORS on the core API is closed by default and is only opened (`WZ_CORS_ORIGINS`) for native app WebView origins;
credentials are not allowed.

### 9.4 Mobile security and MDM

![Mobile PKCE and MDM flow](../images/06-mobile-pkce-mdm-flow.png)

*Figure 7 – Native app sign-in with PKCE and MDM configuration (source: `docs/images/06-mobile-pkce-mdm-flow.svg`).*

| Control | Implementation (`web/employee-app`) | Status |
|---|---|---|
| OIDC Authorization Code + PKCE (S256), `state`, `nonce` | `src/auth/pkce.ts`, `nativeOidcAuthClient.ts`; WebCrypto; system browser via `@capacitor/browser` (RFC 8252); public client `wellnessz-mobile` | Implemented |
| Token storage | Access/ID token in memory only; refresh token through `SecureStorage` abstraction – **in-memory fallback by default**; Keychain/Keystore plugin must be registered for production | Extension point (plugin registration) |
| Biometric gate | `wz.requireBiometric=true` releases the refresh token only after `verifyBiometric()`; fails closed without a verifier | Extension point (plugin) |
| Token revocation | RFC 7009 on logout when the IdP advertises a revocation endpoint | Implemented |
| MDM managed app configuration | `ManagedConfig` reads `wz.apiBaseUrl`, `wz.oidcIssuer`, `wz.oidcClientId`, `wz.redirectUri`, `wz.requireBiometric` (Intune App Configuration, Workspace ONE, Jamf; iOS managed dictionary, Android restrictions) via a `ManagedConfigurations` plugin hook; precedence MDM → `/config.json` → defaults | Implemented (hook); native plugin to be added |
| App protection (MAM): copy/paste, screenshot, selective wipe, conditional launch | Requires Intune App SDK / Workspace ONE SDK or app wrapping in the native projects; Entra Conditional Access "require app protection policy" | Planned (native integration) |
| No PHI caching | ESLint bans `localStorage`/`sessionStorage`/`console`; all API calls `cache: 'no-store'`; service worker caches app shell only and is not registered in native app | Implemented |
| WebView hardening | `androidScheme: https`, no cleartext, no mixed content, WebView debugging off, link previews off | Implemented |
| Session | 15-minute wall-clock idle logout; privacy screen on background (web best effort; native plugin recommended) | Implemented / plugin recommended |
| Redirect hijacking | Custom scheme today; Universal Links / App Links recommended for production | Planned |

### 9.5 Audit trail

| Property | Implementation |
|---|---|
| Content | time, actor, actor roles, action, resource type/id, outcome (`SUCCESS`/`DENIED`/`FAILURE`), sanitised reason, correlation id, client IP. |
| Tamper evidence | Each row stores `prev_hash` and `hash = SHA-256(prev_hash ‖ canonical(row))`; `GET /api/v1/audit/verify` recomputes the chain and returns `{intact, recordsChecked, firstBrokenId}`. |
| Append-only | Trigger rejects `UPDATE`, `DELETE`, `TRUNCATE` for every role; runtime role has only `SELECT, INSERT`. Integration test proves both. |
| Ordering | `pg_advisory_xact_lock` serialises writers across replicas so the chain stays linear. |
| Independence from business transaction | `REQUIRES_NEW` – denied or failed operations are still recorded. |
| Coverage | case list/read/status read, self-referral, transition, fitness, consent grant/withdraw, config change, master-data upserts, EHS imports and rejected mails, inbound messages, event replay, analytics queries, audit reads, authentication failures, authorisation denials. |
| Access | `GET /api/v1/audit?from&to&limit` (AUDITOR/PLATFORM_ADMIN, max 5,000 rows, itself audited). |

**Limits:** a database owner could drop the trigger and rewrite the whole chain consistently. Mitigation (open item O2):
periodically anchor the latest hash outside the database (SIEM, immutable/WORM storage such as Azure immutable blob or
GCS bucket lock) and stream audit rows to the SIEM. Platform audit logs (Azure activity/diagnostic logs, GCP Data
Access audit logs enabled in Terraform, pgaudit) complement the application trail.

### 9.6 Logging without PHI

JSON logs (logstash encoder 9.0) outside local/test profiles, with `correlationId`, `traceId`, `spanId`. Code logs ids
and metadata only; `LogSanitizer` neutralises CR/LF (CWE-117); the error handler never returns stack traces, SQL or
messages (`server.error.include-*: never`) and returns a correlation id instead; log statements in intake, outbox and
notification code log exception class names, not messages; ArchUnit forbids `System.out`/`err`. Web apps log static
event codes only.

### 9.7 Container and platform hardening

Non-root images (Java uid 10001, nginx uid 101), `readOnlyRootFilesystem`, all capabilities dropped, seccomp
`RuntimeDefault`, no privilege escalation, `/tmp` as size-limited `emptyDir`; OS packages patched at build; layered
Spring Boot jars. Default-deny NetworkPolicies per workload (ingress controller, DNS, monitoring, database, HTTPS
egress excluding RFC 1918). AKS: private cluster option, local accounts disabled, Azure Policy, Defender, Key Vault CSI
with rotation, Cilium network policy. GKE: private nodes, Workload Identity, application-layer secrets encryption,
Binary Authorization (enforced), shielded nodes, Cloud Armor WAF.

### 9.8 Secure SDLC pipeline

| Stage | Tool / control | Where | Status |
|---|---|---|---|
| Unit, integration, architecture tests | JUnit 5, Spring Boot test, Testcontainers (PostgreSQL 16) or CI service DB, ArchUnit 1.5.1; Vitest 5.0.5 | Maven / npm | Implemented |
| Coverage | JaCoCo 0.8.16 | root `pom.xml` | Implemented |
| SAST | SpotBugs 4.10.5.0 + FindSecBugs 1.14.0 (`-Psecurity`); CodeQL | `pom.xml`; workflows | Maven: implemented; CodeQL: per design brief |
| SCA | OWASP dependency-check 13.0.0, fail on CVSS ≥ 7, suppressions need justification/owner/expiry; npm lockfiles with exact pins | `pom.xml`, `config/dependency-check-suppressions.xml` | Implemented |
| SBOM | CycloneDX Maven plugin 2.9.3 (`makeAggregateBom` at `package`) | `pom.xml` | Implemented |
| Secret scanning | gitleaks | `.github/workflows/` | Per design brief |
| Image scan | Trivy | `.github/workflows/` | Per design brief |
| Signing / admission | cosign signing; GKE Binary Authorization attestor (Terraform `binauthz.tf`) | workflows, Terraform | Terraform implemented; signing per design brief |
| DAST | OWASP ZAP baseline against a test deployment | – | Recommended (not yet defined) |
| Deployment | Helm with GitHub environments and approvals; GitHub OIDC federation to Azure/GCP | workflows, Terraform | Federation implemented; workflows per design brief |

> The workflow files under `.github/workflows/` are being completed by another engineer at the time of writing; GRC
> should verify the final pipeline content before using it as SOC 2 evidence.

## 10. Analytics and reporting architecture

| Layer | Today | Planned |
|---|---|---|
| Source | `analytics.v_case_facts` (de-identified, restricted class excluded) | Per-module fact views (absence, surveillance, claims) with the same rules |
| Query API | `GET /api/v1/analytics/kpis/cases-by-status?groupBy=orgUnit\|category\|source\|month` – dimensions are allow-listed and mapped to SQL expressions (no user input in SQL) | Further KPIs, regulatory reports (e.g. occupational-disease notifications, statutory surveillance coverage) |
| Disclosure control | `SmallCellSuppression`: primary suppression of cells with `0 < n < k`; complementary suppression when exactly one cell is suppressed (next-smallest cell also hidden); `k` from `WZ_ANALYTICS_MIN_GROUP` (default 5), values below 3 rejected at start-up | Row/column-wise complementary suppression for multi-dimensional tables; differencing protection across successive queries |
| Pseudonymisation | `Pseudonymizer`: HMAC-SHA256 with `WZ_PSEUDONYM_KEY` (≥ 256-bit), key held only by the platform | Used for warehouse extracts and model training sets (not yet called) |
| Warehouse / BI | – | Scheduled extract of pseudonymised, aggregated facts to Azure Synapse/Fabric or BigQuery; Power BI or Looker with row-level security; no direct BI access to OLTP schemas |
| Predictive models | – | Model governance: purpose approval by OH governance and DPO, DPIA, `RESEARCH_DEIDENTIFIED` consent or other lawful basis, pseudonymised training data, bias/performance review, human-in-the-loop for any individual-level output, model cards and versioned approval records |

Two clarifications for GRC: (1) the seeded configuration key `analytics.min-group-size` in `masterdata.config_entry`
is informational – `KpiService` reads the application property; (2) the KPI endpoint is open to MANAGER and HR at
organisation-wide level; suppression is the control, not org scope.

## 11. Observability

| Signal | Implementation |
|---|---|
| Metrics | Micrometer with Prometheus registry on management port (`/actuator/prometheus`), tag `application`; Helm `ServiceMonitor` optional. |
| Traces | OpenTelemetry via `spring-boot-starter-opentelemetry`, OTLP export (`OTEL_EXPORTER_OTLP_ENDPOINT`), sampling 10 % by default (`WZ_TRACE_SAMPLING`), 100 % locally; collector `otel/opentelemetry-collector-contrib:0.164.0` in the local stack. |
| Logs | JSON to stdout → Log Analytics / Cloud Logging → SIEM (Sentinel, Chronicle, Splunk). |
| Correlation | `X-Correlation-Id` accepted if well-formed (`[A-Za-z0-9._-]{8,64}`), otherwise generated; propagated by the BFF, stored on outbox events and audit rows, returned in problem responses. |
| Health | Liveness/readiness probes on management port; details hidden; startup probe allows Flyway on first start. |
| Operational KPIs to alert on (proposal) | outbox `DEAD` count > 0, outbox lag, mailbox poll failures (`EHS_MAILBOX_POLL` FAILURE audits), audit-chain verification result (scheduled), authentication-failure rate, 5xx rate, DB connection saturation, notification `FAILED` count. |

## 12. Scalability, high availability and disaster recovery

| Aspect | Design |
|---|---|
| Stateless compute | Core API and BFF are stateless (BFF sessions in PostgreSQL); 2 replicas minimum, HPA 2–6 on CPU 70 % / memory 80 %, PDB `minAvailable: 1`, topology spread across zones. |
| Concurrency in jobs | Outbox relay and notification dispatch use `SKIP LOCKED`; audit chain uses an advisory lock (a throughput ceiling for audit writes – acceptable for expected volumes, to be load-tested); mailbox job tolerant of concurrent runs. |
| Virtual threads | Enabled (`spring.threads.virtual.enabled`), reducing thread-pool tuning for I/O-bound workloads. |
| Database HA | Azure Flexible Server zone-redundant HA (standby zone 2), Cloud SQL `REGIONAL`, CNPG 3 instances with at least one synchronous standby (`dataDurability: required`). |
| Backups | Azure: 35-day retention, geo-redundant backup (default on), CMK; GCP: 30 retained backups, 7 days of transaction logs (PITR); CNPG: continuous WAL archiving + scheduled base backups to object storage. |
| Region failure | Not automated in the current IaC (single region). |

**Proposed targets (decision D9):**

| Tier | Scenario | Proposed RPO | Proposed RTO | Basis |
|---|---|---|---|---|
| Zone failure | AZ outage | 0 (synchronous standby) | ≤ 15 min | Zone-redundant DB HA + multi-zone pods |
| Data corruption / operator error | logical error | ≤ 15 min (PITR) | ≤ 4 h | PITR restore to new server, re-point application |
| Region failure | regional outage | ≤ 1 h (geo-backup) – lower with cross-region replica | ≤ 24 h (≤ 4 h with warm standby region) | Geo-redundant backup today; cross-region read replica + IaC re-deploy as increment |

OH case management is important but not flight-safety-critical in real time: rostering keeps its last known fitness
status, and fitness outcomes are re-sent by outbox replay after recovery. BCP must define the manual fallback for
fitness-for-duty decisions during an outage.

## 13. Licensing notes

| Item | Licence / obligation |
|---|---|
| SNOMED CT | Use requires an **affiliate licence** from SNOMED International, free of charge in member countries via the National Release Centre; non-member countries pay fees. The airline's operating countries determine obligations; national extensions may apply. Seed data contains no SNOMED content. |
| LOINC | Free under the LOINC licence (Regenstrief); attribution and licence-notice requirements; seed contains three LOINC codes. |
| ICD-10 / ICD-11 | ICD-11 under CC BY-ND 3.0 IGO (WHO); ICD-10 – WHO copyright, national modifications (ICD-10-GM, ICD-10-CM, ICD-10-AM) have their own terms; seed contains three ICD-10 codes. |
| HL7 FHIR / v2 / ODH | HL7 specifications are free to use (FHIR CC0 for the specification); HAPI FHIR and HAPI HL7 v2 libraries are Apache 2.0 / MPL-style dual licences (HAPI HL7 v2: MPL 1.1 or GPL – use under MPL). |
| Open-source stack | Spring (Apache 2.0), Hibernate (Apache 2.0 for Hibernate 7), PostgreSQL (PostgreSQL licence), Apache POI (Apache 2.0), React (MIT), Capacitor (MIT), Keycloak (Apache 2.0), Flyway Community (Apache 2.0). The CycloneDX SBOM is the authoritative inventory for licence review. |
| Commercial options | Eclipse Temurin is free; commercial Java support (e.g. from Microsoft, Red Hat, Azul) optional. Terminology servers (Ontoserver – commercial; Snowstorm – Apache 2.0) to be chosen. |

## 14. Technology selection rationale

Each table records the options assessed and why the repository's choice was made. Versions are those pinned in the
repository.

### 14.1 Language and application framework

**Choice:** Java 21 LTS (Eclipse Temurin `21.0.12_8`), Spring Boot **4.1.2** (Spring Security 7, Hibernate 7 via the
Boot BOM), Maven 3.9 (`maven:3.9.16-eclipse-temurin-21-noble`).

| Criterion | Spring Boot 4 | Quarkus | Micronaut | Jakarta EE (e.g. Open Liberty / WildFly) |
|---|---|---|---|---|
| OAuth2 resource server with multi-issuer, OIDC client for BFF | Built-in (`JwtIssuerAuthenticationManagerResolver`, OAuth2 client) | OIDC extension; multi-tenant via tenant resolver | Security module; less mature multi-issuer | MicroProfile JWT; BFF needs custom work |
| Session store for BFF | Spring Session JDBC | custom / Infinispan | custom | container sessions |
| Ecosystem (HAPI FHIR, POI, Flyway, Testcontainers) | first-class | good | good | good |
| Talent availability (airline IT, integrators) | highest | medium | low–medium | medium |
| Start-up time / memory | moderate (mitigated by virtual threads, CDS/AOT optional) | best (native) | very good | moderate |
| Long-term support | VMware/Broadcom OSS + commercial support | Red Hat build | Oracle-backed OSS | vendor-dependent |
| **Verdict** | **Selected** – security features needed by this design exist out of the box; broadest skills market | Strong alternative if native images or very low footprint become a requirement | Not selected | Not selected |

### 14.2 Database

**Choice:** PostgreSQL **16** with Flyway (Spring Boot managed; Job image `flyway/flyway:11.20.3-alpine`).

| Criterion | PostgreSQL 16 | SQL Server | Oracle |
|---|---|---|---|
| Managed service on Azure / GCP / on-prem | Azure Flexible Server, Cloud SQL, CloudNativePG | Azure SQL / MI; Cloud SQL for SQL Server; on-prem | Oracle DB@Azure/Google, Exadata; on-prem |
| Features used (`SKIP LOCKED`, advisory locks, partial indexes, `timestamptz`, triggers) | all native | mostly available (different syntax) | available |
| Licence cost | none | per-core licensing | highest |
| Portability across target clouds | highest | medium | medium |
| Audit extension | pgaudit (configured on Azure and CNPG) | SQL Server Audit | Unified Auditing |
| **Verdict** | **Selected** | Viable if the airline standardises on Microsoft data platform | Not selected |

### 14.3 Identity provider

**Choice:** any OIDC provider; Microsoft Entra ID assumed for workforce; Keycloak 26.8.1 for local and optionally
partners.

| Criterion | Microsoft Entra ID | Keycloak (self-hosted) | Okta / Ping |
|---|---|---|---|
| Workforce SSO, MFA, Conditional Access, device compliance (Intune) | native | via external IdP brokering | native (own policy engine) |
| Partner / external identities | Entra External ID (B2B/B2C) | realms per audience | Okta CIC / Ping |
| Operating effort | SaaS | must be operated, patched, made HA | SaaS |
| Cost | often included in M365 E3/E5 | none (support optional) | per user |
| Framework support | app roles + group GUID mapping | realm/client roles | configurable claim paths |
| **Verdict** | **Primary** (assumption A1) | Local/dev; partner realm option | Supported alternatives |

### 14.4 Messaging

**Choice now:** transactional outbox in PostgreSQL + in-process delivery; external brokers via the `EventPublisher` SPI.

| Criterion | Outbox + in-process (current) | Apache Kafka (e.g. Confluent, Event Hubs Kafka API) | Azure Service Bus | Google Pub/Sub |
|---|---|---|---|---|
| Atomicity with DB change | yes (outbox) | needs outbox anyway | needs outbox | needs outbox |
| Additional infrastructure | none | significant (or managed service) | managed | managed |
| Ordering / replay | per row, operator replay | partitions, long retention, consumer replay | sessions, dead-letter queue | ordering keys, seek/replay |
| Fit for enterprise fan-out (rostering, HR, ERP, SMS) | limited (logging stand-in) | strong | strong (Azure estates) | strong (GCP estates) |
| Hybrid / on-prem | yes | yes | Azure only | GCP only |
| **Verdict** | **Sufficient for module-to-module events and for first increments** | Choose if a group-wide Kafka platform exists | Choose for Azure-hosted deployment | Choose for GCP-hosted deployment |

Recommendation (decision D3): keep the outbox as the reliability boundary in all cases and select **one** broker per
hosting decision; the adapter is a small component implementing `EventPublisher`.

### 14.5 FHIR and HL7 libraries

**Choice:** HAPI FHIR structures R4 **8.12.1**, HAPI HL7 v2 **2.6.0** (`hapi-structures-v25`).

| Option | Assessment |
|---|---|
| HAPI FHIR (library only, embedded) | **Selected** – de-facto Java reference implementation, parsing/validation, no separate server to operate. |
| HAPI FHIR JPA server / Azure Health Data Services / Google Cloud Healthcare API | Candidates if a full FHIR repository becomes a requirement (e.g. partner query access, SMART on FHIR); adds a second data store for PHI. |
| Firely .NET SDK | Not applicable (Java stack). |
| Integration engine (Rhapsody, InterSystems HealthShare, Mirth/NextGen Connect) | Complementary for MLLP, routing and mapping to many lab systems; can terminate MLLP and forward to the HTTPS endpoint. |

### 14.6 Mobile

**Choice:** Capacitor **8.6.0** wrapping the same React codebase, plus installable PWA.

| Criterion | Capacitor | Flutter | React Native | Native (Swift/Kotlin) |
|---|---|---|---|---|
| Code reuse with web portal | ~100 % (same React app) | none (Dart) | partial (shared logic) | none |
| Access to MDM AppConfig, Keychain/Keystore, biometrics | via plugins | via plugins | via modules | native |
| Intune / Workspace ONE SDK integration | native projects can embed SDKs | supported (wrapper plugins) | supported | best |
| UX fidelity for simple form-based app | adequate | high | high | highest |
| Team skills / cost | lowest (web skills) | new language | medium | two teams |
| **Verdict** | **Selected** – the app is form- and status-centric; one codebase across web, PWA and both stores | Not selected | Not selected | Reconsider only if offline clinical use or heavy device integration is required |

### 14.7 Web framework and authentication pattern

**Choice:** React **19.3.1**, TypeScript **6.0.3**, Vite **8.3.5**, no UI framework, hash routing; BFF for browser apps.

| Criterion | React | Angular |
|---|---|---|
| Shared code with Capacitor app | direct | possible (Ionic Angular) |
| Ecosystem / hiring | largest | large (strong in enterprises) |
| Built-in structure (DI, forms, router) | assembled from libraries | batteries included |
| Bundle and dependency surface | small (only React + React DOM at runtime) | larger |
| **Verdict** | **Selected** | Valid alternative; no decisive advantage here |

| Criterion | BFF (selected) | SPA holds tokens (public client + PKCE in browser) |
|---|---|---|
| Token theft via XSS | tokens not reachable by JavaScript | access/refresh tokens exposed to XSS |
| Refresh-token handling | server-side, encrypted at rest | browser storage or rotation tricks |
| CSRF | needs CSRF tokens (implemented) | not applicable |
| Infrastructure | one more service + session store | none |
| Alignment with IETF *OAuth 2.0 for Browser-Based Apps* | recommended pattern for sensitive data | discouraged for high-value data |

### 14.8 Container platform

| Criterion | AKS | GKE | OpenShift / RKE2 (on-prem, hybrid) |
|---|---|---|---|
| Fit with Entra ID / Intune estate | strongest | good (federation) | good |
| Supply-chain enforcement | Azure Policy, Defender, image integrity (preview features) | Binary Authorization (configured) | OpenShift policy / Kyverno, sigstore |
| Managed PostgreSQL alongside | Flexible Server | Cloud SQL | CloudNativePG (self-operated) |
| Data residency / PHI on-prem | EU regions | EU regions | full control |
| Repository support | `deploy/terraform/azure`, `values-azure.yaml` | `deploy/terraform/gcp`, `values-gcp.yaml` | `deploy/hybrid`, `values-hybrid.yaml` |
| **Verdict** | Default if Microsoft-centric | Equivalent alternative | Hybrid option where PHI must remain on-premises |

Helm chart `0.1.0` requires Kubernetes `>= 1.30`.

### 14.9 Infrastructure as code

**Choice:** Terraform `>= 1.11` with `hashicorp/azurerm ~> 4.81`, `hashicorp/google ~> 8.9` (+ `google-beta`),
`hashicorp/random ~> 3.7`; Helm for workloads.

| Option | Assessment |
|---|---|
| Terraform (selected) | Multi-cloud, write-only arguments keep secrets out of state, large module ecosystem; licence is BSL 1.1 for Terraform ≥ 1.6 – acceptable for internal use; **OpenTofu** is a drop-in open-source alternative. |
| Bicep / ARM | Azure only. |
| Pulumi | General-purpose languages; smaller operational footprint in airline IT. |
| Crossplane | Kubernetes-native; more operational complexity. |

### 14.10 CI/CD

**Choice:** GitHub Actions (design brief), with an Azure DevOps equivalent to be documented.

| Criterion | GitHub Actions | Azure DevOps Pipelines | GitLab CI |
|---|---|---|---|
| OIDC federation to Azure/GCP (no stored cloud secrets) | yes (configured in Terraform) | yes (workload identity federation) | yes |
| Security tooling (CodeQL, secret scanning, Dependabot) | native (GitHub Advanced Security) | via extensions / GHAzDO | native (Ultimate) |
| Environments with approvals | yes | yes | yes |
| **Verdict** | **Selected** | Supported alternative where the airline standardises on ADO | Not selected |

### 14.11 Version summary

| Component | Version pinned in repository | Source |
|---|---|---|
| Java runtime | Temurin 21 (`eclipse-temurin:21.0.12_8-jre-noble`) | `deploy/docker/Dockerfile.core`, `.bff` |
| Build image | `maven:3.9.16-eclipse-temurin-21-noble` | Dockerfiles |
| Spring Boot | 4.1.2 | `pom.xml` parent |
| HAPI FHIR / HAPI HL7 v2 | 8.12.1 / 2.6.0 | `pom.xml` |
| Apache POI | 5.5.1 | `pom.xml` |
| springdoc-openapi | 3.0.2 | `pom.xml` |
| logstash-logback-encoder | 9.0 | `pom.xml` |
| ArchUnit / JaCoCo | 1.5.1 / 0.8.16 | `pom.xml` |
| CycloneDX / dependency-check | 2.9.3 / 13.0.0 | `pom.xml` |
| SpotBugs plugin / FindSecBugs | 4.10.5.0 / 1.14.0 | `pom.xml` |
| PostgreSQL | 16 (compose `postgres:16.15-bookworm`; CNPG `16.15`; Testcontainers `postgres:16-alpine`) | `deploy/`, tests |
| Flyway CLI image | 11.20.3-alpine | `values.yaml` |
| Keycloak (local) | 26.8.1 | `deploy/local/docker-compose.yml` |
| OpenTelemetry Collector (local) | contrib 0.164.0 | `deploy/local/docker-compose.yml` |
| React / React DOM | 19.3.1 | `web/*/package.json` |
| TypeScript / Vite / Vitest / ESLint | 6.0.3 / 8.3.5 / 5.0.5 / 10.13.0 | `web/*/package.json` |
| Capacitor core/iOS/Android/CLI | 8.6.0 (`@capacitor/app` 8.1.2, `@capacitor/browser` 8.0.5) | `web/employee-app/package.json` |
| Node build image / nginx | `node:24.22.2-alpine3.24` / `nginx-unprivileged:1.30.6-alpine3.24` | `web/*/Dockerfile` |
| Terraform / azurerm / google | `>= 1.11` / `~> 4.81` / `~> 8.9` | `deploy/terraform/*/versions.tf` |
| Helm chart / Kubernetes | 0.1.0 / `>= 1.30` | `deploy/helm/wellnessz/Chart.yaml` |

## 15. Risks, assumptions and open items

### 15.1 Architecture risks

| ID | Risk | Likelihood | Impact | Mitigation / owner |
|---|---|---|---|---|
| R1 | Master-key compromise exposes all field-encrypted data (single key, HKDF-derived versions) | Low | High | Envelope encryption with KMS/HSM, strict Key Vault RBAC, re-encryption job, rotation runbook (D5) |
| R2 | Outbox payloads unencrypted and never purged; accumulate personal data | High | Medium | Payload encryption or purge of `PUBLISHED` rows; retention policy (D7) |
| R3 | No retention/deletion jobs → GDPR storage-limitation non-compliance | High | High | Retention schedule and purge jobs before go-live (D6) |
| R4 | DB owner could rewrite audit chain | Low | High | External hash anchoring, SIEM streaming, separation of duties for DB owner role (O2) |
| R5 | Outbound guard checks top-level fields only | Medium | High | Flat event records rule, code review, contract tests per destination |
| R6 | Mobile refresh token in memory only until secure-storage plugin registered; MAM SDK not integrated | Medium (functional) | Medium | Register Keychain/Keystore plugin and MAM SDK in native projects before store release |
| R7 | CI/CD pipeline not yet complete → supply-chain controls not evidenced | Medium | High | Complete `.github/workflows/`, include signing and admission control |
| R8 | Single-region deployment | Low | High | Cross-region replica and DR runbook (D9) |
| R9 | Audit advisory lock serialises writers – throughput ceiling | Low | Medium | Load test; partitioned chains per shard if needed |
| R10 | Authentication-failure audit writes per invalid token could be abused to amplify load | Medium | Low | WAF rate limiting; sampling or aggregation of failure events |
| R11 | Module enablement flags not enforced at runtime | Medium | Low | Enforce in module guard (Developer Guide) |
| R12 | SameSite documentation inconsistency (Lax vs Strict) | – | Low | Align documentation (O3) |
| R13 | Terminology licensing (SNOMED CT) in non-member countries | Medium | Medium | Licence assessment per operating country (D10) |

### 15.2 Open items

| ID | Item |
|---|---|
| O1 | Confirm IdP claims for `employee_id` and `org_scope` (Entra directory extensions or claims-mapping policy fed from HRIS). |
| O2 | Implement audit-hash anchoring and audit export to SIEM; scheduled `/audit/verify` with alerting. |
| O3 | Align `SameSite` documentation with the BFF configuration (`Lax`). |
| O4 | Apply Entra group-GUID mapping in the BFF role gate if app roles are not used. |
| O5 | Add a scheduler lock (e.g. ShedLock) for mailbox polling. |
| O6 | Decide on in-cluster mTLS (service mesh) vs NetworkPolicies only. |
| O7 | Add a read-only reporting DB role for warehouse extraction. |
| O8 | Define DAST in the pipeline (ZAP baseline). |

## 16. Decisions requested from the steering committee

| ID | Decision | Options | Recommendation |
|---|---|---|---|
| D1 | Primary hosting | AKS · GKE · hybrid (on-prem PHI) | AKS in EU region unless data-residency analysis requires hybrid |
| D2 | Workforce and partner identity | Entra ID + External ID · Entra ID + Keycloak partner realm | Entra ID for workforce; decide partner IdP with E04 |
| D3 | Enterprise broker | Service Bus · Pub/Sub · group Kafka | Follow D1; keep outbox in all cases |
| D4 | Mobile distribution | Public stores + MAM · managed (MDM-only) distribution | MDM-managed distribution with Intune APP for crew devices |
| D5 | Key management | Master key in secret store (today) · envelope encryption with KMS/HSM | Envelope encryption + re-encryption job before go-live |
| D6 | Retention schedule | per classification and record type | DPO + OH governance to define; purge jobs in increment 2 |
| D7 | Outbox payload protection | encrypt · purge after N days · both | Both (purge after 30 days, encrypt payloads) |
| D8 | Policy engine | in-code ABAC (today) · OPA/Cedar externalised | Keep in-code until policy count/complexity justifies externalisation |
| D9 | RPO/RTO targets | as proposed in Section 12 | Approve proposals; fund cross-region replica |
| D10 | Terminology | local tables · terminology server (Snowstorm/Ontoserver) · national service | Terminology server with licensed national editions |
| D11 | CI/CD platform | GitHub Actions · Azure DevOps | GitHub Actions with GitHub Advanced Security |

## 17. Roadmap of increments

| Increment | Scope | Key platform work |
|---|---|---|
| **0 – Base framework** (this repository) | Platform services, EHS intake, case kernel, BFF, employee app, workspace, deployment assets | – |
| **1 – Production readiness** | CI/CD completion, envelope encryption + re-encryption job, outbox purge/encryption, audit anchoring, secure-storage & MAM plugins, enforcement of module flags, broker adapter (D3), rostering transport, HRIS sync, DR runbook | pen test, SOC 2 readiness assessment |
| **2 – Core pathway** | E01 configurable rule engine and management referral, E02 triage/queues/SLAs, E03 OH record & encounters (FHIR mapping), E05 full fitness/RTW, retention jobs | terminology server, notification triggers from events |
| **3 – Prevention & absence** | E06 absence (T&A feed), E07 surveillance/medicals/immunisation (hazard-driven recall), E11 travel health | scheduled system triggers, regulatory reports |
| **4 – Restricted class & external parties** | E09 psychological health & peer support, E10 substance testing, E04 referrals & partner portal (consent gate), E08 claims & insurers | partner IdP, consent enforcement, break-glass |
| **5 – Engagement & insight** | E12 health promotion & wearables, E14 scheduling & group clinics, E13 extended self-service, warehouse/BI feed, governed predictive models | pseudonymised extracts, model governance |

## Appendix A – API surface of the base framework

| Method & path | Roles | Module |
|---|---|---|
| `GET /api/v1/me` | authenticated | wz-security |
| `GET /api/v1/cases`, `GET /cases/{id}`, `GET /cases/{id}/status` | authenticated, ABAC-filtered | wz-case-kernel |
| `POST /api/v1/cases/self-referral` | EMPLOYEE | wz-case-kernel |
| `POST /api/v1/cases/{id}/transition` | OH_CLINICIAN, CASE_MANAGER, PSYCH_CLINICIAN + ABAC | wz-case-kernel |
| `POST /api/v1/cases/{id}/fitness` | OH_CLINICIAN, PSYCH_CLINICIAN + ABAC | wz-case-kernel |
| `GET /api/v1/modules` | authenticated | wz-case-kernel |
| `POST /api/v1/consents`, `GET /consents/me`, `DELETE /consents/{id}` | data subject | wz-security |
| `GET /api/v1/audit`, `GET /audit/verify` | AUDITOR, PLATFORM_ADMIN | wz-security |
| `GET /api/v1/masterdata/{org-units\|job-codes\|hazards}` | authenticated | wz-masterdata |
| `POST /api/v1/masterdata/{org-units\|job-codes\|hazards}` | OH_ADMIN, PLATFORM_ADMIN, INTEGRATION | wz-masterdata |
| `GET\|PUT /api/v1/masterdata/config/{key}` | OH_ADMIN, PLATFORM_ADMIN | wz-masterdata |
| `POST /api/v1/integration/inbound/{source}` (Idempotency-Key) | INTEGRATION, PLATFORM_ADMIN, OH_ADMIN | wz-integration |
| `GET /api/v1/integration/connectors`, `GET /integration/events` | INTEGRATION, PLATFORM_ADMIN, OH_ADMIN | wz-integration |
| `POST /api/v1/integration/events/{id}/replay` | PLATFORM_ADMIN, OH_ADMIN | wz-integration |
| `GET /api/v1/integration/fhir/metadata`, `POST /integration/fhir/{type}` | INTEGRATION, PLATFORM_ADMIN, OH_ADMIN | wz-integration |
| `POST /api/v1/integration/hl7v2` | INTEGRATION, PLATFORM_ADMIN, OH_ADMIN | wz-integration |
| `POST /api/v1/intake/ehs/excel` | OH_ADMIN, CASE_MANAGER, INTEGRATION | wz-ehs-intake |
| `POST /api/v1/intake/ehs/mailbox/poll` | OH_ADMIN, PLATFORM_ADMIN | wz-ehs-intake |
| `GET /api/v1/intake/ehs/incidents` | OH_ADMIN, CASE_MANAGER, OH_CLINICIAN | wz-ehs-intake |
| `GET /api/v1/analytics/kpis/cases-by-status` | MANAGER, HR, OH_CLINICIAN, OH_ADMIN, CASE_MANAGER, PLATFORM_ADMIN | wz-analytics |
| `POST /api/v1/notifications/test` | PLATFORM_ADMIN | wz-notification |
| BFF: `/oauth2/authorization/wellnessz`, `/login/oauth2/code/wellnessz`, `POST /logout`, `GET /bff/session`, `/bff/api/**` | session | wz-bff |

## Appendix B – Glossary

| Term | Meaning |
|---|---|
| ABAC | Attribute-based access control – decisions based on attributes of user, action and resource |
| BFF | Backend-for-Frontend – server component holding OAuth tokens on behalf of a browser app |
| CSI | Container Storage Interface; the Secrets Store CSI driver mounts secrets from a vault as files |
| EHS | Environment, Health & Safety incident-management system |
| HKDF | HMAC-based key derivation function (RFC 5869) |
| MAM / MDM | Mobile application management / mobile device management |
| ODH | HL7 Occupational Data for Health |
| Outbox | Pattern that writes events in the same DB transaction as the business change and relays them afterwards |
| PKCE | Proof Key for Code Exchange (RFC 7636) |
| RPO / RTO | Recovery point / recovery time objective |
| Status-only | Disclosure level exposing case status, fitness status and dates without clinical content |
