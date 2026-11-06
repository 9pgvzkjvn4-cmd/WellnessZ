# ---- Azure Database for PostgreSQL Flexible Server 16: private access only, TLS enforced, PITR backups in-region
# (Qatar Central has no paired region → no geo-redundant backup; see docs/azure/Operations-and-DR.md).
resource "azurerm_private_dns_zone" "pg" {
  name                = "${local.base}.private.postgres.database.azure.com"
  resource_group_name = azurerm_resource_group.rg.name
  tags                = local.tags
}

resource "azurerm_private_dns_zone_virtual_network_link" "pg" {
  name                  = "link-pg"
  resource_group_name   = azurerm_resource_group.rg.name
  private_dns_zone_name = azurerm_private_dns_zone.pg.name
  virtual_network_id    = azurerm_virtual_network.vnet.id
}

resource "azurerm_postgresql_flexible_server" "pg" {
  name                          = "psql-${local.base}-${random_string.suffix.result}"
  location                      = azurerm_resource_group.rg.location
  resource_group_name           = azurerm_resource_group.rg.name
  version                       = "16"
  sku_name                      = var.postgres_sku
  storage_mb                    = var.postgres_storage_mb
  storage_tier                  = lookup({ 32768 = "P4", 65536 = "P6", 131072 = "P10", 262144 = "P15", 524288 = "P20", 1048576 = "P30" }, var.postgres_storage_mb, null)
  auto_grow_enabled             = true
  backup_retention_days         = var.postgres_backup_retention_days
  geo_redundant_backup_enabled  = false
  delegated_subnet_id           = azurerm_subnet.postgres.id
  private_dns_zone_id           = azurerm_private_dns_zone.pg.id
  public_network_access_enabled = false
  administrator_login           = "intimedadmin"
  administrator_password        = random_password.pg_admin.result
  zone                          = "1"
  tags                          = local.tags

  authentication {
    active_directory_auth_enabled = true
    password_auth_enabled         = true
    tenant_id                     = data.azurerm_client_config.current.tenant_id
  }

  dynamic "high_availability" {
    for_each = var.postgres_ha_mode == "Disabled" ? [] : [1]
    content {
      mode                      = var.postgres_ha_mode
      standby_availability_zone = var.postgres_ha_mode == "ZoneRedundant" ? "2" : "1"
    }
  }

  maintenance_window {
    day_of_week  = 5
    start_hour   = 0
    start_minute = 0
  }

  depends_on = [azurerm_private_dns_zone_virtual_network_link.pg]
  lifecycle {
    ignore_changes = [zone, high_availability[0].standby_availability_zone]
  }
}

resource "azurerm_postgresql_flexible_server_database" "intimed" {
  name      = "intimed"
  server_id = azurerm_postgresql_flexible_server.pg.id
  charset   = "UTF8"
  collation = "en_US.utf8"
}

locals {
  pg_parameters = {
    require_secure_transport     = "ON"
    ssl_min_protocol_version     = "TLSv1.2"
    log_connections              = "ON"
    log_disconnections           = "ON"
    "connection_throttle.enable" = "ON"
    log_min_duration_statement   = "2000"
  }
}

resource "azurerm_postgresql_flexible_server_configuration" "params" {
  for_each  = local.pg_parameters
  name      = each.key
  server_id = azurerm_postgresql_flexible_server.pg.id
  value     = each.value
}

# ---- Archive storage: sealed evidence packs and SIEM audit stream, write-once (WORM), zone-redundant, private only.
resource "azurerm_storage_account" "archive" {
  name                              = "st${local.compact}${random_string.suffix.result}"
  location                          = azurerm_resource_group.rg.location
  resource_group_name               = azurerm_resource_group.rg.name
  account_kind                      = "StorageV2"
  account_tier                      = "Standard"
  account_replication_type          = "ZRS"
  https_traffic_only_enabled        = true
  min_tls_version                   = "TLS1_2"
  shared_access_key_enabled         = false
  allow_nested_items_to_be_public   = false
  public_network_access_enabled     = false
  default_to_oauth_authentication   = true
  infrastructure_encryption_enabled = true
  tags                              = local.tags

  blob_properties {
    versioning_enabled = true
    delete_retention_policy {
      days = 30
    }
    container_delete_retention_policy {
      days = 30
    }
  }
}

resource "azurerm_storage_container" "archive" {
  for_each              = toset(["evidence", "audit"])
  name                  = each.value
  storage_account_id    = azurerm_storage_account.archive.id
  container_access_type = "private"
}

resource "azurerm_storage_container_immutability_policy" "archive" {
  for_each                              = azurerm_storage_container.archive
  storage_container_resource_manager_id = each.value.id
  immutability_period_in_days           = var.archive_retention_days
  protected_append_writes_all_enabled   = false
  locked                                = false # lock after acceptance testing (guide §9) – a locked policy cannot be shortened
}

resource "azurerm_private_endpoint" "blob" {
  name                = "pe-blob-${local.base}"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  subnet_id           = azurerm_subnet.endpoints.id
  tags                = local.tags
  private_service_connection {
    name                           = "blob"
    private_connection_resource_id = azurerm_storage_account.archive.id
    subresource_names              = ["blob"]
    is_manual_connection           = false
  }
  private_dns_zone_group {
    name                 = "blob"
    private_dns_zone_ids = [azurerm_private_dns_zone.zones["blob"].id]
  }
}
