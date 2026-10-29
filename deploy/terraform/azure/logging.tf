resource "azurerm_log_analytics_workspace" "main" {
  name                       = "log-${local.name}"
  location                   = azurerm_resource_group.main.location
  resource_group_name        = azurerm_resource_group.main.name
  sku                        = "PerGB2018"
  retention_in_days          = var.log_retention_days
  internet_ingestion_enabled = true
  internet_query_enabled     = true
  tags                       = local.tags
}

# Diagnostic settings: all audit-relevant logs to Log Analytics (SOC 2 CC7.2)
locals {
  diagnostic_targets = {
    aks      = azurerm_kubernetes_cluster.main.id
    keyvault = azurerm_key_vault.main.id
    postgres = azurerm_postgresql_flexible_server.main.id
    acr      = azurerm_container_registry.main.id
    appgw    = azurerm_application_gateway.main.id
  }
}

data "azurerm_monitor_diagnostic_categories" "targets" {
  for_each    = local.diagnostic_targets
  resource_id = each.value
}

resource "azurerm_monitor_diagnostic_setting" "targets" {
  for_each                       = local.diagnostic_targets
  name                           = "diag-to-law"
  target_resource_id             = each.value
  log_analytics_workspace_id     = azurerm_log_analytics_workspace.main.id
  log_analytics_destination_type = "Dedicated"

  dynamic "enabled_log" {
    # allLogs is a superset of audit; never send both
    for_each = contains(data.azurerm_monitor_diagnostic_categories.targets[each.key].log_category_groups, "allLogs") ? ["allLogs"] : data.azurerm_monitor_diagnostic_categories.targets[each.key].log_category_groups
    content {
      category_group = enabled_log.value
    }
  }

  dynamic "enabled_metric" {
    for_each = data.azurerm_monitor_diagnostic_categories.targets[each.key].metrics
    content {
      category = enabled_metric.value
    }
  }
}

# Microsoft Defender for Cloud plans (subscription scope)
resource "azurerm_security_center_subscription_pricing" "plans" {
  for_each      = var.enable_defender_plans ? toset(["Containers", "KeyVaults", "OpenSourceRelationalDatabases", "Arm"]) : toset([])
  tier          = "Standard"
  resource_type = each.value
}
