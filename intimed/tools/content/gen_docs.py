#!/usr/bin/env python3
"""Generates seed/delivery-status.json and the traceability documents from tools/content/traceability.py."""
import json, os, sys, collections
sys.path.insert(0, os.path.dirname(__file__))
import traceability as T
R = os.path.join(os.path.dirname(__file__), "..", "..")
SEED = os.path.join(R, "intimed-platform", "src", "main", "resources", "seed")
DOC = os.path.join(R, "docs", "traceability")
os.makedirs(DOC, exist_ok=True)
bl = json.load(open(os.path.join(SEED, "backlog.json")))
hl = json.load(open(os.path.join(SEED, "hlrs.json")))
fe = json.load(open(os.path.join(SEED, "features.json")))
ep = json.load(open(os.path.join(SEED, "epics.json")))
sp = json.load(open(os.path.join(SEED, "sprints.json")))

items = {}
for b in bl:
    st, inc, ev, reason, strat = T.S[b["id"]]
    items[b["id"]] = {k: v for k, v in {"status": st, "increment": inc, "evidence": ev, "reason": reason, "strategy": strat}.items() if v}
cap = collections.defaultdict(lambda: {"planned": 0, "delivered": 0, "partial": 0, "deferred": 0, "items": 0})
for b in bl:
    c = cap[b["sprint"]]; c["planned"] += b["sp"]; c["items"] += 1
    c[{"DELIVERED": "delivered", "PARTIAL": "partial", "DEFERRED": "deferred"}[items[b["id"]]["status"]]] += b["sp"]
json.dump({"items": items, "increments": T.INCREMENTS, "capacity": cap}, open(os.path.join(SEED, "delivery-status.json"), "w"), indent=1, ensure_ascii=False)

def esc(s): return (s or "").replace("|", "\\|").replace("\n", " ")
tot = collections.Counter(); tsp = collections.Counter()
for b in bl: tot[items[b["id"]]["status"]] += 1; tsp[items[b["id"]]["status"]] += b["sp"]

# --- matrix
L = ["# IntiMed – Requirements Traceability Matrix (local-scope release 1.0.0-local)", "",
     "Generated from `tools/content/traceability.py` (single source of truth, also served at `/api/quality/backlog`).", "",
     "| | Items | Story points |", "|---|---:|---:|"]
for s in ("DELIVERED", "PARTIAL", "DEFERRED"): L.append("| %s | %d | %d |" % (s, tot[s], tsp[s]))
L += ["| **Total** | **%d** | **%d** |" % (len(bl), sum(b["sp"] for b in bl)), "",
      "Status: **DELIVERED** – complete for the LOCAL (MacBook) environment setting; **PARTIAL** – implemented and tested locally, the remainder needs cloud, real devices, partners or the organisation; **DEFERRED** – not applicable to a local scope (see `Deferred-and-Partial-Backlog.md`).", ""]
for e in ep:
    L += ["## %s – %s" % (e["epic"], e["name"].split(" – ")[0]), "", "_" + e["goal"][:400] + ("…_" if len(e["goal"]) > 400 else "_"), "", "### High-level requirements → features", "",
          "| Feature | Name | HLRs | Backlog items (status) |", "|---|---|---|---|"]
    for f in [x for x in fe if x["id"].startswith(e["epic"])]:
        its = [b for b in bl if b["feature"] == f["id"]]
        L.append("| %s | %s | %s | %s |" % (f["id"], esc(f["name"]), esc(f["hlrs"]), "<br>".join("%s (%s)" % (b["id"], items[b["id"]]["status"]) for b in its)))
    L += ["", "### Backlog → implementation evidence", "", "| Backlog | Title | SP | Sprint | Status | Increment | Evidence / reason |", "|---|---|---:|---|---|---|---|"]
    for b in [x for x in bl if x["id"].startswith(e["epic"])]:
        i = items[b["id"]]
        L.append("| %s | %s | %d | %s | %s | %s | %s |" % (b["id"], esc(b["title"]), b["sp"], b["sprint"], i["status"], i.get("increment", "—"), esc(i.get("evidence") or i.get("reason"))))
    L.append("")
