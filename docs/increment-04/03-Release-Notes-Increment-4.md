# WellnessZ OHCM – Release Notes, Increment 4 (E04 Treatment Coordination + E08 Claims Coordination + Partner Portal)

| | |
|---|---|
| **Document** | Increment 4 – 03 Release Notes |
| **Release content** | E04 Injury & Illness Management, Treatment Coordination & Partner Referrals · E08 Workers' Compensation, Insurance & Claims Coordination · new partner portal for external providers and TPA / insurer adjusters · four new kernel SPIs · MFA gate for external roles |
| **Audience** | CMO, Nursing Head, DPO, claims / insurance coordination, Finance, Legal, Procurement, HSE, Cyber GRC, CIO, project sponsor, platform operations |
| **Database** | Flyway V13, V14, V15 (+ repeatable demo data `R__demo_e04_treatment.sql`, `R__demo_e08_claims.sql` for the `local` / test profiles only) |
| **New application** | **`web/partner-portal`** – new container image and Helm component `partnerPortal` (host `partners.<domain>`); two new backend modules inside `wz-core-app`; new screens in `web/oh-workspace`, `web/employee-app` and `web/manager-workspace` |
| **Companion documents** | 01 – Solution Design E04/E08 · 02 – Requirements Traceability Matrix · API contract `API-Contract-E04-E08.md` · OpenAPI `docs/api/openapi.json` (509 paths) |

---

## 1. Highlights

* **Treatment plans that drive follow-up** – eight injury templates turn into a dated plan with goals, interventions,
  follow-up tasks and clinic bookings in one step; the expected recovery comes from the duration guideline and a nightly
  job flags cases that overrun it. Employees see their plan in plain language and confirm or decline appointments.
* **Electronic referrals to partners** – a wizard with a mandatory data set per service, consent check, signed referral
  letter and FHIR R4 `ServiceRequest`; the provider accepts, books, marks seen, uploads the report and invoices in the
  new partner portal; overdue reports are chased and escalated automatically.
* **A managed provider network** – 20 hub, outstation and layover-city providers with versioned contracts, rate cards,
  SLAs, accreditation and languages; network-gap list for procurement; monthly vendor scorecards (timeliness, report
  quality, outcome, cost) with a CSV review pack.
* **Pre-authorisation without e-mail** – guarantee-of-payment requests shared with the insurer / TPA under consent and
  decided by the adjuster in the portal, by claims coordination or by the insurer system; invoices matched against the
  approved amount; every estimate and invoice lands in one case cost ledger.
* **Safer clinic medication** – administration with allergy check against the record, refused unknown or expired
  batches, stock decrement and monthly usage; e-prescribing prepared but switched off until the legal route is decided.
* **Outcomes on every treated case** – pain, function scale, RTW date and impairment at baseline, review and closure; a
  case with treatment cannot close without the closure outcome.
* **Telehealth for crew on layover** – video or secure chat with an OH clinician or partner specialist, per-session
  consent, single-use join tokens and a draft encounter for signature when the consultation ends.
* **Claims linked to the case – never to the clinical record** – one-click claim from the case, status workflow, badge in
  the case header, automatic nurse case manager for every lost-time claim, statutory and insurer first reports with
  deadline timers, consent-gated and redactable disclosure sets with a DPO report, adjuster collaboration (RTW status
  only, shared tasks, screened messages), cost roll-ups by subsidiary with budget vs actual and a checksummed ERP export,
  litigation hold, privilege and medico-legal reports.
* **A portal for partners, protected by MFA** – providers and adjusters sign in with multi-factor authentication, see
  only their own organisation's work and only what the employee consented to share; every disclosure is logged.

Requirement scope: E04 F01–F07 (19 backlog items), E08 F01–F06 (12). Coverage per item: see document 02.

---

## 2. What is new, by epic and feature

### 2.1 E04 – Injury & Illness Management, Treatment Coordination & Partner Referrals

