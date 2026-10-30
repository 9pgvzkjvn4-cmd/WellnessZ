# WellnessZ OHCM – Setup Guide 2: Microsoft Azure

**Version 1.0**

*WellnessZ OHCM platform – increments 1–5*

| | |
|---|---|
| **Document** | 06 – Setup guide: Microsoft Azure (AKS, PostgreSQL Flexible Server, Key Vault, App Gateway WAF) |
| **Version** | 1.0 (final, after increment 5 of 5) |
| **Audience** | Cloud platform team, DevSecOps, identity administrators, DBA, release manager |
| **Result** | Three environments – `azure-dev`, `azure-uat`, `azure-prod` – built from the same Terraform and Helm chart with per-environment variable files, deployed by GitHub Actions with signed images promoted by digest, PROD accepted through the go-live checklist |
| **Time** | DEV: 1 day (Entra ID approvals and DNS usually dominate). UAT: ½ day once DEV works. PROD: ½ day build + the go-live checklist (backup/DR test, penetration test, access review) |
| **Related** | [04 – Deployment Architecture](../deployment/Deployment-Architecture.md) · [`deploy/environments/README.md`](../../deploy/environments/README.md) (authoritative matrix) · 09 – CI/CD Guide · `deploy/terraform/azure` · `deploy/helm/wellnessz/environments/azure/` |

![Azure topology](../images/09-deploy-azure.png)

*Figure 1 – Azure topology (source: `docs/images/09-deploy-azure.svg`).*

> **Validation status.** The Terraform code passes `terraform fmt -check` and `terraform validate` (azurerm 4.81,
> Terraform 1.13.4) with each of `environments/dev.tfvars`, `uat.tfvars` and `prod.tfvars`. The Helm chart renders
> and lints with `values-azure.yaml` + `environments/azure/<env>.yaml` for all three environments (CI job *IaC &
> config checks*). The workflows pass actionlint. **No `terraform apply` was run in a real subscription** while
> preparing this guide; your first DEV build is the acceptance test. Expect minor corrections, for example region
> SKU availability or tenant policy conflicts.
>
> **Increments.** Increments 1–4 added roles, schemas, gateway clients, the OH workspace and the partner portal (host
> `partners.<domain>`, MFA for external users). **Increment 5** needs no new Azure resource: it adds eight app roles
> and groups (section 5.1–5.2), the EAP partner organisation (5.3), the TOXLAB laboratory client, the duty-assignment
> feed and the campaign / wearable clients (5.6), four schemas created by Flyway (8), business configuration (11),
> smoke checks 43–50 (14) and the PROD go-live checklist (17).

## 1. Overview of the steps

**Legend.** **[A]** = automated (Terraform, GitHub Actions, Helm, scripts in the repository).
**[M]** = manual step that cannot be automated from this repository (portal or tenant settings, approvals,
ceremonies) – keep evidence (screenshot, ticket) for SOC 2.

Build **DEV first**, then UAT, then PROD. Every step below is repeated per environment unless marked *once*.

| # | Stage | Who | DEV | UAT | PROD | Section |
|---|---|---|---|---|---|---|
| 1 | Workstation tools (*once*) | Engineer | [M] | – | – | 2 |
| 2 | Landing zone: subscription per environment, providers, quotas, policy | Cloud admin | [M] | [M] | [M] | 3 |
| 3 | Terraform state backend per environment | Cloud admin | [M] storage + [A] `backend-dev.hcl` | same | same | 4 |
| 4 | Entra ID: groups, app registrations per environment, MFA Conditional Access | Identity admin | [M] | [M] | [M] | 5 |
| 5 | `terraform init/plan/apply` with `environments/<env>.tfvars` | Platform team | [A] | [A] | [A] (change ticket) | 6 |
| 6 | Secrets bootstrap (key ceremony) | Two people | [M] | [M] | [M] dual control | 7 |
| 7 | Database bootstrap | DBA | [A] script, run [M] | same | same | 8 |
| 8 | TLS certificates and DNS | Network team | [M] | [M] | [M] | 9 |
| 9 | GitHub Environment `azure-<env>`: variables [A-ready], **required reviewers** [M] | DevSecOps | [M] | [M] reviewers | [M] reviewers + CAB | 10 |
| 10 | Helm overlay `environments/azure/<env>.yaml` with Terraform outputs | Platform team | [A] edit + PR | same | same | 11 |
| 11 | First deployment (`cd.yml` / `deploy-env.yml` / `helm-release.sh`), migrations | CI / release manager | [A] | [A] after approval | [A] after approval | 12 |
| 12 | EHS mailbox, smoke tests, MDM | QA / OH admin | [M] | [M] | [M] | 13–15 |
| 13 | PROD go-live checklist: backups, DR test, pen test, alerts, access review, sign-offs | Service owner, GRC | – | rehearsal | [M] | 17 |

### 1.1 The three Azure environments

| | `azure-dev` | `azure-uat` | `azure-prod` |
|---|---|---|---|
| Purpose / data | Developer integration; **synthetic data only** | Business acceptance on the production architecture; synthetic or masked data | Production; real PHI |
| Terraform | `environments/dev.tfvars` + `dev.local.tfvars` | `environments/uat.tfvars` + `uat.local.tfvars` | `environments/prod.tfvars` + `prod.local.tfvars` |
| State | `environments/backend-dev.hcl` | `environments/backend-uat.hcl` | `environments/backend-prod.hcl` |
| Helm overlay | `environments/azure/dev.yaml` | `environments/azure/uat.yaml` | `environments/azure/prod.yaml` |
| GitHub Environment | `azure-dev` (no reviewers) | `azure-uat` (**required reviewers**) | `azure-prod` (**required reviewers**, CAB) |
| Images | every `main` build (`sha-<12>`) | release candidates `vX.Y.Z-rc.N` by digest | releases `vX.Y.Z` by signed digest |
| Hosts | `employee.dev.<domain>`, `manager.dev.…`, `oh.dev.…`, `partners.dev.…`, `api.dev.…` | `*.uat.<domain>` | `employee.<domain>` … |
| API audience | `api://wellnessz-api-dev` | `api://wellnessz-api-uat` | `api://wellnessz-api` |
| VNet | `10.48.0.0/16` | `10.44.0.0/16` | `10.40.0.0/16` |
| AKS API | public, `aks_authorized_ip_ranges` | private | private |
| PostgreSQL | burstable, no HA, 7 days | GP D2ds_v5, zone-redundant HA, 14 days | GP D4ds_v5, zone-redundant HA, geo-redundant, 35 days |
| WAF | Detection | Prevention | Prevention |
| Flyway | `migrations.mode: app` | pre-upgrade Job | pre-upgrade Job |
| `WZ_MFA_REQUIRED_ROLES` | empty (test identities) | `PARTNER_PROVIDER,TPA_ADJUSTER` | `PARTNER_PROVIDER,TPA_ADJUSTER` |

Throughout this guide `ENV` is `dev`, `uat` or `prod`:

```bash
export ENV=dev
```

## 2. Workstation prerequisites

| Tool | Version | Install (macOS) |
|---|---|---|
| Azure CLI | 2.70+ | `brew install azure-cli` |
| Terraform | ≥ 1.11 (tested 1.13.4) | `brew install terraform` |
| kubectl + kubelogin | 1.33 / 0.2.x | `brew install kubernetes-cli Azure/kubelogin/kubelogin` |
| Helm | 3.19 (Helm 4 also works) | `brew install helm` |
| Docker + buildx | 27+ | see Setup guide 1 |
| cosign | 3.x | `brew install cosign` |
| jq, openssl, psql | current / psql 16 | `brew install jq openssl@3 libpq` |
| PowerShell + ExchangeOnlineManagement | 7.x / 3.x | Only for the EHS mailbox via Graph (section 13) |

Sign in and select the subscription. Use one subscription per environment.

```bash
az login --tenant <tenant-id>
```

```bash
az account set --subscription <subscription-id> && az account show --query "{name:name, id:id, tenant:tenantId}" -o table
```

## 3. Landing zone and subscription preparation

