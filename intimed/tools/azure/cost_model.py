#!/usr/bin/env python3
"""IntiMed on Azure Qatar Central – cost model (IM-E08-BL-009 TCO, IM-E08-BL-007 FinOps).

Builds docs/azure/IntiMed-Azure-Qatar-Cost-Model.xlsx from the committed retail-price snapshot
(deploy/azure/pricing/azure-retail-qatarcentral-*.json, pulled from https://prices.azure.com). Every line carries the
Azure meter id, so a buyer can verify it in the pricing calculator. Quantities are editable cells; totals are formulas.
Prices are list (retail, pay-as-you-go) USD; QAR at the fixed peg 3.64. Negotiated (EA/MACC/CSP) prices differ.

Usage: python3 tools/azure/cost_model.py [snapshot.json] [out.xlsx]
"""
import glob
import json
import os
import re
import sys

from openpyxl import Workbook
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.utils import get_column_letter

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
SNAP = sys.argv[1] if len(sys.argv) > 1 else sorted(glob.glob(os.path.join(ROOT, "deploy/azure/pricing/azure-retail-qatarcentral-*.json")))[-1]
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(ROOT, "docs/azure/IntiMed-Azure-Qatar-Cost-Model.xlsx")
QAR = 3.64
HOURS = 730
snap = json.load(open(SNAP))
ITEMS = snap["items"]


def price(service, meter, sku=None, product=None, tier=0.0, region="qatarcentral", ptype="Consumption", term=None):
    """Exact meter lookup; raises if not found so a silently wrong price can never reach the sheet."""
    for i in ITEMS:
        if i["serviceName"] != service and not i["productName"].startswith(service):
            continue
        if i["meterName"] != meter or i["type"] != ptype or abs(i["tierMinimumUnits"] - tier) > 1e-9:
            continue
        if sku and i["skuName"] != sku:
            continue
        if product and not re.search(product, i["productName"]):
            continue
        if region and i["armRegionName"] != region:
            continue
        if term and i.get("reservationTerm") != term:
            continue
        return i["retailPrice"], i["meterId"], i["productName"] + " / " + i["skuName"]
    raise KeyError(f"meter not found: {service} | {meter} | {sku} | {product} | tier {tier} | {region} | {ptype} {term}")


