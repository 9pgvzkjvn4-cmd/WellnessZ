# WellnessZ OHCM – Technology Standards Comparison by Layer

**Version 1.0** – *WellnessZ OHCM platform – increments 1–5*

| | |
|---|---|
| **Document** | 04 – Technology Standards Comparison by Layer |
| **Version** | 1.0 (2026-10-30) |
| **Audience** | Chief Information Officer, Cyber Governance, Risk & Compliance (GRC) team, infrastructure and cloud platform teams, auditors |
| **Scope** | For every deployment layer: the technology standard used in each set-up – Local MacBook Pro (unit and integration testing), Microsoft Azure (DEV / UAT / PROD), Google Cloud (DEV / UAT / PROD) and Hybrid cloud (DEV / UAT / PROD) – the differences between DEV, UAT and PROD, the recommended standard and its relevance for SOC 2 and ISO/IEC 27001:2022 |
| **Sources (verified)** | `deploy/local`, `deploy/docker`, `deploy/helm/wellnessz` (values, `environments/{azure,gcp,hybrid}/{dev,uat,prod}.yaml`), `deploy/terraform/azure` and `deploy/terraform/gcp` (incl. `environments/{dev,uat,prod}.tfvars`), `deploy/hybrid`, `deploy/sql`, `deploy/scripts/helm-release.sh`, `.github/workflows/*`, `deploy/environments/README.md` |
| **Companion documents** | [01 – Technology Architecture and Technology Selection](01-Technology-Architecture-and-Technology-Selection.md) · [02 – Security and SOC 2 Control Mapping](02-Security-and-SOC2-Control-Mapping.md) · [Deployment Architecture](../deployment/Deployment-Architecture.md) · [Environment matrix](../../deploy/environments/README.md) |

> **How to read this document.** Each layer has one table that lists, per set-up and environment, the standard that
> the repository actually ships, followed by the recommended standard and the compliance relevance. Every entry
> carries a status:
>
> * **Shipped** – provisioned or configured by the repository's Terraform, Helm chart, manifests, Compose file or
>   workflows;
> * **Shipped (off)** – implemented in the infrastructure code but switched off by default or in the stated
>   environment (a variable turns it on);
> * **Recommended – not shipped** – an architectural recommendation that the infrastructure code does **not**
>   provision; the operator must add it (or confirm an equivalent group service).
>
> **Validation status.** The local set-up was executed end to end. Terraform for Azure and GCP passes `fmt` and
> `validate` for all three environment variable files; the Helm chart passes `helm lint` and kubeconform on 21 value
> combinations; the workflows pass `actionlint`. **Nothing has been applied to a real Azure subscription, GCP project
> or on-premises cluster yet** – the first DEV build is the acceptance test of the infrastructure code.

## 1. Set-ups and environments

| Set-up | Purpose | Environments | Infrastructure code | Application configuration |
|---|---|---|---|---|
| **Local MacBook Pro** | Developer loop, unit and integration tests, demos; synthetic data only | one (local) | `deploy/local/docker-compose.yml`, `up.sh` / `down.sh` | Spring profile `local`; optional kind / minikube with `values-local.yaml` |
| **Microsoft Azure** | Regulated production on AKS | DEV, UAT, PROD (`azure-dev`, `azure-uat`, `azure-prod`) | `deploy/terraform/azure` + `environments/<env>.tfvars` + per-environment back end | `values.yaml` + `values-azure.yaml` + `environments/azure/<env>.yaml` |
| **Google Cloud** | Production on GKE; one GCP project per environment | DEV, UAT, PROD (`gcp-dev` …) | `deploy/terraform/gcp` + `environments/<env>.tfvars` | `values-gcp.yaml` + `environments/gcp/<env>.yaml` |
| **Hybrid cloud** | PHI stays on-premises: core API and PostgreSQL (CloudNativePG) on RKE2 / OpenShift; web apps and BFF in the cloud | DEV, UAT, PROD | `deploy/hybrid` (CloudNativePG, External Secrets, Vault policies) + the cloud stack of the same environment | on-premises: `values-hybrid.yaml` + `environments/hybrid/<env>.yaml`; cloud edge: cloud overlay + `deploy/hybrid/values-hybrid-cloud-edge.yaml` |

All set-ups run the **same six container images** (core API, BFF, four web apps), promoted by signed digest
DEV → UAT → PROD (Section 15). Environment differences live only in Terraform variable files, Helm overlays and GitHub
Environments.

![Environment promotion](../images/13-environment-promotion.png)

*Figure 1 – Release and promotion flow (source: `docs/images/13-environment-promotion.svg`).*

## 2. Summary matrix

