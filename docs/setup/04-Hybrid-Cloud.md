# WellnessZ OHCM – Setup Guide 4: Hybrid Cloud

| | |
|---|---|
| **Document** | 08 – Setup guide: Hybrid cloud (on-prem core + CloudNativePG, cloud web tier) |
| **Audience** | Infrastructure / platform team, network team, security engineering |
| **Result** | PHI at rest on-premises (RKE2 or OpenShift), web tier and BFF in AKS or GKE, one fleet under Azure Arc or GKE Enterprise |
| **Time** | 2–3 days for the first environment (connectivity and PKI usually dominate) |
| **Related** | 04 – Deployment Architecture · `deploy/hybrid/README.md` · 06 – Azure / 07 – GCP guides |

![Hybrid topology](../images/11-deploy-hybrid.png)

*Figure 1 – Hybrid topology (source: `docs/images/11-deploy-hybrid.svg`).*

> **Validation status.** All manifests in `deploy/hybrid` validate against their published schemas (kubeconform
> with the CRD catalogue: CloudNativePG `Cluster`/`ScheduledBackup`, Barman Cloud `ObjectStore`, External Secrets
> v1, cert-manager). The CNPG grants SQL was executed on PostgreSQL 16.15. `values-hybrid.yaml` and
> `values-hybrid-cloud-edge.yaml` render and lint. **Nothing was installed on a real RKE2/OpenShift cluster or
> connected through Arc or GKE Enterprise.**
>
> **Increment 1** (E01 intake, E02 triage) adds the OH workspace to the cloud web tier, schema `triage` to the
> on-prem database and the EHS webhook on the on-prem API. The changes are listed in sections 8–10.
>
> **Increment 2** (E03 OH record, E05 fitness for duty) adds schemas `docs`, `ohrecord`, `fitness` to the on-prem
> database, the `DPO` role, and gateway connectivity from the laboratory, device middleware and crew rostering to the
> on-prem API. All clinical data, documents and the search index stay on-premises. Changes are in sections 4, 8 and 10.
>
> **Increment 3** (E06 absence & sickness, E07 medical surveillance, E14 scheduling & clinic operations) adds schemas
> `absence`, `surveillance`, `scheduling` to the on-prem database, four roles, nine scheduled jobs in the on-prem core
> and gateway connectivity from HRIS / time & attendance / crew systems, industrial hygiene, dosimetry, recruiting and
> IoT fridge gateways to the on-prem API. Absence episodes, certificates, surveillance results and cold-chain readings
> stay on-premises. Changes are in sections 4, 8, 10 and 12.
>
> **Increment 4** (E04 treatment coordination & partner referrals, E08 claims coordination) adds the **partner portal**
> to the cloud web tier (`partners.<domain>`, the first host for users outside the airline, MFA required), schemas
> `treatment` and `claims` to the on-prem database, five roles, four scheduled jobs in the on-prem core and inbound
> connectivity from partner hospital systems, insurer systems and (later) the video platform to the on-prem API.
> Referrals, provider reports and invoices, claims, costs and medico-legal reports stay on-premises; the edge holds
> no partner data. Changes are in sections 1, 4, 8, 9, 10 and 12.

## 1. Target state

| Location | Runs | Values / manifests |
|---|---|---|
| On-premises cluster (RKE2 ≥ 1.31 or OpenShift ≥ 4.18) | `wz-core-app`, Flyway Job, CloudNativePG cluster `wellnessz-db`, Vault CSI provider, External Secrets, cert-manager | `deploy/helm/wellnessz/values-hybrid.yaml`, `deploy/hybrid/cnpg/*`, `deploy/hybrid/external-secrets/*` |
| Cloud cluster (AKS from guide 06, or GKE from guide 07) | `employee-portal`, `manager-workspace`, `oh-workspace` (increment 1), `partner-portal` (increment 4), `wz-bff`; small managed PostgreSQL for BFF sessions | `values-azure.yaml` (or `values-gcp.yaml`) + `deploy/hybrid/values-hybrid-cloud-edge.yaml` |
| Between them | ExpressRoute / Interconnect / IPsec VPN; only TCP 443 from the BFF egress range to the on-prem API ingress | Firewall change request |

## 2. Prerequisites

| Item | Version / requirement | Notes |
|---|---|---|
| On-prem Kubernetes | RKE2 1.31+ with the CIS profile, or OpenShift 4.18+ | 3+ worker nodes across failure domains; CNI with NetworkPolicy (Cilium, Calico, OVN-Kubernetes) |
| Storage | CSI StorageClass `encrypted-ssd` with encryption at rest (LUKS, vSAN encryption or array encryption) and volume expansion | Used by CNPG for data and WAL |
| Object storage | S3-compatible (MinIO, StorageGRID, ECS) with SSE, versioning and object lock; replication to a second site | Backups |
| Internal PKI | ClusterIssuer `internal-pki` in cert-manager (Vault PKI or AD CS via an issuer) | Database TLS, API ingress TLS |
| HashiCorp Vault | 1.18+ with KV v2 at `kv/`, an audit device, and HA | Or Azure Key Vault via Arc |
| Operators | cert-manager 1.18+, CloudNativePG 1.30.x + plugin-barman-cloud 0.15.x, External Secrets Operator 0.19+ (API `v1`), Vault CSI provider + Secrets Store CSI driver | Helm charts from the vendors |
| Image registry mirror | Harbor or Artifactory replicating from ACR / Artifact Registry | Signatures replicated (OCI referrers) |
| Tools | kubectl, helm, `oc` (OpenShift), vault CLI, `kubectl cnpg` plugin, az or gcloud | |

