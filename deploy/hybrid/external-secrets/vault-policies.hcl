# HashiCorp Vault policies for WellnessZ (one per identity, least privilege).
# KV v2 mounted at "kv/". Kubernetes auth mounted at "kubernetes-wellnessz/".
#
#   vault auth enable -path=kubernetes-wellnessz kubernetes
#   vault write auth/kubernetes-wellnessz/config kubernetes_host=https://<api>:6443
#   vault policy write wellnessz-core       - <<< "$(sed -n '/^# core/,/^# end core/p' vault-policies.hcl)"
#   vault write auth/kubernetes-wellnessz/role/wellnessz-core \
#       bound_service_account_names=wellnessz-wellnessz-core \
#       bound_service_account_namespaces=wellnessz \
#       audience=vault policies=wellnessz-core ttl=15m
#   (repeat for wellnessz-migrations, wellnessz-db-operator)
#
# Enable an audit device (SOC 2 evidence):
#   vault audit enable file file_path=/vault/audit/audit.log   (ship to SIEM)

# core
path "kv/data/wellnessz/core" {
  capabilities = ["read"]
}
# end core

# migrations
path "kv/data/wellnessz/migrations" {
  capabilities = ["read"]
}
path "kv/data/wellnessz/core" {
  # only the DB CA certificate is needed; split into its own path if required
  capabilities = ["read"]
}
# end migrations

# db-operator (External Secrets in namespace wellnessz-db)
path "kv/data/wellnessz/migrations" {
  capabilities = ["read"]
}
path "kv/data/wellnessz/core" {
  capabilities = ["read"]
}
path "kv/data/wellnessz/backup" {
  capabilities = ["read"]
}
# end db-operator
