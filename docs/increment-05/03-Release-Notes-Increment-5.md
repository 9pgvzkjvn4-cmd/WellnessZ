# WellnessZ OHCM – Release Notes, Increment 5 (E09 Mental Health + E10 Substance Testing + E11 Travel Health + E12 Health Promotion)

**Version 1.0** · WellnessZ OHCM platform – increments 1–5

| | |
|---|---|
| **Document** | Increment 5 – 03 Release Notes (final increment, 5 of 5) |
| **Release content** | E09 Mental Health, Psychosocial Risk & Peer Support Programmes · E10 Drug, Alcohol & Psychoactive Substance Testing Programme · E11 Travel Health, Immunisation Campaigns & Communicable Disease / Exposure Management · E12 Health Promotion, Wellbeing & Prevention Campaigns · restricted-class confidentiality model · duty-assignment (roster / shift / facility) feed · DEV / UAT / PROD environment engineering · client customisation · developer environment guide |
| **Audience** | CMO, Nursing Head, mental-health lead, MRO, DPO, Legal, HSE / Safety (SMS), Health Promotion, HR, Crew Scheduling, Cyber GRC, CIO, project sponsor, platform operations, developers |
| **Database** | Flyway V16, V17, V18, V19, V20 (+ repeatable demo data `R__demo_e09_mentalhealth.sql`, `R__demo_e10_substance.sql`, `R__demo_e11_travelhealth.sql`, `R__demo_e12_promotion.sql`, `R__demo_reference_duties.sql` for the `local` / test profiles only) |
| **Applications** | No new application. Four new backend modules inside `wz-core-app`; new screens in all four web apps (`web/oh-workspace`, `web/employee-app`, `web/manager-workspace`, `web/partner-portal`); BFF workspace access for two more roles |
| **Companion documents** | 02 – Requirements Traceability Matrix · API contract `API-Contract-E09-E10-E11-E12.md` · OpenAPI `docs/api/openapi.json` (647 paths) · `deploy/environments/README.md` · [Client Customisation Guide](../customisation/Client-Customisation-Guide.md) · `docs/developer-setup/README.md` |

---

## 1. Highlights

* **A visibly separate, confidential pathway for psychological health** – employees self-refer from the app with
  signposting and an always-visible emergency banner, complete a PHQ-9 or GAD-7 screen and are called back the same day
  when the answers show urgent risk. Support cases are a *restricted class*: only the named mental-health clinician opens
  them; a general OH nurse gets `404` and sees only "1 restricted item – hidden" on the chart. The employee decides what,
  if anything, the manager sees (nothing, fitness only, fitness and adjustments) and can change it at any time.
* **EAP referral without clinical leakage** – the counselling partner works in the partner portal with minimal referral
  data for its own organisation and returns attendance and closure only; free text is refused (`422 field_not_allowed`).
* **Crew peer support that is genuinely non-punitive** – crew reach a trained peer with a self-chosen alias and a random
  contact code; the contact log holds no employee identity; the safety management system receives quarterly aggregated
  figures with small cells suppressed.
* **Critical-incident stress management** – a fume event, assault or other trigger class from the incident feed opens a
  CISM event; affected crew come from the flight roster; support is offered within 72 hours and follow-ups at 1 and 4
  weeks are scheduled automatically. Safety managers see counts, never names.
* **Psychosocial risk register (ISO 45003)** per department with RAG scoring, actions and owners, shown alongside an
  anonymised support-case rate. **Safety-critical fitness** decisions with medication review feed rostering with the status
  only, and an authority fitness report when required.
* **A defensible drug and alcohol testing programme** – policies per role group, auditable random selection reproducible
  from its stored seed, post-incident tests from the incident feed with alcohol / drug windows, for-cause requests with an
  observation checklist and second observer, electronic chain of custody, a laboratory interface bound to the toxicology
  laboratory, MRO review with prescription check and split specimen. A verified positive removes the employee from
  safety-sensitive duty at once, opens a confidential support referral and a return-to-duty plan with unannounced
  follow-up tests – while HR and crew scheduling see the duty status only.
* **Outbreak-ready travel health** – travel consultations with destination recommendations, vaccines recorded in the
  immunisation registry and a signed travel health certificate; communicable-disease cases with exclusion and return
  criteria; contact tracing from rosters, shifts and facilities inside the 4-hour target (1.03 h in the E2E run); contacts
  notified without naming the case and followed up by app check-ins; exposure pathways with live protocol timers (PEP
  decision within 2 hours); influenza campaigns with fast entry and suppressed uptake; layover advisories with
  acknowledgement; notifiable-disease reports tracked against the statutory deadline.
* **Prevention driven by anonymised intelligence** – hotspots from cases, incidents, audiometry and screenings, a campaign
  planner with a seeded portfolio, publication to the group campaign app, screening events where an abnormal result
  opens a follow-up case within 48 hours with a result letter, ergonomic assessments on a tablet with RAG and job-demand
  profile, consented wearable programmes (daily totals only, purge on withdrawal) and participation / outcome reports with
  suppression.
* **Ready to run in DEV, UAT and PROD** – per-environment Terraform and Helm overlays, promotion of one signed image digest
  from DEV to UAT to PROD with smoke tests and automatic rollback, client customisation without forking the chart, and a
  complete developer environment guide for IntelliJ IDEA, VS Code, Eclipse and Dev Containers.

Requirement scope: E09 F01–F06 (15 backlog items), E10 F01–F05 (10), E11 F01–F06 (13), E12 F01–F05 (8) – 90 traced items:
62 implemented, 16 implemented with configuration needing sign-off, 12 partial. Coverage per item: see document 02.

---

## 2. What is new, by epic and feature

### 2.1 E09 – Mental Health, Psychosocial Risk & Peer Support Programmes

| Feature | What is new | Main users |
|---|---|---|
| **E09-F01** Confidential psychological-health case type | Support cases (SUP-YYYY-NNNNNN) on restricted kernel cases (`PSYCHOLOGICAL`, `RESTRICTED_CLINICAL`); only named clinicians (`mentalhealth.named-clinicians`) read them – everyone else gets an audited `404`; placeholder count for general OH users; break-glass read-only; encrypted notes; employee sharing choice `NOTHING / FITNESS_ONLY / FITNESS_AND_ADJUSTMENTS` (screened adjustments, revocable, audited); manager view of shared outcomes (disclosure-logged) | Mental-health clinician, employee, manager, DPO |
| **E09-F02** Self-referral, screening & EAP referral | Signposting with emergency text and number; self-referral (phone / app chat / video); PHQ-9 and GAD-7 with scoring and bands; PHQ-9 item 9 > 0 or SEVERE → URGENT: same-day call-back task and content-free alert to named clinicians; urgent-risk protocol script and documented call outcomes (incl. escalation to emergency services); clinician-administered screens; e-referral to the EAP partner (1–12 sessions, first appointment within 5 working days) under consent; partner feedback limited to status, attendance and closure reason; governance register (**sign-off pending**) | Employee, mental-health clinician, EAP partner, CMO |
| **E09-F03** Flight-crew peer support | Volunteer register with training records, base, languages and availability (expired training → not offered); pseudonymous contact request (alias + `PS-XXXX-XXXX` code, callback phone encrypted and purged after 30 days) routed to an available trained peer; volunteer contact log (category, outcome – no names, no employee id); referral to the trusted mental-health professional's queue; temporary relief from flight duties with status-only roster feed and AME return decision; quarterly aggregated SMS report with suppression, published to `sms-safety` | Peer volunteer, peer coordinator, mental-health clinician, AME, safety manager |
| **E09-F04** Critical incident stress management | Event from `ehs.incident.received.v1` (severity HIGH / CRITICAL or `cism.trigger-classes`) or manual; affected list = incident employee + same duty / proximity group ± 2 h from the duty feed; offers (`cism.offer`) with 72 h SLA; responses in the employee app; group / individual sessions; 1- and 4-week follow-ups (`cism.follow-up`); referral to support | Mental-health clinician, OH nurse, safety manager (counts), employee |
| **E09-F05** Psychosocial risk register | Assessments per unit across 8 ISO 45003 factors (likelihood × severity, overall = worst factor, RAG); actions with owner, due date and status; dashboard with overdue reviews / actions and anonymised support-case rate per 100 staff (suppressed); manager view of the unit's assessment | HSE, CMO, Nursing Head, HR, manager |
| **E09-F06** Safety-critical mental-health fitness | Structured assessment (standard, medications with drug class, encrypted findings, decision FIT / FIT_WITH_LIMITATIONS / TEMPORARILY_UNFIT / REFER_TO_AUTHORITY, review date); status-only roster / fitness feed; review task; restricted `AUTHORITY_FITNESS_REPORT` when required | AME, OH physician, mental-health clinician |

