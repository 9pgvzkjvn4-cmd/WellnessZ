terraform {
  # >= 1.11 for write-only arguments (secrets never stored in state)
  required_version = ">= 1.11.0"

  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 5.11"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.7"
    }
  }

  # Remote state in Azure Storage (Entra ID auth, no access keys).
  # terraform init -backend-config=backend.hcl   (see backend.hcl.example)
  backend "azurerm" {}
}

provider "azurerm" {
  subscription_id     = var.subscription_id
  storage_use_azuread = true

  features {
    key_vault {
      purge_soft_delete_on_destroy          = false
      purge_soft_deleted_secrets_on_destroy = false
      purge_soft_deleted_keys_on_destroy    = false
      recover_soft_deleted_key_vaults       = true
      recover_soft_deleted_secrets          = true
      recover_soft_deleted_keys             = true
    }
    resource_group {
      prevent_deletion_if_contains_resources = true
    }
    log_analytics_workspace {
      permanently_delete_on_destroy = false
    }
    postgresql_flexible_server {
      restart_server_on_configuration_value_change = true
    }
  }
}
