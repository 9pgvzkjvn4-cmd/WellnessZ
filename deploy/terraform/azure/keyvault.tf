resource "azurerm_key_vault" "main" {
  name                          = "kv-${local.compact_name}"
  location                      = azurerm_resource_group.main.location
  resource_group_name           = azurerm_resource_group.main.name
  tenant_id                     = data.azurerm_client_config.current.tenant_id
  sku_name                      = "premium" # HSM-backed keys for CMK
  rbac_authorization_enabled    = true
  purge_protection_enabled      = true
  soft_delete_retention_days    = 90
  public_network_access_enabled = length(var.key_vault_allowed_ips) > 0
  tags                          = local.tags

  network_acls {
    default_action = "Deny"
    bypass         = "AzureServices"
    ip_rules       = var.key_vault_allowed_ips
  }
}

resource "azurerm_private_endpoint" "key_vault" {
  name                = "pe-${local.name}-kv"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  subnet_id           = azurerm_subnet.private_endpoints.id
  tags                = local.tags

  private_service_connection {
    name                           = "kv"
    private_connection_resource_id = azurerm_key_vault.main.id
    subresource_names              = ["vault"]
    is_manual_connection           = false
  }

  private_dns_zone_group {
    name                 = "kv"
    private_dns_zone_ids = [azurerm_private_dns_zone.zones["keyvault"].id]
  }
}

# The identity running Terraform manages secrets/keys (data plane RBAC)
resource "azurerm_role_assignment" "tf_kv_secrets_officer" {
  scope                = azurerm_key_vault.main.id
  role_definition_name = "Key Vault Secrets Officer"
  principal_id         = data.azurerm_client_config.current.object_id
}

resource "azurerm_role_assignment" "tf_kv_crypto_officer" {
  count                = var.postgres_cmk_enabled ? 1 : 0
  scope                = azurerm_key_vault.main.id
  role_definition_name = "Key Vault Crypto Officer"
  principal_id         = data.azurerm_client_config.current.object_id
}

# ---------------------------------------------------------------------------
# Application secrets.
# DB passwords are generated ephemerally and written with write-only
# arguments (never persisted in Terraform state). Key material placeholders
# MUST be replaced out-of-band (see README "Bootstrap secrets"):
#   openssl rand -base64 32 | az keyvault secret set --vault-name <kv> -n wz-master-key --file /dev/stdin
# ---------------------------------------------------------------------------
ephemeral "random_password" "pg_admin" {
  length           = 32
  special          = true
  override_special = "-_.~"
}

ephemeral "random_password" "wz_app" {
  length  = 32
  special = false
}

ephemeral "random_password" "wz_bff" {
  length  = 32
  special = false
}

ephemeral "random_password" "wz_migrator" {
  length  = 32
  special = false
}

locals {
  # name => { owner identity, initial value }
  kv_secrets = {
    "wz-pg-admin-password"    = { owner = null }
    "wz-db-password"          = { owner = "core" }
    "wz-master-key"           = { owner = "core" }
    "wz-pseudonym-key"        = { owner = "core" }
    "wz-migrator-db-password" = { owner = "migrations" }
    "wz-bff-client-secret"    = { owner = "bff" }
    "wz-bff-db-password"      = { owner = "bff" }
    "wz-bff-session-key"      = { owner = "bff" }
  }
  generated_secrets = toset(["wz-pg-admin-password", "wz-db-password", "wz-bff-db-password", "wz-migrator-db-password"])
  kv_secret_values = {
    "wz-pg-admin-password"    = ephemeral.random_password.pg_admin.result
    "wz-db-password"          = ephemeral.random_password.wz_app.result
    "wz-migrator-db-password" = ephemeral.random_password.wz_migrator.result
    "wz-bff-db-password"      = ephemeral.random_password.wz_bff.result
    "wz-bff-session-key"      = "REPLACE-ME-openssl-rand-base64-32"
    "wz-master-key"           = "REPLACE-ME-openssl-rand-base64-32"
    "wz-pseudonym-key"        = "REPLACE-ME-openssl-rand-base64-32"
    "wz-bff-client-secret"    = "REPLACE-ME-from-IdP-app-registration"
  }
}

resource "azurerm_key_vault_secret" "app" {
  for_each     = local.kv_secrets
  name         = each.key
  key_vault_id = azurerm_key_vault.main.id
  value_wo     = local.kv_secret_values[each.key]
  # Generated DB passwords follow db_password_version; operator-managed
  # placeholders are written once and never overwritten by Terraform.
  value_wo_version = contains(local.generated_secrets, each.key) ? var.db_password_version : 1
  content_type     = "text/plain"
  # Rotation reminder (Azure Policy / Event Grid near-expiry events)
  expiration_date = timeadd(plantimestamp(), "8760h")
  tags            = merge(local.tags, { owner = coalesce(each.value.owner, "break-glass") })

  depends_on = [azurerm_role_assignment.tf_kv_secrets_officer, azurerm_private_endpoint.key_vault]

  lifecycle {
    # Values are rotated out-of-band; do not churn on every plan
    ignore_changes = [expiration_date]
  }
}

# Per-secret read access for each workload identity (least privilege)
resource "azurerm_role_assignment" "workload_secret_read" {
  for_each             = { for k, v in local.kv_secrets : k => v if v.owner != null }
  scope                = azurerm_key_vault_secret.app[each.key].resource_versionless_id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = azurerm_user_assigned_identity.workload[each.value.owner].principal_id
}

# In migrations.mode=app core and bff run Flyway themselves and need the
# migrator password; in job mode (prod default) only the migrations identity does.
resource "azurerm_role_assignment" "app_mode_migrator_read" {
  for_each             = var.migrations_mode == "app" ? toset(["core", "bff"]) : toset([])
  scope                = azurerm_key_vault_secret.app["wz-migrator-db-password"].resource_versionless_id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = azurerm_user_assigned_identity.workload[each.key].principal_id
  description          = "Helm migrations.mode=app only."
}

# ---------------------------------------------------------------------------
# Customer-managed key for PostgreSQL (optional)
# ---------------------------------------------------------------------------
resource "azurerm_key_vault_key" "postgres" {
  count        = var.postgres_cmk_enabled ? 1 : 0
  name         = "cmk-postgres"
  key_vault_id = azurerm_key_vault.main.id
  key_type     = "RSA-HSM"
  key_size     = 3072
  key_opts     = ["wrapKey", "unwrapKey"]

  rotation_policy {
    automatic {
      time_before_expiry = "P30D"
    }
    expire_after         = "P1Y"
    notify_before_expiry = "P29D"
  }

  depends_on = [azurerm_role_assignment.tf_kv_crypto_officer, azurerm_private_endpoint.key_vault]
}

resource "azurerm_role_assignment" "postgres_cmk" {
  count                = var.postgres_cmk_enabled ? 1 : 0
  scope                = azurerm_key_vault.main.id
  role_definition_name = "Key Vault Crypto Service Encryption User"
  principal_id         = azurerm_user_assigned_identity.postgres.principal_id
}

resource "azurerm_role_assignment" "appgw_kv_certs" {
  scope                = azurerm_key_vault.main.id
  role_definition_name = "Key Vault Secrets User" # AppGW reads TLS certificates as secrets
  principal_id         = azurerm_user_assigned_identity.appgw.principal_id
}
