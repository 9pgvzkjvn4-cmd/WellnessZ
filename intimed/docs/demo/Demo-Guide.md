# IntiMed demo guide

The demo pack runs the complete platform on a MacBook with simulated devices. **All people are synthetic**; device brands
and models come from the worldwide inventory of the requirement pack; driver packages, golden sessions and keys are
generated for the demo. Nothing leaves your machine.

## 1. Start

1. Unzip `IntiMed-Demo-Pack-macOS-<AppleSilicon|Intel>.zip` (Finder double-click).
2. **Right-click → Open** `Start IntiMed Demo.command` (first time only: macOS asks to confirm an app from the internet).
   The launcher uses the bundled Java runtime, stores a master key in your login Keychain (service `intimed-demo`),
   starts the platform and opens https://localhost:9443. First start takes ~1 minute: it creates the PKI, the demo tenants
   and personas, and **auto-provisions 20 of the 22 template scenarios in the background** (TPL-02 and TPL-18 are left for
   you to provision live).
3. Your browser warns about the certificate the first time unless you let the launcher trust the local root CA (answer
   `y`). For localhost you can also click *Advanced → Proceed*.
4. Stop with `Stop IntiMed Demo.command` or Ctrl-C in the launcher window. Data stays in `~/IntiMed-Demo-Data` (delete the
   folder to reset).

## 2. Personas (password for all: `IntiMed-Demo-2026!`)

| Username | Role | MFA |
|---|---|---|
| `admin.device` | Device Administrator (Dana) | TOTP – the **demo authenticator** on the sign-in page shows the code (demo profile only) |
| `approver.device` | second Device Administrator (four-eyes approver) | TOTP |
| `template.owner` | Template Owner / clinical informatics | – |
| `nurse.clinic`, `nurse.icu` | Clinicians | – |
| `physician` | Physician | – |
| `soc.analyst` | Security Analyst / SOC | TOTP |
| `ciso` | CISO / DPO | TOTP |
| `sre` | Platform SRE | TOTP |
| `tenant.admin` | Tenant Administrator | TOTP |
| `dev.integrator` | Integration Developer | – |
| `auditor`, `regulatory` | read-only / quality | – |
| `oh.admin`, `oh.nurse` | OHCM tenant (reference client) | TOTP / – |

## 3. Guided scenarios

| ID | Scenario | Persona(s) | Steps | Expected result |
|---|---|---|---|---|
| D01 | Universal catalogue | admin.device | Sign in (TOTP via demo authenticator) → *Device catalogue* → search `EasyOne` → open IMD-001 | 225 devices, 24 families, FDA counts; connectivity matrix shows P2 **CERTIFIED_IN_LAB**; posture ACCEPTABLE |
| D02 | Provision a spirometer with configuration intelligence (TPL-18) | admin.device | *Provision this model* → step 1 accept driver → step 2 option P2 (RR-01 why) → step 3 endpoint *Demo clinic gateway*, station `OH-SPIRO-1` → step 4 parameters (try `pairing`/inline secret ideas in *Validate*) → step 5 association WORKLIST, bindings pre-filled (RR-10), grading ATS_ERS_2019 → *Save* each step | Every recommendation shows rule, source, confidence and *why*; Accept/Adjust/Override recorded; policy findings listed |
| D03 | Exchange test & evidence | admin.device | Step 6 → *Run exchange test* → *Download evidence pack* → *Verify seal* → *Sandbox template instances* → FHIR / HL7V2 renderings | 15/15 checks PASS (TLS 1.3 mTLS, identity, coverage, units, round trip, latency, negatives, security, quality gates, provenance, sealed evidence) |
| D04 | Four-eyes certification & publish gate | admin.device → approver.device → sre | Step 7 *Submit*; try *Approve* as admin (refused: four-eyes); sign in as approver → *Approve*; step 8 *Publish to DEV*, *UAT*; *PROD* refused until `sre` *approves PROD promotion* | CERTIFIED with certification record; promotion history with diffs; PROD after SRE approval |
| D05 | Clinician capture in the host form | nurse.clinic | *Capture* → template TPL-18, station `OH-SPIRO-1`, subject `EMP-700102` → *Capture* on the device → result filed; open `/host-demo/` | Values filed into the form with provenance (driver, configuration hash, adapters FHIR/REST/PDF) |
| D06 | Quality gates | nurse.clinic / admin.device | *Fleet* → *Fail ✗* the spirometer calibration → capture again → device unavailable / held; *Release* as admin | Calibration lockout (XT-14 behaviour); release audited |
| D07 | Verify-before-post & fallback | nurse.clinic | Capture TPL-19 (vision): OCR device → *Photo / OCR* → submit → review → *Accept & file*; *Device unavailable? Fallback* | OCR proposals held until verified; fallback chain: other device → OCR → manual with attestation |
| D08 | Endpoint security & anomaly isolation | soc.analyst (+ admin.device) | *Security → Endpoints* → *Register endpoint* (one-time token + CSR command) → *Alerts*; trigger the drill (`POST /api/demo/anomaly-drill/<configId>` or Demo control) → AN-07 auto-isolation → *Release* refused until the bound configuration is re-tested → admin re-tests → release | Isolation within the 15-minute target, re-certification gate enforced, everything in the audit chain (*Audit trail* → INTACT) |
| D09 | Interoperability | dev.integrator | *Integration & APIs* → create client (`system/Observation.read`) → *FHIR explorer* → call; *Webhooks* → subscribe the reference host inbox → capture something → deliveries + host inbox *VALID* signatures | ES256 token with least-privilege scope; FHIR Bundle; signed webhooks verified by the host |
| D10 | All 22 template scenarios | admin.device | *Demo control* → *Run all 22 scenarios* (~1–2 min) | Every TPL-01…TPL-22 PUBLISHED_AND_LIVE with a passing exchange test and a live reading filed |
| D11 | Traceability & regulatory | auditor / regulatory | *Traceability & quality* → filter DEFERRED → classification record | 117 backlog items with status, evidence or reason; excluded functions behind feature flags |
| D12 | Security test kit | terminal | `Run Security Test Kit.command` | 69 checks PASS; `security-test-kit/results/REPORT.md` |

