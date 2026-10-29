# WellnessZ OHCM – Setup Guide 2: Microsoft Azure

| | |
|---|---|
| **Document** | 06 – Setup guide: Microsoft Azure (AKS, PostgreSQL Flexible Server, Key Vault, App Gateway WAF) |
| **Audience** | Cloud platform team, DevSecOps, identity administrators |
| **Result** | A dev, test or prod environment on Azure, deployed from CI with signed images, ready for incremental releases |
| **Time** | 1 day for the first environment (Entra ID approvals and DNS usually dominate) |
| **Related** | 04 – Deployment Architecture · 09 – CI/CD Guide · `deploy/terraform/azure` · `deploy/helm/wellnessz/values-azure.yaml` |

![Azure topology](../images/09-deploy-azure.png)

*Figure 1 – Azure topology (source: `docs/images/09-deploy-azure.svg`).*

> **Validation status.** The Terraform code passes `terraform fmt -check` and `terraform validate` (azurerm 4.81,
> Terraform 1.13.4). The Helm chart renders with `values-azure.yaml` and passes Kubernetes and CRD schema
> validation. The workflows pass actionlint. **No `terraform apply` was run in a real subscription** while
> preparing this guide; your first environment build is the acceptance test. Run it in **dev** first and expect
> minor corrections, for example region SKU availability or tenant policy conflicts.
>
> **Increment 2** (E03 OH record, E05 fitness for duty) needs no new Azure resource. It adds the `DPO` app role and
> group (section 5), an `org_scope` claim for partner users (5.3), gateway clients for laboratory, devices and crew
> rostering (5.6), three schemas created by Flyway (8), new core settings including optional PDF font and OCR (11)
> and smoke checks 18–24 (14).
>
> **Increment 3** (E06 absence & sickness, E07 medical surveillance, E14 scheduling & clinic operations) needs no new
> Azure resource either. It adds four app roles and groups (`CLINIC_SCHEDULER`, `BIOMEDICAL`, `CREW_LICENSING`,
> `CANDIDATE`, section 5), a separate identity source for candidates (5.3), gateway clients for absence feeds,
> industrial hygiene, dosimetry, recruiting and the IoT fridge gateway (5.6), three schemas created by Flyway (8),
> business configuration set through the API after the first deployment (11) and smoke checks 25–32 (14).
>
> **Increment 4** (E04 treatment coordination & partner referrals, E08 claims coordination) adds one Kubernetes
> component – the **partner portal** on `partners.<domain>` (Helm `partnerPortal`, image `partner-portal`) – but no new
> Azure resource: it uses the existing App Gateway (or Front Door) with its own listener, certificate and DNS record
> (section 9). It adds five app roles and groups (section 5.1–5.2), **MFA for partner and adjuster B2B guests through
> Conditional Access** (section 5.3a), the partner-portal redirect URI (5.4), gateway clients for partner hospitals,
> insurer systems and the video platform (5.6), two schemas created by Flyway (8), `WZ_PARTNER_PORTAL_URL`, the MFA
> gate and business configuration (11) and smoke checks 33–42 (14).

## 1. Overview of the steps

| # | Stage | Who | Output |
|---|---|---|---|
| 1 | Workstation tools | Engineer | CLIs installed |
| 2 | Subscription preparation (providers, features, quotas) | Cloud admin | Subscription ready |
| 3 | Terraform remote state | Cloud admin | Storage account `stwellnessztfstate…` |
| 4 | Entra ID: groups and app registrations | Identity admin | API, BFF, mobile and mailbox apps; app roles |
| 5 | Terraform plan/apply | Platform team | Network, AKS, PostgreSQL, Key Vault, ACR, App Gateway, logging, identities |
| 6 | Secrets bootstrap | Two people (dual control) | Key material and client secrets in Key Vault |
| 7 | Database bootstrap | DBA | Roles and schemas |
| 8 | TLS certificates and DNS | Network team | `employee.`, `manager.`, `api.` hosts |
| 9 | GitHub configuration (OIDC, environments) | DevSecOps | Pipeline can push and deploy |
| 10 | Build, sign and push images | CI | Signed images in ACR |
| 11 | Helm install | CI (or platform team for the first install) | Running workloads |
| 12 | Smoke tests, EHS mailbox, MDM | QA / OH admin | Environment accepted |

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

## 3. Subscription preparation

Register the resource providers (idempotent):

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

