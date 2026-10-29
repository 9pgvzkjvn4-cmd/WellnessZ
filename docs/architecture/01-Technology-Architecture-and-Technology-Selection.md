# WellnessZ OHCM – Technology Architecture and Technology Selection

| | |
|---|---|
| **Document** | 01 – Technology Architecture and Technology Selection |
| **System** | WellnessZ Airline Occupational Health Case Management (OHCM) – base framework |
| **Audience** | Chief Information Officer, Cyber Governance, Risk & Compliance (GRC) team, architecture board |
| **Baseline** | Repository working tree, Maven version `0.1.0-SNAPSHOT`, Helm chart `0.1.0`; updated for **increment 1** (E01 case intake, E02 clinical triage) **increment 2** (E03 OH record & clinical encounters, E05 fitness for duty, restrictions & return to work) **increment 3** (E06 absence & sickness management, E07 medical surveillance, statutory medicals & immunisation, E14 scheduling, clinic operations & resource management) and **increment 4** (E04 injury & illness management, treatment coordination & partner referrals, E08 workers' compensation, insurance & claims coordination, partner portal) |
| **Status** | Draft for steering-committee review |
| **Increment 1 documents** | [Solution Design E01/E02](../increment-01/01-Solution-Design-E01-E02.md) · [Requirements Traceability Matrix](../increment-01/02-Requirements-Traceability-Matrix.md) · [Release Notes](../increment-01/03-Release-Notes-Increment-1.md) · [API Contract](../increment-01/API-Contract-E01-E02.md) |
| **Increment 2 documents** | [Solution Design E03/E05](../increment-02/01-Solution-Design-E03-E05.md) · [Requirements Traceability Matrix](../increment-02/02-Requirements-Traceability-Matrix.md) · [Release Notes](../increment-02/03-Release-Notes-Increment-2.md) · [API Contract](../increment-02/API-Contract-E03-E05.md) |
| **Increment 3 documents** | [Solution Design E06/E07/E14](../increment-03/01-Solution-Design-E06-E07-E14.md) · [Requirements Traceability Matrix](../increment-03/02-Requirements-Traceability-Matrix.md) · [Release Notes](../increment-03/03-Release-Notes-Increment-3.md) · [API Contract](../increment-03/API-Contract-E06-E07-E14.md) |
| **Increment 4 documents** | [Solution Design E04/E08](../increment-04/01-Solution-Design-E04-E08.md) · [Requirements Traceability Matrix](../increment-04/02-Requirements-Traceability-Matrix.md) · [Release Notes](../increment-04/03-Release-Notes-Increment-4.md) · [API Contract](../increment-04/API-Contract-E04-E08.md) |
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

- A **modular monolith** core API (`apps/wz-core-app`) on Java 21 and Spring Boot 4.1.2, composed of nine platform Maven
  modules plus, since increment 1, two business modules (`modules/wz-e01-intake`, `modules/wz-e02-triage`); module
  boundaries are enforced by ArchUnit tests. Increment 2 adds a tenth platform module (`platform/wz-documents`) and
  two business modules (`modules/wz-e03-record`, `modules/wz-e05-fitness`); increment 3 adds three business modules
  (`modules/wz-e06-absence`, `modules/wz-e07-surveillance`, `modules/wz-e14-scheduling`) and increment 4 two more
  (`modules/wz-e04-treatment`, `modules/wz-e08-claims`), so the core now consists of ten platform and nine business
  modules (17 ArchUnit rules).
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
  Browser apps use a **Backend-for-Frontend** (`apps/wz-bff`) so that tokens never reach the browser. Increment 1 adds
  the React **OH clinical workspace** (`web/oh-workspace`) for nurses, physicians, AMEs, the CMO, the Nursing Head,
  OH administrators, clinic reception and first aiders. Increment 4 adds a fourth browser front end, the **partner
  portal** (`web/partner-portal`, host `partners.<domain>`) for external partner providers and TPA / insurer adjusters,
  who are admitted only with multi-factor authentication proven in the token.
- **Deployment assets** for Azure (AKS), Google Cloud (GKE) and a hybrid on-premises option (RKE2/OpenShift with
  CloudNativePG): hardened Helm chart, Terraform, Dockerfiles, local Docker Compose stack.

**What increment 1 added (Section 19):** E01 case intake (EHS webhook with dead-letter queue, configurable intake rule
table, referral templates, walk-in/emergency registration, system triggers, duplicate detection, recordability
classifier, notification matrix) and E02 clinical triage (server-side triage protocols with red-flag hard stop, SLA
timers, assignment engine, work queues, escalations, complexity score, status-only APIs for crew scheduling and
catering hygiene), the `triage` schema, nine new roles and the OH workspace.

**What increment 2 added (Section 20):** E03 OH record & clinical encounters (employee chart with problems, allergies,
medications, immunisations and job history; eight versioned encounter templates with body map, coded diagnosis,
e-signature, addenda and photographs; terminology search over SNOMED CT / ICD / LOINC; laboratory orders and results
over a pull queue and HL7 v2; device capture; clinical decision support; FHIR R4 and ODH export; a consent-gated
AI-assistant extension point with no provider enabled) and E05 fitness for duty (decisions against versioned fitness
standards with AME / CMO sign-off, restriction catalogue and job-demand matching, modified duty, return-to-work plans,
job offers, crew assessment with a status-only roster feed and acknowledgement / reconciliation, food-handler
exclusion and clearance, ill-health retirement with appeals). The new platform document service generates PDFs with
Apache PDFBox, signs them with a SHA-256 integrity hash, shares them with consent and a disclosure log, indexes them for
search through keyed blind-index tokens and applies a retention schedule with legal hold. Security additions are the
`DPO` role, break-glass access, the disclosure log, consent-withdrawal listeners and record-level ABAC
(`EmployeeRecordAccess`). New schemas: `docs`, `ohrecord`, `fitness` (migrations V6–V8).

**What increment 3 added (Section 21):** E06 absence & sickness management (absence feed from HRIS, time & attendance
and crew systems with reconciliation, versioned trigger rules and an early-intervention queue, sick certificates with
validation, a long-term absence pathway with milestones and case conferences, management advice requests answered by
an OH advice report that the employee reviews before release, absence analytics with small-group suppression), E07
medical surveillance (13 hazard-driven programmes, due lists and invitations, exposure registers for industrial-hygiene
measurements and cosmic radiation, result follow-up, a return-after-serious-illness clearance guard, pre-employment
assessment with a candidate portal and 12-month purge, crew medical certificates, an immunisation registry with GS1
barcode entry, compliance reporting and anonymised audit packs) and E14 scheduling & clinic operations (service
catalogue, clinic resources and diary templates, booking with conflict detection, waitlist offers, employee
self-booking, badge / app check-in and a queue board without names, screening campaigns, an equipment register whose
calibration state blocks device results, cold-chain temperature logging from staff and IoT fridges with excursion
handling, capacity forecasting and telehealth links). Seven new kernel SPIs connect the three modules with each other
and with E03 / E05 without any module-to-module dependency. Platform additions: clinic sites, new hazards and job codes,
the roles `CLINIC_SCHEDULER`, `BIOMEDICAL`, `CREW_LICENSING` and `CANDIDATE`, three document templates, 23 content-free
notification templates and two proposed retention classes. New schemas: `absence`, `surveillance`, `scheduling`
(migrations V9–V12).

**What increment 4 added (Section 22):** E04 injury & illness management, treatment coordination & partner referrals
(treatment plans from eight injury templates with follow-ups and a recovery-variance alert against duration guidelines,
an employee view in plain language; e-referrals with a required data set, consent check, signed referral letter and a
FHIR R4 `ServiceRequest`, chased and escalated when reports are overdue; a provider network directory with contracts,
rate cards, SLAs, network gaps and monthly scorecards; pre-authorisation shared with the payer under consent and
decided by the TPA adjuster; provider invoices matched against the approval; clinic medication administration with
allergy and batch checks; outcome measures with a closure guard; telehealth sessions with consent, single-use join
tokens and secure chat) and E08 workers' compensation, insurance & claims coordination (claim record and lifecycle with
a case-header badge, statutory and insurer first reports with deadline timers, consent-gated disclosure sets with
redaction, automatic nurse case manager for lost-time claims and adjuster collaboration through tasks and messages,
a cost ledger with roll-ups by subsidiary, budget versus actual and a monthly ERP export with reconciliation,
litigation hold, privilege and medico-legal reports). The **partner portal** is the first front end for external
users; `PARTNER_PROVIDER` and `TPA_ADJUSTER` are granted by the core API only when the token proves MFA. Platform
additions: 25 partner organisations (providers, insurers, a TPA, authorities), the `INSURER` share target, four
document templates, the retention class `CLAIM_RECORD` with case-level legal hold, the consent purpose
`TELEHEALTH_CONSULTATION`, 13 content-free notification templates, three reference-only outbound destinations
(`insurer-gateway`, `authority-gateway`, `erp-finance`), four kernel SPIs and the roles `CLAIMS_COORDINATOR`,
`TPA_ADJUSTER`, `LEGAL_COUNSEL`, `FINANCE` and `PROCUREMENT`. New schemas: `treatment`, `claims` (migrations V13–V15).

**What is deliberately not yet built:** the clinical business modules E03, E04, E06–E12 and E14 (E05 and E13 exist
only as kernel seeds; E01 and E02 were delivered in increment 1), production broker adapters (Kafka / Azure Service Bus / Google Pub/Sub), enterprise connector
mappings (HRIS, rostering transport, ERP, insurers), the partner provider portal, the data-warehouse feed and predictive
models, retention/deletion jobs for OH records (only outbox, idempotency and session purges exist), the master-key
re-encryption job, break-glass access and maker-checker approval of configuration changes. Section 18 lists
the increments; Section 17 lists the decisions the steering committee is asked to take.
*Update for increment 2:* E03 and E05 are now implemented, break-glass access exists (Section 20), and documents
have a retention schedule with legal hold and DPO flagging – but still **no automatic deletion** of OH records. The
partner provider portal is limited to reading documents shared with the partner organisation under consent.
*Update for increment 3:* E06, E07 and E14 are implemented (E14 was pulled forward from increment 5). The first
automatic deletion exists for pre-employment data of candidates who were not hired (anonymised after 12 months);
OH records are still only flagged. Production connectors to HRIS / time & attendance / crew systems, the IoT fridge
gateway and a telehealth video platform are extension points (Section 21).
*Update for increment 4:* E04 and E08 are implemented and the partner portal exists, so the remaining planned
business modules are E09–E12 (increment 5). Real insurer, authority and ERP connectors (`ClaimsSubmissionGateway`
default = outbox event plus content-free e-mail), a live FHIR exchange with a partner hospital, a telehealth video
platform (`TelehealthProvider` default = link provider) and e-prescribing (`EPrescribingGateway` no-op) are extension
points (Section 22). OH records are still only flagged; claim records follow the same approach, and a litigation hold
suspends retention for every document of the case.

**Assessment for the CIO.** The framework establishes the controls that are hardest to retrofit – identity federation,
ABAC with disclosure levels, tamper-evident audit, encryption and key derivation, the outbox/idempotency backbone and the
status-only boundary – before any clinical module is written. The principal residual risks are operational (key
management and rotation procedure, retention of OH records, broker selection) rather than structural. Section 15
summarises the verification evidence available today. The
technology choices are mainstream, long-term-supported and portable across Azure, Google Cloud and on-premises
Kubernetes.

## 2. Scope, context and assumptions

**In scope** of this document: the repository content under `platform/`, `apps/`, `web/`, `deploy/`, `config/` and
the CI/CD design. **Out of scope:** the clinical content of modules E01–E14 (forms, pathways, rules), organisational
policies, and the operating model of the airline's IT organisation, which are referenced where they are a dependency.

**Assumptions** (to be confirmed – see Section 17):

| # | Assumption |
|---|---|
| A1 | The airline uses Microsoft Entra ID as the workforce identity provider; Keycloak or another OIDC provider may be used for partners and for local development. Increment 4: external partner and adjuster users authenticate with MFA enforced at the IdP (Entra ID B2B with Conditional Access, or a Keycloak partner realm with OTP / WebAuthn) and the token carries `amr` / `acr`. |
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
| P6 | **Modular monolith, extractable modules.** | One deployable, ten platform Maven modules plus the business modules under `modules/` (E01, E02 since increment 1; E03, E05 since increment 2; E06, E07, E14 since increment 3; E04, E08 since increment 4), communication across business modules via events and kernel SPIs only; any module can later become a service. |
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
| EHS incident-management system – webhook (increment 1) | `POST /api/v1/intake/ehs/incidents` (role `INTEGRATION`, client-credentials token, mandatory `Idempotency-Key`, ≤ 500 incidents per call) → `IncidentWebhookService` (per-item schema validation; invalid items to the dead-letter queue `intake.incident_dlq`, operator view `/api/v1/intake/dlq`). | Implemented |
| EHS → case | `ehs.incident.received.v1` event → E01 `IncidentIntakeListener` → `RuleEngine` over the admin-editable table `intake.intake_rule` (AUTO_CREATE / SUGGEST / IGNORE, first match by priority); every incident's fate recorded in `intake.incident_evaluation` for reconciliation. Replaces the code-based `IncidentToCaseRule` seed of the base framework. | Implemented (increment 1) |
| Employee / crew mobile app & portal | `web/employee-app` (React 19.3.1, Capacitor 8.6.0): my cases, self-referral, consents, profile; web/PWA via BFF; iOS/Android with OIDC + PKCE and MDM AppConfig. | Implemented (E13 seed); native projects generated on demand (`npx cap add`) |
| Manager & HR workspace (status-only) | `web/manager-workspace`: team case status, case status detail, KPI tile with suppression; manager and HR referral forms (increment 1) with server-side clinical-text guard. | Implemented |
| OH clinical workspace (increment 1) | `web/oh-workspace`: work queues, case view (triage, recordability, assignment, escalation, complexity, checklists, duplicates, tasks, timeline), intake operations, configuration screens, reception / first-aid registration. | Implemented |
| Group clinics | Clinic devices can send results via HL7 v2 / FHIR R4 inbound; increment 3: E14 runs the clinic diaries, check-in (badge, app or kiosk), queue board and call-next for six seeded clinic sites (`masterdata.clinic_site`: HQ medical centre, airside post, catering room, cargo / engineering clinic, outstation, mobile unit) | Implemented (increment 3) |
| Candidate (pre-employment) portal (increment 3) | `web/employee-app` with role `CANDIDATE` (identity not in the HRIS master, e.g. `C9001`): only the own pre-employment questionnaire, consent, documents and booking (`/api/v1/me/pre-employment/**`) | Implemented; candidate identity provisioning (IdP) open |
| IoT fridge gateway (increment 3) | `POST /api/v1/integration/cold-chain/readings` (role `INTEGRATION`, ≤ 5,000 readings per call, device id + temperature + time) → E14 cold-chain log and excursion detection | Endpoint implemented; gateway integration open |
| Partner provider / TPA portal (increment 4) | `web/partner-portal` (host `partners.<domain>`, dev port 5176, same-origin BFF login): `PARTNER_PROVIDER` – referral inbox, accept / decline, appointment, seen, report and invoice upload, telehealth sessions and chat, shared documents; `TPA_ADJUSTER` – claims where the organisation is payer (RTW status and milestones only), shared tasks, secure messages, consented documents, pre-authorisation decisions. Organisation = last segment of `org_scope` (`/PARTNERS/<CODE>`); the core grants both roles only with MFA proof in the token (Section 8.2). Partner hospital systems use `GET /integration/referrals/{id}/fhir` (FHIR R4 `ServiceRequest`) and `POST /integration/referrals/fhir/document-reference`. | Implemented (increment 4); production MFA enrolment and a live FHIR partner open |
| System triggers | E01 `TriggerController`: absence episodes, surveillance results and D&A test triggers (role `INTEGRATION`), crew fitness self-declaration (role `EMPLOYEE`); absence thresholds configurable in E18 (`intake.absence.*`). Increment 3: scheduled triggers – E06 absence trigger rules (`absence-triggers` job), E07 surveillance due list and re-invitation (`surveillance-due`), crew certificate expiry (`crew-certificates`), E14 reminders and no-shows. Increment 4: recovery variance and follow-up reminders (`treatment-variance`), referral report chasing (`referral-chase`), monthly provider scorecards, statutory / insurer report deadlines (`claims-report-deadlines`, hourly). | Implemented |

### 4.2 OH case-management core (E01–E14)

Status per module is taken from `ModuleRegistry` (`platform/wz-case-kernel/.../module/ModuleRegistry.java`), exposed
at `GET /api/v1/modules`. `KERNEL` means a minimal seed exists in the case kernel; `PLANNED` means not built;
`IMPLEMENTED` is reported automatically once a `CaseModule` bean with that code is deployed.

| Code | Module | Registry status | What exists in the base framework |
|---|---|---|---|
| E01 | Case intake & incident-triggered case initiation | IMPLEMENTED (increment 1, `modules/wz-e01-intake`) | Configurable intake rule table, EHS webhook + DLQ, self / manager / HR referral templates (en/ar/fr) with body map, walk-in and emergency encounters, system triggers, duplicate detection and merge, recordability classifier with local mapping and closure guard, notification matrix, reconciliation report and dashboard. Case reference `UNIT-YYYY-NNNNNN` per unit and year (`CaseRefGenerator`). |
| E02 | Clinical triage, risk stratification & case assignment | IMPLEMENTED (increment 1, `modules/wz-e02-triage`) | Versioned triage protocols (JSON, 13 DRAFT protocols awaiting CMO approval) with red-flag hard stop, SLA timers (amber / breach / pause), assignment engine and work queues, escalations and case conferences, complexity score, safety checklists, crew and food-handler status-only APIs, triage QA sampling, telephone triage mode. |
| E03 | OH record & clinical encounters | IMPLEMENTED (increment 2, `modules/wz-e03-record`) | Chart (problems, allergies, medications, immunisations, job history / ODH, record timeline), 8 versioned encounter templates (JSON, DRAFT until CMO approval) with body map, SOAP, coded diagnosis, e-signature, addenda, entered-in-error and photographs; terminology catalogue; lab orders (HL7 v2 ORM^O01 pull queue) and results (LIS JSON, HL7 v2 ORU^R01, device capture with flagging rules), result worklist with 72 h escalation; CDS (allergy / drug-class, rules, override log); case summary; FHIR R4 and ODH export; AI documentation SPI (no provider). Base-framework seed: encrypted `clinical_summary`, FHIR inbound store. |
| E04 | Injury & illness management, treatment coordination & partner referrals | IMPLEMENTED (increment 4, `modules/wz-e04-treatment`) | Treatment plans from 8 injury templates (goals, interventions, follow-ups booked through `AppointmentBooking`), recovery-variance alerts against E02 duration guidelines, employee plan view and printable `TREATMENT_PLAN`; e-referrals for 14 services with required data set, `TREATMENT_SHARING_PARTNER` consent, signed `REFERRAL_LETTER` and FHIR R4 `ServiceRequest`, partner-portal workflow (accept, appointment, seen, report, invoice), chase and escalation; provider directory with contracts, rate cards, SLAs, network gaps, monthly scorecards and CSV review pack; pre-authorisation (`PREAUTH_REQUEST` shared `INSURER`, SLA 48 h, adjuster / coordinator / insurer-system decision) and invoice matching with costs posted through `CaseCostRecorder`; clinic formulary, stock batches and administration with allergy and batch checks mirrored to the E03 medication list; `EPrescribingGateway` (no-op); outcome measures (NPRS, ODI, QuickDASH, PSFS, LEFS) with `outcome_required` close guard; telehealth sessions (`TelehealthProvider` SPI, consent `TELEHEALTH_CONSULTATION`, single-use hashed tokens, encrypted chat, draft E03 encounter on completion); KPIs; implements `ExternalReferralGateway`. |
| E05 | Fitness for duty, restrictions & return to work | IMPLEMENTED (increment 2, `modules/wz-e05-fitness`) | Fitness decisions against versioned standards (12 seeded, incl. Part-MED Class 1/2 and cabin crew) with AME / CMO sign-off and certificates; restriction catalogue, job-demand matching, modified-duty proposals; RTW plans with acknowledgements and reviews; HR job offers; crew assessment with limitation codes, roster feed with acknowledgement and reconciliation, CMO crew dashboard; food-handler exclusion / clearance; ill-health retirement and redeployment with appeals; daily expiry / review job. Base-framework seed: `FitnessStatus`, `restrictionUntil`, `POST /cases/{id}/fitness`, status-only `fitness.status.changed.v1`. |
| E06 | Absence & sickness management | IMPLEMENTED (increment 3, `modules/wz-e06-absence`) | Absence feed (push `POST /integration/absence/episodes`, daily pull through the `AbsenceFeedConnector` SPI – no-op by default) with reconciliation of unknown employees; versioned trigger rules (8 seeded, e.g. 10+ days, frequency, Monday / Friday and post-holiday patterns) with subsidiary overrides and an early-intervention queue; sick certificates (employee photo upload, OH validation, HR sees validity only); long-term absence pathway (milestones at weeks 2, 4, 8, 12, 26, case conferences); management advice requests and OH advice reports (`OH_ADVICE_REPORT`) released after employee review; absence analytics with small-group suppression; `AbsenceSignalListener` to E07. |
| E07 | Surveillance, statutory medicals & immunisation | IMPLEMENTED (increment 3, `modules/wz-e07-surveillance`) | 13 hazard-driven programmes (content pending CMO validation) with eligibility from job code, hazards and age; due lists, invitations with re-invitation and manager escalation; exposure registers (industrial-hygiene measurements, cosmic dose, pregnancy declarations); result follow-up rules on E03 results (`ResultFollowUpListener`); return-after-serious-illness requirement enforced through `FitnessClearanceGuard`; pre-employment with candidate portal, HR status-only outcome and 12-month purge; crew medical certificates with expiry alerts and a crew-licensing status view; immunisation registry (GS1 barcode lots, titres, reminders, `VACCINATION_CERTIFICATE`); compliance and audit packs (anonymised by default). Base-framework seed: hazard catalogue, job-code hazard profiles, FHIR `Immunization` inbound. |
| E08 | Workers' compensation, insurance & claims coordination | IMPLEMENTED (increment 4, `modules/wz-e08-claims`) | Claim record per case and type (workers' compensation, employer's liability, group medical, third party) with lifecycle NOTIFIED → OPEN → UNDER_REVIEW → SETTLED → CLOSED / LITIGATED, reserves, subrogation and a case-header badge (`ClaimContextProvider`); first reports of injury for three seeded forms (`GB_OSH_F2508`, `AE_LABOUR_INJURY`, `INSURER_FNOL` – pending Legal / HSE sign-off) with deadline timers, `ClaimsSubmissionGateway` SPI and acknowledgements; consent-gated disclosure sets with allowed document types, privilege exclusion and redacted copies; automatic nurse case manager for lost-time claims (48 h KPI) and adjuster collaboration (tasks, messages, RTW status); cost ledger (`CaseCostRecorder` implementation), roll-ups with small-group suppression, budgets, monthly ERP export with checksum and reconciliation; litigation hold (`legalHoldForCase`), privilege, medico-legal / expert / IME requests (IME through `ExternalReferralGateway`); HR status view, DPO disclosure report. Never reads the E03 record (ArchUnit). |
| E09 | Psychological health & crew peer support – restricted class | PLANNED | Category `PSYCHOLOGICAL` → `RESTRICTED_CLINICAL`; restricted-class deny policy; role `PSYCH_CLINICIAN`; excluded from analytics view. |
| E10 | Substance-testing programme – restricted class | PLANNED | Category `SUBSTANCE` → `RESTRICTED_CLINICAL` with the same controls as E09. |
| E11 | Travel health, exposures & communicable disease | PLANNED | Category `EXPOSURE`; EHS exposure rule. |
| E12 | Health promotion & campaigns | PLANNED | Consent purposes `HEALTH_CAMPAIGNS`, `WEARABLE_DATA`; connector catalogued. |
| E13 | Employee & manager self-service | KERNEL | Self-referral, my cases, consents, profile (employee app); status-only team view (workspace). |
| E14 | Scheduling, clinic operations & resource management | IMPLEMENTED (increment 3, `modules/wz-e14-scheduling`) | Service catalogue (appointment types with duration, roles, room and equipment needs, telehealth flag, charging code), clinic resources and diary templates per site, booking with resource-conflict detection (`409 resource_conflict`), waitlist offers, employee self-booking / reschedule / cancel, check-in window and ticketed queue board without names, call-next hand-off to the chart, campaigns with cohort booking, equipment register with calibration certificates (blocks device results via `DeviceCalibrationCheck`), cold-chain readings (manual and IoT) with excursion acknowledgement, capacity forecast from `CapacityDemandSource`, telehealth join links; implements `AppointmentBooking` for E06 / E07. |

