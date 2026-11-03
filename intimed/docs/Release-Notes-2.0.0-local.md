# IntiMed 2.0.0-local – release notes

IntiMed 2.0 adds clinical and integration intelligence on top of the 1.0 platform: precise device identification and
mapping, physiology-aware simulators and a category-based validation framework that shows the mapped output template
with reference ranges for the person's age group, sex and context.

## New

| Area | What you get | Where |
|---|---|---|
| Reference ranges | 33 parameters (vital signs, blood gas/electrolytes, haematology, chemistry, ECG intervals, fetal HR, spirometry ratio, audiometry, BrAC, AHI, Kt/V, fit factor, BIS) partitioned by 8 age bands, sex, pregnancy trimester; modifiers for site altitude, fasting, respirator type and regulation; critical limits and grades (ACC/AHA BP, WHO hearing, AASM AHI, fever); every interval cited | `clinical/reference-ranges.json`, *Intelligence studio › Reference ranges* |
| Live interpretation | Filed template fields carry `referenceRange` (interval, partition, age band, source, notes) and `interpretation` (HL7 N/L/H/LL/HH) from the subject's birth date, sex and pregnancy; switch per configuration | Router, subject registry |
| Host outputs | FHIR `Observation.referenceRange` + `interpretation`; HL7 v2 OBX-7 range + OBX-8 flag | Template adapters |
| Validation framework | 8 healthcare-platform categories (acute EHR, primary care, occupational health, LIS, imaging/cardiology, RPM, specialist, wellness) with layered rule packs: structure, terminology, units, plausibility, clinical range, cross-field (BP/MAP, FEV1/FVC, QTc vs Bazett, UF, VTBI, neonatal gradient), grades, regulatory (DOT, OSHA), lab range required, dose vs DRL, consent | *Validation studio* |
| XT-17 | Exchange-harness check *Clinical reference validation* (annotation coverage, cohort flags, category validation) sealed in the evidence pack | Harness |
| Device identification | Paste any payload → driver, model, option and device type with confidence and evidence; benchmark over all golden sessions | *Device identification* |
| Device taxonomy & integration profiles | 41 device types (IEEE 11073-104xx specialisation, data class); all 225 catalogue models classified; integration profile per model/option (protocol, transport, security, vendor gateway, mapping precision) | *Device types & profiles* |
| Auto-mapping | Binding suggestions for any template from codes, clinical concept, UCUM compatibility and label similarity, with confidence and explanation | *Auto-mapping* |
| Cohort simulator | 33 cohorts (neonate → older adult, pregnancy, altitude, fasting, DOT, respirator) rendered in each device's own protocol and units; artefacts (noise, spike, drift, dropout, duplicate, clock skew); expected-flag precision report | *Simulator cohorts* |
| Drift monitor | AN-08 data-quality drift per configuration vs fleet and abnormal-flag share; demo drift drill | *Drift*, *Demo control* |

## Measured (integration tests, shipped content)

| Metric | Result |
|---|---|
| Device identification top-1 over 26 golden sessions | 100 % |
| Auto-mapping recall vs certified bindings (coded / labels only) | 100 % / 100 % |
| Cohort expected-flag precision through real drivers and pipeline | 100 % |
| Tests | 92 (36 unit, 37 clinical intelligence, 15 integration, 4 architecture) – all pass |
| Security test kit | 69/69 |
| Demo auto-provisioning | 20/20 scenarios published and live (TPL-02 and TPL-18 are left for the guided walkthrough), XT-17 included |

The drivers were authored against the same templates, so these figures are an upper bound for third-party payloads and
host schemas.

## Important

Reference intervals are defaults for integration validation and demonstration. Replace them with locally verified
intervals (CLSI EP28-A3c) and complete a regulatory assessment before enabling flags in production. Flags are informational:
IntiMed raises no alarms.

## Not yet (see the enhancement backlog)

Tenant interval governance (import + EP28 verification), percent-predicted spirometry (GLI-2012) and growth z-scores,
derived values (eGFR), host demographics from PID-7/8 or FHIR Patient, quarantine-listener identification, terminology
server, ML drift. Each has a reason and strategy in `docs/enhancements/`.

## Upgrade

Version 2.0.0-local is a drop-in replacement for 1.0.0-local data: no schema change. Existing subjects without a birth date
are interpreted with adult intervals and the note “age unknown – adult interval assumed”.
