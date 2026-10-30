# WellnessZ OHCM – Setup Guide 3: Google Cloud

**Version 1.0**

*WellnessZ OHCM platform – increments 1–5*

| | |
|---|---|
| **Document** | 07 – Setup guide: Google Cloud (GKE, Cloud SQL, Secret Manager, Cloud Armor, Binary Authorization) |
| **Version** | 1.0 (final, after increment 5 of 5) |
| **Audience** | Cloud platform team, DevSecOps, identity administrators, DBA, release manager |
| **Result** | Three environments – `gcp-dev`, `gcp-uat`, `gcp-prod`, **one GCP project each** – built from the same Terraform and Helm chart with per-environment variable files, deployed by GitHub Actions with signed, attested images promoted by digest, PROD accepted through the go-live checklist |
| **Time** | DEV: 1 day. UAT: ½ day once DEV works. PROD: ½ day build + the go-live checklist |
| **Related** | [04 – Deployment Architecture](../deployment/Deployment-Architecture.md) · [`deploy/environments/README.md`](../../deploy/environments/README.md) (authoritative matrix) · [06 – Azure guide](02-Azure.md) (Entra ID steps, smoke-check list) · 09 – CI/CD Guide · `deploy/terraform/gcp` · `deploy/helm/wellnessz/environments/gcp/` |

![GCP topology](../images/10-deploy-gcp.png)

*Figure 1 – Google Cloud topology (source: `docs/images/10-deploy-gcp.svg`).*

> **Validation status.** `terraform validate` passes with google/google-beta 8.9 and `terraform fmt` is clean with
> each `environments/<env>.tfvars`. The Helm chart renders and lints with `values-gcp.yaml` +
> `environments/gcp/<env>.yaml` for all three environments, including the GKE CRDs (BackendConfig, FrontendConfig,
> ManagedCertificate) and the Secret Manager SecretProviderClass. **No `terraform apply` was run in a real project.**
> Build DEV first.
>
> **Increments.** Increments 1–4 added roles, schemas, gateway clients, the OH workspace and the partner portal behind
> the existing load balancer and Cloud Armor policy. **Increment 5** needs no new GCP resource: eight roles and groups,
> the EAP partner organisation, the TOXLAB laboratory client and the duty-assignment feed at the IdP (section 5), four
> Flyway-created schemas (8), business configuration (12), increment-5 smoke checks (14) and the PROD go-live
> checklist (17).

## 1. Overview of the steps

**Legend.** **[A]** automated from the repository (Terraform, GitHub Actions, Helm, scripts). **[M]** manual – organisation
or IdP settings, approvals, ceremonies; keep evidence for SOC 2. Build **DEV first**, then UAT, then PROD; repeat every
step per environment unless marked *once*.

| # | Stage | DEV | UAT | PROD | Section |
|---|---|---|---|---|---|
| 1 | Workstation tools (*once*) | [M] | – | – | 2 |
| 2 | Landing zone: project per environment, billing, organisation policies | [M] | [M] | [M] | 3 |
| 3 | Terraform state bucket per environment + `environments/backend-<env>.hcl` | [M] + [A] | same | same | 4 |
| 4 | Identity provider: per-environment registrations, MFA for external users | [M] | [M] | [M] | 5 |
| 5 | `terraform init/plan/apply` with `environments/<env>.tfvars` | [A] | [A] | [A] (change ticket) | 6 |
| 6 | Secrets bootstrap (key ceremony) | [M] | [M] | [M] dual control | 7 |
| 7 | Database bootstrap | [A] script, run [M] | same | same | 8 |
| 8 | Mirror the Flyway image (Binary Authorization) | [A] | [A] | [A] | 9 |
| 9 | DNS and managed certificates | [M] | [M] | [M] | 10 |
| 10 | GitHub Environment `gcp-<env>`: variables, **required reviewers** [M] | [M] | [M] reviewers | [M] reviewers + CAB | 11 |
| 11 | Helm overlay `environments/gcp/<env>.yaml` with Terraform outputs | [A] | [A] | [A] | 12 |
| 12 | First deployment (`cd.yml` / `deploy-env.yml` / `helm-release.sh`), migrations, attestation | [A] | [A] after approval | [A] after approval | 13 |
| 13 | Smoke tests, EHS mailbox, MDM | [M] | [M] | [M] | 14–15 |
| 14 | PROD go-live checklist | – | rehearsal | [M] | 17 |

### 1.1 The three GCP environments

