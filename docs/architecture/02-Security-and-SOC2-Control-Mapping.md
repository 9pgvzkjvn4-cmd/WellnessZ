# WellnessZ OHCM – Security and SOC 2 Control Mapping

| | |
|---|---|
| **Document** | 02 – Security and SOC 2 Control Mapping |
| **System** | WellnessZ Airline Occupational Health Case Management (OHCM) – base framework |
| **Audience** | Cyber GRC team, CISO, internal audit, Data Protection Officer, CIO |
| **Baseline** | Repository working tree, Maven version `0.1.0-SNAPSHOT`; updated for **increment 1** (E01, E02 – Section 11) |
| **Companion documents** | 01 – Technology Architecture and Technology Selection · 03 – Developer Guide |

> **Important – what SOC 2 is and is not.** A SOC 2 Type 2 report is an attestation by an independent CPA firm about
> the controls of a **service organisation** – the organisation that operates WellnessZ – over a review period
> (typically 6–12 months). Software cannot be "SOC 2 compliant" on its own. This document shows which technical
> controls the WellnessZ framework provides that are **designed to support** the Trust Services Criteria (TSC),
> which controls belong to the cloud provider, and which must be **operated by the organisation** to obtain an
> unqualified opinion. Statements reflect the code at the stated baseline; items marked *planned* or *extension point*
> do not yet exist and cannot be evidenced.

## 1. Scope and system boundary

**In scope for this mapping:** the core API (`apps/wz-core-app` and `platform/*`), the BFF (`apps/wz-bff`), the web and
mobile clients (`web/*`), the PostgreSQL schemas they own, the deployment assets (`deploy/*`) and the software
development lifecycle around the repository.

**Data in scope:** occupational-health records (special-category health data, GDPR Art. 9; treated as PHI-equivalent),
employee identifiers and contact details, consent records, audit records, integration messages.

**Out of scope (subservice organisations / carve-out candidates):** the cloud provider (Microsoft Azure or Google Cloud
– physical security, hypervisor, managed PostgreSQL, Key Vault/Secret Manager), the identity provider (Microsoft Entra
ID or other OIDC IdP), the MDM provider, SMTP/SMS/push gateways, and enterprise systems that receive status-only data.
Their SOC 2 / ISO 27001 reports must be obtained and reviewed (complementary subservice organisation controls, CSOCs).

**Applicable TSC categories recommended for the report:** Security (Common Criteria, mandatory), Availability,
Confidentiality, Privacy. Processing Integrity is recommended for fitness-for-duty outputs because rostering decisions
depend on them.

## 2. Shared-responsibility model

| Control domain | Framework (this repository) provides | Cloud provider / SaaS provides | Organisation (WellnessZ operator) must operate |
|---|---|---|---|
| Governance, policies, risk | Documentation, data classification enum, ArchUnit-enforced architecture rules | Their own SOC 2 / ISO 27001 reports | ISMS, policies, risk register, management review, vendor management, training |
| Identity & authentication | OIDC multi-issuer validation, role mapping, no local passwords, session controls in BFF, PKCE in mobile | IdP availability, MFA, Conditional Access engine | IdP configuration (MFA, CA policies), joiner/mover/leaver, **quarterly access reviews**, role assignment approval, privileged-access management |
| Authorisation | RBAC rules, ABAC policy engine (deny-overrides, default deny), status-only projection, restricted class | – | Role definitions, approval of policy changes, segregation of duties |
| Encryption & keys | AES-256-GCM field encryption, HKDF key separation, `ENC()` secrets, TLS `verify-full` to DB, encrypted BFF sessions | Storage encryption, KMS/HSM, Key Vault / Secret Manager durability | Key generation ceremony, **key rotation**, key-access reviews, envelope-encryption decision, certificate lifecycle |
| Logging & monitoring | Hash-chained append-only audit, audit verification API, JSON logs without PHI, correlation ids, metrics, traces | Log storage, platform audit logs (Activity Log / Cloud Audit Logs) | SIEM onboarding, alert rules, **24×7 or business-hours monitoring**, log review, retention settings, incident response |
| Change & SDLC | Tests (unit, integration, architecture), SAST, SCA, SBOM tooling in the build, pinned versions, migration discipline | GitHub/ADO platform security | Branch protection, mandatory PR review, CAB/approval records, release evidence retention, pipeline completion |
| Vulnerability management | dependency-check fail ≥ CVSS 7, dependency review, npm audit, CodeQL, Trivy image gate, FindSecBugs (Maven profile), patched base images | Managed-service patching (DB, AKS/GKE control plane) | Patch SLAs, **annual penetration test**, vulnerability triage, exception register |
| Network | NetworkPolicies default deny, private endpoints (IaC), WAF (IaC), strict headers, CORS closed by default | DDoS protection, network fabric | Firewall rule reviews, WAF tuning, exposure reviews |
| Availability & DR | Stateless replicas, HPA, PDB, zone spread, outbox replay, idempotency | Zone-redundant DB HA, backups, region services | **BCP/DR tests**, backup-restore tests, capacity planning, RPO/RTO approval |
| Physical | – | Data-centre security | Office/endpoint security, on-prem DC for hybrid option |
| Privacy | Consent register, data-subject self-access, status-only rule, de-identified analytics with suppression, pseudonymisation utility | Data-processing terms | Privacy notices, lawful-basis assessment, DPIA, **retention schedule and deletion**, data-subject request handling, breach notification |
| Mobile | PKCE, no PHI caching, idle logout, MDM AppConfig hook | MDM/MAM platform | Device enrolment policy, app protection policies, Conditional Access, store/distribution management |

## 3. Trust Services Criteria mapping – Common Criteria (Security)

Legend for **Responsibility**: **F** = framework control (technical, in repository), **C** = cloud/SaaS provider,
**O** = organisation-operated. **Status**: Implemented · Partial · Planned · Org (organisational, outside software).

### 3.1 CC1 – Control environment

| Criterion | Framework contribution | Evidence source | Resp. | Status |
|---|---|---|---|---|
| CC1.1 Integrity and ethical values | – | Code of conduct, HR records | O | Org |
| CC1.2 Board oversight | Steering-committee decisions (doc 01, Section 17) | Committee minutes | O | Org |
| CC1.3 Structures, reporting lines, authorities | Canonical roles (`Roles`) map to job functions; module ownership by Maven module | RACI, CODEOWNERS (recommended) | O/F | Partial |
| CC1.4 Competence | Developer Guide with secure-coding standards | Training records | O | Org |
| CC1.5 Accountability | Every action attributed to an authenticated subject in the audit trail | `/api/v1/audit` | F/O | Implemented |

### 3.2 CC2 – Communication and information

| Criterion | Framework contribution | Evidence source | Resp. | Status |
|---|---|---|---|---|
| CC2.1 Quality information for internal control | Structured audit records with correlation ids; metrics; OpenAPI contract | audit export, Prometheus, `/v3/api-docs` (non-prod) | F | Implemented |
| CC2.2 Internal communication of objectives and responsibilities | Architecture and developer documentation in repo | Git history of `docs/` | O/F | Implemented (docs) |
| CC2.3 External communication | Privacy notice version recorded with each consent (`notice_version`) | `platform.consent` | F/O | Implemented (notice content: Org) |

