"""Release 2.2 (IntiMed_GCP – Google Cloud me-central1, Doha) overlay on top of release 2.1 (tools/content/release_azure.py).

Applied after the Azure overlay by tools/content/traceability.py. Also produces the GCP flavour of the Manual Backlog
Execution Guide (docs/gcp/IntiMed_GCP_Manual_Backlog_Execution_Guide.md) – same sections M-01…M-16, with the cloud
specific steps rewritten for Google Cloud.
"""
import copy
import release_azure as A

D, P, F = A.D, A.P, A.F
J = A.J
G = "deploy/gcp/terraform"

INCREMENTS = [
    {"id": "L9", "name": "IntiMed_GCP: Google Cloud me-central1 (Doha) – Terraform + Helm gcp mode, Secret Manager / GCS adapters, Binary Authorization, Cloud Armor, CI/CD (WIF), GCP posture checks, cost/TCO",
     "sprints": "IM-S1 cloud track, IM-S13, IM-S14"},
]

_ov = A.OVERLAY


def _plus(key, gcp_evidence):
    st, inc, ev, reason, strat, man = _ov[key]
    return (st, inc, ev + "; GCP (IntiMed_GCP): " + gcp_evidence, reason, strat, man)


OVERLAY = {
    "IM-E08-BL-003": (D, "L9", G + " (VPC, private regional GKE with Workload Identity/Dataplane V2/Shielded nodes/Binary Authorization, Cloud SQL PG16 regional HA + PITR, Cloud KMS HSM CMEK, Secret Manager Doha-only, Artifact Registry, retention-locked GCS, Cloud Armor, regional ALB, HA VPN, Cloud NGFW, org location policy, monitoring, budget; terraform validate + 3 plan tests) and the Helm chart cloud=gcp mode",
                      "", "", "M-01"),
    "IM-E06-BL-004": (D, "L9", J + "security/secrets/{AzureKeyVaultSecretStore,AzureIdentity,GcpSecretManagerSecretStore,GcpIdentity,HashiCorpVaultSecretStore}.java – Azure Key Vault, GCP Secret Manager (Doha-only replication, tenant-bound annotations) and Vault behind one SecretStore contract; file-based runtime secrets (common/Secrets.java); CloudAdaptersTest + GcpAdaptersTest",
                      "", "", ""),
    "IM-E06-BL-010": (P, "L9", "security-test-kit/{16_azure_posture_check,16_gcp_posture_check,05_container_runtime_check,01_network_segmentation_check}.py + run-kit-aks.sh / run-kit-gke.sh; azure/gcp good+bad fixtures; both release pipelines run 16 + 05 against UAT",
                      "Private-cloud (OpenShift/RKE2) posture checks not built", "Port script 16 to the private-cloud platform when one is commissioned (guide M-13)", "M-13"),
    "IM-E08-BL-004": (F, "", "", "Private-cloud blueprint is outside the Azure (2.1) and GCP (2.2) releases", "Helm values for OpenShift/RKE2 + Patroni when a private-cloud customer is signed (guide M-13)", "M-13"),
    "IM-E08-BL-010": (P, "L9", "Reference deployments on Azure Qatar Central (2.1) and Google Cloud me-central1 Doha (2.2, IntiMed_GCP) – IntiMed-hosted or client-hosted, Terraform envs uat/prod, setup guides",
                      "Client-hosted private-cloud reference not built", "Guide M-13", "M-13"),
    "IM-E08-BL-005": _plus("IM-E08-BL-005", "regional GKE + regional PD (pod restarts in the second zone with its data), Cloud SQL REGIONAL HA + 35-day PITR, retention-locked GCS archive, deploy/gcp/scripts/dr-drill.sh (D1–D4), docs/gcp/IntiMed_GCP_Operations_and_DR.md"),
    "IM-E08-BL-006": _plus("IM-E08-BL-006", ".github/workflows/intimed-gcp-release.yml (Workload Identity Federation, Trivy gate, Artifact Registry, cosign with the Cloud KMS HSM key, Binary Authorization attestation, UAT gates, PROD approval + enforced attestation), intimed-gcp-infra.yml"),
    "IM-E08-BL-007": _plus("IM-E08-BL-007", "docs/gcp/IntiMed_GCP_SLO_and_Runbooks.md, PromQL alert policies + dashboard + billing budget (monitoring.tf), docs/gcp/IntiMed_GCP_Cost_Model.xlsx"),
    "IM-E08-BL-009": _plus("IM-E08-BL-009", "docs/gcp/IntiMed_GCP_Cost_Model.xlsx (me-central1 price snapshot with per-line source and confidence, UAT/PROD BOM, 5-year TCO with 1y/3y CUDs) built by tools/gcp/cost_model.py"),
    "IM-E01-BL-027": _plus("IM-E01-BL-027", "Managed Service for Prometheus PodMonitoring, PromQL alert policies, Cloud Monitoring dashboard, SIEM log sink → Pub/Sub (Doha)"),
    "IM-E06-BL-002": _plus("IM-E06-BL-002", "VPC firewall rules (device ports from site CIDRs only, deny-all ingress), Dataplane V2 NetworkPolicies, internal passthrough NLB for devices"),
}