| | `gcp-dev` | `gcp-uat` | `gcp-prod` |
|---|---|---|---|
| Project | `<prefix>-wellnessz-dev` | `<prefix>-wellnessz-uat` | `<prefix>-wellnessz-prod` |
| Terraform | `environments/dev.tfvars` + `dev.local.tfvars` | `uat.tfvars` + `uat.local.tfvars` | `prod.tfvars` + `prod.local.tfvars` |
| State | `gs://wellnessz-tfstate-dev`, prefix `wellnessz/dev/gcp` | `…-uat` | `…-prod` |
| Helm overlay | `environments/gcp/dev.yaml` | `environments/gcp/uat.yaml` | `environments/gcp/prod.yaml` |
| GitHub Environment | `gcp-dev` (no reviewers) | `gcp-uat` (**required reviewers**) | `gcp-prod` (**required reviewers**, CAB) |
| Names | cluster `wellnessz-dev`, Cloud SQL `sql-wellnessz-$ENV`, WIF pool `github-dev` | `…-uat` | `…-prod` |
| Subnet / PSA | `10.68.0.0/20` / `10.68.64.0/20` | `10.56.0.0/20` / `10.56.64.0/20` | `10.60.0.0/20` / `10.60.64.0/20` |
| Cloud SQL | `db-custom-1-3840`, `ZONAL`, 7 backups, 1-day PITR | `db-custom-2-7680`, `REGIONAL`, 14 backups, 7-day PITR | `db-custom-4-16384`, `REGIONAL`, 30 backups, 7-day PITR |
| Binary Authorization | `DRYRUN_AUDIT_LOG_ONLY` | `ENFORCED_BLOCK_AND_AUDIT_LOG` | `ENFORCED_BLOCK_AND_AUDIT_LOG` |
| Cloud Armor | preview (log only) | prevention + adaptive | prevention + adaptive |
| Deletion protection | `false` | `true` | `true` |
| Flyway | `migrations.mode: app` | pre-upgrade Job | pre-upgrade Job |
| Log retention | 30 days | 90 days | 365 days |
| Hosts / audience | `*.dev.<domain>`, `api://wellnessz-api-dev` | `*.uat.<domain>`, `api://wellnessz-api-uat` | `*.<domain>`, `api://wellnessz-api` |

```bash
export ENV=dev PROJECT=<prefix>-wellnessz-$ENV REGION=europe-west4
```

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
gcloud config set project $PROJECT && gcloud config list
```

## 3. Landing zone and project preparation [M]

Use **one project per environment** (the Terraform resource names – static IP, Cloud Armor policy, SSL policy, WIF pool
`github-<env>` – assume it). Recommended folder layout: `wellnessz/non-prod` (DEV, UAT) and `wellnessz/prod` (PROD),
each with the organisation policies below; PROD additionally with VPC Service Controls around Cloud SQL, Secret
Manager and KMS if the client uses them. Recommended organisation
policies for this workload:

| Policy | Value |
|---|---|
| `constraints/sql.restrictPublicIp` | Enforced |
| `constraints/compute.vmExternalIpAccess` | Deny all |
| `constraints/iam.disableServiceAccountKeyCreation` | Enforced (this setup never needs keys) |
| `constraints/gcp.resourceLocations` | Your approved regions (for example `in:eu-locations`) |
| `constraints/compute.requireOsLogin` | Enforced |

```bash
gcloud billing projects describe $PROJECT --format="value(billingEnabled)"
```

Expected: `True`.

Terraform enables the required APIs itself. Only Service Usage and Cloud Resource Manager must be enabled
beforehand:

```bash
gcloud services enable serviceusage.googleapis.com cloudresourcemanager.googleapis.com
```

Set a budget alert per project. The identity running Terraform needs Owner on the project for the first build (PROD:
time-bound through a privileged-access request and a change ticket). For subsequent runs, a custom role
or the combination Editor + Security Admin + Service Networking Admin + Cloud KMS Admin is sufficient.

## 4. Terraform state bucket (one per environment)

The repository ships `deploy/terraform/gcp/environments/backend-{dev,uat,prod}.hcl.example` (bucket
`wellnessz-tfstate-<env>`, prefix `wellnessz/<env>/gcp`, optional `kms_encryption_key`). **[M]** Create the bucket in
the environment's project (bucket names are global – adapt if taken):

```bash
gcloud storage buckets create gs://wellnessz-tfstate-$ENV --project=$PROJECT --location=$REGION --uniform-bucket-level-access --public-access-prevention
```

```bash
gcloud storage buckets update gs://wellnessz-tfstate-$ENV --versioning
```

Optionally add CMEK with a key ring created beforehand (`--default-encryption-key=projects/$PROJECT/locations/$REGION/keyRings/tfstate/cryptoKeys/tfstate`,
then also set `kms_encryption_key` in the backend file). Restrict access to this environment's Terraform runner:

```bash
gcloud storage buckets add-iam-policy-binding gs://wellnessz-tfstate-$ENV --member=user:<you@example.com> --role=roles/storage.objectAdmin
```

**[A]** Create the backend file (git-ignored):

```bash
cd deploy/terraform/gcp && cp environments/backend-$ENV.hcl.example environments/backend-$ENV.hcl
```

## 5. Identity provider

**[M]** GKE does not change the identity model. Users authenticate with **Microsoft Entra ID**, **Keycloak** or any
other OIDC provider (Okta, Ping, Auth0, ADFS 2019+) that issues JWT access tokens with an audience, a role claim,
`employee_id`, `org_scope` and `amr`/`acr` for MFA. **Create the registrations per environment** (`wellnessz-api-dev`
/ `api://wellnessz-api-dev`, `wellnessz-api-uat`, `wellnessz-api` for PROD; likewise for the BFF) – the overlays
`environments/gcp/<env>.yaml` expect these scopes. With Entra ID, follow the Azure guide (document 06), section 5, to
create:

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
  and reports;
