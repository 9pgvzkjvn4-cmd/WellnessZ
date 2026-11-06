# IntiMed_GCP – step-by-step setup and configuration guide (Google Cloud, Doha)

**Release 2.2 · region `me-central1` (Doha, zones a/b/c) · environments UAT and PROD · pack name IntiMed_GCP**

This guide takes an empty Google Cloud organisation to a tested IntiMed deployment in UAT, and then to production. It
uses only the material in this repository:

- `deploy/gcp/terraform` – infrastructure;
- `deploy/helm/intimed` with `cloud: gcp` – the application;
- `deploy/gcp/scripts` – the one-command steps;
- `.github/workflows/intimed-gcp-*.yml` – the pipelines;
- `security-test-kit` – acceptance checks, including `16_gcp_posture_check.py`.

Steps that need people, hardware, vendors, licences or regulator decisions are in the separate
[IntiMed_GCP Manual Backlog Execution Guide](IntiMed_GCP_Manual_Backlog_Execution_Guide.md) (M-01…M-16). This guide
links to them where they apply. Costs are in [IntiMed_GCP_Cost_Model.xlsx](IntiMed_GCP_Cost_Model.xlsx).

> **Data residency.** Everything is created in `me-central1` (Doha):
>
> - Terraform refuses any other region (`var.region` validation).
> - The organisation policy `gcp.resourceLocations = in:me-central1-locations` is set on the project.
> - Secret Manager replicas, Cloud SQL backups, log buckets, Pub/Sub message storage and Artifact Registry are all pinned to `me-central1`.
>
> The only other GCC Google region (me-central2, Dammam) is outside Qatar, so DR stays inside Doha across three zones.
> Treat health data as residency-restricted (Law No. 13 of 2016 / MoPH). Record the approval described in M-01
> **before** you load real patient data.

---

## 0. What you will build

```
Hospitals / clinics ──HA VPN (BGP)──► VPC ── internal passthrough NLB 10.50.15.250 :2575 MLLP-TLS / :8443 HTTPS (mutual TLS to the pod)
Users (browser) ──HTTPS──► regional external Application Load Balancer (Gateway API, Doha) + Cloud Armor WAF ──HTTPS──► pod :9443
                                 GKE Standard regional, private nodes, IAM-authenticated DNS endpoint, Workload Identity,
                                 Dataplane V2 network policy, Shielded nodes, Binary Authorization, CMEK secrets encryption
                                 └─ StatefulSet intimed-0 (single active, regional PD-SSD replicated across 2 zones)
                                      ├─ Cloud SQL for PostgreSQL 16 (private IP only, TLS verify-ca, CMEK, PITR, REGIONAL HA in PROD)
                                      ├─ Secret Manager (master key, DB passwords, tenant secrets – Doha-only replicas, CMEK)
                                      ├─ Cloud Storage evidence / audit buckets (retention policy → lock, create-only uploads, CMEK)
                                      └─ Cloud Logging (Doha log bucket), SIEM topic (Pub/Sub, Doha), Managed Prometheus, alert policies
Egress ─► Cloud NAT (+ Cloud NGFW FQDN allow-list in PROD)      Images ─► Artifact Registry (Doha, CMEK, immutable tags), cosign + Binary Authorization
Keys ─► Cloud KMS key ring in me-central1, HSM protection: CMEK key (90-day rotation) + EC P-256 signing key (cosign / attestor)
```

| | UAT | PROD |
|---|---|---|
| GKE | 3 × e2-standard-2 system (1 per zone) + 0–2 × n2-standard-4 apps | 3 × n2-standard-2 system + 2–4 × n2-standard-8 apps |
| Cloud SQL | 2 vCPU / 7.5 GiB, ZONAL, 14 backups | 4 vCPU / 15 GiB, **REGIONAL HA**, 35 backups |
| Binary Authorization | dry-run (audit log only) | **enforced** |
| Egress | Cloud NAT | Cloud NAT + **Cloud NGFW FQDN allow-list** |
| Sites | HA VPN optional | **HA VPN** per hospital (2 tunnels, BGP) |
| Archive retention | 30 days (unlocked) | 7 years (**locked** after acceptance, M-11) |
| List price (Nov 2026) | ≈ USD 723 / month | ≈ USD 2,211 / month |

