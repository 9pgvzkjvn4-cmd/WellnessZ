# WellnessZ – Deployment, Infrastructure & CI/CD

Everything needed to run WellnessZ locally, package it, provision Azure / GCP /
hybrid infrastructure and deliver it through a gated pipeline. Contract:
[`docs/00-design-brief.md`](../docs/00-design-brief.md).

Step-by-step guides: [`docs/deployment/Deployment-Architecture.md`](../docs/deployment/Deployment-Architecture.md),
[`docs/setup/`](../docs/setup/) (Local MacBook Pro, Azure, GCP, Hybrid) and
[`docs/cicd/CI-CD-Guide.md`](../docs/cicd/CI-CD-Guide.md); rendered PDF/DOCX in `docs/dist/`.

| Path | What it is |
|---|---|
| [`local/`](local/) | Docker Compose dev stack: PostgreSQL 16, Keycloak 26 (realm import), Mailpit, GreenMail IMAP, OTel collector, optional core + bff |
| [`local/keycloak/wellnessz-realm.json`](local/keycloak/wellnessz-realm.json) | Realm `wellnessz`: roles (incl. the nine increment-1 roles, the increment-2 role `DPO`, the increment-3 roles `CLINIC_SCHEDULER`, `BIOMEDICAL`, `CREW_LICENSING`, `CANDIDATE` and the increment-4 roles `CLAIMS_COORDINATOR`, `TPA_ADJUSTER`, `LEGAL_COUNSEL`, `FINANCE`, `PROCUREMENT`), clients (bff, mobile, api, integration; bff redirects for the four dev servers 5173–5176), test users |
| [`docker/`](docker/) | `Dockerfile.core`, `Dockerfile.bff` (+ per-Dockerfile `.dockerignore`) |
| [`helm/wellnessz/`](helm/wellnessz/) | Helm chart (core, bff, employee-portal, manager-workspace, oh-workspace, partner-portal) + `values-{local,azure,gcp,hybrid}.yaml`, `values.schema.json` |
| [`terraform/azure/`](terraform/azure/) | AKS, PostgreSQL Flexible Server, Key Vault, ACR, App Gateway WAF v2 / Front Door, Log Analytics, identities |
| [`terraform/gcp/`](terraform/gcp/) | GKE (Autopilot/Standard), Cloud SQL, Secret Manager, Artifact Registry, Cloud Armor, KMS, Binary Authorization |
| [`hybrid/`](hybrid/) | On-prem core + CloudNativePG, cloud edge values, External Secrets / Vault – see [hybrid/README.md](hybrid/README.md) |
| [`sql/bootstrap-managed-postgres.sql`](sql/bootstrap-managed-postgres.sql) | One-time, idempotent role/schema bootstrap for managed PostgreSQL |
| [`../.github/workflows/`](../.github/workflows/) | `ci.yml`, `codeql.yml`, `container.yml`, `cd.yml` (+ reusable `deploy-env.yml`) |
| [`../.github/dependabot.yml`](../.github/dependabot.yml), `CODEOWNERS`, `pull_request_template.md` | Supply-chain hygiene and review controls |
| [`../.gitleaks.toml`](../.gitleaks.toml), [`../.trivyignore`](../.trivyignore) | Secret-scan allowlist (placeholders only) and vulnerability exception register |

---

## 1. Local development (`deploy/local`)

```bash
cd deploy/local
./up.sh            # infra only; generates .env (mode 600) with random local secrets on first run
./up.sh --apps     # + builds and runs core (8080/8081) and bff (8090/8091) from deploy/docker
./down.sh          # stop (keeps data)      ./down.sh --purge   # stop and wipe the database volume
```

| Service | Endpoint | Notes |
|---|---|---|
| PostgreSQL 16.15 | `localhost:5432` | dbs `wellnessz` + `keycloak`; SCRAM only; DDL/connection logging |
| Keycloak 26.8 | <http://localhost:8180> realm `wellnessz` | dev mode (HTTP) – admin creds in `.env` |
| Mailpit | UI <http://localhost:8025>, SMTP `1025` | notification sink |
| GreenMail | IMAP `3143`, SMTP `3025` | mailbox `ehs-feed@wellnessz.local` (EHS feed) |
| OTel collector | OTLP `4317`/`4318`, health `13133` | `debug` exporter (successor of `logging`) |

