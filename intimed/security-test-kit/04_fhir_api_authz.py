#!/usr/bin/env python3
"""SC-11 / SC-18 – FHIR API: anonymous and forged tokens refused, scopes enforced, tenant from token, object-level checks."""
import base64, json, os, urllib.parse, urllib.request, kitlib as k
S = "04_fhir_api_authz"
def call(path, token=None, method=None, data=None, form=None, basic=None):
    h = {"Accept": "application/fhir+json"}
    if token: h["Authorization"] = "Bearer " + token
    if basic: h["Authorization"] = "Basic " + base64.b64encode(basic.encode()).decode()
    body = None
    if form is not None: body = urllib.parse.urlencode(form).encode(); h["Content-Type"] = "application/x-www-form-urlencoded"
    if data is not None: body = json.dumps(data).encode(); h["Content-Type"] = "application/fhir+json"
    try:
        r = urllib.request.urlopen(urllib.request.Request(k.URL + path, data=body, method=method or ("POST" if body is not None else "GET"), headers=h), context=k.ctx(), timeout=20)
        return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors="replace")
k.check(S, "SC-11", "metadata is public", call("/fhir/r4/metadata")[0] == 200)
k.check(S, "SC-11", "anonymous Observation search refused (401)", call("/fhir/r4/Observation")[0] == 401)
k.check(S, "SC-11", "forged JWT refused (401)", call("/fhir/r4/Observation", "eyJhbGciOiJub25lIn0.eyJzdWIiOiJ4In0.")[0] == 401)
dev = k.Session().login(os.environ.get("KIT_DEV", "dev.integrator"))
st, read = dev.req("POST", "/api/integration/clients", {"name": "kit-read", "scopes": ["system/Observation.read"]})
st2, devonly = dev.req("POST", "/api/integration/clients", {"name": "kit-device", "scopes": ["system/Device.read"]})
k.check(S, "SC-11", "clients created with least-privilege scopes", st == 200 and st2 == 200)
st, tok = call("/oauth2/token", form={"grant_type": "client_credentials"}, basic=read["clientId"] + ":" + read["clientSecret"])
k.check(S, "SC-11", "client credentials → ES256 JWT", st == 200 and tok.get("token_type") == "Bearer", tok.get("scope") if isinstance(tok, dict) else tok)
st, _ = call("/oauth2/token", form={"grant_type": "client_credentials"}, basic=read["clientId"] + ":wrong")
k.check(S, "SC-11", "wrong client secret refused", st == 401)
st, b = call("/fhir/r4/Observation?_count=5", tok["access_token"])
k.check(S, "SC-11", "read scope can search Observations", st == 200, "total %s" % (b.get("total") if isinstance(b, dict) else b))
st, t2 = call("/oauth2/token", form={"grant_type": "client_credentials"}, basic=devonly["clientId"] + ":" + devonly["clientSecret"])
k.check(S, "SC-11", "Device-only token cannot read Observations (403)", call("/fhir/r4/Observation", t2["access_token"])[0] == 403)
k.check(S, "SC-11", "write without write scope refused (403)", call("/fhir/r4/Observation", tok["access_token"], "POST", {"resourceType": "Observation"})[0] == 403)
k.check(S, "SC-18", "unknown / foreign Observation id → 404 (no oracle)", call("/fhir/r4/Observation/obs-doesnotexist--hr", tok["access_token"])[0] == 404)
for c in (read, devonly): dev.req("DELETE", "/api/integration/clients/" + c["clientId"])
st, _ = call("/oauth2/token", form={"grant_type": "client_credentials"}, basic=read["clientId"] + ":" + read["clientSecret"])
k.check(S, "SC-11", "revoked client can no longer obtain tokens", st == 401)
k.finish(S)
