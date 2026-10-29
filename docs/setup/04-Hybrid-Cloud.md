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

## 1. Target state

| Location | Runs | Values / manifests |
|---|---|---|
| On-premises cluster (RKE2 ≥ 1.31 or OpenShift ≥ 4.18) | `wz-core-app`, Flyway Job, CloudNativePG cluster `wellnessz-db`, Vault CSI provider, External Secrets, cert-manager | `deploy/helm/wellnessz/values-hybrid.yaml`, `deploy/hybrid/cnpg/*`, `deploy/hybrid/external-secrets/*` |
| Cloud cluster (AKS from guide 06, or GKE from guide 07) | `employee-portal`, `manager-workspace`, `wz-bff`; small managed PostgreSQL for BFF sessions | `values-azure.yaml` (or `values-gcp.yaml`) + `deploy/hybrid/values-hybrid-cloud-edge.yaml` |
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
   log denies.
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

## 13. Teardown

```bash
helm uninstall wellnessz -n wellnessz
```

```bash
kubectl delete -f deploy/hybrid/cnpg/04-scheduled-backup.yaml -f deploy/hybrid/cnpg/03-cluster.yaml
```

PVCs and object-store backups are retained by design. Delete them only after the retention period, with an
approved change. Disconnect Arc or the fleet membership, and remove the firewall rule and Vault roles.