### 3.3 CC3 – Risk assessment

| Criterion | Framework contribution | Evidence source | Resp. | Status |
|---|---|---|---|---|
| CC3.1 Objectives specified | Principles P1–P10 (doc 01) | doc 01 | O | Implemented (docs) |
| CC3.2 Risks identified and analysed | STRIDE threat model (Section 7), risk register in doc 01 Section 16 | this document | O | Partial (formal register: Org) |
| CC3.3 Fraud risk | Segregation: clinicians record fitness, managers cannot; replay/config changes audited | audit trail, tests | F/O | Implemented |
| CC3.4 Changes affecting controls | ArchUnit rules fail the build if boundaries erode; migration review | CI test reports | F/O | Implemented |

### 3.4 CC4 – Monitoring activities

| Criterion | Framework contribution | Evidence source | Resp. | Status |
|---|---|---|---|---|
| CC4.1 Ongoing / separate evaluations | `GET /api/v1/audit/verify` integrity check; health probes; test suite on every build | verification results (schedule + retain), CI runs | F/O | Implemented (scheduling: Org) |
| CC4.2 Deficiencies communicated | Problem responses carry correlation id for tickets; dead-letter visibility (`/integration/events?status=DEAD`) | ticketing system | O | Org |

### 3.5 CC5 – Control activities

| Criterion | Framework contribution | Evidence source | Resp. | Status |
|---|---|---|---|---|
| CC5.1 Control activities selected | Default deny at URL, ABAC, outbound and network layers | code, Helm NetworkPolicies | F | Implemented |
| CC5.2 Technology general controls | IaC (Terraform), Helm, pinned versions, hardened containers | Terraform plans/state, Git | F/O | Implemented |
| CC5.3 Policies and procedures deployed | Coding standards and Definition of Done (doc 03) | PR templates, reviews | O | Partial |

### 3.6 CC6 – Logical and physical access controls

| Criterion | Framework control | Evidence source | Resp. | Status |
|---|---|---|---|---|
| CC6.1 Logical access security, infrastructure and architecture | OIDC resource server with trusted-issuer allow-list and audience check; RBAC URL rules; `@PreAuthorize`; ABAC `PolicyEngine` with deny-overrides and default deny; status-only projection type; restricted class policy; field-level AES-256-GCM; DB roles least privilege (`${app_role}` DML-only); secrets via CSI + workload identity | code (`ResourceServerSecurityConfig`, `DefaultPolicies`, migrations), integration tests, Helm values | F | Implemented |
| CC6.2 Registration and authorisation of users | No local accounts; users provisioned in IdP; role claims mapped (`RoleClaims`, Entra group GUID mapping) | IdP provisioning logs, access requests | O/C | Org (IdP) |
| CC6.3 Role-based access, least privilege, segregation of duties | 20 canonical roles (9 added in increment 1, Section 11.1); disclosure levels; clinicians vs managers; AUDITOR read-only to audit; INTEGRATION limited to `/integration/**` | role matrix (doc 01 App. A), **quarterly access review** | F/O | Implemented / Org |
| CC6.4 Physical access | – | cloud provider SOC 2 | C | Carve-out |
| CC6.5 Disposal of data and assets | Consent withdrawal preserves evidence; delivered outbox events purged after 30 days (configurable), idempotency records after 7 days; **no retention/deletion jobs for OH records yet** | purge configuration, DB counts | F/O | Partial |
| CC6.6 Protection against threats from outside the boundary | WAF (Front Door / App Gateway WAF_v2 / Cloud Armor) in Terraform; TLS 1.2+; CSP, HSTS, X-Frame-Options; CORS closed; EHS sender allow-list; hardened Excel parser; FHIR JSON-only (no XXE) | Terraform, response headers (scan), tests | F/C | Implemented |
| CC6.7 Restriction of transmission and movement of information | TLS everywhere incl. DB `verify-full`; **OutboundPayloadGuard** allow-list per destination (default deny); notification content rules; BFF header allow-list; mobile no-store, no localStorage | config `outbound-allowlist`, test `fitnessOutcomeIsPublishedToRosteringAsStatusOnly` | F | Implemented |
| CC6.8 Prevention/detection of unauthorised or malicious software | Trivy image gate (CRITICAL/HIGH with fix fails the build), cosign keyless signatures with SBOM and SLSA provenance attestations, signature verification in the deploy job, Binary Authorization (GKE); CodeQL, gitleaks; read-only root FS, non-root, dropped capabilities; no formula evaluation in Excel | `container.yml`, `deploy-env.yml`, Terraform `binauthz.tf`, code-scanning alerts | F/O | Implemented (cluster admission enforcement on AKS/on-prem: recommended) |

### 3.7 CC7 – System operations

| Criterion | Framework control | Evidence source | Resp. | Status |
|---|---|---|---|---|
| CC7.1 Detection of configuration changes and vulnerabilities | dependency-check (CVSS ≥ 7 fails), SpotBugs/FindSecBugs, CycloneDX SBOM; Dependabot proposed for images; config changes audited (`CONFIG_CHANGE`) | SARIF/HTML reports, SBOM, audit | F/O | Implemented (build) |
| CC7.2 Monitoring for anomalies | Audit of authentication failures and authorisation denials (`SecurityEventAuditor`), denied case reads, rejected mails; JSON logs with correlation; metrics; GCP Data Access audit logs; pgaudit | SIEM, audit trail | F/O | Implemented (SIEM rules: Org) |
| CC7.3 Evaluation of security events | Audit chain verification; correlation id across BFF, API, outbox | incident tickets | O | Org |
| CC7.4 Incident response | Replay of dead-lettered events; fail-safe defaults (guard aborts transaction) | IR plan, post-incident reviews | O | Org |
| CC7.5 Recovery from incidents | Idempotent re-processing (mailbox, outbox, Idempotency-Key); PITR backups (IaC) | restore test records | F/O | Implemented / Org |

### 3.8 CC8 – Change management

| Criterion | Framework control | Evidence source | Resp. | Status |
|---|---|---|---|---|
| CC8.1 Changes authorised, designed, developed, tested, approved, implemented | Git history; Flyway versioned, immutable migrations (owner role separate from runtime role); `ddl-auto: validate`; CI on every PR and push (`ci.yml`: tests incl. architecture tests, SCA, dependency review, secret scan, IaC checks); SBOM per build; CD (`cd.yml`) promotes signed images dev → test → prod through GitHub Environments with atomic Helm deploys; suppression files require justification/owner/expiry | PRs with approvals, CI run logs and artefacts, deployment records, `flyway_schema_history`, release tags, Terraform plans | F/O | Implemented (branch protection and Environment required reviewers: Org settings) |

### 3.9 CC9 – Risk mitigation

| Criterion | Framework control | Evidence source | Resp. | Status |
|---|---|---|---|---|
| CC9.1 Business disruption risk | Multi-replica, zone spread, PDB, zone-redundant DB HA (IaC), outbox buffering when downstream is unavailable | DR plan, test results | F/O | Implemented / Org |
| CC9.2 Vendor and business-partner risk | Data contracts per connector (`ConnectorRegistry`); status-only outbound; consent purposes for partner/insurer sharing | vendor assessments, DPAs | O/F | Partial |

## 4. Additional criteria

