param(
    [string] $UrlCheckout = 'http://localhost:8080',
    [string] $UrlEstoque = 'http://localhost:8081',
    [string] $UrlPagamento = 'http://localhost:8083',
    [string] $UrlProvedorPrincipal = 'http://localhost:8090',
    [string] $UrlProvedorContingencia = 'http://localhost:8091',
    [int] $TempoLimiteSegundos = 120
)

$ErrorActionPreference = 'Stop'

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

function Ler-SegredoLocal {
    param([string] $Nome)

    $arquivoAmbiente = Join-Path $PSScriptRoot '..\.env'
    $linha = Get-Content $arquivoAmbiente | Where-Object { $_ -match "^$([regex]::Escape($Nome))=" } | Select-Object -First 1
    if (-not $linha) {
        throw "A variavel $Nome nao foi encontrada em .env. Execute scripts/iniciar.ps1 primeiro."
    }

    return ($linha -split '=', 2)[1].Trim().Trim('"').Trim("'")
}

function Consultar-Provedor {
    param([string] $Url, [guid] $IdCompra, [hashtable] $Cabecalhos)

    try {
        return [pscustomobject]@{
            statusHttp = 200
            corpo = Invoke-RestMethod -Uri "$Url/api/v1/autorizacoes/compras/$IdCompra" -Headers $Cabecalhos
        }
    } catch {
        $status = [int] $_.Exception.Response.StatusCode
        return [pscustomobject]@{ statusHttp = $status; corpo = $null }
    }
}

$idEmpresa = [guid]::NewGuid()
$idProduto = [guid]::NewGuid()
$chaveIdempotencia = "resposta-ambigua-$([guid]::NewGuid())"
$cabecalhoEmpresa = @{ 'X-Empresa-Id' = $idEmpresa }
$cabecalhosCompra = @{
    'X-Empresa-Id' = $idEmpresa
    'Idempotency-Key' = $chaveIdempotencia
}
$cabecalhoProvedor = @{ 'X-Provedor-Api-Key' = Ler-SegredoLocal 'PROVEDOR_CHAVE_API' }

Write-Host '1/5 Preparando estoque para o teste de resposta perdida...'
Enviar-Json PUT "$UrlEstoque/api/v1/estoques/$idProduto" `
    @{ quantidadeDisponivel = 10; motivo = 'Teste de seguranca contra cobranca dupla' } `
    $cabecalhoEmpresa | Out-Null

Write-Host '2/5 Criando compra cujo provedor processa, mas perde a resposta...'
$compra = Enviar-Json POST "$UrlCheckout/api/v1/compras" @{
    idCliente = 'cliente-resposta-ambigua'
    emailCliente = 'resposta.ambigua@orquestrapay.local'
    moeda = 'BRL'
    pais = 'BR'
    identificadorDispositivo = "dispositivo-$([guid]::NewGuid())"
    tokenPagamento = 'tok_resposta_perdida'
    metodoPagamento = 'CARTAO'
    parcelas = 1
    itens = @(
        @{ idProduto = $idProduto; quantidade = 1; precoUnitario = 79.90 }
    )
} $cabecalhosCompra

Write-Host '3/5 Aguardando a recuperacao idempotente no mesmo provedor...'
$limite = (Get-Date).AddSeconds($TempoLimiteSegundos)
do {
    Start-Sleep -Milliseconds 500
    $estado = Invoke-RestMethod -Uri "$UrlCheckout/api/v1/compras/$($compra.idCompra)" -Headers $cabecalhoEmpresa
    if ($estado.status -in @('RECUSADA', 'COMPENSADA')) {
        throw "A compra terminou indevidamente em $($estado.status): $($estado.motivo)"
    }
} while ($estado.status -ne 'CONCLUIDA' -and (Get-Date) -lt $limite)

Garantir ($estado.status -eq 'CONCLUIDA') "A compra nao concluiu em $TempoLimiteSegundos segundos."

Write-Host '4/5 Conferindo pagamento e autorizacao no provedor principal...'
$pagamento = Invoke-RestMethod -Uri "$UrlPagamento/api/v1/pagamentos/compras/$($compra.idCompra)" `
    -Headers $cabecalhoEmpresa
$principal = Consultar-Provedor $UrlProvedorPrincipal $compra.idCompra $cabecalhoProvedor
$contingencia = Consultar-Provedor $UrlProvedorContingencia $compra.idCompra $cabecalhoProvedor

Garantir ($pagamento.status -eq 'AUTORIZADO') 'O pagamento nao terminou autorizado.'
Garantir ($pagamento.provedor -eq 'principal') 'O pagamento mudou indevidamente de provedor.'
Garantir ($principal.statusHttp -eq 200) 'O provedor principal nao preservou a autorizacao original.'
Garantir ($principal.corpo.aprovada) 'A autorizacao preservada no provedor principal nao esta aprovada.'
Garantir ($principal.corpo.idAutorizacao -eq $pagamento.idAutorizacao) 'O pagamento local divergiu da autorizacao do provedor.'

Write-Host '5/5 Provando que o provedor de contingencia nao realizou cobranca...'
Garantir ($contingencia.statusHttp -eq 404) 'Falha critica: o provedor de contingencia tambem autorizou a compra.'

Write-Host 'Resposta ambigua recuperada sem cobranca dupla.' -ForegroundColor Green
[pscustomobject]@{
    idEmpresa = $idEmpresa
    idCompra = $compra.idCompra
    estadoCompra = $estado.status
    estadoPagamento = $pagamento.status
    provedorPagamento = $pagamento.provedor
    autorizacaoPrincipal = $principal.corpo.idAutorizacao
    autorizacaoContingencia = 'NAO_EXISTE'
} | Format-List