* (increment 5) groups `sg-wellnessz-peer-volunteers`, `sg-wellnessz-peer-coordinators`, `sg-wellnessz-safety-managers`,
  `sg-wellnessz-da-programme`, `sg-wellnessz-mro`, `sg-wellnessz-collectors`, `sg-wellnessz-health-promotion`,
  `sg-wellnessz-ergonomists` with their app roles (38 in total); **named individuals only** in `sg-wellnessz-psych` and
  `sg-wellnessz-mro` (restricted clinical class); EAP counsellors as partner guests with `org_scope=/PARTNERS/<EAP code>`
  and MFA; the TOXLAB laboratory client with the fixed claim `org_scope=/PARTNERS/TOXLAB`; internal duty-assignment
  feed clients (crew rostering, shift, facility) without a partner claim; campaign-app and wearable clients (Azure guide,
  sections 5.1–5.6).

**MFA [M].** Conditional Access (Entra ID) or OTP / WebAuthn enrolment (Keycloak) for partner, adjuster and EAP users
cannot be configured from this repository. DEV leaves `WZ_MFA_REQUIRED_ROLES` empty; enable enforcement at the IdP for
the UAT and PROD registrations before their first partner sign-in.

Those steps need only Entra ID, not an Azure subscription. If the airline uses **Keycloak**, follow section 5.7 of
the Azure guide with one realm per environment; keep all 38 roles (increments 1–5) in the scope mappings of `wellnessz-bff` and `wellnessz-mobile`
(`fullScopeAllowed=false`), add the `partners.<domain>` redirect URI, post-logout URI and web origin, and require OTP
or WebAuthn for every partner and adjuster account with an AMR mapper or a level-of-authentication flow so that the
token carries `amr` / `acr` / `acrs` proof (Azure guide, section 5.7; setup guide 05, section 6.1). On GKE, run Keycloak with the Keycloak Operator and a separate Cloud SQL database, behind its own
Ingress and Cloud Armor policy.

Note per environment for section 12: the **issuer**, **audience** (Entra: API client ID), **BFF client ID** and
**mobile client ID**.

## 6. Provision the infrastructure with Terraform [A]

```bash
cd deploy/terraform/gcp && cp environments/local.tfvars.example environments/$ENV.local.tfvars
```

Fill the identifiers (no secrets) in `environments/$ENV.local.tfvars`:

| Variable | Example | Notes |
|---|---|---|
| `project_id` | `<prefix>-wellnessz-dev` | The environment's project |
| `sql_iam_admin_group` | `wellnessz-dba-dev@example.com` | Google group for IAM database login |
| `github_repository` | `OWNER/WellnessZ` | Creates the WIF pool `github-<env>`, provider and deployer SA trusted by GitHub Environment `gcp-<env>` only |
| `gke_master_authorized_networks` (optional override) | `[{ cidr_block = "<corporate egress>/24", display_name = "corporate" }]` | Replace the `203.0.113.0/24` placeholder of the committed tfvars |
| `gke_rbac_security_group` (optional) | `gke-security-groups@example.com` | Google Groups for RBAC: the group must exist in Cloud Identity / Workspace and contain the groups bound in RoleBindings; omitted = off (IAM users and service accounts only) |

Review what differs in the committed `environments/$ENV.tfvars`:

| Variable | `dev.tfvars` | `uat.tfvars` | `prod.tfvars` |
|---|---|---|---|
| `deletion_protection` | `false` | `true` | `true` |
| `subnet_cidr` / `pods_cidr` / `services_cidr` | `10.68.0.0/20` / `10.72.0.0/14` / `10.68.16.0/20` | `10.56.0.0/20` / `10.76.0.0/14` / `10.56.16.0/20` | `10.60.0.0/20` / `10.64.0.0/14` / `10.60.16.0/20` |
| `master_cidr` / `psa_range` | `172.16.0.64/28` / `10.68.64.0/20` | `172.16.0.48/28` / `10.56.64.0/20` | `172.16.0.32/28` / `10.60.64.0/20` |
| `binauthz_enforcement_mode` | `DRYRUN_AUDIT_LOG_ONLY` | `ENFORCED_BLOCK_AND_AUDIT_LOG` | `ENFORCED_BLOCK_AND_AUDIT_LOG` |
| `migrations_mode` | `app` | `job` | `job` |
| `sql_tier` / `sql_disk_size_gb` / `sql_availability_type` | `db-custom-1-3840` / 20 / `ZONAL` | `db-custom-2-7680` / 50 / `REGIONAL` | `db-custom-4-16384` / 100 / `REGIONAL` |
| `sql_backup_retention` / `sql_transaction_log_retention_days` | 7 / 1 | 14 / 7 | 30 / 7 |
| `cloud_armor_preview` / `cloud_armor_adaptive_protection` | `true` / `false` | `false` / `true` | `false` / `true` |
| `log_retention_days` | 30 | 90 | 365 |
| `github_environments` / `github_image_publish_branches` | `["gcp-dev"]` / `["main"]` | `["gcp-uat"]` / `[]` | `["gcp-prod"]` / `[]` |

