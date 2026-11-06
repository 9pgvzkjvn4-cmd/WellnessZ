#!/usr/bin/env python3
"""SC-19 / IM-E06-BL-010 – Google Cloud posture of an IntiMed_GCP environment (me-central1, Doha). Uses gcloud (login
with roles/viewer + roles/iam.securityReviewer on the project). Checks data residency, private networking, encryption,
identity, supply chain and monitoring:
  location org policy · Cloud SQL / GKE / buckets / secrets / KMS / Artifact Registry in me-central1 · Cloud SQL
  private IP only, TLS enforced, CMEK, PITR backups ≥ 14 days kept in Doha, pgaudit · GKE private nodes + private
  endpoint, Workload Identity, Shielded nodes, Dataplane V2, Binary Authorization, CMEK secrets encryption, no legacy
  ABAC / basic auth · buckets: uniform access, public access prevention, CMEK, retention on evidence/audit · secrets
  CMEK + Doha-only replication · KMS HSM + rotation · Artifact Registry CMEK + immutable tags · Cloud Armor with the
  preconfigured WAF rules · Binary Authorization enforced in PROD · _Default log sink disabled · no 0.0.0.0/0 ingress.
Offline/CI: --from-json DIR with files produced by --dump DIR. INTIMED_ENV=prod enables the production-only checks."""
import argparse, json, os, subprocess, sys, kitlib as k

S = "16_gcp_posture_check"
REGION = "me-central1"
ap = argparse.ArgumentParser()
ap.add_argument("--project", default=os.environ.get("GCP_PROJECT"))
ap.add_argument("--env", default=os.environ.get("INTIMED_ENV", "uat"))
ap.add_argument("--from-json")
ap.add_argument("--dump")
a = ap.parse_args()
if not a.project and not a.from_json:
    print("  SKIP 16_gcp_posture_check – set GCP_PROJECT or --project"); k.finish(S); sys.exit(0)
PROD = a.env == "prod"

QUERIES = {
    "orgpolicy": ["org-policies", "describe", "gcp.resourceLocations", "--effective", "--project", "{p}"],
    "sql": ["sql", "instances", "list", "--project", "{p}"],
    "gke": ["container", "clusters", "list", "--project", "{p}"],
    "buckets": ["storage", "buckets", "list", "--project", "{p}"],
    "secrets": ["secrets", "list", "--project", "{p}"],
    "keyrings": ["kms", "keyrings", "list", "--location", REGION, "--project", "{p}"],
    "artifacts": ["artifacts", "repositories", "list", "--location", REGION, "--project", "{p}"],
    "armor": ["compute", "security-policies", "list", "--regions", REGION, "--project", "{p}"],
    "binauthz": ["container", "binauthz", "policy", "export", "--project", "{p}"],
    "sinks": ["logging", "sinks", "list", "--project", "{p}"],
    "firewalls": ["compute", "firewall-rules", "list", "--project", "{p}"],
}


def gc(args):
    out = subprocess.run(["gcloud", *args, "--format=json"], capture_output=True, text=True)
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
            data[key] = gc([x.format(p=a.project) for x in q])
        except Exception as e:  # noqa: BLE001
            data[key] = []; print("  WARN", key, e)
if not a.from_json:
    for kr in data["keyrings"] or []:
        try:
            kr["_keys"] = gc(["kms", "keys", "list", "--keyring", kr["name"], "--location", REGION, "--project", a.project])
        except Exception:  # noqa: BLE001
            kr["_keys"] = []
if a.dump:
    os.makedirs(a.dump, exist_ok=True)
    for key, v in data.items():
        json.dump(v, open(os.path.join(a.dump, key + ".json"), "w"), indent=1)

