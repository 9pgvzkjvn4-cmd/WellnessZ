# =============================================================================
# WellnessZ on Azure - UAT (production-like, smaller)
#   terraform init -reconfigure -backend-config=environments/backend-uat.hcl
#   terraform plan -var-file=environments/uat.tfvars -var-file=environments/uat.local.tfvars
# Same topology as prod (zone redundant, private cluster, private endpoints,
# WAF Prevention, Flyway job) at reduced capacity and retention, so release
# candidates are verified on the production architecture.
# =============================================================================
environment = "uat"
location    = "westeurope"
name_prefix = "wz"

availability_zones    = ["1", "2", "3"]
resource_lock_enabled = true

# Keep in sync with deploy/helm/wellnessz/environments/azure/uat.yaml networkPolicy.
vnet_address_space = ["10.44.0.0/16"]
subnet_cidrs = {
  aks               = "10.44.0.0/23"
  appgw             = "10.44.2.0/24"
  postgres          = "10.44.3.0/24"
  private_endpoints = "10.44.4.0/24"
}
aks_service_cidr   = "10.45.0.0/16"
aks_dns_service_ip = "10.45.0.10"

aks_private_cluster     = true # deploy via az aks command invoke or a self-hosted runner
aks_sku_tier            = "Standard"
aks_system_node_vm_size = "Standard_D2ds_v5"
aks_system_node_min     = 2
aks_system_node_max     = 3
aks_user_node_vm_size   = "Standard_D4ds_v5"
aks_user_node_min       = 2
aks_user_node_max       = 5
migrations_mode         = "job"

# PostgreSQL: general purpose, zone-redundant HA (exercise failover tests), 14-day PITR
postgres_sku                   = "GP_Standard_D2ds_v5"
postgres_storage_mb            = 65536
postgres_ha_enabled            = true
postgres_geo_redundant_backup  = false
postgres_backup_retention_days = 14
postgres_cmk_enabled           = false

waf_mode           = "Prevention"
appgw_min_capacity = 2
appgw_max_capacity = 4
enable_front_door  = false

# Images are promoted (digest-preserving copy) by the azure-uat deploy job, never pushed by builds
acr_public_network_access  = true
key_vault_soft_delete_days = 30
log_retention_days         = 90
enable_defender_plans      = true

github_environments           = ["azure-uat"]
github_image_publish_branches = []

tags = {
  cost-center = "occupational-health"
  owner       = "platform-team@example.com"
  data        = "synthetic-or-masked"
}
