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
role, break-glass access and a disclosure log; **increment 3** delivered E06 (absence & sickness management), E07
(medical surveillance, statutory medicals & immunisation, including pre-employment and crew medical certificates) and
E14 (scheduling, clinic operations & resource management: diaries, self-booking, check-in and queue, equipment
calibration, cold chain, campaigns, capacity), seven kernel SPIs that connect them without module-to-module
dependencies, clinic sites, and the roles `CLINIC_SCHEDULER`, `BIOMEDICAL`, `CREW_LICENSING` and `CANDIDATE`;
**increment 4** delivered E04 (injury & illness management, treatment coordination & partner referrals: treatment
plans, e-referrals with FHIR R4, provider network and scorecards, pre-authorisation and invoices, clinic medication,
outcomes, telehealth) and E08 (workers' compensation, insurance & claims coordination: claim lifecycle, statutory and
insurer first reports, consent-gated disclosure sets, adjuster collaboration, cost ledger and ERP export, litigation
hold and medico-legal support), the **partner portal** for external providers and TPA adjusters (MFA required),
four kernel SPIs, partner organisations and the roles `CLAIMS_COORDINATOR`, `TPA_ADJUSTER`, `LEGAL_COUNSEL`, `FINANCE`
and `PROCUREMENT`. The final increment 5 covers the restricted classes and health promotion (E09–E12).

> Core rule: clinical data stays inside the Occupational Health domain; operations and HR receive
> **status-only** outputs. It is enforced in the ABAC policy engine, in the API projections, at the outbound
> integration allow-list, and by automated tests.

## Repository map

