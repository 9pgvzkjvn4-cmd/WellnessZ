#!/usr/bin/env python3
"""IntiMed_GCP – Google Cloud me-central1 (Doha) cost model (IM-E08-BL-009 TCO, IM-E08-BL-007 FinOps).

Builds docs/gcp/IntiMed_GCP_Cost_Model.xlsx from the committed price snapshot deploy/gcp/pricing/gcp-me-central1-*.json.
Every line shows its price source and a confidence label:
  snapshot          – me-central1 value from the cited dataset (Cyclenerd pricing.yml / Bytebase Cloud SQL table)
  confirmed (global)– global SKU price confirmed on the Google pricing page
  estimate – verify – global or derived figure; confirm in the Google Cloud Pricing Calculator for me-central1
Quantities are editable (yellow) cells; totals are formulas. List prices (on-demand) in USD; QAR at the 3.64 peg.

Usage: python3 tools/gcp/cost_model.py [snapshot.json] [out.xlsx]
"""
import glob
import json
import os
import sys

from openpyxl import Workbook
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.utils import get_column_letter

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
SNAP = sys.argv[1] if len(sys.argv) > 1 else sorted(glob.glob(os.path.join(ROOT, "deploy/gcp/pricing/gcp-me-central1-*.json")))[-1]
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(ROOT, "docs/gcp/IntiMed_GCP_Cost_Model.xlsx")
snap = json.load(open(SNAP))
QAR = snap["qar_peg"]
HOURS = 730
I = snap["items"]

ASSUME = {
    "uat": {"log_gib_day": 3, "log_extra_retention_gib": 0, "egress_gb": 50, "archive_gb": 20, "gmp_msamples": 60, "sm_access_10k": 5,
            "armor_mreq": 1, "lb_gb": 100, "nat_gb": 30, "inter_zone_gb": 50, "ngfw_gb": 0, "images": 20, "sql_backup_gb": 20},
    "prod": {"log_gib_day": 12, "log_extra_retention_gib": 730, "egress_gb": 400, "archive_gb": 300, "gmp_msamples": 160, "sm_access_10k": 15,
             "armor_mreq": 10, "lb_gb": 800, "nat_gb": 300, "inter_zone_gb": 800, "ngfw_gb": 300, "images": 30, "sql_backup_gb": 250},
}
VPN_SITES = 3          # production hospital sites (2 HA VPN tunnels each)
ARMOR_RULES = 14       # 11 preconfigured WAF rules (edge.tf) + rate-based ban + allow-list + default


