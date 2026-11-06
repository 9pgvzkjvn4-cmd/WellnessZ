# IntiMed on Azure Qatar Central – step-by-step setup and configuration guide

**Release 2.1 · region `qatarcentral` (Doha, 3 availability zones, no paired region) · environments UAT and PROD**

This guide takes an empty Azure subscription to a tested IntiMed deployment for UAT, then to production. It uses only
the material in this repository:

- `deploy/azure/terraform` – infrastructure;
- `deploy/helm/intimed` – the application;
- `deploy/azure/scripts` – the one-command steps;
- `.github/workflows` – the pipelines;
- `security-test-kit` – acceptance checks.

Steps that need people, hardware, vendors, licences or regulator decisions are in the separate
[Manual Backlog Execution Guide](Manual-Backlog-Execution-Guide.md) (M-01…M-16); this guide links to them where they
apply.

> **Data residency.** Everything is created in Qatar Central. The Terraform configuration refuses another region, and an
> Allowed-locations policy is assigned to the resource group. Qatar Central has no paired region, so backups and DR stay
> in-region by default. Treat health data as residency-restricted (Law No. 13 of 2016 / MoPH). Record the approval
> described in M-01 **before** loading real patient data.

---

## 0. What you will build

```
Hospitals / clinics ──IPsec VPN──► snet-devices ── internal LB 10.40.5.250 :2575 MLLP-TLS / :8443 HTTPS (mutual TLS to the pod)
Users (browser) ──HTTPS──► Application Gateway WAF v2 (public IP, Key Vault certificate) ──HTTPS──► internal LB 10.40.3.250 → pod :9443
                                     AKS (Entra RBAC, workload identity, Cilium network policy, Defender, Azure Policy)
                                     └─ StatefulSet intimed-0 (single active, zone-redundant Premium disk)
                                          ├─ PostgreSQL Flexible 16 (private, TLS, zone-redundant HA in PROD)
                                          ├─ Key Vault Premium (master key, DB password, tenant secrets, HSM cosign key)
                                          ├─ Blob Storage ZRS, immutable containers (evidence packs, audit stream)
                                          └─ Log Analytics + Sentinel, Managed Prometheus, alert rules, workbook
Egress (PROD) ─► Azure Firewall (FQDN allow-list)          Container images ─► ACR Premium (private endpoint), cosign-signed
```

| | UAT | PROD |
|---|---|---|
| AKS | 2 × D2s_v5 system + 1–2 × D4s_v5 apps, public API with IP allow-list | 3 × D2s_v5 system + 2–4 × D8s_v5 apps, **private API** |
| PostgreSQL | GP D2ds_v5, no HA, 14-day PITR | GP D4ds_v5, **zone-redundant HA**, 35-day PITR |
| Egress | AKS load balancer | **Azure Firewall** (FQDN allow-list) |
| Sites | VPN optional | **Site-to-site VPN** per hospital |
| Defender plans | optional | on |
| List price (Nov 2026) | ≈ USD 1,627 / month | ≈ USD 5,949 / month |

Costs are detailed in [IntiMed-Azure-Qatar-Cost-Model.xlsx](IntiMed-Azure-Qatar-Cost-Model.xlsx). That workbook also
has the 5-year TCO, reservation scenarios and cost per connected device.

---

## 1. Prerequisites

| Item | Detail |
|---|---|
| Azure | One subscription per environment (recommended) in your Entra tenant; you need Owner (or Contributor + User Access Administrator) for the first deployment |
| Approvals | M-01 completed for PROD: residency confirmation, DPIA (appendix A of the manual guide), Defender plans decision |
| Workstation | Azure CLI ≥ 2.60, Terraform 1.9.x, Helm 3.16, kubectl + kubelogin, jq, openssl, Python 3.10+, Java 21 (for the SDK commands). Run `az aks install-cli` for kubectl and kubelogin |
| Network data | Hospital / clinic device VLAN CIDRs; site VPN public IPs; operator egress IPs (for UAT API access) |
| DNS & certificate | A hostname for the console (e.g. `intimed.uat.example.qa`) and a TLS certificate for it (PFX), and a DNS name for device traffic (e.g. `devices.uat.example.qa`, private DNS / site DNS) |
| GitHub | Repository admin, to create environments `uat-registry`, `uat`, `uat-infra`, `prod`, `prod-infra` with required reviewers on `prod*` |

