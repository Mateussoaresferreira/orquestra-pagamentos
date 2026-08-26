param(
    [string] $UrlCheckout = 'http://localhost:8080',
    [string] $UrlEstoque = 'http://localhost:8081',
    [string] $UrlRisco = 'http://localhost:8082',
    [string] $UrlPagamento = 'http://localhost:8083',
    [string] $UrlProvedorPrincipal = 'http://localhost:8090',
    [string] $UrlProvedorContingencia = 'http://localhost:8091',
    [string] $UrlToxiproxy = 'http://localhost:8474',
    [ValidateRange(60, 600)]
    [int] $TempoLimiteSegundos = 240,
    [ValidateRange(1, 30)]
    [int] $DuracaoMinimaFalhaSegundos = 3
)

$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot
$resultados = [System.Collections.Generic.List[object]]::new()
$proxies = @('kafka', 'banco-risco', 'provedor-principal')
$cabecalhosToxiproxy = @{ 'User-Agent' = 'toxiproxy-cli' }

function Garantir {
    param([bool] $Condicao, [string] $Mensagem)

    if (-not $Condicao) {
        throw $Mensagem
    }
}

function Enviar-Json {
    param(
        [string] $Metodo,
        [string] $Url,
        [object] $Corpo,
        [hashtable] $Cabecalhos = @{}
    )

    return Invoke-RestMethod -Method $Metodo -Uri $Url -Headers $Cabecalhos `
        -ContentType 'application/json' -Body ($Corpo | ConvertTo-Json -Depth 10)
}

function Consultar-Banco {
    param([string] $Servico, [string] $Banco, [string] $Consulta)

    $resultado = docker compose exec -T $Servico psql `
        -U orquestrapay -d $Banco -Atc $Consulta
    Garantir ($LASTEXITCODE -eq 0) "Falha ao consultar o banco $Banco."
    return @($resultado | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}

function Ler-SegredoLocal {
    param([string] $Nome)

    $arquivoAmbiente = Join-Path $raiz '.env'
    $linha = Get-Content $arquivoAmbiente |
        Where-Object { $_ -match "^$([regex]::Escape($Nome))=" } |
        Select-Object -First 1
    if (-not $linha) {
        throw "A variavel $Nome nao foi encontrada no arquivo local de segredos."
    }
    return ($linha -split '=', 2)[1].Trim().Trim('"').Trim("'")
}

function Definir-Proxy {
    param([string] $Nome, [bool] $Habilitado)

    Invoke-RestMethod -Method Post -Uri "$UrlToxiproxy/proxies/$Nome" `
        -Headers $cabecalhosToxiproxy `
        -ContentType 'application/json' `
        -Body (@{ enabled = $Habilitado } | ConvertTo-Json) | Out-Null
}

function Restaurar-Proxies {
    try {
        Invoke-RestMethod -Method Post -Uri "$UrlToxiproxy/reset" `
            -Headers $cabecalhosToxiproxy | Out-Null
    }
    catch {
        Write-Warning "Nao foi possivel restaurar os proxies automaticamente: $($_.Exception.Message)"
    }
}

function Obter-StatusHttp {
    param([string] $Url)

    try {
        $resposta = Invoke-WebRequest -Uri $Url -TimeoutSec 3 -UseBasicParsing
        return [int]$resposta.StatusCode
    }
    catch {
        if ($null -ne $_.Exception.Response) {
            return [int]$_.Exception.Response.StatusCode
        }
        return 0
    }
}

function Aguardar-StatusHttp {
    param(
        [string] $Descricao,
        [string] $Url,
        [int[]] $Esperados,
        [int] $LimiteSegundos = 60
    )

    $limite = [DateTimeOffset]::UtcNow.AddSeconds($LimiteSegundos)
    do {
        $status = Obter-StatusHttp $Url
        if ($status -in $Esperados) {
            return $status
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTimeOffset]::UtcNow -lt $limite)
    throw "$Descricao nao apresentou HTTP $($Esperados -join '/') dentro de $LimiteSegundos segundos."
}

function Preparar-Produto {
    param([guid] $IdEmpresa, [guid] $IdProduto, [string] $Motivo)

    Enviar-Json PUT "$UrlEstoque/api/v1/estoques/$IdProduto" `
        @{ quantidadeDisponivel = 100; motivo = $Motivo } `
        @{ 'X-Empresa-Id' = $IdEmpresa } | Out-Null
}

