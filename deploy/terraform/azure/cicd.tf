# ---------------------------------------------------------------------------
# GitHub Actions deployer identity (OIDC federation, no secrets)
#   - AcrPush on the registry (container.yml)
#   - AKS cluster user + RBAC writer scoped to the app namespace (cd.yml)
# ---------------------------------------------------------------------------
resource "azurerm_user_assigned_identity" "github_deployer" {
  count               = var.github_repository == "" ? 0 : 1
  name                = "id-${local.name}-github-deployer"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = local.tags
}

resource "azurerm_federated_identity_credential" "github_env" {
  for_each            = var.github_repository == "" ? toset([]) : toset(var.github_environments)
  name                = "github-${each.value}"
  resource_group_name = azurerm_resource_group.main.name
  parent_id           = azurerm_user_assigned_identity.github_deployer[0].id
  issuer              = "https://token.actions.githubusercontent.com"
  subject             = "repo:${var.github_repository}:environment:${each.value}"
  audience            = ["api://AzureADTokenExchange"]
}

# Image publishing from the default branch / tags
resource "azurerm_federated_identity_credential" "github_main" {
  count               = var.github_repository == "" ? 0 : 1
  name                = "github-main"
  resource_group_name = azurerm_resource_group.main.name
  parent_id           = azurerm_user_assigned_identity.github_deployer[0].id
  issuer              = "https://token.actions.githubusercontent.com"
  subject             = "repo:${var.github_repository}:ref:refs/heads/main"
  audience            = ["api://AzureADTokenExchange"]
}

resource "azurerm_role_assignment" "github_acr_push" {
  count                = var.github_repository == "" ? 0 : 1
  scope                = azurerm_container_registry.main.id
  role_definition_name = "AcrPush"
  principal_id         = azurerm_user_assigned_identity.github_deployer[0].principal_id
}

resource "azurerm_role_assignment" "github_aks_user" {
  count                = var.github_repository == "" ? 0 : 1
  scope                = azurerm_kubernetes_cluster.main.id
  role_definition_name = "Azure Kubernetes Service Cluster User Role"
  principal_id         = azurerm_user_assigned_identity.github_deployer[0].principal_id
}

resource "azurerm_role_assignment" "github_aks_ns_writer" {
  count                = var.github_repository == "" ? 0 : 1
  scope                = "${azurerm_kubernetes_cluster.main.id}/namespaces/${var.k8s_namespace}"
  role_definition_name = "Azure Kubernetes Service RBAC Writer"
  principal_id         = azurerm_user_assigned_identity.github_deployer[0].principal_id
}
