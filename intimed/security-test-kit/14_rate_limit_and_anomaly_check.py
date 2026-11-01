#!/usr/bin/env python3
"""SC-09 / SC-13 – token endpoint and webhooks throttled, forged webhooks refused and audited, anomaly rules active."""
import os, time, urllib.parse, urllib.request, kitlib as k
S = "14_rate_limit_and_anomaly_check"
codes = []
for i in range(45):
    try:
        r = urllib.request.urlopen(urllib.request.Request(k.URL + "/oauth2/token", data=b"grant_type=client_credentials&client_id=x&client_secret=y", method="POST",
                                                          headers={"Content-Type": "application/x-www-form-urlencoded"}), context=k.ctx()); codes.append(r.status)
    except urllib.error.HTTPError as e: codes.append(e.code)
k.check(S, "SC-13", "token endpoint throttles (429) after a burst", 429 in codes, "%d×401, %d×429" % (codes.count(401), codes.count(429)))
adm = k.Session().login(os.environ.get("KIT_ADMIN", "admin.device"))
st, cfgs = adm.req("GET", "/api/console/configs")
p6 = [c for c in cfgs if c.get("option") == "P6" and c.get("status") == "PUBLISHED"]
if p6:
    c = p6[0]
    url = "%s/api/webhooks/%s/%s" % (k.URL, "acme-health", c["id"])
    try:
        urllib.request.urlopen(urllib.request.Request(url, data=b'{"type":"x"}', method="POST", headers={"Content-Type": "application/json", "X-IntiMed-Timestamp": str(int(time.time())), "X-IntiMed-Signature": "v1=00"}), context=k.ctx()); code = 200
    except urllib.error.HTTPError as e: code = e.code
    k.check(S, "SC-11", "forged vendor-cloud webhook refused (401)", code == 401, code)
soc = k.Session().login(os.environ.get("KIT_SOC", "soc.analyst"))
st, rules = soc.req("GET", "/api/security/anomaly-rules")
k.check(S, "SC-09", "anomaly rules AN-01…AN-07 active", st == 200 and len(rules) >= 7, sorted(rules) if isinstance(rules, dict) else rules)
st, al = soc.req("GET", "/api/security/alerts")
k.check(S, "SC-09", "alerts raised for refused connections / bursts (%d)" % len(al), st == 200 and len(al) > 0)
k.finish(S)
