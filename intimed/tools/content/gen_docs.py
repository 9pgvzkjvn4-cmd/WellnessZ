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
    items[b["id"]] = {k: v for k, v in {"status": st, "increment": inc, "evidence": ev, "reason": reason, "strategy": strat, "manual": T.MANUAL_REF.get(b["id"])}.items() if v}
cap = collections.defaultdict(lambda: {"planned": 0, "delivered": 0, "partial": 0, "deferred": 0, "items": 0})
for b in bl:
    c = cap[b["sprint"]]; c["planned"] += b["sp"]; c["items"] += 1
    c[{"DELIVERED": "delivered", "PARTIAL": "partial", "DEFERRED": "deferred"}[items[b["id"]]["status"]]] += b["sp"]
json.dump({"items": items, "increments": T.INCREMENTS, "capacity": cap}, open(os.path.join(SEED, "delivery-status.json"), "w"), indent=1, ensure_ascii=False)

def esc(s): return (s or "").replace("|", "\\|").replace("\n", " ")
tot = collections.Counter(); tsp = collections.Counter()
for b in bl: tot[items[b["id"]]["status"]] += 1; tsp[items[b["id"]]["status"]] += b["sp"]

# --- matrix
L = ["# IntiMed – Requirements Traceability Matrix (release 2.1 – local + Azure Qatar Central)", "",
     "Generated from `tools/content/traceability.py` (single source of truth, also served at `/api/quality/backlog`).", "",
     "| | Items | Story points |", "|---|---:|---:|"]
for s in ("DELIVERED", "PARTIAL", "DEFERRED"): L.append("| %s | %d | %d |" % (s, tot[s], tsp[s]))
L += ["| **Total** | **%d** | **%d** |" % (len(bl), sum(b["sp"] for b in bl)), "",
      "Status: **DELIVERED** – implemented in the product or its automation (LOCAL and Azure Qatar Central); **PARTIAL** – implemented as far as software can go, the remainder needs people, hardware, vendors, licences or audits; **DEFERRED** – deliberately outside this release. Every item that still needs a manual step names its section in `docs/azure/Manual-Backlog-Execution-Guide.md` (M-01…M-16).", ""]
for e in ep:
    L += ["## %s – %s" % (e["epic"], e["name"].split(" – ")[0]), "", "_" + e["goal"][:400] + ("…_" if len(e["goal"]) > 400 else "_"), "", "### High-level requirements → features", "",
          "| Feature | Name | HLRs | Backlog items (status) |", "|---|---|---|---|"]
    for f in [x for x in fe if x["id"].startswith(e["epic"])]:
        its = [b for b in bl if b["feature"] == f["id"]]
        L.append("| %s | %s | %s | %s |" % (f["id"], esc(f["name"]), esc(f["hlrs"]), "<br>".join("%s (%s)" % (b["id"], items[b["id"]]["status"]) for b in its)))
    L += ["", "### Backlog → implementation evidence", "", "| Backlog | Title | SP | Sprint | Status | Increment | Evidence / reason | Manual guide |", "|---|---|---:|---|---|---|---|---|"]
    for b in [x for x in bl if x["id"].startswith(e["epic"])]:
        i = items[b["id"]]
        L.append("| %s | %s | %d | %s | %s | %s | %s | %s |" % (b["id"], esc(b["title"]), b["sp"], b["sprint"], i["status"], i.get("increment", "—"), esc(i.get("evidence") or i.get("reason")), i.get("manual", "—")))
    L.append("")