All three use `gke_autopilot = true`, `gke_private_endpoint = false` (set `true` in PROD only with CI inside the VPC
or the Connect Gateway) and `region = "europe-west4"`.

Security settings that are the same in every environment (no variable): GKE intranode visibility on (default in
Autopilot), no legacy client certificates, Shielded nodes with Secure Boot and the GKE metadata server (enforced by
Autopilot; set explicitly for Standard node pools); Cloud Armor rule 1011 evaluates all `cve-canary` (Log4j)
signatures and follows `cloud_armor_preview` like the other WAF rules; Cloud SQL logs failing statements
(`log_min_error_statement = error`) and client host names, but never statement text by duration
(`log_min_duration_statement = -1`) – statements and bind values can carry health data; use Query Insights for slow
queries. `log_statement` stays unset: `pgaudit.log = ddl,role` audits DDL and role changes with redacted passwords,
whereas core `log_statement = ddl` would log `ALTER ROLE … PASSWORD` (section 8) in clear text.

```bash
terraform init -reconfigure -backend-config=environments/backend-$ENV.hcl
```

```bash
terraform plan -var-file=environments/$ENV.tfvars -var-file=environments/$ENV.local.tfvars -out $ENV.tfplan
```

Expect about 90 resources. KMS keys, Cloud SQL and the cluster are protected by `prevent_destroy` or
`deletion_protection`. For UAT and PROD attach the plan to the change ticket and have it reviewed **[M]**.

```bash
terraform apply $ENV.tfplan && rm $ENV.tfplan
```

Duration: 25–40 minutes (Cloud SQL `REGIONAL` HA and GKE). Export the outputs:

```bash
terraform output -json > /tmp/wz-gcp-$ENV-outputs.json && jq 'with_entries(.value |= .value)' /tmp/wz-gcp-$ENV-outputs.json
```

| Output | Used for |
|---|---|
| `artifact_registry` | `global.imageRegistry`, GitHub variable `IMAGE_REGISTRY` |
| `cloudsql_private_ip`, `cloudsql_connection_name` | JDBC URLs of core, BFF and the migration Job (`<env-cloudsql-private-ip>`) |
| `workload_service_accounts` | `workloadIdentity.gcp.serviceAccounts` (`wellnessz-core@`, `wellnessz-bff@`, `wellnessz-migrator@`) |
| `cloud_armor_policy_name`, `ssl_policy_name`, `ingress_static_ip` | BackendConfig / FrontendConfig (already the defaults – one project per environment); DNS (section 10) |
| `psa_cidr` | `networkPolicy.database.cidrs` (already in the overlay) |
| `binauthz_attestor`, `binauthz_kms_key_version` | GitHub variables `BINAUTHZ_ATTESTOR`, `BINAUTHZ_KMS_KEY_VERSION` |
| `github_workload_identity_provider`, `github_deployer_service_account` | GitHub variables `GCP_WORKLOAD_IDENTITY_PROVIDER`, `GCP_SERVICE_ACCOUNT` of Environment `gcp-<env>` |
| `gke_cluster_name`, `gke_location` | GitHub variables `GKE_CLUSTER`, `GKE_LOCATION` |

Validate locally before any Terraform PR: `terraform fmt -check -recursive && terraform init -backend=false && terraform validate`.

### 6.1 Verify

```bash
gcloud container clusters get-credentials $(terraform output -raw gke_cluster_name) --region $(terraform output -raw gke_location) --dns-endpoint
```

```bash
kubectl get nodes
```

On Autopilot, nodes appear only once workloads are scheduled; `No resources found` is normal.

```bash
gcloud sql instances describe sql-wellnessz-$ENV --format="table(state, databaseVersion, settings.availabilityType, settings.ipConfiguration.ipv4Enabled, settings.ipConfiguration.sslMode)"
```

Expected: `RUNNABLE  POSTGRES_16  REGIONAL  False  ENCRYPTED_ONLY` (DEV: `ZONAL`).

```bash
gcloud container binauthz policy export | head -20
```

Expected: the default admission rule `REQUIRE_ATTESTATION` with the `wellnessz-ci` attestor; enforcement mode dry-run in
DEV, enforced in UAT and PROD.

## 7. Bootstrap secrets (dual control) [M]

Run in the environment's project (`gcloud config set project $PROJECT`); generate **different** key material per
environment.

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

**`ENC(...)` values.** The database passwords reach the pods as CSI-mounted files (`WZ_DB_PASSWORD=file:…`), never as
configuration. A password that must live in a values file or ConfigMap (for example an IMAP / SMTP relay password in
`core.extraEnv`) is stored as `ENC(...)`, encrypted with this environment's master key:

```bash
umask 077 && KEYFILE=$(mktemp) && gcloud secrets versions access latest --secret=wz-master-key > $KEYFILE
```

```bash
WZ_MASTER_KEY_FILE=$KEYFILE ./tools/encrypt-secret.sh; rm -f $KEYFILE
```

