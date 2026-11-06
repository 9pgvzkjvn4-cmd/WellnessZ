# ---- Application Gateway WAF v2 (zone-redundant) in front of the console; HTTPS end-to-end (re-encrypted to the
# platform on 9443, backend trusted through the platform's own installation root CA). Phase 2 of the setup.
resource "azurerm_public_ip" "appgw" {
  count               = var.enable_app_gateway ? 1 : 0
  name                = "pip-appgw-${local.base}"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  allocation_method   = "Static"
  sku                 = "Standard"
  zones               = var.zones
  tags                = local.tags
}

resource "azurerm_user_assigned_identity" "appgw" {
  count               = var.enable_app_gateway ? 1 : 0
  name                = "id-appgw-${local.base}"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  tags                = local.tags
}

resource "azurerm_role_assignment" "appgw_kv" {
  count                = var.enable_app_gateway ? 1 : 0
  scope                = azurerm_key_vault.kv.id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = azurerm_user_assigned_identity.appgw[0].principal_id
}

resource "azurerm_web_application_firewall_policy" "waf" {
  count               = var.enable_app_gateway ? 1 : 0
  name                = "waf-${local.base}"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  tags                = local.tags

  policy_settings {
    enabled                     = true
    mode                        = "Prevention"
    request_body_check          = true
    max_request_body_size_in_kb = 2000
    file_upload_limit_in_mb     = 12
  }
  managed_rules {
    managed_rule_set {
      type    = "Microsoft_DefaultRuleSet"
      version = "2.1"
    }
    managed_rule_set {
      type    = "Microsoft_BotManagerRuleSet"
      version = "1.0"
    }
  }
}

locals {
  agw = {
    gw_ip    = "gw-ip"
    fe_ip    = "fe-public"
    fe_443   = "fe-443"
    fe_80    = "fe-80"
    pool     = "pool-console"
    settings = "https-console"
    probe    = "probe-console"
    l_https  = "listener-https"
    l_http   = "listener-http"
    cert     = "cert-console"
    root     = "intimed-root-ca"
    redirect = "redirect-https"
  }
}

resource "azurerm_application_gateway" "agw" {
  count               = var.enable_app_gateway ? 1 : 0
  name                = "agw-${local.base}"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  zones               = var.zones
  firewall_policy_id  = azurerm_web_application_firewall_policy.waf[0].id
  http2_enabled       = true
  tags                = local.tags

  sku {
    name = "WAF_v2"
    tier = "WAF_v2"
  }
  autoscale_configuration {
    min_capacity = var.environment == "prod" ? 2 : 1
    max_capacity = 4
  }
  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.appgw[0].id]
  }
  ssl_policy {
    policy_type = "Predefined"
    policy_name = "AppGwSslPolicy20220101S"
  }
  gateway_ip_configuration {
    name      = local.agw.gw_ip
    subnet_id = azurerm_subnet.appgw.id
  }
  frontend_ip_configuration {
    name                 = local.agw.fe_ip
    public_ip_address_id = azurerm_public_ip.appgw[0].id
  }
  frontend_port {
    name = local.agw.fe_443
    port = 443
  }
  frontend_port {
    name = local.agw.fe_80
    port = 80
  }
  ssl_certificate {
    name                = local.agw.cert
    key_vault_secret_id = var.appgw_certificate_secret_id
  }
  trusted_root_certificate {
    name = local.agw.root
    data = base64encode(var.console_root_ca_pem)
  }
  backend_address_pool {
    name         = local.agw.pool
    ip_addresses = [var.console_lb_ip]
  }
  probe {
    name                = local.agw.probe
    protocol            = "Https"
    host                = "intimed-console.intimed.svc.cluster.local"
    path                = "/api/public/info"
    interval            = 30
    timeout             = 10
    unhealthy_threshold = 3
    match {
      status_code = ["200"]
    }
  }
  backend_http_settings {
    name                           = local.agw.settings
    protocol                       = "Https"
    port                           = 443
    cookie_based_affinity          = "Disabled"
    request_timeout                = 60
    host_name                      = "intimed-console.intimed.svc.cluster.local"
    probe_name                     = local.agw.probe
    trusted_root_certificate_names = [local.agw.root]
  }
  http_listener {
    name                           = local.agw.l_https
    frontend_ip_configuration_name = local.agw.fe_ip
    frontend_port_name             = local.agw.fe_443
    protocol                       = "Https"
    ssl_certificate_name           = local.agw.cert
    host_name                      = var.console_hostname
  }
  http_listener {
    name                           = local.agw.l_http
    frontend_ip_configuration_name = local.agw.fe_ip
    frontend_port_name             = local.agw.fe_80
    protocol                       = "Http"
    host_name                      = var.console_hostname
  }
  redirect_configuration {
    name                 = local.agw.redirect
    redirect_type        = "Permanent"
    target_listener_name = local.agw.l_https
    include_path         = true
    include_query_string = true
  }
  request_routing_rule {
    name                       = "rule-https"
    priority                   = 100
    rule_type                  = "Basic"
    http_listener_name         = local.agw.l_https
    backend_address_pool_name  = local.agw.pool
    backend_http_settings_name = local.agw.settings
  }
  request_routing_rule {
    name                        = "rule-http-redirect"
    priority                    = 110
    rule_type                   = "Basic"
    http_listener_name          = local.agw.l_http
    redirect_configuration_name = local.agw.redirect
  }

  lifecycle {
    precondition {
      condition     = var.console_root_ca_pem != "" && var.appgw_certificate_secret_id != "" && var.console_hostname != ""
      error_message = "enable_app_gateway needs console_hostname, appgw_certificate_secret_id and console_root_ca_pem (setup guide §8)."
    }
  }
  depends_on = [azurerm_role_assignment.appgw_kv]
}