## 2. Sign in and pick the subscription

```bash
az login --tenant <tenant-id>
```

```bash
az account set --subscription <uat-subscription-id>
```

```bash
export ENV=uat
```

## 3. Preflight (providers, VM sizes per zone, quotas, PostgreSQL HA support)

```bash
deploy/azure/scripts/preflight.sh
```

Read the output carefully:

1. **Providers.** All are registered; the script registers missing ones, and registration can take a few minutes.
2. **VM sizes.** Each size shows `zones=[1,2,3]` and `restriction=none`. If a size is restricted, choose another in `envs/<env>.tfvars`.
3. **vCPU quota.** UAT needs ≥ 8 vCPU of DSv5; PROD needs ≥ 38 (3×2 + 4×8). Request an increase in *Quotas* if needed.
4. **PostgreSQL HA.** If zone-redundant HA is not listed for the SKU in `qatarcentral`, set `postgres_ha_mode = "SameZone"` in `envs/prod.tfvars`. Same-zone HA still protects against a server failure.

## 4. Terraform state and Entra groups (once per subscription)

1. Create the state storage in Qatar Central, using Entra auth only (no access keys):

   ```bash
   az group create -n rg-intimed-tfstate-qc -l qatarcentral
   ```

   ```bash
   az storage account create -n stintimedtfstateqc -g rg-intimed-tfstate-qc -l qatarcentral --sku Standard_ZRS --min-tls-version TLS1_2 --allow-shared-key-access false --allow-blob-public-access false
   ```

   ```bash
   az storage container create --account-name stintimedtfstateqc -n tfstate --auth-mode login
   ```

   ```bash
   az role assignment create --assignee $(az ad signed-in-user show --query id -o tsv) --role "Storage Blob Data Contributor" --scope $(az storage account show -n stintimedtfstateqc -g rg-intimed-tfstate-qc --query id -o tsv)
   ```

2. Copy `deploy/azure/terraform/envs/backend.hcl.example` to `envs/backend-uat.hcl` and adjust the key (`intimed-uat.tfstate`).
3. Create `envs/uat.local.tfvars`. It holds the site-specific values and is git-ignored:

   ```hcl
   subscription_id     = "<uat subscription id>"
   device_source_cidrs = ["10.60.0.0/16"]           # hospital device VLANs / VPN clients
   admin_source_cidrs  = ["203.0.113.10/32"]         # your operators' egress IPs (AKS API, Key Vault, ACR)
   alert_emails        = ["ops@example.qa"]
   ```

4. Create the Entra groups `IntiMed-PlatformSRE`, `IntiMed-DeviceAdmins`, `IntiMed-SOC` and `IntiMed-Auditors` (M-01 step 5).

## 5. Deploy the infrastructure (phase 1)

```bash
cd deploy/azure/terraform
```

```bash
terraform init -backend-config=envs/backend-uat.hcl
```

```bash
terraform test
```

```bash
terraform plan -var-file=envs/uat.tfvars -var-file=envs/uat.local.tfvars -out=tfplan
```

```bash
terraform apply tfplan
```

- `terraform test` runs the shipped plan/apply tests with mocked providers: UAT, PROD, the residency guard and the phase-2 guard. They must all pass before you apply.
- Phase 1 creates everything except Application Gateway, which needs the platform root CA from step 7.
- It takes about 25–35 minutes. AKS and PostgreSQL are the longest.

Then give the operators access to the cluster through Entra RBAC; there are no local accounts:

```bash
AKS_ID=$(az aks show -g rg-intimed-uat-qc -n aks-intimed-uat-qc --query id -o tsv)
```

```bash
az role assignment create --assignee-object-id $(az ad group show -g IntiMed-PlatformSRE --query id -o tsv) --assignee-principal-type Group --role "Azure Kubernetes Service RBAC Cluster Admin" --scope $AKS_ID
```

