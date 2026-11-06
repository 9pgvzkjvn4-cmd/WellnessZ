# CI/CD identity: GitHub Actions → Workload Identity Federation → sa-intimed-deploy (no service-account keys).
# Only the configured repository (attribute condition) can impersonate the deployer. The deployer additionally needs
# roles/storage.objectViewer on the Terraform state bucket only (granted in Setup Guide step 2 – never project-wide,
# so it cannot read the evidence/audit archives).
locals { cicd = var.github_repository != "" }

resource "google_iam_workload_identity_pool" "github" {
  count                     = local.cicd ? 1 : 0
  workload_identity_pool_id = "github-${var.environment}"
  display_name              = "GitHub Actions (${var.environment})"
}

resource "google_iam_workload_identity_pool_provider" "github" {
  count                              = local.cicd ? 1 : 0
  workload_identity_pool_id          = google_iam_workload_identity_pool.github[0].workload_identity_pool_id
  workload_identity_pool_provider_id = "github"
  attribute_mapping = {
    "google.subject"       = "assertion.sub"
    "attribute.repository" = "assertion.repository"
    "attribute.ref"        = "assertion.ref"
  }
  attribute_condition = "assertion.repository == \"${var.github_repository}\""
  oidc { issuer_uri = "https://token.actions.githubusercontent.com" }
}

resource "google_service_account" "deploy" {
  count        = local.cicd ? 1 : 0
  account_id   = "sa-intimed-deploy-${var.environment}"
  display_name = "IntiMed release pipeline (${var.environment})"
}

resource "google_service_account_iam_member" "deploy_wif" {
  count              = local.cicd ? 1 : 0
  service_account_id = google_service_account.deploy[0].name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github[0].name}/attribute.repository/${var.github_repository}"
}

resource "google_project_iam_member" "deploy" {
  for_each = local.cicd ? toset([
    "roles/container.developer",              # helm on the cluster (namespace-scoped RBAC is added by the chart)
    "roles/artifactregistry.writer",          # push images (immutable tags)
    "roles/containeranalysis.notes.attacher", # Binary Authorization attestations
    "roles/containeranalysis.occurrences.editor",
    "roles/binaryauthorization.attestorsVerifier",
    "roles/storage.objectViewer", # read Terraform state outputs
  ]) : toset([])
  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.deploy[0].email}"
}

resource "google_kms_crypto_key_iam_member" "deploy_sign" {
  count         = local.cicd ? 1 : 0
  crypto_key_id = google_kms_crypto_key.cosign.id
  role          = "roles/cloudkms.signerVerifier"
  member        = "serviceAccount:${google_service_account.deploy[0].email}"
}

output "wif_provider" { value = local.cicd ? google_iam_workload_identity_pool_provider.github[0].name : "" }
output "deploy_sa" { value = local.cicd ? google_service_account.deploy[0].email : "" }
