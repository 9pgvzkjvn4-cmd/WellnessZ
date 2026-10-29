data "azurerm_client_config" "current" {}

locals {
  name = "${var.name_prefix}-${var.environment}"
  # Globally unique, alphanumeric names (ACR, Key Vault, storage)
  compact_name = substr(replace("${var.name_prefix}${var.environment}${random_string.suffix.result}", "-", ""), 0, 22)

  tags = merge({
    application         = "wellnessz"
    environment         = var.environment
    data-classification = "phi-special-category"
    managed-by          = "terraform"
    compliance          = "soc2-type2"
  }, var.tags)

  sa_prefix = "system:serviceaccount:${var.k8s_namespace}:${var.helm_release_name}-wellnessz"
}

resource "random_string" "suffix" {
  length  = 4
  special = false
  upper   = false
}

resource "azurerm_resource_group" "main" {
  name     = "rg-${local.name}"
  location = var.location
  tags     = local.tags
}

# Prevent accidental deletion of the production resource group
resource "azurerm_management_lock" "rg" {
  count      = var.environment == "prod" ? 1 : 0
  name       = "do-not-delete"
  scope      = azurerm_resource_group.main.id
  lock_level = "CanNotDelete"
  notes      = "WellnessZ production - PHI. Remove only via approved change."
}
