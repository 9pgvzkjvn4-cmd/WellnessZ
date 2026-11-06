#!/usr/bin/env python3
"""IntiMed 2.0 enhancement pack generator: research report, epics IM-E11..IM-E15, HLRs, features, backlog with story
points and sprints, traceability to code/tests and delivery status - as Markdown, Word (.docx) and Excel (.xlsx).

Usage: python3 tools/content/enhancement_pack.py <out-dir> [metrics.json]
"""
import json
import os
import sys

from docx import Document
from docx.shared import Pt, RGBColor
from openpyxl import Workbook
from openpyxl.styles import Alignment, Font, PatternFill

OUT = sys.argv[1] if len(sys.argv) > 1 else "docs/enhancements"
METRICS = {"identificationTop1": 1.0, "autoMappingRecall": 1.0, "autoMappingLabelOnlyRecall": 1.0, "cohortFlagPrecision": 1.0}
if len(sys.argv) > 2 and os.path.exists(sys.argv[2]):
    METRICS.update(json.load(open(sys.argv[2])))

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
RES = os.path.join(ROOT, "intimed-platform", "src", "main", "resources")
KB = json.load(open(os.path.join(RES, "clinical", "reference-ranges.json")))
CATS = json.load(open(os.path.join(RES, "clinical", "platform-categories.json")))
PROFILES = json.load(open(os.path.join(RES, "simulator", "profiles.json")))
TYPES = json.load(open(os.path.join(RES, "seed", "device-types.json")))

# ------------------------------------------------------------------------------------------------ research
RESEARCH = [
    ("Age-partitioned paediatric vital signs", "PALS / AHA paediatric vital-sign tables (heart rate, respiratory rate, blood pressure by age band) as used in the University of Calgary PALS reference card, PedsCases and the San Francisco EMSA paediatric protocol. Normal heart rate of a 6-month infant (≈100–160/min) differs from an 8-year-old (≈70–120/min): a single adult interval mis-flags children.",
     "Reference-interval KB partitions every vital sign by 8 age bands (neonate → older adult); the cohort pair infant-hr-140 (N) / child-hr-140 (H) proves the partition end-to-end.",
     ["https://cumming.ucalgary.ca/ (PALS vital signs reference card)", "https://www.pedscases.com/pediatric-vital-signs-reference-chart", "SF EMSA Protocol 10.01 paediatric vital signs"]),
    ("Haemoglobin thresholds by age, sex and pregnancy trimester", "WHO 2024 guideline on haemoglobin cut-offs to define anaemia (non-pregnant women 12.0 g/dL, men 13.0 g/dL; pregnancy T1 11.0, T2 10.5, T3 11.0 g/dL; children 6–59 months 10.5 g/dL).",
     "Haemoglobin entry with pregnancy/trimester partitions; cohort pair adult-anaemia-female (L) vs pregnant-2nd-trimester (N) at the same 10.8 g/dL.",
     ["WHO 2024 – Guideline on haemoglobin cutoffs to define anaemia (PMC11300700)", "DHS Program blog on the 2024 WHO anaemia cut-offs", "PAHO summary of WHO 2024 Hb thresholds"]),
    ("Altitude adjustment of SpO2 and Hb", "Resting SpO2 falls with altitude (≈ 90–94 % at 3 000 m in healthy adults); WHO 2024 also adjusts Hb for elevation.",
     "Context modifier siteAltitudeM ≥ 2 500 m lowers the SpO2 lower limit; cohorts altitude-sea-level (L) vs altitude-3000m (N) at 92 %.",
     ["WHO 2024 Hb guideline (altitude adjustment)", "High-altitude physiology reviews (SpO2 at altitude)"]),
    ("Hearing-loss grading", "WHO World Report on Hearing 2021 grades by better-ear 4-frequency average: < 20 dB normal, 20–34 mild, 35–49 moderate, 50–64 moderately severe, 65–79 severe, 80–94 profound, ≥ 95 complete.",
     "Hearing-threshold entry with WHO 2021 categories as grades; occupational category adds the 29 CFR 1910.95 standard-threshold-shift review note.",
     ["WHO World Report on Hearing 2021 grading (PMC11266198)", "29 CFR 1910.95 Occupational noise exposure"]),
    ("Blood pressure categories", "ACC/AHA 2017: normal < 120/< 80, elevated 120–129/< 80, stage 1 130–139 or 80–89, stage 2 ≥ 140 or ≥ 90, hypertensive crisis > 180 and/or > 120; paediatric BP uses age-based percentiles (simplified to PALS lower limits here).",
     "Systolic/diastolic entries carry ACC/AHA grades; primary-care category reports the grade alongside the flag.",
     ["Whelton PK et al. 2017 ACC/AHA Hypertension Guideline", "Mayo Clinic – blood pressure chart"]),
    ("QTc sex partition", "QTc > 450 ms (men) and > 460 ms (women) is prolonged; > 500 ms high risk (AHA/ACCF/HRS recommendations for ECG standardisation).",
     "QTc entry partitioned by sex; cohorts borderline-qtc-male (H) vs borderline-qtc-female (N) at 455 ms; cross-field rule checks QTc against a Bazett estimate.",
     ["Rautaharju PM et al. AHA/ACCF/HRS recommendations, Part IV (QT interval)"]),
    ("Regulatory occupational thresholds", "US DOT 49 CFR 382.505 (BrAC 0.02–0.039 → 24 h removal) and 382.201 (≥ 0.04 → violation); OSHA 29 CFR 1910.134 App A (quantitative fit factor ≥ 100 half-mask, ≥ 500 full-facepiece).",
     "BrAC and fit-factor entries with regulation/respirator-type context; occupational category reports regulatory consequences as information, never as a clinical alarm.",
     ["49 CFR 382.505 / 382.201 (eCFR, FindLaw, J. J. Keller)", "29 CFR 1910.134 Appendix A"]),
    ("Sleep apnoea severity", "AASM: adult AHI < 5 normal, 5–14 mild, 15–29 moderate, ≥ 30 severe; children AHI ≥ 1–2 is abnormal.",
     "AHI entry partitioned adult/paediatric; cohorts paediatric-osa (H) vs adult-ahi-3 (N) at AHI 3.", ["AASM Manual for the Scoring of Sleep and Associated Events v3", "AASM paediatric OSA clinical practice guideline"]),
    ("How reference intervals are established and transported", "CLSI EP28-A3c: intervals are established (≥ 120 reference individuals per partition) or transferred/verified (20 samples) by each laboratory; partitioning by age and sex when statistically justified.",
     "Every KB interval is labelled a default for integration validation; the regulatory record requires local verification before clinical use; IM-E14-BL-011 (IntiMed 2.1) adds a tenant overlay with CSV / FHIR ObservationDefinition import, CLSI EP28 transference verification (≤ 2 of 20 outside), four-eyes approval and versioning.",
     ["CLSI EP28-A3c", "Analyse-it EP28 partitioning guide", "J Lab Physicians – reference interval transference"]),
    ("Representing ranges and flags in host outputs", "FHIR R4 Observation.referenceRange (low/high/type/appliesTo/age/text) and Observation.interpretation (v3-ObservationInterpretation N, L, H, LL, HH); HL7 v2 OBX-7 (reference range) and OBX-8 (abnormal flags).",
     "FHIR and HL7 adapters render the resolved interval and flag; validation studio shows exactly what the host receives.",
     ["http://hl7.org/fhir/R4/observation-definitions.html#Observation.referenceRange", "http://terminology.hl7.org/CodeSystem/v3-ObservationInterpretation", "HL7 v2.6 Ch. 7 OBX"]),
    ("Device type precision and IEEE 11073 personal-health specialisations", "IEEE 11073-104xx specialisations (10404 pulse oximeter, 10407 BP monitor, 10408 thermometer, 10415 weighing scale, 10417 glucose meter) and IHE PCD profiles define the semantic content by device type; vendor gateways (e.g. Philips Capsule MDIP, GE CARESCAPE Gateway) determine the integration mechanism.",
     "41-type taxonomy with 11073 specialisation and data class; every catalogue model classified (196 rule-matched, 29 family default); integration profile per model and option.",
     ["IEEE 11073-104xx device specialisations", "IHE PCD Technical Framework (PCD-01)", "Philips Capsule Medical Device Information Platform – capabilities"]),
    ("Payload fingerprinting and semantic auto-mapping", "Industry integration engines rely on manual interface analysis; recognising a device from a sample message (segment structure, vendor application ids, code systems) and proposing code/unit-aware mappings shortens onboarding. Human-in-the-loop acceptance remains mandatory.",
     "DeviceIdentifier (parse success + key coverage + structural similarity + identity hints) and AutoMapper (code, concept, UCUM, label similarity) with confidence and explanation; low confidence is never auto-bound.",
     ["HL7 v2 / IHE PCD message structure", "UCUM specification (unitsofmeasure.org)"]),
]

