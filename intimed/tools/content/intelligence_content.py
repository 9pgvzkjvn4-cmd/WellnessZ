#!/usr/bin/env python3
"""IntiMed 2.0 intelligence content: reference-interval knowledge base, healthcare-platform categories, device-type
taxonomy (assigned to all catalogue models), physiological simulator cohorts. Run: python3 tools/content/intelligence_content.py

IMPORTANT – reference intervals are DEFAULTS for integration validation and demonstration. Every deploying organisation must
confirm or replace them with its own (laboratory-verified, CLSI EP28-A3c) intervals and action thresholds before clinical use.
Values marked 'demo action threshold' are illustrative critical limits, not clinical recommendations."""
import json, os, re
R = os.path.join(os.path.dirname(__file__), "..", "..", "intimed-platform", "src", "main", "resources")

SRC = {
 "PALS": "AHA Pediatric Advanced Life Support normal vital signs by age (PALS 2015/2020 tables as reproduced in published charts) – verify against the AHA PALS Provider Manual",
 "ACCAHA": "2017 ACC/AHA Guideline for High Blood Pressure in Adults – categories normal <120/<80, elevated 120–129/<80, stage 1 130–139 or 80–89, stage 2 ≥140 or ≥90, crisis >180 and/or >120",
 "WHOHB": "WHO 2024 Guideline on haemoglobin cutoffs to define anaemia (children 6–23 months 10.5 g/dL; pregnancy trimester-specific); upper limits are laboratory-specific demo values",
 "WHOHEAR": "WHO World Report on Hearing 2021 – grades: <20 dB normal, 20–<35 mild, 35–<50 moderate, 50–<65 moderately severe, 65–<80 severe, 80–<95 profound, ≥95 complete (epidemiological, better ear, adults)",
 "DOT": "49 CFR 382.505 / 382.201 (FMCSA) – breath alcohol 0.02–0.039: no safety-sensitive functions for ≥24 h; ≥0.04: violation",
 "AASM": "AASM scoring – adult AHI <5 normal, 5–<15 mild, 15–<30 moderate, ≥30 severe; paediatric AHI >1 abnormal",
 "ADA": "ADA Standards of Care – fasting plasma glucose 3.9–5.5 mmol/L (70–99 mg/dL), HbA1c <5.7 % normal, 5.7–6.4 % prediabetes, ≥6.5 % diabetes; level 2 hypoglycaemia <3.0 mmol/L",
 "GOLD": "GOLD – post-bronchodilator FEV1/FVC <0.70 (fixed ratio); GLI-2012 lower limit of normal preferred (age/sex/height-specific equations, not bundled)",
 "KDOQI": "KDOQI Haemodialysis Adequacy 2015 – minimum delivered spKt/V 1.2 (target 1.4)",
 "OSHA": "OSHA 29 CFR 1910.134 Appendix A – quantitative fit test pass: fit factor ≥100 (half-mask), ≥500 (full-facepiece)",
 "FIGO": "FIGO / NICE intrapartum CTG – baseline fetal heart rate 110–160 bpm; <100 or >180 abnormal",
 "WHONEO": "WHO thermal protection of the newborn – normal axillary/skin temperature 36.5–37.5 °C",
 "LAB": "Common adult laboratory reference intervals (illustrative; replace with the performing laboratory's verified intervals – CLSI EP28-A3c)",
 "VITAL": "Common adult vital-sign references (illustrative defaults)",
 "ECG": "Common adult ECG interval references (PR 120–200 ms, QRS <120 ms, QTc prolonged ≥450 ms men / ≥460 ms women, ≥500 ms high risk)",
 "KDIGO": "KDIGO 2024 Clinical Practice Guideline for the Evaluation and Management of CKD – GFR categories G1 ≥90, G2 60–89, G3a 45–59, G3b 30–44, G4 15–29, G5 <15 mL/min/1.73 m²; eGFR by the race-free CKD-EPI 2021 creatinine equation (Inker LA et al., NEJM 2021)",
 "BIS": "Manufacturer guidance – BIS 40–60 general-anaesthesia target (treatment range, not a reference interval)",
}
A = lambda f, t: {"ageFrom": f, "ageTo": t}
def rg(f, t, low=None, high=None, cl=None, ch=None, sex="any", **kw):
    d = {"ageFrom": f, "ageTo": t, "sex": sex}
    for k, v in (("low", low), ("high", high), ("criticalLow", cl), ("criticalHigh", ch)):
        if v is not None: d[k] = v
    d.update(kw); return d