> Module enablement: the flag `modules.eXX.enabled` (E18 configuration) is enforced by
> `ModuleRegistry.requireEnabled(code)`, which answers **503** with problem code `module_disabled`. Today
> `CaseService.selfReferral` requires E13, and the E01 `IncidentIntakeListener` skips case creation when E01 is disabled
> (the incident stays in intake and can be processed later). The integration test `disabledModuleIsRefused` covers the guard. New
> modules must call it on every entry point (see the Developer Guide). Increment 3 seeds `modules.e06.enabled=true` in
> V10. Note: the business modules of increments 2 and 3 (E03, E05, E06, E07, E14) register through `CaseModule` and
> report the flag at `GET /api/v1/modules`, but their services do not yet call `requireEnabled` – switching one of
> them off is therefore not enforced today (open item O17). The same applies to E04 and E08 (V14 / V15 seed
> `modules.e04.enabled` and `modules.e08.enabled`).

### 4.3 Platform services

| Logical service | Technical realisation | Status |
|---|---|---|
| **E15 Integration & API gateway** – HL7 FHIR R4 | `FhirController`: `POST /api/v1/integration/fhir/{type}` (JSON only, 11 allow-listed resource types, strict parser), `GET .../metadata` CapabilityStatement; HAPI FHIR structures R4 8.12.1. Raw resources stored encrypted in `platform.inbound_message`; `integration.inbound.received.v1` emitted. | Implemented (inbound store); increment 2: HL7 v2 ORU^R01 results mapped into `ohrecord.result` (`OruResultListener`), FHIR R4 `Bundle` export of the OH record (`GET /employees/{id}/fhir`); increment 4: FHIR R4 partner exchange for e-referrals (`ServiceRequest` out, `DocumentReference` in – E04 `FhirMapper`, Section 14.27); FHIR inbound mapping for E07 planned |
| E15 – HL7 v2 | `Hl7v2Controller`: ER7 over HTTPS, HAPI HL7 v2 2.6.0 (`hapi-structures-v25`), ACK `AA`/`AR`. MLLP is terminated by an integration engine/gateway. | Implemented |
| E15 – ODH / SNOMED / ICD / LOINC | `CodeSystem` enum with canonical URIs; `TerminologyService` over `platform.code_concept` (7 illustrative seed concepts). Licensed releases or terminology server: planned. Increment 2: `TerminologyCatalog` (E03) adds word- and code-prefix search, favourites, recently used codes, local mappings, bulk import and a release register (`platform.terminology_release`); ODH work information exported as FHIR (`GET /employees/{id}/odh`) – Section 14.19 | Extension point (starter content implemented) |
| E15 – OAuth2 | All integration endpoints require role `INTEGRATION`, `PLATFORM_ADMIN` or `OH_ADMIN` via client-credentials tokens from a trusted issuer. | Implemented |
| E15 – Event bus with idempotency & replay | `EventBus` → `platform.outbox_event` → `OutboxRelay` → `EventPublisher` SPI; `IdempotentConsumer`; `IdempotencyService` (`Idempotency-Key`); `GET /integration/events`, `POST /integration/events/{id}/replay`. | Implemented (in-process); broker adapters extension point |
| E15 – API gateway | Kubernetes ingress plus WAF (Azure Front Door/App Gateway WAF_v2, GCP Cloud Armor) in Terraform; no dedicated API-management product. | Implemented (infra); APIM optional |
| **E16 Identity, ABAC, consent, immutable audit** | `wz-security`: multi-issuer resource server, `WellnessZJwtConverter`, `PolicyEngine` + `DefaultPolicies`, `ConsentService`, `AuditService` (hash chain), `SecurityEventAuditor`. Increment 4: MFA gate for external roles in `WellnessZJwtConverter` (`wellnessz.security.mfa-required-roles`, Section 8.2), consent purpose `TELEHEALTH_CONSULTATION`. | Implemented |
| **E17 Analytics, KPIs, regulatory reporting, predictive models** | `wz-analytics`: `analytics.v_case_facts`, `KpiService` (one KPI: cases by status), `SmallCellSuppression`, `Pseudonymizer`. Increment 3: module-level aggregates with the same minimum group size (`wellnessz.analytics.min-group-size`) – E06 absence analytics per unit (`UnitAggregator`: absence rate, frequency, average duration, Bradford mean, KPIs) and E07 surveillance compliance (`ComplianceMaths`); groups below the minimum are published as `suppressed: true` without counts. Increment 4: E04 treatment cost and KPI views and E08 cost roll-ups by subsidiary / department / cost centre (`CostMath`) suppress units with fewer cases than the minimum group size; finance never sees names. | KPI + suppression implemented; regulatory reports, warehouse feed and models planned |
| **E18 Master data & no-code configuration** | `wz-masterdata`: org units (materialised path), job codes, hazards, `ConfigService` key/value configuration (JSON values allowed, versioned, audited). Increment 3: clinic sites (`masterdata.clinic_site`, `GET /api/v1/masterdata/clinic-sites`), nine further hazards and five job codes for the surveillance programmes, business keys `absence.*`, `surveillance.*`, `scheduling.*`, `notifications.*` (Section 21.4). Increment 4: partner organisations (`masterdata.partner_organisation`, `GET\|POST /api/v1/masterdata/partner-organisations`, 25 seeded), business keys `treatment.*`, `claims.*` (Section 22.4). | Implemented (data model + API); HRIS sync and configuration UI planned |
| **Notification gateways** with content rules & quiet hours | `wz-notification`: approved templates with allow-listed placeholders, `ContentRules` (clinical-term and ICD-code detector), `QuietHours` (recipient time zone, URGENT bypass), SMTP channel; SMS/push channel SPI with logging stand-ins. Increment 3: 23 further content-free templates (absence, advice, certificates, surveillance invitations, crew medical expiry, immunisation due, appointments, waitlist offers, calibration and cold-chain alerts); `NotificationRulesTest` renders every template through the content rules. Increment 4: 13 content-free templates (`referral.*`, `treatment.*`, `preauth.status`, `telehealth.invite`, `claim.*`, `legal.request`), placeholder `reference`, partner-portal links (`wellnessz.notification.partner-portal-url`). | Implemented (e-mail); SMS/push adapters extension point; event-driven triggering planned |
| **Document service** (increment 2) | `wz-documents`: template catalogue (`DocumentTemplates`), PDF rendering with Apache PDFBox 3.0.8 (`PdfRenderer`), SHA-256 integrity hash and e-signature, versioning, sharing with consent and disclosure log, scanned-document ingestion (PDF text layer, `OcrProvider` SPI), blind-index full-text search, retention schedule and legal hold (`RetentionService`). Schema `docs`. Increment 3: templates `OH_ADVICE_REPORT`, `VACCINATION_CERTIFICATE`, `PRE_EMPLOYMENT_OUTCOME`; module-level API `storeUpload`, `generateSigned`, `shareAsSystem`, `systemContent` for documents created by modules and jobs rather than by a user in the OH workspace. Increment 4: share target `INSURER` (active `INSURER_SHARING` consent, disclosure purpose `CLAIM`), `shareAsSystem(actor, id, target, org, consentId)`, `legalHoldForCase`, upload types `PROVIDER_REPORT`, `PROVIDER_INVOICE`, `INSURER_ACKNOWLEDGEMENT`, `REDACTED_COPY`, templates `TREATMENT_PLAN`, `PREAUTH_REQUEST`, `FIRST_REPORT_OF_INJURY`, `MEDICO_LEGAL_REPORT`, retention class `CLAIM_RECORD`. | Implemented; qualified e-signature and cloud OCR adapters are extension points |

### 4.4 Enterprise and external systems

Catalogued in `ConnectorRegistry` (`GET /api/v1/integration/connectors`). The *data contract* column is the agreed
boundary content.

| System | Direction | Data contract | Status in the base framework |
|---|---|---|---|
| HRIS / HCM | in | employees, org hierarchy, job codes, lifecycle events | Generic idempotent `POST /integration/inbound/hris` and master-data upsert APIs exist; since increment 1 the HRIS employee master `masterdata.employee` is loaded through `POST /api/v1/masterdata/employees` (batch upsert ≤ 5,000, INTEGRATION / OH_ADMIN / PLATFORM_ADMIN, audited `HRIS_SYNC`); scheduled mapping job planned |
| Time & attendance / crew sickness | in | absence episodes (source reference, employee or badge / crew id, dates, absence type, reason category, certified flag) | Increment 3 (E06): push `POST /integration/absence/episodes` (≤ 5,000 rows, reconciliation of unmatched employees) and `POST /integration/absence/certificates`; daily pull through the `AbsenceFeedConnector` SPI – the shipped implementation is a no-op, site adapters for HRIS / T&A / crew management are open (connector E15) |
| Industrial hygiene, cosmic-radiation dosimetry | in | measurements per exposure group; annual crew dose | Increment 3 (E07): `POST /integration/ih-measurements`, `POST /integration/cosmic-dose` (role `INTEGRATION`) |
| Recruiting / applicant tracking | in / out | offer reference, candidate id; pre-employment status | Increment 3 (E07): `POST /integration/pre-employment` opens the assessment; HR receives `preEmploymentStatus` only (allow-list `hr-systems`) |
| Crew training & licensing | out | crew medical certificate validity and expiry | Increment 3 (E07): status-only view for `CREW_LICENSING` and `crew.certificate.status.v1`; roster status through the existing kernel feed |
| IoT fridge monitoring | in | device id, temperature, timestamp | Increment 3 (E14): `POST /integration/cold-chain/readings`; gateway product not selected (decision D16) |
| Telehealth video platform | out (link) | join URL per appointment / session | Increment 3 (E14): link only (`scheduling.telehealth-base-url` + random room id). Increment 4 (E04): telehealth sessions through the provider-agnostic `TelehealthProvider` SPI – default link provider (`treatment.telehealth.base-url`) with single-use participant tokens that the video platform redeems at `POST /integration/telehealth/tokens/redeem`; platform not yet procured (decision D15, Section 14.26) |
| Crew rostering | out | `fitnessStatus`, `restrictionUntil`, `effectiveFrom` (+ `caseRef`, `employeeId` keys); increment 2: `crew.roster-status.changed.v1` with `messageId`, `rosterStatus` (FIT / LIMITED / UNFIT / PENDING), `validFrom`, `validUntil`, `operationalLimitations` | Event produced and guarded; transport adapter is an extension point (logging publisher today). Increment 2 adds a pull feed for the rostering gateway (`GET /integration/roster-feed`, `POST /integration/roster-feed/{messageId}/ack`, reconciliation report; people use `/crew/roster-feed`) |
| HR systems / catering hygiene | out | fitness & restriction status only | Allow-lists `hr-systems` and (increment 1) `catering-hygiene`; `CaseService.publishFitness` sends every fitness change to `hr-systems`, aeromedical / unclassified cases to `crew-rostering` and food-handler cases to `catering-hygiene`; increment 3 adds `ohEngaged` (absence), `certificateValidity`, `offerRef`, `candidateId`, `preEmploymentStatus` to the `hr-systems` list; transport adapters are an extension point |
| Laboratories & clinic devices | both | orders / results | Inbound HL7 v2 / FHIR implemented; increment 2: the lab gateway **pulls** new orders (`GET /integration/lis/orders`, HL7 v2 ORM^O01 per order) and acknowledges them, posts results as JSON (`POST /integration/lis/results`) or HL7 v2 ORU^R01 (`POST /integration/hl7v2`); devices or their middleware post to `POST /employees/{id}/results/device` (audiometry, spirometry, vision, ECG with calibration id); increment 3: a device that is out of calibration or out of service in the E14 equipment register is refused (`422 device_out_of_calibration` / `device_out_of_service`) |
| Partner hospitals, specialists, physio, EAP | both | referrals / reports, consent required | Increment 4 (E04): e-referrals through the partner portal (letter and attachments shared under `TREATMENT_SHARING_PARTNER`, report and invoice upload) or FHIR R4 (`GET /integration/referrals/{id}/fhir` → `ServiceRequest`, `POST /integration/referrals/fhir/document-reference` → report received); tested with a simulated partner, no live partner hospital yet (E04-BL-006). Increment 2: signed documents (for example referral letters) can be shared with a partner organisation only with an active `TREATMENT_SHARING_PARTNER` consent; `PARTNER_PROVIDER` users read them at `GET /partner/documents` (organisation from the `org_scope` claim); withdrawal revokes the share immediately |
| Insurers / TPAs | both | first report of injury, pre-authorisation, documents, acknowledgements, consent required | Increment 4 (E04 / E08): reference-only event `claim.report.submitted.v1` to `insurer-gateway` (allow-list `messageId, claimRef, reportRef, formCode, payerCode, employeeId, incidentDate, submittedAt, deadlineAt`) plus content-free e-mail pointing to the partner portal; documents only through consent-checked `INSURER` shares; adjusters decide pre-authorisations in the portal or insurer systems call `POST /integration/preauthorisations/{ref}/decision` and `POST /integration/claims/reports/{ref}/acknowledgement`. Real insurer FNOL / pre-auth APIs are a `ClaimsSubmissionGateway` extension point |
| Reporting authorities (OSH / labour inspectorates) | out | statutory injury notice reference, form code, jurisdiction, deadline | Increment 4 (E08): `claim.report.submitted.v1` to `authority-gateway` (allow-list `messageId, reportRef, formCode, jurisdiction, employerRef, incidentDate, submittedAt, deadlineAt`); legal obligation – no consent, disclosure log with legal basis; authority API connectors are an extension point; form mapping pending Legal / HSE sign-off (E08-BL-003) |
| ERP / finance | out | cost centre, subsidiary, category, amount, currency (no names) | Increment 4 (E08): monthly export (CSV with SHA-256 checksum) and `claim.cost.export.v1` to `erp-finance` (allow-list `messageId, batchId, month, costCentre, subsidiaryCode, category, amount, currency, entryCount, checksum`); reconciliation against the ERP total (`RECONCILED` / `MISMATCH`); ERP transport adapter is an extension point |
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
| `oh-workspace` (increment 1) | React 19.3.1, same toolchain, same nginx image | OH clinical workspace (E01 intake, E02 triage, queues, SLA; increment 2 chart, encounters, fitness; increment 3 absence, surveillance, scheduling, reception kiosk, queue board, equipment and cold chain) | Static files on `:8080`; host `oh.<domain>`; `/bff/**` routed to BFF by ingress |
| `partner-portal` (increment 4) | React 19.3.1, same toolchain, same nginx image | External partner providers and TPA adjusters (referrals, reports, invoices, telehealth, claims, pre-authorisations, shared documents); MFA sign-in page | Static files on `:8080`; host `partners.<domain>` (the only host intended for users outside the airline); `/bff/**` routed to BFF by ingress |
| `wz-bff` | Spring Boot 4.1.2, Spring Security OAuth2 client, Spring Session JDBC; `eclipse-temurin:21.0.12_8-jre-noble` | OIDC login, session, CSRF, token relay | `:8090`, management `:8091` |
| `wz-core-app` | Spring Boot 4.1.2 modular monolith, Java 21 (virtual threads enabled) | All APIs, business logic, jobs (outbox relay, mailbox poll, notification dispatch, idempotency purge, SLA timer poller; increment 2: retention flagging, fitness daily job, result escalation; increment 3: absence feed / triggers / LTA, surveillance due list, crew certificates, pre-employment purge, appointment reminders and no-shows, equipment calibration; increment 4: treatment variance, referral chase, provider scorecards, claims report deadlines) | `:8080` API, `:8081` management (health, Prometheus) |
| Flyway migrations | Flyway via Spring Boot starter or `flyway/flyway:11.20.3-alpine` Job | Schema migration with owner role | Job / start-up |
| PostgreSQL 16 | Managed service or CloudNativePG (`postgresql:16.15`) | System of record | Private endpoint, TLS |
| Identity provider | Entra ID / Keycloak 26.8.1 (local) / Okta etc. | Authentication, MFA (mandatory for partner and adjuster users since increment 4), tokens | External |

### 5.2 Component view (wz-core-app)

![Component view](../images/03-component-view.png)

*Figure 3 – Components of the core API (source: `docs/images/03-component-view.svg`).*

| Maven module | Package root | Depends on | Key components |
|---|---|---|---|
| `wz-common` | `com.wellnessz.common` | – (no Spring Security, ArchUnit-enforced) | `ApiException`, `GlobalExceptionHandler` (RFC 9457), `DomainEvent`, `IntegrationEnvelope`, `DataClassification`, `DisclosureLevel`, `Roles`, `RoleClaims`, `CorrelationIdFilter`, `LogSanitizer`, UTC `Clock` |
| `wz-secrets` | `com.wellnessz.secrets` | common | `SecretsEnvironmentPostProcessor`, `SecretValueResolver`, `MasterKey`, `Hkdf`, `AesGcmCipher`, `FieldEncryptor`, `EncryptedStringConverter`, `EncryptCli` |
| `wz-security` (E16) | `com.wellnessz.security` | common | `ResourceServerSecurityConfig`, `WellnessZJwtConverter`, `CurrentUserProvider`, `PolicyEngine`, `AccessPolicy`, `DefaultPolicies`, `ConsentService`, `AuditService`, `SecurityEventAuditor` |
| `wz-masterdata` (E18) | `com.wellnessz.masterdata` | security | `OrgUnit`, `JobCode`, `Hazard`, `ConfigService`, `MasterDataController`; increment 1: `Employee` (HRIS master), `StaffMember` (OH staff roster, caseload caps, out-of-office cover), `EmployeeController` |
| `wz-integration` (E15) | `com.wellnessz.integration` | security, secrets | `EventBus`, `OutboxRelay`, `EventPublisher`, `OutboundPayloadGuard`, `IdempotentConsumer`, `IdempotencyService`, `FhirController`, `Hl7v2Controller`, `TerminologyService`, `ConnectorRegistry` |
| `wz-notification` | `com.wellnessz.notification` | common | `NotificationService`, `NotificationTemplates`, `ContentRules`, `QuietHours`, `NotificationChannel` |
| `wz-analytics` (E17) | `com.wellnessz.analytics` | security | `KpiService`, `SmallCellSuppression`, `Pseudonymizer`, `AnalyticsController` |
| `wz-case-kernel` | `com.wellnessz.casekernel` | masterdata, integration, security, secrets | `OhCase`, `CaseService`, `CaseViews`, `CaseModule`, `ModuleRegistry`; increment 1: `OpenCaseCommand`, `CaseRefGenerator`, `CaseTimeline`, `CaseWorkService` (tasks, links, attachments), `CaseAccess`, **`CaseTransitionGuard` SPI**; increment 3: seven SPIs (Section 21.1); increment 4: **`CaseCostRecorder`, `ExternalReferralGateway`, `ReferralStatusListener`, `ClaimContextProvider`** |
| `wz-ehs-intake` | `com.wellnessz.ehsintake` | integration, security, secrets | `ExcelIncidentParser`, `MailboxIngestionJob`, `ImapMailboxReader`, `GraphMailboxReader`, `IncidentIntakeService`; increment 1: `IncidentWebhookService`, `DeadLetterController` |
| `wz-e01-intake` (increment 1) | `com.wellnessz.e01` | case-kernel, ehs-intake, notification | `E01Module`, `IncidentIntakeListener`, `RuleEngine`, `IntakeRule`, `ReferralService`, `TemplateCatalog`, `EncounterService`, `TriggerService`, `DuplicateService`, `RecordabilityService` (a `CaseTransitionGuard`), `ReconciliationService`, `IntakeNotificationDispatcher`, **`RecipientResolver` SPI** |
| `wz-e02-triage` (increment 1) | `com.wellnessz.e02` | e01-intake (only for the `RecipientResolver` SPI and notification types) | `E02Module`, `ProtocolCatalog`, `ProtocolEvaluator`, `TriageService`, `SlaService`, `AssignmentEngine`, `AssignmentService`, `QueueService`, `EscalationService`, `ComplexityService`, `SafetyService` (a `CaseTransitionGuard`), `TriageAuditService`, `OhRecipientResolver` |
| `wz-documents` (increment 2) | `com.wellnessz.documents` | case-kernel, notification, Apache PDFBox | `DocumentService`, `DocumentTemplates`, `PdfRenderer`, `RetentionService`, `OcrProvider` / `TesseractOcrProvider` |
| `wz-e03-record` (increment 2) | `com.wellnessz.e03` | e02-triage, documents | `E03Module`, `ChartService`, `ClinicalEncounterService`, `TerminologyCatalog`, `OrderService`, `ResultService` (calls `DeviceCalibrationCheck` and `ResultFollowUpListener` since increment 3), `CdsService`, `FhirExportService` |
| `wz-e05-fitness` (increment 2) | `com.wellnessz.e05` | e02-triage, documents | `E05Module`, `FitnessDecisionService` (calls `FitnessClearanceGuard` since increment 3), `RestrictionService`, `MatchingEngine`, `RtwService`, `JobOfferService`, `CrewService`, `RosterFeedLog`, `FitnessChartContributor` |
| `wz-e06-absence` (increment 3) | `com.wellnessz.e06` | e05-fitness, documents | `E06Module`, `AbsenceFeedService` + `AbsenceFeedConnector` SPI, `RuleEngine` / `AbsenceTriggerEngine`, `AbsenceQueueService`, `CertificateService`, `LtaService` (calls `AbsenceSignalListener`, books through `AppointmentBooking`), `AdviceService`, `AbsenceAnalyticsService` / `UnitAggregator`, `AbsenceCapacityDemand`, `AbsenceJobs` |
| `wz-e07-surveillance` (increment 3) | `com.wellnessz.e07` | e03-record, e05-fitness, documents | `E07Module`, `ProgrammeService` / `EnrolmentEngine`, `DueService`, `ExposureService`, `FollowUpService` (a `ResultFollowUpListener`), `RaiService` (`AbsenceSignalListener` + `FitnessClearanceGuard`), `PreEmploymentService`, `CrewCertificateService`, `ImmunisationService` (`Gs1Lot`), `ComplianceService`, `AuditPackService`, `SurveillanceSpi`, `SurveillanceChartContributor`, `SurveillanceJobs` |
| `wz-e14-scheduling` (increment 3) | `com.wellnessz.e14` | e02-triage, documents | `E14Module`, `CatalogueService`, `SlotEngine` / `BookingRules`, `AppointmentService`, `AppointmentBookingAdapter` (`AppointmentBooking`), `StatusNotifier`, `ClinicQueueService`, `CampaignService` / `CampaignPlanner`, `EquipmentService` / `EquipmentRules`, `EquipmentCalibrationCheck` (`DeviceCalibrationCheck`), `CapacityService`, `SchedulingJobs` |
| `wz-e04-treatment` (increment 4) | `com.wellnessz.e04` | e03-record, e05-fitness, documents | `E04Module`, `PlanService` / `PlanMaths`, `EReferralService` / `ReferralCore` / `DataSetValidator` / `ChaseRules` / `FhirMapper` (implements `ExternalReferralGateway`), `PartnerReferralService`, `ProviderDirectoryService` / `ScorecardMaths`, `PreauthService` / `InvoiceMatcher` (calls `CaseCostRecorder`, `ClaimContextProvider`), `MedicationAdminService` / `MedicationChecks`, `EPrescribingGateway` (SPI, no-op default), `OutcomeService`, `OutcomeClosureGuard` (`CaseTransitionGuard`), `TelehealthService` / `TokenWindow` + `TelehealthProvider` SPI, `TreatmentChartContributor`, `TreatmentAppointmentListener`, `TreatmentJobs` |
| `wz-e08-claims` (increment 4) | `com.wellnessz.e08` | e05-fitness (RTW status), documents | `E08Module`, `ClaimService` / `ClaimWorkflow` (implements `ClaimContextProvider`), `NurseAssignmentService`, `ReportService` / `ReportDeadlines` + `ClaimsSubmissionGateway` SPI (`DefaultClaimsSubmissionGateway`), `DisclosureSetService` / `DisclosureRules` / `Redaction`, `CollaborationService`, `CostService` / `CostMath` / `ErpExport` (implements `CaseCostRecorder`), `LegalService` (implements `ReferralStatusListener` for `CLAIM_IME`), `ClaimsJobs`, `ClaimsDemoSeeder` (local / test only) |