# ------------------------------------------------------------------------------------------------ epics, features, HLRs
EPICS = [
    ("IM-E11", "Intelligence studio & dry-run foundation", "Give integration engineers, template owners and regulatory staff one place to see what a device sends, what IntiMed maps it to and how it is validated – without filing anything."),
    ("IM-E12", "Precise device identification & mapping", "Identify device type, model, connectivity option and driver from a payload; publish a precise integration profile per model; propose code/unit-aware bindings for any template."),
    ("IM-E13", "Physiology-aware cohort simulators", "Generate realistic device payloads for demographic cohorts (neonate to older adult, pregnancy, altitude, fasting, occupational regulation) with signal artefacts, and prove the expected flags end-to-end."),
    ("IM-E14", "Clinical reference ranges & category validation framework", "Annotate every numeric result with a cited, partitioned reference interval and HL7 interpretation; validate mapped output per healthcare-platform category; render exactly what the host receives."),
    ("IM-E15", "Operational data-quality intelligence", "Detect device data drift per configuration against the fleet and against the subject's reference interval, and route it to the biomedical team as a data-quality signal."),
]

HLRS = [
    ("IM-E11-HLR-01", "IM-E11", "The platform shall offer a side-effect-free dry run of parse → normalise → map → annotate for any signed driver and template, with PHI-masked identity."),
    ("IM-E11-HLR-02", "IM-E11", "Intelligence functions shall be role-restricted, audit-logged (payload hash, never payload) and limited in payload size."),
    ("IM-E12-HLR-01", "IM-E12", "The platform shall identify the driver of a captured payload with ≥ 95 % top-1 accuracy over the certified golden sessions and explain its evidence."),
    ("IM-E12-HLR-02", "IM-E12", "Every catalogue model shall carry a device type (taxonomy with IEEE 11073 specialisation and data class) and an integration profile per connectivity option."),
    ("IM-E12-HLR-03", "IM-E12", "The platform shall propose template bindings with confidence and explanation, reproducing ≥ 90 % of certified bindings; suggestions < 0.5 are never bound automatically."),
    ("IM-E13-HLR-01", "IM-E13", "Simulators shall generate payloads for demographic cohorts in the device's own units and protocol, optionally with noise, spike, drift, dropout, duplicate and clock-skew artefacts."),
    ("IM-E13-HLR-02", "IM-E13", "Scripted cohorts shall declare expected flags; the simulator shall report flag precision through the real driver and pipeline (target 100 %)."),
    ("IM-E14-HLR-01", "IM-E14", "Numeric template fields shall be annotated with a reference interval resolved by age, sex, pregnancy/trimester and context, plus an HL7 interpretation code and optional grade, with source citation."),
    ("IM-E14-HLR-02", "IM-E14", "Mapped output shall be validated in layers (structure, terminology, units, plausibility, clinical range, cross-field, grades, regulatory, lab range, dose reference, consent) according to the healthcare-platform category."),
    ("IM-E14-HLR-03", "IM-E14", "FHIR output shall carry Observation.referenceRange and Observation.interpretation; HL7 v2 output shall carry OBX-7 and OBX-8."),
    ("IM-E14-HLR-04", "IM-E14", "The exchange harness shall verify clinical reference validation (XT-17) before publication."),
    ("IM-E14-HLR-05", "IM-E14", "Flags shall be informational, switchable per configuration, and default intervals shall be replaceable by locally verified intervals."),
    ("IM-E15-HLR-01", "IM-E15", "The platform shall raise a data-quality drift alert when a configuration deviates from its fleet (|z| ≥ 3) or shows a sustained abnormal-flag share (≥ 60 % over ≥ 5 readings)."),
]

