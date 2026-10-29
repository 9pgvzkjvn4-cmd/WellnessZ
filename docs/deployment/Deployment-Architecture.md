# WellnessZ OHCM – Deployment Architecture

| | |
|---|---|
| **Document** | 04 – Deployment Architecture |
| **Audience** | CIO, Cyber GRC, infrastructure and cloud platform teams, auditors |
| **Scope** | Reference topology and the four supported setups: Local MacBook Pro, Microsoft Azure, Google Cloud, Hybrid cloud |
| **Artefacts** | `deploy/local`, `deploy/docker`, `deploy/helm/wellnessz`, `deploy/terraform/azure`, `deploy/terraform/gcp`, `deploy/hybrid`, `deploy/sql`, `.github/workflows` |
| **Companion documents** | 01 – Technology Architecture · 02 – Security & SOC 2 Control Mapping · 05–08 – Setup guides · 09 – CI/CD Guide |

This document describes **where** WellnessZ runs and **how** it is protected in each deployment option. It is
written for decision makers: every statement is backed by an artefact in the repository, and the final sections
state proposed service levels, cost shape and the validation status of each setup.

> **Status of validation.** The local setup was run end to end in a sandbox. That run covered the full Docker
> Compose stack, real Keycloak tokens, core and BFF containers on a read-only root filesystem, Flyway in both
> migration modes, and the database least-privilege checks. The Helm chart renders and passes schema validation
> for every platform. Terraform passes `validate` for Azure and GCP. **Nothing has yet been applied to a real Azure
> subscription, GCP project or on-premises cluster**; the first environment build is therefore also the
> acceptance test of the infrastructure code.
>
> **Increment 1** (E01 intake, E02 triage) adds the OH workspace web app, the `triage` schema, nine roles and the
> EHS webhook. It was verified on the local setup (Java and web tests, `tools/e2e/e2e-increment1.js` 18/18); Helm
> values for every platform include the new component. Section 17 lists the changes per environment.
>
> **Increment 2** (E03 OH record, E05 fitness for duty) adds no new image or Helm component. It adds three schemas
> (`docs`, `ohrecord`, `fitness`), the `DPO` role, optional PDF font and OCR settings and pull endpoints for the
> laboratory, device and rostering gateways. It was verified on the local setup (128 Java tests, web tests,
> `tools/e2e/e2e-increment2.js` 25/25). Section 18 lists the changes per environment.
>
> **Increment 3** (E06 absence, E07 surveillance, E14 scheduling & clinic operations) adds no new image or Helm
> component either. It adds three schemas (`absence`, `surveillance`, `scheduling`), four roles, nine scheduled jobs in
> the core and machine endpoints for absence feeds, industrial hygiene, dosimetry, recruiting and IoT fridge readings.
> It was verified on the local setup (210 Java tests, web tests, `tools/e2e/e2e-increment3.js` 32/32). Section 19
> lists the changes per environment.
>
> **Increment 4** (E04 treatment coordination & partner referrals, E08 claims coordination) adds **one new image and
> Helm component**, the partner portal (`partnerPortal`, host `partners.<domain>`) for external partner providers and
> TPA adjusters – the first host intended for users outside the airline, with MFA enforced at the IdP and verified by
> the core. It adds two schemas (`treatment`, `claims`), five roles, four scheduled jobs and reference-only outbound
> destinations for insurers, authorities and the ERP. It was verified on the local setup (275 Java tests, web tests
> including partner-portal 119, `tools/e2e/e2e-increment4.js` 22/22 plus the earlier scripts), and the chart passes
> `helm lint`, `helm template` and kubeconform with the new component. Section 20 lists the changes per environment.

## 1. Reference deployment topology

All four setups share the same building blocks and the same container images. Only the platform services
around the cluster change.

![Deployment reference](../images/07-deployment-reference.png)

*Figure 1 – Reference deployment view (source: `docs/images/07-deployment-reference.svg`).*

| Building block | Implementation | Notes |
|---|---|---|
| **Employee portal / mobile PWA** | `web/employee-app` – static SPA in `nginx-unprivileged` (port 8080, uid 101) | Same origin as the BFF; mobile builds (Capacitor) call the core API directly with PKCE tokens |
| **Manager & HR workspace** | `web/manager-workspace` – static SPA (port 8080) | Status-only views |
| **OH workspace** (increment 1) | `web/oh-workspace` – static SPA (port 8080), Helm component `ohWorkspace`, host `oh.<domain>` | Clinical console for the OH team, reception and first aiders; same origin as the BFF; since increment 4 also claims, finance, legal and procurement users (no clinical screens) |
| **Partner portal** (increment 4) | `web/partner-portal` – static SPA (port 8080), Helm component `partnerPortal`, host `partners.<domain>` | External partner providers and TPA adjusters; same origin as the BFF; partner roles only with MFA proof in the token |
| **BFF** | `apps/wz-bff` – Spring Boot, port 8090, management 8081 (k8s) / 8091 (local) | OIDC confidential client, encrypted JDBC sessions (schema `bff`), CSRF, token relay; tokens never reach the browser |
| **Core API** | `apps/wz-core-app` – Spring Boot, port 8080, management 8081 | Multi-issuer JWT resource server, ABAC, audit, integrations, EHS intake, Flyway migrations, business modules E01–E08, E14 and their scheduled jobs |
| **Database** | PostgreSQL 16 | Schemas `platform, masterdata, intake, casemgmt, triage, docs, ohrecord, fitness, absence, surveillance, scheduling, treatment, claims, analytics` + `bff` (`triage` since increment 1; `docs`, `ohrecord`, `fitness` since increment 2; `absence`, `surveillance`, `scheduling` since increment 3; `treatment`, `claims` since increment 4); roles `wz_migrator` (owner), `wz_app`, `wz_bff` |
| **Identity provider** | Microsoft Entra ID (production), Keycloak 26 (local / alternative), any OIDC IdP | Roles from `realm_access.roles`, `roles` (Entra app roles) or `groups`; `employee_id` and `org_scope` claims drive ABAC; since increment 4 MFA proof (`amr`, `acr` or the Entra ID `acrs` authentication context) for partner and adjuster users, and a fixed `org_scope=/PARTNERS/<CODE>` claim on partner / insurer machine clients |
| **Secrets** | Azure Key Vault, GCP Secret Manager or HashiCorp Vault via Secrets Store CSI | Mounted as files; consumed through `file:` references, `WZ_MASTER_KEY_FILE` and `ENC(...)` values |
| **Edge** | App Gateway WAF v2 / Front Door (Azure), Cloud Armor + global LB (GCP), internal ingress (on-prem) | TLS 1.2+, OWASP rule sets, rate limiting on login paths; `partners.<domain>` is internet-facing for external users (Section 20) |
| **Observability** | Micrometer/Prometheus, OpenTelemetry (OTLP), JSON logs with PHI masking | Log Analytics / Cloud Logging / SIEM |

### 1.1 Runtime contract shared by every setup

* **One image per component, promoted unchanged.** The same digest moves from dev to prod; images are signed
  with cosign and carry SBOM and SLSA provenance attestations (`container.yml`).
* **Hardened pods.** Every pod runs non-root with a read-only root filesystem, drops `ALL` capabilities, uses the
  `RuntimeDefault` seccomp profile, does not automount a service-account token and has no privilege escalation.
  `values.schema.json` makes these settings impossible to switch off through values.
* **Default-deny networking.** NetworkPolicies deny all traffic and then allow only these flows:
  * ingress controller → web apps and BFF;
  * BFF → core;
  * core and BFF → database;
  * port 443 egress to the identity provider and Microsoft Graph;
  * optionally, telemetry to the OTel collector.
* **One identity per component.** Core, BFF and the migration job each have their own Kubernetes ServiceAccount,
  cloud identity and secret set. The BFF never holds the core master key and has no access to clinical schemas.
* **Database least privilege.** `wz_migrator` owns the schemas and runs DDL only. `wz_app` gets exactly the
  per-table privileges granted by the Flyway migrations; for example, `platform.audit_event` is SELECT/INSERT only,
  which makes it append-only. `wz_bff` can reach only the `bff` session tables.

## 2. The four setups at a glance

