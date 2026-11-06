#!/usr/bin/env python3
"""SC-19 / IM-E06-BL-010 – Azure posture of an IntiMed environment (Qatar Central). Uses the Azure CLI (az login with
Reader on the resource group). Checks data residency, private networking, encryption, identity and monitoring:
  every resource in qatarcentral (or global) · Key Vault premium, RBAC, purge protection, no public access by default
  · Storage https-only, TLS 1.2, no shared keys, no public blobs, immutability on evidence/audit · PostgreSQL private,
  TLS enforced (require_secure_transport, TLS ≥ 1.2), backups ≥ 14 days · AKS local accounts disabled, Entra RBAC,
  workload identity, Azure Policy, Defender, network policy, API server private or IP-restricted · ACR admin disabled
  · Application Gateway WAF in Prevention · diagnostic settings to Log Analytics · Allowed-locations policy assigned.
Offline/CI: --from-json DIR with files produced by --dump DIR."""
import argparse, json, os, subprocess, sys, kitlib as k

S = "16_azure_posture_check"
ap = argparse.ArgumentParser()
ap.add_argument("--resource-group", default=os.environ.get("AZ_RESOURCE_GROUP"))
ap.add_argument("--from-json")
ap.add_argument("--dump")
a = ap.parse_args()
if not a.resource_group and not a.from_json:
    print("  SKIP 16_azure_posture_check – set AZ_RESOURCE_GROUP or --resource-group"); k.finish(S); sys.exit(0)

QUERIES = {
    "resources": ["resource", "list", "-g", "{rg}"],
    "keyvaults": ["keyvault", "list", "-g", "{rg}"],
    "storage": ["storage", "account", "list", "-g", "{rg}"],
    "postgres": ["postgres", "flexible-server", "list", "-g", "{rg}"],
    "aks": ["aks", "list", "-g", "{rg}"],
    "acr": ["acr", "list", "-g", "{rg}"],
    "appgw": ["network", "application-gateway", "list", "-g", "{rg}"],
    "waf": ["network", "application-gateway", "waf-policy", "list", "-g", "{rg}"],
    "policy": ["policy", "assignment", "list", "-g", "{rg}"],
}