L += ["## HLR index", "", "| HLR | Requirement (abridged) | Priority |", "|---|---|---|"] + ["| %s | %s | %s |" % (h["id"], esc(h["requirement"][:220]), h["priority"]) for h in hl]
L += ["", "## Tests", "", "| Test class | What it proves |", "|---|---|",
      "| **Total 134 automated tests** (Maven) + 4 Terraform plan/apply tests (mocked providers) + security kit 69 local checks + Azure checks (01, 05, 16) | |",
      "| `UnitTests` (36) | All 26 driver packages valid, signed, secret-free, golden session parses every semantic key, malformed rejected; XXE refused; SFLOAT; serial checksum; GDT length; payload size; UCUM; AES-GCM context binding; RFC 6238 TOTP vector; PHI masking; secret scanner |",
      "| `PlatformIntegrationTest` (10) | Wizard with intelligence → exchange test 15/15 over real mTLS → four-eyes refusal/approval → publish gate → DEV/UAT, PROD needs SRE; live capture files into TPL-18, PHI encrypted at rest, idempotent re-send; re-test after change; RR-03 block; SC-19 accept-all refused in UAT; plaintext/no-cert/revoked refused; anomaly isolation + re-certification gate; tenant isolation (ciphertext copied across tenants cannot be read); audit tamper detection; HTTP controls (401, CSRF, CSP, HSTS, FHIR auth, webhook HMAC); password policy and lockout |",
      "| `ClinicalIntelligenceTests` (42), `ClinicalIntelligenceDeferredIntegrationTest` (8) | Reference ranges, cohorts, validation, eGFR/derived values, delta checks, EP28 governance, host demographics, RR-17/RR-18, trajectories, terminology adapter |",
      "| `OperationsCommercialTest` (8) | Regression suites, shadow sign-off, monthly report, health score, billing HMAC + reconciliation, trials (simulator-only), branding hardening, k-anonymous benchmarks |",
      "| `PartnerCatalogueTest` (9), `ProtocolCodecTests` (8) | Partner submissions + four-eyes + badges, GUDID/EUDAMED feeds, AI gateway policy, OpenAPI; ASTM E1381 / POCT1-A2 codecs |",
      "| `CloudAdaptersTest` (4) | Azure Key Vault secret store (tenant binding, HTTPS/host rules), workload-identity token exchange, immutable Blob archiver |",
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
L = ["# IntiMed – Backlog items with a remaining manual or out-of-release part (release 2.1)", "",
     "Release 2.1 adds the Azure Qatar Central deployment and implements the automatable deferred items. Every item below is **PARTIAL** (implemented as far as software can go – the remainder needs people, hardware, vendors, licences or audits) or **DEFERRED** (deliberately outside this Azure Qatar Central release). The *Manual guide* column points to the step-by-step section in `docs/azure/Manual-Backlog-Execution-Guide.md`.", "",
     "| | Items | SP |", "|---|---:|---:|", "| PARTIAL | %d | %d |" % (tot["PARTIAL"], tsp["PARTIAL"]), "| DEFERRED | %d | %d |" % (tot["DEFERRED"], tsp["DEFERRED"]), ""]
for g, rows in groups.items():
    if not rows: continue
    L += ["## " + g, "", "| Backlog | Title | SP | Planned sprint | Status | Delivered | Remaining part | Strategy | Manual guide |", "|---|---|---:|---|---|---|---|---|---|"]
    for b, i in rows:
        L.append("| %s | %s | %d | %s | %s | %s | %s | %s | %s |" % (b["id"], esc(b["title"]), b["sp"], b["sprint"], i["status"], esc(i.get("evidence") or "—"), esc(i.get("reason")), esc(i.get("strategy")), i.get("manual", "—")))
    L.append("")
L += ["## Delivered in release 2.1", "", "DELIVERED items that still name a guide section only need to be *executed* in the operated environment (e.g. soak, DR drills, site validation). The list of everything delivered is in `Traceability-Matrix.md`.", ""]
open(os.path.join(DOC, "Deferred-and-Partial-Backlog.md"), "w").write("\n".join(L) + "\n")

# --- increments & capacity
L = ["# IntiMed – Incremental delivery and capacity model (release 2.1)", "",
     "The requirement pack plans 17 two-week sprints (IM-S0…IM-S16+, ≈1,395 SP). The platform was built in eight increments that follow the sprint order and the traceability above (L1–L5 local release, L6–L8 backlog follow-up and Azure Qatar Central); the capacity table compares the pack's planned story points per sprint with what is delivered.", "",
     "## Increments", "", "| Increment | Scope | Sprints covered |", "|---|---|---|"] + ["| %s | %s | %s |" % (i["id"], i["name"], i["sprints"]) for i in T.INCREMENTS]
L += ["", "## Capacity by sprint (story points)", "", "| Sprint | Theme | Planned SP | Delivered | Partial | Deferred | Local coverage* |", "|---|---|---:|---:|---:|---:|---:|"]
for s in sp:
    c = cap.get(s["sprint"], {"planned": 0, "delivered": 0, "partial": 0, "deferred": 0})
    cov = (c["delivered"] + 0.5 * c["partial"]) / c["planned"] * 100 if c["planned"] else 0
    L.append("| %s | %s | %d | %d | %d | %d | %.0f %% |" % (s["sprint"], esc(s["theme"]), c["planned"], c["delivered"], c["partial"], c["deferred"], cov))
T2 = {k: sum(cap[s][k] for s in cap) for k in ("planned", "delivered", "partial", "deferred")}
L += ["| **Total** | | **%d** | **%d** | **%d** | **%d** | **%.0f %%** |" % (T2["planned"], T2["delivered"], T2["partial"], T2["deferred"], (T2["delivered"] + 0.5 * T2["partial"]) / T2["planned"] * 100), "",
      "\\* Coverage counts PARTIAL items at 50 % – a planning convention, not a measurement.", "",
      "## Capacity model for the local environment (IM-E01-BL-030 local subset)", "",
      "| Measure | Requirement (NFR §14) | Local evidence |", "|---|---|---|",
      "| Spot result end-to-end | ≤ 3 s p95 | XT-10 per exchange test: typically 10–60 ms per message over loopback mTLS (see run evidence timings) |",
      "| Streaming interval | ≤ 1 s | XT-10 continuous templates ≤ 1000 ms per message |",
      "| Throughput per tenant | 500 msg/s with horizontal scaling | Token-bucket quota per tenant (500/s, burst 1000) and per endpoint (50/s); single-node local measurement documented in `docs/operations/Capacity-Model.md` |",
      "| Harness run single device | ≤ 10 min | 1–3 s per run with simulators (20+ runs during demo autoprovision in ~2 min) |",
      "| MacBook bootstrap | < 30 min | Demo pack: double-click launcher, first start < 1 min; code base: `./mvnw package` + run |"]
open(os.path.join(DOC, "Increments-and-Capacity.md"), "w").write("\n".join(L) + "\n")
# --- manual backlog execution guide (docs/azure)
import release_azure as R
AZ = os.path.join(R_DIR := R.__file__ and os.path.join(os.path.dirname(__file__), "..", ".."), "docs", "azure")
os.makedirs(AZ, exist_ok=True)
enh = {}
try:
    sys.path.insert(0, os.path.dirname(__file__))
    import enhancement_pack as EP
    enh = {b[0]: (b[3], b[6]) for b in EP.BACKLOG}
except Exception:
    pass
title = {b["id"]: b["title"] for b in bl}
G = ["# IntiMed – Manual Backlog Execution Guide (release 2.1, Azure Qatar Central)", "",
     "Everything that software can do is in the product, its Terraform/Helm and its pipelines. This guide covers the remaining steps that need **people, hardware, vendors, licences, audits or regulator decisions** – for every backlog item (1.0 and 2.0) that is PARTIAL or DEFERRED, and for DELIVERED items that still have to be *executed* in the operated environment.", "",
     "| Section | Title | Owner | Backlog items |", "|---|---|---|---|"]
for m in R.MANUAL:
    G.append("| [%s](#%s) | %s | %s | %s |" % (m["id"], m["id"].lower(), m["title"], m["owner"], ", ".join(m["items"])))
for m in R.MANUAL:
    G += ["", "## %s" % m["id"], "", "### %s" % m["title"], "", "**Owner:** %s  " % m["owner"], "**Why manual:** %s" % m["why"], "", "**Prerequisites**", ""]
    G += ["- " + x for x in m["pre"]]
    G += ["", "**Steps**", ""] + ["%d. %s" % (n + 1, x) for n, x in enumerate(m["steps"])]
    G += ["", "**Tooling shipped with the product:** " + "; ".join(m["tool"]), "", "**Evidence that closes the item**", ""] + ["- " + x for x in m["evidence"]]
    G += ["", "| Backlog | Title | Status |", "|---|---|---|"]
    for i in m["items"]:
        if i in items:
            G.append("| %s | %s | %s |" % (i, esc(title.get(i, "")), items[i]["status"]))
        else:
            t, st = enh.get(i, ("", ""))
            G.append("| %s | %s | %s (2.0 enhancement) |" % (i, esc(t), st))
G += ["", R.DPIA_TEMPLATE]
open(os.path.join(AZ, "Manual-Backlog-Execution-Guide.md"), "w").write("\n".join(G) + "\n")
print("ok", tot, tsp)