`EncryptCli` prompts without echo and prints `ENC(…)`; the core decrypts it at start-up with `WZ_MASTER_KEY_FILE`
mounted from Secret Manager (Azure guide, section 7.1).

## 8. Bootstrap the database

Cloud SQL has no public IP. Run the bootstrap from a temporary pod in GKE as the built-in `postgres` user. First
give `postgres` a strong break-glass password and store it only in Secret Manager:

```bash
PGPW=$(openssl rand -base64 24) && gcloud sql users set-password postgres --instance=sql-wellnessz-$ENV --password="$PGPW" && printf '%s' "$PGPW" | gcloud secrets create wz-pg-admin-password --replication-policy=user-managed --locations=$REGION --data-file=- ; unset PGPW
```

```bash
gcloud sql instances describe sql-wellnessz-$ENV --format="value(serverCaCert.cert)" > /tmp/server-ca.pem
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
scheduling, treatment, claims, mentalhealth, substance, travelhealth, promotion, analytics` – `triage` since increment
1, `docs`, `ohrecord`, `fitness` since increment 2, `absence`, `surveillance`, `scheduling` since increment 3,
`treatment`, `claims` since increment 4 and `mentalhealth`, `substance`, `travelhealth`, `promotion` since increment 5.
For an instance bootstrapped earlier, Flyway V5, V6–V8, V10–V12, V14–V15 and V17–V20 create the schemas and grant `USAGE` to
`wz_app` during the next deployment; re-run the idempotent script to align the roles' `search_path`.

```bash
kubectl delete namespace wz-admin
```

