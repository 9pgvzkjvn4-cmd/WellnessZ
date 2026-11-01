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