P = {
    "aks_sla": price("Azure Kubernetes Service", "Standard Uptime SLA", sku="Standard"),
    "d2": price("Virtual Machines", "D2s v5", product=r"^Virtual Machines Dsv5 Series$"),
    "d4": price("Virtual Machines", "D4s v5", product=r"^Virtual Machines Dsv5 Series$"),
    "d8": price("Virtual Machines", "D8s v5", product=r"^Virtual Machines Dsv5 Series$"),
    # PostgreSQL compute meters are per server SKU (the "4 vCore" meter is the hourly price of a 4-vCore server)
    "pg_2vc": price("Azure Database for PostgreSQL", "vCore", sku="2 vCore", product=r"General Purpose Ddsv5"),
    "pg_4vc": price("Azure Database for PostgreSQL", "vCore", sku="4 vCore", product=r"General Purpose Ddsv5"),
    "pg_storage": price("Azure Database for PostgreSQL", "Storage Data Stored", sku="Storage", product=r"Flex Server Storage"),
    "pg_backup": price("Azure Database for PostgreSQL", "Backup Storage LRS Data Stored", product=r"Flexible Server Backup"),
    "kv_ops": price("Key Vault", "Operations", sku="Premium"),
    "kv_hsm_adv": price("Key Vault", "Premium HSM-protected Advanced Key", sku="Premium"),
    "kv_adv_ops": price("Key Vault", "Advanced Key Operations", sku="Premium"),
    "acr_premium": price("Container Registry", "Premium Registry Unit", sku="Premium"),
    "acr_storage": price("Container Registry", "Data Stored", sku="Premium"),
    "agw_fixed": price("Application Gateway", "Standard Fixed Cost", sku="Standard", product=r"^Application Gateway WAF v2$"),
    "agw_cu": price("Application Gateway", "Standard Capacity Units", sku="Standard", product=r"^Application Gateway WAF v2$"),
    "fw_deploy": price("Azure Firewall", "Standard Deployment", sku="Standard"),
    "fw_data": price("Azure Firewall", "Standard Data Processed", sku="Standard"),
    "vpn_gw": price("VPN Gateway", "VpnGw1AZ", sku="VpnGw1AZ"),
    "vpn_s2s": price("VPN Gateway", "S2S Connection", sku="VpnGw1AZ"),
    "la_ingest": price("Log Analytics", "Analytics Logs Data Ingestion", tier=5.0),
    "la_retention": price("Log Analytics", "Analytics Logs Data Retention"),
    "sentinel": price("Sentinel", "Pay-as-you-go Analysis", sku="Pay-as-you-go"),
    "prom_ingest": price("Azure Monitor", "Metrics ingestion Metric samples"),
    "disk_p10_lrs": price("Storage", "P10 LRS Disk", product=r"Premium SSD Managed Disks"),
    "disk_p4_zrs": price("Storage", "P4 ZRS Disk", product=r"Premium SSD Managed Disks"),
    "disk_p6_zrs": price("Storage", "P6 ZRS Disk", product=r"Premium SSD Managed Disks"),
    "disk_p10_zrs": price("Storage", "P10 ZRS Disk", product=r"Premium SSD Managed Disks"),
    "blob_hot_zrs": price("Storage", "Hot ZRS Data Stored", product=r"^General Block Blob v2$"),
    "pip": price("Virtual Network", "Standard IPv4 Static Public IP", sku="Standard"),
    "egress": price("Bandwidth", "Standard Data Transfer Out", tier=100.0, product=r"Rtn Preference: MGN"),
    "inter_az": price("Bandwidth", "Standard Inter-Availability Zone Data Transfer Out", product=r"Rtn Preference: MGN"),
    "def_cont": price("Microsoft Defender for Containers", "Standard vCore vCore Pack"),
    "def_kv": price("Microsoft Defender for Key Vault", "Per node Std Node"),
    "def_storage": price("Microsoft Defender for Storage", "Standard Node", sku="Standard"),
    "def_pg": price("Microsoft Defender for PostgreSQL", "Standard Node", sku="Standard"),
    "def_arm": price("Microsoft Defender for Resource Manager", "Per node Std Node"),
    "lb_rules": price("Load Balancer", "Standard Included LB Rules and Outbound Rules", sku="Standard", region="Global"),
    "lb_data": price("Load Balancer", "Standard Data Processed", sku="Standard", region="Global"),
    "dns_private": price("Azure DNS", "Private Zone", sku="Private", region=None),
    # reservations (upfront for the term, per instance) – for the TCO scenarios
    "d2_1y": price("Virtual Machines", "D2s v5", product=r"^Virtual Machines Dsv5 Series$", ptype="Reservation", term="1 Year"),
    "d2_3y": price("Virtual Machines", "D2s v5", product=r"^Virtual Machines Dsv5 Series$", ptype="Reservation", term="3 Years"),
    "d4_1y": price("Virtual Machines", "D4s v5", product=r"^Virtual Machines Dsv5 Series$", ptype="Reservation", term="1 Year"),
    "d4_3y": price("Virtual Machines", "D4s v5", product=r"^Virtual Machines Dsv5 Series$", ptype="Reservation", term="3 Years"),
    "d8_1y": price("Virtual Machines", "D8s v5", product=r"^Virtual Machines Dsv5 Series$", ptype="Reservation", term="1 Year"),
    "d8_3y": price("Virtual Machines", "D8s v5", product=r"^Virtual Machines Dsv5 Series$", ptype="Reservation", term="3 Years"),
}
PRIVATE_ENDPOINT = (0.01, "(global list price – Private Link not returned for qatarcentral by the API; verify in the calculator)", "Private Endpoint / hour")

# --------------------------------------------------------------------------------------------- bill of materials
# (category, key or (price, meterId, desc), unit text, quantity, multiplier label/hours, note)
ASSUME = {
    "uat": {"log_gb_day": 3, "retention_extra_gb": 0, "egress_gb": 50, "archive_gb": 20, "prom_samples_10m": 18, "kv_ops_10k": 50, "fw_gb": 0, "inter_az_gb": 30},
    "prod": {"log_gb_day": 12, "retention_extra_gb": 700, "egress_gb": 400, "archive_gb": 300, "prom_samples_10m": 40, "kv_ops_10k": 300, "fw_gb": 300, "inter_az_gb": 600},
}


