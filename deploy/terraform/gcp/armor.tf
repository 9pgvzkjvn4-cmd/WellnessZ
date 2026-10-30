# ---------------------------------------------------------------------------
# Cloud Armor WAF policy attached to the GKE Ingress backends via
# BackendConfig (values-gcp.yaml gcp.backendConfig.securityPolicy).
# ---------------------------------------------------------------------------
locals {
  # Preconfigured WAF rules (ModSecurity CRS 3.3 based)
  waf_rules = {
    1000 = "evaluatePreconfiguredWaf('sqli-v33-stable', {'sensitivity': 2})"
    1001 = "evaluatePreconfiguredWaf('xss-v33-stable', {'sensitivity': 2})"
    1002 = "evaluatePreconfiguredWaf('lfi-v33-stable', {'sensitivity': 1})"
    1003 = "evaluatePreconfiguredWaf('rfi-v33-stable', {'sensitivity': 1})"
    1004 = "evaluatePreconfiguredWaf('rce-v33-stable', {'sensitivity': 1})"
    1005 = "evaluatePreconfiguredWaf('methodenforcement-v33-stable', {'sensitivity': 1})"
    1006 = "evaluatePreconfiguredWaf('scannerdetection-v33-stable', {'sensitivity': 1})"
    1007 = "evaluatePreconfiguredWaf('protocolattack-v33-stable', {'sensitivity': 1})"
    1008 = "evaluatePreconfiguredWaf('sessionfixation-v33-stable', {'sensitivity': 1})"
    1009 = "evaluatePreconfiguredWaf('php-v33-stable', {'sensitivity': 1})"
    1010 = "evaluatePreconfiguredWaf('java-v33-stable', {'sensitivity': 1})"
    1011 = "evaluatePreconfiguredWaf('cve-canary', {'sensitivity': 2})"
  }
}

resource "google_compute_security_policy" "waf" {
  name        = "wellnessz-waf"
  description = "WellnessZ ${var.environment} WAF: OWASP CRS 3.3 preconfigured rules, rate limiting, adaptive protection"
  type        = "CLOUD_ARMOR"

  advanced_options_config {
    json_parsing = "STANDARD"
    log_level    = "VERBOSE"
  }

  adaptive_protection_config {
    layer_7_ddos_defense_config {
      enable = var.cloud_armor_adaptive_protection
    }
  }

  dynamic "rule" {
    for_each = local.waf_rules
    content {
      action   = "deny(403)"
      priority = rule.key
      preview  = var.cloud_armor_preview # true = log-only (dev tuning); false = prevention
      match {
        expr {
          expression = rule.value
        }
      }
      description = "Preconfigured WAF rule"
    }
  }

  # Throttle authentication endpoints (credential stuffing)
  rule {
    action   = "rate_based_ban"
    priority = 2000
    match {
      expr {
        expression = "request.path.startsWith('/oauth2/') || request.path.startsWith('/login/')"
      }
    }
    rate_limit_options {
      conform_action   = "allow"
      exceed_action    = "deny(429)"
      enforce_on_key   = "IP"
      ban_duration_sec = 600
      rate_limit_threshold {
        count        = 100
        interval_sec = 60
      }
    }
    description = "Rate-limit login endpoints per client IP"
  }

  rule {
    action   = "allow"
    priority = 2147483647
    match {
      versioned_expr = "SRC_IPS_V1"
      config {
        src_ip_ranges = ["*"]
      }
    }
    description = "Default allow (after WAF rules)"
  }
}

resource "google_compute_ssl_policy" "modern" {
  name            = "wellnessz-modern-tls"
  profile         = "RESTRICTED"
  min_tls_version = "TLS_1_2"
}