All ports bind to `127.0.0.1`. Containers run with `no-new-privileges`; the app
containers additionally run read-only with `cap_drop: ALL` and a `/tmp` tmpfs.

**Database roles** (created by `postgres/01-init-wellnessz.sh`):

| Role | Purpose | Privileges |
|---|---|---|
| `wz_migrator` | Flyway only | owns `wellnessz` and schemas `platform, masterdata, intake, casemgmt, triage, docs, ohrecord, fitness, absence, surveillance, scheduling, treatment, claims, analytics` (+ `bff` created by BFF migrations; `triage` since increment 1; `docs`, `ohrecord`, `fitness` since increment 2; `absence`, `surveillance`, `scheduling` since increment 3; `treatment`, `claims` since increment 4) |
| `wz_app` | core runtime | `CONNECT` + schema `USAGE`; **table privileges are granted only by the Flyway migrations** (e.g. `platform.audit_event` is SELECT/INSERT = append-only) |
| `wz_bff` | BFF session store | `CONNECT`; DML on `bff.spring_session*` via BFF migrations; no access to clinical schemas |
| `keycloak` | IdP | owns the separate `keycloak` database |

**Keycloak realm**: clients `wellnessz-bff` (confidential, PKCE S256, secret
`change-me-local-only`), `wellnessz-mobile` (public, PKCE S256), `wellnessz-api`
(audience only, added to access tokens of bff/mobile/integration via audience
mappers), `wellnessz-integration` (client-credentials, role `INTEGRATION`).
Least-privilege scope mappings (no full-scope – every role a token may carry,
including the increment-1 roles, `DPO`, the four increment-3 roles and the five increment-4 roles, must be listed in the scope mappings of
`wellnessz-bff` and `wellnessz-mobile`), direct-access grants disabled,
brute-force detection (5 failures, up to 15 min lockout), password policy
(≥12 chars, mixed classes, history 5, not username/email), access token 5 min,
SSO idle 15 min, refresh-token rotation, login + admin events enabled.
Test users `employee1, manager1, hr1, clinician1, psych1, auditor1, admin1` and
(increment 1) `nurse1, nurse2, physician1, ame1, cmo1, nursinghead1, hse1,
crewsched1, hygiene1, reception1, firstaider1` and (increment 2) `dpo1` (DPO) and
`partner1` (PARTNER_PROVIDER, `org_scope=/PARTNERS/CITYHOSP`) and (increment 3) `scheduler1`
(CLINIC_SCHEDULER), `biomed1` (BIOMEDICAL), `crewlicensing1` (CREW_LICENSING, `org_scope=/AIRLINE/OPS`) and
`candidate1` (CANDIDATE, `employee_id=C9001`, `org_scope=/CANDIDATES`) and (increment 4) `claims1`
(CLAIMS_COORDINATOR), `adjuster1` (TPA_ADJUSTER, `org_scope=/PARTNERS/GULFTPA`), `legal1` (LEGAL_COUNSEL), `finance1`
(FINANCE), `procurement1` (PROCUREMENT) and `physio1` (PARTNER_PROVIDER, `org_scope=/PARTNERS/PHYSIOPLUS`) use the temporary password
`ChangeMe!123` and must change it on first login. The realm is imported only
when it does not exist: after a realm change run `./down.sh --purge` and `./up.sh`.
The BFF client allows redirects, post-logout redirects and web origins for the dev servers on 5173, 5174, 5175
(OH workspace) and 5176 (partner portal, increment 4). The demo realm has no OTP enrolment, so the core's `local`
profile switches the MFA gate for `PARTNER_PROVIDER` / `TPA_ADJUSTER` off (`WZ_MFA_REQUIRED_ROLES` empty); every other
profile requires `amr` / `acr` proof in the token. The Compose core service lists all four dev origins in
`WZ_CORS_ORIGINS`.

**Issuer inside containers**: tokens carry `iss=http://localhost:8180/realms/wellnessz`.
In the `apps` profile a tiny socat container (`apps-net`) owns the network
namespace of core and bff and forwards its loopback `:8180` to Keycloak, so the
same issuer URL works in the browser and inside the containers.

Generate key material yourself with `openssl rand -base64 32` (`WZ_MASTER_KEY`,
`WZ_PSEUDONYM_KEY`, `WZ_BFF_MASTER_KEY`) – see `.env.example`.

## 2. Container images (`deploy/docker`)

