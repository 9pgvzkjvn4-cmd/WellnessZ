# IntiMed 2.0 demo guide

The demo pack runs the complete platform on a MacBook with simulated devices. **All people are synthetic**; device brands
and models come from the worldwide inventory of the requirement pack; driver packages, golden sessions and keys are
generated for the demo. Nothing leaves your machine.

## 1. Start

1. Unzip `IntiMed-2.0-Demo-Pack-macOS-Universal.zip` (Finder double-click). One pack for Apple silicon and Intel Macs.
2. **Right-click → Open** `Start IntiMed Demo.command` (first time only: macOS asks to confirm an app from the internet).
   The launcher picks the bundled Java runtime for your Mac (`uname -m`), stores a master key in your login Keychain (service `intimed-demo`),
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
| D03 | Exchange test & evidence | admin.device | Step 6 → *Run exchange test* → *Download evidence pack* → *Verify seal* → *Sandbox template instances* → FHIR / HL7V2 renderings | 16/16 applicable checks PASS (TLS 1.3 mTLS, identity, coverage, units, round trip, latency, negatives, security, quality gates, provenance, sealed evidence, **XT-17 clinical reference validation**); XT-12 N/A (one-way device) |
| D04 | Four-eyes certification & publish gate | admin.device → approver.device → sre | Step 7 *Submit*; try *Approve* as admin (refused: four-eyes); sign in as approver → *Approve*; step 8 *Publish to DEV*, *UAT*; *PROD* refused until `sre` *approves PROD promotion* | CERTIFIED with certification record; promotion history with diffs; PROD after SRE approval |
| D05 | Clinician capture in the host form | nurse.clinic | *Capture* → template TPL-18, station `OH-SPIRO-1`, subject `EMP-700102` → *Capture* on the device → result filed; open `/host-demo/` | Values filed into the form with provenance (driver, configuration hash, adapters FHIR/REST/PDF) |
| D06 | Quality gates | nurse.clinic / admin.device | *Fleet* → *Fail ✗* the spirometer calibration → capture again → device unavailable / held; *Release* as admin | Calibration lockout (XT-14 behaviour); release audited |
| D07 | Verify-before-post & fallback | nurse.clinic | Capture TPL-19 (vision): OCR device → *Photo / OCR* → submit → review → *Accept & file*; *Device unavailable? Fallback* | OCR proposals held until verified; fallback chain: other device → OCR → manual with attestation |
| D08 | Endpoint security & anomaly isolation | soc.analyst (+ admin.device) | *Security → Endpoints* → *Register endpoint* (one-time token + CSR command) → *Alerts*; trigger the drill (`POST /api/demo/anomaly-drill/<configId>` or Demo control) → AN-07 auto-isolation → *Release* refused until the bound configuration is re-tested → admin re-tests → release | Isolation within the 15-minute target, re-certification gate enforced, everything in the audit chain (*Audit trail* → INTACT) |
| D09 | Interoperability | dev.integrator | *Integration & APIs* → create client (`system/Observation.read`) → *FHIR explorer* → call; *Webhooks* → subscribe the reference host inbox → capture something → deliveries + host inbox *VALID* signatures | ES256 token with least-privilege scope; FHIR Bundle; signed webhooks verified by the host |
| D10 | All 22 template scenarios | admin.device | *Demo control* → *Run all 22 scenarios* (~1–2 min) | Every TPL-01…TPL-22 PUBLISHED_AND_LIVE with a passing exchange test (XT-17 included where the device carries clinical quantities) and a live reading filed with reference range and flag |
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

## 5. IntiMed 2.0 intelligence scenarios (*Intelligence studio*)

Personas: `dev.integrator` (no MFA) or `template.owner` for E01–E07; `admin.device` for E08. Reference ranges are
defaults for integration validation and demonstration – not for clinical use.