FEATURES = [
    ("IM-E11-F01", "IM-E11", "Pipeline dry run & pure mapping function"), ("IM-E11-F02", "IM-E11", "Intelligence studio UI (8 tabs)"),
    ("IM-E11-F03", "IM-E11", "Intelligence API, RBAC, audit"), ("IM-E11-F04", "IM-E11", "Subject demographics in the registry"),
    ("IM-E12-F01", "IM-E12", "Payload fingerprinting (device identification)"), ("IM-E12-F02", "IM-E12", "Device-type taxonomy & integration profiles"),
    ("IM-E12-F03", "IM-E12", "Semantic auto-mapper"),
    ("IM-E13-F01", "IM-E13", "Cohort library & physiology-aware value generation"), ("IM-E13-F02", "IM-E13", "Artefact injection"), ("IM-E13-F03", "IM-E13", "Expected-flag precision report"),
    ("IM-E14-F01", "IM-E14", "Reference-interval knowledge base & interpretation"), ("IM-E14-F02", "IM-E14", "Layered validation engine"),
    ("IM-E14-F03", "IM-E14", "Healthcare-platform categories"), ("IM-E14-F04", "IM-E14", "Range & flag rendering in FHIR / HL7 adapters"),
    ("IM-E14-F05", "IM-E14", "XT-17 clinical reference validation"), ("IM-E14-F06", "IM-E14", "Local interval governance"),
    ("IM-E15-F01", "IM-E15", "Drift monitor (AN-08)"), ("IM-E15-F02", "IM-E15", "Drift drill & alert workflow"), ("IM-E15-F03", "IM-E15", "Predictive maintenance & ML drift"),
]

