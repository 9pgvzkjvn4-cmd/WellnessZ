# WellnessZ OHCM – Security and SOC 2 Control Mapping

| | |
|---|---|
| **Document** | 02 – Security and SOC 2 Control Mapping |
| **System** | WellnessZ Airline Occupational Health Case Management (OHCM) – base framework |
| **Audience** | Cyber GRC team, CISO, internal audit, Data Protection Officer, CIO |
| **Baseline** | Repository working tree, Maven version `0.1.0-SNAPSHOT` |
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
| Vulnerability management | dependency-check fail ≥ CVSS 7, FindSecBugs, Trivy/CodeQL (pipeline), patched base images | Managed-service patching (DB, AKS/GKE control plane) | Patch SLAs, **annual penetration test**, vulnerability triage, exception register |
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
| CC1.2 Board oversight | Steering-committee decisions (doc 01, Section 16) | Committee minutes | O | Org |
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
| CC3.2 Risks identified and analysed | STRIDE threat model (Section 7), risk register in doc 01 Section 15 | this document | O | Partial (formal register: Org) |
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
| CC6.3 Role-based access, least privilege, segregation of duties | 11 canonical roles; disclosure levels; clinicians vs managers; AUDITOR read-only to audit; INTEGRATION limited to `/integration/**` | role matrix (doc 01 App. A), **quarterly access review** | F/O | Implemented / Org |
| CC6.4 Physical access | – | cloud provider SOC 2 | C | Carve-out |
| CC6.5 Disposal of data and assets | Consent withdrawal preserves evidence; idempotency purge 7 days; **no retention/deletion jobs yet** | – | F/O | Planned |
| CC6.6 Protection against threats from outside the boundary | WAF (Front Door / App Gateway WAF_v2 / Cloud Armor) in Terraform; TLS 1.2+; CSP, HSTS, X-Frame-Options; CORS closed; EHS sender allow-list; hardened Excel parser; FHIR JSON-only (no XXE) | Terraform, response headers (scan), tests | F/C | Implemented |
| CC6.7 Restriction of transmission and movement of information | TLS everywhere incl. DB `verify-full`; **OutboundPayloadGuard** allow-list per destination (default deny); notification content rules; BFF header allow-list; mobile no-store, no localStorage | config `outbound-allowlist`, test `fitnessOutcomeIsPublishedToRosteringAsStatusOnly` | F | Implemented |
| CC6.8 Prevention/detection of unauthorised or malicious software | Signed images & Binary Authorization (GKE, Terraform); Trivy/CodeQL/gitleaks (pipeline); read-only root FS, non-root, dropped capabilities; no formula evaluation in Excel | Terraform `binauthz.tf`, pipeline artefacts | F/O | Partial (pipeline in progress) |

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
| CC8.1 Changes authorised, designed, developed, tested, approved, implemented | Git history; Flyway versioned, immutable migrations (owner role separate from runtime role); `ddl-auto: validate`; automated tests incl. architecture tests; SBOM per build; Helm deploy with environment approvals (pipeline); suppression files require justification/owner/expiry | PRs with approvals, CI logs, `flyway_schema_history`, release tags, Terraform plans | F/O | Implemented (pipeline approvals: in progress) |

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
| PI1.5 Storage of inputs and outputs | Encrypted inbound store; outbox history (retention to be defined) | DB | F/O | Partial |

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
| P6.2–P6.3 Record of disclosures | Outbox records every external disclosure with destination and time; audit trail | Records of processing (Art. 30) | Implemented (retention TBD) |
| P6.4–P6.5 Third-party commitments and notification | – | DPAs, breach-notification clauses | Org |
| P6.6–P6.7 Breach notification; accounting of disclosures | Audit and outbox provide data for the accounting | Breach procedure (GDPR 72 h) | Org |
| P7.1 Data quality | Validation, master data from HRIS (planned sync) | Data-quality ownership | Partial |
| P8.1 Inquiries, complaints, disputes | – | DPO contact and process | Org |

## 5. Evidence catalogue