**[M] Landing-zone prerequisites per environment** (from the client's cloud platform team):

| Item | DEV | UAT | PROD |
|---|---|---|---|
| Subscription | non-production subscription (may be shared with other non-prod workloads) | own subscription recommended | **own subscription**, under the production management group |
| Management-group policy | non-prod initiative | production initiative in audit mode | production initiative (deny) – allowed regions, no public IPs except the App Gateway, diagnostic settings |
| Network | VNet `10.48.0.0/16` free and routable to the hub (optional) | `10.44.0.0/16` | `10.40.0.0/16`; hub peering / ExpressRoute for corporate access to private listeners |
| Groups | `sg-wellnessz-aks-admins-dev`, `sg-wellnessz-db-admins-dev` | `…-uat` | `…-prod` (PIM-eligible membership only) |
| Owner for the first apply | engineer with Owner | Owner via PIM | Owner via PIM + change ticket |
| Budget alert | yes | yes | yes |

Register the resource providers in **each** environment subscription (idempotent):

```bash
for p in Microsoft.ContainerService Microsoft.DBforPostgreSQL Microsoft.KeyVault Microsoft.Network Microsoft.OperationalInsights Microsoft.ContainerRegistry Microsoft.Cdn Microsoft.Security Microsoft.ManagedIdentity Microsoft.Insights Microsoft.OperationsManagement Microsoft.PolicyInsights; do az provider register -n $p; done
```

Enable encryption at host. This is required because `aks_host_encryption=true` is the default:

```bash
az feature register --namespace Microsoft.Compute --name EncryptionAtHost
```

```bash
az feature show --namespace Microsoft.Compute --name EncryptionAtHost --query properties.state -o tsv
```

Wait until the output is `Registered`, then run:

```bash
az provider register -n Microsoft.Compute
```

Check the vCPU quota for the D-series v5 family in your region. Minimum per environment (maximum node counts
from the tfvars, plus PostgreSQL): DEV about 16 vCPU, UAT about 30 vCPU, PROD about 60 vCPU.

```bash
az vm list-usage -l westeurope --query "[?contains(name.value,'standardDDSv5Family')]" -o table
```

## 4. Terraform remote state (one backend per environment)

Each environment has its own state, ideally in its own storage account inside its own subscription, with
data-plane access only for that environment's Terraform runner. The repository ships one example per environment:
`deploy/terraform/azure/environments/backend-{dev,uat,prod}.hcl.example` (resource group
`rg-wellnessz-tfstate-<env>`, account `stwztfstate<env>`, container `tfstate`, key `wellnessz/<env>/azure.tfstate`,
`use_azuread_auth = true`).

**[M]** Create the storage account in the environment subscription (storage account names are global – adapt
`stwztfstate$ENV` if taken):

```bash
az group create -n rg-wellnessz-tfstate-$ENV -l westeurope
```

```bash
az storage account create -n stwztfstate$ENV -g rg-wellnessz-tfstate-$ENV -l westeurope --sku Standard_ZRS --kind StorageV2 --min-tls-version TLS1_2 --allow-blob-public-access false --allow-shared-key-access false --require-infrastructure-encryption true --https-only true
```

```bash
az storage account blob-service-properties update -n stwztfstate$ENV -g rg-wellnessz-tfstate-$ENV --enable-versioning true --enable-delete-retention true --delete-retention-days 30 --enable-container-delete-retention true --container-delete-retention-days 30
```

```bash
az storage container create -n tfstate --account-name stwztfstate$ENV --auth-mode login
```

Grant data-plane access only to the engineers or CI identity who run Terraform for **this** environment:

```bash
az role assignment create --assignee <object-id-of-terraform-runner> --role "Storage Blob Data Contributor" --scope $(az storage account show -n stwztfstate$ENV -g rg-wellnessz-tfstate-$ENV --query id -o tsv)
```

PROD only: add a `CanNotDelete` lock on the state resource group:

```bash
az lock create -n do-not-delete --lock-type CanNotDelete -g rg-wellnessz-tfstate-prod
```

**[A]** Create the backend file from the example (the `.hcl` copy is git-ignored):

```bash
cd deploy/terraform/azure && cp environments/backend-$ENV.hcl.example environments/backend-$ENV.hcl
```

Edit `storage_account_name` if you changed it. Expected:
`az storage container list --account-name stwztfstate$ENV --auth-mode login -o table` lists `tfstate`.

## 5. Entra ID configuration

**[M]** Identity configuration is done by an identity administrator in the Entra ID admin centre or with the
commands below; it cannot be applied from this repository.

* **Groups** (section 5.1) are created once per tenant. Use separate membership per environment where test
  identities must not reach PROD – recommended: suffix `-dev` / `-uat` for the groups that hold test users and keep
  the unsuffixed groups for PROD.
* **App registrations are per environment** so that a DEV or UAT token is never accepted by PROD: `wellnessz-api-dev`
  (`api://wellnessz-api-dev`), `wellnessz-api-uat` (`api://wellnessz-api-uat`), `wellnessz-api` (`api://wellnessz-api`,
  PROD); likewise `wellnessz-bff-dev`, `wellnessz-bff-uat`, `wellnessz-bff`. The Helm overlays already expect these
  scopes (`SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_WELLNESSZ_SCOPE` in `environments/azure/<env>.yaml`). The
  commands below use `$API_NAME` and `$BFF_NAME`:

```bash
if [ "$ENV" = prod ]; then API_NAME=wellnessz-api BFF_NAME=wellnessz-bff; else API_NAME=wellnessz-api-$ENV BFF_NAME=wellnessz-bff-$ENV; fi
```

* **MFA** for external users (section 5.3a) is a Conditional Access policy per environment's apps.
* Prefer a separate test tenant for DEV if the client's policy forbids test accounts in the workforce tenant; the
  steps are identical.

### 5.1 Groups (role assignment through groups)

| Group | App role | Typical members |
|---|---|---|
| `sg-wellnessz-employees` | EMPLOYEE | All crew and staff (dynamic group) |
| `sg-wellnessz-managers` | MANAGER | Line managers |
| `sg-wellnessz-hr` | HR | HR partners |
| `sg-wellnessz-oh-clinicians` | OH_CLINICIAN | OH physicians and nurses |
| `sg-wellnessz-case-managers` | CASE_MANAGER | OH case managers |
| `sg-wellnessz-psych` | PSYCH_CLINICIAN | Psychological health clinicians – **named individuals only**; the restricted clinical class of E09 (support cases, screening, urgent call-backs, EAP detail) |
| `sg-wellnessz-oh-admins` | OH_ADMIN | OH service administrators |
| `sg-wellnessz-auditors` | AUDITOR | Internal audit, GRC |
| `sg-wellnessz-platform-admins` | PLATFORM_ADMIN | Platform operators (PIM-eligible only) |
| `sg-wellnessz-partners` | PARTNER_PROVIDER | External providers incl. EAP counsellors (B2B guests; MFA required – section 5.3a) |
| `sg-wellnessz-oh-physicians` | OH_PHYSICIAN | OH physicians (increment 1) |
| `sg-wellnessz-ame` | AME | Aeromedical examiners (increment 1) |
| `sg-wellnessz-cmo` | CMO | Chief Medical Officer and deputy (increment 1; approves triage protocols) |
| `sg-wellnessz-nursing-head` | NURSING_HEAD | Head of OH nursing (increment 1) |
| `sg-wellnessz-hse` | HSE_OFFICER | Health & safety officers (increment 1) |
| `sg-wellnessz-crew-scheduling` | CREW_SCHEDULING | Crew scheduling desk – status only (increment 1) |
| `sg-wellnessz-hygiene` | HYGIENE_MANAGER | Catering hygiene managers – status only (increment 1) |
| `sg-wellnessz-clinic-reception` | CLINIC_RECEPTION | Clinic front desk (increment 1) |
| `sg-wellnessz-first-aiders` | FIRST_AIDER | Workplace first aiders (increment 1) |
| `sg-wellnessz-dpo` | DPO | Data protection officer and deputy – break-glass review, disclosure log, retention; no clinical access (increment 2) |
| `sg-wellnessz-clinic-schedulers` | CLINIC_SCHEDULER | Clinic schedulers – diaries, bookings, campaigns, waitlists; no clinical data (increment 3) |
| `sg-wellnessz-biomedical` | BIOMEDICAL | Biomedical / equipment technicians – equipment register, calibration, cold chain (increment 3) |
| `sg-wellnessz-crew-licensing` | CREW_LICENSING | Crew training & licensing – crew medical certificate validity, status only (increment 3) |
| `sg-wellnessz-candidates` | CANDIDATE | Pre-employment candidates – **only** if candidates are guests in this tenant; preferably a separate candidate identity source (section 5.3, increment 3) |
| `sg-wellnessz-claims` | CLAIMS_COORDINATOR | Claims coordinators – claims, first reports, disclosure sets, costs; no clinical record (increment 4) |
| `sg-wellnessz-tpa-adjusters` | TPA_ADJUSTER | External TPA / insurer adjusters (B2B guests, MFA required – section 5.3a, increment 4) |
| `sg-wellnessz-legal` | LEGAL_COUNSEL | Legal counsel – litigation hold, privilege, medico-legal requests (increment 4) |
| `sg-wellnessz-finance` | FINANCE | Finance – cost roll-ups by unit, budgets, ERP export; no names (increment 4) |
| `sg-wellnessz-procurement` | PROCUREMENT | Procurement – provider directory, contracts, rates, SLAs (increment 4) |
| `sg-wellnessz-peer-volunteers` | PEER_SUPPORT_VOLUNTEER | Trained crew peer-support volunteers – pseudonymous requests only (increment 5) |
| `sg-wellnessz-peer-coordinators` | PEER_SUPPORT_COORDINATOR | Peer-support programme coordinators (increment 5) |
| `sg-wellnessz-safety-managers` | SAFETY_MANAGER | Safety managers – for-cause requests, CISM and testing counts, status only (increment 5) |
| `sg-wellnessz-da-programme` | DA_PROGRAMME_MANAGER | Drug & alcohol programme managers – policies, pools, selections, statistics; **no results** (increment 5) |
| `sg-wellnessz-mro` | MRO | Medical review officers – **named individuals only**, restricted clinical class for test results (increment 5) |
| `sg-wellnessz-collectors` | COLLECTOR | Specimen collectors – collection and chain of custody (increment 5) |
| `sg-wellnessz-health-promotion` | HEALTH_PROMOTION_LEAD | Health promotion leads – insights, campaigns, screening events, aggregated reports (increment 5) |
| `sg-wellnessz-ergonomists` | ERGONOMIST | Ergonomists – assessments and actions (increment 5) |
| `sg-wellnessz-aks-admins` | – | AKS cluster admins (`aks_admin_group_object_ids`) |
| `sg-wellnessz-db-admins` | – | PostgreSQL Entra admin (`postgres_entra_admin`) |

Create one group:

```bash
az ad group create --display-name sg-wellnessz-oh-clinicians --mail-nickname sg-wellnessz-oh-clinicians
```

Repeat for every group in the table.

### 5.2 API app registration `wellnessz-api` (app roles)

Write the app-role definitions to a file:

```bash
cat > /tmp/wz-roles.json <<'EOF'
[
 {"allowedMemberTypes":["User"],"displayName":"Employee","value":"EMPLOYEE","description":"Employee / crew member","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-000000000001"},
 {"allowedMemberTypes":["User"],"displayName":"Manager","value":"MANAGER","description":"Line manager - status only","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-000000000002"},
 {"allowedMemberTypes":["User"],"displayName":"HR","value":"HR","description":"HR partner - status only","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-000000000003"},
 {"allowedMemberTypes":["User"],"displayName":"OH clinician","value":"OH_CLINICIAN","description":"Occupational health clinician","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-000000000004"},
 {"allowedMemberTypes":["User"],"displayName":"OH admin","value":"OH_ADMIN","description":"OH service administrator","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-000000000005"},
 {"allowedMemberTypes":["User"],"displayName":"Case manager","value":"CASE_MANAGER","description":"OH case manager","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-000000000006"},
 {"allowedMemberTypes":["User"],"displayName":"Psych clinician","value":"PSYCH_CLINICIAN","description":"Restricted class E09","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-000000000007"},
 {"allowedMemberTypes":["User"],"displayName":"Auditor","value":"AUDITOR","description":"Read-only audit","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-000000000008"},
 {"allowedMemberTypes":["User"],"displayName":"Platform admin","value":"PLATFORM_ADMIN","description":"Platform administration","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-000000000009"},
 {"allowedMemberTypes":["Application"],"displayName":"Integration","value":"INTEGRATION","description":"Machine-to-machine clients","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-00000000000a"},
 {"allowedMemberTypes":["User"],"displayName":"Partner provider","value":"PARTNER_PROVIDER","description":"External provider","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-00000000000b"},
 {"allowedMemberTypes":["User"],"displayName":"OH physician","value":"OH_PHYSICIAN","description":"Occupational health physician","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-00000000000c"},
 {"allowedMemberTypes":["User"],"displayName":"AME","value":"AME","description":"Aeromedical examiner","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-00000000000d"},
 {"allowedMemberTypes":["User"],"displayName":"CMO","value":"CMO","description":"Chief Medical Officer - approves triage protocols","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-00000000000e"},
 {"allowedMemberTypes":["User"],"displayName":"Nursing head","value":"NURSING_HEAD","description":"Head of OH nursing","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-00000000000f"},
 {"allowedMemberTypes":["User"],"displayName":"HSE officer","value":"HSE_OFFICER","description":"Health and safety - reconciliation, no clinical data","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-000000000010"},
 {"allowedMemberTypes":["User"],"displayName":"Crew scheduling","value":"CREW_SCHEDULING","description":"Crew fitness status only","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-000000000011"},
 {"allowedMemberTypes":["User"],"displayName":"Hygiene manager","value":"HYGIENE_MANAGER","description":"Food-handler status only","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-000000000012"},
 {"allowedMemberTypes":["User"],"displayName":"Clinic reception","value":"CLINIC_RECEPTION","description":"Walk-in registration, no clinical data","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-000000000013"},
 {"allowedMemberTypes":["User"],"displayName":"First aider","value":"FIRST_AIDER","description":"First-aid registration, no clinical data","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-000000000014"},
 {"allowedMemberTypes":["User"],"displayName":"DPO","value":"DPO","description":"Data protection officer - break-glass review, disclosures, retention; no clinical data","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-000000000015"},
 {"allowedMemberTypes":["User"],"displayName":"Clinic scheduler","value":"CLINIC_SCHEDULER","description":"Diaries, bookings, campaigns - no clinical data","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-000000000016"},
 {"allowedMemberTypes":["User"],"displayName":"Biomedical","value":"BIOMEDICAL","description":"Equipment register, calibration, cold chain","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-000000000017"},
 {"allowedMemberTypes":["User"],"displayName":"Crew licensing","value":"CREW_LICENSING","description":"Crew medical certificate validity - status only","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-000000000018"},
 {"allowedMemberTypes":["User"],"displayName":"Candidate","value":"CANDIDATE","description":"Pre-employment candidate - own questionnaire, consent, booking","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-000000000019"},
 {"allowedMemberTypes":["User"],"displayName":"Claims coordinator","value":"CLAIMS_COORDINATOR","description":"Claims, e-reporting, costs, disclosure sets - no clinical record","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-00000000001a"},
 {"allowedMemberTypes":["User"],"displayName":"TPA adjuster","value":"TPA_ADJUSTER","description":"External adjuster - own payer claims, partner portal, MFA required","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-00000000001b"},
 {"allowedMemberTypes":["User"],"displayName":"Legal counsel","value":"LEGAL_COUNSEL","description":"Litigation hold, privilege, medico-legal requests","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-00000000001c"},
 {"allowedMemberTypes":["User"],"displayName":"Finance","value":"FINANCE","description":"Cost roll-ups by unit, budgets, ERP export - no names","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-00000000001d"},
 {"allowedMemberTypes":["User"],"displayName":"Procurement","value":"PROCUREMENT","description":"Provider directory, contracts, rates, SLAs","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-00000000001e"},
 {"allowedMemberTypes":["User"],"displayName":"Peer support volunteer","value":"PEER_SUPPORT_VOLUNTEER","description":"Pseudonymous peer-support requests","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-00000000001f"},
 {"allowedMemberTypes":["User"],"displayName":"Peer support coordinator","value":"PEER_SUPPORT_COORDINATOR","description":"Peer-support programme coordination","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-000000000020"},
 {"allowedMemberTypes":["User"],"displayName":"Safety manager","value":"SAFETY_MANAGER","description":"For-cause requests, counts and status only","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-000000000021"},
 {"allowedMemberTypes":["User"],"displayName":"D&A programme manager","value":"DA_PROGRAMME_MANAGER","description":"Testing policies, pools, selections, statistics - no results","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-000000000022"},
 {"allowedMemberTypes":["User"],"displayName":"MRO","value":"MRO","description":"Medical review officer - restricted clinical class","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-000000000023"},
 {"allowedMemberTypes":["User"],"displayName":"Collector","value":"COLLECTOR","description":"Specimen collection and chain of custody","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-000000000024"},
 {"allowedMemberTypes":["User"],"displayName":"Health promotion lead","value":"HEALTH_PROMOTION_LEAD","description":"Insights, campaigns, screening events - aggregated only","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-000000000025"},
 {"allowedMemberTypes":["User"],"displayName":"Ergonomist","value":"ERGONOMIST","description":"Ergonomic assessments and actions","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-000000000026"}
]
EOF
```

Generate your own GUIDs (`uuidgen`) for the `id` fields; the values above are examples. The last nine roles were
added in increment 1 (E01 intake, E02 triage); `DPO` was added in increment 2 (E03, E05); `CLINIC_SCHEDULER`,
`BIOMEDICAL`, `CREW_LICENSING` and `CANDIDATE` in increment 3 (E06, E07, E14); `CLAIMS_COORDINATOR`,
`TPA_ADJUSTER`, `LEGAL_COUNSEL`, `FINANCE` and `PROCUREMENT` in increment 4 (E04, E08); the last eight in increment 5
(E09–E12) – 38 roles in total. Then create the API app of this environment:

```bash
API_APP_ID=$(az ad app create --display-name $API_NAME --identifier-uris api://$API_NAME --app-roles @/tmp/wz-roles.json --query appId -o tsv) && echo $API_APP_ID
```

Issue v2 access tokens and expose a delegated scope `access_as_user`:

```bash
API_OBJ=$(az ad app show --id $API_APP_ID --query id -o tsv); SCOPE_ID=$(uuidgen)
```

```bash
az rest --method PATCH --uri https://graph.microsoft.com/v1.0/applications/$API_OBJ --body "{\"api\":{\"requestedAccessTokenVersion\":2,\"oauth2PermissionScopes\":[{\"id\":\"$SCOPE_ID\",\"value\":\"access_as_user\",\"type\":\"User\",\"isEnabled\":true,\"adminConsentDisplayName\":\"Access WellnessZ\",\"adminConsentDescription\":\"Access the WellnessZ API as the signed-in user\",\"userConsentDisplayName\":\"Access WellnessZ\",\"userConsentDescription\":\"Access WellnessZ as you\"}]}}"
```

```bash
az ad sp create --id $API_APP_ID
```

Require user assignment, so that only users in assigned groups can obtain tokens:

```bash
az ad sp update --id $API_APP_ID --set appRoleAssignmentRequired=true
```

Assign each group to its app role (this needs Entra ID P1 or higher). Example for clinicians:

```bash
SP_OBJ=$(az ad sp show --id $API_APP_ID --query id -o tsv); GROUP_OBJ=$(az ad group show -g sg-wellnessz-oh-clinicians --query id -o tsv)
```

```bash
az rest --method POST --uri https://graph.microsoft.com/v1.0/servicePrincipals/$SP_OBJ/appRoleAssignedTo --body "{\"principalId\":\"$GROUP_OBJ\",\"resourceId\":\"$SP_OBJ\",\"appRoleId\":\"6f1d4a10-0000-4000-8000-000000000004\"}"
```

The access token then contains `"roles": ["OH_CLINICIAN"]`. The core reads `roles` (Entra), `realm_access.roles`
(Keycloak) and, as a fallback, `groups`.

**Existing tenant (upgrade to increment 1).** `az ad app create --app-roles` only applies to new registrations. For
an existing `wellnessz-api`, append the nine new entries to the current list (keep the existing `id` values) and
update it, then assign the new groups:

```bash
az ad app show --id $API_APP_ID --query appRoles > /tmp/wz-roles-current.json   # edit: append the nine new roles
```

```bash
az ad app update --id $API_APP_ID --app-roles @/tmp/wz-roles-current.json
```

Repeat the `appRoleAssignedTo` call for each new group (for example `sg-wellnessz-nursing-head` with the
`NURSING_HEAD` role id). A user in several groups receives all their roles; the OH workspace shows
*Access restricted* to users without any role it knows. If you map roles from group object ids instead of app roles
(`wellnessz.security.group-role-mapping` on the core and `wellnessz.bff.group-role-mapping` on the BFF), add the new
groups to **both** mappings.

**Existing tenant (upgrade to increment 2).** Append the `DPO` entry to the current role list in the same way, update
the registration and assign `sg-wellnessz-dpo` to it. The DPO account needs no other WellnessZ role; do not add DPO
members to clinical groups.

**Existing tenant (upgrade to increment 3).** Append the four increment-3 entries (`CLINIC_SCHEDULER`, `BIOMEDICAL`,
`CREW_LICENSING`, `CANDIDATE`) in the same way, update the registration and assign `sg-wellnessz-clinic-schedulers`,
`sg-wellnessz-biomedical` and `sg-wellnessz-crew-licensing`. None of these roles may be combined with clinical groups
for the same person unless that person is also an OH clinician. Assign `CANDIDATE` only to candidate identities
(section 5.3). If you map roles from group object ids, add the new groups to **both** group-role mappings.

**Existing tenant (upgrade to increment 4).** Append the five increment-4 entries (`CLAIMS_COORDINATOR`,
`TPA_ADJUSTER`, `LEGAL_COUNSEL`, `FINANCE`, `PROCUREMENT`) in the same way, update the registration and assign
`sg-wellnessz-claims`, `sg-wellnessz-tpa-adjusters`, `sg-wellnessz-legal`, `sg-wellnessz-finance` and
`sg-wellnessz-procurement`. Claims, legal, finance and procurement users must not also be in clinical groups.
`TPA_ADJUSTER` and `PARTNER_PROVIDER` are for B2B guests only and require MFA (section 5.3a); if you map roles from
group object ids, add the new groups to **both** group-role mappings.

**Existing tenant (upgrade to increment 5).** Append the eight increment-5 entries (`PEER_SUPPORT_VOLUNTEER`,
`PEER_SUPPORT_COORDINATOR`, `SAFETY_MANAGER`, `DA_PROGRAMME_MANAGER`, `MRO`, `COLLECTOR`, `HEALTH_PROMOTION_LEAD`,
`ERGONOMIST`) in the same way, update the registration of **each** environment and assign the eight new groups.
`PSYCH_CLINICIAN` and `MRO` open the restricted clinical class: add named individuals only, record the approval of the
Clinical Director / CMO, and include both groups in the quarterly access review (section 17). The programme manager
must not also be an MRO. If you map roles from group object ids, add the new groups to **both** group-role mappings.

**Values to note per environment.** The API's application (client) ID is the **audience** (`WZ_OIDC_AUDIENCE` →
`<env-wellnessz-api-client-id>` in `environments/azure/<env>.yaml`). The issuer is
`https://login.microsoftonline.com/<tenant-id>/v2.0`.