KB = [
 dict(id="heart-rate", name="Heart rate", unit="/min", match={"loinc": ["8867-4", "40443-4"], "fieldIds": ["hr", "pulse", "mhr", "resting_hr"]}, source="PALS",
      ranges=[rg(0, 28/365, 100, 205), rg(28/365, 1, 100, 190), rg(1, 3, 98, 140), rg(3, 6, 80, 120), rg(6, 13, 75, 118), rg(13, 18, 60, 100), rg(18, 200, 60, 100, 40, 150, note="adult critical limits: demo action threshold")]),
 dict(id="resp-rate", name="Respiratory rate", unit="/min", match={"loinc": ["9279-1"], "fieldIds": ["rr"]}, source="PALS",
      ranges=[rg(0, 28/365, 30, 60), rg(28/365, 1, 30, 53), rg(1, 3, 22, 37), rg(3, 6, 20, 28), rg(6, 13, 18, 25), rg(13, 18, 12, 20), rg(18, 200, 12, 20, 8, 30, note="adult critical limits: demo action threshold")]),
 dict(id="spo2", name="Oxygen saturation (SpO2)", unit="%", match={"loinc": ["59408-5", "2708-6"], "fieldIds": ["spo2"]}, source="VITAL",
      ranges=[rg(0, 200, 95, 100, 90, None)], modifiers=[{"param": "siteAltitudeM", "gte": 2500, "set": {"low": 90, "criticalLow": 85}, "note": "high-altitude site (≥2,500 m): lower limit reduced – configure per site"}]),
 dict(id="systolic", name="Systolic blood pressure", unit="mm[Hg]", match={"loinc": ["8480-6"], "fieldIds": ["nibp_sys", "sys", "m_sys"]}, source="PALS / ACCAHA",
      ranges=[rg(0, 28/365, 67, 84), rg(28/365, 1, 72, 104), rg(1, 3, 86, 106), rg(3, 6, 89, 112), rg(6, 13, 97, 120), rg(13, 18, 110, 131),
              rg(18, 200, 90, 119, 70, 180, categories=[["Normal", None, 120], ["Elevated", 120, 130], ["Hypertension stage 1", 130, 140], ["Hypertension stage 2", 140, 181], ["Hypertensive crisis", 181, None]], note="adult: ACC/AHA 2017 categories; paediatric: PALS 50th height percentile ranges")]),
 dict(id="diastolic", name="Diastolic blood pressure", unit="mm[Hg]", match={"loinc": ["8462-4"], "fieldIds": ["nibp_dia", "dia", "m_dia"]}, source="PALS / ACCAHA",
      ranges=[rg(0, 28/365, 35, 53), rg(28/365, 1, 37, 56), rg(1, 3, 42, 63), rg(3, 6, 46, 72), rg(6, 13, 57, 80), rg(13, 18, 64, 83),
              rg(18, 200, 60, 79, 40, 120, categories=[["Normal", None, 80], ["Hypertension stage 1", 80, 90], ["Hypertension stage 2", 90, 121], ["Hypertensive crisis", 121, None]])]),
 dict(id="body-temp", name="Body temperature", unit="Cel", match={"loinc": ["8310-5"], "fieldIds": ["temp"]}, source="VITAL",
      ranges=[rg(0, 200, 36.0, 37.5, 35.0, 40.0, categories=[["Hypothermia", None, 35.0], ["Normal", 35.0, 37.6], ["Raised", 37.6, 38.0], ["Fever", 38.0, 40.0], ["Hyperpyrexia", 40.0, None]])]),
 dict(id="neonatal-skin-temp", name="Neonatal skin temperature", unit="Cel", match={"local": ["INTIMED-NEO-SKINT"], "fieldIds": ["skin_temp"]}, source="WHONEO",
      ranges=[rg(0, 1, 36.5, 37.5, 35.5, 38.5)], gapNote="incubator/warmer skin temperature interval defined for infants < 1 y only"),
 dict(id="etco2", name="End-tidal CO2", unit="mm[Hg]", match={"mdc": ["MDC_AWAY_CO2_ET"], "fieldIds": ["etco2"]}, source="VITAL", ranges=[rg(0, 200, 35, 45, 20, 60, note="critical: demo action threshold")]),
 dict(id="glucose", name="Glucose", unit="mmol/L", match={"loinc": ["15074-8", "14749-6", "2339-0", "2345-7"], "fieldIds": ["glucose"]}, source="ADA",
      ranges=[rg(0, 200, 3.9, 7.8, 3.0, 22.2, note="non-fasting / random; HH: demo action threshold")], delta={"pct": 50, "windowHours": 24},
      modifiers=[{"param": "fasting", "eq": True, "set": {"high": 5.5}, "note": "fasting: 3.9–5.5 mmol/L"}]),
 dict(id="ph-arterial", name="pH (arterial)", unit="[pH]", match={"loinc": ["2744-1"], "fieldIds": ["ph"]}, source="LAB", ranges=[rg(0, 200, 7.35, 7.45, 7.20, 7.60)]),
 dict(id="pco2", name="pCO2 (arterial)", unit="mm[Hg]", match={"loinc": ["2019-8"], "fieldIds": ["pco2"]}, source="LAB", ranges=[rg(0, 200, 35, 45, 20, 70)]),
 dict(id="po2", name="pO2 (arterial)", unit="mm[Hg]", match={"loinc": ["2703-7"], "fieldIds": ["po2"]}, source="LAB", ranges=[rg(0, 65, 80, 100, 60, None), rg(65, 200, 70, 100, 60, None)]),
 dict(id="sodium", name="Sodium", unit="mmol/L", match={"loinc": ["2947-0", "2951-2"], "fieldIds": ["na"]}, source="LAB", ranges=[rg(0, 200, 135, 145, 120, 160)], delta={"abs": 8, "windowHours": 48}),
 dict(id="potassium", name="Potassium", unit="mmol/L", match={"loinc": ["6298-4", "2823-3"], "fieldIds": ["k"]}, source="LAB", ranges=[rg(18, 200, 3.5, 5.1, 2.8, 6.2)], gapNote="paediatric potassium: laboratory-specific interval required", delta={"abs": 1.0, "windowHours": 48}),
 dict(id="lactate", name="Lactate", unit="mmol/L", match={"loinc": ["2518-9", "2524-7"], "fieldIds": ["lactate"]}, source="LAB", ranges=[rg(0, 200, 0.5, 2.0, None, 4.0)]),
 dict(id="haemoglobin", name="Haemoglobin", unit="g/dL", match={"loinc": ["718-7"], "fieldIds": ["hb"]}, source="WHOHB",
      ranges=[rg(0.5, 2, 10.5, 14.5, 7.0, None), rg(2, 5, 11.0, 14.5, 7.0, None), rg(5, 12, 11.5, 15.5, 7.0, None), rg(12, 15, 12.0, 16.0, 7.0, None),
              rg(15, 200, 12.0, 15.5, 7.0, None, sex="female"), rg(15, 200, 13.0, 17.5, 7.0, None, sex="male"),
              rg(15, 60, 11.0, 15.5, 7.0, None, sex="female", pregnant=True, trimester=1), rg(15, 60, 10.5, 15.5, 7.0, None, sex="female", pregnant=True, trimester=2),
              rg(15, 60, 11.0, 15.5, 7.0, None, sex="female", pregnant=True, trimester=3)], gapNote="infants <6 months: laboratory-specific interval required", delta={"abs": 2.0, "windowHours": 72}),
 dict(id="creatinine", name="Creatinine", unit="umol/L", match={"loinc": ["14682-9"], "fieldIds": ["creatinine"]}, source="LAB",
      ranges=[rg(18, 200, 62, 106, sex="male"), rg(18, 200, 44, 80, sex="female")], gapNote="paediatric creatinine: age-partitioned laboratory interval required (e.g. CALIPER)", delta={"pct": 50, "windowHours": 168}),
 dict(id="egfr", name="eGFR (CKD-EPI 2021, race-free)", unit="mL/min/{1.73_m2}", match={"loinc": ["98979-8", "62238-1"], "fieldIds": ["egfr"]}, source="KDIGO", derived=True,
      ranges=[rg(18, 200, 60, None, 15, None, categories=[["G5 kidney failure", None, 15], ["G4 severely decreased", 15, 30], ["G3b moderately to severely decreased", 30, 45], ["G3a mildly to moderately decreased", 45, 60], ["G2 mildly decreased", 60, 90], ["G1 normal or high", 90, None]],
                 note="derived value (IM-E14-BL-010) – G1/G2 are not CKD without markers of kidney damage; LL below 15: demo action threshold")],
      gapNote="CKD-EPI 2021 is validated for adults ≥18 y; paediatric eGFR needs the bedside Schwartz equation (height)"),
 dict(id="hba1c", name="HbA1c", unit="%", match={"loinc": ["4548-4"], "fieldIds": ["hba1c"]}, source="ADA",
      ranges=[rg(0, 200, 4.0, 5.6, categories=[["Normal", None, 5.7], ["Prediabetes", 5.7, 6.5], ["Diabetes range", 6.5, None]])]),
 dict(id="cholesterol", name="Total cholesterol", unit="mmol/L", match={"loinc": ["14647-2", "2093-3"], "fieldIds": ["chol"]}, source="LAB",
      ranges=[rg(18, 200, None, 5.1, categories=[["Desirable", None, 5.2], ["Borderline high", 5.2, 6.2], ["High", 6.2, None]])]),
 dict(id="alt", name="ALT", unit="U/L", match={"loinc": ["1742-6"], "fieldIds": ["alt"]}, source="LAB", ranges=[rg(18, 200, 7, 55, sex="male"), rg(18, 200, 7, 45, sex="female")]),
 dict(id="wbc", name="Leukocytes", unit="10*9/L", match={"loinc": ["6690-2"], "fieldIds": ["wbc"]}, source="LAB", ranges=[rg(18, 200, 4.0, 11.0, 2.0, 30.0)]),
 dict(id="pr-interval", name="PR interval", unit="ms", match={"loinc": ["8625-6"], "fieldIds": ["pr"]}, source="ECG", ranges=[rg(18, 200, 120, 200)]),
 dict(id="qrs-duration", name="QRS duration", unit="ms", match={"loinc": ["8633-0"], "fieldIds": ["qrs"]}, source="ECG", ranges=[rg(18, 200, 60, 119)]),
 dict(id="qtc", name="QTc interval", unit="ms", match={"loinc": ["8636-3"], "fieldIds": ["qtc"]}, source="ECG",
      ranges=[rg(18, 200, 350, 449, None, 500, sex="male"), rg(18, 200, 350, 459, None, 500, sex="female")]),
 dict(id="qrs-axis", name="QRS axis", unit="deg", match={"loinc": ["8632-2"], "fieldIds": ["axis"]}, source="ECG", ranges=[rg(18, 200, -30, 90)]),
 dict(id="fetal-hr", name="Fetal heart rate", unit="/min", match={"loinc": ["55283-6"], "fieldIds": ["fhr"]}, source="FIGO", ranges=[rg(0, 200, 110, 160, 100, 180)], subject="fetus (baseline)"),
 dict(id="fev1-fvc", name="FEV1/FVC", unit="%", match={"loinc": ["19926-5"], "fieldIds": ["ratio"]}, source="GOLD", ranges=[rg(18, 200, 70, 100)], gapNote="GLI-2012 LLN not bundled – fixed ratio used for adults"),
 dict(id="hearing-threshold", name="Hearing threshold (pure tone)", unit="dB", match={"localPrefix": ["INTIMED-AUD-R", "INTIMED-AUD-L"], "fieldIds": []}, source="WHOHEAR",
      ranges=[rg(18, 200, -10, 19, None, None, categories=[["Normal", None, 20], ["Mild", 20, 35], ["Moderate", 35, 50], ["Moderately severe", 50, 65], ["Severe", 65, 80], ["Profound", 80, 95], ["Complete", 95, None]],
                 note="per-frequency application of better-ear epidemiological grades – approximation for screening display")]),
 dict(id="brac", name="Breath alcohol concentration", unit="g/210L", match={"local": ["INTIMED-BRAC"], "fieldIds": ["brac"]}, source="DOT",
      ranges=[rg(0, 200, 0, 0.019, None, 0.04, categories=[["Negative", None, 0.02], ["0.02–0.039: no safety-sensitive functions for ≥24 h", 0.02, 0.04], ["≥0.04: regulatory violation", 0.04, None]])],
      context={"param": "regulation", "values": ["US-DOT-FMCSA"], "note": "occupational regulatory thresholds – apply only where the employer programme is subject to them"}),
 dict(id="ahi", name="Apnoea–hypopnoea index", unit="/h", match={"local": ["INTIMED-PSG-AHI"], "fieldIds": ["ahi"]}, source="AASM",
      ranges=[rg(0, 18, 0, 1), rg(18, 200, 0, 4.9, categories=[["Normal", None, 5], ["Mild OSA", 5, 15], ["Moderate OSA", 15, 30], ["Severe OSA", 30, None]])]),
 dict(id="ktv", name="Kt/V", unit="1", match={"local": ["INTIMED-DIA-KTV"], "fieldIds": ["ktv"]}, source="KDOQI", ranges=[rg(0, 200, 1.2, None)], type="treatment"),
 dict(id="fit-factor", name="Respirator fit factor", unit="1", match={"local": ["INTIMED-FIT-FF"], "fieldIds": ["fit_factor"]}, source="OSHA", ranges=[rg(0, 200, 100, None)],
      modifiers=[{"param": "respiratorType", "eq": "FULL_FACE", "set": {"low": 500}, "note": "full-facepiece: ≥500"}]),
 dict(id="bis", name="Bispectral index", unit="1", match={"mdc": ["MDC_EEG_BISPECTRAL_INDEX"], "fieldIds": ["bis"]}, source="BIS", ranges=[rg(0, 200, 40, 60)], type="treatment"),
]

