"""Reference Template Definitions TPL-01..TPL-22 (solution architecture section 9).
Codes: LOINC where the code is well established; otherwise a flagged local code (INTIMED-*) that configuration
intelligence (RR-10) highlights for clinical-informatics mapping. Field tuple:
(id, label, code_system, code, unit, type, mandatory, min, max, decimals)"""

def F(i, label, sys, code, unit=None, typ="quantity", mand=False, mn=None, mx=None, dec=None, verify=False):
    f = {"id": i, "label": label, "type": typ, "mandatory": mand}
    if sys:
        f["code"] = {sys: code}
    if unit:
        f["unit"] = unit
    if mn is not None or mx is not None:
        f["range"] = {k: v for k, v in (("min", mn), ("max", mx)) if v is not None}
    if dec is not None:
        f["decimals"] = dec
    if verify:
        f["verify"] = True
    return f

HR = lambda m=True: F("hr", "Heart rate", "loinc", "8867-4", "/min", mand=m, mn=20, mx=250, dec=0)
SPO2 = lambda m=True: F("spo2", "SpO2 (pulse oximetry)", "loinc", "59408-5", "%", mand=m, mn=50, mx=100, dec=0)
SYS = lambda m=True, i="nibp_sys": F(i, "Systolic blood pressure", "loinc", "8480-6", "mm[Hg]", mand=m, mn=50, mx=260, dec=0)
DIA = lambda m=True, i="nibp_dia": F(i, "Diastolic blood pressure", "loinc", "8462-4", "mm[Hg]", mand=m, mn=25, mx=160, dec=0)
RR = lambda m=True: F("rr", "Respiratory rate", "loinc", "9279-1", "/min", mand=m, mn=4, mx=70, dec=0)
TEMP = lambda m=True: F("temp", "Body temperature", "loinc", "8310-5", "Cel", mand=m, mn=30, mx=43, dec=1)