| Feature | What is new | Main users |
|---|---|---|
| **E04-F01** Treatment plan & follow-up | 8 plan templates; plans DRAFT / ACTIVE / COMPLETED / CANCELLED with versions (SUPERSEDED history); interventions and follow-ups expanded from the template; follow-ups as case tasks and optional clinic bookings; employee reminders 2 days ahead; confirm / decline in the employee app (reason screened); NO_SHOW → MISSED; printable signed `TREATMENT_PLAN`; nightly variance alerts (> 20 % over expected, plan override) with acknowledge / escalate | OH nurse, OH physician, employee |
| **E04-F02** e-Referral management | 14 referral services with required data sets; wizard with consent; signed `REFERRAL_LETTER` + FHIR R4 `ServiceRequest` shared to the provider; worklist with status, overdue, chase count, escalation, bulk reminder; partner accept / decline / appointment / seen / report / invoice; daily chase (2 chases, then escalation to the referring clinician); FHIR `DocumentReference` report intake; report-quality rating 1–5; treatment KPIs | OH nurse / physician, partner provider |
| **E04-F03** Provider network directory | Search by service, specialty, city, country, contract status, preferred; profiles (specialties, services, languages, accreditation, coverage, coordinates); versioned contracts with rate cards and SLAs; contract status ACTIVE / EXPIRING / EXPIRED / NONE; network-gap list; monthly scorecards with recompute and CSV export | Procurement, OH nurse, CMO |
| **E04-F04** Pre-authorisation & cost tracking | Request with payer default from the claim; clinical justification from OH only; signed `PREAUTH_REQUEST` shared `INSURER` under consent; 48 h SLA; decisions by claims coordination, the adjuster (portal) or the insurer system; expiry after validity; invoices with duplicate check and matching (5 % tolerance); costs posted to the case ledger; unit cost roll-up with suppression and CSV | Claims coordinator, OH nurse, TPA adjuster, Finance |
| **E04-F05** Clinic medication & e-prescribing | Formulary of 10 clinic items; stock batches per site with reorder and expiry flags; administration with allergy alert (override with reason), batch checks and stock decrement, mirrored into the record's medication list; monthly usage; e-prescribing status endpoint (disabled) | OH nurse, Nursing Head |
| **E04-F06** Outcomes & provider performance | Outcomes at BASELINE / REVIEW / CLOSURE (NPRS pain, ODI / QuickDASH / PSFS / LEFS function, RTW date, impairment) with improvement; closure guard `outcome_required`; *Treatment* chart tab; outcomes feed the scorecards | OH nurse / physician, CMO |
| **E04-F07** Telehealth consultation | Sessions (video / chat, 10–90 min, partner specialist, crew-on-layover mode); `telehealth.invite`; consent per session; single-use join tokens (15 min before start → end); encrypted secure chat; token redemption for the video platform; completion into a draft encounter with optional follow-up; NO_SHOW from the diary | OH physician, partner specialist, employee |

### 2.2 E08 – Workers' Compensation, Insurance & Claims Coordination

| Feature | What is new | Main users |
|---|---|---|
| **E08-F01** Claim record & lifecycle | Claim from the case (one per case and type) with payer, policy, adjuster, lost time, incident date, screened notes; workflow NOTIFIED → OPEN → UNDER_REVIEW → SETTLED → CLOSED with LITIGATED and reopen; reserves and insurer-reported paid total; case header badge; subrogation flag with a legal task; monthly reconciliation of closed claims; HR status-only view (disclosure-logged) | Claims coordinator, nurse case manager, HR |
| **E08-F02** Statutory & insurer e-reporting | Report forms GB F2508, UAE labour injury notice and insurer FNOL (**pending Legal / HSE sign-off**); auto-populated first reports with deadline timers (specified-injury variant); signed `FIRST_REPORT_OF_INJURY`; insurer submission under consent, authority submission as a legal obligation; replaceable submission gateway; acknowledgements manual or by integration; hourly reminders, LATE and escalation; on-time KPI | Claims coordinator, HSE |
| **E08-F03** Consent-gated document exchange | Disclosure sets per recipient with disclosability rules (allowed, OH-only, never); redaction into a `REDACTED_COPY`; consent check on send; one insurer share per document; revoke; DPO disclosure report | Claims coordinator, OH nurse, DPO |
| **E08-F04** Nurse CM ↔ adjuster collaboration | Automatic nurse case manager on lost-time claims (case owner, else least-loaded case manager) through the E02 assignment service; 48 h KPI with unassigned list; shared tasks; screened, encrypted secure messages; RTW status and milestones only; adjuster claim list and detail in the partner portal | Nursing Head, nurse case manager, TPA adjuster |
| **E08-F05** Cost tracking & finance export | Cost ledger (6 categories, 4 kinds) including E04 entries; conversion to GBP; roll-ups by subsidiary / department / cost centre with cost per employee and suppression; budgets and budget vs actual with trend; monthly ERP export with SHA-256 checksum and reconciliation | Finance, claims coordinator, CMO |
| **E08-F06** Medico-legal & litigation support | Litigation hold (every case document on hold, retention suspended); privilege marking (excluded from disclosure); legal requests (medico-legal report, expert opinion, IME); signed `MEDICO_LEGAL_REPORT` versions; legal access set with disclosure log; IME raised as an E04 referral with status updates | Legal counsel, OH physician, CMO, claims coordinator |

### 2.3 Platform

* **Kernel SPIs**: `CaseCostRecorder`, `ExternalReferralGateway`, `ReferralStatusListener`, `ClaimContextProvider`
  (Solution Design §4.3). E04 also implements `CaseTransitionGuard`, `ChartContributor` and `AppointmentStatusListener`.
* **Module SPIs**: `TelehealthProvider` (default link provider), `EPrescribingGateway` (disabled default),
  `ClaimsSubmissionGateway` (default outbox event + content-free e-mail) – each replaceable by a `@Primary` bean.