CATEGORIES = [
 dict(id="ACUTE_EHR", name="Hospital EHR – acute care (ICU, ED, OR, wards)", hosts="Hospital EHRs, anaesthesia information systems, flowsheets",
      templates=["TPL-01", "TPL-02", "TPL-03", "TPL-04", "TPL-05", "TPL-16"], outputs=[{"format": "FHIR R4", "profile": "http://hl7.org/fhir/StructureDefinition/vitalsigns"}, {"format": "HL7 v2.6", "profile": "IHE PCD-01 ORU^R01 (OBX-7 range, OBX-8 flag)"}],
      rules=["STRUCTURE", "TERMINOLOGY", "UNITS", "CLINICAL_RANGE", "CROSS_FIELD", "PLAUSIBILITY"], terminology={"requireStandardCode": False}, demographics=["age", "sex"], interpretation="flag", criticalHandling="flagged LL/HH for the host's alarm/notification workflow – IntiMed raises no alarms"),
 dict(id="PRIMARY_CARE", name="Primary care / outpatient EHR", hosts="GP and outpatient EHRs, IPS-based records",
      templates=["TPL-02", "TPL-08", "TPL-13"], outputs=[{"format": "FHIR R4", "profile": "http://hl7.org/fhir/us/core/StructureDefinition/us-core-vital-signs"}, {"format": "FHIR R4", "profile": "IPS Observation (results)"}],
      rules=["STRUCTURE", "TERMINOLOGY", "UNITS", "CLINICAL_RANGE", "CROSS_FIELD", "PLAUSIBILITY", "CATEGORY_GRADES"], terminology={"requireStandardCode": True}, demographics=["age", "sex"], interpretation="flag+grade", criticalHandling="flag"),
 dict(id="OCC_HEALTH", name="Occupational health platform (e.g. OHCM)", hosts="Occupational-health case management, surveillance and fitness-for-duty systems",
      templates=["TPL-17", "TPL-18", "TPL-19", "TPL-20", "TPL-21", "TPL-22"], outputs=[{"format": "FHIR R4", "profile": "Observation + DiagnosticReport (OH)"}, {"format": "REST/PDF", "profile": "OH form post-back with grade and regulation"}],
      rules=["STRUCTURE", "UNITS", "CLINICAL_RANGE", "CROSS_FIELD", "CATEGORY_GRADES", "REGULATORY"], terminology={"requireStandardCode": False}, demographics=["age", "sex"], interpretation="grade+regulatory", criticalHandling="regulatory outcome (e.g. DOT removal from duty) reported as status-only to HR via the host",
      parameters={"regulation": "US-DOT-FMCSA", "respiratorType": "HALF_MASK"}),
 dict(id="LIS", name="Laboratory information system / POCT", hosts="LIS, POCT data managers, middleware",
      templates=["TPL-05", "TPL-06"], outputs=[{"format": "HL7 v2.5.1/2.6", "profile": "ORU^R01 with OBX-7 reference range and OBX-8 abnormal flag (LAW)"}, {"format": "FHIR R4", "profile": "http://hl7.org/fhir/us/core/StructureDefinition/us-core-observation-lab"}],
      rules=["STRUCTURE", "TERMINOLOGY", "UNITS", "CLINICAL_RANGE", "LAB_RANGE_REQUIRED", "CROSS_FIELD"], terminology={"requireStandardCode": True}, demographics=["age", "sex", "pregnancy"], interpretation="flag", criticalHandling="LL/HH flagged for the laboratory critical-result call process"),
 dict(id="IMAGING_CARDIO", name="Imaging & cardiology systems", hosts="PACS/RIS, ECG management, device-clinic systems",
      templates=["TPL-07", "TPL-08", "TPL-09"], outputs=[{"format": "FHIR R4", "profile": "ImagingStudy / DiagnosticReport / Observation"}, {"format": "HL7 v2", "profile": "IHE IDCO ORU"}],
      rules=["STRUCTURE", "UNITS", "CLINICAL_RANGE", "DOSE_REFERENCE", "CROSS_FIELD"], terminology={"requireStandardCode": False}, demographics=["age", "sex"], interpretation="flag", criticalHandling="flag",
      parameters={"drlCtDlp": 600}),
 dict(id="RPM_HOME", name="Remote patient monitoring / hospital at home", hosts="RPM dashboards, chronic-care and virtual-ward platforms",
      templates=["TPL-13", "TPL-14", "TPL-22"], outputs=[{"format": "FHIR R4", "profile": "Personal Health Device (PHD) Observation"}, {"format": "REST", "profile": "dashboard post-back"}],
      rules=["STRUCTURE", "UNITS", "CLINICAL_RANGE", "CROSS_FIELD", "CONSENT"], terminology={"requireStandardCode": False}, demographics=["age", "sex"], interpretation="flag+care-plan", criticalHandling="flagged for the care team's escalation protocol in the host"),
 dict(id="SPECIALIST", name="Specialist unit systems (dialysis, neonatal, maternity, sleep)", hosts="Dialysis TDMS, neonatal/maternity systems, sleep lab software",
      templates=["TPL-10", "TPL-11", "TPL-12", "TPL-15"], outputs=[{"format": "FHIR R4", "profile": "Observation / DiagnosticReport"}, {"format": "HL7 v2", "profile": "ORU^R01"}],
      rules=["STRUCTURE", "UNITS", "CLINICAL_RANGE", "CROSS_FIELD", "CATEGORY_GRADES"], terminology={"requireStandardCode": False}, demographics=["age", "sex", "pregnancy"], interpretation="flag+grade", criticalHandling="flag"),
 dict(id="WELLNESS", name="Wellness / consumer health", hosts="Wellness programmes, employee health apps",
      templates=["TPL-22", "TPL-13"], outputs=[{"format": "FHIR R4", "profile": "PHD Observation"}],
      rules=["STRUCTURE", "UNITS", "CONSENT"], terminology={"requireStandardCode": False}, demographics=["age"], interpretation="informational", criticalHandling="none – informational display only"),
]