## 3. Fleet management

### 3.1 Option A – Azure Arc

```bash
az extension add --name connectedk8s && az extension add --name k8s-extension
```

```bash
az connectedk8s connect -n wz-onprem-prod -g rg-wz-hybrid -l westeurope --enable-oidc-issuer --enable-workload-identity
```

```bash
az k8s-extension create -n azure-defender --cluster-type connectedClusters -c wz-onprem-prod -g rg-wz-hybrid --extension-type Microsoft.AzureDefender.Kubernetes
```

```bash
az k8s-extension create -n azurepolicy --cluster-type connectedClusters -c wz-onprem-prod -g rg-wz-hybrid --extension-type Microsoft.PolicyInsights
```

```bash
az k8s-extension create -n azuremonitor-containers --cluster-type connectedClusters -c wz-onprem-prod -g rg-wz-hybrid --extension-type Microsoft.AzureMonitor.Containers
```

Optional: install the Key Vault secrets provider in place of Vault, then use `secrets.provider=azure` and
`cluster-secret-store-azure-arc.yaml`:

```bash
az k8s-extension create -n akvsecretsprovider --cluster-type connectedClusters -c wz-onprem-prod -g rg-wz-hybrid --extension-type Microsoft.Azure.KeyVault.SecretsProvider --configuration-settings secrets-store-csi-driver.enableSecretRotation=true
```

Verify:

```bash
az connectedk8s show -n wz-onprem-prod -g rg-wz-hybrid --query connectivityStatus -o tsv
```

Expected: `Connected`. Assign the same Azure Policy initiative as AKS (Kubernetes cluster pod security restricted
standards, allowed registries).

### 3.2 Option B – GKE Enterprise (attached cluster)

```bash
gcloud container fleet memberships register wz-onprem-prod --context=<kube-context> --kubeconfig=$HOME/.kube/config --enable-workload-identity --has-private-issuer
```

Then enable Config Sync (GitOps) and Policy Controller with the `pss-restricted` and `cis-k8s` bundles in the
fleet:

```bash
gcloud container fleet policycontroller enable --memberships=wz-onprem-prod
```

## 4. Connectivity

1. **Link.** Use one of:
   * ExpressRoute private peering into the AKS hub VNet;
   * Dedicated/Partner Interconnect with Cloud Router;
   * HA IPsec VPN (IKEv2, AES-256-GCM, SHA-384, DH group 20+, PFS).
2. **Routing.** Advertise only these ranges:
   * from on-prem: the API ingress VIP;
   * from the cloud: the AKS node/egress range (Azure CNI Overlay SNATs pods to node IPs) or the GKE subnet.
3. **Firewall.** Allow `cloud BFF egress range → on-prem API ingress VIP : 443/TCP` only. Deny everything else and
   log denies. The EHS webhook (increment 1) is called by the EHS system on the corporate network: allow
   `EHS system → on-prem API ingress VIP : 443/TCP` as a separate, documented rule rather than routing it through
   the cloud edge (`ingress.api.enabled` is `false` there). Increment 2 adds the same kind of rule for each gateway
   (laboratory / LIS integration engine, device middleware, crew-rostering gateway → on-prem API ingress VIP :
   443/TCP). All three **pull** from or post to the API; the core opens no connections towards them. Increment 3 adds
   one rule per new gateway → on-prem API ingress VIP : 443/TCP: absence feeds (HRIS, time & attendance, crew
   management), industrial hygiene / dosimetry, recruiting and the **IoT fridge gateway** on the site networks (clinic
   VLANs). If an `AbsenceFeedConnector` is later added to pull from a source system, that is the first outbound
   connection of the core: add a `networkPolicy.extraEgress.core` entry and a firewall rule for exactly that system.
   Increment 4: partner portal users arrive at the **cloud edge** like every other browser user, so no new user-facing
   rule is needed on the link (the edge BFF already reaches the on-prem API). **Partner hospital systems (FHIR) and
   insurer systems are outside the corporate network**: publish only `/api/v1/integration/referrals/**`,
   `/integration/preauthorisations/**`, `/integration/claims/reports/**` and `/integration/telehealth/tokens/redeem` of
   the on-prem API through the DMZ reverse proxy / API gateway with a WAF, mutual TLS or certificate-bound client
   credentials and a source-IP allow-list per partner – never the whole API. Future insurer, authority and ERP
   connectors are outbound connections of the on-prem core: one firewall rule per destination through the corporate
   proxy plus a `networkPolicy.extraEgress.core` entry; nothing is sent from the cloud edge.
