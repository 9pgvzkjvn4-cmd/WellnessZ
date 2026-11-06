# ---- Cloud Monitoring: notification channels, SLO alert policies on Managed Service for Prometheus metrics (PromQL),
# log-based security alert, operations dashboard. (Runbooks RB-01…RB-20: IntiMed_GCP_SLO_and_Runbooks.)
resource "google_monitoring_notification_channel" "email" {
  for_each     = toset(var.alert_emails)
  display_name = "IntiMed ops ${each.value}"
  type         = "email"
  labels = {
    email_address = each.value
  }
}

locals {
  channels = [for c in google_monitoring_notification_channel.email : c.id]
  promql_alerts = {
    "IntiMedDown"            = { q = "absent(up{namespace=\"intimed\",pod=\"intimed-0\"} == 1)", d = "300s", sev = "CRITICAL", doc = "RB-01 platform pod not scraped for 5 minutes" }
    "IntiMedRestarting"      = { q = "increase(kube_pod_container_status_restarts_total{namespace=\"intimed\",pod=\"intimed-0\"}[30m]) > 2", d = "60s", sev = "ERROR", doc = "RB-02 restart loop" }
    "IntiMedHttp5xx"         = { q = "sum(rate(http_server_requests_seconds_count{namespace=\"intimed\",status=~\"5..\"}[5m])) / clamp_min(sum(rate(http_server_requests_seconds_count{namespace=\"intimed\"}[5m])), 1) > 0.02", d = "600s", sev = "ERROR", doc = "RB-03 5xx ratio above 2 %" }
    "IntiMedLatencyP95"      = { q = "histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{namespace=\"intimed\"}[5m])) by (le)) > 1", d = "900s", sev = "WARNING", doc = "RB-04 API p95 above 1 s" }
    "IntiMedHeapPressure"    = { q = "sum(jvm_memory_used_bytes{namespace=\"intimed\",area=\"heap\"}) / sum(jvm_memory_max_bytes{namespace=\"intimed\",area=\"heap\"}) > 0.9", d = "600s", sev = "ERROR", doc = "RB-05 JVM heap above 90 %" }
    "IntiMedDbPoolExhausted" = { q = "max(hikaricp_connections_pending{namespace=\"intimed\"}) > 5", d = "300s", sev = "ERROR", doc = "RB-06 database pool saturated" }
  }
}

resource "google_monitoring_alert_policy" "slo" {
  for_each              = local.promql_alerts
  display_name          = "${each.key} (${var.environment})"
  combiner              = "OR"
  severity              = each.value.sev
  notification_channels = local.channels
  documentation {
    content   = each.value.doc
    mime_type = "text/markdown"
  }
  conditions {
    display_name = each.key
    condition_prometheus_query_language {
      query               = each.value.q
      duration            = each.value.d
      evaluation_interval = "60s"
    }
  }
}

# Security: repeated login failures / refused device certificates / isolations from the audit-chain stream.
resource "google_logging_metric" "security_events" {
  name   = "intimed_security_events_${var.environment}"
  filter = "resource.type=\"k8s_container\" AND resource.labels.namespace_name=\"intimed\" AND textPayload:\"intimed.siem\" AND (textPayload:\"auth.failed\" OR textPayload:\"auth.locked\" OR textPayload:\"endpoint.refused\" OR textPayload:\"anomaly.isolated\")"
  metric_descriptor {
    metric_kind = "DELTA"
    value_type  = "INT64"
  }
}

resource "google_monitoring_alert_policy" "security" {
  display_name          = "IntiMed security events burst (${var.environment})"
  combiner              = "OR"
  severity              = "WARNING"
  notification_channels = local.channels
  documentation {
    content   = "RB-15 / RB-09 – ≥ 5 authentication failures, refused device certificates or isolations in 15 minutes. Triage in the IntiMed Security console and Cloud Logging (bucket intimed-${var.environment})."
    mime_type = "text/markdown"
  }
  conditions {
    display_name = "security events"
    condition_threshold {
      filter          = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.security_events.name}\" AND resource.type=\"k8s_container\""
      comparison      = "COMPARISON_GT"
      threshold_value = 4
      duration        = "0s"
      aggregations {
        alignment_period     = "900s"
        per_series_aligner   = "ALIGN_SUM"
        cross_series_reducer = "REDUCE_SUM"
      }
    }
  }
}

resource "google_monitoring_dashboard" "ops" {
  dashboard_json = jsonencode({
    displayName = "IntiMed operations – ${var.environment} (Doha)"
    mosaicLayout = {
      columns = 12
      tiles = [
        { width = 6, height = 4, widget = { title = "Requests per second by status", xyChart = { dataSets = [{ timeSeriesQuery = { prometheusQuery = "sum(rate(http_server_requests_seconds_count{namespace=\"intimed\"}[5m])) by (status)" }, plotType = "LINE" }] } } },
        { xPos = 6, width = 6, height = 4, widget = { title = "API latency p95 (s)", xyChart = { dataSets = [{ timeSeriesQuery = { prometheusQuery = "histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{namespace=\"intimed\"}[5m])) by (le))" }, plotType = "LINE" }] } } },
        { yPos = 4, width = 6, height = 4, widget = { title = "JVM heap used / max", xyChart = { dataSets = [{ timeSeriesQuery = { prometheusQuery = "sum(jvm_memory_used_bytes{namespace=\"intimed\",area=\"heap\"}) / sum(jvm_memory_max_bytes{namespace=\"intimed\",area=\"heap\"})" }, plotType = "LINE" }] } } },
        { xPos = 6, yPos = 4, width = 6, height = 4, widget = { title = "Database pool (active / pending)", xyChart = { dataSets = [{ timeSeriesQuery = { prometheusQuery = "max(hikaricp_connections_active{namespace=\"intimed\"})" }, plotType = "LINE" }, { timeSeriesQuery = { prometheusQuery = "max(hikaricp_connections_pending{namespace=\"intimed\"})" }, plotType = "LINE" }] } } },
        { yPos = 8, width = 12, height = 4, widget = { title = "Security events (audit stream)", xyChart = { dataSets = [{ timeSeriesQuery = { timeSeriesFilter = { filter = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.security_events.name}\"", aggregation = { alignmentPeriod = "300s", perSeriesAligner = "ALIGN_SUM" } } }, plotType = "STACKED_BAR" }] } } },
      ]
    }
  })
}

resource "google_billing_budget" "budget" {
  count           = var.billing_account != "" && length(var.alert_emails) > 0 ? 1 : 0
  billing_account = var.billing_account
  display_name    = "IntiMed ${var.environment}"
  budget_filter {
    projects = ["projects/${data.google_project.this.number}"]
  }
  amount {
    specified_amount {
      currency_code = "USD"
      units         = tostring(var.monthly_budget_usd)
    }
  }
  threshold_rules {
    threshold_percent = 0.8
    spend_basis       = "FORECASTED_SPEND"
  }
  threshold_rules {
    threshold_percent = 1.0
  }
  all_updates_rule {
    monitoring_notification_channels = local.channels
    disable_default_iam_recipients   = false
  }
}