L += ["## HLR index", "", "| HLR | Requirement (abridged) | Priority |", "|---|---|---|"] + ["| %s | %s | %s |" % (h["id"], esc(h["requirement"][:220]), h["priority"]) for h in hl]
L += ["", "## Tests", "", "| Test class | What it proves |", "|---|---|",
      "| `UnitTests` (36) | All 26 driver packages valid, signed, secret-free, golden session parses every semantic key, malformed rejected; XXE refused; SFLOAT; serial checksum; GDT length; payload size; UCUM; AES-GCM context binding; RFC 6238 TOTP vector; PHI masking; secret scanner |",
      "| `PlatformIntegrationTest` (10) | Wizard with intelligence → exchange test 15/15 over real mTLS → four-eyes refusal/approval → publish gate → DEV/UAT, PROD needs SRE; live capture files into TPL-18, PHI encrypted at rest, idempotent re-send; re-test after change; RR-03 block; SC-19 accept-all refused in UAT; plaintext/no-cert/revoked refused; anomaly isolation + re-certification gate; tenant isolation (ciphertext copied across tenants cannot be read); audit tamper detection; HTTP controls (401, CSRF, CSP, HSTS, FHIR auth, webhook HMAC); password policy and lockout |",
      "| `ArchitectureTest` (4) | Engine core independent of control plane; security independent of engine; templates host-agnostic; nothing depends on the demo |",
      "| `security-test-kit/` | SC-01…SC-20 local scripts against the running platform (see kit README) |"]
open(os.path.join(DOC, "Traceability-Matrix.md"), "w").write("\n".join(L) + "\n")

# --- deferred / partial
groups = collections.OrderedDict([("Cloud environments & operations (Azure / GCP / private cloud)", []), ("Edge hardware, radios and native apps", []), ("Real devices, vendors & partners", []),
                                  ("Organisation, assurance & regulatory programmes", []), ("Commercial / P3 scale", []), ("Other", [])])
def group(b, i):
    t = (i.get("reason") or "").lower() + " " + b["title"].lower()
    if any(k in t for k in ["azure", "gcp", "cloud", "cluster", "ha/dr", "observability", "registr", "argo", "soak", "operated", "saas"]): return list(groups)[0]
    if any(k in t for k in ["gateway", "ble", "radio", "mobile", "ios", "android", "kiosk", "image", "hardware", "msi", "smart app", "serial", "vendor pc", "device host"]): return list(groups)[1]
    if any(k in t for k in ["vendor", "partner", "lab", "device", "analyser", "pacs", "connectathon", "ehr", "uat"]): return list(groups)[2]
    if any(k in t for k in ["soc 2", "iso", "pen", "dpo", "cso", "qms", "usability", "post-market", "finance", "clinical safety", "pilot", "sites", "organisation"]): return list(groups)[3]
    if any(k in t for k in ["p3", "commercial", "billing", "white-label", "marketplace", "trial", "benchmark", "customer"]): return list(groups)[4]
    return list(groups)[5]
for b in bl:
    i = items[b["id"]]
    if i["status"] != "DELIVERED": groups[group(b, i)].append((b, i))
L = ["# IntiMed – Backlog not (fully) applicable to the local scope", "",
     "This release targets the **LOCAL environment setting** (a MacBook running the whole platform with simulators). Every backlog item below is either **DEFERRED** (not applicable locally) or **PARTIAL** (implemented and tested locally; the remainder needs cloud, real devices, partners or organisational programmes). For each item: why it is not complete now, and the strategy to deliver the delta in a future release. The sprint column is the requirement pack's plan.", "",
     "| | Items | SP |", "|---|---:|---:|", "| PARTIAL | %d | %d |" % (tot["PARTIAL"], tsp["PARTIAL"]), "| DEFERRED | %d | %d |" % (tot["DEFERRED"], tsp["DEFERRED"]), ""]
for g, rows in groups.items():
    if not rows: continue
    L += ["## " + g, "", "| Backlog | Title | SP | Planned sprint | Status | Delivered locally | Reason it is not complete now | Strategy for the future release |", "|---|---|---:|---|---|---|---|---|"]
    for b, i in rows:
        L.append("| %s | %s | %d | %s | %s | %s | %s | %s |" % (b["id"], esc(b["title"]), b["sp"], b["sprint"], i["status"], esc(i.get("evidence") or "—"), esc(i["reason"]), esc(i["strategy"])))
    L.append("")
