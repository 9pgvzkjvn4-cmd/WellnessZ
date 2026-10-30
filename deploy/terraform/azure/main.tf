data "azurerm_client_config" "current" {}

locals {
  name = "${var.name_prefix}-${var.environment}"
  # Globally unique, alphanumeric names (ACR, Key Vault, storage)
  compact_name = substr(replace("${var.name_prefix}${var.environment}${random_string.suffix.result}", "-", ""), 0, 22)

  is_prod = var.environment == "prod"

  zone_redundant = length(var.availability_zones) > 1
  aks_sku_tier   = coalesce(var.aks_sku_tier, var.environment == "dev" ? "Free" : "Standard")
  resource_lock  = coalesce(var.resource_lock_enabled, local.is_prod)

  # Each cloud environment trusts only its own GitHub Environment (azure-dev|azure-uat|azure-prod)
  github_environments = coalesce(var.github_environments, ["azure-${var.environment}"])

  tags = merge({
    application         = "wellnessz"
    environment         = var.environment
    environment-tier    = local.is_prod ? "production" : "non-production"
    data-classification = "phi-special-category"
    managed-by          = "terraform"
    compliance          = "soc2-type2"
  }, var.tags)

  # Mirrors the chart's "wellnessz.fullname" helper: a release name that already
  # contains "wellnessz" is used as-is (release "wellnessz" -> SA "wellnessz-core").
  helm_fullname = strcontains(var.helm_release_name, "wellnessz") ? var.helm_release_name : "${var.helm_release_name}-wellnessz"
  sa_prefix     = "system:serviceaccount:${var.k8s_namespace}:${local.helm_fullname}"
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

# Prevent accidental deletion (production by default, opt-in elsewhere)
resource "azurerm_management_lock" "rg" {
  count      = local.resource_lock ? 1 : 0
  name       = "do-not-delete"
  scope      = azurerm_resource_group.main.id
  lock_level = "CanNotDelete"
  notes      = "WellnessZ ${var.environment} - PHI. Remove only via approved change."
}
