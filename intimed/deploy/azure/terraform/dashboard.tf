# Operations dashboard (Azure Monitor workbook – Azure Managed Grafana is not offered in Qatar Central): pod health,
# restarts, CPU/memory, platform audit activity (from the SIEM stream), refused devices and anomalies per tenant.
resource "random_uuid" "workbook" {}

resource "azurerm_application_insights_workbook" "ops" {
  name                = random_uuid.workbook.result
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  display_name        = "IntiMed operations – ${var.environment}"
  source_id           = lower(azurerm_log_analytics_workspace.law.id)
  category            = "workbook"
  tags                = local.tags
  data_json = jsonencode({
    version = "Notebook/1.0"
    items = [
      { type = 1, content = { json = "## IntiMed ${var.environment} – Qatar Central\nSLOs and runbooks: docs/azure/SLO-and-Runbooks.md" } },
      { type = 3, content = { version = "KqlItem/1.0", title = "Pod restarts and readiness (24 h)", visualization = "table",
        query = "KubePodInventory | where Namespace == 'intimed' | summarize restarts=max(ContainerRestartCount), lastStatus=arg_max(TimeGenerated, PodStatus) by Name | project Name, restarts, PodStatus=lastStatus",
      queryType = 0, resourceType = "microsoft.operationalinsights/workspaces", timeContext = { durationMs = 86400000 } } },
      { type = 3, content = { version = "KqlItem/1.0", title = "CPU and memory of intimed-0", visualization = "timechart",
        query = "Perf | where InstanceName has 'intimed-0' and CounterName in ('cpuUsageNanoCores','memoryWorkingSetBytes') | summarize avg(CounterValue) by CounterName, bin(TimeGenerated, 5m)",
      queryType = 0, resourceType = "microsoft.operationalinsights/workspaces", timeContext = { durationMs = 86400000 } } },
      { type = 3, content = { version = "KqlItem/1.0", title = "Audited actions per hour (SIEM stream)", visualization = "barchart",
        query = "ContainerLogV2 | where PodName startswith 'intimed-' and LogMessage has '\"hash\"' | extend a=parse_json(tostring(LogMessage)) | summarize n=count() by action=tostring(a.action), bin(TimeGenerated, 1h)",
      queryType = 0, resourceType = "microsoft.operationalinsights/workspaces", timeContext = { durationMs = 86400000 } } },
      { type = 3, content = { version = "KqlItem/1.0", title = "Security events – refused devices, anomalies, isolations", visualization = "table",
        query = "ContainerLogV2 | where PodName startswith 'intimed-' and LogMessage has '\"hash\"' | extend a=parse_json(tostring(LogMessage)) | where tostring(a.action) matches regex '^(anomaly|endpoint|auth)\\\\.' | project TimeGenerated, tenant=tostring(a.tenant), action=tostring(a.action), target=tostring(a.target) | order by TimeGenerated desc | take 200",
      queryType = 0, resourceType = "microsoft.operationalinsights/workspaces", timeContext = { durationMs = 604800000 } } }
    ]
  })
}