def az(args):
    out = subprocess.run(["az", *args, "-o", "json"], capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(out.stderr.strip()[:200])
    return json.loads(out.stdout or "null")


data = {}
for key, q in QUERIES.items():
    if a.from_json:
        p = os.path.join(a.from_json, key + ".json")
        data[key] = json.load(open(p)) if os.path.exists(p) else []
    else:
        try:
            data[key] = az([x.format(rg=a.resource_group) for x in q])
        except Exception as e:  # noqa: BLE001
            data[key] = []; print("  WARN", key, e)
if not a.from_json:
    for pg in data["postgres"]:
        for prm in ("require_secure_transport", "ssl_min_protocol_version"):
            try:
                pg.setdefault("_params", {})[prm] = az(["postgres", "flexible-server", "parameter", "show", "-g", a.resource_group, "-s", pg["name"], "-n", prm]).get("value")
            except Exception:  # noqa: BLE001
                pass
    for kind in ("keyvaults", "storage", "postgres", "aks", "acr", "appgw"):
        for r in data[kind]:
            try:
                r["_diag"] = az(["monitor", "diagnostic-settings", "list", "--resource", r["id"] + ("/blobServices/default" if kind == "storage" else "")])
            except Exception:  # noqa: BLE001
                r["_diag"] = []
    for st in data["storage"]:
        try:
            st["_containers"] = az(["storage", "container-rm", "list", "--storage-account", st["id"]])
            for c in st["_containers"]:
                c["_immutability"] = az(["storage", "container", "immutability-policy", "show", "--account-name", st["name"], "-c", c["name"], "-g", a.resource_group])
        except Exception:  # noqa: BLE001
            pass
if a.dump:
    os.makedirs(a.dump, exist_ok=True)
    for key, v in data.items():
        json.dump(v, open(os.path.join(a.dump, key + ".json"), "w"), indent=1)

# ---- residency
bad = [r["name"] for r in data["resources"] if r.get("location") not in ("qatarcentral", "global")]
k.check(S, "SC-19", "all resources in Qatar Central (or global) – %d resources" % len(data["resources"]), not bad and len(data["resources"]) > 0, bad[:5])
k.check(S, "SC-19", "Allowed-locations policy assigned", any("allowed" in (p.get("displayName") or p.get("name") or "").lower() or "residency" in (p.get("name") or "") for p in data["policy"]))
# ---- Key Vault
for v in data["keyvaults"]:
    p = v.get("properties", {})
    k.check(S, "SC-05", f"Key Vault {v['name']}: premium (HSM), RBAC, purge protection", p.get("sku", {}).get("name", "").lower() == "premium"
            and p.get("enableRbacAuthorization") is True and p.get("enablePurgeProtection") is True)
    k.check(S, "SC-05", f"Key Vault {v['name']}: firewall default deny", p.get("networkAcls", {}).get("defaultAction") == "Deny")
# ---- Storage
for s in data["storage"]:
    k.check(S, "SC-10", f"Storage {s['name']}: https only, TLS 1.2, no shared key, no public blobs",
            s.get("enableHttpsTrafficOnly") is True and s.get("minimumTlsVersion") == "TLS1_2" and s.get("allowSharedKeyAccess") is False and s.get("allowBlobPublicAccess") is False)
    k.check(S, "SC-10", f"Storage {s['name']}: public network disabled, zone-redundant", s.get("publicNetworkAccess") == "Disabled" and "ZRS" in s.get("sku", {}).get("name", ""))
    cons = s.get("_containers", [])
    if cons:
        k.check(S, "SC-20", f"Storage {s['name']}: immutability policy on evidence and audit",
                all(c.get("_immutability", {}).get("immutabilityPeriodSinceCreationInDays", 0) >= 1 for c in cons if c["name"] in ("evidence", "audit")))
# ---- PostgreSQL
for pg in data["postgres"]:
    net = pg.get("network", {})
    k.check(S, "SC-04", f"PostgreSQL {pg['name']}: private access only", net.get("publicNetworkAccess") == "Disabled")
    prm = pg.get("_params", {})
    k.check(S, "SC-04", f"PostgreSQL {pg['name']}: TLS enforced (≥ 1.2)", str(prm.get("require_secure_transport", "")).lower() == "on" and prm.get("ssl_min_protocol_version") in ("TLSv1.2", "TLSv1.3"))
    k.check(S, "SC-10", f"PostgreSQL {pg['name']}: backups ≥ 14 days", pg.get("backup", {}).get("backupRetentionDays", 0) >= 14)
    k.check(S, "SC-19", f"PostgreSQL {pg['name']}: HA mode recorded ({pg.get('highAvailability', {}).get('mode')})", "highAvailability" in pg)
# ---- AKS
for c in data["aks"]:
    k.check(S, "SC-02", f"AKS {c['name']}: local accounts disabled, Entra RBAC", c.get("disableLocalAccounts") is True and (c.get("aadProfile") or {}).get("enableAzureRbac") is True)
    sp = c.get("securityProfile") or {}
    k.check(S, "SC-17", f"AKS {c['name']}: workload identity + OIDC", (sp.get("workloadIdentity") or {}).get("enabled") is True and (c.get("oidcIssuerProfile") or {}).get("enabled") is True)
    k.check(S, "SC-17", f"AKS {c['name']}: Defender + Azure Policy", (sp.get("defender") or {}).get("securityMonitoring", {}).get("enabled") is True
            and ((c.get("addonProfiles") or {}).get("azurepolicy") or {}).get("enabled") is True)
    k.check(S, "SC-18", f"AKS {c['name']}: network policy enabled", bool((c.get("networkProfile") or {}).get("networkPolicy")))
    api = c.get("apiServerAccessProfile") or {}
    k.check(S, "SC-02", f"AKS {c['name']}: API server private or IP-restricted", api.get("enablePrivateCluster") is True or bool(api.get("authorizedIpRanges")))
# ---- ACR
for r in data["acr"]:
    k.check(S, "SC-18", f"ACR {r['name']}: admin user disabled, no anonymous pull", r.get("adminUserEnabled") is False and not r.get("anonymousPullEnabled"))
# ---- WAF
for w in data["waf"]:
    k.check(S, "SC-03", f"WAF policy {w['name']}: enabled in Prevention mode", (w.get("policySettings") or {}).get("mode") == "Prevention" and (w.get("policySettings") or {}).get("state") == "Enabled")
# ---- diagnostics
for kind in ("keyvaults", "storage", "postgres", "aks", "acr"):
    for r in data[kind]:
        if "_diag" in r:
            k.check(S, "SC-08", f"{ {'keyvaults': 'Key Vault', 'storage': 'Storage', 'postgres': 'PostgreSQL', 'aks': 'AKS', 'acr': 'ACR'}[kind]} {r['name']}: diagnostic settings to Log Analytics", any(d.get("workspaceId") for d in r["_diag"]))
k.finish(S)
