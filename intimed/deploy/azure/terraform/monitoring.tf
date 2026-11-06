# Log Analytics (Container Insights, diagnostics, SIEM audit stream from stdout) + Microsoft Sentinel + Azure Monitor
# workspace (Managed Prometheus) with SLO alert rules (IM-E01-BL-027, IM-E08-BL-007).
resource "azurerm_log_analytics_workspace" "law" {
  name                = "log-${local.base}"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  sku                 = "PerGB2018"
  retention_in_days   = var.log_retention_days
  tags                = local.tags
}

resource "azurerm_sentinel_log_analytics_workspace_onboarding" "sentinel" {
  count        = var.enable_sentinel ? 1 : 0
  workspace_id = azurerm_log_analytics_workspace.law.id
}

resource "azurerm_monitor_workspace" "prom" {
  name                = "amw-${local.base}"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  tags                = local.tags
}

resource "azurerm_monitor_data_collection_endpoint" "prom" {
  name                = "dce-${local.base}"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  kind                = "Linux"
  tags                = local.tags
}

resource "azurerm_monitor_data_collection_rule" "prom" {
  name                        = "dcr-prom-${local.base}"
  location                    = azurerm_resource_group.rg.location
  resource_group_name         = azurerm_resource_group.rg.name
  data_collection_endpoint_id = azurerm_monitor_data_collection_endpoint.prom.id
  kind                        = "Linux"
  tags                        = local.tags

  destinations {
    monitor_account {
      monitor_account_id = azurerm_monitor_workspace.prom.id
      name               = "MonitoringAccount1"
    }
  }
  data_flow {
    streams      = ["Microsoft-PrometheusMetrics"]
    destinations = ["MonitoringAccount1"]
  }
  data_sources {
    prometheus_forwarder {
      streams = ["Microsoft-PrometheusMetrics"]
      name    = "PrometheusDataSource"
    }
  }
}

resource "azurerm_monitor_data_collection_rule_association" "prom" {
  name                    = "dcra-prom-${local.base}"
  target_resource_id      = azurerm_kubernetes_cluster.aks.id
  data_collection_rule_id = azurerm_monitor_data_collection_rule.prom.id
}

resource "azurerm_monitor_action_group" "ops" {
  name                = "ag-${local.base}"
  resource_group_name = azurerm_resource_group.rg.name
  short_name          = "intimed"
  tags                = local.tags

  dynamic "email_receiver" {
    for_each = var.alert_emails
    content {
      name                    = "ops-${email_receiver.key}"
      email_address           = email_receiver.value
      use_common_alert_schema = true
    }
  }
}

# SLO alert rules – see docs/azure/SLO-and-Runbooks.md. Metric names are Micrometer/Prometheus names exported by
# the platform on /actuator/prometheus (scraped through pod annotations by ama-metrics).
resource "azurerm_monitor_alert_prometheus_rule_group" "slo" {
  name                = "prom-slo-${local.base}"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  cluster_name        = azurerm_kubernetes_cluster.aks.name
  scopes              = [azurerm_monitor_workspace.prom.id, azurerm_kubernetes_cluster.aks.id]
  rule_group_enabled  = true
  interval            = "PT1M"
  tags                = local.tags

  rule {
    alert       = "IntiMedDown"
    enabled     = true
    expression  = "absent(up{job=~\".*\",pod=~\"intimed-0\"} == 1)"
    for         = "PT5M"
    severity    = 1
    annotations = { summary = "IntiMed pod not scraped for 5 minutes (RB-01)" }
    action { action_group_id = azurerm_monitor_action_group.ops.id }
  }
  rule {
    alert       = "IntiMedRestarting"
    enabled     = true
    expression  = "increase(kube_pod_container_status_restarts_total{pod=\"intimed-0\"}[30m]) > 2"
    for         = "PT1M"
    severity    = 2
    annotations = { summary = "IntiMed restarted more than twice in 30 minutes (RB-02)" }
    action { action_group_id = azurerm_monitor_action_group.ops.id }
  }
  rule {
    alert       = "IntiMedHttp5xx"
    enabled     = true
    expression  = "sum(rate(http_server_requests_seconds_count{pod=\"intimed-0\",status=~\"5..\"}[5m])) / clamp_min(sum(rate(http_server_requests_seconds_count{pod=\"intimed-0\"}[5m])), 1) > 0.02"
    for         = "PT10M"
    severity    = 2
    annotations = { summary = "Console/API 5xx ratio above 2 % (RB-03)" }
    action { action_group_id = azurerm_monitor_action_group.ops.id }
  }
  rule {
    alert       = "IntiMedLatencyP95"
    enabled     = true
    expression  = "histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{pod=\"intimed-0\"}[5m])) by (le)) > 1"
    for         = "PT15M"
    severity    = 3
    annotations = { summary = "API p95 latency above 1 s (RB-04)" }
    action { action_group_id = azurerm_monitor_action_group.ops.id }
  }
  rule {
    alert       = "IntiMedHeapPressure"
    enabled     = true
    expression  = "sum(jvm_memory_used_bytes{pod=\"intimed-0\",area=\"heap\"}) / sum(jvm_memory_max_bytes{pod=\"intimed-0\",area=\"heap\"}) > 0.9"
    for         = "PT10M"
    severity    = 2
    annotations = { summary = "JVM heap above 90 % (RB-05)" }
    action { action_group_id = azurerm_monitor_action_group.ops.id }
  }
  rule {
    alert       = "IntiMedDbPoolExhausted"
    enabled     = true
    expression  = "max(hikaricp_connections_pending{pod=\"intimed-0\"}) > 5"
    for         = "PT5M"
    severity    = 2
    annotations = { summary = "Database connection pool saturated (RB-06)" }
    action { action_group_id = azurerm_monitor_action_group.ops.id }
  }
}

# Audit-chain records arrive as JSON lines from the "intimed.siem" logger in ContainerLogV2; this Sentinel rule raises
# an incident for repeated authentication failures and refused device certificates (detections DR-01…DR-03 in the guide).
resource "azurerm_sentinel_alert_rule_scheduled" "auth_failures" {
  count                      = var.enable_sentinel ? 1 : 0
  name                       = "intimed-auth-failures"
  log_analytics_workspace_id = azurerm_sentinel_log_analytics_workspace_onboarding.sentinel[0].workspace_id
  display_name               = "IntiMed – repeated login / device-certificate failures"
  severity                   = "Medium"
  query                      = <<-KQL
    ContainerLogV2
    | where PodName startswith "intimed-" and LogMessage has "\"action\""
    | extend a = parse_json(tostring(LogMessage))
    | where tostring(a.action) in ("auth.failed", "auth.locked", "endpoint.refused", "anomaly.isolated")
    | summarize n = count() by tostring(a.tenant), tostring(a.action), bin(TimeGenerated, 15m)
    | where n >= 5
  KQL
  query_frequency            = "PT15M"
  query_period               = "PT15M"
  trigger_operator           = "GreaterThan"
  trigger_threshold          = 0
  tactics                    = ["CredentialAccess", "InitialAccess"]
}