| Path | What |
|---|---|
| `platform/wz-common` | Shared kernel: RFC 9457 errors, correlation ids, classification & disclosure enums, IdP-neutral role mapping |
| `platform/wz-secrets` | `ENC(...)` AES-256-GCM config secrets, `file:` mounted secrets, field-level encryption, `EncryptCli` |
| `platform/wz-security` | E16 – multi-issuer OIDC resource server, ABAC policy engine, consent, hash-chained audit; MFA gate for external roles (`mfa-required-roles`, increment 4) |
| `platform/wz-masterdata` | E18 – org hierarchy, job codes, hazards, versioned configuration / feature flags; clinic sites (increment 3); partner organisations – providers, insurers, TPA, authorities (increment 4) |
| `platform/wz-integration` | E15 – transactional outbox event bus, idempotency, replay, FHIR R4, HL7 v2, terminology, connectors |
| `platform/wz-notification` | Push / SMS / e-mail gateway with content rules and quiet hours |
| `platform/wz-analytics` | E17 – de-identified KPIs, small-cell + complementary suppression, pseudonymisation |
| `platform/wz-case-kernel` | Case aggregate, pathway state machine, status-only projection, `CaseModule` and `CaseTransitionGuard` SPIs, case numbering, timeline, tasks, attachments; increment 3: `AppointmentBooking`, `AppointmentStatusListener`, `DeviceCalibrationCheck`, `ResultFollowUpListener`, `FitnessClearanceGuard`, `AbsenceSignalListener`, `CapacityDemandSource` SPIs; increment 4: `CaseCostRecorder`, `ExternalReferralGateway`, `ReferralStatusListener`, `ClaimContextProvider` |
| `platform/wz-documents` | Document service (increment 2): code-defined templates, PDF rendering with Apache PDFBox, SHA-256 e-signature, versioning, sharing with consent and disclosure log, scanned documents with optional OCR, blind-index search, retention schedule and legal hold; increment 4: `INSURER` share target, case-level legal hold, treatment plan / pre-authorisation / first report / medico-legal templates |
| `platform/wz-ehs-intake` | EHS feed: IMAP / Microsoft Graph mailbox reader and hardened Excel import |
| `modules/wz-e01-intake` | E01 – intake rule table, EHS incident rules, referrals, walk-in/emergency encounters, system triggers, duplicates, recordability, notification matrix |
| `modules/wz-e02-triage` | E02 – triage protocols (versioned JSON), SLA timers, assignment engine, work queues, escalations, complexity, status-only crew / food-handler APIs |
| `modules/wz-e03-record` | E03 (increment 2, schema `ohrecord`) – OH chart, versioned encounter templates (JSON) with body map and e-signature, terminology, lab orders / results (HL7 v2 pull queue, ORU), device capture, CDS, FHIR R4 / ODH export, AI documentation SPI (no provider) |
| `modules/wz-e05-fitness` | E05 (increment 2, schema `fitness`) – fitness decisions and sign-off, restrictions and job-demand matching, modified duty, RTW plans, job offers, crew roster feed with acknowledgement, food handlers, ill-health retirement |
| `modules/wz-e06-absence` | E06 (increment 3, schema `absence`) – absence feed (push endpoint + `AbsenceFeedConnector` pull SPI) with reconciliation, versioned trigger rules and early-intervention queue, sick certificates (upload, validation, HR sees validity only), long-term absence pathway with milestones, management advice requests and OH advice reports released after employee review, absence analytics with small-group suppression |
| `modules/wz-e07-surveillance` | E07 (increment 3, schema `surveillance`) – hazard-driven surveillance programmes, due lists and invitations, exposure registers (IH measurements, cosmic dose), result follow-up, return-after-illness clearance guard, pre-employment with candidate portal and 12-month purge, crew medical certificates, immunisation registry (GS1 barcodes), compliance and anonymised audit packs |
| `modules/wz-e04-treatment` | E04 (increment 4, schema `treatment`) – treatment plans and follow-ups with recovery-variance alerts, e-referrals (data set, consent, signed letter, FHIR R4 `ServiceRequest` / `DocumentReference`) with chase and escalation, provider directory, contracts, network gaps and scorecards, pre-authorisation and invoice matching, clinic formulary / stock / administration, outcome measures with closure guard, telehealth (`TelehealthProvider` SPI, single-use tokens, secure chat), `EPrescribingGateway` (no-op) |
| `modules/wz-e08-claims` | E08 (increment 4, schema `claims`) – claim record and lifecycle with case badge, statutory and insurer first reports with deadlines (`ClaimsSubmissionGateway` SPI), consent-gated disclosure sets with redaction, automatic nurse case manager and adjuster collaboration, cost ledger / roll-ups / budgets / ERP export, litigation hold, privilege and medico-legal requests; never reads the clinical record |
| `modules/wz-e14-scheduling` | E14 (increment 3, schema `scheduling`) – service catalogue, clinic resources and diary templates, booking with conflict detection, waitlist, self-booking, check-in and queue board, campaigns, equipment register with calibration (blocks device results), cold-chain readings (manual and IoT) with excursions, capacity forecast, telehealth links |
| `apps/wz-core-app` | Runnable core API (8080, management 8081) + Flyway migrations (`db/migration`; demo data `db/demo` for the `local` profile only) |
| `apps/wz-bff` | Backend-for-Frontend (8090): OIDC login, encrypted JDBC sessions, CSRF, token relay |
| `web/employee-app` | React + Capacitor crew app (PWA / iOS / Android, MDM AppConfig); increment 3: my absence and certificates, advice-report review, surveillance and immunisations, appointments with self-booking and check-in, candidate (pre-employment) portal; increment 4: my treatment plan, telehealth sessions (consent, join, chat) |
| `web/manager-workspace` | React manager & HR workspace (status-only, manager and HR referrals); increment 3: team absence, OH advice requests, surveillance compliance, pre-employment clearance (HR); increment 4: claim status (HR) |
| `web/oh-workspace` | React OH clinical workspace (dev port 5175): queues, triage, assignment, escalation, intake operations, configuration, reception; chart, encounters, fitness (increment 2); absence, surveillance, scheduling and clinic operations (increment 3); treatment, referrals, providers, medications, telehealth, claims, costs and legal (increment 4) |
| `web/partner-portal` | React partner portal (increment 4, dev port 5176, host `partners.<domain>`) for external partner providers (referral inbox, reports, invoices, telehealth, documents) and TPA adjusters (claims, tasks, messages, pre-authorisation decisions); MFA required |
| `deploy/` | Docker Compose (local), Dockerfiles, Helm chart, Terraform (Azure, GCP), hybrid blueprint |
| `.github/` | CI, CodeQL, container build/sign, CD workflows, Dependabot |
| `docs/` | Architecture, SOC 2 mapping, deployment architecture, setup guides, CI/CD guide, OpenAPI |
| `tools/` | `encrypt-secret.sh`; `tools/e2e/` Playwright end-to-end tests: `e2e.js` (base framework), `e2e-increment1.js` (E01/E02, 18 checks), `e2e-increment2.js` (E03/E05, 25 checks), `e2e-increment3.js` (E06/E07/E14, 32 checks), `e2e-increment4.js` (E04/E08 and the partner portal, 22 checks) |

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
(+ `_USERNAME`, `_PASSWORD`, `_MIGRATOR_USERNAME`, `_MIGRATOR_PASSWORD`) is set. Encrypted columns are keyed per
test JVM (the test keys are generated on every run), so drop and recreate such a test database before every run.

## Quality gates

* `./mvnw verify` – unit, integration (real PostgreSQL), ArchUnit boundary tests, JaCoCo, CycloneDX SBOM
* `./mvnw -Psecurity verify` – SpotBugs + FindSecBugs SAST and OWASP dependency-check (needs `NVD_API_KEY`)
* `npm run lint && npm test && npm run build` in each `web/*` app (`employee-app`, `manager-workspace`, `oh-workspace`, `partner-portal`)
* `node tools/e2e/e2e-increment1.js` … `node tools/e2e/e2e-increment4.js` in order against the running local stack on a freshly purged database (release gate)
* CI adds CodeQL, gitleaks, Trivy image scanning, SBOM attestation and cosign signing

## Documentation

Start with `docs/README.md`. Increment 1 documents are in `docs/increment-01/`, increment 2 documents in
`docs/increment-02/`, increment 3 documents in `docs/increment-03/`, increment 4 documents in `docs/increment-04/`.
