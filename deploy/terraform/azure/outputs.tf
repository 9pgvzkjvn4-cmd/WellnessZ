output "resource_group_name" {
  value = azurerm_resource_group.main.name
}

output "aks_cluster_name" {
  value = azurerm_kubernetes_cluster.main.name
}

output "aks_oidc_issuer_url" {
  value = azurerm_kubernetes_cluster.main.oidc_issuer_url
}

output "acr_login_server" {
  description = "global.imageRegistry = <this>/wellnessz"
  value       = azurerm_container_registry.main.login_server
}

output "key_vault_name" {
  description = "secrets.azure.keyVaultName"
  value       = azurerm_key_vault.main.name
}

output "tenant_id" {
  value = data.azurerm_client_config.current.tenant_id
}

output "core_identity_client_id" {
  description = "workloadIdentity.azure.clientIds.core"
  value       = azurerm_user_assigned_identity.workload["core"].client_id
}

output "bff_identity_client_id" {
  description = "workloadIdentity.azure.clientIds.bff"
  value       = azurerm_user_assigned_identity.workload["bff"].client_id
}

output "migrator_identity_client_id" {
  description = "workloadIdentity.azure.clientIds.migrations"
  value       = azurerm_user_assigned_identity.workload["migrations"].client_id
}

output "postgres_fqdn" {
  description = "Use in core.env.WZ_DB_URL (sslmode=verify-full)"
  value       = azurerm_postgresql_flexible_server.main.fqdn
}

output "application_gateway_public_ip" {
  value = azurerm_public_ip.appgw.ip_address
}

output "log_analytics_workspace_id" {
  value = azurerm_log_analytics_workspace.main.id
}

output "front_door_endpoint_host" {
  value = var.enable_front_door ? azurerm_cdn_frontdoor_endpoint.main[0].host_name : null
}

output "subnet_cidrs" {
  description = "Copy into values-azure.yaml networkPolicy (appgw -> ingressController.ipBlocks, postgres -> database.cidrs)"
  value       = var.subnet_cidrs
}

output "github_deployer_client_id" {
  description = "GitHub Environment variable AZURE_CLIENT_ID"
  value       = var.github_repository == "" ? null : azurerm_user_assigned_identity.github_deployer[0].client_id
}

output "environment" {
  value = var.environment
}

output "github_environments" {
  description = "GitHub Environments trusted by the deployer identity (configure AZURE_CLIENT_ID etc. there)"
  value       = var.github_repository == "" ? [] : local.github_environments
}
