#!/usr/bin/env python3
"""SC-19 – recommendations carry provenance and confidence, are never auto-applied outside development stages,
insecure values are blocked by policy regardless of recommendations, decisions are audited."""
import os, kitlib as k
S = "18_recommendation_guardrails_check"
adm = k.Session().login(os.environ.get("KIT_ADMIN", "admin.device"))
st, cfgs = adm.req("GET", "/api/console/configs")
prod = [c for c in cfgs if c.get("stage") in ("UAT", "PROD")]
if prod:
    st, r = adm.req("POST", "/api/console/configs/%s/accept-all?step=4" % prod[0]["id"])
    k.check(S, "SC-19", "'accept all' refused for a %s configuration" % prod[0]["stage"], st == 403 and "SC-19" in str(r), st)
st, c = adm.req("POST", "/api/console/configs", {"modelId": "IMD-037", "name": "kit guardrail probe"})
cid = c["id"]
adm.req("PUT", "/api/console/configs/%s/steps/1" % cid, {"driver": "bp.and.ua651-ble@1.0.0"})
adm.req("PUT", "/api/console/configs/%s/steps/2" % cid, {"option": "P3"})
st, recs = adm.req("GET", "/api/console/configs/%s/recommendations?step=4" % cid)
k.check(S, "SC-19", "every recommendation has rule, source, why and confidence (%d)" % len(recs), all(r.get("rule") and r.get("source") and r.get("why") and r.get("confidence") for r in recs))
k.check(S, "SC-19", "recommendations start PROPOSED (nothing applied)", all(r["status"] == "PROPOSED" for r in recs))
st, v = adm.req("PUT", "/api/console/configs/%s/steps/4" % cid, {"params": {"pairing": "JUST_WORKS"}})
k.check(S, "RR-03", "Just-Works pairing blocked by tenant policy", v.get("blocked") is True and "RR-03" in str(v.get("violations")))
st, v = adm.req("POST", "/api/console/configs/%s/validate" % cid, {"params": {"tls": "PLAINTEXT"}})
k.check(S, "RR-04", "plaintext MLLP blocked", "RR-04" in str(v))
st, v = adm.req("POST", "/api/console/configs/%s/validate" % cid, {"params": {"passkey": "123456"}})
k.check(S, "SC-05", "inline secret in configuration blocked", "SC-05" in str(v) or "vault" in str(v))
dec = recs[0]
st, r = adm.req("POST", "/api/console/recommendations/%s/decision" % dec["id"], {"decision": "OVERRIDE"})
k.check(S, "SC-19", "override without a reason refused", st == 400, st)
st, r = adm.req("POST", "/api/console/recommendations/%s/decision" % dec["id"], {"decision": "OVERRIDE", "reason": "kit test"})
st, aud = adm.req("GET", "/api/console/configs/%s/audit" % cid)
k.check(S, "SC-19", "decision written to the audit trail with provenance", any(a["action"] == "config.decision" for a in aud))
adm.req("POST", "/api/console/configs/%s/decommission?revokeEndpoint=false" % cid)
k.finish(S)