TYPES = [
 # code, name, IEEE 11073 PHD specialization (where standardised), data class, keyword regex, default families
 ("AED_DEFIB", "Defibrillator / AED (readiness and event data)", None, "EVENT", r"heartstart|\baed\b|defibrill|lifepak|plustrac|zoll"),
 ("AMBULATORY_ECG_EARLY", "Ambulatory ECG / Holter / patch", None, "CONTINUOUS", r"holter|zio|mcot|ecg patch|event recorder"),
 ("SPOT_VITALS_MONITOR", "Spot-check vital signs monitor", None, "SPOT", r"spot|connex|vs-?9|vs 600|accutorr|dinamap|carescape v100|vital signs monitor"),
 ("MULTIPARAM_MONITOR", "Multiparameter patient monitor / telemetry", None, "CONTINUOUS", r"intellivue|carescape b|carescape one|carescape monitor|telemetry|bedside monitor|benevision|epm \d|infinity|nihon kohden|life scope|spacelabs|masimo root|\biris\b|mx\d{3}"),
 ("BP_MONITOR", "Blood pressure monitor (NIBP)", "IEEE 11073-10407", "SPOT", r"\bbp\b|blood pressure|ua-6|bpm connect|sphygmo|abpm|omron"),
 ("PULSE_OXIMETER", "Pulse oximeter", "IEEE 11073-10404", "SPOT", r"oximet|spo2|rad-|nonin|3230|onyx"),
 ("THERMOMETER", "Thermometer", "IEEE 11073-10408", "SPOT", r"thermo|temp(erature)? "),
 ("WEIGHING_SCALE", "Weighing scale / body composition", "IEEE 11073-10415", "SPOT", r"scale|seca|tanita|body comp|weight"),
 ("GLUCOSE_METER", "Glucose meter / CGM", "IEEE 11073-10417", "SPOT", r"gluco|cgm|libre|dexcom|accu-chek|statstrip"),
 ("SPIROMETER", "Spirometer / lung function", None, "SPOT", r"spiro|easyone|lung|feno|niox|peak flow|pft|vyntus|ndd"),
 ("AUDIOMETER", "Audiometer / hearing screener", None, "SPOT", r"audio|hearing|tympan|oae|benson|tremetrics|cca-|ra\d{3}|algo|otoport"),
 ("ECG", "Electrocardiograph / ECG management", None, "SPOT", r"ecg|ekg|electrocardio|mac ?\d|pagewriter|eli |muse|tracemaster|cardiograph"),
 ("AMBULATORY_ECG", "Ambulatory ECG / Holter / patch", None, "CONTINUOUS", r"holter|zio|mcot|patch|event recorder"),
 ("VISION_SCREENER", "Vision / colour-vision screener", None, "SPOT", r"vision|optec|titmus|snellen|ishihara|keystone|acuity|logmar|perimet|ophthal|retina|fundus|slit"),
 ("BREATH_ALCOHOL", "Evidential breath-alcohol tester", None, "SPOT", r"breath|alcohol|lifeloc|intoxim|alcotest|alco"),
 ("DRUG_TEST", "Drug test analyser / collection", None, "SPOT", r"drug ?test|dds2|oral fluid|urine drug|toxic"),
 ("POC_ANALYSER", "Point-of-care analyser", None, "SPOT", r"i-stat|afinion|hemocue|cholestech|dca|cobas b|clinitek|sofia|id now|leadcare|poc"),
 ("BLOOD_GAS", "Blood gas analyser", None, "SPOT", r"blood gas|abl\d|gem premier|rapidpoint|epoc"),
 ("LAB_ANALYSER", "Laboratory analyser / middleware", None, "SPOT", r"cobas pro|cobas 8000|alinity|atellica|architect|middleware|instrument manager|infinity|lis|analy[sz]er"),
 ("INFUSION_PUMP", "Infusion pump / pump server", None, "CONTINUOUS", r"infusion|alaris|plum|spectrum|sigma|space|agilia|pca|syringe|pump"),
 ("VENTILATOR", "Ventilator", None, "CONTINUOUS", r"ventilat|hamilton|evita|servo|puritan|pb ?980|v60|trilogy|babylog"),
 ("ANAESTHESIA_WORKSTATION", "Anaesthesia workstation", None, "CONTINUOUS", r"anaesth|anesth|aisys|perseus|atlan|carestation|a7|a9"),
 ("CAPNOGRAPH_BIS", "Capnography / depth-of-anaesthesia / haemodynamics", None, "CONTINUOUS", r"capno|bis\b|hemosphere|haemodyn|cardiac output|icp"),
 ("IMAGING_MODALITY", "Imaging modality (CT, MR, DR, US)", None, "STUDY", r"\bct\b|somatom|revolution|mri|magnetom|signa|x-?ray|radiograph|ultrasound|dr\b|mammo|c-arm|fluoro"),
 ("PACS_VNA", "PACS / VNA / imaging archive", None, "STUDY", r"pacs|vna|enterprise imaging|sectra|vue"),
 ("ECHO", "Echocardiography system", None, "STUDY", r"echo|epiq|vivid"),
 ("IMPLANT_REMOTE", "Implant remote-monitoring portal (IDCO)", None, "SPOT", r"carelink|paceart|latitude|merlin|biotronik|implant|icd|pacemaker"),
 ("DIALYSIS", "Dialysis system", None, "SESSION", r"dialys|5008|6008|2008|artis|tdms|kinexus|sharesource"),
 ("ECMO_MCS", "ECMO / mechanical circulatory support", None, "CONTINUOUS", r"ecmo|cardiohelp|impella|lvad|mcs"),
 ("NEONATAL_INCUBATOR", "Incubator / warmer / neonatal device", None, "CONTINUOUS", r"incubat|warmer|giraffe|babyleo|isolette|bilirubin|panda|cot"),
 ("FETAL_MONITOR", "Fetal monitor (CTG)", None, "CONTINUOUS", r"fetal|ctg|avalon|corometrics"),
 ("RPM_KIT", "RPM kit / cellular home device", None, "SPOT", r"rpm|bodytrace|tenovi|smart meter|cellular|home kit"),
 ("HOSPITAL_AT_HOME", "Hospital-at-home monitoring platform", None, "CONTINUOUS", r"safetynet|hospital-at-home|biofourmis|current health|radius"),
 ("WEARABLE", "Consumer wearable / aggregator", None, "DAILY", r"garmin|apple watch|fitbit|oura|whoop|wearable|aggregator|healthkit|health connect"),
 ("CPAP_SLEEP", "CPAP / sleep therapy", None, "DAILY", r"cpap|airsense|airview|dreamstation|sleep therapy"),
 ("PSG_EEG", "Polysomnography / EEG / neurophysiology", None, "STUDY", r"polysom|psg|eeg|embla|nox|alice|nicolet|emg"),
 ("PERFUSION_OR", "Perfusion / OR integration / surgical device", None, "SESSION", r"perfusion|heart-lung|system 1|s5|cell saver|navigation|da vinci|energy|electrosurg|steril"),
 ("FIT_TESTER", "Respirator fit tester", None, "SPOT", r"portacount|quantifit|fit test|fittester"),
 ("EXPOSURE_MONITOR", "Exposure monitor / dosimeter / environment", None, "DAILY", r"dosimet|noise|dbadge|gas detector|wbgt|heat stress|exposure|radiation badge|particulate"),
 ("DYNAMOMETER_ERGO", "Dynamometer / ergonomics / functional capacity", None, "SPOT", r"dynamom|jamar|grip|ergonom|functional capacity|e-link|pinch"),
 ("SMART_BED_ENV", "Smart bed / location / environment", None, "CONTINUOUS", r"bed|rtls|location|nurse call"),
]
FAMILY_DEFAULT = {"Cardiology": "ECG", "Vital signs & body composition": "SPOT_VITALS_MONITOR", "Point-of-care laboratory": "POC_ANALYSER", "Spirometry & lung function": "SPIROMETER",
  "Vision & ophthalmic": "VISION_SCREENER", "Acute-care monitoring & telemetry": "MULTIPARAM_MONITOR", "Laboratory analysers & middleware": "LAB_ANALYSER", "Audiometry & hearing": "AUDIOMETER",
  "Drug & alcohol testing": "DRUG_TEST", "Respiratory & anaesthesia": "VENTILATOR", "Imaging & PACS": "IMAGING_MODALITY", "Infusion & medication delivery": "INFUSION_PUMP",
  "Respiratory protection & ergonomics": "FIT_TESTER", "Occupational-health imaging & other instruments": "IMAGING_MODALITY", "Cardiology diagnostics & implant remote monitoring": "IMPLANT_REMOTE",
  "Exposure & environment feeds": "EXPOSURE_MONITOR", "Wearables & personal health": "WEARABLE", "Dialysis & extracorporeal": "DIALYSIS", "Neonatal & maternal": "NEONATAL_INCUBATOR",
  "Remote patient monitoring & home": "RPM_KIT", "Operating room & surgical": "PERFUSION_OR", "Beds, location & environment": "SMART_BED_ENV", "Rehabilitation, neurophysiology & sleep": "PSG_EEG",
  "Specialist outpatient (dental, ophthalmic, ENT)": "VISION_SCREENER"}

