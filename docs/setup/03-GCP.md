# WellnessZ OHCM – Setup Guide 3: Google Cloud

| | |
|---|---|
| **Document** | 07 – Setup guide: Google Cloud (GKE, Cloud SQL, Secret Manager, Cloud Armor) |
| **Audience** | Cloud platform team, DevSecOps, identity administrators |
| **Result** | A dev, test or prod environment on GCP, deployed from CI with signed and attested images |
| **Time** | 1 day for the first environment |
| **Related** | 04 – Deployment Architecture · 06 – Azure guide (Entra ID steps) · 09 – CI/CD Guide · `deploy/terraform/gcp` · `values-gcp.yaml` |

![GCP topology](../images/10-deploy-gcp.png)

*Figure 1 – Google Cloud topology (source: `docs/images/10-deploy-gcp.svg`).*

> **Validation status.** `terraform validate` passes with google/google-beta 8.9 (also tested against 7.46), and
> `terraform fmt` is clean. The Helm chart renders with `values-gcp.yaml` and passes schema validation, including
> the GKE CRDs (BackendConfig, FrontendConfig, ManagedCertificate) and the Secret Manager add-on
> SecretProviderClass. **No `terraform apply` was run in a real project.** Build **dev** first.
>
> **Increment 2** (E03 OH record, E05 fitness for duty) needs no new GCP resource. It adds the `DPO` role, partner
> `org_scope` claims and gateway clients in Entra ID (section 5), three Flyway-created schemas (section 8), new core
> settings with optional font and OCR (section 12) and increment-2 smoke checks (section 14).
>
> **Increment 3** (E06 absence & sickness, E07 medical surveillance, E14 scheduling & clinic operations) needs no new
> GCP resource. It adds four app roles and groups and a candidate identity decision (section 5), gateway clients for
> absence feeds, industrial hygiene, dosimetry, recruiting and the IoT fridge gateway (section 5), three Flyway-created
> schemas (section 8), E18 business configuration after deployment (section 12) and increment-3 smoke checks
> (section 14).
>
> **Increment 4** (E04 treatment coordination & partner referrals, E08 claims coordination) adds one Kubernetes
> component – the **partner portal** (`partnerPortal`, host `partners.<domain>`) – behind the existing global load
> balancer, Cloud Armor policy and a Google-managed certificate (section 10), but no new GCP resource. It adds five
> app roles and groups and **MFA for partner and adjuster guests** at the IdP (section 5), two Flyway-created schemas
> (section 8), `WZ_PARTNER_PORTAL_URL` and business configuration (section 12) and increment-4 smoke checks
> (section 14).

## 1. Overview of the steps

| # | Stage | Output |
|---|---|---|
| 1 | Workstation tools | gcloud and the other CLIs |
| 2 | Project preparation (billing, organisation policies, quotas) | Project ready |
| 3 | Terraform state bucket | `gs://wellnessz-tfstate-<env>` |
| 4 | Identity provider (Entra ID or Keycloak) | Issuer, audience, client IDs |
| 5 | Terraform plan/apply | VPC, GKE, Cloud SQL, KMS, Secret Manager, Artifact Registry, Cloud Armor, Binary Authorization, IAM |
| 6 | Secrets bootstrap | Key material in Secret Manager |
| 7 | Database bootstrap | Roles and schemas, `cloudsqlsuperuser` removed from app roles |
| 8 | DNS and certificates | Google-managed certificates active |
| 9 | GitHub configuration (Workload Identity Federation) | CI can push, attest and deploy |
| 10 | Images: build, sign, attest, mirror Flyway | Images in Artifact Registry |
| 11 | Helm install | Running workloads |
| 12 | Smoke tests, EHS mailbox, MDM | Environment accepted |

## 2. Workstation prerequisites

| Tool | Version | Install (macOS) |
|---|---|---|
| Google Cloud CLI + GKE auth plugin | current | `brew install --cask google-cloud-sdk` then `gcloud components install gke-gcloud-auth-plugin` |
| Terraform | ≥ 1.11 (tested 1.13.4) | `brew install terraform` |
| kubectl / Helm | 1.33 / 3.19 | `brew install kubernetes-cli helm` |
| cosign, crane | 3.x / current | `brew install cosign crane` |
| psql | 16 | `brew install libpq` |

```bash
gcloud auth login && gcloud auth application-default login
```

```bash
gcloud config set project <project-id> && gcloud config list
```

## 3. Project preparation

Use one project per environment, inside a folder with your organisation policies. Recommended organisation
policies for this workload:

| Policy | Value |
|---|---|
| `constraints/sql.restrictPublicIp` | Enforced |
| `constraints/compute.vmExternalIpAccess` | Deny all |
| `constraints/iam.disableServiceAccountKeyCreation` | Enforced (this setup never needs keys) |
| `constraints/gcp.resourceLocations` | Your approved regions (for example `in:eu-locations`) |
| `constraints/compute.requireOsLogin` | Enforced |

