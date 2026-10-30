# =============================================================================
# WellnessZ on Azure - PROD
#   terraform init -reconfigure -backend-config=environments/backend-prod.hcl
#   terraform plan -var-file=environments/prod.tfvars -var-file=environments/prod.local.tfvars -out prod.tfplan
# Zone-redundant everything, PostgreSQL zone-redundant HA + geo-redundant
# backups + 35-day PITR, private cluster and private endpoints, WAF Prevention,
# resource lock, 1-year log retention (SOC 2 evidence), Flyway pre-upgrade job.
# =============================================================================
environment = "prod"
location    = "westeurope"
name_prefix = "wz"

availability_zones    = ["1", "2", "3"]
resource_lock_enabled = true

# Keep in sync with deploy/helm/wellnessz/environments/azure/prod.yaml networkPolicy.
vnet_address_space = ["10.40.0.0/16"]
subnet_cidrs = {
  aks               = "10.40.0.0/23"
  appgw             = "10.40.2.0/24"
  postgres          = "10.40.3.0/24"
  private_endpoints = "10.40.4.0/24"
}
aks_service_cidr   = "10.41.0.0/16"
aks_dns_service_ip = "10.41.0.10"

aks_private_cluster     = true
aks_sku_tier            = "Standard"
aks_system_node_vm_size = "Standard_D4ds_v5"
aks_system_node_min     = 3
aks_system_node_max     = 5
aks_user_node_vm_size   = "Standard_D4ds_v5"
aks_user_node_min       = 3
aks_user_node_max       = 9
migrations_mode         = "job"

postgres_sku                   = "GP_Standard_D4ds_v5"
postgres_storage_mb            = 131072
postgres_ha_enabled            = true
postgres_geo_redundant_backup  = true # immutable after creation
postgres_backup_retention_days = 35
postgres_cmk_enabled           = false # set true + geo-backup key/identity for CMK

waf_mode           = "Prevention"
appgw_min_capacity = 2
appgw_max_capacity = 10
enable_front_door  = false

acr_public_network_access  = true # promotion copies images in from the azure-prod deploy job
key_vault_soft_delete_days = 90
log_retention_days         = 365
enable_defender_plans      = true

github_environments           = ["azure-prod"]
github_image_publish_branches = []

tags = {
  cost-center = "occupational-health"
  owner       = "platform-team@example.com"
  data        = "phi-production"
}
