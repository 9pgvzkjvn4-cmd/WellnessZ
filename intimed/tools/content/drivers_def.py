"""Driver package specifications (26 certified reference drivers covering P1..P11 and TPL-01..TPL-22).
Simulator tokens in golden payloads: {{SUBJECT}} {{SERIAL}} {{TS}} (driver timeFormat) {{TSISO}} {{CTRL}} {{V:key}} {{CS}} (serial XOR
checksum of the text between '$' and '*'). BLE drivers compute the characteristic value from the values (gatt)."""

MSH = "MSH|^~\\&|{app}|{fac}|INTIMED|INTIMED|{{TS}}||ORU^R01^ORU_R01|{{CTRL}}|P|2.6|||NE|AL||UNICODE UTF-8|||IHE_PCD_001^IHE PCD^1.3.6.1.4.1.19376.1.6.1.1.1^ISO\r"

def obx(n, code, name, key, unit_mdc, ts=True):
    return ("OBX|%d|NM|%s^%s^MDC|1.1.1.%s|{{V:%s}}|%s^MDC|||||F|||{{TS}}||||{{SERIAL}}\r" % (n, code, name, code, key, unit_mdc))

def obx_txt(n, code, name, key, sys="MDC"):
    return "OBX|%d|ST|%s^%s^%s||{{V:%s}}||||||F|||{{TS}}||||{{SERIAL}}\r" % (n, code, name, sys, key)

def hl7_fields(spec):  # spec: list of (key, code) -> selectors on OBX-3.1, unit from OBX-6.2
    return [{"key": k, "sel": "OBX[OBX-3.1=%s]/OBX-5" % c, "unitSel": "OBX[OBX-3.1=%s]/OBX-6.2" % c} for k, c in spec]

HL7_ID = {"subject": "PID-3.1", "serial": "OBX-18.1", "time": "OBR-7", "timeFormat": "yyyyMMddHHmmss"}

D = []

# ---------------------------------------------------------------- P4 HL7 acute care
D.append(dict(id="monitor.philips.intellivue-pcd01", name="Philips IntelliVue via PIIC iX / IntelliBridge (IHE PCD-01)", family="Acute-care monitoring & telemetry",
  models=["IMD-201"], options=["P4"], primary="TPL-01", also={"TPL-02": None}, captureMode="CONTINUOUS", association="ENCOUNTER_BED",
  transport={"P4": {"sendingApplication": "PIIC_IX", "sendingFacility": "ICU", "profile": "IHE_PCD_01", "ackMode": "ORIGINAL"}},
  grammar={"type": "HL7V2", "messageTypes": ["ORU^R01"], "identity": HL7_ID, "alarmSel": "OBX[OBX-3.1=196616]/OBX-5",
           "fields": hl7_fields([("hr", "147842"), ("spo2", "150456"), ("nibp_sys", "150021"), ("nibp_dia", "150022"), ("nibp_mean", "150023"), ("rr", "151562"), ("etco2", "151708"), ("temp", "150364")])},
  values={"hr": 78, "spo2": 97, "nibp_sys": 118, "nibp_dia": 72, "nibp_mean": 87, "rr": 16, "etco2": 36, "temp": 37.1},
  serial="PIIC-0042-MX750", out_of_range=("spo2", "38"), artefact={"hr": 40, "spo2": 15},
  golden=MSH.format(app="PIIC_IX", fac="ICU") + "PID|||{{SUBJECT}}^^^HOSP^MR||TESTPATIENT^ALPHA\rPV1||I|ICU^^BED-07\rOBR|1|{{CTRL}}^INTIMED||182777000^monitoring of patient^SCT|||{{TS}}\r"
    + obx(1, "147842", "MDC_ECG_HEART_RATE", "hr", "264864^MDC_DIM_BEAT_PER_MIN") + obx(2, "150456", "MDC_PULS_OXIM_SAT_O2", "spo2", "262688^MDC_DIM_PERCENT")
    + obx(3, "150021", "MDC_PRESS_BLD_NONINV_SYS", "nibp_sys", "266016^MDC_DIM_MMHG") + obx(4, "150022", "MDC_PRESS_BLD_NONINV_DIA", "nibp_dia", "266016^MDC_DIM_MMHG")
    + obx(5, "150023", "MDC_PRESS_BLD_NONINV_MEAN", "nibp_mean", "266016^MDC_DIM_MMHG") + obx(6, "151562", "MDC_RESP_RATE", "rr", "264928^MDC_DIM_RESP_PER_MIN")
    + obx(7, "151708", "MDC_AWAY_CO2_ET", "etco2", "266016^MDC_DIM_MMHG") + obx(8, "150364", "MDC_TEMP_BODY", "temp", "268192^MDC_DIM_DEGC"),
  recs={"intervalMinutes": [5, "ICU flowsheet policy: validated values every 5 min (RR-07 allows 1–15)"], "ackMode": ["ORIGINAL", "PIIC iX expects original-mode ACK per message"]},
  gates={"grading": "SPO2_PERFUSION", "blockImplausible": True}))

D.append(dict(id="vitals.welchallyn.csm-pcd01", name="Welch Allyn Connex Spot Monitor (HL7 spot, PCD-01)", family="Vital signs & body composition",
  models=["IMD-027"], options=["P4"], primary="TPL-02", also={}, captureMode="SPOT", association="WORKLIST",
  transport={"P4": {"sendingApplication": "CSM", "sendingFacility": "ED", "profile": "IHE_PCD_01"}},
  grammar={"type": "HL7V2", "messageTypes": ["ORU^R01"], "identity": HL7_ID,
           "fields": hl7_fields([("hr", "149530"), ("spo2", "150456"), ("nibp_sys", "150021"), ("nibp_dia", "150022"), ("rr", "151562"), ("temp", "150364"), ("weight", "188736")])},
  values={"hr": 84, "spo2": 98, "nibp_sys": 131, "nibp_dia": 84, "rr": 18, "temp": 98.9, "weight": 81.4}, serial="CSM-100377", out_of_range=("nibp_sys", "290"),
  golden=MSH.format(app="CSM", fac="ED") + "PID|||{{SUBJECT}}^^^HOSP^MR\rOBR|1|{{CTRL}}^INTIMED||CSM-SPOT^Spot vitals^L|||{{TS}}\r"
    + obx(1, "149530", "MDC_PULS_RATE_NON_INV", "hr", "264864^MDC_DIM_BEAT_PER_MIN") + obx(2, "150456", "MDC_PULS_OXIM_SAT_O2", "spo2", "262688^MDC_DIM_PERCENT")
    + obx(3, "150021", "MDC_PRESS_BLD_NONINV_SYS", "nibp_sys", "266016^MDC_DIM_MMHG") + obx(4, "150022", "MDC_PRESS_BLD_NONINV_DIA", "nibp_dia", "266016^MDC_DIM_MMHG")
    + obx(5, "151562", "MDC_RESP_RATE", "rr", "264928^MDC_DIM_RESP_PER_MIN") + obx(6, "150364", "MDC_TEMP_BODY", "temp", "266560^MDC_DIM_FAHR")
    + obx(7, "188736", "MDC_MASS_BODY_ACTUAL", "weight", "263875^MDC_DIM_KILO_G"),
  recs={"temperatureUnit": ["[degF] → Cel", "Device configured for °F in this site; template TPL-02 needs Cel – convert in normalisation (RR-06)"]},
  gates={"grading": "BP_VALIDATION"}))