# Manual guide section M-13 is now "private cloud" for both cloud flavours.
M13 = {"id": "M-13", "title": "Private cloud (OpenShift / RKE2) and further clouds", "owner": "Platform architecture",
       "why": "Azure Qatar Central (2.1) and Google Cloud me-central1 (2.2, IntiMed_GCP) are delivered; a client-hosted private cloud is triggered by a customer contract.",
       "pre": ["Signed customer requirement", "Target platform (OpenShift 4.x or RKE2) with a CSI driver, an ingress/route and a secret store (Vault)"],
       "steps": ["Reuse the Helm chart; add a values profile for the platform (StorageClass of the platform CSI, Route/Ingress instead of the cloud load balancer, Vault CSI provider).",
                 "Use the HashiCorp Vault SecretStore (same contract and tests as the Azure and GCP stores) and file-based runtime secrets (INTIMED_SECRETS_DIR).",
                 "Provision PostgreSQL 16 with Patroni (3 nodes, synchronous replica) and object storage with object lock (MinIO) for the evidence/audit archive.",
                 "Write the Ansible / GitOps for the platform; port posture script 16 (cluster + database + storage checks).",
                 "Run the full security kit and the DR drills in the new environment."],
       "tool": ["Helm chart", "SecretStore contract (HashiCorpVaultSecretStore)", "security kit"],
       "evidence": ["Kit report in the private-cloud environment", "DR drill report"],
       "items": ["IM-E06-BL-010", "IM-E08-BL-004", "IM-E08-BL-010"]}

GCP_SECTIONS = {
    "M-01": {"title": "Google Cloud organisation, projects and regulatory approvals for health data in Qatar", "owner": "CISO / DPO with the customer's IT and legal",
             "why": "Data residency, the legal basis for processing and the organisation-level governance are decisions for the organisation, not for software.",
             "pre": ["Google Cloud organisation (Cloud Identity / Workspace) with a billing account", "One project per environment (UAT, PROD)", "Named DPO and CISO", "IntiMed_GCP Setup Guide §1–2 completed"],
             "steps": ["Record the data-residency decision: all resources in me-central1 (Doha), enforced by the Terraform region validation and the organisation policy gcp.resourceLocations = in:me-central1-locations. Obtain written confirmation from the Ministry of Public Health / NCSA contact for hosting patient data in Google Cloud Doha.",
                       "Complete the DPIA (appendix A template) for each deployment setting (acute, OH, RPM) and have the DPO sign it; attach it to the change record.",
                       "Accept the Google Cloud Data Processing Addendum (Cloud Data Processing Addendum) for the billing account and record it in the supplier register (M-09). Consider Assured Workloads / Sovereign controls if the regulator requires them.",
                       "Decide cross-region DR: the only other GCC Google region (me-central2, Dammam) is outside Qatar – keep DR in-region across the three Doha zones (default) or obtain approval before adding a secondary region.",
                       "Create the Google groups (intimed-platform-sre, intimed-device-admins, intimed-soc, intimed-auditors) and grant them IAM roles on the projects; enable 2-step verification enforcement for the organisation.",
                       "Enable Security Command Center (Standard at least) at organisation level and route findings to the SOC."],
             "tool": ["deploy/gcp/terraform (region validation + org location policy)", "IntiMed_GCP Setup Guide", "Appendix A DPIA template"],
             "evidence": ["Signed DPIA", "Regulator / customer approval letter on file", "Org policy gcp.resourceLocations effective on both projects", "security-test-kit 16_gcp: residency checks PASS"],
             "items": ["IM-E06-BL-009", "IM-E08-BL-002", "IM-E08-BL-003"]},
    "M-02": {"title": "Hospital and clinic connectivity: HA VPN / Cloud Interconnect and segmentation validation", "owner": "Customer network team with Platform SRE",
             "why": "Site networks, firewalls and IPsec peers are owned by each hospital.",
             "pre": ["Public IP and BGP ASN of the site VPN device, device VLAN CIDRs", "Shared secret exchanged out-of-band (never e-mail)"],
             "steps": ["Add each site to vpn_sites (peer_ip, peer_asn, two /30 BGP link ranges) in envs/prod.local.tfvars and its shared secret to the pipeline secret VPN_SHARED_SECRETS_JSON (JSON map: site name → shared secret); apply Terraform (HA VPN gateway, 2 tunnels per site, Cloud Router BGP).",
                       "Configure the site VPN device with both tunnels (IKEv2) and BGP; confirm both tunnels are ESTABLISHED and BGP sessions UP (gcloud compute vpn-tunnels describe / routers get-status).",
                       "Allow only TCP 2575 and 8443 from the device VLANs to the IntiMed device IP (devices_lb_ip, internal passthrough NLB); block everything else (docs/security/Network-Reference-Designs.md).",
                       "Add the device VLAN CIDRs to device_source_cidrs (VPC firewall rule) and networkPolicy.deviceSourceCidrs (Helm, via render-values.sh); re-deploy.",
                       "From a laptop on each device VLAN run: python3 security-test-kit/01_network_segmentation_check.py --host <devices DNS> --console-host <console DNS> --expect-console blocked",
                       "File the report (results/REPORT.md) with the site acceptance."],
             "tool": ["security-test-kit/01_network_segmentation_check.py", "Terraform edge.tf (HA VPN) / network.tf (firewall)", "Helm NetworkPolicy"],
             "evidence": ["Script 01 report per site with 0 failures", "Both tunnels ESTABLISHED, BGP UP", "Firewall change records"],
             "items": ["IM-E06-BL-002"]},
    "M-11": {"title": "Operations readiness: drills, soak, on-call, FinOps, tracing, WORM lock", "owner": "Platform SRE",
             "why": "These are executed in the operated environment by the operations team.",
             "pre": ["UAT and PROD deployed (IntiMed_GCP Setup Guide)", "gcloud access with the SRE group"],
             "steps": ["Soak: run the intimed-perf-soak workflow at 2× peak (e.g. 120 msg/s for 1 h) against the UAT devices address (through HA VPN or a self-hosted runner in the VPC) with an enrolled soak endpoint; archive soak-report.json.",
                       "DR drills: ENV=uat deploy/gcp/scripts/dr-drill.sh D1 D2 D3 D4 quarterly (PROD semi-annually in a change window); compare RTO/RPO with targets; delete the D3 clone afterwards.",
                       "Lock the retention policies of the evidence/audit buckets after acceptance: ENV=prod deploy/gcp/scripts/lock-retention.sh (irreversible – needs DPO confirmation of the 7-year period), then set lock_archive_retention = true.",
                       "Staff the on-call rota on the Cloud Monitoring notification channels (alert_emails; add PagerDuty/SMS channels); test paging with a synthetic incident.",
                       "Agree patch windows (GKE maintenance window and exclusions; Cloud SQL maintenance window Friday 22:00 UTC = Saturday 01:00 AST).",
                       "Optional tracing: add the OpenTelemetry Java agent with the Google Cloud Trace exporter (no PHI in span attributes).",
                       "Back up the platform master key (Secret Manager intimed-master-key) to the offline break-glass store (two custodians).",
                       "Finance: review IntiMed_GCP_Cost_Model.xlsx, confirm every 'estimate – verify' line in the Google Cloud Pricing Calculator, buy 1- or 3-year committed-use discounts for steady compute and Cloud SQL, keep the billing budgets per project."],
             "tool": ["sdk soak + workflow", "deploy/gcp/scripts/dr-drill.sh, lock-retention.sh", "billing budget + alert policies", "cost model workbook"],
             "evidence": ["Soak report", "Drill reports", "Retention lock confirmation (isLocked = true)", "Finance sign-off of the TCO"],
             "items": ["IM-E01-BL-027", "IM-E01-BL-030", "IM-E08-BL-005", "IM-E08-BL-006", "IM-E08-BL-007", "IM-E08-BL-009"]},
    "M-13": M13,
}