# ---- Azure Firewall (production): FQDN allow-list for all cluster egress (AKS required endpoints + feeds + Entra).
resource "azurerm_public_ip" "fw" {
  count               = var.enable_firewall ? 1 : 0
  name                = "pip-fw-${local.base}"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  allocation_method   = "Static"
  sku                 = "Standard"
  zones               = var.zones
  tags                = local.tags
}

resource "azurerm_firewall_policy" "fw" {
  count                    = var.enable_firewall ? 1 : 0
  name                     = "afwp-${local.base}"
  location                 = azurerm_resource_group.rg.location
  resource_group_name      = azurerm_resource_group.rg.name
  sku                      = "Standard"
  threat_intelligence_mode = "Deny"
  tags                     = local.tags
  dns {
    proxy_enabled = true
  }
}

resource "azurerm_firewall_policy_rule_collection_group" "fw" {
  count              = var.enable_firewall ? 1 : 0
  name               = "intimed-egress"
  firewall_policy_id = azurerm_firewall_policy.fw[0].id
  priority           = 200

  application_rule_collection {
    name     = "aks-required"
    priority = 100
    action   = "Allow"
    rule {
      name                  = "aks-fqdn-tag"
      source_addresses      = [var.subnets.aks]
      destination_fqdn_tags = ["AzureKubernetesService"]
      protocols {
        type = "Https"
        port = 443
      }
    }
  }
  application_rule_collection {
    name     = "intimed-allowed"
    priority = 200
    action   = "Allow"
    rule {
      name              = "feeds-identity"
      source_addresses  = [var.subnets.aks]
      destination_fqdns = var.firewall_allowed_fqdns
      protocols {
        type = "Https"
        port = 443
      }
    }
  }
  network_rule_collection {
    name     = "time"
    priority = 300
    action   = "Allow"
    rule {
      name                  = "ntp"
      protocols             = ["UDP"]
      source_addresses      = [var.subnets.aks]
      destination_addresses = ["*"]
      destination_ports     = ["123"]
    }
  }
}

resource "azurerm_firewall" "fw" {
  count               = var.enable_firewall ? 1 : 0
  name                = "afw-${local.base}"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  sku_name            = "AZFW_VNet"
  sku_tier            = "Standard"
  firewall_policy_id  = azurerm_firewall_policy.fw[0].id
  zones               = var.zones
  tags                = local.tags
  ip_configuration {
    name                 = "fw"
    subnet_id            = azurerm_subnet.firewall[0].id
    public_ip_address_id = azurerm_public_ip.fw[0].id
  }
}

# ---- Site-to-site VPN to hospitals / clinics (device traffic never crosses the internet unencrypted or unfiltered).
resource "azurerm_public_ip" "vpn" {
  count               = var.enable_vpn ? 1 : 0
  name                = "pip-vpn-${local.base}"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  allocation_method   = "Static"
  sku                 = "Standard"
  zones               = var.zones
  tags                = local.tags
}

resource "azurerm_virtual_network_gateway" "vpn" {
  count               = var.enable_vpn ? 1 : 0
  name                = "vgw-${local.base}"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  type                = "Vpn"
  vpn_type            = "RouteBased"
  sku                 = "VpnGw1AZ"
  generation          = "Generation1"
  tags                = local.tags
  ip_configuration {
    name                 = "vpn"
    public_ip_address_id = azurerm_public_ip.vpn[0].id
    subnet_id            = azurerm_subnet.gateway[0].id
  }
}

resource "azurerm_local_network_gateway" "site" {
  for_each            = var.enable_vpn ? var.vpn_sites : {}
  name                = "lgw-${each.key}-${local.base}"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  gateway_address     = each.value.gateway_ip
  address_space       = each.value.address_prefixes
  tags                = local.tags
}

resource "azurerm_virtual_network_gateway_connection" "site" {
  for_each                   = var.enable_vpn ? var.vpn_sites : {}
  name                       = "cn-${each.key}-${local.base}"
  location                   = azurerm_resource_group.rg.location
  resource_group_name        = azurerm_resource_group.rg.name
  type                       = "IPsec"
  virtual_network_gateway_id = azurerm_virtual_network_gateway.vpn[0].id
  local_network_gateway_id   = azurerm_local_network_gateway.site[each.key].id
  shared_key                 = var.vpn_shared_keys[each.key]
  tags                       = local.tags
  ipsec_policy {
    ike_encryption   = "GCMAES256"
    ike_integrity    = "SHA384"
    dh_group         = "ECP384"
    ipsec_encryption = "GCMAES256"
    ipsec_integrity  = "GCMAES256"
    pfs_group        = "ECP384"
    sa_lifetime      = 27000
  }
}