### 4.1 Availability (A1)

| Criterion | Framework control | Evidence | Resp. | Status |
|---|---|---|---|---|
| A1.1 Capacity | HPA 2–6 replicas, resource requests/limits, virtual threads, DB pool size configurable | Helm values, monitoring | F/O | Implemented |
| A1.2 Environmental protections, backup, recovery infrastructure | Zone-redundant HA, geo-redundant backups 35 d (Azure), REGIONAL + PITR (GCP), CNPG synchronous replica + WAL archive | Terraform, backup reports | C/F | Implemented (IaC) |
| A1.3 Recovery testing | – | **semi-annual restore test, annual DR exercise** | O | Org |

### 4.2 Confidentiality (C1)

| Criterion | Framework control | Evidence | Resp. | Status |
|---|---|---|---|---|
| C1.1 Identify and maintain confidential information | `DataClassification` (5 levels), classification derived from case category, field encryption, ABAC disclosure levels, restricted class, PHI-free logging | code, data inventory | F/O | Implemented |
| C1.2 Dispose of confidential information | Retention schedule & purge jobs | – | O/F | **Planned** |

### 4.3 Processing integrity (PI1)

| Criterion | Framework control | Evidence | Resp. | Status |
|---|---|---|---|---|
| PI1.1 Processing specifications | Versioned event types, OpenAPI contract, FHIR CapabilityStatement | `/v3/api-docs`, `/integration/fhir/metadata` | F | Implemented |
| PI1.2 Input completeness and accuracy | Bean Validation, regex validation of ids, strict FHIR parser, HL7 validation, Excel row-level error reporting, import batch counts and SHA-256 | `intake.import_batch`, tests | F | Implemented |
| PI1.3 Processing | Case state machine (`CaseStatus` allowed transitions), optimistic locking (`@Version`), idempotent consumers and incident/case de-duplication | tests (`CaseStatusTest`, integration tests) | F | Implemented |
| PI1.4 Output completeness and accuracy | Outbox at-least-once delivery, dead-letter and replay, receiver de-duplication on `messageId` | `/integration/events` | F/O | Implemented (adapter: extension point) |
| PI1.5 Storage of inputs and outputs | Encrypted inbound store; encrypted outbox history (delivered events kept 30 days by default; failed/dead kept for replay) | DB | F/O | Implemented (OH-record retention: planned) |

### 4.4 Privacy (P-series)

| Criterion | Framework control | Organisation responsibility | Status |
|---|---|---|---|
| P1.1 Notice | `notice_version` on every consent | Publish privacy notice for OH processing | F: Implemented; O: Org |
| P2.1 Choice and consent | Consent per purpose, grant/withdraw by data subject, audited, evidence preserved | Decide lawful basis per purpose (Art. 9(2)(h)/(b) vs (a)) | Implemented; gate `hasActiveConsent` not yet called (no sharing flows yet) |
| P3.1–P3.2 Collection limited to purpose; explicit consent for sensitive data | Status-only rule; minimal event payloads; restricted class | DPIA, data-minimisation review per module | Implemented (base) |
| P4.1 Use limited to purpose | ABAC per purpose of role; analytics on de-identified view only | Purpose specification per module | Implemented |
| P4.2 Retention | – | Retention schedule | **Planned** |
| P4.3 Disposal | – | Secure deletion procedures | **Planned** |
| P5.1 Data-subject access | `GET /cases` (own), `/consents/me`, `/me` in the employee app | DSAR process for full export | Partial (no export endpoint) |
| P5.2 Correction | – | Correction process via OH | Planned |
| P6.1 Disclosure to third parties with consent | Outbound allow-list (default deny); consent purposes `TREATMENT_SHARING_PARTNER`, `INSURER_SHARING` | Processor/controller agreements | Partial |
| P6.2–P6.3 Record of disclosures | Outbox records every external disclosure with destination and time (encrypted payload, 30-day default retention); audit trail | Records of processing (Art. 30); export to long-term store if disclosures must be kept longer | Implemented (retention to be confirmed) |
| P6.4–P6.5 Third-party commitments and notification | – | DPAs, breach-notification clauses | Org |
| P6.6–P6.7 Breach notification; accounting of disclosures | Audit and outbox provide data for the accounting | Breach procedure (GDPR 72 h) | Org |
| P7.1 Data quality | Validation, master data from HRIS (planned sync) | Data-quality ownership | Partial |
| P8.1 Inquiries, complaints, disputes | – | DPO contact and process | Org |

## 5. Evidence catalogue

| Evidence | How to obtain | Supports |
|---|---|---|
| Application audit trail | `GET /api/v1/audit?from=...&to=...&limit=5000` as AUDITOR (each read is itself audited) | CC6.1, CC6.3, CC7.2, P6.2 |
| Audit integrity proof | `GET /api/v1/audit/verify` → `{intact, recordsChecked, firstBrokenId}`; schedule daily, store results and the head hash in WORM storage | CC4.1, CC7.2, PI1.5 |
| Integration event log | `GET /api/v1/integration/events?status=DEAD\|FAILED\|PUBLISHED` (metadata only) | PI1.4, CC7.4, P6.2 |
| Test reports | CI artefact `java-test-reports` (Surefire/Failsafe, JaCoCo), Vitest results per build (`ci.yml`) | CC8.1, CC3.4 |
| Secret-scan and SCA reports | CI artefacts `gitleaks-report`, `dependency-check-report`; SARIF in GitHub code scanning; dependency-review results on PRs | CC7.1, CC8.1 |
| Deployment records | `cd.yml` / `deploy-env.yml` run history per GitHub Environment (who approved, which digest, Helm status) | CC8.1 |
| SAST / SCA reports | SpotBugs + FindSecBugs (`mvn -Psecurity verify`), OWASP dependency-check HTML + SARIF, CodeQL alerts | CC7.1, CC8.1 |
| SBOM | CI artefacts `sbom-java` (CycloneDX `target/bom.json`/`.xml`), `sbom-employee-app`, `sbom-manager-workspace`; image SBOMs attested to each image | CC7.1, CC9.2 |
| Container scan & signatures | Trivy SARIF in code scanning, cosign signatures, SBOM and SLSA provenance attestations (`container.yml`), signature verification step in `deploy-env.yml`, Binary Authorization admission logs (GKE) | CC6.8, CC8.1 |
| Infrastructure configuration | Terraform plans and state (remote, access-controlled), `terraform show` of security settings; Helm values per environment | CC5.2, CC6.6, A1.2 |
| Change history | Git history, pull requests with reviewers and approvals, release tags, `flyway_schema_history` / `flyway_schema_history_bff` | CC8.1 |
| Identity configuration | Entra ID app registrations, app-role assignments, Conditional Access policies, sign-in logs; access-review records | CC6.2, CC6.3 |
| Cloud audit logs | Azure Activity / diagnostic logs (Key Vault, PostgreSQL with pgaudit), GCP Admin + Data Access audit logs | CC7.2 |
| Backups and restore tests | Backup policy screenshots/exports, restore test reports | A1.2, A1.3 |
| Consent records | `platform.consent` extract (via DPO-approved query) | P2.1 |
| Increment 1: rule and protocol governance | Audit actions `INTAKE_RULE_*`, `ASSIGNMENT_RULE_SAVE`, `SLA_POLICY_UPDATE`, `NOTIFICATION_MATRIX_UPDATE`, `PROTOCOL_APPROVE`; `triage.triage_protocol` (status, approver, time) | CC8.1, PI1.3 |
| Increment 1: intake completeness | `GET /api/v1/intake/reconciliation[/export]`, `GET /intake/dlq`, audit `EHS_WEBHOOK_DLQ`, `EHS_DLQ_REPLAY`, `EHS_DLQ_DISCARD` | PI1.2, PI1.4 |
| Increment 1: status-only consumption | Audit `CREW_STATUS_READ`, `HYGIENE_STATUS_READ`; E2E log of `tools/e2e/e2e-increment1.js` (18 checks) | CC6.7, P3.1 |