ArchUnit rules (`apps/wz-core-app/src/test/java/com/wellnessz/app/ArchitectureTest.java`, 17 rules since increment 4):

1. Platform packages must not depend on `casekernel`, `ehsintake`, `documents` or any business module (`e01`–`e14`).
2. `casekernel` must not depend on any business module or on `documents` – modules plug into the kernel through SPIs
   (`CaseModule`, `CaseTransitionGuard`, `ChartContributor`, the increment-3 and increment-4 SPIs) and events, never
   the reverse.
3. `documents` must not depend on any business module (increment 2).
4. `e03` must not depend on `e05` (increment 2).
5. `e05` must not depend on `e03` (increment 2).
6. `e01` / `e02` must not depend on any later module (increments 2–4, including `e04` and `e08`).
7. `e03` / `e05` must not depend on `e06`, `e07` or `e14` – they call increment-3 behaviour only through
   `DeviceCalibrationCheck`, `ResultFollowUpListener` and `FitnessClearanceGuard` (increment 3).
8. `e03`, `e05`, `e06`, `e07`, `e14` must not depend on `e04` or `e08` – increment-4 modules plug in through
   `ExternalReferralGateway`, `CaseCostRecorder` and `ClaimContextProvider` (increment 4).
9. `e04` must not depend on `e08`, `e06`, `e07` or `e14` – it posts costs through `CaseCostRecorder` and reads the
   claim context through `ClaimContextProvider` (increment 4).
10. `e08` must not depend on `e04`, `e03`, `e06`, `e07` or `e14` – **claims never read the clinical record**; IMEs go
    through `ExternalReferralGateway` (increment 4).
11. `e06` must not depend on `e07` or `e14` (increment 3).
12. `e07` must not depend on `e06` or `e14` (increment 3).
13. `e14` must not depend on `e03`, `e05`, `e06` or `e07` – scheduling never reads clinical content (increment 3).
14. `e01` must not depend on `e02` – E02 depends on E01 through the `RecipientResolver` SPI; the reverse would create
    a cycle (increment 1).
15. `ehsintake` must not depend on `casekernel` – the two communicate only via `ehs.incident.received.v1`.
16. `common` must not depend on Spring Security.
17. No code outside `com.wellnessz.secrets` (the CLI) may use `System.out`/`System.err`.

### 5.3 Deployment view

![Deployment reference](../images/07-deployment-reference.png)

*Figure 4 – Deployment reference (source: `docs/images/07-deployment-reference.svg`). The deployment assets are
maintained under `deploy/` and are still being finalised; this view reflects their current content.*

## 6. Data architecture

### 6.1 Schemas and stores

All schemas live in one PostgreSQL 16 database; the BFF uses its own schema and migration history.

| Schema | Tables / views (migration) | Content | Classification (highest) |
|---|---|---|---|
| `platform` | `audit_event`, `consent`, `outbox_event`, `processed_message`, `idempotency_record`, `inbound_message`, `code_concept`, `notification` (V1) | Cross-cutting platform state | CLINICAL (inbound and outbox payloads field-encrypted) |
| `masterdata` | `org_unit`, `job_code`, `hazard`, `config_entry` (V2); `employee` (HRIS master), `staff_member` (OH staff roster) (V3); fitness standards, restriction catalogue, job-demand profiles (V6); `clinic_site` (V9); `partner_organisation` (V13) | Organisation, job/hazard catalogue, configuration, employee and OH staff master data, clinic sites, partner organisations (providers, insurers, TPA, authorities) | CONFIDENTIAL (`employee`), otherwise INTERNAL |
| `intake` | `import_batch`, `ehs_incident` (V2); `incident_dlq`, `intake_rule`, `incident_evaluation`, `encounter`, `absence_episode`, `trigger_event`, `recordability_assessment`, `recordability_mapping`, `notification_rule` (V4) | EHS incidents, import provenance, E01 rules, encounters, triggers, recordability | CLINICAL (`description`, DLQ payload, encounter clinical fields, recordability rationale encrypted) |
| `casemgmt` | `oh_case`, sequence `case_ref_seq` (V2); `case_sequence`, `case_link`, `case_event`, `case_task`, `case_attachment` (V3) | Case aggregate, timeline, tasks, links, attachments | CLINICAL / RESTRICTED_CLINICAL (`clinical_summary` and attachment content encrypted) |
| `triage` (increment 1) | `work_queue`, `sla_policy`, `sla_timer`, `assignment_rule`, `case_assignment`, `triage_protocol`, `triage_assessment`, `escalation`, `case_conference`, `case_risk_flags`, `complexity_assessment`, `duration_guideline`, `case_checklist`, `handover`, `saved_view`, `triage_audit_sample`, `case_advice` (V5) | E02 triage, queues, SLA, assignment, escalation | CLINICAL (triage answers and notes, escalation notes, conference minutes, handover notes, checklist answers encrypted) |
| `docs` (increment 2) | `document`, `document_share`, `document_sequence`, `retention_schedule` (V6) | Generated and scanned documents, shares, retention schedule | CLINICAL / RESTRICTED_CLINICAL (content, merge fields and extracted text encrypted; `search_vector` holds keyed tokens only) |
| `ohrecord` (increment 2) | `problem`, `allergy`, `medication`, `immunisation`, `job_history`, `encounter`, `encounter_diagnosis`, `encounter_observation`, `encounter_addendum`, `encounter_photo`, `encounter_template`, `lab_order`, `order_sequence`, `result`, `drug_class`, `cds_rule`, `cds_alert_log`, `term_favourite`, `term_usage`, `term_mapping` (V7) | E03 OH record, encounters, orders, results, CDS, terminology preferences | CLINICAL / RESTRICTED_CLINICAL (mental-health encounters and restricted result codes) |
| `fitness` (increment 2) | `fitness_decision`, `restriction_instance`, `duty_proposal`, `rtw_plan`, `rtw_review`, `job_offer`, `crew_profile`, `roster_feed_message`, `food_handler_episode`, `ihr_assessment` (V8) | E05 decisions, restrictions, duties, RTW, job offers, crew feed log, food handlers, IHR | CLINICAL (rationale and clinical notes); status / functional restriction columns are CONFIDENTIAL |
| `absence` (increment 3) | `feed_run`, `episode`, `feed_mismatch`, `trigger_rule`, `trigger_rule_version`, `queue_item`, `contact`, `certificate`, `certificate_call`, `lta_pathway`, `lta_milestone`, `case_conference`, `advice_request`, `advice_report`, sequence `advice_ref_seq` (V10) | E06 absence episodes and feed reconciliation, trigger rules and queue, certificates, LTA pathway, advice requests and reports | CLINICAL (contact, milestone and conference notes, certificate extraction and validation notes, advice context and decline reason encrypted); episodes with dates, type and reason category are CONFIDENTIAL and never shown to managers with the reason |
| `surveillance` (increment 3) | `programme`, `employee_profile`, `enrolment`, `completion`, `invitation`, `pre_employment`, `crew_certificate`, `immunisation_requirement`, `vaccine_administration`, `titre`, `immunisation_reminder`, `exposure_group`, `ih_measurement`, `cosmic_dose`, `pregnancy_declaration`, `evaluation_rule`, `follow_up`, `return_after_illness`, `compliance_snapshot`, `audit_pack` (V11) | E07 programmes and enrolments, invitations, pre-employment, crew certificates, immunisations, exposure registers, follow-ups, return after illness, compliance and audit packs | CLINICAL (pre-employment questionnaire and conditions, crew certificate answers, follow-up and return-after-illness notes, exemption reasons encrypted); `employee_profile` holds date of birth and licence types (CONFIDENTIAL) for age-based frequencies |
| `scheduling` (increment 3) | `appointment_type`, `resource`, `diary_template`, `campaign`, `appointment`, `campaign_slot`, `waitlist_entry`, `equipment`, `calibration`, `temperature_reading`, `excursion` (V12) | E14 service catalogue, resources and diaries, appointments, campaigns, waitlist, equipment register, calibration certificates (`bytea` + SHA-256), cold-chain readings and excursions | CONFIDENTIAL (who has which appointment type when; no clinical content by design); equipment and cold-chain data INTERNAL |
| `treatment` (increment 4) | `ref_sequence`, `plan_template`, `treatment_plan`, `follow_up`, `variance_alert`, `service_catalogue`, `referral`, `referral_history`, `provider_profile`, `provider_contract`, `provider_scorecard`, `preauth`, `invoice`, `formulary_item`, `stock_batch`, `administration`, `outcome`, `telehealth_session`, `telehealth_token`, `telehealth_message` (V14) | E04 treatment plans and follow-ups, variance alerts, e-referrals and history, provider profiles / contracts / scorecards, pre-authorisations, invoices, clinic formulary, stock and administrations, outcomes, telehealth sessions, tokens and chat | CLINICAL (plan goals and interventions, referral reason, question, data set, report summary and FHIR payload, pre-auth justification, administration dose and allergy override, outcome and follow-up notes, chat text encrypted); telehealth participant tokens stored as hashes only; provider contracts and rates INTERNAL |
| `claims` (increment 4) | `ref_counter`, `claim`, `claim_history`, `report_form`, `first_report`, `disclosure_set`, `disclosure_set_document`, `claim_task`, `claim_message`, `message_read`, `cost_entry`, `budget`, `cost_export`, `legal_request`, `legal_report` (V15) | E08 claims and lifecycle history, report forms and first reports, disclosure sets, adjuster tasks and messages, cost ledger, budgets, ERP exports, legal requests and medico-legal report versions | CONFIDENTIAL (claim status, payer, amounts); CLINICAL for encrypted free text (claim notes, first-report fields, messages, legal questions); medico-legal reports live in `docs` and are restricted to the legal access set |
| `analytics` | view `v_case_facts` (V2) | De-identified case facts | INTERNAL (aggregate) |
| `bff` | `spring_session`, `spring_session_attributes` (`db/bff/V1`) | Browser sessions including OAuth2 tokens | CONFIDENTIAL (attributes encrypted) |

Increment 1 adds migrations V3 (kernel and master data), V4 (E01) and V5 (E02, schema `triage`). Synthetic demo people
(`db/demo/R__demo_reference_people.sql`, a repeatable migration) are loaded **only** by the `local` and `test` profiles,
which add `classpath:db/demo` to `spring.flyway.locations`; every other environment uses `WZ_FLYWAY_LOCATIONS`
(default `classpath:db/migration`) and the Helm migration Job reads `db/migration` only. The schema list is maintained
in `spring.flyway.schemas`, the Helm value `migrations.job.core.schemas`, `deploy/local/postgres/01-init-wellnessz.sh`,
`deploy/sql/bootstrap-managed-postgres.sql` and `deploy/hybrid/cnpg/03-cluster.yaml`.

Increment 2 adds migrations V6 (platform: master data for fitness standards, restriction catalogue and job-demand
profiles; `platform.break_glass_grant`, `platform.disclosure_log`, `platform.scheduled_job_run`; schema `docs`; primary
coded diagnosis on `casemgmt.oh_case`), V7 (schema `ohrecord`, terminology starter content and
`platform.terminology_release`) and V8 (schema `fitness`). Demo data for the `local` and `test` profiles only:
`db/demo/R__demo_e03_record.sql` (OH chart of demo employee `E1004`) and `db/demo/R__demo_e05_fitness.sql` (crew
fleet / base). The schema list in all five places named above then read
`platform, masterdata, intake, casemgmt, triage, docs, ohrecord, fitness, analytics` (extended by increment 3, below). The runtime role receives no
`DELETE` on `docs.document`.

Increment 3 adds migrations V9 (platform: `masterdata.clinic_site` with six seeded sites, nine exposure hazards and five
job codes for the surveillance programmes, retention classes `ABSENCE_RECORD` and `PRE_EMPLOYMENT`), V10 (schema
`absence`), V11 (schema `surveillance`) and V12 (schema `scheduling`). Demo data for the `local` and `test` profiles
only: `R__demo_e06_absence.sql`, `R__demo_e07_surveillance.sql`, `R__demo_e14_scheduling.sql` and five more HRIS
employees (`E1006`–`E1010`) in `R__demo_reference_people.sql`; encrypted columns are left empty in demo data because
field keys differ per environment. The schema list in all five places named above now reads
`platform, masterdata, intake, casemgmt, triage, docs, ohrecord, fitness, absence, surveillance, scheduling, analytics`.
The runtime role has no `DELETE` on clinical tables in the new schemas (corrections are state changes); exceptions are
technical rows (`surveillance.immunisation_reminder`, `surveillance.compliance_snapshot`, `scheduling.diary_template`).

Increment 4 adds migrations V13 (platform: `masterdata.partner_organisation` with 25 seeded organisations – 20
providers including hub, outstation and layover-city partners and a travel-assistance partner, the payers `GULFTPA`
(TPA), `AVIA_INSURE` and `GROUPMED` (insurers) and the authorities `AUTH_OSH` and `AUTH_LABOUR_AE` – and the retention
class `CLAIM_RECORD`), V14 (schema `treatment`, service catalogue, plan templates, provider profiles, contracts and the
clinic formulary) and V15 (schema `claims`, report forms). Each module migration starts with
`create schema if not exists`. Demo data for the `local` and `test` profiles only: `R__demo_e04_treatment.sql`,
`R__demo_e08_claims.sql` and `ClaimsDemoSeeder` (a `local` / `test` bean that fills the encrypted demo fields of E08,
which SQL cannot do). The schema list in all five places named above now reads
`platform, masterdata, intake, casemgmt, triage, docs, ohrecord, fitness, absence, surveillance, scheduling, treatment,
claims, analytics`.

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
| JPA integration | `@Convert(converter = EncryptedStringConverter.class)` – used today on `oh_case.clinical_summary`, `ehs_incident.description`, `inbound_message.payload`, `outbox_event.payload`; verified by integration tests (raw column starts with `v1:` and does not contain the plaintext, for case summaries and outbox payloads). |
| Blind index | `FieldEncryptor.blindIndex()` – keyed HMAC-SHA256 for equality search over encrypted columns. Available; no column uses it yet. Increment 2: used for document full-text search – each word becomes a keyed token in `docs.document.search_vector` (Section 14.17). |

**Key-rotation properties (important for GRC).** Changing the *active version* rotates the data-encryption sub-key for
new writes without downtime. Because all versions are derived from the same master key, this does **not** protect
against compromise of the master key itself. Rotating the master key requires re-encrypting existing values; the
re-encryption job referred to in `FieldEncryptor`'s documentation is **planned, not implemented**. The blind-index key
is not versioned. Recommendation (decision D5): adopt envelope encryption with the master key wrapped by Azure Key
Vault / Cloud KMS / Vault Transit, and deliver the re-encryption job before go-live.

### 6.4 Data that is not field-encrypted

To set expectations precisely: the following columns rely on storage encryption and access control only.
(`outbox_event.payload` is field-encrypted and delivered rows are purged – see 7.1.)

| Column(s) | Content | Mitigation / recommendation |
|---|---|---|
| `oh_case.employee_id`, `employee_display_name`, `org_unit_*`, `category`, `status`, `fitness_status` | Identifiers and status needed for queries and status-only views | ABAC on every read; `category` reveals the restricted class – consider encrypting category for RESTRICTED rows in E09/E10 |
| `ehs_incident.employee_name`, `location`, `incident_type`, `severity`, `injury` | Incident metadata | Description (free text) is encrypted and not exposed in list API |
| `idempotency_record.response_body` | Stored API responses | Purged after 7 days by `IdempotencyService.purge()` |
| `notification.address`, `body` | Recipient address and content-free message | Body is content-free by construction |
| `audit_event` | Actor, action, resource id, reason, client IP | Reasons are sanitised; must not contain PHI by coding standard |
| `absence.episode` (increment 3) | Employee, dates, absence type, reason category, certified flag | ABAC (status-only for managers / HR within org scope); managers never receive `reasonCategory`; only sickness and injury episodes (`SICKNESS`, `INJURY_WORK`, `INJURY_NON_WORK`) feed the trigger rules |
| `scheduling.appointment` (increment 3) | Employee, appointment type, site, time, status, check-in ticket | Visible to OH, schedulers and reception (no clinical read); queue board shows tickets, not names |
| `surveillance.employee_profile` (increment 3) | Date of birth, licence types | OH roles only; used for age-based examination frequencies (e.g. Class 1 every 6 months from age 40) |
| `scheduling.calibration.document_content` (increment 3) | Calibration certificate (vendor PDF) | Not personal data; integrity by stored SHA-256 |
| `treatment.referral` status columns, `treatment.preauth` / `invoice` amounts (increment 4) | Service, provider, urgency, dates, status; estimate, approved and invoiced amounts | OH roles and claims coordinators only; partners see their own organisation's referrals (404 otherwise); adjusters see their payer's pre-authorisations without justification |
| `claims.claim`, `claims.cost_entry`, `claims.cost_export` (increment 4) | Claim type, status, payer, reserves, cost ledger, ERP export rows | Money only for CLAIMS_COORDINATOR, FINANCE, CMO; finance views aggregate by unit with suppression; export rows carry cost centre and category, no names |

### 6.5 De-identified analytics view

`analytics.v_case_facts` exposes only `org_unit_code`, `category`, `source`, `status`, `fitness_status` and the month of
opening; it has no identifiers, names or free text and excludes `RESTRICTED_CLINICAL` cases entirely. The runtime role
has `SELECT` on the view only within the `analytics` schema (it still has table access in `casemgmt`, so a separate
read-only reporting role is recommended for warehouse extraction).

### 6.6 Retention and deletion – to be defined for OH records

The code implements purges only for technical data: delivered outbox events after `wellnessz.integration.outbox.retention`
(default `P30D`, `OutboxRelay.purgeDelivered`, cron `wellnessz.integration.outbox.purge-cron`, default 02:30 daily),
idempotency records after 7 days, and Spring Session expiry. There are **no retention or deletion jobs for OH records**
(cases, incidents, consents, audit) yet. Retention periods for OH records are governed by national occupational-health and
aviation-medicine rules (often decades for exposure records) and must be set by the Data Protection Officer and OH
governance. Proposed approach (decision D6): a retention schedule per `DataClassification` and record type in E18
configuration, a scheduled purge/anonymisation job per module, legal-hold flags, and audit events for every purge.
Audit records must be retained at least as long as the records they describe.

**Increment 2 update.** Documents now carry a retention schedule and legal hold (`platform/wz-documents`,
`RetentionService`): `docs.retention_schedule` holds one rule per record class (`OH_RECORD` 40 years from the last
encounter, `EXPOSURE_RECORD` 40, `AEROMEDICAL_RECORD` 10, `FITNESS_CERTIFICATE` 6, `CORRESPONDENCE` 6, `PHOTO` 6,
`EXTERNAL_REPORT` 10 years – **proposals pending DPO / CMO approval**), editable only by the DPO (audited
`RETENTION_SCHEDULE_APPROVED`). A daily job (`wellnessz.documents.retention-cron`, default `0 30 2 * * *` UTC, once per
day across replicas through `JobRunGuard`) lists documents past retention and not on legal hold for DPO review
(`GET /documents/retention/due`). **Nothing is deleted automatically**: destruction remains a reviewed, audited
decision outside the job, and the runtime role has no `DELETE` grant on documents. Triggers other than the document
date (`LAST_ENCOUNTER`, `EMPLOYMENT_END`) need HRIS employment end dates. Retention of the structured E03/E05 tables
(chart, encounters, results, decisions) and of cases is still open (decision D6, open item O12).

**Increment 3 update.** Two proposed retention classes were added (pending DPO / CMO approval): `ABSENCE_RECORD`
6 years from the document date (sick certificates, OH advice reports – limitation period of employment claims) and
`PRE_EMPLOYMENT` 1 year (health data of candidates who are not hired; hired candidates move to `OH_RECORD`). E07
implements the **first automatic purge** of personal data: the nightly `pre-employment-purge` job
(`wellnessz.surveillance.purge-job-cron`, default `0 10 3 * * *` UTC, once across replicas) anonymises pre-employment
records whose 12-month retention has passed – name replaced, e-mail, questionnaire, conditions, documents and
government reference cleared – and audits each purge (`PRE_EMPLOYMENT_PURGE`). Everything else in the new schemas
follows the document approach (flagging, legal hold, no automatic deletion).

