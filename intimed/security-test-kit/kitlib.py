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
            if os.environ.get("INTIMED_TOTP_SECRET"):
                s3, r3 = self.req("POST", "/api/auth/mfa", {"code": totp(os.environ["INTIMED_TOTP_SECRET"])})
                if s3 != 200:
                    raise RuntimeError("MFA failed %s" % r3)
                return self
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


def totp(secret_b32, step=None):
    import base64, hmac, hashlib, struct
    key = base64.b32decode(secret_b32 + "=" * (-len(secret_b32) % 8))
    step = int(time.time() // 30) if step is None else step
    h = hmac.new(key, struct.pack(">Q", step), hashlib.sha1).digest()
    o = h[-1] & 15
    return "%06d" % ((struct.unpack(">I", h[o:o + 4])[0] & 0x7fffffff) % 1000000)

def mllp(sock, msg):
    sock.sendall(b"\x0b" + msg.encode() + b"\x1c\x0d")
    buf = b""
    sock.settimeout(10)
    while not buf.endswith(b"\x1c\x0d"):
        chunk = sock.recv(4096)
        if not chunk:
            break
        buf += chunk
    return buf.strip(b"\x0b\x1c\x0d").decode(errors="replace")

def enrol_test_endpoint(session, workdir, name="Security kit probe"):
    """Registers an endpoint, creates key + CSR with the system openssl, enrols, returns (cert, key) paths."""
    import subprocess, tempfile
    os.makedirs(workdir, exist_ok=True)
    s, r = session.req("POST", "/api/security/endpoints", {"name": name, "type": "GATEWAY", "site": "kit", "zone": "MDZ-KIT"})
    if s != 200:
        raise RuntimeError("endpoint registration failed %s %s" % (s, r))
    key, csr = os.path.join(workdir, "probe.key"), os.path.join(workdir, "probe.csr")
    subprocess.run(["openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", key], check=True, capture_output=True)
    subprocess.run(["openssl", "req", "-new", "-key", key, "-subj", "/CN=kit-probe", "-out", csr], check=True, capture_output=True)
    s2, e = session.req("POST", "/api/enrol", {"token": r["enrolmentToken"], "csr": open(csr).read()})
    if s2 != 200:
        raise RuntimeError("enrolment failed %s %s" % (s2, e))
    crt = os.path.join(workdir, "probe.crt")
    open(crt, "w").write(e["certificate"] + e["chain"])
    return crt, key, r["endpoint"]["id"], r["enrolmentToken"]
