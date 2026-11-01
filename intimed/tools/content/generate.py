#!/usr/bin/env python3
"""Generates IntiMed reference content into intimed-platform/src/main/resources:
templates/TPL-*.json, drivers/*.json (unsigned – sign with `java -jar intimed-platform.jar sdk sign-all`), optionpacks/P*.json.
Run: python3 tools/content/generate.py"""
import json, os, sys
sys.path.insert(0, os.path.dirname(__file__))
from templates_def import T
from drivers_def import D

ROOT = os.path.join(os.path.dirname(__file__), "..", "..", "intimed-platform", "src", "main", "resources")

OPTION_PACKS = {
 "P1": dict(name="Serial / USB direct", how="RS-232, USB-CDC (virtual COM) or USB-HID keyboard wedge read by the Device Host agent or gateway at the station.",
   security="MEDIUM", reliability="HIGH", effort="LOW", latency="< 1 s", via="Device Host agent / gateway → HTTPS ingress (mTLS)",
   prerequisites=["Device Host agent or gateway enrolled (mTLS identity)", "USB VID/PID on the allow-list", "Cable / adapter verified", "Calibration evidence uploaded"],
   params=[("port", "string", "COM3", True), ("baud", "enum:1200,2400,4800,9600,19200,38400,57600,115200", 9600, True), ("parity", "enum:NONE,EVEN,ODD", "NONE", True),
           ("dataBits", "enum:7,8", 8, True), ("stopBits", "enum:1,2", 1, True), ("flowControl", "enum:NONE,RTS_CTS,XON_XOFF", "NONE", True),
           ("terminator", "string", "\\r\\n", True), ("mode", "enum:PUSH,POLL", "PUSH", True), ("pollSeconds", "int:1:3600", 30, False), ("usbAllowList", "string", "", False)],
   tests=["XT-01 port opened via agent", "XT-03 frame acquired", "XT-04 frame grammar + checksum", "XT-11 truncated / bad checksum frame rejected"]),
 "P2": dict(name="Vendor PC software – database / file export watcher", how="Read-only access to the vendor application's export folder (XML, GDT, CSV, PDF, JSON) or database; the vendor software stays the regulated measurement application.",
   security="MEDIUM", reliability="MEDIUM", effort="MEDIUM", latency="poll interval + stability wait", via="Device Host agent on the vendor PC (outbound only) → HTTPS ingress (mTLS)",
   prerequisites=["Vendor software version recorded", "Read-only service account in the vault", "Vendor PC isolated in the device zone (SC-07)", "Export format enabled in vendor software"],
   params=[("source", "enum:FOLDER,DATABASE", "FOLDER", True), ("path", "string", "", True), ("pattern", "string", "*.*", True), ("pollSeconds", "int:5:3600", 30, True),
           ("stabilitySeconds", "int:0:300", 5, True), ("archive", "enum:MOVE_TO_DONE,DELETE,LEAVE", "MOVE_TO_DONE", True), ("serviceAccount", "secret", "", False),
           ("encoding", "enum:UTF-8,ISO-8859-1,ISO-8859-15,WINDOWS-1252", "UTF-8", False), ("dedupKey", "string", "sha256(file)", False)],
   tests=["XT-01 folder / DB readable with service account", "XT-03 export file acquired", "XT-04 schema parse", "XT-11 partial file (stability) and duplicate file"]),
 "P3": dict(name="Bluetooth LE / Classic", how="Standard GATT health profiles (BLP, HTP, PLX, WSS, GLS, BCS) or vendor services via gateway, tablet or kiosk; Classic SPP for older devices.",
   security="HIGH", reliability="MEDIUM", effort="LOW", latency="1–3 s", via="Gateway / tablet / kiosk (mTLS identity) → HTTPS ingress",
   prerequisites=["Bonding owner enrolled (gateway, tablet or kiosk)", "Device on the approved-device list", "Pairing method LE Secure Connections"],
   params=[("pairing", "enum:LE_SECURE_CONNECTIONS_PASSKEY,LE_SECURE_CONNECTIONS_OOB,LE_SECURE_CONNECTIONS_NUMERIC,JUST_WORKS", "LE_SECURE_CONNECTIONS_PASSKEY", True),
           ("bondingOwner", "enum:GATEWAY,TABLET,KIOSK", "GATEWAY", True), ("service", "string", "", True), ("characteristic", "string", "", True),
           ("reconnect", "enum:AUTO,ON_DEMAND", "AUTO", False), ("passkey", "secret", "", False)],
   tests=["XT-01 bonded (LE SC)", "XT-03 indication received", "XT-04 IEEE-11073 SFLOAT decode", "XT-13 pairing policy"]),
 "P4": dict(name="LAN / Wi-Fi – HL7 v2 MLLP (IHE DEV PCD-01) or vendor TCP", how="Device or vendor gateway sends ORU^R01 (and receives ADT/ORM) over MLLP with TLS and client certificates.",
   security="HIGH", reliability="HIGH", effort="MEDIUM", latency="< 1 s", via="MLLP-TLS listener :2575 (mTLS required)",
   prerequisites=["Vendor gateway / device enrolled with an endpoint certificate", "Firewall conduit device zone → engine :2575", "Time synchronised (NTP)"],
   params=[("host", "string", "intimed.local", True), ("port", "int:1:65535", 2575, True), ("tls", "enum:MTLS,TLS,PLAINTEXT", "MTLS", True), ("tlsMin", "enum:1.3,1.2,1.1", "1.2", True),
           ("sendingApplication", "string", "", True), ("sendingFacility", "string", "", True), ("ackMode", "enum:ORIGINAL,ENHANCED", "ORIGINAL", True),
           ("profile", "enum:IHE_PCD_01,IHE_PCD_IPEC,IHE_IDCO,NONE", "IHE_PCD_01", True), ("timeZone", "string", "UTC", False), ("maxMessagesPerSecond", "int:1:1000", 50, False)],
   tests=["XT-01 TLS 1.2+/mTLS handshake", "XT-02 certificate ↔ endpoint", "XT-03 ORU acquired", "XT-04 OBX mapping", "XT-11 malformed / duplicate / skew", "XT-13 plaintext refused"]),
 "P5": dict(name="POCT1-A2 data manager / LIS middleware", how="Point-of-care analysers talk POCT1-A to the vendor data manager, analysers talk ASTM/LIS2 to middleware, which forwards results with QC/lot data.",
   security="HIGH", reliability="HIGH", effort="MEDIUM", latency="1–5 s", via="Data manager → HTTPS ingress or MLLP-TLS (mTLS)",
   prerequisites=["Data manager / middleware enrolled", "Operator list synchronised", "QC lot policy agreed with the lab"],
   params=[("dataManager", "string", "", True), ("framing", "enum:POCT1A_XML,ASTM_E1381,HL7", "POCT1A_XML", True), ("qcLotPolicy", "enum:BLOCK_UNTIL_QC,WARN,OFF", "BLOCK_UNTIL_QC", True),
           ("operatorList", "enum:SYNC_FROM_IDP,MANUAL", "SYNC_FROM_IDP", False), ("deviceIds", "string", "", False)],
   tests=["XT-03 result acquired", "XT-04 analyte mapping", "XT-14 QC lot gate", "XT-11 unknown analyte"]),
 "P6": dict(name="Vendor cloud API / webhook", how="OAuth2-secured REST APIs or HMAC-signed webhooks from vendor clouds and consumer platforms; consent-scoped for home and wearable devices.",
   security="MEDIUM", reliability="MEDIUM", effort="MEDIUM", latency="seconds to minutes", via="HTTPS webhook receiver (HMAC, replay window) or poller",
   prerequisites=["Vendor contract / BAA", "Client credentials or webhook secret in the vault", "Residency of the vendor cloud checked", "Consent model for home devices"],
   params=[("mode", "enum:WEBHOOK,POLL", "WEBHOOK", True), ("webhookSecret", "secret", "", False), ("clientId", "string", "", False), ("clientSecret", "secret", "", False),
           ("scopes", "string", "", True), ("pollSeconds", "int:30:86400", 300, False), ("rateLimitPerMinute", "int:1:6000", 60, False), ("residencyCheck", "bool", True, False)],
   tests=["XT-01 webhook signature / token", "XT-03 sample acquired", "XT-13 scopes minimal, replay refused", "XT-11 stale timestamp"]),
 "P7": dict(name="DICOM", how="C-STORE SCP, Modality Worklist, Query/Retrieve, MPPS, SR/RDSR via the DICOM router; DICOMweb to PACS/VNA.",
   security="HIGH", reliability="HIGH", effort="MEDIUM", latency="seconds", via="DICOM router (TLS) → HTTPS ingress (DICOMweb JSON)",
   prerequisites=["AE titles agreed", "DICOM TLS certificates", "MWL filter rules"],
   params=[("aeTitle", "string", "INTIMED_SCP", True), ("callingAeTitles", "string", "", True), ("port", "int:1:65535", 11112, False), ("tls", "enum:DICOM_TLS,NONE", "DICOM_TLS", True),
           ("storageClasses", "string", "", False), ("mwlFilter", "string", "", False)],
   tests=["XT-01 association", "XT-03 SR/RDSR acquired", "XT-04 tag mapping", "XT-11 missing Study UID"]),
 "P8": dict(name="HL7 FHIR R4 API", how="Devices, gateways and apps post FHIR Observation/Device per PoCD IG or PCHA with SMART backend scopes.",
   security="HIGH", reliability="HIGH", effort="LOW", latency="< 1 s", via="FHIR endpoint /fhir/r4 (OAuth2 client credentials, ES256 JWT)",
   prerequisites=["API client registered with write scope", "Profile agreed (PoCD / PCHA)"],
   params=[("endpoint", "string", "/fhir/r4/Observation", True), ("scopes", "string", "system/Observation.write", True), ("profileValidation", "enum:POCD,PCHA_LITE,NONE", "POCD", True)],
   tests=["XT-01 OAuth2 token", "XT-04 profile validation", "XT-13 scope least privilege"]),
 "P9": dict(name="Image capture & OCR / barcode (fallback)", how="The nurse photographs the display or printout; OCR proposes values; verification is mandatory before posting.",
   security="MEDIUM", reliability="LOW", effort="LOW", latency="manual", via="Capture component (camera) → OCR proposal → verification",
   prerequisites=["OCR template for the device", "Image retention policy"],
   params=[("ocrTemplate", "string", "", True), ("confidenceThreshold", "number:0.5:1", 0.85, True), ("imageRetentionDays", "int:0:3650", 30, True)],
   tests=["XT-04 OCR proposal parsed", "XT-14 verify-before-post enforced"]),
 "P10": dict(name="Manual entry with device attestation (last resort)", how="Values typed by the clinician with attestation of device model, serial and calibration; bulk CSV for campaigns.",
   security="LOW", reliability="LOW", effort="LOW", latency="manual", via="Capture component form",
   prerequisites=["Attestation fields agreed", "Validation rules per field"],
   params=[("attestationFields", "string", "model,serial,calibrationDate,attestedBy", True), ("csvImport", "bool", False, False)],
   tests=["XT-04 attestation mandatory", "XT-14 verify-before-post"]),
 "P11": dict(name="IEEE 11073 SDC (service-oriented device connectivity)", how="Discovery and subscription to OR/ICU devices publishing SDC services (BICEPS/MDPWS) – prepared adapter; local kit consumes BICEPS JSON reports.",
   security="HIGH", reliability="MEDIUM", effort="HIGH", latency="< 1 s", via="SDC consumer (stub) → pipeline",
   prerequisites=["SDC participant keys", "Discovery scope per room"],
   params=[("discoveryScope", "string", "", True), ("metricSubscription", "enum:EpisodicMetricReport,WaveformStream", "EpisodicMetricReport", True), ("participantKey", "secret", "", False)],
   tests=["XT-03 metric report acquired", "XT-04 handle mapping"]),
}

