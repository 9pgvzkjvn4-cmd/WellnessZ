terraform {
  required_version = ">= 1.9"
  required_providers {
    azurerm = { source = "hashicorp/azurerm", version = "~> 4.50" }
    random  = { source = "hashicorp/random", version = "~> 3.6" }
  }
  # Remote state in a Qatar Central storage account with Entra auth (see envs/backend-*.hcl and docs/azure/Setup-Guide.md §4).
  backend "azurerm" {}
}

provider "azurerm" {
  subscription_id     = var.subscription_id
  storage_use_azuread = true
  features {
    key_vault {
      purge_soft_delete_on_destroy    = false
      recover_soft_deleted_key_vaults = true
    }
    resource_group {
      prevent_deletion_if_contains_resources = true
    }
  }
}
