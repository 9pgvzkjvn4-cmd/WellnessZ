# ---------------------------------------------------------------------------
# Optional: Azure Front Door Premium (global edge, WAF, DDoS) in front of the
# Application Gateway. When enabled the AppGW NSG only admits
# AzureFrontDoor.Backend; add an AppGW WAF custom rule matching the
# X-Azure-FDID header for full origin lock-down.
# ---------------------------------------------------------------------------
resource "azurerm_cdn_frontdoor_profile" "main" {
  count                    = var.enable_front_door ? 1 : 0
  name                     = "afd-${local.name}"
  resource_group_name      = azurerm_resource_group.main.name
  sku_name                 = "Premium_AzureFrontDoor"
  response_timeout_seconds = 60
  tags                     = local.tags
}

resource "azurerm_cdn_frontdoor_firewall_policy" "main" {
  count                      = var.enable_front_door ? 1 : 0
  name                       = "afdwaf${replace(local.name, "-", "")}"
  resource_group_name        = azurerm_resource_group.main.name
  sku_name                   = "Premium_AzureFrontDoor"
  enabled                    = true
  mode                       = var.waf_mode
  request_body_check_enabled = true
  tags                       = local.tags

  managed_rule {
    type    = "Microsoft_DefaultRuleSet"
    version = "2.1"
    action  = "Block"
  }

  managed_rule {
    type    = "Microsoft_BotManagerRuleSet"
    version = "1.1"
    action  = "Block"
  }
}

resource "azurerm_cdn_frontdoor_endpoint" "main" {
  count                    = var.enable_front_door ? 1 : 0
  name                     = "fde-${local.name}-${random_string.suffix.result}"
  cdn_frontdoor_profile_id = azurerm_cdn_frontdoor_profile.main[0].id
  tags                     = local.tags
}

resource "azurerm_cdn_frontdoor_origin_group" "appgw" {
  count                    = var.enable_front_door ? 1 : 0
  name                     = "appgw"
  cdn_frontdoor_profile_id = azurerm_cdn_frontdoor_profile.main[0].id
  session_affinity_enabled = false

  load_balancing {
    sample_size                 = 4
    successful_samples_required = 3
  }

  health_probe {
    protocol            = "Https"
    path                = "/healthz"
    request_type        = "HEAD"
    interval_in_seconds = 60
  }
}

resource "azurerm_cdn_frontdoor_origin" "appgw" {
  count                          = var.enable_front_door ? 1 : 0
  name                           = "appgw"
  cdn_frontdoor_origin_group_id  = azurerm_cdn_frontdoor_origin_group.appgw[0].id
  enabled                        = true
  host_name                      = coalesce(var.front_door_origin_host, azurerm_public_ip.appgw.fqdn)
  origin_host_header             = coalesce(var.front_door_origin_host, azurerm_public_ip.appgw.fqdn)
  certificate_name_check_enabled = true
  http_port                      = 80
  https_port                     = 443
  priority                       = 1
  weight                         = 1000
}

resource "azurerm_cdn_frontdoor_route" "default" {
  count                         = var.enable_front_door ? 1 : 0
  name                          = "default"
  cdn_frontdoor_endpoint_id     = azurerm_cdn_frontdoor_endpoint.main[0].id
  cdn_frontdoor_origin_group_id = azurerm_cdn_frontdoor_origin_group.appgw[0].id
  cdn_frontdoor_origin_ids      = [azurerm_cdn_frontdoor_origin.appgw[0].id]
  supported_protocols           = ["Http", "Https"]
  https_redirect_enabled        = true
  forwarding_protocol           = "HttpsOnly"
  patterns_to_match             = ["/*"]
  link_to_default_domain        = true
}

resource "azurerm_cdn_frontdoor_security_policy" "main" {
  count                    = var.enable_front_door ? 1 : 0
  name                     = "waf"
  cdn_frontdoor_profile_id = azurerm_cdn_frontdoor_profile.main[0].id

  security_policies {
    firewall {
      cdn_frontdoor_firewall_policy_id = azurerm_cdn_frontdoor_firewall_policy.main[0].id
      association {
        patterns_to_match = ["/*"]
        domain {
          cdn_frontdoor_domain_id = azurerm_cdn_frontdoor_endpoint.main[0].id
        }
      }
    }
  }
}

resource "azurerm_monitor_diagnostic_setting" "front_door" {
  count                      = var.enable_front_door ? 1 : 0
  name                       = "diag-to-law"
  target_resource_id         = azurerm_cdn_frontdoor_profile.main[0].id
  log_analytics_workspace_id = azurerm_log_analytics_workspace.main.id

  enabled_log {
    category_group = "allLogs"
  }
}