**Migrations afterwards.** DEV (`migrations.mode: app`): core and BFF run Flyway at start-up. UAT and PROD (`job`): the
pre-upgrade Job `wellnessz-migrations` runs Flyway with the `wellnessz-migrator@` identity, using the mirrored Flyway
image (section 9). Migrations are forward-only; a `helm rollback` does not revert them.

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
crane copy flyway/flyway:11.20.3-alpine $REGION-docker.pkg.dev/$PROJECT/wellnessz/flyway/flyway:11.20.3-alpine
```

Do this in each environment's registry (UAT and PROD need it for the migration Job; DEV for parity). The UAT and PROD
overlays point `migrations.job.image.fullName` at `<region>-docker.pkg.dev/<env-project-id>/wellnessz/flyway/flyway:11.20.3-alpine`.
A client migration image (`customisation.migrations.image`) is **not** copied or attested by `deploy-env.yml` (it
handles the six product images only): the client pipeline must sign it, copy it by digest into the environment
registry and create the Binary Authorization attestation for UAT and PROD.

## 10. DNS and certificates

**[M]** per environment – hosts `employee`, `manager`, `oh`, `partners`, `api` under `.dev.<domain>`, `.uat.<domain>`
or `<domain>` (PROD), as in `ingress.hosts` of `environments/gcp/<env>.yaml`.

1. Create A records for the five hosts of the environment pointing to that project's `ingress_static_ip`.
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

## 11. GitHub configuration: Environments, Workload Identity Federation, required reviewers

Terraform created per environment the pool `github-<env>`, the provider `github` (attribute condition on the
repository and on the exact token subjects of `github_environments` / `github_image_publish_branches`, GitHub's default
subject format) and the deployer service account `github-deployer-<env>@`, which only the GitHub Environment `gcp-<env>`
may impersonate (`github_environments`); in DEV the `main` branch may also push images (`github_image_publish_branches`).
Roles: `container.developer`, Artifact Registry writer on the repository, the Binary Authorization attestation roles and
`cloudkms.signerVerifier` on the attestor key.

**[M] GitHub Environments – cannot be set from code** (*Settings → Environments*; screenshot as SOC 2 CC8.1 evidence):

| Environment | Branches / tags | Required reviewers | Other |
|---|---|---|---|
| `gcp-dev` | `main` + tags `v*` | none | – |
| `gcp-uat` | tags `v*` | **yes** (QA / business lead) | prevent self-review |
| `gcp-prod` | tags `v*` | **yes** (CAB / security, two people recommended) | prevent self-review, optional wait timer, no admin bypass |

**Environment variables** (from the `terraform output` of the same environment):

| Variable | Value |
|---|---|
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | `github_workload_identity_provider` |
| `GCP_SERVICE_ACCOUNT` | `github_deployer_service_account` |
| `GCP_PROJECT_ID` | the environment's project |
| `GKE_CLUSTER`, `GKE_LOCATION` | `gke_cluster_name`, `gke_location` |
| `BINAUTHZ_ATTESTOR`, `BINAUTHZ_KMS_KEY_VERSION` | `binauthz_attestor`, `binauthz_kms_key_version` |
| `IMAGE_REGISTRY` | `artifact_registry` |
| `HELM_RELEASE`, `K8S_NAMESPACE` | `wellnessz` / `wellnessz` (defaults) |
| `SMOKE_URLS`, `EXTRA_VALUES_FILES` | optional, as in the Azure guide, section 10.2 |

```bash
gh variable set GCP_WORKLOAD_IDENTITY_PROVIDER --env gcp-$ENV --body "$(jq -r .github_workload_identity_provider.value /tmp/wz-gcp-$ENV-outputs.json)"
```

**Repository variables:** `DEPLOY_CLOUDS` (`["gcp"]` or `["azure","gcp"]`), `RELEASE_REGISTRY` (default
`ghcr.io/<owner>/wellnessz`), optional `DEPLOY_RUNNER`; secret `NVD_API_KEY`. Only if `container.yml` should publish
straight to the DEV Artifact Registry (manual run with `registry = gar`): repository-level `GAR_LOCATION`,
`GCP_PROJECT_ID`, `GCP_WORKLOAD_IDENTITY_PROVIDER`, `GCP_SERVICE_ACCOUNT`, `BINAUTHZ_ATTESTOR`,
`BINAUTHZ_KMS_KEY_VERSION` of DEV. Branch protection on `main` is a manual repository setting as well. The commands to
create the federation by hand (existing project) are in the CI/CD Guide, section 6.3.

## 12. Per-environment values

The chart is layered `values.yaml` → `values-gcp.yaml` → `environments/gcp/<env>.yaml`. The overlay already holds
everything that differs per environment (replicas, PDBs, hosts, `migrations.mode`, `networkPolicy.database.cidrs`
= `psa_range`, log level, trace sampling, `WZ_MFA_REQUIRED_ROLES`, `global.requireImageDigests`). **[A]** Replace its
`<placeholders>` with the values of the same environment and commit through a PR (identifiers only):

| Placeholder | Source |
|---|---|
| `<region>`, `<env-project-id>` | tfvars / `$PROJECT` |
| Workload identity service accounts | `workload_service_accounts` |
| `<env-cloudsql-private-ip>` | `cloudsql_private_ip` (three URLs: core, BFF, job) |
| `<tenant-id>`, `<wellnessz-api-client-id>`, `<bff-client-id>`, `<ehs-mailbox-app-client-id>` | Entra ID |
| `gcp.backendConfig.securityPolicy` | `cloud_armor_policy_name` |
| `gcp.frontendConfig.sslPolicy` | `ssl_policy_name` |
| `networkPolicy.database.cidrs` | `psa_cidr` |
| Hostnames | Replace `wellnessz.example.com` with your domain in `ingress.hosts.*` (incl. `partnerPortal`), `ingress.api.host`, `WZ_CORS_ORIGINS`, the four `WZ_*_URL` links and `WZ_BFF_POST_LOGOUT_URI` |
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

**Increment 5** adds no required value. Optional `core.extraEnv` job schedules
(`WELLNESSZ_TRAVELHEALTH_EXPOSURETIMERCRON`, `WELLNESSZ_TRAVELHEALTH_PUBLICHEALTHCRON`,
`WELLNESSZ_TRAVELHEALTH_DAILYCRON`, `WELLNESSZ_MENTALHEALTH_CISMFOLLOWUPSCRON`, `WELLNESSZ_MENTALHEALTH_PEERCALLBACKPURGECRON`);
keep `WZ_ANALYTICS_MIN_GROUP` at `5`. After the first deployment set the E18 keys of the Azure guide, section 11
(increment 5) – above all **`mentalhealth.named-clinicians`**, which Flyway V17 sets to the demo user `psych1` in every
environment and which must list the named clinicians in UAT and PROD, and `substance.laboratory-code` matching the
TOXLAB client's `org_scope`; testing policies and clinical thresholds need sign-off before PROD.

Validate all three overlays (as the CI job *IaC & config checks* does):

```bash
D=sha256:$(printf '0%.0s' $(seq 64)); for e in dev uat prod; do helm lint deploy/helm/wellnessz -f deploy/helm/wellnessz/values.yaml -f deploy/helm/wellnessz/values-gcp.yaml -f deploy/helm/wellnessz/environments/gcp/$e.yaml --set-string core.image.digest=$D --set-string bff.image.digest=$D --set-string employeePortal.image.digest=$D --set-string managerWorkspace.image.digest=$D --set-string ohWorkspace.image.digest=$D --set-string partnerPortal.image.digest=$D; done
```

Never add `classpath:db/demo` to `WZ_FLYWAY_LOCATIONS` outside local development. Load the OH staff roster
(`masterdata.staff_member`) with a reviewed SQL script and push HRIS employees to `POST /api/v1/masterdata/employees`.

## 13. Build, attest and deploy

1. **Images – built once [A].** Every push to `main` runs `container.yml`: build, Trivy gate, push to the release
   registry, SBOM and provenance attestations, cosign signature (`sha-<12>`). Release tags are not rebuilt.
2. **First deployment [A].**
   * **DEV** – automatic after `container.yml` on `main` (`cd.yml` → `deploy-env.yml` for `gcp-dev` when
     `DEPLOY_CLOUDS` contains `gcp`), or by hand: `gh workflow run deploy-env.yml -f cloud=gcp -f environment=dev -f image_tag=sha-<12>`.
   * **UAT** – push a release-candidate tag (`git tag -s v1.0.0-rc.1 … && git push origin v1.0.0-rc.1`); after DEV the
     run waits for the `gcp-uat` reviewers **[M]**.
   * **PROD** – push the release tag `v1.0.0`; DEV → UAT → PROD with approvals **[M]**; or
     `gh workflow run cd.yml -f cloud=gcp -f start_at=prod -f image_tag=v1.0.0` for an approved release.

   Each stage verifies the cosign signatures, copies the images **by digest** into the environment's Artifact Registry
   (`cosign copy`), **creates the Binary Authorization attestation** with the environment's KMS key, runs
   `helm upgrade --install --atomic` with the layered values and all six digests, runs the smoke tests and rolls back
   automatically on failure. The GKE control plane is reached through the DNS-based endpoint.
3. **Operator path.** `deploy/scripts/helm-release.sh` with `CLOUD=gcp DEPLOY_ENV=$ENV RELEASE=wellnessz
   NAMESPACE=wellnessz IMAGE_REGISTRY=<artifact_registry>` and a `digests.txt` (Azure guide, section 12.3). With
   Binary Authorization enforced (UAT, PROD) the images must already be attested – use the workflow unless in an
   incident.
4. **Verify the attestation and the result:**

   ```bash
   gcloud container binauthz attestations list --attestor=wellnessz-ci --attestor-project=$PROJECT --format="value(resourceUri)" | head
   ```

   ```bash
   kubectl -n wellnessz get pods,ingress,backendconfig,managedcertificate,secretproviderclass
   ```

   Expected: all pods `Running` (including `wellnessz-oh-workspace` and `wellnessz-partner-portal`); the Ingress has the
   static IP; the BackendConfigs reference `wellnessz-waf`; in UAT/PROD the migration Job completed.
5. **Rollback.** Automatic after failed smoke tests; by hand `deploy/scripts/helm-release.sh rollback <revision>` or
   `helm rollback wellnessz <revision> -n wellnessz`. Database migrations are forward-only (expand/contract), so the
   previous release keeps working; data problems are handled with point-in-time restore (section 17).

DEV keeps Binary Authorization in dry-run (`DRYRUN_AUDIT_LOG_ONLY`) so that branch builds can be tried; check the
`cloudaudit` log for would-be denials before the first UAT deployment – UAT and PROD are enforced from the start.

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
* **Which checks where:** run everything in DEV; in UAT use named test accounts (no demo data); in PROD only the
  non-destructive checks with dedicated MFA-enrolled smoke-test accounts (Azure guide, section 14, introduction).
* **End-to-end:** use `WZ_E2E_*` as in the Azure guide (DEV only).
* **Increment 1:** run checks 12–17 of the Azure guide, section 14: OH workspace host and login, E01/E02
  `IMPLEMENTED` in `GET /api/v1/modules`, new roles in tokens, EHS webhook `202`, status-only crew API, and
  `tools/e2e/e2e-increment1.js` with `WZ_E2E_OH_URL=https://oh.<domain>` in DEV. Cloud Armor must allow webhook
  bodies of up to 500 incidents; check `jsonPayload.enforcedSecurityPolicy` if the call returns `403`.