| Evidence | How to obtain | Supports |
|---|---|---|
| Application audit trail | `GET /api/v1/audit?from=...&to=...&limit=5000` as AUDITOR (each read is itself audited) | CC6.1, CC6.3, CC7.2, P6.2 |
| Audit integrity proof | `GET /api/v1/audit/verify` → `{intact, recordsChecked, firstBrokenId}`; schedule daily, store results and the head hash in WORM storage | CC4.1, CC7.2, PI1.5 |
| Integration event log | `GET /api/v1/integration/events?status=DEAD|FAILED|PUBLISHED` (metadata only) | PI1.4, CC7.4, P6.2 |
| Test reports | Maven Surefire/Failsafe reports, JaCoCo coverage, Vitest results per build | CC8.1, CC3.4 |
| SAST / SCA reports | SpotBugs + FindSecBugs (`mvn -Psecurity verify`), OWASP dependency-check HTML + SARIF, CodeQL alerts | CC7.1, CC8.1 |
| SBOM | CycloneDX aggregate BOM (`target/bom.json`/`bom.xml`) attached to each release | CC7.1, CC9.2 |
| Container scan & signatures | Trivy reports, cosign signatures/attestations, Binary Authorization admission logs (GKE) | CC6.8, CC8.1 |
| Infrastructure configuration | Terraform plans and state (remote, access-controlled), `terraform show` of security settings; Helm values per environment | CC5.2, CC6.6, A1.2 |
| Change history | Git history, pull requests with reviewers and approvals, release tags, `flyway_schema_history` / `flyway_schema_history_bff` | CC8.1 |
| Identity configuration | Entra ID app registrations, app-role assignments, Conditional Access policies, sign-in logs; access-review records | CC6.2, CC6.3 |
| Cloud audit logs | Azure Activity / diagnostic logs (Key Vault, PostgreSQL with pgaudit), GCP Admin + Data Access audit logs | CC7.2 |
| Backups and restore tests | Backup policy screenshots/exports, restore test reports | A1.2, A1.3 |
| Consent records | `platform.consent` extract (via DPO-approved query) | P2.1 |

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
| F6 | **I**nformation disclosure | Clinical field leaks to rostering/HR | OutboundPayloadGuard allow-list, default deny; flat payloads | M – top-level check only |
| F6 | **T**ampering / **R**epudiation | Message altered or disputed | Outbox history; TLS; broker adapter authentication (to be implemented); messageId | M until adapter + message signing decided |
| F6 | **D**oS | Downstream outage | Back-off, dead-letter, replay; outbox absorbs backlog | L |
| F7 | **S**poofing | Unauthorised system posts lab results | Client-credentials token from trusted issuer + INTEGRATION role | L |
| F7 | **T**ampering | Malformed/hostile FHIR or HL7 | JSON-only FHIR (no XXE), strict parser, type allow-list, HL7 validation | L |
| F7 | Replay / duplication | Retry storms create duplicates | Idempotency-Key with request hash; HL7 control id stored | L |
| F8 | **I**nformation disclosure | Re-identification from small groups or differencing | k ≥ 5 (floor 3), complementary suppression, restricted class excluded, allow-listed dimensions | M – no protection against differencing across time windows/dimensions |
| F9 | **T**ampering | Audit rows altered or deleted | Trigger + grants block UPDATE/DELETE/TRUNCATE; hash chain; verify API | M – DB owner could rewrite chain; external anchoring planned |
| F9 | **I**nformation disclosure | Audit contains PHI | Reasons sanitised; coding standard forbids PHI in reasons | L |
| F10 | **E**levation / **T**ampering | Admin changes config (e.g. module flag, min group size) maliciously | Role-restricted, audited `CONFIG_CHANGE`; analytics floor k ≥ 3 enforced in code | M – no maker-checker; old/new values not in audit |
| F10 | **T**ampering | Operator replays events to re-send data | Replay restricted to PLATFORM_ADMIN/OH_ADMIN, audited; consumers idempotent | L |
| F11 | **I**nformation disclosure | Master key theft | Key in vault, CSI file mount, workload identity, never in image or Git | M – single master key; envelope encryption recommended |
| F11 | **E**levation | Pod identity abused | Per-workload service accounts, least-privilege vault roles (Vault policies in `deploy/hybrid`) | L–M |
| F12 | **T**ampering | Malicious dependency or image | Pinned versions, lockfiles, SCA, SBOM, signing + Binary Authorization (GKE) | M until pipeline completed and admission enforced on AKS/on-prem |
| F12 | **I**nformation disclosure | Secrets committed to Git | `ENC()`/`file:` pattern, gitleaks (pipeline), `.env` git-ignored | M – `deploy/local/.env` is git-ignored; `apps/wz-core-app/id_file` is currently not ignored and must be removed or ignored before the first commit |