# token substitutions for the remaining sections in the GCP guide
SUBS = [
    ("Key Vault (secret://azure-openai-key) or use managed identity", "Secret Manager (secret://vertex-ai-key) or use Workload Identity"),
    ("Azure OpenAI (Foundry) deployment in Qatar Central if offered for the chosen model – otherwise a regulator-approved region", "Vertex AI model endpoint in me-central1 if offered for the chosen model – otherwise a regulator-approved region"),
    ("Confirm model availability in Qatar Central", "Confirm model availability in me-central1"),
    ("Azure Marketplace (or billing provider)", "Google Cloud Marketplace (or billing provider)"),
    ("firewall_allowed_fqdns", "egress_allowed_fqdns (Cloud NGFW)"),
    ("in Key Vault through the console secret store", "in Secret Manager through the console secret store"),
    ("Key Vault secret store", "Secret Manager secret store"),
    ("-t <acr>/intimed/gateway", "-t me-central1-docker.pkg.dev/<project>/intimed-prod/intimed/gateway"),
    ("(web, API, device ports, AKS)", "(web, API, device ports, GKE)"),
    ("the Azure posture checks", "the GCP posture checks (16_gcp)"),
    ("(Dependabot / Defender)", "(Dependabot / Artifact Analysis)"),
]


def manual_gcp():
    out = []
    for m in A.MANUAL:
        if m["id"] in GCP_SECTIONS:
            n = dict(GCP_SECTIONS[m["id"]]); n["id"] = m["id"]
        else:
            n = copy.deepcopy(m)
            for k in ("title", "why", "pre", "steps", "tool", "evidence"):
                v = n[k]
                for a, b in SUBS:
                    v = [x.replace(a, b) for x in v] if isinstance(v, list) else v.replace(a, b)
                n[k] = v
        out.append(n)
    return out


# patch the Azure guide's M-13 as well (GCP is no longer "other cloud")
for _i, _m in enumerate(A.MANUAL):
    if _m["id"] == "M-13":
        A.MANUAL[_i] = M13