The prices are on-demand list prices from the committed me-central1 snapshot
(`deploy/gcp/pricing/gcp-me-central1-2026-11-06.json`). Lines marked *estimate – verify* in the workbook must be
confirmed in the Google Cloud Pricing Calculator before a budget is committed.

---

## 1. Prerequisites

| Item | Detail |
|---|---|
| Google Cloud | An organisation (Cloud Identity or Workspace), a billing account, and one project per environment (for example `intimed-uat-qa` and `intimed-prod-qa`). For the first deployment you need Owner on the project and Organization Policy Administrator (for the location policy) |
| Approvals | M-01 completed for PROD: residency confirmation, DPIA (appendix A of the manual guide), Cloud Data Processing Addendum accepted |
| Workstation | Google Cloud CLI (`gcloud`, with the `beta` and `gke-gcloud-auth-plugin` components), Terraform 1.9.x, Helm 3.16, kubectl, jq, openssl, Python 3.10+, Java 21 (for the SDK commands), Docker with buildx |
| Network data | Hospital / clinic device VLAN CIDRs; for each site its VPN public IP, BGP ASN and two /30 link ranges (169.254.x.x) |
| DNS & certificate | A hostname for the console (e.g. `intimed.uat.example.qa`) with a public TLS certificate and key in PEM format; a DNS name for device traffic (e.g. `devices.uat.example.qa`, in the hospital or private DNS) |
| GitHub | Repository admin, to create the environments `gcp-uat`, `gcp-uat-infra`, `gcp-prod` and `gcp-prod-infra`, with required reviewers on `gcp-prod*` |

Install the CLI components once:

```bash
gcloud components install beta gke-gcloud-auth-plugin
```

## 2. Sign in, projects and Terraform state

1. Sign in and select the UAT project:

   ```bash
   gcloud auth login
   ```

   ```bash
   gcloud auth application-default login
   ```

   ```bash
   gcloud config set project intimed-uat-qa
   ```

   ```bash
   export ENV=uat PROJECT=intimed-uat-qa
   ```

2. Link the billing account if the project is new:

   ```bash
   gcloud billing projects link $PROJECT --billing-account <billing-account-id>
   ```

3. Create the Terraform state bucket in Doha, with uniform access, versioning and public access prevention:

   ```bash
   gcloud storage buckets create gs://$PROJECT-intimed-tfstate --location=me-central1 --uniform-bucket-level-access --public-access-prevention
   ```

   ```bash
   gcloud storage buckets update gs://$PROJECT-intimed-tfstate --versioning
   ```

4. Copy `deploy/gcp/terraform/envs/backend.hcl.example` to `envs/backend-uat.hcl`. Set `bucket = "<project>-intimed-tfstate"` and `prefix = "intimed/uat"`. The file is git-ignored.
5. Create `deploy/gcp/terraform/envs/uat.local.tfvars`. It holds the site-specific values and is git-ignored:

   ```hcl
   project_id          = "intimed-uat-qa"
   billing_account     = "XXXXXX-XXXXXX-XXXXXX"          # for the budget alert
   device_source_cidrs = ["10.60.0.0/16"]                 # hospital device VLANs / VPN clients
   alert_emails        = ["ops@example.qa"]
   github_repository   = "<org>/<repo>"                   # creates the CI/CD identity (§10); leave out to skip
   # console_allowed_cidrs = ["203.0.113.0/24"]           # optional: restrict the console to your public ranges
   ```

6. Create the Google groups `intimed-platform-sre`, `intimed-device-admins`, `intimed-soc` and `intimed-auditors` (M-01 step 5).

## 3. Preflight (APIs, service agents, machine types, quotas, HSM)

```bash
deploy/gcp/scripts/preflight.sh
```