**Increment 4 update.** One proposed retention class was added (pending DPO approval): `CLAIM_RECORD` 10 years from
the document date (claim, pre-authorisation, first-report and medico-legal records – limitation period for injury
claims plus margin). A **litigation hold** set by `LEGAL_COUNSEL` on a claim calls `DocumentService.legalHoldForCase`,
which puts every document of the case on legal hold, so the retention job no longer lists them; releasing the hold
releases them again. Medico-legal reports stay in the legal access set (legal counsel and physicians). No new
automatic purge.

## 7. Integration architecture

![Data flow EHS intake to status-only feed](../images/04-dataflow-ehs-intake.png)

*Figure 5 – EHS incident → OH case → status-only outputs (source: `docs/images/04-dataflow-ehs-intake.svg`).*

### 7.1 Transactional outbox event bus

| Concern | Implementation |
|---|---|
| Publishing | `EventBus.publish(event[, destination])` requires an active transaction (`Propagation.MANDATORY`) and inserts into `platform.outbox_event` in the **same transaction** as the business change – no dual-write problem. |
| Envelope | `IntegrationEnvelope(messageId, eventType, aggregateType, aggregateId, payload, headers{correlationId}, occurredAt)`; event types are versioned (`ehs.incident.received.v1`, `case.opened.v1`, `fitness.status.changed.v1`, `integration.inbound.received.v1`; increment 1: `case.assigned.v1`, `case.triaged.v1`, `case.escalated.v1`, `sla.at-risk.v1`, `sla.breached.v1`; increments 2–4: Sections 20.1, 21.1 and 22.1). |
| Relay | `OutboxRelay` every `PT2S` on every replica; claims due rows with `FOR UPDATE SKIP LOCKED` (batch 50) so replicas never deliver the same row concurrently. |
| Delivery semantics | At-least-once. Failures increment `attempts`, back-off `2^attempts` seconds capped at one hour; after `max-attempts` (10) the row becomes `DEAD`. |
| Destinations | `internal` → `InProcessEventPublisher` (Spring application event); any other destination → an `EventPublisher` adapter. Today `LoggingExternalPublisher` (metadata only) stands in. Kafka / Azure Service Bus / Google Pub/Sub adapters are **extension points**. |
| Idempotent consumers | `IdempotentConsumer.handleOnce(consumer, envelope, handler)` inserts `(consumer, messageId)` into `platform.processed_message` in a new transaction together with the handler's effects; duplicates are skipped. |
| Protection at rest & retention | `payload` is field-encrypted with `EncryptedStringConverter` (AES-256-GCM); `PUBLISHED` rows are deleted after `wellnessz.integration.outbox.retention` (default 30 days) by `OutboxRelay.purgeDelivered`; `FAILED`/`DEAD` rows are kept for diagnosis and replay. |
| Monitoring & replay | `GET /api/v1/integration/events?status=DEAD` (metadata only, never payloads); `POST /api/v1/integration/events/{id}/replay` (PLATFORM_ADMIN/OH_ADMIN, audited `EVENT_REPLAY`). Replay re-sends the same `messageId`, so consumers that already processed the message ignore it – replay is intended for `FAILED`/`DEAD` events. |

### 7.2 Inbound APIs

| Endpoint | Behaviour |
|---|---|
| `POST /api/v1/integration/inbound/{source}` | JSON only; **requires** `Idempotency-Key` (8–200 chars, `[A-Za-z0-9._:-]`). First request executes; retry with same key and payload returns the stored response with `Idempotent-Replayed: true`; same key with different payload → `422`. Advisory lock prevents concurrent first-execution. Records purged after 7 days. |
| `POST /api/v1/integration/fhir/{resourceType}` | `application/fhir+json` only (no XML parser – no XXE surface); allow-list: Patient, Observation, DiagnosticReport, ServiceRequest, Condition, Encounter, Immunization, DocumentReference, Bundle, Appointment, Claim; strict parser; URL type must match body type; responds with `OperationOutcome` and `Location: urn:uuid:<id>`. |
| `GET /api/v1/integration/fhir/metadata` | FHIR 4.0.1 CapabilityStatement listing the supported `create` interactions. |
| `POST /api/v1/intake/ehs/incidents` (increment 1) | EHS webhook. Role `INTEGRATION`; **requires** `Idempotency-Key` (same `IdempotencyService` as above, scope `ehs-webhook`); body is one incident or `{"incidents":[…]}` (≤ 500). Each item is validated against an allow-listed schema (identifier regexes, known `incidentClass` and `severity` values, ISO-8601 instants, length caps); invalid items are stored encrypted in `intake.incident_dlq`, a WARN alert is logged and `EHS_WEBHOOK_DLQ` is audited; valid items follow the common de-duplicating ingestion path. Answers `202` with `{accepted, duplicates, deadLettered, batchId}`. Operators list, replay or discard dead letters at `/api/v1/intake/dlq` (OH_ADMIN, PLATFORM_ADMIN; audited). |
| `POST /api/v1/intake/absence-episodes`, `/intake/triggers/surveillance-result`, `/intake/triggers/da-test` (increment 1) | System triggers for E01 (role `INTEGRATION`). |
| `POST /api/v1/integration/hl7v2` | ER7 over HTTPS; HAPI default validation; `MSA\|AA` on success, `AR` + HTTP 400 when unparseable. |
| `POST /api/v1/integration/absence/episodes`, `/integration/absence/certificates` (increment 3) | E06 absence feed push (≤ 5,000 rows per batch, `sourceSystem` `HRIS` / `TNA` / `CREW` / `MANUAL`, allow-listed absence types and reason categories) and certificate metadata from source systems; unmatched employees go to the reconciliation list (`absence.feed_mismatch`). Role `INTEGRATION`. |
| `POST /api/v1/integration/ih-measurements`, `/integration/cosmic-dose`, `/integration/pre-employment` (increment 3) | E07 industrial-hygiene measurements per exposure group (action / limit values evaluated), annual cosmic dose per crew member (AME review above `surveillance.cosmic-dose-alert-msv`), new pre-employment assessments from recruiting. Role `INTEGRATION`. |
| `POST /api/v1/integration/cold-chain/readings` (increment 3) | E14 IoT fridge readings (≤ 5,000 per call, device id pattern, −90…60 °C, timestamp); unknown devices are reported back, out-of-range readings open or extend an excursion. Role `INTEGRATION`. |
| `GET /api/v1/integration/referrals/{id}/fhir`, `POST /integration/referrals/fhir/document-reference` (increment 4) | E04 FHIR R4 partner exchange: the partner hospital system reads the referral as a `ServiceRequest` and returns its report as a `DocumentReference` (`context.related` = ServiceRequest id, base64 attachment) → stored as `PROVIDER_REPORT`, referral `REPORT_RECEIVED`. Role `INTEGRATION`. |
| `POST /api/v1/integration/preauthorisations/{ref}/decision`, `/integration/claims/reports/{ref}/acknowledgement` (increment 4) | Insurer / TPA systems decide a pre-authorisation (same body as the adjuster in the portal) and acknowledge or reject a submitted first report. Role `INTEGRATION`. |
| `POST /api/v1/integration/telehealth/tokens/redeem` (increment 4) | The video platform redeems a single-use participant token (hash compared, window 15 minutes before start to the end) and learns the session reference and role – no clinical content. Role `INTEGRATION`. |

All inbound messages are stored encrypted in `platform.inbound_message` with the authenticated client as `received_by`
and audited (`INBOUND_<format>`). FHIR profile validation (e.g. ODH profiles), patient matching and mapping to clinical
records are **planned** with E03/E07.

### 7.3 Outbound disclosure allow-list guard

`OutboundPayloadGuard` is invoked by `EventBus.publish(event, destination)` before the outbox row is written. For each
external destination a list of permitted top-level JSON fields is configured; any other field aborts the business
transaction (`disclosure_violation`), and a destination without a list is refused (`destination_not_allowed`).

```yaml
wellnessz.integration.outbound-allowlist:          # as shipped after increment 4
  crew-rostering:   [messageId, employeeId, fitnessStatus, rosterStatus, restrictionUntil, validFrom, validUntil,
                     operationalLimitations, effectiveFrom]                                  # roster feed: increment 2
  hr-systems:       [caseRef, employeeId, fitnessStatus, restrictionUntil, effectiveFrom,
                     messageId, offerId, accepted, respondedAt,                              # job offers: increment 2
                     offerRef, candidateId, preEmploymentStatus, ohEngaged, certificateValidity]  # increment 3
  catering-hygiene: [caseRef, employeeId, fitnessStatus, restrictionUntil, effectiveFrom,
                     foodHandlerStatus, excludedFrom, expectedReturn]                       # increments 1–2
  # increment 4 (E08): references only – documents travel through consent-checked shares
  insurer-gateway:   [messageId, claimRef, reportRef, formCode, payerCode, employeeId, incidentDate, submittedAt, deadlineAt]
  authority-gateway: [messageId, reportRef, formCode, jurisdiction, employerRef, incidentDate, submittedAt, deadlineAt]
  erp-finance:       [messageId, batchId, month, costCentre, subsidiaryCode, category, amount, currency, entryCount, checksum]
```

The integration test `fitnessOutcomeIsPublishedToRosteringAsStatusOnly` verifies that a fitness decision produces a
rostering payload without the clinical narrative or case category. The guard also **rejects nested objects and
arrays**: payloads to enterprise destinations must be flat records, so every transmitted field is visible to the
allow-list check. Values are not content-inspected – the allow-list defines *which* fields may leave, and each new
destination's allow-list is a change subject to privacy review.

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
destination)` → allow-list → outbox → broker adapter. The broker choice is decision D3 (Section 14.4).

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
| Microsoft Entra ID | Workforce (employees, crew, managers, OH staff); increment 4: partner and adjuster users as B2B guests | App roles in `roles`; security groups in `groups` mapped by object id; v2.0 issuer `https://login.microsoftonline.com/<tenant>/v2.0`. Conditional Access and MFA are configured in Entra ID; for guests a Conditional Access policy with an authentication strength is mandatory (Section 14.30). |
| Keycloak | Local development (realm import in `deploy/local/keycloak`), optionally a partner realm | Realm roles in `realm_access.roles`; MFA through the OTP (`CONFIGURE_TOTP`) or WebAuthn required action and a step-up flow that sets `acr` (Section 14.30). |
| Okta, Ping, Google, others | Alternatives | Any claim path configurable. |

### 8.2 Role claim mapping

`RoleClaims.extract` reads the configured claim paths (default `realm_access.roles`, `roles`, `groups`), maps raw values
through `wellnessz.security.group-role-mapping` (used for **Entra ID group GUIDs**, e.g.
`"6f1c…": OH_CLINICIAN`), normalises to upper case, and accepts only names matching `[A-Z][A-Z0-9_]{0,63}` (defence
against claim injection). Roles become `ROLE_<NAME>` authorities. Employee id and org scope come from configurable
claims (`employee_id`, `org_scope`), which the IdP must populate (e.g. Entra ID directory extension or claims mapping
policy fed from HRIS).

Canonical roles (`wz-common Roles`): `EMPLOYEE`, `MANAGER`, `HR`, `OH_CLINICIAN`, `OH_ADMIN`, `CASE_MANAGER`,
`PSYCH_CLINICIAN`, `AUDITOR`, `PLATFORM_ADMIN`, `INTEGRATION`, `PARTNER_PROVIDER`; increment 1 adds `OH_PHYSICIAN`,
`AME`, `CMO`, `NURSING_HEAD`, `HSE_OFFICER`, `CREW_SCHEDULING`, `HYGIENE_MANAGER`, `CLINIC_RECEPTION` and
`FIRST_AIDER`; increment 2 adds `DPO`; increment 3 adds `CLINIC_SCHEDULER` (diaries, bookings, campaigns, waitlists –
demographics and appointment type only), `BIOMEDICAL` (equipment register, calibration, cold-chain logs),
`CREW_LICENSING` (crew medical certificate validity, status only) and `CANDIDATE` (pre-employment candidate who is not
yet in the HRIS master – own questionnaire, consent, documents and booking only); increment 4 adds
`CLAIMS_COORDINATOR` (claims, e-reporting, costs, disclosure sets, pre-authorisation follow-up – no clinical record),
`TPA_ADJUSTER` (external adjuster of the payer organisation, partner portal only), `LEGAL_COUNSEL` (litigation hold,
privilege, medico-legal requests), `FINANCE` (cost roll-ups, budgets, ERP export – money by unit, never names) and
`PROCUREMENT` (provider directory, contracts, rates, SLAs), and gives the existing `PARTNER_PROVIDER` its first
access – 30 roles in total. `Roles.OH_TEAM` groups the clinical roles (`OH_CLINICIAN`, `OH_PHYSICIAN`, `AME`, `CMO`, `NURSING_HEAD`,
`CASE_MANAGER`, `PSYCH_CLINICIAN`); `Roles.FITNESS_DECIDERS` lists who may record a fitness decision. The role
responsibilities and segregation of duties are described in document 02, Sections 11, 12.1, 13.1 and 14.1.

**MFA gate for external roles (increment 4).** `WellnessZJwtConverter` drops every role listed in
`wellnessz.security.mfa-required-roles` (env `WZ_MFA_REQUIRED_ROLES`, default `PARTNER_PROVIDER,TPA_ADJUSTER`) unless
the token proves multi-factor authentication: an `amr` value in `wellnessz.security.mfa-amr-values` (default `mfa`,
`otp`, `totp`, `hwk`, `swk`, `sms`, `webauthn`, `fido` – RFC 8176 plus common IdP values) or an `acr` value in
`wellnessz.security.mfa-acr-values` (default `2`, `3`, `mfa`, `c1`). A partner who signed in with a password only
therefore holds no partner role, `GET /api/v1/me` reports no partner role and the portal shows its *multi-factor
sign-in required* page; every partner endpoint answers `403`. The gate is on by default and switched off only in the
`local` profile (the demo realm has no OTP enrolment; set `WZ_MFA_REQUIRED_ROLES=PARTNER_PROVIDER,TPA_ADJUSTER` to
rehearse it). The IdP enforces MFA; the gate makes the API independent of a misconfigured IdP policy. Unit test:
`MfaRoleGateTest`. How each IdP produces the claim is described in Section 14.30.

> The BFF maps roles from the ID and access token for **UI gating** (session roles and the `/bff/api/workspace/**`
> gate), applying the same group mapping as the core API through `wellnessz.bff.group-role-mapping` (Entra group object
> ids → roles). Authoritative decisions are always taken by the core API.

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
| `CLINICAL` | standard OH clinical access | `clinicianPolicy`: OH_CLINICIAN / CASE_MANAGER and, since increment 1, OH_PHYSICIAN / AME / CMO / NURSING_HEAD; not restricted class |
| `RESTRICTED_CLINICAL` | includes psychological health / substance testing | `clinicianPolicy`: PSYCH_CLINICIAN |
| (deny) | restricted class for anyone else | `restrictedClassPolicy` (deny policy): RESTRICTED_CLINICAL unless PSYCH_CLINICIAN, data subject, or MANAGER/HR (who still only get status) |

`CaseService` applies the decision on **every** read and returns a projection chosen by the disclosure level:
`CaseViews.StatusOnly` (no clinical fields exist in the type) or `CaseViews.Clinical`. Denied reads return **404**, not
403, so that existence is not disclosed; every read, list and denial is audited. Integration tests verify: manager in
scope receives status-only without the narrative; manager outside scope receives 404; general clinician cannot see a
psychological case; psychological-health clinician can.

Increment 3 adds no new disclosure level and no new ABAC policy: the four new roles receive **no clinical
disclosure**. `GET /api/v1/me` reports `STATUS_ONLY` for `CREW_LICENSING` and `CLINIC_SCHEDULER` and `SELF` for
`CANDIDATE`; `BIOMEDICAL` has `NONE`. What each role may call is fixed per endpoint with `@PreAuthorize` (for example
`E14Roles`, E07 `Spel`), and the responses for these roles are projection types without clinical fields (appointment
type instead of reason, certificate validity instead of findings).

Increment 4 again adds no disclosure level. `GET /api/v1/me` reports `STATUS_ONLY` for `CLAIMS_COORDINATOR`,
`TPA_ADJUSTER`, `LEGAL_COUNSEL`, `FINANCE`, `PROCUREMENT` and `PARTNER_PROVIDER`. External users are isolated by
**organisation**: the organisation code is the last segment of `org_scope` (`/PARTNERS/CITYHOSP` → `CITYHOSP`); a partner
sees only referrals, telehealth sessions and shares addressed to that organisation, an adjuster only claims and
pre-authorisations where the organisation is the payer – anything else answers `404`. Claims coordinators, finance,
legal and procurement have no clinical read (endpoint-level `@PreAuthorize`, projection types, ArchUnit rule 10:
E08 cannot even reference the E03 record); clinical free text entered in claims, tasks, messages and first reports is
screened by `ClinicalContentException.screen` (`422 clinical_content_not_allowed`).

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
*Since increment 2* the document service calls the gate for partner sharing. *Increment 3* adds two employee
decisions that are recorded in the module rather than in `platform.consent`: the release decision on an OH advice
report (the employee consents to or declines its release to the requester) and the candidate's pre-employment
consent (questionnaire processing and the purposes listed in the portal). Both are audited.
*Increment 4* makes consent the gate for every external flow: e-referrals and the partner shares of letters and
attachments require an active `TREATMENT_SHARING_PARTNER` consent; pre-authorisation requests, insurer first reports
and disclosure sets require an active `INSURER_SHARING` consent (the new share target `INSURER`, disclosure purpose
`CLAIM`); joining a telehealth session requires `TELEHEALTH_CONSULTATION` (new purpose) – each answers
`422 consent_required` otherwise. Statutory reports to an authority are a legal obligation: no consent, but a
disclosure-log entry with the legal basis. The UIs offer consent pickers (no free-text consent ids).

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
| Client → edge | TLS 1.2+ (Azure Front Door/App Gateway policy, GCP SSL policy, profile `RESTRICTED`, minimum TLS 1.2); WAF rate limits on login endpoints (App Gateway `RateLimitLogin`, Cloud Armor `rate_based_ban`), HSTS (1 year, includeSubDomains) sent by APIs, BFF and nginx. |
| Ingress → pods | TLS at ingress (`wellnessz-tls`); in-cluster traffic restricted by NetworkPolicies. A service mesh for in-cluster mTLS is optional (open item O6). |
| Apps → PostgreSQL | `sslmode=verify-full` by default (local profile relaxes to `prefer`); Azure `ssl_min_protocol_version TLSv1.2`; CNPG `TLSv1.3`, `hostssl` + SCRAM-SHA-256 only. |
| Mailbox | IMAPS with server-identity check; Graph over HTTPS. |
| SMTP | STARTTLS **required** outside the local profile. |
| HL7 v2 | over HTTPS instead of plaintext MLLP. |

### 9.3 BFF pattern, CSRF and security headers

The BFF implements the *OAuth 2.0 for Browser-Based Apps* recommendation: the BFF is a confidential client; the browser
holds only the session cookie `WZSESSION` (`HttpOnly`, `Secure`, **`SameSite=Lax`**). `Lax` is a deliberate choice:
after authentication the IdP returns the browser to the BFF callback with a cross-site top-level navigation; with
`Strict` the browser would not send the session cookie on that navigation, so the BFF could not correlate the
authorization response with the session (state/nonce) and the login would fail. `Lax` still withholds the cookie from
cross-site sub-requests and cross-site `POST`s, and every state-changing call additionally requires the CSRF token
described below. The design brief and the web READMEs state the same. Session attributes, including access and refresh
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
credentials are not allowed. (The `local` profile and the Docker Compose stack list the four dev servers
`http://localhost:5173`–`5176`; the partner portal on 5176 was added in increment 4.)

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
| SAST | CodeQL (`java-kotlin`, `javascript-typescript`) on push, PR and weekly; SpotBugs 4.10.5.0 + FindSecBugs 1.14.0, effort Max (`mvn -Psecurity verify`) | `.github/workflows/codeql.yml`; `pom.xml` | Implemented (SpotBugs runs via the Maven profile, not yet as a CI job) |
| SCA | OWASP dependency-check 13.0.0 aggregate (fail CVSS ≥ 7, SARIF to code scanning); GitHub dependency review on PRs (fail on high); `npm audit --omit=dev --audit-level=high`; exact pins and lockfiles | `ci.yml`, `pom.xml`, `config/dependency-check-suppressions.xml` | Implemented |
| SBOM | CycloneDX Maven plugin 2.9.3 (Java), `@cyclonedx/cyclonedx-npm` 6.0.1 (web), Syft image SBOMs; uploaded as CI artefacts and attested to images | `pom.xml`, `ci.yml`, `container.yml` | Implemented |
| Secret scanning | gitleaks v8.30.1 (full history, SARIF) | `ci.yml` | Implemented |
| Image & IaC scan | Trivy image gate (CRITICAL/HIGH with fix → build fails) + SARIF; Trivy config scan of Dockerfiles, Helm, Terraform; `helm lint`/template for all values files; `terraform fmt` | `container.yml`, `ci.yml` | Implemented |
| Signing / admission | cosign keyless signing (GitHub OIDC, Fulcio/Rekor), SBOM and SLSA build-provenance attestations, Binary Authorization attestation for Artifact Registry; deploy job verifies cosign signatures before `helm upgrade` | `container.yml`, `deploy-env.yml`, Terraform `binauthz.tf` | Implemented (cluster-side admission enforced on GKE; AKS/on-prem admission policy recommended) |
| DAST | OWASP ZAP baseline against a test deployment | – | Recommended (not yet defined) |
| Deployment | `cd.yml` on version tags: build/scan/sign → dev → test → prod via `deploy-env.yml` (GitHub Environments, `helm upgrade --atomic`, deployment record); OIDC federation to Azure/GCP, AKS private-cluster `command invoke` path | `cd.yml`, `deploy-env.yml`, Terraform | Implemented; **required reviewers** for `test`/`prod` must be configured in GitHub Environment settings (organisation control) |

> All third-party actions are pinned to full commit SHAs. CI artefacts (test and coverage reports, SBOMs,
> dependency-check and gitleaks reports, SARIF in GitHub code scanning, attestations) are the evidence sources listed in
> document 02. Not yet in the pipeline: SpotBugs as a CI job and DAST.

## 10. Analytics and reporting architecture

