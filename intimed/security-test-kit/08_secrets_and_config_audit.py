#!/usr/bin/env python3
"""SC-05 / SC-15 – no secrets or PHI in configuration exports, driver packages or the knowledge base; secrets are write-only."""
import json, os, re, kitlib as k
S = "08_secrets_and_config_audit"
PATTERNS = {"private key": r"-----BEGIN [A-Z ]*PRIVATE KEY-----", "inline credential": r'"(password|clientSecret|webhookSecret|apiKey|token)"\s*:\s*"(?!secret://)[^"]{4,}"',
            "AWS key": r"AKIA[0-9A-Z]{16}", "JWT": r"eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.", "MRN/employee id": r"\b(MRN|EMP)-\d{6}\b"}
adm = k.Session().login(os.environ.get("KIT_ADMIN", "admin.device"))
st, cfgs = adm.req("GET", "/api/console/configs")
hits = []
for c in cfgs:
    st, e = adm.req("GET", "/api/console/configs/%s/export" % c["id"])
    txt = json.dumps(e)
    for n, p in PATTERNS.items():
        if n != "MRN/employee id" and re.search(p, txt): hits.append((c["id"], n))
    st2, srv = adm.req("POST", "/api/security/scan/secrets", txt)
    if srv.get("findings"): hits.append((c["id"], "server scanner"))
k.check(S, "SC-05", "%d configuration exports free of secrets (local + server scanner)" % len(cfgs), not hits, hits[:5])
st, drivers = adm.req("GET", "/api/drivers")
dh = []
for d in drivers:
    st, pkg = adm.req("GET", "/api/drivers/" + d["ref"])
    for n, p in PATTERNS.items():
        if n in ("private key", "inline credential", "AWS key", "JWT") and re.search(p, json.dumps(pkg)): dh.append((d["ref"], n))
k.check(S, "SC-05", "%d driver packages free of secrets" % len(drivers), not dh, dh[:5])
st, kb = adm.req("GET", "/api/console/knowledge-base")
kbtxt = json.dumps(kb)
k.check(S, "SC-15", "knowledge base has no PHI, tenant ids, hosts or paths (%d fingerprints)" % len(kb) if isinstance(kb, list) else 0,
        st == 200 and not re.search(PATTERNS["MRN/employee id"], kbtxt) and "acme-health" not in kbtxt and not re.search(r'[A-Za-z]:\\\\\\\\|/mnt/|/home/|ProgramData', kbtxt) and "secret://" not in kbtxt)
soc = k.Session().login(os.environ.get("KIT_SOC", "soc.analyst"))
st, sec = soc.req("GET", "/api/security/secrets")
k.check(S, "SC-05", "secrets API lists names only (write-only values)", st == 200 and "values" not in sec and all(isinstance(n, str) for n in sec.get("names", [])), sec.get("provider"))
k.finish(S)
