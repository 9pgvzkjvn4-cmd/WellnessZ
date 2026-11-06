# `terraform test` – applies the full configuration against mocked providers (nothing is created in Azure) (no Azure credentials needed) for the UAT
# and production variable sets, and proves the data-residency guard and the phase-2 preconditions.
mock_provider "azurerm" {
  mock_data "azurerm_client_config" {
    defaults = {
      tenant_id       = "00000000-0000-0000-0000-000000000000"
      object_id       = "11111111-1111-1111-1111-111111111111"
      subscription_id = "22222222-2222-2222-2222-222222222222"
    }
  }
  mock_data "azurerm_policy_definition" {
    defaults = { id = "/providers/Microsoft.Authorization/policyDefinitions/e56962a6-4747-49cd-b67b-bf8b01975c4c" }
  }
  mock_data "azurerm_monitor_diagnostic_categories" {
    defaults = { log_category_types = ["AuditEvent"], metrics = ["AllMetrics"] }
  }
  mock_resource "azurerm_subnet" {
    defaults = { id = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/rg-intimed-test-qc/providers/Microsoft.Network/virtualNetworks/vnet/subnets/snet" }
  }
  mock_resource "azurerm_private_dns_zone" {
    defaults = { id = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/rg-intimed-test-qc/providers/Microsoft.Network/privateDnsZones/privatelink.vaultcore.azure.net" }
  }
  mock_resource "azurerm_network_security_group" {
    defaults = { id = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/rg-intimed-test-qc/providers/Microsoft.Network/networkSecurityGroups/nsg" }
  }
  mock_resource "azurerm_monitor_data_collection_endpoint" {
    defaults = { id = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/rg-intimed-test-qc/providers/Microsoft.Insights/dataCollectionEndpoints/dce" }
  }
  mock_resource "azurerm_monitor_data_collection_rule" {
    defaults = { id = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/rg-intimed-test-qc/providers/Microsoft.Insights/dataCollectionRules/dcr" }
  }
  mock_resource "azurerm_monitor_action_group" {
    defaults = { id = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/rg-intimed-test-qc/providers/Microsoft.Insights/actionGroups/ag" }
  }
  mock_resource "azurerm_route_table" {
    defaults = { id = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/rg-intimed-test-qc/providers/Microsoft.Network/routeTables/rt" }
  }
  mock_resource "azurerm_public_ip" {
    defaults = { id = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/rg-intimed-test-qc/providers/Microsoft.Network/publicIPAddresses/pip" }
  }
  mock_resource "azurerm_firewall_policy" {
    defaults = { id = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/rg-intimed-test-qc/providers/Microsoft.Network/firewallPolicies/afwp" }
  }
  mock_resource "azurerm_web_application_firewall_policy" {
    defaults = { id = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/rg-intimed-test-qc/providers/Microsoft.Network/applicationGatewayWebApplicationFirewallPolicies/waf" }
  }
  mock_resource "azurerm_virtual_network_gateway" {
    defaults = { id = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/rg-intimed-test-qc/providers/Microsoft.Network/virtualNetworkGateways/vgw" }
  }
  mock_resource "azurerm_local_network_gateway" {
    defaults = { id = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/rg-intimed-test-qc/providers/Microsoft.Network/localNetworkGateways/lgw" }
  }
  mock_resource "azurerm_key_vault_key" {
    defaults = { id = "https://kv.vault.azure.net/keys/cosign-signing/0123456789abcdef0123456789abcdef" }
  }
  mock_resource "azurerm_sentinel_log_analytics_workspace_onboarding" {
    defaults = { id = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/rg-intimed-test-qc/providers/Microsoft.OperationalInsights/workspaces/law/providers/Microsoft.SecurityInsights/onboardingStates/default" }
  }
  mock_resource "azurerm_postgresql_flexible_server_database" {
    defaults = { id = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/rg-intimed-test-qc/providers/Microsoft.DBforPostgreSQL/flexibleServers/psql/databases/intimed" }
  }
  mock_resource "azurerm_private_endpoint" {
    defaults = { id = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/rg-intimed-test-qc/providers/Microsoft.Network/privateEndpoints/pe" }
  }
  mock_resource "azurerm_firewall" {
    defaults = { id = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/rg-intimed-test-qc/providers/Microsoft.Network/azureFirewalls/afw" }
  }
  mock_resource "azurerm_resource_group" {
    defaults = { id = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/rg-intimed-test-qc" }
  }
  mock_resource "azurerm_virtual_network" {
    defaults = { id = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/rg/providers/Microsoft.Network/virtualNetworks/vnet" }
  }
  mock_resource "azurerm_key_vault" {
    defaults = { id = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/rg/providers/Microsoft.KeyVault/vaults/kv", vault_uri = "https://kv.vault.azure.net/" }
  }
  mock_resource "azurerm_storage_account" {
    defaults = { id = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/rg/providers/Microsoft.Storage/storageAccounts/st", primary_blob_endpoint = "https://st.blob.core.windows.net/" }
  }
  mock_resource "azurerm_storage_container" {
    defaults = { id = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/rg/providers/Microsoft.Storage/storageAccounts/st/blobServices/default/containers/c" }
  }
  mock_resource "azurerm_kubernetes_cluster" {
    defaults = {
      id              = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/rg/providers/Microsoft.ContainerService/managedClusters/aks"
      oidc_issuer_url = "https://qatarcentral.oic.prod-aks.azure.com/tenant/issuer/"
    }
  }
  mock_resource "azurerm_log_analytics_workspace" {
    defaults = { id = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/rg/providers/Microsoft.OperationalInsights/workspaces/law" }
  }
  mock_resource "azurerm_monitor_workspace" {
    defaults = { id = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/rg/providers/Microsoft.Monitor/accounts/amw" }
  }
  mock_resource "azurerm_user_assigned_identity" {
    defaults = { id = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/rg/providers/Microsoft.ManagedIdentity/userAssignedIdentities/id", principal_id = "55555555-5555-5555-5555-555555555555", client_id = "66666666-6666-6666-6666-666666666666" }
  }
  mock_resource "azurerm_postgresql_flexible_server" {
    defaults = { id = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/rg/providers/Microsoft.DBforPostgreSQL/flexibleServers/psql", fqdn = "psql.postgres.database.azure.com" }
  }
  mock_resource "azurerm_container_registry" {
    defaults = { id = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/rg/providers/Microsoft.ContainerRegistry/registries/acr", login_server = "acr.azurecr.io" }
  }
  mock_resource "azurerm_application_gateway" {
    defaults = { id = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/rg/providers/Microsoft.Network/applicationGateways/agw" }
  }
}
mock_provider "random" {
  mock_resource "random_uuid" {
    defaults = { result = "7f1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", id = "7f1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d" }
  }
  mock_resource "random_string" {
    defaults = { result = "ab12", id = "ab12" }
  }
}

override_resource {
  target = azurerm_kubernetes_cluster.aks
  values = {
    id              = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/rg/providers/Microsoft.ContainerService/managedClusters/aks"
    oidc_issuer_url = "https://qatarcentral.oic.prod-aks.azure.com/tenant/issuer/"
  }
}

override_resource {
  target = azurerm_subnet.firewall
  values = { id = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/rg-intimed-test-qc/providers/Microsoft.Network/virtualNetworks/vnet/subnets/AzureFirewallSubnet" }
}

override_resource {
  target = azurerm_subnet.gateway
  values = { id = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/rg-intimed-test-qc/providers/Microsoft.Network/virtualNetworks/vnet/subnets/GatewaySubnet" }
}


variables {
  subscription_id     = "22222222-2222-2222-2222-222222222222"
  device_source_cidrs = ["10.60.0.0/16"]
  admin_source_cidrs  = ["203.0.113.10/32"]
  alert_emails        = ["ops@example.qa"]
}

run "uat_plan" {
  command = apply
  variables {
    environment = "uat"
  }
  assert {
    condition     = azurerm_resource_group.rg.location == "qatarcentral"
    error_message = "UAT must be in Qatar Central"
  }
  assert {
    condition     = azurerm_postgresql_flexible_server.pg.public_network_access_enabled == false && azurerm_postgresql_flexible_server.pg.geo_redundant_backup_enabled == false
    error_message = "PostgreSQL must be private, backups in-region"
  }
  assert {
    condition     = azurerm_storage_account.archive.shared_access_key_enabled == false && azurerm_storage_account.archive.account_replication_type == "ZRS"
    error_message = "archive storage must be Entra-only and zone-redundant"
  }
  assert {
    condition     = azurerm_kubernetes_cluster.aks.local_account_disabled && azurerm_kubernetes_cluster.aks.workload_identity_enabled
    error_message = "AKS must disable local accounts and use workload identity"
  }
  assert {
    condition     = length(azurerm_firewall.fw) == 0 && length(azurerm_application_gateway.agw) == 0
    error_message = "UAT phase 1 has no firewall and no app gateway"
  }
}

run "prod_plan" {
  command = apply
  variables {
    environment                 = "prod"
    aks_private_cluster         = true
    system_node_count           = 3
    app_node_vm_size            = "Standard_D8s_v5"
    postgres_sku                = "GP_Standard_D4ds_v5"
    postgres_storage_mb         = 262144
    postgres_ha_mode            = "ZoneRedundant"
    enable_firewall             = true
    enable_vpn                  = true
    vpn_sites                   = { hmc = { gateway_ip = "198.51.100.7", address_prefixes = ["10.61.0.0/16"] } }
    vpn_shared_keys             = { hmc = "test-only-shared-key-0123456789" }
    enable_app_gateway          = true
    console_hostname            = "intimed.example.qa"
    appgw_certificate_secret_id = "https://kv.vault.azure.net/secrets/console-tls"
    console_root_ca_pem         = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----"
    enable_defender_plans       = true
  }
  assert {
    condition     = azurerm_postgresql_flexible_server.pg.high_availability[0].mode == "ZoneRedundant" && azurerm_postgresql_flexible_server.pg.storage_tier == "P15"
    error_message = "prod PostgreSQL must be zone-redundant HA with a matching storage tier"
  }
  assert {
    condition     = azurerm_kubernetes_cluster.aks.network_profile[0].outbound_type == "userDefinedRouting"
    error_message = "prod egress must go through Azure Firewall"
  }
  assert {
    condition     = azurerm_web_application_firewall_policy.waf[0].policy_settings[0].mode == "Prevention"
    error_message = "WAF must be in Prevention mode"
  }
  assert {
    condition     = length(azurerm_virtual_network_gateway_connection.site) == 1
    error_message = "one VPN site connection expected"
  }
}

run "residency_guard" {
  command = plan
  variables {
    environment = "uat"
    location    = "uaenorth"
  }
  expect_failures = [azurerm_resource_group.rg]
}

run "appgw_needs_phase2_inputs" {
  command = plan
  variables {
    environment        = "uat"
    enable_app_gateway = true
  }
  expect_failures = [azurerm_application_gateway.agw]
}