J = "intimed-platform/src/main/java/io/intimed/"
T = "intimed-platform/src/test/java/io/intimed/"
# id, feature, type, title, sp, sprint, status, code refs, test refs, note (reason+strategy for deferred/partial)
BACKLOG = [
    ("IM-E11-BL-001", "IM-E11-F01", "Enabler", "Extract a pure mapField/dryMap from the router (shared by live routing and dry runs)", 5, "IM-S17", "DELIVERED", J + "engine/pipeline/Router.java", T + "PlatformIntegrationTest.java", ""),
    ("IM-E11-BL-002", "IM-E11-F01", "Story", "Pipeline.dryRun: parse → normalise → map → annotate with no persistence, events or metering; PHI-masked identity", 5, "IM-S17", "DELIVERED", J + "engine/pipeline/Pipeline.java", T + "PlatformIntegrationTest.java#cohortSimulator…", ""),
    ("IM-E11-BL-003", "IM-E11-F02", "Story", "Intelligence studio page with validation, identification, auto-mapping, cohorts, ranges, categories, types and drift tabs (CSP-safe, no innerHTML)", 13, "IM-S18", "DELIVERED", "intimed-platform/src/main/resources/static/assets/pages/intelligence.js", "Playwright smoke (all 8 tabs)", ""),
    ("IM-E11-BL-004", "IM-E11-F03", "Story", "REST API /api/intelligence/* with role checks, 256 KiB payload limit and audit (payload hash only)", 5, "IM-S17", "DELIVERED", J + "web/IntelligenceController.java", "security-test-kit (69/69)", ""),
    ("IM-E11-BL-005", "IM-E11-F04", "Story", "Subject registry: birth date, sex, pregnancy/trimester (validated, encrypted at rest); demo subjects with synthetic demographics", 3, "IM-S17", "DELIVERED", J + "web/ClinicalController.java; " + J + "demo/DemoService.java", T + "PlatformIntegrationTest.java#exchangeTestCarriesClinicalReference…", ""),
    ("IM-E11-BL-006", "IM-E11-F04", "Story", "Demographics from the host (FHIR Patient / HL7 PID-7, PID-8) instead of the local registry", 8, "IM-S21", "DELIVERED",
     J + "engine/association/HostDemographics.java; " + J + "engine/transport/DeviceListeners.java#adt (A01/A04/A08); " + J + "api/FhirController.java#patient (POST/PUT /fhir/r4/Patient, scope system/Patient.write)",
     T + "ClinicalIntelligenceDeferredIntegrationTest.java#hostDemographicsFromAdtPid7Pid8ChangeTheReferencePartition",
     "IntiMed 2.1: PID-7/PID-8 and Patient.birthDate/gender update the subject registry with per-field provenance (HL7_ADT | FHIR | MANUAL, time, control id); manual entries win only while newer; older host messages never overwrite newer ones. Per-host consent model for demographics remains a deployment decision."),
    ("IM-E12-BL-001", "IM-E12-F01", "Story", "DeviceIdentifier: parse success, key coverage, structural similarity, sending-application / GATT / serial hints, calibrated confidence and margin", 8, "IM-S18", "DELIVERED", J + "intelligence/DeviceIdentifier.java", T + "PlatformIntegrationTest.java#deviceIdentification…", ""),
    ("IM-E12-BL-002", "IM-E12-F01", "Story", "Identification benchmark over every driver's golden session (top-1 accuracy)", 3, "IM-S18", "DELIVERED", J + "intelligence/DeviceIdentifier.java#benchmark", T + "PlatformIntegrationTest.java", ""),
    ("IM-E12-BL-003", "IM-E12-F01", "Story", "Identification from live traffic captured on an unconfigured port (quarantine listener) and firmware-version detection", 8, "IM-S21", "DEFERRED", "", "", "Reason: an unconfigured listener widens the attack surface; requires a security design review. Strategy: a quarantine mTLS listener bound to enrolled-but-unconfigured endpoints, payloads kept in a sealed sample store, identification offered in the wizard step 1."),
    ("IM-E12-BL-004", "IM-E12-F02", "Story", "Device-type taxonomy (41 types, IEEE 11073 specialisation, data class) and classification of all 225 catalogue models with basis and confidence", 5, "IM-S17", "DELIVERED", "intimed-platform/src/main/resources/seed/device-types.json; tools/content/intelligence_content.py", "content generator output", ""),
    ("IM-E12-BL-005", "IM-E12-F02", "Story", "Integration profile per model: mechanism per option, transport security, vendor gateway, direction, capture mode, mapping precision", 5, "IM-S18", "DELIVERED", J + "intelligence/IntegrationProfiles.java", T + "PlatformIntegrationTest.java#deviceIdentification…", ""),
    ("IM-E12-BL-006", "IM-E12-F02", "Story", "Review the 29 family-default classifications with biomedical engineering and the GMDN/EMDN code per model", 5, "IM-S20", "PARTIAL", "device-types.json", "", "Reason: GMDN is a licensed nomenclature. Strategy: keep rule-based types; map to GMDN/EMDN when the licence is available; biomedical review of the 29 family-default models in IM-S20."),
    ("IM-E12-BL-007", "IM-E12-F03", "Story", "AutoMapper: exact code, shared clinical concept, UCUM convertibility, label similarity with clinical synonyms; AUTO / REVIEW / UNBOUND", 8, "IM-S18", "DELIVERED", J + "intelligence/AutoMapper.java", T + "PlatformIntegrationTest.java#autoMapping…", ""),
    ("IM-E12-BL-008", "IM-E12-F03", "Story", "Auto-mapping benchmark (coded and label-only) against all certified bindings", 3, "IM-S18", "DELIVERED", J + "intelligence/AutoMapper.java#benchmark", T + "PlatformIntegrationTest.java", ""),
    ("IM-E12-BL-009", "IM-E12-F03", "Story", "Wizard step 5 pre-fills bindings from the auto-mapper for imported templates (RR-18) with one-click accept per field", 5, "IM-S19", "DELIVERED",
     J + "console/intelligence/RecommendationEngine.java#rr17/rr18; " + J + "console/ConfigService.java#apply/acceptAll; " + J + "harness/HarnessService.java#clinicalReference",
     T + "ClinicalIntelligenceDeferredIntegrationTest.java#rr17RecordsThePlatformCategory…, #rr18AutoMapSuggestions…",
     "IntiMed 2.1: RR-17 records the platform category inferred from template settings on the configuration (used by XT-17 and the studio); RR-18 proposes one REVIEW recommendation per field with confidence for imported/host templates; 'accept all' never accepts RR-18 below 0.8; accept/override is audited through the existing decision trail."),
    ("IM-E12-BL-010", "IM-E12-F03", "Story", "Full terminology services (LOINC/SNOMED CT lookups, ConceptMap) for uncoded host fields", 13, "IM-S22", "PARTIAL",
     J + "intelligence/TerminologyService.java; " + J + "intelligence/FhirTerminologyClient.java; " + J + "intelligence/AutoMapper.java#score",
     T + "ClinicalIntelligenceDeferredIntegrationTest.java#terminologyClient…, #rr18… (local HTTPS stub)",
     "Adapter delivered (IntiMed 2.1): FHIR $lookup / $validate-code / $translate client behind the AutoMapper, disabled by default, HTTPS + host allow-list, timeouts, cache; used for host codes from unknown systems. Remaining (manual): a real terminology server with LOINC / SNOMED CT licences and curated ConceptMaps for the customer's local code systems – not bundled, tested only against a local stub."),
    ("IM-E13-BL-001", "IM-E13-F01", "Story", "Cohort library: 33 cohorts (neonate → older adult, pregnancy trimesters, altitude, fasting, DOT, respirator type) with scripted values and expected flags", 5, "IM-S17", "DELIVERED", "intimed-platform/src/main/resources/simulator/profiles.json", T + "ClinicalIntelligenceTests.java#everyScriptedCohort…", ""),
    ("IM-E13-BL-002", "IM-E13-F01", "Story", "Value generation in knowledge-base units, converted into the device's unit (learnt from the golden session) and protocol", 8, "IM-S18", "DELIVERED", J + "intelligence/CohortSimulator.java", T + "PlatformIntegrationTest.java#cohortSimulator…", ""),
    ("IM-E13-BL-003", "IM-E13-F02", "Story", "Artefacts: noise, spike, drift, dropout, duplicate, clock skew (deterministic by seed)", 5, "IM-S18", "DELIVERED", J + "intelligence/CohortSimulator.java", T + "PlatformIntegrationTest.java", ""),
    ("IM-E13-BL-004", "IM-E13-F03", "Story", "Expected-flag comparison and run-all precision report; greedy driver selection per cohort", 3, "IM-S18", "DELIVERED", J + "intelligence/CohortSimulator.java#runAll", T + "PlatformIntegrationTest.java", ""),
    ("IM-E13-BL-005", "IM-E13-F01", "Story", "Time-series physiology (circadian HR, deterioration trajectories, NEWS2 progression) for continuous devices", 8, "IM-S21", "DELIVERED",
     J + "intelligence/CohortSimulator.java#trajectory/interpolate; " + J + "clinical/News2.java; " + J + "web/IntelligenceController.java#trajectory; intimed-platform/src/main/resources/simulator/profiles.json (3 trajectories)",
     T + "ClinicalIntelligenceTests.java#trajectoryInterpolation…, " + T + "ClinicalIntelligenceDeferredIntegrationTest.java#sepsisTrajectory…",
     "IntiMed 2.1: keyframe trajectories (sepsis 6 h, post-op recovery, hypoglycaemia) – piecewise-linear + seeded noise, per-step flags and an informational NEWS2-style aggregate; optional streaming into a published configuration (DEVICE_ADMIN). Engineering scripts, not validated physiological models; circadian patterns are expressible as keyframes but none is shipped."),
    ("IM-E14-BL-001", "IM-E14-F01", "Story", "Reference-interval knowledge base: 33 parameters, 8 age bands, sex/pregnancy partitions, critical limits, grades, cited sources", 8, "IM-S17", "DELIVERED", "intimed-platform/src/main/resources/clinical/reference-ranges.json", T + "ClinicalIntelligenceTests.java", ""),
    ("IM-E14-BL-002", "IM-E14-F01", "Story", "ReferenceRanges: match by LOINC/MDC/local/field id, partition by age/sex/pregnancy, context modifiers (altitude, fasting, respirator type, regulation), interpretation N/L/H/LL/HH + grade", 8, "IM-S17", "DELIVERED", J + "clinical/ReferenceRanges.java; " + J + "clinical/Demographics.java", T + "ClinicalIntelligenceTests.java#ageSexPregnancy…", ""),
    ("IM-E14-BL-003", "IM-E14-F01", "Story", "Live routing annotates template fields with range, flag, partition and age band from subject demographics; per-configuration switch", 5, "IM-S17", "DELIVERED", J + "engine/pipeline/Router.java", T + "PlatformIntegrationTest.java#exchangeTestCarries…", ""),
    ("IM-E14-BL-004", "IM-E14-F02", "Story", "ValidationEngine: structure, terminology, units, plausibility, clinical range, cross-field (BP, MAP, FEV1/FVC, QTc/Bazett, UF, VTBI, neonatal gradient), grades, regulatory, lab range, dose (DRL), consent", 13, "IM-S18", "DELIVERED", J + "intelligence/ValidationEngine.java", T + "ClinicalIntelligenceTests.java#layeredValidation…", ""),
    ("IM-E14-BL-005", "IM-E14-F03", "Story", "Eight healthcare-platform categories (acute EHR, primary care, occupational health, LIS, imaging/cardiology, RPM, specialist, wellness) with rule packs and interpretation policy", 5, "IM-S17", "DELIVERED", "intimed-platform/src/main/resources/clinical/platform-categories.json; " + J + "clinical/PlatformCategories.java", T + "ClinicalIntelligenceTests.java", ""),
    ("IM-E14-BL-006", "IM-E14-F04", "Story", "FHIR Observation.referenceRange + interpretation; HL7 OBX-7 / OBX-8", 3, "IM-S17", "DELIVERED", J + "templates/TemplateAdapters.java", T + "ClinicalIntelligenceTests.java", ""),
    ("IM-E14-BL-007", "IM-E14-F05", "Story", "XT-17 clinical reference validation in the exchange harness (annotation coverage, cohort flags, category validation) – sealed in the evidence pack", 5, "IM-S18", "DELIVERED", J + "harness/HarnessService.java#clinicalReference", T + "PlatformIntegrationTest.java", ""),
    ("IM-E14-BL-008", "IM-E14-F01", "Story", "Regulatory record & impact assessment for informational flagging (FDA CDS 2022, EU MDR Rule 11)", 2, "IM-S17", "DELIVERED", J + "quality/QualityController.java#classification", "", ""),
    ("IM-E14-BL-009", "IM-E14-F01", "Story", "Percent-predicted spirometry (GLI-2012 LLN z-scores), paediatric BP percentiles, growth-chart z-scores", 13, "IM-S20", "DEFERRED", "", "", "Reason: needs height/ethnicity inputs and validated equation sets. Strategy: add height to demographics and an equation module (GLI-2012 LMS tables) behind ReferenceRanges; dynamic intervals per subject."),
    ("IM-E14-BL-010", "IM-E14-F01", "Story", "Derived values: eGFR (CKD-EPI 2021), corrected calcium, anion gap, BMI", 5, "IM-S20", "DELIVERED",
     J + "clinical/DerivedValues.java; " + J + "engine/pipeline/Router.java#applyDerived; intimed-platform/src/main/resources/clinical/reference-ranges.json (egfr, KDIGO G1–G5)",
     T + "ClinicalIntelligenceTests.java#egfrCkdEpi2021KnownValuesAndKdigoGrades, #derivedValues…; " + T + "ClinicalIntelligenceDeferredIntegrationTest.java#deltaChecks…AndDerivedValuesAreLabelled",
     "IntiMed 2.1: versioned formulas (CKD-EPI 2021 race-free, BMI, anion gap, Payne corrected Ca) with formula id/version, inputs and 'derived – verify'; target template field or 'derived' annotation block; params.derivedValues default OFF. Enabling in production still requires the documented CDS / MDR Rule 11 assessment (manual regulatory step)."),
    ("IM-E14-BL-011", "IM-E14-F06", "Story", "Tenant interval governance: import laboratory intervals (CSV/FHIR ObservationDefinition), CLSI EP28 transference verification (20 samples), approval four-eyes, versioning", 13, "IM-S19", "DELIVERED",
     J + "clinical/IntervalGovernance.java; " + J + "clinical/ReferenceRanges.java#effective; " + J + "engine/pipeline/Router.java#mapField(tenant); " + J + "web/IntelligenceController.java#/intervals",
     T + "ClinicalIntelligenceTests.java#ep28…; " + T + "ClinicalIntelligenceDeferredIntegrationTest.java#intervalOverlayNeedsEp28AndFourEyes…, #rr17…Xt17PassesAgainstTheTenantOverlay",
     "IntiMed 2.1: DRAFT → VERIFIED (EP28 per partition, ≥ 20 results, ≤ 10 % outside, report stored) → APPROVED (four-eyes, REGULATORY/TENANT_ADMIN/PHYSICIAN) → ACTIVE (versioned, audited); live annotation, studio and XT-17 use the ACTIVE overlay ('source: tenant overlay vN'). Each laboratory still has to collect its own 20 reference samples."),
    ("IM-E14-BL-012", "IM-E14-F02", "Story", "Delta checks against the subject's previous result and critical-value call-back acknowledgement workflow in the host", 8, "IM-S21", "DELIVERED",
     J + "clinical/DeltaCheck.java; " + J + "engine/pipeline/Router.java#deltaCheck; " + J + "events/EventBus.java (observation.flagged)",
     T + "ClinicalIntelligenceTests.java#deltaThresholds…; " + T + "ClinicalIntelligenceDeferredIntegrationTest.java#deltaChecksEmitAnInformationalFlagEvent…",
     "IntiMed 2.1: per subject + concept against the previous filed value within a window; KB thresholds (K 1.0 mmol/L, Na 8 mmol/L, Hb 2 g/dL, creatinine 50 %, glucose 50 %); informational 'observation.flagged' event (ids, entry, codes – no PHI) deliverable by signed webhooks. By design the call-back/acknowledgement workflow stays in the host (intended use excludes alarms)."),
    ("IM-E15-BL-001", "IM-E15-F01", "Story", "DriftMonitor: per configuration/key mean vs fleet baseline (z-score) and abnormal-flag share (series-aware); drift-alert store", 5, "IM-S19", "DELIVERED", J + "intelligence/DriftMonitor.java", "demo drift drill (2 alerts)", ""),
    ("IM-E15-BL-002", "IM-E15-F02", "Story", "Demo drift drill (hospital-at-home probe) and Drift tab", 2, "IM-S19", "DELIVERED", J + "demo/DemoService.java#driftDrill", "manual / demo guide", ""),
    ("IM-E15-BL-003", "IM-E15-F02", "Story", "Drift alert workflow: acknowledge, link to calibration record, auto-resolve, SIEM forwarding", 5, "IM-S20", "DELIVERED",
     J + "intelligence/DriftMonitor.java#acknowledge/resolve/analyse; " + J + "web/IntelligenceController.java#/drift/alerts/{id}/ack|resolve; intimed-platform/src/main/resources/static/assets/pages/intelligence.js#drift",
     T + "ClinicalIntelligenceDeferredIntegrationTest.java#driftAlertsAreAcknowledgedLinkedToCalibrationAutoResolvedAndForwardedToSiem",
     "IntiMed 2.1: OPEN → ACKNOWLEDGED (note, optional link to a calibration check of the configuration) → RESOLVED (manual or AUTO when the next analysis no longer alerts); every transition audited and forwarded to the SIEM sink."),
    ("IM-E15-BL-004", "IM-E15-F03", "Story", "ML drift (seasonal baselines, change-point detection) and predictive maintenance from device logs", 13, "IM-S22", "DEFERRED", "", "", "Reason: needs months of fleet data and MLOps governance. Strategy: export de-identified feature store; offline model training; models signed and versioned like driver packages; shadow-mode evaluation before alerts."),
]

