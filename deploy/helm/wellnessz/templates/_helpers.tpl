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
{{- with .Values.customisation.client }}
wellnessz.io/client: {{ . | quote }}
{{- end }}
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
{{- if and $g.requireImageDigests (not .comp.image.digest) -}}
{{- fail (printf "global.requireImageDigests=true but %s.image.digest is empty - deploy the signed release digest (never a mutable tag)" (default .comp.name .key)) -}}
{{- end -}}
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
{{- $img := $mig.job.image -}}
{{- $ref := ternary (printf "%s@%s" $img.repository $img.digest) (printf "%s:%s" $img.repository $img.tag) (not (empty $img.digest)) -}}
image: {{ default $ref $img.fullName | quote }}
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

{{/* ===================== client customisation ===================== */}}
{{/* Environment variables from customisation.* for core/bff ConfigMaps (dict of NAME -> string) */}}
{{- define "wellnessz.customisationEnv" -}}
{{- $c := .Values.customisation -}}
{{- $env := dict -}}
{{- with $c.client }}{{- $_ := set $env "WZ_CLIENT_ID" . -}}{{- end }}
{{- range $name, $value := $c.featureFlags }}
{{- $key := printf "WZ_FEATURE_%s" (regexReplaceAll "[^A-Z0-9]+" (upper $name) "_" | trimAll "_") -}}
{{- $_ := set $env $key (toString $value) -}}
{{- end }}
{{- toYaml $env -}}
{{- end -}}

{{- define "wellnessz.clientMigrationsEnabled" -}}
{{- $m := .Values.customisation.migrations -}}
{{- if $m.enabled -}}
{{- if not (or $m.image.repository $m.configMap) -}}
{{- fail "customisation.migrations.enabled requires customisation.migrations.image.repository or customisation.migrations.configMap" -}}
{{- end -}}
{{- if and $m.image.repository $m.configMap -}}
{{- fail "customisation.migrations: set either image.repository or configMap, not both" -}}
{{- end -}}
true
{{- end -}}
{{- end -}}

{{- define "wellnessz.clientMigrationSchemas" -}}
{{- $m := .Values.customisation.migrations -}}
{{- if $m.schemas -}}
{{- $m.schemas -}}
{{- else if .Values.customisation.client -}}
{{- printf "client_%s" (regexReplaceAll "[^a-z0-9]+" (lower .Values.customisation.client) "_") -}}
{{- else -}}
{{- fail "customisation.migrations needs customisation.migrations.schemas or customisation.client" -}}
{{- end -}}
{{- end -}}

{{/* Image holding client SQL: full reference when it contains "/", else relative to global.imageRegistry */}}
{{- define "wellnessz.clientMigrationImage" -}}
{{- $i := .Values.customisation.migrations.image -}}
{{- $repo := ternary $i.repository (printf "%s/%s" (trimSuffix "/" .Values.global.imageRegistry) $i.repository) (contains "/" $i.repository) -}}
{{- if $i.digest -}}
{{- printf "%s@%s" $repo $i.digest -}}
{{- else if .Values.global.requireImageDigests -}}
{{- fail "global.requireImageDigests=true but customisation.migrations.image.digest is empty" -}}
{{- else -}}
{{- printf "%s:%s" $repo (required "customisation.migrations.image.tag or digest is required" $i.tag) -}}
{{- end -}}
{{- end -}}

{{/* Init container that copies client SQL into the "sql" volume at /flyway/client (image or ConfigMap source).
     (dict "root" $ "target" "/flyway/client" "volume" "sql" "subPath" "client") */}}
{{- define "wellnessz.copyClientMigrations" -}}
{{- $m := .root.Values.customisation.migrations -}}
- name: copy-client-migrations
  {{- if $m.image.repository }}
  image: {{ include "wellnessz.clientMigrationImage" .root | quote }}
  command: ["sh", "-c", "cp -R {{ $m.image.path }}/. {{ .target }}/"]
  {{- else }}
  image: {{ include "wellnessz.image" (dict "root" .root "comp" .root.Values.core) | quote }}
  # *.sql only: skips the ConfigMap's ..data symlink farm (Flyway would see duplicates)
  command: ["sh", "-c", "cp -L /client-configmap/*.sql {{ .target }}/"]
  {{- end }}
  imagePullPolicy: {{ .root.Values.global.imagePullPolicy }}
  securityContext:
    {{- toYaml .root.Values.containerSecurityContext | nindent 4 }}
  resources:
    requests: { cpu: 50m, memory: 64Mi }
    limits: { memory: 128Mi }
  volumeMounts:
    - name: {{ .volume }}
      mountPath: {{ .target }}
      {{- with .subPath }}
      subPath: {{ . }}
      {{- end }}
    {{- if not $m.image.repository }}
    - name: client-configmap
      mountPath: /client-configmap
      readOnly: true
    {{- end }}
{{- end -}}
