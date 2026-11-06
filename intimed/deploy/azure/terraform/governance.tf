# ---- Guardrails: data residency (Allowed locations), Defender plans, diagnostics to Log Analytics, cost budget.
data "azurerm_policy_definition" "allowed_locations" {
  display_name = "Allowed locations"
}

resource "azurerm_resource_group_policy_assignment" "residency" {
  name                 = "intimed-data-residency"
  resource_group_id    = azurerm_resource_group.rg.id
  policy_definition_id = data.azurerm_policy_definition.allowed_locations.id
  description          = "IntiMed health data stays in Qatar Central (PDPPL Law No. 13 of 2016 / MoPH)."
  parameters           = jsonencode({ listOfAllowedLocations = { value = ["qatarcentral", "global"] } })
}

resource "azurerm_security_center_subscription_pricing" "plans" {
  for_each      = var.enable_defender_plans ? toset(["Containers", "KeyVaults", "StorageAccounts", "OpenSourceRelationalDatabases", "Arm"]) : toset([])
  tier          = "Standard"
  resource_type = each.value
}

locals {
  diag_targets = merge({
    kv      = azurerm_key_vault.kv.id
    pg      = azurerm_postgresql_flexible_server.pg.id
    acr     = azurerm_container_registry.acr.id
    aks     = azurerm_kubernetes_cluster.aks.id
    archive = "${azurerm_storage_account.archive.id}/blobServices/default"
    }, var.enable_app_gateway ? { agw = azurerm_application_gateway.agw[0].id } : {},
  var.enable_firewall ? { fw = azurerm_firewall.fw[0].id } : {})
}

data "azurerm_monitor_diagnostic_categories" "cats" {
  for_each    = local.diag_targets
  resource_id = each.value
}

resource "azurerm_monitor_diagnostic_setting" "diag" {
  for_each                   = local.diag_targets
  name                       = "to-log-analytics"
  target_resource_id         = each.value
  log_analytics_workspace_id = azurerm_log_analytics_workspace.law.id

  dynamic "enabled_log" {
    for_each = data.azurerm_monitor_diagnostic_categories.cats[each.key].log_category_types
    content {
      category = enabled_log.value
    }
  }
  enabled_metric {
    category = "AllMetrics"
  }
}

resource "azurerm_consumption_budget_resource_group" "budget" {
  count             = length(var.alert_emails) > 0 ? 1 : 0
  name              = "budget-${local.base}"
  resource_group_id = azurerm_resource_group.rg.id
  amount            = var.monthly_budget_usd
  time_grain        = "Monthly"
  time_period {
    start_date = var.budget_start_date
  }
  notification {
    enabled        = true
    threshold      = 80
    operator       = "GreaterThan"
    threshold_type = "Forecasted"
    contact_emails = var.alert_emails
  }
  notification {
    enabled        = true
    threshold      = 100
    operator       = "GreaterThan"
    contact_emails = var.alert_emails
  }
  lifecycle {
    ignore_changes = [time_period]
  }
}
