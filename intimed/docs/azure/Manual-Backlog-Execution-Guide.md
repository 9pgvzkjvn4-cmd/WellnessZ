# IntiMed – Manual Backlog Execution Guide (release 2.1, Azure Qatar Central)

Everything that software can do is in the product, its Terraform/Helm and its pipelines. This guide covers the remaining steps that need **people, hardware, vendors, licences, audits or regulator decisions** – for every backlog item (1.0 and 2.0) that is PARTIAL or DEFERRED, and for DELIVERED items that still have to be *executed* in the operated environment.

| Section | Title | Owner | Backlog items |
|---|---|---|---|
| [M-01](#m-01) | Azure tenancy, landing zone and regulatory approvals for health data in Qatar | CISO / DPO with the customer's IT and legal | IM-E06-BL-009, IM-E08-BL-002 |
| [M-02](#m-02) | Hospital and clinic connectivity: site-to-site VPN / ExpressRoute and segmentation validation | Customer network team with Platform SRE | IM-E06-BL-002 |
| [M-03](#m-03) | Edge: device-host agent, gateways, serial/USB/BLE/file sources, kiosks | Biomedical engineering with Platform SRE | IM-E01-BL-003, IM-E01-BL-005, IM-E01-BL-006, IM-E01-BL-025, IM-E05-BL-007, IM-E06-BL-003, IM-E08-BL-008 |
| [M-04](#m-04) | Device lab certification with real units (driver families, analysers, DICOM, harness accuracy) | Device lab lead with vendors | IM-E01-BL-007, IM-E01-BL-009, IM-E01-BL-012, IM-E01-BL-013, IM-E01-BL-014, IM-E01-BL-016, IM-E01-BL-017, IM-E01-BL-018, IM-E01-BL-019, IM-E04-BL-009 |
| [M-05](#m-05) | Vendor cloud integrations (RPM, CGM, wearables, hospital-at-home platforms) | Partnerships with Integration developers | IM-E01-BL-008, IM-E01-BL-015 |
| [M-06](#m-06) | Standards conformance and host-partner integrations (IHE, SMART on FHIR, reference integrations) | Integration lead with host partners | IM-E01-BL-004, IM-E07-BL-001, IM-E07-BL-002, IM-E07-BL-007, IM-E03-BL-014 |
| [M-07](#m-07) | Native mobile SDKs, SMART app packaging and the patient app | Mobile team | IM-E05-BL-005, IM-E05-BL-009, IM-E07-BL-004 |
| [M-08](#m-08) | Clinical pilot: shadow run and sign-off, time-and-motion, usability engineering | Clinical lead / Template owner with the CSO | IM-E04-BL-005, IM-E05-BL-014, IM-E10-BL-004 |
| [M-09](#m-09) | Quality and regulatory files (ISO 14971, clinical safety, PMS, suppliers, CDS assessment) | Quality & Regulatory with the clinical safety officer | IM-E10-BL-003, IM-E10-BL-005, IM-E10-BL-006, IM-E10-BL-007, IM-E14-BL-010 |
| [M-10](#m-10) | Security assurance programme | CISO | IM-E06-BL-011, IM-E06-BL-005 |
| [M-11](#m-11) | Operations readiness: drills, soak, on-call, FinOps, tracing | Platform SRE | IM-E01-BL-027, IM-E01-BL-030, IM-E08-BL-005, IM-E08-BL-006, IM-E08-BL-007, IM-E08-BL-009 |
| [M-12](#m-12) | Licensed content and data-dependent intelligence | Clinical informatics / Laboratory / Data science | IM-E12-BL-006, IM-E12-BL-010, IM-E14-BL-009, IM-E14-BL-011, IM-E15-BL-004 |
| [M-13](#m-13) | Other clouds (GCP, private cloud) and their adapters | Platform architecture | IM-E06-BL-004, IM-E06-BL-010, IM-E08-BL-003, IM-E08-BL-004, IM-E08-BL-010 |
| [M-14](#m-14) | Commercial accounts and contracts | Commercial / Finance | IM-E09-BL-002, IM-E02-BL-006 |
| [M-15](#m-15) | AI explanation endpoint approval | CISO / DPO / Clinical safety officer | IM-E03-BL-006 |
| [M-16](#m-16) | Quarantine listener for identification of unconfigured devices (security review) | CISO with Platform architecture | IM-E12-BL-003 |

## M-01

### Azure tenancy, landing zone and regulatory approvals for health data in Qatar

**Owner:** CISO / DPO with the customer's IT and legal  
**Why manual:** Data residency, the legal basis for processing and the landing-zone governance are decisions for the organisation, not for software.

**Prerequisites**

- Azure subscription(s) for UAT and PROD under the customer's Entra tenant
- Named DPO and CISO
- Setup Guide §1–3 completed

**Steps**

1. Record the data-residency decision: all resources in Qatar Central (enforced by the Terraform validation and the Allowed-locations policy). Obtain written confirmation from the Ministry of Public Health / NCSA contact for hosting patient data in the Azure Qatar Central region (secondary sources disagree on the strictness – confirm the current position).
2. Complete the DPIA (appendix A template) for each deployment setting (acute, OH, RPM) and have the DPO sign it; attach it to the change record.
3. Agree the Microsoft Online Services DPA / Product Terms for the subscription; record it in the supplier register (M-09).
4. Decide cross-region DR: Qatar Central has no paired region – keep DR in-region (default) or obtain approval for a secondary region before enabling it.
5. Create the Entra groups (IntiMed-PlatformSRE, IntiMed-DeviceAdmins, IntiMed-SOC, IntiMed-Auditors) and the PIM eligible assignments used by the Setup Guide.
6. Enable Microsoft Defender plans at subscription scope (enable_defender_plans = true once per subscription).

**Tooling shipped with the product:** deploy/azure/terraform (residency validation + policy); Setup Guide; Appendix A DPIA template

**Evidence that closes the item**

- Signed DPIA
- Regulator / customer approval letter on file
- Policy compliance view shows 100 % in qatarcentral
- security-test-kit 16: residency checks PASS

| Backlog | Title | Status |
|---|---|---|
| IM-E06-BL-009 | Privacy controls (minimisation, consent, retention, pseudonymised telemetry, de-identified non-prod) and DPIA templates per deployment setting | PARTIAL |
| IM-E08-BL-002 | Terraform modules and Helm charts for Azure (AKS, PostgreSQL Flexible HA, Event Hubs/Strimzi, Blob, Key Vault, Entra, Front Door/WAF, Monitor/Sentinel) | DELIVERED |

## M-02

### Hospital and clinic connectivity: site-to-site VPN / ExpressRoute and segmentation validation

**Owner:** Customer network team with Platform SRE  
**Why manual:** Site networks, firewalls and IPsec peers are owned by each hospital.

**Prerequisites**

- Site public IP of the VPN device, device VLAN CIDRs
- Shared key exchanged out-of-band (never e-mail)

**Steps**

1. Add each site to vpn_sites in envs/prod.local.tfvars and the shared key to the pipeline secret VPN_SHARED_KEYS_JSON; apply Terraform (GCMAES256 / ECP384 IPsec policy).
2. Configure the site VPN device with the same policy; confirm the tunnel is Connected (az network vpn-connection show).
3. Allow only TCP 2575 and 8443 from the device VLANs to the IntiMed device IP (devices_lb_ip); block everything else (docs/security/Network-Reference-Designs.md).
4. Add the device VLAN CIDRs to device_source_cidrs (Terraform NSG) and networkPolicy.deviceSourceCidrs (Helm); re-deploy.
5. From a laptop on each device VLAN run: python3 security-test-kit/01_network_segmentation_check.py --host <devices DNS> --console-host <console DNS> --expect-console blocked
6. File the report (results/REPORT.md) with the site acceptance.

**Tooling shipped with the product:** security-test-kit/01_network_segmentation_check.py; Terraform vpn.tf/NSG; Helm NetworkPolicy

**Evidence that closes the item**

- Script 01 report per site with 0 failures
- VPN connection status Connected
- Firewall change records

| Backlog | Title | Status |
|---|---|---|
| IM-E06-BL-002 | Publish reference network designs for hospital, clinic, home and cloud with firewall templates and validation scripts | DELIVERED |

## M-03

### Edge: device-host agent, gateways, serial/USB/BLE/file sources, kiosks

**Owner:** Biomedical engineering with Platform SRE  
**Why manual:** Physical ports, radios, vendor PCs and kiosk hardware exist only at the sites.

**Prerequisites**

- Gateway hardware (arm64 or amd64) or a Windows device host per site
- Network path per M-02

**Steps**

1. Build the gateway image: docker buildx build --platform linux/arm64,linux/amd64 -f deploy/docker/Dockerfile.gateway -t <acr>/intimed/gateway:<ver> . and sign it with cosign (same key as the platform).
2. Harden the host OS: CIS benchmark level 1, full-disk encryption, EDR agent, automatic security updates, no inbound ports.
3. Register the gateway endpoint in the console (Security → Endpoints) and enrol it with the one-time token: sdk enrol --server … --token … --ca root-ca.pem --out /gateway/identity.
4. Map serial ports (--device /dev/ttyUSB0) or vendor export folders read-only into the container; run sdk gateway with the device's driver; verify readings in the console.
5. For BLE: use a gateway with a supported adapter, enforce LE Secure Connections pairing (RR-03), test with the vendor's device.
6. Power-loss test: unplug the WAN for ≥ 48 h of simulated traffic; confirm encrypted queue flush in order after reconnection.
7. Kiosk / batch screening: deploy the capture component in kiosk mode on the clinic tablet; pilot with consent screens.

**Tooling shipped with the product:** Dockerfile.gateway; sdk enrol / sdk gateway; enrolment API; capture web component

**Evidence that closes the item**

- Gateway image signature verified
- Endpoint ACTIVE with certificate in console
- Exchange test PASS through the gateway
- Store-and-forward test log

| Backlog | Title | Status |
|---|---|---|
| IM-E01-BL-003 | Serial/USB adapter for gateway and device host with port discovery and framing options | PARTIAL |
| IM-E01-BL-005 | Vendor database/file watcher adapter (SQL Server/SQLite/Access/PostgreSQL read-only; XML/GDT/CSV/PDF folders) | PARTIAL |
| IM-E01-BL-006 | BLE adapter (standard GATT health profiles and vendor services) for gateway, kiosk and mobile with LE Secure Connections | PARTIAL |
| IM-E01-BL-025 | Encrypted store-and-forward on gateway, device host and mobile with streaming buffer and reconciliation | PARTIAL |
| IM-E05-BL-007 | As a nurse I want batch screening mode with BLE devices and offline queueing; as a patient I want kiosk self-measurement with consent | PARTIAL |
| IM-E06-BL-003 | Build hardened gateway and device-host images with zero-touch enrolment, drift detection and patch campaigns | PARTIAL |
| IM-E08-BL-008 | Edge packaging (gateway arm64/amd64 images, Windows device host MSI, mobile SDK packaging) and cloud-independent fleet management | PARTIAL |

## M-04

### Device lab certification with real units (driver families, analysers, DICOM, harness accuracy)

**Owner:** Device lab lead with vendors  
**Why manual:** Golden sessions must be captured from real devices and firmware versions.

**Prerequisites**

- Device lab network (UAT)
- Vendor interface specifications
- Loan units / site access

**Steps**

1. Connect the real device (or vendor gateway) to UAT through a lab gateway (M-03).
2. Capture ≥ 20 real messages per scenario; use Intelligence studio → Device identification to confirm model/option and Auto-mapping for bindings.
3. Author or extend the driver package with the SDK (sdk validate / sdk test), sign it with the release key (offline) and publish it as CANDIDATE.
4. Run the exchange test (XT-01…XT-17) per option; for analysers bind the POCT1-A2 / ASTM E1381 codecs to the device-host serial/TCP port; for imaging deploy the DICOM router (Orthanc / dcm4che) in the lab and route DICOMweb JSON to IntiMed.
5. Certify with four-eyes; record firmware versions and limitations in the driver docs.
6. Submit vendor-authored drivers through Partners & registers → Submissions (badge on certification).
7. Harness accuracy study (IM-E04-BL-009): five families × ≥ 30 runs with seeded faults; compute false-positive / false-negative rates of each XT check.

**Tooling shipped with the product:** sdk validate/test/simulate/sign; Intelligence studio; Partner submissions + badges; AstmE1381 / Poct1aConversation codecs

**Evidence that closes the item**

- Signed driver packages with golden sessions
- Sealed evidence packs
- Accuracy report with FP/FN rates

| Backlog | Title | Status |
|---|---|---|
| IM-E01-BL-007 | POCT1-A2 adapter, LIS2/ASTM and IHE LAW analyser interfaces and data-manager HL7 bridges | PARTIAL |
| IM-E01-BL-009 | DICOM adapter (C-STORE SCP, MWL, Q/R, MPPS, DICOMweb, SR, waveform) with PACS/VNA routing | PARTIAL |
| IM-E01-BL-012 | Driver packages – acute-care monitoring & telemetry: Philips IntelliVue/PIIC iX, GE CARESCAPE Gateway, Mindray eGateway, Dräger Infinity Gateway, Nihon Kohden, Spacelabs, Masimo Root/Iris, Medtronic Capnostream/BIS, Edwards HemoSphere (PCD-01/02 periodic & spot, ACM observations, waveform snippets) | PARTIAL |
| IM-E01-BL-013 | Driver packages – infusion, ventilation & anaesthesia: Alaris Systems Manager, ICU Medical MedNet, Baxter Dose IQ, B. Braun Space Online, Fresenius Vigilant, PharmGuard; Hamilton, Dräger Evita/Babylog, Getinge Servo, PB 980, Philips V60/Trilogy; Aisys, Perseus/Atlan, Mindray A7/A9 (IPEC/PIV, MEDIBUS.X, SDC stub) | PARTIAL |
| IM-E01-BL-014 | Driver packages – outpatient & occupational-health instruments: spirometers, audiometers, vision testers, vitals monitors, ECG systems, breath-alcohol and drug testers, fit testers, ergonomics, exposure feeds (serial/USB, vendor DB/file, BLE, HL7) – parity with the OHCM MDI pack families | PARTIAL |
| IM-E01-BL-016 | Driver packages – laboratory & POCT: middleware (Instrument Manager, cobas infinity, AlinIQ, Atellica DM, REMISOL, WAM), blood gas data managers (AQURE, GEMweb Plus, POCcelerator), molecular connectivity (C360, FilmArray Link), POC analysers via POCT1-A2 (i-STAT, Afinion, StatStrip, DCA, HemoCue) | PARTIAL |
| IM-E01-BL-017 | Driver packages – cardiology diagnostics & implant remote monitoring: IHE IDCO from CareLink/Paceart, LATITUDE, Merlin.net, BIOTRONIK; Zio/MCOT portals; echo DICOM SR; MUSE/TraceMaster/IntelliSpace ECG; ABPM/Holter software | PARTIAL |
| IM-E01-BL-018 | Driver packages – imaging & PACS: DICOM MWL/MPPS/C-STORE/RDSR/SR, DICOMweb to Sectra/Agfa/Philips/Fujifilm/GE PACS-VNA, dose management, ophthalmic (FORUM/HEYEX), dental and endoscopy exports, AI-triage flags | PARTIAL |
| IM-E01-BL-019 | Driver packages – specialist units: dialysis (TDMS/Kinexus, Baxter, Nexadia, Sharesource/Nx2me), ECMO/MCS consoles, incubators/warmers, bilirubinometers, newborn hearing, fetal monitors, OR integration and energy devices, sterilisation tracking, perfusion, EEG/PSG exports, smart beds, nurse call | PARTIAL |
| IM-E04-BL-009 | Validate the harness in UAT with real devices from five families and document false-positive/negative rates | DEFERRED |

## M-05

### Vendor cloud integrations (RPM, CGM, wearables, hospital-at-home platforms)

**Owner:** Partnerships with Integration developers  
**Why manual:** Each vendor cloud needs a contract, a data-processing agreement and API credentials.

**Prerequisites**

- Signed vendor agreement and DPA
- Vendor sandbox account

**Steps**

1. Store the vendor API credentials in Key Vault through the console secret store (secret://<vendor>-api); never in configuration.
2. Add the vendor host to the egress allow-list (firewall_allowed_fqdns) and INTIMED_WEBHOOK_ALLOWED_HOSTS where webhooks are sent back.
3. Configure the webhook (HMAC secret) or poller in the vendor portal; verify signature validation and the replay window with the vendor's test events.
4. Build/extend the driver package from captured payloads; run the exchange test; certify.
5. Enable consent-bound enrolment for patients (association CONSENT).

**Tooling shipped with the product:** webhook receiver; JsonPath grammar; EgressClient allow-list; Key Vault secret store

**Evidence that closes the item**

- Exchange test PASS per vendor
- DPA in supplier register

| Backlog | Title | Status |
|---|---|---|
| IM-E01-BL-008 | Cloud API/webhook adapter framework (OAuth2, polling, webhooks, rate limits, consent scopes) incl. CRM remote-monitoring portals, RPM and wearable platforms | PARTIAL |
| IM-E01-BL-015 | Driver packages – RPM, wearables & home: RPM kits (BodyTrace, Smart Meter, Tenovi, Withings), CGM/insulin clouds (Dexcom, LibreView, CareLink, t:connect, Glooko), ResMed AirView, Care Orchestrator, hospital-at-home platforms, aggregators (Validic, Human API, HealthKit, Health Connect), telehealth kits | PARTIAL |

## M-06

### Standards conformance and host-partner integrations (IHE, SMART on FHIR, reference integrations)

**Owner:** Integration lead with host partners  
**Why manual:** Conformance tools, connectathons and partner sandboxes are external.

**Prerequisites**

- Gazelle / IHE test tool access
- Host EHR / LIS sandbox with SMART authorisation server

**Steps**

1. Run IHE PCD-01/02, ACM and IPEC test cases in Gazelle against UAT; fix deviations in drivers / listeners; register for the next IHE connectathon.
2. Register IntiMed as a SMART app in the host sandbox; implement the launch context mapping (user, patient) to IntiMed sessions; test with the capture web component.
3. Build the reference integrations (EHR flowsheet via FHIR + HL7, OHCM via the capture component, LIS via HL7/ASTM) and add them to the regression suite (Operations → Regression).
4. Publish the web component to the package registry agreed with partners.

**Tooling shipped with the product:** FHIR API + OpenAPI; capture web component; regression suites

**Evidence that closes the item**

- Gazelle reports
- Partner sign-off
- Regression trend green

| Backlog | Title | Status |
|---|---|---|
| IM-E01-BL-004 | HL7 v2 MLLP-TLS adapter: PCD-01 periodic and spot, PCD-02, ACM, IPEC, ORM/OML, ADT; IHE DEV conformance tests | PARTIAL |
| IM-E07-BL-001 | Expose the FHIR R4 API surface with Subscriptions, SMART launch, scopes and OpenAPI/conformance statements; contract tests | PARTIAL |
| IM-E07-BL-002 | HL7 v2 and IHE DEV endpoints with profiles and conformance tests; prepare connectathon participation | PARTIAL |
| IM-E07-BL-007 | Build and maintain reference integrations (EHR flowsheet FHIR + v2, OHCM, RPM app, LIS) with harness regression in CI | PARTIAL |
| IM-E03-BL-014 | As a host-product vendor I want to embed the console as web components / a SMART app and drive it by API | PARTIAL |

## M-07

### Native mobile SDKs, SMART app packaging and the patient app

**Owner:** Mobile team  
**Why manual:** App-store accounts, signing certificates and mobile CI are outside the server product.

**Prerequisites**

- Apple / Google developer accounts
- Mobile CI with signing

**Steps**

1. Generate Swift / Kotlin clients from /api/public/openapi.json; add CoreBluetooth / Android BLE device adapters reusing the GATT decoding rules.
2. Package the capture component as a SMART app; complete store submissions.
3. Patient app: localised (Arabic / English) plain-language summaries and consent management on the enrolment API; usability test (M-08).
4. Extend the TypeScript / Python SDKs into published packages and stand up the developer portal.

**Tooling shipped with the product:** OpenAPI 3.1; sdk/typescript; sdk/python

**Evidence that closes the item**

- Store approvals
- SDK packages published

| Backlog | Title | Status |
|---|---|---|
| IM-E05-BL-005 | SMART on FHIR app and iOS/Android SDKs exposing the capture component for host products | DEFERRED |
| IM-E05-BL-009 | As a patient I want plain-language result summaries in my language and control over my wearable data consent | DEFERRED |
| IM-E07-BL-004 | Publish SDKs (TypeScript, Java/Kotlin, .NET, Swift, Android, Python), web components, sample apps, sandbox tenants and the developer portal | PARTIAL |

## M-08

### Clinical pilot: shadow run and sign-off, time-and-motion, usability engineering

**Owner:** Clinical lead / Template owner with the CSO  
**Why manual:** Only clinicians at real sites can produce the evidence.

**Prerequisites**

- Published configurations with shadow mode enabled
- Ethics / quality approval for the study

**Steps**

1. Run each new configuration in shadow mode for the agreed period; nurses record manual reference values (Operations → Shadow sign-off).
2. Review the per-field agreement report; resolve discrepancies; a template owner other than the configuration author signs off (four-eyes enforced).
3. Time-and-motion: measure transcription time per reading before and after in one acute ward and one OH clinic (targets ≥ 1 min / ≥ 3 min saved).
4. IEC 62366-1: formative evaluations of capture component, console and wizard; summative evaluation with representative users; record use errors.
5. Feed findings into the risk file (M-09).

**Tooling shipped with the product:** ShadowReviewService report + sign-off; monthly executive report

**Evidence that closes the item**

- Signed shadow reports
- Study reports
- Usability engineering file

| Backlog | Title | Status |
|---|---|---|
| IM-E04-BL-005 | As a template owner I want a shadow-mode parallel run with discrepancy reports by field and device before I sign off go-live | DELIVERED |
| IM-E05-BL-014 | Time-and-motion and UX audit in two reference sites (acute ward and OH clinic) | DEFERRED |
| IM-E10-BL-004 | Usability engineering file with formative and summative evaluations of capture component, console and wizard | DEFERRED |

## M-09

### Quality and regulatory files (ISO 14971, clinical safety, PMS, suppliers, CDS assessment)

**Owner:** Quality & Regulatory with the clinical safety officer  
**Why manual:** Regulatory files are signed by accountable people in the eQMS.

**Prerequisites**

- eQMS
- Named CSO

**Steps**

1. Import docs/regulatory/Risk-Management-Summary.md into the ISO 14971 file; complete severity/probability, benefit-risk and residual-risk acceptance per hazard.
2. Clinical safety case per host (DCB0129/0160 style): hazard log, safety requirements, test evidence (sealed evidence packs, XT-17).
3. Regulatory assessment of reference-range flags (IM-E14) and derived values (eGFR etc.) under FDA CDS 2022 / EU MDR Rule 11 before enabling them in production.
4. Run python3 tools/compliance/supplier_register.py <sbom> supplier-register.xlsx per release and complete the assessments.
5. Set up post-market surveillance: complaint intake, trend review, vigilance timelines; link to the release notes' known anomalies.

**Tooling shipped with the product:** Risk management summary; supplier_register.py; classification record; evidence packs

**Evidence that closes the item**

- Approved risk file
- Signed safety case
- Supplier assessments
- PMS procedure

| Backlog | Title | Status |
|---|---|---|
| IM-E10-BL-003 | Build the ISO 14971 risk management file with hazard analysis and controls traced to features and tests | PARTIAL |
| IM-E10-BL-005 | Clinical safety case templates, hazard logs, labelling, IFU, release notes and known-anomaly process | PARTIAL |
| IM-E10-BL-006 | Post-market surveillance, complaint handling and vigilance interfaces with timelines and risk-file feedback | DEFERRED |
| IM-E10-BL-007 | Supplier management register for open source, cloud providers and device vendors | DELIVERED |
| IM-E14-BL-010 | Derived values: eGFR (CKD-EPI 2021), corrected calcium, anion gap, BMI | DELIVERED (2.0 enhancement) |

## M-10

### Security assurance programme

**Owner:** CISO  
**Why manual:** Independent testing and certification need external auditors.

**Prerequisites**

- UAT deployed per Setup Guide
- Scope statement

**Steps**

1. Commission an independent penetration test of UAT (web, API, device ports, AKS); provide the threat model and the security kit report.
2. Publish /.well-known/security.txt and a vulnerability-disclosure policy with response SLAs; monitor advisories for SBOM components (Dependabot / Defender).
3. Scope ISO 27001 / SOC 2 Type II; map controls SC-01…SC-20 and the Azure posture checks to the control framework.
4. Prepare IEC 80001-1 responsibility agreements with each hospital.

**Tooling shipped with the product:** security-test-kit (69 local + cloud checks); Threat model; release pipeline scans

**Evidence that closes the item**

- Pen-test report with remediation
- Certificates / attestation reports
- Signed 80001-1 agreements

| Backlog | Title | Status |
|---|---|---|
| IM-E06-BL-011 | Launch the assurance programme: SOC 2 Type II readiness, ISO 27001 scope, GA penetration test, IEC 80001-1 agreement template | DEFERRED |
| IM-E06-BL-005 | Establish the IEC 81001-5-1 life-cycle: threat models, SAST/DAST/SCA gates, signed releases with SBOM, advisory monitoring, disclosure process and SLAs | DELIVERED |

## M-11

### Operations readiness: drills, soak, on-call, FinOps, tracing

**Owner:** Platform SRE  
**Why manual:** These are executed in the operated environment by the operations team.

**Prerequisites**

- UAT and PROD deployed
- Self-hosted runner in the VNet for soak and PROD deploys

**Steps**

1. Soak: run the intimed-perf-soak workflow at 2× peak (e.g. 120 msg/s for 1 h) with an enrolled soak endpoint bound to a published soak configuration; archive soak-report.json.
2. DR drills: ENV=uat deploy/azure/scripts/dr-drill.sh D1 D2 D3 D4 quarterly (PROD semi-annually in a change window); compare RTO/RPO with targets.
3. Lock the immutability policies on the evidence/audit containers after acceptance (az storage container immutability-policy lock).
4. Staff the on-call rota from the SLO-and-Runbooks action group; test paging.
5. Agree patch windows (AKS maintenance window Friday 01:00 AST by default).
6. Optional tracing: add the Azure Monitor OpenTelemetry Java agent to the image and set APPLICATIONINSIGHTS_CONNECTION_STRING (no PHI in spans – keep sampling of attributes off).
7. Back up the platform master key to the offline break-glass store (two custodians).
8. Finance: review the cost model, apply reservations / savings plans for steady compute, set budgets per environment.

**Tooling shipped with the product:** sdk soak + workflow; dr-drill.sh; budget alerts; cost model workbook

**Evidence that closes the item**

- Soak report
- Drill reports
- Finance sign-off of the TCO

| Backlog | Title | Status |
|---|---|---|
| IM-E01-BL-027 | OpenTelemetry instrumentation, tenant-scoped dashboards, alert rules, ops API, streaming-gap detection | DELIVERED |
| IM-E01-BL-030 | Performance and soak testing at 2× projected acute-care peak; capacity model per environment | DELIVERED |
| IM-E08-BL-005 | HA/DR implementation and drills on each cloud; failover automation; backup verification | DELIVERED |
| IM-E08-BL-006 | CI/CD with signed images, SBOM, simulator/performance/security stages, GitOps promotion with approval gates; independent driver and configuration promotion | DELIVERED |
| IM-E08-BL-007 | SLO catalogue, observability stacks per cloud, runbooks for top-20 failure modes, on-call, patch windows, capacity model and FinOps dashboards | DELIVERED |
| IM-E08-BL-009 | Build the 5-year hosting TCO model per cloud and environment with cost per connected device; review with Finance | DELIVERED |

## M-12

### Licensed content and data-dependent intelligence

**Owner:** Clinical informatics / Laboratory / Data science  
**Why manual:** Licences, laboratory samples and historical data cannot be shipped in the product.

**Prerequisites**

- Licences (GMDN, LOINC/SNOMED CT), laboratory cooperation

**Steps**

1. Laboratory intervals (IM-E14-BL-011): collect ≥ 20 local reference results per partition; import the intervals (Intelligence studio → Interval governance), run the EP28 verification, approve with four-eyes, activate.
2. Terminology server: provision a FHIR terminology server licensed for LOINC / SNOMED CT; configure intimed.terminology.* with its HTTPS host on the allow-list.
3. GMDN / EMDN: with a licence, map the 41 device types and review the 29 family-default classifications with biomedical engineering.
4. Percent-predicted spirometry (GLI-2012) and growth z-scores: add height (and ethnicity where required) to demographics and load the published LMS tables; validate against reference calculators.
5. ML drift: after ≥ 6 months of de-identified fleet data, train seasonal baselines offline, sign the model like a driver package, run in shadow before alerting.

**Tooling shipped with the product:** IntervalGovernance (EP28); TerminologyService adapter; DriftMonitor

**Evidence that closes the item**

- EP28 verification reports
- Terminology server contract
- Model validation report

| Backlog | Title | Status |
|---|---|---|
| IM-E12-BL-006 | Review the 29 family-default classifications with biomedical engineering and the GMDN/EMDN code per model | PARTIAL (2.0 enhancement) |
| IM-E12-BL-010 | Full terminology services (LOINC/SNOMED CT lookups, ConceptMap) for uncoded host fields | PARTIAL (2.0 enhancement) |
| IM-E14-BL-009 | Percent-predicted spirometry (GLI-2012 LLN z-scores), paediatric BP percentiles, growth-chart z-scores | DEFERRED (2.0 enhancement) |
| IM-E14-BL-011 | Tenant interval governance: import laboratory intervals (CSV/FHIR ObservationDefinition), CLSI EP28 transference verification (20 samples), approval four-eyes, versioning | DELIVERED (2.0 enhancement) |
| IM-E15-BL-004 | ML drift (seasonal baselines, change-point detection) and predictive maintenance from device logs | DEFERRED (2.0 enhancement) |

## M-13

### Other clouds (GCP, private cloud) and their adapters

**Owner:** Platform architecture  
**Why manual:** Outside this Azure Qatar Central release; triggered by a customer contract.

**Prerequisites**

- Signed customer requirement

**Steps**

1. Reuse the Helm chart; replace the CSI provider (GCP Secret Manager / Vault) and storage class.
2. Implement the GCP Secret Manager adapter behind SecretStore (same contract and tests as AzureKeyVaultSecretStore).
3. Write Terraform for GKE / Cloud SQL HA / Cloud KMS or Ansible for OpenShift + Patroni; port posture script 16.
4. Run the full security kit and DR drills in the new environment.

**Tooling shipped with the product:** Helm chart; SecretStore contract; security kit

**Evidence that closes the item**

- Kit report in the new environment

| Backlog | Title | Status |
|---|---|---|
| IM-E06-BL-004 | Integrate Azure Key Vault, GCP Secret Manager/Cloud KMS and HashiCorp Vault behind one secrets interface; HSM for production; CI secret scanning | PARTIAL |
| IM-E06-BL-010 | Maintain the security test kit with cloud-specific checks (Azure, GCP, private cloud) and run it in all reference environments per release | PARTIAL |
| IM-E08-BL-003 | Terraform modules and Helm charts for GCP (GKE, Cloud SQL HA, Pub/Sub/Strimzi, Cloud Storage, KMS/Secret Manager, Identity Platform, Cloud Armor, Logging/Chronicle) | DEFERRED |
| IM-E08-BL-004 | Private-cloud blueprint (OpenShift/Rancher on VMware, Patroni PostgreSQL, Strimzi/NATS, MinIO, Vault, Keycloak, HAProxy/F5, Prometheus/Grafana/Loki) with Ansible/Terraform | DEFERRED |
| IM-E08-BL-010 | Reference deployments: IntiMed-hosted SaaS (Azure) and client-hosted single tenant (GCP and private cloud) with runbook parity | PARTIAL |

## M-14

### Commercial accounts and contracts

**Owner:** Commercial / Finance  
**Why manual:** Marketplace listings, billing providers and OEM / partner contracts are commercial acts.

**Prerequisites**

- Pricing model approved

**Steps**

1. Create the Azure Marketplace (or billing provider) account; map meters to the billing export (Operations → Billing).
2. Monthly: export metering, verify the HMAC signature, reconcile against the invoice lines (POST /api/billing/reconcile).
3. Sign partner (vendor) programme terms before enabling PARTNER_DEV accounts; OEM agreements before white-label branding.

**Tooling shipped with the product:** BillingService export + reconciliation; Partner submissions; Branding

**Evidence that closes the item**

- Reconciliation reports
- Signed agreements

| Backlog | Title | Status |
|---|---|---|
| IM-E09-BL-002 | Implement licensing models, entitlements and metering with billing/marketplace feeds and reconciliation | DELIVERED |
| IM-E02-BL-006 | As a vendor I want to submit my model, interface specification, MDS2/SBOM and driver for certification and track status | DELIVERED |

## M-15

### AI explanation endpoint approval

**Owner:** CISO / DPO / Clinical safety officer  
**Why manual:** Using a language model requires an approved endpoint, region and DPIA – the gateway is off by default.

**Prerequisites**

- Azure OpenAI (Foundry) deployment in Qatar Central if offered for the chosen model – otherwise a regulator-approved region

**Steps**

1. Confirm model availability in Qatar Central; deploy the model with private endpoint and content filtering.
2. Store the key in Key Vault (secret://azure-openai-key) or use managed identity; set intimed.llm.endpoint/deployment/allowed-hosts; enable globally and per tenant.
3. Validate redaction and citation behaviour with the CSO; keep the output label (UNVERIFIED, citations required).

**Tooling shipped with the product:** ExplanationService policy gateway

**Evidence that closes the item**

- DPIA addendum
- Approval record

| Backlog | Title | Status |
|---|---|---|
| IM-E03-BL-006 | As a device administrator I want cited suggestions from the vendor's interface specification I uploaded, marked unverified until the test passes | DELIVERED |

## M-16

### Quarantine listener for identification of unconfigured devices (security review)

**Owner:** CISO with Platform architecture  
**Why manual:** An extra listener widens the attack surface; it needs a design review before it is built.

**Prerequisites**

- Threat-model update

**Steps**

1. Review the design (mTLS-only, enrolled-but-unconfigured endpoints, sealed sample store, rate limits).
2. If approved, implement behind a feature flag and extend the security kit.

**Tooling shipped with the product:** DeviceIdentifier (manual paste works today)

**Evidence that closes the item**

- Approved design

| Backlog | Title | Status |
|---|---|---|
| IM-E12-BL-003 | Identification from live traffic captured on an unconfigured port (quarantine listener) and firmware-version detection | DEFERRED (2.0 enhancement) |

## Appendix A – DPIA template (Qatar, Law No. 13 of 2016 on Personal Data Privacy Protection)

| Section | Content to complete |
|---|---|
| 1. Processing description | Purpose (transfer of device measurements into the host record), data subjects (patients, employees), data categories (identifiers, vital signs and test results = special-nature/health data), recipients (host product), retention |
| 2. Legal basis and special-nature data | Basis for processing health data; approvals obtained (MoPH / NCSA) – record references |
| 3. Data location | Azure Qatar Central only (Terraform validation + Allowed-locations policy); backups in-region (no paired region) |
| 4. Data flows | Device → mTLS → IntiMed (AKS) → PostgreSQL (application-encrypted PHI) → host via FHIR/HL7/REST; evidence/audit archive (masked) |
| 5. Minimisation | Events carry ids only; audit details masked; dry runs persist nothing; demographics limited to birth date, sex, pregnancy |
| 6. Security measures | SC-01…SC-20, Key Vault HSM, workload identity, private endpoints, WAF, Sentinel, security kit results |
| 7. Data-subject rights | Access / correction via host; consent withdrawal stops RPM ingestion; offboarding crypto-shreds tenant data |
| 8. Risks and mitigations | Wrong-patient filing, misleading reference interval, breach, insider access – controls and residual risk |
| 9. Breach notification | Incident runbook RB-15; NCSA notification path and timelines |
| 10. Sign-off | DPO, CISO, clinical safety officer, date, review date |

