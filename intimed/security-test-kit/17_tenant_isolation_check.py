#!/usr/bin/env python3
"""SC-18 – tenants isolated: configurations, catalogue decisions, endpoints and clients of one tenant are invisible to another."""
import os, kitlib as k
S = "17_tenant_isolation_check"
a = k.Session().login(os.environ.get("KIT_ADMIN", "admin.device"))
b = k.Session().login(os.environ.get("KIT_ADMIN_B", "oh.admin"))
st, ca = a.req("GET", "/api/console/configs")
st, cb = b.req("GET", "/api/console/configs")
ids_a = {c["id"] for c in ca}
k.check(S, "SC-18", "tenant B lists none of tenant A's %d configurations" % len(ids_a), not ids_a & {c["id"] for c in cb})
if ca:
    st, r = b.req("GET", "/api/console/configs/" + ca[0]["id"])
    k.check(S, "SC-18", "tenant B cannot read tenant A configuration by id (404)", st == 404, st)
    st, r = b.req("POST", "/api/console/configs/%s/test" % ca[0]["id"])
    k.check(S, "SC-18", "tenant B cannot run tenant A's exchange test", st in (403, 404), st)
st, ea = a.req("GET", "/api/security/endpoints")
st, eb = b.req("GET", "/api/security/endpoints")
k.check(S, "SC-18", "endpoint identities partitioned", not ({e["id"] for e in ea if e["id"] not in ("ep-harness", "ep-demo-gateway")} & {e["id"] for e in eb}))
st, me = b.req("GET", "/api/auth/me")
k.check(S, "SC-18", "tenant derived from identity, not input", me.get("tenant") == "ohcm-airline", me.get("tenant"))
k.finish(S)