def bom(env):
    a = ASSUME[env]
    prod = env == "prod"
    sys_nodes, app_nodes, app_key = (3, 2, "d8") if prod else (2, 1, "d4")
    pg_vcores = 4 if prod else 2
    pg_instances = 2 if prod else 1  # zone-redundant HA bills the standby too
    pg_gb = 256 if prod else 64
    nodes = sys_nodes + app_nodes
    vcpus = sys_nodes * 2 + app_nodes * (8 if prod else 4)
    L = [
        ("Compute (AKS)", "aks_sla", "cluster-hour", 1, HOURS, "AKS Standard tier (uptime SLA, required for production)"),
        ("Compute (AKS)", "d2", "node-hour", sys_nodes, HOURS, f"System node pool {sys_nodes} × Standard_D2s_v5 across zones (Linux)"),
        ("Compute (AKS)", app_key, "node-hour", app_nodes, HOURS, f"App node pool {app_nodes} × Standard_{app_key.upper()}s_v5 (autoscale min; max {4 if prod else 2})"),
        ("Compute (AKS)", "disk_p10_lrs", "disk-month", nodes, 1, "Node OS disks (128 GiB Premium SSD) – use ephemeral OS disks to remove this line"),
        ("Data", "disk_p6_zrs" if not prod else "disk_p10_zrs", "disk-month", 1, 1, f"Platform data volume (Premium ZRS, {32 if not prod else 128} GiB) – evidence, PKI, SIEM file"),
        ("Data", "pg_4vc" if prod else "pg_2vc", "server-hour", pg_instances, HOURS, f"PostgreSQL Flexible GP_Standard_D{pg_vcores}ds_v5 ({pg_vcores} vCore)" + (" × 2 (zone-redundant HA primary + standby)" if prod else " (no HA in UAT)")),
        ("Data", "pg_storage", "GB-month", pg_gb * pg_instances, 1, f"PostgreSQL storage {pg_gb} GB" + (" × 2 (HA)" if prod else "")),
        ("Data", "pg_backup", "GB-month", pg_gb if prod else 0, 1, "Backup storage above the free 100 % of provisioned storage (35-day PITR estimate)"),
        ("Data", "blob_hot_zrs", "GB-month", a["archive_gb"], 1, "Immutable archive (evidence packs, audit stream) Hot ZRS"),
        ("Security", "kv_hsm_adv", "key-month", 1, 1, "Key Vault Premium HSM-protected EC key (cosign image signing)"),
        ("Security", "kv_ops", "10K operations", a["kv_ops_10k"], 1, "Key Vault secret operations (CSI rotation every 5 min + tenant secrets)"),
        ("Security", "kv_adv_ops", "10K operations", 1, 1, "HSM key operations (image signing per release)"),
        ("Platform", "acr_premium", "registry-day", 1, 30.4, "Container Registry Premium (private endpoint, zone redundancy)"),
        ("Platform", "acr_storage", "GB-month", 20 if prod else 10, 1, "Registry storage (images + signatures + SBOM attestations)"),
        ("Network", "lb_rules", "LB-hour", 2, HOURS, "Standard Load Balancers (AKS outbound/internal console, internal devices) – first 5 rules included"),
        ("Network", "lb_data", "GB", a["egress_gb"] * 2, 1, "Load-balancer data processed"),
        ("Network", "pip", "IP-hour", (3 if prod else 2), HOURS, "Standard public IPs (" + ("App Gateway, Firewall, VPN" if prod else "AKS egress, App Gateway") + ")"),
        ("Network", PRIVATE_ENDPOINT, "endpoint-hour", 3, HOURS, "Private endpoints (Key Vault, Blob, ACR)"),
        ("Network", "dns_private", "zone-month", 4, 1, "Private DNS zones (vault, blob, acr, postgres)"),
        ("Network", "egress", "GB", a["egress_gb"], 1, "Internet egress above 100 GB free (console, webhooks to hosts)"),
        ("Network", "inter_az", "GB", a["inter_az_gb"] * 2, 1, "Inter-availability-zone transfer, billed out and in (zone-redundant services)"),
        ("Edge", "agw_fixed", "gateway-hour", 1, HOURS, "Application Gateway WAF v2 fixed cost (zone-redundant)"),
        ("Edge", "agw_cu", "CU-hour", 2 if prod else 1, HOURS, "WAF v2 capacity units (autoscale minimum)"),
        ("Monitoring", "la_ingest", "GB", a["log_gb_day"] * 30.4, 1, f"Log Analytics ingestion {a['log_gb_day']} GB/day (Container Insights, diagnostics, SIEM stream)"),
        ("Monitoring", "la_retention", "GB-month", a["retention_extra_gb"], 1, "Retention beyond the included 31 days (prod 90 days)"),
        ("Monitoring", "prom_ingest", "10M samples", a["prom_samples_10m"], 1, "Managed Prometheus metric samples (platform + cluster)"),
        ("Security", "sentinel", "GB", a["log_gb_day"] * 30.4 * (0.35 if prod else 0.25), 1, "Microsoft Sentinel analysis on the security-relevant share of logs (audit stream, AKS audit, diagnostics)"),
    ]
    if prod:
        L += [
            ("Network", "fw_deploy", "firewall-hour", 1, HOURS, "Azure Firewall Standard (egress FQDN allow-list, zone-redundant)"),
            ("Network", "fw_data", "GB", a["fw_gb"], 1, "Firewall data processed"),
            ("Network", "vpn_gw", "gateway-hour", 1, HOURS, "VPN Gateway VpnGw1AZ (site-to-site to hospitals)"),
            ("Network", "vpn_s2s", "connection-hour", 3, HOURS, "Site-to-site connections (3 sites) – check how many tunnels the SKU includes before paying for these"),
            ("Security", "def_cont", "vCore-hour", vcpus, HOURS, "Defender for Containers (per node vCore)"),
            ("Security", "def_kv", "vault-hour", 1, HOURS, "Defender for Key Vault"),
            ("Security", "def_storage", "account-hour", 1, HOURS, "Defender for Storage"),
            ("Security", "def_pg", "server-month", 1, 1, "Defender for open-source relational databases"),
            ("Security", "def_arm", "subscription-hour", 1, HOURS, "Defender for Resource Manager"),
        ]
    return L