```bash
az role assignment create --assignee-object-id $(az ad group show -g IntiMed-Auditors --query id -o tsv) --assignee-principal-type Group --role "Azure Kubernetes Service RBAC Reader" --scope $AKS_ID
```

## 6. Runtime secrets, image and first deployment

1. **Secrets.** These are generated locally and piped straight into Key Vault; they are never printed and never stored in state or Git.

   ```bash
   ENV=uat deploy/azure/scripts/bootstrap-secrets.sh
   ```

   This creates `intimed-master-key` (the root key-encryption key, 32 random bytes) and `intimed-db-password`. Terraform has already stored `intimed-db-admin-password`.
   **Immediately** back up the master key with the two-custodian break-glass procedure in [Operations-and-DR.md §4](Operations-and-DR.md). Losing it makes all PHI unrecoverable.

2. **Image.** The normal route is the release pipeline (§10). For the very first manual build:

   ```bash
   cd intimed && ./mvnw -B verify
   ```

   ```bash
   az acr login -n $(terraform -chdir=deploy/azure/terraform output -raw acr_login_server | cut -d. -f1)
   ```

   ```bash
   docker buildx build --platform linux/amd64 -f deploy/docker/Dockerfile -t $(terraform -chdir=deploy/azure/terraform output -raw acr_login_server)/intimed/platform:2.1.0 --push .
   ```

3. **Deploy.**

   ```bash
   ENV=uat IMAGE_TAG=2.1.0 deploy/azure/scripts/deploy.sh
   ```

   The script does three things:
   - It writes `deploy/helm/intimed/values-uat.generated.yaml` from `terraform output`, which contains no secrets.
   - It runs `helm upgrade --install`:
     - The pre-install hook job `intimed-db-bootstrap` creates the application's least-privilege PostgreSQL role. It has no superuser, createrole or createdb rights, and the admin password is mounted only into that job.
     - The StatefulSet then starts. Flyway migrates the schema and the pod becomes Ready, usually 1–2 minutes after the first image pull.
   - It runs `helm test intimed`, which reports `SMOKE PASS`: readiness, plus the console over TLS 1.3.

4. **Check the pods.**

   ```bash
   kubectl get pods -n intimed
   ```

   You should see `intimed-0` with status `1/1 Running`.

## 7. Acceptance in UAT before exposure (security kit, cloud posture)

Until the console is published, UAT is reachable only through `kubectl port-forward`. That makes this the right moment to
run the full security kit with the demo personas.

1. Temporarily deploy with the demo profile. It creates synthetic personas with a published password, so **never use it
   on an exposed environment**:

   ```bash
   helm upgrade intimed deploy/helm/intimed -n intimed --reuse-values --set profiles=postgres\,demo
   ```

2. Run the kit through port-forward, adding the Azure posture check (16) and the container runtime check (05):

   ```bash
   AZ_RESOURCE_GROUP=rg-intimed-uat-qc security-test-kit/run-kit-aks.sh intimed
   ```

   Expected: every local check (02, 03, 04, 07, 08, 09, 10 in-cluster via `sdk phi-check`, 12, 13, 14, 17, 18, 19) plus
   05 and 16 pass. The report is `security-test-kit/results/REPORT.md`.
3. Optional: run the demo scenarios and the Intelligence studio checks from the [demo guide](../demo/Demo-Guide.md)
   through `kubectl port-forward -n intimed pod/intimed-0 9443:9443` and https://localhost:9443.
4. Remove the demo profile again. The demo data stays in the UAT database; for a clean UAT, recreate the database or
   offboard the demo tenants.

   ```bash
   helm upgrade intimed deploy/helm/intimed -n intimed --reuse-values --set profiles=postgres
   ```

5. Create the real tenant and administrator accounts through the platform's tenant API / console, with TOTP for every
   privileged role.

## 8. Publish the console (phase 2): Application Gateway WAF v2

1. Export the platform's installation root CA. This is a public certificate, used by Application Gateway to verify the
   backend:

   ```bash
   ENV=uat deploy/azure/scripts/export-root-ca.sh
   ```

