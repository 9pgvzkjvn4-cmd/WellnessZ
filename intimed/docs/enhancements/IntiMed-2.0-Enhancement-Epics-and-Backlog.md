# IntiMed 2.0 – Enhancement Epics & Backlog Pack

Scope: more intelligence in the platform; precise mapping of device type, model, integration mechanism and simulators; a comprehensive validation framework that shows the mapped output template per healthcare-platform category with reference ranges by age group and other parameters.

## Delivery summary

| Status | Items | Story points |
|---|---|---|
| DELIVERED | 32 | 192 |
| PARTIAL | 2 | 18 |
| DEFERRED | 3 | 34 |

Measured precision (integration tests on the 26 shipped drivers, 22 templates, 33 cohorts):

| Metric | Result |
|---|---|
| Device identification top-1 (golden sessions) | 100 % |
| Auto-mapping recall vs certified bindings (coded templates) | 100 % |
| Auto-mapping recall with codes stripped (labels + units only) | 100 % |
| Cohort expected-flag precision through real drivers and pipeline | 100 % |

Caveat: the shipped drivers were authored against the same 22 templates, so these figures are an upper bound. Real host schemas and third-party payloads will score lower; low-confidence suggestions are never bound automatically.

## Research findings and how they shaped the design

### 1. Age-partitioned paediatric vital signs

**Finding.** PALS / AHA paediatric vital-sign tables (heart rate, respiratory rate, blood pressure by age band) as used in the University of Calgary PALS reference card, PedsCases and the San Francisco EMSA paediatric protocol. Normal heart rate of a 6-month infant (≈100–160/min) differs from an 8-year-old (≈70–120/min): a single adult interval mis-flags children.

**Design response.** Reference-interval KB partitions every vital sign by 8 age bands (neonate → older adult); the cohort pair infant-hr-140 (N) / child-hr-140 (H) proves the partition end-to-end.

Sources: https://cumming.ucalgary.ca/ (PALS vital signs reference card); https://www.pedscases.com/pediatric-vital-signs-reference-chart; SF EMSA Protocol 10.01 paediatric vital signs

### 2. Haemoglobin thresholds by age, sex and pregnancy trimester

**Finding.** WHO 2024 guideline on haemoglobin cut-offs to define anaemia (non-pregnant women 12.0 g/dL, men 13.0 g/dL; pregnancy T1 11.0, T2 10.5, T3 11.0 g/dL; children 6–59 months 10.5 g/dL).

**Design response.** Haemoglobin entry with pregnancy/trimester partitions; cohort pair adult-anaemia-female (L) vs pregnant-2nd-trimester (N) at the same 10.8 g/dL.

Sources: WHO 2024 – Guideline on haemoglobin cutoffs to define anaemia (PMC11300700); DHS Program blog on the 2024 WHO anaemia cut-offs; PAHO summary of WHO 2024 Hb thresholds

### 3. Altitude adjustment of SpO2 and Hb

**Finding.** Resting SpO2 falls with altitude (≈ 90–94 % at 3 000 m in healthy adults); WHO 2024 also adjusts Hb for elevation.

**Design response.** Context modifier siteAltitudeM ≥ 2 500 m lowers the SpO2 lower limit; cohorts altitude-sea-level (L) vs altitude-3000m (N) at 92 %.

Sources: WHO 2024 Hb guideline (altitude adjustment); High-altitude physiology reviews (SpO2 at altitude)

### 4. Hearing-loss grading

**Finding.** WHO World Report on Hearing 2021 grades by better-ear 4-frequency average: < 20 dB normal, 20–34 mild, 35–49 moderate, 50–64 moderately severe, 65–79 severe, 80–94 profound, ≥ 95 complete.

**Design response.** Hearing-threshold entry with WHO 2021 categories as grades; occupational category adds the 29 CFR 1910.95 standard-threshold-shift review note.

Sources: WHO World Report on Hearing 2021 grading (PMC11266198); 29 CFR 1910.95 Occupational noise exposure

### 5. Blood pressure categories

**Finding.** ACC/AHA 2017: normal < 120/< 80, elevated 120–129/< 80, stage 1 130–139 or 80–89, stage 2 ≥ 140 or ≥ 90, hypertensive crisis > 180 and/or > 120; paediatric BP uses age-based percentiles (simplified to PALS lower limits here).

**Design response.** Systolic/diastolic entries carry ACC/AHA grades; primary-care category reports the grade alongside the flag.

Sources: Whelton PK et al. 2017 ACC/AHA Hypertension Guideline; Mayo Clinic – blood pressure chart

### 6. QTc sex partition

**Finding.** QTc > 450 ms (men) and > 460 ms (women) is prolonged; > 500 ms high risk (AHA/ACCF/HRS recommendations for ECG standardisation).

**Design response.** QTc entry partitioned by sex; cohorts borderline-qtc-male (H) vs borderline-qtc-female (N) at 455 ms; cross-field rule checks QTc against a Bazett estimate.

Sources: Rautaharju PM et al. AHA/ACCF/HRS recommendations, Part IV (QT interval)

### 7. Regulatory occupational thresholds

**Finding.** US DOT 49 CFR 382.505 (BrAC 0.02–0.039 → 24 h removal) and 382.201 (≥ 0.04 → violation); OSHA 29 CFR 1910.134 App A (quantitative fit factor ≥ 100 half-mask, ≥ 500 full-facepiece).

**Design response.** BrAC and fit-factor entries with regulation/respirator-type context; occupational category reports regulatory consequences as information, never as a clinical alarm.

Sources: 49 CFR 382.505 / 382.201 (eCFR, FindLaw, J. J. Keller); 29 CFR 1910.134 Appendix A

### 8. Sleep apnoea severity

**Finding.** AASM: adult AHI < 5 normal, 5–14 mild, 15–29 moderate, ≥ 30 severe; children AHI ≥ 1–2 is abnormal.

**Design response.** AHI entry partitioned adult/paediatric; cohorts paediatric-osa (H) vs adult-ahi-3 (N) at AHI 3.

Sources: AASM Manual for the Scoring of Sleep and Associated Events v3; AASM paediatric OSA clinical practice guideline

### 9. How reference intervals are established and transported

**Finding.** CLSI EP28-A3c: intervals are established (≥ 120 reference individuals per partition) or transferred/verified (20 samples) by each laboratory; partitioning by age and sex when statistically justified.

**Design response.** Every KB interval is labelled a default for integration validation; the regulatory record requires local verification before clinical use; IM-E14-BL-011 (IntiMed 2.1) adds a tenant overlay with CSV / FHIR ObservationDefinition import, CLSI EP28 transference verification (≤ 2 of 20 outside), four-eyes approval and versioning.

Sources: CLSI EP28-A3c; Analyse-it EP28 partitioning guide; J Lab Physicians – reference interval transference

### 10. Representing ranges and flags in host outputs