Kernel SPI `SupportProgrammeReferral` is implemented by E09: a referral from another programme (E10 positive or refusal)
opens one restricted support case per source with source `PROGRAMME_REFERRAL` and alerts the named clinicians.

### 2.2 E10 – Drug, Alcohol & Psychoactive Substance Testing Programme

| Feature | What is new | Main users |
|---|---|---|
| **E10-F01** Policy & pool management | Policies for six role groups (flight crew, cabin crew, airside driver, engineer, forklift, security) with panels, random rates, alcohol / drug windows (8 h / 32 h), lead time, jurisdiction, consent – seeded **PENDING approval**; approval with `approvedBy` and optimistic version (any change returns to PENDING); pools from HRIS job codes with exclusions; random selection n = ceil(pool × rate / 12) with `SecureRandom` seed, pool-snapshot hash and seed commitment; `/verify` reproduces the draw; selection notices and collector dispatch; coverage dashboard with behind-plan flag | D&A programme manager, auditor, CMO |
| **E10-F02** Post-incident & for-cause triggers | Rule table on incident class / severity for pool members → POST_INCIDENT tests with alcohol and drug due times from the event and urgent dispatch; for-cause request by a manager in scope or safety manager (observation checklist, encrypted description, second observer, observation time); countdown timers; WINDOW_MISSED after the window (4 h grace for random, follow-up and RTD tests) | Safety manager, manager, collector |
| **E10-F03** Collection, chain of custody & laboratory | Collection (urine, oral fluid, breath) with temperature, seal, identity check and split; signed restricted `CHAIN_OF_CUSTODY_FORM`; insert-only custody events (SEALED, COURIER_PICKUP, LAB_RECEIVED); refusal recording; laboratory orders and results only for the bound TOXLAB client (others `404` + audit); MRO queue, verdicts (negative, legitimate prescription, positive, dilute, adulterated, substituted, cancelled) with prescription verification and encrypted interview note; split-specimen orders; designated physician as acting MRO (`substance.acting-mro-physicians`) | Collector, MRO, partner laboratory |
| **E10-F04** Positive / refusal pathway | Synchronous on the MRO decision: restricted SUBSTANCE case, duty status REMOVED via `publishFitness`, `testing.status` to HR and crew scheduling (status only, disclosure-logged), support-programme referral to E09, draft return-to-duty plan; follow-up tests on randomised unannounced dates (visible to the MRO only); RTD evaluation – READY requires a verified negative RTD test before AVAILABLE; a later CANCELLED split result reverses the positive | MRO, OH physician, HR, crew scheduling |
| **E10-F05** Programme reporting | Statistics by reason × role group (tests, verified positives, refusals, rate) with suppression; anonymised annual regulatory report as PDF or CSV | D&A programme manager, CMO, safety manager |

Employees and managers never see results: test statuses are masked (`COLLECTED`, `COMPLETED`, `CLOSED`) and the
employee notice shows only where and when to report.

### 2.3 E11 – Travel Health, Immunisation Campaigns & Communicable Disease / Exposure Management

| Feature | What is new | Main users |
|---|---|---|
| **E11-F01** Travel & posting consultation | 15 in-house destinations (risks, recommended vaccines, prophylaxis, advice; employees see advice only); consultation with recommendations against the E07 immunisation history (late-consultation flag), vaccines recorded in E07 (mirrored to the record), prophylaxis, signed `TRAVEL_HEALTH_CERTIFICATE` shared to the employee; `/me/travel` with upcoming roster destinations; `travel.consultation-due` reminders at most every 30 days | OH nurse, OH physician, employee |
| **E11-F02** Communicable disease & contact tracing | 9 conditions (notifiable, report-within hours, exclusion days, food-handler exclusion, monitoring days); cases CD-YYYY-NNNNNN with confirmation time; exclusion / isolation (fitness TEMPORARILY_UNFIT, food handlers through the E05 exclusion – catering hygiene sees status and dates only); tracing from crew rosters, shifts and facilities (HIGH = same proximity group ≥ 15 min, MEDIUM = same duty / facility ≥ 15 min, LOW otherwise) with the 4-hour KPI; contact notices that name neither condition nor case; daily app check-ins, symptomatic contacts alert the nurse; clearance only when every return criterion is met | OH nurse, OH physician, employee |
| **E11-F03** Exposure pathways with protocol timers | Pathways NEEDLESTICK_BBP, CHEMICAL_DGR, CABIN_FUME, HEAT_ILLNESS, BITE_STING with timed tasks; serology at 6 weeks, 3 and 6 months; chemical register with SDS links; started automatically from the incident feed (once per incident); `exposure-timers` job alerts once when due soon (30 min) and once when overdue | OH nurse, OH physician, AME |
| **E11-F04** Vaccination campaigns | Campaigns VC-YYYY-NNNNNN with eligibility (all / job codes / subsidiaries), sites and target uptake; invitations that never name the vaccine; booking on acceptance through `AppointmentBooking`; fast administration entry by employee id or badge, lot number or GS1 barcode (recorded in E07 with the campaign id); declines and adverse events; uptake by subsidiary / department with suppression | Nursing Head, OH nurse, employee, CMO |
| **E11-F05** Health advisories | Authoring with destination, base, job codes, severity and validity; targeting of crew rostered to the destination plus base staff; daily re-targeting of newly rostered crew; acknowledgement in the employee app; acknowledgement rate (suppressed when small) with a pending list for OH | OH physician, CMO, AME, crew |
| **E11-F06** Public-health reporting | Reports PHR-YYYY-NNNNNN generated as `PUBLIC_HEALTH_REPORT` for a case or exposure, due from the condition's report-within hours (exposures 72 h); submission tracking by channel with on-time flag; correspondence log incl. uploaded `AUTHORITY_CORRESPONDENCE`; hourly deadline job (`public-health.report-due`) | OH physician, CMO, authority liaison |

### 2.4 E12 – Health Promotion, Wellbeing & Prevention Campaigns