D.append(dict(id="anaesthesia.ge.aisys-pcd01", name="GE Aisys CS2 / Carestation via anaesthesia gateway (PCD-01)", family="Respiratory & anaesthesia",
  models=["IMD-247"], options=["P4"], primary="TPL-03", also={}, captureMode="CONTINUOUS", association="SESSION",
  transport={"P4": {"sendingApplication": "AISYS_GW", "sendingFacility": "OR3"}},
  grammar={"type": "HL7V2", "messageTypes": ["ORU^R01"], "identity": HL7_ID,
           "fields": hl7_fields([("etco2", "151708"), ("fio2", "152196"), ("et_sevo", "152872"), ("mac", "MAC"), ("tv", "151980"), ("peep", "151976"), ("hr", "147842"), ("bis", "BIS")])},
  values={"etco2": 37, "fio2": 45, "et_sevo": 1.9, "mac": 0.95, "tv": 480, "peep": 5, "hr": 66, "bis": 48}, serial="AISYS-CS2-7781", out_of_range=("etco2", "130"),
  golden=MSH.format(app="AISYS_GW", fac="OR3") + "PID|||{{SUBJECT}}^^^HOSP^MR\rOBR|1|{{CTRL}}^INTIMED||ANES^Anaesthesia^L|||{{TS}}\r"
    + obx(1, "151708", "MDC_AWAY_CO2_ET", "etco2", "266016^MDC_DIM_MMHG") + obx(2, "152196", "MDC_CONC_AWAY_O2_INSP", "fio2", "262688^MDC_DIM_PERCENT")
    + obx(3, "152872", "MDC_CONC_AWAY_SEVOFL_ET", "et_sevo", "262688^MDC_DIM_PERCENT") + obx(4, "MAC", "MAC", "mac", "262144^MDC_DIM_DIMLESS")
    + obx(5, "151980", "MDC_VOL_AWAY_TIDAL_EXP", "tv", "263762^MDC_DIM_MILLI_L") + obx(6, "151976", "MDC_VENT_PRESS_AWAY_END_EXP_POS", "peep", "266048^MDC_DIM_CM_H2O")
    + obx(7, "147842", "MDC_ECG_HEART_RATE", "hr", "264864^MDC_DIM_BEAT_PER_MIN") + obx(8, "BIS", "MDC_EEG_BISPECTRAL_INDEX", "bis", "262144^MDC_DIM_DIMLESS"),
  recs={"intervalMinutes": [1, "Anaesthesia record policy: 1-minute values during the case (RR-07)"]}, gates={}))

D.append(dict(id="infusion.bd.alaris-ipec", name="BD Alaris System via Systems Manager (IHE PCD IPEC + PIV auto-programming)", family="Infusion & medication delivery",
  models=["IMD-221"], options=["P4"], primary="TPL-04", also={}, captureMode="SPOT", association="ENCOUNTER_BED", bidirectional=True,
  transport={"P4": {"sendingApplication": "ALARIS_SM", "sendingFacility": "ICU", "profile": "IHE_PCD_IPEC"}},
  grammar={"type": "HL7V2", "messageTypes": ["ORU^R01"], "identity": HL7_ID,
           "fields": [{"key": "drug", "sel": "OBX[OBX-3.1=DRUG]/OBX-5"}] + hl7_fields([("rate", "157784"), ("vtbi", "157872"), ("infused", "157888")]) + [{"key": "pump_state", "sel": "OBX[OBX-3.1=STATE]/OBX-5"}, {"key": "program_confirmed", "sel": "OBX[OBX-3.1=PIV]/OBX-5"}]},
  values={"drug": "Noradrenaline 8 mg/50 mL", "rate": 4.5, "vtbi": 50, "infused": 12, "pump_state": "INFUSING", "program_confirmed": "CONFIRMED"}, serial="ALARIS-8015-22719",
  out_of_range=("rate", "2400"),
  golden=MSH.format(app="ALARIS_SM", fac="ICU") + "PID|||{{SUBJECT}}^^^HOSP^MR\rOBR|1|{{CTRL}}^INTIMED||IPEC^Infusion event^L|||{{TS}}\r"
    + obx_txt(1, "DRUG", "Drug", "drug", "L") + obx(2, "157784", "MDC_FLOW_FLUID_PUMP", "rate", "265266^MDC_DIM_MILLI_L_PER_HR")
    + obx(3, "157872", "MDC_VOL_FLUID_TBI", "vtbi", "263762^MDC_DIM_MILLI_L") + obx(4, "157888", "MDC_VOL_FLUID_DELIV", "infused", "263762^MDC_DIM_MILLI_L")
    + obx_txt(5, "STATE", "Pump state", "pump_state", "L") + obx_txt(6, "PIV", "Programming confirmation", "program_confirmed", "L"),
  recs={"autoProgramming": [True, "Vendor supports PIV (RGV^O15) – order-to-pump auto-programming reduces transcription error (IM-E01-BL-024)"]}, gates={}))

D.append(dict(id="idco.medtronic.carelink-hl7", name="Medtronic CareLink / Paceart – IHE IDCO (HL7 v2 ORU)", family="Cardiology diagnostics & implant remote monitoring",
  models=["IMD-301"], options=["P4"], primary="TPL-09", also={}, captureMode="SPOT", association="MATCH",
  transport={"P4": {"sendingApplication": "CARELINK", "sendingFacility": "DEVICE_CLINIC", "profile": "IHE_IDCO"}},
  grammar={"type": "HL7V2", "messageTypes": ["ORU^R01"], "identity": {"subject": "PID-3.1", "serial": "OBX[OBX-3.1=MDC_IDC_DEV_SERIAL]/OBX-5", "time": "OBR-7", "timeFormat": "yyyyMMddHHmmss"},
           "fields": [{"key": "battery_v", "sel": "OBX[OBX-3.1=MDC_IDC_MSMT_BATTERY_VOLTAGE]/OBX-5", "unitSel": "OBX[OBX-3.1=MDC_IDC_MSMT_BATTERY_VOLTAGE]/OBX-6.1"},
                      {"key": "battery_status", "sel": "OBX[OBX-3.1=MDC_IDC_MSMT_BATTERY_STATUS]/OBX-5"},
                      {"key": "rv_impedance", "sel": "OBX[OBX-3.1=MDC_IDC_MSMT_LEADCHNL_RV_IMPEDANCE_VALUE]/OBX-5", "unitSel": "OBX[OBX-3.1=MDC_IDC_MSMT_LEADCHNL_RV_IMPEDANCE_VALUE]/OBX-6.1"},
                      {"key": "ra_impedance", "sel": "OBX[OBX-3.1=MDC_IDC_MSMT_LEADCHNL_RA_IMPEDANCE_VALUE]/OBX-5", "unitSel": "OBX[OBX-3.1=MDC_IDC_MSMT_LEADCHNL_RA_IMPEDANCE_VALUE]/OBX-6.1"},
                      {"key": "af_burden", "sel": "OBX[OBX-3.1=MDC_IDC_STAT_AT_BURDEN_PERCENT]/OBX-5", "unit": "%"},
                      {"key": "episodes", "sel": "OBX[OBX-3.1=MDC_IDC_STAT_EPISODE_TOTAL_COUNT]/OBX-5", "unit": "1"}]},
  values={"battery_v": 2.97, "battery_status": "BOS (beginning of service)", "rv_impedance": 512, "ra_impedance": 448, "af_burden": 1.8, "episodes": 0},
  serial="PZK603221S", out_of_range=("rv_impedance", "4200"),
  golden="MSH|^~\\&|CARELINK|DEVICE_CLINIC|INTIMED|INTIMED|{{TS}}||ORU^R01^ORU_R01|{{CTRL}}|P|2.6|||NE|AL||UNICODE UTF-8|||IHE_IDCO^IHE IDCO^1.3.6.1.4.1.19376.1.4.1.6.4.4^ISO\r"
    + "PID|||{{SUBJECT}}^^^CLINIC^MR\rOBR|1|{{CTRL}}||Remote^Remote interrogation^IDCO|||{{TS}}\r"
    + "OBX|1|ST|MDC_IDC_DEV_SERIAL^Device serial^MDC||{{SERIAL}}||||||F\r"
    + "OBX|2|NM|MDC_IDC_MSMT_BATTERY_VOLTAGE^Battery voltage^MDC||{{V:battery_v}}|V^volt^UCUM|||||F\r"
    + "OBX|3|ST|MDC_IDC_MSMT_BATTERY_STATUS^Battery status^MDC||{{V:battery_status}}||||||F\r"
    + "OBX|4|NM|MDC_IDC_MSMT_LEADCHNL_RV_IMPEDANCE_VALUE^RV impedance^MDC||{{V:rv_impedance}}|Ohm^ohm^UCUM|||||F\r"
    + "OBX|5|NM|MDC_IDC_MSMT_LEADCHNL_RA_IMPEDANCE_VALUE^RA impedance^MDC||{{V:ra_impedance}}|Ohm^ohm^UCUM|||||F\r"
    + "OBX|6|NM|MDC_IDC_STAT_AT_BURDEN_PERCENT^AT/AF burden^MDC||{{V:af_burden}}|%^percent^UCUM|||||F\r"
    + "OBX|7|NM|MDC_IDC_STAT_EPISODE_TOTAL_COUNT^Episodes^MDC||{{V:episodes}}|1^count^UCUM|||||F\r",
  recs={"association": ["MATCH", "Transmissions carry the clinic patient id; vendor id matching with suggestion queue (RR-08 variant for device clinics)"]}, gates={}))