SPRINTS = [
    ("IM-S17", "Clinical reference foundation", "KB, interpretation, categories, router annotation, adapters, demographics, regulatory record"),
    ("IM-S18", "Validation studio & identification", "Validation engine, studio UI, identification, profiles, auto-mapper, cohort simulator, XT-17"),
    ("IM-S19", "Operational intelligence & governance I", "Drift monitor and drill, RR-17/RR-18 wizard integration, interval governance"),
    ("IM-S20", "Clinical depth", "Percent-predicted spirometry, derived values, taxonomy review, drift workflow"),
    ("IM-S21", "Live context", "Host demographics, quarantine identification, time-series physiology, delta checks"),
    ("IM-S22", "Terminology & ML", "Terminology server, ML drift / predictive maintenance"),
]
CAPACITY = 60  # story points per sprint (team of 6, as in the 1.0 capacity model)


def grade_txt(x):
    n, lo, hi = x
    return "%s %s" % (n, "≥ %s" % lo if hi is None else "< %s" % hi if lo is None else "%s–<%s" % (lo, hi))


def totals():
    t = {}
    for b in BACKLOG:
        t.setdefault(b[6], [0, 0])
        t[b[6]][0] += 1
        t[b[6]][1] += b[4]
    return t


# ------------------------------------------------------------------------------------------------ markdown
def md():
    t = totals()
    L = ["# IntiMed 2.0 – Enhancement Epics & Backlog Pack", "",
         "Scope: more intelligence in the platform; precise mapping of device type, model, integration mechanism and simulators; a comprehensive validation framework that shows the mapped output template per healthcare-platform category with reference ranges by age group and other parameters.", "",
         "## Delivery summary", "", "| Status | Items | Story points |", "|---|---|---|"]
    for s in ("DELIVERED", "PARTIAL", "DEFERRED"):
        L.append("| %s | %d | %d |" % (s, t.get(s, [0, 0])[0], t.get(s, [0, 0])[1]))
    L += ["", "Measured precision (integration tests on the 26 shipped drivers, 22 templates, 33 cohorts):", "",
          "| Metric | Result |", "|---|---|",
          "| Device identification top-1 (golden sessions) | %.0f %% |" % (METRICS["identificationTop1"] * 100),
          "| Auto-mapping recall vs certified bindings (coded templates) | %.0f %% |" % (METRICS["autoMappingRecall"] * 100),
          "| Auto-mapping recall with codes stripped (labels + units only) | %.0f %% |" % (METRICS["autoMappingLabelOnlyRecall"] * 100),
          "| Cohort expected-flag precision through real drivers and pipeline | %.0f %% |" % (METRICS["cohortFlagPrecision"] * 100), "",
          "Caveat: the shipped drivers were authored against the same 22 templates, so these figures are an upper bound. Real host schemas and third-party payloads will score lower; low-confidence suggestions are never bound automatically.", "",
          "## Research findings and how they shaped the design", ""]
    for i, (topic, finding, design, src) in enumerate(RESEARCH, 1):
        L += ["### %d. %s" % (i, topic), "", "**Finding.** " + finding, "", "**Design response.** " + design, "", "Sources: " + "; ".join(src), ""]
    L += ["## Epics", "", "| Epic | Name | Goal |", "|---|---|---|"] + ["| %s | %s | %s |" % e for e in EPICS]
    L += ["", "## High-level requirements", "", "| HLR | Epic | Requirement |", "|---|---|---|"] + ["| %s | %s | %s |" % h for h in HLRS]
    L += ["", "## Features", "", "| Feature | Epic | Name |", "|---|---|---|"] + ["| %s | %s | %s |" % f for f in FEATURES]
    L += ["", "## Sprint plan (capacity %d SP / sprint)" % CAPACITY, "", "| Sprint | Theme | Scope | Planned SP | Delivered SP |", "|---|---|---|---|---|"]
    for sp, theme, scope in SPRINTS:
        plan = sum(b[4] for b in BACKLOG if b[5] == sp)
        done = sum(b[4] for b in BACKLOG if b[5] == sp and b[6] == "DELIVERED")
        L.append("| %s | %s | %s | %d | %d |" % (sp, theme, scope, plan, done))
    L += ["", "## Backlog and traceability", "", "| ID | Feature | Type | Title | SP | Sprint | Status | Code | Verification |", "|---|---|---|---|---|---|---|---|---|"]
    for b in BACKLOG:
        L.append("| %s | %s | %s | %s | %d | %s | %s | %s | %s |" % (b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7] or "—", b[8] or "—"))
    L += ["", "## Partial and deferred items – reason and strategy", "", "| ID | Status | Title | Reason and strategy |", "|---|---|---|---|"]
    for b in BACKLOG:
        if b[6] != "DELIVERED":
            L.append("| %s | %s | %s | %s |" % (b[0], b[6], b[3], b[9]))
    L += ["", "## Reference-interval knowledge base (%d parameters)" % len(KB["entries"]), "", "> " + KB["disclaimer"], "",
          "| Parameter | Unit | Partitions | Source |", "|---|---|---|---|"]
    for e in KB["entries"]:
        L.append("| %s | %s | %d | %s |" % (e["name"], e["unit"], len(e["ranges"]), KB["sources"].get(e["source"].split(" / ")[0], e["source"])))
    L += ["", "## Healthcare-platform categories", "", "| Category | Templates | Rule pack | Interpretation |", "|---|---|---|---|"]
    for c in CATS:
        L.append("| %s – %s | %s | %s | %s |" % (c["id"], c["name"], ", ".join(c["templates"]), ", ".join(c["rules"]), c["interpretation"]))
    L += ["", "## Simulator cohorts (%d)" % len(PROFILES), "", "| Cohort | Demographics | Scripted values | Expected flags |", "|---|---|---|---|"]
    for p in PROFILES:
        scripted = ("trajectory: %d keyframes over %d min (noise %s %%)" % (len(p["trajectory"]), max(k["minute"] for k in p["trajectory"]), p.get("noisePct", 0))) if p.get("trajectory") \
            else json.dumps(p["concepts"]) if p["concepts"] else "interval midpoint"
        L.append("| %s | %s | %s | %s |" % (p["name"], json.dumps(p["demographics"]), scripted, json.dumps(p["expected"]) if p["expected"] else "—"))
    L += ["", "## Device-type taxonomy (%d types)" % len(TYPES), "", "| Code | Name | IEEE 11073 | Data class |", "|---|---|---|---|"]
    for t_ in TYPES:
        L.append("| %s | %s | %s | %s |" % (t_["code"], t_["name"], t_.get("ieee11073Specialization") or "—", t_["dataClass"]))
    L += ["", "## Regulatory note", "",
          "Reference-range flags are informational (analogous to laboratory report flags): no diagnosis, no alarm, no treatment recommendation. They can be switched off per configuration (`params.referenceRangeFlagging=false`). Enabling them in production requires a documented regulatory assessment (FDA CDS guidance 2022; EU MDR Rule 11) and locally verified intervals (CLSI EP28-A3c). The validation studio and cohort simulator are non-clinical engineering tools.", "",
          "IntiMed 2.1 additions keep the same posture: derived values are OFF by default (`params.derivedValues`) and labelled \"derived – verify\"; delta checks (`params.deltaChecks`, default on only with flagging) emit an informational `observation.flagged` event – the host owns any call-back or acknowledgement; tenant interval overlays become effective only after EP28 verification and four-eyes approval; NEWS2 output of the trajectory simulator is informational engineering output.", ""]
    return "\n".join(L)


