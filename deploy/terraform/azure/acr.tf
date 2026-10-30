resource "azurerm_container_registry" "main" {
  #checkov:skip=CKV_AZURE_233:zone_redundancy_enabled = local.zone_redundant (true in uat/prod); dev single-zone by design
  #checkov:skip=CKV_AZURE_164:Docker Content Trust is deprecated; images are signed with cosign (keyless) and verified before deploy
  #checkov:skip=CKV_AZURE_166:no ACR quarantine scanner; Trivy gate in container.yml and signature verification in deploy-env.yml
  #checkov:skip=CKV_AZURE_139:GitHub-hosted runners push images; Entra-only auth, admin user disabled; private ACR is enhancement SEC-E04
  name                          = "acr${local.compact_name}"
  location                      = azurerm_resource_group.main.location
  resource_group_name           = azurerm_resource_group.main.name
  sku                           = "Premium"
  admin_enabled                 = false
  anonymous_pull_enabled        = false
  data_endpoint_enabled         = true
  zone_redundancy_enabled       = local.zone_redundant
  public_network_access_enabled = var.acr_public_network_access
  export_policy_enabled         = var.acr_public_network_access
  network_rule_bypass_option    = "AzureServices"
  retention_policy_in_days      = 30
  tags                          = local.tags

  # Optional geo-replicas (Premium) when the workload also runs in another
  # region; each replica is zone redundant when the environment spans zones.
  dynamic "georeplications" {
    for_each = var.acr_georeplication_locations
    content {
      location                  = georeplications.value
      zone_redundancy_enabled   = local.zone_redundant
      regional_endpoint_enabled = true
      tags                      = local.tags
    }
  }

  dynamic "network_rule_set" {
    for_each = var.acr_public_network_access && length(var.acr_allowed_ips) > 0 ? [1] : []
    content {
      default_action = "Deny"
      dynamic "ip_rule" {
        for_each = var.acr_allowed_ips
        content {
          action   = "Allow"
          ip_range = ip_rule.value
        }
      }
    }
  }
}

resource "azurerm_private_endpoint" "acr" {
  name                = "pe-${local.name}-acr"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  subnet_id           = azurerm_subnet.private_endpoints.id
  tags                = local.tags

  private_service_connection {
    name                           = "acr"
    private_connection_resource_id = azurerm_container_registry.main.id
    subresource_names              = ["registry"]
    is_manual_connection           = false
  }

  private_dns_zone_group {
    name                 = "acr"
    private_dns_zone_ids = [azurerm_private_dns_zone.zones["acr"].id]
  }
}
