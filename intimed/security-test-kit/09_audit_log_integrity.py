#!/usr/bin/env python3
"""SC-08 – tamper-evident audit: hash chain verifies end to end; SIEM sink receives every record; details are PHI-masked."""
import json, os, re, kitlib as k
S = "09_audit_log_integrity"
soc = k.Session().login(os.environ.get("KIT_SOC", "soc.analyst"))
st, v = soc.req("GET", "/api/security/audit/verify")
k.check(S, "SC-08", "audit hash chain intact", st == 200 and v.get("valid") is True, v)
st, rows = soc.req("GET", "/api/security/audit?limit=500")
phi = [r for r in rows if re.search(r"\b(MRN|EMP)-\d{6}\b", str(r.get("detail")))]
k.check(S, "SC-08", "audit details PHI-masked (%d records sampled)" % len(rows), not phi, phi[:2])
siem = os.path.join(k.HOME, "siem", "audit.jsonl")
if os.path.exists(siem):
    lines = open(siem).read().splitlines()
    last = json.loads(lines[-1]) if lines else {}
    k.check(S, "SC-08", "SIEM sink receives records with hashes (%d lines)" % len(lines), bool(lines) and len(last.get("hash", "")) == 64)
else:
    k.check(S, "SC-08", "SIEM sink file present", False, siem)
k.finish(S)