def bom(env):
    a = ASSUME[env]
    prod = env == "prod"
    sys_mt, app_mt, app_nodes = ("n2-standard-2", "n2-standard-8", 2) if prod else ("e2-standard-2", "n2-standard-4", 1)
    sql = "sql_4vcpu_15gb" if prod else "sql_2vcpu_7_5gb"
    sql_n = 2 if prod else 1
    sql_gb = 250 if prod else 50
    pv_gb = 128 if prod else 32
    nodes = 3 + app_nodes
    L = [
        ("Compute (GKE)", "gke_fee", 1, HOURS, "GKE Standard regional cluster management fee"),
        ("Compute (GKE)", sys_mt, 3, 1, f"System node pool: 1 × {sys_mt} per zone (3 zones)"),
        ("Compute (GKE)", app_mt, app_nodes, 1, f"App node pool: {app_nodes} × {app_mt} (autoscale minimum; max {4 if prod else 2}) in zones a+b"),
        ("Compute (GKE)", "pd_balanced", nodes * 50, 1, f"Node boot disks: {nodes} × 50 GB pd-balanced"),
        ("Data", "pd_ssd_regional", pv_gb, 1, f"Platform data volume: regional PD-SSD {pv_gb} GB (synchronously replicated across 2 zones)"),
        ("Data", sql, sql_n, 1, "Cloud SQL for PostgreSQL 16 Enterprise " + ("4 vCPU / 15 GiB, REGIONAL HA (primary + standby billed)" if prod else "2 vCPU / 7.5 GiB, ZONAL")),
        ("Data", "sql_ssd", sql_gb * sql_n, 1, f"Cloud SQL SSD storage {sql_gb} GB" + (" × 2 (HA)" if prod else "")),
        ("Data", "sql_backup", a["sql_backup_gb"], 1, f"Cloud SQL backups + PITR logs ({35 if prod else 14} backups, located in me-central1)"),
        ("Data", "gcs_standard", a["archive_gb"] * 2, 1, "Evidence + audit archive buckets (Standard, CMEK, retention policy) – both buckets"),
        ("Security", "kms_hsm_sym", 4, 1, "CMEK HSM key: ≈ 4 enabled versions/year with 90-day rotation (all data services)"),
        ("Security", "kms_hsm_ec", 1, 1, "HSM EC P-256 key for cosign signatures / Binary Authorization attestor"),
        ("Security", "kms_ops", 30 if prod else 10, 1, "KMS operations (CMEK envelope decrypts by SQL/GCS/AR/Secret Manager/GKE + signing)"),
        ("Security", "sm_version", 13, 1, "Secret Manager: 3 runtime secrets + ≈ 10 tenant secrets, 1 location (me-central1)"),
        ("Security", "sm_access", a["sm_access_10k"], 1, "Secret Manager accesses (CSI add-on rotation polling + platform reads)"),
        ("Edge", "armor_policy", 1, 1, "Cloud Armor regional security policy for the console"),
        ("Edge", "armor_rule", ARMOR_RULES, 1, "Cloud Armor rules (OWASP CRS v3.3 preconfigured, rate-based ban, allow-list)"),
        ("Edge", "armor_req", a["armor_mreq"], 1, "Requests evaluated by Cloud Armor (millions/month)"),
        ("Network", "lb_rule", 3, HOURS, "Forwarding rules: console ALB HTTPS + HTTP redirect, internal device NLB"),
        ("Network", "lb_data", a["lb_gb"], 1, "Regional external Application Load Balancer data processed"),
        ("Network", "nat_gw", 1, HOURS, "Cloud NAT gateway (private nodes egress)"),
        ("Network", "ip_vm", 2, HOURS, "Cloud NAT external IPs"),
        ("Network", "nat_data", a["nat_gb"], 1, "Cloud NAT data processed"),
        ("Network", "egress", a["egress_gb"], 1, "Internet egress (console users, webhooks)"),
        ("Network", "inter_zone", a["inter_zone_gb"], 1, "Inter-zone traffic (regional PD replication, SQL HA, cross-zone pods)"),
        ("Network", "dns_zone", 3, 1, "Private DNS zones (googleapis restricted, pkg.dev, gcr.io)"),
        ("Platform", "ar_storage", 20 if prod else 10, 1, "Artifact Registry images + signatures + SBOM attestations"),
        ("Platform", "scan_image", a["images"], 1, "Artifact Analysis on-push vulnerability scans"),
        ("Monitoring", "log_ingest", max(0, a["log_gib_day"] * 30.4 - 50), 1, f"Cloud Logging ingestion {a['log_gib_day']} GiB/day, minus 50 GiB free"),
        ("Monitoring", "log_retain", a["log_extra_retention_gib"], 1, "Log retention beyond 30 days (PROD 90 days in the Doha log bucket)"),
        ("Monitoring", "gmp_samples", a["gmp_msamples"], 1, "Managed Service for Prometheus samples (platform + cluster metrics)"),
    ]
    if prod:
        L += [
            ("Network", "vpn_tunnel", VPN_SITES * 2, HOURS, f"HA VPN: {VPN_SITES} hospital sites × 2 tunnels (99.99 % SLA)"),
            ("Network", "ngfw_data", a["ngfw_gb"], 1, "Cloud NGFW Standard FQDN egress allow-list – data processed"),
        ]
    return L


wb = Workbook()
wb.remove(wb.active)
HEAD = Font(bold=True, color="FFFFFF")
FILL = PatternFill("solid", fgColor="1A73E8")
TOT = Font(bold=True)
EDIT = PatternFill("solid", fgColor="FFF4DC")
WARN = PatternFill("solid", fgColor="FDE7E9")
USD = '"$"#,##0.00'
QARF = '"QAR "#,##0'


