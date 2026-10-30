# =============================================================================
# WellnessZ on GCP - DEV (one GCP project per environment)
#   terraform init -reconfigure -backend-config=environments/backend-dev.hcl
#   terraform plan -var-file=environments/dev.tfvars -var-file=environments/dev.local.tfvars
# Small, zonal Cloud SQL without HA, short retention, Cloud Armor rules in
# preview (log-only) for tuning, Binary Authorization dry-run, no deletion
# protection. Synthetic data only.
# project_id, sql_iam_admin_group and github_repository go into dev.local.tfvars.
# =============================================================================
environment = "dev"
region      = "europe-west4"
name_prefix = "wellnessz"

deletion_protection = false

# Network (non-overlapping per environment for hub / on-prem peering).
# Keep psa_range in sync with deploy/helm/wellnessz/environments/gcp/dev.yaml networkPolicy.database.cidrs.
subnet_cidr   = "10.68.0.0/20"
pods_cidr     = "10.72.0.0/14"
services_cidr = "10.68.16.0/20"
master_cidr   = "172.16.0.64/28"
psa_range = {
  address       = "10.68.64.0"
  prefix_length = 20
}

gke_autopilot        = true
gke_private_endpoint = false
gke_master_authorized_networks = [
  { cidr_block = "203.0.113.0/24", display_name = "corporate-egress" }, # replace
]
gke_release_channel       = "REGULAR"
binauthz_enforcement_mode = "DRYRUN_AUDIT_LOG_ONLY"
migrations_mode           = "app"

sql_tier                           = "db-custom-1-3840"
sql_edition                        = "ENTERPRISE"
sql_disk_size_gb                   = 20
sql_availability_type              = "ZONAL"
sql_pitr_enabled                   = true
sql_transaction_log_retention_days = 1
sql_backup_retention               = 7

cloud_armor_preview             = true
cloud_armor_adaptive_protection = false

log_retention_days = 30

github_environments           = ["gcp-dev"]
github_image_publish_branches = ["main"]

labels = {
  cost-center = "occupational-health"
  data        = "synthetic-only"
}