| ID | Scenario | Persona(s) | Steps | Expected result |
|---|---|---|---|---|
| E01 | Validation studio – mapped output per platform category | dev.integrator | *Intelligence studio → Validation studio* → driver *Philips IntelliVue*, template TPL-01, source *Simulated cohort* `Adult female, 64 y – tachycardia…` → *Validate & render* | Mapped TPL-01 with value, unit, code, reference range (partition `age ≥ 18 y`), flag (HR **H**, SpO2 **L**, RR **H**, temp **H** with grade *Fever*), source; validation layers PASS with INFO findings; FHIR shows `referenceRange` + `interpretation`; HL7 OBX-7 `60-100`, OBX-8 `H` |
| E02 | Same value, different age band | dev.integrator | Validation studio → cohort `Infant, 6 months – heart rate 140/min` → validate; then `School-age child, 8 y – heart rate 140/min` | Infant: HR 140 **N** (partition `age 28 d–<1 y`); child: HR 140 **H** (partition `age 6 y–<13 y`) – the age group changes the flag, the value does not |
| E03 | Pregnancy, altitude and sex partitions | dev.integrator | *Reference ranges* tab → Haemoglobin 10.8 g/dL: female 35 y → evaluate; then pregnancy T2; SpO2 92 % at altitude 0 vs 3000 m; QTc 455 ms male vs female | Hb **L** (non-pregnant) vs **N** (T2, WHO 2024); SpO2 **L** at sea level vs **N** at 3000 m; QTc **H** male vs **N** female; source and notes shown |
| E04 | Category rule packs | template.owner | Validation studio → driver *Roche cobas*, template TPL-06, category **LIS**; then driver *BodyTrace*, TPL-13, category **RPM_HOME**, untick *Consent on file*; then driver *Lifeloc FC20*, TPL-20, category **OCC_HEALTH**, paste/source cohort `Driver, 41 y – BrAC 0.052 (violation)` | LIS: every numeric result carries a range (LAB_RANGE_REQUIRED PASS); RPM without consent: CONSENT **FAIL**; OH: BrAC **HH** with the 49 CFR 382.201 consequence reported as information |
| E05 | Cross-field validation | dev.integrator | Validation studio → source *Pasted payload*: load a sample for *Welch Allyn Connex* (Device identification tab → *Load sample*), edit systolic below diastolic, paste into the studio, validate TPL-02 | CROSS_FIELD **FAIL**: “systolic ≤ diastolic”; nothing is filed (dry run) |
| E06 | Device identification & integration profile | dev.integrator | *Device identification* → load a sample from *GE Aisys* → *Identify*; then *Run benchmark* | IDENTIFIED with ~99 % confidence, evidence (grammar, keys recovered, sending application, serial prefix), device type VENTILATOR, integration profile (options, transports, mapping precision); benchmark 100 % top-1 over all golden sessions |
| E07 | Auto-mapping & cohorts | dev.integrator | *Auto-mapping* → *Welch Allyn* + TPL-02 → *Suggest* → *Benchmark*; *Simulator cohorts* → `Infant 6 months – HR 140`, tick NOISE + DROPOUT → *Simulate*; *Run all cohorts* | Suggestions with confidence and *why* (code, unit, label); 100 % recall (coded and label-only). Cohort payload in the device’s own protocol and units, artefacts listed, expected flags met; precision report 100 % |
| E08 | Data-quality drift | admin.device | *Demo control* → *Drift drill (hospital-at-home probe)* → lands on *Intelligence studio → Drift* | Six low-SpO2 / high-pulse readings filed (flagged), two AN-08 drift alerts (SpO2, pulse) with advice for the biomedical team – a data-quality signal, never a clinical alarm |
| E09 | Exchange test with XT-17 | admin.device | Provision TPL-02 (guided) → step 6 *Run exchange test* → open XT-17 evidence | XT-17 PASS: clinical fields annotated, cohort flags as expected for the device’s concepts, category validation free of unit/cross-field errors; sealed in the evidence pack |
| E10 | Regulatory position | regulatory | *Traceability & quality* → classification record | `referenceRangeFlagging` and `regulatoryImpact` statements: informational flags, switchable per configuration, local interval verification (CLSI EP28-A3c) and regulatory assessment required before production |