D.append(dict(id="dialysis.fresenius.5008-tdms", name="Fresenius 5008/6008 via TDMS (HL7 session export)", family="Dialysis & extracorporeal",
  models=["IMD-311"], options=["P4"], primary="TPL-10", also={}, captureMode="SPOT", association="SESSION",
  transport={"P4": {"sendingApplication": "TDMS", "sendingFacility": "DIALYSIS"}},
  grammar={"type": "HL7V2", "messageTypes": ["ORU^R01"], "identity": HL7_ID,
           "fields": [{"key": k, "sel": "OBX[OBX-3.1=%s]/OBX-5" % c, "unitSel": "OBX[OBX-3.1=%s]/OBX-6.1" % c} for k, c in
                      [("qb", "QB"), ("qd", "QD"), ("uf_volume", "UFV"), ("uf_goal", "UFG"), ("ktv", "KTV"), ("vp", "VP"), ("ap", "AP"), ("weight_pre", "WPRE"), ("duration", "DUR")]]},
  values={"qb": 300, "qd": 500, "uf_volume": 2150, "uf_goal": 2200, "ktv": 1.42, "vp": 165, "ap": -180, "weight_pre": 74.6, "duration": 240}, serial="5008S-AB1123",
  out_of_range=("qb", "900"),
  golden=MSH.format(app="TDMS", fac="DIALYSIS") + "PID|||{{SUBJECT}}^^^HOSP^MR\rOBR|1|{{CTRL}}||HD^Haemodialysis session^L|||{{TS}}\r"
    + "".join("OBX|%d|NM|%s^%s^L||{{V:%s}}|%s|||||F|||{{TS}}||||{{SERIAL}}\r" % (i + 1, c, c, k, u) for i, (k, c, u) in enumerate(
      [("qb", "QB", "mL/min"), ("qd", "QD", "mL/min"), ("uf_volume", "UFV", "mL"), ("uf_goal", "UFG", "mL"), ("ktv", "KTV", "1"), ("vp", "VP", "mm[Hg]"), ("ap", "AP", "mm[Hg]"), ("weight_pre", "WPRE", "kg"), ("duration", "DUR", "min")])),
  recs={}, gates={}, plausible_override={"ap": [-400, 0]}))

D.append(dict(id="fetal.philips.avalon-hl7", name="Philips Avalon FM30/FM50 via perinatal gateway (HL7)", family="Neonatal & maternal",
  models=["IMD-325"], options=["P4"], primary="TPL-12", also={}, captureMode="CONTINUOUS", association="ENCOUNTER_BED",
  transport={"P4": {"sendingApplication": "AVALON_GW", "sendingFacility": "LABOUR"}},
  grammar={"type": "HL7V2", "messageTypes": ["ORU^R01"], "identity": HL7_ID,
           "fields": [{"key": k, "sel": "OBX[OBX-3.1=%s]/OBX-5" % c, "unitSel": "OBX[OBX-3.1=%s]/OBX-6.1" % c} for k, c in
                      [("fhr", "FHR"), ("uc_freq", "UCF"), ("uc_intensity", "UCI"), ("mhr", "MHR"), ("m_sys", "MSYS"), ("m_dia", "MDIA")]]},
  values={"fhr": 138, "uc_freq": 3, "uc_intensity": 55, "mhr": 88, "m_sys": 124, "m_dia": 78}, serial="FM50-DE33190", out_of_range=("fhr", "35"),
  golden=MSH.format(app="AVALON_GW", fac="LABOUR") + "PID|||{{SUBJECT}}^^^HOSP^MR\rOBR|1|{{CTRL}}||CTG^Cardiotocography^L|||{{TS}}\r"
    + "".join("OBX|%d|NM|%s^%s^L||{{V:%s}}|%s|||||F|||{{TS}}||||{{SERIAL}}\r" % (i + 1, c, c, k, u) for i, (k, c, u) in enumerate(
      [("fhr", "FHR", "/min"), ("uc_freq", "UCF", "1"), ("uc_intensity", "UCI", "mm[Hg]"), ("mhr", "MHR", "/min"), ("m_sys", "MSYS", "mm[Hg]"), ("m_dia", "MDIA", "mm[Hg]")])),
  recs={"intervalMinutes": [5, "Partogram convention: values charted per 5 min, raw CTG stays in the perinatal system"]}, gates={}))

# ---------------------------------------------------------------- P11 SDC (stub)
D.append(dict(id="monitor.ge.carescape-sdc", name="GE CARESCAPE monitor – IEEE 11073 SDC (BICEPS metric report, SDC-ready stub)", family="Acute-care monitoring & telemetry",
  models=["IMD-202"], options=["P11"], primary="TPL-03", also={"TPL-01": None}, captureMode="CONTINUOUS", association="SESSION",
  transport={"P11": {"discoveryScope": "sdc.ctxt.loc:/sdc.ctxt.loc.detail/OR3", "metricSubscription": "EpisodicMetricReport"}},
  grammar={"type": "SDC", "requireField": "/MdibVersion", "identity": {"subject": "/PatientContext/Identification", "serial": "/Mds/SerialNumber", "time": "/Timestamp"},
           "fields": [{"key": "hr", "path": "/MetricState[Handle=hr.ecg]/Value", "unitPath": "/MetricState[Handle=hr.ecg]/Unit"},
                      {"key": "etco2", "path": "/MetricState[Handle=co2.et]/Value", "unitPath": "/MetricState[Handle=co2.et]/Unit"},
                      {"key": "fio2", "path": "/MetricState[Handle=o2.insp]/Value", "unit": "%"},
                      {"key": "nibp_sys", "path": "/MetricState[Handle=nibp.sys]/Value", "unit": "mm[Hg]"},
                      {"key": "nibp_dia", "path": "/MetricState[Handle=nibp.dia]/Value", "unit": "mm[Hg]"},
                      {"key": "spo2", "path": "/MetricState[Handle=spo2]/Value", "unit": "%"}]},
  values={"hr": 71, "etco2": 4.8, "fio2": 50, "nibp_sys": 112, "nibp_dia": 64, "spo2": 99}, serial="CARESCAPE-B850-55012", out_of_range=("hr", "320"),
  golden='{"MdibVersion": 1042, "Timestamp": "{{TSISO}}", "Mds": {"Handle": "mds0", "SerialNumber": "{{SERIAL}}", "Manufacturer": "GE HealthCare"}, '
         '"PatientContext": {"Identification": "{{SUBJECT}}"}, "MetricState": [{"Handle": "hr.ecg", "Value": "{{V:hr}}", "Unit": "/min"}, '
         '{"Handle": "co2.et", "Value": "{{V:etco2}}", "Unit": "kPa"}, {"Handle": "o2.insp", "Value": "{{V:fio2}}"}, {"Handle": "nibp.sys", "Value": "{{V:nibp_sys}}"}, '
         '{"Handle": "nibp.dia", "Value": "{{V:nibp_dia}}"}, {"Handle": "spo2", "Value": "{{V:spo2}}"}]}',
  recs={"note": ["SDC-ready", "MDPWS discovery / BICEPS subscription replaced by the BICEPS JSON rendering until devices ship SDC (P11 prepared adapter)"]}, gates={}))