* **Master data**: partner organisations (`GET/POST /masterdata/partner-organisations`; 25 seeded).
* **Documents**: share target `INSURER` (consent `INSURER_SHARING`, purpose CLAIM); `shareAsSystem` with organisation and
  consent; `legalHoldForCase`; templates `TREATMENT_PLAN`, `PREAUTH_REQUEST`, `FIRST_REPORT_OF_INJURY`,
  `MEDICO_LEGAL_REPORT`; upload types `PROVIDER_REPORT`, `PROVIDER_INVOICE`, `INSURER_ACKNOWLEDGEMENT`, `REDACTED_COPY`;
  retention class `CLAIM_RECORD` (10 y, proposed).
* **Security**: MFA gate for `PARTNER_PROVIDER` and `TPA_ADJUSTER`; consent purpose `TELEHEALTH_CONSULTATION`; `/me`
  disclosure level STATUS_ONLY for the new roles.
* **Notifications**: 13 content-free templates; placeholder `reference`; partner-portal links.
* **Outbound allow-lists**: `insurer-gateway`, `authority-gateway`, `erp-finance` (references and amounts only).
* **BFF**: `/bff/api/workspace/claims/**` keeps its prefix; redirect URIs and CORS include port 5176.

---

## 3. New roles and demo users

### 3.1 New roles

| Role | Purpose | Disclosure |
|---|---|---|
| `CLAIMS_COORDINATOR` | Claims, e-reporting, costs, disclosure sets, pre-authorisation follow-up | STATUS_ONLY – never the OH record |
| `TPA_ADJUSTER` | External adjuster in the partner portal (**MFA required**) | STATUS_ONLY – own payer's claims, RTW status, consented documents |
| `LEGAL_COUNSEL` | Litigation hold, privilege, medico-legal requests | STATUS_ONLY – litigated / held claims only |
| `FINANCE` | Cost roll-ups, budgets, ERP export | Units, not people; no claim detail |
| `PROCUREMENT` | Provider directory, contracts, rates, SLAs, scorecards | No person-level data |

Existing roles with new rights: `PARTNER_PROVIDER` (now active – partner portal, **MFA required**), `CASE_MANAGER`
(claims, automatic assignment, collaboration), `OH_CLINICIAN` / `OH_PHYSICIAN` (treatment, referrals, medication,
outcomes, telehealth, redaction, medico-legal reports), `NURSING_HEAD` (formulary, usage, nurse-assignment KPI), `CMO`
(KPIs, scorecards, costs), `HR` (claim status), `DPO` (claim disclosure report), `HSE_OFFICER` (first reports),
`EMPLOYEE` (my treatment, telehealth), `INTEGRATION` (FHIR, insurer decisions, acknowledgements, token redemption).

### 3.2 Demo users (local realm `deploy/local/keycloak/wellnessz-realm.json`)

| User | Role(s) | Employee ID / org scope | Used for |
|---|---|---|---|
| `claims1` (new) | CLAIMS_COORDINATOR | S8711, /AIRLINE | Claims, first reports, disclosure sets, invoices |
| `adjuster1` (new) | TPA_ADJUSTER | X8702, **/PARTNERS/GULFTPA** | Partner portal: claims, collaboration, pre-authorisation decisions |
| `legal1` (new) | LEGAL_COUNSEL | S8703, /AIRLINE | Litigation hold, medico-legal report |
| `finance1` (new) | FINANCE | S8704, /AIRLINE | Cost roll-up, budgets, ERP export |
| `procurement1` (new) | PROCUREMENT | S8705, /AIRLINE | Provider directory, contracts |
| `physio1` (new) | PARTNER_PROVIDER | X8706, **/PARTNERS/PHYSIOPLUS** | Partner portal: referral inbox, report, invoice |
| `partner1` | PARTNER_PROVIDER | P9101, **/PARTNERS/CITYHOSP** | Partner portal; organisation-isolation check |
| `nurse1`, `clinician1`, `physician1`, `cmo1`, `nursinghead1` | OH roles | – | Plans, referrals, medication, outcomes, telehealth, nurse assignment |
| `employee1`, `hr1`, `dpo1` | EMPLOYEE, HR, DPO | E1001, H3001, – | Employee app, HR claim status, DPO report |

Demo data (`R__demo_e04_treatment.sql`): injury case for E1004 Rami Ramp with an ACTIVE back-strain plan (variance alert
OPEN), five referrals in different states (SENT to CITYHOSP, SCHEDULED with PHYSIOPLUS, SEEN at LDN_IMAGING with an
overdue report, REPORT_RECEIVED from STMARYS_ORTHO, DRAFT), two pre-authorisations (SUBMITTED and APPROVED with a matched
invoice), outcomes; telehealth session for crew member E1003 on layover in Singapore; clinic stock at LHR-AIRSIDE and
HQ-MC (one batch expired, one below reorder level); last month's scorecards. `R__demo_e08_claims.sql`: four claims
(OPEN lost-time workers' compensation for E1004 with RTW plan, two first reports, shared tasks and a draft disclosure
set; LITIGATED employer's liability for E1001 with hold, privilege and an open medico-legal request; CLOSED group medical
reconciled; NOTIFIED third party), case-level costs across units, budgets and a GENERATED ERP export for last month.
`ClaimsDemoSeeder` (local / test profiles) fills the encrypted demo fields through the application.