```bash
gcloud billing projects describe <project-id> --format="value(billingEnabled)"
```

Expected: `True`.

Terraform enables the required APIs itself. Only Service Usage and Cloud Resource Manager must be enabled
beforehand:

```bash
gcloud services enable serviceusage.googleapis.com cloudresourcemanager.googleapis.com
```

The identity running Terraform needs Owner on the project for the first build. For subsequent runs, a custom role
or the combination Editor + Security Admin + Service Networking Admin + Cloud KMS Admin is sufficient.

## 4. Terraform state bucket

```bash
gcloud storage buckets create gs://wellnessz-tfstate-dev --location=europe-west4 --uniform-bucket-level-access --public-access-prevention
```

```bash
gcloud storage buckets update gs://wellnessz-tfstate-dev --versioning
```

Optionally, add CMEK for the state bucket with a key ring that you create beforehand, then
`--default-encryption-key=projects/<p>/locations/europe-west4/keyRings/tfstate/cryptoKeys/tfstate`. Restrict
access to the Terraform runner:

```bash
gcloud storage buckets add-iam-policy-binding gs://wellnessz-tfstate-dev --member=user:<you@example.com> --role=roles/storage.objectAdmin
```

## 5. Identity provider

GKE does not change the identity model. Users still authenticate with **Microsoft Entra ID**. Follow the Azure guide
(document 06), section 5, to create:

* the groups (including the nine increment-1 groups for OH_PHYSICIAN, AME, CMO, NURSING_HEAD, HSE_OFFICER,
  CREW_SCHEDULING, HYGIENE_MANAGER, CLINIC_RECEPTION and FIRST_AIDER);
* `wellnessz-api` with app roles (20, including the nine increment-1 roles; 21 with the increment-2 role `DPO`; 25 with the increment-3 roles `CLINIC_SCHEDULER`, `BIOMEDICAL`, `CREW_LICENSING`, `CANDIDATE`; 30 with the increment-4 roles `CLAIMS_COORDINATOR`, `TPA_ADJUSTER`, `LEGAL_COUNSEL`, `FINANCE`, `PROCUREMENT`), the `access_as_user` scope and v2 tokens;
* the `employee_id` / `org_scope` claims;
* `wellnessz-bff`, with redirect URIs `https://employee.<domain>/login/oauth2/code/wellnessz`,
  `https://manager.<domain>/login/oauth2/code/wellnessz`, (increment 1) `https://oh.<domain>/login/oauth2/code/wellnessz`
  and (increment 4) `https://partners.<domain>/login/oauth2/code/wellnessz`;
* `wellnessz-mobile`;
* M2M apps, including the **EHS webhook client** with the `INTEGRATION` application role (Azure guide, section 5.6);
* (increment 2) the `DPO` app role and group `sg-wellnessz-dpo`, `org_scope=/PARTNERS/<ORG>` for partner users
  (Azure guide, sections 5.1–5.3), and one `INTEGRATION` client per gateway – laboratory, device middleware, crew
  rostering (Azure guide, section 5.6);
* (increment 3) groups `sg-wellnessz-clinic-schedulers`, `sg-wellnessz-biomedical`, `sg-wellnessz-crew-licensing`
  with their app roles, a candidate identity source with `CANDIDATE`, `employee_id` = candidate id and
  `org_scope=/CANDIDATES` (Azure guide, sections 5.1–5.3), and one `INTEGRATION` client per new gateway – absence
  feed per source system, industrial hygiene, dosimetry, recruiting, IoT fridge gateway (Azure guide, section 5.6);
* (increment 4) groups `sg-wellnessz-claims`, `sg-wellnessz-tpa-adjusters`, `sg-wellnessz-legal`,
  `sg-wellnessz-finance`, `sg-wellnessz-procurement` with their app roles; partner and adjuster **B2B guests** with
  `org_scope=/PARTNERS/<CODE>` and a **Conditional Access policy requiring an authentication strength** for guest /
  external users on `wellnessz-bff` and `wellnessz-api` (Azure guide, sections 5.1–5.3a) – the core grants
  `PARTNER_PROVIDER` / `TPA_ADJUSTER` only when the token carries `amr: mfa` (or an authentication context such as
  `c1` in `acrs`); and one `INTEGRATION` client per partner hospital (FHIR), insurer system and, once selected, the
  video platform (Azure guide, section 5.6) – partner-hospital and insurer clients with a fixed
  `org_scope=/PARTNERS/<CODE>` claim, which binds each client to its own organisation's referrals, pre-authorisations
  and reports.