| Feature | What is new | Main users |
|---|---|---|
| **E12-F01** Campaign planner | Anonymised insights per unit and risk (MSK, hearing, metabolic, fatigue, mental wellbeing) from kernel cases, intake incidents, E03 audiometry and E12 screenings – restricted-class cases excluded, primary and complementary suppression; campaigns with objective, target units, insight reference, budget, timeline, owners, KPIs and status; 5-campaign portfolio seeded `PLANNED` (**approval pending**); publication sets ACTIVE, invites targeted staff and sends a flat `CampaignPublished` event to the `campaign-app` outbox | Health promotion lead, CMO, Nursing Head |
| **E12-F02** Screening events & follow-up | Events (biometric, MSK, hearing, vision, mental wellbeing) with participants booked through `AppointmentBooking`; manual result entry for blood pressure, BMI, glucose, cholesterol, waist, MSK and wellbeing scores against configurable thresholds; abnormal → follow-up kernel case with a task due within 48 h, signed `RESULT_LETTER`, `screening.follow-up`; hourly sweep for any abnormal result still without a case; hearing-conservation education attendance with an aggregated report | OH nurse, health promotion lead (counts only), employee |
| **E12-F03** Ergonomic assessments | Checklists for DSE workstation, manual handling and task (0–3 per item → weighted risk score and RAG), job-demand profile attached, photos (`ERGONOMIC_PHOTO`), actions with owner and due date, managers close their own unit's actions, daily `ergonomic.action-due` reminders, closure-within-60-days KPI | Ergonomist, HSE, manager |
| **E12-F04** Campaign-app & wearable integration | Participation import for the internal campaign app (`received / accepted / duplicates / unmatched / rejected`); wearable enrolment only with an active campaign and `WEARABLE_DATA` consent; daily aggregates only (steps, sleep minutes, active minutes); withdrawal – or withdrawing the consent – purges the data | Health promotion lead, employee |
| **E12-F05** Participation & outcome analytics | Report per campaign grouped by unit, subsidiary, job code or location: participation with suppression, outcome baseline / current / delta from screenings, wearables and MSK cases, KPIs target vs actual; CSV export; unit participation in the manager workspace | Health promotion lead, CMO, manager |

### 2.5 Platform

* **Restricted classes** – kernel categories `PSYCHOLOGICAL` and `SUBSTANCE` carry `DataClassification.RESTRICTED_CLINICAL`;
  `/me` reports disclosure level `RESTRICTED_CLINICAL` for `PSYCH_CLINICIAN` and `MRO`; every denied attempt is audited;
  break-glass (increment 1–2) is logged and reviewed. Small-cell suppression (`wellnessz.analytics.min-group-size`,
  default 5) applies to every new aggregate.
* **Duty assignments** (V16 `masterdata.duty_assignment`) – crew rosters, shifts and facility access fed by internal
  systems through `POST /integration/duty-assignments` (internal clients only) and read through
  `GET /masterdata/duty-assignments`; used by E09 CISM, E11 contact tracing and advisory targeting.
* **Kernel SPI** `SupportProgrammeReferral` (implemented by E09, called by E10).
* **Partner organisations** `EAP_WELLBEING`, `TOXLAB`, `UKHSA_LOCAL`; retention classes `RESTRICTED_RECORD` and
  `PUBLIC_HEALTH` (10 years, **proposed – DPO approval pending**).
* **Documents** – `DocumentService.generateSigned(…, DataClassification)` stores restricted-class documents; templates
  `AUTHORITY_FITNESS_REPORT`, `CHAIN_OF_CUSTODY_FORM`, `TRAVEL_HEALTH_CERTIFICATE`, `PUBLIC_HEALTH_REPORT`; upload types
  `ERGONOMIC_PHOTO`, `AUTHORITY_CORRESPONDENCE`.
* **Notifications** – content-free templates `support.*`, `cism.*`, `testing.*`, `travel.consultation-due`, `tracing.*`,
  `exposure.timer-alert`, `advisory.published`, `public-health.report-due`, `campaign.invitation`, `screening.follow-up`,
  `ergonomic.action-due`; the content rules refuse words such as *positive*, *negative*, *result*, *drug* and *alcohol* in
  any notification.
* **Outbound allow-lists** – `campaign-app` (campaign publication to the group health-campaign application) and
  `sms-safety`, the feed to the airline's **Safety Management System (SMS)** carrying aggregated, suppressed
  peer-support / CISM figures (`messageId, quarter, reportRef, category, count, base`). `sms-safety` is not a text-message
  (SMS) gateway and carries no personal data.
* **BFF** – `/bff/api/workspace/{testing, psychosocial, campaigns, ergonomics}/**` keep their prefix; workspace roles add
  `SAFETY_MANAGER` and `CREW_SCHEDULING`.
* **Authentication** – core API, BFF and the four web apps work with Microsoft Entra ID, Keycloak or any standards-based
  OIDC provider (for example Okta, Ping, Auth0, AD FS 2019 or later); the MFA gate for partner / TPA roles accepts proof in
  `amr`, `acr` or the Entra ID `acrs` claim (`WZ_MFA_REQUIRED_ROLES`).
* **Secrets** – database connector passwords never appear in plain configuration: `wz-secrets`
  (`SecretValueResolver`, `SecretsEnvironmentPostProcessor`) resolves `ENC(…)` values (AES-256-GCM, produced by
  `EncryptCli`, decrypted with a master key held in Key Vault / Secret Manager / Vault) and `file:` references to mounted
  secret files (CSI driver / External Secrets) for sensitive property names; clinical columns use field-level encryption
  (`EncryptedStringConverter`, HKDF-derived keys). See `docs/architecture/02-Security-and-SOC2-Control-Mapping.md`.
* **Error handling** – `GlobalExceptionHandler` answers malformed JSON with `400 malformed_request` and mistyped
  parameters with `400 invalid_parameter` instead of a `500`.
* **Architecture rules** – `ArchitectureTest` now holds 22 rules: the four new modules depend on each other only through
  kernel SPIs, and no earlier module depends on E09–E12.

---

## 3. What is new, by application

| Application | New screens and behaviour |
|---|---|
| **OH Workspace** (`web/oh-workspace`) | *Support* group: `#/support/cases` (+ detail with notes, screens, contacts, timeline), `#/support/urgent` (call-back countdown, protocol, governance panel), `#/support/eap`, `#/support/fitness` (fitness assessments, relief and return); restricted-case placeholder on the employee chart; `#/peer-support` (volunteer: my requests and contact log; coordinator: register, training, quarterly SMS report and send); `#/cism` (+ detail); `#/psychosocial`. *Testing* group: `#/testing/policies`, `pools`, `selections`, `requirements`, `collection` (scanner-friendly specimen field), `mro`, `rtd`, `reports`. *Public health* group: `#/travel/destinations`, `#/travel/consultations`, `#/communicable` (+ tracing), `#/exposures` (+ live timers), `#/vaccination-campaigns` (fast entry with GS1 lot scanning), `#/advisories`, `#/public-health`. *Promotion* group: `#/promotion/insights`, `#/promotion/campaigns`, `#/promotion/screening`, `#/ergonomics` (photo upload), `#/promotion/reports` (CSV). Role gating: restricted roles (peer support, collector, MRO, programme manager, ergonomist, health promotion lead) never reach other clinical screens; general OH roles never reach support cases or the MRO queue |
| **Employee app** (`web/employee-app`) | New *Wellbeing and prevention* tile block on Home and nav entries: `/support` (emergency banner first, signposting, self-referral, PHQ-9 / GAD-7 without "diagnosis" wording, sharing choice, CISM offers), `/peer-support` (pseudonymous contact request), `/travel` (consultations, certificates, upcoming destinations, advisories with acknowledgement), `/check-ins` (symptom check-ins), `/campaigns` (invitations, vaccination response, screening bookings, wearable consent and withdrawal), `/testing` – *workplace checks*: only where and when to report, with a countdown. English, French and Arabic strings |
| **Manager workspace** (`web/manager-workspace`) | `/testing` – managers and safety managers: for-cause request with observation checklist and own requests (status only); HR and crew scheduling: duty-status list; `/psychosocial` – unit assessment and actions plus the outcomes employees chose to share; `/campaigns` – unit participation ("Fewer than N – suppressed"); `/ergonomics` – unit actions, mark done. Users with only `SAFETY_MANAGER` or `CREW_SCHEDULING` land on `/testing` |
| **Partner portal** (`web/partner-portal`) | `/eap-referrals` for the EAP partner: minimal referral list for the own organisation; feedback form with status, attendance rows and closure reason – deliberately **no free-text field**; validation of dates, authorised sessions and closure |
| **Core API / BFF** | Four new modules (`wz-e09-mentalhealth`, `wz-e10-substance`, `wz-e11-travelhealth`, `wz-e12-promotion`), duty feed, 138 new API paths, 9 new scheduled jobs; BFF workspace roles extended |

