# =============================================================================
# WellnessZ on Azure - DEV
#   terraform init -reconfigure -backend-config=environments/backend-dev.hcl
#   terraform plan -var-file=environments/dev.tfvars -var-file=environments/dev.local.tfvars
# Sizing: small, single zone, no HA, short retention, WAF in Detection for
# rule tuning. Still private-by-default (PHI rules apply to test data too:
# use synthetic data only in dev).
# Tenant-specific identifiers (subscription_id, aks_admin_group_object_ids,
# postgres_entra_admin, github_repository) go into dev.local.tfvars (git-ignored).
# =============================================================================
environment = "dev"
location    = "westeurope"
name_prefix = "wz"

availability_zones    = ["1"]
resource_lock_enabled = false

# Network (non-overlapping per environment so all three can peer to a hub / on-prem).
# Keep in sync with deploy/helm/wellnessz/environments/azure/dev.yaml networkPolicy.
vnet_address_space = ["10.48.0.0/16"]
subnet_cidrs = {
  aks               = "10.48.0.0/23"
  appgw             = "10.48.2.0/24"
  postgres          = "10.48.3.0/24"
  private_endpoints = "10.48.4.0/24"
}
aks_service_cidr   = "10.49.0.0/16"
aks_dns_service_ip = "10.49.0.10"

# AKS: public API server restricted to corporate egress / runner IPs (no self-hosted runner needed)
aks_private_cluster      = false
aks_authorized_ip_ranges = ["203.0.113.0/24"] # replace: corporate egress + CI runner ranges
aks_sku_tier             = "Free"
aks_system_node_vm_size  = "Standard_D2ds_v5"
aks_system_node_min      = 1
aks_system_node_max      = 2
aks_user_node_vm_size    = "Standard_D4ds_v5"
aks_user_node_min        = 1
aks_user_node_max        = 3
migrations_mode          = "app"

# PostgreSQL: burstable, no HA, local backups only, 7-day PITR
postgres_sku                   = "B_Standard_B2ms"
postgres_storage_mb            = 32768
postgres_ha_enabled            = false
postgres_geo_redundant_backup  = false
postgres_backup_retention_days = 7
postgres_cmk_enabled           = false

# Edge
waf_mode           = "Detection"
appgw_min_capacity = 1
appgw_max_capacity = 2
enable_front_door  = false

# Registry / vault / logging
acr_public_network_access  = true # GitHub-hosted runners push branch builds here
key_vault_soft_delete_days = 7
log_retention_days         = 30
enable_defender_plans      = false # subscription-wide; enable once in the shared non-prod subscription

# CI: dev ACR receives branch builds from container.yml (main) and deploys from GitHub Environment azure-dev
github_environments           = ["azure-dev"]
github_image_publish_branches = ["main"]

tags = {
  cost-center = "occupational-health"
  owner       = "platform-team@example.com"
  data        = "synthetic-only"
}