---

## 4. New application – partner portal

| Item | Detail |
|---|---|
| Location | `web/partner-portal` – React 19, TypeScript, Vite (same stack as the other web apps); teal theme |
| Local URL | `http://localhost:5176` (dev server proxies `/bff`, `/oauth2`, `/login`, `/logout` to the BFF) |
| Production host | `partners.<domain>` via Helm component `partnerPortal`; the ingress routes the BFF paths on the same host |
| Authentication | BFF session only (same-origin cookie, tokens never in the browser); sign-in page explains MFA; 15-minute idle timeout |
| Access | Decided on the roles of `GET /bff/api/me`: `PARTNER_PROVIDER` → referrals, telehealth, documents; `TPA_ADJUSTER` → claims, pre-authorisations, documents; partner role without MFA proof → MFA / no-access page; any other role → "not available" |
| Isolation | Organisation = last segment of `org_scope`; everything else is 404 ("Not found or not shared with your organisation") |
| Container | Node build stage → unprivileged nginx on 8080, `/healthz`, strict security headers and CSP, read-only root file system |
| Tests | 119 vitest unit and screen tests; lint 0 warnings |
| Pipeline | CI web matrix, container build, deploy-environment image list, Dependabot (npm + Docker) |

---

## 5. API summary

The authoritative references are the contract `docs/increment-04/API-Contract-E04-E08.md` and the regenerated
OpenAPI document `docs/api/openapi.json`, which grows from 391 to **509 paths** (+118 paths, 141 operations):

| Area | Paths | Operations |
|---|---|---|
| `/cases/{caseId}/{treatment-plans, referrals, preauthorisations, invoices, medications/…, outcomes, consents, claims, claim-status}` | 10 | 15 |
| `/treatment/**`, `/treatment-plans/**` (templates, variance, formulary, stock, usage, costs, KPIs, e-prescribing status; plan updates) | 16 | 18 |
| `/referrals/**` | 8 | 9 |
| `/providers/**` | 7 | 8 |
| `/preauthorisations/**`, `/invoices/**` | 4 | 4 |
| `/telehealth/**` | 5 | 7 |
| `/claims/**` (claims, reports, disclosure sets, tasks, messages, RTW, costs, budgets, exports, reconciliation, nurse assignment) | 35 | 44 |
| `/legal-requests/**` | 5 | 5 |
| `/partner/**` (referrals, telehealth, claims, pre-authorisations) | 15 | 16 |
| `/me/{treatment-plans, telehealth}/**` | 6 | 7 |
| `/workspace/claims` (HR) | 1 | 1 |
| `/integration/**` (FHIR referral and report, pre-authorisation decision, first-report acknowledgement, telehealth token redemption) | 5 | 5 |
| `/masterdata/partner-organisations` | 1 | 2 |