**Finding.** FHIR R4 Observation.referenceRange (low/high/type/appliesTo/age/text) and Observation.interpretation (v3-ObservationInterpretation N, L, H, LL, HH); HL7 v2 OBX-7 (reference range) and OBX-8 (abnormal flags).

**Design response.** FHIR and HL7 adapters render the resolved interval and flag; validation studio shows exactly what the host receives.

Sources: http://hl7.org/fhir/R4/observation-definitions.html#Observation.referenceRange; http://terminology.hl7.org/CodeSystem/v3-ObservationInterpretation; HL7 v2.6 Ch. 7 OBX

### 11. Device type precision and IEEE 11073 personal-health specialisations

**Finding.** IEEE 11073-104xx specialisations (10404 pulse oximeter, 10407 BP monitor, 10408 thermometer, 10415 weighing scale, 10417 glucose meter) and IHE PCD profiles define the semantic content by device type; vendor gateways (e.g. Philips Capsule MDIP, GE CARESCAPE Gateway) determine the integration mechanism.

**Design response.** 41-type taxonomy with 11073 specialisation and data class; every catalogue model classified (196 rule-matched, 29 family default); integration profile per model and option.

Sources: IEEE 11073-104xx device specialisations; IHE PCD Technical Framework (PCD-01); Philips Capsule Medical Device Information Platform – capabilities

### 12. Payload fingerprinting and semantic auto-mapping

**Finding.** Industry integration engines rely on manual interface analysis; recognising a device from a sample message (segment structure, vendor application ids, code systems) and proposing code/unit-aware mappings shortens onboarding. Human-in-the-loop acceptance remains mandatory.

**Design response.** DeviceIdentifier (parse success + key coverage + structural similarity + identity hints) and AutoMapper (code, concept, UCUM, label similarity) with confidence and explanation; low confidence is never auto-bound.

Sources: HL7 v2 / IHE PCD message structure; UCUM specification (unitsofmeasure.org)

## Epics

| Epic | Name | Goal |
|---|---|---|
| IM-E11 | Intelligence studio & dry-run foundation | Give integration engineers, template owners and regulatory staff one place to see what a device sends, what IntiMed maps it to and how it is validated – without filing anything. |
| IM-E12 | Precise device identification & mapping | Identify device type, model, connectivity option and driver from a payload; publish a precise integration profile per model; propose code/unit-aware bindings for any template. |
| IM-E13 | Physiology-aware cohort simulators | Generate realistic device payloads for demographic cohorts (neonate to older adult, pregnancy, altitude, fasting, occupational regulation) with signal artefacts, and prove the expected flags end-to-end. |
| IM-E14 | Clinical reference ranges & category validation framework | Annotate every numeric result with a cited, partitioned reference interval and HL7 interpretation; validate mapped output per healthcare-platform category; render exactly what the host receives. |
| IM-E15 | Operational data-quality intelligence | Detect device data drift per configuration against the fleet and against the subject's reference interval, and route it to the biomedical team as a data-quality signal. |

## High-level requirements

| HLR | Epic | Requirement |
|---|---|---|
| IM-E11-HLR-01 | IM-E11 | The platform shall offer a side-effect-free dry run of parse → normalise → map → annotate for any signed driver and template, with PHI-masked identity. |
| IM-E11-HLR-02 | IM-E11 | Intelligence functions shall be role-restricted, audit-logged (payload hash, never payload) and limited in payload size. |
| IM-E12-HLR-01 | IM-E12 | The platform shall identify the driver of a captured payload with ≥ 95 % top-1 accuracy over the certified golden sessions and explain its evidence. |
| IM-E12-HLR-02 | IM-E12 | Every catalogue model shall carry a device type (taxonomy with IEEE 11073 specialisation and data class) and an integration profile per connectivity option. |
| IM-E12-HLR-03 | IM-E12 | The platform shall propose template bindings with confidence and explanation, reproducing ≥ 90 % of certified bindings; suggestions < 0.5 are never bound automatically. |
| IM-E13-HLR-01 | IM-E13 | Simulators shall generate payloads for demographic cohorts in the device's own units and protocol, optionally with noise, spike, drift, dropout, duplicate and clock-skew artefacts. |
| IM-E13-HLR-02 | IM-E13 | Scripted cohorts shall declare expected flags; the simulator shall report flag precision through the real driver and pipeline (target 100 %). |
| IM-E14-HLR-01 | IM-E14 | Numeric template fields shall be annotated with a reference interval resolved by age, sex, pregnancy/trimester and context, plus an HL7 interpretation code and optional grade, with source citation. |
| IM-E14-HLR-02 | IM-E14 | Mapped output shall be validated in layers (structure, terminology, units, plausibility, clinical range, cross-field, grades, regulatory, lab range, dose reference, consent) according to the healthcare-platform category. |
| IM-E14-HLR-03 | IM-E14 | FHIR output shall carry Observation.referenceRange and Observation.interpretation; HL7 v2 output shall carry OBX-7 and OBX-8. |
| IM-E14-HLR-04 | IM-E14 | The exchange harness shall verify clinical reference validation (XT-17) before publication. |
| IM-E14-HLR-05 | IM-E14 | Flags shall be informational, switchable per configuration, and default intervals shall be replaceable by locally verified intervals. |
| IM-E15-HLR-01 | IM-E15 | The platform shall raise a data-quality drift alert when a configuration deviates from its fleet (|z| ≥ 3) or shows a sustained abnormal-flag share (≥ 60 % over ≥ 5 readings). |

## Features

| Feature | Epic | Name |
|---|---|---|
| IM-E11-F01 | IM-E11 | Pipeline dry run & pure mapping function |
| IM-E11-F02 | IM-E11 | Intelligence studio UI (8 tabs) |
| IM-E11-F03 | IM-E11 | Intelligence API, RBAC, audit |
| IM-E11-F04 | IM-E11 | Subject demographics in the registry |
| IM-E12-F01 | IM-E12 | Payload fingerprinting (device identification) |
| IM-E12-F02 | IM-E12 | Device-type taxonomy & integration profiles |
| IM-E12-F03 | IM-E12 | Semantic auto-mapper |
| IM-E13-F01 | IM-E13 | Cohort library & physiology-aware value generation |
| IM-E13-F02 | IM-E13 | Artefact injection |
| IM-E13-F03 | IM-E13 | Expected-flag precision report |
| IM-E14-F01 | IM-E14 | Reference-interval knowledge base & interpretation |
| IM-E14-F02 | IM-E14 | Layered validation engine |
| IM-E14-F03 | IM-E14 | Healthcare-platform categories |
| IM-E14-F04 | IM-E14 | Range & flag rendering in FHIR / HL7 adapters |
| IM-E14-F05 | IM-E14 | XT-17 clinical reference validation |
| IM-E14-F06 | IM-E14 | Local interval governance |
| IM-E15-F01 | IM-E15 | Drift monitor (AN-08) |
| IM-E15-F02 | IM-E15 | Drift drill & alert workflow |
| IM-E15-F03 | IM-E15 | Predictive maintenance & ML drift |

