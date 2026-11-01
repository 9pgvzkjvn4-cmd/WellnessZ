#!/usr/bin/env python3
"""SC-14 – clock agreement with the platform, endpoint certificates expiring within 30 days, rotation available."""
import email.utils, os, time, urllib.request, kitlib as k
S = "13_time_sync_and_cert_expiry"
r = urllib.request.urlopen(k.URL + "/api/public/info", context=k.ctx())
srv = email.utils.parsedate_to_datetime(r.headers["Date"]).timestamp()
k.check(S, "SC-14", "server clock within 5 s of this host", abs(srv - time.time()) < 5, "%.1f s" % (srv - time.time()))
soc = k.Session().login(os.environ.get("KIT_SOC", "soc.analyst"))
st, exp = soc.req("GET", "/api/security/endpoints/expiring?days=30")
k.check(S, "SC-14", "no ACTIVE endpoint certificate expires within 30 days", st == 200 and not exp, [e["id"] for e in exp] if isinstance(exp, list) else exp)
st, eps = soc.req("GET", "/api/security/endpoints")
active = [e for e in eps if e.get("status") == "ACTIVE" and e.get("notAfter")]
k.check(S, "SC-14", "%d active endpoints carry certificates with expiry tracked" % len(active), len(active) > 0)
k.finish(S)
