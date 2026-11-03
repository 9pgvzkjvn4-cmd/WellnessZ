# Classification record (IM-E10-BL-001 – local release)

* **Intended use** – transfer, storage, format conversion and display of medical device data; configuration management and
  verification of device integrations for host healthcare products.
* **US** – Medical Device Data System functions (21 CFR 880.6310), enforcement discretion; no active patient monitoring,
  no alarm management, no clinical interpretation.
* **EU MDR / UK** – software that transfers/stores data without changing it for a medical purpose is outside Rule 11 when
  no interpretation is performed; reassess per market when any excluded function is enabled.
* **Excluded functions (feature flags off, served at `/api/quality/classification`)** – alarm management, clinical
  interpretation, closed-loop control, active patient-monitoring notification. Alarms are flagged `ALARM_STATE_FLAGGED`
  and never interpreted; device interpretations (e.g. ECG statements) are marked unverified and need physician sign-off.
* **IEC 62304** – software system class A with class B items (association and routing) because wrong-patient or wrong-value
  filing is mitigated by external measures (verification, thresholds, publish gate). Life-cycle evidence: traceability
  matrix, tests, release notes.
* **Maintenance** – reviewed at every release that adds a function or market.

## IntiMed 2.0 – reference-range flagging (impact assessment)

* **Function** – numeric results are annotated with a cited, age/sex/pregnancy/context-partitioned reference interval and an
  HL7 interpretation code (N, L, H, LL, HH) plus optional grade (e.g. ACC/AHA BP category, WHO hearing grade). Display only:
  no diagnosis, no alarm, no notification, no treatment recommendation.
* **Control** – switchable per configuration (`params.referenceRangeFlagging=false`); default intervals are labelled
  “for integration validation and demonstration”; production use requires locally verified intervals (CLSI EP28-A3c) and the
  tenant interval-governance workflow (IM-E14-BL-011).
* **Assessment** – flagging against standard reference ranges resembles laboratory report flags; under the FDA CDS guidance
  (2022) and EU MDR Rule 11 it may nevertheless move the product out of MDDS enforcement discretion in some markets. A documented
  regulatory assessment per market is required **before** enabling flags in production. The validation studio and cohort
  simulator are non-clinical engineering tools.
* **Risk controls** – see the risk summary rows *Misleading reference interval* and *Flag mistaken for an alarm*.

