resource "azurerm_postgresql_flexible_server" "main" {
  name                = "psql-${local.name}-${random_string.suffix.result}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  version             = "16"
  sku_name            = var.postgres_sku
  storage_mb          = var.postgres_storage_mb
  auto_grow_enabled   = true
  zone                = var.availability_zones[0]
  tags                = local.tags

  # Private access only (VNet injection)
  delegated_subnet_id           = azurerm_subnet.postgres.id
  private_dns_zone_id           = azurerm_private_dns_zone.zones["postgres"].id
  public_network_access_enabled = false

  # Break-glass admin; password never stored in state (write-only)
  administrator_login               = var.postgres_admin_login
  administrator_password_wo         = ephemeral.random_password.pg_admin.result
  administrator_password_wo_version = var.db_password_version

  authentication {
    active_directory_auth_enabled = true
    password_auth_enabled         = true # wz_app / wz_migrator; switch to Entra tokens when the app supports it
    tenant_id                     = data.azurerm_client_config.current.tenant_id
  }

  backup_retention_days        = var.postgres_backup_retention_days
  geo_redundant_backup_enabled = var.postgres_geo_redundant_backup

  dynamic "high_availability" {
    for_each = var.postgres_ha_enabled ? [1] : []
    content {
      # Zone-redundant standby when the environment spans zones, otherwise same-zone HA
      mode                      = local.zone_redundant ? "ZoneRedundant" : "SameZone"
      standby_availability_zone = local.zone_redundant ? var.availability_zones[1] : var.availability_zones[0]
    }
  }

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.postgres.id]
  }

  dynamic "customer_managed_key" {
    for_each = var.postgres_cmk_enabled ? [1] : []
    content {
      key_vault_key_id                     = azurerm_key_vault_key.postgres[0].versionless_id
      primary_user_assigned_identity_id    = azurerm_user_assigned_identity.postgres.id
      geo_backup_key_vault_key_id          = var.postgres_geo_redundant_backup ? var.postgres_geo_backup_cmk_key_id : null
      geo_backup_user_assigned_identity_id = var.postgres_geo_redundant_backup ? var.postgres_geo_backup_identity_id : null
    }
  }

  maintenance_window {
    day_of_week  = 0
    start_hour   = 2
    start_minute = 0
  }

  depends_on = [
    azurerm_private_dns_zone_virtual_network_link.links,
    azurerm_role_assignment.postgres_cmk,
  ]

  lifecycle {
    prevent_destroy = true
    ignore_changes  = [zone, high_availability[0].standby_availability_zone]
  }
}

resource "azurerm_postgresql_flexible_server_active_directory_administrator" "main" {
  server_name         = azurerm_postgresql_flexible_server.main.name
  resource_group_name = azurerm_resource_group.main.name
  tenant_id           = data.azurerm_client_config.current.tenant_id
  object_id           = var.postgres_entra_admin.object_id
  principal_name      = var.postgres_entra_admin.principal_name
  principal_type      = "Group"
}

resource "azurerm_postgresql_flexible_server_database" "wellnessz" {
  name      = "wellnessz"
  server_id = azurerm_postgresql_flexible_server.main.id
  charset   = "UTF8"
  collation = "en_US.utf8"

  lifecycle {
    prevent_destroy = true
  }
}

# Server parameters: encryption in transit + audit logging.
# log_min_duration_statement = -1: statement text is never logged by duration -
# logged statements (and their bind parameters) can carry special-category
# health data. Slow-query analysis uses pg_stat_statements (normalised, no
# literals) instead.
locals {
  postgres_parameters = {
    require_secure_transport     = "ON"
    ssl_min_protocol_version     = "TLSv1.2"
    log_connections              = "ON"
    log_disconnections           = "ON"
    log_checkpoints              = "ON"
    log_min_duration_statement   = "-1"
    "connection_throttle.enable" = "ON"
    "azure.extensions"           = "PGCRYPTO,PGAUDIT,PG_STAT_STATEMENTS"
    shared_preload_libraries     = "pg_stat_statements,pgaudit"
    "pgaudit.log"                = "DDL,ROLE"
    "pgaudit.log_catalog"        = "OFF"
    password_encryption          = "SCRAM-SHA-256"
  }
}

resource "azurerm_postgresql_flexible_server_configuration" "params" {
  for_each  = local.postgres_parameters
  name      = each.key
  server_id = azurerm_postgresql_flexible_server.main.id
  value     = each.value
}
