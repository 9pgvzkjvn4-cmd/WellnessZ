# IntiMed – Azure Qatar Central deployment kit

| Path | What |
|---|---|
| `terraform/` | Infrastructure (azurerm 4.x): `envs/uat.tfvars`, `envs/prod.tfvars`, `tests/plan.tftest.hcl` (`terraform test`, mocked providers) |
| `scripts/preflight.sh` | Providers, VM sizes per zone, quotas, PostgreSQL HA support in qatarcentral |
| `scripts/bootstrap-secrets.sh` | Master key + DB password into Key Vault (generated locally, never printed) |
| `scripts/render-values.sh`, `scripts/deploy.sh` | Helm values from `terraform output`; `helm upgrade --install` + `helm test` (private clusters via `az aks command invoke`) |
| `scripts/export-root-ca.sh` | Platform root CA for Application Gateway backend trust (phase 2) |
| `scripts/dr-drill.sh` | DR drills D1–D4 |
| `pricing/` | Retail price snapshot used by `tools/azure/cost_model.py` |
| `../helm/intimed/` | Helm chart (values, values-uat, values-prod) |
| `../docker/Dockerfile`, `Dockerfile.gateway` | Platform and edge-gateway images |

Start with `docs/azure/Setup-Guide.md`.