4. **DNS.** A conditional forwarder for `onprem.example.internal`: Azure Private DNS Resolver, Cloud DNS
   forwarding zone or on-prem DNS.
5. **TLS.** The API ingress certificate comes from the internal PKI. Add the internal root CA to the BFF JVM
   truststore with an extra volume and `JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=…`, or use a publicly trusted
   certificate on the internal name.

Verify from the cloud cluster:

```bash
kubectl -n wellnessz run nettest --rm -it --restart=Never --image=curlimages/curl:8.11.1 -- curl -sS -o /dev/null -w '%{http_code}\n' https://api.wellnessz.onprem.example.internal/api/v1/me
```

Expected: `401` (reachable, authentication required). A timeout points to a routing or firewall problem.

## 5. Vault configuration (on-prem secrets)

```bash
export VAULT_ADDR=https://vault.onprem.example.internal:8200 && vault login -method=oidc
```

```bash
vault secrets enable -path=kv kv-v2 2>/dev/null; vault auth enable -path=kubernetes-wellnessz kubernetes
```

```bash
vault write auth/kubernetes-wellnessz/config kubernetes_host=https://<on-prem-api>:6443 kubernetes_ca_cert=@/path/to/cluster-ca.crt
```

Create the policies from `deploy/hybrid/external-secrets/vault-policies.hcl`, one per block (`wellnessz-core`,
`wellnessz-migrations`, `wellnessz-db-operator`, `wellnessz-bff`). Then create the roles:

```bash
vault write auth/kubernetes-wellnessz/role/wellnessz-core bound_service_account_names=wellnessz-core bound_service_account_namespaces=wellnessz policies=wellnessz-core audience=vault ttl=15m
```

```bash
vault write auth/kubernetes-wellnessz/role/wellnessz-migrations bound_service_account_names=wellnessz-migrations bound_service_account_namespaces=wellnessz policies=wellnessz-migrations audience=vault ttl=15m
```

```bash
vault write auth/kubernetes-wellnessz/role/wellnessz-db-operator bound_service_account_names=external-secrets bound_service_account_namespaces=external-secrets policies=wellnessz-db-operator audience=vault ttl=15m
```

Generate and store the secrets with two people present (key ceremony). The values go straight from `openssl` into
Vault:

```bash
vault kv put kv/wellnessz/core wz-db-password="$(openssl rand -hex 24)" wz-master-key="$(openssl rand -base64 32)" wz-pseudonym-key="$(openssl rand -base64 32)" db-ca-crt=@/path/to/internal-root-ca.crt
```

```bash
vault kv put kv/wellnessz/migrations password="$(openssl rand -hex 24)"
```

```bash
vault kv put kv/wellnessz/bff wz-bff-db-password="$(openssl rand -hex 24)"
```

```bash
vault kv put kv/wellnessz/backup access_key_id=<s3-access-key> secret_access_key=<s3-secret-key>
```

Enable an audit device if none exists, and ship its output to the SIEM:

```bash
vault audit enable file file_path=/vault/audit/audit.log
```

## 6. Install the operators (on-prem)

```bash
helm repo add jetstack https://charts.jetstack.io && helm repo add cnpg https://cloudnative-pg.github.io/charts && helm repo add external-secrets https://charts.external-secrets.io && helm repo add hashicorp https://helm.releases.hashicorp.com && helm repo add secrets-store-csi-driver https://kubernetes-sigs.github.io/secrets-store-csi-driver/charts && helm repo update
```

```bash
helm upgrade --install cert-manager jetstack/cert-manager -n cert-manager --create-namespace --set crds.enabled=true
```

```bash
helm upgrade --install cnpg cnpg/cloudnative-pg -n cnpg-system --create-namespace
```

```bash
kubectl apply -f https://github.com/cloudnative-pg/plugin-barman-cloud/releases/download/v0.15.1/manifest.yaml
```

```bash
helm upgrade --install external-secrets external-secrets/external-secrets -n external-secrets --create-namespace
```

```bash
helm upgrade --install csi-secrets-store secrets-store-csi-driver/secrets-store-csi-driver -n kube-system --set syncSecret.enabled=false --set enableSecretRotation=true
```

```bash
helm upgrade --install vault-csi hashicorp/vault -n vault-csi --create-namespace --set server.enabled=false --set injector.enabled=false --set csi.enabled=true
```

On OpenShift, install cert-manager, CloudNativePG and External Secrets from OperatorHub instead, and grant the
Vault CSI provider the documented SCC.

Verify:

```bash
kubectl get pods -n cert-manager -n cnpg-system -n external-secrets 2>/dev/null; kubectl get csidriver
```

Expected: operators `Running`; CSI driver `secrets-store.csi.k8s.io` present.

## 7. PostgreSQL with CloudNativePG

Create the CA secret that CNPG uses as `serverCASecret` and `clientCASecret` (public certificate only):

