data "azurerm_client_config" "current" {}

locals {
  env     = var.environment
  base    = "${var.prefix}-${var.environment}-qc"
  compact = replace("${var.prefix}${var.environment}qc", "-", "")
  tags = merge({
    product             = "IntiMed"
    environment         = var.environment
    data-residency      = "Qatar"
    data-classification = "PHI"
    managed-by          = "terraform"
  }, var.tags)
}

resource "azurerm_resource_group" "rg" {
  name     = "rg-${local.base}"
  location = var.location
  tags     = local.tags

  lifecycle {
    precondition {
      condition     = var.location == "qatarcentral" || var.allow_non_qatar_region
      error_message = "IntiMed health data must stay in Qatar Central."
    }
  }
}

resource "random_string" "suffix" {
  length  = 4
  upper   = false
  special = false
}