### 5.3 `employee_id` and `org_scope` claims

The core's ABAC needs two user attributes: `employee_id` and `org_scope` (an organisation path such as
`/AIRLINE/OPS/CABIN`). Two options:

**Option A – directory extensions (recommended).**

1. Register both extension properties on the API app:

   ```bash
   az rest --method POST --uri https://graph.microsoft.com/v1.0/applications/$API_OBJ/extensionProperties --body '{"name":"employee_id","dataType":"String","targetObjects":["User"]}'
   ```

   ```bash
   az rest --method POST --uri https://graph.microsoft.com/v1.0/applications/$API_OBJ/extensionProperties --body '{"name":"org_scope","dataType":"String","targetObjects":["User"]}'
   ```

2. Populate the values from the HR system. Provisioning or the HR-driven inbound flow writes
   `extension_<apiAppIdWithoutDashes>_employee_id` and `extension_<apiAppIdWithoutDashes>_org_scope` on each user.
3. In the API app registration, open **Token configuration → Add optional claim → Access token**. Add both
   extension attributes. Alternatively, edit `optionalClaims.accessToken` in the manifest with
   `"source": "user"`.
4. Decode a real access token (<https://jwt.ms>) and note the emitted claim names.
5. If the names differ from `employee_id` and `org_scope`, map them in the core:

   ```yaml
   core:
     extraEnv:
       - name: WELLNESSZ_SECURITY_EMPLOYEEIDCLAIM
         value: "<emitted claim name for employee_id>"
       - name: WELLNESSZ_SECURITY_ORGSCOPECLAIM
         value: "<emitted claim name for org_scope>"
   ```

**Option B – built-in attributes.** Use the built-in `employeeId` attribute and a claims-mapping policy on the
API service principal. Map `org_scope` from `department` or an `extensionAttributeN`.

Only identity administrators may write these attributes. They are security-relevant (ABAC scope), so log changes
with Entra audit logs.

**Partner users (increment 2).** External providers (B2B guests in `sg-wellnessz-partners`, role `PARTNER_PROVIDER`)
need `org_scope` set to `/PARTNERS/<ORG>`, where `<ORG>` is the partner code that OH uses when sharing a document
(`[A-Z0-9_]{2,40}`, for example `/PARTNERS/CITYHOSP`). Without it a partner sees no documents. Set the value only
after the data-sharing agreement for that partner is signed.

**Candidates (increment 3).** Pre-employment candidates are not employees and must not be in the workforce
directory. Recommended: a separate Microsoft Entra External ID tenant (or a Keycloak candidate realm). The core can
trust it as an additional issuer (`WZ_OIDC_ISSUERS`), but the BFF has a single OIDC registration (`wellnessz`), so
browser sign-in needs either a broker in front of both directories (for example Keycloak brokering Entra ID and the
candidate directory) or a second BFF registration – a design decision that is still open (doc 01, D17). Each candidate account
carries the app role `CANDIDATE`, `employee_id` = the candidate id from recruiting (for example `C9001`, the same value
recruiting sends to `POST /api/v1/integration/pre-employment`) and `org_scope=/CANDIDATES`. Disable the account when
the offer is closed; non-hired candidates' health data is purged after 12 months by the core. Until a candidate
identity source is decided (doc 01, D17), use B2B guest accounts in `sg-wellnessz-candidates` only in dev / test.

**EAP providers (increment 5).** Counsellors of the contracted employee assistance programme are B2B guests in
`sg-wellnessz-partners` (role `PARTNER_PROVIDER`) with `org_scope=/PARTNERS/EAP_WELLBEING` – or the code of the
contracted EAP in the partner-organisation master data. They see only the *EAP referrals* of their own organisation
(minimal data) and return attendance only; MFA applies as for every partner (section 5.3a).

**TPA adjusters (increment 4).** Adjusters are B2B guests in `sg-wellnessz-tpa-adjusters` with
`org_scope=/PARTNERS/<PAYER>`, where `<PAYER>` is the insurer or TPA code in the partner-organisation master data (for
example `/PARTNERS/GULFTPA`). An adjuster sees only claims and pre-authorisations where that organisation is the payer.
Invite partner and adjuster users as named guests only after the data-sharing agreement is signed:

```bash
az rest --method POST --uri https://graph.microsoft.com/v1.0/invitations --body '{"invitedUserEmailAddress":"adjuster@gulftpa.example","inviteRedirectUrl":"https://partners.<domain>/","sendInvitationMessage":true}'
```

Then add the guest to `sg-wellnessz-partners` or `sg-wellnessz-tpa-adjusters` and set the `org_scope` extension
attribute (Option A above) on the guest user object.

### 5.3a MFA for partner, adjuster and EAP guests [M]

> **Manual, per environment.** Conditional Access policies and MFA enrolment are tenant settings; they cannot be
> created from this repository. In DEV the overlay leaves `WZ_MFA_REQUIRED_ROLES` empty so that test identities
> work; UAT and PROD enforce it – create and **enable** the policy for the UAT and PROD apps before their first
> partner sign-in. Each guest enrols a second factor at first sign-in (or brings it through cross-tenant trust) –
> this enrolment is a user action and is part of partner onboarding.

The core grants `PARTNER_PROVIDER` and `TPA_ADJUSTER` only when the access token proves MFA (`amr` contains `mfa`,
or `acr` is one of `2`, `3`, `mfa`, `c1` – `WZ_MFA_REQUIRED_ROLES`, on by default outside the `local` profile). In
Entra ID, MFA for guests is enforced with Conditional Access:

1. Optionally trust MFA from the partner's home tenant: *External Identities → Cross-tenant access settings → Default
   settings (or the partner organisation) → Inbound access → Trust settings → Trust multifactor authentication from
   Microsoft Entra tenants*. Without it the guest registers MFA in your tenant at first sign-in.
2. Create the Conditional Access policy (report-only first, then on) – after sections 5.2 and 5.4, so that
   `$API_APP_ID` and `$BFF_APP_ID` hold this environment's registrations:

   ```bash
   az rest --method POST --uri https://graph.microsoft.com/v1.0/identity/conditionalAccess/policies --body "{\"displayName\":\"WellnessZ - partners and adjusters require MFA\",\"state\":\"enabledForReportingButNotEnforced\",\"conditions\":{\"clientAppTypes\":[\"all\"],\"applications\":{\"includeApplications\":[\"$BFF_APP_ID\",\"$API_APP_ID\"]},\"users\":{\"includeGuestsOrExternalUsers\":{\"guestOrExternalUserTypes\":\"b2bCollaborationGuest,b2bCollaborationMember\",\"externalTenants\":{\"membershipKind\":\"all\"}}}},\"grantControls\":{\"operator\":\"OR\",\"authenticationStrength\":{\"id\":\"00000000-0000-0000-0000-000000000002\"}},\"sessionControls\":{\"signInFrequency\":{\"value\":12,\"type\":\"hours\",\"isEnabled\":true},\"persistentBrowser\":{\"mode\":\"never\",\"isEnabled\":true}}}"
   ```

   `00000000-0000-0000-0000-000000000002` is the built-in authentication strength *Multifactor authentication*; use
   `…0004` (*Phishing-resistant MFA*) where partners can use FIDO2 keys or Windows Hello. Equivalent in the portal:
   *Protection → Conditional Access → New policy*, users *Guest or external users*, target resources `wellnessz-bff`
   and `wellnessz-api`, grant *Require authentication strength*.
3. Review the report-only results in the sign-in logs (*Conditional Access* tab), then set `"state":"enabled"`
   (`az rest --method PATCH --uri https://graph.microsoft.com/v1.0/identity/conditionalAccess/policies/<id> --body '{"state":"enabled"}'`).
4. Verify the claim. After an MFA sign-in, the access token for `wellnessz-api` must contain `"amr": [..., "mfa"]`.
   Check it functionally (smoke check 35: the partner portal shows the inbox, `GET /bff/api/me` lists the partner role)
   and in the sign-in log (*Authentication details* shows the second factor). The core also accepts Entra ID
   **authentication contexts** from the `acrs` claim (for example `["c1"]`) when the context id is listed in
   `wellnessz.security.mfa-acr-values` (default `2,3,mfa,c1`). If you protect the applications with an
   authentication context other than `c1`, add its id to that list – after confirming the claim in a decoded token of
   a test tenant.
5. Keep `WZ_MFA_REQUIRED_ROLES` at its default in every shared environment (section 11). Workforce users are not gated
   by the core; their MFA remains the airline's general Conditional Access policy.

### 5.4 BFF app registration `wellnessz-bff` (confidential web client)

Set the host suffix of the environment (`.dev`, `.uat` or empty for PROD) and your domain:

```bash
DOMAIN=wellnessz.example.com; if [ "$ENV" = prod ]; then SUF=""; else SUF=".$ENV"; fi
```

```bash
BFF_APP_ID=$(az ad app create --display-name $BFF_NAME --web-redirect-uris https://employee$SUF.$DOMAIN/login/oauth2/code/wellnessz https://manager$SUF.$DOMAIN/login/oauth2/code/wellnessz https://oh$SUF.$DOMAIN/login/oauth2/code/wellnessz https://partners$SUF.$DOMAIN/login/oauth2/code/wellnessz --query appId -o tsv) && echo $BFF_APP_ID
```

The BFF builds the redirect URI from the host the browser used, so every web host needs its own entry. The OH
workspace host `oh.<domain>` was added in increment 1, the partner portal host `partners.<domain>` in increment 4.
For an existing registration:

```bash
az ad app update --id $BFF_APP_ID --web-redirect-uris https://employee$SUF.$DOMAIN/login/oauth2/code/wellnessz https://manager$SUF.$DOMAIN/login/oauth2/code/wellnessz https://oh$SUF.$DOMAIN/login/oauth2/code/wellnessz https://partners$SUF.$DOMAIN/login/oauth2/code/wellnessz
```

```bash
az ad app update --id $BFF_APP_ID --enable-id-token-issuance false --enable-access-token-issuance false --set "web.logoutUrl=https://employee$SUF.$DOMAIN/"
```

Grant the delegated scope on the API and give admin consent:

```bash
az ad app permission add --id $BFF_APP_ID --api $API_APP_ID --api-permissions $SCOPE_ID=Scope
```

```bash
az ad app permission add --id $BFF_APP_ID --api 00000003-0000-0000-c000-000000000000 --api-permissions 37f7f235-527c-4136-accd-4a02d197296e=Scope 14dad69e-099b-42c9-810b-d002981feec1=Scope 64a6cdd6-aab1-4aaf-94b8-3cc8405e90d0=Scope 7427e0e9-2fba-42fe-b0c0-848c9e6a8182=Scope
```

These four Microsoft Graph scopes are `openid`, `profile`, `email` and `offline_access`.

```bash
az ad app permission admin-consent --id $BFF_APP_ID
```

Create a client secret. It is stored **only** in the Key Vault **of this environment** in section 7; do not keep
a copy. Prefer a 6-month
expiry and rotate it.

```bash
az ad app credential reset --id $BFF_APP_ID --display-name wellnessz-bff-$(date +%Y%m) --years 0.5 --query password -o tsv
```

### 5.5 Mobile app registration `wellnessz-mobile` (public client, PKCE)

```bash
MOB_APP_ID=$(az ad app create --display-name wellnessz-mobile --is-fallback-public-client true --public-client-redirect-uris com.wellnessz.app://callback --query appId -o tsv) && echo $MOB_APP_ID
```

```bash
az ad app permission add --id $MOB_APP_ID --api $API_APP_ID --api-permissions $SCOPE_ID=Scope && az ad app permission admin-consent --id $MOB_APP_ID
```

Then add Conditional Access: require MFA, a compliant device or an app protection policy for `wellnessz-mobile`
and `wellnessz-bff`. Block legacy authentication.

### 5.6 Machine-to-machine clients

For each integration partner, create an app registration with a certificate credential. Assign it the
application role `INTEGRATION` on `wellnessz-api` (an `appRoleAssignedTo` call like the one in 5.2, with the
partner's service principal as `principalId`). Partners request tokens with the scope
`api://wellnessz-api/.default`.

**EHS webhook client (increment 1).** The EHS incident-management system pushes incidents to
`POST https://api.<domain>/api/v1/intake/ehs/incidents`:

1. Create an app registration `wellnessz-ehs-webhook` with a certificate credential (or a short-lived secret held
   by the EHS vendor's secret store) and assign it the `INTEGRATION` application role as above.
2. The EHS system requests a token with `client_credentials` and scope `api://wellnessz-api/.default`, then sends
   each batch (≤ 500 incidents) with a unique `Idempotency-Key` header (8–200 characters, reused on retries of the
   same batch).
3. Invalid items are not rejected as a whole: they land in the dead-letter queue, which OH administrators review in
   the OH workspace (*Intake → DLQ*). Add a SIEM alert on the log line `ALERT ehs-webhook dead-lettered`.
4. Smoke test from a workstation with a token of that client:

   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' -X POST https://api.<domain>/api/v1/intake/ehs/incidents -H "Authorization: Bearer $EHS_TOKEN" -H 'Content-Type: application/json' -H "Idempotency-Key: smoke-$(date +%s)" -d '{"externalRef":"SMOKE-1","occurredAt":"2026-10-28T09:00:00Z","incidentClass":"NEAR_MISS","severity":"LOW"}'
   ```

   Expected: `202`. The near miss matches the seeded `IGNORE` rule, so no case is created; it appears in the
   reconciliation report.

**Gateway clients (increment 2).** Each gateway gets its own app registration with a certificate credential and the
`INTEGRATION` application role, so that it can be revoked and audited separately:

| App registration (example) | Calls (all on `https://api.<domain>/api/v1`) | Notes |
|---|---|---|
| `wellnessz-lis-gateway` | `GET /integration/lis/orders?status=QUEUED`, `POST /integration/lis/orders/{id}/ack`, `POST /integration/lis/results` or `POST /integration/hl7v2` (`ORU^R01`, `text/plain`) | Pull model: the core never connects to the laboratory; MLLP stays on the integration engine |
| `wellnessz-device-gateway` | `POST /employees/{id}/results/device` | Device middleware for audiometers, spirometers, vision screeners, ECG; send the calibration id |
| `wellnessz-roster-gateway` | `GET /integration/roster-feed?since=…`, `POST /integration/roster-feed/{messageId}/ack` | Acknowledge every message within 15 minutes; unacknowledged messages are audited and shown in the reconciliation report |

**Gateway clients (increment 3).** Same pattern – one app registration per source, certificate credential,
`INTEGRATION` application role:

| App registration (example) | Calls (all on `https://api.<domain>/api/v1`) | Notes |
|---|---|---|
| `wellnessz-absence-feed` (one per source: HRIS, time & attendance, crew management) | `POST /integration/absence/episodes` (≤ 5,000 rows, `sourceSystem` `HRIS` / `TNA` / `CREW`), `POST /integration/absence/certificates` | Reason **categories** only, never diagnoses; unknown employees appear in *Absence → Absence feed* for reconciliation |
| `wellnessz-ih-gateway`, `wellnessz-dosimetry` | `POST /integration/ih-measurements`, `POST /integration/cosmic-dose` | Industrial hygiene measurements per exposure group; annual crew cosmic dose |
| `wellnessz-recruiting` | `POST /integration/pre-employment` | Opens the pre-employment assessment for a candidate id; HR receives the clearance status only |
| `wellnessz-iot-coldchain` | `POST /integration/cold-chain/readings` (≤ 5,000 readings per call) | Fridge gateway; device ids must match the E14 equipment register (`deviceId`); unknown devices are listed in the response |

**Partner-system clients (increment 4).** External systems, so prefer a certificate credential issued to the partner
and one registration per partner organisation; grant these clients only after the data-sharing agreement. The API
**binds each partner-system client to its organisation** (doc 02, RR33 – closed): the client-credentials token must
carry `org_scope=/PARTNERS/<CODE>`, where `<CODE>` is the partner code in the partner-organisation master data. A
FHIR client then reaches only referrals whose provider is `<CODE>`, an insurer / TPA client only pre-authorisations
whose payer and first reports whose recipient is `<CODE>`; anything else answers `404` and is audited as `DENIED`.
A client without such a claim (absence feed, IoT, laboratory and other gateway clients) is refused on these endpoints.

Emit the claim as a fixed value per registration that the partner cannot influence. Entra ID has no per-user
directory extension for an app-only token, so configure it through an app role / optional claim or a claims-mapping
policy that yields the constant `org_scope` value for that partner's registration (the core reads the claim named
by `WELLNESSZ_SECURITY_ORGSCOPECLAIM`, section 5.3 – one claim name for users and clients). Decode a test token
(client-credentials flow) and confirm `"org_scope": "/PARTNERS/<CODE>"` before handing the credential to the partner.
In Keycloak the equivalent is a *Hardcoded claim* mapper on the partner's client (`org_scope` = `/PARTNERS/<CODE>`,
added to the access token).

| App registration (example) | Calls (all on `https://api.<domain>/api/v1`) | Notes |
|---|---|---|
| `wellnessz-fhir-<partner>` (partner hospital) | `GET /integration/referrals/{id}/fhir` (FHIR R4 `ServiceRequest`), `POST /integration/referrals/fhir/document-reference` (report as `DocumentReference`, base64 attachment ≤ 10 MB) | Only for partners with a FHIR interface; others use the partner portal. Each read is disclosure-logged |
| `wellnessz-insurer-<payer>` | `POST /integration/preauthorisations/{ref}/decision`, `POST /integration/claims/reports/{ref}/acknowledgement` | Insurer / TPA system instead of the adjuster portal |
| `wellnessz-telehealth` (video platform) | `POST /integration/telehealth/tokens/redeem` | Only after the platform is selected (doc 01, D15) |

**Clients for increment 5.** Same pattern – one registration per system and environment, certificate credential,
`INTEGRATION` application role:

| App registration (example) | Calls (all on `https://api.<domain>/api/v1`) | Binding / notes |
|---|---|---|
| `wellnessz-toxlab` (toxicology laboratory LIMS) | `GET /integration/testing/lab-orders`, `POST /integration/testing/lab-results` | **Must** carry the fixed claim `org_scope=/PARTNERS/TOXLAB` (or the code set in the E18 key `substance.laboratory-code`). Any other or unbound client gets `404` and an audit entry. External system – through the WAF |
| `wellnessz-duty-feed` (one per source: crew rostering, shift planning, facility access) | `POST /integration/duty-assignments` (`source` `CREW_ROSTER`, `SHIFT`, `FACILITY`) | **Internal clients only** – no `org_scope` partner claim; reach the API through the private listener (ExpressRoute / VPN). Feeds CISM affected-crew lists, contact tracing and travel advisories |
| `wellnessz-campaign-app` | `POST /integration/promotion/participation` | No partner claim (partner-bound clients get `403`); receives campaign publications from the `campaign-app` outbox once a connector exists |
| `wellnessz-wearables-<vendor>` | `POST /integration/wearables/data` | Daily totals only; data without an active consented enrolment is rejected; no partner claim |

The safety management system receives quarterly aggregated peer-support figures through the `sms-safety` outbox
destination; like the other outbound destinations it is delivered by the configured publisher (logging today) – no
inbound client is needed.

Gateways in the corporate network reach the API through the App Gateway private listener (ExpressRoute / VPN) where
possible; partner and insurer systems come from the internet through the WAF. Smoke test with a gateway token:

```bash
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $LIS_TOKEN" 'https://api.<domain>/api/v1/integration/lis/orders?status=QUEUED'
```

Expected: `200`. A user token gets `403` on `/integration/**`; crew scheduling staff use `/api/v1/crew/roster-feed`.

### 5.7 Alternative: Keycloak instead of Entra ID

If the airline uses Keycloak (or brokers Entra ID through Keycloak), run Keycloak 26 in **production mode**. Use
the Keycloak Operator in its own namespace, or a managed Keycloak service.

1. Give Keycloak its own PostgreSQL database and role: either a separate Flexible Server database `keycloak` or a
   separate server. Never share roles with `wellnessz`.
2. Set `hostname=https://login.<domain>` and serve it behind the same App Gateway WAF.
3. Import `deploy/local/keycloak/wellnessz-realm.json` as a **template only**, after these changes:
   * delete the `users` array (test users);
   * regenerate both client secrets;
   * replace the localhost redirect URIs, post-logout URIs and web origins with the real hosts (employee, manager, OH
     workspace and – since increment 4 – `partners.<domain>`);
   * keep the client **scope mappings** of `wellnessz-bff` and `wellnessz-mobile` complete: both clients have
     `fullScopeAllowed=false`, so every role (including the nine increment-1 roles) must be listed there or it is
     dropped from tokens (38 roles after increment 5, including `PEER_SUPPORT_VOLUNTEER`, `PEER_SUPPORT_COORDINATOR`,
     `SAFETY_MANAGER`, `DA_PROGRAMME_MANAGER`, `MRO`, `COLLECTOR`, `HEALTH_PROMOTION_LEAD`, `ERGONOMIST`);
   * set `sslRequired: all`;
   * configure SMTP;
   * enable OTP/WebAuthn for privileged roles, and **require** it for every `PARTNER_PROVIDER` / `TPA_ADJUSTER`
     account (increment 4): required action *Configure OTP* or *WebAuthn Register* on each such user, plus either the
     *Authentication Method Reference (AMR)* mapper on `wellnessz-bff` (access token) or a step-up browser flow with
     *Condition – Level of Authentication* and an ACR-to-LoA mapping, so that the token carries `amr` (`otp`,
     `webauthn`) or `acr` `2`; without it the core drops the partner roles (doc 01, Section 14.30; setup guide 05,
     Section 6.1).
4. Map federated users to realm roles through group mappers. Set `employee_id` and `org_scope` through the user
   profile (admin-only) or identity-provider mappers.
5. Use one realm (or one Keycloak instance) **per environment** – `https://login.dev.<domain>/realms/wellnessz`,
   `https://login.uat.<domain>/realms/wellnessz`, `https://login.<domain>/realms/wellnessz` – so that test users never
   exist in the PROD realm. For the TOXLAB client add a *Hardcoded claim* mapper `org_scope` = `/PARTNERS/TOXLAB`.
6. Configure the core and BFF in `environments/azure/<env>.yaml`:
   * `WZ_OIDC_ISSUERS=https://login.<domain>/realms/wellnessz`;
   * `WZ_OIDC_AUDIENCE=wellnessz-api`;
   * `WZ_BFF_ISSUER_URI` set to the same issuer;
   * remove `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_WELLNESSZ_SCOPE` (the audience mapper supplies the
     audience).

`WZ_OIDC_ISSUERS` accepts a comma-separated list, but the core validates a **single** audience
(`WZ_OIDC_AUDIENCE`). To trust Entra ID and Keycloak side by side during a migration, use the same audience in both
IdPs: in Keycloak, set the audience mapper's *Included Custom Audience* to the Entra API client ID.

## 6. Provision the infrastructure with Terraform [A]

The committed variable files hold everything that differs between environments (sizes, zones, CIDRs, retention,
WAF mode, `migrations_mode`, GitHub trust); tenant-specific identifiers go into a git-ignored
`environments/<env>.local.tfvars`. Never edit `variables.tf` defaults per environment.

```bash
cd deploy/terraform/azure
```

**Once per environment** – identifiers (no secrets) from `environments/local.tfvars.example`:

```bash
cp environments/local.tfvars.example environments/$ENV.local.tfvars
```

| Variable (`$ENV.local.tfvars`) | Example | Notes |
|---|---|---|
| `subscription_id` | `0000…` | The environment's subscription (section 3) |
| `aks_admin_group_object_ids` | `["<sg-wellnessz-aks-admins-$ENV id>"]` | Cluster admins via Azure RBAC |
| `postgres_entra_admin` | `{ object_id = "<id>", principal_name = "sg-wellnessz-db-admins-$ENV" }` | Entra admin of the database |
| `github_repository` | `OWNER/WellnessZ` | Creates the GitHub OIDC deployer identity trusted by GitHub Environment `azure-$ENV` only |
| `key_vault_allowed_ips` | `["<runner egress IP>/32"]` | Only while Terraform runs outside the VNet (needed for section 7 too) |
| `aks_authorized_ip_ranges` | `["<corporate egress>/24", "<runner range>"]` | **DEV only** (public API server); replace the `203.0.113.0/24` placeholder in `dev.tfvars` by setting it here |

Review the committed `environments/$ENV.tfvars` (excerpt of what differs):

| Variable | `dev.tfvars` | `uat.tfvars` | `prod.tfvars` |
|---|---|---|---|
| `environment` | `dev` | `uat` | `prod` |
| `availability_zones` / `resource_lock_enabled` | `["1"]` / `false` | `["1","2","3"]` / `true` | `["1","2","3"]` / `true` |
| `vnet_address_space` | `10.48.0.0/16` | `10.44.0.0/16` | `10.40.0.0/16` |
| `aks_private_cluster` / `aks_sku_tier` | `false` / `Free` | `true` / `Standard` | `true` / `Standard` |
| `aks_user_node_min` – `max` | 1–3 | 2–5 | 3–9 |
| `migrations_mode` | `app` | `job` | `job` |
| `postgres_sku` | `B_Standard_B2ms` | `GP_Standard_D2ds_v5` | `GP_Standard_D4ds_v5` |
| `postgres_ha_enabled` / `postgres_geo_redundant_backup` | `false` / `false` | `true` / `false` | `true` / `true` (immutable after creation) |
| `postgres_backup_retention_days` | 7 | 14 | 35 |
| `waf_mode` | `Detection` | `Prevention` | `Prevention` |
| `log_retention_days` / `key_vault_soft_delete_days` | 30 / 7 | 90 / 30 | 365 / 90 |
| `enable_defender_plans` | `false` | `true` | `true` |
| `github_environments` / `github_image_publish_branches` | `["azure-dev"]` / `["main"]` | `["azure-uat"]` / `[]` | `["azure-prod"]` / `[]` |

`location` is `westeurope` in all three files; change it in all three together if the client needs another region
(it must offer availability zones and zone-redundant PostgreSQL HA). `postgres_cmk_enabled` is `false` everywhere –
set it to `true` in `prod.tfvars` (plus the geo-backup key and identity) if the client requires customer-managed keys.
`acr_georeplication_locations` is `[]` everywhere (single-region deployment); list a second region in `prod.tfvars`
once AKS also runs there – each replica is zone redundant like the primary registry.

Network security applied in every environment (no variable): every subnet has an NSG. The AKS node subnet NSG
(`nsg-<prefix>-<env>-aks`) allows node↔node, node↔pod and pod↔pod traffic (the rules Azure CNI Overlay needs when a
subnet NSG exists, pod CIDR = `aks_pod_cidr`) and App Gateway → nodes/pods; the default rules deny inbound traffic
from the internet. If you change `aks_pod_cidr` or `subnet_cidrs`, the NSG follows automatically. PostgreSQL logs no
statement text by duration (`log_min_duration_statement = -1`, statements can carry health data); use
`pg_stat_statements` for slow queries.

Initialise against this environment's state (`-reconfigure` switches cleanly between environments in one checkout):

```bash
terraform init -reconfigure -backend-config=environments/backend-$ENV.hcl
```

Expected: `Terraform has been successfully initialized!` The provider versions and checksums come from the
committed `.terraform.lock.hcl`.

```bash
terraform plan -var-file=environments/$ENV.tfvars -var-file=environments/$ENV.local.tfvars -out $ENV.tfplan
```

Review the plan. Expect about 110 resources on the first apply. Look for anything that destroys or replaces
PostgreSQL or Key Vault: both carry `prevent_destroy`. For UAT and PROD attach the plan output to the change ticket
**[M]** and have a second engineer review it.

```bash
terraform apply $ENV.tfplan && rm $ENV.tfplan
```

(The plan file is not git-ignored and may contain sensitive values – delete it after the apply.) Duration: 30–50
minutes; AKS and the PostgreSQL HA standby take longest. Then export the outputs:

```bash
terraform output -json > /tmp/wz-azure-$ENV-outputs.json && jq 'with_entries(.value |= .value)' /tmp/wz-azure-$ENV-outputs.json
```

| Output | Used for |
|---|---|
| `acr_login_server` | `global.imageRegistry` (`<acr_login_server>/wellnessz`), GitHub variable `IMAGE_REGISTRY` |
| `key_vault_name` | `secrets.azure.keyVaultName`; section 7 |
| `tenant_id` | `workloadIdentity.azure.tenantId`, `secrets.azure.tenantId`, issuer URLs, `AZURE_TENANT_ID` |
| `core_identity_client_id`, `bff_identity_client_id`, `migrator_identity_client_id` | `workloadIdentity.azure.clientIds.{core,bff,migrations}` |
| `postgres_fqdn` | `core.env.WZ_DB_URL` (first label = `<env-pg-server-name>`); section 8 |
| `resource_group_name`, `aks_cluster_name` | GitHub variables `AKS_RESOURCE_GROUP`, `AKS_CLUSTER_NAME` |
| `subnet_cidrs` | `networkPolicy.ingressController.ipBlocks` (appgw) and `networkPolicy.database.cidrs` (postgres) – already set in the overlays |
| `application_gateway_public_ip` / `front_door_endpoint_host` | DNS (section 9) |
| `github_deployer_client_id`, `github_environments` | GitHub variable `AZURE_CLIENT_ID` of Environment `azure-$ENV` (section 10) |
| `aks_oidc_issuer_url`, `log_analytics_workspace_id`, `environment` | Reference / evidence |

Validate locally before any PR that touches Terraform:

```bash
terraform fmt -check -recursive && terraform init -backend=false && terraform validate
```

### 6.1 Verify

```bash
az aks get-credentials -g $(terraform output -raw resource_group_name) -n $(terraform output -raw aks_cluster_name) && kubelogin convert-kubeconfig -l azurecli
```

```bash
kubectl get nodes -o wide
```

Expected: PROD 3 `system` and at least 3 `apps` nodes across zones 1–3; UAT 2 + 2; DEV 1 + 1 in zone 1 – all
`Ready`. UAT and PROD are private clusters: run the same check through command invoke instead:

```bash
az aks command invoke -g <rg> -n <aks> --command "kubectl get nodes"
```

```bash
az postgres flexible-server show -g <rg> -n <server> --query "{state:state, ha:highAvailability.state, version:version, publicAccess:network.publicNetworkAccess}" -o table
```

Expected: `Ready`, `Healthy`, `16`, `Disabled`.

## 7. Bootstrap secrets (dual control) [M]

Terraform has created all Key Vault secrets. The generated DB passwords (`wz-pg-admin-password`,
`wz-db-password`, `wz-bff-db-password`, `wz-migrator-db-password`) are written through write-only arguments and
never appear in Terraform state. Four secrets contain placeholders. Replace them now, with two people present, and
record the action in the key-ceremony log. The person running these commands needs **Key Vault Secrets Officer**;
a private runner or `key_vault_allowed_ips` must allow network access.

```bash
KV=$(terraform output -raw key_vault_name)
```

```bash
for s in wz-master-key wz-pseudonym-key wz-bff-session-key; do openssl rand -base64 32 | tr -d '\n' | az keyvault secret set --vault-name $KV -n $s --file /dev/stdin --query "{name:name, updated:attributes.updated}" -o tsv; done
```

```bash
read -rs BFF_SECRET && az keyvault secret set --vault-name $KV -n wz-bff-client-secret --value "$BFF_SECRET" --query name -o tsv; unset BFF_SECRET
```

Paste the BFF client secret from section 5.4 at the prompt; it is not echoed. Do the same for
`wz-graph-client-secret` if you use the Graph mailbox (section 13).

> `wz-master-key` encrypts field-level data and `ENC(...)` values. **Losing it makes encrypted data
> unrecoverable.** Keep the Key Vault soft-delete and purge protection (enforced) and add a key-escrow procedure,
> for example a sealed export in a second vault under dual control.

Verify (names and versions only, never values):

```bash
az keyvault secret list --vault-name $KV --query "[].{name:name, enabled:attributes.enabled, expires:attributes.expires}" -o table
```

Generate **different** key material for every environment; never copy a PROD key to DEV or UAT.

### 7.1 `ENC(...)` values for passwords that must live in configuration

In Kubernetes the database passwords are **not** in configuration at all: the CSI driver mounts them from Key Vault
and the chart passes `WZ_DB_PASSWORD=file:/mnt/secrets/core/wz-db-password` (`format: fileRef` in
`values-azure.yaml`); `platform/wz-secrets` reads the file at start-up. When a password has to live in a values file
or ConfigMap instead – for example an IMAP or SMTP relay password, or a client-specific connector in `core.extraEnv` –
store it as an `ENC(...)` value encrypted with **this environment's** `wz-master-key`:

```bash
umask 077 && KEYFILE=$(mktemp) && az keyvault secret show --vault-name $KV -n wz-master-key --query value -o tsv > $KEYFILE
```

```bash
WZ_MASTER_KEY_FILE=$KEYFILE ./tools/encrypt-secret.sh; rm -f $KEYFILE
```

The tool (`com.wellnessz.secrets.EncryptCli`) prompts for the value without echo and prints `ENC(…)`. Put it into the
overlay, for example:

```yaml
core:
  extraEnv:
    - name: WZ_IMAP_PASSWORD
      value: "ENC(…)"
```

The core decrypts it at start-up with `WZ_MASTER_KEY_FILE` (mounted from Key Vault). The value is useless in another
environment, and gitleaks ignores `ENC(...)` values only because they are ciphertext – never commit a plaintext.

## 8. Bootstrap the database

The server accepts connections only from inside the VNet. Run the idempotent bootstrap script from a temporary
pod in AKS, signed in as a member of `sg-wellnessz-db-admins` with an Entra token.

```bash
PGHOST=$(terraform output -raw postgres_fqdn); TOKEN=$(az account get-access-token --resource-type oss-rdbms --query accessToken -o tsv)
```

```bash
kubectl create namespace wz-admin --dry-run=client -o yaml | kubectl apply -f - && kubectl -n wz-admin create configmap bootstrap-sql --from-file=../../sql/bootstrap-managed-postgres.sql
```

```bash
kubectl -n wz-admin run psql --rm -it --restart=Never --image=postgres:16.15-bookworm --overrides='{"spec":{"containers":[{"name":"psql","image":"postgres:16.15-bookworm","stdin":true,"tty":true,"command":["bash"],"volumeMounts":[{"name":"sql","mountPath":"/sql"}]}],"volumes":[{"name":"sql","configMap":{"name":"bootstrap-sql"}}]}}'
```

Inside the pod, paste the values of `PGHOST` and `TOKEN` and the three passwords. Read the passwords from Key Vault
on your workstation with `az keyvault secret show --vault-name $KV -n <name> --query value -o tsv` and copy them
in; do not store them in files.

```bash
PGPASSWORD="<token>" psql "host=<pghost> dbname=wellnessz user=sg-wellnessz-db-admins-<env> sslmode=verify-full sslrootcert=system" -v migrator_pw='<wz-migrator-db-password>' -v app_pw='<wz-db-password>' -v bff_pw='<wz-bff-db-password>' -f /sql/bootstrap-managed-postgres.sql
```

Expected last line:
`WellnessZ bootstrap complete: wz_migrator owns wellnessz + schemas; wz_app/wz_bff have CONNECT only (table grants via Flyway).`

The script creates the schemas `platform, masterdata, intake, casemgmt, triage, docs, ohrecord, fitness, absence,
surveillance, scheduling, treatment, claims, mentalhealth, substance, travelhealth, promotion, analytics` (`triage`
since increment 1; `docs`, `ohrecord`, `fitness` since increment 2; `absence`, `surveillance`, `scheduling` since
increment 3; `treatment`, `claims` since increment 4; `mentalhealth`, `substance`, `travelhealth`, `promotion` since
increment 5). Run it once per environment, against that environment's server.
**Upgrading an environment bootstrapped before increment 1:** Flyway V5 creates schema `triage` itself (as
`wz_migrator`, which owns the database) and grants `USAGE` to `wz_app`, so the upgrade works without a manual step.
Re-run the idempotent bootstrap script anyway to align ownership and the roles' `search_path` with the new schema
list. The same applies to increment 2: Flyway V6, V7 and V8 create `docs`, `ohrecord` and `fitness` and grant `USAGE`
to `wz_app`; re-run the bootstrap to update `search_path`. Increment 3 is identical: Flyway V10, V11 and V12 create
`absence`, `surveillance` and `scheduling` (V9 adds platform rows only); re-run the bootstrap once. Increment 4 is
the same: Flyway V14 and V15 create `treatment` and `claims` (V13 adds the partner organisations and the `CLAIM_RECORD`
retention class); re-run the bootstrap once to update `search_path`. Increment 5 likewise: Flyway V17–V20 create
`mentalhealth`, `substance`, `travelhealth` and `promotion` (V16 adds the duty-assignment table, the partner
organisations `EAP_WELLBEING`, `TOXLAB`, `UKHSA_LOCAL` and the retention classes `RESTRICTED_RECORD`, `PUBLIC_HEALTH`).

**Who runs the migrations afterwards.** DEV (`migrations.mode: app`): core and BFF run Flyway as `wz_migrator` at
start-up. UAT and PROD (`migrations.mode: job`): the Helm pre-install/pre-upgrade Job `wellnessz-migrations` runs
Flyway with its own identity; the runtime pods never hold the migrator password. Migrations are forward-only –
a `helm rollback` does not undo them (section 12.4).

The pod is removed when you exit. Then delete the helper namespace:

```bash
kubectl delete namespace wz-admin
```

## 9. TLS certificates and DNS

**[M]** per environment – the hosts are `employee`, `manager`, `oh`, `partners` and `api`, with the suffix
`.dev.<domain>` (DEV), `.uat.<domain>` (UAT) or directly under `<domain>` (PROD), as set in
`ingress.hosts` of `environments/azure/<env>.yaml`.

1. Obtain certificates for the five hosts of the environment (a SAN or wildcard certificate per environment, for
   example `*.uat.<domain>`), either from your public CA or with Key
   Vault-integrated issuance (DigiCert/GlobalSign).
2. Import the certificate into Key Vault:

   ```bash
   az keyvault certificate import --vault-name $KV -n wellnessz-tls -f wellnessz-tls.pfx
   ```

3. Attach it to the Application Gateway. AGIC then references it with the annotation
   `appgw-ssl-certificate: wellnessz-tls`; the App Gateway's managed identity already has read access.

   ```bash
   az network application-gateway ssl-cert create -g <rg> --gateway-name <agw> -n wellnessz-tls --key-vault-secret-id $(az keyvault certificate show --vault-name $KV -n wellnessz-tls --query sid -o tsv | sed 's#/[^/]*$##')
   ```

4. Create DNS A records for the five hosts pointing to `application_gateway_public_ip`. If Front Door is enabled,
   use CNAMEs to the Front Door endpoint and validate the custom domains there (add `partners.<domain>` as a custom
   domain).
5. **Exposure (increment 4).** `partners.<domain>` is the host for users outside the airline and must be reachable
   from the internet. `manager.<domain>` and `oh.<domain>` should preferably be reachable only from the corporate
   network: serve them on the App Gateway **private** listener (ExpressRoute / VPN), or add a custom WAF rule that
   allows only the corporate egress ranges for those host names (`RequestHeaders['host']` match plus
   `RemoteAddr` not in the allow-list → *Block*). Keep the rate-limit rule on `/oauth2/` and `/login/` for every host;
   for `partners.<domain>` consider a per-listener WAF policy with Bot Manager in prevention mode and an optional geo
   filter for the partner-network countries.

Verify:

```bash
dig +short employee.wellnessz.example.com
```

Expected: the App Gateway IP (or the Front Door address).

## 10. GitHub configuration: Environments, OIDC federation, required reviewers

Detailed rationale is in the CI/CD Guide (document 09). Terraform has already created the **OIDC federation**: the
deployer identity of each environment (output `github_deployer_client_id`) has a federated credential for
`repo:<owner/repo>:environment:azure-<env>` only, and – in DEV only – for `…:ref:refs/heads/main` so that
`container.yml` may push branch builds into the DEV ACR (`github_image_publish_branches = ["main"]`). No client secret
exists anywhere.

### 10.1 GitHub Environments [M]

> **Manual – cannot be set from code.** Environments, their protection rules and required reviewers are repository
> settings (*Settings → Environments*). Configure them with a repository administrator and keep a screenshot as
> SOC 2 CC8.1 evidence.

| Environment | Deployment branches and tags | Required reviewers | Other protection |
|---|---|---|---|
| `azure-dev` | branch `main` + tags `v*` | none (automatic) | – |
| `azure-uat` | tags `v*` | **yes** – QA lead / business acceptance lead (at least one) | *Prevent self-review* on |
| `azure-prod` | tags `v*` | **yes** – CAB / security group (at least two people recommended) | *Prevent self-review* on; optional wait timer (for example 30 minutes); *Allow administrators to bypass* **off** |

### 10.2 Environment variables (no secrets) [A-ready values, entered once]

Set these on **each** of the three Environments from the `terraform output` of the same environment:

| Variable | Value |
|---|---|
| `AZURE_CLIENT_ID` | `github_deployer_client_id` |
| `AZURE_TENANT_ID` | `tenant_id` |
| `AZURE_SUBSCRIPTION_ID` | the environment's subscription |
| `AKS_RESOURCE_GROUP`, `AKS_CLUSTER_NAME` | `resource_group_name`, `aks_cluster_name` |
| `AKS_PRIVATE` | `false` (DEV) / `true` (UAT, PROD – the workflow then uses `az aks command invoke`) |
| `IMAGE_REGISTRY` | `<acr_login_server>/wellnessz` |
| `HELM_RELEASE`, `K8S_NAMESPACE` | `wellnessz` / `wellnessz` (defaults; must match Terraform `helm_release_name` / `k8s_namespace`) |
| `SMOKE_URLS` | optional, space separated, for example `https://employee.uat.<domain>/ https://oh.uat.<domain>/ https://partners.uat.<domain>/` |
| `EXTRA_VALUES_FILES` | optional client overlay, for example `deploy/clients/acme/values.yaml` |

With the GitHub CLI (repository admin), for example for UAT:

```bash
gh variable set AZURE_CLIENT_ID --env azure-uat --body "$(jq -r .github_deployer_client_id.value /tmp/wz-azure-uat-outputs.json)"
```

### 10.3 Repository variables and secret

| Variable | Value |
|---|---|
| `DEPLOY_CLOUDS` | `["azure"]` (or `["azure","gcp"]`) – clouds that `cd.yml` deploys |
| `RELEASE_REGISTRY` | registry that `container.yml` pushes to (default `ghcr.io/<owner>/wellnessz`) |
| `ACR_NAME` + repository-level `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID` | Only if `container.yml` should publish straight to the DEV ACR (manual run with `registry = acr`); the default publishes to GHCR and `deploy-env.yml` copies by digest |
| `DEPLOY_RUNNER` | optional self-hosted runner label (alternative to `az aks command invoke` for private clusters) |
| Secret `NVD_API_KEY` | OWASP dependency-check (the only repository secret) |

Also **[M]**: branch protection on `main` (PR, 2 approvals, Code Owners, required checks `CI` and `CodeQL`, signed
commits, linear history), secret scanning with push protection, Dependabot alerts.

### 10.4 Cluster objects for the deployer [M, once per cluster]

The deployer identity holds *AKS RBAC Writer* on the namespace; it additionally needs the namespace itself and a Role
for the CSI CRD. Create both once per environment as a cluster admin (UAT/PROD through `az aks command invoke`):

```bash
kubectl create namespace wellnessz && kubectl label namespace wellnessz pod-security.kubernetes.io/enforce=restricted
```

```bash
kubectl -n wellnessz create role spc-writer --verb=get,list,watch,create,update,patch,delete --resource=secretproviderclasses.secrets-store.csi.x-k8s.io
```

```bash
kubectl -n wellnessz create rolebinding spc-writer --role=spc-writer --user=$(jq -r '.github_deployer_client_id.value' /tmp/wz-azure-$ENV-outputs.json)
```

With Azure RBAC for Kubernetes, the user name is the identity's object ID. Use
`az identity show --query principalId` if the client ID is not accepted.

## 11. Per-environment Helm values

The chart is layered `values.yaml` → `values-azure.yaml` → `environments/azure/<env>.yaml` (later wins) – the same
order that `deploy/scripts/helm-release.sh` and `deploy-env.yml` use. The overlay already carries everything that
differs per environment (replicas, PDBs, resources, hosts, `migrations.mode`, network CIDRs, log level, trace sampling,
`WZ_MFA_REQUIRED_ROLES`, `global.requireImageDigests`). **[A]** Replace its `<placeholders>` with the outputs of the
same environment and commit the change through a PR (identifiers only – no secrets):

| Placeholder | Source |
|---|---|
| `<env-acr-name>` | `acr_login_server` |
| `<env-tenant-id>` | `tenant_id` |
| `<core-identity-client-id>`, `<bff-identity-client-id>`, `<migrator-identity-client-id>` | Terraform outputs |
| `<key-vault-name>` | `key_vault_name` |
| `<pg-server-name>` | First label of `postgres_fqdn` |
| `<wellnessz-api-client-id>` | `$API_APP_ID` (audience) |
| `<bff-app-registration-client-id>` | `$BFF_APP_ID` |
| `<ehs-mailbox-app-client-id>` | Section 13 |
| Hostnames | Replace `wellnessz.example.com` with your domain in `ingress.hosts.*`, `ingress.api.host`, `core.env.WZ_CORS_ORIGINS`, the four `WZ_*_URL` notification links and `bff.env.WZ_BFF_POST_LOGOUT_URI` |
| `networkPolicy.ingressController.ipBlocks`, `networkPolicy.database.cidrs` | Already the `subnet_cidrs` of the tfvars (`10.48/10.44/10.40` …) – change only together with the tfvars |

Validate all three overlays before committing (the CI job *IaC & config checks* does the same):

```bash
for e in dev uat prod; do helm lint deploy/helm/wellnessz -f deploy/helm/wellnessz/values.yaml -f deploy/helm/wellnessz/values-azure.yaml -f deploy/helm/wellnessz/environments/azure/$e.yaml --set-string core.image.digest=sha256:$(printf '0%.0s' $(seq 64)) --set-string bff.image.digest=sha256:$(printf '0%.0s' $(seq 64)) --set-string employeePortal.image.digest=sha256:$(printf '0%.0s' $(seq 64)) --set-string managerWorkspace.image.digest=sha256:$(printf '0%.0s' $(seq 64)) --set-string ohWorkspace.image.digest=sha256:$(printf '0%.0s' $(seq 64)) --set-string partnerPortal.image.digest=sha256:$(printf '0%.0s' $(seq 64)); done
```

(UAT and PROD set `global.requireImageDigests: true`, so rendering fails without a digest for every component – the
dummy digests above are for linting only.)

Increment 1 also adds these core settings (put them under `core.env` in the environment overlay):

| Variable | Value |
|---|---|
| `WZ_OH_WORKSPACE_URL` | `https://oh.<domain>` – link in OH-team notification e-mails. Set in `values.yaml` to `https://oh.wellnessz.example.com` – override it with your domain |
| `WZ_OH_CONTACT` | Contact line in stakeholder notifications, for example `Occupational Health service desk, ext. 4242` |
| `WZ_SLA_POLL_INTERVAL` | Optional; default `PT60S` |

Increment 2 adds these core settings:

| Variable | Value |
|---|---|
| `WZ_EMPLOYEE_PORTAL_URL` | `https://employee.<domain>` – link in employee notifications (certificates, documents, job offers). Set in `values.yaml` (`https://employee.wellnessz.example.com`) – override it with your domain |
| `WZ_MANAGER_WORKSPACE_URL` | `https://manager.<domain>` – link in manager and HR notifications. Set in `values.yaml` (`https://manager.wellnessz.example.com`) – override it with your domain |
| `WZ_DPO_EMAIL` | Mailbox of the DPO (shared mailbox recommended) for content-free break-glass alerts |
| `WZ_DOCUMENTS_RETENTION_CRON` | Optional; default `0 30 2 * * *` (UTC) |
| `WZ_DIAGNOSIS_REQUIRED_ON_CLOSE` | Optional; default `true` – keep it in prod |
| `WZ_DOCUMENTS_FONT_PATH` | Optional; path of a Unicode TrueType font **inside the image** (for Arabic certificates). The chart has no extra-volume hook, so build a derived core image that adds the font (for example under `/opt/fonts`) in your pipeline, scanned and signed like the base image |

**Optional OCR.** Scanned images are not OCR'd by default. Two options:

* **Tesseract in the cluster** – derived core image with `tesseract-ocr` and the `eng`, `ara`, `fra` language packs,
  then `core.extraEnv`: `WELLNESSZ_DOCUMENTS_OCR_TESSERACTPATH=/usr/bin/tesseract` and
  `WELLNESSZ_DOCUMENTS_OCR_LANGUAGES=eng+ara+fra` (allow-listed values only). No data leaves the cluster.
* **Azure AI Document Intelligence** – an extension point (`OcrProvider` SPI), not shipped. It needs an adapter, a
  resource in the same region with a private endpoint and disabled public access, the core's managed identity with
  *Cognitive Services User*, a `networkPolicy.extraEgress.core` rule to the private endpoint, and a DPIA. Do not
  enable it without DPO approval.

Increment 3 adds **no** environment variable that must be set. Optional core settings (`core.extraEnv`):

| Variable | Value |
|---|---|
| `WELLNESSZ_RECORD_REQUIREREGISTEREDDEVICES` | `true` once every audiometer, spirometer, ECG and vision screener that records results is in the equipment register (default `false`: unregistered devices are accepted, registered ones are checked for calibration) |
| `WELLNESSZ_ABSENCE_FEEDCRON`, `WELLNESSZ_ABSENCE_TRIGGERSCRON`, `WELLNESSZ_ABSENCE_LTACRON`, `WELLNESSZ_SURVEILLANCE_DUEJOBCRON`, `WELLNESSZ_SURVEILLANCE_CREWJOBCRON`, `WELLNESSZ_SURVEILLANCE_PURGEJOBCRON`, `WELLNESSZ_SCHEDULING_REMINDERCRON`, `WELLNESSZ_SCHEDULING_NOSHOWCRON`, `WELLNESSZ_SCHEDULING_CALIBRATIONCRON` | Optional job schedules (UTC); defaults in doc 01, Section 21.3 – keep the absence feed before the trigger run |

**Business configuration after the first deployment (increment 3).** Mailboxes, the telehealth link and the
holiday calendar are E18 configuration, not Helm values. Set them once per environment as an OH_ADMIN or
PLATFORM_ADMIN user (each change is audited), for example:

```bash
curl -s -X PUT -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' https://api.<domain>/api/v1/masterdata/config/absence.hr-email -d '{"value":"hr-absence@<domain>","description":"HR absence distribution list (status only)"}'
```

| Key | Set to |
|---|---|
| `absence.hr-email` | HR absence mailbox (status-only notifications) |
| `notifications.hr-onboarding.email` | HR onboarding mailbox (pre-employment status) |
| `notifications.crew-licensing.email` | Crew training & licensing mailbox (crew medical expiry) |
| `notifications.biomedical.email` | Biomedical engineering mailbox (calibration-due alerts; cold-chain excursions go to the site's clinicians and the Nursing Head from the OH staff roster) |
| `scheduling.telehealth-base-url` | Join-URL prefix of the video service approved by the DPO (doc 01, D15); leave the default only if telehealth appointment types are disabled |
| `absence.public-holidays` | Public holidays of the operating country (comma-separated ISO dates) for the post-holiday absence pattern |

Increment 4 adds these core settings (`core.env`):

| Variable | Value |
|---|---|
| `WZ_PARTNER_PORTAL_URL` | `https://partners.<domain>` – link in partner and adjuster notifications. Set in `values.yaml` to `https://partners.wellnessz.example.com` (in line with `ingress.hosts.partnerPortal`) – override it with your domain, as for the other portal URLs |
| `WZ_MFA_REQUIRED_ROLES` | Leave unset (default `PARTNER_PROVIDER,TPA_ADJUSTER`). Never set it empty outside local development |
| `WELLNESSZ_TREATMENT_VARIANCEJOBCRON`, `WELLNESSZ_TREATMENT_CHASEJOBCRON`, `WELLNESSZ_TREATMENT_SCORECARDJOBCRON`, `WELLNESSZ_CLAIMS_REPORTDEADLINESCRON` (`core.extraEnv`) | Optional job schedules (UTC); defaults in doc 01, Section 22.3 |

The partner portal component is enabled by default (`partnerPortal.enabled: true`, two replicas, HPA 2–4, PDB);
set `ingress.hosts.partnerPortal` in the environment overlay. To keep it off in an environment that has no partners yet,
set `partnerPortal.enabled: false`.

**Business configuration after the first deployment (increment 4).** As an OH_ADMIN or PLATFORM_ADMIN user:

| Key | Set to |
|---|---|
| `claims.mailbox-email` | Claims team mailbox (report deadlines, late reports) |
| `claims.legal-email` | Legal mailbox (subrogation and legal requests) |
| `claims.employer-ref`, `claims.employer-name` | Employer reference and name used on statutory first reports |
| `claims.fx-rates` | Exchange rates to the base currency `GBP` (`claims.base-currency`), for example `GBP:1,AED:0.21,USD:0.79,EUR:0.85` – review monthly |
| `treatment.telehealth.base-url` | Join-URL prefix of the approved video platform (doc 01, D15) |
| `treatment.network.cities` | Hub, outstation and layover cities for the network-gap report |

Procurement replaces the illustrative partner network with the contracted one (`POST /api/v1/masterdata/partner-organisations`,
`PUT /api/v1/providers/{code}`, `POST /api/v1/providers/{code}/contracts`); report forms stay pending until Legal / HSE
sign-off (`claims.forms.sign-off`).

Increment 5 adds **no** required Helm value. Optional `core.extraEnv`: job schedules
`WELLNESSZ_MENTALHEALTH_CISMFOLLOWUPSCRON`, `WELLNESSZ_MENTALHEALTH_PEERCALLBACKPURGECRON`,
`WELLNESSZ_TRAVELHEALTH_DAILYCRON`, `WELLNESSZ_TRAVELHEALTH_EXPOSURETIMERCRON` (default every 5 minutes – keep it
short), `WELLNESSZ_TRAVELHEALTH_PUBLICHEALTHCRON` (UTC). Keep `WZ_ANALYTICS_MIN_GROUP` at its default `5` (small-cell
suppression) in UAT and PROD.

**Business configuration after the first deployment (increment 5).** As an OH_ADMIN or PLATFORM_ADMIN user, per
environment:

| Key / data | Set to |
|---|---|
| `mentalhealth.named-clinicians` | **Must be changed in UAT and PROD.** Flyway V17 sets it to the demo user `psych1` in every environment. Set the comma-separated usernames (as they appear in the token) of the named mental-health clinicians who may read restricted E09 content; empty means every `PSYCH_CLINICIAN` |
| `substance.laboratory-code` | Partner code of the contracted toxicology laboratory (default `TOXLAB`) – must equal the `org_scope` suffix of the laboratory client (section 5.6) |
| `substance.acting-mro-physicians` | Usernames of OH physicians allowed to act as MRO (empty by default – leave empty unless the MRO contract names deputies) |
| Testing policies (`PUT /api/v1/testing/policies/{roleGroup}`) | Approved policy per role group (rates, panels, cut-offs) – **MRO / Legal sign-off** before PROD |
| `promotion.screening.threshold.*`, PHQ-9 / GAD-7 bands, travel recommendations, PEP timers | Reviewed values – **Clinical Governance sign-off** before PROD |
| Partner organisations | Replace the illustrative `EAP_WELLBEING` / `TOXLAB` / `UKHSA_LOCAL` entries with the contracted EAP, laboratory and the competent public-health authority (`POST /api/v1/masterdata/partner-organisations`) |

Do not set `WZ_FLYWAY_LOCATIONS` to include `classpath:db/demo` in any shared environment; with `migrations.mode: job`
the migration Job reads only `db/migration`. The OH staff roster (`masterdata.staff_member`) has no import yet: load it
with a reviewed SQL script as `wz_migrator` and push HRIS employees to `POST /api/v1/masterdata/employees`.

`values.schema.json` rejects invalid values. For example, switching off the read-only root filesystem fails.

**Client customisation.** Per-client settings (`customisation.client`, `customisation.featureFlags` →
`WZ_FEATURE_<NAME>`, client Flyway migrations in schema `client_<client>`) go into
`deploy/clients/<client>/values.yaml`, referenced by the Environment variable `EXTRA_VALUES_FILES` – see
`deploy/environments/README.md`. Feature flags are delivered to core and BFF but no product feature reads them yet. A client migration image
(`customisation.migrations.image`) is **not** handled by `deploy-env.yml`, which signs, verifies and copies only the
six product images: the client's own pipeline must build, sign and copy it by digest into the environment ACR before
the release that references it.

## 12. Build, sign and deploy

### 12.1 Images – built once (CI) [A]

Every push to `main` runs **Container images** (`container.yml`): six images built once, Trivy gate (CRITICAL/HIGH),
push to the release registry (`RELEASE_REGISTRY`, default GHCR) with OIDC, SBOM and SLSA provenance attestations,
cosign keyless signature, tag `sha-<12>`. Release tags are **not** rebuilt.

Verify a signature from your workstation:

```bash
cosign verify ghcr.io/OWNER/wellnessz/wz-core-app:sha-<12> --certificate-identity-regexp "^https://github.com/OWNER/WellnessZ/.github/workflows/container.yml@" --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

### 12.2 First deployment of an environment

Pre-conditions: sections 3–11 done for this environment, overlay committed on `main`, GitHub Environment configured.

**DEV – automatic.** When `container.yml` succeeds on `main`, `cd.yml` resolves the digests once, verifies the
signatures and calls `deploy-env.yml` for `azure-dev` (and `gcp-dev` if `DEPLOY_CLOUDS` contains `gcp`). To deploy a
specific build by hand: **Actions → Deploy environment → Run workflow** with cloud `azure`, environment `dev`,
image tag `sha-<12>` – or with the CLI:

```bash
gh workflow run deploy-env.yml -f cloud=azure -f environment=dev -f image_tag=sha-<12>
```

**UAT – release candidate.** Tag the commit that passed DEV; `cd.yml` deploys DEV, then waits for the **required
reviewers** of `azure-uat` [M approval in the Actions run], then deploys UAT with the **same digests**:

```bash
git tag -s v1.0.0-rc.1 -m "WellnessZ 1.0.0 RC1" && git push origin v1.0.0-rc.1
```

**PROD – release.** Tag the final release; `cd.yml` runs DEV → UAT → PROD, each later stage after its reviewers
approve (PROD: CAB) [M]:

```bash
git tag -s v1.0.0 -m "WellnessZ 1.0.0" && git push origin v1.0.0
```

To start the chain at a later stage (for example re-deploy an approved release to PROD only):

```bash
gh workflow run cd.yml -f cloud=azure -f start_at=prod -f image_tag=v1.0.0
```

What each stage does (`deploy-env.yml`): checks the promotion policy (DEV `sha-*`/`v*`, UAT `vX.Y.Z[-rc.N]`, PROD
`vX.Y.Z`), logs in with OIDC, verifies the cosign signatures, copies the images **by digest** into the environment ACR
(`cosign copy`, signatures and attestations included), runs `helm upgrade --install --atomic` with the layered values
and every component pinned by digest, runs the smoke tests and, if they fail, rolls back automatically.

### 12.3 Operator path: `deploy/scripts/helm-release.sh`

The workflow calls `deploy/scripts/helm-release.sh`; operators can use the same script with the same inputs (for
example for a first install before GitHub is configured, or in an incident). It needs bash, helm and kubectl and a
kube context for the environment (`az aks get-credentials`; private clusters: run it inside `az aks command invoke`
with a `release.env` file, as the workflow does).

```bash
cat > digests.txt <<'EOF'
wz-core-app sha256:<64 hex>
wz-bff sha256:<64 hex>
employee-app sha256:<64 hex>
manager-workspace sha256:<64 hex>
oh-workspace sha256:<64 hex>
partner-portal sha256:<64 hex>
EOF
```

Take the digests from the summary of the `container.yml` run (or `crane digest <registry>/wellnessz/<image>:<tag>`);
the images must exist in the environment registry (`cosign copy` them first for UAT/PROD).

```bash
export CLOUD=azure DEPLOY_ENV=$ENV RELEASE=wellnessz NAMESPACE=wellnessz IMAGE_REGISTRY=$(jq -r .acr_login_server.value /tmp/wz-azure-$ENV-outputs.json)/wellnessz IMAGE_TAG=v1.0.0
```

```bash
PREV=$(deploy/scripts/helm-release.sh previous); deploy/scripts/helm-release.sh upgrade && deploy/scripts/helm-release.sh smoke || { [ -n "$PREV" ] && deploy/scripts/helm-release.sh rollback "$PREV"; }
```

Expected: `STATUS: deployed`; in UAT/PROD the pre-upgrade Job `wellnessz-migrations` completed (hook); all pods
`Running` and `Ready` with the promoted digests.

```bash
kubectl -n wellnessz get pods,hpa,pdb,secretproviderclass,networkpolicy
```

The migration Job is deleted after success (hook policy). If it failed, the release rolls back (`--atomic`) and the
Job log shows the reason: `kubectl -n wellnessz logs job/wellnessz-migrations -c flyway-core` while it exists.

### 12.4 Database migrations and rollback

* **Migrations** run automatically: DEV inside the core/BFF at start-up, UAT/PROD in the pre-upgrade Job before any
  pod is replaced. A failed migration fails the Helm release, and `--atomic` keeps the previous pods running.
* **Rollback** (`helm-release.sh rollback <revision>`, automatic after failed smoke tests, or
  `helm rollback wellnessz <revision> -n wellnessz`) restores the previous pods and values. It does **not** revert
  the database: Flyway migrations are forward-only and written as expand/contract, so release N-1 keeps working on
  schema N. A data problem that needs the old schema is handled with point-in-time restore (section 17, DR test).

## 13. EHS mailbox: IMAP or Microsoft Graph

| Mode | When | Configuration |
|---|---|---|
| `graph` (recommended for Exchange Online) | The EHS system sends to an M365 shared mailbox | Settings in `values-azure.yaml`: `WZ_GRAPH_TENANT_ID`, `WZ_GRAPH_CLIENT_ID`, `WZ_GRAPH_MAILBOX`, `WZ_EHS_ALLOWED_SENDERS`; secret `wz-graph-client-secret` |
| `imap` | On-prem Exchange or another mail server | `WZ_IMAP_HOST`, `WZ_IMAP_PORT=993`, `WZ_IMAP_SSL=true`, `WZ_IMAP_USERNAME`; add `WZ_IMAP_PASSWORD` as a `fileRef` entry in `secrets.core.files` |
| `none` | Excel import only | – |

**Graph with least privilege – Exchange RBAC for Applications.** Do not grant tenant-wide `Mail.ReadWrite` in
Entra ID. Scope the application to the single EHS mailbox instead:

1. Create the mailbox reader app and service principal. Do **not** add Graph API permissions in Entra.

   ```bash
   EHS_APP_ID=$(az ad app create --display-name wellnessz-ehs-mailbox --query appId -o tsv) && az ad sp create --id $EHS_APP_ID && az ad app credential reset --id $EHS_APP_ID --years 0.5 --query password -o tsv
   ```

   Store the password in Key Vault as `wz-graph-client-secret` (section 7). A certificate credential is preferred
   where your policy allows.
2. In Exchange Online PowerShell (`Connect-ExchangeOnline`), register the service principal and scope the role:

   ```powershell
   New-ServicePrincipal -AppId <EHS_APP_ID> -ObjectId <service-principal-object-id> -DisplayName "WellnessZ EHS mailbox"
   ```

   ```powershell
   New-ManagementScope -Name "WellnessZ EHS mailbox" -RecipientRestrictionFilter "PrimarySmtpAddress -eq 'ehs-feed@airline.example.com'"
   ```

   ```powershell
   New-ManagementRoleAssignment -App <EHS_APP_ID> -Role "Application Mail.ReadWrite" -CustomResourceScope "WellnessZ EHS mailbox"
   ```

3. Verify the scope:

   ```powershell
   Test-ServicePrincipalAuthorization -Identity <EHS_APP_ID> -Resource ehs-feed@airline.example.com
   ```

   Expected: `InScope: True` for the EHS mailbox. For any other mailbox the result must be `False`.

4. Create a `Processed` folder in the mailbox. The reader moves handled messages there.
5. Restrict accepted senders with `WZ_EHS_ALLOWED_SENDERS` (domain suffix or addresses). Mails from other senders
   are ignored and logged.

**Smoke test.** Send a mail from the EHS system (or an allowed test sender) with these body lines:

```text
Incident ID: INC-TEST-001
Date: 2026-10-28 14:30
Location: LHR Hangar 2
Employee ID: E1001
Severity: Low
Injury: No
Description: Test incident
```

After the next poll (default 5 minutes), the incident appears in the OH workspace and in
`GET /api/v1/intake/ehs/incidents`. To poll immediately, an `OH_ADMIN` calls
`POST /api/v1/intake/ehs/mailbox/poll`.

**Excel import test.** As `OH_ADMIN` or `CASE_MANAGER`, upload the sample workbook described in Setup guide 1,
section 8.2, through the workspace or `POST /api/v1/intake/ehs/excel`. The response reports `rowsAccepted`,
`rowsDuplicate` and `rowsRejected`.

## 14. Smoke tests and acceptance

The pipeline's own smoke test (rollout, running digests, `SMOKE_URLS`) runs on every deployment. The checks below
are the **acceptance** of a newly built environment and of each release in UAT; run them in DEV first. Checks marked
*DEV only* use the end-to-end scripts, which need the local demo users and demo data – never load demo data into UAT
or PROD; in UAT use named test accounts with the same roles instead. In PROD run checks 1–7, 9–14, 33, 35–37, 43–47
with dedicated, MFA-enrolled smoke-test accounts that hold no clinical access beyond what each check needs, and remove
their access after go-live.

| # | Check | Command / action | Expected |
|---|---|---|---|
| 1 | Pods healthy | `kubectl -n wellnessz get pods` | All `Running`, readiness `1/1` |
| 2 | Readiness inside the cluster | `kubectl -n wellnessz exec deploy/wellnessz-core -- bash -c 'exec 3<>/dev/tcp/127.0.0.1/8081; printf "GET /actuator/health/readiness HTTP/1.0\r\n\r\n" >&3; cat <&3' \| tail -1` | `{"status":"UP"}` |
| 3 | TLS and WAF | `curl -sI https://employee.<domain>/` | `200`, HSTS header; `curl -s "https://employee.<domain>/?q=<script>alert(1)</script>"` returns `403` (WAF) |
| 4 | OIDC login | Browser: `https://employee.<domain>/` → *Sign in* | Entra login, MFA, back to the portal; cookie `WZSESSION` HttpOnly + Secure |
| 5 | Status-only rule | Manager account opens the workspace | Case status visible, no clinical text |
| 6 | API host | `curl -s -o /dev/null -w '%{http_code}' https://api.<domain>/api/v1/me` | `401` without a token |
| 7 | Management port not exposed | `curl -s -o /dev/null -w '%{http_code}' https://api.<domain>/actuator/env` | `404` |
| 8 | End-to-end | `WZ_E2E_EMPLOYEE_URL=https://employee.<domain> WZ_E2E_WORKSPACE_URL=https://manager.<domain> WZ_E2E_IDP_HOST=login.microsoftonline.com node tools/e2e/e2e.js` | Adapt the login step for Entra test accounts (MFA-exempt test users in a dedicated group, DEV/UAT only) |
| 9 | Audit chain | `GET /api/v1/audit/verify` as `AUDITOR` | Chain valid |
| 10 | Logs | Log Analytics: `ContainerLogV2 \| where PodNamespace == "wellnessz" \| take 20` | JSON logs, no PHI in clear text |
| 11 | EHS intake | Section 13 | Incident visible |
| 12 | OH workspace (increment 1) | `curl -sI https://oh.<domain>/`, then browser login as a Nursing Head test account | `200` with HSTS; *Work queues* screen with counts; a reception account sees only *Reception* |
| 13 | Modules | `GET /api/v1/modules` with any user token | E01 and E02 `IMPLEMENTED` |
| 14 | New roles in tokens | Decode an access token of a CMO test user (<https://jwt.ms>) | `"roles"` contains `CMO` |
| 15 | EHS webhook | Section 5.6 smoke call | `202`; repeating with the same key returns `Idempotent-Replayed: true` |
| 16 | Status-only APIs | `GET https://api.<domain>/api/v1/status/crew?employeeIds=<id>` with a crew-scheduling token | Rows with `fitnessStatus`, `restrictionUntil` only |
| 17 | Increment-1 end-to-end (DEV only) | `WZ_E2E_EMPLOYEE_URL=https://employee.<domain> WZ_E2E_WORKSPACE_URL=https://manager.<domain> WZ_E2E_OH_URL=https://oh.<domain> WZ_E2E_CORE_URL=https://api.<domain> node tools/e2e/e2e-increment1.js` | Requires dedicated test accounts with the local user names and an adapted login step for Entra; 18 `PASS` lines |
| 18 | Increment-2 schemas | Bootstrap pod (section 8): `psql … -c '\dn'` | `docs`, `ohrecord`, `fitness` owned by `wz_migrator` |
| 19 | Modules (increment 2) | `GET /api/v1/modules` with any user token | E03 and E05 `IMPLEMENTED` |
| 20 | DPO segregation | Browser login on `https://oh.<domain>` as a DPO test account; then open an employee chart URL | *Break-glass reviews*, *Disclosures* and *Retention* load; the chart is not shown (the API answers `403` or `404`) |
| 21 | Break-glass alert | As a clinician test account, declare break-glass on a test employee | E-mail to `WZ_DPO_EMAIL` without clinical content; review item visible to the DPO |
| 22 | Documents | Generate and sign a fitness certificate for a test employee (OH workspace) and download it | PDF opens; Arabic names render if a font is configured |
| 23 | Gateway pull queue | Section 5.6 smoke call with a gateway token | `200`; the same call with a user token returns `403` |
| 24 | Increment-2 end-to-end (DEV only) | `WZ_E2E_EMPLOYEE_URL=https://employee.<domain> WZ_E2E_WORKSPACE_URL=https://manager.<domain> WZ_E2E_OH_URL=https://oh.<domain> WZ_E2E_CORE_URL=https://api.<domain> node tools/e2e/e2e-increment2.js` | Requires test accounts with the local user names (including `dpo1`, `ame1`, `cmo1`, `crewsched1`) and an adapted login step for Entra; 25 `PASS` lines |
| 25 | Increment-3 schemas | Bootstrap pod (section 8): `psql … -c '\dn'` | `absence`, `surveillance`, `scheduling` owned by `wz_migrator` |
| 26 | Modules (increment 3) | `GET /api/v1/modules` with any user token | E06, E07 and E14 `IMPLEMENTED` |
| 27 | New roles in tokens | Decode access tokens of scheduler, biomedical and crew-licensing test users | `"roles"` contains `CLINIC_SCHEDULER`, `BIOMEDICAL`, `CREW_LICENSING` respectively |
| 28 | Scheduler segregation | Browser login on `https://oh.<domain>` as a scheduler test account | *Clinic diary* and *Due list* load; `GET /api/v1/absence/queue` answers `403` |
| 29 | Upload through the BFF | Employee test account uploads a sick certificate (photo) in the employee portal | Upload succeeds (a `400` points to a BFF with multipart parsing enabled – keep the shipped default) |
| 30 | Calibration block | Record a device result (OH workspace or device gateway) with a device that is overdue in *Equipment & cold chain* | `422 device_out_of_calibration`; accepted after a passing calibration is recorded |
| 31 | Cold-chain gateway | `POST https://api.<domain>/api/v1/integration/cold-chain/readings` with the IoT gateway token and a registered fridge id | `200` with `accepted: 1`; a value outside 2–8 °C opens an excursion and sends the urgent, content-free `coldchain.excursion` notice to the site's clinicians and the Nursing Head |
| 32 | Increment-3 end-to-end (DEV only) | `WZ_E2E_EMPLOYEE_URL=https://employee.<domain> WZ_E2E_WORKSPACE_URL=https://manager.<domain> WZ_E2E_OH_URL=https://oh.<domain> WZ_E2E_CORE_URL=https://api.<domain> node tools/e2e/e2e-increment3.js` | Requires the increment-3 demo data (dev only – never `db/demo` in shared environments, so run it against a dev database prepared with the demo files), test accounts with the local user names (including `scheduler1`, `biomed1`, `crewlicensing1`, `candidate1`) and an adapted login step; 32 `PASS` lines |
| 33 | Partner portal | `curl -sI https://partners.<domain>/` | `200` with HSTS and `Content-Security-Policy`; `kubectl -n wellnessz get deploy wellnessz-partner-portal` ready 2/2 |
| 34 | Increment-4 schemas and modules | Bootstrap pod: `psql … -c '\dn'`; `GET /api/v1/modules` | `treatment`, `claims` owned by `wz_migrator`; E04 and E08 `IMPLEMENTED` |
| 35 | MFA gate – positive | Partner test guest (in `sg-wellnessz-partners`, `org_scope=/PARTNERS/<CODE>`) signs in on `https://partners.<domain>` with MFA | Referral inbox of that organisation; `https://partners.<domain>/bff/api/me` lists `PARTNER_PROVIDER` |
| 36 | MFA gate – negative | Same account while the Conditional Access policy is report-only (or a token without `amr: mfa`) | Portal shows *multi-factor sign-in required*; `GET /bff/api/partner/referrals` answers `403` |
| 37 | Organisation isolation | Partner test account opens a referral id of another organisation (`/#/referrals/<id>`) | *Not found or not shared with your organisation* (`404`) |
| 38 | Adjuster | Adjuster test guest (`/PARTNERS/<PAYER>`) opens *Claims* and *Pre-authorisations* | Only claims where its organisation is payer; RTW status only; a message with clinical wording → `422` |
| 39 | Non-clinical roles | Claims, finance, legal and procurement test accounts on `https://oh.<domain>` | Their menus only; an employee chart URL is not shown (`403`/`404`) |
| 40 | FHIR partner endpoint | `GET https://api.<domain>/api/v1/integration/referrals/<id>/fhir` with the token of the referral's provider system (`org_scope=/PARTNERS/<provider>`) | `200` with `"resourceType": "ServiceRequest"`; another partner's or an unbound gateway client's token gets `404`, user tokens get `403` |
| 41 | Notifications | Send a test referral to a partner test organisation | Content-free `referral.sent` e-mail with a link to `https://partners.<domain>/#/referrals/<id>` (a `partners.wellnessz.example.com` or `partners.wellnessz.local` link means `WZ_PARTNER_PORTAL_URL` was not overridden with your domain) |
| 42 | Increment-4 end-to-end (DEV only) | `WZ_E2E_EMPLOYEE_URL=https://employee.<domain> WZ_E2E_WORKSPACE_URL=https://manager.<domain> WZ_E2E_OH_URL=https://oh.<domain> WZ_E2E_PARTNER_URL=https://partners.<domain> WZ_E2E_CORE_URL=https://api.<domain> node tools/e2e/e2e-increment4.js` | Requires a fresh dev database with the increment-3 and increment-4 demo data (the increment-3 and increment-4 scripts consume demo state – run the scripts 1 → 4 in order), test accounts with the local user names (including `claims1`, `adjuster1`, `legal1`, `finance1`, `procurement1`, `physio1`), an adapted login step for Entra **with MFA** (or a dev core with `WZ_MFA_REQUIRED_ROLES` unset for partner test accounts only in dev); 22 `PASS` lines |
| 43 | Increment-5 schemas and modules | Bootstrap pod: `psql … -c '\dn'`; `GET /api/v1/modules` | `mentalhealth`, `substance`, `travelhealth`, `promotion` owned by `wz_migrator`; E09, E10, E11 and E12 `IMPLEMENTED` |
| 44 | New roles in tokens | Decode access tokens of MRO, programme-manager and health-promotion test users | `"roles"` contains `MRO`, `DA_PROGRAMME_MANAGER`, `HEALTH_PROMOTION_LEAD` respectively |
| 45 | Restricted clinical class | A general OH clinician test account opens the chart of a test employee with a support case; then the named psych-clinician test account | Clinician: placeholder *1 restricted item – hidden*, the case URL answers `404`; psych clinician: the case opens; any break-glass shows in the DPO review |
| 46 | Programme manager without results | Programme-manager test account opens *Testing → MRO* | `403`; selections and statistics load, small cells suppressed |
| 47 | Laboratory binding | `POST https://api.<domain>/api/v1/integration/testing/lab-results` with the TOXLAB client token, then with another gateway token | Bound client: `200` (or `422` for an unknown specimen); other client: `404` and an audit entry |
| 48 | EAP partner | EAP test guest (`/PARTNERS/<EAP code>`) signs in with MFA on `https://partners.<domain>` → *EAP referrals* | Only its organisation's referrals with minimal data; feedback with a free-text field → `422 field_not_allowed` |
| 49 | Duty-assignment feed | `POST https://api.<domain>/api/v1/integration/duty-assignments` with the internal roster client token (one test duty) | `200` with `upserted: 1`; a partner-bound client is refused |
| 50 | Increment-5 end-to-end (DEV only) | `WZ_E2E_EMPLOYEE_URL=https://employee.dev.<domain> WZ_E2E_WORKSPACE_URL=https://manager.dev.<domain> WZ_E2E_OH_URL=https://oh.dev.<domain> WZ_E2E_PARTNER_URL=https://partners.dev.<domain> WZ_E2E_CORE_URL=https://api.dev.<domain> node tools/e2e/e2e-increment5.js` | Requires a fresh DEV database with the demo data of increments 3–5 (scripts 1 → 5 in order), test accounts with the local user names (including `psych1`, `mro1`, `collector1`, `damanager1`, `promo1`, `ergo1`, `eap1`) and an adapted login step for Entra; 24 `PASS` lines |

## 15. Mobile app and MDM onboarding

The Capacitor app reads its runtime configuration from managed app configuration (MDM), then `/config.json`,
then built-in defaults. Configure these keys:

| Key | Value for this environment |
|---|---|
| `wz.apiBaseUrl` | `https://api.<domain>/api/v1` |
| `wz.oidcIssuer` | `https://login.microsoftonline.com/<tenant-id>/v2.0` |
| `wz.oidcClientId` | `$MOB_APP_ID` (`wellnessz-mobile`) |
| `wz.redirectUri` | `com.wellnessz.app://callback` |
| `wz.requireBiometric` | `true` |

* **Microsoft Intune:** *Apps → App configuration policies → Add → Managed devices*, select the app and add the keys
  (string / boolean). For MAM (copy/paste, screenshot and selective-wipe controls), integrate the Intune App SDK
  in the native projects. Then require an app protection policy for `wellnessz-mobile` in Conditional Access.
* **Workspace ONE UEM:** *Assignment → Application Configuration*, same keys.
* **Jamf Pro:** same keys in the iOS managed app configuration dictionary.

Details: `web/employee-app/README.md`, section *Runtime configuration & MDM*.

## 16. Troubleshooting

| Symptom | Likely cause | Resolution |
|---|---|---|
| `terraform apply`: `EncryptionAtHost feature not enabled` | Section 3 not completed | Register the feature, or set `aks_host_encryption=false` (document the exception) |
| `terraform apply`: Key Vault `403 ForbiddenByFirewall` | Runner outside the VNet | Add the runner IP to `key_vault_allowed_ips` or run from a private runner |
| PostgreSQL create fails: zone-redundant HA unavailable | Region or SKU restriction | Choose a supported region or SKU; dev can use `postgres_ha_enabled=false` |
| Pods `ContainerCreating`, event `failed to mount secrets store objects` | Federated credential subject or namespace/release name mismatch, or missing secret | Check that `k8s_namespace` and `helm_release_name` match the Helm release; `az keyvault secret show` for the named secret |
| Flyway job fails `password authentication failed for user "wz_migrator"` | Bootstrap (section 8) not run or passwords differ | Re-run the bootstrap with the Key Vault values |
| Core 401 for valid Entra tokens | Audience mismatch (`api://…` vs client ID) or v1 tokens | `WZ_OIDC_AUDIENCE` = API client ID; `requestedAccessTokenVersion=2` |
| Core 403 for a user with a group | Group not assigned to an app role, or the user has too many groups (overage) | Assign the group to the app role on the API enterprise app |
| BFF login error `AADSTS50011` | Redirect URI mismatch | Add `https://<host>/login/oauth2/code/wellnessz` for each web host, including `oh.<domain>` |
| OH workspace shows *Access restricted* for a clinician | New app role not defined or group not assigned; or group-based mapping missing the new group | Section 5.2 (existing tenant upgrade); check `GET /bff/session` roles |
| Webhook `400 idempotency_key_required` / `403` | Missing `Idempotency-Key`; client lacks the `INTEGRATION` application role | Section 5.6 |
| DPO account sees *Access restricted* | `DPO` app role missing on `wellnessz-api` or group not assigned | Section 5.2 (upgrade to increment 2) |
| Scheduler, biomedical or crew-licensing account sees *Access restricted* | Increment-3 app role missing on `wellnessz-api` or group not assigned | Section 5.2 (upgrade to increment 3) |
| Candidate cannot sign in or sees employee menus | Candidate account in the wrong directory, or `CANDIDATE` role / `employee_id` / `org_scope=/CANDIDATES` missing | Section 5.3 (candidates) |
| Uploads through the portal fail with `400` | BFF multipart parsing re-enabled by an override | Remove the override; the BFF ships with `spring.servlet.multipart.enabled=false` |
| No calibration-due or HR absence e-mails | E18 mailbox keys still at their `@wellnessz.local` defaults | Section 11, business configuration |
| No cold-chain excursion e-mails | No clinician with the fridge's site in the OH staff roster (`masterdata.staff_member.site_codes`) and no Nursing Head | Load the staff roster with site codes (doc 01, O9) |
| Partner sees no shared documents | `org_scope` not `/PARTNERS/<ORG>`, or the employee withdrew consent | Section 5.3; the disclosure log shows the share as `REVOKED` after withdrawal |
| Partner or adjuster sees *Your account needs multi-factor sign-in or has no partner access* | Conditional Access not applied (report-only, wrong target apps, guest type not included) so the token has no `amr: mfa`; or app role / group missing | Section 5.3a; sign-in log → *Conditional Access* tab; section 5.2 (upgrade to increment 4) |
| Partner portal login: `AADSTS50011` | `https://partners.<domain>/login/oauth2/code/wellnessz` missing on `wellnessz-bff` | Section 5.4 |
| Partner sees an empty referral inbox / adjuster no claims | `org_scope` not `/PARTNERS/<CODE>` with the code from the partner-organisation master data, or no referral / claim for that organisation | Section 5.3; `GET /api/v1/masterdata/partner-organisations` |
| Partner e-mails link to `partners.wellnessz.example.com` (or `.local`) | `WZ_PARTNER_PORTAL_URL` not overridden with your domain | Section 11 |
| Partner FHIR / insurer system gets `404` for a referral, pre-authorisation or report it should see | Client token has no `org_scope=/PARTNERS/<CODE>` or a code other than the referral's provider / the payer / the report recipient (audit shows `DENIED`) | Section 5.6 (partner-system clients) |
| Claims, finance, legal or procurement account sees *Access restricted* | Increment-4 app role missing or group not assigned | Section 5.2 (upgrade to increment 4) |
| `422 currency_not_supported` on a cost entry or invoice | Currency not in `claims.fx-rates` | Add the rate (section 11, business configuration) |
| Core does not start after enabling OCR: `Unsupported OCR binary location` | Path or language list outside the allow-list | Use `/usr/bin/tesseract` and one of `eng`, `ara`, `fra`, `eng+ara`, `eng+fra`, `eng+ara+fra` |
| App Gateway backend unhealthy | NetworkPolicy blocks health probes on 8081 | `networkPolicy.ingressController.allowHealthChecks=true` and the App Gateway subnet in `ipBlocks` |
| `cd.yml` cannot reach a private API server | GitHub-hosted runner | `AKS_PRIVATE=true` (uses command invoke) or a self-hosted runner via `DEPLOY_RUNNER` |
| Graph mailbox `403` | RBAC for Applications scope missing or still replicating | `Test-ServicePrincipalAuthorization`; allow up to 2 hours for replication |
| `terraform plan` wants to replace everything after switching environment | Initialised against another environment's state | `terraform init -reconfigure -backend-config=environments/backend-$ENV.hcl` before every plan |
| `terraform plan`: `Invalid value for variable "environment"` | Old value `test` | Use `uat`; an existing `test` stack needs a state move / rename (`deploy/environments/README.md`) |
| `deploy-env.yml` fails at the policy check | Wrong tag for the stage (for example `v1.0.0-rc.1` to PROD, `sha-…` to UAT) | DEV `sha-*`/`v*`, UAT `vX.Y.Z[-rc.N]`, PROD `vX.Y.Z` |
| `deploy-env.yml` waits forever on `azure-uat` / `azure-prod` | Required reviewers have not approved | Approve in the Actions run (*Review deployments*); self-approval is blocked by design |
| `azure/login` fails with `AADSTS70021` (no matching federated identity) | GitHub Environment name differs from Terraform `github_environments`, or the job runs without `environment:` | Use exactly `azure-dev` / `azure-uat` / `azure-prod`; re-apply Terraform after renaming |
| Helm render fails: image digest required | UAT/PROD overlay sets `global.requireImageDigests: true` and a component has no digest | Deploy through `deploy-env.yml` / `helm-release.sh upgrade` with all six digests |
| Smoke test failed and the release was rolled back | A pod did not become ready or runs another digest; a `SMOKE_URLS` entry did not return 2xx | Check the workflow log and `kubectl -n wellnessz get events`; fix and re-run the same tag |
| MRO, psych clinician or programme manager sees *Access restricted* | Increment-5 app role missing or group not assigned in **this** environment's registration | Section 5.2 (upgrade to increment 5) |
| General OH users cannot see a support case or test result | Intended: restricted clinical class | Only the named `PSYCH_CLINICIAN` / `MRO`; break-glass is logged and reviewed |
| TOXLAB results answer `404` | Laboratory client token without `org_scope=/PARTNERS/<code>` or the code differs from `substance.laboratory-code` | Section 5.6 (increment 5) and section 11 |
| `safety1`-type users (SAFETY_MANAGER only) get an error page in the manager workspace | BFF image older than increment 5 | Deploy the current release |

## 17. PROD go-live checklist

Complete every item **before** the first real user signs in to `azure-prod`. Items marked [M] are manual and need
evidence (ticket, screenshot or report) in the go-live record; UAT runs the same checklist as a rehearsal.

| # | Area | Item | Evidence |
|---|---|---|---|
| 1 | Change | CAB approval for the PROD build and the release `vX.Y.Z`; the same digests passed UAT acceptance (section 14) and business sign-off [M] | CAB record, UAT test report |
| 2 | Infrastructure | `terraform plan` for PROD shows no changes after the apply; resource lock `do-not-delete` present; `prevent_destroy` on PostgreSQL and Key Vault intact | Plan output |
| 3 | Backups | PostgreSQL backups visible with 35-day retention and geo-redundancy: `az postgres flexible-server backup list -g rg-wz-prod -n <server> -o table`; Key Vault purge protection on | Command output |
| 4 | DR test | [M] Point-in-time restore into a **new** server (`az postgres flexible-server restore --source-server <server> --restore-time <UTC> -n <server>-drtest -g rg-wz-prod`), core of a scratch namespace pointed at it, `GET /api/v1/audit/verify` valid, row counts recorded; measured RTO/RPO compared with doc 04, Section 10.2; geo-restore rehearsed into the paired region once a year; delete the test server afterwards | DR test report (SOC 2 A1.3) |
| 5 | HA | [M] Planned failover of PostgreSQL (`az postgres flexible-server restart --failover Planned`) and a node drain in one zone; application stays available | Test log |
| 6 | Security testing | [M] External penetration test of `employee.`, `partners.`, `api.` (and `manager.`/`oh.` if internet-facing) against UAT or PROD before go-live; high and critical findings fixed or risk-accepted by the CISO | Pen-test report, remediation record |
| 7 | WAF | `waf_mode = Prevention`; no rule in exclusion without a ticket; rate limits on `/oauth2/` and `/login/` | WAF policy export |
| 8 | Monitoring alerts | [M] Alerts of doc 04, Section 12 created in Azure Monitor / Sentinel with an on-call action group: WAF spikes, DB failover, Key Vault access by unexpected principals, pod restarts/OOM, readiness failures, audit-chain failure, certificate expiry < 30 days, `exposure-timers` / `public-health-deadlines` not run, break-glass on restricted records, smoke-test rollback | Alert rule list, test notification |
| 9 | Logging | Log Analytics retention 365 days; diagnostic settings on every resource; logs contain no PHI in clear text (sample query) | Query output |
| 10 | Identity | [M] PROD app registrations separate from DEV/UAT; Conditional Access for guests **enabled** (not report-only); MFA enrolled for every partner, adjuster and EAP account (smoke checks 35–36, 48); workforce Conditional Access with compliant devices for `oh.` and `manager.` | CA policy export, sign-in log |
| 11 | Access review | [M] Review of all `sg-wellnessz-*` groups for PROD – especially `sg-wellnessz-psych`, `sg-wellnessz-mro`, `sg-wellnessz-platform-admins`, `sg-wellnessz-aks-admins-prod`, `sg-wellnessz-db-admins-prod` (PIM-eligible only); GitHub `azure-prod` reviewers; Key Vault data-plane roles | Access review export |
| 12 | Secrets | [M] Key ceremony record for `wz-master-key`, `wz-pseudonym-key`, `wz-bff-session-key` (dual control, escrow copy); client secrets with expiry ≤ 6 months and a rotation reminder | Ceremony record |
| 12a | Restricted class | `mentalhealth.named-clinicians` no longer contains the demo user `psych1` (set by V17 in every environment) but the named PROD clinicians: `GET /api/v1/masterdata/config/mentalhealth.named-clinicians` | Config export |
| 13 | Data | No demo data (`WZ_FLYWAY_LOCATIONS` without `db/demo`); OH staff roster and HRIS employees loaded; partner network replaced by contracted partners (EAP, TOXLAB, public-health authority) | SQL check, master-data export |
| 14 | Clinical governance | [M] Sign-off of the configurable clinical content: PHQ-9/GAD-7 bands, testing policies and cut-offs (MRO, Legal), travel recommendations and PEP timers, screening thresholds, statutory report forms | Sign-off minutes |
| 15 | DPIA / privacy | [M] DPIA updated for increment 5 (restricted classes, wearables, peer support); DPO mailbox `WZ_DPO_EMAIL` set; break-glass review procedure in place | DPIA, procedure |
| 16 | Operations | Runbooks (deploy, rollback, restore, key rotation), on-call rota, support contacts in `WZ_OH_CONTACT`; GitHub Environment protection screenshots filed | Runbook links |

## 18. Teardown

Production resources are protected: a resource-group delete lock (prod), `prevent_destroy` on PostgreSQL, the
database and the CMK, and Key Vault purge protection. For DEV and UAT environments:

```bash
helm uninstall wellnessz -n wellnessz
```

Before destroying, delete or comment out the `lifecycle { prevent_destroy = true }` blocks in `postgres.tf` (two)
and `keyvault.tf` (CMK), on a throwaway branch only. Make sure Terraform points at the right environment:

```bash
terraform init -reconfigure -backend-config=environments/backend-$ENV.hcl && terraform destroy -var-file=environments/$ENV.tfvars -var-file=environments/$ENV.local.tfvars
```

UAT has `resource_lock_enabled = true`: remove the lock first (`az lock delete -n do-not-delete -g rg-wz-uat`). Never
destroy PROD without a decommissioning decision and a final backup export.

Key Vault remains soft-deleted for 90 days because of purge protection; this is expected and cannot be bypassed.
Remove the Entra app registrations, groups and DNS records if they are not shared with other environments.
