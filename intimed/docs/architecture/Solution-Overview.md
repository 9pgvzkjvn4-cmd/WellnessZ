# Solution overview – local release

The product solution architecture (IntiMed Product Pack v1.0) defines host products → control plane → engine core →
edge tier, wrapped by security services, deployed in four environment settings. This release implements the **LOCAL
(MacBook)** setting as one process; the same components scale out to Dev/UAT/Prod in the cloud releases.

## Layers → code

| Architecture layer (§5.1) | Local implementation |
|---|---|
| Host products & people | Web console (13 role-aware pages), `<intimed-capture>` web component, reference host page `/host-demo/`, FHIR/HL7/webhook consumers |
| Control plane | `catalogue`, `console` (+ `intelligence`), `harness`, `templates`, `api`, `tenancy` |
| Engine core | `engine` (adapters P1–P11 via grammar parsers, normalisation & terminology, association, quality gates, routing & bindings, store with provenance, raw archive, driver SDK & registry, simulators) |
| Edge tier | `sdk gateway` / `sdk enrol` (CSR enrolment, encrypted store-and-forward), demo gateway identity |
| Security services | `security` (PKI & identity, secrets interface, envelope encryption, audit & SIEM sink, anomaly & isolation, privacy masking) + security test kit |
| Platform & environments | self-contained jar (encrypted H2) or compose (PostgreSQL + Vault), macOS bootstrap, CI |
| Quality & regulatory | traceability API, classification record, risk summary, feature flags for excluded functions |

## The canonical pipeline

`acquire → parse → normalise → associate → validate → route → persist → acknowledge` – identical for every device:

1. **acquire** – MLLP-TLS / mTLS ingress / HMAC webhook / FHIR; endpoint identity → tenant; quotas; raw payload archived (encrypted, SHA-256).
2. **parse** – the driver's grammar (HL7V2, ASTM, SERIAL, CSV, XML, POCT1A, GDT, BLE_GATT, JSON, SDC, DICOM_JSON, FHIR, OCR_TEXT, MANUAL); failures → DLQ with masked trace, AE/AR.
3. **normalise** – MDC/LOINC/local codes, UCUM units and conversions, stable observation id (idempotency), device serial binding.
4. **associate** – worklist, encounter–bed (ADT), session, badge, matching (0.97/0.92/0.85), consent; unmatched queue below 0.95.
5. **validate** – calibration/QC lockout, plausibility, artefacts, clock skew, guideline grading, verify-before-post, alarm flags.
6. **route** – many-to-many template bindings, conversion into the field unit (blocked if impossible), shadow mode.
7. **persist** – observation (encrypted with the tenant DEK), template instance, adapter renderings (FHIR, HL7 v2, REST, PDF).
8. **acknowledge** – HL7 ACK / JSON summary; events (no PHI) → signed webhooks.

## Configuration life-cycle

Select device → connectivity option → prerequisites → parameters → associate & bind → **exchange test** (XT-01…XT-16 over
the real listeners with the harness probe's own certificate, sandbox template instances, sealed evidence) → **certify**
(four-eyes, security co-approval for posture exceptions, certification record) → **publish** DEV → UAT → PROD (gate on the
configuration hash; PROD needs a Platform SRE) → monitor (fleet, calibration, diagnostics) → maintain (new version, clone,
decommission). Recommendations accompany every step; decisions are audited and certified fingerprints feed the
de-identified knowledge base.

## Environment settings

| Setting | This release |
|---|---|
| LOCAL – MacBook | Implemented: single process or compose; simulators; local PKI (step-ca equivalent) and vault interface (local or HashiCorp Vault dev) |
| DEV / UAT / PROD on Azure, GCP, private cloud | Modelled as promotion stages with diffs and gates inside the console; infrastructure deferred (see traceability) |