* **Increment 2:** run checks 18–24 of the Azure guide, section 14: new schemas (`\dn` from the bootstrap pod), E03 /
  E05 `IMPLEMENTED`, DPO segregation, break-glass alert, document generation, gateway pull queue (`200` with a
  gateway token, `403` with a user token) and `tools/e2e/e2e-increment2.js` (25 `PASS` lines) in DEV. Cloud
  Armor must accept HL7 v2 ER7 bodies (`text/plain`) on `/api/v1/integration/hl7v2` and multipart uploads of up to
  10 MB on `/api/v1/documents/scan`; check `jsonPayload.enforcedSecurityPolicy` for false positives on the
  preconfigured CRS rules.
* **Increment 3:** run checks 25–32 of the Azure guide, section 14: new schemas, E06 / E07 / E14 `IMPLEMENTED`, new
  roles in tokens, scheduler segregation (`403` on `/api/v1/absence/queue`), certificate upload through the BFF,
  calibration block, cold-chain gateway call and `tools/e2e/e2e-increment3.js` (32 `PASS` lines) in DEV. Cloud
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
* **Increment 5:** run checks 43–50 of the Azure guide, section 14: new schemas and E09–E12 `IMPLEMENTED`, new roles in
  tokens, restricted clinical class (`404` and placeholder for a general clinician, the named psych clinician opens the
  case), programme manager without results (`403` on the MRO queue), laboratory binding (TOXLAB client `200`, other
  clients `404`), EAP partner with MFA and attendance-only feedback (`422 field_not_allowed` for free text),
  duty-assignment feed and `tools/e2e/e2e-increment5.js` (24 `PASS` lines) on a fresh DEV database. Cloud Armor must
  accept JSON batches on `/api/v1/integration/duty-assignments` and `/api/v1/integration/testing/lab-results` and image
  uploads (ergonomic photos) on the ergonomics endpoints; check `jsonPayload.enforcedSecurityPolicy` for false
  positives.

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
| `terraform plan` shows the wrong project or wants to replace everything | Initialised against another environment's state or `local.tfvars` | `terraform init -reconfigure -backend-config=environments/backend-$ENV.hcl` and the matching `$ENV.local.tfvars` |
| `google-github-actions/auth` fails: permission `iam.serviceAccounts.getAccessToken` denied | GitHub Environment name differs from `github_environments` (`gcp-<env>`) | Use exactly `gcp-dev` / `gcp-uat` / `gcp-prod` |
| UAT/PROD pods denied by Binary Authorization after a manual `helm` install | Images copied without attestation | Deploy through `deploy-env.yml`, which creates the attestation |
| Client migration Job denied by Binary Authorization | Client image not attested (`deploy-env.yml` handles the six product images only) | Attest it in the client pipeline (section 9) |
| `deploy-env.yml` stops at the policy check / waits for approval | Wrong tag for the stage, or reviewers have not approved | DEV `sha-*`/`v*`, UAT `vX.Y.Z[-rc.N]`, PROD `vX.Y.Z`; approve in the Actions run |
| General OH users cannot open a support case / test result | Intended: restricted clinical class | Named `PSYCH_CLINICIAN` (listed in `mentalhealth.named-clinicians`) / `MRO` only |
| The named psych clinician gets `404` on support cases in UAT/PROD | `mentalhealth.named-clinicians` still holds the demo value `psych1` from V17 | Set the real usernames (section 12) |
| TOXLAB results answer `404` | Laboratory client without `org_scope=/PARTNERS/<code>` or code ≠ `substance.laboratory-code` | Azure guide, sections 5.6 and 11 |