PROFILES = [
 # id, name, group, demographics, concept values (template units), expected flags
 ("adult-normal", "Adult male, 40 y – healthy", "normal", dict(ageYears=40, sex="male"), {}, {}),
 ("adult-female-normal", "Adult female, 34 y – healthy", "normal", dict(ageYears=34, sex="female"), {}, {}),
 ("older-adult-normal", "Older adult female, 72 y – healthy", "normal", dict(ageYears=72, sex="female"), {}, {}),
 ("adolescent-normal", "Adolescent male, 15 y – healthy", "age", dict(ageYears=15, sex="male"), {}, {}),
 ("school-child-normal", "School-age child, 8 y – healthy", "age", dict(ageYears=8, sex="female"), {}, {}),
 ("preschool-normal", "Preschool child, 4 y – healthy", "age", dict(ageYears=4, sex="male"), {}, {}),
 ("infant-normal", "Infant, 6 months – healthy", "age", dict(ageYears=0.5, sex="female"), {}, {}),
 ("neonate-normal", "Neonate, 5 days – healthy", "age", dict(ageYears=5 / 365, sex="male"), {}, {}),
 ("infant-hr-140", "Infant, 6 months – heart rate 140/min (normal for age)", "age", dict(ageYears=0.5, sex="male"), {"heart-rate": 140}, {"heart-rate": "N"}),
 ("child-hr-140", "School-age child, 8 y – heart rate 140/min (high for age)", "age", dict(ageYears=8, sex="male"), {"heart-rate": 140}, {"heart-rate": "H"}),
 ("adult-hypertensive-crisis", "Adult male, 58 y – hypertensive crisis", "abnormal", dict(ageYears=58, sex="male"), {"systolic": 186, "diastolic": 112}, {"systolic": "HH", "diastolic": "H"}),
 ("adult-sepsis-like", "Adult female, 64 y – tachycardia, tachypnoea, fever, low SpO2", "abnormal", dict(ageYears=64, sex="female"),
  {"heart-rate": 128, "resp-rate": 26, "body-temp": 38.9, "spo2": 92}, {"heart-rate": "H", "resp-rate": "H", "body-temp": "H", "spo2": "L"}),
 ("copd-hypoxaemia", "Older male, 70 y – COPD with hypoxaemia", "abnormal", dict(ageYears=70, sex="male"), {"spo2": 87, "fev1-fvc": 58}, {"spo2": "LL", "fev1-fvc": "L"}),
 ("altitude-sea-level", "Adult, 30 y at sea level – SpO2 92 %", "environment", dict(ageYears=30, sex="male", siteAltitudeM=0), {"spo2": 92}, {"spo2": "L"}),
 ("altitude-3000m", "Adult, 30 y at 3,000 m – SpO2 92 % (normal for altitude)", "environment", dict(ageYears=30, sex="male", siteAltitudeM=3000), {"spo2": 92}, {"spo2": "N"}),
 ("adult-anaemia-female", "Adult female, 35 y, not pregnant – Hb 10.8 g/dL", "pregnancy", dict(ageYears=35, sex="female"), {"haemoglobin": 10.8}, {"haemoglobin": "L"}),
 ("pregnant-2nd-trimester", "Pregnant female, 29 y, 2nd trimester – Hb 10.8 g/dL (normal for trimester)", "pregnancy", dict(ageYears=29, sex="female", pregnant=True, trimester=2), {"haemoglobin": 10.8}, {"haemoglobin": "N"}),
 ("diabetic-hyperglycaemia", "Adult male, 55 y, fasting – hyperglycaemia, HbA1c 8.1 %", "abnormal", dict(ageYears=55, sex="male", fasting=True), {"glucose": 13.5, "hba1c": 8.1}, {"glucose": "H", "hba1c": "H"}),
 ("hypoglycaemia", "Adult female, 45 y – glucose 2.6 mmol/L", "abnormal", dict(ageYears=45, sex="female"), {"glucose": 2.6}, {"glucose": "LL"}),
 ("hyperkalaemia", "Older male, 67 y – potassium 6.8 mmol/L", "abnormal", dict(ageYears=67, sex="male"), {"potassium": 6.8}, {"potassium": "HH"}),
 ("long-qtc-female", "Adult female, 50 y – QTc 505 ms", "abnormal", dict(ageYears=50, sex="female"), {"qtc": 505}, {"qtc": "HH"}),
 ("borderline-qtc-male", "Adult male, 50 y – QTc 455 ms (prolonged for men)", "sex", dict(ageYears=50, sex="male"), {"qtc": 455}, {"qtc": "H"}),
 ("borderline-qtc-female", "Adult female, 50 y – QTc 455 ms (normal for women)", "sex", dict(ageYears=50, sex="female"), {"qtc": 455}, {"qtc": "N"}),
 ("noise-induced-hearing-loss", "Adult male, 45 y – noise notch at 4 kHz", "occupational", dict(ageYears=45, sex="male"), {"hearing-threshold@r4000": 45, "hearing-threshold@l4000": 40, "hearing-threshold@r6000": 35}, {"hearing-threshold@r4000": "H", "hearing-threshold@l4000": "H", "hearing-threshold@r6000": "H"}),
 ("dot-brac-removal", "Driver, 38 y – BrAC 0.031 (24 h removal)", "occupational", dict(ageYears=38, sex="male", regulation="US-DOT-FMCSA"), {"brac": 0.031}, {"brac": "H"}),
 ("dot-brac-violation", "Driver, 41 y – BrAC 0.052 (violation)", "occupational", dict(ageYears=41, sex="male", regulation="US-DOT-FMCSA"), {"brac": 0.052}, {"brac": "HH"}),
 ("fit-test-fail", "Worker, 30 y – half-mask fit factor 64 (fail)", "occupational", dict(ageYears=30, sex="female", respiratorType="HALF_MASK"), {"fit-factor": 64}, {"fit-factor": "L"}),
 ("severe-osa-adult", "Adult male, 52 y – AHI 34 /h", "abnormal", dict(ageYears=52, sex="male"), {"ahi": 34}, {"ahi": "H"}),
 ("paediatric-osa", "Child, 7 y – AHI 3 /h (abnormal for a child)", "age", dict(ageYears=7, sex="female"), {"ahi": 3}, {"ahi": "H"}),
 ("adult-ahi-3", "Adult, 40 y – AHI 3 /h (normal for an adult)", "age", dict(ageYears=40, sex="female"), {"ahi": 3}, {"ahi": "N"}),
 ("fetal-tachycardia", "Pregnant female, 28 y – fetal heart rate 172", "pregnancy", dict(ageYears=28, sex="female", pregnant=True, trimester=3), {"fetal-hr": 172}, {"fetal-hr": "H"}),
 ("neonatal-hypothermia", "Neonate, 2 days – skin temperature 35.8 °C", "age", dict(ageYears=2 / 365, sex="female"), {"neonatal-skin-temp": 35.8}, {"neonatal-skin-temp": "L"}),
 ("dialysis-inadequate", "Adult male, 61 y – Kt/V 1.05", "abnormal", dict(ageYears=61, sex="male"), {"ktv": 1.05}, {"ktv": "L"}),
]

