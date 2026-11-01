"""Shared helpers for the IntiMed security test kit (Python 3 standard library only – runs on a stock macOS python3).
Configuration via environment: INTIMED_URL (default https://localhost:9443), INTIMED_CA (root CA PEM, default
$INTIMED_HOME/pki/root-ca.pem), INTIMED_USER / INTIMED_PASSWORD (demo personas), INTIMED_MLLP_PORT, INTIMED_INGRESS_PORT."""
import http.cookiejar, json, os, socket, ssl, sys, time, urllib.parse, urllib.request

URL = os.environ.get("INTIMED_URL", "https://localhost:9443")
HOME = os.environ.get("INTIMED_HOME", os.path.expanduser("~/IntiMed-Demo-Data"))
CA = os.environ.get("INTIMED_CA", os.path.join(HOME, "pki", "root-ca.pem"))
MLLP_PORT = int(os.environ.get("INTIMED_MLLP_PORT", "2575"))
INGRESS_PORT = int(os.environ.get("INTIMED_INGRESS_PORT", "8443"))
DEMO_PASSWORD = os.environ.get("INTIMED_PASSWORD", "IntiMed-Demo-2026!")
RESULTS = []

def ctx(cert=None, key=None):
    c = ssl.create_default_context(cafile=CA) if os.path.exists(CA) else ssl._create_unverified_context()
    if cert:
        c.load_cert_chain(cert, key)
    return c

class Session:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPSHandler(context=ctx()), urllib.request.HTTPCookieProcessor(self.jar))
        self.csrf = None

    def _csrf(self):
        for c in self.jar:
            if c.name == "XSRF-TOKEN":
                return c.value
        return None

    def req(self, method, path, body=None, headers=None, raw=False):
        h = {"Accept": "application/json"}
        data = None
        if body is not None:
            data = body.encode() if isinstance(body, str) else json.dumps(body).encode()
            h["Content-Type"] = "application/json"
        if method not in ("GET", "HEAD") and self._csrf():
            h["X-XSRF-TOKEN"] = self._csrf()
        h.update(headers or {})
        r = urllib.request.Request(URL + path, data=data, method=method, headers=h)
        try:
            with self.opener.open(r, timeout=120) as resp:
                b = resp.read()
                return resp.status, (b if raw else (json.loads(b) if b and b[:1] in b"[{" else b.decode(errors="replace")))
        except urllib.error.HTTPError as e:
            b = e.read()
            try:
                return e.code, json.loads(b)
            except Exception:
                return e.code, b.decode(errors="replace")

    def login(self, user, password=DEMO_PASSWORD):
        self.req("GET", "/api/auth/csrf")
        s, r = self.req("POST", "/api/auth/login", {"username": user, "password": password})
        if s != 200:
            raise RuntimeError("login failed %s %s" % (s, r))
        if r.get("mfaRequired"):
            s2, code = self.req("GET", "/api/auth/demo-mfa")
            if s2 != 200:
                raise RuntimeError("MFA code unavailable (demo profile only): %s" % code)
            s3, r3 = self.req("POST", "/api/auth/mfa", {"code": code["code"]})
            if s3 != 200:
                raise RuntimeError("MFA failed %s" % r3)
        return self

def check(script, cid, name, ok, detail=""):
    RESULTS.append({"script": script, "control": cid, "check": name, "result": "PASS" if ok else "FAIL", "detail": str(detail)[:400]})
    print(("  PASS " if ok else "  FAIL ") + "%s %s %s" % (cid, name, ("– " + str(detail)[:200]) if detail else ""))
    return ok

def finish(script):
    out = os.environ.get("KIT_RESULTS", os.path.join(os.path.dirname(__file__), "results"))
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, script + ".json"), "w") as f:
        json.dump({"script": script, "at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "results": RESULTS}, f, indent=1)
    fails = [r for r in RESULTS if r["result"] != "PASS"]
    print("%s: %d checks, %d failed" % (script, len(RESULTS), len(fails)))
    sys.exit(1 if fails else 0)