def header(ws, cols, widths):
    ws.append(cols)
    for c in ws[1]:
        c.font, c.fill = HEAD, FILL
        c.alignment = Alignment(wrap_text=True, vertical="center")
    for i, w in enumerate(widths, 1):
        ws.column_dimensions[get_column_letter(i)].width = w
    ws.freeze_panes = "A2"


def wrap(ws, start=2):
    for row in ws.iter_rows(min_row=start):
        for c in row:
            c.alignment = Alignment(wrap_text=True, vertical="top")


totals = {}
for env in ("uat", "prod"):
    ws = wb.create_sheet(f"{env.upper()} monthly")
    header(ws, ["Category", "Service / SKU", "Unit", "Unit price (USD)", "Quantity", "Units per month", "Monthly USD", "Monthly QAR", "Description / assumption", "Confidence", "Price source"],
           [15, 44, 18, 14, 11, 12, 14, 14, 62, 18, 60])
    py = 0.0
    for cat, key, qty, mult, note in bom(env):
        it = I[key]
        r = ws.max_row + 1
        ws.append([cat, it["description"], it["unit"], it["price"], round(qty, 2), mult, f"=D{r}*E{r}*F{r}", f"=G{r}*{QAR}", note, it["confidence"], it["source"]])
        ws[f"E{r}"].fill = EDIT
        ws[f"F{r}"].fill = EDIT
        if it["confidence"].startswith("estimate"):
            ws[f"J{r}"].fill = WARN
        ws[f"D{r}"].number_format = '"$"#,##0.0000'
        ws[f"G{r}"].number_format = USD
        ws[f"H{r}"].number_format = QARF
        py += it["price"] * qty * mult
    r = ws.max_row + 1
    ws.append(["TOTAL", "", "", "", "", "", f"=SUM(G2:G{r - 1})", f"=SUM(H2:H{r - 1})", "List prices (on-demand, sustained-use discount included for N2), before tax, support and negotiated discounts", "", ""])
    for c in ws[r]:
        c.font = TOT
    ws[f"G{r}"].number_format = USD
    ws[f"H{r}"].number_format = QARF
    wrap(ws)
    totals[env] = (py, f"'{env.upper()} monthly'!G{r}")

# ---- 5-year TCO with committed-use discounts
res = wb.create_sheet("5-year TCO")
header(res, ["Item", "Year 1", "Year 2", "Year 3", "Year 4", "Year 5", "5-year total", "Notes"], [58, 15, 15, 15, 15, 15, 17, 70])
res.append(["Connected devices (production, end of year)", 500, 1000, 1800, 2600, 3500, "", "Planning assumption – edit (yellow)"])
for c in res[2][1:6]:
    c.fill = EDIT