# --------------------------------------------------------------------------------------------- workbook
wb = Workbook()
wb.remove(wb.active)
HEAD = Font(bold=True, color="FFFFFF")
FILL = PatternFill("solid", fgColor="0B5FFF")
TOT = Font(bold=True)
EDIT = PatternFill("solid", fgColor="FFF4DC")
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


totals = {}
for env in ("uat", "prod"):
    ws = wb.create_sheet(f"{env.upper()} monthly")
    header(ws, ["Category", "Service / meter", "Unit", "Unit price (USD)", "Quantity", "Units per month", "Monthly USD", "Monthly QAR", "Description / assumption", "Azure meter id"],
           [16, 46, 16, 15, 11, 14, 15, 15, 70, 40])
    py_total = 0.0
    for cat, key, unit, qty, mult, note in bom(env):
        p, meter, desc = P[key] if isinstance(key, str) else key
        r = ws.max_row + 1
        ws.append([cat, desc, unit, p, qty, mult, f"=D{r}*E{r}*F{r}", f"=G{r}*{QAR}", note, meter])
        for col in ("E", "F"):
            ws[f"{col}{r}"].fill = EDIT
        ws[f"D{r}"].number_format = '"$"#,##0.0000'
        ws[f"G{r}"].number_format = USD
        ws[f"H{r}"].number_format = QARF
        py_total += p * qty * mult
    r = ws.max_row + 1
    ws.append(["TOTAL", "", "", "", "", "", f"=SUM(G2:G{r - 1})", f"=SUM(H2:H{r - 1})", "List prices (pay-as-you-go), before tax and negotiated discounts", ""])
    for c in ws[r]:
        c.font = TOT
    ws[f"G{r}"].number_format = USD
    ws[f"H{r}"].number_format = QARF
    for row in ws.iter_rows(min_row=2):
        for c in row:
            c.alignment = Alignment(wrap_text=True, vertical="top")
    totals[env] = (py_total, f"'{env.upper()} monthly'!G{r}")

# ---- reservations / 5-year TCO
res = wb.create_sheet("5-year TCO")
header(res, ["Item", "Year 1", "Year 2", "Year 3", "Year 4", "Year 5", "5-year total", "Notes"], [52, 15, 15, 15, 15, 15, 17, 70])
devices = [500, 1000, 1800, 2600, 3500]
res.append(["Connected devices (production, end of year)"] + devices + ["", "Planning assumption – edit (yellow)"])
for c in res[2][1:6]:
    c.fill = EDIT
