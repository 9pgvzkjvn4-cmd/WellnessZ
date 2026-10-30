# WellnessZ deployment environments

The same application (same images, same Helm chart) is deployed to whichever
target the client chooses. Environment differences live only in
**Terraform variable files**, **Helm overlays** and **GitHub Environments**.

## Environment matrix

| Target | Purpose | Infrastructure | Application config | Images | Approval |
|---|---|---|---|---|---|
| **local** (MacBook Pro) | unit/integration tests, developer loop | `deploy/local` docker compose (PostgreSQL 16, Keycloak, Mailpit, GreenMail, OTel) | Spring profile `local`; optional kind/minikube with `values-local.yaml` | built locally | – |
| **azure-dev** | developer integration | `terraform/azure` + `environments/dev.tfvars` | `values-azure.yaml` + `environments/azure/dev.yaml` | every `main` build (`sha-<12>`) | none (automatic) |
| **azure-uat** | business acceptance | `terraform/azure` + `environments/uat.tfvars` | `values-azure.yaml` + `environments/azure/uat.yaml` | release candidates `vX.Y.Z-rc.N` | required reviewers |
| **azure-prod** | production | `terraform/azure` + `environments/prod.tfvars` | `values-azure.yaml` + `environments/azure/prod.yaml` | final releases `vX.Y.Z` (digest) | required reviewers (CAB) |
| **gcp-dev / gcp-uat / gcp-prod** | as above on GCP | `terraform/gcp` + `environments/<env>.tfvars` (one GCP project per environment) | `values-gcp.yaml` + `environments/gcp/<env>.yaml` | as above | as above |
| **hybrid** dev/uat/prod | PHI stays on-prem: core + CloudNativePG on-prem, BFF + web in the cloud | `deploy/hybrid` (CNPG, Vault/External Secrets) + the cloud stack of the same environment | on-prem: `values-hybrid.yaml` + `environments/hybrid/<env>.yaml`; edge: cloud overlay + `deploy/hybrid/values-hybrid-cloud-edge.yaml` | mirrored by digest | as cloud |

### What differs between DEV, UAT and PROD

| | DEV | UAT | PROD |
|---|---|---|---|
| Availability zones | 1 (Azure) / zonal Cloud SQL | 3 / regional | 3 / regional |
| Database HA | none | zone-redundant (Azure) / REGIONAL (GCP) | zone-redundant / REGIONAL |
| Backups / PITR | 7 days / 1-day PITR (GCP) | 14 days, 7-day PITR | Azure 35 days + geo-redundant; GCP 30 backups + 7-day PITR |
| Cluster | public API (authorized IPs), Free tier (AKS) | private (AKS), Standard tier | private, Standard tier, resource lock |
| WAF | App Gateway *Detection* / Cloud Armor *preview* | Prevention | Prevention + adaptive protection |
| Binary Authorization (GCP) | dry-run | enforced | enforced |
| Deletion protection / locks | off | on | on |
| Log retention | 30 days | 90 days | 365 days |
| Replicas (core) / PDB | 1-2 / none | 2-4 / minAvailable 1 | 3-10 / minAvailable 2 |
| Flyway | in the app (`migrations.mode=app`) | pre-upgrade Job | pre-upgrade Job |
| Image policy | branch builds by tag | release candidate digest | signed release digest (`global.requireImageDigests`) |
| `WZ_MFA_REQUIRED_ROLES` | empty (test identities) | `PARTNER_PROVIDER,TPA_ADJUSTER` | `PARTNER_PROVIDER,TPA_ADJUSTER` |
| Log level / trace sampling | DEBUG / 1.0 | INFO / 0.25 | INFO (root WARN) / 0.05 |
| Network policy | component policies; no namespace-wide deny | namespace default deny | namespace default deny |
| Hosts | `employee.dev.<domain>`, `oh.dev.<domain>`, `api.dev.<domain>` … | `*.uat.<domain>` | `employee.<domain>` … |
| OIDC | per-environment Entra ID app registrations (`api://wellnessz-api-dev`) | `…-uat` | `api://wellnessz-api` |