## 17. PROD go-live checklist

Use the checklist of the Azure guide, section 17, with these GCP equivalents. UAT runs it as a rehearsal.

| # | Item | GCP specifics / evidence |
|---|---|---|
| 1 | CAB approval; the same digests passed UAT acceptance [M] | CAB record, UAT report |
| 2 | `terraform plan` for PROD clean; `deletion_protection = true`; `prevent_destroy` intact | Plan output |
| 3 | Backups: 30 automated backups, PITR 7 days – `gcloud sql backups list --instance=sql-wellnessz-prod`; add a cross-region backup location if region loss must be covered | Command output |
| 4 | [M] DR test: PITR clone `gcloud sql instances clone sql-wellnessz-prod sql-wellnessz-prod-drtest --point-in-time=<UTC>`; scratch core against it; `GET /api/v1/audit/verify`; measured RTO/RPO; delete the clone | DR report (SOC 2 A1.3) |
| 5 | [M] HA: `gcloud sql instances failover sql-wellnessz-prod` in a maintenance window; application stays available | Test log |
| 6 | [M] External penetration test of the internet-facing hosts; findings fixed or risk-accepted | Report |
| 7 | Binary Authorization `ENFORCED_BLOCK_AND_AUDIT_LOG`; an unsigned pod is denied (section 14) | Policy export |
| 8 | Cloud Armor prevention + adaptive protection; exclusions ticketed; internal-host rule active | Policy export |
| 9 | [M] Monitoring alerts (doc 04, Section 12) in Cloud Monitoring with an on-call channel, including `exposure-timers` / `public-health-deadlines` not run and break-glass on restricted records | Alert policy list, test notification |
| 10 | Logs: `_Default` retention 365 days, Data Access audit logs on; no PHI in clear text | Query output |
| 11 | [M] Identity: PROD registrations; MFA enforced (not report-only) for partner, adjuster and EAP users; MFA enrolment completed for every external account | IdP export, sign-in logs |
| 12 | [M] Access review: groups (especially psych clinicians, MROs, admins), project IAM (Owner removed from people), Secret Manager accessors, `gcp-prod` reviewers | Review export |
| 13 | [M] Key ceremony record for the three keys; client secret expiry ≤ 6 months | Ceremony record |
| 14 | **`mentalhealth.named-clinicians`** changed from the demo value `psych1` (Flyway V17) to the named PROD clinicians; no demo data; staff roster, HRIS employees and contracted partners (EAP, TOXLAB, public-health authority) loaded | Config export, SQL check |
| 15 | [M] Clinical governance sign-off (screening bands, testing policies, travel recommendations, PEP timers); DPIA updated for increment 5 | Minutes, DPIA |
| 16 | Runbooks, on-call rota, GitHub Environment protection screenshots | Links |

## 18. Teardown (DEV/UAT only)

```bash
helm uninstall wellnessz -n wellnessz
```

Before destroying:

* set `deletion_protection = false` on the cluster and the Cloud SQL instance;
* remove the `prevent_destroy` blocks (Cloud SQL, KMS keys) on a throwaway branch.

```bash
terraform init -reconfigure -backend-config=environments/backend-$ENV.hcl && terraform destroy -var-file=environments/$ENV.tfvars -var-file=environments/$ENV.local.tfvars
```

Never destroy PROD without a decommissioning decision and a final backup export.

Cloud KMS keys cannot be deleted. Their versions are scheduled for destruction (30 days) and the key ring remains,
which is expected. Secrets and audit logs follow their retention settings.