Those steps need only Entra ID, not an Azure subscription. If the airline uses **Keycloak**, follow section 5.7 of
the Azure guide; keep the nine increment-1 roles, the increment-2 role `DPO`, the four increment-3 roles and the five increment-4 roles in the scope mappings of `wellnessz-bff` and `wellnessz-mobile`
(`fullScopeAllowed=false`), add the `partners.<domain>` redirect URI, post-logout URI and web origin, and require OTP
or WebAuthn for every partner and adjuster account with an AMR mapper or a level-of-authentication flow so that the
token carries `amr` / `acr` / `acrs` proof (Azure guide, section 5.7; setup guide 05, section 6.1). On GKE, run Keycloak with the Keycloak Operator and a separate Cloud SQL database, behind its own
Ingress and Cloud Armor policy.

Note for section 11: the **issuer**, **audience** (Entra: API client ID), **BFF client ID** and **mobile client
ID**.

## 6. Provision the infrastructure with Terraform

```bash
cd deploy/terraform/gcp && cp backend.hcl.example backend.hcl && cp terraform.tfvars.example terraform.tfvars
```

Edit `backend.hcl` to set `bucket = "wellnessz-tfstate-dev"` and `prefix = "wellnessz/dev/gcp"`. Then edit
`terraform.tfvars`:

| Variable | Example | Notes |
|---|---|---|
| `project_id`, `region` | `my-wz-dev`, `europe-west4` | |
| `environment` | `dev` | |
| `gke_autopilot` | `true` | `false` creates a Standard cluster with a hardened node pool (Shielded VM, COS, autoscaling) |
| `gke_private_endpoint` | `false` (dev), `true` (prod) | With `true`, CI uses a runner in the VPC or the Connect Gateway / DNS endpoint |
| `gke_master_authorized_networks` | Corporate egress and CI ranges | Control-plane allow-list |
| `binauthz_enforcement_mode` | `DRYRUN_AUDIT_LOG_ONLY` | Switch to `ENFORCED_BLOCK_AND_AUDIT_LOG` after the first attested release |
| `sql_tier`, `sql_edition` | `db-custom-4-16384`, `ENTERPRISE` | |
| `sql_iam_admin_group` | `wellnessz-dba@example.com` | Google group for IAM database login |
| `migrations_mode` | `job` | Must match Helm |
| `github_repository` | `OWNER/WellnessZ` | Creates the Workload Identity pool, provider and deployer service account |

```bash
terraform init -backend-config=backend.hcl
```

```bash
terraform plan -out tfplan
```

Expect about 90 resources. KMS keys, Cloud SQL and the cluster are protected by `prevent_destroy` or
`deletion_protection`.

```bash
terraform apply tfplan
```

Duration: 25–40 minutes (Cloud SQL `REGIONAL` HA and GKE). Export the outputs:

```bash
terraform output -json > /tmp/wz-gcp-outputs.json && jq 'with_entries(.value |= .value)' /tmp/wz-gcp-outputs.json
```

Key outputs:

* `artifact_registry`, `cloudsql_private_ip`, `workload_service_accounts`;
* `cloud_armor_policy_name`, `ssl_policy_name`, `ingress_static_ip`, `psa_cidr`;
* `binauthz_attestor`, `binauthz_kms_key_version`;
* `github_workload_identity_provider`, `github_deployer_service_account`.

### 6.1 Verify

```bash
gcloud container clusters get-credentials $(terraform output -raw gke_cluster_name) --region $(terraform output -raw gke_location) --dns-endpoint
```

```bash
kubectl get nodes
```

On Autopilot, nodes appear only once workloads are scheduled; `No resources found` is normal.

```bash
gcloud sql instances describe sql-wellnessz-dev --format="table(state, databaseVersion, settings.availabilityType, settings.ipConfiguration.ipv4Enabled, settings.ipConfiguration.sslMode)"
```

Expected: `RUNNABLE  POSTGRES_16  REGIONAL  False  ENCRYPTED_ONLY`.

```bash
gcloud container binauthz policy export | head -20
```

Expected: the default admission rule `REQUIRE_ATTESTATION` with the `wellnessz-ci` attestor.

## 7. Bootstrap secrets (dual control)

Terraform created all Secret Manager secrets, encrypted with CMEK and with per-secret IAM.

* `wz-db-password`, `wz-bff-db-password` and `wz-migrator-db-password` already hold generated passwords. They were
  written with write-only arguments; the same values were set on the Cloud SQL users.
* `wz-cloudsql-server-ca` holds the instance CA certificate (public).

Replace the placeholders:

```bash
for s in wz-master-key wz-pseudonym-key wz-bff-session-key; do openssl rand -base64 32 | tr -d '\n' | gcloud secrets versions add $s --data-file=-; done
```

```bash
read -rs V && printf '%s' "$V" | gcloud secrets versions add wz-bff-client-secret --data-file=- ; unset V
```

Repeat for `wz-graph-client-secret` (EHS mailbox app, see the Azure guide section 13).

Disable the placeholder versions so they can never be mounted:

```bash
for s in wz-master-key wz-pseudonym-key wz-bff-session-key wz-bff-client-secret wz-graph-client-secret; do gcloud secrets versions disable 1 --secret=$s; done
```

Verify (metadata only):

```bash
gcloud secrets list --format="table(name, replication.userManaged.replicas[0].customerManagedEncryption.kmsKeyName.basename())"
```

## 8. Bootstrap the database

Cloud SQL has no public IP. Run the bootstrap from a temporary pod in GKE as the built-in `postgres` user. First
give `postgres` a strong break-glass password and store it only in Secret Manager:

```bash
PGPW=$(openssl rand -base64 24) && gcloud sql users set-password postgres --instance=sql-wellnessz-dev --password="$PGPW" && printf '%s' "$PGPW" | gcloud secrets create wz-pg-admin-password --replication-policy=user-managed --locations=europe-west4 --data-file=- ; unset PGPW
```

```bash
gcloud sql instances describe sql-wellnessz-dev --format="value(serverCaCert.cert)" > /tmp/server-ca.pem
```

```bash
kubectl create namespace wz-admin && kubectl -n wz-admin create configmap bootstrap --from-file=../../sql/bootstrap-managed-postgres.sql --from-file=/tmp/server-ca.pem
```

```bash
kubectl -n wz-admin run psql --rm -it --restart=Never --image=postgres:16.15-bookworm --overrides='{"spec":{"containers":[{"name":"psql","image":"postgres:16.15-bookworm","stdin":true,"tty":true,"command":["bash"],"volumeMounts":[{"name":"b","mountPath":"/b"}]}],"volumes":[{"name":"b","configMap":{"name":"bootstrap"}}]}}'
```

Inside the pod, use the password from `gcloud secrets versions access latest --secret=wz-pg-admin-password`:

```bash
PGPASSWORD='<postgres password>' psql "host=<cloudsql_private_ip> dbname=wellnessz user=postgres sslmode=verify-ca sslrootcert=/b/server-ca.pem" -f /b/bootstrap-managed-postgres.sql
```

On GCP no password variables are passed: Terraform already set them. The script:

* revokes `cloudsqlsuperuser` from `wz_migrator`, `wz_app` and `wz_bff`;
* makes `wz_migrator` the owner of the database and schemas;
* grants `CONNECT` to the runtime roles.

The schema list is `platform, masterdata, intake, casemgmt, triage, docs, ohrecord, fitness, absence, surveillance,
scheduling, treatment, claims, analytics` – `triage` since increment 1, `docs`, `ohrecord`, `fitness` since
increment 2, `absence`, `surveillance`, `scheduling` since increment 3 and `treatment`, `claims` since increment 4. For
an instance bootstrapped earlier, Flyway V5, V6–V8, V10–V12 and V14–V15 create the schemas and grant `USAGE` to
`wz_app` during the next deployment; re-run the idempotent script to align the roles' `search_path`.

```bash
kubectl delete namespace wz-admin
```

> **Cloud SQL specifics (verify in dev).** Users created through the Cloud SQL API are members of
> `cloudsqlsuperuser`. On PostgreSQL 16, the `postgres` user may lack the ADMIN option on roles it did not create.
> If `GRANT wz_migrator TO postgres` fails with `permission denied to grant role`:
>
> 1. Remove `google_sql_user.wz_app`, `wz_bff` and `wz_migrator` from Terraform.
> 2. Let the bootstrap script create the roles (pass `-v migrator_pw=… -v app_pw=… -v bff_pw=…` with the values
>    from Secret Manager).
>
> The script is idempotent. It was validated with a non-superuser `CREATEROLE` admin on PostgreSQL 16.15, but not
> on Cloud SQL itself.

## 9. Mirror the Flyway image (Binary Authorization)

Binary Authorization admits only attested images plus the allow-listed path `…/wellnessz/flyway/*`. Mirror the
Flyway image into Artifact Registry, pinned by digest:

```bash
gcloud auth configure-docker europe-west4-docker.pkg.dev --quiet
```

```bash
crane copy flyway/flyway:11.20.3-alpine europe-west4-docker.pkg.dev/<project-id>/wellnessz/flyway/flyway:11.20.3-alpine
```

`values-gcp.yaml` already points `migrations.job.image.fullName` at this path.

## 10. DNS and certificates

1. Create A records for `employee.<domain>`, `manager.<domain>`, `oh.<domain>` (OH workspace, increment 1),
   `partners.<domain>` (partner portal, increment 4) and `api.<domain>` pointing to `ingress_static_ip`.
2. The chart creates `ManagedCertificate` resources; the `oh.<domain>` and `partners.<domain>` hosts are added to the
   certificate domains automatically when the component is enabled (`ohWorkspace.enabled`, `partnerPortal.enabled`)
   and its `ingress.hosts.*` value is set. Adding a domain to an existing certificate re-provisions it. Provisioning
   starts after the Ingress exists and DNS resolves, and takes 15–60 minutes.
