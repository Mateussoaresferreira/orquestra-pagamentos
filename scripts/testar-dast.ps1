param(
    [string] $UrlOpenApi = 'http://host.docker.internal:8080/v3/api-docs'
)

$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot
$pastaRelatorios = Join-Path $raiz '.auditoria'
$imagemZap = 'ghcr.io/zaproxy/zaproxy@sha256:781a2bdaea47324e7bab583e2263f21d257b0aee61ed51521a5be45f5f5081ef'
$idEmpresa = [guid]::NewGuid().ToString()
$nomeRelatorio = 'zap-api.json'

New-Item -ItemType Directory -Path $pastaRelatorios -Force | Out-Null

$opcoesZap = @(
    '-config replacer.full_list(0).description=empresa'
    '-config replacer.full_list(0).enabled=true'
    '-config replacer.full_list(0).matchtype=REQ_HEADER'
    '-config replacer.full_list(0).matchstr=X-Empresa-Id'
    "-config replacer.full_list(0).replacement=$idEmpresa"
) -join ' '

Write-Host 'Executando OWASP ZAP contra o contrato OpenAPI local...'
docker run --rm `
    --add-host host.docker.internal:host-gateway `
    -v "${pastaRelatorios}:/zap/wrk/:rw" `
    $imagemZap `
    zap-api-scan.py `
    -t $UrlOpenApi `
    -f openapi `
    -J $nomeRelatorio `
    -r 'zap-api.html' `
    -w 'zap-api.md' `
    -T 10 `
    -I `
    -l WARN `
    -s `
    -z $opcoesZap

if ($LASTEXITCODE -ne 0) {
    throw 'O OWASP ZAP nao conseguiu concluir a varredura dinamica.'
}

$caminhoRelatorio = Join-Path $pastaRelatorios $nomeRelatorio
$relatorio = Get-Content -LiteralPath $caminhoRelatorio -Raw | ConvertFrom-Json
$alertas = @($relatorio.site | ForEach-Object { $_.alerts })
$alertasRelevantes = @($alertas | Where-Object { [int] $_.riskcode -gt 0 })

if ($alertasRelevantes.Count -gt 0) {
    $resumo = $alertasRelevantes |
        ForEach-Object { "[$($_.riskdesc)] $($_.alert)" }
    throw "O ZAP encontrou alertas de risco:`n$($resumo -join [Environment]::NewLine)"
}

Write-Host 'DAST aprovado: nenhum alerta de risco encontrado.' -ForegroundColor Green
Write-Host "Relatorios: $pastaRelatorios"
