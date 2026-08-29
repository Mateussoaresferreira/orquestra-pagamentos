param(
    [string] $RedeDocker = 'orquestrapay',
    [string] $UrlCheckoutOpenApi = 'http://servico-checkout:8080/v3/api-docs',
    [string] $UrlEstoqueOpenApi = 'http://servico-estoque:8080/v3/api-docs',
    [string] $UrlRiscoOpenApi = 'http://servico-risco:8080/v3/api-docs',
    [string] $UrlPagamentoOpenApi = 'http://servico-pagamento:8080/v3/api-docs',
    [string] $UrlRazaoOpenApi = 'http://servico-razao:8080/v3/api-docs',
    [string] $UrlNotificacaoOpenApi = 'http://servico-notificacao:8080/v3/api-docs'
)

$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot
$pastaRelatorios = Join-Path $raiz '.auditoria'
$imagemZap = 'ghcr.io/zaproxy/zaproxy@sha256:781a2bdaea47324e7bab583e2263f21d257b0aee61ed51521a5be45f5f5081ef'
$idEmpresa = [guid]::NewGuid().ToString()
$alvos = [ordered]@{
    checkout = $UrlCheckoutOpenApi
    estoque = $UrlEstoqueOpenApi
    risco = $UrlRiscoOpenApi
    pagamento = $UrlPagamentoOpenApi
    razao = $UrlRazaoOpenApi
    notificacao = $UrlNotificacaoOpenApi
}

New-Item -ItemType Directory -Path $pastaRelatorios -Force | Out-Null

$opcoesZap = @(
    '-config replacer.full_list(0).description=empresa'
    '-config replacer.full_list(0).enabled=true'
    '-config replacer.full_list(0).matchtype=REQ_HEADER'
    '-config replacer.full_list(0).matchstr=X-Empresa-Id'
    "-config replacer.full_list(0).replacement=$idEmpresa"
) -join ' '

foreach ($alvo in $alvos.GetEnumerator()) {
    $nomeRelatorio = "zap-$($alvo.Key).json"
    Write-Host "Executando OWASP ZAP no servico $($alvo.Key)..." -ForegroundColor Cyan
    docker run --rm `
        --network $RedeDocker `
        -v "${pastaRelatorios}:/zap/wrk/:rw" `
        -v "${raiz}:/zap/orquestrapay:ro" `
        $imagemZap `
        zap-api-scan.py `
        -t $alvo.Value `
        -f openapi `
        -J $nomeRelatorio `
        -r "zap-$($alvo.Key).html" `
        -w "zap-$($alvo.Key).md" `
        -T 10 `
        -I `
        -l WARN `
        -s `
        --hook /zap/orquestrapay/tests/security/configurar-zap.py `
        -z $opcoesZap

    if ($LASTEXITCODE -ne 0) {
        throw "O OWASP ZAP nao conseguiu concluir a varredura de $($alvo.Key)."
    }

    $caminhoRelatorio = Join-Path $pastaRelatorios $nomeRelatorio
    $relatorio = Get-Content -LiteralPath $caminhoRelatorio -Raw | ConvertFrom-Json
    $alertas = @($relatorio.site | ForEach-Object { $_.alerts })
    $alertasRelevantes = @($alertas | Where-Object { [int] $_.riskcode -gt 0 })

    if ($alertasRelevantes.Count -gt 0) {
        $resumo = $alertasRelevantes |
            ForEach-Object {
                $locais = @($_.instances | ForEach-Object {
                    "$($_.method) $(([uri] $_.uri).AbsolutePath), parametro $($_.param)"
                }) -join '; '
                "[$($_.riskdesc)] $($_.alert): $locais"
            }
        throw "O ZAP encontrou alertas em $($alvo.Key):`n$($resumo -join [Environment]::NewLine)"
    }
}

Write-Host 'DAST aprovado nos seis contratos: nenhum alerta de risco encontrado.' -ForegroundColor Green
Write-Host "Relatorios: $pastaRelatorios"