| Aspect | 1 · Local MacBook Pro | 2 · Microsoft Azure | 3 · Google Cloud | 4 · Hybrid cloud |
|---|---|---|---|---|
| Purpose | Development, demos, tests | Production / regulated SaaS-style operation | Production on GCP | Production with PHI kept on-premises |
| Compute | Docker Desktop or Colima; IDE processes | AKS (Azure CNI Overlay + Cilium), 3 zones | GKE Autopilot (or Standard), regional | Cloud AKS/GKE (web tier) + RKE2/OpenShift (core) |
| Database | PostgreSQL 16.15 container | PostgreSQL Flexible Server 16, zone-redundant HA | Cloud SQL Postgres 16, REGIONAL HA | CloudNativePG 3 instances on-prem + small cloud PG for sessions |
| Identity (users) | Keycloak 26 realm `wellnessz` | Entra ID | Entra ID (or Keycloak/Okta) | Entra ID |
| Identity (workloads) | n/a | Workload identity (federated credentials) | Workload Identity (KSA → GSA) | Arc workload identity / Vault Kubernetes auth |
| Secrets | `.env` generated locally | Key Vault + CSI (rotation 2 min) | Secret Manager + GKE CSI add-on | Vault + CSI / External Secrets (or Key Vault via Arc) |
| Edge / WAF | none (localhost only) | App Gateway WAF v2 (DRS 2.1), optional Front Door | Cloud Armor (CRS 3.3), managed certs | Cloud WAF for web tier; internal ingress on-prem |
| Encryption at rest | Docker volume (dev data only) | Platform keys + optional CMK, host encryption | CMEK (Cloud KMS HSM) for SQL, etcd, registry, secrets | Encrypted StorageClass, encrypted backups |
| Backups / PITR | none | 35 days, geo-redundant, PITR | 30 backups, PITR 7 days | Barman Cloud to object storage, 35 days PITR |
| Proposed RPO / RTO | n/a | 0 / < 5 min (zone); ≤ 1 h / < 4 h (region) | 0 / < 5 min (zone); ≤ 5 min PITR / < 4 h (region) | 0 / < 15 min (node); DR restore < 8 h |
| Infrastructure code | `deploy/local` | `deploy/terraform/azure` + `values-azure.yaml` | `deploy/terraform/gcp` + `values-gcp.yaml` | `deploy/hybrid` + `values-hybrid*.yaml` |
| Setup guide | 05 – `setup/01-Local-MacBook-Pro.md` | 06 – `setup/02-Azure.md` | 07 – `setup/03-GCP.md` | 08 – `setup/04-Hybrid-Cloud.md` |
| Validation status | **Executed** in sandbox | Code validated, not applied | Code validated, not applied | Manifests schema-validated, not applied |

## 3. Setup 1 – Local MacBook Pro

![Local MacBook Pro](../images/08-deploy-local-macbook.png)

*Figure 2 – Local developer topology (source: `docs/images/08-deploy-local-macbook.svg`).*

* **Components.** `deploy/local/docker-compose.yml` runs:
  * PostgreSQL 16.15 (databases `wellnessz` and `keycloak`);
  * Keycloak 26.8.1, importing the realm `wellnessz`;
  * Mailpit (SMTP sink) and GreenMail (IMAP mailbox `ehs-feed@wellnessz.local`);
  * an OpenTelemetry collector.

  Core and BFF run either from the IDE (Spring profile `local`) or as hardened containers with `./up.sh --apps`.
  The four web apps run as Vite dev servers on ports 5173 (employee), 5174 (manager), 5175 (OH workspace) and 5176
  (partner portal, increment 4) that proxy `/bff`, `/oauth2`, `/login` and `/logout` to the BFF.
* **Network.** All ports bind to `127.0.0.1`; nothing is reachable from the LAN. In the `apps` profile, a small
  `apps-net` container owns the network namespace of core and BFF and forwards loopback port 8180 to Keycloak. As a
  result, the issuer URL `http://localhost:8180/realms/wellnessz` is identical in the browser and inside the
  containers.
* **Identity.** Keycloak realm `wellnessz` with these clients:

  | Client | Type |
  |---|---|
  | `wellnessz-bff` | Confidential, PKCE S256 |
  | `wellnessz-mobile` | Public, PKCE S256 |
  | `wellnessz-api` | Audience |
  | `wellnessz-integration` | Client credentials, role `INTEGRATION` (also used for the EHS webhook) |

  Eighteen test users have temporary passwords (the seven of the base framework plus `nurse1`, `nurse2`,
  `physician1`, `ame1`, `cmo1`, `nursinghead1`, `hse1`, `crewsched1`, `hygiene1`, `reception1`, `firstaider1`). The
  realm defines the nine increment-1 roles, and the scope mappings of `wellnessz-bff` and `wellnessz-mobile` include
  them, because both clients have `fullScopeAllowed=false`. Increment 2 adds the role `DPO` (also in both scope
  mappings) and two more test users, `dpo1` (DPO) and `partner1` (PARTNER_PROVIDER, `org_scope=/PARTNERS/CITYHOSP`).
  Increment 3 adds the roles `CLINIC_SCHEDULER`, `BIOMEDICAL`, `CREW_LICENSING` and `CANDIDATE` (also in both scope
  mappings) and the users `scheduler1`, `biomed1`, `crewlicensing1` (`org_scope=/AIRLINE/OPS`) and `candidate1`
  (`employee_id=C9001`, `org_scope=/CANDIDATES`). Increment 4 adds the roles `CLAIMS_COORDINATOR`, `TPA_ADJUSTER`,
  `LEGAL_COUNSEL`, `FINANCE` and `PROCUREMENT` (also in both scope mappings), the users `claims1`, `adjuster1`
  (`/PARTNERS/GULFTPA`), `legal1`, `finance1`, `procurement1` and `physio1` (`/PARTNERS/PHYSIOPLUS`), and the 5176
  redirect URI, post-logout URI and web origin for the partner portal. The demo realm has no OTP enrolment, so the
  `local` profile switches the core's MFA gate off. The realm also enforces brute-force protection, a password policy,
  5-minute access tokens, a 15-minute SSO idle timeout and least-privilege scope mappings. The ABAC attributes
  `employee_id` and `org_scope` are editable by administrators only.
* **Secrets.** `./up.sh` generates `deploy/local/.env` (mode 600, git-ignored) containing random values. These
  values are never reused anywhere else.
* **Data protection.** Development data only. Real or production PHI must never be loaded onto a laptop.

## 4. Setup 2 – Microsoft Azure

![Azure](../images/09-deploy-azure.png)

*Figure 3 – Azure topology (source: `docs/images/09-deploy-azure.svg`).*

### 4.1 Components

| Layer | Resource (Terraform `deploy/terraform/azure`) | Key settings |
|---|---|---|
| Network | VNet `10.40.0.0/16`; subnets AKS `/23`, App Gateway `/24`, PostgreSQL (delegated) `/24`, private endpoints `/24`; NSGs; private DNS zones | Database and private-endpoint subnets accept 5432/443 only from the AKS subnet |
| Edge | Application Gateway WAF_v2 (zone-redundant, autoscale 2–10), WAF policy | Prevention mode, Microsoft DRS 2.1 (successor of OWASP CRS 3.2) + Bot Manager 1.1, rate limit on `/oauth2/` and `/login/`, TLS policy `AppGwSslPolicy20220101S` |
| Optional global edge | Azure Front Door Premium + WAF | App Gateway NSG then admits only `AzureFrontDoor.Backend` |
| Compute | AKS, system pool + user pool across zones 1–3 | Entra ID-only access with Azure RBAC, local accounts disabled, private API server, OIDC issuer + workload identity, Azure Policy, Defender, Key Vault CSI with rotation, Container Insights, host encryption, auto-upgrade channels |
| Database | PostgreSQL Flexible Server 16 | Private access, zone-redundant HA, geo-redundant backups (35 days), Entra authentication, `require_secure_transport`, TLS ≥ 1.2, pgaudit, optional customer-managed key |
| Secrets & keys | Key Vault Premium | RBAC, purge protection, 90-day soft delete, private endpoint, per-secret role assignments, RSA-HSM keys for CMK |
| Registry | Azure Container Registry Premium | No admin user, zone-redundant, private endpoint, 30-day retention for untagged manifests |
| Logging | Log Analytics (365 days) + diagnostic settings on every resource; Defender plans | Evidence for SOC 2 CC7.2 |
| Identities | User-assigned identities for core, BFF and migrations (federated to AKS service accounts); GitHub deployer identity (federated to GitHub Environments) | No client secrets anywhere in the pipeline |

### 4.2 Request path

1. The user resolves `employee.<domain>` (or `manager.<domain>`, `oh.<domain>`, `partners.<domain>`). Traffic reaches
   Front Door (optional), then the App Gateway WAF over TLS 1.2+.
2. The AGIC-managed listener routes requests by path:
   * `/` goes to `employee-portal` (or `manager-workspace`, `oh-workspace`, `partner-portal`);
   * `/bff`, `/oauth2`, `/login` and `/logout` go to `wz-bff` on the same origin.
3. The BFF authenticates the user against Entra ID with the authorization-code flow and PKCE. It stores the
   encrypted session in schema `bff` and relays the access token to `wz-core-app`. For partner and adjuster guests
   Entra ID enforces MFA through Conditional Access before it issues the token (increment 4).
4. The core validates the JWT (issuer allow-list and audience `api://wellnessz-api`), applies ABAC and writes the
   audit trail. It reaches PostgreSQL over TLS with `sslmode=verify-full`.

## 5. Setup 3 – Google Cloud

![GCP](../images/10-deploy-gcp.png)