```bash
kubectl apply -f deploy/hybrid/cnpg/00-namespace.yaml && kubectl -n wellnessz-db create secret generic internal-pki-ca --from-file=ca.crt=/path/to/internal-root-ca.crt
```

Also create the object-store CA ConfigMap or Secret referenced by `02-objectstore.yaml` (`endpointCA`).

Apply the manifests in order:

```bash
kubectl apply -f deploy/hybrid/external-secrets/cluster-secret-store-vault.yaml -f deploy/hybrid/external-secrets/db-secrets.yaml
```

```bash
kubectl -n wellnessz-db get externalsecret
```

Expected: `SecretSynced` for `wellnessz-db-migrator`, `wellnessz-db-app`, `wellnessz-db-bff` and
`wellnessz-backup-s3`.

```bash
kubectl apply -f deploy/hybrid/cnpg/01-certificates.yaml && kubectl -n wellnessz-db wait certificate --all --for=condition=Ready --timeout=5m
```

Adjust `destinationPath` and `endpointURL` in `02-objectstore.yaml` first, then:

```bash
kubectl apply -f deploy/hybrid/cnpg/02-objectstore.yaml -f deploy/hybrid/cnpg/03-cluster.yaml
```

```bash
kubectl cnpg status wellnessz-db -n wellnessz-db
```

Expected after 5–10 minutes:

* `Cluster in healthy state`;
* 3 instances, 1 primary, synchronous standby `quorum 1`;
* `Continuous Backup status: OK`, with the first WAL archived.

```bash
kubectl apply -f deploy/hybrid/cnpg/04-scheduled-backup.yaml -f deploy/hybrid/cnpg/05-networkpolicy.yaml && kubectl -n wellnessz-db get backup
```

Expected: an immediate backup in phase `completed`.

TLS check (expects TLS 1.3):

```bash
kubectl -n wellnessz-db exec wellnessz-db-1 -c postgres -- psql -U postgres -d wellnessz -c "select ssl, version from pg_stat_ssl where pid = pg_backend_pid()"
```

Local socket connections show `f`. Check a remote session from the core pod after section 8 instead.

## 8. Deploy the core on-prem

1. Copy `values-hybrid.yaml` placeholders to `deploy/env/prod-hybrid-onprem.yaml`: registry mirror, Vault
   address, tenant and audience, mail hosts, ingress host and DNS selectors.
2. Set `global.openshift: true` on OpenShift. The SCC then assigns UIDs, and the images run under any UID.

```bash
kubectl create namespace wellnessz && kubectl label namespace wellnessz pod-security.kubernetes.io/enforce=restricted
```

```bash
helm upgrade --install wellnessz deploy/helm/wellnessz -n wellnessz -f deploy/helm/wellnessz/values.yaml -f deploy/helm/wellnessz/values-hybrid.yaml -f deploy/env/prod-hybrid-onprem.yaml --set-string global.imageTag=v0.1.0 --atomic --wait --timeout 20m
```

The pre-upgrade Flyway Job migrates `wellnessz` as `wz_migrator` and grants the per-table privileges to `wz_app`.

**Increment 1 on-prem.**

* Schema `triage` is in `migrations.job.core.schemas` and in the CNPG bootstrap (`deploy/hybrid/cnpg/03-cluster.yaml`:
  `postInitApplicationSQL` and the grants loop). A cluster initialised before increment 1 gets the schema from
  Flyway V5 (`wz_migrator` owns the database; V5 grants `USAGE` to `wz_app`). To align `search_path`, run
  `ALTER ROLE wz_app SET search_path = platform, masterdata, intake, casemgmt, triage, analytics;` as `postgres`
  (`kubectl cnpg psql wellnessz-db -n wellnessz-db`).
* `values-hybrid.yaml` disables `ohWorkspace` on-prem (like the other web apps) and already lists
  `https://oh.wellnessz.example.com` in `WZ_CORS_ORIGINS`; replace the host with yours.
* Set `WZ_OH_WORKSPACE_URL=https://oh.<domain>` (`values.yaml` ships `https://oh.wellnessz.example.com` – override it
  with your domain) and `WZ_OH_CONTACT` in the core `env` of your overrides file.
* Register the EHS webhook client (Entra app with the `INTEGRATION` application role, guide 06 section 5.6). Its
  calls go to `https://api.wellnessz.onprem.example.internal/api/v1/intake/ehs/incidents`.
* Load the OH staff roster (`masterdata.staff_member`) with a reviewed SQL script; never enable `classpath:db/demo`.

**Increment 2 on-prem.**

* Schemas `docs`, `ohrecord`, `fitness` are in `migrations.job.core.schemas` and in the CNPG bootstrap
  (`postInitApplicationSQL` and the grants loop of `deploy/hybrid/cnpg/03-cluster.yaml`). A cluster initialised
  earlier gets them from Flyway V6–V8; then align `search_path` as `postgres`:
  `ALTER ROLE wz_app SET search_path = platform, masterdata, intake, casemgmt, triage, docs, ohrecord, fitness, analytics;`