| Layer | Local MacBook Pro | Azure | Google Cloud | Hybrid cloud | Recommended standard |
|---|---|---|---|---|---|
| Edge / DNS / WAF / CDN | none (loopback only) | Application Gateway WAF_v2 (DRS 2.1 + Bot Manager); Front Door Premium **shipped (off)** | Global external Application LB + Cloud Armor (CRS 3.3, adaptive protection) | cloud WAF for the web tier; internal ingress on-prem | Managed WAF in prevention mode in front of every public host; public DNS with DNSSEC and CAA (**recommended – not shipped**) |
| Ingress / load balancing | Vite dev servers + BFF on localhost | AGIC on App Gateway | GKE Ingress (`gce`) with BackendConfig / FrontendConfig | OpenShift router / RKE2 ingress, cert-manager internal PKI | Cloud-native L7 load balancer integrated with the WAF |
| Compute / orchestration | Docker Desktop or Colima; IDE processes | AKS (Cilium, workload identity, Azure Policy, Defender) | GKE Autopilot (Dataplane V2, Shielded nodes, Workload Identity) | RKE2 (CIS profile) or OpenShift, fleet via Azure Arc / GKE Enterprise | Managed Kubernetes; hardened on-prem distribution for hybrid |
| Registry / supply chain | local images | ACR Premium; cosign, SBOM, SLSA | Artifact Registry (CMEK, immutable tags, scanning); Binary Authorization | images mirrored by digest; signature admission **recommended – not shipped** | Signed images, provenance and SBOM; admission control on every cluster |
| Identity & access | Keycloak 26.8.1 realm `wellnessz` | Entra ID (per-environment app registrations), Entra RBAC on AKS | Entra ID issuers (or any OIDC); Google IAM for the platform | Entra ID; Vault Kubernetes auth / Arc workload identity | Entra ID for the workforce with Conditional Access; Keycloak only where no cloud IdP is allowed |
| Secrets & keys | generated `.env` (mode 600) | Key Vault Premium + CSI; RSA-HSM keys for CMK | Secret Manager + CSI; Cloud KMS HSM (CMEK) | HashiCorp Vault + CSI / External Secrets (or Key Vault via Arc) | Platform secret store, file-mounted, workload identity; KMS-wrapped master key (**recommended**) |
| Application runtime | Temurin 21 JRE, Spring profile `local` | same image, non-root, read-only root FS | same | same (OpenShift: arbitrary UID) | Unchanged hardened image everywhere |
| Database & HA | PostgreSQL 16.15 container | Flexible Server 16 (HA in UAT / PROD) | Cloud SQL 16 (`REGIONAL` in UAT / PROD) | CloudNativePG 3 instances, synchronous standby | Managed PostgreSQL with zone-redundant HA and PITR |
| Messaging / outbox | PostgreSQL outbox, in-process | same | same | same | Outbox plus one managed broker per hosting decision (**recommended – not shipped**) |
| Object / document storage | documents in PostgreSQL (encrypted) | same | same | same; S3-compatible object store for backups only | Keep documents in PostgreSQL at current volumes; immutable object storage for audit anchoring (**recommended**) |
| Observability | OTel collector (debug exporter), Mailpit | Log Analytics + Container Insights, diagnostic settings, Defender | Cloud Logging / Monitoring, Managed Prometheus, Data Access audit logs | Prometheus / OTel collector on-prem (operator) | OpenTelemetry to the platform back end, SIEM forwarding |
| Networking & private connectivity | loopback ports only | VNet, NSGs, private endpoints, private DNS, private AKS (UAT / PROD) | custom VPC, private nodes, Private Service Access, Cloud NAT | ExpressRoute / Interconnect / IPsec VPN, port 443 only | Private endpoints for all data services; no public database endpoint |
| CI/CD | local Maven / npm, git hook | GitHub Actions, OIDC federation, environment `azure-<env>` | GitHub Actions, Workload Identity Federation, `gcp-<env>` | as cloud, plus `az aks command invoke` / GitOps option | GitHub Actions with protected environments and required reviewers |
| Infrastructure as code | Compose | Terraform `>= 1.11`, azurerm `~> 4.81` | Terraform, google `~> 8.9` | Kubernetes manifests + Helm (no Terraform for on-prem) | Terraform with one state per environment |
| Backup & DR | none | 7 / 14 / 35 days, geo-redundant in PROD | 7 / 14 / 30 backups, PITR | Barman Cloud WAL + base backups, 35 days | PITR plus cross-region / second-site copies and tested restores |
| Security scanning | SpotBugs, tests, pre-commit hook | CI gates + Defender for Cloud (UAT / PROD) | CI gates + Artifact Registry scanning, GKE security posture | CI gates; in-cluster scanning **recommended – not shipped** | CI gates (SAST, SCA, secrets, IaC, image) plus runtime posture management |

## 3. Edge, DNS, WAF and CDN

| Set-up | Standard used | DEV | UAT | PROD | Status |
|---|---|---|---|---|---|
| Local | None – every port binds to `127.0.0.1`; no WAF | – | – | – | Shipped |
| Azure | Application Gateway **WAF_v2** with WAF policy: Microsoft DRS 2.1 + Bot Manager 1.1, rate-limit rule on login paths (100 requests / minute), TLS policy `AppGwSslPolicy20220101S`; optional **Azure Front Door Premium** + WAF (`enable_front_door`) | WAF **Detection**, capacity 1–2, single zone | **Prevention**, capacity 2–4, zones 1–3 | **Prevention**, capacity 2–10, zones 1–3 | WAF shipped; Front Door **shipped (off)** in all three environments |
| GCP | Global external Application Load Balancer with Google-managed certificates, SSL policy `RESTRICTED` (TLS 1.2+); **Cloud Armor** CRS 3.3 preconfigured rules, rate-based ban on login paths, verbose logging | Cloud Armor rules in **preview** (log only), adaptive protection off | enforced, adaptive protection on | enforced, adaptive protection on | Shipped |
| Hybrid | Web tier and BFF use the cloud edge of the same environment (App Gateway or Cloud Armor); the on-premises core is reachable only through the private link on an internal ingress | as cloud DEV | as cloud UAT | as cloud PROD | Shipped (cloud part) |
| All | Public DNS zone, DNSSEC, CAA records, Cloud CDN / Front Door caching of static assets | – | – | – | **Recommended – not shipped** (Terraform provisions only Azure private DNS zones for private endpoints) |

**Recommended standard.** A managed WAF in **prevention** mode in front of every public host (`employee.*`,
`manager.*`, `oh.*`, `partners.*`, `api.*`), with the OWASP-based rule set, bot protection and rate limits on
`/oauth2/` and `/login/`. Keep DEV in detection / preview to tune false positives, but promote the same rule set to
UAT before PROD. Add Front Door (Azure) or a global edge only when a multi-region or DDoS posture requires it;
CDN caching gives little benefit for an authenticated line-of-business app and must never cache `/bff/**` responses.
Manage public DNS in the organisation's DNS service with DNSSEC and CAA restricting certificate issuers.

**SOC 2 / ISO 27001 relevance.** CC6.6 (boundary protection), CC6.7 (encryption in transit), CC7.2 (WAF logs as
monitoring evidence); ISO 27001 A.8.20 (network security), A.8.21 (security of network services), A.8.23 (web
filtering), A.8.24 (cryptography). Evidence: WAF policy export, WAF logs in Log Analytics / Cloud Logging, TLS policy.

## 4. Ingress and load balancing