*Figure 4 – Google Cloud topology (source: `docs/images/10-deploy-gcp.svg`).*

| Layer | Resource (Terraform `deploy/terraform/gcp`) | Key settings |
|---|---|---|
| Network | Custom-mode VPC, subnet with secondary ranges for pods and services, VPC flow logs, Cloud NAT, Private Service Access `10.60.64.0/20` | Explicit deny for SSH/RDP from the internet |
| Edge | Global external Application Load Balancer via GKE Ingress, Google-managed certificates, FrontendConfig (HTTPS redirect, SSL policy `RESTRICTED`, TLS 1.2+) | Cloud Armor policy attached through BackendConfig |
| WAF | Cloud Armor | Preconfigured CRS 3.3 rules (SQLi, XSS, LFI, RFI, RCE, protocol, session fixation, scanner, Java, CVE canary), rate-based ban on login paths, adaptive L7 DDoS protection, verbose logging |
| Compute | GKE Autopilot (default) or Standard, private nodes, Dataplane V2 | Workload Identity, Binary Authorization (KMS-backed attestor, dry-run then enforce), Shielded nodes, CMEK for etcd secrets, Secret Manager CSI add-on, security posture, managed Prometheus |
| Database | Cloud SQL PostgreSQL 16 | Private IP only, `REGIONAL` HA, PITR (7 days of logs), 30 backups, `ENCRYPTED_ONLY`, IAM database authentication, pgaudit, CMEK, password policy |
| Secrets & keys | Secret Manager with CMEK, Cloud KMS key ring (HSM, 90-day rotation) | Per-secret accessor IAM per workload service account |
| Registry | Artifact Registry | CMEK, immutable tags, cleanup policies, vulnerability scanning |
| Logging | `_Default` bucket retention 365 days; Data Access audit logs for Secret Manager, KMS, Cloud SQL, GKE, IAM | Evidence for SOC 2 CC7.2 |

## 6. Setup 4 – Hybrid cloud

![Hybrid](../images/11-deploy-hybrid.png)

*Figure 5 – Hybrid topology (source: `docs/images/11-deploy-hybrid.svg`).*

**Rationale.** Some airlines must keep occupational-health records in their own data centre, for data
residency, works-council agreements or regulator expectations. The hybrid option therefore splits the system:

* the PHI-free, elastic web tier (the SPAs – employee portal, manager workspace, since increment 1 the OH workspace
  and since increment 4 the partner portal – and the BFF) runs in a cloud cluster behind a managed WAF;
* the core API and PostgreSQL run on-premises, on RKE2 or OpenShift.

Both clusters are managed as one fleet through **Azure Arc** or **GKE Enterprise**.

| Concern | Design |
|---|---|
| Connectivity | ExpressRoute private peering, Cloud Interconnect or IPsec site-to-site VPN (IKEv2, AES-256-GCM). Only TCP 443 is open, from the BFF egress range to the internal API ingress (internal PKI, optional mTLS). |
| Database | CloudNativePG cluster with 3 instances and a synchronous replica (RPO 0). TLS 1.3 uses cert-manager certificates from the internal PKI; `pg_hba` allows only `hostssl` + SCRAM. pgaudit is enabled and storage is encrypted. |
| Backups | Barman Cloud plugin streams WAL and daily base backups to S3-compatible object storage; 35-day PITR, replicated to a second site. |
| Secrets | HashiCorp Vault through the CSI provider for pods; External Secrets Operator for operator-consumed secrets (CNPG role passwords, backup credentials). Alternative: Azure Key Vault via the Arc extension. |
| Cloud session store | A small managed PostgreSQL in the cloud holds only `bff` sessions (encrypted OAuth tokens, no PHI). |
| Policy | The same Pod Security, NetworkPolicy and admission policies (Azure Policy / Policy Controller); image signatures verified on admission. |

## 7. Network zones and segmentation

| Zone | Contents | Allowed inbound | Allowed outbound |
|---|---|---|---|
| Internet edge | Front Door / App Gateway / global LB + WAF | 443 (80 redirect only) from the internet | Ingress backends only |
| Web tier | `employee-portal`, `manager-workspace`, `oh-workspace`, `partner-portal` (increment 4) | 8080 from the ingress controller / LB ranges | none |
| BFF tier | `wz-bff` | 8090 from the ingress; 8081 from monitoring | Core 8080, database 5432 (`bff` schema), IdP 443 |
| Core tier | `wz-core-app`, Flyway job | 8080 from the BFF and API ingress (mobile, M2M, EHS webhook); 8081 from monitoring | Database 5432, IdP 443 (JWKS), Microsoft Graph / IMAPS / SMTP submission, OTLP |
| Data tier | PostgreSQL, Key Vault / Secret Manager / Vault (private endpoints) | 5432 from the AKS subnet or PSA range; 443 from CSI drivers | none |
| Management | Kubernetes API (private), bastion / self-hosted runners | Entra ID-authenticated admins; CI deployer identity | – |

Kubernetes NetworkPolicies enforce the pod-level rules and fail closed: an empty peer list is never rendered.
Cloud NSGs and firewall rules enforce the subnet level.

## 8. Identity

### 8.1 Human identities

* **Production:** Microsoft Entra ID with three app registrations, configured step by step in the
  [Azure setup guide](../setup/02-Azure.md):

  | App registration | Type | Purpose |
  |---|---|---|
  | `wellnessz-bff` | Confidential web client | Browser login through the BFF |
  | `wellnessz-mobile` | Public client, PKCE | Mobile app login |
  | `wellnessz-api` | Resource | Exposes the app roles (EMPLOYEE, MANAGER, HR, OH_CLINICIAN, …; increment 1 adds OH_PHYSICIAN, AME, CMO, NURSING_HEAD, HSE_OFFICER, CREW_SCHEDULING, HYGIENE_MANAGER, CLINIC_RECEPTION, FIRST_AIDER; increment 2 adds DPO; increment 3 adds CLINIC_SCHEDULER, BIOMEDICAL, CREW_LICENSING, CANDIDATE; increment 4 adds CLAIMS_COORDINATOR, TPA_ADJUSTER, LEGAL_COUNSEL, FINANCE, PROCUREMENT) |

  Users receive roles through groups. Conditional Access enforces MFA and compliant devices; the mobile app
  additionally requires an app protection policy.
* **ABAC attributes.** `employee_id` and `org_scope` are emitted as token claims: Entra directory extensions with
  optional claims, or Keycloak user-profile attributes. Only administrators can change them. Since increment 2,
  partner users (`PARTNER_PROVIDER`) need an `org_scope` of the form `/PARTNERS/<ORG>`; the last segment identifies
  the partner organisation that documents are shared with. Since increment 4 the same applies to TPA / insurer
  adjusters (`TPA_ADJUSTER`, for example `/PARTNERS/GULFTPA`), and both external roles require MFA: Entra ID
  Conditional Access for guest / external users with an authentication strength, or Keycloak OTP / WebAuthn; the core
  grants the roles only when the token carries `amr` / `acr` / `acrs` proof (`WZ_MFA_REQUIRED_ROLES`). Partner-hospital
  and insurer **machine clients** also carry a fixed `org_scope=/PARTNERS/<CODE>` claim (Keycloak hardcoded-claim
  mapper; Entra ID app role / optional claim or claims-mapping policy); the integration endpoints serve only their own
  organisation's referrals, pre-authorisations and reports and refuse unbound gateway clients.
* **Keycloak** remains a supported alternative IdP. The core accepts several issuers at once (`WZ_OIDC_ISSUERS`),
  so migrating between IdPs needs no downtime.

### 8.2 Workload identities

| Platform | Mechanism | Scope |
|---|---|---|
| Azure | User-assigned managed identity + federated credential `system:serviceaccount:wellnessz:wellnessz-<component>` | Key Vault Secrets User on the individual secrets of that component |
| GCP | Google service account + `roles/iam.workloadIdentityUser` binding to the KSA | `roles/secretmanager.secretAccessor` per secret |
| On-prem | Vault Kubernetes auth role per ServiceAccount | Vault policy per path |
| CI/CD | GitHub OIDC → Entra federated credential per Environment / GCP Workload Identity Federation (repository condition) | AcrPush/Artifact Registry writer, AKS RBAC Writer on the namespace / `container.developer` |

## 9. Secrets flow

![Secrets flow](../images/12-secrets-flow.png)

*Figure 6 – Secrets flow (source: `docs/images/12-secrets-flow.svg`).*

