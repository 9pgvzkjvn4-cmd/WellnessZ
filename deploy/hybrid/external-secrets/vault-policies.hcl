# Per environment: render with WZ_ENV=dev|uat|prod, e.g.
#   WZ_ENV=uat envsubst '$WZ_ENV' < vault-policies.hcl > /tmp/vault-policies.hcl
# (matches kv/data/wellnessz/<env>/... and kubernetes-wellnessz-<env> in helm/wellnessz/environments/hybrid/<env>.yaml)
# HashiCorp Vault policies for WellnessZ (one per identity, least privilege).
# KV v2 mounted at "kv/". Kubernetes auth mounted at "kubernetes-wellnessz-${WZ_ENV}/".
#
#   vault auth enable -path=kubernetes-wellnessz-${WZ_ENV} kubernetes
#   vault write auth/kubernetes-wellnessz-${WZ_ENV}/config kubernetes_host=https://<api>:6443
#   vault policy write wellnessz-core-${WZ_ENV} - <<< "$(sed -n '/^# core/,/^# end core/p' vault-policies.hcl)"
#   vault write auth/kubernetes-wellnessz-${WZ_ENV}/role/wellnessz-core-${WZ_ENV} \
#       bound_service_account_names=wellnessz-core \
#       bound_service_account_namespaces=wellnessz \
#       audience=vault policies=wellnessz-core-${WZ_ENV} ttl=15m
#   (repeat for wellnessz-migrations, wellnessz-db-operator)
#
# Enable an audit device (SOC 2 evidence):
#   vault audit enable file file_path=/vault/audit/audit.log   (ship to SIEM)

# core
path "kv/data/wellnessz/${WZ_ENV}/core" {
  capabilities = ["read"]
}
# end core

# bff (only when the BFF also runs on-prem)
path "kv/data/wellnessz/${WZ_ENV}/bff" {
  capabilities = ["read"]
}
# end bff

# migrations
path "kv/data/wellnessz/${WZ_ENV}/migrations" {
  capabilities = ["read"]
}
path "kv/data/wellnessz/${WZ_ENV}/core" {
  # only the DB CA certificate is needed; split into its own path if required
  capabilities = ["read"]
}
# end migrations

# db-operator (External Secrets in namespace wellnessz-db)
path "kv/data/wellnessz/${WZ_ENV}/migrations" {
  capabilities = ["read"]
}
path "kv/data/wellnessz/${WZ_ENV}/core" {
  capabilities = ["read"]
}
path "kv/data/wellnessz/${WZ_ENV}/bff" {
  capabilities = ["read"]
}
path "kv/data/wellnessz/${WZ_ENV}/backup" {
  capabilities = ["read"]
}
# end db-operator
