{{/*
NetworkPolicy helpers.
An empty or missing from/to list in a NetworkPolicy rule means "all peers",
so every helper below renders NOTHING when it has no peers (fail closed).
*/}}

{{- define "wellnessz.np.peerFromSelectors" -}}
{{- if or .namespaceSelector .podSelector }}
- {{- with .namespaceSelector }}
  namespaceSelector:
    {{- toYaml . | nindent 4 }}
  {{- end }}
  {{- with .podSelector }}
  podSelector:
    {{- toYaml . | nindent 4 }}
  {{- end }}
{{- end }}
{{- end -}}

{{- define "wellnessz.np.ingressControllerPeers" -}}
{{- include "wellnessz.np.peerFromSelectors" (dict "namespaceSelector" .ingressController.namespaceSelector "podSelector" .ingressController.podSelector) }}
{{- range .ingressController.ipBlocks }}
- ipBlock:
    cidr: {{ . }}
{{- end }}
{{- end -}}

{{- define "wellnessz.np.componentPeer" -}}
- podSelector:
    matchLabels:
      {{- include "wellnessz.componentSelectorLabels" (dict "root" .root "component" .component) | nindent 6 }}
{{- end -}}

{{/* (dict "peers" <rendered peer list> "port" <int>) -> ingress rule or nothing */}}
{{- define "wellnessz.np.ingressRule" -}}
{{- if trim .peers }}
- from:
    {{- .peers | nindent 4 }}
  ports:
    - protocol: TCP
      port: {{ .port }}
{{- end }}
{{- end -}}

{{- define "wellnessz.np.dbEgress" -}}
{{- $db := .database -}}
{{- $peers := include "wellnessz.np.peerFromSelectors" (dict "namespaceSelector" $db.namespaceSelector "podSelector" $db.podSelector) -}}
{{- if or $db.cidrs (trim $peers) }}
- to:
    {{- range $db.cidrs }}
    - ipBlock:
        cidr: {{ . }}
    {{- end }}
    {{- with trim $peers }}
    {{- . | nindent 4 }}
    {{- end }}
  ports:
    - protocol: TCP
      port: {{ $db.port }}
{{- end }}
{{- end -}}

{{- define "wellnessz.np.httpsEgress" -}}
{{- if .https.cidrs }}
- to:
    {{- range .https.cidrs }}
    - ipBlock:
        cidr: {{ . }}
        {{- if and $.https.except (eq . "0.0.0.0/0") }}
        except:
          {{- toYaml $.https.except | nindent 10 }}
        {{- end }}
    {{- end }}
  ports:
    - protocol: TCP
      port: 443
{{- end }}
{{- end -}}

{{- define "wellnessz.np.otelEgress" -}}
{{- if .otel.enabled }}
{{- $peers := include "wellnessz.np.peerFromSelectors" (dict "namespaceSelector" .otel.namespaceSelector "podSelector" .otel.podSelector) -}}
{{- if trim $peers }}
- to:
    {{- trim $peers | nindent 4 }}
  ports:
    {{- range .otel.ports }}
    - protocol: TCP
      port: {{ . }}
    {{- end }}
{{- end }}
{{- end }}
{{- end -}}

{{- define "wellnessz.np.monitoringIngress" -}}
{{- if and .np.monitoring.enabled .np.monitoring.namespaceSelector }}
- from:
    - namespaceSelector:
        {{- toYaml .np.monitoring.namespaceSelector | nindent 8 }}
  ports:
    - protocol: TCP
      port: {{ .port }}
{{- end }}
{{- end -}}