Network ranges do not overlap between environments (Azure VNets
`10.48/16` dev, `10.44/16` uat, `10.40/16` prod; GCP subnets `10.68`, `10.56`,
`10.60`) so all of them can peer with a hub or on-prem. The Helm overlays carry
the matching App Gateway / PostgreSQL / Private Service Access CIDRs - change
both together.

## Terraform per environment

One state per environment (separate backend config, ideally a separate state
storage account/bucket and subscription/project per environment):

```bash
cd deploy/terraform/azure            # or deploy/terraform/gcp
ENV=uat                              # dev | uat | prod
cp environments/backend-$ENV.hcl.example environments/backend-$ENV.hcl        # once, edit names
cp environments/local.tfvars.example environments/$ENV.local.tfvars          # once, tenant/project IDs (git-ignored)
terraform init -reconfigure -backend-config=environments/backend-$ENV.hcl
terraform plan  -var-file=environments/$ENV.tfvars -var-file=environments/$ENV.local.tfvars -out $ENV.tfplan
terraform apply $ENV.tfplan
terraform output                     # values for the Helm overlay + GitHub Environment variables
```

* `environment` must be `dev`, `uat` or `prod` (`test` was renamed to `uat`;
  an existing `test` stack needs a state move / rename). It drives names
  (`rg-wz-uat`, `aks-wz-uat`, `wellnessz-uat` …), tags/labels
  (`environment`, `environment-tier`) and environment-dependent defaults.
* GitHub OIDC: each environment's deployer identity trusts only its own GitHub
  Environment (`github_environments`, default `["azure-<env>"]` /
  `["gcp-<env>"]`). Only DEV accepts image pushes from `main`
  (`github_image_publish_branches`).
* Validate locally: `terraform fmt -check -recursive` and
  `terraform init -backend=false && terraform validate`.

## Helm per environment

```bash
CHART=deploy/helm/wellnessz CLOUD=azure ENV=uat
helm upgrade --install wellnessz $CHART -n wellnessz \
  -f $CHART/values.yaml -f $CHART/values-$CLOUD.yaml -f $CHART/environments/$CLOUD/$ENV.yaml \
  [-f deploy/clients/<client>/values.yaml] \
  --set-string core.image.digest=sha256:... --set-string bff.image.digest=sha256:... # (all six components)
```

Replace the `<…>` placeholders in the overlay with that environment's
`terraform output`. In CI the same layering is done by
`deploy/scripts/helm-release.sh` (also usable by operators).

## Promotion flow (GitHub Actions)

```
push main ──► container.yml: build 6 images once, Trivy gate, SBOM/SLSA, cosign sign (sha-<12>)
                 │
                 ▼ (workflow_run)
cd.yml ──► resolve digests once + verify signatures ──► azure-dev / gcp-dev (automatic)
tag vX.Y.Z-rc.N ─► same digests of the tagged commit ──► dev ──► uat  [required reviewers]
tag vX.Y.Z      ─► same digests ────────────────────────► dev ──► uat ──► prod [required reviewers]
manual: deploy-env.yml (cloud + environment + tag)  |  cd.yml (cloud(s) + first stage + tag)
```

Every stage (`deploy-env.yml`):
1. checks the promotion policy (dev: `sha-*`/`v*`, uat: `vX.Y.Z[-rc.N]`, prod: `vX.Y.Z`);
2. logs in with OIDC (no stored cloud credentials);
3. verifies the cosign signatures (uat/prod: built by `container.yml` on `main`);
4. copies the images **by digest** into the environment registry (`cosign copy`
   keeps digest, signatures and attestations) and, on GKE, creates the Binary
   Authorization attestation;
5. `helm upgrade --install --atomic` with the environment overlay and all
   components pinned by digest;