## 6. Cross-reference to other frameworks

| Framework control | WellnessZ control(s) | Status |
|---|---|---|
| **HIPAA §164.312(a)(1)** Access control | RBAC + ABAC, default deny, restricted class | Implemented |
| §164.312(a)(2)(i) Unique user identification | IdP subject per user; no shared accounts in app | Implemented (IdP: Org) |
| §164.312(a)(2)(ii) Emergency access procedure | Break-glass access | **Planned** |
| §164.312(a)(2)(iii) Automatic logoff | BFF session 15 min idle; client idle logout 15 min | Implemented |
| §164.312(a)(2)(iv) Encryption and decryption | AES-256-GCM field encryption; storage encryption | Implemented |
| §164.312(b) Audit controls | Hash-chained append-only audit trail | Implemented |
| §164.312(c)(1) Integrity | GCM authentication tags, audit hash chain, optimistic locking | Implemented |
| §164.312(d) Person or entity authentication | OIDC (MFA at IdP), client credentials for M2M | Implemented |
| §164.312(e)(1) Transmission security | TLS everywhere, DB `verify-full`, HL7 v2 over HTTPS | Implemented |
| §164.308(a)(1)(ii)(D) Information system activity review | Audit API; SIEM review | Org |
| §164.308(a)(7) Contingency plan | Backups/HA in IaC; DR tests | Org |
| **GDPR Art. 5(1)(c)** Data minimisation | Status-only rule, minimal event payloads, de-identified analytics | Implemented |
| Art. 5(1)(e) Storage limitation | Retention jobs | **Planned** |
| Art. 9 Special categories | Classification CLINICAL / RESTRICTED_CLINICAL, restricted class, consent register | Implemented (lawful basis: Org) |
| Art. 15 Right of access | Employee self-view of cases and consents | Partial |
| Art. 25 Data protection by design and default | Default deny, projection types, suppression, pseudonymisation | Implemented |
| Art. 30 Records of processing | Connector catalogue with data contracts supports the record | Org |
| Art. 32 Security of processing | Encryption, access control, audit, resilience | Implemented / Org |
| Art. 33/34 Breach notification | Audit + logs support investigation | Org |
| Art. 35 DPIA | This document and doc 01 as inputs | Org |
| **ISO/IEC 27001:2022 A.5.15–5.18** Access control, identity, authentication information, access rights | OIDC, role mapping, ABAC; access reviews | Implemented / Org |
| A.5.33 Protection of records | Append-only audit, consent evidence | Implemented |
| A.5.34 Privacy and protection of PII | Classification, status-only, suppression | Implemented |
| A.8.2 Privileged access rights | PLATFORM_ADMIN/OH_ADMIN separated; DB owner vs runtime role | Implemented / Org (PAM) |
| A.8.3 Information access restriction | ABAC disclosure levels | Implemented |
| A.8.5 Secure authentication | OIDC, PKCE, MFA at IdP | Implemented |
| A.8.8 Management of technical vulnerabilities | SCA, SAST, image scan | Implemented (build) / Org |
| A.8.9 Configuration management | IaC, Helm, E18 audited configuration | Implemented |
| A.8.10 Information deletion | Retention jobs | Planned |
| A.8.11 Data masking | Status-only projection, pseudonymisation, small-cell suppression | Implemented |
| A.8.12 Data leakage prevention | Outbound allow-list, notification content rules, no PHI in logs/caches | Implemented |
| A.8.13 / 8.14 Backup / redundancy | IaC HA + backups | Implemented (IaC) / Org tests |
| A.8.15 / 8.16 Logging / monitoring | Audit trail, JSON logs, metrics, traces | Implemented / Org (SIEM) |
| A.8.20–8.22 Network security, segregation | NetworkPolicies, private endpoints, WAF | Implemented (IaC) |
| A.8.24 Use of cryptography | AES-256-GCM, HKDF, TLS 1.2+/1.3 | Implemented; key management Org |
| A.8.25–8.29 Secure development, coding, testing | SDLC in Section 8, Developer Guide | Implemented / Partial |
| A.8.31 Separation of environments | Spring profiles, per-environment values, Swagger disabled in prod | Implemented |
| A.8.32 Change management | Git/PR, Flyway, approvals | Implemented / Org |
| A.8.33 Test information | Test keys only in test profile; synthetic test data | Implemented (policy: Org) |

## 7. Threat model (STRIDE)

The model covers the main data flows of the base framework. Ratings are qualitative (L/M/H) for residual risk after
the listed controls.

### 7.1 Flows in scope

| ID | Flow |
|---|---|
| F1 | EHS system → feed mailbox → `MailboxIngestionJob` (IMAP/Graph) → intake |
| F2 | OH administrator → `POST /intake/ehs/excel` → `ExcelIncidentParser` |
| F3 | Browser/PWA → ingress → BFF → core API (session, CSRF, token relay) |
| F4 | Native app → IdP (PKCE) → core API (bearer) |
| F5 | Core API case reads/writes under ABAC |
| F6 | Outbox → relay → external destination (status-only, e.g. crew rostering) |
| F7 | Labs/partners → FHIR R4 / HL7 v2 / generic inbound with Idempotency-Key |
| F8 | Managers/HR → KPI API (aggregates) |
| F9 | Auditor → audit API; integrity verification |
| F10 | Administrators → configuration, master data, event replay, mailbox poll |
| F11 | Pods → secrets (CSI, workload identity) and master key |
| F12 | Developer → Git → CI/CD → registry → cluster (supply chain) |

The diagrams below show the trust boundaries for the main flows (system context, EHS-to-status-only data flow, browser
identity flow, mobile flow); they are the same views as in document 01.

![C4 system context](../images/01-c4-context.png)

*Figure 1 – System context and trust boundary (F1, F6, F7).*

![Data flow EHS intake to status-only outputs](../images/04-dataflow-ehs-intake.png)

*Figure 2 – Data flow F1, F2, F5, F6, F8 with processing order.*

![Identity and BFF flow](../images/05-identity-bff-flow.png)

*Figure 3 – Browser identity flow F3.*

![Mobile PKCE and MDM flow](../images/06-mobile-pkce-mdm-flow.png)

*Figure 4 – Native app flow F4.*

### 7.2 STRIDE analysis

