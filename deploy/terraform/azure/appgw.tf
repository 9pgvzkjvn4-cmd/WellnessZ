# ---------------------------------------------------------------------------
# Application Gateway WAF v2 - managed by the AGIC add-on after creation.
# Terraform owns the SKU, WAF policy, TLS policy, identity and IP; AGIC owns
# listeners/pools/rules (ignored below).
# ---------------------------------------------------------------------------
resource "azurerm_public_ip" "appgw" {
  name                 = "pip-${local.name}-appgw"
  location             = azurerm_resource_group.main.location
  resource_group_name  = azurerm_resource_group.main.name
  allocation_method    = "Static"
  sku                  = "Standard"
  zones                = var.availability_zones
  domain_name_label    = "${local.name}-${random_string.suffix.result}"
  ddos_protection_mode = "VirtualNetworkInherited"
  tags                 = local.tags
}

resource "azurerm_web_application_firewall_policy" "main" {
  name                = "wafp-${local.name}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = local.tags

  policy_settings {
    enabled                          = true
    mode                             = var.waf_mode
    request_body_check               = true
    request_body_enforcement         = true
    max_request_body_size_in_kb      = 2000
    file_upload_limit_in_mb          = 20 # EHS Excel import
    request_body_inspect_limit_in_kb = 128
  }

  managed_rules {
    managed_rule_set {
      type    = var.waf_rule_set.type
      version = var.waf_rule_set.version
    }
    managed_rule_set {
      type    = "Microsoft_BotManagerRuleSet"
      version = "1.1"
    }
  }

  # Example rate limit on authentication endpoints (credential stuffing)
  custom_rules {
    name                 = "RateLimitLogin"
    priority             = 10
    rule_type            = "RateLimitRule"
    action               = "Block"
    rate_limit_duration  = "OneMin"
    rate_limit_threshold = 100
    group_rate_limit_by  = "ClientAddr"
    match_conditions {
      match_variables {
        variable_name = "RequestUri"
      }
      operator     = "BeginsWith"
      match_values = ["/oauth2/", "/login/"]
      transforms   = ["Lowercase"]
    }
  }
}

locals {
  appgw = {
    gateway_ip_config  = "gwip"
    frontend_ip_public = "fe-public"
    frontend_port_http = "port-80"
    backend_pool       = "default-pool"
    backend_settings   = "default-settings"
    listener           = "default-listener"
    rule               = "default-rule"
  }
}

resource "azurerm_application_gateway" "main" {
  name                = "agw-${local.name}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  zones               = var.availability_zones
  firewall_policy_id  = azurerm_web_application_firewall_policy.main.id
  http2_enabled       = true
  tags                = local.tags

  sku {
    name = "WAF_v2"
    tier = "WAF_v2"
  }

  autoscale_configuration {
    min_capacity = var.appgw_min_capacity
    max_capacity = var.appgw_max_capacity
  }

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.appgw.id]
  }

  # TLS 1.2+ with strong ciphers only
  ssl_policy {
    policy_type = "Predefined"
    policy_name = "AppGwSslPolicy20220101S"
  }

  gateway_ip_configuration {
    name      = local.appgw.gateway_ip_config
    subnet_id = azurerm_subnet.appgw.id
  }

  frontend_ip_configuration {
    name                 = local.appgw.frontend_ip_public
    public_ip_address_id = azurerm_public_ip.appgw.id
  }

  frontend_port {
    name = local.appgw.frontend_port_http
    port = 80
  }

  # Bootstrap-only objects; AGIC replaces them from Ingress resources.
  backend_address_pool {
    name = local.appgw.backend_pool
  }

  backend_http_settings {
    name                  = local.appgw.backend_settings
    cookie_based_affinity = "Disabled"
    port                  = 80
    protocol              = "Http"
    request_timeout       = 60
  }

  http_listener {
    name                           = local.appgw.listener
    frontend_ip_configuration_name = local.appgw.frontend_ip_public
    frontend_port_name             = local.appgw.frontend_port_http
    protocol                       = "Http"
  }

  request_routing_rule {
    name                       = local.appgw.rule
    priority                   = 19500
    rule_type                  = "Basic"
    http_listener_name         = local.appgw.listener
    backend_address_pool_name  = local.appgw.backend_pool
    backend_http_settings_name = local.appgw.backend_settings
  }

  lifecycle {
    ignore_changes = [
      backend_address_pool,
      backend_http_settings,
      frontend_port,
      http_listener,
      probe,
      redirect_configuration,
      request_routing_rule,
      ssl_certificate,
      url_path_map,
      rewrite_rule_set,
      tags["managed-by-k8s-ingress"],
    ]
  }

  depends_on = [azurerm_subnet_network_security_group_association.appgw]
}
