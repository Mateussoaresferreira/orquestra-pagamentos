{{- define "orquestrapay.rotulos" -}}
app.kubernetes.io/part-of: orquestrapay
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end }}

{{- define "orquestrapay.rotulosServico" -}}
{{ include "orquestrapay.rotulos" .raiz }}
app.kubernetes.io/name: {{ .servico.nome }}
app.kubernetes.io/component: {{ .servico.chave }}
{{- end }}
