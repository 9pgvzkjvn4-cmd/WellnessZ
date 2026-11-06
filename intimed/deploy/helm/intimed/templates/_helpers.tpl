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