| Layer | Today | Planned |
|---|---|---|
| Source | `analytics.v_case_facts` (de-identified, restricted class excluded); increment 3: E06 absence aggregates per unit and E07 compliance per programme / unit computed in the modules with the same minimum group size; increment 4: E04 treatment KPIs, costs per unit and provider scorecards, E08 cost roll-ups by subsidiary / department / cost centre, budget versus actual and the ERP export | Per-module fact views (health promotion) and a warehouse feed with the same rules |
| Query API | `GET /api/v1/analytics/kpis/cases-by-status?groupBy=orgUnit\|category\|source\|month` – dimensions are allow-listed and mapped to SQL expressions (no user input in SQL) | Further KPIs, regulatory reports (e.g. occupational-disease notifications, statutory surveillance coverage) |
| Disclosure control | `SmallCellSuppression`: primary suppression of cells with `0 < n < k`; complementary suppression when exactly one cell is suppressed (next-smallest cell also hidden); `k` from `WZ_ANALYTICS_MIN_GROUP` (default 5), values below 3 rejected at start-up | Row/column-wise complementary suppression for multi-dimensional tables; differencing protection across successive queries |
| Pseudonymisation | `Pseudonymizer`: HMAC-SHA256 with `WZ_PSEUDONYM_KEY` (≥ 256-bit), key held only by the platform | Used for warehouse extracts and model training sets (not yet called) |
| Warehouse / BI | – | Scheduled extract of pseudonymised, aggregated facts to Azure Synapse/Fabric or BigQuery; Power BI or Looker with row-level security; no direct BI access to OLTP schemas |
| Predictive models | – | Model governance: purpose approval by OH governance and DPO, DPIA, `RESEARCH_DEIDENTIFIED` consent or other lawful basis, pseudonymised training data, bias/performance review, human-in-the-loop for any individual-level output, model cards and versioned approval records |

Increment 3 aggregates follow the same disclosure rules without using the view: `UnitAggregator` (E06) publishes a
unit only when its headcount reaches the minimum group size, otherwise `{key, label, suppressed: true}` without any
counts; reason categories appear only in the OH variant. `ComplianceMaths` (E07) suppresses programme / unit rows
with fewer enrolled people. Audit packs are anonymised by default (pseudonymous subject ids, no names, small groups as
`<k`); only the CMO and the Nursing Head may generate identifiable packs. With the small demo population most groups
are therefore suppressed – this is intended.

Increment 4: E08 `CostMath` suppresses roll-up units with fewer cases than the minimum group size (`suppressed:
true`, no amounts, no names); budget versus actual is reported per unit without suppression because it contains no
person-level dimension. Roll-ups, budget versus actual and the ERP export count `INVOICE` and `PAYMENT` entries only
(`ESTIMATE` and `RESERVE` are exposure on the claim) and convert amounts to `claims.base-currency` (GBP) with
`claims.fx-rates`; E04 cost and KPI views sum base-currency amounts only (`treatment.base-currency`).

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
| Concurrency in jobs | Outbox relay, notification dispatch and (increment 1) the SLA timer poller use `SKIP LOCKED`; audit chain uses an advisory lock (a throughput ceiling for audit writes – acceptable for expected volumes, to be load-tested); mailbox job tolerant of concurrent runs. Daily, hourly and quarter-hourly jobs of increments 2–4 run once per run key across replicas through `JobRunGuard` (`platform.scheduled_job_run`). |
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
| Apache PDFBox (increment 2) | Apache License 2.0 – permissive, no copyleft, attribution via NOTICE; pure Java, no native components. Selected over AGPL / commercial PDF engines (Section 14.15). |
| Fonts for generated PDFs (increment 2) | Not shipped. The default is the PDF standard font Helvetica (no embedding). For Arabic and other scripts the operator mounts a TrueType font (`wellnessz.documents.font-path`); Noto fonts are under the SIL Open Font License 1.1, which permits embedding. |
| OCR (increment 2, optional) | Tesseract OCR – Apache License 2.0, installed separately on the host / image (not bundled). Cloud alternatives (Azure AI Document Intelligence, Google Document AI) are pay-per-page services under the cloud agreement; a DPA and data-residency review apply. |
| Terminology starter content (increment 2) | V7 seeds a small starter subset of ICD-10, LOINC and SNOMED CT concepts for development, demos and tests, recorded in `platform.terminology_release` with its licence note. Production loads the licensed national editions through `POST /api/v1/terminology/concepts/import`; the SNOMED CT sample requires the affiliate licence wherever the software is used (open item O13). |
| GS1 barcodes (increment 3) | Vaccine packs are scanned as GS1 DataMatrix / GS1-128 element strings (GTIN, lot, expiry) and parsed server-side by `Gs1Lot`; the GS1 General Specifications are freely available and reading barcodes needs no GS1 membership. No barcode library is bundled (a keyboard-wedge scanner types the element string). |
| FHIR R4 partner exchange, telehealth, e-prescribing (increment 4) | No new library: the FHIR `ServiceRequest` / `DocumentReference` mapping is plain JSON in `FhirMapper` (HAPI FHIR structures already present); no video SDK is bundled. Licences of the telehealth video platform, insurer / authority connectors and a national e-prescription service are procurement items (decisions D15, D18, D19). |
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
| MFA proof in the access token (increment 4) | `amr` (for example `mfa`) after Conditional Access; authentication context `c1` via `acrs` (Section 14.30) | `acr` level of authentication (`2` after OTP / WebAuthn step-up) and optional AMR mapper | `amr` / `acr` configurable |
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
| Apache PDFBox (increment 2) | 3.0.8 | `pom.xml` (`pdfbox.version`) |
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

### 14.12 SLA timer scheduling (increment 1)

**Choice:** database-backed timers plus an in-application poller. Each case milestone (today `TRIAGE` and
`ESCALATION_RESPONSE`) has a row in `triage.sla_timer` with `started_at`, `amber_at`, `due_at` and a status
(`RUNNING`, `PAUSED`, `MET`, `BREACHED`, `CANCELLED`). Targets and the amber percentage come from `triage.sla_policy`
(per urgency, optionally per case type; seeded: EMERGENCY 15 min, URGENT 60 min, SEMI_URGENT 240 min, ROUTINE 480 min,
ADVICE 960 min, amber at 75 %). `SlaService.monitor()` runs on every replica every `wellnessz.triage.sla.poll-interval`
(`WZ_SLA_POLL_INTERVAL`, default `PT60S`) and claims due rows with
`select … from triage.sla_timer where status = 'RUNNING' and (amber due or due_at <= now) order by due_at limit 500 for update skip locked`
(partial index `ix_sla_timer_due`). In the **same transaction** it marks the breach, flags the case as escalated,
writes the timeline entry and publishes `sla.at-risk.v1` / `sla.breached.v1` to the outbox. Waiting for the employee
or an external party pauses the timer (remaining seconds stored) and resumes it later.

| Criterion | DB timers + `SKIP LOCKED` (selected) | Quartz with JDBC job store | Scan job + ShedLock | External scheduler or workflow engine (Kubernetes CronJob, cloud scheduler, Temporal, Camunda timers) | Broker delayed messages (e.g. Service Bus scheduled messages) |
|---|---|---|---|---|---|
| Atomic with the case change (no dual write) | yes – same transaction as case, timeline and outbox | yes (same DB) but separate tables and API | yes | no – needs callbacks into the API and idempotency | no – needs the outbox anyway |
| Additional infrastructure / operating effort | none | library + 11 tables | library + lock table | separate service or cluster, credentials, HA | broker, and only on some clouds |
| Safe on several replicas | yes – rows are claimed, never double-processed | yes (clustered mode) | one active node only | yes | yes |
| Pause / resume, re-targeting by policy change | simple row update | reschedule triggers | simple | engine-specific | cancel and re-send messages |
| Hybrid / on-premises portability | PostgreSQL only | yes | yes | depends on product | Azure / GCP specific |
| Reporting (MET / BREACHED per milestone) | plain SQL on the timer table | needs extra history | plain SQL | export from engine | not retained |
| **Verdict** | **Selected** – same reliability pattern as the outbox relay; no new moving parts | Over-sized for one timer type | Single active node wastes replicas | Reconsider if multi-step clinical workflows with long-running timers arrive (E04, E05) | Not selected |

Trade-offs accepted: alert latency is bounded by the poll interval (≤ 60 s against targets of 15–960 min); the SLA
state shown in queues and the case header is computed on read from the timestamps, so it is exact. Durations are
elapsed minutes – "one working day" is configured as 480 minutes until a business calendar exists in E18 (open item
O10). The integration test `QueuePerformanceTest` seeds 5,000 open cases with running timers and requires three queue
calls to complete in under 2 seconds.

### 14.13 Intake and assignment rule engine (increment 1)

**Choice:** an **in-house declarative rule table** in PostgreSQL rather than a rules engine such as Drools. E01 intake
rules (`intake.intake_rule`) and E02 assignment rules (`triage.assignment_rule`) are rows with nullable conditions
(null = wildcard: incident class, minimum severity, lost time, injury, exposure type, unit, body part; for assignment:
subsidiary, site, language, case type, safety category) and an action (`AUTO_CREATE` / `SUGGEST` / `IGNORE` with case
type and urgency; queue and owner strategy). The **first active rule by priority wins**. OH administrators and the
Nursing Head edit them in the OH workspace, a dry-run endpoint (`POST /intake/rules/test`, `POST /assignment/rules/test`)
shows which rule would fire, and every change is audited (`INTAKE_RULE_CREATE`, `INTAKE_RULE_UPDATE`,
`ASSIGNMENT_RULE_SAVE`).

| Criterion | Declarative rule rows (selected) | Drools / KIE (DRL or decision tables) | DMN engine (e.g. Camunda / Kogito DMN) | Rules hard-coded in Java (base framework seed) |
|---|---|---|---|---|
| Who changes rules | OH admin / Nursing Head in the UI, audited | developers (DRL) or analysts (spreadsheets) with a deployment | analysts with a modelling tool | developers, release required |
| Expressiveness needed by E01/E02 | sufficient – conjunctions of equality / threshold conditions, first match | far beyond the need (forward chaining, CEP) | sufficient (hit policy FIRST) | unlimited |
| Explainability for reconciliation and audit | rule id stored on each `incident_evaluation` and assignment | requires rule-firing listeners | decision trace available | log statements only |
| Testability | unit test of `RuleEngine.firstMatch`, dry-run API | KIE test harness | DMN test tooling | unit tests |
| Dependency and security surface | none (plain JPA) | large (MVEL / executable model, compiler at runtime, frequent CVE handling) | medium–large | none |
| Change control / SOC 2 | data change audited in the hash-chained trail; maker-checker planned (RR6 in doc 02) | artefact release through CI/CD | model release | code review |
| **Verdict** | **Selected** | Reconsider if rules need chaining across facts or temporal reasoning | Candidate if business analysts want standard decision models | Superseded |

### 14.14 Triage protocol content as versioned JSON (increment 1)

**Choice:** triage protocols are **data, not code**: one JSON document per protocol version stored in
`triage.triage_protocol` (`code`, `version`, `status` `DRAFT` → `APPROVED` → `RETIRED`, `approved_by`, `approved_at`,
unique `(code, version)`). A document holds the start question, questions (`BOOLEAN`, `NUMBER`, `SELECT`) with
`onYes` / `onNo` / `next` branches, threshold `rules` (`>=8`, `4..7`, `else`), `redFlag` markers and outcomes. The
13 draft protocols (body fluid, cardiac, chemical, crew fatigue, eye, fume event, head injury, heat, infectious GI,
laceration/burn, MSK, psychological, respiratory) ship in `modules/wz-e02-triage/src/main/resources/triage/protocols/`
and are seeded as `DRAFT` at start-up only when the code does not exist yet – existing versions are never
overwritten. Only the CMO can approve a version (`POST /triage/protocols/{code}/approve`, audited
`PROTOCOL_APPROVE`); approving retires the previous approved version. `TriageService` uses the latest approved
version and falls back to the latest draft (for test environments; the UI shows the status). Each assessment stores
the protocol code and version it was evaluated against.

Urgency is **always computed on the server** (`ProtocolEvaluator`): the client sends answers only, a red-flag "yes" is
a hard stop at `EMERGENCY` that cannot be overridden downwards (`422 red_flag_hard_stop`), and any other override
needs a reason. The OH workspace runs the same JSON for navigation and a preview only.

| Criterion | Versioned JSON in the DB (selected) | Protocols coded in Java | Commercial triage content / decision-support service | FHIR `Questionnaire` + `PlanDefinition` |
|---|---|---|---|---|
| Clinical governance (draft → CMO approval → retire, audit) | built in | code review only, not clinical sign-off | vendor-governed | needs a FHIR server and tooling |
| Change without a software release | yes (new version) | no | yes | yes |
| Same definition drives server evaluation and UI | yes | no | vendor UI | yes, with a renderer |
| Reproducibility of past decisions | version stored per assessment | git history only | vendor-dependent | yes |
| Licensing / cost | none | none | per-use licence | none (tooling effort) |
| **Verdict** | **Selected** | Not selected | Option if the airline licenses validated content | Candidate for E03 interoperability; the JSON grammar can be mapped later |

The protocol content in the repository is **draft clinical content** that must be reviewed and approved by the CMO
and the Nursing Head before clinical use (open item O11).

### 14.15 Document generation – Apache PDFBox (increment 2)

**Choice:** Apache PDFBox **3.0.8** (Apache-2.0), used by `PdfRenderer` in `platform/wz-documents`. Templates are
**defined in code** (`DocumentTemplates`: code, type, audience, `containsClinical`, record class, generator and signer
roles, merge fields with length caps, paragraph bodies with `{{placeholder}}` merge). The renderer lays out A4 pages
with a header, document number and version, the merged paragraphs, a generation line and a confidentiality footer,
and returns the PDF together with its text layer (used for the search index). Unknown merge fields are rejected,
required fields enforced, and for templates flagged `containsClinical=false` (fitness certificate, restriction note,
food-handler clearance, RTW plan, job offer, IHR decision letter) every merge field is **screened by the clinical-text
guard** (`ClinicalContentException.screen`), so a diagnosis cannot be typed into a document that managers or HR may
receive. Uploaded PDFs are read with the same library (text layer of up to 50 pages).

| Criterion | Apache PDFBox (selected) | iText 7/8 | Commercial engines (e.g. Aspose.PDF, PDFTron/Apryse) | HTML-to-PDF (headless Chromium / Puppeteer, wkhtmltopdf, OpenHTMLtoPDF / Flying Saucer) | Apache FOP (XSL-FO) / JasperReports |
|---|---|---|---|---|---|
| Licence | Apache-2.0 | AGPL-3.0 or commercial licence | commercial, per-server / per-developer | Chromium BSD-style; wkhtmltopdf LGPL (project archived); OpenHTMLtoPDF / Flying Saucer LGPL | Apache-2.0 / LGPL |
| Runtime footprint | pure Java library, no native dependencies, fits the read-only, non-root core image | pure Java | Java library (some with native parts) | separate browser binary or native renderer; larger attack surface and image size, sandbox flags inside containers | Java, heavier template tooling |
| Maturity / maintenance | Apache top-level project since 2009, regular 3.0.x releases | mature | vendor-supported | varies; wkhtmltopdf unmaintained | mature, slower moving |
| Fit for the requirement (short letters and certificates, text extraction of uploads, later signing) | yes – generation and extraction in one library | yes | yes, plus conversion features not needed | layout power not needed; no text extraction of uploads | over-sized |
| **Verdict** | **Selected** | Not selected (AGPL obligations or licence cost) | Not selected (cost, lock-in) | Not selected (native dependency, CVE handling) | Not selected |

Trade-offs accepted: layout is programmatic (no WYSIWYG designer); template wording changes need a release and is a
**draft for DPO / CMO approval** (open item O14). Non-Latin scripts need a mounted Unicode font
(`wellnessz.documents.font-path`, environment variable `WZ_DOCUMENTS_FONT_PATH`); without it Helvetica is used.

### 14.16 Electronic signature and document integrity (increment 2)

Every stored document records the **SHA-256** of its content. Signing a draft (`POST /documents/{id}/sign`, signer
roles per template – clinicians for certificates, AME / CMO for aeromedical reports, CMO for IHR letters, HR for job
offers) locks it and stores a signature hash `SHA-256(contentHash | signer | signing time)` with signer and time;
scanned documents are sealed on upload with `SHA-256(contentHash | user who filed them)`. A new version supersedes the
previous signed version and **revokes its shares**. This is a *simple electronic signature with integrity evidence*
(eIDAS terminology) – it proves who signed what and when inside WellnessZ and detects later modification, backed by
the hash-chained audit trail (`DOCUMENT_SIGN`). Advanced or qualified electronic signatures (PAdES with certificates
from a trust service provider or a remote signing service) are an extension point for documents that leave the
organisation with legal effect (decision D12).

### 14.17 Search over encrypted documents – blind-index tokens (increment 2)

Document content and extracted text are field-encrypted, so a plaintext full-text index would undo that protection.
`DocumentService` therefore indexes **keyed tokens**: the text is lower-cased, split into words of 3–40 letters or
digits, each word is turned into an HMAC-SHA256 blind-index value (`FieldEncryptor.blindIndex`, key purpose
`wellnessz/blind-index/v1`, domain prefix `doc-search:`), truncated and stored as a lexeme in the PostgreSQL `tsvector`
column `docs.document.search_vector` (configuration `simple`, GIN index, at most 5,000 distinct words per document).
A query is tokenised the same way and matched with `to_tsquery` (all words must match), scoped to one employee and to
callers with clinical access; restricted-class documents are filtered for callers without restricted access; every
search is audited (`DOCUMENT_SEARCH`). Snippets are cut from the decrypted text after the access check.

| Criterion | Blind-index tokens in `tsvector` (selected) | Plaintext PostgreSQL full-text index | External search engine (Elasticsearch / OpenSearch, Azure AI Search) | Decrypt and scan on query |
|---|---|---|---|---|
| Plaintext in the database or index | none (keyed tokens only) | yes | yes, in a second PHI store | none |
| Extra infrastructure | none | none | cluster to operate, secure and back up | none |
| Query features | exact word match only (no stemming, prefix, fuzzy or ranking) | full | full | full but slow |
| Performance | GIN index | GIN index | fast | linear in document volume |
| **Verdict** | **Selected** | Not selected (defeats field encryption) | Not selected (second PHI store) | Not selected |

Residual properties: equal words produce equal tokens, so token frequencies inside one document are visible to a
database reader (no plaintext); the blind-index key is not versioned (existing risk R1 / decision D5 – re-indexing
must be part of the key-rotation job).

### 14.18 OCR for scanned images (increment 2)

PDFs are indexed from their text layer. Images (JPEG, PNG) need OCR, which is **off by default**. The `OcrProvider`
SPI (`platform/wz-documents`) has one implementation, `TesseractOcrProvider`, that activates only when
`wellnessz.documents.ocr.tesseract-path` is set. The binary must be one of three allow-listed install paths
(`/usr/bin/tesseract`, `/usr/local/bin/tesseract`, `/opt/homebrew/bin/tesseract`) and
`wellnessz.documents.ocr.languages` one of six allow-listed values (`eng`, `ara`, `fra`, `eng+ara`, `eng+fra`,
`eng+ara+fra`); the only other argument is a JVM-created temporary file that is deleted afterwards; output is capped
at 1 MB and the process at 60 s. No data leaves the host.

| Option | Assessment |
|---|---|
| No OCR (default) | Images are stored and classified by type only; acceptable while most external reports arrive as PDFs with a text layer |
| Tesseract via the SPI (implemented, optional) | Apache-2.0, on-host, no data transfer; needs the binary and language packs in the image (a derived core image) and adequate accuracy testing for Arabic |
| Azure AI Document Intelligence / Google Document AI (extension point) | Higher accuracy and layout extraction; PHI leaves the cluster to a regional cloud service – needs a DPA, regional endpoint, private endpoint / VPC-SC and egress allow-listing; implement as another `OcrProvider` bean |

### 14.19 Clinical terminology approach (increment 2)

Codes are stored as `(system, code, display)` with `system` one of `SNOMED_CT`, `ICD_10`, `ICD_11`, `LOINC`, `ODH`. E03
uses a **local concept cache** (`platform.code_concept`) rather than calling a terminology server on every keystroke:
`TerminologyCatalog` offers word-prefix search on the display (GIN `to_tsvector('simple', display)` index) and
code-prefix search, per-user and per-role favourites, recently used codes, a local reporting-category mapping and a bulk
import (`POST /api/v1/terminology/concepts/import`, OH_ADMIN, ≤ 20,000 concepts per call) that records every release in
`platform.terminology_release` with its licence note. Diagnoses are coded on encounters and the primary coded diagnosis
is copied to the case; `DiagnosisRequiredGuard` refuses to close a clinical case without one
(`wellnessz.case.diagnosis-required-on-close`, default `true`). Licensing obligations are in Section 13. A terminology
server (Snowstorm, Ontoserver or a national service, decision D10) can later feed the same cache.

### 14.20 Interoperability standards in use (increment 2)

| Standard | Use in increment 2 | Component |
|---|---|---|
| HL7 v2.5 ORM^O01 | One message per new lab order, placed in a **pull queue** for the laboratory gateway (`GET /integration/lis/orders?status=QUEUED`, `POST /integration/lis/orders/{id}/ack`) – no outbound connection from the core | `Hl7Messages`, `OrderService` |
| HL7 v2.5 ORU^R01 | Results accepted on the existing `POST /integration/hl7v2` endpoint and mapped exactly once per message (OBX → results, grouped per patient and placer order) | `OruResultListener` |
| JSON LIS results | `POST /integration/lis/results` for gateways that prefer JSON | `ResultService` |
| HL7 FHIR R4 | `Bundle` export of the OH record (Patient, Condition, AllergyIntolerance, MedicationStatement, Immunization, Observation, DocumentReference, Encounter), audited; restricted-class items omitted for callers without restricted access | `FhirExportService` |
| HL7 ODH (FHIR profile) | Work information (PastOrPresentJob, UsualWork, exposures) from the job history as FHIR | `FhirExportService`, `JobHistoryService` |
| LOINC | Result and device observation codes; restricted result codes `wellnessz.record.restricted-result-codes` | `ResultService`, `DeviceRules` |

MLLP remains terminated by an integration engine (Section 14.5); the core only exposes HTTPS endpoints protected by
client-credentials tokens with the `INTEGRATION` role.

### 14.21 AI-assisted documentation – extension point only (increment 2)

E03 defines the SPI `AiDocumentationProvider` (draft structured note from a consented consultation transcript) and the
consent-gated `AiAssistantService`. **No provider ships enabled** (`wellnessz.record.ai.provider` defaults to `none`;
the API answers that no provider is configured). Rules fixed in the code and the SPI contract: the employee's consent
(purpose `AI_DOCUMENTATION`, captured by the clinician and recorded) is required per encounter; the provider sees only
the encounter type, the template field keys and the transcript; drafts are **returned, never saved automatically** –
the clinician reviews and saves, and the encounter is flagged `aiAssisted`; every use is audited; implementations must
not retain transcripts and must process data only in approved hosting locations. The choice between a locally hosted
model and a vendor service is an evaluation spike (privacy, accuracy, clinician acceptance, DPIA) and a steering
decision (D13); no vendor or model is selected in this architecture.

### 14.22 Scheduling and clinic operations engine (increment 3)