| Set-up | Standard used | DEV | UAT | PROD | Status |
|---|---|---|---|---|---|
| Local | Vite dev servers on ports 5173–5176 proxy `/bff`, `/oauth2`, `/login`, `/logout` to the BFF (8090); core on 8080; management on 8081 / 8091 | – | – | – | Shipped |
| Azure | **Application Gateway Ingress Controller** (AKS add-on, `ingressClassName: azure-application-gateway`); certificate referenced from Key Vault (no TLS secret in the cluster); connection draining; path routing of SPA and BFF on the same origin | hosts `*.dev.<domain>` | `*.uat.<domain>` | plain hosts | Shipped |
| GCP | **GKE Ingress** (`gce`) → global external ALB; BackendConfig attaches the Cloud Armor policy, FrontendConfig enforces HTTPS redirect and the SSL policy; managed certificates | `*.dev.<domain>` | `*.uat.<domain>` | plain hosts | Shipped |
| Hybrid | On-premises: OpenShift router (`openshift-default`, re-encrypt termination) or RKE2 ingress (nginx / traefik) with certificates from the internal PKI via cert-manager; mTLS-terminating internal ingress for the BFF → core hop (`values-hybrid-cloud-edge.yaml`) | as overlay | as overlay | as overlay | Shipped |

The management port is never exposed through an ingress. Every web host serves its SPA and the BFF paths on the
same origin, so the session cookie stays `SameSite=Lax` and first-party.

**Recommended standard.** The cloud provider's L7 load balancer integrated with its WAF (as shipped). On-premises,
use the platform router of OpenShift or a supported ingress controller; ingress-nginx is retired (March 2026) and is
not used by the cloud set-ups.

**SOC 2 / ISO 27001 relevance.** CC6.6, CC6.7; ISO 27001 A.8.20, A.8.22 (segregation of networks).

## 5. Compute and container orchestration

| Set-up | Standard used | DEV | UAT | PROD | Status |
|---|---|---|---|---|---|
| Local | Docker Desktop or Colima; core and BFF from the IDE or as hardened containers (`./up.sh --apps`, read-only root filesystem) | – | – | – | Shipped |
| Azure | **AKS**: Entra ID-only access with Azure RBAC, local accounts disabled, OIDC issuer + workload identity, Azure Policy, Microsoft Defender, Key Vault CSI with rotation, Container Insights, Azure CNI with **Cilium** data plane and network policy, host encryption, auto-upgrade `patch` and `NodeImage` channels | **Free** tier, public API with authorised IP ranges, 1 zone, system pool 1–2, user pool 1–3 | **Standard** tier, **private** API server, 3 zones, system 2–3, user 2–5 | Standard tier, private API server, 3 zones, system 3–5 (D4ds_v5), user 3–9, **resource lock** | Shipped |
| GCP | **GKE Autopilot** (Standard possible): private nodes, Dataplane V2 (`ADVANCED_DATAPATH`), Workload Identity, Shielded nodes, application-layer secrets encryption (CMEK), Secret Manager CSI add-on, security posture, Managed Prometheus, release channel `REGULAR` | deletion protection **off**, master authorised networks | deletion protection on | deletion protection on; private control-plane endpoint **shipped (off)** (`gke_private_endpoint=false` – needs CI inside the VPC or Connect Gateway) | Shipped |
| Hybrid | **RKE2** (CIS profile) or **OpenShift** for the core; cloud AKS / GKE for web and BFF; fleet management through **Azure Arc** or **GKE Enterprise** (Config Sync, Policy Controller) | 1–2 core replicas | 2–4, PDB `minAvailable: 1` | 3–10, PDB `minAvailable: 2` | Manifests and overlays shipped; Arc / GKE Enterprise registration is an operator step |

Workload settings common to every cluster (Helm chart, enforced by `values.schema.json`): non-root, read-only root
filesystem, all capabilities dropped, seccomp `RuntimeDefault`, no privilege escalation, no automounted service-account
token, HPA, PDB and zone topology spread (`DoNotSchedule` in PROD).

| Workload (Helm overlay) | DEV | UAT | PROD |
|---|---|---|---|
| Core replicas / HPA | 1–2 | 2–4 | 3–10 |
| PDB | none | `minAvailable: 1` | `minAvailable: 2` |
| Flyway | in the application (`migrations.mode=app`) – hybrid always Job | pre-upgrade Job | pre-upgrade Job |
| Log level / trace sampling | DEBUG / 1.0 | INFO / 0.25 | INFO (root WARN) / 0.05 |

**Recommended standard.** Managed Kubernetes with a private control plane in UAT and PROD (AKS as shipped; for GKE,
enable the private endpoint once a runner inside the VPC or Connect Gateway exists). On-premises, RKE2 with the CIS
profile or OpenShift, managed as part of the cloud fleet so that the same policies apply.

**SOC 2 / ISO 27001 relevance.** CC6.1, CC6.8 (unauthorised software), CC7.1, A1.2 (capacity, resilience); ISO 27001
A.8.9 (configuration management), A.8.19 (software on operational systems), A.8.6 (capacity), A.8.14 (redundancy),
A.8.31 (separation of development, test and production).

## 6. Container registry and supply chain

| Set-up | Standard used | DEV | UAT | PROD | Status |
|---|---|---|---|---|---|
| Local | Images built locally (`wellnessz/wz-core-app:local`, `wz-bff:local`) | – | – | – | Shipped |
| Build (all clouds) | `container.yml` builds six images **once** on `main`: Trivy gate (CRITICAL / HIGH with a fix → fail), BuildKit provenance `mode=max`, Syft CycloneDX SBOM, SBOM and SLSA provenance attestations, **cosign keyless** signature (GitHub OIDC, Fulcio, Rekor); release registry default `ghcr.io/<owner>/wellnessz` | images tagged `sha-<12>` | release candidates `vX.Y.Z-rc.N` | final `vX.Y.Z` | Shipped |
| Azure | **ACR Premium**: no admin user, zone redundancy derived from the zone list, private endpoint, 30-day retention of untagged manifests; `deploy-env.yml` copies images **by digest** with `cosign copy` (signatures and attestations included) and re-verifies them | accepts pushes from `main` | promotion copies only | promotion copies only; `global.requireImageDigests` | Shipped. Public network access is enabled in all three variable files so that GitHub-hosted runners can copy – restrict to runner ranges or use self-hosted runners (**recommended**) |
| GCP | **Artifact Registry** with CMEK, immutable tags, cleanup policies, vulnerability scanning; **Binary Authorization** with a KMS-backed attestor; `deploy-env.yml` creates the attestation | Binary Authorization **dry-run** (`DRYRUN_AUDIT_LOG_ONLY`) | **enforced** | enforced | Shipped |
| Hybrid | Images mirrored by digest into the on-premises or cloud registry | – | – | – | Mirroring shipped via pipeline; on-cluster signature verification (Sigstore policy-controller or Kyverno `verifyImages`) **recommended – not shipped** |
| AKS | Signature verification at admission (Azure Policy image integrity, Ratify or Kyverno) | – | – | – | **Recommended – not shipped** (the deploy job verifies signatures before `helm upgrade`) |