loc = lambda s: (s or "").lower()  # noqa: E731
# ---- residency
op = data["orgpolicy"] or {}
allowed = [v for r in (op.get("spec") or {}).get("rules", []) for v in (r.get("values") or {}).get("allowedValues", [])]
k.check(S, "SC-19", "org policy gcp.resourceLocations restricted to me-central1", bool(allowed) and all("me-central1" in v for v in allowed), allowed)
outside = [i["name"] for i in data["sql"] if i.get("region") != REGION] + [c["name"] for c in data["gke"] if c.get("location") != REGION] \
    + [b["name"] for b in data["buckets"] if loc(b.get("location")) != REGION] + [r["name"] for r in data["artifacts"] if f"/locations/{REGION}/" not in r.get("name", "")]
k.check(S, "SC-19", "Cloud SQL, GKE, buckets and Artifact Registry in me-central1 (%d resources)" % (len(data["sql"]) + len(data["gke"]) + len(data["buckets"]) + len(data["artifacts"])),
        not outside and len(data["sql"]) + len(data["gke"]) > 0, outside[:5])
for s in data["secrets"]:
    reps = ((s.get("replication") or {}).get("userManaged") or {}).get("replicas", [])
    k.check(S, "SC-19", f"Secret {s['name'].split('/')[-1]}: replicated in me-central1 only, CMEK",
            bool(reps) and all(r.get("location") == REGION and (r.get("customerManagedEncryption") or {}).get("kmsKeyName") for r in reps))
# ---- Cloud SQL
for i in data["sql"]:
    st = i.get("settings", {}); ipc = st.get("ipConfiguration", {}); bk = st.get("backupConfiguration", {})
    k.check(S, "SC-04", f"Cloud SQL {i['name']}: private IP only (no public IPv4)", ipc.get("ipv4Enabled") is False and bool(ipc.get("privateNetwork")))
    k.check(S, "SC-04", f"Cloud SQL {i['name']}: TLS enforced", ipc.get("sslMode") in ("ENCRYPTED_ONLY", "TRUSTED_CLIENT_CERTIFICATE_REQUIRED"))
    k.check(S, "SC-10", f"Cloud SQL {i['name']}: CMEK", bool((i.get("diskEncryptionConfiguration") or {}).get("kmsKeyName")))
    k.check(S, "SC-10", f"Cloud SQL {i['name']}: PITR backups ≥ 14 days kept in me-central1",
            bk.get("enabled") is True and bk.get("pointInTimeRecoveryEnabled") is True and bk.get("location") == REGION
            and int((bk.get("backupRetentionSettings") or {}).get("retainedBackups", 0)) >= 14)
    k.check(S, "SC-08", f"Cloud SQL {i['name']}: pgaudit enabled", any(f.get("name") == "cloudsql.enable_pgaudit" and f.get("value") == "on" for f in st.get("databaseFlags", [])))
    if PROD:
        k.check(S, "SC-19", f"Cloud SQL {i['name']}: regional HA (PROD)", st.get("availabilityType") == "REGIONAL")
# ---- GKE
for c in data["gke"]:
    pc = c.get("privateClusterConfig") or {}
    ip_ep = ((c.get("controlPlaneEndpointsConfig") or {}).get("ipEndpointsConfig") or {})
    k.check(S, "SC-02", f"GKE {c['name']}: private nodes, no public control-plane endpoint",
            pc.get("enablePrivateNodes") is True and (pc.get("enablePrivateEndpoint") is True or ip_ep.get("enablePublicEndpoint") is False))
    k.check(S, "SC-17", f"GKE {c['name']}: Workload Identity", bool((c.get("workloadIdentityConfig") or {}).get("workloadPool")))
    k.check(S, "SC-17", f"GKE {c['name']}: Shielded nodes + no legacy ABAC / client certificate",
            (c.get("shieldedNodes") or {}).get("enabled") is True and not (c.get("legacyAbac") or {}).get("enabled")
            and not ((c.get("masterAuth") or {}).get("clientCertificateConfig") or {}).get("issueClientCertificate"))
    k.check(S, "SC-18", f"GKE {c['name']}: Dataplane V2 (network policy enforced)", (c.get("networkConfig") or {}).get("datapathProvider") == "ADVANCED_DATAPATH")
    k.check(S, "SC-18", f"GKE {c['name']}: Binary Authorization", (c.get("binaryAuthorization") or {}).get("evaluationMode") == "PROJECT_SINGLETON_POLICY_ENFORCE")
    k.check(S, "SC-05", f"GKE {c['name']}: Kubernetes secrets encrypted with CMEK", (c.get("databaseEncryption") or {}).get("state") == "ENCRYPTED")