2. Import your public TLS certificate for the console hostname into Key Vault:

   ```bash
   az keyvault certificate import --vault-name <kv> -n console-tls -f console.pfx --password <pfx-password>
   ```

   Note its versionless secret id: `https://<kv>.vault.azure.net/secrets/console-tls`.
3. Add these to `envs/uat.local.tfvars`, then plan and apply again:

   ```hcl
   enable_app_gateway          = true
   console_hostname            = "intimed.uat.example.qa"
   appgw_certificate_secret_id = "https://<kv>.vault.azure.net/secrets/console-tls"
   console_root_ca_pem         = file("envs/uat-root-ca.pem")
   ```

   The WAF runs Microsoft Default Rule Set 2.1 plus Bot Manager in **Prevention** mode, with TLS ≥ 1.2 (policy
   AppGwSslPolicy20220101S) and an HTTP→HTTPS redirect.
4. Create the DNS A record for `console_hostname`, pointing to the `appgw_public_ip` output.
5. Browse to https://intimed.uat.example.qa and sign in. The health probe uses `/api/public/info`.
6. Optional: restrict the WAF to your organisation's public IP ranges with a custom rule, if the console should not be
   internet-reachable.

## 9. Connect hospitals and devices

1. **Site VPN.** Follow M-02:
   - add the sites to `vpn_sites`, and the shared keys to the pipeline secret `VPN_SHARED_KEYS_JSON`;
   - set `enable_vpn = true` and apply;
   - configure each site's VPN device;
   - add the device VLANs to `device_source_cidrs` (Terraform) and `networkPolicy.deviceSourceCidrs` (Helm), then redeploy.
2. **Device DNS.** Point `devices.<env>.example.qa` at the device load-balancer IP 10.40.5.250 in the hospital DNS. Then
   set `DEVICES_DNS_NAME` for `render-values.sh`, so the platform certificate carries that name as a SAN.
3. **Segmentation check.** Run it from each device VLAN; expect 0 failures:

   ```bash
   python3 security-test-kit/01_network_segmentation_check.py --host devices.uat.example.qa --console-host intimed.uat.example.qa --expect-console blocked
   ```

4. **Gateways and device hosts.** Follow M-03: build and sign the gateway image (`deploy/docker/Dockerfile.gateway`),
   register the endpoint in **Security → Endpoints**, and enrol it:

   ```bash
   java -jar intimed-platform.jar sdk enrol --server https://intimed.uat.example.qa --token <one-time token> --ca root-ca.pem --out identity
   ```

5. **Device configurations.** Provision each device model in the console (wizard). Then run the exchange test: XT-01…XT-17
   through the real transports, with sealed evidence. Certify with four-eyes and publish.
6. **Real devices.** Certifying new device families with real units follows M-04.

## 10. CI/CD (signed images, gated promotion)

1. Create an Entra app registration for GitHub OIDC per environment, with federated credentials for
   `repo:<org>/<repo>:environment:uat` (and `uat-registry`, `uat-infra`, `prod`, `prod-infra`). Grant it these roles, at
   the narrowest scope possible:
   - `AcrPush` on the registry;
   - `Key Vault Crypto User` on the vault, for the cosign key;
   - `Azure Kubernetes Service RBAC Writer` on the namespace;
   - `Contributor` on the resource group, for the infra workflow only.
2. Set repository / environment **variables**: `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`, `ACR_NAME`,
   `KEY_VAULT_NAME`, `UAT_CONSOLE_URL`, `PROD_ACR_NAME`, `UAT_DEVICES_HOST`. The only **secret** needed is
   `VPN_SHARED_KEYS_JSON`, for the infra workflow.
