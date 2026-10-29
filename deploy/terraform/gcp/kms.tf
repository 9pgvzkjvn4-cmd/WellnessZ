resource "google_kms_key_ring" "main" {
  name       = "kr-${local.name}"
  location   = var.region
  depends_on = [google_project_service.apis]
}

locals {
  cmek_keys = {
    cloudsql          = "sqladmin.googleapis.com"
    gke-secrets       = "container.googleapis.com"
    artifact-registry = "artifactregistry.googleapis.com"
    secret-manager    = "secretmanager.googleapis.com"
  }
}

resource "google_kms_crypto_key" "cmek" {
  for_each        = local.cmek_keys
  name            = each.key
  key_ring        = google_kms_key_ring.main.id
  purpose         = "ENCRYPT_DECRYPT"
  rotation_period = "7776000s" # 90 days

  version_template {
    algorithm        = "GOOGLE_SYMMETRIC_ENCRYPTION"
    protection_level = "HSM"
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "google_kms_crypto_key_iam_member" "cmek_agents" {
  for_each      = local.cmek_keys
  crypto_key_id = google_kms_crypto_key.cmek[each.key].id
  role          = "roles/cloudkms.cryptoKeyEncrypterDecrypter"
  member        = google_project_service_identity.agents[each.value].member
}

# Binary Authorization attestor signing key (CI signs after scans pass)
resource "google_kms_crypto_key" "binauthz" {
  name     = "binauthz-attestor"
  key_ring = google_kms_key_ring.main.id
  purpose  = "ASYMMETRIC_SIGN"

  version_template {
    algorithm        = "EC_SIGN_P256_SHA256"
    protection_level = "HSM"
  }

  lifecycle {
    prevent_destroy = true
  }
}