Check the vCPU quota for the D-series v5 family in your region. You need at least 3 system nodes + 3 user nodes ×
4 vCPU, plus PostgreSQL.

```bash
az vm list-usage -l westeurope --query "[?contains(name.value,'standardDDSv5Family')]" -o table
```

## 4. Terraform remote state

Create a dedicated, locked-down storage account. Use it for all environments, with one key per environment.

```bash
az group create -n rg-wellnessz-tfstate -l westeurope
```

```bash
az storage account create -n stwellnessztfstate -g rg-wellnessz-tfstate -l westeurope --sku Standard_ZRS --kind StorageV2 --min-tls-version TLS1_2 --allow-blob-public-access false --allow-shared-key-access false --require-infrastructure-encryption true --https-only true
```

```bash
az storage account blob-service-properties update -n stwellnessztfstate -g rg-wellnessz-tfstate --enable-versioning true --enable-delete-retention true --delete-retention-days 30 --enable-container-delete-retention true --container-delete-retention-days 30
```

```bash
az storage container create -n tfstate --account-name stwellnessztfstate --auth-mode login
```

Grant data-plane access only to the engineers or CI identity who run Terraform:

```bash
az role assignment create --assignee <object-id-of-terraform-runner> --role "Storage Blob Data Contributor" --scope $(az storage account show -n stwellnessztfstate -g rg-wellnessz-tfstate --query id -o tsv)
```

Expected: `az storage container list --account-name stwellnessztfstate --auth-mode login -o table` lists `tfstate`.

## 5. Entra ID configuration

Create the configuration once per tenant. For dev and test you can share the app registrations and add redirect
URIs; prod should have its own registrations.

### 5.1 Groups (role assignment through groups)