function Criar-Compra {
    param(
        [guid] $IdEmpresa,
        [guid] $IdProduto,
        [string] $TokenPagamento,
        [string] $Sufixo
    )

    $cabecalhos = @{
        'X-Empresa-Id' = $IdEmpresa
        'Idempotency-Key' = "caos-$Sufixo-$([guid]::NewGuid())"
    }
    return Enviar-Json POST "$UrlCheckout/api/v1/compras" @{
        idCliente = "cliente-caos-$Sufixo"
        emailCliente = "caos.$Sufixo@orquestrapay.local"
        moeda = 'BRL'
        pais = 'BR'
        identificadorDispositivo = "dispositivo-caos-$([guid]::NewGuid())"
        tokenPagamento = $TokenPagamento
        metodoPagamento = 'CARTAO'
        parcelas = 1
        itens = @(
            @{ idProduto = $IdProduto; quantidade = 1; precoUnitario = 49.90 }
        )
    } $cabecalhos
}

function Aguardar-CompraConcluida {
    param([guid] $IdEmpresa, [guid] $IdCompra)

    $cabecalhos = @{ 'X-Empresa-Id' = $IdEmpresa }
    $inicio = [DateTimeOffset]::UtcNow
    $limite = $inicio.AddSeconds($TempoLimiteSegundos)
    do {
        Start-Sleep -Milliseconds 500
        $estado = Invoke-RestMethod -Uri "$UrlCheckout/api/v1/compras/$IdCompra" `
            -Headers $cabecalhos
        if ($estado.status -in @('RECUSADA', 'COMPENSADA')) {
            throw "A compra $IdCompra terminou em $($estado.status): $($estado.motivo)"
        }
        if ($estado.status -eq 'CONCLUIDA') {
            return [pscustomobject]@{
                estado = $estado
                duracaoMs = [long]([DateTimeOffset]::UtcNow - $inicio).TotalMilliseconds
            }
        }
    } while ([DateTimeOffset]::UtcNow -lt $limite)
    throw "A compra $IdCompra nao concluiu em $TempoLimiteSegundos segundos."
}

function Aguardar-PagamentoRegistrado {
    param([guid] $IdCompra, [int] $LimiteSegundos = 45)

    $limite = [DateTimeOffset]::UtcNow.AddSeconds($LimiteSegundos)
    do {
        $linhas = @(Consultar-Banco banco-pagamento orquestrapay_pagamento @"
SELECT status || '|' || COALESCE(provedor, '')
FROM pagamento
WHERE id_compra = '$IdCompra'::uuid;
"@)
        if ($linhas.Count -gt 0) {
            return @($linhas[0] -split '\|')
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTimeOffset]::UtcNow -lt $limite)
    throw "O pagamento da compra $IdCompra nao foi registrado dentro do prazo."
}

function Validar-EfeitoUnico {
    param([guid] $IdCompra)

    $pagamentos = @(Consultar-Banco banco-pagamento orquestrapay_pagamento `
        "SELECT COUNT(*) FROM pagamento WHERE id_compra = '$IdCompra'::uuid;")
    $transacoes = @(Consultar-Banco banco-razao orquestrapay_razao `
        "SELECT COUNT(*) FROM transacao_contabil WHERE id_compra = '$IdCompra'::uuid;")
    $comparacoes = @(Consultar-Banco banco-risco orquestrapay_risco `
        "SELECT COUNT(*) FROM comparacao_modelos_risco WHERE id_compra = '$IdCompra'::uuid;")
    Garantir ([int]$pagamentos[0] -eq 1) "A compra $IdCompra nao possui exatamente um pagamento."
    Garantir ([int]$transacoes[0] -eq 1) "A compra $IdCompra nao possui exatamente uma transacao contabil."
    Garantir ([int]$comparacoes[0] -eq 1) "A compra $IdCompra nao possui exatamente uma comparacao de modelos."
}

function Consultar-Provedor {
    param([string] $Url, [guid] $IdCompra, [hashtable] $Cabecalhos)

    try {
        return [pscustomobject]@{
            statusHttp = 200
            corpo = Invoke-RestMethod `
                -Uri "$Url/api/v1/autorizacoes/compras/$IdCompra" `
                -Headers $Cabecalhos
        }
    }
    catch {
        $status = if ($null -ne $_.Exception.Response) {
            [int]$_.Exception.Response.StatusCode
        } else { 0 }
        return [pscustomobject]@{ statusHttp = $status; corpo = $null }
    }
}