# ---- buckets
for b in data["buckets"]:
    k.check(S, "SC-10", f"Bucket {b['name']}: uniform access, public access prevention, CMEK",
            b.get("uniform_bucket_level_access") is True and b.get("public_access_prevention") == "enforced" and bool(b.get("default_kms_key")))
    if b["name"].endswith(("-evidence", "-audit")):
        rp = b.get("retention_policy") or {}
        k.check(S, "SC-20", f"Bucket {b['name']}: retention policy (WORM){' locked' if PROD else ''}",
                int(rp.get("retentionPeriod", 0) or 0) >= 86400 and (rp.get("isLocked") is True or not PROD))
# ---- KMS
for kr in data["keyrings"]:
    for key in kr.get("_keys", []):
        if key.get("purpose") == "ENCRYPT_DECRYPT":
            k.check(S, "SC-05", f"KMS key {key['name'].split('/')[-1]}: HSM + rotation ≤ 90 days",
                    (key.get("versionTemplate") or {}).get("protectionLevel") == "HSM" and key.get("rotationPeriod", "0s").rstrip("s").isdigit()
                    and int(key.get("rotationPeriod", "0s").rstrip("s")) <= 90 * 86400)
        else:
            k.check(S, "SC-18", f"KMS key {key['name'].split('/')[-1]}: HSM signing key", (key.get("versionTemplate") or {}).get("protectionLevel") == "HSM")
# ---- Artifact Registry
for r in data["artifacts"]:
    k.check(S, "SC-18", f"Artifact Registry {r['name'].split('/')[-1]}: CMEK + immutable tags", bool(r.get("kmsKeyName")) and (r.get("dockerConfig") or {}).get("immutableTags") is True)
# ---- Cloud Armor
for p in data["armor"]:
    exprs = " ".join(((r.get("match") or {}).get("expr") or {}).get("expression", "") for r in p.get("rules", []))
    k.check(S, "SC-03", f"Cloud Armor {p['name']}: preconfigured WAF (SQLi, XSS, LFI, RCE) + rate-based ban",
            all(x in exprs for x in ("sqli-", "xss-", "lfi-", "rce-")) and any(r.get("action") == "rate_based_ban" for r in p.get("rules", [])))
k.check(S, "SC-03", "Cloud Armor policy present for the console", len(data["armor"]) > 0)
# ---- Binary Authorization policy
bp = data["binauthz"] or {}
rule = bp.get("defaultAdmissionRule") or {}
k.check(S, "SC-18", "Binary Authorization: attestation required by default rule", rule.get("evaluationMode") == "REQUIRE_ATTESTATION" and bool(rule.get("requireAttestationsBy")))
if PROD:
    k.check(S, "SC-18", "Binary Authorization: enforced (PROD)", rule.get("enforcementMode") == "ENFORCED_BLOCK_AND_AUDIT_LOG")
# ---- logging
sinks = {s["name"]: s for s in data["sinks"]}
k.check(S, "SC-08", "_Default log sink disabled (logs stay in the Doha log bucket)", (sinks.get("_Default") or {}).get("disabled") is True)
k.check(S, "SC-08", "log sink to a me-central1 log bucket", any(f"/locations/{REGION}/buckets/" in (s.get("destination") or "") for s in data["sinks"]))
# ---- firewall
open_rules = [f["name"] for f in data["firewalls"] if f.get("direction") == "INGRESS" and not f.get("disabled") and f.get("allowed") and "0.0.0.0/0" in (f.get("sourceRanges") or [])]
k.check(S, "SC-02", "no VPC firewall rule allows ingress from 0.0.0.0/0", not open_rules, open_rules)
k.finish(S)