# ---------------------------------------------------------------- P3 BLE
D.append(dict(id="bp.and.ua651-ble", name="A&D UA-651BLE – Bluetooth LE Blood Pressure Profile (0x2A35)", family="Vital signs & body composition",
  models=["IMD-037"], options=["P3"], primary="TPL-13", also={"TPL-02": {"nibp_sys": "sys", "nibp_dia": "dia", "hr": "pulse"}}, captureMode="SPOT", association="CONSENT",
  transport={"P3": {"pairing": "LE_SECURE_CONNECTIONS_PASSKEY", "bondingOwner": "GATEWAY", "service": "1810", "characteristic": "2A35"}},
  grammar={"type": "BLE_GATT", "characteristic": "2A35", "fields": [{"key": "sys", "decoded": "systolic"}, {"key": "dia", "decoded": "diastolic"}, {"key": "pulse", "decoded": "pulse"}]},
  values={"sys": 128, "dia": 82, "pulse": 71}, serial="UA651-5110274", out_of_range=("sys", "300"), gatt="2A35",
  recs={"pairing": ["LE_SECURE_CONNECTIONS_PASSKEY", "Device supports passkey entry – Just-Works is blocked by tenant policy (RR-03)"],
        "connectVia": ["GATEWAY", "Fixed station: bond to the clinic gateway, not a tablet (RR-02)"]}, gates={"grading": "BP_VALIDATION"}))

D.append(dict(id="oximeter.nonin.3230-ble", name="Nonin 3230 – Bluetooth LE Pulse Oximeter Profile (0x2A5E spot-check)", family="Vital signs & body composition",
  models=["IMD-033"], options=["P3"], primary="TPL-13", also={"TPL-02": {"spo2": "spo2", "hr": "pulse"}}, captureMode="SPOT", association="CONSENT",
  transport={"P3": {"pairing": "LE_SECURE_CONNECTIONS_PASSKEY", "bondingOwner": "GATEWAY", "service": "1822", "characteristic": "2A5E"}},
  grammar={"type": "BLE_GATT", "characteristic": "2A5E", "fields": [{"key": "spo2", "decoded": "spo2"}, {"key": "pulse", "decoded": "pulse"}]},
  values={"spo2": 96, "pulse": 74}, serial="N3230-77109", out_of_range=("spo2", "40"), gatt="2A5E",
  recs={"pairing": ["LE_SECURE_CONNECTIONS_PASSKEY", "Passkey supported – RR-03"]}, gates={"grading": "SPO2_PERFUSION"}))

# ---------------------------------------------------------------- P5 POCT1-A / ASTM
D.append(dict(id="bloodgas.radiometer.abl90-poct1a", name="Radiometer ABL90 FLEX PLUS via AQURE (POCT1-A2 OBS)", family="Laboratory analysers & middleware",
  models=["IMD-267"], options=["P5"], primary="TPL-05", also={}, captureMode="SPOT", association="WORKLIST",
  transport={"P5": {"dataManager": "AQURE", "qcLotPolicy": "BLOCK_UNTIL_QC", "operatorList": "SYNC_FROM_IDP"}},
  grammar={"type": "POCT1A", "root": "OBS.R01", "identity": {"subject": "//PT.patient_id/@V", "serial": "//DEV.device_id/@V", "time": "//SVC.observation_dttm/@V"},
           "extras": {"lot": "//RGT.lot_number/@V", "operator": "//OPR.operator_id/@V"},
           "fields": [{"key": k, "xpath": "//OBS[OBS.observation_id/@V='%s']/OBS.value/@V" % c, "unitXpath": "//OBS[OBS.observation_id/@V='%s']/OBS.value/@U" % c}
                      for k, c in [("ph", "pH"), ("pco2", "pCO2"), ("po2", "pO2"), ("na", "cNa+"), ("k", "cK+"), ("lactate", "cLac"), ("glucose", "cGlu"), ("hb", "ctHb")]]
                     + [{"key": "lot", "xpath": "//RGT.lot_number/@V"}]},
  values={"ph": 7.38, "pco2": 5.6, "po2": 11.9, "na": 139, "k": 4.1, "lactate": 1.3, "glucose": 6.2, "hb": 13.8, "lot": "L2610A"}, serial="ABL90-1245R0331",
  out_of_range=("ph", "8.9"),
  golden='<OBS.R01><HDR><HDR.message_type V="OBS.R01"/><HDR.control_id V="{{CTRL}}"/><HDR.version_id V="POCT1"/><HDR.creation_dttm V="{{TSISO}}"/></HDR>'
         '<SVC><SVC.role V="OBS"/><SVC.observation_dttm V="{{TSISO}}"/><PT><PT.patient_id V="{{SUBJECT}}"/>'
         + "".join('<OBS><OBS.observation_id V="%s"/><OBS.value V="{{V:%s}}" U="%s"/></OBS>' % (c, k, u) for k, c, u in
                   [("ph", "pH", "pH"), ("pco2", "pCO2", "kPa"), ("po2", "pO2", "kPa"), ("na", "cNa+", "mmol/L"), ("k", "cK+", "mmol/L"), ("lactate", "cLac", "mmol/L"), ("glucose", "cGlu", "mmol/L"), ("hb", "ctHb", "g/dL")])
         + '</PT><OPR><OPR.operator_id V="OP-117"/></OPR><RGT><RGT.lot_number V="{{V:lot}}"/></RGT></SVC><DEV><DEV.device_id V="{{SERIAL}}"/><DEV.vendor_id V="Radiometer"/></DEV></OBS.R01>',
  recs={"qcLotPolicy": ["BLOCK_UNTIL_QC", "Results from a new reagent/cartridge lot are held until QC for the lot is recorded (RR-11)"]}, gates={"qcLotGate": True}))