3. **Exposure (increment 4).** `partners.<domain>` must be reachable from the internet for external partners and
   adjusters. Restrict `manager.<domain>` and `oh.<domain>` to the corporate egress ranges with a Cloud Armor rule on
   the host name (all components share one policy, `gcp.backendConfig.securityPolicy`), for example:

   ```bash
   gcloud compute security-policies rules create 900 --security-policy=<cloud_armor_policy_name> --expression="(request.headers['host'] == 'oh.<domain>' || request.headers['host'] == 'manager.<domain>') && !inIpRange(origin.ip, '<corporate-cidr>')" --action=deny-403 --description="internal WellnessZ apps from the corporate network only"
   ```

   Keep the rate-based ban on login paths for every host; Identity-Aware Proxy is an alternative for the internal
   hosts but adds a second sign-in.

```bash
kubectl -n wellnessz get managedcertificate
```

Expected: `Active` for each certificate. `Provisioning` is normal for up to an hour; `FailedNotVisible` means DNS
does not point to the static IP yet.

## 11. GitHub configuration (Workload Identity Federation)

Terraform created:

* the pool `github-<env>`;
* the provider `github`, with the attribute condition `assertion.repository == 'OWNER/WellnessZ'`;
* the deployer service account. Its roles: `container.developer`, `artifactregistry.writer` on the repository,
  the Binary Authorization attestation roles, and `cloudkms.signerVerifier` on the attestor key.

Configure GitHub:

| Scope | Variable | Value |
|---|---|---|
| Repository | `IMAGE_REGISTRY_KIND` | `gar` |
| Repository | `GAR_LOCATION`, `GCP_PROJECT_ID` | `europe-west4`, `<project-id>` |
| Repository | `GCP_WORKLOAD_IDENTITY_PROVIDER` | `github_workload_identity_provider` output |
| Repository | `GCP_SERVICE_ACCOUNT` | `github_deployer_service_account` output |
| Repository | `BINAUTHZ_ATTESTOR`, `BINAUTHZ_KMS_KEY_VERSION` | `binauthz_attestor`, `binauthz_kms_key_version` outputs |
| Environment (`dev`/`test`/`prod`) | `CLOUD` | `gcp` |
| Environment | `GCP_WORKLOAD_IDENTITY_PROVIDER`, `GCP_SERVICE_ACCOUNT`, `GCP_PROJECT_ID` | Per-environment project values |
| Environment | `GKE_CLUSTER`, `GKE_LOCATION` | `gke_cluster_name`, `gke_location` |
| Environment | `IMAGE_REGISTRY`, `VALUES_FILE`, `ENV_VALUES_FILE` | `<artifact_registry>`, `deploy/helm/wellnessz/values-gcp.yaml`, overrides file |
| Secret | `NVD_API_KEY` | NVD API key |

The commands to create the same federation manually (for example in an existing project) are listed in the CI/CD
Guide, section 6.3.

## 12. Per-environment values

Copy `values-gcp.yaml` placeholders into an overrides file, for example `deploy/env/dev-gcp.yaml`:

| Placeholder | Source |
|---|---|
| `<region>`, `<project-id>` | tfvars |
| Workload identity service accounts | `workload_service_accounts` |
| `<cloudsql-private-ip>` | `cloudsql_private_ip` (three URLs: core, BFF, job) |
| `<tenant-id>`, `<wellnessz-api-client-id>`, `<bff-client-id>`, `<ehs-mailbox-app-client-id>` | Entra ID |
| `gcp.backendConfig.securityPolicy` | `cloud_armor_policy_name` |
| `gcp.frontendConfig.sslPolicy` | `ssl_policy_name` |
| `networkPolicy.database.cidrs` | `psa_cidr` |
| Hostnames | Your domain: `ingress.hosts.employeePortal`, `managerWorkspace`, `ohWorkspace` (increment 1), `ingress.api.host`; the same web hosts in `core.env.WZ_CORS_ORIGINS` |
| `core.env.WZ_OH_WORKSPACE_URL`, `WZ_OH_CONTACT` (increment 1) | `https://oh.<domain>` (`values.yaml` sets `https://oh.wellnessz.example.com` – override with your domain); the OH service-desk contact line for notifications |
| `core.env.WZ_EMPLOYEE_PORTAL_URL`, `WZ_MANAGER_WORKSPACE_URL`, `WZ_DPO_EMAIL` (increment 2) | `https://employee.<domain>`, `https://manager.<domain>` (set in `values.yaml` to the `*.wellnessz.example.com` hosts – override with your domain); the DPO mailbox for break-glass alerts |
| `core.env.WZ_DOCUMENTS_FONT_PATH`, `WZ_DOCUMENTS_RETENTION_CRON`, `WZ_DIAGNOSIS_REQUIRED_ON_CLOSE` (increment 2, optional) | Font path inside a derived core image (Arabic PDFs); default `0 30 2 * * *`; default `true` |