| Secret | Generated by | Stored in | Consumed as |
|---|---|---|---|
| `wz-db-password` (`wz_app`) | Terraform ephemeral random value (write-only argument) | Key Vault / Secret Manager / Vault | `WZ_DB_PASSWORD=file:/mnt/secrets/core/wz-db-password` |
| `wz-migrator-db-password` | Terraform ephemeral | as above | Flyway job (`FLYWAY_PASSWORD`) or `WZ_DB_MIGRATOR_PASSWORD=file:…` in app mode |
| `wz-bff-db-password` (`wz_bff`) | Terraform ephemeral | as above | `WZ_BFF_DB_PASSWORD=file:/mnt/secrets/bff/…` |
| `wz-master-key` | Operator: `openssl rand -base64 32` | as above | `WZ_MASTER_KEY_FILE` – decrypts `ENC(...)` values and field-level encryption |
| `wz-pseudonym-key` | Operator | as above | `WZ_PSEUDONYM_KEY=file:…` (analytics pseudonyms) |
| `wz-bff-session-key` | Operator | as above | BFF `WZ_MASTER_KEY_FILE` (session encryption, separate from core) |
| `wz-bff-client-secret`, `wz-graph-client-secret` | Entra ID app registrations | as above | `file:` references |
| Other configuration secrets | Developer / operator | Git or ConfigMap as `ENC(...)` (AES-256-GCM, `tools/encrypt-secret.sh`) | Decrypted at start-up |

Generated passwords never enter Terraform state. Kubernetes Secret sync is disabled by default, so nothing is
written to etcd. Every secret read is logged:

* Key Vault `AuditEvent`;
* Secret Manager Data Access logs;
* Vault audit device.

## 10. Data protection

| Control | Azure | GCP | Hybrid (on-prem) |
|---|---|---|---|
| TLS at the edge | TLS 1.2+ (App Gateway policy 2022-S, Front Door) | SSL policy `RESTRICTED`, TLS 1.2+ | Internal PKI, TLS 1.2+; private link |
| TLS to database | `require_secure_transport=ON`, TLS ≥ 1.2, JDBC `verify-full` | `ENCRYPTED_ONLY`, JDBC `verify-ca` with the instance CA | TLS 1.3, `verify-full` against the internal CA |
| Encryption at rest | Platform-managed AES-256; optional CMK for PostgreSQL (Key Vault RSA-HSM 3072, automatic rotation); AKS host encryption; optional disk encryption set | CMEK (Cloud KMS HSM, 90-day rotation) for Cloud SQL, GKE etcd secrets, Artifact Registry, Secret Manager | Encrypted StorageClass; backup server-side encryption |
| Application-level | Field-level AES-256-GCM (`FieldEncryptor`), encrypted BFF sessions, `ENC()` configuration | same | same |
| Backups | 35 days, geo-redundant, PITR | 30 automated backups, PITR (7-day logs) | Barman Cloud WAL + daily base, 35 days, second site |
| HA | Zone-redundant standby (synchronous) | `REGIONAL` (synchronous standby) | Synchronous replica, 3 instances |

### 10.1 Proposed RPO/RTO

These targets are proposals for GRC sign-off.

| Scenario | RPO | RTO | Mechanism |
|---|---|---|---|
| Pod / node failure | 0 | < 1 min | ReplicaSets, PDB, HPA, readiness probes |
| Zone failure | 0 | < 5 min | Zone-redundant DB failover; pods spread over 3 zones |
| Logical corruption / operator error | ≤ 5 min | < 2 h | Point-in-time restore into a new server, then cut over |
| Region / data-centre loss | ≤ 1 h (Azure geo-backup), ≤ 24 h (GCP cross-region backup) | < 4 h cloud, < 8 h on-prem | IaC re-provisioning in the paired region + restore |

Restore tests run every quarter and are recorded as SOC 2 A1.3 evidence. Each test restores into an isolated
environment and runs the smoke tests from the setup guides.

## 11. Ingress, WAF and API exposure

* **Web hosts** (`employee.*`, `manager.*`, `oh.*`, since increment 4 `partners.*`). Each host serves its SPA and the BFF paths on the same origin;
  each needs its own BFF redirect URI `https://<host>/login/oauth2/code/wellnessz`. The
  `WZSESSION` cookie is HttpOnly and Secure; CSRF protection uses the `XSRF-TOKEN` cookie and header pair.
* **API host** (`api.*`). Publishes only `/api/v1` for the mobile app and M2M integrations, including the EHS
  webhook `POST /api/v1/intake/ehs/incidents` (increment 1) and, since increment 2, the gateway endpoints
  `/api/v1/integration/lis/**`, `/api/v1/integration/hl7v2`, `/api/v1/integration/roster-feed/**` and
  `/api/v1/employees/{id}/results/device`, and since increment 3 `/api/v1/integration/absence/**`,
  `/api/v1/integration/ih-measurements`, `/api/v1/integration/cosmic-dose`, `/api/v1/integration/pre-employment` and the
  IoT fridge readings `/api/v1/integration/cold-chain/readings`, and since increment 4 the partner-system endpoints
  `/api/v1/integration/referrals/**` (FHIR), `/api/v1/integration/preauthorisations/{ref}/decision`,
  `/api/v1/integration/claims/reports/{ref}/acknowledgement` and `/api/v1/integration/telehealth/tokens/redeem`. The
  management port is never exposed through the ingress.
* **WAF rule sets:**
  * Microsoft DRS 2.1 + Bot Manager (Azure);
  * Cloud Armor CRS 3.3 preconfigured rules (GCP).

  Both run in prevention mode with request-body inspection and upload limits sized for the EHS Excel import
  (20 MB). Login paths are rate-limited per client IP.
* **TLS certificates:**
  * Key Vault certificate referenced by App Gateway (Azure);
  * Google-managed certificates (GCP);
  * cert-manager with the internal PKI (on-prem).

## 12. Observability

| Signal | Source | Destination |
|---|---|---|
| Application logs | JSON (logstash encoder) with PHI-safe masking | Container Insights / Cloud Logging → SIEM (Sentinel, Chronicle, Splunk) |
| Metrics | `/actuator/prometheus` on the management port | Azure Managed Prometheus / Google Managed Prometheus / Prometheus Operator (`serviceMonitor.enabled`) |
| Traces | OpenTelemetry OTLP (`OTEL_EXPORTER_OTLP_ENDPOINT`) | Collector → Azure Monitor / Cloud Trace / Tempo |
| Audit (application) | Hash-chained `platform.audit_event` | `GET /api/v1/audit/verify`; export to the SIEM |
| Audit (platform) | Key Vault, PostgreSQL (pgaudit), AKS (`kube-audit-admin`), App Gateway / Cloud Armor, Data Access logs | Log Analytics / Cloud Logging, 365 days |
| IdP events | Entra sign-in and audit logs; Keycloak login and admin events | SIEM |

Recommended alerts:

* WAF block spikes;
* database failover;
* Key Vault or Secret Manager access by unexpected principals;
* pod restarts or OOM;
* `/actuator/health/readiness` failures;
* audit chain verification failure;
* certificate expiry under 30 days;
* (increment 3) a scheduled job that did not run (`platform.scheduled_job_run` has no row for today's
  `absence-feed`, `surveillance-due` or `pre-employment-purge`), cold-chain excursions left unacknowledged, IoT
  gateway silence (no fridge readings for longer than the expected interval);
* (increment 4) statutory or insurer reports in status `LATE`, `claims-report-deadlines` not run in the last two hours,
  spikes of `403` / `404` for one partner or adjuster account, `TELEHEALTH_TOKEN_REDEEM` denials, WAF blocks on
  `partners.<domain>`.

## 13. Scaling and capacity

| Component | Scaling | Default bounds (Helm) | Notes |
|---|---|---|---|
| Web apps | HPA on CPU | 2–4 | Static content; scale is cheap |
| BFF | HPA on CPU and memory | 2–6 | Sessions in PostgreSQL, so pods are stateless |
| Core | HPA on CPU and memory | 2–6 | Virtual threads; the DB pool (`WZ_DB_POOL_SIZE`, default 20) × replicas must stay below the server's `max_connections`; scheduled jobs run once per occurrence regardless of the replica count (`JobRunGuard`) |
| Nodes | AKS cluster autoscaler (3–9 user nodes) / GKE Autopilot | – | Zone spread with `DoNotSchedule` in prod |
| Database | Vertical (SKU / tier) + storage auto-grow | GP D4ds_v5 / db-custom-4-16384 | Add read replicas for analytics if needed |

An airline OH workload (tens of thousands of employees, hundreds of concurrent users) fits comfortably in the
defaults. Load-test in the `test` environment before go-live.

## 14. Cost shape (indicative, not a quote)

| Driver | Azure | GCP | Hybrid |
|---|---|---|---|
| Fixed baseline | App Gateway WAF_v2 (largest fixed item), AKS Standard tier, Flexible Server with HA (≈ 2× compute), Key Vault Premium, ACR Premium, Log Analytics ingestion | Global LB + Cloud Armor (policy + per-request), Cloud SQL `REGIONAL` (≈ 2× compute), Autopilot pod resources, KMS HSM keys, Artifact Registry storage | On-prem hardware/licences (OpenShift subscriptions, Vault Enterprise optional), connectivity (ExpressRoute/Interconnect port), a smaller cloud footprint |
| Variable | Log ingestion (GB/day), egress, Defender per node | Log ingestion, Cloud Armor requests, egress | Interconnect data, object storage for backups |
| Savings levers | Reserved instances / savings plan for nodes and the DB; non-prod without HA and Front Door; log sampling | Committed use discounts; Autopilot right-sizing; non-prod `ZONAL` SQL | Reuse existing DC capacity |

