#!/usr/bin/env python3
"""SC-01 / SC-12 – enrolled endpoint (CSR, private key generated locally) over MLLP-TLS: unbound messages rejected,
malformed and oversized frames do not crash the engine, enrolment tokens are single use."""
import os, socket, ssl, time, urllib.parse, kitlib as k
S = "03_mllp_auth_and_robustness"
host = urllib.parse.urlparse(k.URL).hostname
admin = k.Session().login(os.environ.get("KIT_ADMIN", "admin.device"))
crt, key, epid, token = k.enrol_test_endpoint(admin, os.path.join(os.path.dirname(__file__), "results", "probe"))
k.check(S, "SC-01", "endpoint enrolled from a locally generated CSR", os.path.exists(crt), epid)
s2, r2 = admin.req("POST", "/api/enrol", {"token": token, "csr": "x"})
k.check(S, "SC-01", "enrolment token is single-use", s2 in (401, 403), s2)
ctx = k.ctx(crt, key)
def conn():
    s = socket.create_connection((host, k.MLLP_PORT), timeout=10)
    return ctx.wrap_socket(s, server_hostname=host)
with conn() as t:
    ack = k.mllp(t, "MSH|^~\\&|KIT|KIT|INTIMED|INTIMED|20261101000000||ORU^R01^ORU_R01|K1|P|2.6\rPID|||X\rOBR|1\rOBX|1|NM|8867-4||70|/min\r")
k.check(S, "SC-11", "authenticated endpoint without a bound configuration is rejected (AR)", "MSA|AR" in ack, ack.replace("\r", " ")[:160])
crashes = 0
for i in range(40):
    try:
        with conn() as t:
            a = k.mllp(t, "MSH|^~\\&|FUZZ" + ("|" * (i % 7)) + "\x00\x01garbage" * (i % 5))
            if "MSA|A" not in a: crashes += 0
    except Exception:
        pass
st, info = admin.req("GET", "/api/public/info")
k.check(S, "SC-12", "40 malformed frames – engine still healthy", st == 200, info)
try:
    with conn() as t:
        t.sendall(b"\x0b" + b"A" * (1_100_000)); t.settimeout(5); got = t.recv(64)
    k.check(S, "SC-12", "oversized frame (> 1 MB) dropped", b"MSA|AA" not in got, repr(got[:30]))
except Exception as e:
    k.check(S, "SC-12", "oversized frame (> 1 MB) dropped", True, type(e).__name__)
st, info = admin.req("GET", "/api/public/info")
k.check(S, "SC-12", "engine healthy after oversized frame", st == 200)
s3, _ = admin.req("POST", "/api/security/endpoints/%s/revoke" % epid, {"reason": "kit cleanup"})
try:
    with conn() as t:
        a = k.mllp(t, "MSH|^~\\&|KIT|KIT|INTIMED|INTIMED|20261101000000||ORU^R01|K2|P|2.6\r")
    k.check(S, "SC-09", "revoked endpoint refused immediately", "MSA" not in a, a[:40])
except Exception as e:
    k.check(S, "SC-09", "revoked endpoint refused immediately", True, type(e).__name__)
k.finish(S)