# Time-series physiology (IM-E13-BL-005): keyframes {minute, concept → value} interpolated piecewise-linearly with seeded
# noise (noisePct, relative SD) and replayed through a driver at an interval. Illustrative engineering scripts – not
# validated physiological models. No expected flags: trajectories report per-step flags and a NEWS2-style aggregate.
TRAJECTORIES = [
 ("traj-sepsis-6h", "Sepsis deterioration over 6 h – adult female, 64 y", dict(ageYears=64, sex="female"), 2.0, [
   (0, {"heart-rate": 92, "resp-rate": 18, "body-temp": 37.6, "spo2": 96, "systolic": 124, "diastolic": 78}),
   (120, {"heart-rate": 108, "resp-rate": 22, "body-temp": 38.4, "spo2": 94, "systolic": 112, "diastolic": 70}),
   (240, {"heart-rate": 122, "resp-rate": 26, "body-temp": 38.9, "spo2": 92, "systolic": 98, "diastolic": 60}),
   (360, {"heart-rate": 134, "resp-rate": 30, "body-temp": 39.3, "spo2": 89, "systolic": 86, "diastolic": 52})]),
 ("traj-post-op-recovery", "Post-operative recovery over 4 h – adult male, 58 y", dict(ageYears=58, sex="male"), 2.0, [
   (0, {"heart-rate": 110, "resp-rate": 22, "body-temp": 35.8, "spo2": 93, "systolic": 98, "diastolic": 60}),
   (60, {"heart-rate": 100, "resp-rate": 20, "body-temp": 36.2, "spo2": 94, "systolic": 108, "diastolic": 66}),
   (240, {"heart-rate": 82, "resp-rate": 16, "body-temp": 36.8, "spo2": 97, "systolic": 122, "diastolic": 76})]),
 ("traj-hypoglycaemia", "Hypoglycaemia episode and treatment over 90 min – adult female, 45 y", dict(ageYears=45, sex="female"), 1.5, [
   (0, {"glucose": 5.2, "heart-rate": 76}), (30, {"glucose": 3.4, "heart-rate": 96}), (45, {"glucose": 2.4, "heart-rate": 108}),
   (60, {"glucose": 3.8, "heart-rate": 92}), (90, {"glucose": 5.6, "heart-rate": 80})]),
]