res.append(["Extra app nodes for growth (n2-standard-8, +1 per 1,500 devices above 1,500)"] + [f"=MAX(0,ROUNDUP(({c}2-1500)/1500,0))" for c in "BCDEF"] + ["", "Single-active platform pod scales vertically first; nodes host growth and drills"])
n8 = I["n2-standard-8"]
res.append(["PROD – on-demand (base)"] + [f"={totals['prod'][1]}*12+{n8['price']}*12*{c}3" for c in "BCDEF"] + ["=SUM(B4:F4)", "Monthly PROD total × 12 plus growth nodes"])
res.append(["UAT – on-demand"] + [f"={totals['uat'][1]}*12"] * 5 + ["=SUM(B5:F5)", "Scale the app pool to 0 outside test windows to save ≈ 30–40 % of UAT compute"])
n2 = I["n2-standard-2"]
od = 3 * n2["price"] + 2 * n8["price"]
cud1 = 3 * n2["month_1y"] + 2 * n8["month_1y"]
cud3 = 3 * n2["month_3y"] + 2 * n8["month_3y"]
res.append(["Saving with 1-year resource CUDs (PROD nodes)"] + [f"=-({od:.2f}-{cud1:.2f})*12"] * 5 + ["=SUM(B6:F6)", "Committed-use prices (month_1y) from the same snapshot; Cloud SQL CUDs (≈ 25 % / 52 %) add further savings"])
res.append(["Saving with 3-year resource CUDs (PROD nodes) – alternative"] + [f"=-({od:.2f}-{cud3:.2f})*12"] * 5 + ["=SUM(B7:F7)", "Use either row 6 or row 7, not both"])
res.append(["Total cloud cost – on-demand (PROD + UAT)"] + [f"={c}4+{c}5" for c in "BCDEF"] + ["=SUM(B8:F8)", ""])
res.append(["Total cloud cost – with 3-year CUDs"] + [f"={c}8+{c}7" for c in "BCDEF"] + ["=SUM(B9:F9)", ""])
res.append(["Cloud cost per connected device per month (on-demand)"] + [f"={c}8/12/{c}2" for c in "BCDEF"] + ["", "Excludes people, licences and third-party services"])
res.append(["Total in QAR (on-demand)"] + [f"={c}8*{QAR}" for c in "BCDEF"] + [f"=G8*{QAR}", "Fixed peg 3.64 QAR/USD"])
for row in res.iter_rows(min_row=3, max_row=res.max_row):
    for c in row[1:7]:
        c.number_format = USD
for c in res[res.max_row][1:7]:
    c.number_format = QARF
for c in res[2][1:6]:
    c.number_format = "#,##0"
for c in res[3][1:6]:
    c.number_format = "0"
wrap(res)

# ---- one-time & non-GCP
ot = wb.create_sheet("One-time & non-GCP")
header(ot, ["Item", "Effort / quantity", "Unit", "Your rate", "Cost", "Notes"], [62, 16, 16, 12, 14, 70])
ONE = [
    ("Organisation, projects, billing, groups, org policies (Setup Guide §1–2)", 2, "person-days", "Cloud admin"),
    ("Terraform UAT + PROD, preflight, secrets bootstrap (§3–5)", 3, "person-days", "Platform SRE"),
    ("Helm deploy, console certificate + DNS, Cloud Armor tuning (§6–8)", 2, "person-days", ""),
    ("HA VPN and segmentation validation per hospital (M-02)", 2, "person-days/site", "Network team"),
    ("Security kit + DR drills acceptance (§9, M-11)", 2, "person-days", ""),
    ("Penetration test (M-10)", 1, "engagement", "External – obtain quotes"),
    ("Public TLS certificate for the console hostname", 1, "per year", "Customer CA or commercial CA"),
    ("Google Cloud support plan (Enhanced recommended for production)", 12, "months", "Priced as % of spend – see Google support pricing"),
    ("Terminology server licence (LOINC/SNOMED CT) – optional (M-12)", 1, "per year", "Vendor quote"),
    ("Gateway hardware per site – optional (M-03)", 1, "per site", "Depends on device mix"),
]
for item, q, unit, note in ONE:
    r = ot.max_row + 1
    ot.append([item, q, unit, None, f"=IF(D{r}=\"\",\"\",B{r}*D{r})", note])
    ot[f"D{r}"].fill = EDIT
    ot[f"B{r}"].fill = EDIT
wrap(ot)

# ---- assumptions & sources
asm = wb.create_sheet("Assumptions & sources")
header(asm, ["Assumption / source", "UAT", "PROD", "Notes"], [52, 22, 22, 90])
LABELS = [("log_gib_day", "Cloud Logging ingestion GiB/day"), ("log_extra_retention_gib", "Log GiB retained beyond 30 days"), ("egress_gb", "Internet egress GB/month"),
          ("archive_gb", "Archive GB per bucket"), ("gmp_msamples", "Prometheus samples (millions)/month"), ("sm_access_10k", "Secret Manager accesses (×10K)/month"),
          ("armor_mreq", "Cloud Armor requests (millions)/month"), ("lb_gb", "Load balancer GB/month"), ("nat_gb", "Cloud NAT GB/month"),
          ("inter_zone_gb", "Inter-zone GB/month"), ("ngfw_gb", "Cloud NGFW GB/month"), ("images", "Images scanned/month"), ("sql_backup_gb", "Cloud SQL backup GB")]
