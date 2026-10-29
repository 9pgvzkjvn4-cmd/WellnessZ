# WellnessZ – Hybrid deployment (on-prem core + cloud edge)

Occupational-health records are special-category health data. Some airlines
must keep them inside their own data centre (data-residency, works-council or
regulator requirements) while still offering internet-facing employee and
manager portals. The hybrid topology keeps **all PHI at rest on-prem** and
runs only the stateless, PHI-free web tier in the cloud.

```
                Internet (employees, managers, mobile)
                               |
                 +-------------v--------------+
                 |  Cloud WAF / LB            |  App Gateway WAF v2 / Front Door (Azure)
                 |  (TLS 1.2+, OWASP rules)   |  Cloud Armor + GCLB (GCP)
                 +-------------+--------------+
   CLOUD (AKS / GKE)           |
   +---------------------------v-----------------------------+
   | employee-portal  manager-workspace   bff (OIDC, session) |   values-hybrid-cloud-edge.yaml
   +---------------------------+-----------------------------+
                               | HTTPS (mTLS optional), private routing only
             ExpressRoute / Cloud Interconnect / IPsec site-to-site VPN
                               |
   ON-PREM (RKE2 or OpenShift, attached to Azure Arc or GKE Enterprise)
   +---------------------------v-----------------------------+
   | internal ingress (api.wellnessz.onprem...)               |
   | wz-core-app  ----TLS verify-full---->  CloudNativePG     |   values-hybrid.yaml
   |   (CSI secrets from Vault)              3 instances      |   cnpg/*.yaml
   +----------------------------------------+----------------+
                                            | WAL + base backups (encrypted)
                                     S3-compatible object store (on-prem, + off-site copy)
```

## What runs where

| Component | Location | Data | Helm values |
|---|---|---|---|
| employee-portal, manager-workspace, oh-workspace (static; OH workspace since increment 1) | cloud | none | `values-hybrid-cloud-edge.yaml` |
| wz-bff (OIDC client, session cookie, token relay) | cloud | session only (no PHI persisted) | `values-hybrid-cloud-edge.yaml` |
| wz-core-app (API, Flyway, outbox, integrations) | on-prem | PHI in transit/processing | `../helm/wellnessz/values-hybrid.yaml` |
| PostgreSQL 16 (CloudNativePG, 3 instances) | on-prem | PHI at rest | `cnpg/` |
| Secrets | HashiCorp Vault on-prem **or** Azure Key Vault via Arc | – | `external-secrets/` |
| Mobile app / M2M integrations | call the on-prem API through an API gateway / DMZ ingress | – | – |

## Fleet management

* **Azure Arc–enabled Kubernetes**: connect the RKE2/OpenShift cluster
  (`az connectedk8s connect`), enable the extensions *Azure Policy* (same
  initiative as AKS), *Microsoft Defender for Containers*, *Azure Monitor
  Container Insights*, *Flux (GitOps)* and optionally *Azure Key Vault Secrets
  Provider*. Enable the Arc workload-identity feature so pods federate to Entra
  ID without secrets.
* **GKE Enterprise (Anthos) attached clusters**: register the cluster to the
  fleet, enable Config Sync (GitOps), Policy Controller (Gatekeeper bundles:
  `pss-restricted`, `cis-k8s`), and Cloud Service Mesh for mTLS between the
  edge and on-prem if desired. Fleet Workload Identity provides GCP identity.

Either way the **same Helm chart** is deployed; only the values differ.

## Connectivity

* Azure: **ExpressRoute** private peering (preferred) or a VPN Gateway
  (IKEv2, AES-256-GCM, PFS) – the AKS VNet is peered/hub-connected; private DNS
  forwards `onprem.example.internal` to on-prem resolvers.
* GCP: **Dedicated/Partner Interconnect** or **HA VPN** + Cloud Router (BGP);
  Cloud DNS forwarding zone for `onprem.example.internal`.
* Only TCP/443 from the cloud BFF egress range to the on-prem API ingress is
  allowed through the on-prem firewall. The on-prem core NetworkPolicy
  (`networkPolicy.coreExtraIngressPeers`) admits the ingress controller only.
* The on-prem API ingress presents a certificate from the internal PKI; the
  BFF trusts it via the JVM truststore (mount the CA) – optionally enforce
  mutual TLS (client certificate for the BFF) at the ingress.
* No database port is ever exposed outside the on-prem cluster.

## Secrets

Two supported patterns (pick one per organisation):

