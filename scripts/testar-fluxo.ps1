param(
    [string] $UrlCheckout = 'http://localhost:8080',
    [int] $TempoLimiteSegundos = 90
)

$ErrorActionPreference = 'Stop'
$idEmpresa = [guid]::NewGuid()
$idProdutoA = [guid]::NewGuid()
$idProdutoB = [guid]::NewGuid()
$chave = "teste-$([guid]::NewGuid())"
$cabecalhoEmpresa = @{ 'X-Empresa-Id' = $idEmpresa }

function Enviar-Json {
    param([string] $Metodo, [string] $Url, [object] $Corpo, [hashtable] $Cabecalhos = @{})
    $json = $Corpo | ConvertTo-Json -Depth 10
    return Invoke-RestMethod -Method $Metodo -Uri $Url -Headers $Cabecalhos `
        -ContentType 'application/json' -Body $json
}

Write-Host '1/6 Preparando estoque...'
Enviar-Json PUT "http://localhost:8081/api/v1/estoques/$idProdutoA" `
    @{ quantidadeDisponivel = 100; motivo = 'Carga automatizada ponta a ponta' } $cabecalhoEmpresa | Out-Null
Enviar-Json PUT "http://localhost:8081/api/v1/estoques/$idProdutoB" `
    @{ quantidadeDisponivel = 100; motivo = 'Carga automatizada ponta a ponta' } $cabecalhoEmpresa | Out-Null

$compra = [ordered]@{
    idCliente = 'cliente-teste-integrado'
    emailCliente = 'cliente.teste@orquestrapay.local'
    moeda = 'BRL'
    pais = 'BR'
    identificadorDispositivo = "dispositivo-$([guid]::NewGuid())"
    tokenPagamento = 'tok_aprovado'
    itens = @(
        @{ idProduto = $idProdutoA; quantidade = 2; precoUnitario = 79.90 },
        @{ idProduto = $idProdutoB; quantidade = 1; precoUnitario = 39.90 }
    )
}
$cabecalhosCompra = @{
    'X-Empresa-Id' = $idEmpresa
    'Idempotency-Key' = $chave
}

Write-Host '2/6 Iniciando compra...'
$criada = Enviar-Json POST "$UrlCheckout/api/v1/compras" $compra $cabecalhosCompra
$idCompra = $criada.idCompra

Write-Host '3/6 Confirmando idempotencia...'
$repetida = Enviar-Json POST "$UrlCheckout/api/v1/compras" $compra $cabecalhosCompra
if ($repetida.idCompra -ne $idCompra) {
    throw 'A repeticao idempotente criou outra compra.'
}

Write-Host '4/6 Aguardando a saga distribuida...'
$limite = (Get-Date).AddSeconds($TempoLimiteSegundos)
do {
    Start-Sleep -Milliseconds 750
    $estado = Invoke-RestMethod -Uri "$UrlCheckout/api/v1/compras/$idCompra" -Headers $cabecalhoEmpresa
    Write-Host ("  Estado atual: {0}" -f $estado.status)
    if ($estado.status -in @('RECUSADA', 'COMPENSADA')) {
        throw "A compra terminou em $($estado.status): $($estado.motivo)"
    }
} while ($estado.status -ne 'CONCLUIDA' -and (Get-Date) -lt $limite)

if ($estado.status -ne 'CONCLUIDA') {
    throw "A saga nao terminou em $TempoLimiteSegundos segundos."
}

Write-Host '5/6 Consultando evidencias nos seis servicos...'
$historico = Invoke-RestMethod -Uri "$UrlCheckout/api/v1/compras/$idCompra/historico" -Headers $cabecalhoEmpresa
$risco = Invoke-RestMethod -Uri "http://localhost:8082/api/v1/analises-risco/compras/$idCompra" -Headers $cabecalhoEmpresa
$pagamento = Invoke-RestMethod -Uri "http://localhost:8083/api/v1/pagamentos/compras/$idCompra" -Headers $cabecalhoEmpresa
$razao = Invoke-RestMethod -Uri "http://localhost:8084/api/v1/transacoes-contabeis/compras/$idCompra" -Headers $cabecalhoEmpresa
$notificacoes = Invoke-RestMethod -Uri "http://localhost:8085/api/v1/notificacoes/compras/$idCompra" -Headers $cabecalhoEmpresa

if (-not $risco.aprovada -or $pagamento.status -ne 'AUTORIZADO') {
    throw 'Risco ou pagamento nao registrou o resultado esperado.'
}
if ($razao.totalDebitos -ne $razao.totalCreditos) {
    throw 'Os lancamentos contabeis nao estao balanceados.'
}
if ($notificacoes.Count -lt 1) {
    throw 'A notificacao final nao foi gerada.'
}

Write-Host '6/6 Fluxo aprovado.' -ForegroundColor Green
[pscustomobject]@{
    idEmpresa = $idEmpresa
    idCompra = $idCompra
    status = $estado.status
    etapasHistorico = $historico.Count
    pontuacaoRisco = $risco.pontuacao
    statusPagamento = $pagamento.status
    lancamentos = $razao.lancamentos.Count
    notificacoes = $notificacoes.Count
} | Format-List