for k, label in LABELS:
    asm.append([label, ASSUME["uat"][k], ASSUME["prod"][k], "Change the quantity cells on the monthly sheets to re-price"])
asm.append([])
asm.append(["Region", snap["region"], "", snap["location"]])
asm.append(["Price snapshot", os.path.basename(SNAP), "", "Retrieved " + snap["retrievedAt"] + ". " + snap["note"]])
srcs = sorted({it["source"] for it in I.values()})
for s in srcs:
    asm.append(["Source", "", "", s])
asm.append(["Region facts", "", "", "me-central1 (Doha) launched May 2023 with 3 zones – sources: Google Cloud blog/press (convergedigest, networkworld, The Peninsula Qatar 2023), northflank.com/cloud/gcp/regions/me-central1"])
asm.append(["Not included", "", "", "Taxes, support plan, negotiated discounts / commitments, Security Command Center Premium (optional), people"])
wrap(asm)
conf = wb.create_sheet("Price confidence")
header(conf, ["Key", "SKU", "Unit price (USD)", "Unit", "Confidence", "Source"], [18, 60, 14, 22, 20, 80])
for k, it in sorted(I.items(), key=lambda x: (x[1]["confidence"], x[0])):
    conf.append([k, it["description"], it["price"], it["unit"], it["confidence"], it["source"]])
    if it["confidence"].startswith("estimate"):
        conf[f"E{conf.max_row}"].fill = WARN
wrap(conf)

sm = wb.create_sheet("Summary", 0)
header(sm, ["Environment", "Monthly USD (on-demand)", "Monthly QAR", "Annual USD", "Annual QAR", "What it is"], [30, 18, 16, 16, 16, 90])
sm.append(["UAT / test", f"={totals['uat'][1]}", f"=B2*{QAR}", "=B2*12", f"=D2*{QAR}",
           "me-central1; private regional GKE (3 × e2-standard-2 system + 1 × n2-standard-4 app), Cloud SQL PG16 2 vCPU ZONAL, KMS HSM, Secret Manager, Cloud Armor, regional ALB, Cloud Logging/Prometheus; no VPN/NGFW"])
sm.append(["Production", f"={totals['prod'][1]}", f"=B3*{QAR}", "=B3*12", f"=D3*{QAR}",
           "Regional GKE (3 × n2-standard-2 + 2 × n2-standard-8), Cloud SQL PG16 4 vCPU REGIONAL HA, regional PD, HA VPN (3 sites), Cloud NGFW FQDN egress, Binary Authorization enforced, 90-day logs, 7-year WORM archive"])
sm.append(["Total", "=B2+B3", "=C2+C3", "=D2+D3", "=E2+E3", "List prices; see '5-year TCO' for committed-use discounts and growth"])
for row in sm.iter_rows(min_row=2):
    for c in row[1:5]:
        c.number_format = USD if c.column in (2, 4) else QARF
for c in sm[4]:
    c.font = TOT
sm.append([])
sm.append(["Prices: " + os.path.basename(SNAP) + " (me-central1, retrieved " + snap["retrievedAt"] + "). Red 'estimate – verify' lines must be confirmed in the Google Cloud Pricing Calculator before a budget is committed. Edit yellow cells to re-price."])
wrap(sm)

os.makedirs(os.path.dirname(OUT), exist_ok=True)
wb.save(OUT)
est = {e: sum(I[k]["price"] * q * m for _, k, q, m, _ in bom(e) if I[k]["confidence"].startswith("estimate")) for e in ("uat", "prod")}
for e in ("uat", "prod"):
    print("%-4s monthly USD %.0f (QAR %.0f) – of which estimate-verify lines USD %.0f" % (e.upper(), totals[e][0], totals[e][0] * QAR, est[e]))
print("->", OUT)