**Recommended standard.** Build once, sign keyless, attach SBOM and SLSA provenance, promote the **same digest**, and
verify the signature twice – in the pipeline (shipped) and at cluster admission on every platform (shipped on GKE
only). Require digests in UAT and PROD (shipped).

**SOC 2 / ISO 27001 relevance.** CC7.1 (vulnerability management), CC8.1 (change authorisation and integrity);
ISO 27001 A.5.21 (ICT supply chain), A.8.8 (technical vulnerabilities), A.8.25 / A.8.28 (secure development and coding),
A.8.32 (change management). Evidence: Trivy SARIF, SBOM artefacts, Rekor entries, Binary Authorization audit logs.

## 7. Identity and access

| Set-up | Standard used | DEV | UAT | PROD | Status |
|---|---|---|---|---|---|
| Local | **Keycloak 26.8.1**, realm `wellnessz` (clients `wellnessz-bff`, `wellnessz-mobile`, `wellnessz-api`, `wellnessz-integration`; 38 roles; demo users with temporary passwords; brute-force protection, 5-minute access tokens, 15-minute SSO idle); MFA gate off (`local` profile) | – | – | – | Shipped |
| Azure | **Microsoft Entra ID** v2.0 issuer per environment (`WZ_OIDC_ISSUERS`, `WZ_BFF_ISSUER_URI`), app registrations per environment (`api://wellnessz-api-dev`, `…-uat`, `api://wellnessz-api`); Conditional Access and MFA in Entra ID; AKS with Entra ID RBAC and local accounts disabled; workload identities per component (core, BFF, migrations) | `WZ_MFA_REQUIRED_ROLES` empty (test identities), authorised IPs on the API server | MFA roles `PARTNER_PROVIDER,TPA_ADJUSTER` | as UAT | Shipped (Helm / Terraform); Entra configuration is an organisation step |
| GCP | Same Entra ID issuers in the overlays (any OIDC provider can be substituted); Google IAM for operators, Workload Identity (KSA → GSA) per component, Cloud SQL IAM database authentication for DBAs | as Azure | as Azure | as Azure | Shipped |
| Hybrid | Entra ID for users; on-premises workloads authenticate to Vault with Kubernetes auth roles per service account, or to Key Vault via Arc workload identity | as overlay | as overlay | as overlay | Shipped (manifests, Vault policies) |
| CI/CD | GitHub OIDC federation: each environment's deployer trusts only its own GitHub Environment (`azure-<env>` / `gcp-<env>`); no stored cloud credentials | publish from `main` allowed | environment only | environment only | Shipped |