L += ["## Release strategy for the delta", "",
      "1. **R1.1 – Azure Dev + edge track (IM-S1 cloud/edge):** Terraform/Helm for Azure Dev, Key Vault adapter, cosign-signed images + Argo CD, hardened gateway image and Windows device-host agent using this release's enrolment API, OTel/Prometheus stack.",
      "2. **R1.2 – Device lab families (IM-S5…S12):** certify additional vendors per family with golden sessions captured from real units; POCT1-A2 dialogue, MLLP PCD-02/ACM, DICOM router, OAuth2 vendor pollers; OHCM reference integration in the OHCM UAT.",
      "3. **R1.3 – Multi-cloud & assurance (IM-S13/S14):** GCP and private-cloud blueprints, HA/DR drills, cloud posture checks in the security kit, pen test, SOC 2/ISO 27001 readiness, usability and clinical safety files.",
      "4. **R1.4 – Pilot & GA (IM-S15):** shadow-mode discrepancy reports with clinical sign-off, time-and-motion study, monthly client analytics, post-market surveillance.",
      "5. **R2 – Scale (IM-S16+):** vendor portal and marketplace, partner certification badges, licensing/billing feeds, OEM white-label, privacy-preserving cross-tenant benchmarking.", "",
      "Everything in this list builds on interfaces already present and tested locally (SecretStore, DriverRegistry publish channel, harness API, enrolment API, EventBus, template adapters), so the delta is additive – no rework of the local core."]
open(os.path.join(DOC, "Deferred-and-Partial-Backlog.md"), "w").write("\n".join(L) + "\n")

# --- increments & capacity
L = ["# IntiMed – Incremental delivery and capacity model (local scope)", "",
     "The requirement pack plans 17 two-week sprints (IM-S0…IM-S16+, ≈1,395 SP). This local release was built in five increments that follow the sprint order and the traceability above; the capacity table compares the pack's planned story points per sprint with what was delivered for the LOCAL setting.", "",
     "## Increments", "", "| Increment | Scope | Sprints covered |", "|---|---|---|"] + ["| %s | %s | %s |" % (i["id"], i["name"], i["sprints"]) for i in T.INCREMENTS]
L += ["", "## Capacity by sprint (story points)", "", "| Sprint | Theme | Planned SP | Delivered | Partial | Deferred | Local coverage* |", "|---|---|---:|---:|---:|---:|---:|"]
for s in sp:
    c = cap.get(s["sprint"], {"planned": 0, "delivered": 0, "partial": 0, "deferred": 0})
    cov = (c["delivered"] + 0.5 * c["partial"]) / c["planned"] * 100 if c["planned"] else 0
    L.append("| %s | %s | %d | %d | %d | %d | %.0f %% |" % (s["sprint"], esc(s["theme"]), c["planned"], c["delivered"], c["partial"], c["deferred"], cov))
T2 = {k: sum(cap[s][k] for s in cap) for k in ("planned", "delivered", "partial", "deferred")}
L += ["| **Total** | | **%d** | **%d** | **%d** | **%d** | **%.0f %%** |" % (T2["planned"], T2["delivered"], T2["partial"], T2["deferred"], (T2["delivered"] + 0.5 * T2["partial"]) / T2["planned"] * 100), "",
      "\\* Local coverage counts PARTIAL items at 50 % – a planning convention, not a measurement.", "",
      "## Capacity model for the local environment (IM-E01-BL-030 local subset)", "",
      "| Measure | Requirement (NFR §14) | Local evidence |", "|---|---|---|",
      "| Spot result end-to-end | ≤ 3 s p95 | XT-10 per exchange test: typically 10–60 ms per message over loopback mTLS (see run evidence timings) |",
      "| Streaming interval | ≤ 1 s | XT-10 continuous templates ≤ 1000 ms per message |",
      "| Throughput per tenant | 500 msg/s with horizontal scaling | Token-bucket quota per tenant (500/s, burst 1000) and per endpoint (50/s); single-node local measurement documented in `docs/operations/Capacity-Model.md` |",
      "| Harness run single device | ≤ 10 min | 1–3 s per run with simulators (20+ runs during demo autoprovision in ~2 min) |",
      "| MacBook bootstrap | < 30 min | Demo pack: double-click launcher, first start < 1 min; code base: `./mvnw package` + run |"]
open(os.path.join(DOC, "Increments-and-Capacity.md"), "w").write("\n".join(L) + "\n")
print("ok", tot, tsp)