D.append(dict(id="lab.roche.cobas-astm", name="Roche cobas 8000 via cobas infinity / middleware (ASTM E1394 / LIS2-A2)", family="Laboratory analysers & middleware",
  models=["IMD-261"], options=["P5"], primary="TPL-06", also={}, captureMode="SPOT", association="WORKLIST",
  transport={"P5": {"dataManager": "cobas infinity", "framing": "ASTM_E1381", "qcLotPolicy": "WARN"}},
  grammar={"type": "ASTM", "identity": {"subject": "P-3", "serial": "H-5.3", "time": "H-14", "timeFormat": "yyyyMMddHHmmss"},
           "fields": [{"key": k, "sel": "R[R-3.4=%s]/R-4" % c, "unitSel": "R[R-3.4=%s]/R-5" % c} for k, c in
                      [("glucose", "GLU"), ("creatinine", "CREA"), ("hba1c", "HBA1C"), ("chol", "CHOL"), ("alt", "ALT"), ("wbc", "WBC")]]
                     + [{"key": "specimen", "sel": "O-16"}, {"key": "analyser", "sel": "H-5.1"}]},
  values={"glucose": 5.4, "creatinine": 82, "hba1c": 5.6, "chol": 4.9, "alt": 23, "wbc": 6.8, "specimen": "Serum", "analyser": "cobas"}, serial="SN80041", out_of_range=("glucose", "95"),
  golden="H|\\^&|||{{V:analyser}}^8000^{{SERIAL}}|||||||P|1|{{TS}}\rP|1||{{SUBJECT}}\rO|1|SPEC-{{CTRL}}||^^^GLU|R||||||||||{{V:specimen}}\r"
         + "".join("R|%d|^^^%s|{{V:%s}}|%s||N||F||||{{TS}}\r" % (i + 1, c, k, u) for i, (k, c, u) in enumerate(
           [("glucose", "GLU", "mmol/L"), ("creatinine", "CREA", "umol/L"), ("hba1c", "HBA1C", "%"), ("chol", "CHOL", "mmol/L"), ("alt", "ALT", "U/L"), ("wbc", "WBC", "10*9/L")]))
         + "L|1|N\r",
  recs={}, gates={}))

# ---------------------------------------------------------------- P7 DICOM
D.append(dict(id="imaging.ct.dicom-rdsr", name="CT scanner – DICOM RDSR & study attributes via DICOM router (DICOMweb JSON)", family="Imaging & PACS",
  models=["IMD-281"], options=["P7"], primary="TPL-07", also={}, captureMode="SPOT", association="WORKLIST",
  transport={"P7": {"aeTitle": "INTIMED_SCP", "callingAeTitles": "CT01", "tls": "DICOM_TLS", "storageClasses": "1.2.840.10008.5.1.4.1.1.88.67"}},
  grammar={"type": "DICOM_JSON", "identity": {"subject": "00100020", "serial": "00181000", "time": "00080020"},
           "fields": [{"key": "accession", "tag": "00080050"}, {"key": "study_uid", "tag": "0020000D"}, {"key": "modality", "tag": "00080060"},
                      {"key": "body_part", "tag": "00180015"}, {"key": "dlp", "tag": "SR:113813", "unit": "mGy.cm"}, {"key": "ctdivol", "tag": "SR:113830", "unit": "mGy"}]},
  values={"accession": "ACC-260117", "study_uid": "1.2.826.0.1.3680043.10.543.7.1001", "modality": "CT", "body_part": "CHEST", "dlp": 412.6, "ctdivol": 9.85},
  serial="SOMATOM-X-1077", out_of_range=("dlp", "99999"),
  golden='{"00080020": {"vr": "DA", "Value": ["{{TSISO}}"]}, "00080050": {"vr": "SH", "Value": ["{{V:accession}}"]}, "00080060": {"vr": "CS", "Value": ["{{V:modality}}"]}, '
         '"00100020": {"vr": "LO", "Value": ["{{SUBJECT}}"]}, "00180015": {"vr": "CS", "Value": ["{{V:body_part}}"]}, "00181000": {"vr": "LO", "Value": ["{{SERIAL}}"]}, '
         '"0020000D": {"vr": "UI", "Value": ["{{V:study_uid}}"]}, "0040A730": {"vr": "SQ", "Value": [{"0040A043": {"vr": "SQ", "Value": [{"00080100": {"vr": "SH", "Value": ["113813"]}, "00080104": {"vr": "LO", "Value": ["CT Dose Length Product Total"]}}]}, '
         '"0040A300": {"vr": "SQ", "Value": [{"0040A30A": {"vr": "DS", "Value": ["{{V:dlp}}"]}, "004008EA": {"vr": "SQ", "Value": [{"00080100": {"vr": "SH", "Value": ["mGy.cm"]}}]}}]}}, '
         '{"0040A043": {"vr": "SQ", "Value": [{"00080100": {"vr": "SH", "Value": ["113830"]}}]}, "0040A300": {"vr": "SQ", "Value": [{"0040A30A": {"vr": "DS", "Value": ["{{V:ctdivol}}"]}, "004008EA": {"vr": "SQ", "Value": [{"00080100": {"vr": "SH", "Value": ["mGy"]}}]}}]}}]}}',
  recs={"tls": ["DICOM_TLS", "DICOM TLS (PS3.15 BCP 195) between modality and router; plaintext DICOM blocked outside the imaging VLAN"]}, gates={}))

# ---------------------------------------------------------------- P2 vendor software exports
D.append(dict(id="ecg.ge.muse-xml", name="GE MUSE / MAC 5500 – resting ECG XML export (vendor software)", family="Cardiology",
  models=["IMD-307"], options=["P2"], primary="TPL-08", also={}, captureMode="SPOT", association="WORKLIST",
  transport={"P2": {"source": "FOLDER", "path": "\\\\muse01\\export\\intimed", "pattern": "*.xml", "pollSeconds": 30, "stabilitySeconds": 5, "archive": "MOVE_TO_DONE"}},
  grammar={"type": "XML", "root": "RestingECG", "identity": {"subject": "/RestingECG/PatientDemographics/PatientID", "serial": "/RestingECG/TestDemographics/DeviceSerial", "time": "/RestingECG/TestDemographics/AcquisitionDateTime"},
           "fields": [{"key": "hr", "xpath": "/RestingECG/RestingECGMeasurements/VentricularRate", "unit": "/min"},
                      {"key": "pr", "xpath": "/RestingECG/RestingECGMeasurements/PRInterval", "unit": "ms"},
                      {"key": "qrs", "xpath": "/RestingECG/RestingECGMeasurements/QRSDuration", "unit": "ms"},
                      {"key": "qt", "xpath": "/RestingECG/RestingECGMeasurements/QTInterval", "unit": "ms"},
                      {"key": "qtc", "xpath": "/RestingECG/RestingECGMeasurements/QTCorrected", "unit": "ms"},
                      {"key": "axis", "xpath": "/RestingECG/RestingECGMeasurements/RAxis", "unit": "deg"},
                      {"key": "interpretation", "xpath": "/RestingECG/Diagnosis/DiagnosisStatement[1]/StmtText"}]},
  values={"hr": 68, "pr": 158, "qrs": 92, "qt": 384, "qtc": 407, "axis": 41, "interpretation": "Normal sinus rhythm. Normal ECG (unconfirmed)"}, serial="MAC5500-SFH09231",
  out_of_range=("qtc", "980"),
  golden="<RestingECG><PatientDemographics><PatientID>{{SUBJECT}}</PatientID></PatientDemographics><TestDemographics><AcquisitionDevice>MAC5500</AcquisitionDevice>"
         "<DeviceSerial>{{SERIAL}}</DeviceSerial><AcquisitionDateTime>{{TSISO}}</AcquisitionDateTime></TestDemographics><RestingECGMeasurements>"
         "<VentricularRate>{{V:hr}}</VentricularRate><PRInterval>{{V:pr}}</PRInterval><QRSDuration>{{V:qrs}}</QRSDuration><QTInterval>{{V:qt}}</QTInterval>"
         "<QTCorrected>{{V:qtc}}</QTCorrected><RAxis>{{V:axis}}</RAxis></RestingECGMeasurements><Diagnosis><DiagnosisStatement><StmtText>{{V:interpretation}}</StmtText>"
         "</DiagnosisStatement></Diagnosis></RestingECG>",
  recs={"pollSeconds": [30, "Certified MUSE configurations poll every 30 s with a 5 s file-stability wait (RR-13)"]}, gates={"verifyBeforePost": False}))

