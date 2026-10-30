# =============================================================================
# WellnessZ on GCP - UAT (production-like, smaller; own project)
#   terraform init -reconfigure -backend-config=environments/backend-uat.hcl
#   terraform plan -var-file=environments/uat.tfvars -var-file=environments/uat.local.tfvars
# Regional (HA) Cloud SQL with PITR, Cloud Armor in prevention, Binary
# Authorization enforced, deletion protection, Flyway job - same architecture
# as prod at reduced capacity and retention.
# =============================================================================
environment = "uat"
region      = "europe-west4"
name_prefix = "wellnessz"

deletion_protection = true

# Keep psa_range in sync with deploy/helm/wellnessz/environments/gcp/uat.yaml networkPolicy.database.cidrs.
subnet_cidr   = "10.56.0.0/20"
pods_cidr     = "10.76.0.0/14"
services_cidr = "10.56.16.0/20"
master_cidr   = "172.16.0.48/28"
psa_range = {
  address       = "10.56.64.0"
  prefix_length = 20
}

gke_autopilot        = true
gke_private_endpoint = false
gke_master_authorized_networks = [
  { cidr_block = "203.0.113.0/24", display_name = "corporate-egress" }, # replace
]
gke_release_channel       = "REGULAR"
binauthz_enforcement_mode = "ENFORCED_BLOCK_AND_AUDIT_LOG"
migrations_mode           = "job"

sql_tier                           = "db-custom-2-7680"
sql_edition                        = "ENTERPRISE"
sql_disk_size_gb                   = 50
sql_availability_type              = "REGIONAL"
sql_pitr_enabled                   = true
sql_transaction_log_retention_days = 7
sql_backup_retention               = 14

cloud_armor_preview             = false
cloud_armor_adaptive_protection = true

log_retention_days = 90

github_environments           = ["gcp-uat"]
github_image_publish_branches = []

labels = {
  cost-center = "occupational-health"
  data        = "synthetic-or-masked"
}