```bash
docker build -f deploy/docker/Dockerfile.core -t wellnessz/wz-core-app:dev .
docker build -f deploy/docker/Dockerfile.bff  -t wellnessz/wz-bff:dev .
```

* Build context = repository root; `Dockerfile.<x>.dockerignore` sends only Maven inputs.
* Multi-stage: `maven:3.9.16-eclipse-temurin-21-noble` → `eclipse-temurin:21.0.12_8-jre-noble`
  (OS packages upgraded at build), Spring Boot layered extraction (`-Djarmode=tools extract --layers --launcher`).
* Non-root `10001:10001`, application files root-owned/read-only, only `/tmp` writable
  (`readOnlyRootFilesystem`), `JAVA_TOOL_OPTIONS` with `MaxRAMPercentage=75`, `ExitOnOutOfMemoryError`, UTC.
* Web images are built from `web/<app>/Dockerfile` (owned by the web team; nginx-unprivileged on 8080):
  `employee-app`, `manager-workspace`, since increment 1 `oh-workspace`
  (`docker build -t wellnessz/oh-workspace:dev web/oh-workspace`) and since increment 4 `partner-portal`
  (`docker build -t wellnessz/partner-portal:dev web/partner-portal`).

## 3. Kubernetes (`deploy/helm/wellnessz`)

```bash
helm upgrade --install wellnessz deploy/helm/wellnessz -n wellnessz --create-namespace \
  -f deploy/helm/wellnessz/values.yaml -f deploy/helm/wellnessz/values-azure.yaml \
  --set global.imageTag=v1.2.3 --atomic --wait
```

| Control | Implementation |
|---|---|
| Pod hardening | `runAsNonRoot`, uid 10001 (web: 101), `readOnlyRootFilesystem`, drop `ALL` caps, `seccompProfile: RuntimeDefault`, no SA token automount, `enableServiceLinks: false` – enforced by `values.schema.json` |
| Availability | Rolling updates (`maxUnavailable: 0`), startup/liveness/readiness on management port `/actuator/health/{liveness,readiness}`, HPA (CPU+memory), PDB, zone + host topology spread, preStop drain |
| Network | Default-deny (namespace), DNS allow, ingress → web/bff, bff → core, core/bff → DB, 443 egress to IdP only (RFC1918 excluded), optional OTel; peers are fail-closed (empty peer lists are never rendered) |
| Secrets | Secrets Store CSI (`secrets-store.csi.x-k8s.io/v1`): Azure Key Vault, GCP Secret Manager (GKE add-on), or Vault; one `SecretProviderClass` per identity; files under `/mnt/secrets/<component>/`, consumed via `file:` refs / `WZ_MASTER_KEY_FILE`; no K8s Secret sync by default |
| Identity | One ServiceAccount per component with workload identity annotations (`azure.workload.identity/client-id`, `iam.gke.io/gcp-service-account`) |
| Migrations | `migrations.mode: app` (core/bff run Flyway as `wz_migrator`) or `job` (pre-install/upgrade Flyway Job with its own identity; runtime pods never hold the migrator secret – prod default) |
| Ingress | Same-origin routing (`/bff`, `/oauth2`, `/login`, `/logout` → bff; `/` → SPA), API host for mobile/M2M, TLS; AGIC (Azure), GCE + BackendConfig/Cloud Armor/FrontendConfig/ManagedCertificate (GCP), OpenShift router (hybrid) |
| Observability | Prometheus annotations / optional ServiceMonitor, OTLP endpoint + resource attributes |

Increment 1 adds the component `ohWorkspace` (image `oh-workspace`, host
`ingress.hosts.ohWorkspace`, e.g. `oh.wellnessz.example.com`; disabled on-prem in
`values-hybrid.yaml`, served by the cloud edge) and schema `triage` in
`migrations.job.core.schemas`.

Increment 2 adds no component: schemas `docs`, `ohrecord` and `fitness` are added to
`migrations.job.core.schemas`, `postgres/01-init-wellnessz.sh`, `sql/bootstrap-managed-postgres.sql` and
`hybrid/cnpg/03-cluster.yaml`. New core settings (`WZ_EMPLOYEE_PORTAL_URL`, `WZ_MANAGER_WORKSPACE_URL`,
`WZ_DPO_EMAIL`, optional `WZ_DOCUMENTS_FONT_PATH` and OCR) go into `core.env` / `core.extraEnv`; a font or Tesseract
needs a derived core image because the chart has no extra-volume hook. Gateways (laboratory, devices, crew rostering)
call `/api/v1/integration/**` on the API host with `INTEGRATION` client-credentials tokens – see
[`docs/deployment/Deployment-Architecture.md`](../docs/deployment/Deployment-Architecture.md), Section 18.