Push-Location $raiz
try {
    Write-Host '0/4 Validando o laboratorio de caos...' -ForegroundColor Cyan
    $configurados = Invoke-RestMethod -Uri "$UrlToxiproxy/proxies" `
        -Headers $cabecalhosToxiproxy
    foreach ($proxy in $proxies) {
        Garantir ($null -ne $configurados.$proxy) "O proxy $proxy nao esta configurado."
        Definir-Proxy $proxy $true
    }

    $idEmpresa = [guid]::NewGuid()
    $produtos = 1..4 | ForEach-Object { [guid]::NewGuid() }
    for ($indice = 0; $indice -lt $produtos.Count; $indice++) {
        Preparar-Produto $idEmpresa $produtos[$indice] "Cenario de caos $($indice + 1)"
    }
    Start-Sleep -Seconds 2

    Write-Host '1/4 Interrompendo Kafka e comprovando a drenagem da outbox...' -ForegroundColor Cyan
    $inicioFalha = [DateTimeOffset]::UtcNow
    Definir-Proxy 'kafka' $false
    $compraKafka = Criar-Compra $idEmpresa $produtos[0] 'tok_aprovado' 'kafka'
    Start-Sleep -Seconds $DuracaoMinimaFalhaSegundos
    $pendentes = @(Consultar-Banco banco-checkout orquestrapay_checkout @"
SELECT COUNT(*)
FROM evento_saida
WHERE id_compra = '$($compraKafka.idCompra)'::uuid
  AND publicado_em IS NULL
  AND descartado_em IS NULL;
"@)
    Garantir ([int]$pendentes[0] -gt 0) 'A outbox nao reteve o evento durante a queda do Kafka.'
    $inicioRecuperacao = [DateTimeOffset]::UtcNow
    Definir-Proxy 'kafka' $true
    $conclusaoKafka = Aguardar-CompraConcluida $idEmpresa $compraKafka.idCompra
    Validar-EfeitoUnico $compraKafka.idCompra
    $pendentesDepois = @(Consultar-Banco banco-checkout orquestrapay_checkout @"
SELECT COUNT(*) FROM evento_saida
WHERE id_compra = '$($compraKafka.idCompra)'::uuid AND publicado_em IS NULL;
"@)
    Garantir ([int]$pendentesDepois[0] -eq 0) 'A outbox nao foi drenada depois da volta do Kafka.'
    $resultados.Add([pscustomobject]@{
        cenario = 'kafka-outbox'
        idCompra = $compraKafka.idCompra
        falhaObservada = 'evento-retido-na-outbox'
        duracaoFalhaMs = [long]($inicioRecuperacao - $inicioFalha).TotalMilliseconds
        recuperacaoMs = $conclusaoKafka.duracaoMs
        resultado = 'CONCLUIDA_SEM_DUPLICIDADE'
    })

    Write-Host '2/4 Interrompendo o banco de risco e comprovando a recuperacao...' -ForegroundColor Cyan
    $inicioFalha = [DateTimeOffset]::UtcNow
    Definir-Proxy 'banco-risco' $false
    $statusIndisponivel = Aguardar-StatusHttp `
        'A saude do servico de risco' `
        "$UrlRisco/actuator/health" `
        @(0, 500, 503) `
        30
    Start-Sleep -Seconds $DuracaoMinimaFalhaSegundos
    $inicioRecuperacao = [DateTimeOffset]::UtcNow
    Definir-Proxy 'banco-risco' $true
    $statusRecuperado = Aguardar-StatusHttp `
        'A recuperacao do servico de risco' `
        "$UrlRisco/actuator/health" `
        @(200) `
        60
    $fimRecuperacao = [DateTimeOffset]::UtcNow
    $compraBanco = Criar-Compra $idEmpresa $produtos[1] 'tok_aprovado' 'banco-risco'
    $conclusaoBanco = Aguardar-CompraConcluida $idEmpresa $compraBanco.idCompra
    Validar-EfeitoUnico $compraBanco.idCompra
    $resultados.Add([pscustomobject]@{
        cenario = 'banco-risco'
        idCompra = $compraBanco.idCompra
        falhaObservada = "health-http-$statusIndisponivel"
        duracaoFalhaMs = [long]($inicioRecuperacao - $inicioFalha).TotalMilliseconds
        recuperacaoMs = [long]($fimRecuperacao - $inicioRecuperacao).TotalMilliseconds
        statusRecuperado = $statusRecuperado
        resultado = 'CONCLUIDA_APOS_RECUPERACAO'
    })

    Write-Host '3/4 Simulando resposta ambigua do provedor sem cobranca dupla...' -ForegroundColor Cyan
    $inicioFalha = [DateTimeOffset]::UtcNow
    Definir-Proxy 'provedor-principal' $false
    $compraProvedor = Criar-Compra $idEmpresa $produtos[2] 'tok_aprovado' 'provedor'
    $estadoIntermediario = Aguardar-PagamentoRegistrado $compraProvedor.idCompra
    Start-Sleep -Seconds $DuracaoMinimaFalhaSegundos
    $inicioRecuperacao = [DateTimeOffset]::UtcNow
    Definir-Proxy 'provedor-principal' $true
    $conclusaoProvedor = Aguardar-CompraConcluida $idEmpresa $compraProvedor.idCompra
    Validar-EfeitoUnico $compraProvedor.idCompra
    $cabecalhoEmpresa = @{ 'X-Empresa-Id' = $idEmpresa }
    $pagamento = Invoke-RestMethod `
        -Uri "$UrlPagamento/api/v1/pagamentos/compras/$($compraProvedor.idCompra)" `
        -Headers $cabecalhoEmpresa
    Garantir ($pagamento.status -eq 'AUTORIZADO') 'O pagamento nao se recuperou como AUTORIZADO.'
    $cabecalhoProvedor = @{ 'X-Provedor-Api-Key' = Ler-SegredoLocal 'PROVEDOR_CHAVE_API' }
    $principal = Consultar-Provedor $UrlProvedorPrincipal $compraProvedor.idCompra $cabecalhoProvedor
    $contingencia = Consultar-Provedor $UrlProvedorContingencia $compraProvedor.idCompra $cabecalhoProvedor
    Garantir ($principal.statusHttp -eq 200) 'O provedor principal nao preservou a unica autorizacao.'
    Garantir ($contingencia.statusHttp -eq 404) 'Uma resposta ambigua provocou cobranca no segundo provedor.'
    $resultados.Add([pscustomobject]@{
        cenario = 'provedor-resposta-ambigua'
        idCompra = $compraProvedor.idCompra
        falhaObservada = "pagamento-$($estadoIntermediario[0])"
        duracaoFalhaMs = [long]($inicioRecuperacao - $inicioFalha).TotalMilliseconds
        recuperacaoMs = $conclusaoProvedor.duracaoMs
        provedorFinal = $pagamento.provedor
        resultado = 'AUTORIZADA_UMA_UNICA_VEZ'
    })

    Write-Host '4/4 Validando indisponibilidade confirmada e fallback seguro...' -ForegroundColor Cyan
    $compraFallback = Criar-Compra $idEmpresa $produtos[3] 'tok_fallback' 'fallback'
    $conclusaoFallback = Aguardar-CompraConcluida $idEmpresa $compraFallback.idCompra
    Validar-EfeitoUnico $compraFallback.idCompra
    $pagamentoFallback = Invoke-RestMethod `
        -Uri "$UrlPagamento/api/v1/pagamentos/compras/$($compraFallback.idCompra)" `
        -Headers $cabecalhoEmpresa
    Garantir ($pagamentoFallback.provedor -eq 'contingencia') `
        'A indisponibilidade confirmada nao direcionou o pagamento para a contingencia.'
    $principalFallback = Consultar-Provedor `
        $UrlProvedorPrincipal $compraFallback.idCompra $cabecalhoProvedor
    $contingenciaFallback = Consultar-Provedor `
        $UrlProvedorContingencia $compraFallback.idCompra $cabecalhoProvedor
    Garantir ($principalFallback.statusHttp -eq 404) `
        'O provedor principal registrou uma autorizacao que havia confirmado nao processar.'
    Garantir ($contingenciaFallback.statusHttp -eq 200) `
        'O provedor de contingencia nao registrou a autorizacao esperada.'
    $resultados.Add([pscustomobject]@{
        cenario = 'provedor-fallback-confirmado'
        idCompra = $compraFallback.idCompra
        falhaObservada = 'principal-http-503-nao-processada'
        recuperacaoMs = $conclusaoFallback.duracaoMs
        provedorFinal = $pagamentoFallback.provedor
        resultado = 'FALLBACK_SEGURO'
    })

    & (Join-Path $PSScriptRoot 'auditar-consistencia.ps1') -EsperaSegundos 2
    Garantir ($LASTEXITCODE -eq 0) 'A auditoria distribuida falhou apos os cenarios de caos.'

    $diretorioAuditoria = Join-Path $raiz '.auditoria'
    New-Item -ItemType Directory -Force -Path $diretorioAuditoria | Out-Null
    $instante = [DateTimeOffset]::UtcNow
    $arquivoRelatorio = Join-Path `
        $diretorioAuditoria `
        "caos-recuperacao-$($instante.ToString('yyyyMMdd-HHmmss')).json"
    [pscustomobject]@{
        executadoEm = $instante.ToString('O')
        ferramenta = 'Toxiproxy 2.12.0'
        cenarios = $resultados
        consistenciaDistribuida = 'APROVADA'
    } | ConvertTo-Json -Depth 8 | Set-Content -Path $arquivoRelatorio -Encoding utf8

    Write-Host ''
    Write-Host 'Caos e recuperacao aprovados.' -ForegroundColor Green
    $resultados | Format-Table -AutoSize
    Write-Host "Relatorio: $arquivoRelatorio" -ForegroundColor DarkGray
}
finally {
    Restaurar-Proxies
    Pop-Location
}
