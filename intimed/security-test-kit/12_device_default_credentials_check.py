#!/usr/bin/env python3
"""SC-07 / SC-11 – no default credentials; brute force throttled; enrolment requires a one-time token."""
import os, kitlib as k
S = "12_device_default_credentials_check"
for u, p in (("admin", "admin"), ("admin", "password"), ("root", "root"), ("intimed", "intimed"), ("admin.device", "admin")):
    s = k.Session(); s.req("GET", "/api/auth/csrf")
    st, r = s.req("POST", "/api/auth/login", {"username": u, "password": p})
    k.check(S, "SC-07", "default credential %s/%s refused" % (u, p), st in (403, 429), st)
s = k.Session(); s.req("GET", "/api/auth/csrf")
codes = [s.req("POST", "/api/auth/login", {"username": "nobody-%d" % i, "password": "x"})[0] for i in range(16)]
k.check(S, "SC-13", "login brute force throttled (429)", 429 in codes, codes[-5:])
st, r = k.Session().req("POST", "/api/enrol", {"token": "enr_guess", "csr": "x"})
k.check(S, "SC-01", "enrolment without a valid one-time token refused", st in (401, 403, 429), st)
k.finish(S)