The script enables every API Terraform needs and creates the Google service agents that the CMEK grants refer to
(Cloud SQL, Artifact Registry, Secret Manager, Pub/Sub, GKE, Cloud Storage). Then read its output:

1. **Machine types.** Each configured type shows `ok` in `me-central1-a`, `-b` and `-c`. If one is missing, choose another in `envs/<env>.tfvars`.
2. **Quotas.** UAT needs about 10 vCPU (`CPUS` / `E2_CPUS` / `N2_CPUS`); PROD needs about 38 (3 × 2 + 4 × 8 at the autoscaling maximum) and about 300 GB of `SSD_TOTAL_GB`. Request an increase under *IAM & Admin → Quotas* if needed.
3. **Cloud HSM.** It should say `HSM available`. If not, set `kms_protection_level = "SOFTWARE"` and record the deviation in the risk file.
4. **`_Default` log sink.** The script disables it so that logs are kept only in the Doha log bucket. Terraform creates that bucket and the sink to it.

## 4. Deploy the infrastructure

```bash
cd deploy/gcp/terraform
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

- `terraform test` runs the shipped plan tests with mocked providers: UAT, PROD and the residency guard (a region other than `me-central1` must fail). All three must pass before you apply.
- The apply takes about 25–35 minutes. The GKE cluster and Cloud SQL take the longest.
- The KMS key ring and keys have `prevent_destroy`. KMS key rings can never be deleted in Google Cloud, so plan their names carefully. A random suffix is added.
- If the organisation policy step fails with a permission error, ask an organisation admin to apply `gcp.resourceLocations` to the project, or set `enforce_location_org_policy = false` for UAT only.

Give the operator groups access. GKE uses Google IAM for the DNS endpoint and Kubernetes RBAC; there are no client certificates or basic auth:

```bash
gcloud projects add-iam-policy-binding $PROJECT --member=group:intimed-platform-sre@example.qa --role=roles/container.admin
```

```bash
gcloud projects add-iam-policy-binding $PROJECT --member=group:intimed-auditors@example.qa --role=roles/container.viewer
```

Check that you can reach the private cluster through its DNS endpoint. No VPN or bastion is needed:

```bash
gcloud container clusters get-credentials $(terraform output -raw gke_name) --region me-central1 --dns-endpoint
```

```bash
kubectl get nodes -L topology.kubernetes.io/zone
```

## 5. Runtime secrets

```bash
cd ../../..
```

```bash
ENV=uat deploy/gcp/scripts/bootstrap-secrets.sh
```

This adds the first version of two secrets:

- `intimed-master-key`: the root key-encryption key, 32 random bytes;
- `intimed-db-password`: the application's database password.

Both values are generated locally and piped to Secret Manager. They are never printed and never stored in Terraform
state or Git. Terraform has already stored `intimed-db-admin-password`, the Cloud SQL admin password.

**Immediately** back up the master key with the two-custodian break-glass procedure in
[IntiMed_GCP_Operations_and_DR.md §4](IntiMed_GCP_Operations_and_DR.md). Losing it makes all PHI unrecoverable.

How the secrets reach the platform: the GKE Secret Manager add-on mounts them as files into `/mnt/secrets`, using the
pod's Workload Identity (`sa-intimed-app-<env>`, which has `secretAccessor` on exactly these secrets). The platform
reads them as files (`INTIMED_SECRETS_DIR`). No Kubernetes Secret objects are created.

## 6. Image and first deployment (console not yet published)

1. **Image.** The normal route is the release pipeline (§10). For the very first manual build:

   ```bash
   cd intimed && ./mvnw -B verify
   ```

   ```bash
   gcloud auth configure-docker me-central1-docker.pkg.dev
   ```

   ```bash
   docker buildx build --platform linux/amd64 -f deploy/docker/Dockerfile -t $(terraform -chdir=deploy/gcp/terraform output -raw artifact_registry)/intimed/platform:2.2.0 --push .
   ```

   Binary Authorization runs in **dry-run** in UAT, so an unsigned manual image is admitted and the violation is
   audit-logged. In PROD only attested images are admitted, so PROD images always come from the pipeline.

2. **Deploy.**

   ```bash
   ENV=uat IMAGE_TAG=2.2.0 deploy/gcp/scripts/deploy.sh
   ```

   The script does four things:
   - It writes `deploy/helm/intimed/values-gcp-uat.generated.yaml` from `terraform output`. The file contains no secrets, only the Cloud SQL server CA (a public certificate) used for `sslmode=verify-ca`.
   - It gets cluster credentials through the DNS endpoint.
   - It runs `helm upgrade --install` with `values-gcp-uat.yaml`:
     - The pre-install hook job `intimed-db-bootstrap` creates the least-privilege PostgreSQL role. Only that job mounts the admin password.
     - The StatefulSet starts on a regional PD-SSD volume. Flyway migrates the schema and the pod becomes Ready, usually 1–2 minutes after the first image pull.
   - It runs `helm test intimed`, which should report `SMOKE PASS`.

3. **Check the pods.**

   ```bash
   kubectl get pods -n intimed -o wide
   ```

   You should see `intimed-0` with status `1/1 Running` on an `apps` node.

## 7. Acceptance in UAT before exposure (security kit, GCP posture)

The console is not published yet (`gcp.console.publish: false`), so UAT is reachable only through
`kubectl port-forward`. That makes this the right moment to run the full security kit with the demo personas.

1. Temporarily deploy with the demo profile. It creates synthetic personas with a published password, so **never use it
   on an exposed environment**:

   ```bash
   helm upgrade intimed deploy/helm/intimed -n intimed --reuse-values --set profiles=postgres\,demo
   ```

2. Run the kit through port-forward. It adds the GCP posture check (16) and the container runtime check (05):

   ```bash
   GCP_PROJECT=$PROJECT INTIMED_ENV=uat security-test-kit/run-kit-gke.sh intimed
   ```

   Expected result:
   - every local check passes (02, 03, 04, 07, 08, 09, 10 in-cluster via `sdk phi-check`, 12, 13, 14, 17, 18, 19);
   - 05 passes;
   - every 16_gcp check passes: residency, private Cloud SQL with TLS, CMEK, PITR in Doha, private GKE with Workload Identity / Dataplane V2 / Binary Authorization, bucket protections, HSM keys, immutable Artifact Registry, Cloud Armor rules, `_Default` sink disabled, no 0.0.0.0/0 ingress.

   The report is `security-test-kit/results/REPORT.md`. In PROD, run it with `INTIMED_ENV=prod`, which also checks REGIONAL HA, enforced Binary Authorization and locked retention.
3. Optional: run the demo scenarios and the Intelligence studio checks from the [demo guide](../demo/Demo-Guide.md):
   run `kubectl port-forward -n intimed pod/intimed-0 9443:9443`, then open https://localhost:9443.
4. Remove the demo profile again:

   ```bash
   helm upgrade intimed deploy/helm/intimed -n intimed --reuse-values --set profiles=postgres
   ```

   The demo data stays in the UAT database. For a clean UAT, recreate the database or offboard the demo tenants.
5. Create the real tenant and administrator accounts through the tenant API / console, with TOTP for every privileged role.

## 8. Publish the console: regional Application Load Balancer + Cloud Armor

Terraform has already created three things (`enable_console_gateway = true` by default):

- the static regional IP (`console_ip_name` / `console_ip` outputs);
- the Cloud Armor policy (`armor_policy`), with the preconfigured WAF rules (OWASP CRS v3.3: SQLi, XSS, LFI, RFI, RCE, method enforcement, scanner detection, protocol attack, session fixation, Java, CVE canary), a rate-based ban and the optional allow-list;
- the TLS policy `intimed-tls12-modern` (TLS ≥ 1.2, MODERN profile).

1. Create the DNS A record for the console hostname, pointing to the `console_ip` output:

   ```bash
   terraform -chdir=deploy/gcp/terraform output -raw console_ip
   ```

2. Publish. The certificate and key PEM files are loaded once into the Kubernetes TLS secret `intimed-console-tls`:

   ```bash
   ENV=uat CONSOLE_PUBLISH=true CONSOLE_HOSTNAME=intimed.uat.example.qa CONSOLE_TLS_CERT=console.crt CONSOLE_TLS_KEY=console.key IMAGE_TAG=2.2.0 deploy/gcp/scripts/deploy.sh
   ```

   The chart now also renders:
   - the Gateway (class `gke-l7-regional-external-managed`), which terminates TLS in Doha;
   - an HTTP→HTTPS redirect;
   - a HealthCheckPolicy (HTTPS `/api/public/info` on 9443);
   - a GCPBackendPolicy that attaches the Cloud Armor policy and enables request logging;
   - a GCPGatewayPolicy for the TLS policy.

   The load balancer re-encrypts to the pod (`appProtocol: HTTPS`).
3. Wait 3–5 minutes for the load balancer to program, then check:

   ```bash
   kubectl -n intimed get gateway intimed-console
   ```

   It should show `PROGRAMMED True` with the static IP. Then browse to https://intimed.uat.example.qa and sign in.
4. Optional: restrict the console to your organisation's public ranges by setting `console_allowed_cidrs` in `uat.local.tfvars` and applying again. The default rule then denies everyone else.
5. Renew the certificate by running step 2 again with the new files. The secret is replaced in place.

## 9. Connect hospitals and devices

1. **Site VPN.** Follow M-02:
   - add the sites to `vpn_sites`, and the shared secrets to the pipeline secret `VPN_SHARED_SECRETS_JSON` (`{"<site>": "<shared secret>"}`, used for both tunnels of the site) or the `TF_VAR_vpn_shared_secrets` environment variable (never a file);
   - set `enable_vpn = true` and apply;
   - configure each site's VPN device: 2 tunnels, IKEv2, BGP;
   - add the device VLANs to `device_source_cidrs`, which feeds both the VPC firewall rule and the Helm NetworkPolicy through `render-values.sh`, then redeploy.
2. **Device DNS.** Point `devices.<env>.example.qa` at the internal device load-balancer IP 10.50.15.250 in the hospital DNS. Then set `DEVICES_DNS_NAME` for `deploy.sh`, so the platform certificate carries that name as a SAN.
3. **Segmentation check.** Run it from each device VLAN; expect 0 failures:

   ```bash
   python3 security-test-kit/01_network_segmentation_check.py --host devices.uat.example.qa --console-host intimed.uat.example.qa --expect-console blocked
   ```

4. **Gateways and device hosts.** Follow M-03:
   - build and sign the gateway image (`deploy/docker/Dockerfile.gateway`) and push it to Artifact Registry;
   - register the endpoint in **Security → Endpoints**;
   - enrol it:

   ```bash
   java -jar intimed-platform.jar sdk enrol --server https://intimed.uat.example.qa --token <one-time token> --ca root-ca.pem --out identity
   ```

5. **Device configurations.** Provision each device model in the console (wizard), then run the exchange test: XT-01…XT-17 through the real transports, with sealed evidence. Certify with four-eyes and publish.
6. **Real devices.** Certifying new device families with real units follows M-04.

## 10. CI/CD (signed images, attestations, gated promotion)

1. **Identity.** Setting `github_repository` (§2.5) makes Terraform create the CI/CD identity per project:
   - a Workload Identity pool and provider for GitHub, which accepts only that repository;
   - the deployer service account `sa-intimed-deploy-<env>`, with these roles:
     - `container.developer`;
     - `artifactregistry.writer`;
     - the Artifact Analysis roles needed for attestations;
     - `cloudkms.signerVerifier` on the cosign key only.

   No service-account keys are created. Grant the deployer read access to the state bucket only (never project-wide, so it cannot read the archives):

   ```bash
   gcloud storage buckets add-iam-policy-binding gs://$PROJECT-intimed-tfstate --member=serviceAccount:$(terraform -chdir=deploy/gcp/terraform output -raw deploy_sa) --role=roles/storage.objectViewer
   ```

   For the infra workflow, create a separate Terraform service account with Owner on the project. Allow the same pool to impersonate it, and store its email as `GCP_TERRAFORM_SA`.
2. **GitHub environment variables.** Set these on `gcp-uat` / `gcp-prod`, and on the `-infra` environments:

   | Variable | Value |
   |---|---|
   | `GCP_PROJECT` | project id |
   | `GCP_WIF_PROVIDER` | `terraform output -raw wif_provider` |
   | `GCP_DEPLOY_SA` | `terraform output -raw deploy_sa` |
   | `COSIGN_KEY` | `terraform output -raw cosign_key` |
   | `CONSOLE_HOSTNAME`, `CONSOLE_PUBLISH` | console name; `true` once §8 is done |
   | `UAT_CONSOLE_URL` | for the ZAP baseline |
   | `GCP_UAT_PROJECT`, `UAT_COSIGN_KEY` | `gcp-prod` only, to verify the UAT signature before promotion |
   | `GCP_BILLING_ACCOUNT`, `GCP_TERRAFORM_SA` | infra environments |

   **Secrets:**
   - `VPN_SHARED_SECRETS_JSON` (infra);
   - optionally `CONSOLE_TLS_CERT_PEM` / `CONSOLE_TLS_KEY_PEM`, for certificate renewal through the pipeline.
3. **Required reviewers.** Set them on `gcp-prod` and `gcp-prod-infra`: a Platform SRE and a Device Administrator (four-eyes).
4. **Pipelines.**
   - `intimed-gcp-release.yml` runs on a tag `intimed-gcp-v*` or manually:
     - build and tests;
     - CodeQL and gitleaks;
     - Trivy, which blocks on fixable HIGH/CRITICAL findings;
     - push to Artifact Registry (immutable tags);
     - a cosign signature with the **Cloud KMS HSM key**, plus a **Binary Authorization attestation**;
     - UAT deployment, `helm test`, `16_gcp` + `05` and the ZAP baseline;
     - after approval: verification of the UAT signature, copy of the image to the PROD registry by digest, PROD signature and attestation, then the PROD deployment.
     - GitHub-hosted runners are enough: the GKE DNS endpoint and Artifact Registry are IAM-authenticated.
   - `intimed-gcp-infra.yml`:
     - on pull requests it runs fmt, validate, `terraform test` and a UAT plan;
     - when started manually it runs plan and apply behind the environment approval.
   - `intimed-perf-soak.yml`: a soak at 2× peak with `sdk soak` (M-11).

## 11. Production

Repeat steps 2–9 with `ENV=prod`, the PROD project, `envs/prod.tfvars` and `envs/prod.local.tfvars`. The differences,
all already set in `prod.tfvars`:

- **Cloud SQL REGIONAL HA.** Primary and standby are in different Doha zones, with automatic failover, 35 backups and PITR.
- **Binary Authorization enforced.** Only images attested by the release pipeline's HSM key run. GKE system images from `gke.gcr.io` are allow-listed.
- **Cloud NGFW FQDN egress policy.** From the nodes, only the `egress_allowed_fqdns` (allow-listed registers, vendor clouds and webhooks) and the private Google API ranges (restricted.googleapis.com, internal ranges) are reachable; everything else is denied. Extend the list only through a reviewed change, for example to add a vendor cloud in M-05.
- **HA VPN** to each hospital (99.99 % SLA with two tunnels).
- **7-year archive retention.** Lock it after acceptance with `ENV=prod deploy/gcp/scripts/lock-retention.sh` (irreversible – M-11), then set `lock_archive_retention = true`.
- **Larger nodes and volumes:** `values-gcp-prod.yaml` (128 GiB regional PD-SSD, 6 GiB container memory).

After go-live:

- run the DR drills (§12) and the soak;
- confirm every *estimate – verify* line of the cost model and buy committed-use discounts (M-11 step 8);
- complete the regulatory items in M-08 and M-09.

## 12. Operate

| Task | How |
|---|---|
| Health, SLOs, alerts | Cloud Monitoring dashboard "IntiMed operations – <env>"; PromQL alert policies IntiMedDown, Restarting, Http5xx, LatencyP95, HeapPressure, DbPoolExhausted → e-mail channels – [IntiMed_GCP_SLO_and_Runbooks.md](IntiMed_GCP_SLO_and_Runbooks.md) |
| Security monitoring | Audit-chain stream (logger `intimed.siem`) → log sink → Pub/Sub topic in Doha → your SIEM; log-based metric + alert "security events"; Security Command Center findings |
| Evidence & audit archive | Every 5 minutes the platform uploads new sealed evidence packs and audit segments to the evidence / audit buckets, create-only (`ifGenerationMatch=0`), under a retention policy |
| DR drills | `ENV=uat deploy/gcp/scripts/dr-drill.sh D1 D2 D3 D4` (zone loss, Cloud SQL failover, PITR clone, archive check) – [IntiMed_GCP_Operations_and_DR.md](IntiMed_GCP_Operations_and_DR.md) |
| Upgrades | GKE REGULAR channel with a maintenance window (Friday 01:00–05:00 AST); Cloud SQL maintenance Saturday 01:00 AST. IntiMed: release pipeline; Helm rolls the single pod (≈ 1–2 min console downtime – schedule it) |
| Costs | Billing budget with alerts at 80 % and 100 %; [cost model](IntiMed_GCP_Cost_Model.xlsx); committed-use discounts after 3 months of stable usage |
| Security kit | Every release: `run-kit-gke.sh` in UAT; quarterly in PROD (read-only checks 05, 16_gcp and 01) |

## 13. Troubleshooting

| Symptom | Check |
|---|---|
| `terraform apply` fails on a CMEK grant | The service agent does not exist yet – run `preflight.sh` again (it calls `gcloud beta services identity create`) and re-apply |
| `intimed-0` Pending | `kubectl describe pod`. The regional PD class `intimed-regional-pd` needs nodes in both `gcp.diskZones` (Terraform `app_node_locations`) and `SSD_TOTAL_GB` quota |
| Pod stuck in ContainerCreating with a secrets mount error | Secret Manager add-on enabled on the cluster; the secrets have an ENABLED version (`bootstrap-secrets.sh`); the KSA annotation `iam.gke.io/gcp-service-account` matches `app_gsa_email`; the Workload Identity binding exists |
| db-bootstrap job fails | `intimed-db-admin-password` / `intimed-db-password` exist; NetworkPolicy allows 5432 to the PSA range; the server CA in the generated values is current (`render-values.sh`) |
| Pod CrashLoop "INTIMED_MASTER_KEY must be 32 bytes" | The secret must be base64 of exactly 32 bytes. Recreate it with `bootstrap-secrets.sh` only if no data exists yet |
| Pod rejected by Binary Authorization (PROD) | The image was not deployed by digest from the pipeline, or the attestation is missing: `gcloud container binauthz attestations list --attestor=intimed-release-prod --attestor-project=$PROJECT` |
| Gateway not PROGRAMMED / 502 | The proxy-only subnet exists; the firewall rule allows the proxy-only range and 35.191.0.0/16, 130.211.0.0/22 to 9443; the HealthCheckPolicy path `/api/public/info` answers 200; the TLS secret exists in namespace `intimed` |
| 403 from the console | Cloud Armor: check the request logs (`jsonPayload.enforcedSecurityPolicy`); tune or exclude a preconfigured rule signature, or add your range to `console_allowed_cidrs` |
| Devices refused | Device CIDR in both the VPC firewall (`device_source_cidrs`) and the NetworkPolicy; endpoint ACTIVE and certificate valid (Security → Endpoints); run script 01 from the site |
| No metrics | The PodMonitoring `intimed` exists; `gmp-system` pods are running; the NetworkPolicy allows `gmp-system` to 9444 |