| Flow | Threat (STRIDE) | Scenario | Controls | Residual |
|---|---|---|---|---|
| F1 | **S**poofing | Attacker e-mails a forged incident to the feed mailbox | Sender allow-list; Graph app scoped to one mailbox; audit of rejections | M – sender addresses can be spoofed without DMARC; enforce DMARC `p=reject` and consider DKIM verification |
| F1 | **T**ampering | Malicious attachment (zip bomb, XXE, formulas) | Size caps, magic bytes, POI zip limits, no formula evaluation, XXE-safe parsers | L |
| F1 | **D**oS | Mail flood | ≤ 25 messages/poll, 10 MB cap, poll interval | L |
| F1 | **E**levation | Graph app with tenant-wide `Mail.ReadWrite` | Exchange application access policy / RBAC for Applications (required configuration) | M until verified by Org |
| F2 | **T**ampering / **E**levation | Crafted workbook exploits parser | Same hardening as F1; role restriction to OH_ADMIN/CASE_MANAGER/INTEGRATION; test rejects non-OOXML | L |
| F2 | **R**epudiation | Uploader denies import | `import_batch.created_by`, SHA-256 of file, audit `EHS_IMPORT` | L |
| F3 | **S**poofing | Session hijacking | HttpOnly/Secure cookie, session-id rotation, 15-min timeout, max 3 sessions, TLS + HSTS | L |
| F3 | **T**ampering | CSRF against BFF | Spring Security SPA CSRF token on all mutating requests and logout; SameSite=Lax | L |
| F3 | **I**nformation disclosure | XSS stealing tokens | Tokens never in browser (BFF); strict CSP without inline script; React escaping | L |
| F3 | **I**nformation disclosure | Session store dump reveals tokens | AES-256-GCM-encrypted session attributes | L (master-key dependent) |
| F3 | **E**levation | Path traversal via proxy to admin endpoints | `..`/`//` rejected; core re-checks authorisation; header allow-list | L |
| F4 | **S**poofing | Authorization-code interception / custom-scheme hijack | PKCE S256, `state` + `nonce`; Universal/App Links recommended | M until App Links adopted |
| F4 | **I**nformation disclosure | Token or PHI left on device | Access token in memory; refresh token in Keychain/Keystore (plugin to be registered); no localStorage; no-store; privacy screen; MAM | M until secure-storage and MAM integrated |
| F5 | **I**nformation disclosure | Manager reads clinical details | ABAC status-only projection type; org-scope check; 404 for out-of-scope; tests | L |
| F5 | **I**nformation disclosure | Clinician reads psychological case | Restricted-class deny policy; test | L |
| F5 | **E**levation | Forged role claims | Trusted-issuer allow-list, signature + audience validation, role-name regex | L |
| F5 | **R**epudiation | User denies access to a record | Every read/list/denial audited with actor, roles, correlation id, IP | L |
| F5 | **I**nformation disclosure | DB/backup exfiltration | Field-level encryption of clinical text; storage encryption; private endpoints | M – identifiers and categories not field-encrypted |
| F6 | **I**nformation disclosure | Clinical field leaks to rostering/HR | OutboundPayloadGuard: field allow-list per destination, default deny, nested objects/arrays rejected; encrypted outbox payloads | L – values not content-inspected; allow-lists reviewed by DPO |
| F6 | **T**ampering / **R**epudiation | Message altered or disputed | Outbox history; TLS; broker adapter authentication (to be implemented); messageId | M until adapter + message signing decided |
| F6 | **D**oS | Downstream outage | Back-off, dead-letter, replay; outbox absorbs backlog | L |
| F7 | **S**poofing | Unauthorised system posts lab results | Client-credentials token from trusted issuer + INTEGRATION role | L |
| F7 | **T**ampering | Malformed/hostile FHIR or HL7 | JSON-only FHIR (no XXE), strict parser, type allow-list, HL7 validation | L |
| F7 | Replay / duplication | Retry storms create duplicates | Idempotency-Key with request hash; HL7 control id stored | L |
| F8 | **I**nformation disclosure | Re-identification from small groups or differencing | k ≥ 5 (floor 3), complementary suppression, restricted class excluded, allow-listed dimensions | M – no protection against differencing across time windows/dimensions |
| F9 | **T**ampering | Audit rows altered or deleted | Trigger + grants block UPDATE/DELETE/TRUNCATE; hash chain; verify API | M – DB owner could rewrite chain; external anchoring planned |
| F9 | **I**nformation disclosure | Audit contains PHI | Reasons sanitised; coding standard forbids PHI in reasons | L |
| F10 | **E**levation / **T**ampering | Admin changes config (e.g. module flag, min group size) maliciously | Role-restricted, audited `CONFIG_CHANGE`; disabled modules answer 503 `module_disabled` (fail closed); analytics floor k ≥ 3 enforced in code | M – no maker-checker; old/new values not in audit |
| F10 | **T**ampering | Operator replays events to re-send data | Replay restricted to PLATFORM_ADMIN/OH_ADMIN, audited; consumers idempotent | L |
| F11 | **I**nformation disclosure | Master key theft | Key in vault, CSI file mount, workload identity, never in image or Git | M – single master key; envelope encryption recommended |
| F11 | **E**levation | Pod identity abused | Per-workload service accounts, least-privilege vault roles (Vault policies in `deploy/hybrid`) | L–M |
| F12 | **T**ampering | Malicious dependency or image | SHA-pinned actions, pinned versions, lockfiles, SCA and dependency review, SBOM, Trivy gate, cosign signing + provenance, signature verification at deploy, Binary Authorization (GKE) | L–M – cluster-side admission not yet enforced on AKS/on-prem |
| F12 | **I**nformation disclosure | Secrets committed to Git | `ENC()`/`file:` pattern, gitleaks on full history in CI (SARIF), `deploy/local/.env` git-ignored | L |

## 8. Secure software development lifecycle

| Phase | Practice | Tooling / artefact |
|---|---|---|
| Requirements | Classify data (`DataClassification`), decide disclosure level and outbound contract per module; DPIA screening | Module design note, ConnectorRegistry entry |
| Design | Threat-model new flows (extend Section 7); define `AccessPolicy` beans; events as flat records | design review |
| Implementation | Developer Guide standards: no PHI in logs/errors/audit reasons, validation, RFC 9457, parameterised SQL / allow-listed dimensions, `EncryptedStringConverter` for clinical text | IDE, code review |
| Verification | Unit, integration (real PostgreSQL), ArchUnit, web unit tests, Playwright E2E; SAST (CodeQL in CI; SpotBugs/FindSecBugs via `mvn -Psecurity`); SCA (dependency-check, dependency review, npm audit); gitleaks; IaC scan (Trivy config, helm lint); DAST (recommended) | `ci.yml`, `codeql.yml` reports |
| Release | SBOM, image build from pinned bases, Trivy gate, cosign keyless signing + attestations, signature verification, atomic Helm deploy per GitHub Environment | `container.yml`, `cd.yml`, `deploy-env.yml`, Helm |
| Operations | Patch base images (Dependabot), monitor, audit verification, incident response | SIEM, runbooks |
| Exceptions | Suppression/exclusion files require justification, owner, expiry; reviewed each release | `config/dependency-check-suppressions.xml`, `config/spotbugs-exclude.xml` |

## 9. Residual risks and compensating controls

