#!/usr/bin/env python3
"""SC-16 input – consolidates all kit results into a Markdown + JSON evidence report (results/REPORT.md)."""
import glob, json, os, time
D = os.environ.get("KIT_RESULTS", os.path.join(os.path.dirname(__file__), "results"))
rows = []
for f in sorted(glob.glob(os.path.join(D, "[0-9]*.json"))):
    rows += json.load(open(f)).get("results", [])
p = sum(r["result"] == "PASS" for r in rows)
L = ["# IntiMed security test kit – evidence report", "", "Generated %s · %d checks · %d passed · %d failed" % (time.strftime("%Y-%m-%d %H:%M UTC", time.gmtime()), len(rows), p, len(rows) - p), "",
     "| Script | Control | Check | Result | Detail |", "|---|---|---|---|---|"]
L += ["| %s | %s | %s | %s | %s |" % (r["script"], r["control"], r["check"], r["result"], str(r["detail"]).replace("|", "/")[:160]) for r in rows]
L += ["", "Not run locally (need cloud / network / hardware): 01 network segmentation, 05/05b hardening, 06 BLE radio pairing, 11 ZAP DAST (optional), 16 cloud posture."]
open(os.path.join(D, "REPORT.md"), "w").write("\n".join(L) + "\n")
json.dump({"checks": len(rows), "passed": p, "results": rows}, open(os.path.join(D, "REPORT.json"), "w"), indent=1)
print("\n".join(L[:3]))