D.append(dict(id="sleep.natus.embla-csv", name="Natus Embla / Nox – PSG summary CSV export", family="Rehabilitation, neurophysiology & sleep",
  models=["IMD-362"], options=["P2"], primary="TPL-15", also={}, captureMode="SPOT", association="WORKLIST",
  transport={"P2": {"source": "FOLDER", "path": "/mnt/psg/export", "pattern": "*_summary.csv", "pollSeconds": 300, "stabilitySeconds": 30}},
  grammar={"type": "CSV", "delimiter": ",", "identity": {"subject": "PatientID", "serial": "Recorder", "time": "StudyStart"},
           "fields": [{"key": "ahi", "column": "AHI", "unit": "/h"}, {"key": "odi", "column": "ODI", "unit": "/h"}, {"key": "min_spo2", "column": "MinSpO2", "unit": "%"},
                      {"key": "tst", "column": "TST_min", "unit": "min"}, {"key": "efficiency", "column": "SleepEfficiency", "unit": "%"}, {"key": "edf_ref", "column": "EDF"}]},
  values={"ahi": 14.2, "odi": 11.8, "min_spo2": 84, "tst": 402, "efficiency": 87, "edf_ref": "PSG-2611-0042.edf"}, serial="EMBLA-NDX-3301", out_of_range=("ahi", "250"),
  golden="PatientID,Recorder,StudyStart,AHI,ODI,MinSpO2,TST_min,SleepEfficiency,EDF\r\n{{SUBJECT}},{{SERIAL}},{{TSISO}},{{V:ahi}},{{V:odi}},{{V:min_spo2}},{{V:tst}},{{V:efficiency}},{{V:edf_ref}}\r\n",
  recs={}, gates={}))

D.append(dict(id="perfusion.terumo.system1-json", name="Terumo System 1 perfusion – case data export (JSON)", family="Operating room & surgical",
  models=["IMD-344"], options=["P2"], primary="TPL-16", also={}, captureMode="SPOT", association="SESSION",
  transport={"P2": {"source": "FOLDER", "path": "/mnt/or/perfusion", "pattern": "*.json", "pollSeconds": 60, "stabilitySeconds": 10}},
  grammar={"type": "JSON", "identity": {"subject": "/case/patientId", "serial": "/device/serial", "time": "/case/timestamp"},
           "fields": [{"key": "pump_flow", "path": "/readings/arterialFlow", "unit": "L/min"}, {"key": "line_pressure", "path": "/readings/linePressure", "unit": "mm[Hg]"},
                      {"key": "svo2", "path": "/readings/svo2", "unit": "%"}, {"key": "blood_temp", "path": "/readings/arterialTemp", "unit": "Cel"},
                      {"key": "cardioplegia", "path": "/totals/cardioplegia", "unit": "mL"}, {"key": "cell_saver", "path": "/totals/cellSaver", "unit": "mL"}]},
  values={"pump_flow": 4.6, "line_pressure": 212, "svo2": 74, "blood_temp": 32.5, "cardioplegia": 1100, "cell_saver": 450}, serial="S1-HLM-2207", out_of_range=("blood_temp", "51"),
  golden='{"case": {"patientId": "{{SUBJECT}}", "timestamp": "{{TSISO}}"}, "device": {"serial": "{{SERIAL}}", "model": "System 1"}, '
         '"readings": {"arterialFlow": {{V:pump_flow}}, "linePressure": {{V:line_pressure}}, "svo2": {{V:svo2}}, "arterialTemp": {{V:blood_temp}}}, '
         '"totals": {"cardioplegia": {{V:cardioplegia}}, "cellSaver": {{V:cell_saver}}}}',
  recs={}, gates={}))

D.append(dict(id="audio.benson.cca200-xml", name="Benson CCA-200mini via vendor software – audiogram XML export", family="Audiometry & hearing",
  models=["IMD-015"], options=["P2"], primary="TPL-17", also={}, captureMode="SPOT", association="WORKLIST",
  transport={"P2": {"source": "FOLDER", "path": "C:\\\\Benson\\\\Export", "pattern": "*.xml", "pollSeconds": 20, "stabilitySeconds": 3, "serviceAccount": "secret://benson-share"}},
  grammar={"type": "XML", "root": "Audiogram", "identity": {"subject": "/Audiogram/Patient/@id", "serial": "/Audiogram/Device/@serial", "time": "/Audiogram/@tested"},
           "fields": [{"key": "%s%d" % (e.lower()[0], f), "xpath": "/Audiogram/Ear[@side='%s']/Threshold[@hz='%d']/@db" % (e, f), "unit": "dB"} for e in ("Right", "Left") for f in (500, 1000, 2000, 3000, 4000, 6000, 8000)]
                     + [{"key": "reliability", "xpath": "/Audiogram/Quality/@reliable", "unit": "1"}, {"key": "booth_noise", "xpath": "/Audiogram/Quality/@boothNoise", "unit": "dB"}]},
  values=dict([("r%d" % f, v) for f, v in zip((500, 1000, 2000, 3000, 4000, 6000, 8000), (10, 10, 15, 20, 30, 35, 25))] + [("l%d" % f, v) for f, v in zip((500, 1000, 2000, 3000, 4000, 6000, 8000), (5, 10, 10, 20, 25, 30, 20))] + [("reliability", 1), ("booth_noise", 28)]),
  serial="CCA200-61742", out_of_range=("r4000", "160"),
  golden='<Audiogram tested="{{TSISO}}"><Patient id="{{SUBJECT}}"/><Device serial="{{SERIAL}}" model="CCA-200mini" calibrated="2026-06-01"/>'
         + "".join('<Ear side="%s">%s</Ear>' % (e, "".join('<Threshold hz="%d" db="{{V:%s%d}}"/>' % (f, e.lower()[0], f) for f in (500, 1000, 2000, 3000, 4000, 6000, 8000))) for e in ("Right", "Left"))
         + '<Quality reliable="{{V:reliability}}" boothNoise="{{V:booth_noise}}"/></Audiogram>',
  recs={"serviceAccount": ["secret://benson-share", "Read-only service account held in the vault – never typed into the configuration (SC-05)"]},
  gates={"grading": "AUDIOMETRIC_VALIDITY"}))

D.append(dict(id="spiro.ndd.easyone-gdt", name="ndd EasyOne Air via EasyOne Connect – GDT 3.0 export", family="Spirometry & lung function",
  models=["IMD-001"], options=["P2"], primary="TPL-18", also={}, captureMode="SPOT", association="WORKLIST",
  transport={"P2": {"source": "FOLDER", "path": "C:\\\\ProgramData\\\\ndd\\\\GDT\\\\out", "pattern": "*.gdt", "pollSeconds": 10, "stabilitySeconds": 2, "encoding": "ISO-8859-15"}},
  grammar={"type": "GDT", "identity": {"subject": "3000", "serial": "8402", "time": "6200"},
           "fields": [{"key": "fev1", "test": "8410=FEV1", "unit": "L"}, {"key": "fvc", "test": "8410=FVC", "unit": "L"}, {"key": "ratio", "test": "8410=FEV1FVC", "unit": "%"},
                      {"key": "pef", "test": "8410=PEF", "unit": "L/s"}, {"key": "repeatability", "test": "8410=FVCREP", "unit": "mL"}]},
  values={"fev1": 3.42, "fvc": 4.31, "ratio": 79, "pef": 8.95, "repeatability": 120}, serial="EASYONE-AIR-200381", out_of_range=("fev1", "14"),
  gdt=[["8000", "6310"], ["3000", "{{SUBJECT}}"], ["8402", "{{SERIAL}}"], ["6200", "{{TSISO}}"], ["8410", "FEV1"], ["8420", "{{V:fev1}}"], ["8421", "L"],
       ["8410", "FVC"], ["8420", "{{V:fvc}}"], ["8421", "L"], ["8410", "FEV1FVC"], ["8420", "{{V:ratio}}"], ["8421", "%"],
       ["8410", "PEF"], ["8420", "{{V:pef}}"], ["8421", "L/s"], ["8410", "FVCREP"], ["8420", "{{V:repeatability}}"], ["8421", "mL"]],
  recs={"grading": ["ATS_ERS_2019", "Spirometry family: ATS/ERS 2019 repeatability grading and calibration lockout (RR-11)"]},
  gates={"grading": "ATS_ERS_2019", "calibrationLockout": True}, extra_semantics=[]))