def param_schema(p):
    name, typ, default, req = p
    s = {"name": name, "required": req, "default": default}
    if typ.startswith("enum:"):
        s["type"] = "enum"; s["values"] = typ[5:].split(",")
    elif typ.startswith("int:"):
        _, lo, hi = typ.split(":"); s["type"] = "int"; s["min"] = int(lo); s["max"] = int(hi)
    elif typ.startswith("number:"):
        _, lo, hi = typ.split(":"); s["type"] = "number"; s["min"] = float(lo); s["max"] = float(hi)
    else:
        s["type"] = typ
    return s

def main():
    os.makedirs(os.path.join(ROOT, "templates"), exist_ok=True)
    os.makedirs(os.path.join(ROOT, "drivers"), exist_ok=True)
    os.makedirs(os.path.join(ROOT, "optionpacks"), exist_ok=True)
    tpl_by_id = {}
    for t in T:
        out = {k: v for k, v in t.items() if k != "sections"}
        out["version"] = "1.0.0"
        out["sections"] = [{"id": sid, "label": lab, "fields": fs} for sid, lab, fs in t["sections"]]
        tpl_by_id[t["id"]] = out
        json.dump(out, open(os.path.join(ROOT, "templates", t["id"] + ".json"), "w"), indent=1, ensure_ascii=False)
    for opt, p in OPTION_PACKS.items():
        pack = {"option": opt, **{k: v for k, v in p.items() if k != "params"}, "parameters": [param_schema(x) for x in p["params"]]}
        json.dump(pack, open(os.path.join(ROOT, "optionpacks", opt + ".json"), "w"), indent=1, ensure_ascii=False)
    for d in D:
        keys = set(d["values"].keys())
        # bindings
        bindings = {}
        prim = tpl_by_id[d["primary"]]
        def tfields(t): return [f for s in t["sections"] for f in s["fields"]]
        bindings[d["primary"]] = {f["id"]: f["id"] for f in tfields(prim) if f["id"] in keys}
        for tid, mapping in d.get("also", {}).items():
            t = tpl_by_id[tid]
            if mapping is None:
                bindings[tid] = {f["id"]: f["id"] for f in tfields(t) if f["id"] in keys}
            else:
                bindings[tid] = mapping
        # semantics from bound template fields
        sem = {}
        for tid, mapping in bindings.items():
            for f in tfields(tpl_by_id[tid]):
                k = mapping.get(f["id"])
                if k is None or k in sem:
                    continue
                s = {"key": k, "display": f["label"]}
                s.update(f.get("code", {}))
                if f["type"] == "text" or k in d.get("text_keys", []):
                    s["type"] = "text"
                else:
                    s["ucum"] = f.get("unit", "1")
                    if "range" in f:
                        s["plausible"] = f["range"]
                    if k in d.get("plausible_override", {}):
                        lo, hi = d["plausible_override"][k]; s["plausible"] = {"min": lo, "max": hi}
                    if k in d.get("artefact", {}):
                        s["artefactJumpPct"] = d["artefact"][k]
                    if "glucose" in k:
                        s["factors"] = {"mg/dL>mmol/L": 0.0555, "mmol/L>mg/dL": 18.016}
                sem[k] = s
        for k in keys:
            if k not in sem:
                raise SystemExit("driver %s key %s has no template field semantics" % (d["id"], k))
        opt = d["options"][0]
        defaults = {p[0]: p[2] for p in OPTION_PACKS[opt]["params"]}
        tr = {}
        for o in d["options"]:
            params = dict({p[0]: p[2] for p in OPTION_PACKS[o]["params"]})
            params.update(d["transport"].get(o, {}))
            sec = {"tls": "1.2+", "mtls": o in ("P1", "P2", "P3", "P4", "P5", "P7", "P9", "P10", "P11")}
            if o == "P3": sec["pairing"] = "LE_SECURE_CONNECTIONS"
            if o == "P6": sec["hmac"] = "SHA-256"; sec["replayWindowSeconds"] = 300
            if o == "P8": sec["oauth2"] = "client_credentials"
            tr[o] = {"parameters": params, "security": sec}
        recs = {k: {"value": v, "why": "Certified driver default for %s (%s)" % (d["name"].split(" ")[0], opt)} for k, v in tr[opt]["parameters"].items() if v not in ("", None)}
        for k, (v, why) in d["recs"].items():
            recs[k] = {"value": v, "why": why}
        golden = {"name": "nominal"}
        if "gdt" in d:
            golden["gdtLines"] = d["gdt"]
        elif "gatt" in d:
            golden["gatt"] = d["gatt"]
        else:
            golden["payload"] = d["golden"]
        tf = d["grammar"].get("identity", {}).get("timeFormat")
        neg = ["DUPLICATE", "MALFORMED", "TIME_SKEW", "WRONG_SUBJECT"] + (["OUT_OF_RANGE"] if d.get("out_of_range") else [])
        pkg = {
          "manifest": {"packageId": d["id"], "version": "1.0.0", "name": d["name"], "family": d["family"], "models": d["models"], "options": d["options"],
                       "compatibility": {"firmware": ">=1.0", "vendorSoftware": "certified versions per IntiMed lab"}, "author": "IntiMed driver team",
                       "sbomRef": "sbom/%s-1.0.0.cdx.json" % d["id"], "captureMode": d["captureMode"], "restricted": d.get("restricted", False),
                       "bidirectional": d.get("bidirectional", False), "certification": {"status": "CERTIFIED", "lab": "IntiMed device lab (simulator golden sessions)"}},
          "transport": tr,
          "grammar": d["grammar"],
          "semantics": [sem[k] for k in d["values"].keys()],
          "bindings": {tid: {"fields": m} for tid, m in bindings.items()},
          "recommendations": {"association": {"value": d["association"], "why": "Setting rule for %s templates" % d["primary"]}, "captureMode": {"value": d["captureMode"], "why": "Template capture pattern"},
                              "qualityGates": {"value": d.get("gates", {}), "why": "Driver quality-gate defaults (RR-11)"}, **recs},
          "tests": {"golden": [golden], "values": d["values"], "serial": d["serial"], "timeFormat": tf or "ISO",
                    "outOfRange": ({"key": d["out_of_range"][0], "value": d["out_of_range"][1]} if d.get("out_of_range") else None), "negative": neg},
          "docs": {"guide": "Configure %s with option %s. %s" % (d["name"], opt, OPTION_PACKS[opt]["how"]),
                   "troubleshooting": ["Check the endpoint certificate is ACTIVE in the security console", "Inspect the masked payload in diagnostics", "Re-run the exchange test after any change"]}}
        json.dump(pkg, open(os.path.join(ROOT, "drivers", d["id"] + "-1.0.0.json"), "w"), indent=1, ensure_ascii=False)
    print("templates:", len(T), "drivers:", len(D), "option packs:", len(OPTION_PACKS))

if __name__ == "__main__":
    main()
