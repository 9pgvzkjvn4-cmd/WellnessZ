#!/usr/bin/env bash
# Step 1 – checks before the first `terraform apply` in Qatar Central: tools, login, providers, VM sizes per zone,
# vCPU quota, PostgreSQL SKU and zone-redundant HA availability. Usage: ENV=uat deploy/azure/scripts/preflight.sh
source "$(dirname "$0")/common.sh"
need az terraform helm kubectl jq openssl
LOC=qatarcentral
log "Azure account"
az account show --query '{subscription:name, id:id, tenant:tenantId, user:user.name}' -o table
log "Resource providers"
for p in Microsoft.ContainerService Microsoft.DBforPostgreSQL Microsoft.KeyVault Microsoft.ContainerRegistry Microsoft.Network \
         Microsoft.Storage Microsoft.OperationalInsights Microsoft.OperationsManagement Microsoft.Monitor Microsoft.Insights \
         Microsoft.Security Microsoft.SecurityInsights Microsoft.PolicyInsights Microsoft.ManagedIdentity Microsoft.Consumption; do
  s=$(az provider show -n "$p" --query registrationState -o tsv 2>/dev/null || echo NotFound)
  if [ "$s" != "Registered" ]; then echo "registering $p ($s)"; az provider register -n "$p" >/dev/null; else echo "ok  $p"; fi
done
az feature register --namespace Microsoft.ContainerService --name EnableWorkloadIdentityPreview >/dev/null 2>&1 || true
log "VM sizes in $LOC (zones)"
VARS="$TF_DIR/envs/$ENV.tfvars"
for size in $(grep -E '^(system_node_vm_size|app_node_vm_size)' "$VARS" | sed -E 's/.*"(.*)"/\1/') Standard_D2s_v5; do
  z=$(az vm list-skus -l $LOC --size "$size" --query "[0].locationInfo[0].zones" -o tsv | tr '\t' ',')
  r=$(az vm list-skus -l $LOC --size "$size" --query "[0].restrictions[0].reasonCode" -o tsv)
  echo "$size zones=[${z:-none}] restriction=${r:-none}"
done
log "vCPU quota (Dsv5 family, regional)"
az vm list-usage -l $LOC --query "[?contains(name.value,'standardDSv5Family') || name.value=='cores'].{name:name.localizedValue,used:currentValue,limit:limit}" -o table
log "PostgreSQL flexible server: SKU and HA support in $LOC"
SKU=$(grep -E '^postgres_sku' "$VARS" | sed -E 's/.*"(.*)"/\1/'); SKU=${SKU#GP_}
az postgres flexible-server list-skus -l $LOC -o json > /tmp/pg-skus.$$.json
jq -r --arg s "$SKU" '.[0].supportedFlexibleServerEditions[]? | select(.name=="GeneralPurpose") | .supportedServerSkus[]? | select(.name==$s) |
   "sku=\(.name) vCores=\(.vCores) haModes=\(.supportedHaMode // "n/a") zones=\(.supportedZones // "n/a")"' /tmp/pg-skus.$$.json || true
jq -r '.[0] | "zoneRedundantHaSupported=\(.zoneRedundantHaSupported // "unknown") zoneRedundantHaAndGeoBackupSupported=\(.zoneRedundantHaAndGeoBackupSupported // "unknown")"' /tmp/pg-skus.$$.json || true
rm -f /tmp/pg-skus.$$.json
echo "→ if ZoneRedundant is not listed for $SKU, set postgres_ha_mode = \"SameZone\" in envs/$ENV.tfvars"
log "Kubernetes versions"
az aks get-versions -l $LOC --query "values[?isDefault].version" -o tsv | head -1 | sed 's/^/default: /'
echo; echo "Preflight finished – review the output above before terraform apply."
