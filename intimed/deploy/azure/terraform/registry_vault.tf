# ---- Azure Container Registry (Premium: private endpoint, zone redundancy, content trust via cosign signatures)
resource "azurerm_container_registry" "acr" {
  name                          = "acr${local.compact}${random_string.suffix.result}"
  location                      = azurerm_resource_group.rg.location
  resource_group_name           = azurerm_resource_group.rg.name
  sku                           = "Premium"
  admin_enabled                 = false
  public_network_access_enabled = var.acr_public_network
  zone_redundancy_enabled       = true
  anonymous_pull_enabled        = false
  data_endpoint_enabled         = true
  tags                          = local.tags

  dynamic "network_rule_set" {
    for_each = var.acr_public_network && length(var.admin_source_cidrs) > 0 ? [1] : []
    content {
      default_action = "Deny"
      dynamic "ip_rule" {
        for_each = var.admin_source_cidrs
        content {
          action   = "Allow"
          ip_range = ip_rule.value
        }
      }
    }
  }
}

resource "azurerm_private_endpoint" "acr" {
  name                = "pe-acr-${local.base}"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  subnet_id           = azurerm_subnet.endpoints.id
  tags                = local.tags
  private_service_connection {
    name                           = "acr"
    private_connection_resource_id = azurerm_container_registry.acr.id
    subresource_names              = ["registry"]
    is_manual_connection           = false
  }
  private_dns_zone_group {
    name                 = "acr"
    private_dns_zone_ids = [azurerm_private_dns_zone.zones["acr"].id]
  }
}

# ---- Key Vault Premium (HSM-backed keys): master key, DB passwords, tenant integration secrets, cosign signing key,
# Application Gateway certificate. RBAC authorisation, purge protection, private endpoint, deny-by-default firewall.
resource "azurerm_key_vault" "kv" {
  name                          = "kv-${local.compact}${random_string.suffix.result}"
  location                      = azurerm_resource_group.rg.location
  resource_group_name           = azurerm_resource_group.rg.name
  tenant_id                     = data.azurerm_client_config.current.tenant_id
  sku_name                      = "premium"
  rbac_authorization_enabled    = true
  purge_protection_enabled      = true
  soft_delete_retention_days    = 90
  public_network_access_enabled = length(var.admin_source_cidrs) > 0
  tags                          = local.tags

  network_acls {
    default_action = "Deny"
    bypass         = "AzureServices"
    ip_rules       = var.admin_source_cidrs
  }
}

resource "azurerm_role_assignment" "deployer_kv_admin" {
  scope                = azurerm_key_vault.kv.id
  role_definition_name = "Key Vault Administrator"
  principal_id         = data.azurerm_client_config.current.object_id
}

resource "azurerm_private_endpoint" "kv" {
  name                = "pe-kv-${local.base}"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  subnet_id           = azurerm_subnet.endpoints.id
  tags                = local.tags
  private_service_connection {
    name                           = "kv"
    private_connection_resource_id = azurerm_key_vault.kv.id
    subresource_names              = ["vault"]
    is_manual_connection           = false
  }
  private_dns_zone_group {
    name                 = "kv"
    private_dns_zone_ids = [azurerm_private_dns_zone.zones["kv"].id]
  }
}

# Image-signing key for cosign (azurekms://) – HSM-protected, never exported.
resource "azurerm_key_vault_key" "cosign" {
  name         = "cosign-signing"
  key_vault_id = azurerm_key_vault.kv.id
  key_type     = "EC-HSM"
  curve        = "P-256"
  key_opts     = ["sign", "verify"]
  depends_on   = [azurerm_role_assignment.deployer_kv_admin]
  rotation_policy {
    expire_after         = "P2Y"
    notify_before_expiry = "P30D"
  }
}

# ---- PostgreSQL admin password (bootstrap only – the app uses its own least-privilege role, see scripts/bootstrap-db.sh)
resource "random_password" "pg_admin" {
  length  = 32
  special = false
}

resource "azurerm_key_vault_secret" "pg_admin" {
  name            = "intimed-db-admin-password"
  value           = random_password.pg_admin.result
  key_vault_id    = azurerm_key_vault.kv.id
  content_type    = "text/plain"
  expiration_date = timeadd(timestamp(), "8760h")
  depends_on      = [azurerm_role_assignment.deployer_kv_admin]
  lifecycle {
    ignore_changes = [expiration_date]
  }
}
