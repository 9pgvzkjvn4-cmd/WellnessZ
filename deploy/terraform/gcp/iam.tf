# ---------------------------------------------------------------------------
# Workload identities: one Google SA per component, bound to its KSA.
# ---------------------------------------------------------------------------
locals {
  workloads = {
    core       = { gsa = "wellnessz-core", ksa = "${local.ksa_prefix}-core" }
    bff        = { gsa = "wellnessz-bff", ksa = "${local.ksa_prefix}-bff" }
    migrations = { gsa = "wellnessz-migrator", ksa = "${local.ksa_prefix}-migrations" }
  }
}

resource "google_service_account" "workload" {
  for_each     = local.workloads
  account_id   = each.value.gsa
  display_name = "WellnessZ ${each.key} workload (${var.environment})"
}

resource "google_service_account_iam_member" "workload_identity" {
  for_each           = local.workloads
  service_account_id = google_service_account.workload[each.key].name
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:${local.wi_pool}[${var.k8s_namespace}/${each.value.ksa}]"
}

# Telemetry from core/bff
resource "google_project_iam_member" "workload_telemetry" {
  for_each = { for p in setproduct(["core", "bff"], ["roles/cloudtrace.agent", "roles/monitoring.metricWriter"]) : "${p[0]}/${p[1]}" => p }
  project  = var.project_id
  role     = each.value[1]
  member   = google_service_account.workload[each.value[0]].member
}

# ---------------------------------------------------------------------------
# GitHub Actions: Workload Identity Federation (no JSON keys)
# ---------------------------------------------------------------------------
locals {
  # Default GitHub OIDC subject formats (no customised sub claim template)
  github_wif_subjects = concat(
    [for e in local.github_envs : "repo:${var.github_repository}:environment:${e}"],
    [for b in var.github_image_publish_branches : "repo:${var.github_repository}:ref:refs/heads/${b}"],
  )
}

resource "google_iam_workload_identity_pool" "github" {
  count                     = var.github_repository == "" ? 0 : 1
  workload_identity_pool_id = "github-${var.environment}"
  display_name              = "GitHub Actions (${var.environment})"
}

resource "google_iam_workload_identity_pool_provider" "github" {
  #checkov:skip=CKV_GCP_125:attribute_condition restricts repository and exact subjects (generated expression the scanner cannot parse)
  count                              = var.github_repository == "" ? 0 : 1
  workload_identity_pool_id          = google_iam_workload_identity_pool.github[0].workload_identity_pool_id
  workload_identity_pool_provider_id = "github"
  display_name                       = "GitHub OIDC"
  attribute_mapping = {
    "google.subject"         = "assertion.sub"
    "attribute.repository"   = "assertion.repository"
    "attribute.ref"          = "assertion.ref"
    "attribute.environment"  = "assertion.environment"
    "attribute.workflow_ref" = "assertion.job_workflow_ref"
  }
  # Only this repository, and only the token subjects the deployer SA bindings
  # below accept (its GitHub Environments and the image-publish branches),
  # may exchange a token at all - defence in depth for the IAM bindings.
  attribute_condition = "assertion.repository == '${var.github_repository}' && (${join(" || ", [for s in local.github_wif_subjects : "assertion.sub == '${s}'"])})"
  oidc {
    issuer_uri = "https://token.actions.githubusercontent.com"
  }
}

resource "google_service_account" "github_deployer" {
  count        = var.github_repository == "" ? 0 : 1
  account_id   = "github-deployer-${var.environment}"
  display_name = "GitHub Actions deployer (${var.environment})"
}

# Only jobs running in this environment's GitHub Environment (gcp-<env>) may
# impersonate the deployer; required reviewers on that GitHub Environment
# therefore gate every cloud credential for uat/prod.
resource "google_service_account_iam_member" "github_wif_env" {
  for_each           = var.github_repository == "" ? toset([]) : toset(local.github_envs)
  service_account_id = google_service_account.github_deployer[0].name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github[0].name}/attribute.environment/${each.value}"
}

# Image publishing (container.yml, no GitHub Environment) from selected branches - normally dev only
resource "google_service_account_iam_member" "github_wif_publish" {
  for_each           = var.github_repository == "" ? toset([]) : toset(var.github_image_publish_branches)
  service_account_id = google_service_account.github_deployer[0].name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github[0].name}/attribute.ref/refs/heads/${each.value}"
}

resource "google_project_iam_member" "github_deployer" {
  for_each = var.github_repository == "" ? toset([]) : toset([
    "roles/container.developer",              # helm upgrade into the cluster
    "roles/containeranalysis.notes.attacher", # Binary Authorization attestations
    "roles/containeranalysis.occurrences.editor",
  ])
  project = var.project_id
  role    = each.value
  member  = google_service_account.github_deployer[0].member
}

resource "google_artifact_registry_repository_iam_member" "github_push" {
  count      = var.github_repository == "" ? 0 : 1
  location   = google_artifact_registry_repository.main.location
  repository = google_artifact_registry_repository.main.name
  role       = "roles/artifactregistry.writer"
  member     = google_service_account.github_deployer[0].member
}

resource "google_kms_crypto_key_iam_member" "github_attestor_signer" {
  count         = var.github_repository == "" ? 0 : 1
  crypto_key_id = google_kms_crypto_key.binauthz.id
  role          = "roles/cloudkms.signerVerifier"
  member        = google_service_account.github_deployer[0].member
}
