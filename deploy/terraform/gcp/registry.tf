resource "google_artifact_registry_repository" "main" {
  repository_id = "wellnessz"
  location      = var.region
  format        = "DOCKER"
  description   = "WellnessZ container images (signed with cosign, attested by CI)"
  kms_key_name  = google_kms_crypto_key.cmek["artifact-registry"].id

  docker_config {
    immutable_tags = true
  }

  vulnerability_scanning_config {
    enablement_config = "INHERITED"
  }

  cleanup_policy_dry_run = false
  cleanup_policies {
    id     = "delete-untagged"
    action = "DELETE"
    condition {
      tag_state  = "UNTAGGED"
      older_than = "1209600s" # 14 days
    }
  }
  cleanup_policies {
    id     = "keep-recent-releases"
    action = "KEEP"
    most_recent_versions {
      keep_count = 50
    }
  }

  depends_on = [google_kms_crypto_key_iam_member.cmek_agents]
}
