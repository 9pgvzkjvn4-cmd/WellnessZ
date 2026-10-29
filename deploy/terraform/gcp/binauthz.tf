# ---------------------------------------------------------------------------
# Binary Authorization: only images attested by CI (after Trivy + signing)
# may run. Start in DRYRUN_AUDIT_LOG_ONLY, switch to ENFORCED once CI attests.
# ---------------------------------------------------------------------------
resource "google_container_analysis_note" "attestor" {
  name = "wellnessz-ci-attestor-note"
  attestation_authority {
    hint {
      human_readable_name = "WellnessZ CI (scanned + signed)"
    }
  }
}

resource "google_binary_authorization_attestor" "ci" {
  name = "wellnessz-ci"
  attestation_authority_note {
    note_reference = google_container_analysis_note.attestor.name
    public_keys {
      id = data.google_kms_crypto_key_version.binauthz.id
      pkix_public_key {
        public_key_pem      = data.google_kms_crypto_key_version.binauthz.public_key[0].pem
        signature_algorithm = data.google_kms_crypto_key_version.binauthz.public_key[0].algorithm
      }
    }
  }
}

data "google_kms_crypto_key_version" "binauthz" {
  crypto_key = google_kms_crypto_key.binauthz.id
}

resource "google_binary_authorization_policy" "main" {
  global_policy_evaluation_mode = "ENABLE"

  # Google-maintained system images
  admission_whitelist_patterns {
    name_pattern = "gcr.io/gke-release/*"
  }
  admission_whitelist_patterns {
    name_pattern = "${var.region}-docker.pkg.dev/${var.project_id}/wellnessz/flyway/*"
  }

  default_admission_rule {
    evaluation_mode         = "REQUIRE_ATTESTATION"
    enforcement_mode        = var.binauthz_enforcement_mode
    require_attestations_by = [google_binary_authorization_attestor.ci.name]
  }
}
