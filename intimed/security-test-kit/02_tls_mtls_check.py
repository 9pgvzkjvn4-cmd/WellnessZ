#!/usr/bin/env python3
"""SC-01 / SC-14 – TLS on every port: console TLS 1.2+, HSTS, certificate validity > 30 days; device ports require client certificates."""
import socket, ssl, datetime, urllib.parse, kitlib as k
S = "02_tls_mtls_check"
host = urllib.parse.urlparse(k.URL).hostname; port = urllib.parse.urlparse(k.URL).port or 443
def handshake(p, ctx):
    try:
        with socket.create_connection((host, p), timeout=5) as s, ctx.wrap_socket(s, server_hostname=host) as t:
            return t.version(), t.getpeercert()
    except Exception as e:
        return None, str(e)
v, cert = handshake(port, k.ctx())
k.check(S, "SC-01", "console negotiates TLS 1.2/1.3", v in ("TLSv1.2", "TLSv1.3"), v)
if isinstance(cert, dict):
    left = (datetime.datetime.strptime(cert["notAfter"], "%b %d %H:%M:%S %Y %Z") - datetime.datetime.utcnow()).days
    k.check(S, "SC-14", "console certificate valid > 30 days", left > 30, "%d days left" % left)
legacy = k.ctx(); legacy.minimum_version = ssl.TLSVersion.TLSv1; legacy.maximum_version = ssl.TLSVersion.TLSv1_1
try: legacy.set_ciphers("DEFAULT:@SECLEVEL=0")
except ssl.SSLError: pass
v2, _ = handshake(port, legacy)
k.check(S, "SC-01", "console refuses TLS 1.0/1.1", v2 is None, v2 or "refused")
st, _ = k.Session().req("GET", "/api/public/info")
import urllib.request
r = urllib.request.urlopen(urllib.request.Request(k.URL + "/api/public/info"), context=k.ctx())
k.check(S, "SC-01", "HSTS header present", "max-age" in (r.headers.get("Strict-Transport-Security") or ""), r.headers.get("Strict-Transport-Security"))
k.check(S, "SC-11", "strict CSP without unsafe-inline", "script-src 'self'" in (r.headers.get("Content-Security-Policy") or "") and "unsafe-inline" not in r.headers.get("Content-Security-Policy", ""), r.headers.get("Content-Security-Policy"))
for name, p in (("MLLP-TLS", k.MLLP_PORT), ("device ingress", k.INGRESS_PORT)):
    try:
        with socket.create_connection((host, p), timeout=5) as s, k.ctx().wrap_socket(s, server_hostname=host) as t:
            t.settimeout(5)
            if name == "MLLP-TLS":
                t.sendall(b"\x0bMSH|^~\\&|KIT|KIT|I|I|20260101||ORU^R01|1|P|2.6\r\x1c\x0d")
                got = t.recv(256)
            else:
                t.sendall(b"POST /ingest HTTP/1.1\r\nHost: x\r\nContent-Length: 2\r\n\r\n{}")
                got = t.recv(256)
            ok = b"MSA|" not in got and b"HTTP/1.1 200" not in got
    except Exception as e:
        ok, got = True, str(e)
    k.check(S, "SC-01", name + " refuses clients without a certificate", ok, str(got)[:120])
    try:
        with socket.create_connection((host, p), timeout=5) as s:
            s.sendall(b"\x0bMSH|^~\\&|PLAIN|PLAIN|I|I|20260101||ORU^R01|1|P|2.6\r\x1c\x0d"); s.settimeout(5)
            got = s.recv(256)
        k.check(S, "SC-01", name + " refuses plaintext", b"MSA|" not in got and b"HTTP/1.1 200" not in got, repr(got[:40]))
    except Exception as e:
        k.check(S, "SC-01", name + " refuses plaintext", True, str(e))
k.finish(S)