**Increment 4** adds one required value and configuration:

| Value | Set to |
|---|---|
| `ingress.hosts.partnerPortal` | `partners.<domain>` (component enabled by default; `partnerPortal.enabled: false` to keep it off) |
| `core.env.WZ_PARTNER_PORTAL_URL` | `https://partners.<domain>` – links in partner and adjuster notifications (set in `values.yaml` to `https://partners.wellnessz.example.com`, in line with `ingress.hosts.partnerPortal` – override it with your domain) |
| `core.env.WZ_MFA_REQUIRED_ROLES` | Leave unset (default `PARTNER_PROVIDER,TPA_ADJUSTER`); never empty outside local development |
| `core.extraEnv` (optional) | `WELLNESSZ_TREATMENT_VARIANCEJOBCRON`, `WELLNESSZ_TREATMENT_CHASEJOBCRON`, `WELLNESSZ_TREATMENT_SCORECARDJOBCRON`, `WELLNESSZ_CLAIMS_REPORTDEADLINESCRON` |

The partner portal needs no CORS entry (same-origin BFF). After the first deployment set the E18 keys `claims.mailbox-email`,
`claims.legal-email`, `claims.employer-ref`, `claims.employer-name`, `claims.fx-rates`, `treatment.telehealth.base-url`
and `treatment.network.cities` (Azure guide, section 11, increment 4). Insurer, authority and ERP messages stay in the
outbox until connectors exist; a future connector calls out through Cloud NAT (public endpoints, FQDN allow-list on a
Secure Web Proxy or firewall policy) or Private Service Connect / Interconnect (private ERP) with a
`networkPolicy.extraEgress.core` rule.

**Increment 3** adds no required value. Optional `core.extraEnv`: `WELLNESSZ_RECORD_REQUIREREGISTEREDDEVICES=true`
once every result-producing device is in the equipment register, and job schedule overrides
(`WELLNESSZ_ABSENCE_FEEDCRON` etc., Azure guide section 11). After the first deployment set the E18 business keys
through `PUT /api/v1/masterdata/config/{key}` as an OH_ADMIN – `absence.hr-email`, `notifications.hr-onboarding.email`,
`notifications.crew-licensing.email`, `notifications.biomedical.email`, `scheduling.telehealth-base-url` and
`absence.public-holidays` (Azure guide, section 11, *Business configuration*). The IoT fridge gateway and the absence
feeds call the API host; if a future `AbsenceFeedConnector` pulls from a source system, add a
`networkPolicy.extraEgress.core` rule and route it through Cloud NAT or Private Service Connect.

**Optional OCR (increment 2).** Off by default. Either a derived core image with Tesseract
(`core.extraEnv`: `WELLNESSZ_DOCUMENTS_OCR_TESSERACTPATH=/usr/bin/tesseract`,
`WELLNESSZ_DOCUMENTS_OCR_LANGUAGES=eng+ara+fra`; allow-listed values only), or **Google Document AI** as an
`OcrProvider` extension point (not shipped): processor in the same region, access through Private Service Connect /
VPC Service Controls perimeter, the core's Google service account with `roles/documentai.apiUser`, a
`networkPolicy.extraEgress.core` rule and a DPIA approved by the DPO. Derived images must go through the same
build, attestation and Binary Authorization path as the base image.

```bash
helm lint deploy/helm/wellnessz -f deploy/helm/wellnessz/values.yaml -f deploy/helm/wellnessz/values-gcp.yaml -f deploy/env/dev-gcp.yaml
```

Never add `classpath:db/demo` to `WZ_FLYWAY_LOCATIONS` outside local development. Load the OH staff roster
(`masterdata.staff_member`) with a reviewed SQL script and push HRIS employees to `POST /api/v1/masterdata/employees`.

## 13. Build, attest and deploy

1. Run **Actions → Container images** with `registry = gar` (or push to `main`). For each image the workflow:
   * builds it and runs the Trivy gate;
   * pushes it with Workload Identity Federation;
   * attests the SBOM and provenance;
   * signs it with cosign;
   * creates the **Binary Authorization attestation** with the KMS key.
2. Verify the attestation:

   ```bash
   gcloud container binauthz attestations list --attestor=wellnessz-ci --attestor-project=<project-id> --format="value(resourceUri)" | head
   ```

3. Deploy. Either push the tag `v0.1.0` (CD workflow), or install manually for the first time:

   ```bash
   kubectl create namespace wellnessz && kubectl label namespace wellnessz pod-security.kubernetes.io/enforce=restricted
   ```

   ```bash
   helm upgrade --install wellnessz deploy/helm/wellnessz -n wellnessz -f deploy/helm/wellnessz/values.yaml -f deploy/helm/wellnessz/values-gcp.yaml -f deploy/env/dev-gcp.yaml --set-string global.imageTag=v0.1.0 --atomic --wait --timeout 20m
   ```