## 8. Secure software development lifecycle

| Phase | Practice | Tooling / artefact |
|---|---|---|
| Requirements | Classify data (`DataClassification`), decide disclosure level and outbound contract per module; DPIA screening | Module design note, ConnectorRegistry entry |
| Design | Threat-model new flows (extend Section 7); define `AccessPolicy` beans; events as flat records | design review |
| Implementation | Developer Guide standards: no PHI in logs/errors/audit reasons, validation, RFC 9457, parameterised SQL / allow-listed dimensions, `EncryptedStringConverter` for clinical text | IDE, code review |
| Verification | Unit, integration (real PostgreSQL), ArchUnit, web unit tests; SAST (SpotBugs/FindSecBugs, CodeQL); SCA (dependency-check, npm lockfile audit); secret scan; DAST (recommended) | CI reports |
| Release | SBOM, image build from pinned bases, Trivy scan, cosign signing, Helm deploy with approvals | `.github/workflows/` (in progress), Helm |
| Operations | Patch base images (Dependabot), monitor, audit verification, incident response | SIEM, runbooks |
| Exceptions | Suppression/exclusion files require justification, owner, expiry; reviewed each release | `config/dependency-check-suppressions.xml`, `config/spotbugs-exclude.xml` |

## 9. Residual risks and compensating controls

| ID | Residual risk | Compensating control (now) | Target remediation |
|---|---|---|---|
| RR1 | Single master key; master-key rotation requires re-encryption tooling not yet built | Vault RBAC, purge protection, access logging on Key Vault/Secret Manager | Envelope encryption via KMS/HSM; re-encryption job (increment 1) |
| RR2 | Outbox payloads unencrypted, retained indefinitely | Storage encryption; DB access restricted; payloads minimal | Encrypt payloads; purge PUBLISHED after 30 days |
| RR3 | No retention/deletion jobs | Data volume small at start; manual deletion procedure via DPO | Retention schedule and purge jobs (increment 2) |
| RR4 | Audit chain rewritable by DB owner | Separate owner role used only by migration Job; pgaudit DDL/role logging; cloud audit logs | External hash anchoring + SIEM streaming |
| RR5 | No break-glass access | OH_ADMIN escalation via IdP role assignment (audited in IdP) | Break-glass flow with justification and alerting |
| RR6 | No maker-checker on configuration | Audited changes; limited admin population | Approval workflow in E18 UI |
| RR7 | Mobile secure storage / MAM not yet integrated | In-memory fallback (re-login), idle logout, no-store | Keychain/Keystore plugin, Intune APP SDK before store release |
| RR8 | External broker adapter and message authentication not implemented | Only logging publisher; no PHI leaves the system externally yet | Adapter with mTLS/managed identity per D3 |
| RR9 | Outbound guard top-level only | Flat record convention, code review, integration test | Schema-based contract tests per destination |
| RR10 | Differencing attacks on KPI API | Single KPI, allow-listed dimensions, k ≥ 5 | Query auditing thresholds, row/column suppression, fixed reporting periods |
| RR11 | CI/CD pipeline not complete | Maven security profile available locally | Complete workflows with gates; evidence retention |
| RR12 | Local development artefacts with credentials in working tree (`deploy/local/.env`, `apps/wz-core-app/id_file`) | Local-only values; `deploy/local/.gitignore` excludes `.env` | Remove or git-ignore `id_file` before the first commit; rotate any credential that was ever pushed |

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