| ID | Residual risk | Compensating control (now) | Target remediation |
|---|---|---|---|
| RR1 | Single master key; master-key rotation requires re-encryption tooling not yet built | Vault RBAC, purge protection, access logging on Key Vault/Secret Manager | Envelope encryption via KMS/HSM; re-encryption job (increment 1) |
| RR2 | Delivered outbox events kept 30 days (needed for replay window) | Payloads field-encrypted; metadata-only operations API | Confirm retention with DPO |
| RR3 | No retention/deletion jobs | Data volume small at start; manual deletion procedure via DPO | Retention schedule and purge jobs (increment 2) |
| RR4 | Audit chain rewritable by DB owner | Separate owner role used only by migration Job; pgaudit DDL/role logging; cloud audit logs | External hash anchoring + SIEM streaming |
| RR5 | No break-glass access | OH_ADMIN escalation via IdP role assignment (audited in IdP) | Break-glass flow with justification and alerting |
| RR6 | No maker-checker on configuration | Audited changes; limited admin population | Approval workflow in E18 UI |
| RR7 | Mobile secure storage / MAM not yet integrated | In-memory fallback (re-login), idle logout, no-store | Keychain/Keystore plugin, Intune APP SDK before store release |
| RR8 | External broker adapter and message authentication not implemented | Only logging publisher; no PHI leaves the system externally yet | Adapter with mTLS/managed identity per D3 |
| RR9 | Outbound guard checks field names, not values | Flat payloads enforced; allow-list per destination; integration test | Schema-based contract tests per destination |
| RR10 | Differencing attacks on KPI API | Single KPI, allow-listed dimensions, k ≥ 5 | Query auditing thresholds, row/column suppression, fixed reporting periods |
| RR11 | SpotBugs not a CI job; no DAST; admission enforcement only on GKE | SpotBugs run via Maven profile before release (0 findings at baseline); deploy job verifies cosign signatures | Add SpotBugs CI job, ZAP baseline, Kyverno/Ratify or Azure Policy image verification |
| RR12 | Branch protection and Environment required reviewers are repository settings, not code | Workflows designed for approvals (`test`, `prod` environments) | Configure and evidence settings; review quarterly |

## 10. Controls the organisation must operate

| Control | Frequency | Owner (proposed) | TSC |
|---|---|---|---|
| User access review of WellnessZ app roles and Entra group memberships (incl. PSYCH_CLINICIAN, AUDITOR, PLATFORM_ADMIN) | Quarterly; privileged roles monthly | OH service owner + IAM | CC6.2, CC6.3 |
| Joiner / mover / leaver in IdP, HRIS-driven | Continuous | IAM | CC6.2 |
| Review of Graph application access policy for the EHS mailbox | Semi-annual | M365 admin | CC6.1 |
| Key rotation: master key (with re-encryption), pseudonym key, BFF client secret, DB passwords, certificates | Annual (keys), 90–180 days (secrets), on compromise immediately | Platform security | CC6.1, A.8.24 |
| Audit-chain verification and head-hash anchoring; review of verification failures | Daily automated, monthly review | Security operations | CC4.1, CC7.2 |
| SIEM use cases: auth-failure spikes, DENIED bursts, DEAD events, config changes, audit verification failure | Continuous | SOC | CC7.2, CC7.3 |
| Vulnerability management (patch SLAs: critical 7 d, high 30 d), base-image refresh | Continuous / monthly | Platform team | CC7.1 |
| Penetration test (web, API, mobile, BFF), including authorisation testing across disclosure levels | Annual and before major release | Security | CC4.1, CC7.1 |
| Backup restore test (PITR) | Semi-annual | DBA / platform | A1.3 |
| BCP/DR exercise incl. manual fitness-for-duty fallback | Annual | Service owner + BCM | A1.3, CC9.1 |
| Change approval (CAB or PR approvals), release evidence retention | Each change | Engineering lead | CC8.1 |
| Vendor reviews (cloud, IdP, MDM, messaging, SMS/e-mail providers) – SOC 2 bridge letters | Annual | Vendor management | CC9.2 |
| Privacy: DPIA per module, records of processing, retention schedule, DSAR handling, breach procedure | Per module / annual | DPO | P-series |
| Security awareness and secure-coding training for developers | Annual | CISO office | CC1.4 |
| Review of outbound allow-lists and consent purposes | Each new destination / annual | DPO + architecture | CC6.7, P6.1 |
| Review of suppression/exclusion files (SCA/SAST exceptions) | Each release | Security champion | CC7.1 |
| Mobile: MDM enrolment, app protection policies, Conditional Access | Continuous | Endpoint team | CC6.1, CC6.7 |

## 11. Increment 1 – E01 intake and E02 triage

Increment 1 adds the first two business modules (`modules/wz-e01-intake`, `modules/wz-e02-triage`), the OH clinical
workspace (`web/oh-workspace`), nine roles and the `triage` schema. The design is in
[`docs/increment-01/01-Solution-Design-E01-E02.md`](../increment-01/01-Solution-Design-E01-E02.md), requirement
coverage in [`02-Requirements-Traceability-Matrix.md`](../increment-01/02-Requirements-Traceability-Matrix.md). This
section records the security controls they introduce and how they map to the criteria above. Statements were
checked against the code at the increment-1 baseline.

### 11.1 New roles and segregation of duties

The canonical role list grows from 11 to 20 (`com.wellnessz.common.model.Roles`). Clinical disclosure (`CLINICAL`) is
granted by `DefaultPolicies.clinicianPolicy` to `OH_CLINICIAN`, `CASE_MANAGER`, `OH_PHYSICIAN`, `AME`, `CMO` and
`NURSING_HEAD`; `RESTRICTED_CLINICAL` remains exclusive to `PSYCH_CLINICIAN`. All other new roles receive **no**
clinical disclosure: they reach only dedicated endpoints that return status, configuration or registration data.

| Role | Function | Data reach | Notable permissions | Cannot (enforced server-side) |
|---|---|---|---|---|
| `OH_PHYSICIAN` | OH physician | CLINICAL | Decide physician escalations; `FOOD_SAFETY` and `PHYSICIAN` queues; fitness decisions | Approve protocols |
| `AME` | Aeromedical examiner | CLINICAL | `AME_AEROMEDICAL` queue (crew, ATC); decide physician escalations | Approve protocols |
| `CMO` | Chief Medical Officer | CLINICAL | **Only role that approves triage protocols** (`PROTOCOL_APPROVE`); decides CMO escalations; schedules case conferences; complexity calibration | – |
| `NURSING_HEAD` | Head of OH nursing | CLINICAL | Reassign and bulk-reassign, caseload and out-of-office cover, SLA-breach recipient, triage QA sampling, edit intake rules, assignment rules and SLA policies | Audit a triage they performed themselves (`409 self_review`) |
| `OH_ADMIN` (extended) | OH service administrator | none (configuration only) | Notification matrix and recordability mapping (only writer), intake / assignment rules, SLA policies, DLQ replay / discard, intake dashboard | Read case clinical content (no ABAC permit) |
| `HSE_OFFICER` | Health & safety officer | status / aggregate | Reconciliation report and CSV export, weekly exceptions, incident → cases (status), recordability report | Open queues or case clinical content |
| `CREW_SCHEDULING` | Crew scheduling desk | status-only | `GET /api/v1/status/crew` (≤ 500 employee ids per call, audited `CREW_STATUS_READ`) | Anything else |
| `HYGIENE_MANAGER` | Catering hygiene manager | status-only | `GET /api/v1/status/food-handlers?department=` (audited `HYGIENE_STATUS_READ`) | Anything else |
| `CLINIC_RECEPTION` | Clinic front desk | registration data | Badge / name lookup, walk-in and emergency registration (five fields), encounter list (chief complaint and action taken are returned only to OH clinical roles) | Clinical queues (`403`, E2E-tested), convert an encounter into a case |
| `FIRST_AIDER` | Workplace first aider | registration data | Register encounters; enrich only **own** encounters | Enrich others' encounters (`403`), queues, cases |