Increment 3 adds no component either: schemas `absence`, `surveillance` and `scheduling` are added to the same four
places (and `search_path` of `wz_app` / `wz_migrator`), so the list then read
`platform,masterdata,intake,casemgmt,triage,docs,ohrecord,fitness,absence,surveillance,scheduling,analytics` (extended
by increment 4, below). The new jobs (absence feed / triggers / LTA, surveillance due list, crew certificates, pre-employment purge, appointment
reminders and no-shows, equipment calibration) run inside the core and are guarded once per run across replicas.
New machine endpoints on the API host (`INTEGRATION` role): `/api/v1/integration/absence/{episodes,certificates}`,
`/api/v1/integration/ih-measurements`, `/api/v1/integration/cosmic-dose`, `/api/v1/integration/pre-employment` and the
IoT fridge readings `/api/v1/integration/cold-chain/readings`. The BFF must run with
`spring.servlet.multipart.enabled=false` (shipped in its `application.yml`) so that browser uploads reach the core
untouched – see Section 19 of the deployment architecture.

Increment 4 adds the component `partnerPortal` (image `partner-portal`, nginx-unprivileged on 8080, `/healthz`, HPA
2–4, PDB; host `ingress.hosts.partnerPortal`, e.g. `partners.wellnessz.example.com`, `partners.localtest.me` locally;
disabled on-prem in `values-hybrid.yaml`, served by the cloud edge) – it is in every component list of the templates
(`_helpers.tpl`, `deployment-web.yaml`, `service.yaml`, `serviceaccount.yaml`, `hpa.yaml`, `pdb.yaml`,
`networkpolicy.yaml`, `ingress.yaml`, `gke.yaml`, `NOTES.txt`). It is the first host intended for users outside the
airline; its users need MFA at the IdP and the core verifies it (`WZ_MFA_REQUIRED_ROLES`, default
`PARTNER_PROVIDER,TPA_ADJUSTER`). Schemas `treatment` and `claims` are added to the same four places, so the list now
reads `platform,masterdata,intake,casemgmt,triage,docs,ohrecord,fitness,absence,surveillance,scheduling,treatment,claims,analytics`.
`values.yaml` sets the notification links `WZ_EMPLOYEE_PORTAL_URL`, `WZ_MANAGER_WORKSPACE_URL`, `WZ_OH_WORKSPACE_URL` and
`WZ_PARTNER_PORTAL_URL` in `core.env` to the `https://<app>.wellnessz.example.com` hosts (keep them in line with
`ingress.hosts`) – override them with your domain; `values-local.yaml` sets the `http://*.localtest.me` equivalents and
adds `http://partners.localtest.me` to `WZ_CORS_ORIGINS`. New machine endpoints on the API host:
`/api/v1/integration/referrals/**` (FHIR R4), `/api/v1/integration/preauthorisations/{ref}/decision`,
`/api/v1/integration/claims/reports/{ref}/acknowledgement` and `/api/v1/integration/telehealth/tokens/redeem` – partner-hospital
and insurer clients need a fixed `org_scope=/PARTNERS/<CODE>` claim (only their own organisation's referrals,
pre-authorisations and reports are served, other clients get `404`); the
outbound destinations `insurer-gateway`, `authority-gateway` and `erp-finance` carry references only – see Section 20 of
the deployment architecture.

Platform files: `values-local.yaml` (kind / minikube / Docker Desktop, secrets
from pre-created K8s Secrets), `values-azure.yaml`, `values-gcp.yaml`,
`values-hybrid.yaml` (on-prem core) and `../hybrid/values-hybrid-cloud-edge.yaml`.
Placeholders in `<angle brackets>` come from `terraform output`.

> The community **ingress-nginx** project was retired in March 2026; defaults
> are controller-neutral and the local overlay is the only one using it.

## 4. Infrastructure as code

```bash
cd deploy/terraform/azure        # or gcp
cp backend.hcl.example backend.hcl && cp terraform.tfvars.example terraform.tfvars
terraform init -backend-config=backend.hcl
terraform plan -out tfplan && terraform apply tfplan
```