def assign_type(d):
    text = (d["model"] + " " + d["manufacturer"] + " " + d["use"]).lower()
    for code, name, spec, dc, rx in TYPES:
        if re.search(rx, text, re.I):
            return ("AMBULATORY_ECG" if code == "AMBULATORY_ECG_EARLY" else code), "RULE_MATCH"
    return FAMILY_DEFAULT.get(d["family"], "SMART_BED_ENV"), "FAMILY_DEFAULT"

def main():
    os.makedirs(os.path.join(R, "clinical"), exist_ok=True)
    os.makedirs(os.path.join(R, "simulator"), exist_ok=True)
    json.dump({"version": "2026.11-1", "disclaimer": __doc__.split("IMPORTANT – ")[1].strip(), "sources": SRC,
               "ageBands": [["Neonate", 0, 28 / 365], ["Infant", 28 / 365, 1], ["Toddler", 1, 3], ["Preschool", 3, 6], ["School age", 6, 13], ["Adolescent", 13, 18], ["Adult", 18, 65], ["Older adult", 65, 200]],
               "entries": KB}, open(os.path.join(R, "clinical", "reference-ranges.json"), "w"), indent=1, ensure_ascii=False)
    json.dump(CATEGORIES, open(os.path.join(R, "clinical", "platform-categories.json"), "w"), indent=1, ensure_ascii=False)
    json.dump([dict(id=i, name=n, group=g, demographics=dm, concepts=c, expected=e) for i, n, g, dm, c, e in PROFILES]
              + [dict(id=i, name=n, group="trajectory", demographics=dm, concepts={}, expected={}, noisePct=nz, trajectory=[dict(minute=m, values=v) for m, v in kf])
                 for i, n, dm, nz, kf in TRAJECTORIES],
              open(os.path.join(R, "simulator", "profiles.json"), "w"), indent=1, ensure_ascii=False)
    json.dump([dict(code=c, name=n, ieee11073Specialization=s, dataClass=dc) for c, n, s, dc, _ in TYPES if c != "AMBULATORY_ECG_EARLY"], open(os.path.join(R, "seed", "device-types.json"), "w"), indent=1, ensure_ascii=False)
    devs = json.load(open(os.path.join(R, "seed", "devices.json")))
    from collections import Counter
    c = Counter()
    for d in devs:
        t, how = assign_type(d); d["deviceType"] = t; d["deviceTypeBasis"] = how; d["deviceTypeConfidence"] = 0.85 if how == "RULE_MATCH" else 0.6; c[how] += 1
    json.dump(devs, open(os.path.join(R, "seed", "devices.json"), "w"), indent=1, ensure_ascii=False)
    print("ranges", len(KB), "categories", len(CATEGORIES), "profiles", len(PROFILES), "trajectories", len(TRAJECTORIES), "types", len(TYPES), dict(c), Counter(d["deviceType"] for d in devs).most_common(8))

if __name__ == "__main__":
    main()