# ---------------------------------------------------------------- P9 OCR / P10 manual
D.append(dict(id="vision.optec.6500-ocr", name="Stereo Optical Optec 6500 (display-only) – image capture & OCR with verification", family="Vision & ophthalmic",
  models=["IMD-065"], options=["P9"], primary="TPL-19", also={}, captureMode="SPOT", association="WORKLIST",
  transport={"P9": {"ocrTemplate": "optec-6500-result-card", "confidenceThreshold": 0.85, "imageRetentionDays": 30}},
  grammar={"type": "OCR_TEXT", "fields": [{"key": "acuity_r", "regex": "RIGHT\\s*20/(\\d+)"}, {"key": "acuity_l", "regex": "LEFT\\s*20/(\\d+)"}, {"key": "acuity_both", "regex": "BOTH\\s*20/(\\d+)"},
                                          {"key": "ishihara", "regex": "COLOU?R\\s*(\\d+)/14", "unit": "1"}, {"key": "stereo", "regex": "STEREO\\s*(\\d+)", "unit": "1"}]},
  values={"acuity_r": "20", "acuity_l": "25", "acuity_both": "20", "ishihara": 14, "stereo": 40}, serial="OPTEC6500-44120", out_of_range=None,
  golden='{"serial": "{{SERIAL}}", "time": "{{TSISO}}", "ocrConfidence": 0.93, "text": "OPTEC 6500  RIGHT 20/{{V:acuity_r}}  LEFT 20/{{V:acuity_l}}  BOTH 20/{{V:acuity_both}}  COLOR {{V:ishihara}}/14  STEREO {{V:stereo}}"}',
  recs={"verification": ["MANDATORY", "OCR values are proposals – posting requires clinician verification (P9)"]}, gates={"verifyBeforePost": True}, text_keys=["acuity_r", "acuity_l", "acuity_both"]))

D.append(dict(id="vision.manual.snellen", name="Snellen / LogMAR chart – manual entry with device attestation", family="Occupational-health imaging & other instruments",
  models=["IMD-133"], options=["P10"], primary="TPL-19", also={}, captureMode="SPOT", association="WORKLIST",
  transport={"P10": {"attestationFields": "model,serial,calibrationDate,attestedBy", "csvImport": True}},
  grammar={"type": "MANUAL", "fields": [{"key": "acuity_r"}, {"key": "acuity_l"}, {"key": "acuity_both"}, {"key": "ishihara", "unit": "1"}, {"key": "stereo", "unit": "1"}]},
  values={"acuity_r": "6/6", "acuity_l": "6/9", "acuity_both": "6/6", "ishihara": 13, "stereo": 60}, serial="SNELLEN-WALL-3", out_of_range=None,
  golden='{"time": "{{TSISO}}", "subject": "{{SUBJECT}}", "values": {"acuity_r": "{{V:acuity_r}}", "acuity_l": "{{V:acuity_l}}", "acuity_both": "{{V:acuity_both}}", "ishihara": {{V:ishihara}}, "stereo": {{V:stereo}}}, '
         '"attestation": {"model": "Snellen 6 m wall chart", "serial": "{{SERIAL}}", "calibrationDate": "2026-01-15", "attestedBy": "nurse.demo"}}',
  recs={"note": ["LAST_RESORT", "Manual entry only when no certified device or OCR path is available (fallback chain)"]}, gates={"verifyBeforePost": True}, text_keys=["acuity_r", "acuity_l", "acuity_both"]))

# ---------------------------------------------------------------- P1 serial
D.append(dict(id="da.lifeloc.fc20-serial", name="Lifeloc FC20 evidential breath tester – RS-232 via Device Host agent", family="Drug & alcohol testing",
  models=["IMD-097"], options=["P1"], primary="TPL-20", also={}, captureMode="SPOT", association="SESSION", restricted=True,
  transport={"P1": {"port": "COM3", "baud": 9600, "parity": "NONE", "dataBits": 8, "stopBits": 1, "flowControl": "NONE", "terminator": "\\r\\n", "mode": "PUSH", "usbAllowList": "0403:6001"}},
  grammar={"type": "SERIAL", "terminator": "\r\n", "checksum": "xor-hex",
           "frame": "^\\$BRAC,(?<serial>[A-Z0-9-]+),(?<time>\\d{14}),(?<brac>\\d\\.\\d{3}),(?<result>PASS|FAIL|REFUSAL),(?<custody>[A-Z0-9-]*)\\*[0-9A-F]{2}$",
           "identity": {"serial": "serial", "time": "time", "timeFormat": "yyyyMMddHHmmss"},
           "fields": [{"key": "brac", "group": "brac", "unit": "g/210L"}, {"key": "result", "group": "result"}, {"key": "device_serial", "group": "serial"}, {"key": "custody_id", "group": "custody"}]},
  values={"brac": "0.000", "result": "PASS", "device_serial": "FC20-118842", "custody_id": "CCF-7781234"}, serial="FC20-118842", out_of_range=("brac", "0.900"),
  golden="$BRAC,{{SERIAL}},{{TS}},{{V:brac}},{{V:result}},{{V:custody_id}}*{{CS}}\r\n",
  recs={"association": ["SESSION", "Restricted category: no identifier matching – collector session or worklist only (RR-08)"],
        "baud": [9600, "FC20 serial framing 9600 8N1, no flow control (RR-05)"]}, gates={"calibrationLockout": True}, text_keys=["result", "device_serial", "custody_id"]))

D.append(dict(id="neonatal.drager.babyleo-serial", name="Dräger Babyleo TN500 – serial data export (MEDIBUS-style frames) via gateway", family="Neonatal & maternal",
  models=["IMD-322"], options=["P1"], primary="TPL-11", also={}, captureMode="CONTINUOUS", association="ENCOUNTER_BED",
  transport={"P1": {"port": "/dev/ttyUSB0", "baud": 19200, "parity": "EVEN", "dataBits": 8, "stopBits": 1, "flowControl": "NONE", "terminator": "\\r", "mode": "POLL", "pollSeconds": 60}},
  grammar={"type": "SERIAL", "terminator": "\r", "checksum": "xor-hex",
           "frame": "^\\$INC,(?<serial>[A-Z0-9-]+),(?<time>\\d{14}),AT=(?<air>[0-9.]+),ST=(?<skin>[0-9.]+),RH=(?<rh>[0-9.]+),O2=(?<o2>[0-9.]+),MODE=(?<mode>AIR|SKIN)\\*[0-9A-F]{2}$",
           "identity": {"serial": "serial", "time": "time", "timeFormat": "yyyyMMddHHmmss"},
           "fields": [{"key": "air_temp", "group": "air", "unit": "Cel"}, {"key": "skin_temp", "group": "skin", "unit": "Cel"}, {"key": "humidity", "group": "rh", "unit": "%"},
                      {"key": "o2", "group": "o2", "unit": "%"}, {"key": "mode", "group": "mode"}]},
  values={"air_temp": 34.2, "skin_temp": 36.6, "humidity": 65, "o2": 30, "mode": "SKIN"}, serial="TN500-ARMB-0092", out_of_range=("skin_temp", "45.0"),
  golden="$INC,{{SERIAL}},{{TS}},AT={{V:air_temp}},ST={{V:skin_temp}},RH={{V:humidity}},O2={{V:o2}},MODE={{V:mode}}*{{CS}}\r",
  recs={"parity": ["EVEN", "Driver default framing 19200 8E1 (RR-05) – warn if changed"]}, gates={}, text_keys=["mode"]))

