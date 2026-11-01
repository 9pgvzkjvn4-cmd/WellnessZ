#!/usr/bin/env python3
"""SC-20 – no publication without a passing exchange test for the current configuration hash; evidence sealed and verifiable."""
import hashlib, io, json, os, zipfile, kitlib as k
S = "19_exchange_harness_gate_check"
adm = k.Session().login(os.environ.get("KIT_ADMIN", "admin.device"))
st, c = adm.req("POST", "/api/console/configs", {"modelId": "IMD-033", "name": "kit gate probe", "site": {"site": "Kit", "station": "KIT-1"}})
cid = c["id"]
adm.req("PUT", "/api/console/configs/%s/steps/1" % cid, {"driver": "oximeter.nonin.3230-ble@1.0.0"})
for step in (2, 3, 4, 5):
    adm.req("GET", "/api/console/configs/%s/recommendations?step=%d" % (cid, step))
    adm.req("POST", "/api/console/configs/%s/accept-all?step=%d" % (cid, step))
st, r = adm.req("POST", "/api/console/configs/%s/publish?stage=DEV" % cid)
k.check(S, "SC-20", "publish without exchange test blocked (409 publish-gate)", st == 409 and "publish gate" in str(r), st)
st, run = adm.req("POST", "/api/console/configs/%s/test" % cid)
k.check(S, "SC-20", "exchange test executed (%s %s/%s)" % (run.get("status"), run.get("passed"), run.get("total")), st == 200 and run.get("status") == "PASSED", [x["id"] + " " + x["detail"] for x in run.get("results", {}).values() if x["status"] == "FAIL"][:3])
st, v = adm.req("GET", "/api/console/runs/%s/verify" % run["id"])
k.check(S, "SC-20", "evidence seal verifies on the server", v.get("valid") is True, v)
st, z = adm.req("GET", "/api/console/runs/%s/evidence" % run["id"], raw=True)
zf = zipfile.ZipFile(io.BytesIO(z))
seal = json.loads(zf.read("seal.json"))
ok = all(hashlib.sha256(zf.read(n)).hexdigest() == d for n, d in seal["manifest"]["files"].items())
k.check(S, "SC-20", "evidence digests recomputed independently (%d files)" % len(seal["manifest"]["files"]), ok)
k.check(S, "SC-20", "evidence contains no clear PHI test subject outside the manifest", b"MRN-1002" not in zf.read("traces_masked.txt"))
adm.req("PUT", "/api/console/configs/%s/steps/4" % cid, {"params": {"reconnect": "ON_DEMAND"}})
st, cfg = adm.req("GET", "/api/console/configs/" + cid)
k.check(S, "SC-20", "technical change invalidates the passing run (re-test required)", cfg["status"] == "DRAFT" and cfg["hash"] != cfg.get("lastPassingRunHash"))
adm.req("POST", "/api/console/configs/%s/decommission?revokeEndpoint=false" % cid)
k.finish(S)