* Core `env` in your overrides file: `WZ_EMPLOYEE_PORTAL_URL`, `WZ_MANAGER_WORKSPACE_URL` (cloud hosts; `values.yaml`
  sets the `*.wellnessz.example.com` hosts – override them with your domain),
  `WZ_DPO_EMAIL` (on-prem or M365 DPO mailbox reachable through the SMTP relay).
* Optional PDF font and Tesseract OCR: build a derived core image in your pipeline and push it to the registry mirror;
  set `WZ_DOCUMENTS_FONT_PATH` and, for OCR, `WELLNESSZ_DOCUMENTS_OCR_TESSERACTPATH=/usr/bin/tesseract` and
  `WELLNESSZ_DOCUMENTS_OCR_LANGUAGES` (allow-listed). On-host Tesseract keeps scanned documents on-premises; cloud OCR
  services would move PHI to the cloud and contradict the purpose of the hybrid setup.
* Register one `INTEGRATION` client per gateway (guide 06, section 5.6); their calls go to
  `https://api.wellnessz.onprem.example.internal/api/v1/integration/...` and
  `/api/v1/employees/{id}/results/device`.
* Add `DPO` to the IdP (Entra app role or Keycloak realm role plus BFF / mobile scope mappings).

**Increment 3 on-prem.**

* Schemas `absence`, `surveillance`, `scheduling` are in `migrations.job.core.schemas` and in the CNPG bootstrap
  (`postInitApplicationSQL` and the grants loop of `deploy/hybrid/cnpg/03-cluster.yaml`). A cluster initialised
  earlier gets them from Flyway V10–V12; then align `search_path` as `postgres`:
  `ALTER ROLE wz_app SET search_path = platform, masterdata, intake, casemgmt, triage, docs, ohrecord, fitness, absence, surveillance, scheduling, analytics;`
  (and the same for `wz_migrator`).
* Add `CLINIC_SCHEDULER`, `BIOMEDICAL`, `CREW_LICENSING` and `CANDIDATE` to the IdP (Entra app roles or Keycloak realm
  roles plus BFF / mobile scope mappings); decide the candidate identity source (guide 06, section 5.3).
* Register one `INTEGRATION` client per new gateway (guide 06, section 5.6); their calls go to
  `https://api.wellnessz.onprem.example.internal/api/v1/integration/{absence/episodes,absence/certificates,ih-measurements,cosmic-dose,pre-employment,cold-chain/readings}`.
* After the first deployment set the E18 business keys (mailboxes, telehealth URL, public holidays) through
  `PUT /api/v1/masterdata/config/{key}` (guide 06, section 11). Mailboxes must be reachable through the SMTP relay.
* The jobs run in the on-prem core (UTC cron, once per run across replicas); nothing is scheduled in the cloud edge.
* The cloud edge BFF ships with multipart parsing disabled, so certificate photos and candidate documents are streamed
  unchanged across the link to the on-prem core; keep the MTU / MSS settings of section 12 in mind for 10 MB uploads.

**Increment 4 on-prem.**

* Schemas `treatment` and `claims` are in `migrations.job.core.schemas` and in the CNPG bootstrap
  (`postInitApplicationSQL` and the grants loop of `deploy/hybrid/cnpg/03-cluster.yaml`). A cluster initialised
  earlier gets them from Flyway V14–V15 (V13 adds the partner organisations and the `CLAIM_RECORD` retention class);
  then align `search_path` as `postgres`:
  `ALTER ROLE wz_app SET search_path = platform, masterdata, intake, casemgmt, triage, docs, ohrecord, fitness, absence, surveillance, scheduling, treatment, claims, analytics;`
  (and the same for `wz_migrator`).
* `values-hybrid.yaml` disables `partnerPortal` on-prem (`enabled: false`, empty host) – the portal runs on the edge.
* Core `env` in your overrides file: `WZ_PARTNER_PORTAL_URL=https://partners.<domain>` (cloud host; set in
  `values.yaml` to `https://partners.wellnessz.example.com` – override it with your domain); leave `WZ_MFA_REQUIRED_ROLES` unset so the default `PARTNER_PROVIDER,TPA_ADJUSTER` applies.
* Add `CLAIMS_COORDINATOR`, `TPA_ADJUSTER`, `LEGAL_COUNSEL`, `FINANCE` and `PROCUREMENT` to the IdP (Entra app roles or
  Keycloak realm roles plus BFF / mobile scope mappings); partner and adjuster accounts as B2B guests (or in a Keycloak
  partner realm) with `org_scope=/PARTNERS/<CODE>` and **MFA**: Entra Conditional Access with an authentication
  strength for guest / external users (guide 06, section 5.3a) or Keycloak OTP / WebAuthn required action with an AMR
  mapper or level-of-authentication flow (guide 06, section 5.7). The on-prem core verifies the `amr` / `acr` / `acrs` claim.