**Choice:** an **in-house scheduling engine** inside the modular monolith (`modules/wz-e14-scheduling`, schema
`scheduling`) rather than a commercial clinic-scheduling product or a generic booking service. Availability is derived
from diary templates per site and clinician (sessions with the appointment types they offer); `SlotEngine` computes
free slots from the service catalogue (duration, required role, room type and device category) and existing
appointments; `BookingRules` rejects double booking of a clinician, room or device (`409 resource_conflict`) and
bookings outside a session (`422 no_session`, unless a named clinician books an administrative override). Campaigns
(`CampaignPlanner`) book whole cohorts into campaign slots and report `{booked, waitlisted, alreadyBooked, rejected}`;
a freed slot produces a **waitlist offer**, never an automatic booking. Other modules book through the kernel SPI
`AppointmentBooking` and learn about attendance through `AppointmentStatusListener`, so E06 and E07 work (as
"invited, not booked") even when E14 is not deployed. Bookings are conflict-checked under PostgreSQL advisory locks, so
two replicas cannot book the same resource concurrently. Reminders and no-shows are quarter-hourly jobs; a booked or
checked-in appointment that has not started 30 minutes after its end becomes `NO_SHOW`
(`scheduling.no-show.grace-minutes`).

| Criterion | In-house engine in the core (selected) | Commercial OH / clinic scheduling suite | Generic booking SaaS (e.g. Microsoft Bookings, Calendly-type services) | Calendar integration only (Exchange / Google resource calendars) |
|---|---|---|---|---|
| Fit for OH rules (role, room, device and calibration constraints; campaigns; surveillance recall; queue without names) | built for them, unit-tested (`SlotEngine`, `BookingRules`, `CampaignPlanner`) | usually good, but a second system of record for OH data | weak – no device or calibration constraints, no surveillance cohorts | none |
| Where person-level health context lives | stays in the OH database under the same ABAC, audit and encryption | copied to the vendor; DPA, residency and integration needed | appointment type reveals health context to a general SaaS | appointment titles leak into corporate calendars |
| Integration effort | none (SPIs inside the monolith) | HL7 SIU / FHIR `Appointment` interfaces both ways | APIs and webhooks | free/busy only |
| Licence / cost | none | per-site / per-user licences | per-user subscription | existing |
| **Verdict** | **Selected** | Reconsider if the airline already operates one for its group clinics | Not selected (privacy, constraints) | Not selected |

Trade-offs accepted: no external calendar synchronisation (clinician calendars are the WellnessZ diary); the queue board
and kiosk run in the OH workspace, not on dedicated signage hardware; capacity forecasting is a monthly count
comparison (supply from the diary templates versus demand from `CapacityDemandSource`), not an optimiser.

### 14.23 Cold-chain monitoring and IoT ingestion (increment 3)

**Choice:** vaccine fridges are registered as equipment (`VACCINE_FRIDGE`, default range 2–8 °C, configurable per
device). Readings arrive in two ways – manual entry by clinicians or biomedical staff, and **batched HTTPS push** from an
IoT gateway to `POST /api/v1/integration/cold-chain/readings` (client-credentials token with the `INTEGRATION` role,
≤ 5,000 readings per call, device id pattern, plausible temperature range). `EquipmentRules.evaluate` opens, extends
or ends an **excursion**; an open excursion raises the content-free `coldchain.excursion` notification and must be
**acknowledged** by a clinician with the action taken before it can be closed. The core never connects to devices.

| Criterion | HTTPS batch push from a gateway (selected) | Direct MQTT from sensors to the core | Cloud IoT platform (Azure IoT Hub, AWS IoT Core; Google Cloud IoT Core was retired in 2023) feeding the API | Vendor monitoring portal only (no integration) |
|---|---|---|---|---|
| Attack surface on the core | none new – same authenticated API path as other gateways, WAF in front | broker and device credentials inside the PHI zone | none new (platform pushes through the API) | none |
| Works on Azure, GCP and on-premises alike | yes | yes, with an extra broker | Azure / AWS specific | yes |
| Device provisioning, firmware and offline buffering | gateway / vendor responsibility | core team responsibility | platform features | vendor |
| Audit trail next to vaccine administrations | yes (readings, excursions and acknowledgements in `scheduling`) | yes | yes | no – manual transcription |
| **Verdict** | **Selected** | Not selected | Option for the gateway side if the airline standardises on it | Not selected |

The gateway product is open (decision D16); until then readings are entered manually. Excursion decisions about vaccine
usability stay clinical and are recorded as the acknowledgement action.

### 14.24 Telehealth – link only (increment 3)

Appointment types flagged `telehealth` get a join link built from `scheduling.telehealth-base-url` (E18 configuration,
fallback `wellnessz.scheduling.telehealth-base-url`) plus a random room id; the link is shown to the employee and the
clinician and is never sent with clinical content. **No video platform is integrated**: the platform choice (an
enterprise meeting service with healthcare terms, a dedicated telehealth service, or a self-hosted WebRTC service),
recording policy, waiting room and identity checks belong to E04 (treatment coordination) and decision D15. Until then
the base URL must point to a service that the DPO has approved for consultations.

### 14.25 Absence data feed and vaccine barcodes (increment 3)

**Absence feed.** Absence data is owned by HRIS, time & attendance and crew-management systems. E06 accepts it in two
ways that share one reconciliation path: **push** to `POST /api/v1/integration/absence/episodes` (batches of up to 5,000
rows) and a daily **pull** (`absence-feed` job) through the `AbsenceFeedConnector` SPI, whose shipped implementation is
a no-op. Each source system gets its own connector bean (CSV/SFTP drop, REST or database view – decided per system with
E15); unknown employees or badges are listed for reconciliation instead of being dropped. Only the reason **category**
is accepted (allow-listed values), never a diagnosis. Rule evaluation runs nightly (`absence-triggers`) over open
episodes and those ended within 30 days; only `SICKNESS`, `INJURY_WORK` and `INJURY_NON_WORK` count; rule thresholds are versioned data
(`absence.trigger_rule_version`), with subsidiary-specific overrides of the same type and action.

**Vaccine barcodes.** Immunisation fast entry accepts the GS1 element string of the vaccine pack (GTIN, expiry, lot,
serial – human-readable or raw scanner form with group separators) and parses it in `Gs1Lot`; a keyboard-wedge scanner
types it into the lot field, so no barcode library or camera permission is needed. Vaccine, lot, site and route stay
filled between people for clinic sessions.

## 15. Verification evidence

The following results were produced against the baseline described on the cover and can be reproduced from the
repository.

| Check | Result | How to reproduce |
|---|---|---|
| Java tests | **42 tests, all passing**: unit tests in the platform modules, integration tests on a real PostgreSQL 16 (Testcontainers or `WZ_TEST_DB_URL`), 4 ArchUnit rules, BFF security tests | `mvn verify` (CI job *Java build & test*) |
| SAST – SpotBugs + FindSecBugs (effort Max, threshold Medium) | **0 findings**; 4 justified exclusions in `config/spotbugs-exclude.xml` (entity/record representation exposure, constructor fail-fast, CSRF disabled on the stateless bearer API, validated SPA logout redirect) | `mvn -Psecurity verify` |
| Web unit tests (Vitest) | employee-app **30**, manager-workspace **35**, all passing | `npm test` in each app (CI job *Web*) |
| End-to-end (Playwright, real Keycloak 26.8.1) | Login through the BFF; session cookie `WZSESSION` is `HttpOnly`; no tokens in `localStorage`; employee self-referral → manager sees a status-only row without clinical text | `tools/e2e/e2e.js` with the local stack; screenshots in `docs/images/screens/` |

Integration tests covering security properties include: status-only projection for managers and 404 outside org scope,
restricted class hidden from general clinicians, clinical text and outbox payloads encrypted at rest, status-only
rostering payload, append-only audit table and intact hash chain, Idempotency-Key replay/conflict, FHIR/HL7 validation,
Excel import hardening and de-duplication, small-cell suppression, notification content rules and the disabled-module
guard (`disabledModuleIsRefused`).

![Employee self-referral](../images/screens/employee-03-referral.png)

*Figure 8 – Employee app (BFF session): self-referral to Occupational Health (E2E run).*

![Manager status-only team view](../images/screens/manager-02-team.png)

*Figure 9 – Manager workspace after the self-referral: status-only row – case reference, name, status, fitness status,
restriction date – with no clinical text (E2E run).*

**Increment 1 (E01, E02).** Results of the increment-1 verification run (details in the
[Release Notes](../increment-01/03-Release-Notes-Increment-1.md)):

| Check | Result | How to reproduce |
|---|---|---|
| Java tests | **65 tests, all passing**, including `Increment1IntegrationTest`, `QueuePerformanceTest` (5,000 open cases, < 2 s), `RuleEngineTest`, `RecordabilityClassifierTest`, `ProtocolEvaluatorTest`, `ClinicalTextDetectorTest` and the 6 ArchUnit rules | `./mvnw verify` |
| SAST – SpotBugs + FindSecBugs | **0 findings**; 5 justified exclusions (the fifth covers the queue SQL in `QueueService`, assembled from constant fragments with every user value bound as a parameter) | `./mvnw -Psecurity -DskipTests -Ddependency-check.skip=true verify` |
| Web unit tests (Vitest) | oh-workspace **151**, employee-app **81**, manager-workspace **55**, all passing | `npm test` in each app |
| End-to-end (Playwright, real Keycloak) | **18 / 18 checks** of `tools/e2e/e2e-increment1.js`: EHS webhook → auto-created and routed case, self-referral through the UI, manager referral clinical-text guard and org scope, status-only manager view, queue counts, reassignment by the Nursing Head, red-flag hard stop, triage, escalation, SLA clock, first-aid suggestion, DLQ, reception and crew-scheduling restrictions | `node tools/e2e/e2e-increment1.js`; screenshots in `docs/images/screens/increment-01/` |

![OH workspace team queue](../images/screens/increment-01/inc1-oh-01-queues-team.png)

*Figure 10 – OH workspace, team work queue with SLA state, urgency and owner (increment-1 E2E run).*

![OH workspace triage](../images/screens/increment-01/inc1-oh-05-case-triage-result.png)

*Figure 11 – OH workspace, server-computed triage result for an EHS-triggered case (increment-1 E2E run).*

**Increment 2 (E03, E05).** Results of the increment-2 verification run (details in the
[Release Notes](../increment-02/03-Release-Notes-Increment-2.md)):

| Check | Result | How to reproduce |
|---|---|---|
| Java tests | **128 tests, all passing**, including `Increment2RecordIntegrationTest`, `Increment2FitnessIntegrationTest`, `Increment2PlatformIntegrationTest`, unit tests for HL7 messages, device rules, result flags, CDS, terminology, decision rules, matching engine and crew dashboard, and **10 ArchUnit rules** (new: document service independent of business modules, E03 ↔ E05 independence, earlier modules do not depend on later ones) | `./mvnw verify` |
| SAST – SpotBugs + FindSecBugs | **0 findings**; **6** justified exclusions (the sixth covers the allow-listed Tesseract command line in `TesseractOcrProvider`) | `./mvnw -Psecurity -DskipTests -Ddependency-check.skip=true verify` |
| Web unit tests (Vitest) | oh-workspace **305**, employee-app **138**, manager-workspace **99**, all passing | `npm test` in each app |
| End-to-end (Playwright, real Keycloak) | **25 / 25 checks** of `tools/e2e/e2e-increment2.js`: chart summary, CDS block, terminology, signed injury encounter, device audiogram, fitness decision, certificate shared, job-demand matching, modified duty and RTW plan, manager functional-restriction view and denial of the chart, HR job offer, employee acknowledgements and disclosure log, AME crew assessment and roster feed, restricted class and break-glass, DPO review and DPO without clinical access | `node tools/e2e/e2e-increment2.js`; screenshots in `docs/images/screens/increment-02/` |
| OpenAPI | **240 paths** in `docs/api/openapi.json` | `GET /v3/api-docs` (local profile) |

![OH chart summary](../images/screens/increment-02/inc2-oh-01-chart-summary.png)

*Figure 12 – OH workspace, employee chart summary with allergy alert and surveillance status (increment-2 E2E run).*

![Fitness decision](../images/screens/increment-02/inc2-oh-07-case-fitness-decision.png)

*Figure 13 – OH workspace, fitness decision against a versioned standard with restrictions and certificate
(increment-2 E2E run).*

![Manager team restrictions](../images/screens/increment-02/inc2-mgr-01-team-restrictions.png)

*Figure 14 – Manager workspace, functional restrictions of the team without diagnosis or clinical text (increment-2
E2E run).*

**Increment 3 (E06, E07, E14).** Results of the increment-3 verification run on 2026-10-29 (details in the
[Release Notes](../increment-03/03-Release-Notes-Increment-3.md)):

| Check | Result | How to reproduce |
|---|---|---|
| Java tests | **210 tests, all passing** – wz-core-app 89 including `Increment3AbsenceIntegrationTest` (10), `Increment3SurveillanceIntegrationTest` (9), `Increment3SchedulingIntegrationTest` (9) and **14 ArchUnit rules**; module unit tests E06 18 (rule engine, patterns, certificate checks, milestone plan, unit aggregation), E07 13 (eligibility, surveillance rules), E14 18; `NotificationRulesTest` renders every template through the content rules | `./mvnw verify` (with `WZ_TEST_DB_URL`: recreate the test database first – Section 21.5) |
| SAST – SpotBugs + FindSecBugs | **0 findings**; still **6** justified exclusions (none added) | `./mvnw -Psecurity -DskipTests -Ddependency-check.skip=true verify` |
| Web unit tests (Vitest) | oh-workspace **463**, employee-app **191**, manager-workspace **154**, all passing; lint 0 warnings | `npm test` in each app |
| End-to-end (Playwright, real Keycloak) | **32 / 32 checks** of `tools/e2e/e2e-increment3.js` (and 18/18, 25/25 for the earlier scripts): absence feed and reconciliation, trigger rules and early intervention, certificates (HR sees validity only), LTA milestones, clinical-text guard on HR stages and advice reports, employee consent before release, analytics suppression; surveillance simulation, scheduler booking, IH measurement above the action level, calibration block then acceptance, follow-up cases, `422 clearance_required`, candidate portal and HR status, crew certificates, GS1 vaccine entry, anonymised audit pack; double booking `409`, self-booking, check-in and queue board without names, call-next, cold-chain excursion, campaign and capacity | `node tools/e2e/e2e-increment3.js`; 37 screenshots in `docs/images/screens/increment-03/` |
| Defect found by E2E | BFF parsed multipart uploads itself and forwarded an empty body (`400` on browser uploads); fixed with `spring.servlet.multipart.enabled=false` in `apps/wz-bff` | Release Notes |
| OpenAPI | **391 paths** in `docs/api/openapi.json` (was 240) | `GET /v3/api-docs` (local profile) |

![OH absence queue](../images/screens/increment-03/inc3-oh-01-absence-queue.png)

*Figure 15 – OH workspace, early-intervention queue raised by versioned absence trigger rules (increment-3 E2E run).*

![Surveillance due list](../images/screens/increment-03/inc3-oh-11-due-list.png)

*Figure 16 – OH workspace, surveillance due list with invitations and booking status (increment-3 E2E run).*

![Queue board](../images/screens/increment-03/inc3-oh-23-queue-board.png)

*Figure 17 – Clinic queue board showing ticket numbers only, no names (increment-3 E2E run).*

## 16. Risks, assumptions and open items

### 16.1 Architecture risks

| ID | Risk | Likelihood | Impact | Mitigation / owner |
|---|---|---|---|---|
| R1 | Master-key compromise exposes all field-encrypted data (single key, HKDF-derived versions) | Low | High | Envelope encryption with KMS/HSM, strict Key Vault RBAC, re-encryption job, rotation runbook (D5) |
| R3 | No retention/deletion jobs → GDPR storage-limitation non-compliance | High | High | Retention schedule and purge jobs before go-live (D6) |
| R4 | DB owner could rewrite audit chain | Low | High | External hash anchoring, SIEM streaming, separation of duties for DB owner role (O2) |
| R5 | Outbound guard checks field names, not values | Low | High | Flat payloads enforced (nested objects/arrays rejected), allow-list review by DPO, contract tests per destination |
| R6 | Mobile refresh token in memory only until secure-storage plugin registered; MAM SDK not integrated | Medium (functional) | Medium | Register Keychain/Keystore plugin and MAM SDK in native projects before store release |
| R7 | Supply-chain gaps: SpotBugs not a CI job, no DAST, admission enforcement only on GKE | Medium | Medium | Add SpotBugs job and ZAP baseline; Kyverno/Ratify or Azure policy for signature verification on AKS/on-prem |
| R8 | Single-region deployment | Low | High | Cross-region replica and DR runbook (D9) |
| R9 | Audit advisory lock serialises writers – throughput ceiling | Low | Medium | Load test; partitioned chains per shard if needed |
| R10 | Authentication-failure audit writes per invalid token could be abused to amplify load | Medium | Low | WAF rate limiting; sampling or aggregation of failure events |
| R13 | Terminology licensing (SNOMED CT) in non-member countries | Medium | Medium | Licence assessment per operating country (D10) |
| R14 | Increment 2: document retention flags but never deletes; structured OH tables have no retention yet | High | Medium | DPO-approved schedule, reviewed destruction procedure, extend schedule to `ohrecord` / `fitness` (D6, O12) |
| R15 | Increment 2: blind-index search leaks token frequency per document and depends on the unversioned blind-index key | Low | Medium | Access restricted to OH roles and DB owners; re-index as part of the key-rotation job (D5) |
| R16 | Increment 2: simple electronic signature may not be accepted by external parties (CAA, courts) where advanced / qualified signatures are expected | Medium | Medium | Qualified signing provider for external-effect documents (D12); CAA format confirmation of crew medical reports |
| R17 | Increment 2: break-glass could be misused by an authorised clinician | Low | High | Justification ≥ 20 characters, 4 h validity, DPO alert, 24 h review, monthly report, no self-review |
| R18 | Increment 3: absence data in OH and HR systems diverges because only a no-op `AbsenceFeedConnector` ships | High (until connectors exist) | Medium | Push endpoint and reconciliation list today; one connector per source system with E15 (O18) |
| R19 | Increment 3: managers or HR infer health information from absence patterns, advice reports or surveillance status | Medium | High | Status-only projections (no `reasonCategory` for managers), clinical-text guard on advice reports and HR stages, employee review before release, disclosure log, small-group suppression |
| R20 | Increment 3: candidate identities outside the HRIS master (role `CANDIDATE`) need an identity source and de-provisioning | Medium | Medium | Short-lived candidate accounts, 12-month purge of non-hired candidates; candidate IdP decision (D17) |
| R21 | Increment 3: device results recorded on unregistered devices are accepted (`wellnessz.record.require-registered-devices=false` by default) | Medium | Medium | Register all audiometers, spirometers, ECGs and vision screeners; then set the flag to `true` |
| R22 | Increment 3: telehealth link points to an external service not yet assessed; IoT gateway not selected | Medium | Medium | DPO approval of the video service (D15); manual cold-chain logging until the gateway exists (D16) |

### 16.2 Open items

| ID | Item |
|---|---|
| O1 | Confirm IdP claims for `employee_id` and `org_scope` (Entra directory extensions or claims-mapping policy fed from HRIS). |
| O2 | Implement audit-hash anchoring and audit export to SIEM; scheduled `/audit/verify` with alerting. |
| O5 | Add a scheduler lock (e.g. ShedLock) for mailbox polling. |
| O6 | Decide on in-cluster mTLS (service mesh) vs NetworkPolicies only. |
| O7 | Add a read-only reporting DB role for warehouse extraction. |
| O8 | Define DAST in the pipeline (ZAP baseline) and add SpotBugs as a CI job. |
| O9 | Increment 1: the OH staff roster (`masterdata.staff_member` – usernames, roles, sites, languages, caseload caps) has no API or HRIS feed yet; it is loaded by SQL (demo rows only in the `local` profile). Define the source of truth (IdP group membership or HRIS) and an import before go-live. |
| O10 | Increment 1: SLA targets are elapsed minutes; a business / site calendar (working days, public holidays per station) is needed in E18. |
| O11 | Increment 1: the 13 triage protocols, advice texts (en/ar/fr) and recordability mapping are draft content; CMO / Nursing Head approval and a clinical safety case are required before clinical use. |
| O12 | Increment 2: retention for the structured E03 / E05 tables and for cases; destruction procedure and HRIS employment end dates for the `EMPLOYMENT_END` trigger. |
| O13 | Increment 2: replace the terminology starter subset by licensed national editions; confirm the SNOMED CT affiliate licence for every environment that holds the sample. |
| O14 | Increment 2: CMO / DPO approval of the 8 encounter templates, the document template wording, CDS rules, the 12 fitness standards, restriction catalogue, job-demand profiles and the CAA layout of crew medical reports. |
| O15 | Increment 2: transport adapters for the crew-rostering, HR and catering-hygiene destinations and the laboratory gateway (pull queue client) are not built; rostering must acknowledge each roster-feed message within 15 minutes. |
| O16 | Increment 2: Entra ID needs the `DPO` app role and an `org_scope` claim for partner users (`/PARTNERS/<ORG>`); a partner identity solution (decision D2) is still open. |
| O17 | Increments 2–3: business modules E03, E05, E06, E07, E14 do not call `ModuleRegistry.requireEnabled`; add the guard to their use cases and jobs (listeners skip) so that `modules.eXX.enabled=false` is enforced. |
| O18 | Increment 3: HRIS / time & attendance / crew-management absence connectors (`AbsenceFeedConnector` beans) and the crew roster status transport (E15). |
| O19 | Increment 3: CMO validation of the 13 surveillance programmes, the evaluation (follow-up) rules and the absence trigger rules; CAA confirmation of the crew medical examination formats. |
| O20 | Increment 3: Entra app roles `CLINIC_SCHEDULER`, `BIOMEDICAL`, `CREW_LICENSING`, `CANDIDATE`; identity source and lifecycle for candidates (`employee_id` = candidate id, `org_scope=/CANDIDATES`). |
| O21 | Increment 3: pre-employment outcome documents are shared with HR only when the candidate is already in the HRIS master; otherwise HR sees the status view and the disclosure is logged. Decide the hand-over of the outcome document at hire. |
| O22 | Increment 3: DPO / CMO approval of the retention classes `ABSENCE_RECORD` (6 years) and `PRE_EMPLOYMENT` (1 year) and of the public-holiday list per country (`absence.public-holidays`). |

## 17. Decisions requested from the steering committee