# ---------------------------------------------------------------- P6 cloud
D.append(dict(id="rpm.bodytrace.cloud-webhook", name="BodyTrace cellular RPM kit – vendor cloud webhook (HMAC)", family="Remote patient monitoring & home",
  models=["IMD-331"], options=["P6"], primary="TPL-13", also={}, captureMode="SPOT", association="CONSENT",
  transport={"P6": {"mode": "WEBHOOK", "webhookSecret": "secret://bodytrace-webhook", "scopes": "measurements.read", "residencyCheck": True}},
  grammar={"type": "JSON", "identity": {"serial": "/deviceId", "time": "/ts"},
           "fields": [{"key": "sys", "path": "/values[type=systolic]/v", "unit": "mm[Hg]"}, {"key": "dia", "path": "/values[type=diastolic]/v", "unit": "mm[Hg]"},
                      {"key": "pulse", "path": "/values[type=pulse]/v", "unit": "/min"}, {"key": "weight", "path": "/values[type=weight]/v", "unitPath": "/values[type=weight]/u"}]},
  values={"sys": 136, "dia": 86, "pulse": 77, "weight": 182.4}, serial="BT-353081090123456", out_of_range=("sys", "310"),
  golden='{"deviceId": "{{SERIAL}}", "ts": "{{TSISO}}", "values": [{"type": "systolic", "v": {{V:sys}}}, {"type": "diastolic", "v": {{V:dia}}}, {"type": "pulse", "v": {{V:pulse}}}, {"type": "weight", "v": {{V:weight}}, "u": "lb"}]}',
  recs={"scopes": ["measurements.read", "Minimum scope for TPL-13 bindings; consent required for home devices (RR-15)"], "association": ["CONSENT", "Home device: consent-bound enrolment by device serial (RR-09)"]},
  gates={"grading": "BP_VALIDATION"}))

D.append(dict(id="hah.masimo.safetynet-cloud", name="Masimo SafetyNet hospital-at-home platform – cloud API (continuous)", family="Remote patient monitoring & home",
  models=["IMD-332"], options=["P6"], primary="TPL-14", also={}, captureMode="CONTINUOUS", association="CONSENT",
  transport={"P6": {"mode": "POLL", "pollSeconds": 60, "clientId": "intimed-local", "clientSecret": "secret://safetynet-client", "scopes": "vitals.read alerts.read", "rateLimitPerMinute": 120}},
  grammar={"type": "JSON", "identity": {"serial": "/sensor/serial", "time": "/sample/t"},
           "fields": [{"key": "hr", "path": "/sample/pr", "unit": "/min"}, {"key": "spo2", "path": "/sample/spo2", "unit": "%"}, {"key": "rr", "path": "/sample/rrp", "unit": "/min"},
                      {"key": "temp", "path": "/sample/temp", "unit": "Cel"}, {"key": "alert", "path": "/sample/alert"}]},
  values={"hr": 82, "spo2": 95, "rr": 17, "temp": 37.4, "alert": "none"}, serial="RADIUS-PPG-88031", out_of_range=("spo2", "41"), artefact={"hr": 45},
  golden='{"sensor": {"serial": "{{SERIAL}}", "model": "Radius PPG"}, "sample": {"t": "{{TSISO}}", "pr": {{V:hr}}, "spo2": {{V:spo2}}, "rrp": {{V:rr}}, "temp": {{V:temp}}, "alert": "{{V:alert}}"}}',
  recs={"pollSeconds": [60, "Hospital-at-home flowsheet interval 1 min; rate limit respected (RR-13)"]}, gates={}, text_keys=["alert"]))

D.append(dict(id="fit.tsi.portacount-cloud", name="TSI PortaCount Pro+ via TSI Link – fit-test results (cloud API)", family="Respiratory protection & ergonomics",
  models=["IMD-105"], options=["P6"], primary="TPL-21", also={}, captureMode="SPOT", association="WORKLIST",
  transport={"P6": {"mode": "WEBHOOK", "webhookSecret": "secret://tsi-link-webhook", "scopes": "fittests.read"}},
  grammar={"type": "JSON", "identity": {"subject": "/person/employeeId", "serial": "/instrument/serial", "time": "/completedAt"},
           "fields": [{"key": "fit_factor", "path": "/overallFitFactor", "unit": "1"}, {"key": "respirator", "path": "/respirator/model"}, {"key": "fit_result", "path": "/result"}, {"key": "protocol", "path": "/protocol"}]},
  values={"fit_factor": 186, "respirator": "3M 1860 N95 (M)", "fit_result": "PASS", "protocol": "OSHA 29 CFR 1910.134 App A – fast"}, serial="8048-PCP-301455", out_of_range=None,
  golden='{"person": {"employeeId": "{{SUBJECT}}"}, "instrument": {"serial": "{{SERIAL}}", "model": "PortaCount Pro+ 8048"}, "completedAt": "{{TSISO}}", '
         '"overallFitFactor": {{V:fit_factor}}, "respirator": {"model": "{{V:respirator}}"}, "result": "{{V:fit_result}}", "protocol": "{{V:protocol}}"}',
  recs={}, gates={}, text_keys=["respirator", "fit_result", "protocol"]))

# ---------------------------------------------------------------- P8 FHIR
D.append(dict(id="wear.aggregator.fhir-pcha", name="Wearable aggregator (Garmin / Apple / Fitbit via aggregator) – FHIR R4 PCHA upload", family="Wearables & personal health",
  models=["IMD-121"], options=["P8"], primary="TPL-22", also={}, captureMode="SPOT", association="CONSENT",
  transport={"P8": {"endpoint": "/fhir/r4/Observation", "scopes": "system/Observation.write", "profileValidation": "PCHA_LITE"}},
  grammar={"type": "FHIR", "fields": [{"key": "steps", "code": "http://loinc.org|55423-8"}, {"key": "resting_hr", "code": "http://loinc.org|40443-4"},
                                      {"key": "hrv", "code": "urn:intimed:local|INTIMED-HRV-SDNN"}, {"key": "sleep", "code": "urn:intimed:local|INTIMED-SLEEP-DUR"}]},
  values={"steps": 9412, "resting_hr": 58, "hrv": 46, "sleep": 431}, serial="AGG-GARMIN-FR965-7712", out_of_range=None,
  golden='{"resourceType": "Bundle", "type": "collection", "entry": ['
         + ",".join('{"resource": {"resourceType": "Observation", "status": "final", "code": {"coding": [{"system": "%s", "code": "%s"}]}, "subject": {"identifier": {"value": "{{SUBJECT}}"}}, '
                    '"device": {"identifier": {"value": "{{SERIAL}}"}}, "effectiveDateTime": "{{TSISO}}", "valueQuantity": {"value": {{V:%s}}, "unit": "%s", "system": "http://unitsofmeasure.org", "code": "%s"}}}' % (s, c, k, u, u)
                    for k, s, c, u in [("steps", "http://loinc.org", "55423-8", "{steps}"), ("resting_hr", "http://loinc.org", "40443-4", "/min"), ("hrv", "urn:intimed:local", "INTIMED-HRV-SDNN", "ms"), ("sleep", "urn:intimed:local", "INTIMED-SLEEP-DUR", "min")])
         + "]}",
  recs={"scopes": ["system/Observation.write", "Write-only scope for the aggregator client (RR-15)"]}, gates={}))