Non-production environments should switch off HA, Front Door and geo-redundancy and scale down.
`terraform.tfvars` per environment controls these settings.

## 15. Environment promotion (dev / test / prod)

![Environment promotion](../images/13-environment-promotion.png)

*Figure 7 – Release and promotion flow (source: `docs/images/13-environment-promotion.svg`).*

| Environment | Purpose | Data | Approval | Infrastructure |
|---|---|---|---|---|
| dev | Integration of merged work | Synthetic | Automatic on tag | Smaller SKUs, no HA, public API server allowed with authorised IP ranges |
| test | UAT, security testing (DAST), performance | Synthetic / anonymised | Required reviewers (QA lead) | Production-like topology, HA optional |
| prod | Live service | Real PHI | Required reviewers (CAB / security), tags `v*` only | Full HA, private API server, `migrations.mode=job`, CMK optional |

* Each environment has its own subscription or project, Terraform state, Key Vault or Secret Manager and IdP app
  registrations (or redirect URIs).
* The same signed image digest is promoted through every stage. `cd.yml` verifies cosign signatures before every
  Helm upgrade and uses `--atomic` for automatic rollback.
* Production data is never copied to lower environments.

## 16. Residual risks and open decisions

| # | Item | Proposed handling |
|---|---|---|
| 1 | Infrastructure code not yet applied in a real subscription or project | First dev build is the acceptance test; record defects as change requests |
| 2 | Key material (`WZ_MASTER_KEY`, pseudonym key, BFF session key) created by an operator | Dual control, tracked in the key-ceremony record; rotation every 12 months (`WZ_FIELD_KEY_VERSION` supports re-keying) |
| 3 | Microsoft Graph mailbox uses a client secret | Prefer a certificate credential; restrict with Exchange RBAC for Applications to the single EHS mailbox |
| 4 | ingress-nginx retired (March 2026) | Not used in the cloud setups; on-prem uses the OpenShift router or an alternative controller |
| 5 | Private AKS API server requires a self-hosted runner or `az aks command invoke` | Provision runners inside the VNet before prod |
| 6 | RPO/RTO targets are proposals | GRC and business sign-off; validate in the first DR test |
| 7 | Increment 1: OH staff roster has no import path; demo data must never reach shared environments | Roster import before go-live; `WZ_FLYWAY_LOCATIONS` reviewed in each environment's values |
| 8 | Increment 2: the Helm chart has no extra-volume hook, so a PDF font or Tesseract needs a derived core image | Build `wz-core-app` plus fonts / Tesseract in the pipeline (scanned and signed like the base image), or add a `core.extraVolumes` value |
| 9 | Increment 2: gateway clients (LIS, devices, rostering, HR, hygiene) are not part of the repository | Each gateway is an `INTEGRATION` client with a certificate credential; pull model needs no core egress |
| 10 | Increment 3: no absence connector, IoT gateway or telehealth service is deployed; candidate identity source open | Push endpoints and manual entry until selected (doc 01, D15–D17); each gateway its own `INTEGRATION` client |
| 11 | Increment 3: IoT readings grow `scheduling.temperature_reading` continuously | Archive / partition policy before scaling the fridge estate; include in backup sizing |
| 12 | Increment 4: `partners.<domain>` exposes the system to users on unmanaged devices | MFA at the IdP and in the core, WAF and rate limits, organisation isolation; internal apps preferably not internet-facing (Section 20) |
| 13 | Increment 4: no insurer, authority, ERP or video-platform connector is deployed | Outbox + e-mail and manual channels until connectors exist (doc 01, D15, D18). (`WZ_PARTNER_PORTAL_URL` and the other portal URLs are now set in `values.yaml` – fixed in increment 4 after review; override them with your domain) |

## 17. Increment 1 deployment changes (E01, E02, OH workspace)

Increment 1 adds one web component, one database schema, nine roles and one inbound integration. Nothing changes in
the topology: the OH workspace is a third static SPA next to the other two, sharing the BFF on its own origin.

| Change | What to deploy / configure | Artefact |
|---|---|---|
| **OH workspace** | Helm component `ohWorkspace` (enabled by default; image `oh-workspace`, nginx-unprivileged on 8080, `/healthz`, HPA 2–4, PDB) on host `oh.<domain>` (`ingress.hosts.ohWorkspace`); same-origin routing of `/bff`, `/oauth2`, `/login`, `/logout` to the BFF; web NetworkPolicy, Service, ServiceAccount, GKE BackendConfig and ManagedCertificate are generated like for the other web apps | `deploy/helm/wellnessz/values*.yaml`, `templates/*` |
| DNS and TLS | A / CNAME record and certificate for `oh.<domain>` (Azure: App Gateway certificate; GCP: `ManagedCertificate` domain added automatically; hybrid: cloud edge) | setup guides 06–08 |
| BFF redirect URI | `https://oh.<domain>/login/oauth2/code/wellnessz` on the BFF client (the BFF builds it from `{baseUrl}`); local: `http://localhost:5175/login/oauth2/code/wellnessz` | Entra app registration / Keycloak client `wellnessz-bff` |
| CORS | `WZ_CORS_ORIGINS` adds `https://oh.<domain>` (already in `values-azure.yaml`, `values-gcp.yaml`, `values-hybrid.yaml`; local `http://localhost:5175`) | Helm values, `docker-compose.yml` |
| Schema `triage` | Created by Flyway V5 (owner `wz_migrator`, `USAGE` granted to `wz_app`); new environments get it from the bootstrap scripts; `migrations.job.core.schemas` lists it | `deploy/sql/bootstrap-managed-postgres.sql`, `deploy/hybrid/cnpg/03-cluster.yaml`, `deploy/local/postgres/01-init-wellnessz.sh` |
| Roles | `OH_PHYSICIAN`, `AME`, `CMO`, `NURSING_HEAD`, `HSE_OFFICER`, `CREW_SCHEDULING`, `HYGIENE_MANAGER`, `CLINIC_RECEPTION`, `FIRST_AIDER`: Entra app roles on `wellnessz-api` assigned to groups; Keycloak realm roles **and** client scope mappings of `wellnessz-bff` and `wellnessz-mobile` (`fullScopeAllowed=false`) | setup guide 06 §5.2, local realm |
| EHS webhook | Machine client with the `INTEGRATION` application role calls `POST https://api.<domain>/api/v1/intake/ehs/incidents` with an `Idempotency-Key`; hybrid: the on-prem internal API host | setup guides 06 §5.6, 08 |
| HRIS and OH staff master | HRIS employees pushed to `POST /api/v1/masterdata/employees`; OH staff roster (`masterdata.staff_member`) loaded by the DBA until an import exists. Demo data (`db/demo`) is loaded only by the `local` profile | doc 01, O9 |
| Configuration | `WZ_OH_WORKSPACE_URL` (link in OH notifications; `values.yaml` sets `https://oh.wellnessz.example.com` – override with `https://oh.<domain>`), `WZ_OH_CONTACT`, `WZ_SLA_POLL_INTERVAL` (default `PT60S`), optionally `WZ_FLYWAY_LOCATIONS` (never add `classpath:db/demo` outside local) | core `env` / `extraEnv` |
| Outbound allow-list | `catering-hygiene` destination (status fields only) for food-handler cases | `application.yml` |
| Pipeline | `oh-workspace` in the CI web matrix, the `container.yml` image matrix, the `cosign verify` loop of `deploy-env.yml` and Dependabot | CI/CD Guide |

**Verification per environment** (details in each setup guide):

| Setup | Verification |
|---|---|
| Local | `tools/e2e/e2e-increment1.js` – 18 checks, all `PASS` |
| Azure / GCP | `curl -sI https://oh.<domain>/` → `200` with HSTS; OH login as a Nursing Head test account; webhook smoke call returns `202`; `GET /api/v1/modules` shows E01 and E02 `IMPLEMENTED`; the E2E script with `WZ_E2E_*` pointing at the environment (dev/test only) |
| Hybrid | As above from the cloud edge; additionally confirm that `triage` exists only in the on-prem database and that the cloud session database still holds only `bff` |

## 18. Increment 2 deployment changes (E03, E05, document service)

Increment 2 adds no image, Helm component or host. The modules ship inside the core image. Everything else is
configuration, identity and database bootstrap.