**Recommended standard.** Entra ID (or the organisation's strategic OIDC IdP) for workforce and B2B partners with
Conditional Access enforcing MFA (phishing-resistant for external users) and device compliance for the mobile app;
Keycloak only for local development or where a cloud IdP is not permitted. Separate app registrations per
environment; workload identity everywhere. Configuration per provider: document 01, Section 8.5.

**SOC 2 / ISO 27001 relevance.** CC6.1, CC6.2, CC6.3; ISO 27001 A.5.15–A.5.18 (access control, identity,
authentication information, access rights), A.8.2 (privileged access), A.8.5 (secure authentication).

## 8. Secrets and key management

| Set-up | Standard used | DEV | UAT | PROD | Status |
|---|---|---|---|---|---|
| Local | `./up.sh` generates `deploy/local/.env` (mode 600, git-ignored) with random values; IDE env files hold secrets as `ENC(...)`, master keys in `~/.config/wellnessz/*.key`; pre-commit hook blocks a staged `.env` | – | – | – | Shipped |
| Azure | **Key Vault Premium**: RBAC, purge protection, private endpoint, per-secret role assignments, RSA-HSM keys for customer-managed keys; Secrets Store CSI with rotation polling; database passwords generated by Terraform as ephemeral values with write-only arguments (never in state) | soft delete 7 days | 30 days | 90 days | Shipped; PostgreSQL CMK **shipped (off)** in all environments (`postgres_cmk_enabled=false`) |
| GCP | **Secret Manager** with CMEK; **Cloud KMS** key ring (HSM, 90-day rotation) for Cloud SQL, GKE secrets, Artifact Registry and Secret Manager; per-secret accessor IAM | as PROD | as PROD | CMEK on all listed services | Shipped |
| Hybrid | **HashiCorp Vault** through the CSI provider for pods; **External Secrets Operator** for operator-consumed secrets (CloudNativePG role passwords, backup credentials); alternative: Azure Key Vault via the Arc extension | as overlay | as overlay | as overlay | Shipped (manifests) |
| Application (all) | `file:` references for sensitive properties, `ENC(...)` values, `WZ_MASTER_KEY_FILE`; separate BFF session key; field-level AES-256-GCM with HKDF-derived keys (document 01, Sections 6.3 and 9.9) | per-environment keys | per-environment keys | per-environment keys, key ceremony | Shipped |
| Application (all) | Envelope encryption of the field-encryption master key with a KMS / HSM key and a re-encryption job | – | – | – | **Recommended – not shipped** (decision D5) |

**Recommended standard.** The platform secret store with file mounts and workload identity (as shipped); CMK / CMEK
for databases and backups in PROD; dual-controlled key ceremony for the master key; envelope encryption before go-live.

**SOC 2 / ISO 27001 relevance.** CC6.1, C1.1; ISO 27001 A.8.24 (use of cryptography), A.5.17 (authentication
information), A.8.12 (data-leakage prevention). Evidence: Key Vault `AuditEvent`, Secret Manager Data Access logs,
Vault audit device, key-ceremony record.

## 9. Application runtime

| Set-up | Standard used | DEV | UAT | PROD | Status |
|---|---|---|---|---|---|
| All | Core and BFF: Java 21 (`eclipse-temurin:21.0.12_8-jre-noble`), Spring Boot 4.1.2, virtual threads, uid 10001, read-only root filesystem, `/tmp` as size-limited `emptyDir`; web apps: `nginx-unprivileged:1.30.6-alpine3.24` (uid 101) with strict CSP and security headers | same images | same images | same images, digests required | Shipped |
| Local | Spring profile `local` (relaxed `sslmode=prefer`, MFA gate off, demo data migrations, CORS for 5173–5176) | – | – | – | Shipped |
| Hybrid / OpenShift | `global.openshift=true` omits fixed UIDs so the restricted SCC assigns an arbitrary UID | – | – | – | Shipped |
| Client customisation | `customisation:` values: `WZ_CLIENT_ID`, `WZ_FEATURE_<NAME>` (plumbing only), client Flyway migrations in schema `client_<client>`, `core.extraEnv` | as configured | as configured | as configured | Shipped; extension JAR mounting **not implemented** |

**Recommended standard.** One hardened image per component, promoted unchanged; configuration only through
environment variables and mounted files. Never use the `local` profile or demo migration locations outside the
laptop.

**SOC 2 / ISO 27001 relevance.** CC6.8, CC8.1; ISO 27001 A.8.9, A.8.19, A.8.27 (secure architecture).

## 10. Database, high availability and backup

| Set-up | Standard used | DEV | UAT | PROD | Status |
|---|---|---|---|---|---|
| Local | PostgreSQL **16.15** container (databases `wellnessz`, `keycloak`); roles `wz_migrator`, `wz_app`, `wz_bff` as in production | – | – | – | Shipped |
| Azure | **Azure Database for PostgreSQL Flexible Server 16**: private access, Entra authentication for administrators, `require_secure_transport`, TLS ≥ 1.2, SCRAM-SHA-256, pgaudit | `B_Standard_B2ms`, 32 GB, **no HA**, 7-day backups, no geo-backup | `GP_Standard_D2ds_v5`, 64 GB, **zone-redundant HA**, 14 days | `GP_Standard_D4ds_v5`, 128 GB, zone-redundant HA, **35 days, geo-redundant** | Shipped; CMK **shipped (off)** |
| GCP | **Cloud SQL for PostgreSQL 16** (Enterprise): private IP only, `ENCRYPTED_ONLY`, IAM database authentication, pgaudit, CMEK, password policy | `db-custom-1-3840`, 20 GB, **ZONAL**, PITR 1 day, 7 backups | `db-custom-2-7680`, 50 GB, **REGIONAL**, PITR 7 days, 14 backups | `db-custom-4-16384`, 100 GB, REGIONAL, PITR 7 days, **30 backups** | Shipped |
| Hybrid | **CloudNativePG** (operator ≥ 1.26): 3 instances, at least one synchronous standby (`dataDurability: required`), TLS 1.3 with internal-PKI certificates, `hostssl` + SCRAM only, encrypted StorageClass, pgaudit; plus a small cloud PostgreSQL for BFF sessions only | operator sizing | operator sizing | as manifest | Shipped – the manifests describe one production-shaped cluster; DEV / UAT sizing (for example one instance in DEV) is an operator choice |

Common to all: one database, 18 product schemas plus `bff`; Flyway owner role separate from the DML-only runtime role;
Hibernate `ddl-auto: validate`; JDBC `sslmode=verify-full` (Cloud SQL `verify-ca` with the instance CA).

**Recommended standard.** Managed PostgreSQL with zone-redundant HA, PITR and CMK in PROD (UAT HA as shipped so that
failover is tested before PROD); CloudNativePG with a synchronous standby where data must stay on-premises.

**SOC 2 / ISO 27001 relevance.** A1.2 (availability), C1.1 (confidentiality), CC6.1; ISO 27001 A.8.13 (backup),
A.8.14 (redundancy), A.8.24, A.8.15 (pgaudit logging).

## 11. Messaging, eventing and outbox

| Set-up | Standard used | DEV | UAT | PROD | Status |
|---|---|---|---|---|---|
| All | **Transactional outbox** in `platform.outbox_event` (field-encrypted payloads, at-least-once relay with `SKIP LOCKED`, back-off, dead-lettering, operator replay, 30-day purge of delivered rows), idempotent consumers, in-process delivery between modules; outbound **allow-lists** per destination (`crew-rostering`, `hr-systems`, `catering-hygiene`, `insurer-gateway`, `authority-gateway`, `erp-finance`, `campaign-app`, `sms-safety` – the safety management system) | same | same | same | Shipped |
| External transport | `EventPublisher` SPI; today `LoggingExternalPublisher` (metadata only) and content-free e-mail defaults | – | – | – | Shipped (stand-in) |
| Azure | **Azure Service Bus** adapter | – | – | – | **Recommended – not shipped** |
| GCP | **Google Pub/Sub** adapter | – | – | – | **Recommended – not shipped** |
| Hybrid / group platform | **Apache Kafka** (group-wide platform) or an integration platform consuming the outbox | – | – | – | **Recommended – not shipped** |

**Recommended standard.** Keep the outbox as the reliability boundary in every set-up and add **one** broker per
hosting decision (decision D3); keep payloads flat and reference-only so that the allow-list remains the disclosure
control.

**SOC 2 / ISO 27001 relevance.** PI1.2–PI1.4 (processing integrity), CC6.7 (transmission), P6.1 (disclosure);
ISO 27001 A.5.14 (information transfer), A.8.12.

## 12. Object and document storage

| Set-up | Standard used | DEV | UAT | PROD | Status |
|---|---|---|---|---|---|
| All | Generated and uploaded documents (PDF, images, calibration certificates) are stored **in PostgreSQL** (`docs` schema), field-encrypted with AES-256-GCM, SHA-256 integrity hash, retention schedule and legal hold; uploads ≤ 10 MB checked by magic bytes | same | same | same | Shipped |
| Hybrid | **S3-compatible on-premises object store** (MinIO, NetApp StorageGRID, Dell ECS) for CloudNativePG backups via the Barman Cloud plugin, server-side encryption, off-site copy | – | – | 35-day retention | Shipped (manifest) |
| Azure / GCP | Blob Storage / Cloud Storage for documents | – | – | – | **Not used**; recommended only if document volume grows beyond what the database should hold |
| All | Immutable (WORM) object storage – Azure immutable blob, GCS bucket lock – for audit-hash anchoring and audit export | – | – | – | **Recommended – not shipped** (open item O2) |

**Recommended standard.** Keep documents in the database at current volumes – one encryption, backup and access path,
no second PHI store. Add immutable object storage for audit anchoring and for long-term export of audit evidence.

**SOC 2 / ISO 27001 relevance.** C1.1, C1.2, CC7.2; ISO 27001 A.5.33 (protection of records), A.8.10 (information
deletion), A.8.13.

## 13. Observability – logs, metrics and traces

| Set-up | Standard used | DEV | UAT | PROD | Status |
|---|---|---|---|---|---|
| Local | OpenTelemetry Collector contrib 0.164.0 with the `debug` exporter; Mailpit (SMTP sink); plain-text logs in `local` | trace sampling 100 % | – | – | Shipped |
| Azure | JSON logs → Container Insights / **Log Analytics**; diagnostic settings on every resource; Defender plans; OTLP to an in-cluster collector (`otel-collector.monitoring:4318/v1/traces`); Prometheus endpoint with optional `ServiceMonitor` | log retention **30 days**, Defender off (subscription-wide, enable once in non-prod) | **90 days**, Defender on | **365 days**, Defender on | Shipped; Azure Managed Prometheus / Azure Monitor tracing back end **recommended – not shipped** |
| GCP | **Cloud Logging** (`_Default` bucket retention per environment), **Data Access audit logs** for Secret Manager, KMS, Cloud SQL, GKE, IAM; **Managed Prometheus**; OTLP to a collector → Cloud Trace | 30 days | 90 days | 365 days | Shipped (collector deployment is an operator step) |
| Hybrid | Prometheus Operator / OTel collector / log shipping on-premises, forwarded to the SIEM | as overlay | as overlay | as overlay | **Recommended – not shipped** (application side shipped) |
| All | Hash-chained application audit (`/api/v1/audit/verify`); correlation id on requests, events and audit rows; per-environment log level and sampling (DEBUG / 1.0, INFO / 0.25, INFO-WARN / 0.05) | | | | Shipped |
| All | SIEM integration (Sentinel, Chronicle, Splunk), scheduled audit verification with alerting, external hash anchoring | – | – | – | **Recommended – not shipped** |

**Recommended standard.** OpenTelemetry and Micrometer into the platform's managed back end, JSON logs without PHI,
365-day retention in PROD and SIEM forwarding with the alert set listed in the Deployment Architecture.

**SOC 2 / ISO 27001 relevance.** CC4.1, CC7.2, CC7.3; ISO 27001 A.8.15 (logging), A.8.16 (monitoring activities),
A.8.17 (clock synchronisation – UTC clock).

## 14. Networking and private connectivity

| Set-up | Standard used | DEV | UAT | PROD | Status |
|---|---|---|---|---|---|
| Local | All ports on `127.0.0.1`; `apps-net` container shares the network namespace so that the Keycloak issuer URL is identical in browser and containers | – | – | – | Shipped |
| Azure | VNet with subnets for AKS (`/23`), App Gateway, delegated PostgreSQL and private endpoints; NSGs admitting 5432 / 443 only from the AKS subnet; private DNS zones; private endpoints for Key Vault and ACR; Cilium network policies | `10.48.0.0/16`, public AKS API (authorised IPs) | `10.44.0.0/16`, private AKS | `10.40.0.0/16`, private AKS | Shipped |
| GCP | Custom-mode VPC, secondary ranges for pods and services, VPC flow logs, Cloud NAT, Private Service Access for Cloud SQL, firewall deny for SSH / RDP from the internet, private nodes | subnet `10.68.0.0/20` | `10.56.0.0/20` | `10.60.0.0/20` | Shipped |
| Hybrid | **ExpressRoute** private peering (preferred) or VPN Gateway (Azure); **Dedicated / Partner Interconnect** or HA VPN with Cloud Router (GCP); IPsec IKEv2 AES-256-GCM; only TCP 443 from the BFF egress range to the internal API ingress, optional mTLS | as environment | as environment | as environment | Documented and configured on the application side; circuits and VPNs are **not provisioned** by the repository |
| Kubernetes (all) | Default-deny NetworkPolicies per workload; namespace-wide default deny | component policies only | namespace default deny | namespace default deny | Shipped |
| All | Service mesh with in-cluster mTLS | – | – | – | Optional (open item O6) |

Network ranges never overlap between environments, so DEV, UAT and PROD can peer with a hub or with on-premises
networks. The Helm overlays carry the matching App Gateway / PostgreSQL / Private Service Access ranges.

**Recommended standard.** Private endpoints for every data service, private Kubernetes control planes in UAT and
PROD, default-deny at subnet and pod level, dedicated private connectivity (ExpressRoute / Interconnect) for hybrid.

**SOC 2 / ISO 27001 relevance.** CC6.6, CC6.7; ISO 27001 A.8.20, A.8.21, A.8.22.

## 15. CI/CD

![CI/CD pipeline](../images/14-cicd-pipeline.png)

*Figure 2 – CI/CD pipeline (source: `docs/images/14-cicd-pipeline.svg`).*

| Set-up | Standard used | DEV | UAT | PROD | Status |
|---|---|---|---|---|---|
| Local | `./mvnw verify`, `-Psecurity`, `npm test` / `lint` / `build` per app, Playwright E2E scripts, pre-commit hook (developer guide) | – | – | – | Shipped |
| GitHub Actions (all clouds) | `ci.yml` (Maven tests, JaCoCo, CycloneDX, SpotBugs + FindSecBugs, web matrix, gitleaks, dependency-check, dependency review, Trivy config scan, Helm lint / kubeconform for all overlays, Terraform fmt), `codeql.yml`, `container.yml` (build, scan, sign, attest), `cd.yml` (promotion), `deploy-env.yml` (one stage); all actions pinned to commit SHAs | every `main` build deployed automatically (`sha-*` or `v*`) | `vX.Y.Z[-rc.N]` only, **required reviewers** | `vX.Y.Z` only, **required reviewers (CAB / security, prevent self-review)** | Workflows shipped; **required reviewers and protection rules must be configured in repository settings** – they cannot be set from code |
| Azure | `azure/login` with OIDC; private AKS reached through `az aks command invoke` running `deploy/scripts/helm-release.sh` | public API path | command-invoke path | command-invoke path | Shipped (command-invoke output format not yet validated on real AKS) |
| GCP | `google-github-actions/auth` with Workload Identity Federation (attribute `environment/gcp-<env>`); Binary Authorization attestation | dry-run admission | enforced | enforced | Shipped (attestation listing not yet validated on real GCP) |
| Hybrid | Same pipeline against the cloud edge cluster; on-premises cluster via a self-hosted runner or GitOps (Arc GitOps / GKE Enterprise Config Sync) | | | | Pipeline shipped; GitOps **recommended – not shipped** |

Every stage: promotion-policy check → OIDC login → cosign verify → copy by digest → `helm upgrade --install --atomic`
with the overlay and all images pinned → smoke tests (rollout, running digests, `SMOKE_URLS`) → automatic
`helm rollback` on failure. Migrations are forward-only (expand / contract).

**Recommended standard.** GitHub Actions with protected environments, OIDC federation and promotion by digest (as
shipped); add DAST (OWASP ZAP baseline against UAT – **recommended – not shipped**).

**SOC 2 / ISO 27001 relevance.** CC8.1 (change management), CC7.1; ISO 27001 A.8.25, A.8.29 (security testing),
A.8.31, A.8.32, A.8.4 (access to source code).

## 16. Infrastructure as code

| Set-up | Standard used | DEV | UAT | PROD | Status |
|---|---|---|---|---|---|
| Local | Docker Compose (`postgres:16.15-bookworm`, Keycloak 26.8.1, Mailpit 1.32.0, GreenMail 2.1.15, OTel Collector 0.164.0) | – | – | – | Shipped |
| Azure | **Terraform** `>= 1.11` with `hashicorp/azurerm ~> 4.81`, `hashicorp/random ~> 3.7`; `environment` validated to `dev` / `uat` / `prod`; remote state with Entra ID authentication, one back end per environment | `dev.tfvars`, `backend-dev.hcl` | `uat.tfvars` | `prod.tfvars`, resource locks | Shipped |
| GCP | **Terraform** with `hashicorp/google` and `google-beta ~> 8.9`; GCS state bucket (versioning, CMEK); one project per environment | `dev.tfvars` | `uat.tfvars` | `prod.tfvars` | Shipped |
| Hybrid | Kubernetes manifests (`deploy/hybrid/cnpg`, `external-secrets`), Vault policies (HCL), Helm overlays; no Terraform for on-premises infrastructure | | | | Shipped; Terraform / Ansible for on-premises hosts **recommended – not shipped** |
| Workloads (all) | **Helm** chart `0.1.0` (Kubernetes `>= 1.30`) with JSON schema and nine environment overlays; `global.requireImageDigests` | | digests required | digests required | Shipped |

**Recommended standard.** Terraform with one state per environment (ideally separate subscription / project and state
storage), validated in CI; OpenTofu is a drop-in alternative if the BSL licence is a concern. Policy-as-code checks
(Trivy config scan shipped; Azure Policy / Policy Controller in the clusters).

**SOC 2 / ISO 27001 relevance.** CC8.1, CC7.1; ISO 27001 A.8.9 (configuration management), A.8.32.

## 17. Backup and disaster recovery

| Set-up | Standard used | DEV | UAT | PROD | Status |
|---|---|---|---|---|---|
| Local | none (synthetic data; `down.sh` can purge volumes) | – | – | – | Shipped |
| Azure | Flexible Server automated backups with PITR; geo-redundant backup in PROD (immutable after creation); zone-redundant HA standby | 7 days, no HA | 14 days, HA | 35 days, geo-redundant, HA | Shipped |
| GCP | Cloud SQL automated backups and PITR (transaction logs); `REGIONAL` HA | 7 backups, 1-day PITR, ZONAL | 14 backups, 7-day PITR | 30 backups, 7-day PITR | Shipped |
| Hybrid | Barman Cloud: continuous WAL archiving + daily base backups (`04-scheduled-backup.yaml`) to the S3-compatible object store with off-site copy; synchronous standby | operator choice | operator choice | 35-day retention | Shipped (manifests) |
| All | Region / site failover, cross-region read replica, automated DR runbook, quarterly restore tests | – | – | – | **Recommended – not shipped** (single region in the IaC; decision D9) |

Proposed targets (for GRC sign-off, document 01 Section 12 and Deployment Architecture Section 10.1): zone failure
RPO 0; logical corruption RPO ≤ 15 min (PITR); region loss RPO ≤ 1 h with RTO ≤ 24 h (≤ 4 h with a warm standby).

**Recommended standard.** PITR in every shared environment, geo-redundant or second-site backups for PROD, a
documented and **tested** restore (quarterly, into an isolated environment, smoke-tested), and a funded cross-region
replica if the RTO must be below 24 hours.

**SOC 2 / ISO 27001 relevance.** A1.2, A1.3 (recovery testing), CC9.1; ISO 27001 A.5.29, A.5.30 (ICT readiness for
business continuity), A.8.13, A.8.14.

## 18. Security scanning

| Set-up | Standard used | DEV | UAT | PROD | Status |
|---|---|---|---|---|---|
| Local | SpotBugs + FindSecBugs (`-Psecurity`), unit / integration / ArchUnit tests, ESLint, pre-commit hook against staged secrets | – | – | – | Shipped |
| CI (all) | **CodeQL** (Java, TypeScript; push, PR, weekly), **SpotBugs + FindSecBugs** (CI step, fails on any finding – 0 findings, 6 justified exclusions), **OWASP dependency-check** (fail CVSS ≥ 7, SARIF), GitHub dependency review, `npm audit`, **gitleaks** (full history), **Trivy** image gate and config scan (Dockerfiles, Helm, Terraform), CycloneDX SBOMs | | | | Shipped |
| Azure | Microsoft **Defender for Cloud** plans (containers, Key Vault, databases), Azure Policy on AKS | off (enable once per non-prod subscription) | on | on | Shipped (variable-controlled) |
| GCP | Artifact Registry **vulnerability scanning**, GKE **security posture**, Binary Authorization | dry-run | enforced | enforced | Shipped |
| Hybrid | In-cluster scanning (for example Trivy Operator, OpenShift compliance operator, Policy Controller audit) | – | – | – | **Recommended – not shipped** |
| All | **DAST** (OWASP ZAP baseline) against UAT; annual penetration test | – | – | – | **Recommended – not shipped** |

**Recommended standard.** The shipped CI gates on every change, runtime posture management in every cluster, DAST
against UAT before each PROD release and an independent penetration test before go-live.

**SOC 2 / ISO 27001 relevance.** CC7.1, CC8.1; ISO 27001 A.8.8 (technical vulnerabilities), A.8.29 (security testing
in development and acceptance), A.8.7 (protection against malware).

## 19. Recommended but not shipped – consolidated list

| # | Item | Layer | Why it matters | Reference |
|---|---|---|---|---|
| 1 | Public DNS with DNSSEC and CAA; CDN only for static assets if needed | Edge | Domain hijack and mis-issuance protection | Section 3 |
| 2 | Signature verification at admission on AKS and on-premises | Supply chain | Prevents deployment of unsigned images outside the pipeline | Section 6, doc 01 R7 |
| 3 | Restrict ACR public network access (runner ranges or self-hosted runners) | Supply chain | Registry reachable from the internet in all Azure environments today | Section 6 |
| 4 | Private GKE control-plane endpoint in UAT / PROD | Compute | Master authorised networks only today | Section 5 |
| 5 | PostgreSQL CMK in Azure PROD | Database | Customer control of the encryption key | Section 10 |
| 6 | Envelope encryption of the field-encryption master key, re-encryption job | Secrets | Limits blast radius of master-key compromise | Section 8, doc 01 D5 |
| 7 | One managed broker adapter per hosting decision | Messaging | Production delivery to enterprise systems | Section 11, doc 01 D3 |
| 8 | Immutable object storage for audit anchoring; SIEM forwarding and scheduled audit verification | Storage / observability | Tamper evidence beyond the database | Sections 12–13, doc 01 O2 |
| 9 | Managed tracing / metrics back end on Azure; observability stack on-premises | Observability | Collector shipped without a back end on Azure and hybrid | Section 13 |
| 10 | ExpressRoute / Interconnect circuits and VPNs for hybrid | Networking | Connectivity is an operator deliverable | Section 14 |
| 11 | GitHub Environment protection rules and required reviewers | CI/CD | Approval control cannot be set from code | Section 15, doc 01 O33 |
| 12 | DAST, in-cluster scanning on-premises, penetration test | Security scanning | Run-time vulnerabilities | Section 18 |
| 13 | Cross-region / second-site DR with tested restores | Backup / DR | Single region today | Section 17, doc 01 D9 |
| 14 | GitOps for the on-premises cluster | CI/CD | Pull-based deployment without inbound access | Section 15, doc 01 D24 |

## 20. Framework mapping by layer

| Layer | SOC 2 Trust Services Criteria | ISO/IEC 27001:2022 Annex A |
|---|---|---|
| Edge / DNS / WAF / CDN | CC6.6, CC6.7, CC7.2 | A.8.20, A.8.21, A.8.23, A.8.24 |
| Ingress / load balancing | CC6.6, CC6.7 | A.8.20, A.8.22 |
| Compute / orchestration | CC6.1, CC6.8, CC7.1, A1.2 | A.8.6, A.8.9, A.8.14, A.8.19, A.8.31 |
| Registry / supply chain | CC7.1, CC8.1 | A.5.21, A.8.8, A.8.25, A.8.28, A.8.32 |
| Identity & access | CC6.1, CC6.2, CC6.3 | A.5.15–A.5.18, A.8.2, A.8.5 |
| Secrets & keys | CC6.1, C1.1 | A.5.17, A.8.12, A.8.24 |
| Application runtime | CC6.8, CC8.1 | A.8.9, A.8.19, A.8.27 |
| Database / HA | A1.2, C1.1, CC6.1 | A.8.13, A.8.14, A.8.15, A.8.24 |
| Messaging / outbox | PI1.2–PI1.4, CC6.7, P6.1 | A.5.14, A.8.12 |
| Object / document storage | C1.1, C1.2, CC7.2 | A.5.33, A.8.10, A.8.13 |
| Observability | CC4.1, CC7.2, CC7.3 | A.8.15, A.8.16, A.8.17 |
| Networking | CC6.6, CC6.7 | A.8.20, A.8.21, A.8.22 |
| CI/CD | CC8.1, CC7.1 | A.8.4, A.8.25, A.8.29, A.8.31, A.8.32 |
| Infrastructure as code | CC8.1, CC7.1 | A.8.9, A.8.32 |
| Backup / DR | A1.2, A1.3, CC9.1 | A.5.29, A.5.30, A.8.13, A.8.14 |
| Security scanning | CC7.1, CC8.1 | A.8.7, A.8.8, A.8.29 |

Cross-cutting: separation of DEV, UAT and PROD (separate subscriptions / projects, state, secret stores, IdP
registrations, GitHub Environments; synthetic data in DEV, synthetic or masked data in UAT, production PHI only in
PROD) supports ISO 27001 A.8.31 and A.8.33 (test information) and SOC 2 CC8.1. The cloud providers and the IdP are
subservice organisations whose SOC 2 / ISO 27001 reports must be reviewed (document 02, Section 2; ISO 27001 A.5.23).