Segregation-of-duties controls in the code:

* **Protocol governance.** Draft protocols are seeded by the system; only the CMO can approve a version
  (`TriageService.approve` re-checks the role in addition to `@PreAuthorize`). Nurses use but cannot change protocols.
* **Triage QA.** `TriageAuditService` refuses a review by the clinician who performed the sampled triage
  (`self_review`).
* **Escalation decisions** are restricted to the addressed level (physician level: OH_PHYSICIAN / AME / CMO; CMO
  level: CMO), otherwise `403`.
* **Configuration vs. clinical work.** OH_ADMIN configures but has no clinical read access; reception and first aiders
  register but cannot convert encounters into cases (OH clinical roles only).
* **Status-only consumers.** Crew scheduling and hygiene managers receive `StatusRow(employeeId, displayName,
  fitnessStatus, restrictionUntil, updatedAt)` – a record type without clinical fields, the same pattern as
  `CaseViews.StatusOnly` (the E2E test asserts that no diagnosis, description or triage text appears).
* **Rule changes** (intake rules, assignment rules, SLA policies, notification matrix) are audited
  (`INTAKE_RULE_CREATE/UPDATE`, `ASSIGNMENT_RULE_SAVE`, `SLA_POLICY_UPDATE`, `NOTIFICATION_MATRIX_UPDATE`); maker-checker
  remains open (RR6).

Role-to-group assignment, access reviews and joiner/mover/leaver for the new roles are organisation-operated
(Section 10). Identity configuration: Keycloak realm roles and scope mappings for `wellnessz-bff` and
`wellnessz-mobile` (both clients have `fullScopeAllowed=false`, so a role missing from the scope mapping silently
disappears from tokens – this defect was found and fixed during the increment-1 E2E run); Entra ID app roles on the
`wellnessz-api` app registration assigned to groups (setup guide 06, Section 5).

### 11.2 Clinical-text guard

`com.wellnessz.common.text.ClinicalTextDetector` is a deliberately conservative heuristic (clinical vocabulary such as
diagnosis, depression, fracture, medication, positive test, plus ICD-10-style codes such as `F32.1`). It is applied to:

* the free-text fields of **manager and HR referrals** (`ReferralService.guard`) – a match answers
  `422 clinical_content_not_allowed` naming the field, never echoing the text; fields are capped at 1,000 characters;
  the manager's org scope is checked first (out of scope → `404`, audited `REFERRAL_SCOPE` DENIED);
* **notification content** (`ContentRules`, now delegating to the shared detector plus result keywords).

It is a guard-rail, not a classifier: it errs on rejection and cannot catch paraphrases. The control objective
(managers and HR do not put health data into the system) is supported by UI guidance in the forms and by the
status-only rule on the read side. Unit tests: `ClinicalTextDetectorTest`; E2E check *manager referral with clinical
wording is rejected*.

### 11.3 EHS webhook: authentication, idempotency, dead-letter queue

| Aspect | Control |
|---|---|
| Authentication | Bearer token from a trusted issuer (client-credentials), role `INTEGRATION` (`@PreAuthorize`); Keycloak client `wellnessz-integration` locally, an Entra app registration with the `INTEGRATION` application role in production |
| Replay / retries | `Idempotency-Key` **mandatory** (8–200 chars); same key + same body returns the stored response, same key + different body → `422`; scope `ehs-webhook` |
| Input validation | Per-item allow-list schema: identifier regexes, closed sets for `incidentClass` and `severity`, ISO-8601 instants, length caps (description ≤ 4,000), ≤ 500 items per call; unknown fields ignored |
| Invalid items | Stored **encrypted** in `intake.incident_dlq` with a reason; WARN log `ALERT ehs-webhook dead-lettered=<n>` for SIEM alerting; audit `EHS_WEBHOOK_DLQ` (FAILURE) |
| DLQ operations | `GET /api/v1/intake/dlq` (metadata only, no payload), replay (re-validated) and discard; OH_ADMIN / PLATFORM_ADMIN; audited `EHS_DLQ_REPLAY`, `EHS_DLQ_DISCARD` |
| Duplicates | Unique `(source_system, external_ref)` as for mailbox and Excel intake |
| Exposure | Published only on the API host (`api.<domain>`), behind the WAF; hybrid: internal API ingress on-prem |

### 11.4 Attachments and other input / output controls

* **Attachments** (`CaseWorkService`): file type decided by **magic bytes** (JPEG `FF D8 FF`, PNG `89 50 4E 47`,
  PDF `%PDF`), never by the client header or extension; 1 byte – 5 MB; at most 5 per case; file name sanitised;
  SHA-256 recorded; content stored with `EncryptedStringConverter`; upload and download audited
  (`CASE_ATTACHMENT_UPLOAD`, `CASE_ATTACHMENT_READ`); downloads go through the BFF (`content-disposition` and
  `cache-control` are the only added forwarded response headers).
* **CSV export** of the reconciliation report neutralises spreadsheet formula injection (cells starting with `=`, `+`,
  `-`, `@` are prefixed) and is limited to OH roles and HSE officers.
* **Intake dashboard** applies small-cell suppression with the analytics minimum group size (`k`, default 5); the OH
  workspace additionally renders positive counts below `k` as `<k`.
* **Triage integrity.** Urgency is computed on the server from the protocol version; red-flag answers are a hard stop
  (`422 red_flag_hard_stop` on a downgrade attempt, E2E-tested); overrides need a reason and are stored with computed
  and final urgency; the protocol version is stored with every assessment.
* **Encryption at rest** of the new clinical free text: encounter complaint / action / notes, recordability answers
  and rationale, triage answers / override reason / call log, escalation notes and decisions, conference agenda /
  minutes / decisions, handover summaries, checklist answers, QA comments, DLQ payloads.
* **OH workspace (browser).** BFF only (no tokens in the browser), strict CSP, no `localStorage` / `sessionStorage`
  (ESLint-enforced), 15-minute idle logout; the walk-in offline retry queue is in memory only and its unit test
  asserts that no browser storage is touched.
* **SAST exclusion.** `config/spotbugs-exclude.xml` now has **5** justified entries; the new one covers
  `QueueService`, whose SQL is assembled only from constant fragments selected by a closed switch, with every user
  value bound as a JDBC parameter (covered by `Increment1IntegrationTest` and `QueuePerformanceTest`).

### 11.5 Threat model additions (STRIDE)