4. Check the result:

   ```bash
   kubectl -n wellnessz get pods,ingress,backendconfig,managedcertificate,secretproviderclass
   ```

   Expected: all pods `Running` (including `wellnessz-oh-workspace` and, since increment 4, `wellnessz-partner-portal`);
   the Ingress has the static IP; the BackendConfigs reference `wellnessz-waf`.

5. After a successful release, switch Binary Authorization to enforcement. Set
   `binauthz_enforcement_mode = "ENFORCED_BLOCK_AND_AUDIT_LOG"`, then:

   ```bash
   terraform apply
   ```

## 14. Smoke tests

Run the same checklist as the Azure guide, section 14, with these GCP specifics:

* **WAF test:**

  ```bash
  curl -s -o /dev/null -w '%{http_code}' "https://employee.<domain>/?q=%3Cscript%3Ealert(1)%3C/script%3E"
  ```

  Expected: `403` from Cloud Armor. The Cloud Logging query
  `resource.type="http_load_balancer" jsonPayload.enforcedSecurityPolicy.outcome="DENY"` shows the block.
* **Secret access audit:**

  ```text
  protoPayload.serviceName="secretmanager.googleapis.com" protoPayload.methodName="google.cloud.secretmanager.v1.SecretManagerService.AccessSecretVersion"
  ```

  Only `wellnessz-core@`, `wellnessz-bff@` and `wellnessz-migrator@` should appear.
* **Binary Authorization:**

  ```bash
  kubectl -n wellnessz run unsigned --image=nginx:1.29 --restart=Never
  ```

  This is denied once enforcement is on. In dry-run, a `cloudaudit` log entry is created instead.
* **End-to-end:** use `WZ_E2E_*` as in the Azure guide.
* **Increment 1:** run checks 12–17 of the Azure guide, section 14: OH workspace host and login, E01/E02
  `IMPLEMENTED` in `GET /api/v1/modules`, new roles in tokens, EHS webhook `202`, status-only crew API, and
  `tools/e2e/e2e-increment1.js` with `WZ_E2E_OH_URL=https://oh.<domain>` in dev/test. Cloud Armor must allow webhook
  bodies of up to 500 incidents; check `jsonPayload.enforcedSecurityPolicy` if the call returns `403`.
* **Increment 2:** run checks 18–24 of the Azure guide, section 14: new schemas (`\dn` from the bootstrap pod), E03 /
  E05 `IMPLEMENTED`, DPO segregation, break-glass alert, document generation, gateway pull queue (`200` with a
  gateway token, `403` with a user token) and `tools/e2e/e2e-increment2.js` (25 `PASS` lines) in dev/test. Cloud
  Armor must accept HL7 v2 ER7 bodies (`text/plain`) on `/api/v1/integration/hl7v2` and multipart uploads of up to
  10 MB on `/api/v1/documents/scan`; check `jsonPayload.enforcedSecurityPolicy` for false positives on the
  preconfigured CRS rules.
* **Increment 3:** run checks 25–32 of the Azure guide, section 14: new schemas, E06 / E07 / E14 `IMPLEMENTED`, new
  roles in tokens, scheduler segregation (`403` on `/api/v1/absence/queue`), certificate upload through the BFF,
  calibration block, cold-chain gateway call and `tools/e2e/e2e-increment3.js` (32 `PASS` lines) in dev/test. Cloud
  Armor must accept multipart uploads of up to 10 MB on `/bff/api/me/absence/certificates` and
  `/bff/api/me/pre-employment/documents`, and JSON batches of up to 5,000 rows on `/api/v1/integration/absence/episodes`
  and `/api/v1/integration/cold-chain/readings`; check `jsonPayload.enforcedSecurityPolicy` for false positives.
* **Increment 4:** run checks 33–42 of the Azure guide, section 14: partner portal host (`curl -sI
  https://partners.<domain>/` → `200` with HSTS and CSP; `ManagedCertificate` `Active` with the new domain), new
  schemas, E04 / E08 `IMPLEMENTED`, MFA gate positive and negative with a partner guest, organisation isolation,
  adjuster view, non-clinical roles, FHIR partner endpoint, notification links and `tools/e2e/e2e-increment4.js`
  (22 `PASS` lines, with `WZ_E2E_PARTNER_URL=https://partners.<domain>`) on a fresh dev database. Cloud Armor must
  accept multipart uploads of up to 10 MB on `/bff/api/partner/referrals/*/report` and `/invoice` (PDF / JPEG / PNG)
  and FHIR JSON with base64 attachments on `/api/v1/integration/referrals/fhir/document-reference`; check
  `jsonPayload.enforcedSecurityPolicy` for false positives. The internal-host rule of section 10 must return `403` for
  `oh.<domain>` from outside the corporate range and must not affect `partners.<domain>`.