**Azure** (`azurerm ~> 4.81`): VNet with subnets (AKS, App Gateway, delegated
PostgreSQL, private endpoints) + NSGs and private DNS; AKS (Azure CNI Overlay +
Cilium policy, Entra ID-only with Azure RBAC, local accounts disabled, OIDC
issuer + workload identity, Azure Policy, Defender, Key Vault CSI with rotation,
Container Insights, optional private API server, host encryption, optional CMK
disk encryption set, auto-upgrade channels); PostgreSQL 16 Flexible Server
(private access, zone-redundant HA, geo-redundant backups 35 days, Entra auth,
`require_secure_transport`, TLS ≥ 1.2, pgaudit, optional CMK); Key Vault
(Premium, RBAC, purge protection, private endpoint, per-secret role assignments);
ACR Premium (no admin, zone-redundant, private endpoint); App Gateway WAF v2
(DRS 2.1 = successor of OWASP CRS 3.2 + Bot Manager, Prevention mode, TLS
policy 2022-S) and optional Front Door Premium + WAF; Log Analytics (365 days)
with diagnostic settings on every resource; user-assigned identities with
federated credentials for core / bff / migrations service accounts; optional
GitHub OIDC deployer identity. DB passwords are generated **ephemerally** and
written with **write-only** arguments (never in state).
*Note*: azurerm 5.x exists; a trial `validate` against 5.11 showed only renamed
arguments (`azurerm_private_dns_zone_virtual_network_link`, Log Analytics
internet flags) – upgrade in a dedicated change.

**GCP** (`google ~> 8.9`): VPC + subnet (flow logs, Private Google Access),
Cloud NAT, Private Service Access; GKE Autopilot (or Standard via
`gke_autopilot=false`) private nodes, Workload Identity, Dataplane V2, Binary
Authorization (KMS-backed attestor, dry-run → enforce), Shielded nodes,
CMEK-encrypted etcd secrets, Secret Manager CSI add-on, security posture;
Cloud SQL Postgres 16 (private IP only, REGIONAL HA, PITR, IAM auth,
`ENCRYPTED_ONLY`, pgaudit, CMEK, password policy); Secret Manager (CMEK,
per-secret IAM); Artifact Registry (CMEK, immutable tags, cleanup); Cloud Armor
(preconfigured CRS 3.3 WAF rules, login rate-limit, adaptive protection);
SSL policy RESTRICTED/TLS1.2; Data Access audit logs and 365-day log retention;
GitHub Workload Identity Federation.

### Bootstrap secrets & database (once per environment)

1. Replace placeholder key material (never via Terraform):
   ```bash
   # Azure
   for s in wz-master-key wz-pseudonym-key wz-bff-session-key; do
     openssl rand -base64 32 | tr -d '\n' | az keyvault secret set --vault-name <kv> -n $s --file /dev/stdin >/dev/null; done
   az keyvault secret set --vault-name <kv> -n wz-bff-client-secret --value '<from IdP app registration>'
   # wz-graph-client-secret likewise when WZ_EHS_MAILBOX_MODE=graph
   # GCP
   for s in wz-master-key wz-pseudonym-key wz-bff-session-key; do
     openssl rand -base64 32 | tr -d '\n' | gcloud secrets versions add $s --data-file=-; done
   ```
2. Run [`sql/bootstrap-managed-postgres.sql`](sql/bootstrap-managed-postgres.sql)
   as the server admin (instructions in the file). It creates/locks down
   `wz_migrator`, `wz_app`, `wz_bff`, hands ownership to `wz_migrator` and – on
   Cloud SQL – revokes `cloudsqlsuperuser` from the application roles.
3. Deploy with Helm; Flyway applies the schema and the least-privilege grants.

Rotation: bump `db_password_version` (Terraform) → new KV/Secret Manager
version + `ALTER ROLE … PASSWORD` (step 2 re-run on Azure; automatic on GCP);
the CSI driver picks up the new file (Azure rotation every 2 min).

## 5. CI/CD (`.github/workflows`)