The existing `GET /partner/documents` is now also open to `TPA_ADJUSTER` (documents shared `INSURER` to the
adjuster's organisation under active consent).

**Where the code deviates from the contract** (the code is authoritative; documented in the Solution Design):

| Area | Deviation |
|---|---|
| E04 plans | `409 active_plan_exists` (one ACTIVE plan per case); plan history in the response; a demo plan without stored goals shows its template's content |
| E04 referrals | Extra fields `slaBreached`, `sourceType`, `sourceRef`; extra endpoints `POST /referrals/{id}/close` and `GET /partner/referrals/{id}` (with history); `422 data_set_incomplete` / `referral_incomplete` with `field`; `422 provider_not_suitable`; invalid transitions `422 invalid_transition` |
| E04 consents | Extra endpoint `GET /cases/{caseId}/consents` (consent pickers) |
| E04 pre-authorisation | Justification required from OH roles (`400 justification_required`) and refused from claims coordinators (`400 justification_oh_only`); `422 payer_required` / `invalid_payer`; approval defaults `validUntil` to 90 days |
| E04 costs / KPIs / scorecards | Sum amounts in the base currency (`treatment.base-currency` GBP) only – no conversion in E04 |
| E04 telehealth | Extra fields `joinOpensAt` / `joinClosesAt`; extra endpoint `POST /integration/telehealth/tokens/redeem`; `422 outside_join_window`; diary booking takes the first free slot on or after the scheduled date |
| E08 workflow | Extra transitions LITIGATED → SETTLED / CLOSED and NOTIFIED / UNDER_REVIEW → CLOSED; reason required for a close without settlement and for reopen (`422 reason_required`); legal counsel may only set LITIGATED |
| E08 claim list | `GET /claims` also for CASE_MANAGER and the assigned nurse; `paidTotal` = insurer-reported (`PUT /claims/{id}`), `ledgerTotal` = PAYMENT entries |
| E08 costs | Roll-ups, budget vs actual and ERP export count INVOICE + PAYMENT only (ESTIMATE and RESERVE are exposure on the claim); conversion to `claims.base-currency` with `claims.fx-rates` (`422 currency_not_supported`); budget vs actual not suppressed; one ERP export per month (`409 export_exists`) unless MISMATCH; `422 currency_mismatch` on reconcile; `422 month_not_closed` for future months |
| E08 reports | Submit `422 report_incomplete` with `field`; optional `consentId` (latest active `INSURER_SHARING` used); `specifiedInjury` switches F2508 to 10 days; one report per form and claim (`409 report_exists`) |
| E08 disclosure | Medico-legal / IME reports of a privileged claim are excluded; `422 no_text_layer` for redaction; `422 empty_set` |
| E08 IME | Without E04 the request records `referralStatus=NOT_AVAILABLE` and an OH task; with E04 a DRAFT referral is raised |
| E08 views | HR and adjuster RTW reads disclosure-logged once per recipient and status |
| E08 extra endpoints | `GET /claims/{id}/documents` (disclosable flag + reason), `/claims/{id}/consents`, `/claims/{id}/reports`, `/claims/reports/{id}`, `/claims/reports/kpis` (`onTimeSubmissionPct`), `/claims/{id}/costs/summary`, `/legal-requests/{id}` |
| Errors | Screened clinical text returns `422 clinical_content_not_allowed` with the offending `field` |

---

## 6. Database migrations

| Migration | Schema(s) | Content |
|---|---|---|
| `V13__increment4_platform.sql` | masterdata, docs | `partner_organisation` with 25 organisations (20 providers, 3 payers, 2 authorities); retention class `CLAIM_RECORD` (10 y, suspended by litigation hold – **proposed**) |
| `V14__e04_treatment.sql` | treatment (new) | 20 tables (numbering, plan templates, plans, follow-ups, variance alerts, service catalogue, referrals and history, provider profiles / contracts / scorecards, pre-authorisations, invoices, formulary, stock, administrations, outcomes, telehealth sessions / tokens / messages); 8 plan templates, 14 referral services, 20 provider profiles, 18 contracts, 10 formulary items, 12 configuration entries; grants without DELETE; read on `triage.duration_guideline` |
| `V15__e08_claims.sql` | claims (new) | 15 tables (numbering, claims and history, report forms, first reports, disclosure sets and documents, tasks, messages, read markers, cost ledger, budgets, exports, legal requests and reports); 3 report forms **pending Legal / HSE sign-off**; 10 configuration entries; no DELETE; insert-only on history, messages and legal reports |
| `db/demo/R__demo_e04_treatment.sql`, `R__demo_e08_claims.sql` (repeatable) | treatment, claims, casemgmt, fitness, intake, platform | Demo population (Section 3.2). **Loaded only with the `local` / test profiles**; idempotent; dates relative to `current_date`; encrypted columns left empty. Never enable `classpath:db/demo` in shared environments |

Each module migration starts with `create schema if not exists …`. Migrations are additive; seed rows are inserted once
(`on conflict do nothing`), so edits made through the APIs survive.

**Rollback.** Migrations are forward-only and ship without down scripts. Running the Increment-3 application against
the V15 schema is not supported: claims, referrals, cost entries, legal holds and partner shares created after the
upgrade would no longer be explained by any module, and partner users would lose access mid-process. Rolling back means
restoring the pre-upgrade backup together with the previous images; agree a rollback window before go-live.

---

## 7. Configuration

| Item | Change |
|---|---|
| `spring.flyway.schemas` (core) | Adds `treatment, claims` |
| `WZ_MFA_REQUIRED_ROLES` (`wellnessz.security.mfa-required-roles`) | **New.** Default `PARTNER_PROVIDER,TPA_ADJUSTER`; empty in the `local` profile. Accepted proof: `amr` ∈ {mfa, otp, totp, hwk, swk, sms, webauthn, fido} or `acr` ∈ {2, 3, mfa, c1} (`mfa-amr-values`, `mfa-acr-values`) |
| `WZ_PARTNER_PORTAL_URL` (`wellnessz.notification.partner-portal-url`) | **New.** Default `https://partners.wellnessz.local` – set to `https://partners.<domain>` |
| `wellnessz.integration.outbound-allowlist.insurer-gateway` | **New:** `messageId, claimRef, reportRef, formCode, payerCode, employeeId, incidentDate, submittedAt, deadlineAt` |
| `wellnessz.integration.outbound-allowlist.authority-gateway` | **New:** `messageId, reportRef, formCode, jurisdiction, employerRef, incidentDate, submittedAt, deadlineAt` |
| `wellnessz.integration.outbound-allowlist.erp-finance` | **New:** `messageId, batchId, month, costCentre, subsidiaryCode, category, amount, currency, entryCount, checksum` |
| `wellnessz.treatment.variance-job-cron` / `chase-job-cron` / `scorecard-job-cron` | `0 20 2 * * *` / `0 40 6 * * *` / `0 15 3 1 * *` UTC |
| `wellnessz.claims.report-deadlines-cron` | `0 5 * * * *` UTC (hourly) |
| `WZ_ANALYTICS_MIN_GROUP` | Default 5 – now also suppresses treatment and claims cost roll-ups |
| `WZ_CORS_ORIGINS` (local) | Adds `http://localhost:5176` |
| Mailboxes (`masterdata.config_entry`) | **Set per environment:** `claims.legal-email`, `claims.mailbox-email`; partner and payer contact e-mails in `masterdata.partner_organisation` |
| E04 business settings | `treatment.variance.threshold-pct` (20), `treatment.follow-up.reminder-days` (2), `treatment.referral.max-chases` (2), `treatment.referral.chase-interval-days` (2), `treatment.preauth.sla-hours` (48), `treatment.invoice.tolerance-pct` (5), `treatment.base-currency` (GBP), `treatment.contract.expiring-days` (60), `treatment.network.cities`, `treatment.telehealth.base-url`, `treatment.eprescribing.route` (NONE) |
| E08 business settings | `claims.nurse-sla-hours` (48), `claims.report-reminder-hours` (24), `claims.base-currency` (GBP), `claims.fx-rates` (**proposed – finance to confirm**), `claims.employer-name`, `claims.employer-ref` (**proposed – HSE to confirm**), `claims.forms.sign-off` (PENDING) |
| Helm | New component `partnerPortal` (image `partner-portal`, 2 replicas, HPA 2–4, PDB) and ingress host `partners.<domain>`; disabled in the hybrid on-premises values |
| Business content | Partner organisations, plan templates, referral services, provider profiles / contracts, formulary, report forms – seeded by V13–V15, maintained through the APIs and the OH Workspace |

---

## 8. Verification results (29 October 2026)

| Check | Result |
|---|---|
| Java build and tests (`./mvnw verify`) | **275 tests, 0 failures** – `wz-core-app` 112 (including `Increment4TreatmentIntegrationTest` 10 and `Increment4ClaimsIntegrationTest` 10 on PostgreSQL 16 with their own test databases, and `ArchitectureTest` with 17 rules); module unit tests E04 `TreatmentRulesTest` 11, E08 28; security 7 including `MfaRoleGateTest` 3 |
| SAST (SpotBugs + FindSecBugs, `-Psecurity`) | **0 findings** |
| Web unit tests (vitest) | oh-workspace **570**, employee-app **223**, manager-workspace **164**, partner-portal **119** (new) = **1,076**; lint 0 warnings; production builds clean |
| End-to-end, real Keycloak logins through the BFF, four apps | Increment 4 **22 / 22** (`tools/e2e/e2e-increment4.js`); regressions Increment 1 **18 / 18**, Increment 2 **25 / 25**, Increment 3 **32 / 32** – **97 checks**; 26 screenshots in `docs/images/screens/increment-04/` (`inc4-oh-01..17`, `inc4-partner-01..06`, `inc4-emp-01..02`, `inc4-mgr-01`) |
| Helm | `helm lint`, `helm template` and kubeconform clean with the `partnerPortal` component |
| API contract | `docs/api/openapi.json` regenerated – **509 paths** (was 391) |

Selected end-to-end evidence (Increment 4):

| Flow | Observed |
|---|---|
| Injury and consent | Employee reports an injury (case CAT-2026-000001) and grants partner and insurer consent in the app (201 / 201) |
| Treatment plan | Active plan from the wrist-sprain template with follow-ups; expected recovery 2026-11-19; employee sees the plain-language plan, prints it and confirms a follow-up |
| Referral | PHYSIOTHERAPY requires `bodyPart, sessions`; incomplete → `422 data_set_incomplete` (`sessions`); sent RFL-2026-000001 with report due 2026-11-19 |
| Partner isolation | PHYSIOPLUS sees 2 referrals; CITYHOSP → 404 on PHYSIOPLUS's referral |
| Partner actions | Accept, appointment, seen, report (200) and invoice (201) through the portal; report attached, OH rated it; overdue referral RFL-2026-900003 chased once |
| Provider network | Procurement search by service (ACTIVE / EXPIRING contracts); CMO sees 4 scorecards; 47 network gaps |
| Pre-authorisation | PA-2026-000001 shared with the TPA under consent → SUBMITTED → APPROVED by the adjuster in the portal; 2 pending for GULFTPA |
| Medication | Expired batch → `422 batch_expired`; paracetamol batch PCM-2401 administered |
| Outcomes | Pain Δ −4, function Δ −30 |
| Telehealth | Join without consent → 422; join URL issued; 1 chat message; completion created a draft encounter |
| Treatment KPIs | Median 5 working days to appointment; report within 7 days 66.7 %; missed appointments 0 % |
| Claim and nurse | CLM-2026-000001 from the case; nurse `clinician1` assigned automatically; badge NOTIFIED; lost-time claims with nurse within 48 h 2 / 2 = 100 % |
| First report | FROI-2026-000001 auto-populated, deadline 2026-11-01T19:19, submitted under consent → SUBMITTED |
| Disclosure | 3 of 6 documents disclosable; set SENT; adjuster sees 3 documents; invalid consent → `422 consent_required` |
| Adjuster collaboration | Own claims (2), RTW `FIT_WITH_RESTRICTIONS` only, shared task, secure message; clinical wording → 422; provider → 403 on claims |
| Finance | 4 units (2 suppressed, no names); ERP export of 10 rows → RECONCILED; claim detail → 403 |
| Legal | Litigation hold on; medico-legal report v1 readable by legal (200), claims coordinator 403 |
| HR and DPO | HR sees 5 claims, status only; DPO report lists 14 insurer disclosures with consent |

---

## 9. Known limitations and deviations

| Area | Limitation | Planned handling |
|---|---|---|
| Insurer / authority / ERP connectors | `ClaimsSubmissionGateway` default = outbox event + content-free e-mail; ERP export announced on `erp-finance`, no ERP connector | E15 (replace with `@Primary` beans) |
| FHIR | `ServiceRequest` / `DocumentReference` exchange tested with a simulated partner only | Live partner hospital – E04-BL-006 (P3) |
| E-prescribing | `EPrescribingGateway` is a disabled no-op | Decision memo (E04-BL-013) |
| Telehealth | Video platform not procured; link provider and token redemption ready; consent text says video and sound are not stored (to be confirmed with the platform); diary booking may differ from the scheduled time | Procurement + DPO |
| Report forms | GB F2508, UAE labour injury notice and insurer FNOL pending Legal / HSE sign-off; other jurisdictions not mapped | E08-BL-003 sign-off |
| MFA | Enforced by the core API outside `local`; requires enrolment in the production IdP; not exercised by the local E2E | Upgrade step 4 |
| Provider network | 20 illustrative providers, contracts and rates | Replace with procurement's contracted network |
| Currency | E04 cost figures, KPIs and scorecards sum base-currency amounts only; E08 FX rates proposed | Finance confirmation |
| Litigated-claim KPI | No dedicated trend endpoint | Analytics (E17) |
| Demo data | Encrypted narrative (referral reason, question, data set, justification, chat) cannot be seeded by SQL – demo referral 900001 has no letter | By design |
| Retention | `CLAIM_RECORD` 10 years proposed | DPO / Legal approval |
| Load | No production-volume load test of worklists, roll-ups or exports | Performance test before go-live |
| Sign-offs | Plan templates, referral data sets, scorecard weights, disclosure rules, notification wording (Solution Design §27.1) | Business review |

---

## 10. Upgrade steps

1. **Back up** the database (point-in-time recovery enabled) and note the Flyway version (expected V12). Keep the
   previous application images available for the rollback window.
2. **Schema list everywhere** – add `treatment, claims` (after `scheduling`, before `analytics` where the list is
   ordered) to every place that lists schemas: core `spring.flyway.schemas` (built in), Helm `values.yaml` (Flyway
   schemas), the local Postgres init script `deploy/local/postgres/01-init-wellnessz.sh`, the managed-database bootstrap
   `deploy/sql/bootstrap-managed-postgres.sql` (search paths and schema grants for `wz_app` and `wz_migrator`) and the
   CloudNativePG cluster `deploy/hybrid/cnpg/03-cluster.yaml`. The CNPG `initdb` statements only run for new clusters –
   on an existing cluster create the two schemas owned by `wz_migrator` and update the `wz_app` / `wz_migrator` search
   paths manually. Treat `treatment` and `claims` exactly like the other module schemas in every one of these places
   (usage, search path and default privileges for the runtime role).
3. **Identity provider – roles, users, redirect URI** – create the roles `CLAIMS_COORDINATOR`, `TPA_ADJUSTER`,
   `LEGAL_COUNSEL`, `FINANCE` and `PROCUREMENT` (Entra: app roles and groups) and **add all five to the role scope
   mappings of `wellnessz-bff` and `wellnessz-mobile`** (`fullScopeAllowed=false` – without the mapping the role is
   silently dropped). Add `https://partners.<domain>/login/oauth2/code/wellnessz` to the BFF client's redirect URIs and
   `https://partners.<domain>` to post-logout redirect URIs and web origins. External users need an `org_scope` claim
   `/PARTNERS/<ORGANISATION_CODE>` matching `masterdata.partner_organisation`. Locally, the realm file already contains
   the roles, scope mappings, users (`claims1`, `adjuster1`, `legal1`, `finance1`, `procurement1`, `physio1`) and the
   5176 redirect URI: **re-import the realm** (`./deploy/local/down.sh --purge` then `./deploy/local/up.sh`).
4. **MFA for partner roles** – in production IdPs enforce a second factor for every `PARTNER_PROVIDER` and
   `TPA_ADJUSTER` identity (Entra ID: conditional-access policy with an authentication context reported in `acr`, or
   MFA reported in `amr`; Keycloak: required OTP / WebAuthn with a step-up level reported in `acr`) and verify that the
   **access token** carries the claim. Keep `WZ_MFA_REQUIRED_ROLES=PARTNER_PROVIDER,TPA_ADJUSTER` (the default) in every
   shared environment; only the `local` profile switches it off. Without the claim the portal shows the MFA / no-access
   page – test with one partner and one adjuster account before inviting partners.
5. **Helm – partner portal** – build and push the `partner-portal` image; enable `partnerPortal` with its image tag /
   digest; set `ingress.hosts.partnerPortal` to `partners.<domain>`; create **DNS** for `partners.<domain>` and a **TLS
   certificate** (cert-manager, Key Vault / AGIC or GKE ManagedCertificate as for the other hosts); make sure the ingress
   routes `/bff`, `/oauth2`, `/login`, `/logout` on that host to the BFF. Review network policies and WAF rules: this is
   the first host intended for users **outside** the group network.
6. **Outbound allow-lists** – add `insurer-gateway`, `authority-gateway` and `erp-finance` with the field lists of
   Section 7 if the allow-list is overridden per environment (the shipped `application.yml` contains them); default deny
   remains for any other destination.
7. **Configuration** – set `WZ_PARTNER_PORTAL_URL`, `claims.legal-email`, `claims.mailbox-email`; review
   `claims.fx-rates`, `claims.employer-name`, `claims.employer-ref`, `treatment.network.cities`,
   `treatment.telehealth.base-url`; maintain partner and payer contact e-mails; keep `WZ_FLYWAY_LOCATIONS` **without**
   `classpath:db/demo`.
8. **Migrate** – run the Flyway job with the migrator role; confirm V13–V15 applied and that schemas `treatment` and
   `claims` exist with runtime grants (no DELETE).
9. **Deploy** core API, BFF, employee app, manager workspace, OH Workspace and the **partner portal** (Helm upgrade).
10. **Reference data** – replace the illustrative provider network with procurement's contracted network (organisations,
    profiles, contracts, rates, SLAs); confirm payers and authorities; review plan templates, referral services and the
    formulary; record clinic stock per site before medication administration goes live.