* Register one `INTEGRATION` client per partner hospital (FHIR), insurer system and, once selected, the video platform
  (guide 06, section 5.6). Each partner-hospital and insurer client carries a fixed `org_scope=/PARTNERS/<CODE>` claim
  (Keycloak hardcoded-claim mapper; Entra ID app role / optional claim or claims-mapping policy): the on-prem core
  serves it only its own organisation's referrals, pre-authorisations and reports and refuses unbound clients; their calls reach `https://api.wellnessz.onprem.example.internal/api/v1/integration/{referrals/**,preauthorisations/{ref}/decision,claims/reports/{ref}/acknowledgement,telehealth/tokens/redeem}`
  through the DMZ path of section 4.
* After the first deployment set the E18 keys `claims.mailbox-email`, `claims.legal-email`, `claims.employer-ref`,
  `claims.employer-name`, `claims.fx-rates`, `treatment.telehealth.base-url`, `treatment.network.cities` (guide 06,
  section 11). The claims and legal mailboxes must be reachable through the SMTP relay; insurer contact e-mails
  (content-free, pointing to the partner portal) also leave through the relay.
* The four new jobs (`treatment-variance`, `referral-chase`, `provider-scorecards`, hourly `claims-report-deadlines`) run
  in the on-prem core; outbox events to `insurer-gateway`, `authority-gateway` and `erp-finance` stay on-prem until a
  connector is configured.
* Provider reports and invoices (≤ 10 MB) uploaded in the partner portal cross the link like certificate uploads; the
  MTU / MSS settings of section 12 apply.

Verify:

```bash
kubectl -n wellnessz get pods && kubectl -n wellnessz logs deploy/wellnessz-core | grep -m1 "Started WellnessZCoreApplication"
```

## 9. Deploy the cloud edge

1. Provision the cloud side with guide 06 (Azure) or 07 (GCP). The managed PostgreSQL there stores only BFF
   sessions. Size it small (for example a Burstable/General Purpose 2-vCore tier) and keep HA in prod.
2. Run `deploy/sql/bootstrap-managed-postgres.sql` against it. The BFF's own Flyway creates schema `bff` (mode
   `app` in the edge values).
3. In the cloud Key Vault or Secret Manager, set:
   * `wz-bff-client-secret`;
   * `wz-bff-session-key` (`openssl rand -base64 32`);
   * `wz-bff-db-password` (generated by Terraform);
   * `wz-migrator-db-password`.

   The core secrets are not needed in the cloud.
4. Fill the placeholders of `deploy/hybrid/values-hybrid-cloud-edge.yaml` in your overrides file, especially
   `WZ_CORE_API_URL` and `WZ_BFF_DB_URL`.
5. **OH workspace (increment 1).** The edge release inherits `ohWorkspace.enabled: true` from `values.yaml` and the
   host `ingress.hosts.ohWorkspace` from `values-azure.yaml` (or `values-gcp.yaml`); set your host in the overrides
   file. Create the DNS record and certificate for `oh.<domain>` at the cloud edge, and add
   `https://oh.<domain>/login/oauth2/code/wellnessz` to the `wellnessz-bff` redirect URIs. The OH workspace holds no
   data; all clinical calls go through the BFF to the on-prem core.
6. **Partner portal (increment 4).** The edge release inherits `partnerPortal.enabled: true` and
   `ingress.hosts.partnerPortal` from `values.yaml` / `values-azure.yaml` (or `values-gcp.yaml`); set your host in the
   overrides file. Create the public DNS record and certificate for `partners.<domain>` at the cloud edge and add
   `https://partners.<domain>/login/oauth2/code/wellnessz` (post-logout `https://partners.<domain>/`) to the
   `wellnessz-bff` registration. `partners.<domain>` is internet-facing for external users; keep `manager.<domain>` and
   `oh.<domain>` restricted to the corporate network where possible (guide 06, section 9, step 5; guide 07,
   section 10, step 3). The portal holds no data; every call goes through the edge BFF to the on-prem core, which
   enforces the MFA gate and organisation isolation.

```bash
helm upgrade --install wellnessz-edge deploy/helm/wellnessz -n wellnessz -f deploy/helm/wellnessz/values.yaml -f deploy/helm/wellnessz/values-azure.yaml -f deploy/hybrid/values-hybrid-cloud-edge.yaml -f deploy/env/prod-hybrid-edge.yaml --set-string global.imageTag=v0.1.0 --atomic --wait
```

The Azure workload identity federated credentials expect the release name `wellnessz`. If you use
`wellnessz-edge`, set Terraform `helm_release_name = "wellnessz-edge"` or keep the release name `wellnessz`.

## 10. Smoke tests