| Workflow | Trigger | What it does |
|---|---|---|
| `ci.yml` | PR, push `main` | Java 21 `./mvnw verify` (JaCoCo, Surefire reports, CycloneDX SBOM) + SpotBugs/FindSecBugs (`-Psecurity`); web lint/test/build/`npm audit`/CycloneDX for the four apps (incl. `partner-portal`, increment 4); gitleaks (full history); OWASP dependency-check (fail CVSS ≥ 7, `NVD_API_KEY`, SARIF to code scanning); dependency review on PRs; Helm lint/template, `terraform fmt`, Trivy IaC scan |
| `codeql.yml` | PR, push, weekly | CodeQL `java-kotlin` + `javascript-typescript`, `security-extended` |
| `container.yml` | push `main`/tags, dispatch, `workflow_call` | Build 6 images (incl. `oh-workspace` and `partner-portal`) → Trivy gate (CRITICAL/HIGH, fixed) → push to GHCR / ACR / Artifact Registry (OIDC) → SBOM + SLSA provenance attestations → cosign keyless signature → Binary Authorization attestation (GAR) |
| `cd.yml` (+ `deploy-env.yml`) | tag `v*`, dispatch | dev → test → prod through GitHub Environments; cosign verification of every image before deploy; Azure (`azure/login` OIDC + kubelogin + AKS context or `az aks command invoke` for private clusters) or GCP (WIF + `get-gke-credentials`); `helm upgrade --install --atomic --wait` |

All actions are pinned to full commit SHAs (version in comment), every workflow
and job declares least-privilege `permissions`, checkouts use
`persist-credentials: false`, and no cloud credentials are stored as secrets
(OIDC federation everywhere). The only repository secret is `NVD_API_KEY`.

**Repository settings to apply** (evidence for SOC 2 CC8.1):

* Branch protection on `main`: PR required, 2 approvals, Code Owner review,
  required checks (`CI`, `CodeQL`), signed commits, linear history, no force push.
* Environments `dev`, `test`, `prod` with variables listed at the top of
  `cd.yml`; **required reviewers** on `test` and `prod` (prod: CAB/security
  group), deployment branch rule `v*` tags for `prod`, optional wait timer.
* Repository variable `IMAGE_REGISTRY_KIND` (`ghcr|acr|gar`) and per-registry
  variables listed in `container.yml`.
* Enable secret scanning + push protection, Dependabot alerts and private
  vulnerability reporting.
* AKS: the GitHub deployer identity gets *AKS RBAC Writer* on the namespace;
  add a namespaced Role allowing `secretproviderclasses.secrets-store.csi.x-k8s.io`
  (built-in AKS roles do not cover CRDs).

## 6. Validation performed for this baseline

See the hand-over report; reproduce with:

```bash
helm lint deploy/helm/wellnessz -f deploy/helm/wellnessz/values.yaml -f deploy/helm/wellnessz/values-<platform>.yaml
helm template wz deploy/helm/wellnessz -f ... | kubeconform -strict -summary -schema-location default \
  -schema-location 'https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'
terraform -chdir=deploy/terraform/azure init -backend=false && terraform -chdir=deploy/terraform/azure validate
terraform fmt -check -recursive deploy/terraform
actionlint .github/workflows/*.yml
trivy config --severity CRITICAL,HIGH deploy
gitleaks dir . --config .gitleaks.toml
```

## 7. SOC 2 control mapping (deployment scope)

| TSC | Evidence in this directory |
|---|---|
| CC6.1 logical access | Entra/Keycloak OIDC, AKS local accounts disabled + Azure RBAC, GKE IAM, per-component workload identities, per-secret IAM, DB role separation |
| CC6.6 boundary protection | WAF (App Gateway / Front Door / Cloud Armor), private clusters/DB/Key Vault, NSGs, default-deny NetworkPolicies |
| CC6.7 encryption in transit | TLS 1.2+ at edge, `require_secure_transport`/`ENCRYPTED_ONLY`, JDBC `verify-full`/`verify-ca`, CNPG TLS 1.3 |
| CC6.1/CC6.7 encryption at rest | Platform encryption + optional CMK (PostgreSQL, AKS disks), CMEK (Cloud SQL, GKE secrets, Artifact Registry, Secret Manager), encrypted backups |
| CC7.1/CC7.2 monitoring | Log Analytics/Cloud Logging 365 days, diagnostic settings, Data Access audit logs, pgaudit, Keycloak events, OTel |
| CC7.1 vulnerability mgmt | Dependabot, OWASP dependency-check, CodeQL, Trivy (images + IaC), Defender / GKE security posture |
| CC8.1 change management | PR template, CODEOWNERS, environments with approvals, signed images + provenance, atomic Helm releases |
| A1.2/A1.3 availability & recovery | Zone-redundant HA, PDB/HPA/topology spread, geo-redundant backups, PITR, CNPG backups + restore drills |
