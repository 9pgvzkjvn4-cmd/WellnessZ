# WellnessZ Medical Device Integration Engine – Demo Pack (increment 6, E31–E34)

A standalone, offline demo of **configuring medical devices and testing device-data integration**: the device catalogue and
provisioning console (E32), the integration engine pipeline (E31) with nurse capture (E33), and endpoint and integration
security (E34). It is independent of the general OH demo in `demo/` (it keeps its own copy of the design system).

People are synthetic. Device brands and models are entries of the client inventory (133 models). Driver packages, option
packs and golden datasets are the engine's own resources. Demo keys and tokens are not secrets.

## Contents (download pack = `dist/`)

| File | What it is |
|---|---|
| `index.html` | Launcher: the three apps, scenarios, session information |
| `device-console.html` | Device administrator / approver: catalogue (133 models, search, detail with options), eight-step wizard with P1–P10 forms validated against the option-pack schemas and a prerequisites checklist, bindings with preview, simulator and live tests, four-eyes certification with a printable record, publish with a one-time enrolment token, calibration / QC lockout and release, fleet, P1–P10 reference |
| `integration-lab.html` | Integration tester / nurse / collector: simulators generating HL7 v2 ORU^R01, BLE GATT hex (BP, SpO2, thermometer, scale – IEEE-11073 SFLOAT / FLOAT), POCT1-A XML, CSV and GDT exports, FHIR R4, vendor webhook with HMAC, DICOM JSON; the pipeline visualiser (acquire → parse → normalise → associate → gates → route → persist) with the raw SHA-256; unmatched queue; dead letters with replay; golden conformance; capture preview |
| `security-console.html` | Security operations / auditor: endpoints and zero-touch enrolment, token rotation, zones and conduits (firewall template export), Ed25519 driver-signature check (WebCrypto), anomaly rules and the isolation drill, incidents, hash-chained security event log with verification |
| `device-demo-data.js` | **The demo data file** (`window.WZ_DEVICE_DEMO_SEED`) |
| `WellnessZ-Device-Integration-Demo-Guide.xlsx` | How to run, scenarios, demo users, one sheet per scenario (steps, expected result, Pass / Fail), connectivity options P1–P10, demo devices |
| `recording/` | Screenshots + `manifest.json` (narration per shot) for a narrated video |

## How to run

1. Keep all files in **one folder**; double-click `index.html` in **Chrome or Edge**. No server, installation or internet.
2. Open each app in its own tab. They share **one demo session** (localStorage `wellnessz-device-demo-session`), synchronised
   across tabs through the browser `storage` event.
3. The **demo toolbar** in every app: user switcher (only that app's users), scenario picker with next-step hints ("Switch to …",
   "Open …"), demo clock, Session, **Export** (`wellnessz-device-demo-session.json`), **Import**, **Reset**.
4. Follow the Excel guide. Reset before each scenario for a clean run.

**Deterministic and ID-agnostic.** The demo clock starts at the seeded time (30 Oct 2026 08:30 UTC) and advances one minute per
action, so identifiers, timestamps, tokens and hash chains are identical on every run. Scenarios and tests never rely on
generated ids; they find records by model, station or status.

## Scenarios

| ID | Scenario | Steps |
|---|---|---|
| S01 | Provision a BLE blood-pressure monitor (P3) end to end – wizard, simulator test, four-eyes + security co-approval, publish with one-time token, tablet enrolment, GATT measurement accepted by the nurse | 16 |
| S02 | Provision an HL7 vitals monitor (P4) – plaintext refused, simulator and live tests, ORU^R01 with ACK, plausibility block | 13 |
| S03 | POCT analyser via data manager (P5) – QC lot gate blocks a new reagent lot until QC is recorded | 5 |
| S04 | Spirometer via vendor export (P2) – calibration lockout, passing check, release, grade A with GLI predicted values | 6 |
| S05 | Unmatched result (vendor-typed id, 0.92 < 0.95) resolved by the nurse | 3 |
| S06 | Malformed message → AE + dead letter → discard; transient failure replayed without duplicates | 4 |
| S07 | Breath alcohol (restricted) via serial (P1) – no association without a session; collector-only verification | 6 |
| S08 | Four-eyes refusal (editor of step 5 cannot approve) and certification record | 4 |
| S09 | Zero-touch enrolment of a new gateway; token reuse refused; token rotation; chain verified | 5 |
| S10 | Anomaly isolation drill (≤ 15 min, time-compressed), release refused until re-certification, release, replay | 14 |