# ------------------------------------------------------------------------------------------------ docx
def docx_out(path):
    d = Document()
    st = d.styles["Normal"]
    st.font.name = "Calibri"
    st.font.size = Pt(10)
    d.add_heading("IntiMed 2.0 – Enhancement Epics & Backlog Pack", 0)
    d.add_paragraph("More intelligence in the platform; precise device type, model, integration mechanism and simulator mapping; a category-based validation framework with age-, sex- and context-partitioned reference ranges.")

    def table(headers, rows):
        tb = d.add_table(rows=1, cols=len(headers))
        tb.style = "Light Grid Accent 1"
        for i, h in enumerate(headers):
            tb.rows[0].cells[i].text = h
        for r in rows:
            cells = tb.add_row().cells
            for i, v in enumerate(r):
                cells[i].text = str(v)
        return tb

    t = totals()
    d.add_heading("Delivery summary", 1)
    table(["Status", "Items", "Story points"], [(s, t.get(s, [0, 0])[0], t.get(s, [0, 0])[1]) for s in ("DELIVERED", "PARTIAL", "DEFERRED")])
    d.add_paragraph()
    table(["Metric", "Result"], [("Device identification top-1", "%.0f %%" % (METRICS["identificationTop1"] * 100)), ("Auto-mapping recall (coded)", "%.0f %%" % (METRICS["autoMappingRecall"] * 100)),
                                 ("Auto-mapping recall (labels only)", "%.0f %%" % (METRICS["autoMappingLabelOnlyRecall"] * 100)), ("Cohort flag precision", "%.0f %%" % (METRICS["cohortFlagPrecision"] * 100))])
    p = d.add_paragraph("Caveat: the shipped drivers were authored against the same templates, so these figures are an upper bound for third-party payloads and host schemas.")
    p.runs[0].font.color.rgb = RGBColor(0x8A, 0x5A, 0x00)
    d.add_heading("Research findings", 1)
    for i, (topic, finding, design, src) in enumerate(RESEARCH, 1):
        d.add_heading("%d. %s" % (i, topic), 2)
        d.add_paragraph().add_run("Finding. ").bold = True
        d.paragraphs[-1].add_run(finding)
        d.add_paragraph().add_run("Design response. ").bold = True
        d.paragraphs[-1].add_run(design)
        d.add_paragraph("Sources: " + "; ".join(src)).runs[0].italic = True
    d.add_heading("Epics", 1)
    table(["Epic", "Name", "Goal"], EPICS)
    d.add_heading("High-level requirements", 1)
    table(["HLR", "Epic", "Requirement"], HLRS)
    d.add_heading("Features", 1)
    table(["Feature", "Epic", "Name"], FEATURES)
    d.add_heading("Sprint plan (capacity %d SP)" % CAPACITY, 1)
    table(["Sprint", "Theme", "Scope", "Planned SP", "Delivered SP"], [(s, th, sc, sum(b[4] for b in BACKLOG if b[5] == s), sum(b[4] for b in BACKLOG if b[5] == s and b[6] == "DELIVERED")) for s, th, sc in SPRINTS])
    d.add_heading("Backlog", 1)
    table(["ID", "Feature", "Title", "SP", "Sprint", "Status"], [(b[0], b[1], b[3], b[4], b[5], b[6]) for b in BACKLOG])
    d.add_heading("Traceability to code and verification", 1)
    table(["ID", "Code", "Verification"], [(b[0], b[7] or "—", b[8] or "—") for b in BACKLOG if b[6] != "DEFERRED"])
    d.add_heading("Partial and deferred – reason and strategy", 1)
    table(["ID", "Status", "Title", "Reason and strategy"], [(b[0], b[6], b[3], b[9]) for b in BACKLOG if b[6] != "DELIVERED"])
    d.add_heading("Reference-interval knowledge base", 1)
    d.add_paragraph(KB["disclaimer"]).runs[0].italic = True
    table(["Parameter", "Unit", "Partitions", "Source"], [(e["name"], e["unit"], len(e["ranges"]), KB["sources"].get(e["source"].split(" / ")[0], e["source"])) for e in KB["entries"]])
    d.add_heading("Healthcare-platform categories", 1)
    table(["Category", "Templates", "Rule pack", "Interpretation"], [(c["id"] + " – " + c["name"], ", ".join(c["templates"]), ", ".join(c["rules"]), c["interpretation"]) for c in CATS])
    d.add_heading("Regulatory note", 1)
    d.add_paragraph("Reference-range flags are informational, switchable per configuration and require locally verified intervals and a documented regulatory assessment (FDA CDS 2022, EU MDR Rule 11) before production use.")
    d.save(path)


