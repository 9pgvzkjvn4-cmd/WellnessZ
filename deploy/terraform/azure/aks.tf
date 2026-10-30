resource "azurerm_kubernetes_cluster" "main" {
  name                = "aks-${local.name}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  dns_prefix          = local.name
  node_resource_group = "rg-${local.name}-aks-nodes"
  kubernetes_version  = var.kubernetes_version
  sku_tier            = local.aks_sku_tier
  tags                = local.tags

  automatic_upgrade_channel = "patch"
  node_os_upgrade_channel   = "NodeImage"

  # API server exposure
  private_cluster_enabled             = var.aks_private_cluster
  private_cluster_public_fqdn_enabled = false
  dynamic "api_server_access_profile" {
    for_each = !var.aks_private_cluster && length(var.aks_authorized_ip_ranges) > 0 ? [1] : []
    content {
      authorized_ip_ranges = var.aks_authorized_ip_ranges
    }
  }

  # Identity & access: Entra ID only, Azure RBAC for Kubernetes, no local accounts
  local_account_disabled = true
  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.aks.id]
  }
  azure_active_directory_role_based_access_control {
    tenant_id              = data.azurerm_client_config.current.tenant_id
    azure_rbac_enabled     = true
    admin_group_object_ids = var.aks_admin_group_object_ids
  }

  # Workload identity (federated SA tokens), no pod-managed identity / secrets
  oidc_issuer_enabled       = true
  workload_identity_enabled = true

  # Governance & security add-ons
  azure_policy_enabled = true
  microsoft_defender {
    log_analytics_workspace_id = azurerm_log_analytics_workspace.main.id
  }
  key_vault_secrets_provider {
    secret_rotation_enabled  = true
    secret_rotation_interval = "2m"
  }
  oms_agent {
    log_analytics_workspace_id      = azurerm_log_analytics_workspace.main.id
    msi_auth_for_monitoring_enabled = true
  }
  ingress_application_gateway {
    gateway_id = azurerm_application_gateway.main.id
  }
  disk_encryption_set_id       = var.aks_disk_encryption_set_id
  image_cleaner_enabled        = true
  image_cleaner_interval_hours = 48
  run_command_enabled          = var.aks_private_cluster # needed for `az aks command invoke` from CI

  network_profile {
    network_plugin      = "azure"
    network_plugin_mode = "overlay"
    network_data_plane  = "cilium"
    network_policy      = "cilium"
    pod_cidr            = var.aks_pod_cidr
    service_cidr        = var.aks_service_cidr
    dns_service_ip      = var.aks_dns_service_ip
    outbound_type       = var.aks_outbound_type
    load_balancer_sku   = "standard"
  }

  default_node_pool {
    name                         = "system"
    vm_size                      = var.aks_system_node_vm_size
    vnet_subnet_id               = azurerm_subnet.aks.id
    zones                        = var.availability_zones
    auto_scaling_enabled         = true
    min_count                    = var.aks_system_node_min
    max_count                    = var.aks_system_node_max
    max_pods                     = 110
    os_sku                       = "AzureLinux"
    os_disk_type                 = "Ephemeral"
    os_disk_size_gb              = 128
    host_encryption_enabled      = var.aks_host_encryption
    only_critical_addons_enabled = true
    temporary_name_for_rotation  = "systemtmp"
    upgrade_settings {
      max_surge = "33%"
    }
  }

  maintenance_window_auto_upgrade {
    frequency   = "Weekly"
    interval    = 1
    duration    = 4
    day_of_week = "Sunday"
    start_time  = "02:00"
    utc_offset  = "+00:00"
  }

  maintenance_window_node_os {
    frequency   = "Weekly"
    interval    = 1
    duration    = 4
    day_of_week = "Sunday"
    start_time  = "02:00"
    utc_offset  = "+00:00"
  }

  depends_on = [
    azurerm_role_assignment.aks_network,
    azurerm_subnet_network_security_group_association.aks, # NSG in place before nodes join
  ]

  lifecycle {
    ignore_changes = [
      default_node_pool[0].node_count,
      kubernetes_version, # managed by automatic_upgrade_channel
    ]
  }
}

resource "azurerm_kubernetes_cluster_node_pool" "apps" {
  name                    = "apps"
  kubernetes_cluster_id   = azurerm_kubernetes_cluster.main.id
  vm_size                 = var.aks_user_node_vm_size
  vnet_subnet_id          = azurerm_subnet.aks.id
  zones                   = var.availability_zones
  mode                    = "User"
  auto_scaling_enabled    = true
  min_count               = var.aks_user_node_min
  max_count               = var.aks_user_node_max
  max_pods                = 110
  os_sku                  = "AzureLinux"
  os_disk_type            = "Ephemeral"
  os_disk_size_gb         = 128
  host_encryption_enabled = var.aks_host_encryption
  tags                    = local.tags

  upgrade_settings {
    max_surge = "33%"
  }

  lifecycle {
    ignore_changes = [node_count]
  }
}