1. **HashiCorp Vault (on-prem)** – default in `values-hybrid.yaml`
   (`secrets.provider=vault`). The Vault CSI provider authenticates with the
   pod's service-account token (Kubernetes auth, roles per component, see
   `external-secrets/vault-policies.hcl`). External Secrets Operator syncs
   only what operators need as Kubernetes Secrets (CNPG role passwords, backup
   credentials) – `external-secrets/cluster-secret-store-vault.yaml`,
   `external-secrets/db-secrets.yaml`.
2. **Azure Key Vault via Azure Arc** – install the Arc *Key Vault Secrets
   Provider* extension and set `secrets.provider=azure` +
   `workloadIdentity.azure.*` in the on-prem values; ESO uses
   `cluster-secret-store-azure-arc.yaml`. Key Vault keeps the single audit
   trail across cloud and on-prem.

Vault audit devices / Key Vault diagnostic logs must be shipped to the SIEM.

## Database (CloudNativePG)

Install order (operator ≥ 1.26 and the Barman Cloud plugin, cert-manager,
External Secrets Operator already present):

```bash
kubectl apply -f cnpg/00-namespace.yaml
kubectl apply -f external-secrets/cluster-secret-store-vault.yaml -f external-secrets/db-secrets.yaml
kubectl apply -f cnpg/01-certificates.yaml
kubectl apply -f cnpg/02-objectstore.yaml
kubectl apply -f cnpg/03-cluster.yaml
kubectl apply -f cnpg/04-scheduled-backup.yaml -f cnpg/05-networkpolicy.yaml
kubectl -n wellnessz-db get cluster wellnessz-db -w
```

Controls implemented in `cnpg/03-cluster.yaml`:

* 3 instances, required pod anti-affinity, zone spread; synchronous
  replication (`dataDurability: required`) → RPO 0 for node/zone failure.
* TLS: server + replication certificates from the internal PKI
  (cert-manager), `ssl_min_protocol_version=TLSv1.3`, `pg_hba` allows only
  `hostssl … scram-sha-256` for `wz_app`/`wz_migrator`, everything else rejected.
* Least privilege: `wz_migrator` owns the database and schemas
  (`platform, masterdata, intake, casemgmt, analytics`); `wz_app` gets DML only
  via default privileges; `ALTER SYSTEM` disabled.
* Audit: `pgaudit.log=ddl,role`, connection/disconnection logging.
* Encryption at rest: `encrypted-ssd` StorageClass (LUKS/array encryption)
  plus server-side encryption of backups (`AES256`) in the object store.
* Backups: continuous WAL archiving + daily base backups via the Barman Cloud
  plugin, 35-day retention → PITR. Replicate the bucket to a second site.
  Restore test quarterly (SOC 2 A1.3 evidence): bootstrap a scratch cluster
  with `bootstrap.recovery` from the `ObjectStore` and run the app smoke test.

Then deploy the core:

```bash
helm upgrade --install wellnessz deploy/helm/wellnessz -n wellnessz --create-namespace \
  -f deploy/helm/wellnessz/values.yaml -f deploy/helm/wellnessz/values-hybrid.yaml \
  --set global.imageTag=<release> --atomic --wait
```

and the cloud edge with `values-hybrid-cloud-edge.yaml` (see the file header).

## OpenShift notes

* Set `global.openshift=true` – the chart then omits `runAsUser/runAsGroup/fsGroup`
  so the `restricted-v2` SCC assigns UIDs; images run fine under an arbitrary UID
  (files are root-owned, world-readable; only `/tmp` is written).
* `ingress.className=openshift-default` creates Routes from Ingress objects; use
  `route.openshift.io/termination: reencrypt` for end-to-end TLS.
* DNS NetworkPolicy selectors differ from upstream (`openshift-dns` namespace) –
  already set in `values-hybrid.yaml`.

## RKE2 notes

* Enable the CIS profile (`profile: cis` in `/etc/rancher/rke2/config.yaml`),
  secrets encryption (`secrets-encryption: true`) and Pod Security Admission.
* Use Cilium or Calico as CNI so NetworkPolicies are enforced.
* DNS selector: namespace `kube-system`, label `k8s-app: kube-dns` (rke2-coredns).

## Image supply chain

On-prem clusters pull from a local mirror (Harbor / Artifactory) synchronised
from ACR / Artifact Registry. Verify cosign signatures on admission with
Sigstore policy-controller or Kyverno `verifyImages`, using the keyless
identity of `.github/workflows/container.yml`.
