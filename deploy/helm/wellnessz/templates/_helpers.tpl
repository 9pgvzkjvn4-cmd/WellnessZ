{{/* ===================== naming & labels ===================== */}}
{{- define "wellnessz.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "wellnessz.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else if contains (include "wellnessz.name" .) .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name (include "wellnessz.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "wellnessz.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Resource name of a component: (dict "root" $ "comp" $comp) */}}
{{- define "wellnessz.componentName" -}}
{{- printf "%s-%s" (include "wellnessz.fullname" .root) .comp.name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "wellnessz.selectorLabels" -}}
app.kubernetes.io/name: {{ include "wellnessz.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "wellnessz.labels" -}}
helm.sh/chart: {{ include "wellnessz.chart" . }}
{{ include "wellnessz.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: wellnessz
wellnessz.io/environment: {{ .Values.global.environment | quote }}
{{- with .Values.global.commonLabels }}
{{ toYaml . }}
{{- end }}
{{- end -}}

{{/* (dict "root" $ "component" "core") */}}
{{- define "wellnessz.componentSelectorLabels" -}}
{{ include "wellnessz.selectorLabels" .root }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{- define "wellnessz.componentLabels" -}}
{{ include "wellnessz.labels" .root }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{/* ===================== images ===================== */}}
{{/* (dict "root" $ "comp" $comp) */}}
{{- define "wellnessz.image" -}}
{{- $g := .root.Values.global -}}
{{- $repo := printf "%s/%s" (trimSuffix "/" $g.imageRegistry) .comp.image.repository -}}
{{- if .comp.image.digest -}}
{{- printf "%s@%s" $repo .comp.image.digest -}}
{{- else -}}
{{- printf "%s:%s" $repo (default (default .root.Chart.AppVersion $g.imageTag) .comp.image.tag) -}}
{{- end -}}
{{- end -}}

{{/* ===================== service accounts ===================== */}}
{{- define "wellnessz.serviceAccountName" -}}
{{- if .comp.serviceAccount.create -}}
{{- default (include "wellnessz.componentName" .) .comp.serviceAccount.name -}}
{{- else -}}
{{- default "default" .comp.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/* Workload identity annotations for an identity key (core|bff|migrations) */}}
{{- define "wellnessz.wiAnnotations" -}}
{{- $wi := .root.Values.workloadIdentity -}}
{{- if and $wi.azure.enabled (index $wi.azure.clientIds .key) }}
azure.workload.identity/client-id: {{ index $wi.azure.clientIds .key | quote }}
{{- with $wi.azure.tenantId }}
azure.workload.identity/tenant-id: {{ . | quote }}
{{- end }}
{{- end }}
{{- if and $wi.gcp.enabled (index $wi.gcp.serviceAccounts .key) }}
iam.gke.io/gcp-service-account: {{ index $wi.gcp.serviceAccounts .key | quote }}
{{- end }}
{{- end -}}

{{/* ===================== pod security ===================== */}}
{{/* (dict "root" $ "comp" $comp) */}}
{{- define "wellnessz.podSecurityContext" -}}
{{- $psc := deepCopy .root.Values.podSecurityContext -}}
{{- if not .root.Values.global.openshift -}}
{{- $_ := set $psc "runAsUser" (int .comp.runAsUser) -}}
{{- $_ := set $psc "runAsGroup" (int .comp.runAsUser) -}}
{{- $_ := set $psc "fsGroup" (int .comp.runAsUser) -}}
{{- $_ := set $psc "fsGroupChangePolicy" "OnRootMismatch" -}}
{{- end -}}
{{- toYaml $psc -}}
{{- end -}}

{{- define "wellnessz.topologySpread" -}}
{{- if .root.Values.topologySpread.enabled }}
topologySpreadConstraints:
  - maxSkew: 1
    topologyKey: topology.kubernetes.io/zone
    whenUnsatisfiable: {{ .root.Values.topologySpread.whenUnsatisfiable }}
    labelSelector:
      matchLabels:
        {{- include "wellnessz.componentSelectorLabels" (dict "root" .root "component" .comp.name) | nindent 8 }}
  - maxSkew: 1
    topologyKey: kubernetes.io/hostname
    whenUnsatisfiable: ScheduleAnyway
    labelSelector:
      matchLabels:
        {{- include "wellnessz.componentSelectorLabels" (dict "root" .root "component" .comp.name) | nindent 8 }}
{{- end }}
{{- end -}}

{{/* ===================== secrets ===================== */}}
{{- define "wellnessz.secretsEnabled" -}}
{{- if ne .Values.secrets.provider "none" -}}true{{- end -}}
{{- end -}}

{{/* Directory where a component's secret files are mounted */}}
{{- define "wellnessz.secretDir" -}}
{{- printf "%s/%s" (trimSuffix "/" .root.Values.secrets.mountPath) .key -}}
{{- end -}}

{{/* CSI volume (dict "root" $ "key" "core") */}}
{{- define "wellnessz.secretVolume" -}}
- name: secrets-{{ .key }}
  csi:
    driver: {{ .root.Values.secrets.csiDriver }}
    readOnly: true
    volumeAttributes:
      secretProviderClass: {{ printf "%s-%s" (include "wellnessz.fullname" .root) .key }}
{{- end -}}

{{- define "wellnessz.bffCoreUrl" -}}
{{- if .Values.bff.env.WZ_CORE_API_URL -}}
{{- .Values.bff.env.WZ_CORE_API_URL -}}
{{- else -}}
{{- printf "http://%s:%d" (include "wellnessz.componentName" (dict "root" . "comp" .Values.core)) (int .Values.core.ports.http) -}}
{{- end -}}
{{- end -}}

{{/* All components that can be iterated generically */}}
{{- define "wellnessz.componentKeys" -}}
core bff employeePortal managerWorkspace ohWorkspace partnerPortal
{{- end -}}

{{/* Flyway CLI container for one migration set.
     (dict "root" $ "set" "core|bff" "cfg" $mig.job.<set> "secretsOn" .. "secretDir" ..) */}}
{{- define "wellnessz.flywayContainer" -}}
{{- $mig := .root.Values.migrations -}}
image: {{ default (printf "%s:%s" $mig.job.image.repository $mig.job.image.tag) $mig.job.image.fullName | quote }}
imagePullPolicy: {{ .root.Values.global.imagePullPolicy }}
command: ["sh", "-c"]
args:
  - >-
    {{- if .secretsOn }}
    export FLYWAY_PASSWORD="$(cat {{ .secretDir }}/wz-migrator-db-password)" &&
    {{- end }}
    exec flyway
    -url="${FLYWAY_URL}"
    -user="${FLYWAY_USER}"
    -locations=filesystem:/flyway/{{ .set }}
    -schemas={{ .cfg.schemas }}
    -defaultSchema={{ first (splitList "," .cfg.schemas) }}
    -createSchemas=true
    -table={{ .cfg.table }}
    -placeholders.app_role={{ .cfg.appRole }}
    -connectRetries=10
    -validateMigrationNaming=true
    {{- range $mig.job.extraArgs }} {{ . }}{{- end }}
    migrate
env:
  - name: FLYWAY_URL
    {{- $url := default .root.Values.core.env.WZ_DB_URL $mig.job.dbUrl }}
    {{- if and .root.Values.core.env.WZ_DB_SSLMODE (not (contains "sslmode=" $url)) }}
    {{- $url = printf "%s%ssslmode=%s" $url (ternary "&" "?" (contains "?" $url)) .root.Values.core.env.WZ_DB_SSLMODE }}
    {{- end }}
    value: {{ $url | quote }}
  - name: FLYWAY_USER
    value: {{ $mig.migratorUsername | quote }}
  {{- if and (not .secretsOn) .root.Values.secrets.existingSecret.migrations }}
  - name: FLYWAY_PASSWORD
    valueFrom:
      secretKeyRef:
        name: {{ .root.Values.secrets.existingSecret.migrations }}
        key: WZ_DB_MIGRATOR_PASSWORD
  {{- end }}
securityContext:
  {{- toYaml .root.Values.containerSecurityContext | nindent 2 }}
resources:
  {{- toYaml $mig.job.resources | nindent 2 }}
volumeMounts:
  - name: sql
    mountPath: /flyway/{{ .set }}
    subPath: {{ .set }}
    readOnly: true
  - name: tmp
    mountPath: /tmp
  {{- if .secretsOn }}
  - name: secrets-migrations
    mountPath: {{ .secretDir }}
    readOnly: true
  {{- end }}
{{- end -}}