| ID | Flow |
|---|---|
| F13 | EHS system → API host → `POST /intake/ehs/incidents` → rule table → case |
| F14 | Manager / HR → BFF → referral endpoints (clinical-text guard, org scope) |
| F15 | OH team → OH workspace → BFF → queues, triage, escalation, configuration |
| F16 | Clinic reception / first aider → OH workspace → encounter registration |
| F17 | Crew scheduling / hygiene manager → status-only endpoints |
| F18 | SLA poller and notification dispatcher → e-mail to OH team and stakeholders |

| Flow | Threat (STRIDE) | Scenario | Controls | Residual |
|---|---|---|---|---|
| F13 | **S**poofing | Attacker posts forged incidents | Client-credentials token + `INTEGRATION` role, trusted-issuer allow-list, WAF | L |
| F13 | **T**ampering / **D**oS | Malformed or oversized payloads, retry storms | Schema validation per item, ≤ 500 items, DLQ instead of failure, mandatory `Idempotency-Key`, de-duplication | L |
| F13 | **E**levation | Crafted rule-matching data auto-creates many cases | Rules evaluated on validated fields only; unidentified employees become suggestions; reconciliation shows every incident's fate | L |
| F14 | **I**nformation disclosure | Manager writes a diagnosis into a referral | Clinical-text guard (`422`), field caps, status-only read side | M – heuristic cannot catch every paraphrase |
| F14 | **E**levation | Manager refers an employee outside their org scope | Org-scope check against the HRIS master (`404`, audited) | L |
| F15 | **I**nformation disclosure | Clinician of the wrong team reads restricted cases | ABAC restricted-class deny, `MENTAL_HEALTH` queue requires `PSYCH_CLINICIAN` | L |
| F15 | **T**ampering | Client manipulates triage urgency or bypasses red flags | Server-side evaluation, hard stop, override reason, versioned protocol per assessment | L |
| F15 | **T**ampering | Unapproved protocol used clinically | CMO-only approval; draft fallback visible in the UI | M – drafts are usable while no approved version exists (O11 in doc 01) |
| F15 | **R**epudiation | Disputed reassignment or rule change | Assignment history with reason, audited rule/SLA/matrix changes | L |
| F15 | **I**nformation disclosure | Queue SQL injection | Constant SQL fragments, bound parameters, SAST exclusion reviewed, integration tests | L |
| F16 | **I**nformation disclosure | Reception or first aider browses clinical data | No ABAC permit; `403` on queues (E2E-tested); encounter views omit chief complaint and action taken for non-clinical roles; enrichment limited to the registering user | L – the encounter list shows names, times and locations of recent encounters to all registrars |
| F16 | **I**nformation disclosure | PHI left on a shared reception PC | No browser storage, in-memory retry queue, idle logout, `no-store` | L |
| F17 | **I**nformation disclosure | Status consumer infers diagnoses | Status record type without clinical fields, ≤ 500 ids per call, read audited | L – fitness status itself is disclosed by design |
| F18 | **I**nformation disclosure | Clinical content in e-mail | Approved templates, `ContentRules` with the clinical-text detector, link to the OH workspace instead of content | L |
| F18 | **D**oS | Poller contention on several replicas | `FOR UPDATE SKIP LOCKED`, batch 500, partial index | L |

### 11.6 Control mapping additions

| Criterion | Increment-1 control | Evidence source | Resp. | Status |
|---|---|---|---|---|
| CC6.1 | ABAC extended to OH_PHYSICIAN, AME, CMO, NURSING_HEAD; `@PreAuthorize` on every E01/E02 endpoint; webhook bound to `INTEGRATION` | `DefaultPolicies`, controllers, `Increment1IntegrationTest`, E2E | F | Implemented |
| CC6.2 | Nine roles defined in IdP (Keycloak realm / Entra app roles) and assigned through groups; client scope mappings include the new roles | realm export, Entra app-role assignments | O/F | Implemented (assignment: Org) |
| CC6.3 | Segregation of duties per Section 11.1 (CMO-only approval, no self-review, OH_ADMIN without clinical access, reception without clinical access) | code, tests, access reviews | F/O | Implemented |
| CC6.6 | Webhook schema validation, DLQ, idempotency; attachment magic-byte check; CSV formula-injection neutralisation | code, `IncidentWebhookService`, `CaseWorkService` | F | Implemented |
| CC6.7 | Status-only APIs for crew scheduling and hygiene; `catering-hygiene` outbound allow-list; clinical-text guard on referrals and notifications | `SafetyService.StatusRow`, `application.yml`, `ContentRules` | F | Implemented |
| CC7.2 | DLQ alert log line and audit; SLA breach events and notifications; audited configuration changes | logs, `platform.audit_event` | F/O | Implemented (SIEM rule: Org) |
| CC8.1 | Protocol versions with CMO approval and audit; Flyway V3–V5 under the same migration discipline; demo data excluded from shared environments by profile | `triage.triage_protocol`, `flyway_schema_history` | F/O | Implemented |
| PI1.2 | Webhook per-item validation with DLQ; referral template validation | tests, DLQ | F | Implemented |
| PI1.3 | Server-side triage evaluation with red-flag hard stop; transition guards (recordability, safety); SLA state machine | `ProtocolEvaluatorTest`, `Increment1IntegrationTest` | F | Implemented |
| PI1.4 | Reconciliation report: every incident's fate (auto-created, suggested, ignored, no match) | `/intake/reconciliation` | F/O | Implemented |
| C1.1 | New clinical free-text fields encrypted; `triage` schema classified CLINICAL | entities, migrations | F | Implemented |
| P3.1 | Manager / HR referrals limited to non-clinical fields; status-only consumers | tests, E2E | F | Implemented |

### 11.7 Organisation-operated controls and residual risks added

| Control | Frequency | Owner (proposed) | TSC |
|---|---|---|---|
| Clinical approval of triage protocols, advice texts and the recordability mapping; review on every new version | Before go-live, then per version | CMO + Nursing Head | CC8.1, PI1.3 |
| Access review including `CMO`, `NURSING_HEAD`, `OH_ADMIN`, `CREW_SCHEDULING`, `HYGIENE_MANAGER` | Quarterly | OH service owner + IAM | CC6.2, CC6.3 |
| SIEM rule on `ALERT ehs-webhook dead-lettered` and on `sla.breached.v1` volume | Continuous | SOC | CC7.2 |
| Review of DLQ items (replay or discard) | Daily on business days | OH administrator | PI1.2 |
| Review of intake / assignment rule and SLA policy changes | Monthly | Nursing Head | CC8.1 |
| Integration client credential rotation for the EHS webhook | 90–180 days | IAM / integration team | CC6.1 |

| ID | Residual risk | Compensating control (now) | Target remediation |
|---|---|---|---|
| RR13 | Clinical-text guard is heuristic | Status-only read side, UI guidance, audited referrals | Periodic review of rejected / accepted samples; vocabulary maintained by OH governance |
| RR14 | Draft protocols usable when no approved version exists | UI shows the status; CMO approval workflow | Go-live gate: all protocols in use approved; option to block drafts in prod by configuration |
| RR15 | OH staff roster loaded by SQL (no API, no HRIS feed) | Small population; changes by DBA under change control | Roster import from IdP groups or HRIS (doc 01, O9) |
| RR16 | No maker-checker on rule / SLA / matrix changes | Audited, limited to NURSING_HEAD / OH_ADMIN | Approval workflow (RR6) |