| Change | What to deploy / configure | Artefact |
|---|---|---|
| Schemas `docs`, `ohrecord`, `fitness` | Created by Flyway V6–V8 (owner `wz_migrator`; `USAGE` granted to `wz_app` by the migrations); new environments get them from the bootstrap scripts; `migrations.job.core.schemas` = `platform,masterdata,intake,casemgmt,triage,docs,ohrecord,fitness,analytics`; `spring.flyway.schemas` lists the same set | `deploy/sql/bootstrap-managed-postgres.sql`, `deploy/hybrid/cnpg/03-cluster.yaml`, `deploy/local/postgres/01-init-wellnessz.sh`, `deploy/helm/wellnessz/values.yaml` |
| Search path | `wz_app` and `wz_migrator`: `platform, masterdata, intake, casemgmt, triage, docs, ohrecord, fitness, analytics` (re-run the idempotent bootstrap on existing environments; increment 3 extends the list – Section 19) | same files |
| Demo data | `R__demo_e03_record.sql` and `R__demo_e05_fitness.sql` in `db/demo` – loaded only by the `local` / `test` profiles; never set `WZ_FLYWAY_LOCATIONS` to include `classpath:db/demo` elsewhere | `apps/wz-core-app/src/main/resources/db/demo` |
| Role `DPO` | Entra app role `DPO` on `wellnessz-api` assigned to a DPO group; Keycloak realm role and scope mappings of `wellnessz-bff` and `wellnessz-mobile` | setup guide 06 §5.1–5.2, local realm |
| Partner users | `PARTNER_PROVIDER` with `org_scope=/PARTNERS/<ORG>` (Entra: directory extension or claims mapping for B2B guests) | setup guide 06 §5.3 |
| Notification settings | `WZ_EMPLOYEE_PORTAL_URL` (`https://employee.<domain>`), `WZ_MANAGER_WORKSPACE_URL` (`https://manager.<domain>`) – both set in `values.yaml` to the `*.wellnessz.example.com` hosts, override with your domain; `WZ_DPO_EMAIL` (DPO mailbox for break-glass alerts) | core `env` |
| Document settings | `WZ_DOCUMENTS_FONT_PATH` (optional Unicode font, for example Noto Sans for Arabic), `WZ_DOCUMENTS_RETENTION_CRON` (default `0 30 2 * * *` UTC), `WZ_DIAGNOSIS_REQUIRED_ON_CLOSE` (default `true`) | core `env` |
| Optional OCR | Off by default. Tesseract: derived core image with `tesseract-ocr` and language packs, then `WELLNESSZ_DOCUMENTS_OCR_TESSERACTPATH=/usr/bin/tesseract` and `WELLNESSZ_DOCUMENTS_OCR_LANGUAGES=eng+ara+fra` (allow-listed values only) in `core.extraEnv`. Cloud OCR (Azure AI Document Intelligence, Google Document AI) is an `OcrProvider` extension point: it needs code, a private endpoint / VPC-SC, a `networkPolicy.extraEgress.core` rule and a DPIA | doc 01 §14.18 |
| Read-only root filesystem | Fonts and Tesseract must be in the image (or a mounted volume); OCR writes only its temporary file to the `/tmp` emptyDir | `deployment-java.yaml` |
| Gateway connectivity | Gateways call the **API host** with client-credentials tokens (`INTEGRATION` role): LIS pull queue `GET /api/v1/integration/lis/orders` + `POST …/{id}/ack`, results `POST /api/v1/integration/lis/results` or HL7 v2 ORU via `POST /api/v1/integration/hl7v2`; device middleware `POST /api/v1/employees/{id}/results/device`; crew rostering `GET /api/v1/integration/roster-feed` + `POST …/{messageId}/ack`. The core opens **no** new outbound connections; MLLP stays on the integration engine | Entra M2M apps (guide 06 §5.6), WAF |
| Outbound allow-lists | `crew-rostering`, `hr-systems`, `catering-hygiene` extended (status, dates, limitation codes, job-offer outcome, food-handler status) | `application.yml` |
| BFF | Passes `/bff/api/workspace/{team-restrictions,duty-proposals,rtw-plans,documents}` to the core unchanged; no configuration | `apps/wz-bff` |
| Pipeline | No new image or matrix entry; PDFBox is scanned by dependency-check, Dependabot and the image Trivy scan | CI/CD Guide |

Gateway connectivity per environment:

| Setup | Laboratory / device / rostering gateways |
|---|---|
| Local | No gateways; the integration client `wellnessz-integration` (secret `change-me-local-only`) plays every gateway; `e2e-increment2.js` drives the roster feed |
| Azure | Gateways reach `https://api.<domain>` through App Gateway WAF (public or private listener); on-prem lab systems via ExpressRoute / VPN to the private listener where possible |
| GCP | Gateways reach `https://api.<domain>` through Cloud Armor; Cloud Armor rules must allow HL7 v2 ER7 bodies (`text/plain`) on `/api/v1/integration/hl7v2` |
| Hybrid | Gateways on the corporate network call the **on-prem** internal API host directly (separate firewall rules per gateway, like the EHS webhook); nothing transits the cloud edge |

**Verification per environment** (details in each setup guide):

| Setup | Verification |
|---|---|
| Local | `tools/e2e/e2e-increment2.js` – 25 checks, all `PASS` (after `e2e-increment1.js` 18/18) |
| Azure / GCP | `\dn` lists `docs`, `ohrecord`, `fitness`; `GET /api/v1/modules` shows E03 and E05 `IMPLEMENTED`; a DPO test account sees *Break-glass reviews* but gets `403`/`404` on a chart; LIS pull-queue smoke call with an `INTEGRATION` token returns `200`; the E2E script with `WZ_E2E_*` pointing at the environment (dev/test only) |
| Hybrid | As above from the cloud edge; additionally confirm that the three new schemas exist only in the on-prem database and that gateway calls reach the on-prem API host |

## 19. Increment 3 deployment changes (E06, E07, E14)

Increment 3 adds no image, Helm component or host. The three modules ship inside the core image; the new screens ship
inside the three existing web images. Everything else is database bootstrap, identity, configuration, jobs and
gateway connectivity.

| Change | What to deploy / configure | Artefact |
|---|---|---|
| Schemas `absence`, `surveillance`, `scheduling` | Created by Flyway V10–V12 (V9 adds platform rows: clinic sites, hazards, job codes, retention classes); owner `wz_migrator`, `USAGE` and table grants for `wz_app` from the migrations; new environments get the schemas from the bootstrap scripts; `migrations.job.core.schemas` = `platform,masterdata,intake,casemgmt,triage,docs,ohrecord,fitness,absence,surveillance,scheduling,analytics`; `spring.flyway.schemas` lists the same set | `deploy/sql/bootstrap-managed-postgres.sql`, `deploy/hybrid/cnpg/03-cluster.yaml`, `deploy/local/postgres/01-init-wellnessz.sh`, `deploy/helm/wellnessz/values.yaml` |
| Search path | `wz_app` and `wz_migrator`: `platform, masterdata, intake, casemgmt, triage, docs, ohrecord, fitness, absence, surveillance, scheduling, analytics` (re-run the idempotent bootstrap on existing environments; on CNPG apply the updated `03-cluster.yaml` post-init SQL by hand on an existing cluster; increment 4 extends the list – Section 20) | same files |
| Demo data | `R__demo_e06_absence.sql`, `R__demo_e07_surveillance.sql`, `R__demo_e14_scheduling.sql` and employees `E1006`–`E1010` – `local` / `test` profiles only; never add `classpath:db/demo` to `WZ_FLYWAY_LOCATIONS` elsewhere | `apps/wz-core-app/src/main/resources/db/demo` |
| Roles | `CLINIC_SCHEDULER`, `BIOMEDICAL`, `CREW_LICENSING`, `CANDIDATE`: Entra app roles on `wellnessz-api` assigned to groups; Keycloak realm roles **and** scope mappings of `wellnessz-bff` and `wellnessz-mobile` | setup guide 06 §5.1–5.2, local realm |
| Candidates | Candidate accounts (role `CANDIDATE`) with `employee_id` = candidate id (`C…`) and `org_scope=/CANDIDATES`; a separate identity source (B2C / External ID tenant or a candidate realm), never the workforce directory; accounts removed when the offer closes | doc 01, D17; setup guide 06 §5.3 |
| Business configuration (E18) | Set per environment through `PUT /api/v1/masterdata/config/{key}` (audited): `absence.hr-email`, `notifications.crew-licensing.email`, `notifications.hr-onboarding.email`, `notifications.biomedical.email`, `scheduling.telehealth-base-url` (an approved video service), `absence.public-holidays` (per country), optionally SLA days and self-booking rules | doc 01 §21.4 |
| Jobs | Nine jobs inside the core (doc 01 §21.3), all UTC, once per run across replicas via `platform.scheduled_job_run`; schedules can be overridden with the `wellnessz.absence.*-cron`, `wellnessz.surveillance.*-job-cron`, `wellnessz.scheduling.*-cron` properties (relaxed binding in `core.extraEnv`, e.g. `WELLNESSZ_ABSENCE_FEEDCRON`) | `AbsenceJobs`, `SurveillanceJobs`, `SchedulingJobs` |
| Optional strict device register | `WELLNESSZ_RECORD_REQUIREREGISTEREDDEVICES=true` in `core.extraEnv` once every result-producing device is in the E14 register | doc 02 §13.7 |
| Outbound allow-list | `hr-systems` extended by `offerRef`, `candidateId`, `preEmploymentStatus`, `ohEngaged`, `certificateValidity` | `application.yml` |
| BFF | Ships with `spring.servlet.multipart.enabled=false` (uploads streamed untouched to the core) and passes `/bff/api/workspace/{absence,advice-requests,surveillance,pre-employment}` unchanged; no configuration – do not override the multipart setting | `apps/wz-bff` |
| Upload limits at the edge | Certificate photos, candidate documents and calibration certificates are ≤ 10 MB; the WAF request-body limit (sized for the 20 MB Excel import) already covers them | WAF policy |
| Pipeline | No new image or matrix entry; no new third-party dependency; `e2e-increment3.js` joins the pre-release gate | CI/CD Guide |