3. Set **required reviewers** on the environments `prod` and `prod-infra`: a Platform SRE and a Device Administrator (four-eyes).
4. Pipelines:
   - `intimed-azure-release.yml` runs on a tag or manually:
     - build and tests;
     - CodeQL and gitleaks;
     - Trivy, which blocks on fixable HIGH/CRITICAL findings;
     - push to ACR and a cosign signature with the **HSM key in Key Vault**;
     - UAT deployment, `helm test`, the cloud posture checks and ZAP baseline DAST;
     - after approval: signature verification and the PROD deployment, from a self-hosted runner in the PROD VNet (private cluster and registry).
   - `intimed-azure-infra.yml`: on pull requests it runs fmt, validate and plan for UAT; when started manually it runs plan and apply behind the environment approval.
   - `intimed-perf-soak.yml`: a soak at 2× peak with `sdk soak` (M-11).

## 11. Production

Repeat steps 2–9 with `ENV=prod`, the PROD subscription, `envs/prod.tfvars` and `envs/prod.local.tfvars`. The
differences, all already set in `prod.tfvars`:

- **Private AKS API.** `deploy.sh` and the scripts use `az aks command invoke` automatically, so no VPN is needed for operators.
- **Azure Firewall.** All cluster egress goes through it. Extend `firewall_allowed_fqdns` only through a reviewed change, for example to add a vendor cloud in M-05.
- **Zone-redundant PostgreSQL HA**, 35-day PITR and 90-day logs.
- **Defender plans on.**
- **Private ACR.** Images are imported from the UAT registry *after* signature verification (release pipeline).
- **Self-hosted runner.** A GitHub runner VM with labels `self-hosted, intimed-prod-vnet` in the PROD VNet (snet-aks or a dedicated subnet).

After go-live:

- lock the immutability policies on the archive containers (M-11 step 3);
- run the DR drills (§12) and the soak;
- complete the regulatory items in M-08 and M-09.

## 12. Operate

| Task | How |
|---|---|
| Health, SLOs, alerts | Azure Monitor workbook "IntiMed operations – <env>", Prometheus alert rules (RB-01…RB-06), action group e-mails – [SLO-and-Runbooks.md](SLO-and-Runbooks.md) |
| Security monitoring | Microsoft Sentinel: audit-chain stream (`ContainerLogV2`, logger `intimed.siem`), analytics rule "repeated login / device-certificate failures", Defender alerts |
| Evidence & audit archive | Every 5 minutes the platform uploads new sealed evidence packs and audit segments to the immutable `evidence` / `audit` containers |
| DR drills | `ENV=uat deploy/azure/scripts/dr-drill.sh D1 D2 D3 D4` (zone loss, DB failover, PITR restore, archive check) – [Operations-and-DR.md](Operations-and-DR.md) |
| Upgrades | AKS: patch channel, node images weekly (maintenance window Friday 01:00 AST). IntiMed: release pipeline; Helm rolls the single pod (≈ 1–2 min console downtime – schedule it) |
| Costs | Budget alerts at 80 % forecast / 100 % actual; cost model workbook; reservations after 3 months of stable usage |
| Security kit | Every release: `run-kit-aks.sh` in UAT; quarterly in PROD (read-only checks 05, 16 and 01) |

## 13. Troubleshooting

| Symptom | Check |
|---|---|
| `intimed-0` Pending | `kubectl describe pod` – zone-redundant disk class `intimed-premium-zrs` needs the Azure Disk CSI driver (default) and quota for Premium ZRS |
| db-bootstrap job fails | Key Vault secrets `intimed-db-admin-password` / `intimed-db-password` exist (step 6.1); NetworkPolicy allows 5432 to the PostgreSQL subnet; the PostgreSQL private DNS zone is linked |
| Pod CrashLoop "INTIMED_MASTER_KEY must be 32 bytes" | The secret must be base64 of exactly 32 bytes – recreate with `bootstrap-secrets.sh` only if no data exists yet |
| Application Gateway backend unhealthy | Root CA in `console_root_ca_pem` is the current one (`export-root-ca.sh`), backend host name `intimed-console.intimed.svc.cluster.local` is in the platform certificate SANs (`tls.sans`), NSG allows GatewayManager |
| Devices refused | Device CIDR in both NSG and NetworkPolicy; endpoint ACTIVE and certificate valid (Security → Endpoints); run script 01 from the site |
| No metrics | `ama-metrics` pods running in kube-system; pod annotations `prometheus.io/scrape=true`; data collection rule association exists |