6. smoke tests: rollout status, running pods use exactly the promoted digests,
   optional HTTP checks (`SMOKE_URLS`);
7. on smoke-test failure: automatic `helm rollback` to the previous revision.
   Database migrations are forward-only - write expand/contract migrations so
   the previous release keeps working after a rollback.

### GitHub Environments to create

`azure-dev`, `azure-uat`, `azure-prod`, `gcp-dev`, `gcp-uat`, `gcp-prod`:

* uat/prod: **Required reviewers** (prod: CAB/security group, prevent
  self-review), deployment tag rule `v*`; dev: branch `main` + tags `v*`.
* Variables (no secrets): `IMAGE_REGISTRY`, `HELM_RELEASE`, `K8S_NAMESPACE`,
  `SMOKE_URLS`, `EXTRA_VALUES_FILES`; Azure `AZURE_CLIENT_ID` (output
  `github_deployer_client_id`), `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`,
  `AKS_RESOURCE_GROUP`, `AKS_CLUSTER_NAME`, `AKS_PRIVATE`; GCP
  `GCP_WORKLOAD_IDENTITY_PROVIDER`, `GCP_SERVICE_ACCOUNT`, `GCP_PROJECT_ID`,
  `GKE_CLUSTER`, `GKE_LOCATION`, `BINAUTHZ_ATTESTOR`, `BINAUTHZ_KMS_KEY_VERSION`.
* Repository variables: `DEPLOY_CLOUDS` (`["azure"]`, `["gcp"]` or
  `["azure","gcp"]`), `RELEASE_REGISTRY` (default `ghcr.io/<owner>/wellnessz`),
  optional `DEPLOY_RUNNER`.

## Client customisation

Per-client settings go into a small values file (e.g.
`deploy/clients/<client>/values.yaml`, referenced by `EXTRA_VALUES_FILES`) -
no chart fork:

```yaml
customisation:
  client: acme                      # WZ_CLIENT_ID + label wellnessz.io/client
  featureFlags:                     # -> WZ_FEATURE_<NAME> on core and bff
    travel-health-v2: true          #    WZ_FEATURE_TRAVEL_HEALTH_V2="true"
  migrations:                       # client-specific Flyway migrations (db/client/acme)
    enabled: true
    image: { repository: wz-client-acme, digest: sha256:... }   # SQL under /client/db/migration
    # configMap: acme-migrations    # alternative: ConfigMap of V<n>__<desc>.sql files
core:
  extraEnv:                         # any further env vars (raw EnvVar list)
    - { name: WZ_SOMETHING, value: "x" }
```

Client migrations run **after** the product migrations, in their own schema
(default `client_<client>`) with their own history table
(`flyway_schema_history_client`), so client version numbers never collide with
product releases. Use `${app_role}` in grants. `migrations.mode=job`: extra
`flyway-client` step in the migration Job; `migrations.mode=app`: mounted into
core and applied by the application (`WZ_FLYWAY_EXTRA_LOCATIONS`,
`WZ_FLYWAY_CLIENT_SCHEMAS`, `WZ_FLYWAY_CLIENT_TABLE`). Product migration
locations remain overridable with `WZ_FLYWAY_LOCATIONS`.

## Validation

```bash
terraform fmt -check -recursive deploy/terraform
(cd deploy/terraform/azure && terraform init -backend=false && terraform validate)
(cd deploy/terraform/gcp   && terraform init -backend=false && terraform validate)
for c in azure gcp hybrid; do for e in dev uat prod; do
  helm lint deploy/helm/wellnessz -f deploy/helm/wellnessz/values.yaml -f deploy/helm/wellnessz/values-$c.yaml \
    -f deploy/helm/wellnessz/environments/$c/$e.yaml --set-string core.image.digest=sha256:$(printf '0%.0s' $(seq 64)) ...
done; done
actionlint
```

`ci.yml` (job *IaC & config checks*) lints and renders every cloud/environment overlay.