res.append(["Extra app nodes for growth (D8s_v5, +1 per 1,500 devices above 1,500)", "=MAX(0,ROUNDUP((B2-1500)/1500,0))", "=MAX(0,ROUNDUP((C2-1500)/1500,0))",
            "=MAX(0,ROUNDUP((D2-1500)/1500,0))", "=MAX(0,ROUNDUP((E2-1500)/1500,0))", "=MAX(0,ROUNDUP((F2-1500)/1500,0))", "", "Single-active platform pod scales vertically first; nodes host growth and drills"])
d8 = P["d8"][0]
res.append(["PROD – pay-as-you-go (base)"] + [f"={totals['prod'][1]}*12+{d8}*{HOURS}*12*{c}3" for c in "BCDEF"] + ["=SUM(B4:F4)", "Monthly PROD total × 12 plus growth nodes"])
res.append(["UAT – pay-as-you-go"] + [f"={totals['uat'][1]}*12"] * 5 + ["=SUM(B5:F5)", "Could be stopped outside test windows (AKS stop/start) to save ≈ 40–60 %"])
# compute reserved share: system + app nodes + PG vCores
sys_prod = 3 * P["d2"][0] * HOURS * 12
app_prod = 2 * d8 * HOURS * 12
pg_prod = 2 * P["pg_4vc"][0] * HOURS * 12
ri3_vm = 3 * P["d2_3y"][0] / 3 + 2 * P["d8_3y"][0] / 3
ri1_vm = 3 * P["d2_1y"][0] + 2 * P["d8_1y"][0]
res.append(["Saving with 1-year VM reservations (PROD nodes)"] + [f"=-({sys_prod + app_prod:.2f}-{ri1_vm:.2f})"] * 5 + ["=SUM(B6:F6)", "Reservation prices from the same snapshot (upfront per term); PostgreSQL vCore reservations add further savings"])
res.append(["Saving with 3-year VM reservations (PROD nodes) – alternative"] + [f"=-({sys_prod + app_prod:.2f}-{ri3_vm:.2f})"] * 5 + ["=SUM(B7:F7)", "Use either row 6 or row 7, not both"])
res.append(["Total cloud cost – PAYG (PROD + UAT)", "=B4+B5", "=C4+C5", "=D4+D5", "=E4+E5", "=F4+F5", "=SUM(B8:F8)", ""])
res.append(["Total cloud cost – with 3-year VM reservations", "=B8+B7", "=C8+C7", "=D8+D7", "=E8+E7", "=F8+F7", "=SUM(B9:F9)", ""])
res.append(["Cloud cost per connected device per month (PAYG)", "=B8/12/B2", "=C8/12/C2", "=D8/12/D2", "=E8/12/E2", "=F8/12/F2", "", "Excludes people, licences and third-party services"])
res.append(["Total in QAR (PAYG)", f"=B8*{QAR}", f"=C8*{QAR}", f"=D8*{QAR}", f"=E8*{QAR}", f"=F8*{QAR}", f"=G8*{QAR}", "Fixed peg 3.64 QAR/USD"])
for row in res.iter_rows(min_row=3, max_row=res.max_row):
    for c in row[1:7]:
        c.number_format = USD
for c in res[res.max_row][1:7]:
    c.number_format = QARF
for c in res[2][1:6]:
    c.number_format = "#,##0"
for c in res[3][1:6]:
    c.number_format = "0"
for row in res.iter_rows(min_row=2):
    for c in row:
        c.alignment = Alignment(wrap_text=True, vertical="top")

# ---- one-time & non-Azure
ot = wb.create_sheet("One-time & non-Azure")
header(ot, ["Item", "Effort / quantity", "Unit", "Your rate", "Cost", "Notes"], [58, 16, 12, 12, 14, 70])
ONE = [
    ("Landing zone, subscriptions, Entra groups, policies (Setup Guide §1–3)", 3, "person-days", "Platform SRE"),
    ("Terraform UAT + PROD, preflight, secrets bootstrap (§4–5)", 3, "person-days", ""),
    ("Helm deploy, phase-2 App Gateway with public certificate (§6–8)", 2, "person-days", ""),
    ("Site VPN and segmentation validation per hospital (M-02)", 2, "person-days/site", "Network team"),
    ("Security kit + DR drills acceptance (§9, M-11)", 2, "person-days", ""),
    ("Penetration test (M-10)", 1, "engagement", "External – obtain quotes"),
    ("Public TLS certificate for the console hostname", 1, "per year", "Customer CA or commercial CA"),
    ("Terminology server licence (LOINC/SNOMED CT) – optional (M-12)", 1, "per year", "Vendor quote"),
    ("Gateway hardware per site – optional (M-03)", 1, "per site", "Depends on device mix"),
]
for item, q, unit, note in ONE:
    r = ot.max_row + 1
    ot.append([item, q, unit, None, f"=IF(D{r}=\"\",\"\",B{r}*D{r})", note])
    ot[f"D{r}"].fill = EDIT
    ot[f"B{r}"].fill = EDIT