## What is computed (not canned)

* HL7 v2 segment / field / component parsing with driver selectors (`OBX[OBX-3.1=150021]/OBX-5`) and MSA AA / AE acknowledgements.
* BLE GATT structures driven by the package (`SFLOAT:unitFlagBit=0:altUnit=kPa`, `DATETIME:flagBit=1`, `UINT16:scale=0.005`) with IEEE-11073 SFLOAT / FLOAT decoding and an annotated byte view.
* A strict small XML parser with an XPath subset (POCT1-A, MUSE), RFC 4180 CSV, GDT with line-length checks, JSON pointer, a FHIRPath subset with profile checks, DICOM PS3.18 JSON, serial regex frames.
* MDC → LOINC / UCUM normalisation and unit conversion (kPa → mm[Hg], °F → Cel, lb → kg, m / in → cm), derived BMI, GLI-2012 predicted values (approximation – spline terms omitted, pending clinical verification), clock-skew correction.
* Association (worklist → session → badge → vendor id exact 0.97 / normalised 0.92 / fuzzy 0.85; auto-file 0.95, suggest 0.60; restricted categories worklist/session only) and the quality-gate catalogue.
* SHA-256, SHA-1 / UUIDv5 observation ids, HMAC-SHA256 webhooks with a 300 s replay window, hash-chained logs, Ed25519 signatures verified with WebCrypto (labelled **SIMULATED – digest check** where the browser lacks Ed25519).
* JSON-Schema 2020-12 validation of the P1–P10 option packs plus the practice rules of `OptionRules.java`, and the secret scanner.

All 18 driver packages' 56 test vectors and all 93 golden datasets pass through these parsers (`test/codecs.test.js`).

**Limitations (demo, not production):** no real devices, network, mTLS or PKI; the enrolment proof of possession is a SHA-256
stand-in for a signature with the certificate key; the GLI-2012 values are an approximation; data stays in the browser.
Data note: the inventory lists MD-045 (GE MAC) with options P1 / P4 while the driver `ecg.ge.muse-xml` declares P2; the seeded
configuration CFG-000010 follows the driver package – the inventory row should be confirmed.

## For developers

```
demo-devices/
  data/device-demo-data.js   THE demo data file (generated by tools/gen-data.js from the repository resources)
  shared/codecs.js           parsers, decoders, normalisation, association, gates, pipeline, simulators (window.DXC)
  shared/engine.js           demo backend: session, rules, actions, toolbar, scenarios (window.DX)
  shared/appkit.js           shell, JSON-Schema forms, pipeline visualiser (window.DXUI)
  shared/ui.css, ui.js       design system copied from demo/shared (build.py notes drift)
  shared/devices.css         device-specific styles; shared/scenarios.js is generated
  src/*.html                 app sources; scenarios.json; build.py → dist/
  tools/gen-data.js          regenerate the data file;  tools/build_guide.py → the Excel guide
  test/                      codecs.test.js, engine.test.js, players.js, ui.test.js, record-demo.js
```

```bash
node demo-devices/tools/gen-data.js            # only when the source resources change
python3 demo-devices/build.py
node demo-devices/test/codecs.test.js && node demo-devices/test/engine.test.js
node demo-devices/test/ui.test.js              # Playwright, all scenarios through the UI (or: ui.test.js S03 S07)
python3 demo-devices/tools/build_guide.py && node demo-devices/test/record-demo.js
```