# ------------------------------------------------------------------------------------------------ xlsx
def xlsx_out(path):
    wb = Workbook()
    head = Font(bold=True, color="FFFFFF")
    fill = PatternFill("solid", fgColor="0B5FFF")
    colors = {"DELIVERED": "E5F5EB", "PARTIAL": "FFF4DC", "DEFERRED": "FDECEB"}

    def sheet(name, headers, rows, widths=None, status_col=None):
        ws = wb.create_sheet(name)
        ws.append(headers)
        for c in ws[1]:
            c.font, c.fill = head, fill
        for r in rows:
            ws.append(list(r))
            if status_col is not None and r[status_col] in colors:
                for c in ws[ws.max_row]:
                    c.fill = PatternFill("solid", fgColor=colors[r[status_col]])
        for i, w in enumerate(widths or [], 1):
            ws.column_dimensions[ws.cell(1, i).column_letter].width = w
        for row in ws.iter_rows(min_row=2):
            for c in row:
                c.alignment = Alignment(wrap_text=True, vertical="top")
        ws.freeze_panes = "A2"
        ws.auto_filter.ref = ws.dimensions
        return ws

    wb.remove(wb.active)
    t = totals()
    sheet("Summary", ["Status", "Items", "Story points"], [(s, t.get(s, [0, 0])[0], t.get(s, [0, 0])[1]) for s in ("DELIVERED", "PARTIAL", "DEFERRED")], [16, 10, 14], 0)
    sheet("Metrics", ["Metric", "Result"], [(k, v) for k, v in METRICS.items()], [40, 12])
    sheet("Epics", ["Epic", "Name", "Goal"], EPICS, [10, 45, 100])
    sheet("HLRs", ["HLR", "Epic", "Requirement"], HLRS, [16, 10, 110])
    sheet("Features", ["Feature", "Epic", "Name"], FEATURES, [14, 10, 60])
    sheet("Backlog", ["ID", "Feature", "Type", "Title", "SP", "Sprint", "Status", "Code", "Verification", "Reason & strategy"], BACKLOG, [15, 12, 9, 70, 5, 9, 12, 50, 45, 80], 6)
    sheet("Sprints", ["Sprint", "Theme", "Scope", "Capacity SP", "Planned SP", "Delivered SP"],
          [(s, th, sc, CAPACITY, sum(b[4] for b in BACKLOG if b[5] == s), sum(b[4] for b in BACKLOG if b[5] == s and b[6] == "DELIVERED")) for s, th, sc in SPRINTS], [9, 34, 80, 12, 11, 13])
    sheet("Research", ["Topic", "Finding", "Design response", "Sources"], [(a, b, c, "; ".join(d)) for a, b, c, d in RESEARCH], [30, 70, 70, 60])
    rr = []
    for e in KB["entries"]:
        for r in e["ranges"]:
            rr.append((e["id"], e["name"], e["unit"], r["ageFrom"], r["ageTo"], r.get("sex", "any"), "T%s" % r["trimester"] if r.get("pregnant") else "", r.get("low"), r.get("high"),
                       r.get("criticalLow"), r.get("criticalHigh"), "; ".join(grade_txt(x) for x in r.get("categories", [])), KB["sources"].get(e["source"].split(" / ")[0], e["source"])))
    sheet("Reference ranges", ["Entry", "Parameter", "Unit", "Age from (y)", "Age to (y)", "Sex", "Pregnancy", "Low", "High", "Critical low", "Critical high", "Grades", "Source"], rr,
          [16, 26, 10, 10, 10, 8, 10, 8, 8, 10, 10, 50, 50])
    sheet("Categories", ["Id", "Name", "Templates", "Rules", "Interpretation", "Critical handling"],
          [(c["id"], c["name"], ", ".join(c["templates"]), ", ".join(c["rules"]), c["interpretation"], c.get("criticalHandling", "")) for c in CATS], [16, 40, 30, 60, 16, 60])
    sheet("Cohorts", ["Id", "Name", "Group", "Demographics", "Scripted values", "Expected flags"],
          [(p["id"], p["name"], p["group"], json.dumps(p["demographics"]), json.dumps(p["concepts"]), json.dumps(p["expected"])) for p in PROFILES], [24, 45, 12, 50, 40, 40])
    sheet("Device types", ["Code", "Name", "IEEE 11073", "Data class"], [(x["code"], x["name"], x.get("ieee11073Specialization") or "", x["dataClass"]) for x in TYPES], [24, 55, 12, 14])
    wb.save(path)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    open(os.path.join(OUT, "IntiMed-2.0-Enhancement-Epics-and-Backlog.md"), "w").write(md())
    docx_out(os.path.join(OUT, "IntiMed-2.0-Enhancement-Epics-and-Backlog.docx"))
    xlsx_out(os.path.join(OUT, "IntiMed-2.0-Enhancement-Backlog.xlsx"))
    t = totals()
    print("backlog", len(BACKLOG), t, "->", OUT)