Screenshots (35): `docs/images/screens/increment-05/` – `inc5-oh-01` … `inc5-oh-24`, `inc5-emp-01` … `inc5-emp-05`,
`inc5-mgr-01` … `inc5-mgr-05`, `inc5-partner-01`. Examples:
[support cases](../images/screens/increment-05/inc5-oh-01-support-cases.png) ·
[restricted chart placeholder](../images/screens/increment-05/inc5-oh-03-chart-restricted-placeholder.png) ·
[MRO queue](../images/screens/increment-05/inc5-oh-12-testing-mro.png) ·
[contact tracing](../images/screens/increment-05/inc5-oh-14-contact-tracing.png) ·
[promotion campaigns](../images/screens/increment-05/inc5-oh-21-promotion-campaigns.png) ·
[employee support](../images/screens/increment-05/inc5-emp-01-support.png) ·
[HR duty status](../images/screens/increment-05/inc5-mgr-02-hr-duty-status.png) ·
[EAP referrals](../images/screens/increment-05/inc5-partner-01-eap-referrals.png).

---

## 4. New roles and demo users

### 4.1 New roles

| Role | Purpose | Disclosure |
|---|---|---|
| `PEER_SUPPORT_VOLUNTEER` | Crew peer volunteer – requests routed to them, own pseudonymous contact log | No employee identity at all |
| `PEER_SUPPORT_COORDINATOR` | Volunteer register, training, availability, aggregated programme figures | Aggregates only |
| `SAFETY_MANAGER` | SMS report, CISM events (counts), post-incident and for-cause requests, testing statistics | Counts and status only |
| `DA_PROGRAMME_MANAGER` | Testing policies, pools, random selections, coverage, statistics | Never results (MRO queue `403`) |
| `MRO` | Medical review officer – laboratory results, verdicts, split specimen, return-to-duty | `RESTRICTED_CLINICAL` for E10 results |
| `COLLECTOR` | Specimen collection, electronic chain of custody, refusals | Requirements without observations; never results |
| `HEALTH_PROMOTION_LEAD` | Insights, campaigns, screening events (counts), reports | Anonymised, suppressed figures |
| `ERGONOMIST` | Ergonomic assessments, photos and actions | No clinical record |

Existing roles with new rights: `PSYCH_CLINICIAN` (named mental-health clinician – support cases, urgent items, EAP, CISM,
relief), `AME` (relief return, mental-health fitness, advisories), `OH_PHYSICIAN` (fitness, RTD, destinations, advisories,
public-health reports; acting MRO only when listed), `OH_CLINICIAN` / clinicians (CISM, travel, communicable disease,
exposures, vaccination, screening results), `NURSING_HEAD` (vaccination campaigns and uptake, insights), `CMO`
(governance, reports, statistics), `HSE_OFFICER` (psychosocial register, ergonomics), `HR` / `CREW_SCHEDULING` (duty status
only), `MANAGER` (for-cause requests, shared outcomes, unit campaigns and ergonomics), `PARTNER_PROVIDER` (EAP referrals),
`EMPLOYEE` (support, peer support, travel, check-ins, campaigns, testing notices, wearables), `INTEGRATION` (duty feed,
laboratory, campaign participation, wearable data).

### 4.2 Demo users (local realm `deploy/local/keycloak/wellnessz-realm.json`; password as for the other demo users – see `docs/setup/01-Local-MacBook-Pro.md`)

| User | Role(s) | Employee ID / org scope | Used for |
|---|---|---|---|
| `peer1` (new) | PEER_SUPPORT_VOLUNTEER | S8801 | My requests (PS-DEMQ-7K2R "Blue Jay"), contact log |
| `peercoord1` (new) | PEER_SUPPORT_COORDINATOR | S8802 | Volunteer register, quarterly SMS report |
| `safety1` (new) | SAFETY_MANAGER | S8803 | CISM counts, for-cause request, statistics |
| `damanager1` (new) | DA_PROGRAMME_MANAGER | S8804 | Policies, pools, selections, coverage |
| `mro1` (new) | MRO | S8805 | MRO queue (DAT-YYYY-900003), RTD plans |
| `collector1` (new) | COLLECTOR | S8806 | Post-incident collection (DAT-YYYY-900005) |
| `promo1` (new) | HEALTH_PROMOTION_LEAD | S8807 | Insights, campaign planner, reports |
| `ergo1` (new) | ERGONOMIST | S8808 | Ergonomic assessments |
| `eap1` (new) | PARTNER_PROVIDER | X8809, **/PARTNERS/EAP_WELLBEING** | Partner portal EAP referrals (EAP-YYYY-900001) |
| `psych1`, `ame1` | PSYCH_CLINICIAN, AME | – | Support cases, urgent call-back, relief return |
| `nurse1`, `physician1`, `cmo1`, `nursinghead1` | OH roles | – | Tracing, exposures, consultations, advisories, campaigns, screening |
| `employee1`, `manager1`, `hr1` | EMPLOYEE, MANAGER, HR | E1001, –, H3001 | Employee app, shared outcomes, duty status |

Demo data highlights: support cases SUP-YYYY-900001 (self-referral, PHQ-9 11, EAP referral), -900002 (URGENT with a
call-back due in 3 h, shares fitness only) and -900003 (from the CISM event, active relief); CISM-YYYY-900001 (fume event on
WZ101); psychosocial assessments CATERING (RED) and GROUND (AMBER); four reproducible random selections, a post-incident
test with about 5 hours left in the alcohol window, a verified positive with an active RTD plan; norovirus case
CD-YYYY-900001 on catering line CAT-LINE-2, needle-stick and cabin fume exposures, influenza campaign VC-YYYY-900001, DXB
advisory, public-health report PHR-YYYY-900001 due in about 71 h; MSK campaign (ACTIVE), a biometric screening event
tomorrow, catering trolley assessment (RED 67) with an overdue action, wearable enrolment with 14 days of steps. The
demo seeders (local / test profiles only) fill encrypted fields through the application.

---

## 5. API summary

The authoritative references are the contract `docs/increment-05/API-Contract-E09-E10-E11-E12.md` and the regenerated
OpenAPI document `docs/api/openapi.json`, which grows from 509 to **647 paths** (+138 paths, 165 operations):

| Area | Paths | Operations |
|---|---|---|
| E09 `/support/**` (cases, placeholder, screens, urgent, protocol, EAP, governance, relief, fitness, peer referrals, consents) | 18 | 21 |
| E09 `/peer-support/**`, `/cism/**`, `/psychosocial/**` | 15 | 20 |
| E09 `/me/{support, peer-support, cism}/**`, `/workspace/psychosocial/**`, `/partner/eap-referrals/**` | 13 | 14 |
| E10 `/testing/**` (policies, pools, selections, coverage, requirements, tests, specimens, MRO, RTD, statistics, reports) | 20 | 24 |
| E10 `/workspace/testing/**`, `/me/testing`, `/integration/testing/**` | 5 | 6 |
| E11 `/travel/**`, `/communicable/**`, `/exposures/**` | 18 | 22 |
| E11 `/vaccination-campaigns/**`, `/advisories/**`, `/public-health/**` | 15 | 19 |
| E11 `/me/{travel, check-ins, vaccination-campaigns, advisories}/**` | 7 | 7 |
| E12 `/promotion/**`, `/ergonomics/**` | 18 | 23 |
| E12 `/workspace/{campaigns, ergonomics}`, `/me/{campaigns, wearables}/**`, `/integration/{promotion, wearables}/**` | 7 | 7 |
| Platform `/integration/duty-assignments`, `/masterdata/duty-assignments` | 2 | 2 |