**Integration endpoints (new gateways).** All are machine endpoints on the **API host**, called with client-credentials
tokens carrying the `INTEGRATION` role; the core opens no outbound connection for them:

| Gateway | Endpoint | Volume / sizing note |
|---|---|---|
| HRIS / time & attendance / crew management (absence push) | `POST /api/v1/integration/absence/episodes`, `POST /api/v1/integration/absence/certificates` | Batches ≤ 5,000 rows; daily delta is typically a few hundred rows per 10,000 employees |
| Absence pull (site adapters) | Outbound from the core through an `AbsenceFeedConnector` bean (none shipped) | Needs a `networkPolicy.extraEgress.core` rule per source when an adapter is added |
| Industrial hygiene / dosimetry | `POST /api/v1/integration/ih-measurements`, `POST /api/v1/integration/cosmic-dose` | Periodic batches |
| Recruiting / applicant tracking | `POST /api/v1/integration/pre-employment` | One call per offer |
| **IoT fridge gateway** | `POST /api/v1/integration/cold-chain/readings` | ≤ 5,000 readings per call; at one reading per fridge every 5 minutes, 50 fridges produce ~14,400 rows per day (~5 million per year) in `scheduling.temperature_reading` – plan storage and an archive / partitioning policy before scaling the fridge estate |

**Sizing notes.** The new jobs are light (set-based queries over the employee population, quarter-hourly reminders and
no-shows); campaign booking of 500 people in one call is covered by the integration tests. The main new growth drivers
are `scheduling.temperature_reading` (IoT), `scheduling.appointment` (one row per visit) and `absence.episode` (feed);
the default database tiers in Section 13 still apply. Calibration certificates are stored as `bytea` in
`scheduling.calibration` (vendor PDFs, typically < 1 MB each) and count towards backup size.

Gateway connectivity per environment:

| Setup | Absence feeds, IH / dosimetry, recruiting, IoT gateway |
|---|---|
| Local | No gateways; the integration client `wellnessz-integration` plays all of them; `e2e-increment3.js` posts absence episodes, IH measurements and fridge readings |
| Azure | Gateways reach `https://api.<domain>` through App Gateway WAF; IoT gateways on site networks via ExpressRoute / VPN to the private listener where possible |
| GCP | Gateways reach `https://api.<domain>` through Cloud Armor; allow JSON bodies up to the batch cap on `/api/v1/integration/**` |
| Hybrid | Gateways on the corporate network call the **on-prem** internal API host directly (firewall rule per gateway, like the EHS webhook); fridge readings never transit the cloud edge |

**Verification per environment** (details in each setup guide):

| Setup | Verification |
|---|---|
| Local | `tools/e2e/e2e-increment3.js` – 32 checks, all `PASS` (after `e2e-increment1.js` 18/18 and `e2e-increment2.js` 25/25) |
| Azure / GCP | `\dn` lists `absence`, `surveillance`, `scheduling`; `GET /api/v1/modules` shows E06, E07 and E14 `IMPLEMENTED`; a scheduler test account opens the diary but gets `403` on `/api/v1/absence/queue`; a cold-chain smoke call with an `INTEGRATION` token returns `200` with `unknownDevices` listed; an upload through the BFF (employee certificate) succeeds; the E2E script with `WZ_E2E_*` pointing at the environment (dev/test only) |
| Hybrid | As above from the cloud edge; additionally confirm that the three new schemas exist only in the on-prem database and that gateway calls reach the on-prem API host |

## 20. Increment 4 deployment changes (E04, E08, partner portal)

Increment 4 adds **one new image and Helm component** – the partner portal – and the first host intended for users
outside the airline. The two modules ship inside the core image; the new OH, employee and HR screens ship inside the
existing web images. Everything else is database bootstrap, identity (including MFA for external users),
configuration, jobs and gateway connectivity.

| Change | What to deploy / configure | Artefact |
|---|---|---|
| **Partner portal** | Helm component `partnerPortal` (enabled by default; image `partner-portal`, nginx-unprivileged on 8080, uid 101, `/healthz`, HPA 2–4, PDB `minAvailable: 1`, read-only root filesystem with a `/tmp` emptyDir) on host `partners.<domain>` (`ingress.hosts.partnerPortal`); same-origin routing of `/bff`, `/oauth2`, `/login`, `/logout` to the BFF; web NetworkPolicy, Service, ServiceAccount, HPA, PDB, GKE BackendConfig and ManagedCertificate domain are rendered like for the other web apps; listed in `NOTES.txt`. `values-local.yaml`: `partners.localtest.me`, one replica; `values-azure.yaml` / `values-gcp.yaml`: `partners.wellnessz.example.com`; `values-hybrid.yaml` (on-prem core): disabled – the portal runs on the **cloud edge** (`values-hybrid-cloud-edge.yaml` inherits it from `values.yaml`). `helm lint`, `helm template` and kubeconform are clean for all values files | `deploy/helm/wellnessz/values*.yaml`, `templates/*` |
| DNS and TLS | Public A / CNAME record and certificate for `partners.<domain>` (Azure: App Gateway / Front Door certificate from Key Vault; GCP: `ManagedCertificate` domain added automatically; hybrid: cloud edge) | setup guides 06–08 |
| BFF redirect URI | `https://partners.<domain>/login/oauth2/code/wellnessz`, post-logout `https://partners.<domain>/`; local `http://localhost:5176/…` (already in the realm) | Entra app registration / Keycloak client `wellnessz-bff` |
| CORS | Not needed for the portal (same-origin BFF). Local profile and Docker Compose list `http://localhost:5176` for completeness; cloud `WZ_CORS_ORIGINS` stays limited to native-app origins | `application.yml`, `docker-compose.yml` |
| **MFA for external users** | `PARTNER_PROVIDER` / `TPA_ADJUSTER` accounts must sign in with MFA at the IdP **and** the token must carry `amr` / `acr` / `acrs` proof; the core drops both roles otherwise. Entra ID: B2B guests with a Conditional Access policy (*guest or external users*, authentication strength); Keycloak: OTP / WebAuthn required action and an AMR mapper or level-of-authentication flow. `WZ_MFA_REQUIRED_ROLES` stays at its default (`PARTNER_PROVIDER,TPA_ADJUSTER`) in every shared environment – never set it empty outside `local` | doc 01 §14.30; setup guides 06 §5.3, 07, 08 |
| Schemas `treatment`, `claims` | Created by Flyway V14 / V15 (V13 adds platform rows: 25 partner organisations, retention class `CLAIM_RECORD`); new environments get them from the bootstrap scripts; `migrations.job.core.schemas` = `platform,masterdata,intake,casemgmt,triage,docs,ohrecord,fitness,absence,surveillance,scheduling,treatment,claims,analytics`; `spring.flyway.schemas` lists the same set | `deploy/sql/bootstrap-managed-postgres.sql`, `deploy/hybrid/cnpg/03-cluster.yaml`, `deploy/local/postgres/01-init-wellnessz.sh`, `deploy/helm/wellnessz/values.yaml` |
| Search path | `wz_app` and `wz_migrator`: `platform, masterdata, intake, casemgmt, triage, docs, ohrecord, fitness, absence, surveillance, scheduling, treatment, claims, analytics` (re-run the idempotent bootstrap on existing environments; on CNPG apply the post-init SQL by hand on an existing cluster) | same files |
| Demo data | `R__demo_e04_treatment.sql`, `R__demo_e08_claims.sql` and the `ClaimsDemoSeeder` bean – `local` / `test` profiles only. The partner network in V13 / V14 is **illustrative** and is replaced by procurement's contracted network through `POST /api/v1/masterdata/partner-organisations` and `/api/v1/providers/{code}/contracts` | `db/demo`, doc 01 O27 |
| Roles | `CLAIMS_COORDINATOR`, `TPA_ADJUSTER`, `LEGAL_COUNSEL`, `FINANCE`, `PROCUREMENT`: Entra app roles on `wellnessz-api` assigned to groups (adjusters and partners as guests); Keycloak realm roles **and** scope mappings of `wellnessz-bff` and `wellnessz-mobile` | setup guide 06 §5.1–5.3, local realm |
| Partner and adjuster accounts | Named users only, `org_scope=/PARTNERS/<CODE>` where `<CODE>` is the partner organisation code in master data (for example `/PARTNERS/GULFTPA` for the TPA); set only after the data-sharing agreement is signed; removed at contract end | setup guide 06 §5.3 |
| Notification settings | `WZ_PARTNER_PORTAL_URL` = `https://partners.<domain>` (links in partner / adjuster notifications). Set in `values.yaml` to `https://partners.wellnessz.example.com` (in line with `ingress.hosts.partnerPortal`) and in `values-local.yaml` to `http://partners.localtest.me` (which also adds the host to `WZ_CORS_ORIGINS`) – override it with your domain | core `env` |
| Partner-system clients | FHIR partner and insurer / TPA systems: one `INTEGRATION` client per organisation with a certificate credential and a **fixed `org_scope=/PARTNERS/<CODE>` claim** (Keycloak hardcoded-claim mapper; Entra ID app role / optional claim or claims-mapping policy). The API serves a client only its own organisation's referrals (provider), pre-authorisations (payer) and first reports (recipient); others answer `404`, unbound clients are refused | setup guide 06 §5.6; doc 02 §14.2 |
| Business configuration (E18) | Set per environment through `PUT /api/v1/masterdata/config/{key}`: `claims.mailbox-email`, `claims.legal-email`, `claims.employer-ref`, `claims.employer-name`, `claims.fx-rates`, `treatment.telehealth.base-url` (approved video service), `treatment.network.cities`, optionally SLA / tolerance keys | doc 01 §22.4 |
| Jobs | Four jobs inside the core (`treatment-variance`, `referral-chase`, `provider-scorecards`, hourly `claims-report-deadlines`), UTC, once per run across replicas; overridable with `WELLNESSZ_TREATMENT_VARIANCEJOBCRON`, `WELLNESSZ_TREATMENT_CHASEJOBCRON`, `WELLNESSZ_TREATMENT_SCORECARDJOBCRON`, `WELLNESSZ_CLAIMS_REPORTDEADLINESCRON` in `core.extraEnv` | doc 01 §22.3 |
| Outbound destinations | `insurer-gateway`, `authority-gateway`, `erp-finance` – reference-only allow-lists; events go to the outbox and today to the logging publisher; documents never travel in events | `application.yml`, doc 01 §14.29 |
| BFF | Passes `/bff/api/workspace/claims/**` unchanged (HR claim status); no configuration | `apps/wz-bff` |
| Pipeline | `partner-portal` in the CI web matrix, the `container.yml` image matrix (Trivy, SBOM, provenance, cosign), the `cosign verify` loop of `deploy-env.yml` and Dependabot (npm and Docker); no new Java dependency | CI/CD Guide |