## 4. Template scenarios (Demo control)

| Template | Device (driver) | Option | Association | What it shows |
|---|---|---|---|---|
| TPL-01 ICU flowsheet | Philips IntelliVue via PIIC iX (PCD-01) | P4 | encounter–bed (ADT) | continuous interval values, artefact flags, alarm flagged not interpreted |
| TPL-02 ED triage | Welch Allyn Connex (PCD-01); also BLE BP / oximeter | P4 / P3 | worklist | °F → Cel conversion (RR-06); many-to-many bindings |
| TPL-03 Anaesthesia | GE Aisys (PCD-01); GE CARESCAPE SDC stub | P4 / P11 | session | kPa → mm[Hg]; SDC-ready adapter |
| TPL-04 Infusion/MAR | BD Alaris (IPEC + PIV) | P4 | encounter–bed | XT-12 bidirectional auto-programming round trip |
| TPL-05 Blood gas | Radiometer ABL90 (POCT1-A2) | P5 | worklist | QC-lot gate holds the first result until QC is recorded |
| TPL-06 Lab report | Roche cobas (ASTM E1394) | P5 | worklist | LIS2 records → lab template, PDF adapter |
| TPL-07 Imaging & dose | CT RDSR (DICOMweb JSON) | P7 | worklist | SR numeric content (DLP, CTDIvol) |
| TPL-08 ECG report | GE MUSE XML export | P2 | worklist | device interpretation marked unverified |
| TPL-09 Device clinic | Medtronic CareLink (IHE IDCO) | P4 | matching | vendor id matching with confidence |
| TPL-10 Dialysis | Fresenius 5008 via TDMS | P4 | session | session record |
| TPL-11 Neonatal | Dräger Babyleo (serial frames) | P1 | cot binding | XOR checksum, serial framing (RR-05) |
| TPL-12 Partogram | Philips Avalon | P4 | bed | fetal / maternal values |
| TPL-13 RPM daily | BodyTrace cellular kit (webhook) | P6 | consent | lb → kg, consent-bound enrolment |
| TPL-14 Hospital at home | Masimo SafetyNet (cloud) | P6 | consent | continuous home vitals |
| TPL-15 Sleep report | Natus Embla CSV | P2 | worklist | CSV export |
| TPL-16 Perfusion | Terumo System 1 JSON | P2 | session | OR export |
| TPL-17 Audiogram | Benson CCA-200mini XML | P2 | worklist | posture exception → security co-approval; audiometric validity |
| TPL-18 Spirometry | ndd EasyOne via GDT | P2 | worklist | ATS/ERS grading, calibration lockout |
| TPL-19 Vision | Optec 6500 OCR; Snellen manual | P9 / P10 | worklist | verify-before-post |
| TPL-20 Drug & alcohol | Lifeloc FC20 serial | P1 | session (restricted) | no identifier matching for restricted categories |
| TPL-21 Fit test | TSI PortaCount via TSI Link | P6 | worklist | fit factor / pass-fail |
| TPL-22 Wellness | Wearable aggregator (FHIR) | P8 | consent | FHIR inbound, consent scopes |
