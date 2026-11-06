{{- define "intimed.name" -}}intimed{{- end -}}
{{- define "intimed.labels" -}}
app.kubernetes.io/name: intimed
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
intimed.io/environment: {{ .Values.environment | lower }}
{{- end -}}
{{- define "intimed.selector" -}}
app.kubernetes.io/name: intimed
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
{{- define "intimed.image" -}}
{{- if .Values.image.digest -}}{{ required "image.repository is required" .Values.image.repository }}@{{ .Values.image.digest }}{{- else -}}{{ required "image.repository is required" .Values.image.repository }}:{{ required "image.tag is required" .Values.image.tag }}{{- end -}}
{{- end -}}
{{- define "intimed.sans" -}}
{{- $s := list .Values.tls.sans -}}{{- if .Values.devices.dnsName -}}{{- $s = append $s .Values.devices.dnsName -}}{{- end -}}{{ join "," $s }}
{{- end -}}

{{- define "intimed.gcp" -}}{{- if eq .Values.cloud "gcp" -}}true{{- end -}}{{- end -}}
{{- define "intimed.dbUrl" -}}
{{- if eq .Values.cloud "gcp" -}}
jdbc:postgresql://{{ required "database.host is required" .Values.database.host }}:5432/{{ .Values.database.name }}?sslmode=verify-ca&sslrootcert=/etc/intimed/pg/server-ca.pem
{{- else -}}
jdbc:postgresql://{{ required "database.host is required" .Values.database.host }}:5432/{{ .Values.database.name }}?{{ .Values.database.params }}
{{- end -}}
{{- end -}}
{{- define "intimed.probeCidrs" -}}
{{- if .Values.networkPolicy.lbProbeCidrs -}}{{ toJson .Values.networkPolicy.lbProbeCidrs }}{{- else if eq .Values.cloud "gcp" -}}["35.191.0.0/16","130.211.0.0/22"]{{- else -}}["168.63.129.16/32"]{{- end -}}
{{- end -}}
