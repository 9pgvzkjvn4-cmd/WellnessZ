#!/usr/bin/env python3
"""IM-E10-BL-007 – supplier register for the eQMS: open-source components from the CycloneDX SBOM of the build, cloud
services of the Azure Qatar Central deployment, and device vendors from the catalogue – with licence, criticality,
data access (PHI yes/no) and the assessment status the QMS owner completes.
Usage: python3 tools/compliance/supplier_register.py <sbom.cdx.json> <out.xlsx>"""
import json, os, sys, collections
from openpyxl import Workbook
from openpyxl.styles import Font, PatternFill, Alignment

sbom, out = sys.argv[1], sys.argv[2]
root = os.path.join(os.path.dirname(__file__), "..", "..")
devices = json.load(open(os.path.join(root, "intimed-platform/src/main/resources/seed/devices.json")))
comps = json.load(open(sbom)).get("components", [])

CLOUD = [
    ("Microsoft Azure – AKS (Kubernetes)", "Compute for the platform pod (Qatar Central)", "High", "Yes (processes PHI in memory)", "Microsoft Online Services DPA; ISO 27001/SOC 2; Qatar Central data residency"),
    ("Microsoft Azure – Database for PostgreSQL Flexible Server", "System of record (encrypted documents)", "High", "Yes (application-encrypted PHI)", "DPA; in-region backups only"),
    ("Microsoft Azure – Key Vault Premium (HSM)", "Master key, secrets, image-signing key", "Critical", "No (keys only)", "FIPS 140 HSM-backed keys"),
    ("Microsoft Azure – Blob Storage (immutable)", "Evidence packs and audit archive (WORM)", "High", "No PHI in evidence (masked) / audit details masked", "Immutability policy"),
    ("Microsoft Azure – Container Registry", "Signed platform images", "High", "No", "Private endpoint"),
    ("Microsoft Azure – Application Gateway WAF v2", "Console ingress, WAF", "High", "Transit only (TLS re-encryption)", ""),
    ("Microsoft Azure – Firewall / VPN Gateway", "Egress control, site-to-site VPN", "High", "Transit only (encrypted)", ""),
    ("Microsoft Azure – Monitor / Log Analytics / Sentinel", "Operations telemetry and SIEM", "High", "No PHI by design (masked audit)", "Retention per tfvars"),
    ("Microsoft Defender for Cloud", "Threat protection", "Medium", "Metadata", ""),
    ("GitHub (Actions, repository)", "Source, CI/CD, OIDC to Azure", "High", "No", "No PHI in repository or pipelines"),
]

wb = Workbook(); wb.remove(wb.active)
def sheet(name, head, rows, widths):
    ws = wb.create_sheet(name); ws.append(head)
    for c in ws[1]: c.font = Font(bold=True, color="FFFFFF"); c.fill = PatternFill("solid", fgColor="0B5FFF")
    for r in rows: ws.append(list(r))
    for i, w in enumerate(widths, 1): ws.column_dimensions[ws.cell(1, i).column_letter].width = w
    for row in ws.iter_rows(min_row=2):
        for c in row: c.alignment = Alignment(wrap_text=True, vertical="top")
    ws.freeze_panes = "A2"; ws.auto_filter.ref = ws.dimensions
oss = []
for c in comps:
    lic = ", ".join(sorted({(l.get("license") or {}).get("id") or (l.get("license") or {}).get("name") or l.get("expression") or "" for l in c.get("licenses", [])} - {""})) or "unknown"
    oss.append((c.get("group", ""), c.get("name"), c.get("version"), lic, c.get("purl", ""), "Medium" if c.get("scope") != "optional" else "Low", "Pending", ""))
sheet("Open source (SBOM)", ["Group", "Component", "Version", "Licence", "purl", "Criticality", "QMS assessment", "Notes"], sorted(oss), [32, 34, 14, 26, 60, 12, 16, 30])
sheet("Cloud & tooling", ["Supplier / service", "Use", "Criticality", "PHI access", "Assurance evidence", "QMS assessment"], [r + ("Pending",) for r in CLOUD], [46, 44, 12, 32, 50, 16])
vend = collections.Counter(d["manufacturer"] for d in devices)
sheet("Device vendors", ["Manufacturer", "Catalogued models", "Certified drivers (lab)", "MDS2 / SBOM received", "QMS assessment"],
      [(m, n, "", "", "Pending") for m, n in sorted(vend.items())], [44, 18, 22, 22, 16])
wb.save(out)
print("supplier register:", len(oss), "open-source components,", len(CLOUD), "cloud services,", len(vend), "device vendors ->", out)