T = [
 dict(id="TPL-01", name="ICU / ward interval flowsheet", settings=["ACUTE"], captureMode="CONTINUOUS", association="ENCOUNTER_BED",
      adapters=["FHIR", "HL7V2"], sections=[("vitals", "Interval vital signs", [HR(), SPO2(), SYS(False), DIA(False),
      F("nibp_mean", "Mean arterial pressure", "loinc", "8478-0", "mm[Hg]", dec=0), RR(), F("etco2", "End-tidal CO2", "mdc", "MDC_AWAY_CO2_ET", "mm[Hg]", mn=0, mx=100, dec=0), TEMP(False)])]),
 dict(id="TPL-02", name="ED triage / vital signs set", settings=["ACUTE", "OPD"], captureMode="SPOT", association="WORKLIST",
      adapters=["FHIR", "HL7V2", "REST"], sections=[("triage", "Triage vital signs", [HR(), SPO2(), SYS(), DIA(), RR(False), TEMP(),
      F("weight", "Body weight", "loinc", "29463-7", "kg", mn=0.5, mx=350, dec=1)])]),
 dict(id="TPL-03", name="Anaesthesia record (AIMS)", settings=["ACUTE"], captureMode="CONTINUOUS", association="SESSION",
      adapters=["FHIR", "HL7V2"], sections=[("gas", "Gases & ventilation", [F("etco2", "End-tidal CO2", "mdc", "MDC_AWAY_CO2_ET", "mm[Hg]", mand=True, mn=0, mx=100, dec=0),
      F("fio2", "Inspired O2 fraction", "loinc", "3150-0", "%", mand=True, dec=0), F("et_sevo", "End-tidal sevoflurane", "mdc", "MDC_CONC_AWAY_SEVOFL_ET", "%", dec=1),
      F("mac", "MAC", "local", "INTIMED-MAC", "1", dec=2), F("tv", "Tidal volume (expired)", "mdc", "MDC_VOL_AWAY_TIDAL_EXP", "mL", dec=0),
      F("peep", "PEEP", "mdc", "MDC_VENT_PRESS_AWAY_END_EXP_POS", "cm[H2O]", dec=0)]),
      ("haemo", "Haemodynamics & depth", [HR(), SYS(False), DIA(False), F("bis", "Bispectral index", "mdc", "MDC_EEG_BISPECTRAL_INDEX", "1", dec=0)])]),
 dict(id="TPL-04", name="Infusion / medication administration record", settings=["ACUTE"], captureMode="SPOT", association="ENCOUNTER_BED",
      adapters=["FHIR", "HL7V2"], bidirectional=True, sections=[("infusion", "Infusion", [F("drug", "Drug", "local", "INTIMED-DRUG", typ="text", mand=True),
      F("rate", "Infusion rate", "mdc", "MDC_FLOW_FLUID_PUMP", "mL/h", mand=True, mn=0, mx=1200, dec=1),
      F("vtbi", "Volume to be infused", "mdc", "MDC_VOL_FLUID_TBI", "mL", mand=True, dec=0),
      F("infused", "Volume infused", "mdc", "MDC_VOL_FLUID_DELIV", "mL", dec=0),
      F("pump_state", "Pump state", "local", "INTIMED-PUMP-STATE", typ="text"),
      F("program_confirmed", "Auto-programming confirmed", "local", "INTIMED-AUTOPROG", typ="text")])]),
 dict(id="TPL-05", name="Blood gas & POC result", settings=["ACUTE", "LAB"], captureMode="SPOT", association="WORKLIST",
      adapters=["FHIR", "HL7V2"], sections=[("bg", "Blood gas", [F("ph", "pH (arterial)", "loinc", "2744-1", "[pH]", mand=True, mn=6.5, mx=8, dec=2),
      F("pco2", "pCO2 (arterial)", "loinc", "2019-8", "mm[Hg]", mand=True, dec=1), F("po2", "pO2 (arterial)", "loinc", "2703-7", "mm[Hg]", mand=True, dec=1),
      F("na", "Sodium (blood)", "loinc", "2947-0", "mmol/L", dec=0), F("k", "Potassium (blood)", "loinc", "6298-4", "mmol/L", dec=1),
      F("lactate", "Lactate (arterial)", "loinc", "2518-9", "mmol/L", dec=1), F("glucose", "Glucose (blood)", "loinc", "15074-8", "mmol/L", dec=1),
      F("hb", "Haemoglobin", "loinc", "718-7", "g/dL", dec=1), F("lot", "Cartridge / reagent lot", "local", "INTIMED-LOT", typ="text", mand=True)])]),
 dict(id="TPL-06", name="Laboratory result report", settings=["LAB"], captureMode="SPOT", association="WORKLIST",
      adapters=["FHIR", "HL7V2", "PDF"], sections=[("chem", "Chemistry & haematology", [F("glucose", "Glucose (serum)", "loinc", "14749-6", "mmol/L", mand=True, mn=0.5, mx=50, dec=1),
      F("creatinine", "Creatinine (serum)", "loinc", "14682-9", "umol/L", mn=10, mx=3000, dec=0), F("hba1c", "HbA1c", "loinc", "4548-4", "%", dec=1),
      F("chol", "Cholesterol (serum)", "loinc", "14647-2", "mmol/L", dec=1), F("alt", "ALT", "loinc", "1742-6", "U/L", dec=0),
      F("wbc", "Leukocytes", "loinc", "6690-2", "10*9/L", dec=1), F("specimen", "Specimen", "local", "INTIMED-SPECIMEN", typ="text"),
      F("analyser", "Analyser", "local", "INTIMED-ANALYSER", typ="text")])]),
 dict(id="TPL-07", name="Imaging order / report link & dose record", settings=["IMG"], captureMode="SPOT", association="WORKLIST",
      adapters=["FHIR", "REST"], sections=[("study", "Study & dose", [F("accession", "Accession number", "local", "INTIMED-ACCESSION", typ="text", mand=True),
      F("study_uid", "Study Instance UID", "local", "INTIMED-STUDY-UID", typ="text", mand=True), F("modality", "Modality", "local", "INTIMED-MODALITY", typ="text", mand=True),
      F("body_part", "Body part", "local", "INTIMED-BODY-PART", typ="text"), F("dlp", "CT dose-length product (total)", "local", "DCM-113813", "mGy.cm", mand=True, mn=0, mx=20000, dec=1),
      F("ctdivol", "Mean CTDIvol", "local", "DCM-113830", "mGy", mn=0, mx=500, dec=2)])]),
 dict(id="TPL-08", name="Echo / ECG structured report", settings=["IMG", "ACUTE", "OPD"], captureMode="SPOT", association="WORKLIST",
      adapters=["FHIR", "PDF"], sections=[("ecg", "12-lead ECG measurements (interpretation unverified)", [F("hr", "Ventricular rate", "loinc", "8867-4", "/min", mand=True, mn=20, mx=300, dec=0),
      F("pr", "PR interval", "loinc", "8625-6", "ms", mn=50, mx=500, dec=0), F("qrs", "QRS duration", "loinc", "8633-0", "ms", mand=True, mn=40, mx=300, dec=0),
      F("qt", "QT interval", "loinc", "8634-8", "ms", mn=200, mx=700, dec=0), F("qtc", "QTc interval", "loinc", "8636-3", "ms", mand=True, mn=200, mx=700, dec=0),
      F("axis", "QRS axis", "loinc", "8632-2", "deg", dec=0), F("interpretation", "Device interpretation (unverified – physician sign-off)", "local", "INTIMED-ECG-INTERP", typ="text", verify=True)])]),
 dict(id="TPL-09", name="Device clinic remote-monitoring report (IDCO)", settings=["OPD", "RPM"], captureMode="SPOT", association="MATCH",
      adapters=["FHIR", "HL7V2"], sections=[("idco", "Implant interrogation", [F("battery_v", "Battery voltage", "mdc", "MDC_IDC_MSMT_BATTERY_VOLTAGE", "V", mand=True, mn=1.5, mx=3.6, dec=2),
      F("battery_status", "Battery status", "mdc", "MDC_IDC_MSMT_BATTERY_STATUS", typ="text", mand=True), F("rv_impedance", "RV lead impedance", "mdc", "MDC_IDC_MSMT_LEADCHNL_RV_IMPEDANCE_VALUE", "Ohm", mn=100, mx=3000, dec=0),
      F("ra_impedance", "RA lead impedance", "mdc", "MDC_IDC_MSMT_LEADCHNL_RA_IMPEDANCE_VALUE", "Ohm", mn=100, mx=3000, dec=0), F("af_burden", "AT/AF burden", "mdc", "MDC_IDC_STAT_AT_BURDEN_PERCENT", "%", dec=1),
      F("episodes", "Treated episodes since last session", "mdc", "MDC_IDC_STAT_EPISODE_TOTAL_COUNT", "1", dec=0)])]),
 dict(id="TPL-10", name="Dialysis session record", settings=["SPEC"], captureMode="SPOT", association="SESSION",
      adapters=["FHIR", "HL7V2"], sections=[("session", "Session (prescription vs delivered)", [F("qb", "Blood flow", "local", "INTIMED-DIA-QB", "mL/min", mand=True, mn=0, mx=600, dec=0),
      F("qd", "Dialysate flow", "local", "INTIMED-DIA-QD", "mL/min", mn=0, mx=1000, dec=0), F("uf_volume", "UF volume removed", "local", "INTIMED-DIA-UFV", "mL", mand=True, dec=0),
      F("uf_goal", "UF goal", "local", "INTIMED-DIA-UFG", "mL", dec=0), F("ktv", "Kt/V (online clearance)", "local", "INTIMED-DIA-KTV", "1", mand=True, mn=0, mx=4, dec=2),
      F("vp", "Venous pressure", "local", "INTIMED-DIA-VP", "mm[Hg]", dec=0), F("ap", "Arterial pressure", "local", "INTIMED-DIA-AP", "mm[Hg]", dec=0),
      F("weight_pre", "Pre-dialysis weight", "loinc", "29463-7", "kg", dec=1), F("duration", "Treatment time", "local", "INTIMED-DIA-DUR", "min", dec=0)])]),
 dict(id="TPL-11", name="Neonatal observation chart", settings=["SPEC"], captureMode="CONTINUOUS", association="ENCOUNTER_BED",
      adapters=["FHIR", "HL7V2"], sections=[("incubator", "Incubator & baby", [F("air_temp", "Incubator air temperature", "local", "INTIMED-NEO-AIRT", "Cel", mand=True, mn=20, mx=40, dec=1),
      F("skin_temp", "Skin temperature", "local", "INTIMED-NEO-SKINT", "Cel", mand=True, mn=30, mx=42, dec=1), F("humidity", "Relative humidity", "local", "INTIMED-NEO-RH", "%", mn=0, mx=100, dec=0),
      F("o2", "Incubator O2", "local", "INTIMED-NEO-O2", "%", dec=0), F("mode", "Servo mode", "local", "INTIMED-NEO-MODE", typ="text")])]),
 dict(id="TPL-12", name="Partogram / perinatal record", settings=["SPEC"], captureMode="CONTINUOUS", association="ENCOUNTER_BED",
      adapters=["FHIR", "HL7V2"], sections=[("fetal", "Fetal & maternal", [F("fhr", "Fetal heart rate", "loinc", "55283-6", "/min", mand=True, mn=50, mx=240, dec=0),
      F("uc_freq", "Contractions per 10 min", "local", "INTIMED-UC-FREQ", "1", mand=True, dec=0), F("uc_intensity", "Contraction intensity (IUP)", "local", "INTIMED-UC-INT", "mm[Hg]", dec=0),
      F("mhr", "Maternal heart rate", "loinc", "8867-4", "/min", dec=0), SYS(False, "m_sys"), DIA(False, "m_dia")])]),
 dict(id="TPL-13", name="RPM daily readings & chronic-care dashboard", settings=["RPM"], captureMode="SPOT", association="CONSENT",
      adapters=["FHIR", "REST"], sections=[("daily", "Daily readings", [SYS(False, "sys"), DIA(False, "dia"), F("pulse", "Pulse rate", "loinc", "8867-4", "/min", dec=0),
      F("weight", "Body weight", "loinc", "29463-7", "kg", dec=1), SPO2(False), F("glucose", "Glucose (capillary)", "loinc", "15074-8", "mmol/L", dec=1)])]),
 dict(id="TPL-14", name="Hospital-at-home flowsheet", settings=["RPM", "ACUTE"], captureMode="CONTINUOUS", association="CONSENT",
      adapters=["FHIR", "REST"], sections=[("home", "Continuous vitals", [HR(), SPO2(), RR(), TEMP(False), F("alert", "Platform alert (flag only)", "local", "INTIMED-HAH-ALERT", typ="text")])]),
 dict(id="TPL-15", name="Sleep / neurophysiology report", settings=["SPEC"], captureMode="SPOT", association="WORKLIST",
      adapters=["FHIR", "PDF"], sections=[("psg", "PSG summary", [F("ahi", "Apnoea-hypopnoea index", "local", "INTIMED-PSG-AHI", "/h", mand=True, mn=0, mx=150, dec=1),
      F("odi", "Oxygen desaturation index", "local", "INTIMED-PSG-ODI", "/h", mn=0, mx=150, dec=1), F("min_spo2", "Minimum SpO2", "local", "INTIMED-PSG-MINSPO2", "%", mn=40, mx=100, dec=0),
      F("tst", "Total sleep time", "local", "INTIMED-PSG-TST", "min", mand=True, mn=0, mx=1000, dec=0), F("efficiency", "Sleep efficiency", "local", "INTIMED-PSG-EFF", "%", dec=0),
      F("edf_ref", "EDF recording reference", "local", "INTIMED-PSG-EDF", typ="text")])]),
 dict(id="TPL-16", name="Operative / perfusion record", settings=["ACUTE"], captureMode="SPOT", association="SESSION",
      adapters=["FHIR", "REST"], sections=[("cpb", "Cardiopulmonary bypass", [F("pump_flow", "Arterial pump flow", "local", "INTIMED-CPB-FLOW", "L/min", mand=True, mn=0, mx=8, dec=2),
      F("line_pressure", "Arterial line pressure", "local", "INTIMED-CPB-LINEP", "mm[Hg]", dec=0), F("svo2", "Venous saturation (SvO2)", "local", "INTIMED-CPB-SVO2", "%", dec=0),
      F("blood_temp", "Arterial blood temperature", "local", "INTIMED-CPB-TEMP", "Cel", mand=True, mn=10, mx=42, dec=1), F("cardioplegia", "Cardioplegia volume", "local", "INTIMED-CPB-CPLG", "mL", dec=0),
      F("cell_saver", "Cell-saver return volume", "local", "INTIMED-CPB-CS", "mL", dec=0)])]),
 dict(id="TPL-17", name="OH audiogram (hearing conservation)", settings=["OH"], captureMode="SPOT", association="WORKLIST",
      adapters=["FHIR", "REST", "PDF"], sections=[("right", "Right ear (dB HL)", [F("r%d" % f, "Right %d Hz" % f, "local", "INTIMED-AUD-R%d" % f, "dB", mand=f in (1000, 2000, 3000, 4000), mn=-10, mx=120, dec=0) for f in (500, 1000, 2000, 3000, 4000, 6000, 8000)]),
      ("left", "Left ear (dB HL)", [F("l%d" % f, "Left %d Hz" % f, "local", "INTIMED-AUD-L%d" % f, "dB", mand=f in (1000, 2000, 3000, 4000), mn=-10, mx=120, dec=0) for f in (500, 1000, 2000, 3000, 4000, 6000, 8000)]),
      ("qa", "Test quality", [F("reliability", "Response reliability (1 = reliable)", "local", "INTIMED-AUD-REL", "1", dec=0), F("booth_noise", "Booth ambient noise", "local", "INTIMED-AUD-NOISE", "dB", dec=0)])]),
 dict(id="TPL-18", name="OH spirometry (respirator clearance / surveillance)", settings=["OH", "OPD"], captureMode="SPOT", association="WORKLIST",
      adapters=["FHIR", "REST", "PDF"], sections=[("spiro", "Spirometry (best of session)", [F("fev1", "FEV1", "loinc", "20150-9", "L", mand=True, mn=0.2, mx=8, dec=2),
      F("fvc", "FVC", "loinc", "19870-5", "L", mand=True, mn=0.2, mx=10, dec=2), F("ratio", "FEV1/FVC", "loinc", "19926-5", "%", dec=0),
      F("pef", "Peak expiratory flow", "loinc", "19935-6", "L/s", dec=2), F("repeatability", "FVC repeatability", "local", "INTIMED-SPIRO-REP", "mL", dec=0)])]),
 dict(id="TPL-19", name="OH vision & colour-vision record", settings=["OH", "OPD"], captureMode="SPOT", association="WORKLIST",
      adapters=["FHIR", "PDF"], sections=[("vision", "Vision screening", [F("acuity_r", "Far acuity right (Snellen)", "local", "INTIMED-VA-R", typ="text", mand=True, verify=True),
      F("acuity_l", "Far acuity left (Snellen)", "local", "INTIMED-VA-L", typ="text", mand=True, verify=True), F("acuity_both", "Far acuity both", "local", "INTIMED-VA-B", typ="text"),
      F("ishihara", "Ishihara plates correct (of 14)", "local", "INTIMED-ISHIHARA", "1", dec=0), F("stereo", "Stereo depth (arcsec)", "local", "INTIMED-STEREO", "1", dec=0)])]),
 dict(id="TPL-20", name="OH drug & alcohol record (chain of custody)", settings=["OH"], captureMode="SPOT", association="SESSION", restricted=True,
      adapters=["FHIR", "PDF"], sections=[("brac", "Breath alcohol (evidential)", [F("brac", "Breath alcohol concentration", "local", "INTIMED-BRAC", "g/210L", mand=True, mn=0, mx=0.5, dec=3),
      F("result", "Screening outcome", "local", "INTIMED-BRAC-RESULT", typ="text", mand=True), F("device_serial", "Device serial", "local", "INTIMED-DEVSERIAL", typ="text", mand=True),
      F("custody_id", "Chain-of-custody form", "local", "INTIMED-CCF", typ="text")])]),
 dict(id="TPL-21", name="Respirator fit test & ergonomics", settings=["OH"], captureMode="SPOT", association="WORKLIST",
      adapters=["FHIR", "REST"], sections=[("fit", "Quantitative fit test", [F("fit_factor", "Overall fit factor", "local", "INTIMED-FIT-FF", "1", mand=True, dec=0),
      F("respirator", "Respirator model", "local", "INTIMED-FIT-MODEL", typ="text", mand=True), F("fit_result", "Pass / fail", "local", "INTIMED-FIT-RESULT", typ="text", mand=True),
      F("protocol", "Protocol", "local", "INTIMED-FIT-PROTOCOL", typ="text")])]),
 dict(id="TPL-22", name="Wellness / wearable summary", settings=["OH", "RPM"], captureMode="SPOT", association="CONSENT",
      adapters=["FHIR", "REST"], sections=[("daily", "Daily summary (consented)", [F("steps", "Steps", "loinc", "55423-8", "{steps}", mand=True, dec=0),
      F("resting_hr", "Resting heart rate", "loinc", "40443-4", "/min", dec=0), F("hrv", "HRV (SDNN)", "local", "INTIMED-HRV-SDNN", "ms", dec=0),
      F("sleep", "Sleep duration", "local", "INTIMED-SLEEP-DUR", "min", dec=0)])]),
]