**External exposure: `partners.<domain>` versus the internal apps.** Until increment 3 every browser user was an
employee; increment 4 adds users on devices the airline does not manage. Recommended exposure per host:

| Host | Audience | Exposure (recommended) |
|---|---|---|
| `partners.<domain>` | Partner providers, TPA / insurer adjusters | **Internet**, behind the WAF with OWASP rules, bot protection and rate limits on `/oauth2/` and `/login/`; MFA enforced at the IdP and verified by the core; no IP allow-list (partner networks change), optional geo restriction to the countries of the partner network |
| `employee.<domain>` | Employees and crew (also on layover) | Internet behind the WAF (crew work worldwide); Conditional Access for workforce identities |
| `manager.<domain>`, `oh.<domain>` | Managers / HR; OH team, claims, finance, legal, procurement | Preferably **corporate network only** (App Gateway private listener / Front Door with a WAF IP allow-list / Cloud Armor allow-list or IAP / internal ingress), plus Conditional Access with compliant devices |
| `api.<domain>` | Mobile app, gateways, partner hospital and insurer systems (`/api/v1/integration/**`) | Internet behind the WAF; machine clients with certificate credentials; consider separate WAF policies for user and machine paths |

All browser hosts share one BFF deployment and one BFF client registration, so `/bff`, `/oauth2` and `/login` on
`partners.<domain>` reach the same BFF pods as on the internal hosts. Per-host WAF rules are therefore written on the
**host name**: Azure per-listener WAF policies or custom rules matching the `Host` header (Front Door: security policy
per domain); GCP Cloud Armor rules with CEL expressions such as `request.headers['host'] == 'oh.<domain>' &&
!inIpRange(origin.ip, '<corporate-range>')`. The chart renders one BackendConfig per component, but all of them use
the single `gcp.backendConfig.securityPolicy`; a separate Cloud Armor policy for `partnerPortal` would need a
per-component value in `templates/gke.yaml` (not available today).

**Outbound gateways.** In this increment the core still opens **no** new outbound connection: insurer, authority and
ERP messages are reference-only outbox events delivered by the configured `EventPublisher` (logging today), and
insurers receive a content-free e-mail through the existing SMTP relay. Insurer systems, partner hospitals and the
video platform call **in** on the API host (`/api/v1/integration/preauthorisations/**`, `/claims/reports/**`,
`/referrals/**`, `/telehealth/tokens/redeem`). When a connector (`ClaimsSubmissionGateway` bean, ERP adapter, video
platform adapter) is added:

| Setup | Outbound path for a future connector |
|---|---|
| Azure | Core egress on 443 to public endpoints is already allowed by the NetworkPolicy (RFC 1918 excluded); route through Azure Firewall / NAT Gateway with an FQDN allow-list; private ERP endpoints over private endpoint / ExpressRoute need a `networkPolicy.extraEgress.core` rule |
| GCP | Cloud NAT with a firewall / Secure Web Proxy FQDN allow-list; private ERP via Private Service Connect or Interconnect plus `networkPolicy.extraEgress.core` |
| Hybrid | The on-prem core is the only component that may call insurers, authorities and the ERP: one firewall rule per destination through the corporate proxy; nothing is sent from the cloud edge |
| Local | None; events appear in the outbox (`GET /api/v1/integration/events`) |

**Sizing notes.** Growth drivers are `treatment.referral` / `referral_history`, `treatment.telehealth_message`,
`claims.cost_entry` and the documents of provider reports and invoices (≤ 10 MB each, in `docs`). Hourly
`claims-report-deadlines` and the daily treatment jobs are set-based and light. The default database tiers of
Section 13 still apply; the partner portal adds two small nginx pods (50m CPU / 64 Mi requests).

Partner portal and gateways per environment:

| Setup | Partner portal | Partner / insurer / video-platform systems |
|---|---|---|
| Local | Vite dev server on `http://localhost:5176` (or `partners.localtest.me` with the chart); MFA gate off in the `local` profile | The integration client `wellnessz-integration` plays all of them; `e2e-increment4.js` drives FHIR and insurer calls |
| Azure | `partners.<domain>` on App Gateway WAF v2 (and Front Door if used) with its own listener and certificate; Entra B2B guests with Conditional Access | Call `https://api.<domain>` through the WAF with `INTEGRATION` client credentials (certificate credential) |
| GCP | `partners.<domain>` on the global external Application Load Balancer with Cloud Armor and a Google-managed certificate | Call `https://api.<domain>` through Cloud Armor |
| Hybrid | `partners.<domain>` on the **cloud edge** next to the other SPAs and the BFF; partner data stays on-prem (BFF → on-prem API over the private link) | Call the on-prem internal API host through a published, WAF-protected path or an API gateway in the DMZ (partner systems are outside the corporate network); one firewall rule per partner system |

**Verification per environment** (details in each setup guide):

| Setup | Verification |
|---|---|
| Local | `tools/e2e/e2e-increment4.js` – 22 checks, all `PASS` (after `e2e-increment1.js` 18/18, `e2e-increment2.js` 25/25 and `e2e-increment3.js` 32/32 on a freshly purged database) |
| Azure / GCP | `curl -sI https://partners.<domain>/` → `200` with HSTS and CSP; `\dn` lists `treatment`, `claims`; `GET /api/v1/modules` shows E04 and E08 `IMPLEMENTED`; a partner test account **with** MFA sees its referral inbox, the same account signed in without MFA (or a token without `amr` / `acr`) gets the *multi-factor sign-in required* page and `403` on `/bff/api/partner/referrals`; an adjuster sees only its payer's claims; `GET /api/v1/integration/referrals/{id}/fhir` with an `INTEGRATION` token returns a `ServiceRequest`; the E2E script with `WZ_E2E_*` pointing at the environment (dev/test only, needs MFA-capable test accounts and an adapted login step) |
| Hybrid | As above from the cloud edge; additionally confirm that `treatment` and `claims` exist only in the on-prem database, that the partner portal pods run only on the cloud edge, and that partner system calls reach the on-prem API host |