| ID | Decision | Options | Recommendation |
|---|---|---|---|
| D1 | Primary hosting | AKS · GKE · hybrid (on-prem PHI) | AKS in EU region unless data-residency analysis requires hybrid |
| D2 | Workforce and partner identity | Entra ID + External ID · Entra ID + Keycloak partner realm | Entra ID for workforce; decide partner IdP with E04 |
| D3 | Enterprise broker | Service Bus · Pub/Sub · group Kafka | Follow D1; keep outbox in all cases |
| D4 | Mobile distribution | Public stores + MAM · managed (MDM-only) distribution | MDM-managed distribution with Intune APP for crew devices |
| D5 | Key management | Master key in secret store (today) · envelope encryption with KMS/HSM | Envelope encryption + re-encryption job before go-live |
| D6 | Retention schedule | per classification and record type | DPO + OH governance to define; purge jobs in increment 2 |
| D7 | Outbox retention period | default 30 days (implemented) · other | Confirm 30 days with DPO and operations (replay window) |
| D8 | Policy engine | in-code ABAC (today) · OPA/Cedar externalised | Keep in-code until policy count/complexity justifies externalisation |
| D9 | RPO/RTO targets | as proposed in Section 12 | Approve proposals; fund cross-region replica |
| D10 | Terminology | local tables · terminology server (Snowstorm/Ontoserver) · national service | Terminology server with licensed national editions |
| D11 | CI/CD platform | GitHub Actions · Azure DevOps | GitHub Actions with GitHub Advanced Security |
| D12 | Electronic signature level (increment 2) | simple signature with SHA-256 integrity (today) · advanced / qualified via a trust service provider | Keep simple signature internally; qualified signing for documents with external legal effect |
| D13 | AI-assisted documentation (increment 2) | no provider (today) · locally hosted model · vendor service | Run the evaluation spike with DPIA before enabling any provider |
| D14 | OCR for scanned images (increment 2) | off (today) · Tesseract on-host · Azure AI Document Intelligence / Google Document AI | Tesseract on-host if image volumes justify it; cloud OCR only with DPA and regional endpoint |
| D15 | Telehealth video platform (increment 3) | link only (today) · enterprise meeting service with healthcare terms · dedicated telehealth service · self-hosted WebRTC | Decide with E04; require DPA, regional hosting, waiting room, no recording by default |
| D16 | Cold-chain IoT gateway (increment 3) | manual readings (today) · fridge-vendor gateway pushing to the API · cloud IoT platform feeding the API | Vendor gateway with HTTPS push and certificate credential; keep the core free of device protocols |
| D17 | Candidate identity (increment 3) | local accounts in a candidate realm / External ID tenant · recruiting-system federation | Separate candidate identity with short-lived accounts; never in the workforce directory |

## 18. Roadmap of increments

| Increment | Scope | Key platform work |
|---|---|---|
| **0 – Base framework** (this repository) | Platform services, EHS intake, case kernel, BFF, employee app, workspace, deployment assets, CI/CD workflows | outbox encryption & purge, module flag enforcement, flat-payload guard, BFF group mapping (done) |
| **1 – E01 intake & E02 triage** (delivered, Section 19) | E01 case intake and incident-triggered case initiation; E02 clinical triage, risk stratification, triage queue, SLA and case assignment; OH workspace | `triage` schema, nine new roles, SKIP LOCKED SLA poller, EHS webhook with DLQ, case kernel SPIs (`CaseTransitionGuard`), HRIS employee master |
| **1b – Production readiness** (rescheduled; runs alongside the next increments) | SpotBugs CI job + DAST, envelope encryption + re-encryption job, versioned blind-index key, audit anchoring, secure-storage & MAM plugins, broker adapter (D3), rostering transport, HRIS sync, DR runbook | pen test, SOC 2 readiness assessment |
| **2 – Core pathway** (delivered, Section 20) | E03 OH record & encounters (FHIR mapping), E05 full fitness/RTW, retention jobs (E01 and E02 were pulled forward into increment 1) | terminology server, notification triggers from events; delivered: document service (PDFBox), DPO role, break-glass, disclosure log, retention schedule with legal hold (flagging, no deletion), schemas `docs` / `ohrecord` / `fitness` |
| **3 – Prevention, absence & clinic operations** (delivered, Section 21) | E06 absence & sickness (feed, triggers, certificates, LTA, advice), E07 surveillance / statutory medicals / immunisation (hazard-driven recall, pre-employment, crew certificates), E14 scheduling & clinic operations (pulled forward from increment 5) | delivered: seven kernel SPIs, clinic sites, four roles, schemas `absence` / `surveillance` / `scheduling`, first automatic purge (pre-employment), IoT and telehealth extension points; E11 travel health moved to increment 4 |
| **4 – Restricted class & external parties** | E09 psychological health & peer support, E10 substance testing, E04 referrals & partner portal (consent gate, telehealth video), E08 claims & insurers, E11 travel health | partner IdP, consent enforcement, absence and roster connectors (E15) |
| **5 – Engagement & insight** | E12 health promotion & wearables, E13 extended self-service, warehouse/BI feed, governed predictive models | pseudonymised extracts, model governance |

## 19. Increment 1 – E01 intake and E02 triage on the framework

Increment 1 is the first use of the module SPI by real business modules. The detailed design, requirement
traceability and release notes are in [`docs/increment-01/`](../increment-01/); the endpoint contract is
[`API-Contract-E01-E02.md`](../increment-01/API-Contract-E01-E02.md). This section records the architectural facts.

### 19.1 Module map

| Layer | Component | Increment-1 content |
|---|---|---|
| Business modules (`modules/`) | `wz-e01-intake` → depends on `wz-case-kernel`, `wz-ehs-intake`, `wz-notification` | Intake rules, referrals, encounters, triggers, duplicates, recordability, notification matrix, reconciliation |
| | `wz-e02-triage` → depends on `wz-e01-intake` | Protocols, triage, SLA, assignment, queues, escalation, complexity, safety checklists, QA sampling |
| Case kernel | `wz-case-kernel` | `OpenCaseCommand`, case numbering, timeline (`casemgmt.case_event`), tasks, links, attachments, `CaseTransitionGuard` SPI, new statuses / urgency / safety category |
| Platform | `wz-common`, `wz-security`, `wz-masterdata`, `wz-ehs-intake`, `wz-notification` | Nine roles, `ClinicalTextDetector`, ABAC for new OH roles, HRIS employee and OH staff master, EHS webhook + DLQ, notification templates |
| Front-ends | `web/oh-workspace` (new), `web/employee-app`, `web/manager-workspace` | OH clinical workspace (dev port 5175); self-referral templates with body map, fitness declaration and advice in the employee app; manager and HR referral forms |
| Delivery | Helm component `ohWorkspace`, image `oh-workspace`, host `oh.<domain>`; CI web matrix, `container.yml`, `deploy-env.yml`, Dependabot | See document 04 and the CI/CD Guide |

How the modules plug in (no kernel → module dependency, enforced by ArchUnit):

| Extension point | Provided by | Implemented by |
|---|---|---|
| `CaseModule` (registration, `GET /api/v1/modules` → `IMPLEMENTED`) | kernel | `E01Module`, `E02Module` |
| `CaseTransitionGuard` (veto a status change with `422`) | kernel; called by `CaseService` and `TriageService` | `RecordabilityService` (`422 recordability_required`: an injury / illness case cannot leave `INTAKE` before recordability is classified), `SafetyService` (`422 fitness_required` / `checklist_required`: a safety-critical case cannot close without a fitness decision and the checklist of its safety category) |
| `RecipientResolver` (who receives an intake notification) | E01 | E02 `OhRecipientResolver` (queue distribution lists, case owner, Nursing Head) |
| Events on the outbox | kernel, E01, E02 | E01 consumes `ehs.incident.received.v1` and `case.opened.v1` (duplicates); E02 consumes `case.opened.v1` (assignment and SLA start); E01 dispatches notifications on `case.opened/assigned/escalated` and `sla.at-risk/breached` |

### 19.2 Main runtime flows

1. **EHS incident → case.** Webhook / mailbox / Excel → `intake.ehs_incident` → `ehs.incident.received.v1` →
   E01 rule table → `AUTO_CREATE` opens a case (`CaseService`, `case.opened.v1`), `SUGGEST` parks the incident for
   nurse review (`/intake/suggestions`), `IGNORE` / `NO_MATCH` are recorded for reconciliation. Unidentified employees
   always become suggestions.
2. **Case opened → queue and SLA.** E02 `AssignmentService` reacts to `case.opened.v1`: restricted class →
   `MENTAL_HEALTH`; safety-critical roles → `AME_AEROMEDICAL`, `FOOD_SAFETY` or `PHYSICIAN` with urgency raised to at
   least `SEMI_URGENT`; otherwise the first matching assignment rule. The owner is chosen by least load / round robin
   within caseload caps and out-of-office cover; the `TRIAGE` SLA timer starts.
3. **Triage.** The nurse runs the protocol in the OH workspace; the server evaluates the answers, sets urgency and
   case type, stops the `TRIAGE` timer and publishes `case.triaged.v1`. Advice can be released to the employee
   (`GET /me/advice`).
4. **Escalation.** To physician, CMO, external specialist or emergency services; addressee roles are checked
   server-side; an `ESCALATION_RESPONSE` timer runs.
5. **Status-only outputs.** Fitness changes go to the outbound allow-listed destinations; crew scheduling and the
   catering hygiene manager read status rows through `GET /status/crew` and `GET /status/food-handlers`.

### 19.3 Configuration keys added

| Key | Environment variable | Default | Purpose |
|---|---|---|---|
| `spring.flyway.locations` | `WZ_FLYWAY_LOCATIONS` | `classpath:db/migration` (`local`/`test` profiles add `classpath:db/demo`) | Keeps demo data out of shared environments |
| `wellnessz.triage.sla.poll-interval` | `WZ_SLA_POLL_INTERVAL` | `PT60S` | SLA poller interval (Section 14.12) |
| `wellnessz.notification.oh-workspace-url` | `WZ_OH_WORKSPACE_URL` | `https://oh.wellnessz.local` | Link placed in OH-team notification e-mails |
| `wellnessz.notification.oh-contact` | `WZ_OH_CONTACT` | `Occupational Health service desk` | Contact line in stakeholder notifications |
| `wellnessz.integration.outbound-allowlist.catering-hygiene` | – | status fields only | Outbound destination for food-handler cases |
| `wellnessz.security.cors-origins` (local profile) | `WZ_CORS_ORIGINS` | adds `http://localhost:5175` | OH workspace dev server |
| E18 keys `modules.e02.enabled`, `intake.absence.*`, `triage.complexity.intensive-threshold` | – | seeded by V4 | Business configuration |

## 20. Increment 2 – E03 OH record and E05 fitness for duty on the framework

Increment 2 adds the clinical record and the fitness-for-duty pathway. The detailed design is in
[Solution Design E03/E05](../increment-02/01-Solution-Design-E03-E05.md), requirement coverage in the
[Requirements Traceability Matrix](../increment-02/02-Requirements-Traceability-Matrix.md), the change list in the
[Release Notes](../increment-02/03-Release-Notes-Increment-2.md) and the endpoints in the
[API Contract E03/E05](../increment-02/API-Contract-E03-E05.md). This section records the architectural facts.

### 20.1 Module map

| Layer | Component | Increment-2 content |
|---|---|---|
| Platform | `wz-documents` (new) → depends on `wz-case-kernel`, `wz-notification`, Apache PDFBox | Templates, PDF rendering, e-signature, versioning, sharing with consent and disclosure log, scanned documents with text extraction / OCR SPI, blind-index search, retention schedule and legal hold; schema `docs` |
| Business modules (`modules/`) | `wz-e03-record` → depends on `wz-e02-triage`, `wz-documents` | Chart, encounters, terminology, orders and results, devices, CDS, case summary, FHIR / ODH export, AI SPI; schema `ohrecord` |
| | `wz-e05-fitness` → depends on `wz-e02-triage`, `wz-documents` | Fitness decisions and sign-off, restrictions and matching, duty proposals, RTW plans, job offers, crew and roster feed, food handlers, IHR; schema `fitness` |
| Case kernel | `wz-case-kernel` | `ChartContributor`, `FitnessPublicationListener`, `FitnessFeed`, `EmployeeRecordAccess`, `DiagnosisRequiredGuard`, `CaseEvents.RosterStatusChanged`, primary coded diagnosis on the case |
| Platform services | `wz-security`, `wz-masterdata`, `wz-integration`, `wz-notification`, `wz-common` | `DPO` role, break-glass (`BreakGlassService`, policy in `DefaultPolicies`), disclosure log, `ConsentWithdrawalListener`, consent purpose `AI_DOCUMENTATION`; fitness standards, restriction catalogue, job-demand profiles; `JobRunGuard` (once-per-day jobs across replicas); 11 content-free notification templates and `SecurityAlertListener`; extended `ClinicalTextDetector` |
| BFF | `wz-bff` | `/bff/api/workspace/{team-restrictions,duty-proposals,rtw-plans,documents}` are passed to the core unchanged (other `/workspace/...` paths keep the increment-1 alias behaviour) |
| Front-ends | `web/oh-workspace`, `web/employee-app`, `web/manager-workspace` | Chart, encounters, results, documents, fitness, restrictions, RTW, crew, food handler, IHR, DPO and admin screens; employee documents, RTW plans, job offers, privacy (disclosure log, consent withdrawal); manager / HR restrictions, duty proposals, RTW plans, documents, job offers |
| Delivery | No new image or Helm component; new schemas in all bootstrap paths; new Keycloak role and users | Document 04, Section 18 |

How the modules plug in (no kernel → module, no E03 ↔ E05 dependency, enforced by ArchUnit):

| Extension point | Provided by | Implemented by |
|---|---|---|
| `ChartContributor` (sections and alerts on the chart summary) | kernel; called by E03 `ChartService` | E05 `FitnessChartContributor` (fitness, restrictions) |
| `FitnessPublicationListener` (called inside the transaction that queues a roster message) | kernel `CaseService.publishFitness` | E05 `RosterFeedLog` (acknowledgement and reconciliation log) |
| `CaseTransitionGuard` | kernel | kernel `DiagnosisRequiredGuard` (`422 diagnosis_required`) in addition to the increment-1 guards |
| `ConsentWithdrawalListener` | `wz-security` `ConsentService.withdraw` | `DocumentConsentListener` (revokes partner shares) |
| `OcrProvider` | `wz-documents` | `TesseractOcrProvider` (optional); cloud OCR as an extension point |
| `AiDocumentationProvider` | E03 | none shipped |
| `CrewStatusEnricher` | E02 (crew status view) | E05 (roster status and validity) |

Events added: `encounter.signed.v1`, `result.received.v1` (E03); `fitness.decision.made.v1`,
`fitness.restriction.expiring.v1`, `fitness.job-offer.responded.v1` (to `hr-systems`),
`fitness.food-handler.status.changed.v1` (to `catering-hygiene`) (E05); `crew.roster-status.changed.v1` (kernel, to
`crew-rostering`). The outbound allow-lists were extended accordingly (Section 20.3).

### 20.2 Main runtime flows

1. **Encounter.** The clinician opens an encounter from a case or the chart, fills the versioned template (body map,
   SOAP, observations), codes the diagnosis (terminology search, CDS checks) and signs; corrections are addenda or
   *entered in error*. The primary diagnosis is copied to the case; unsigned encounters are reminded after
   `wellnessz.record.unsigned-after` (default `PT1H`).
2. **Orders and results.** An order creates an HL7 v2 ORM^O01 message in the pull queue; the lab gateway fetches and
   acknowledges it and returns results as JSON or ORU^R01. Device results are captured with calibration ids and rules
   (audiometry standard threshold shift against the baseline, spirometry, vision, ECG). Abnormal results enter the
   worklist; unacknowledged items escalate after `wellnessz.record.result-overdue-after` (default `PT72H`).
3. **Fitness decision.** A clinician decides against a fitness standard; licensed roles need AME / CMO sign-off. The
   kernel publishes status only: `fitness.status.changed.v1` to `hr-systems`, `crew.roster-status.changed.v1` to
   `crew-rostering` (status, dates and operational limitation codes, logged for acknowledgement; unacknowledged after
   15 minutes → audited `ROSTER_FEED_UNACKNOWLEDGED`), food-handler status to `catering-hygiene`. A certificate is
   generated, signed and shared with the employee and manager.
4. **Restrictions and RTW.** Functional restrictions are matched against job-demand profiles; OH proposes modified
   duty; the manager accepts or declines; an RTW plan is acknowledged by employee and manager and reviewed on
   schedule; HR may issue a job offer that the employee answers in the portal. A daily job
   (`wellnessz.fitness.daily-job-cron`) alerts expiring restrictions, reminds / escalates RTW reviews and expires offers.
5. **Restricted class and break-glass.** Mental-health encounters and restricted result codes are
   `RESTRICTED_CLINICAL`. A clinician without restricted access may declare an emergency (justification ≥ 20
   characters) and receives 4 hours of access; the DPO is alerted, reviews within 24 hours and receives a monthly
   report.
6. **Disclosure and consent.** Every share outside OH is recorded in the disclosure log, visible to the employee
   (`GET /me/disclosures`). Withdrawing a consent revokes partner shares made under it immediately.

### 20.3 Configuration keys added

| Key | Environment variable | Default | Purpose |
|---|---|---|---|
| `spring.flyway.schemas` | – | adds `docs, ohrecord, fitness` | Flyway-managed schemas |
| `wellnessz.documents.font-path` | `WZ_DOCUMENTS_FONT_PATH` | empty (Helvetica) | Unicode TrueType font for non-Latin scripts in generated PDFs |
| `wellnessz.documents.retention-cron` | `WZ_DOCUMENTS_RETENTION_CRON` | `0 30 2 * * *` (UTC) | Daily retention flagging run |
| `wellnessz.documents.ocr.tesseract-path` | (relaxed binding `WELLNESSZ_DOCUMENTS_OCR_TESSERACTPATH`) | unset = OCR off | Allow-listed Tesseract binary (Section 14.18) |
| `wellnessz.documents.ocr.languages` | (`WELLNESSZ_DOCUMENTS_OCR_LANGUAGES`) | `eng` | Allow-listed language list |
| `wellnessz.case.diagnosis-required-on-close` | `WZ_DIAGNOSIS_REQUIRED_ON_CLOSE` | `true` | Close guard for clinical cases |
| `wellnessz.notification.employee-portal-url` | `WZ_EMPLOYEE_PORTAL_URL` | `https://employee.wellnessz.local` | Link in employee notifications |
| `wellnessz.notification.manager-workspace-url` | `WZ_MANAGER_WORKSPACE_URL` | `https://manager.wellnessz.local` | Link in manager / HR notifications |
| `wellnessz.notification.dpo-email` | `WZ_DPO_EMAIL` | `dpo@wellnessz.local` | Recipient of content-free break-glass alerts |
| `wellnessz.record.unsigned-after` | – | `PT1H` | Reminder for unsigned encounters |
| `wellnessz.record.result-overdue-after` | – | `PT72H` | Escalation of unacknowledged abnormal results |
| `wellnessz.record.overdue-cron` | – | `0 15 * * * *` | Hourly escalation job |
| `wellnessz.record.restricted-result-codes` | – | `5643-2,19659-3` | LOINC codes stored in the restricted class |
| `wellnessz.record.ai.provider` | – | `none` | Name of an `AiDocumentationProvider` bean (none shipped) |
| `wellnessz.fitness.daily-job-cron` | – | `0 20 2 * * *` (UTC) | Restriction expiry, RTW reviews, offer expiry |
| `wellnessz.fitness.roster-reconciliation-interval` | – | `PT5M` | Check for roster messages unacknowledged after 15 minutes |
| `wellnessz.fitness.ihr-appeal-window-days` | – | `28` | Appeal window after an IHR decision |
| `wellnessz.fitness.ihr-appeal-decision-days` | – | `30` | Target for the appeal decision |
| `wellnessz.integration.outbound-allowlist.crew-rostering` | – | `messageId, employeeId, fitnessStatus, rosterStatus, restrictionUntil, validFrom, validUntil, operationalLimitations, effectiveFrom` | Status-only roster feed |
| `wellnessz.integration.outbound-allowlist.hr-systems` | – | adds `messageId, offerId, accepted, respondedAt` | Job-offer outcome only |
| `wellnessz.integration.outbound-allowlist.catering-hygiene` | – | adds `foodHandlerStatus, excludedFrom, expectedReturn` | Food-handler status only |

## 21. Increment 3 – E06 absence, E07 surveillance and E14 scheduling on the framework

Increment 3 adds absence and sickness management, medical surveillance (including pre-employment, crew medical
certificates and immunisation) and clinic operations. The detailed design is in
[Solution Design E06/E07/E14](../increment-03/01-Solution-Design-E06-E07-E14.md), requirement coverage in the
[Requirements Traceability Matrix](../increment-03/02-Requirements-Traceability-Matrix.md), the change list in the
[Release Notes](../increment-03/03-Release-Notes-Increment-3.md) and the endpoints in the
[API Contract E06/E07/E14](../increment-03/API-Contract-E06-E07-E14.md). This section records the architectural facts
for the CIO and Cyber GRC.

### 21.1 Module map

| Layer | Component | Increment-3 content |
|---|---|---|
| Business modules (`modules/`) | `wz-e06-absence` → depends on `wz-e05-fitness`, `wz-documents` | Absence feed and reconciliation, trigger rules and queue, certificates, LTA pathway, advice requests and reports, analytics; schema `absence` (V10) |
| | `wz-e07-surveillance` → depends on `wz-e03-record`, `wz-e05-fitness`, `wz-documents` | Programmes and enrolment, due lists and invitations, exposure registers, follow-ups, return after illness, pre-employment and candidate portal, crew certificates, immunisation, compliance and audit packs; schema `surveillance` (V11) |
| | `wz-e14-scheduling` → depends on `wz-e02-triage`, `wz-documents` | Service catalogue, resources and diaries, booking, waitlist, self-booking, check-in and queue, campaigns, equipment and calibration, cold chain, capacity; schema `scheduling` (V12) |
| Case kernel | `wz-case-kernel` (`casekernel.support`) | Seven SPIs: `AppointmentBooking`, `AppointmentStatusListener`, `DeviceCalibrationCheck`, `ResultFollowUpListener`, `FitnessClearanceGuard`, `AbsenceSignalListener`, `CapacityDemandSource` |
| Existing modules | `wz-e03-record`, `wz-e05-fitness` | `ResultService` consults `DeviceCalibrationCheck` before device results and notifies `ResultFollowUpListener` for LIS and device results; `FitnessDecisionService` consults `FitnessClearanceGuard` before FIT / FIT_WITH_LIMITATIONS (`422 clearance_required`) |
| Platform services | `wz-common`, `wz-security`, `wz-masterdata`, `wz-documents`, `wz-notification` | Roles `CLINIC_SCHEDULER`, `BIOMEDICAL`, `CREW_LICENSING`, `CANDIDATE` (and their disclosure level in `/me`); clinic sites, hazards, job codes (V9); document templates `OH_ADVICE_REPORT`, `VACCINATION_CERTIFICATE`, `PRE_EMPLOYMENT_OUTCOME` and the module-level API (`storeUpload`, `generateSigned`, `shareAsSystem`, `systemContent`); 23 content-free notification templates; retention classes `ABSENCE_RECORD`, `PRE_EMPLOYMENT` |
| BFF | `wz-bff` | `/bff/api/workspace/{absence,advice-requests,surveillance,pre-employment}` keep the `/workspace` prefix and are passed to the core unchanged; multipart parsing switched off (`spring.servlet.multipart.enabled=false`) so uploads are streamed untouched |
| Front-ends | `web/oh-workspace`, `web/employee-app`, `web/manager-workspace` | OH: absence queue, rules, feed quality, certificates, LTA, advice, analytics; programmes, due list, exposure, equipment, follow-ups, return after illness, pre-employment, crew, immunisation, compliance; diary, reception kiosk, queue board, campaigns, capacity, catalogue; chart tabs *Absence* and *Surveillance*. Employee: absence and certificate upload, advice-report review, surveillance and immunisations, appointments (self-booking, reschedule, check-in), candidate portal. Manager / HR: team absence (managers: table only), advice requests, surveillance compliance, pre-employment (HR) |
| Delivery | No new image or Helm component; three schemas in all bootstrap paths; four realm roles and users | Document 04, Section 19 |

