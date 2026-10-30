# =============================================================================
# WellnessZ on GCP - PROD (own project)
#   terraform init -reconfigure -backend-config=environments/backend-prod.hcl
#   terraform plan -var-file=environments/prod.tfvars -var-file=environments/prod.local.tfvars -out prod.tfplan
# Regional HA Cloud SQL, 30 daily backups + 7-day PITR, CMEK everywhere,
# Cloud Armor prevention + adaptive protection, Binary Authorization enforced,
# deletion protection, 1-year log retention, Flyway pre-upgrade job.
# =============================================================================
environment = "prod"
region      = "europe-west4"
name_prefix = "wellnessz"

deletion_protection = true

# Keep psa_range in sync with deploy/helm/wellnessz/environments/gcp/prod.yaml networkPolicy.database.cidrs.
subnet_cidr   = "10.60.0.0/20"
pods_cidr     = "10.64.0.0/14"
services_cidr = "10.60.16.0/20"
master_cidr   = "172.16.0.32/28"
psa_range = {
  address       = "10.60.64.0"
  prefix_length = 20
}

gke_autopilot        = true
gke_private_endpoint = false # true requires CI inside the VPC / Connect Gateway
gke_master_authorized_networks = [
  { cidr_block = "203.0.113.0/24", display_name = "corporate-egress" }, # replace
]
gke_release_channel       = "REGULAR" # STABLE for a slower upgrade cadence
binauthz_enforcement_mode = "ENFORCED_BLOCK_AND_AUDIT_LOG"
migrations_mode           = "job"

sql_tier                           = "db-custom-4-16384"
sql_edition                        = "ENTERPRISE"
sql_disk_size_gb                   = 100
sql_availability_type              = "REGIONAL"
sql_pitr_enabled                   = true
sql_transaction_log_retention_days = 7
sql_backup_retention               = 30

cloud_armor_preview             = false
cloud_armor_adaptive_protection = true

log_retention_days = 365

github_environments           = ["gcp-prod"]
github_image_publish_branches = []

labels = {
  cost-center = "occupational-health"
  data        = "phi-production"
}