## 15. EHS mailbox and MDM

The application side is identical to Azure (`values-gcp.yaml` contains the same `WZ_GRAPH_*` keys). Follow the
Azure guide, section 13, for Exchange RBAC for Applications, and section 15 for Intune / Workspace ONE / Jamf
AppConfig. Use `wz.apiBaseUrl=https://api.<domain>/api/v1`.

The core reaches `graph.microsoft.com` through Cloud NAT. Port 443 egress is allowed by the NetworkPolicy, and
private ranges are excluded.

## 16. Troubleshooting

| Symptom | Likely cause | Resolution |
|---|---|---|
| `terraform apply`: `Error 403 … serviceusage` | API not yet enabled or still propagating | Re-run `apply` after a minute |
| Cloud SQL create fails: `CMEK key … permission denied` | Cloud SQL service agent not yet created or no key grant | Re-run `apply` (the agent is created by `google_project_service_identity`) |
| Pods `Pending` with `ImagePullBackOff` / `denied by Binary Authorization` | Image not attested, or Flyway image not mirrored | Check the `BINAUTHZ_*` variables in CI; section 9 |
| Pods stuck on `MountVolume.SetUp failed … secrets-store-gke` | Wrong `projectId`, secret version disabled, or service account lacks accessor | `gcloud secrets get-iam-policy <secret>`; the KSA annotation must match `workload_service_accounts` |
| Ingress backend `UNHEALTHY` | Health check blocked by NetworkPolicy, or wrong port | `allowHealthChecks: true`, ranges `35.191.0.0/16` and `130.211.0.0/22`; BackendConfig health check on 8081 |
| Core cannot connect: `SSL error: certificate verify failed` | CA file missing or `WZ_DB_SSLMODE` mismatch | `cloudsql-server-ca.pem` in `secrets.core.files`; `verify-ca` with Cloud SQL |
| ManagedCertificate `FailedNotVisible` | DNS not pointing to the static IP | Fix the A records and wait |
| CD cannot reach the control plane | Private endpoint | Use `use_dns_based_endpoint` (default in `deploy-env.yml`) and authorised networks, or a runner in the VPC |
| Lab gateway `403` on `/integration/hl7v2` with a valid token | Cloud Armor CRS rule matched the pipe-delimited body | Add a scoped exclusion for that path and rule id; keep the `INTEGRATION` role check |
| DPO or partner sees no data | `DPO` app role / partner `org_scope` missing | Azure guide, sections 5.2–5.3 |
| Scheduler, biomedical, crew-licensing or candidate account sees *Access restricted* | Increment-3 app role missing or not assigned; candidate without `org_scope=/CANDIDATES` | Azure guide, sections 5.2–5.3 |
| Certificate upload `403` at the load balancer | Cloud Armor rule matched the multipart body | Scoped exclusion for the upload paths; the core still checks type and size |
| Certificate upload `400` from the BFF | BFF multipart parsing re-enabled by an override | Keep the shipped `spring.servlet.multipart.enabled=false` |
| ManagedCertificate stays `Provisioning` after adding `partners.<domain>` | DNS for the new host missing; the whole certificate re-provisions | Create the A record (section 10) and wait up to an hour |
| Partner or adjuster sees *multi-factor sign-in required* | Token without `amr` / `acr` / `acrs` proof (Conditional Access not enforced for guests, or Keycloak without AMR mapper / LoA flow) | Azure guide, section 5.3a / 5.7 |
| Partner report or invoice upload `403` at the load balancer | Cloud Armor rule matched the multipart body | Scoped exclusion for `/bff/api/partner/referrals/*/report` and `/invoice`; the core checks type and size |
| Internal hosts blocked for staff working remotely | Host-based Cloud Armor rule without the VPN egress range | Add the VPN / ZTNA egress ranges to the rule |
| Partner e-mails link to `partners.wellnessz.example.com` (or `.local`) | `WZ_PARTNER_PORTAL_URL` not overridden with your domain | Section 12 |
| Partner hospital or insurer system gets `404` for its own referral, pre-authorisation or report | Client token without `org_scope=/PARTNERS/<CODE>` or with another organisation's code (audit `DENIED`) | Azure guide, section 5.6 |

## 17. Teardown (dev/test only)

```bash
helm uninstall wellnessz -n wellnessz
```

Before destroying:

* set `deletion_protection = false` on the cluster and the Cloud SQL instance;
* remove the `prevent_destroy` blocks (Cloud SQL, KMS keys) on a throwaway branch.

```bash
terraform destroy
```

Cloud KMS keys cannot be deleted. Their versions are scheduled for destruction (30 days) and the key ring remains,
which is expected. Secrets and audit logs follow their retention settings.
