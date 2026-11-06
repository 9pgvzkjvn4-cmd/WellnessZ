resource "azurerm_user_assigned_identity" "aks" {
  name                = "id-aks-${local.base}"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  tags                = local.tags
}

# Dedicated kubelet identity (pulls images from ACR); the control-plane identity must be able to assign it.
resource "azurerm_user_assigned_identity" "kubelet" {
  name                = "id-kubelet-${local.base}"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  tags                = local.tags
}

resource "azurerm_role_assignment" "aks_kubelet_operator" {
  scope                = azurerm_user_assigned_identity.kubelet.id
  role_definition_name = "Managed Identity Operator"
  principal_id         = azurerm_user_assigned_identity.aks.principal_id
}

# Control plane needs Network Contributor on the VNet for internal load balancers with static IPs in two subnets.
resource "azurerm_role_assignment" "aks_network" {
  scope                = azurerm_virtual_network.vnet.id
  role_definition_name = "Network Contributor"
  principal_id         = azurerm_user_assigned_identity.aks.principal_id
}

resource "azurerm_kubernetes_cluster" "aks" {
  name                              = "aks-${local.base}"
  location                          = azurerm_resource_group.rg.location
  resource_group_name               = azurerm_resource_group.rg.name
  dns_prefix                        = "aks-${local.base}"
  kubernetes_version                = var.kubernetes_version
  sku_tier                          = "Standard"
  private_cluster_enabled           = var.aks_private_cluster
  local_account_disabled            = true
  role_based_access_control_enabled = true
  oidc_issuer_enabled               = true
  workload_identity_enabled         = true
  azure_policy_enabled              = true
  automatic_upgrade_channel         = "patch"
  node_os_upgrade_channel           = "NodeImage"
  image_cleaner_enabled             = true
  image_cleaner_interval_hours      = 48
  tags                              = local.tags

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.aks.id]
  }

  kubelet_identity {
    client_id                 = azurerm_user_assigned_identity.kubelet.client_id
    object_id                 = azurerm_user_assigned_identity.kubelet.principal_id
    user_assigned_identity_id = azurerm_user_assigned_identity.kubelet.id
  }

  azure_active_directory_role_based_access_control {
    azure_rbac_enabled = true
    tenant_id          = data.azurerm_client_config.current.tenant_id
  }

  dynamic "api_server_access_profile" {
    for_each = var.aks_private_cluster ? [] : [1]
    content {
      authorized_ip_ranges = var.admin_source_cidrs
    }
  }

  default_node_pool {
    name                         = "system"
    vm_size                      = var.system_node_vm_size
    node_count                   = var.system_node_count
    zones                        = var.zones
    vnet_subnet_id               = azurerm_subnet.aks.id
    only_critical_addons_enabled = true
    os_sku                       = "AzureLinux"
    host_encryption_enabled      = false
    upgrade_settings {
      max_surge = "33%"
    }
  }

  network_profile {
    network_plugin      = "azure"
    network_plugin_mode = "overlay"
    network_data_plane  = "cilium"
    network_policy      = "cilium"
    load_balancer_sku   = "standard"
    outbound_type       = var.enable_firewall ? "userDefinedRouting" : "loadBalancer"
    pod_cidr            = "192.168.0.0/16"
    service_cidr        = "172.20.0.0/16"
    dns_service_ip      = "172.20.0.10"
  }

  key_vault_secrets_provider {
    secret_rotation_enabled  = true
    secret_rotation_interval = "5m"
  }

  oms_agent {
    log_analytics_workspace_id      = azurerm_log_analytics_workspace.law.id
    msi_auth_for_monitoring_enabled = true
  }

  microsoft_defender {
    log_analytics_workspace_id = azurerm_log_analytics_workspace.law.id
  }

  monitor_metrics {}

  maintenance_window_auto_upgrade {
    frequency   = "Weekly"
    interval    = 1
    duration    = 4
    day_of_week = "Friday"
    start_time  = "01:00"
    utc_offset  = "+03:00"
  }

  depends_on = [azurerm_role_assignment.aks_network, azurerm_role_assignment.aks_kubelet_operator, azurerm_role_assignment.kubelet_acr, azurerm_subnet_route_table_association.aks]
}

resource "azurerm_kubernetes_cluster_node_pool" "apps" {
  name                  = "apps"
  kubernetes_cluster_id = azurerm_kubernetes_cluster.aks.id
  vm_size               = var.app_node_vm_size
  auto_scaling_enabled  = true
  min_count             = var.app_node_min
  max_count             = var.app_node_max
  zones                 = var.zones
  vnet_subnet_id        = azurerm_subnet.aks.id
  os_sku                = "AzureLinux"
  node_labels           = { agentpool = "apps" }
  tags                  = local.tags
  upgrade_settings {
    max_surge = "33%"
  }
}

# ---- workload identity for the IntiMed pod (Key Vault secrets, archive storage) – no secrets in the cluster.
resource "azurerm_user_assigned_identity" "app" {
  name                = "id-intimed-app-${local.base}"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  tags                = local.tags
}

resource "azurerm_federated_identity_credential" "app" {
  name                      = "fic-intimed"
  user_assigned_identity_id = azurerm_user_assigned_identity.app.id
  audience                  = ["api://AzureADTokenExchange"]
  issuer                    = azurerm_kubernetes_cluster.aks.oidc_issuer_url
  subject                   = "system:serviceaccount:intimed:intimed"
}

resource "azurerm_role_assignment" "app_kv_secrets" {
  scope                = azurerm_key_vault.kv.id
  role_definition_name = "Key Vault Secrets Officer" # tenant integration secrets are written by the platform (SecretStore)
  principal_id         = azurerm_user_assigned_identity.app.principal_id
}

resource "azurerm_role_assignment" "app_archive" {
  scope                = azurerm_storage_account.archive.id
  role_definition_name = "Storage Blob Data Contributor"
  principal_id         = azurerm_user_assigned_identity.app.principal_id
}

resource "azurerm_role_assignment" "kubelet_acr" {
  scope                = azurerm_container_registry.acr.id
  role_definition_name = "AcrPull"
  principal_id         = azurerm_user_assigned_identity.kubelet.principal_id
}

# ---- egress route through Azure Firewall (production)
resource "azurerm_route_table" "aks" {
  count               = var.enable_firewall ? 1 : 0
  name                = "rt-aks-${local.base}"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  tags                = local.tags

  route {
    name                   = "default-via-firewall"
    address_prefix         = "0.0.0.0/0"
    next_hop_type          = "VirtualAppliance"
    next_hop_in_ip_address = azurerm_firewall.fw[0].ip_configuration[0].private_ip_address
  }
}

resource "azurerm_subnet_route_table_association" "aks" {
  count          = var.enable_firewall ? 1 : 0
  subnet_id      = azurerm_subnet.aks.id
  route_table_id = azurerm_route_table.aks[0].id
}