## Sprint plan (capacity 60 SP / sprint)

| Sprint | Theme | Scope | Planned SP | Delivered SP |
|---|---|---|---|---|
| IM-S17 | Clinical reference foundation | KB, interpretation, categories, router annotation, adapters, demographics, regulatory record | 59 | 59 |
| IM-S18 | Validation studio & identification | Validation engine, studio UI, identification, profiles, auto-mapper, cohort simulator, XT-17 | 74 | 74 |
| IM-S19 | Operational intelligence & governance I | Drift monitor and drill, RR-17/RR-18 wizard integration, interval governance | 25 | 25 |
| IM-S20 | Clinical depth | Percent-predicted spirometry, derived values, taxonomy review, drift workflow | 28 | 10 |
| IM-S21 | Live context | Host demographics, quarantine identification, time-series physiology, delta checks | 32 | 24 |
| IM-S22 | Terminology & ML | Terminology server, ML drift / predictive maintenance | 26 | 0 |

## Backlog and traceability

| ID | Feature | Type | Title | SP | Sprint | Status | Code | Verification |
|---|---|---|---|---|---|---|---|---|
| IM-E11-BL-001 | IM-E11-F01 | Enabler | Extract a pure mapField/dryMap from the router (shared by live routing and dry runs) | 5 | IM-S17 | DELIVERED | intimed-platform/src/main/java/io/intimed/engine/pipeline/Router.java | intimed-platform/src/test/java/io/intimed/PlatformIntegrationTest.java |
| IM-E11-BL-002 | IM-E11-F01 | Story | Pipeline.dryRun: parse → normalise → map → annotate with no persistence, events or metering; PHI-masked identity | 5 | IM-S17 | DELIVERED | intimed-platform/src/main/java/io/intimed/engine/pipeline/Pipeline.java | intimed-platform/src/test/java/io/intimed/PlatformIntegrationTest.java#cohortSimulator… |
| IM-E11-BL-003 | IM-E11-F02 | Story | Intelligence studio page with validation, identification, auto-mapping, cohorts, ranges, categories, types and drift tabs (CSP-safe, no innerHTML) | 13 | IM-S18 | DELIVERED | intimed-platform/src/main/resources/static/assets/pages/intelligence.js | Playwright smoke (all 8 tabs) |
| IM-E11-BL-004 | IM-E11-F03 | Story | REST API /api/intelligence/* with role checks, 256 KiB payload limit and audit (payload hash only) | 5 | IM-S17 | DELIVERED | intimed-platform/src/main/java/io/intimed/web/IntelligenceController.java | security-test-kit (69/69) |
| IM-E11-BL-005 | IM-E11-F04 | Story | Subject registry: birth date, sex, pregnancy/trimester (validated, encrypted at rest); demo subjects with synthetic demographics | 3 | IM-S17 | DELIVERED | intimed-platform/src/main/java/io/intimed/web/ClinicalController.java; intimed-platform/src/main/java/io/intimed/demo/DemoService.java | intimed-platform/src/test/java/io/intimed/PlatformIntegrationTest.java#exchangeTestCarriesClinicalReference… |
| IM-E11-BL-006 | IM-E11-F04 | Story | Demographics from the host (FHIR Patient / HL7 PID-7, PID-8) instead of the local registry | 8 | IM-S21 | DELIVERED | intimed-platform/src/main/java/io/intimed/engine/association/HostDemographics.java; intimed-platform/src/main/java/io/intimed/engine/transport/DeviceListeners.java#adt (A01/A04/A08); intimed-platform/src/main/java/io/intimed/api/FhirController.java#patient (POST/PUT /fhir/r4/Patient, scope system/Patient.write) | intimed-platform/src/test/java/io/intimed/ClinicalIntelligenceDeferredIntegrationTest.java#hostDemographicsFromAdtPid7Pid8ChangeTheReferencePartition |
| IM-E12-BL-001 | IM-E12-F01 | Story | DeviceIdentifier: parse success, key coverage, structural similarity, sending-application / GATT / serial hints, calibrated confidence and margin | 8 | IM-S18 | DELIVERED | intimed-platform/src/main/java/io/intimed/intelligence/DeviceIdentifier.java | intimed-platform/src/test/java/io/intimed/PlatformIntegrationTest.java#deviceIdentification… |
| IM-E12-BL-002 | IM-E12-F01 | Story | Identification benchmark over every driver's golden session (top-1 accuracy) | 3 | IM-S18 | DELIVERED | intimed-platform/src/main/java/io/intimed/intelligence/DeviceIdentifier.java#benchmark | intimed-platform/src/test/java/io/intimed/PlatformIntegrationTest.java |
| IM-E12-BL-003 | IM-E12-F01 | Story | Identification from live traffic captured on an unconfigured port (quarantine listener) and firmware-version detection | 8 | IM-S21 | DEFERRED | — | — |
| IM-E12-BL-004 | IM-E12-F02 | Story | Device-type taxonomy (41 types, IEEE 11073 specialisation, data class) and classification of all 225 catalogue models with basis and confidence | 5 | IM-S17 | DELIVERED | intimed-platform/src/main/resources/seed/device-types.json; tools/content/intelligence_content.py | content generator output |
| IM-E12-BL-005 | IM-E12-F02 | Story | Integration profile per model: mechanism per option, transport security, vendor gateway, direction, capture mode, mapping precision | 5 | IM-S18 | DELIVERED | intimed-platform/src/main/java/io/intimed/intelligence/IntegrationProfiles.java | intimed-platform/src/test/java/io/intimed/PlatformIntegrationTest.java#deviceIdentification… |
| IM-E12-BL-006 | IM-E12-F02 | Story | Review the 29 family-default classifications with biomedical engineering and the GMDN/EMDN code per model | 5 | IM-S20 | PARTIAL | device-types.json | — |
| IM-E12-BL-007 | IM-E12-F03 | Story | AutoMapper: exact code, shared clinical concept, UCUM convertibility, label similarity with clinical synonyms; AUTO / REVIEW / UNBOUND | 8 | IM-S18 | DELIVERED | intimed-platform/src/main/java/io/intimed/intelligence/AutoMapper.java | intimed-platform/src/test/java/io/intimed/PlatformIntegrationTest.java#autoMapping… |
| IM-E12-BL-008 | IM-E12-F03 | Story | Auto-mapping benchmark (coded and label-only) against all certified bindings | 3 | IM-S18 | DELIVERED | intimed-platform/src/main/java/io/intimed/intelligence/AutoMapper.java#benchmark | intimed-platform/src/test/java/io/intimed/PlatformIntegrationTest.java |
| IM-E12-BL-009 | IM-E12-F03 | Story | Wizard step 5 pre-fills bindings from the auto-mapper for imported templates (RR-18) with one-click accept per field | 5 | IM-S19 | DELIVERED | intimed-platform/src/main/java/io/intimed/console/intelligence/RecommendationEngine.java#rr17/rr18; intimed-platform/src/main/java/io/intimed/console/ConfigService.java#apply/acceptAll; intimed-platform/src/main/java/io/intimed/harness/HarnessService.java#clinicalReference | intimed-platform/src/test/java/io/intimed/ClinicalIntelligenceDeferredIntegrationTest.java#rr17RecordsThePlatformCategory…, #rr18AutoMapSuggestions… |
| IM-E12-BL-010 | IM-E12-F03 | Story | Full terminology services (LOINC/SNOMED CT lookups, ConceptMap) for uncoded host fields | 13 | IM-S22 | PARTIAL | intimed-platform/src/main/java/io/intimed/intelligence/TerminologyService.java; intimed-platform/src/main/java/io/intimed/intelligence/FhirTerminologyClient.java; intimed-platform/src/main/java/io/intimed/intelligence/AutoMapper.java#score | intimed-platform/src/test/java/io/intimed/ClinicalIntelligenceDeferredIntegrationTest.java#terminologyClient…, #rr18… (local HTTPS stub) |
| IM-E13-BL-001 | IM-E13-F01 | Story | Cohort library: 33 cohorts (neonate → older adult, pregnancy trimesters, altitude, fasting, DOT, respirator type) with scripted values and expected flags | 5 | IM-S17 | DELIVERED | intimed-platform/src/main/resources/simulator/profiles.json | intimed-platform/src/test/java/io/intimed/ClinicalIntelligenceTests.java#everyScriptedCohort… |
| IM-E13-BL-002 | IM-E13-F01 | Story | Value generation in knowledge-base units, converted into the device's unit (learnt from the golden session) and protocol | 8 | IM-S18 | DELIVERED | intimed-platform/src/main/java/io/intimed/intelligence/CohortSimulator.java | intimed-platform/src/test/java/io/intimed/PlatformIntegrationTest.java#cohortSimulator… |
| IM-E13-BL-003 | IM-E13-F02 | Story | Artefacts: noise, spike, drift, dropout, duplicate, clock skew (deterministic by seed) | 5 | IM-S18 | DELIVERED | intimed-platform/src/main/java/io/intimed/intelligence/CohortSimulator.java | intimed-platform/src/test/java/io/intimed/PlatformIntegrationTest.java |
| IM-E13-BL-004 | IM-E13-F03 | Story | Expected-flag comparison and run-all precision report; greedy driver selection per cohort | 3 | IM-S18 | DELIVERED | intimed-platform/src/main/java/io/intimed/intelligence/CohortSimulator.java#runAll | intimed-platform/src/test/java/io/intimed/PlatformIntegrationTest.java |
| IM-E13-BL-005 | IM-E13-F01 | Story | Time-series physiology (circadian HR, deterioration trajectories, NEWS2 progression) for continuous devices | 8 | IM-S21 | DELIVERED | intimed-platform/src/main/java/io/intimed/intelligence/CohortSimulator.java#trajectory/interpolate; intimed-platform/src/main/java/io/intimed/clinical/News2.java; intimed-platform/src/main/java/io/intimed/web/IntelligenceController.java#trajectory; intimed-platform/src/main/resources/simulator/profiles.json (3 trajectories) | intimed-platform/src/test/java/io/intimed/ClinicalIntelligenceTests.java#trajectoryInterpolation…, intimed-platform/src/test/java/io/intimed/ClinicalIntelligenceDeferredIntegrationTest.java#sepsisTrajectory… |
| IM-E14-BL-001 | IM-E14-F01 | Story | Reference-interval knowledge base: 33 parameters, 8 age bands, sex/pregnancy partitions, critical limits, grades, cited sources | 8 | IM-S17 | DELIVERED | intimed-platform/src/main/resources/clinical/reference-ranges.json | intimed-platform/src/test/java/io/intimed/ClinicalIntelligenceTests.java |
| IM-E14-BL-002 | IM-E14-F01 | Story | ReferenceRanges: match by LOINC/MDC/local/field id, partition by age/sex/pregnancy, context modifiers (altitude, fasting, respirator type, regulation), interpretation N/L/H/LL/HH + grade | 8 | IM-S17 | DELIVERED | intimed-platform/src/main/java/io/intimed/clinical/ReferenceRanges.java; intimed-platform/src/main/java/io/intimed/clinical/Demographics.java | intimed-platform/src/test/java/io/intimed/ClinicalIntelligenceTests.java#ageSexPregnancy… |
| IM-E14-BL-003 | IM-E14-F01 | Story | Live routing annotates template fields with range, flag, partition and age band from subject demographics; per-configuration switch | 5 | IM-S17 | DELIVERED | intimed-platform/src/main/java/io/intimed/engine/pipeline/Router.java | intimed-platform/src/test/java/io/intimed/PlatformIntegrationTest.java#exchangeTestCarries… |
| IM-E14-BL-004 | IM-E14-F02 | Story | ValidationEngine: structure, terminology, units, plausibility, clinical range, cross-field (BP, MAP, FEV1/FVC, QTc/Bazett, UF, VTBI, neonatal gradient), grades, regulatory, lab range, dose (DRL), consent | 13 | IM-S18 | DELIVERED | intimed-platform/src/main/java/io/intimed/intelligence/ValidationEngine.java | intimed-platform/src/test/java/io/intimed/ClinicalIntelligenceTests.java#layeredValidation… |
| IM-E14-BL-005 | IM-E14-F03 | Story | Eight healthcare-platform categories (acute EHR, primary care, occupational health, LIS, imaging/cardiology, RPM, specialist, wellness) with rule packs and interpretation policy | 5 | IM-S17 | DELIVERED | intimed-platform/src/main/resources/clinical/platform-categories.json; intimed-platform/src/main/java/io/intimed/clinical/PlatformCategories.java | intimed-platform/src/test/java/io/intimed/ClinicalIntelligenceTests.java |
| IM-E14-BL-006 | IM-E14-F04 | Story | FHIR Observation.referenceRange + interpretation; HL7 OBX-7 / OBX-8 | 3 | IM-S17 | DELIVERED | intimed-platform/src/main/java/io/intimed/templates/TemplateAdapters.java | intimed-platform/src/test/java/io/intimed/ClinicalIntelligenceTests.java |
| IM-E14-BL-007 | IM-E14-F05 | Story | XT-17 clinical reference validation in the exchange harness (annotation coverage, cohort flags, category validation) – sealed in the evidence pack | 5 | IM-S18 | DELIVERED | intimed-platform/src/main/java/io/intimed/harness/HarnessService.java#clinicalReference | intimed-platform/src/test/java/io/intimed/PlatformIntegrationTest.java |
| IM-E14-BL-008 | IM-E14-F01 | Story | Regulatory record & impact assessment for informational flagging (FDA CDS 2022, EU MDR Rule 11) | 2 | IM-S17 | DELIVERED | intimed-platform/src/main/java/io/intimed/quality/QualityController.java#classification | — |
| IM-E14-BL-009 | IM-E14-F01 | Story | Percent-predicted spirometry (GLI-2012 LLN z-scores), paediatric BP percentiles, growth-chart z-scores | 13 | IM-S20 | DEFERRED | — | — |
| IM-E14-BL-010 | IM-E14-F01 | Story | Derived values: eGFR (CKD-EPI 2021), corrected calcium, anion gap, BMI | 5 | IM-S20 | DELIVERED | intimed-platform/src/main/java/io/intimed/clinical/DerivedValues.java; intimed-platform/src/main/java/io/intimed/engine/pipeline/Router.java#applyDerived; intimed-platform/src/main/resources/clinical/reference-ranges.json (egfr, KDIGO G1–G5) | intimed-platform/src/test/java/io/intimed/ClinicalIntelligenceTests.java#egfrCkdEpi2021KnownValuesAndKdigoGrades, #derivedValues…; intimed-platform/src/test/java/io/intimed/ClinicalIntelligenceDeferredIntegrationTest.java#deltaChecks…AndDerivedValuesAreLabelled |
| IM-E14-BL-011 | IM-E14-F06 | Story | Tenant interval governance: import laboratory intervals (CSV/FHIR ObservationDefinition), CLSI EP28 transference verification (20 samples), approval four-eyes, versioning | 13 | IM-S19 | DELIVERED | intimed-platform/src/main/java/io/intimed/clinical/IntervalGovernance.java; intimed-platform/src/main/java/io/intimed/clinical/ReferenceRanges.java#effective; intimed-platform/src/main/java/io/intimed/engine/pipeline/Router.java#mapField(tenant); intimed-platform/src/main/java/io/intimed/web/IntelligenceController.java#/intervals | intimed-platform/src/test/java/io/intimed/ClinicalIntelligenceTests.java#ep28…; intimed-platform/src/test/java/io/intimed/ClinicalIntelligenceDeferredIntegrationTest.java#intervalOverlayNeedsEp28AndFourEyes…, #rr17…Xt17PassesAgainstTheTenantOverlay |
| IM-E14-BL-012 | IM-E14-F02 | Story | Delta checks against the subject's previous result and critical-value call-back acknowledgement workflow in the host | 8 | IM-S21 | DELIVERED | intimed-platform/src/main/java/io/intimed/clinical/DeltaCheck.java; intimed-platform/src/main/java/io/intimed/engine/pipeline/Router.java#deltaCheck; intimed-platform/src/main/java/io/intimed/events/EventBus.java (observation.flagged) | intimed-platform/src/test/java/io/intimed/ClinicalIntelligenceTests.java#deltaThresholds…; intimed-platform/src/test/java/io/intimed/ClinicalIntelligenceDeferredIntegrationTest.java#deltaChecksEmitAnInformationalFlagEvent… |
| IM-E15-BL-001 | IM-E15-F01 | Story | DriftMonitor: per configuration/key mean vs fleet baseline (z-score) and abnormal-flag share (series-aware); drift-alert store | 5 | IM-S19 | DELIVERED | intimed-platform/src/main/java/io/intimed/intelligence/DriftMonitor.java | demo drift drill (2 alerts) |
| IM-E15-BL-002 | IM-E15-F02 | Story | Demo drift drill (hospital-at-home probe) and Drift tab | 2 | IM-S19 | DELIVERED | intimed-platform/src/main/java/io/intimed/demo/DemoService.java#driftDrill | manual / demo guide |
| IM-E15-BL-003 | IM-E15-F02 | Story | Drift alert workflow: acknowledge, link to calibration record, auto-resolve, SIEM forwarding | 5 | IM-S20 | DELIVERED | intimed-platform/src/main/java/io/intimed/intelligence/DriftMonitor.java#acknowledge/resolve/analyse; intimed-platform/src/main/java/io/intimed/web/IntelligenceController.java#/drift/alerts/{id}/ack|resolve; intimed-platform/src/main/resources/static/assets/pages/intelligence.js#drift | intimed-platform/src/test/java/io/intimed/ClinicalIntelligenceDeferredIntegrationTest.java#driftAlertsAreAcknowledgedLinkedToCalibrationAutoResolvedAndForwardedToSiem |
| IM-E15-BL-004 | IM-E15-F03 | Story | ML drift (seasonal baselines, change-point detection) and predictive maintenance from device logs | 13 | IM-S22 | DEFERRED | — | — |

## Partial and deferred items – reason and strategy

| ID | Status | Title | Reason and strategy |
|---|---|---|---|
| IM-E12-BL-003 | DEFERRED | Identification from live traffic captured on an unconfigured port (quarantine listener) and firmware-version detection | Reason: an unconfigured listener widens the attack surface; requires a security design review. Strategy: a quarantine mTLS listener bound to enrolled-but-unconfigured endpoints, payloads kept in a sealed sample store, identification offered in the wizard step 1. |
| IM-E12-BL-006 | PARTIAL | Review the 29 family-default classifications with biomedical engineering and the GMDN/EMDN code per model | Reason: GMDN is a licensed nomenclature. Strategy: keep rule-based types; map to GMDN/EMDN when the licence is available; biomedical review of the 29 family-default models in IM-S20. |
| IM-E12-BL-010 | PARTIAL | Full terminology services (LOINC/SNOMED CT lookups, ConceptMap) for uncoded host fields | Adapter delivered (IntiMed 2.1): FHIR $lookup / $validate-code / $translate client behind the AutoMapper, disabled by default, HTTPS + host allow-list, timeouts, cache; used for host codes from unknown systems. Remaining (manual): a real terminology server with LOINC / SNOMED CT licences and curated ConceptMaps for the customer's local code systems – not bundled, tested only against a local stub. |
| IM-E14-BL-009 | DEFERRED | Percent-predicted spirometry (GLI-2012 LLN z-scores), paediatric BP percentiles, growth-chart z-scores | Reason: needs height/ethnicity inputs and validated equation sets. Strategy: add height to demographics and an equation module (GLI-2012 LMS tables) behind ReferenceRanges; dynamic intervals per subject. |
| IM-E15-BL-004 | DEFERRED | ML drift (seasonal baselines, change-point detection) and predictive maintenance from device logs | Reason: needs months of fleet data and MLOps governance. Strategy: export de-identified feature store; offline model training; models signed and versioned like driver packages; shadow-mode evaluation before alerts. |

## Reference-interval knowledge base (34 parameters)

> reference intervals are DEFAULTS for integration validation and demonstration. Every deploying organisation must
confirm or replace them with its own (laboratory-verified, CLSI EP28-A3c) intervals and action thresholds before clinical use.
Values marked 'demo action threshold' are illustrative critical limits, not clinical recommendations.

| Parameter | Unit | Partitions | Source |
|---|---|---|---|
| Heart rate | /min | 7 | AHA Pediatric Advanced Life Support normal vital signs by age (PALS 2015/2020 tables as reproduced in published charts) – verify against the AHA PALS Provider Manual |
| Respiratory rate | /min | 7 | AHA Pediatric Advanced Life Support normal vital signs by age (PALS 2015/2020 tables as reproduced in published charts) – verify against the AHA PALS Provider Manual |
| Oxygen saturation (SpO2) | % | 1 | Common adult vital-sign references (illustrative defaults) |
| Systolic blood pressure | mm[Hg] | 7 | AHA Pediatric Advanced Life Support normal vital signs by age (PALS 2015/2020 tables as reproduced in published charts) – verify against the AHA PALS Provider Manual |
| Diastolic blood pressure | mm[Hg] | 7 | AHA Pediatric Advanced Life Support normal vital signs by age (PALS 2015/2020 tables as reproduced in published charts) – verify against the AHA PALS Provider Manual |
| Body temperature | Cel | 1 | Common adult vital-sign references (illustrative defaults) |
| Neonatal skin temperature | Cel | 1 | WHO thermal protection of the newborn – normal axillary/skin temperature 36.5–37.5 °C |
| End-tidal CO2 | mm[Hg] | 1 | Common adult vital-sign references (illustrative defaults) |
| Glucose | mmol/L | 1 | ADA Standards of Care – fasting plasma glucose 3.9–5.5 mmol/L (70–99 mg/dL), HbA1c <5.7 % normal, 5.7–6.4 % prediabetes, ≥6.5 % diabetes; level 2 hypoglycaemia <3.0 mmol/L |
| pH (arterial) | [pH] | 1 | Common adult laboratory reference intervals (illustrative; replace with the performing laboratory's verified intervals – CLSI EP28-A3c) |
| pCO2 (arterial) | mm[Hg] | 1 | Common adult laboratory reference intervals (illustrative; replace with the performing laboratory's verified intervals – CLSI EP28-A3c) |
| pO2 (arterial) | mm[Hg] | 2 | Common adult laboratory reference intervals (illustrative; replace with the performing laboratory's verified intervals – CLSI EP28-A3c) |
| Sodium | mmol/L | 1 | Common adult laboratory reference intervals (illustrative; replace with the performing laboratory's verified intervals – CLSI EP28-A3c) |
| Potassium | mmol/L | 1 | Common adult laboratory reference intervals (illustrative; replace with the performing laboratory's verified intervals – CLSI EP28-A3c) |
| Lactate | mmol/L | 1 | Common adult laboratory reference intervals (illustrative; replace with the performing laboratory's verified intervals – CLSI EP28-A3c) |
| Haemoglobin | g/dL | 9 | WHO 2024 Guideline on haemoglobin cutoffs to define anaemia (children 6–23 months 10.5 g/dL; pregnancy trimester-specific); upper limits are laboratory-specific demo values |
| Creatinine | umol/L | 2 | Common adult laboratory reference intervals (illustrative; replace with the performing laboratory's verified intervals – CLSI EP28-A3c) |
| eGFR (CKD-EPI 2021, race-free) | mL/min/{1.73_m2} | 1 | KDIGO 2024 Clinical Practice Guideline for the Evaluation and Management of CKD – GFR categories G1 ≥90, G2 60–89, G3a 45–59, G3b 30–44, G4 15–29, G5 <15 mL/min/1.73 m²; eGFR by the race-free CKD-EPI 2021 creatinine equation (Inker LA et al., NEJM 2021) |
| HbA1c | % | 1 | ADA Standards of Care – fasting plasma glucose 3.9–5.5 mmol/L (70–99 mg/dL), HbA1c <5.7 % normal, 5.7–6.4 % prediabetes, ≥6.5 % diabetes; level 2 hypoglycaemia <3.0 mmol/L |
| Total cholesterol | mmol/L | 1 | Common adult laboratory reference intervals (illustrative; replace with the performing laboratory's verified intervals – CLSI EP28-A3c) |
| ALT | U/L | 2 | Common adult laboratory reference intervals (illustrative; replace with the performing laboratory's verified intervals – CLSI EP28-A3c) |
| Leukocytes | 10*9/L | 1 | Common adult laboratory reference intervals (illustrative; replace with the performing laboratory's verified intervals – CLSI EP28-A3c) |
| PR interval | ms | 1 | Common adult ECG interval references (PR 120–200 ms, QRS <120 ms, QTc prolonged ≥450 ms men / ≥460 ms women, ≥500 ms high risk) |
| QRS duration | ms | 1 | Common adult ECG interval references (PR 120–200 ms, QRS <120 ms, QTc prolonged ≥450 ms men / ≥460 ms women, ≥500 ms high risk) |
| QTc interval | ms | 2 | Common adult ECG interval references (PR 120–200 ms, QRS <120 ms, QTc prolonged ≥450 ms men / ≥460 ms women, ≥500 ms high risk) |
| QRS axis | deg | 1 | Common adult ECG interval references (PR 120–200 ms, QRS <120 ms, QTc prolonged ≥450 ms men / ≥460 ms women, ≥500 ms high risk) |
| Fetal heart rate | /min | 1 | FIGO / NICE intrapartum CTG – baseline fetal heart rate 110–160 bpm; <100 or >180 abnormal |
| FEV1/FVC | % | 1 | GOLD – post-bronchodilator FEV1/FVC <0.70 (fixed ratio); GLI-2012 lower limit of normal preferred (age/sex/height-specific equations, not bundled) |
| Hearing threshold (pure tone) | dB | 1 | WHO World Report on Hearing 2021 – grades: <20 dB normal, 20–<35 mild, 35–<50 moderate, 50–<65 moderately severe, 65–<80 severe, 80–<95 profound, ≥95 complete (epidemiological, better ear, adults) |
| Breath alcohol concentration | g/210L | 1 | 49 CFR 382.505 / 382.201 (FMCSA) – breath alcohol 0.02–0.039: no safety-sensitive functions for ≥24 h; ≥0.04: violation |
| Apnoea–hypopnoea index | /h | 2 | AASM scoring – adult AHI <5 normal, 5–<15 mild, 15–<30 moderate, ≥30 severe; paediatric AHI >1 abnormal |
| Kt/V | 1 | 1 | KDOQI Haemodialysis Adequacy 2015 – minimum delivered spKt/V 1.2 (target 1.4) |
| Respirator fit factor | 1 | 1 | OSHA 29 CFR 1910.134 Appendix A – quantitative fit test pass: fit factor ≥100 (half-mask), ≥500 (full-facepiece) |
| Bispectral index | 1 | 1 | Manufacturer guidance – BIS 40–60 general-anaesthesia target (treatment range, not a reference interval) |

## Healthcare-platform categories

| Category | Templates | Rule pack | Interpretation |
|---|---|---|---|
| ACUTE_EHR – Hospital EHR – acute care (ICU, ED, OR, wards) | TPL-01, TPL-02, TPL-03, TPL-04, TPL-05, TPL-16 | STRUCTURE, TERMINOLOGY, UNITS, CLINICAL_RANGE, CROSS_FIELD, PLAUSIBILITY | flag |
| PRIMARY_CARE – Primary care / outpatient EHR | TPL-02, TPL-08, TPL-13 | STRUCTURE, TERMINOLOGY, UNITS, CLINICAL_RANGE, CROSS_FIELD, PLAUSIBILITY, CATEGORY_GRADES | flag+grade |
| OCC_HEALTH – Occupational health platform (e.g. OHCM) | TPL-17, TPL-18, TPL-19, TPL-20, TPL-21, TPL-22 | STRUCTURE, UNITS, CLINICAL_RANGE, CROSS_FIELD, CATEGORY_GRADES, REGULATORY | grade+regulatory |
| LIS – Laboratory information system / POCT | TPL-05, TPL-06 | STRUCTURE, TERMINOLOGY, UNITS, CLINICAL_RANGE, LAB_RANGE_REQUIRED, CROSS_FIELD | flag |
| IMAGING_CARDIO – Imaging & cardiology systems | TPL-07, TPL-08, TPL-09 | STRUCTURE, UNITS, CLINICAL_RANGE, DOSE_REFERENCE, CROSS_FIELD | flag |
| RPM_HOME – Remote patient monitoring / hospital at home | TPL-13, TPL-14, TPL-22 | STRUCTURE, UNITS, CLINICAL_RANGE, CROSS_FIELD, CONSENT | flag+care-plan |
| SPECIALIST – Specialist unit systems (dialysis, neonatal, maternity, sleep) | TPL-10, TPL-11, TPL-12, TPL-15 | STRUCTURE, UNITS, CLINICAL_RANGE, CROSS_FIELD, CATEGORY_GRADES | flag+grade |
| WELLNESS – Wellness / consumer health | TPL-22, TPL-13 | STRUCTURE, UNITS, CONSENT | informational |

## Simulator cohorts (36)

| Cohort | Demographics | Scripted values | Expected flags |
|---|---|---|---|
| Adult male, 40 y – healthy | {"ageYears": 40, "sex": "male"} | interval midpoint | — |
| Adult female, 34 y – healthy | {"ageYears": 34, "sex": "female"} | interval midpoint | — |
| Older adult female, 72 y – healthy | {"ageYears": 72, "sex": "female"} | interval midpoint | — |
| Adolescent male, 15 y – healthy | {"ageYears": 15, "sex": "male"} | interval midpoint | — |
| School-age child, 8 y – healthy | {"ageYears": 8, "sex": "female"} | interval midpoint | — |
| Preschool child, 4 y – healthy | {"ageYears": 4, "sex": "male"} | interval midpoint | — |
| Infant, 6 months – healthy | {"ageYears": 0.5, "sex": "female"} | interval midpoint | — |
| Neonate, 5 days – healthy | {"ageYears": 0.0136986301369863, "sex": "male"} | interval midpoint | — |
| Infant, 6 months – heart rate 140/min (normal for age) | {"ageYears": 0.5, "sex": "male"} | {"heart-rate": 140} | {"heart-rate": "N"} |
| School-age child, 8 y – heart rate 140/min (high for age) | {"ageYears": 8, "sex": "male"} | {"heart-rate": 140} | {"heart-rate": "H"} |
| Adult male, 58 y – hypertensive crisis | {"ageYears": 58, "sex": "male"} | {"systolic": 186, "diastolic": 112} | {"systolic": "HH", "diastolic": "H"} |
| Adult female, 64 y – tachycardia, tachypnoea, fever, low SpO2 | {"ageYears": 64, "sex": "female"} | {"heart-rate": 128, "resp-rate": 26, "body-temp": 38.9, "spo2": 92} | {"heart-rate": "H", "resp-rate": "H", "body-temp": "H", "spo2": "L"} |
| Older male, 70 y – COPD with hypoxaemia | {"ageYears": 70, "sex": "male"} | {"spo2": 87, "fev1-fvc": 58} | {"spo2": "LL", "fev1-fvc": "L"} |
| Adult, 30 y at sea level – SpO2 92 % | {"ageYears": 30, "sex": "male", "siteAltitudeM": 0} | {"spo2": 92} | {"spo2": "L"} |
| Adult, 30 y at 3,000 m – SpO2 92 % (normal for altitude) | {"ageYears": 30, "sex": "male", "siteAltitudeM": 3000} | {"spo2": 92} | {"spo2": "N"} |
| Adult female, 35 y, not pregnant – Hb 10.8 g/dL | {"ageYears": 35, "sex": "female"} | {"haemoglobin": 10.8} | {"haemoglobin": "L"} |
| Pregnant female, 29 y, 2nd trimester – Hb 10.8 g/dL (normal for trimester) | {"ageYears": 29, "sex": "female", "pregnant": true, "trimester": 2} | {"haemoglobin": 10.8} | {"haemoglobin": "N"} |
| Adult male, 55 y, fasting – hyperglycaemia, HbA1c 8.1 % | {"ageYears": 55, "sex": "male", "fasting": true} | {"glucose": 13.5, "hba1c": 8.1} | {"glucose": "H", "hba1c": "H"} |
| Adult female, 45 y – glucose 2.6 mmol/L | {"ageYears": 45, "sex": "female"} | {"glucose": 2.6} | {"glucose": "LL"} |
| Older male, 67 y – potassium 6.8 mmol/L | {"ageYears": 67, "sex": "male"} | {"potassium": 6.8} | {"potassium": "HH"} |
| Adult female, 50 y – QTc 505 ms | {"ageYears": 50, "sex": "female"} | {"qtc": 505} | {"qtc": "HH"} |
| Adult male, 50 y – QTc 455 ms (prolonged for men) | {"ageYears": 50, "sex": "male"} | {"qtc": 455} | {"qtc": "H"} |
| Adult female, 50 y – QTc 455 ms (normal for women) | {"ageYears": 50, "sex": "female"} | {"qtc": 455} | {"qtc": "N"} |
| Adult male, 45 y – noise notch at 4 kHz | {"ageYears": 45, "sex": "male"} | {"hearing-threshold@r4000": 45, "hearing-threshold@l4000": 40, "hearing-threshold@r6000": 35} | {"hearing-threshold@r4000": "H", "hearing-threshold@l4000": "H", "hearing-threshold@r6000": "H"} |
| Driver, 38 y – BrAC 0.031 (24 h removal) | {"ageYears": 38, "sex": "male", "regulation": "US-DOT-FMCSA"} | {"brac": 0.031} | {"brac": "H"} |
| Driver, 41 y – BrAC 0.052 (violation) | {"ageYears": 41, "sex": "male", "regulation": "US-DOT-FMCSA"} | {"brac": 0.052} | {"brac": "HH"} |
| Worker, 30 y – half-mask fit factor 64 (fail) | {"ageYears": 30, "sex": "female", "respiratorType": "HALF_MASK"} | {"fit-factor": 64} | {"fit-factor": "L"} |
| Adult male, 52 y – AHI 34 /h | {"ageYears": 52, "sex": "male"} | {"ahi": 34} | {"ahi": "H"} |
| Child, 7 y – AHI 3 /h (abnormal for a child) | {"ageYears": 7, "sex": "female"} | {"ahi": 3} | {"ahi": "H"} |
| Adult, 40 y – AHI 3 /h (normal for an adult) | {"ageYears": 40, "sex": "female"} | {"ahi": 3} | {"ahi": "N"} |
| Pregnant female, 28 y – fetal heart rate 172 | {"ageYears": 28, "sex": "female", "pregnant": true, "trimester": 3} | {"fetal-hr": 172} | {"fetal-hr": "H"} |
| Neonate, 2 days – skin temperature 35.8 °C | {"ageYears": 0.005479452054794521, "sex": "female"} | {"neonatal-skin-temp": 35.8} | {"neonatal-skin-temp": "L"} |
| Adult male, 61 y – Kt/V 1.05 | {"ageYears": 61, "sex": "male"} | {"ktv": 1.05} | {"ktv": "L"} |
| Sepsis deterioration over 6 h – adult female, 64 y | {"ageYears": 64, "sex": "female"} | trajectory: 4 keyframes over 360 min (noise 2.0 %) | — |
| Post-operative recovery over 4 h – adult male, 58 y | {"ageYears": 58, "sex": "male"} | trajectory: 3 keyframes over 240 min (noise 2.0 %) | — |
| Hypoglycaemia episode and treatment over 90 min – adult female, 45 y | {"ageYears": 45, "sex": "female"} | trajectory: 5 keyframes over 90 min (noise 1.5 %) | — |

## Device-type taxonomy (40 types)

| Code | Name | IEEE 11073 | Data class |
|---|---|---|---|
| AED_DEFIB | Defibrillator / AED (readiness and event data) | — | EVENT |
| SPOT_VITALS_MONITOR | Spot-check vital signs monitor | — | SPOT |
| MULTIPARAM_MONITOR | Multiparameter patient monitor / telemetry | — | CONTINUOUS |
| BP_MONITOR | Blood pressure monitor (NIBP) | IEEE 11073-10407 | SPOT |
| PULSE_OXIMETER | Pulse oximeter | IEEE 11073-10404 | SPOT |
| THERMOMETER | Thermometer | IEEE 11073-10408 | SPOT |
| WEIGHING_SCALE | Weighing scale / body composition | IEEE 11073-10415 | SPOT |
| GLUCOSE_METER | Glucose meter / CGM | IEEE 11073-10417 | SPOT |
| SPIROMETER | Spirometer / lung function | — | SPOT |
| AUDIOMETER | Audiometer / hearing screener | — | SPOT |
| ECG | Electrocardiograph / ECG management | — | SPOT |
| AMBULATORY_ECG | Ambulatory ECG / Holter / patch | — | CONTINUOUS |
| VISION_SCREENER | Vision / colour-vision screener | — | SPOT |
| BREATH_ALCOHOL | Evidential breath-alcohol tester | — | SPOT |
| DRUG_TEST | Drug test analyser / collection | — | SPOT |
| POC_ANALYSER | Point-of-care analyser | — | SPOT |
| BLOOD_GAS | Blood gas analyser | — | SPOT |
| LAB_ANALYSER | Laboratory analyser / middleware | — | SPOT |
| INFUSION_PUMP | Infusion pump / pump server | — | CONTINUOUS |
| VENTILATOR | Ventilator | — | CONTINUOUS |
| ANAESTHESIA_WORKSTATION | Anaesthesia workstation | — | CONTINUOUS |
| CAPNOGRAPH_BIS | Capnography / depth-of-anaesthesia / haemodynamics | — | CONTINUOUS |
| IMAGING_MODALITY | Imaging modality (CT, MR, DR, US) | — | STUDY |
| PACS_VNA | PACS / VNA / imaging archive | — | STUDY |
| ECHO | Echocardiography system | — | STUDY |
| IMPLANT_REMOTE | Implant remote-monitoring portal (IDCO) | — | SPOT |
| DIALYSIS | Dialysis system | — | SESSION |
| ECMO_MCS | ECMO / mechanical circulatory support | — | CONTINUOUS |
| NEONATAL_INCUBATOR | Incubator / warmer / neonatal device | — | CONTINUOUS |
| FETAL_MONITOR | Fetal monitor (CTG) | — | CONTINUOUS |
| RPM_KIT | RPM kit / cellular home device | — | SPOT |
| HOSPITAL_AT_HOME | Hospital-at-home monitoring platform | — | CONTINUOUS |
| WEARABLE | Consumer wearable / aggregator | — | DAILY |
| CPAP_SLEEP | CPAP / sleep therapy | — | DAILY |
| PSG_EEG | Polysomnography / EEG / neurophysiology | — | STUDY |
| PERFUSION_OR | Perfusion / OR integration / surgical device | — | SESSION |
| FIT_TESTER | Respirator fit tester | — | SPOT |
| EXPOSURE_MONITOR | Exposure monitor / dosimeter / environment | — | DAILY |
| DYNAMOMETER_ERGO | Dynamometer / ergonomics / functional capacity | — | SPOT |
| SMART_BED_ENV | Smart bed / location / environment | — | CONTINUOUS |

## Regulatory note

Reference-range flags are informational (analogous to laboratory report flags): no diagnosis, no alarm, no treatment recommendation. They can be switched off per configuration (`params.referenceRangeFlagging=false`). Enabling them in production requires a documented regulatory assessment (FDA CDS guidance 2022; EU MDR Rule 11) and locally verified intervals (CLSI EP28-A3c). The validation studio and cohort simulator are non-clinical engineering tools.

IntiMed 2.1 additions keep the same posture: derived values are OFF by default (`params.derivedValues`) and labelled "derived – verify"; delta checks (`params.deltaChecks`, default on only with flagging) emit an informational `observation.flagged` event – the host owns any call-back or acknowledgement; tenant interval overlays become effective only after EP28 verification and four-eyes approval; NEWS2 output of the trajectory simulator is informational engineering output.