**Where the code deviates from the contract** (the code is authoritative):

| Area | Deviation |
|---|---|
| E09 signposting | `GET /me/support/signposting` returns `{items:[…], emergency:{text, phone}}` (an array cannot carry the emergency block) |
| E09 error codes | Non-named access to PSYCH-only `/support/**` endpoints → `404` (audited); CISM, peer-support, psychosocial and partner endpoints use role checks → `403` |
| E09 extras | `GET /support/cases/{id}/consents`, `GET /support/relief?status=`, `GET /support/eap-referrals`, `GET /support/peer-referrals` + `POST …/{id}/pick-up` (trusted professional's queue), `GET /peer-support/contacts`; optional volunteer `email` for content-free notification; volunteers alerted with `support.urgent-callback` (level "peer contact request"); EAP consent accepts `TREATMENT_SHARING_PARTNER` or `EAP_REFERRAL`; manual CISM by a safety manager returns counts only; relief without `expectedUntil` sets UNFIT_PENDING; relief or fitness for an employee without an open support case opens a clinician support case |
| E10 views | Statuses shown to employees and managers are masked (`COLLECTED` / `COMPLETED` / `CLOSED`); `notify` returns the requirement rows; statistics rows carry `reason` and `roleGroup`; `verify` returns the seed, `poolHashMatches`, `seedCommitmentMatches`, `selectionMatches` |
| E10 rules | Pending policies do not block selections (`policyApprovalStatus` recorded); breath-only tests go straight to MRO_REVIEW; split results return the test to MRO_REVIEW and a CANCELLED verdict reverses a positive; non-crew roles get the HR fitness feed but not the crew-rostering feed; selection periods only previous / current / future month |
| E10 extras | `GET /testing/specimens/{specimenId}/custody`, `GET /testing/rtd-plans/{id}` |
| E11 extras | `GET /communicable/cases[/{id}]`, `POST /communicable/cases/{id}/confirm`, `GET /vaccination-campaigns/{id}`, `GET …/adverse-events`, `GET /advisories/{id}`, `GET /public-health/reports/{id}`, `POST …/correspondence/upload`; table `consultation_reminder` |
| E11 rules | Advisory `roles[]` = job codes for base staff (rostered crew always targeted); acknowledgement statistics suppressed while OH keeps `pending[]`; advisory body not screened (general destination advice); vaccines given in a consultation recorded with consent confirmed; campaign bookings on acceptance; exposure statuses OPEN / OVERDUE / CLOSED (COMPLETED alias); exposure reports due 72 h after the exposure; recommendations limited to the E07 vaccine vocabulary |
| E12 | Service class `PromotionCampaignService` (a `campaignService` bean already exists in E14); extra `GET /ergonomics/checklists`, `GET /ergonomics/kpi`; screening events also open to OH_PHYSICIAN, AME, CMO, NURSING_HEAD; CMO reads campaigns; participant lists only for clinicians; results accepted from the day before the event, one per registered participant (`409`, `422 not_registered`); letters only for abnormal results; report as CSV with `format=csv` |
| Errors | Malformed JSON → `400 malformed_request`; mistyped parameter → `400 invalid_parameter` (previously `500`); screened clinical text → `422 clinical_content_not_allowed` with `field`; extra EAP feedback fields → `422 field_not_allowed` |

---

## 6. Database migrations

| Migration | Schema(s) | Content |
|---|---|---|
| `V16__increment5_platform.sql` | masterdata, docs | `duty_assignment` with indexes on employee, duty, proximity group and destination; partner organisations `EAP_WELLBEING`, `TOXLAB`, `UKHSA_LOCAL`; retention classes `RESTRICTED_RECORD`, `PUBLIC_HEALTH` (10 y – **proposed**) |
| `V17__e09_mentalhealth.sql` | mentalhealth (new) | 26 tables (support cases, notes, contacts, events, instruments / items / options / bands, screen results, urgent items, protocol, signposting, EAP referrals, peer volunteers / training / contact requests / contacts, trusted referrals, SMS reports, relief, CISM events / affected, psychosocial assessments / actions, fitness assessments, reference counter); seeds signposting, PHQ-9 / GAD-7, urgent-risk protocol (**pending sign-off**), 12 configuration entries |
| `V18__e10_substance.sql` | substance (new) | 11 tables (panels, policies, pool exclusions, selections, tests, specimens, custody events – insert-only with a trigger, duty status, RTD plans, post-incident rules, reference counter); 6 policies **PENDING**; 6 configuration entries |
| `V19__e11_travelhealth.sql` | travelhealth (new) | 20 tables (destinations, consultations and reminders, conditions, CD cases, contacts, check-ins, pathways, chemicals, exposures and tasks, campaigns / invitations / declines / adverse events, advisories / targets, public-health reports / correspondence, reference counter); 15 destinations, 9 conditions, 5 pathways, 6 chemicals, 8 configuration entries |
| `V20__e12_promotion.sql` | promotion (new) | 13 tables (campaigns, invitations, participation, screening events / participants / results, education attendance, ergonomic checklists / assessments / photos / actions, wearable enrolments / daily values); 5 portfolio campaigns, 3 checklists, thresholds and settings |
| `db/demo/R__demo_e09…e12_*.sql`, `R__demo_reference_duties.sql` (repeatable) | as above + masterdata, casemgmt, fitness | Demo population (Section 4.2). **Loaded only with the `local` / test profiles**; never enable `classpath:db/demo` in shared environments |

Each module migration starts with `create schema if not exists …`. Grants follow the earlier pattern (no DELETE; custody
events insert-only). The schema lists (core `spring.flyway.schemas`, Helm `values.yaml`, local Postgres init script,
managed-database bootstrap and the CloudNativePG cluster) already contain `mentalhealth, substance, travelhealth,
promotion`.

**Rollback.** Migrations are forward-only. Running the increment-4 application against the V20 schema is not supported:
restricted support cases, positive test pathways, exclusions and exposure timers created after the upgrade would no
longer be governed by any module. Rolling back means restoring the pre-upgrade backup with the previous images.

---

## 7. Configuration

| Item | Change |
|---|---|
| `spring.flyway.schemas` (core) | Adds `mentalhealth, substance, travelhealth, promotion` |
| `mentalhealth.named-clinicians` (`masterdata.config_entry`) | **Must be changed per client before go-live.** V17 seeds the demo user `psych1` in **every** environment (not only `local`); set the production usernames of the named mental-health clinicians. Empty = every `PSYCH_CLINICIAN` |
| `mentalhealth.emergency-text`, `mentalhealth.emergency-phone` | Default UK wording and `999` – **set per country / subsidiary** |
| `mentalhealth.governance.sign-off` | `PENDING` until the CMO / mental-health lead approve instruments, protocol and signposting |
| E09 settings | `mentalhealth.urgent-callback-hours` (4), `first-contact-working-days` (2), `eap-first-appointment-working-days` (5), `peer-callback-retention-days` (30); `cism.trigger-classes`, `cism.offer-sla-hours` (72), `cism.duty-window-hours` (2) |
| `substance.laboratory-code` | `TOXLAB` – organisation whose bound client may use the laboratory interface |
| `substance.acting-mro-physicians` | Empty – usernames of OH physicians allowed to act as MRO |
| E10 settings | `substance.collection-site`, `substance.breath-alcohol-cutoff` (0.02, **MRO / Legal to confirm**), policy table (**PENDING**) |
| Mailboxes | **Set per environment:** `substance.hr-status-email`, `notifications.crew-scheduling.email` (status-only duty notices) |
| E11 settings | `travelhealth.tracing.high-risk-minutes` (15), `max-window-days` (21), `kpi-hours` (4), `check-in.fever-threshold-c` (38.0), `exposure.alert-before-minutes` (30), `public-health.alert-before-hours` (12), `public-health.exposure-report-hours` (72), `consultation.lookahead-days` (30) |
| E12 settings | `promotion.screening.threshold.*` (systolic ≥ 140, diastolic ≥ 90, BMI ≥ 30, glucose ≥ 7.0, cholesterol ≥ 6.5, waist ≥ 102, MSK ≥ 7, wellbeing ≤ 40 – **clinical sign-off**), `promotion.screening.follow-up-hours` (48), `promotion.ergonomics.reminder-days` (7), `closure-target-days` (60), `promotion.participation.target-pct` (40), `promotion.wearables.max-age-days` (31) |
| `wellnessz.integration.outbound-allowlist.campaign-app` | **New:** `messageId, campaignId, title, risk, startDate, endDate, targetUnits` |
| `wellnessz.integration.outbound-allowlist.sms-safety` | **New** – feed to the Safety Management System (aggregated peer-support / CISM figures; not a text-message gateway): `messageId, quarter, reportRef, category, count, base` |
| Job schedules (UTC) | `wellnessz.mentalhealth.cism-follow-ups-cron` (08:20), `peer-callback-purge-cron` (01:40); `wellnessz.substance.follow-ups-cron` (06:15), `windows-cron` (hourly :20); `wellnessz.travelhealth.exposure-timer-cron` (every 5 min), `public-health-cron` (hourly :07), `daily-cron` (06:20); `wellnessz.promotion.ergonomic-job-cron` (02:20), `follow-up-job-cron` (hourly :15) |
| `WZ_ANALYTICS_MIN_GROUP` | Default 5 – now also suppresses every E09–E12 aggregate |
| Client customisation (Helm) | `customisation.client` → `WZ_CLIENT_ID`; `customisation.featureFlags` → `WZ_FEATURE_<NAME>` on core and BFF; `customisation.migrations` → `WZ_FLYWAY_EXTRA_LOCATIONS`, `WZ_FLYWAY_CLIENT_SCHEMAS`, `WZ_FLYWAY_CLIENT_TABLE` (Section 8.2) |
| Observability (Helm) | `observability.{logLevel, rootLogLevel, traceSampling}` → `LOGGING_LEVEL_*`, `MANAGEMENT_TRACING_SAMPLING_PROBABILITY`, `WZ_TRACE_SAMPLING`; `global.requireImageDigests` (on in UAT / PROD) |

---

## 8. Environment engineering

### 8.1 DEV / UAT / PROD and promotion

* **Terraform** (`deploy/terraform/{azure,gcp}`): variable `environment` is now `dev`, `uat` or `prod` (no default; the
  former `test` environment is renamed `uat`); per-environment var files and backend examples in `environments/`
  (`dev.tfvars`, `uat.tfvars`, `prod.tfvars`, `backend-<env>.hcl.example`, `local.tfvars.example`); non-overlapping network
  ranges; environment-dependent zones, HA, backups / PITR, WAF mode, deletion protection, locks and log retention. On GCP,
  GitHub can impersonate the deployer only from its own GitHub Environment (`gcp-<env>`) or the publish branches.
* **Helm** (`deploy/helm/wellnessz/environments/{azure,gcp,hybrid}/{dev,uat,prod}.yaml`): replicas, HPA / PDB, resources,
  hosts (`*.dev.`, `*.uat.`, plain in PROD), OIDC issuer / audience per environment, MFA roles off in DEV only, log level
  and trace sampling, namespace default-deny (UAT / PROD), migrations in-app (DEV) or as a pre-upgrade Job (UAT / PROD),
  image digests required in UAT / PROD.
* **GitHub Actions**: `container.yml` builds and signs the six images once per `main` commit; `cd.yml` deploys every
  `main` build to DEV, an `-rc` tag DEV → UAT and a final tag DEV → UAT → PROD, for each cloud in `DEPLOY_CLOUDS`, with
  the same verified digests; `deploy-env.yml` enforces the tag policy, logs in with OIDC, verifies cosign signatures, copies
  images by digest into the environment registry (with a Binary Authorization attestation on GKE), runs
  `helm upgrade --atomic`, smoke-tests (rollout, promoted digests running, optional `SMOKE_URLS`) and rolls back
  automatically on failure (also on private AKS clusters). Operators can use `deploy/scripts/helm-release.sh` (previous,
  upgrade, smoke, rollback).
* The environment matrix and the DEV / UAT / PROD differences are documented in `deploy/environments/README.md`.

### 8.2 Client customisation

* Per-client values file (for example `deploy/clients/<client>/values.yaml`, passed through `EXTRA_VALUES_FILES`) – no
  chart fork. `customisation.client` sets `WZ_CLIENT_ID` and the label `wellnessz.io/client`; `core.extraEnv` /
  `bff.extraEnv` add further environment variables.
* **Client SQL migrations** run after the product migrations in their own schema (`client_<client>`) with their own
  history table (`flyway_schema_history_client`), from an extension image or a ConfigMap – as an extra `flyway-client`
  step in the migration Job, or in-app through `ClientMigrationsConfig` – so client versions never collide with product
  versions.
* **Feature flags** become `WZ_FEATURE_<NAME>` on core and BFF – see Section 10 for their current scope.
* **Client Java code**: custom modules are added to the Maven build or maintained on a client fork / branch with its own
  build; mounting extension JARs into the core image is not supported. SPI overrides of `NotificationChannel` and
  `SupportProgrammeReferral` need `@Order(Ordered.HIGHEST_PRECEDENCE)` (not `@Primary`).
* The complete procedure – decision tree, client overlay, configuration and reference data, feature flags, client
  migrations, SPI implementations and client modules – is in the
  [Client Customisation Guide](../customisation/Client-Customisation-Guide.md); see also `deploy/environments/README.md`
  and section 8 of the developer environment guide.

### 8.3 Developer environment guide

`docs/developer-setup/` (packaged as a separate zip): prerequisites for macOS (incl. Apple silicon), Windows 11 with WSL2
and Ubuntu; clone, build and run step by step with encrypted IDE env files (`make-ide-env.sh`, master keys outside the
repository); IntelliJ IDEA (32 run configurations and code style), VS Code (launch, tasks, settings, extensions), Eclipse /
Spring Tools (attach configurations) and Dev Container / Codespaces; connecting the database client, Keycloak, Swagger,
Mailpit / GreenMail, OpenTelemetry and the Playwright E2E scripts; local quality gates and a pre-commit hook that blocks
staged secrets; troubleshooting; adding a module or a client customisation. IDE files are installed with
`ide/scripts/install-ide-files.sh`, which never overwrites a file.

---

## 9. Verification results (30 October 2026)

| Check | Result |
|---|---|
| Java build and tests (`./mvnw verify`) | **450 tests, 0 failures** – including `Increment5MentalHealthIntegrationTest` (11), `Increment5SubstanceIntegrationTest` (6), `Increment5TravelHealthIntegrationTest` (14), `Increment5PromotionIntegrationTest` (13) on PostgreSQL 16, `ArchitectureTest` (22 rules), `ClientMigrationsConfigTest` (5) and the E09–E12 unit tests |
| SAST (SpotBugs + FindSecBugs, `-Psecurity`) | **0 findings** |
| Web unit tests (vitest) | oh-workspace **769**, employee-app **285**, manager-workspace **188**, partner-portal **131** = **1,373**; eslint 0 warnings; production builds clean |
| End-to-end, real Keycloak logins through the BFF, four apps | Increment 5 **24 / 24** (`tools/e2e/e2e-increment5.js`); regressions Increment 1 **18 / 18**, Increment 2 **25 / 25**, Increment 3 **32 / 32**, Increment 4 **22 / 22** – **121 checks, all green**; 35 screenshots in `docs/images/screens/increment-05/` |
| Infrastructure as code | `terraform fmt` / `validate` for Azure and GCP; `helm lint` + kubeconform on 21 value combinations (base, 9 environment overlays, hybrid edge, customisation); actionlint with shellcheck on all workflows |
| API contract | `docs/api/openapi.json` regenerated – **647 paths** (was 509) |

End-to-end evidence (Increment 5):

| Flow | Observed |
|---|---|
| Self-referral and screening | Employee self-refers with signposting; PHQ-9 with a self-harm answer → score 11, band MODERATE, **urgent = true** (same-day call) |
| Restricted class | Named clinician sees the case and the urgent item; general OH nurse → 404, placeholder count only |
| Sharing | Urgent call-back documented; employee shares fitness only; the manager sees exactly that – no screening or notes |
| EAP partner | Minimal referral data for the own organisation; attendance returned; free text → `422 field_not_allowed` |
| Peer support | Request reaches a trained peer as alias + code; contact log holds no employee identity |
| CISM | Affected crew from the WZ101 roster; offers within 72 h; safety manager sees counts only |
| Psychosocial | Register by department with actions and an anonymised case rate (small groups suppressed) |
| Relief | AME ends the temporary relief from flight duties – rostering receives the status only |
| Random selection | Reproducible from its stored seed; programme manager → 403 on the MRO queue |
| Collection | Post-incident test collected inside the alcohol window with an electronic chain-of-custody form |
| MRO positive | Employee removed from safety-sensitive duty; HR sees status only; confidential support referral opens |
| For cause | Documented request with observation checklist and second observer |
| Laboratory binding | Results accepted only from the toxicology laboratory system; unbound client → 404 |
| Statistics | By reason and role group with small cells suppressed |
| Contact tracing | From roster and shift data in **1.03 h** (target 4 h); contacts notified without naming the case |
| Needle-stick | Protocol timers; PEP decision due within hours |
| Travel | Destination-based recommendations and a signed travel health certificate |
| Advisory | Reaches crew rostered to the destination; acknowledgement tracking with small counts suppressed |
| Public health | Notifiable-disease report submitted to the health protection team within the statutory time |
| Influenza campaign | Uptake by subsidiary with small groups suppressed |
| Campaigns | Planned from anonymised hotspots; participation and outcome report with suppression – no person-level data |
| Screening | Abnormal blood pressure opens a follow-up case within 48 h and a result letter |
| Ergonomics | Manual-handling assessment score 54, AMBER, with job-demand profile and actions to closure |
| Wearables | Accepted only for consented enrolments (daily totals); data without consent rejected |

---

## 10. Known limitations and decisions needed

| Area | Limitation / decision | Planned handling |
|---|---|---|
| MRO and generic documents | The ABAC restricted-class rule recognises `PSYCH_CLINICIAN`; an MRO opens results and the chain-of-custody form through the E10 MRO endpoints, but probably **not** through the generic `/documents` API for `RESTRICTED_CLINICAL` documents | Follow-up item: extend the restricted-class policy to the MRO for E10 documents |
| Feature flags | `WZ_FEATURE_*` flags are plumbing only: delivered to core and BFF as environment variables, but **no product feature is switched by them yet** | Client features read them when built |
| Client extension JARs | Mounting custom Java modules into the core image is **not implemented** (would need `PropertiesLauncher` with the layered image). Client code customisation = fork / branch with its own build, or a client module added to the Maven build; SQL migrations and configuration are supported without forking | By design for now |
| GitHub Environments | Required reviewers and protection rules for `azure-*` / `gcp-*` environments must be configured in the repository settings – they cannot be set from code | Upgrade step 9 |
| Dev Container | **Decision needed:** commit `.devcontainer/` at the repository root (so Codespaces finds it automatically) or keep it under `docs/developer-setup/ide/devcontainer` (current state) | Engineering lead |
| External connectors | Still simulated or defaulted: EAP provider system (the partner portal is the channel), toxicology laboratory LIMS (bound machine client and API ready), group campaign app (outbox ready), wearable vendor APIs (internal aggregator endpoint ready), public-health authority submission (tracked by channel; outbox + e-mail default), Safety Management System import of the `sms-safety` feed (outbox ready) | E15 integration onboarding |
| Clinical content | PHQ-9 / GAD-7 bands and urgent rule, signposting and urgent-risk protocol, testing policy cut-offs and rates, travel recommendations table, PEP and other protocol timers, screening thresholds are configurable reference data that need **Clinical Governance / MRO / Legal sign-off before go-live** | Sign-offs (document 02, Section 9) |
| Campaign insights | Absence (E06) data is not an insight source; hearing / vision screening results are not captured in E12; no ROI metric | E17 analytics / Nursing Head decision |
| Named clinicians (**go-live item**) | V17 sets `mentalhealth.named-clinicians` to the demo user `psych1` in every environment – until it is changed, only `psych1` can open support cases and every real `PSYCH_CLINICIAN` gets `404` | Change per client before go-live (upgrade step 5) |
| SPI overrides | A client implementation of `NotificationChannel` or `SupportProgrammeReferral` must be annotated `@Order(Ordered.HIGHEST_PRECEDENCE)` – both are consumed as an ordered list / ordered stream (first match wins), so `@Primary` alone has no effect; prove the override with an integration test | [Client Customisation Guide](../customisation/Client-Customisation-Guide.md), Section 7 |
| MFA | Must be enforced in the production IdP (Entra ID conditional access or Keycloak OTP / WebAuthn) for `PARTNER_PROVIDER` (now including the EAP partner) and `TPA_ADJUSTER` | Upgrade step 4 |
| Demo data | Encrypted narrative text cannot be seeded by SQL; the seeders fill some encrypted fields in the `local` / test profiles only | By design |
| Retention | `RESTRICTED_RECORD` and `PUBLIC_HEALTH` (10 years) proposed | DPO / Legal approval |
| Business activities | Awareness and trust campaign (E09-BL-015), destination-content licence decision (E11-BL-002), outbreak playbook and tabletop exercise (E11-BL-013) | Change & Training, Product Owner |
| Load | No production-volume test of a 50K-staff vaccination campaign, tracing over large rosters or report generation | Performance test before go-live |

---

## 11. Upgrade steps (from increment 4)

1. **Back up** the database (point-in-time recovery enabled) and note the Flyway version (expected V15). Keep the
   increment-4 images available for the rollback window.
2. **Schema list** – the repository files already list `mentalhealth, substance, travelhealth, promotion` (core,
   Helm `values.yaml`, `deploy/local/postgres/01-init-wellnessz.sh`, `deploy/sql/bootstrap-managed-postgres.sql`,
   `deploy/hybrid/cnpg/03-cluster.yaml`). On an **existing** managed database or CNPG cluster, create the four schemas owned
   by `wz_migrator` and update the `wz_app` / `wz_migrator` search paths and default privileges manually, exactly as for
   the other module schemas.
3. **Identity provider – roles and users** – create `PEER_SUPPORT_VOLUNTEER`, `PEER_SUPPORT_COORDINATOR`,
   `SAFETY_MANAGER`, `DA_PROGRAMME_MANAGER`, `MRO`, `COLLECTOR`, `HEALTH_PROMOTION_LEAD` and `ERGONOMIST` (Entra ID: app
   roles and groups) and **add all eight to the role scope mappings of `wellnessz-bff` and `wellnessz-mobile`**
   (`fullScopeAllowed=false` – without the mapping the role is silently dropped). EAP counsellors are `PARTNER_PROVIDER`
   users with `org_scope=/PARTNERS/EAP_WELLBEING` (or the code of your EAP partner in `masterdata.partner_organisation`).
   Locally, re-import the realm (`./deploy/local/down.sh --purge` then `./deploy/local/up.sh`).
4. **MFA** – enforce a second factor for every `PARTNER_PROVIDER` (incl. EAP) and `TPA_ADJUSTER` identity in the
   production IdP and verify the access-token claim (`amr`, `acr` or `acrs`); keep `WZ_MFA_REQUIRED_ROLES` at its default
   in UAT and PROD.
5. **Restricted-class configuration (go-live item)** – V17 seeds `mentalhealth.named-clinicians = psych1` (the demo
   user) in every environment: **change it per client** to the production usernames of the named mental-health
   clinicians before go-live; set `substance.acting-mro-physicians` only if an
   OH physician acts as MRO; set `mentalhealth.emergency-text` / `emergency-phone` per country.
6. **Machine clients** – client-credentials `INTEGRATION` clients for: the toxicology laboratory (**fixed claim
   `org_scope=/PARTNERS/TOXLAB`** matching `substance.laboratory-code` – otherwise `404`); the rostering, shift and facility
   systems feeding `/integration/duty-assignments`, the group campaign app and the wearable aggregator (**no** `org_scope` –
   partner-bound clients are refused). Decode a token and check the claims before handing over credentials.
7. **Outbound allow-lists** – add `campaign-app` and `sms-safety` with the field lists of Section 7 if the allow-list is
   overridden per environment; connect the outbox relay to the group campaign app and to the Safety Management System
   (`sms-safety` carries aggregated figures only – it is not a text-message gateway).
8. **Configuration** – set the mailboxes `substance.hr-status-email` and `notifications.crew-scheduling.email`; review
   `substance.collection-site`, `substance.breath-alcohol-cutoff` and the E11 / E12 settings; keep `WZ_FLYWAY_LOCATIONS`
   **without** `classpath:db/demo`.
9. **Environments** – an existing Terraform `test` stack must be moved / renamed to `uat` (state move) before using
   `environments/uat.tfvars`; create backends from `environments/backend-<env>.hcl.example`; create the GitHub Environments
   `azure-dev`, `azure-uat`, `azure-prod`, `gcp-dev`, `gcp-uat`, `gcp-prod` with their variables and **configure required
   reviewers in the repository settings** (UAT, PROD – CAB / security group, no self-review); set the repository
   variables `DEPLOY_CLOUDS` and, if needed, `RELEASE_REGISTRY` / `EXTRA_VALUES_FILES`. On GCP, re-apply Terraform so the
   narrowed workload-identity binding (`attribute.environment/gcp-<env>`) is in place before the first deployment.
10. **Migrate** – run the Flyway job with the migrator role; confirm V16–V20 applied, the four schemas exist with runtime
    grants and `substance.custody_event` rejects updates.
11. **Deploy** core API, BFF, employee app, manager workspace, OH Workspace and partner portal – by promoting the signed
    release digest (`cd.yml`) or with `deploy-env.yml` / `helm-release.sh` and the environment overlay.
12. **Reference data and sign-offs** – obtain the sign-offs listed in Section 10 (instruments and protocol, testing
    policies, travel recommendations and timers, screening thresholds, campaign portfolio, wearable DPIA, retention
    classes); approve the testing policies in `#/testing/policies`; replace the illustrative EAP, laboratory and authority
    organisations with the contracted partners; register peer volunteers and their training.
13. **Smoke test** – run `tools/e2e/e2e-increment5.js` (and the increment 1–4 scripts) against the environment, or the
    equivalent manual checks: self-referral with urgent screen; general OH → 404 on a support case; EAP feedback without
    free text; random selection `verify`; unbound laboratory client → 404; contact tracing from a fed shift; needle-stick
    timers; abnormal screening → follow-up case; wearable without consent rejected.
14. **Monitoring** – watch the nine new scheduled jobs in `platform.scheduled_job_run` (`cism-follow-ups`,
    `peer-callback-purge`, `substance-follow-ups`, `substance-windows`, `exposure-timers`, `public-health-deadlines`,
    `travelhealth-daily`, `ergonomic-actions`, `screening-follow-up`), the outbox relay for `campaign-app` and
    `sms-safety`, overdue exposure timers and public-health reports, WINDOW_MISSED tests, and audit events for `DENIED`
    restricted-class reads and break-glass use (to be reviewed).

---

## 12. Programme complete – increments 1–5

With increment 5 all planned epics of the WellnessZ OHCM programme are delivered: **13 epic modules** (E01–E12 and E14),
**10 platform modules**, **4 web apps**, the **BFF** and **20 Flyway versions**.

| Increment | Epic | Name | Module |
|---|---|---|---|
| 1 | E01 | Case Intake & Incident-Triggered Case Initiation | `wz-e01-intake` |
| 1 | E02 | Clinical Triage, Risk Stratification & Case Assignment | `wz-e02-triage` |
| 2 | E03 | Occupational Health Record & Clinical Encounter Documentation | `wz-e03-record` |
| 2 | E05 | Fitness for Duty, Work Restrictions & Return-to-Work | `wz-e05-fitness` |
| 3 | E06 | Absence & Sickness Management | `wz-e06-absence` |
| 3 | E07 | Medical Surveillance, Periodic & Statutory Medicals | `wz-e07-surveillance` |
| 3 | E14 | Scheduling, Clinic Operations & Resource Management | `wz-e14-scheduling` |
| 4 | E04 | Injury & Illness Management, Treatment Coordination & Partner Referrals | `wz-e04-treatment` |
| 4 | E08 | Workers' Compensation, Insurance & Claims Coordination | `wz-e08-claims` |
| 5 | E09 | Mental Health, Psychosocial Risk & Peer Support Programmes | `wz-e09-mentalhealth` |
| 5 | E10 | Drug, Alcohol & Psychoactive Substance Testing Programme | `wz-e10-substance` |
| 5 | E11 | Travel Health, Immunisation Campaigns & Communicable Disease / Exposure Management | `wz-e11-travelhealth` |
| 5 | E12 | Health Promotion, Wellbeing & Prevention Campaigns | `wz-e12-promotion` |

Traced requirements per increment (each increment's document 02): increment 1 – 74 items (52 implemented, 22 partial);
increment 2 – 73 (49 implemented, 23 partial, 1 deferred); increment 3 – 82 (69, 13); increment 4 – 57 (43, 14);
increment 5 – 90 (62 implemented, 16 implemented with configuration needing sign-off, 12 partial).

Platform modules: `wz-common`, `wz-security`, `wz-secrets`, `wz-integration`, `wz-masterdata`, `wz-notification`,
`wz-documents`, `wz-case-kernel`, `wz-ehs-intake`, `wz-analytics`. Web apps: OH Workspace, employee app, manager workspace,
partner portal. Remaining work after the programme is go-live preparation rather than new epics: the sign-offs and
decisions in Section 10, production connectors (E15), enterprise analytics (E17) and production-volume load testing.