for row in ot.iter_rows(min_row=2):
    for c in row:
        c.alignment = Alignment(wrap_text=True, vertical="top")

# ---- assumptions & sources
asm = wb.create_sheet("Assumptions & sources")
header(asm, ["Assumption", "UAT", "PROD", "Notes"], [48, 14, 14, 80])
for k, label in [("log_gb_day", "Log Analytics ingestion GB/day"), ("retention_extra_gb", "Retention GB beyond 31 days"), ("egress_gb", "Internet egress GB/month"),
                 ("archive_gb", "Immutable archive GB"), ("prom_samples_10m", "Prometheus samples (×10M)/month"), ("kv_ops_10k", "Key Vault operations (×10K)/month"),
                 ("fw_gb", "Firewall data GB/month"), ("inter_az_gb", "Inter-AZ GB/month")]:
    asm.append([label, ASSUME["uat"][k], ASSUME["prod"][k], "Change the quantity cells on the monthly sheets to re-price"])
asm.append([])
asm.append(["Price source", snap["source"], "", "Retrieved " + snap["retrievedAt"] + "; list (retail) prices in USD; QAR at 3.64"])
asm.append(["Region", "qatarcentral (Doha, 3 availability zones, no paired region)", "", "Load Balancer / DNS are global meters; Private Endpoint uses the global list price (not returned for qatarcentral)"])
asm.append(["Not included", "Taxes, support plan, negotiated discounts, Microsoft Defender for Servers (not used: AKS nodes covered by Defender for Containers), people", "", ""])
for row in asm.iter_rows(min_row=2):
    for c in row:
        c.alignment = Alignment(wrap_text=True, vertical="top")

# ---- summary first
sm = wb.create_sheet("Summary", 0)
header(sm, ["Environment", "Monthly USD (PAYG)", "Monthly QAR", "Annual USD", "Annual QAR", "What it is"], [30, 18, 16, 16, 16, 80])
sm.append(["UAT / test", f"={totals['uat'][1]}", f"=B2*{QAR}", "=B2*12", f"=D2*{QAR}",
           "Qatar Central; AKS (2 system + 1 app node), PostgreSQL 2 vCore without HA, Key Vault Premium, ACR, WAF, Log Analytics + Sentinel, no firewall/VPN"])
sm.append(["Production", f"={totals['prod'][1]}", f"=B3*{QAR}", "=B3*12", f"=D3*{QAR}",
           "Zone-redundant AKS (3 system + 2 app D8s_v5), PostgreSQL 4 vCore zone-redundant HA, Azure Firewall, VPN, WAF v2, Defender plans, Sentinel, 90-day logs"])
sm.append(["Total", "=B2+B3", "=C2+C3", "=D2+D3", "=E2+E3", "List prices; see '5-year TCO' for reservations and growth"])
for row in sm.iter_rows(min_row=2):
    for c in row[1:5]:
        c.number_format = USD if c.column in (2, 4) else QARF
    for c in row:
        c.alignment = Alignment(wrap_text=True, vertical="top")
for c in sm[4]:
    c.font = TOT
sm.append([])
sm.append(["Prices: Azure Retail Prices API, region qatarcentral, retrieved " + snap["retrievedAt"] + ". Edit yellow cells to re-price; every line shows its meter id."])

os.makedirs(os.path.dirname(OUT), exist_ok=True)
wb.save(OUT)
print("UAT  monthly USD %.0f  (QAR %.0f)" % (totals["uat"][0], totals["uat"][0] * QAR))
print("PROD monthly USD %.0f  (QAR %.0f)" % (totals["prod"][0], totals["prod"][0] * QAR))
print("->", OUT)