| # | Check | Expected |
|---|---|---|
| 1 | `kubectl cnpg status wellnessz-db -n wellnessz-db` | Healthy, sync standby, continuous backup OK |
| 2 | Edge → on-prem API (section 4 test) | `401` |
| 3 | Browser login on `https://employee.<domain>` | Entra login; portal loads; cases come from the on-prem core |
| 4 | Manager workspace | Status only |
| 5 | Database location | `kubectl -n wellnessz exec deploy/wellnessz-core -- env \| grep WZ_DB_URL` → `wellnessz-db-rw.wellnessz-db.svc` |
| 6 | Cloud DB contents | Only the `bff` schema with `spring_session*` tables (no clinical schemas) |
| 7 | EHS intake | On-prem IMAP (`mail.onprem.example.internal:993`) or Graph via the corporate proxy; Excel import as in guide 05, section 8.2 |
| 8 | `tools/e2e/e2e.js` with `WZ_E2E_*` set to the cloud hosts | Status-only assertions pass |
| 9 | OH workspace (increment 1): `https://oh.<domain>` as a Nursing Head test account | Work queues load; data comes from the on-prem core |
| 10 | Schema location: `kubectl -n wellnessz-db exec wellnessz-db-1 -c postgres -- psql -U postgres -d wellnessz -c '\dn'` | `triage` present on-prem; the cloud session database still holds only `bff` |
| 11 | EHS webhook from the EHS network to the on-prem API host (guide 06, section 5.6 smoke call) | `202` |
| 12 | `tools/e2e/e2e-increment1.js` with `WZ_E2E_EMPLOYEE_URL`, `WZ_E2E_WORKSPACE_URL`, `WZ_E2E_OH_URL` set to the cloud hosts and `WZ_E2E_CORE_URL` to the on-prem API host (dev/test only, run from a host that can reach both) | 18 `PASS` lines |
| 13 | Increment-2 schema location: `kubectl -n wellnessz-db exec wellnessz-db-1 -c postgres -- psql -U postgres -d wellnessz -c '\dn'` | `docs`, `ohrecord`, `fitness` present on-prem; the cloud session database still holds only `bff` |
| 14 | Gateway pull queue from the laboratory network: `GET https://api.wellnessz.onprem.example.internal/api/v1/integration/lis/orders?status=QUEUED` with a gateway token | `200`; a user token gets `403` |
| 15 | DPO test account on `https://oh.<domain>` | Break-glass reviews, disclosures and retention load; a chart is not shown |
| 16 | `tools/e2e/e2e-increment2.js` with the same `WZ_E2E_*` settings as check 12 (dev/test only) | 25 `PASS` lines |
| 17 | Increment-3 schema location: `kubectl -n wellnessz-db exec wellnessz-db-1 -c postgres -- psql -U postgres -d wellnessz -c '\dn'` | `absence`, `surveillance`, `scheduling` present on-prem; the cloud session database still holds only `bff` |
| 18 | `GET /api/v1/modules` through the edge | E06, E07 and E14 `IMPLEMENTED` |
| 19 | IoT fridge gateway from a clinic network: `POST https://api.wellnessz.onprem.example.internal/api/v1/integration/cold-chain/readings` with the gateway token | `200`; a user token gets `403` |
| 20 | Certificate upload in the employee portal (cloud edge) | Upload succeeds; the document is stored in the on-prem `docs` schema |
| 21 | Scheduler test account on `https://oh.<domain>` | *Clinic diary* loads; the absence queue is not available |
| 22 | `tools/e2e/e2e-increment3.js` with the same `WZ_E2E_*` settings as check 12 (dev/test only, against a dev database with the increment-3 demo data) | 32 `PASS` lines |
| 23 | Increment-4 schema location: `kubectl -n wellnessz-db exec wellnessz-db-1 -c postgres -- psql -U postgres -d wellnessz -c '\dn'` | `treatment`, `claims` present on-prem; the cloud session database still holds only `bff` |
| 24 | `GET /api/v1/modules` through the edge | E04 and E08 `IMPLEMENTED` |
| 25 | `curl -sI https://partners.<domain>/` from the internet; `kubectl get deploy` on both clusters | `200` with HSTS and CSP; `wellnessz-partner-portal` runs only on the edge cluster |
| 26 | Partner test guest with MFA on `https://partners.<domain>`; the same account without MFA | Referral inbox from the on-prem core; without MFA the *multi-factor sign-in required* page and `403` on partner calls |
| 27 | Partner system from the internet through the DMZ path: `GET https://<published-api-host>/api/v1/integration/referrals/<id>/fhir` with its `INTEGRATION` token (bound to the referral's provider by `org_scope`) | `200` with a `ServiceRequest`; any non-published path (for example `/api/v1/cases`) is not reachable from outside |
| 28 | Provider report upload (≤ 10 MB) in the partner portal | Upload succeeds; the document is stored in the on-prem `docs` schema |
| 29 | `tools/e2e/e2e-increment4.js` with the settings of check 12 plus `WZ_E2E_PARTNER_URL=https://partners.<domain>` (dev/test only, fresh dev database with the increment-3 and increment-4 demo data, scripts 1 → 4 in order) | 22 `PASS` lines |

## 11. Backup restore test (quarterly, SOC 2 A1.3)

Restore into a scratch cluster from the object store and check the data. Create `restore-test.yaml` with this
`Cluster` resource:

```yaml
apiVersion: postgresql.cnpg.io/v1
kind: Cluster
metadata:
  name: wellnessz-db-restore-test
  namespace: wellnessz-db
spec:
  instances: 1
  imageName: ghcr.io/cloudnative-pg/postgresql:16.15-standard-trixie
  storage: { size: 200Gi, storageClass: encrypted-ssd }
  bootstrap:
    recovery:
      source: origin
      # recoveryTarget: { targetTime: "2026-10-28T10:00:00Z" }   # point-in-time
  externalClusters:
    - name: origin
      plugin:
        name: barman-cloud.cloudnative-pg.io
        parameters:
          barmanObjectName: wellnessz-backup-store
          serverName: wellnessz-db
```

```bash
kubectl apply -f restore-test.yaml && kubectl cnpg status wellnessz-db-restore-test -n wellnessz-db
```

Record:

* the start and end times (the measured RTO);
* the latest restored transaction time (the RPO);
* row counts of `platform.audit_event`;
* the result of `GET /api/v1/audit/verify` against a core pointed at the restored database.

Then delete the test cluster.

## 12. Troubleshooting

| Symptom | Likely cause | Resolution |
|---|---|---|
| ExternalSecret `SecretSyncedError` | Vault role or policy mismatch, or Vault CA not trusted | `vault read auth/kubernetes-wellnessz/role/wellnessz-db-operator`; check `caProvider` |
| CNPG pods `Pending` | StorageClass missing or anti-affinity cannot be satisfied (fewer than 3 nodes) | Add nodes or temporarily set `podAntiAffinityType: preferred` (document the exception) |
| CNPG `Continuous Backup status: Failed` | Object-store endpoint, credentials or CA | `kubectl -n wellnessz-db logs wellnessz-db-1 -c plugin-barman-cloud`; check `endpointCA` |
| Core: `PSQLException: SSL error: … PKIX path` | `wellnessz-db-ca.crt` missing from the Vault secret / CSI files | Add `db-ca-crt` to `kv/wellnessz/core`; restart the pod |
| Flyway job: `permission denied for database` | `grants.sql` not applied at bootstrap | Re-run the SQL from the ConfigMap as `postgres` (`kubectl cnpg psql wellnessz-db -n wellnessz-db`) |
| Edge BFF `504` / connection reset | Firewall or MTU on the VPN, or DNS forwarder | Test with the `nettest` pod; set the MSS clamp to 1350 on the VPN |
| OpenShift pods rejected by SCC | `runAsUser` set | `global.openshift=true` |
| Arc extension `Failed` | Outbound proxy not configured | `az connectedk8s connect --proxy-https …` |
| Lab or rostering gateway times out | Firewall rule for that gateway → on-prem API VIP missing | Section 4, step 3 (one documented rule per gateway) |
| Break-glass alert e-mail not received | `WZ_DPO_EMAIL` unset or the SMTP relay rejects the recipient | Set the variable; check the notification log in the core |
| IoT fridge or absence-feed gateway times out | Firewall rule for that gateway → on-prem API VIP missing | Section 4, step 3 |
| Certificate uploads from the portal fail with `400` | BFF multipart parsing re-enabled by an override on the edge | Keep the shipped `spring.servlet.multipart.enabled=false` |
| Certificate uploads fail or hang for larger files | MTU / MSS on the VPN | MSS clamp to 1350 (see *Edge BFF 504* above) |
| HR absence, crew-licensing or calibration e-mails not received | E18 mailbox keys not set or rejected by the SMTP relay | Set the keys (guide 06, section 11); check the notification log |
| Partner or adjuster sees *multi-factor sign-in required* | Token without `amr` / `acr` / `acrs` proof (Conditional Access not enforced for guests, or Keycloak without AMR mapper / LoA flow) | Guide 06, sections 5.3a and 5.7 |
| Partner portal login error on the edge (`AADSTS50011` / *Invalid redirect_uri*) | `partners.<domain>` redirect URI missing on `wellnessz-bff` | Section 9, step 6 |
| Partner hospital or insurer system times out | DMZ publication or source-IP allow-list for that partner missing | Section 4, step 3 |
| Partner e-mails link to `partners.wellnessz.example.com` (or `.local`) | `WZ_PARTNER_PORTAL_URL` not overridden with your domain in the on-prem core | Section 8, increment 4 |
| Partner hospital or insurer system gets `404` for its own referral, pre-authorisation or report | Client token without `org_scope=/PARTNERS/<CODE>` or with another organisation's code (audit `DENIED`) | Guide 06, section 5.6 |

## 13. Teardown

```bash
helm uninstall wellnessz -n wellnessz
```

```bash
kubectl delete -f deploy/hybrid/cnpg/04-scheduled-backup.yaml -f deploy/hybrid/cnpg/03-cluster.yaml
```

PVCs and object-store backups are retained by design. Delete them only after the retention period, with an
approved change. Disconnect Arc or the fleet membership, and remove the firewall rule and Vault roles.