| Group | App role | Typical members |
|---|---|---|
| `sg-wellnessz-employees` | EMPLOYEE | All crew and staff (dynamic group) |
| `sg-wellnessz-managers` | MANAGER | Line managers |
| `sg-wellnessz-hr` | HR | HR partners |
| `sg-wellnessz-oh-clinicians` | OH_CLINICIAN | OH physicians and nurses |
| `sg-wellnessz-case-managers` | CASE_MANAGER | OH case managers |
| `sg-wellnessz-psych` | PSYCH_CLINICIAN | Psychological health clinicians (restricted E09) |
| `sg-wellnessz-oh-admins` | OH_ADMIN | OH service administrators |
| `sg-wellnessz-auditors` | AUDITOR | Internal audit, GRC |
| `sg-wellnessz-platform-admins` | PLATFORM_ADMIN | Platform operators (PIM-eligible only) |
| `sg-wellnessz-partners` | PARTNER_PROVIDER | External providers (B2B guests; MFA required since increment 4 – section 5.3a) |
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
 {"allowedMemberTypes":["User"],"displayName":"Procurement","value":"PROCUREMENT","description":"Provider directory, contracts, rates, SLAs","isEnabled":true,"id":"6f1d4a10-0000-4000-8000-00000000001e"}
]
EOF
```

Generate your own GUIDs (`uuidgen`) for the `id` fields; the values above are examples. The last nine roles were
added in increment 1 (E01 intake, E02 triage); `DPO` was added in increment 2 (E03, E05); `CLINIC_SCHEDULER`,
`BIOMEDICAL`, `CREW_LICENSING` and `CANDIDATE` in increment 3 (E06, E07, E14); `CLAIMS_COORDINATOR`,
`TPA_ADJUSTER`, `LEGAL_COUNSEL`, `FINANCE` and `PROCUREMENT` in increment 4 (E04, E08). Then create the API app:

```bash
API_APP_ID=$(az ad app create --display-name wellnessz-api --identifier-uris api://wellnessz-api --app-roles @/tmp/wz-roles.json --query appId -o tsv) && echo $API_APP_ID
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

**Values to note.** The API's application (client) ID is the **audience** (`WZ_OIDC_AUDIENCE`). The issuer is
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

**TPA adjusters (increment 4).** Adjusters are B2B guests in `sg-wellnessz-tpa-adjusters` with
`org_scope=/PARTNERS/<PAYER>`, where `<PAYER>` is the insurer or TPA code in the partner-organisation master data (for
example `/PARTNERS/GULFTPA`). An adjuster sees only claims and pre-authorisations where that organisation is the payer.
Invite partner and adjuster users as named guests only after the data-sharing agreement is signed:

```bash
az rest --method POST --uri https://graph.microsoft.com/v1.0/invitations --body '{"invitedUserEmailAddress":"adjuster@gulftpa.example","inviteRedirectUrl":"https://partners.<domain>/","sendInvitationMessage":true}'
```

Then add the guest to `sg-wellnessz-partners` or `sg-wellnessz-tpa-adjusters` and set the `org_scope` extension
attribute (Option A above) on the guest user object.

### 5.3a MFA for partner and adjuster guests (increment 4)

The core grants `PARTNER_PROVIDER` and `TPA_ADJUSTER` only when the access token proves MFA (`amr` contains `mfa`,
or `acr` is one of `2`, `3`, `mfa`, `c1` – `WZ_MFA_REQUIRED_ROLES`, on by default outside the `local` profile). In
Entra ID, MFA for guests is enforced with Conditional Access:

1. Optionally trust MFA from the partner's home tenant: *External Identities → Cross-tenant access settings → Default
   settings (or the partner organisation) → Inbound access → Trust settings → Trust multifactor authentication from
   Microsoft Entra tenants*. Without it the guest registers MFA in your tenant at first sign-in.
2. Create the Conditional Access policy (report-only first, then on):

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

```bash
BFF_APP_ID=$(az ad app create --display-name wellnessz-bff --web-redirect-uris https://employee.wellnessz.example.com/login/oauth2/code/wellnessz https://manager.wellnessz.example.com/login/oauth2/code/wellnessz https://oh.wellnessz.example.com/login/oauth2/code/wellnessz https://partners.wellnessz.example.com/login/oauth2/code/wellnessz --query appId -o tsv) && echo $BFF_APP_ID
```

The BFF builds the redirect URI from the host the browser used, so every web host needs its own entry. The OH
workspace host `oh.<domain>` was added in increment 1, the partner portal host `partners.<domain>` in increment 4.
For an existing registration:

```bash
az ad app update --id $BFF_APP_ID --web-redirect-uris https://employee.wellnessz.example.com/login/oauth2/code/wellnessz https://manager.wellnessz.example.com/login/oauth2/code/wellnessz https://oh.wellnessz.example.com/login/oauth2/code/wellnessz https://partners.wellnessz.example.com/login/oauth2/code/wellnessz
```

```bash
az ad app update --id $BFF_APP_ID --enable-id-token-issuance false --enable-access-token-issuance false --set "web.logoutUrl=https://employee.wellnessz.example.com/"
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

Create a client secret. It is stored **only** in Key Vault in section 7; do not keep a copy. Prefer a 6-month
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
     dropped from tokens;
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
5. Configure the core and BFF:
   * `WZ_OIDC_ISSUERS=https://login.<domain>/realms/wellnessz`;
   * `WZ_OIDC_AUDIENCE=wellnessz-api`;
   * `WZ_BFF_ISSUER_URI` set to the same issuer;
   * remove `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_WELLNESSZ_SCOPE` (the audience mapper supplies the
     audience).

`WZ_OIDC_ISSUERS` accepts a comma-separated list, but the core validates a **single** audience
(`WZ_OIDC_AUDIENCE`). To trust Entra ID and Keycloak side by side during a migration, use the same audience in both
IdPs: in Keycloak, set the audience mapper's *Included Custom Audience* to the Entra API client ID.

## 6. Provision the infrastructure with Terraform

```bash
cd deploy/terraform/azure
```

```bash
cp backend.hcl.example backend.hcl && cp terraform.tfvars.example terraform.tfvars
```

Edit `backend.hcl`: set `key = "wellnessz/dev/azure.tfstate"` (one key per environment). Then edit
`terraform.tfvars`:

| Variable | Example | Notes |
|---|---|---|
| `subscription_id` | `0000…` | Environment subscription |
| `location` | `westeurope` | Must offer availability zones and PostgreSQL zone-redundant HA |
| `environment` | `dev` / `test` / `prod` | Drives names, SKU tier and the delete lock |
| `aks_admin_group_object_ids` | `["<sg-wellnessz-aks-admins id>"]` | Cluster admins via Azure RBAC |
| `aks_private_cluster` | `true` (prod) / `false` + `aks_authorized_ip_ranges` (dev) | Private API needs a runner in the VNet or `az aks command invoke` |
| `postgres_entra_admin` | `{ object_id = "<sg-wellnessz-db-admins id>", principal_name = "sg-wellnessz-db-admins" }` | Entra admin of the database |
| `postgres_ha_enabled`, `postgres_geo_redundant_backup` | `true` in prod, `false` in dev | Geo-redundancy cannot be changed after creation |
| `postgres_cmk_enabled` | `false` (enable for prod if required) | With geo backup, also set the paired-region key and identity |
| `migrations_mode` | `job` | Must match Helm `migrations.mode` |
| `key_vault_allowed_ips` | `["<runner egress IP>/32"]` | Only while Terraform runs outside the VNet |
| `github_repository` | `OWNER/WellnessZ` | Creates the GitHub OIDC deployer identity |
| `log_retention_days` | `365` | SOC 2 evidence |

Initialise against the remote state:

```bash
terraform init -backend-config=backend.hcl
```

Expected: `Terraform has been successfully initialized!` The provider versions and checksums come from the
committed `.terraform.lock.hcl`.

```bash
terraform plan -out tfplan
```

Review the plan. Expect about 110 resources on the first apply. Look for anything that destroys or replaces
PostgreSQL or Key Vault: both carry `prevent_destroy`.

```bash
terraform apply tfplan
```

Duration: 30–50 minutes. AKS and the PostgreSQL HA standby take longest. Then export the outputs:

```bash
terraform output -json > /tmp/wz-azure-outputs.json && jq 'with_entries(.value |= .value)' /tmp/wz-azure-outputs.json
```

Note `acr_login_server`, `key_vault_name`, `core_identity_client_id`, `bff_identity_client_id`,
`migrator_identity_client_id`, `postgres_fqdn`, `aks_cluster_name`, `resource_group_name`,
`application_gateway_public_ip` and `github_deployer_client_id`.

### 6.1 Verify

```bash
az aks get-credentials -g $(terraform output -raw resource_group_name) -n $(terraform output -raw aks_cluster_name) && kubelogin convert-kubeconfig -l azurecli
```

```bash
kubectl get nodes -o wide
```

Expected: 3 `system` and at least 3 `apps` nodes across zones 1–3, all `Ready`. For a private cluster, run the same
check through command invoke instead:

```bash
az aks command invoke -g <rg> -n <aks> --command "kubectl get nodes"
```

```bash
az postgres flexible-server show -g <rg> -n <server> --query "{state:state, ha:highAvailability.state, version:version, publicAccess:network.publicNetworkAccess}" -o table
```

Expected: `Ready`, `Healthy`, `16`, `Disabled`.

## 7. Bootstrap secrets (dual control)

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

To encrypt other configuration values with `ENC(...)`, run `tools/encrypt-secret.sh` with
`WZ_MASTER_KEY_FILE` pointing to a temporary file holding the same key. Delete the file afterwards.

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
PGPASSWORD="<token>" psql "host=<pghost> dbname=wellnessz user=sg-wellnessz-db-admins sslmode=verify-full sslrootcert=system" -v migrator_pw='<wz-migrator-db-password>' -v app_pw='<wz-db-password>' -v bff_pw='<wz-bff-db-password>' -f /sql/bootstrap-managed-postgres.sql
```

Expected last line:
`WellnessZ bootstrap complete: wz_migrator owns wellnessz + schemas; wz_app/wz_bff have CONNECT only (table grants via Flyway).`

The script creates the schemas `platform, masterdata, intake, casemgmt, triage, docs, ohrecord, fitness, absence,
surveillance, scheduling, treatment, claims, analytics` (`triage` since increment 1; `docs`, `ohrecord`, `fitness`
since increment 2; `absence`, `surveillance`, `scheduling` since increment 3; `treatment`, `claims` since increment 4).
**Upgrading an environment bootstrapped before increment 1:** Flyway V5 creates schema `triage` itself (as
`wz_migrator`, which owns the database) and grants `USAGE` to `wz_app`, so the upgrade works without a manual step.
Re-run the idempotent bootstrap script anyway to align ownership and the roles' `search_path` with the new schema
list. The same applies to increment 2: Flyway V6, V7 and V8 create `docs`, `ohrecord` and `fitness` and grant `USAGE`
to `wz_app`; re-run the bootstrap to update `search_path`. Increment 3 is identical: Flyway V10, V11 and V12 create
`absence`, `surveillance` and `scheduling` (V9 adds platform rows only); re-run the bootstrap once. Increment 4 is
the same: Flyway V14 and V15 create `treatment` and `claims` (V13 adds the partner organisations and the `CLAIM_RECORD`
retention class); re-run the bootstrap once to update `search_path`.

The pod is removed when you exit. Then delete the helper namespace:

```bash
kubectl delete namespace wz-admin
```

## 9. TLS certificates and DNS

1. Obtain certificates for `employee.<domain>`, `manager.<domain>`, `oh.<domain>` (OH workspace, increment 1),
   `partners.<domain>` (partner portal, increment 4) and `api.<domain>`, either from your public CA or with Key
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

## 10. GitHub configuration

Detailed rationale is in the CI/CD Guide (document 09). Minimum for Azure:

1. **Environments** `dev`, `test` and `prod` under *Settings → Environments*. Add required reviewers on `test`
   and `prod` and the deployment branch rule `v*` on `prod`.
2. **Environment variables** for each environment:

   | Variable | Value |
   |---|---|
   | `CLOUD` | `azure` |
   | `AZURE_CLIENT_ID` | `github_deployer_client_id` output |
   | `AZURE_TENANT_ID` | `tenant_id` output |
   | `AZURE_SUBSCRIPTION_ID` | Environment subscription |
   | `AKS_RESOURCE_GROUP` | `resource_group_name` output |
   | `AKS_CLUSTER_NAME` | `aks_cluster_name` output |
   | `AKS_PRIVATE` | `true` / `false` |
   | `IMAGE_REGISTRY` | `<acr_login_server>/wellnessz` |
   | `VALUES_FILE` | `deploy/helm/wellnessz/values-azure.yaml` |
   | `ENV_VALUES_FILE` | Per-environment overrides, for example `deploy/env/prod-azure.yaml` |
   | `HELM_RELEASE`, `K8S_NAMESPACE` | `wellnessz` |

3. **Repository variables:**

   | Variable | Value |
   |---|---|
   | `IMAGE_REGISTRY_KIND` | `acr` |
   | `ACR_NAME` | The registry name, i.e. the `acr_login_server` output without `.azurecr.io` |
   | `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID` | Values for the registry subscription |

4. **Secret:** `NVD_API_KEY` (OWASP dependency-check).

Terraform already created federated credentials for `repo:<owner/repo>:environment:<env>` and
`…:ref:refs/heads/main`. No client secret is needed.

Two cluster objects remain. The deployer identity holds *AKS RBAC Writer* on the namespace; it additionally needs
the namespace itself and a Role for the CSI CRD. Create both once as a cluster admin:

```bash
kubectl create namespace wellnessz && kubectl label namespace wellnessz pod-security.kubernetes.io/enforce=restricted
```

```bash
kubectl -n wellnessz create role spc-writer --verb=get,list,watch,create,update,patch,delete --resource=secretproviderclasses.secrets-store.csi.x-k8s.io
```

```bash
kubectl -n wellnessz create rolebinding spc-writer --role=spc-writer --user=$(jq -r '.github_deployer_client_id.value' /tmp/wz-azure-outputs.json)
```

With Azure RBAC for Kubernetes, the user name is the identity's object ID. Use
`az identity show --query principalId` if the client ID is not accepted.

## 11. Per-environment Helm values

Never edit `values-azure.yaml` with real identifiers in the shared repository. Copy it to an environment
overrides file (`ENV_VALUES_FILE`) and replace every `<placeholder>`:

| Placeholder | Source |
|---|---|
| `<acr-name>` | `acr_login_server` |
| `<tenant-id>` | `tenant_id` |
| `<core-identity-client-id>`, `<bff-identity-client-id>`, `<migrator-identity-client-id>` | Terraform outputs |
| `<key-vault-name>` | `key_vault_name` |
| `<pg-server-name>` | First label of `postgres_fqdn` |
| `<wellnessz-api-client-id>` | `$API_APP_ID` (audience) |
| `<bff-app-registration-client-id>` | `$BFF_APP_ID` |
| `<ehs-mailbox-app-client-id>` | Section 13 |
| Hostnames | Your domain: `ingress.hosts.employeePortal`, `managerWorkspace`, `ohWorkspace` (increment 1), `partnerPortal` (increment 4), `ingress.api.host`; the employee / manager / OH hosts in `core.env.WZ_CORS_ORIGINS` (the partner portal needs no CORS entry – it uses the same-origin BFF) |
| `networkPolicy.ingressController.ipBlocks`, `networkPolicy.database.cidrs` | `subnet_cidrs` output (App Gateway, PostgreSQL) |

Increment 1 also adds these core settings (put them under `core.env` in the overrides file):

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
set `ingress.hosts.partnerPortal` in the overrides file. To keep it off in an environment that has no partners yet,
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

Do not set `WZ_FLYWAY_LOCATIONS` to include `classpath:db/demo` in any shared environment; with `migrations.mode: job`
the migration Job reads only `db/migration`. The OH staff roster (`masterdata.staff_member`) has no import yet: load it
with a reviewed SQL script as `wz_migrator` and push HRIS employees to `POST /api/v1/masterdata/employees`.

Validate before committing:

```bash
helm lint deploy/helm/wellnessz -f deploy/helm/wellnessz/values.yaml -f deploy/helm/wellnessz/values-azure.yaml -f deploy/env/dev-azure.yaml
```

`values.schema.json` rejects invalid values. For example, switching off the read-only root filesystem fails.

## 12. Build, sign and deploy

### 12.1 Images (CI, recommended)

Run **Actions → Container images → Run workflow** with `registry = acr`, or push to `main`. For each image the
workflow builds, gates on Trivy (CRITICAL/HIGH), pushes to ACR with OIDC, attests the SBOM and SLSA provenance,
and signs with cosign (keyless).

Verify a signature from your workstation:

```bash
az acr login -n <acr-name> && cosign verify <acr>.azurecr.io/wellnessz/wz-core-app:<tag> --certificate-identity-regexp "^https://github.com/OWNER/WellnessZ/.github/workflows/container.yml@" --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

### 12.2 First install

For the first install, use either path:

* **CD workflow:** push a tag `v0.1.0`, or run the CD workflow manually with `image_tag`.
* **Manual** (cluster admin, public API server):

```bash
helm upgrade --install wellnessz deploy/helm/wellnessz -n wellnessz -f deploy/helm/wellnessz/values.yaml -f deploy/helm/wellnessz/values-azure.yaml -f deploy/env/dev-azure.yaml --set-string global.imageTag=v0.1.0 --atomic --wait --timeout 15m
```

Expected:

* `STATUS: deployed` and NOTES listing the endpoints;
* the Flyway job `wellnessz-migrations` completed (hook);
* all pods `Running` and `Ready`.

```bash
kubectl -n wellnessz get pods,hpa,pdb,secretproviderclass,networkpolicy
```

```bash
kubectl -n wellnessz logs job/wellnessz-migrations -c flyway-core --tail=5
```

The Job is deleted after success (hook policy). If it failed, the release rolls back and the log shows the
reason.

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

| # | Check | Command / action | Expected |
|---|---|---|---|
| 1 | Pods healthy | `kubectl -n wellnessz get pods` | All `Running`, readiness `1/1` |
| 2 | Readiness inside the cluster | `kubectl -n wellnessz exec deploy/wellnessz-core -- bash -c 'exec 3<>/dev/tcp/127.0.0.1/8081; printf "GET /actuator/health/readiness HTTP/1.0\r\n\r\n" >&3; cat <&3' \| tail -1` | `{"status":"UP"}` |
| 3 | TLS and WAF | `curl -sI https://employee.<domain>/` | `200`, HSTS header; `curl -s "https://employee.<domain>/?q=<script>alert(1)</script>"` returns `403` (WAF) |
| 4 | OIDC login | Browser: `https://employee.<domain>/` → *Sign in* | Entra login, MFA, back to the portal; cookie `WZSESSION` HttpOnly + Secure |
| 5 | Status-only rule | Manager account opens the workspace | Case status visible, no clinical text |
| 6 | API host | `curl -s -o /dev/null -w '%{http_code}' https://api.<domain>/api/v1/me` | `401` without a token |
| 7 | Management port not exposed | `curl -s -o /dev/null -w '%{http_code}' https://api.<domain>/actuator/env` | `404` |
| 8 | End-to-end | `WZ_E2E_EMPLOYEE_URL=https://employee.<domain> WZ_E2E_WORKSPACE_URL=https://manager.<domain> WZ_E2E_IDP_HOST=login.microsoftonline.com node tools/e2e/e2e.js` | Adapt the login step for Entra test accounts (MFA-exempt test users in a dedicated group, dev/test only) |
| 9 | Audit chain | `GET /api/v1/audit/verify` as `AUDITOR` | Chain valid |
| 10 | Logs | Log Analytics: `ContainerLogV2 \| where PodNamespace == "wellnessz" \| take 20` | JSON logs, no PHI in clear text |
| 11 | EHS intake | Section 13 | Incident visible |
| 12 | OH workspace (increment 1) | `curl -sI https://oh.<domain>/`, then browser login as a Nursing Head test account | `200` with HSTS; *Work queues* screen with counts; a reception account sees only *Reception* |
| 13 | Modules | `GET /api/v1/modules` with any user token | E01 and E02 `IMPLEMENTED` |
| 14 | New roles in tokens | Decode an access token of a CMO test user (<https://jwt.ms>) | `"roles"` contains `CMO` |
| 15 | EHS webhook | Section 5.6 smoke call | `202`; repeating with the same key returns `Idempotent-Replayed: true` |
| 16 | Status-only APIs | `GET https://api.<domain>/api/v1/status/crew?employeeIds=<id>` with a crew-scheduling token | Rows with `fitnessStatus`, `restrictionUntil` only |
| 17 | Increment-1 end-to-end (dev/test only) | `WZ_E2E_EMPLOYEE_URL=https://employee.<domain> WZ_E2E_WORKSPACE_URL=https://manager.<domain> WZ_E2E_OH_URL=https://oh.<domain> WZ_E2E_CORE_URL=https://api.<domain> node tools/e2e/e2e-increment1.js` | Requires dedicated test accounts with the local user names and an adapted login step for Entra; 18 `PASS` lines |
| 18 | Increment-2 schemas | Bootstrap pod (section 8): `psql … -c '\dn'` | `docs`, `ohrecord`, `fitness` owned by `wz_migrator` |
| 19 | Modules (increment 2) | `GET /api/v1/modules` with any user token | E03 and E05 `IMPLEMENTED` |
| 20 | DPO segregation | Browser login on `https://oh.<domain>` as a DPO test account; then open an employee chart URL | *Break-glass reviews*, *Disclosures* and *Retention* load; the chart is not shown (the API answers `403` or `404`) |
| 21 | Break-glass alert | As a clinician test account, declare break-glass on a test employee | E-mail to `WZ_DPO_EMAIL` without clinical content; review item visible to the DPO |
| 22 | Documents | Generate and sign a fitness certificate for a test employee (OH workspace) and download it | PDF opens; Arabic names render if a font is configured |
| 23 | Gateway pull queue | Section 5.6 smoke call with a gateway token | `200`; the same call with a user token returns `403` |
| 24 | Increment-2 end-to-end (dev/test only) | `WZ_E2E_EMPLOYEE_URL=https://employee.<domain> WZ_E2E_WORKSPACE_URL=https://manager.<domain> WZ_E2E_OH_URL=https://oh.<domain> WZ_E2E_CORE_URL=https://api.<domain> node tools/e2e/e2e-increment2.js` | Requires test accounts with the local user names (including `dpo1`, `ame1`, `cmo1`, `crewsched1`) and an adapted login step for Entra; 25 `PASS` lines |
| 25 | Increment-3 schemas | Bootstrap pod (section 8): `psql … -c '\dn'` | `absence`, `surveillance`, `scheduling` owned by `wz_migrator` |
| 26 | Modules (increment 3) | `GET /api/v1/modules` with any user token | E06, E07 and E14 `IMPLEMENTED` |
| 27 | New roles in tokens | Decode access tokens of scheduler, biomedical and crew-licensing test users | `"roles"` contains `CLINIC_SCHEDULER`, `BIOMEDICAL`, `CREW_LICENSING` respectively |
| 28 | Scheduler segregation | Browser login on `https://oh.<domain>` as a scheduler test account | *Clinic diary* and *Due list* load; `GET /api/v1/absence/queue` answers `403` |
| 29 | Upload through the BFF | Employee test account uploads a sick certificate (photo) in the employee portal | Upload succeeds (a `400` points to a BFF with multipart parsing enabled – keep the shipped default) |
| 30 | Calibration block | Record a device result (OH workspace or device gateway) with a device that is overdue in *Equipment & cold chain* | `422 device_out_of_calibration`; accepted after a passing calibration is recorded |
| 31 | Cold-chain gateway | `POST https://api.<domain>/api/v1/integration/cold-chain/readings` with the IoT gateway token and a registered fridge id | `200` with `accepted: 1`; a value outside 2–8 °C opens an excursion and sends the urgent, content-free `coldchain.excursion` notice to the site's clinicians and the Nursing Head |
| 32 | Increment-3 end-to-end (dev/test only) | `WZ_E2E_EMPLOYEE_URL=https://employee.<domain> WZ_E2E_WORKSPACE_URL=https://manager.<domain> WZ_E2E_OH_URL=https://oh.<domain> WZ_E2E_CORE_URL=https://api.<domain> node tools/e2e/e2e-increment3.js` | Requires the increment-3 demo data (dev only – never `db/demo` in shared environments, so run it against a dev database prepared with the demo files), test accounts with the local user names (including `scheduler1`, `biomed1`, `crewlicensing1`, `candidate1`) and an adapted login step; 32 `PASS` lines |
| 33 | Partner portal | `curl -sI https://partners.<domain>/` | `200` with HSTS and `Content-Security-Policy`; `kubectl -n wellnessz get deploy wellnessz-partner-portal` ready 2/2 |
| 34 | Increment-4 schemas and modules | Bootstrap pod: `psql … -c '\dn'`; `GET /api/v1/modules` | `treatment`, `claims` owned by `wz_migrator`; E04 and E08 `IMPLEMENTED` |
| 35 | MFA gate – positive | Partner test guest (in `sg-wellnessz-partners`, `org_scope=/PARTNERS/<CODE>`) signs in on `https://partners.<domain>` with MFA | Referral inbox of that organisation; `https://partners.<domain>/bff/api/me` lists `PARTNER_PROVIDER` |
| 36 | MFA gate – negative | Same account while the Conditional Access policy is report-only (or a token without `amr: mfa`) | Portal shows *multi-factor sign-in required*; `GET /bff/api/partner/referrals` answers `403` |
| 37 | Organisation isolation | Partner test account opens a referral id of another organisation (`/#/referrals/<id>`) | *Not found or not shared with your organisation* (`404`) |
| 38 | Adjuster | Adjuster test guest (`/PARTNERS/<PAYER>`) opens *Claims* and *Pre-authorisations* | Only claims where its organisation is payer; RTW status only; a message with clinical wording → `422` |
| 39 | Non-clinical roles | Claims, finance, legal and procurement test accounts on `https://oh.<domain>` | Their menus only; an employee chart URL is not shown (`403`/`404`) |
| 40 | FHIR partner endpoint | `GET https://api.<domain>/api/v1/integration/referrals/<id>/fhir` with the token of the referral's provider system (`org_scope=/PARTNERS/<provider>`) | `200` with `"resourceType": "ServiceRequest"`; another partner's or an unbound gateway client's token gets `404`, user tokens get `403` |
| 41 | Notifications | Send a test referral to a partner test organisation | Content-free `referral.sent` e-mail with a link to `https://partners.<domain>/#/referrals/<id>` (a `partners.wellnessz.example.com` or `partners.wellnessz.local` link means `WZ_PARTNER_PORTAL_URL` was not overridden with your domain) |
| 42 | Increment-4 end-to-end (dev/test only) | `WZ_E2E_EMPLOYEE_URL=https://employee.<domain> WZ_E2E_WORKSPACE_URL=https://manager.<domain> WZ_E2E_OH_URL=https://oh.<domain> WZ_E2E_PARTNER_URL=https://partners.<domain> WZ_E2E_CORE_URL=https://api.<domain> node tools/e2e/e2e-increment4.js` | Requires a fresh dev database with the increment-3 and increment-4 demo data (the increment-3 and increment-4 scripts consume demo state – run the scripts 1 → 4 in order), test accounts with the local user names (including `claims1`, `adjuster1`, `legal1`, `finance1`, `procurement1`, `physio1`), an adapted login step for Entra **with MFA** (or a dev core with `WZ_MFA_REQUIRED_ROLES` unset for partner test accounts only in dev); 22 `PASS` lines |

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

## 17. Teardown

Production resources are protected: a resource-group delete lock (prod), `prevent_destroy` on PostgreSQL, the
database and the CMK, and Key Vault purge protection. For dev and test environments:

```bash
helm uninstall wellnessz -n wellnessz
```

Before destroying, delete or comment out the `lifecycle { prevent_destroy = true }` blocks in `postgres.tf` (two)
and `keyvault.tf` (CMK), on a throwaway branch only.

```bash
terraform destroy
```

Key Vault remains soft-deleted for 90 days because of purge protection; this is expected and cannot be bypassed.
Remove the Entra app registrations, groups and DNS records if they are not shared with other environments.