The modules plug in as follows (no kernel → module dependency, E06 / E07 / E14 mutually independent, E03 / E05
independent of increment 3 – all enforced by ArchUnit):

```mermaid
flowchart LR
  subgraph Kernel["wz-case-kernel SPIs (casekernel.support)"]
    AB[AppointmentBooking]
    ASL[AppointmentStatusListener]
    DCC[DeviceCalibrationCheck]
    RFL[ResultFollowUpListener]
    FCG[FitnessClearanceGuard]
    ASig[AbsenceSignalListener]
    CDS[CapacityDemandSource]
  end
  E03[E03 record<br/>ResultService] -->|calls| DCC
  E03 -->|notifies| RFL
  E05[E05 fitness<br/>FitnessDecisionService] -->|consults| FCG
  E06[E06 absence<br/>LtaService] -->|signals| ASig
  E06 -->|books via| AB
  E07[E07 surveillance<br/>Booker] -->|books via| AB
  E14[E14 scheduling] -.implements.-> AB
  E14 -.implements.-> DCC
  E14 -->|calls| ASL
  E14 -->|reads| CDS
  E07 -.implements.-> RFL
  E07 -.implements.-> FCG
  E07 -.implements.-> ASig
  E07 -.implements.-> ASL
  E07 -.implements.-> CDS
  E06 -.implements.-> ASL
  E06 -.implements.-> CDS
```

| Extension point | Called by | Implemented by | Contract |
|---|---|---|---|
| `AppointmentBooking` | E07 invitations and campaigns (`Booker`), E06 LTA contact schedule | E14 `AppointmentBookingAdapter` | `bookNext` books the first free slot or returns empty; with no implementation deployed callers degrade to "invited, not booked" |
| `AppointmentStatusListener` | E14 `StatusNotifier` (inside the appointment transaction) | E07 `SurveillanceSpi` (re-invitation after a no-show), E06 `AbsenceCapacityDemand` | `BOOKED`, `CHECKED_IN`, `ATTENDED`, `NO_SHOW`, `CANCELLED` for appointments whose `sourceType` the listener supports |
| `DeviceCalibrationCheck` | E03 `ResultService` (device results) | E14 `EquipmentCalibrationCheck` | `OUT_OF_CALIBRATION` / `OUT_OF_SERVICE` → `422 device_out_of_calibration` / `device_out_of_service`; `UNKNOWN` accepted unless `wellnessz.record.require-registered-devices=true` |
| `ResultFollowUpListener` | E03 `ResultService` (LIS, HL7 ORU, device results; same transaction) | E07 `FollowUpService` | Evaluates programme rules (for example audiometric standard threshold shift) and opens follow-up cases; identifiers and codes only |
| `FitnessClearanceGuard` | E05 `FitnessDecisionService` | E07 `RaiService` | Returns a non-clinical reason when a return-after-serious-illness medical is outstanding → `422 clearance_required` |
| `AbsenceSignalListener` | E06 `LtaService` (once, when the long-term threshold is crossed) | E07 `RaiService` | Creates the return-after-illness requirement for safety-critical jobs; never receives the absence reason |
| `CapacityDemandSource` | E14 `CapacityService` | E07 `SurveillanceSpi` (due examinations), E06 `AbsenceCapacityDemand` (LTA contacts) | Monthly counts per site and service – never person-level data |
| `ChartContributor` (increment 2) | E03 `ChartService` | E07 `SurveillanceChartContributor` (in addition to E05) | Surveillance section and alerts on the chart |
| `AbsenceFeedConnector` (module SPI) | E06 `absence-feed` job | `NoopAbsenceFeedConnector` (default) | One bean per source system (`HRIS`, `TNA`, `CREW`); rows reconciled like pushed rows |

Events added: `absence.trigger.raised.v1`, `absence.lta.started.v1`, `absence.advice.released.v1`,
`absence.hr-status.v1` (to `hr-systems`: `ohEngaged`, `certificateValidity`) (E06); `surveillance.enrolment.due.v1`,
`surveillance.followup.created.v1`, `preemployment.status.v1` (to `hr-systems`: `offerRef`, `candidateId`,
`preEmploymentStatus`), `crew.certificate.status.v1` (E07); `appointment.status.v1`, `equipment.calibration.v1`,
`coldchain.excursion.v1` (E14). Crew roster status continues to flow through the kernel (`CaseService.publishFitness`).

### 21.2 Main runtime flows

1. **Absence feed → early intervention.** Episodes arrive by push or the nightly pull and are reconciled against the
   HRIS master. The `absence-triggers` job evaluates the versioned rules (for example `DURATION_10D`, frequency,
   Monday / Friday and post-holiday patterns; `minDays` means "at least"); a match opens a queue item. When OH accepts
   it, a case is opened or linked and the first contact is recorded (KPI trigger → contact); the line manager and HR receive the content-free
   `absence.oh-engaged` notice and HR receives `ohEngaged` through the outbound allow-list.
2. **Certificates.** The employee photographs a sick certificate in the app (`storeUpload`, stored as an OH document);
   OH validates it; HR sees **validity only** (`certificateValidity`), never the content. Reception may scan
   certificates and run the validity checks but can neither open their content nor verify them.
3. **Long-term absence.** Crossing the threshold starts the LTA pathway (milestones at weeks 2, 4, 8, 12 and 26 with
   overdue alerts, fortnightly contacts, case conferences, an HR stage that rejects clinical text with
   `422 clinical_content_not_allowed`), signals `AbsenceSignalListener` and books the first contact through
   `AppointmentBooking`; completing the RTW review closes the pathway.
4. **Management advice.** A manager or HR asks OH a structured question (answers capped at 400 characters); OH writes
   the advice report (functional advice only – clinical text is rejected), signs it, and the report waits in
   `AWAITING_EMPLOYEE` until the employee consents to its release (`absence.advice.employee-review-required`). Release
   generates `OH_ADVICE_REPORT`, shares it with the requester (line manager for manager requests, HR otherwise) and
   writes the disclosure log; a declined report is withheld.

```mermaid
sequenceDiagram
  participant M as Manager / HR
  participant OH as OH clinician
  participant E as Employee
  participant WZ as Core (E06 + documents)
  M->>WZ: POST advice request (structured questions)
  OH->>WZ: write report – clinical-text guard
  OH->>WZ: sign report
  WZ-->>E: content-free "advice to review" notice
  E->>WZ: consent to release (or decline)
  WZ->>WZ: OH_ADVICE_REPORT generated, signed, shared
  WZ->>WZ: disclosure log (requester, purpose)
  WZ-->>M: "advice released" – fitness and functional advice only
```

5. **Surveillance recall.** The nightly `surveillance-due` job enrols employees from job code, hazards and age (13
   programmes), raises due events, sends invitations, books slots through `AppointmentBooking`, re-invites after a
   no-show and asks the line manager to support attendance after `surveillance.max-invitations`. IH measurements above
   the action level bring the similar-exposure group into the programme.
6. **Results → follow-up and clearance.** Device and LIS results recorded in E03 are evaluated by E07 rules; abnormal
   findings open follow-up cases. A long-term absence in a safety-critical job creates a return-after-illness
   requirement; until it is completed, a FIT decision in E05 is refused with `422 clearance_required`.
7. **Pre-employment.** Recruiting posts the offer; the candidate (role `CANDIDATE`, not in the HRIS master) completes
   questionnaire and consent and books in the portal; OH records the outcome; HR receives the status only
   (`preEmploymentStatus`), with the disclosure logged. Non-hired candidates are purged after 12 months.
8. **Crew certificates and immunisation.** Crew medical certificates are tracked with expiry alerts to the crew member,
   AME and crew licensing (status view `EXPIRING` / without valid medical); immunisations are entered quickly with GS1
   barcodes, titres and reminders, and a `VACCINATION_CERTIFICATE` can be issued.
9. **Clinic day.** Scheduler or employee books (conflict-checked); the employee checks in by badge, app or kiosk
   (window from 60 minutes before the start); the queue board shows tickets only; *call next* hands the clinician to
   the chart. Device results are refused on out-of-calibration equipment; fridge readings outside 2–8 °C open an
   excursion that a clinician must acknowledge. Campaigns book cohorts; capacity compares diary supply with module
   demand.

### 21.3 Scheduled jobs added

All jobs run in the core, use cron expressions in UTC and run once per run key across replicas (`JobRunGuard`).

| Job | Property (default) | Purpose |
|---|---|---|
| `absence-feed` | `wellnessz.absence.feed-cron` (`0 40 2 * * *`) | Pull from every `AbsenceFeedConnector`, reconcile |
| `absence-triggers` | `wellnessz.absence.triggers-cron` (`0 0 3 * * *`) | Evaluate trigger rules, fill the early-intervention queue |
| `absence-lta` | `wellnessz.absence.lta-cron` (`0 10 3 * * *`) | Start LTA pathways, milestone and overdue alerts |
| `surveillance-due` | `wellnessz.surveillance.due-job-cron` (`0 40 1 * * *`) | Enrolment, due events, invitations, re-invitations, immunisation reminders |
| `crew-certificates` | `wellnessz.surveillance.crew-job-cron` (`0 50 1 * * *`) | Expiry alerts and expiry of crew medical certificates |
| `pre-employment-purge` | `wellnessz.surveillance.purge-job-cron` (`0 10 3 * * *`) | Anonymise non-hired candidates after 12 months |
| `appointment-reminders` | `wellnessz.scheduling.reminder-cron` (`0 */15 * * * *`) | Appointment reminders |
| `appointment-no-show` | `wellnessz.scheduling.no-show-cron` (`0 5/15 * * * *`) | Mark no-shows, notify the source module |
| `equipment-calibration` | `wellnessz.scheduling.calibration-cron` (`0 40 2 * * *`) | Calibration-due alerts to biomedical engineering |

### 21.4 Configuration keys added

| Key | Where | Default | Purpose |
|---|---|---|---|
| `spring.flyway.schemas` | `application.yml` | adds `absence, surveillance, scheduling` | Flyway-managed schemas |
| `wellnessz.integration.outbound-allowlist.hr-systems` | `application.yml` | adds `offerRef, candidateId, preEmploymentStatus, ohEngaged, certificateValidity` | Pre-employment status, OH engagement, certificate validity – status only |
| `wellnessz.record.require-registered-devices` | property (relaxed binding `WELLNESSZ_RECORD_REQUIREREGISTEREDDEVICES`) | `false` | Refuse device results from devices not in the E14 register |
| Job crons of Section 21.3 | properties | see 21.3 | Job schedules |
| `wellnessz.scheduling.telehealth-base-url` | property | `https://telehealth.wellnessz.local/room/` | Fallback when the E18 key below is not set |
| `spring.servlet.multipart.enabled` (BFF) | `apps/wz-bff/.../application.yml` | `false` | BFF streams uploads to the core untouched |
| `modules.e06.enabled`, `absence.notify-hr-on-engagement`, `absence.advice.employee-review-required`, `absence.hr-email`, `absence.queue.sla-days`, `absence.queue.ame-sla-days`, `absence.advice.sla-working-days`, `absence.lta.contact-interval-days`, `absence.public-holidays` | E18 (V10) | `true`, `true`, `true`, `hr-absence@wellnessz.local`, `5`, `1`, `5`, `14`, UK 2026–2027 holidays | Absence business configuration |
| `surveillance.max-invitations`, `surveillance.cosmic-dose-alert-msv`, `surveillance.new-enrolment-grace-days`, `surveillance.pre-employment-sla-days`, `surveillance.crew.issuing-authority`, `notifications.crew-licensing.email`, `notifications.hr-onboarding.email` | E18 (V11) | `2`, `6.0`, `30`, `10`, `UK CAA`, `crew-licensing@…`, `hr-onboarding@…` | Surveillance business configuration and status-only mailboxes |
| `scheduling.self-booking.min-notice-hours`, `.max-days-ahead`, `.one-open-per-type`, `scheduling.check-in.window-minutes`, `scheduling.no-show.grace-minutes`, `scheduling.telehealth-base-url`, `scheduling.calibration.due-alert-days`, `notifications.biomedical.email` | E18 (V12) | `24`, `60`, `true`, `60`, `30`, `https://telehealth.wellnessz.local/room/`, `30`, `biomedical@…` | Scheduling business configuration |

Mailbox addresses and the telehealth URL must be set per environment through `PUT /api/v1/masterdata/config/{key}`
(OH_ADMIN / PLATFORM_ADMIN, audited) before go-live.

### 21.5 Testing note – per-run keys

`IntegrationTestBase` generates `WZ_MASTER_KEY` and `WZ_PSEUDONYM_KEY` randomly for every test JVM, so values
encrypted by an earlier run cannot be decrypted by the next one. With Testcontainers this is invisible (fresh
container per run); with a persistent test database (`WZ_TEST_DB_URL`) **drop and recreate the test database before
every run**. The E07 demo return-after-illness requirement for `E1004` is skipped on databases whose name contains
`_test` so that it does not interfere with the E05 integration tests.

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

Increment 1 adds endpoints under `/api/v1/intake/**`, `/api/v1/triage/**`, `/api/v1/queues/**`,
`/api/v1/cases/{id}/…`, `/api/v1/escalations/**`, `/api/v1/assignment/**`, `/api/v1/sla/**`, `/api/v1/staff/**`,
`/api/v1/status/**`, `/api/v1/employees/{id}/timeline`, `/api/v1/me/advice` and `/api/v1/masterdata/employees`. The
authoritative list with roles is the [API Contract E01/E02](../increment-01/API-Contract-E01-E02.md); selected entries:

| Method & path | Roles | Module |
|---|---|---|
| `POST /api/v1/intake/ehs/incidents` (Idempotency-Key) | INTEGRATION | wz-ehs-intake |
| `GET /api/v1/intake/dlq`, `POST /intake/dlq/{id}/replay\|discard` | OH_ADMIN, PLATFORM_ADMIN | wz-ehs-intake |
| `POST /api/v1/intake/referrals/self\|manager\|hr` | EMPLOYEE / MANAGER / HR (clinical-text guard on manager and HR) | wz-e01-intake |
| `GET\|POST\|PUT /api/v1/intake/rules`, `POST /intake/rules/test` | read: OH roles, OH_ADMIN, HSE_OFFICER; write: OH_ADMIN, NURSING_HEAD | wz-e01-intake |
| `POST /api/v1/intake/encounters`, `POST /intake/encounters/{id}/convert` | CLINIC_RECEPTION, FIRST_AIDER, OH roles / convert: OH clinical roles | wz-e01-intake |
| `GET /api/v1/queues/{queue}`, `GET /queues/counts` | OH roles | wz-e02-triage |
| `POST /api/v1/cases/{id}/triage` | OH triage roles | wz-e02-triage |
| `POST /api/v1/triage/protocols/{code}/approve` | CMO | wz-e02-triage |
| `GET /api/v1/status/crew`, `GET /status/food-handlers` | CREW_SCHEDULING / HYGIENE_MANAGER (status fields only) | wz-e02-triage |
| `POST /api/v1/masterdata/employees` | INTEGRATION, OH_ADMIN, PLATFORM_ADMIN | wz-masterdata |

Increment 2 adds endpoints under `/api/v1/employees/{id}/…` (chart, problems, allergies, medications,
immunisations, jobs, encounters, orders, results, fitness, `fhir`, `odh`), `/api/v1/encounters/**`,
`/api/v1/encounter-templates/**`, `/api/v1/terminology/**`, `/api/v1/results/**`, `/api/v1/cds/**`,
`/api/v1/documents/**`, `/api/v1/break-glass/**`, `/api/v1/disclosures`, `/api/v1/me/{documents,disclosures,rtw-plans,job-offers}`,
`/api/v1/workspace/{team-restrictions,duty-proposals,rtw-plans,documents}`, `/api/v1/partner/documents`,
`/api/v1/fitness/**`, `/api/v1/restrictions/**`, `/api/v1/rtw-plans/**`, `/api/v1/crew/**`,
`/api/v1/masterdata/{fitness-standards,restrictions,job-demands}` and the machine endpoints
`/api/v1/integration/lis/**` and `/api/v1/integration/roster-feed/**` (240 paths in total). The authoritative list
with roles is the [API Contract E03/E05](../increment-02/API-Contract-E03-E05.md); selected entries:

| Method & path | Roles | Module |
|---|---|---|
| `GET /api/v1/employees/{id}/chart`, `GET /employees/{id}/fhir`, `GET /employees/{id}/odh` | OH roles + record-level ABAC | wz-e03-record |
| `POST /api/v1/encounters/{id}/sign` | clinicians | wz-e03-record |
| `GET /api/v1/integration/lis/orders`, `POST /integration/lis/orders/{id}/ack`, `POST /integration/lis/results` | INTEGRATION | wz-e03-record |
| `POST /api/v1/employees/{id}/results/device` | OH roles, INTEGRATION | wz-e03-record |
| `POST /api/v1/documents/generate`, `POST /documents/{id}/sign`, `POST /documents/{id}/share` | OH roles; HR for its own non-clinical templates (job offer, employee target only) | wz-documents |
| `GET\|PUT /api/v1/documents/retention…`, `POST\|DELETE /documents/{id}/legal-hold` | DPO (schedule read also OH_ADMIN) | wz-documents |
| `POST /api/v1/break-glass`; `GET /break-glass`, `POST /break-glass/{id}/review`; `GET /break-glass/report` | clinicians; DPO; DPO, CMO | wz-security |
| `POST /api/v1/cases/{id}/fitness-decisions`, `POST /fitness-decisions/{id}/signoff` | fitness deciders; AME / CMO per standard | wz-e05-fitness |
| `GET /api/v1/integration/roster-feed`, `POST /integration/roster-feed/{messageId}/ack` (`/crew/roster-feed` for people) | INTEGRATION; CREW_SCHEDULING (read) | wz-e05-fitness |
| `GET /api/v1/workspace/team-restrictions`, `GET /workspace/duty-proposals`, `GET /workspace/rtw-plans` | MANAGER, HR (functional restrictions and status only) | wz-e05-fitness |

Increment 3 adds endpoints under `/api/v1/absence/**` (32 paths), `/api/v1/surveillance/**` (47),
`/api/v1/scheduling/**` (39), `/api/v1/me/{absence,advice-reports,appointments,immunisations,pre-employment,pregnancy-declaration,surveillance}`,
`/api/v1/workspace/{absence,advice-requests,surveillance,pre-employment}`, `/api/v1/masterdata/clinic-sites` and the
machine endpoints `/api/v1/integration/absence/**`, `/integration/ih-measurements`, `/integration/cosmic-dose`,
`/integration/pre-employment` and `/integration/cold-chain/readings` (391 paths in total). The authoritative list with
roles is the [API Contract E06/E07/E14](../increment-03/API-Contract-E06-E07-E14.md); selected entries:

| Method & path | Roles | Module |
|---|---|---|
| `POST /api/v1/integration/absence/episodes`, `POST /integration/absence/certificates` | INTEGRATION | wz-e06-absence |
| `GET /api/v1/absence/queue`, `POST /absence/queue/{id}/accept` | OH roles | wz-e06-absence |
| `GET /api/v1/workspace/absence/team`, `/workspace/absence/lta`, `/workspace/absence/certificates`; `GET\|POST /workspace/advice-requests` | MANAGER, HR within org scope (status only, no reason category; LTA and certificate validity shown to HR in the UI) | wz-e06-absence |
| `POST /api/v1/me/advice-reports/{id}/decision` | EMPLOYEE (consent to release or decline) | wz-e06-absence |
| `GET /api/v1/surveillance/due`, `POST /surveillance/invitations` | OH roles, CLINIC_SCHEDULER | wz-e07-surveillance |
| `GET /api/v1/surveillance/crew/certificates`, `/surveillance/crew/without-valid-medical` | AME, CMO; CREW_LICENSING, CREW_SCHEDULING without answers or documents | wz-e07-surveillance |
| `GET /api/v1/me/pre-employment`, `POST /me/pre-employment/{questionnaire,consent,documents,book}` | CANDIDATE | wz-e07-surveillance |
| `POST /api/v1/surveillance/audit-packs` | CMO, NURSING_HEAD (identifiable or anonymised), AUDITOR, HSE_OFFICER (anonymised only) | wz-e07-surveillance |
| `POST /api/v1/integration/ih-measurements`, `/integration/cosmic-dose`, `/integration/pre-employment` | INTEGRATION | wz-e07-surveillance |
| `GET /api/v1/me/appointments/slots`, `POST /me/appointments`, `POST /me/appointments/{id}/{reschedule,cancel,check-in}` | EMPLOYEE | wz-e14-scheduling |
| `POST /api/v1/scheduling/appointments`, diary, waitlist, campaigns | OH roles, CLINIC_SCHEDULER (reception: diary, attendance, check-in, queue) | wz-e14-scheduling |
| `GET\|POST /api/v1/scheduling/equipment/**`, calibrations | BIOMEDICAL, OH_ADMIN (write); OH roles, CLINIC_SCHEDULER (read) | wz-e14-scheduling |
| `POST /api/v1/integration/cold-chain/readings` | INTEGRATION | wz-e14-scheduling |

## Appendix B – Glossary

| Term | Meaning |
|---|---|
| ABAC | Attribute-based access control – decisions based on attributes of user, action and resource |
| BFF | Backend-for-Frontend – server component holding OAuth tokens on behalf of a browser app |
| CSI | Container Storage Interface; the Secrets Store CSI driver mounts secrets from a vault as files |
| EHS | Environment, Health & Safety incident-management system |
| HKDF | HMAC-based key derivation function (RFC 5869) |
| MAM / MDM | Mobile application management / mobile device management |
| Blind-index token | Keyed HMAC of a value (here: of a word) that allows equality search without storing the plaintext |
| Break-glass | Time-limited, justified and reviewed emergency access to restricted records |
| DPO | Data Protection Officer |
| ODH | HL7 Occupational Data for Health |
| Outbox | Pattern that writes events in the same DB transaction as the business change and relays them afterwards |
| PKCE | Proof Key for Code Exchange (RFC 7636) |
| RPO / RTO | Recovery point / recovery time objective |
| Status-only | Disclosure level exposing case status, fitness status and dates without clinical content |
| Bradford factor | Absence measure S² × D (spells squared times days); reported only as a suppressed unit mean |
| Cold chain | Temperature-controlled storage of vaccines (2–8 °C); an excursion is a period outside the range |
| GS1 | Global barcode standard; vaccine packs carry GTIN, lot, expiry and serial as application identifiers |
| IH / SEG | Industrial hygiene / similar-exposure group – workers with comparable exposure, measured as a group |
| LTA | Long-term absence |
| RAI | Return after (serious) illness – medical required before a safety-critical employee returns |
| Small-group suppression | Not publishing an aggregate whose group is smaller than the minimum group size (k) |