11. **Integration clients** – client-credentials `INTEGRATION` clients for partner hospital FHIR systems, insurer
    decision / acknowledgement callbacks and the telehealth platform (one client per partner recommended for audit
    separation).
12. **Smoke test** – run `tools/e2e/e2e-increment4.js` (and the Increment 1–3 scripts) against the environment, or the
    equivalent manual checks: plan from template; referral send → portal accept → report; organisation isolation (404);
    pre-authorisation → adjuster decision; telehealth consent → join; claim → nurse assignment; first report submit;
    disclosure set send under consent; ERP export → reconcile; legal hold; HR status view.
13. **Monitoring** – watch the four new scheduled jobs (`treatment-variance`, `referral-chase`, `provider-scorecards`,
    `claims-report-deadlines`) in `platform.scheduled_job_run`, the outbox relay for `insurer-gateway`,
    `authority-gateway` and `erp-finance`, LATE first reports, escalated referrals, and audit events for denied partner
    reads, legal holds (`LEGAL_HOLD_SET` / `LEGAL_HOLD_RELEASED`) and disclosure sets.

---

## 11. Next steps

* **Increment 5 (final): E09–E12** – restricted classes and health promotion.
* **E15 integration** – production connectors behind `ClaimsSubmissionGateway` (insurer FNOL APIs, authority
  e-reporting), the ERP finance interface, a live FHIR partner hospital and the chosen telehealth video platform.
* **Business sign-offs** – Legal / HSE sign-off of the report forms and jurisdiction mappings (E08-BL-003); e-prescribing
  decision memo (E04-BL-013); CMO approval of plan templates and referral data sets; procurement's contracted network;
  finance confirmation of FX rates; DPO approval of `CLAIM_RECORD` retention, disclosure rules and notification wording.
* **Security** – MFA enrolment and conditional access for all partner and adjuster identities in the production IdP;
  partner onboarding and off-boarding process (organisation codes, `org_scope`, account review).
* **E17 analytics** – treatment and claims KPIs (including the litigated-claim trend) in the enterprise analytics layer;
  load testing of worklists, roll-ups and exports at production volume.
