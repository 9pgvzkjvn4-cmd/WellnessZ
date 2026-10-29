# ---------------------------------------------------------------------------
# User-assigned managed identities
# ---------------------------------------------------------------------------
resource "azurerm_user_assigned_identity" "aks" {
  name                = "id-${local.name}-aks"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = local.tags
}

resource "azurerm_user_assigned_identity" "postgres" {
  name                = "id-${local.name}-postgres"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = local.tags
}

resource "azurerm_user_assigned_identity" "appgw" {
  name                = "id-${local.name}-appgw"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = local.tags
}

# Workload identities: one per component (least privilege)
locals {
  workloads = {
    core       = "${local.sa_prefix}-core"
    bff        = "${local.sa_prefix}-bff"
    migrations = "${local.sa_prefix}-migrations"
  }
}

resource "azurerm_user_assigned_identity" "workload" {
  for_each            = local.workloads
  name                = "id-${local.name}-${each.key}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = local.tags
}

# Federated credential: Kubernetes SA token (AKS OIDC issuer) -> Entra token
resource "azurerm_federated_identity_credential" "workload" {
  for_each            = local.workloads
  name                = "fic-${each.key}"
  resource_group_name = azurerm_resource_group.main.name
  parent_id           = azurerm_user_assigned_identity.workload[each.key].id
  issuer              = azurerm_kubernetes_cluster.main.oidc_issuer_url
  subject             = each.value
  audience            = ["api://AzureADTokenExchange"]
}

# ---------------------------------------------------------------------------
# Role assignments
# ---------------------------------------------------------------------------
# AKS control plane manages the node subnet (custom VNet)
resource "azurerm_role_assignment" "aks_network" {
  scope                = azurerm_subnet.aks.id
  role_definition_name = "Network Contributor"
  principal_id         = azurerm_user_assigned_identity.aks.principal_id
}

# Kubelet pulls images from ACR (no admin user, no pull secrets)
resource "azurerm_role_assignment" "kubelet_acr_pull" {
  scope                            = azurerm_container_registry.main.id
  role_definition_name             = "AcrPull"
  principal_id                     = azurerm_kubernetes_cluster.main.kubelet_identity[0].object_id
  skip_service_principal_aad_check = true
}

# AGIC add-on manages the Application Gateway
resource "azurerm_role_assignment" "agic_appgw" {
  scope                = azurerm_application_gateway.main.id
  role_definition_name = "Contributor"
  principal_id         = azurerm_kubernetes_cluster.main.ingress_application_gateway[0].ingress_application_gateway_identity[0].object_id
}

resource "azurerm_role_assignment" "agic_rg_reader" {
  scope                = azurerm_resource_group.main.id
  role_definition_name = "Reader"
  principal_id         = azurerm_kubernetes_cluster.main.ingress_application_gateway[0].ingress_application_gateway_identity[0].object_id
}

resource "azurerm_role_assignment" "agic_appgw_identity" {
  scope                = azurerm_user_assigned_identity.appgw.id
  role_definition_name = "Managed Identity Operator"
  principal_id         = azurerm_kubernetes_cluster.main.ingress_application_gateway[0].ingress_application_gateway_identity[0].object_id
}

resource "azurerm_role_assignment" "agic_subnet" {
  scope                = azurerm_subnet.appgw.id
  role_definition_name = "Network Contributor"
  principal_id         = azurerm_kubernetes_cluster.main.ingress_application_gateway[0].ingress_application_gateway_identity[0].object_id
}
