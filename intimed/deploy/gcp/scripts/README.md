# IntiMed_GCP – deployment scripts (me-central1, Doha)

Run in this order (full walkthrough: `docs/gcp/IntiMed_GCP_Setup_Guide.md`):

| Step | Script | What it does |
|---|---|---|
| 1 | `ENV=uat PROJECT=<id> ./preflight.sh` | APIs, service agents, machine types, quotas, HSM, SQL, GKE versions, `_Default` sink off |
| 2–3 | `terraform init -backend-config=envs/backend.hcl && terraform apply -var-file=envs/uat.tfvars` | Infrastructure (in `../terraform`) |
| 4 | `ENV=uat ./bootstrap-secrets.sh` | Master key and DB password in Secret Manager (Doha-only, CMEK) |
| 5 | `ENV=uat CONSOLE_HOSTNAME=… ./render-values.sh` | Helm values from Terraform outputs |
| 6 | `ENV=uat CONSOLE_HOSTNAME=… CONSOLE_TLS_CERT=… CONSOLE_TLS_KEY=… ./deploy.sh` | Helm install / upgrade + `helm test` |
| ops | `ENV=uat ./dr-drill.sh` | Quarterly HA/DR drill (IM-E08-BL-005) |
| M-06 | `ENV=prod ./lock-retention.sh` | Irreversible WORM lock of the archive buckets |

All scripts need `gcloud` logged in with an account that has Owner (bootstrap) or the CI deployer roles on the project.
