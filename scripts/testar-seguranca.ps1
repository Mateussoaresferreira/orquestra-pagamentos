param(
    [string] $UrlCheckout = 'http://localhost:8080',
    [string] $UrlEstoque = 'http://localhost:8081',
    [string] $UrlRisco = 'http://localhost:8082',
    [string] $UrlPagamento = 'http://localhost:8083',
    [string] $UrlRazao = 'http://localhost:8084',
    [string] $UrlNotificacao = 'http://localhost:8085',
    [string] $UrlProvedor = 'http://localhost:8090'
)

$ErrorActionPreference = 'Stop'
$idEmpresa = [guid]::NewGuid()
$idOutraEmpresa = [guid]::NewGuid()
$idProduto = [guid]::NewGuid()
$tokenPagamento = "tok_seguranca_$([guid]::NewGuid().ToString('N'))"
$chaveIdempotencia = "seguranca-$([guid]::NewGuid())"
$clienteHostil = "cliente'; DROP TABLE compra; --"

function Garantir {
    param([bool] $Condicao, [string] $Mensagem)
    if (-not $Condicao) {
        throw $Mensagem
    }
}

function Enviar-Http {
    param(
        [string] $Metodo,
        [string] $Url,
        [string] $Corpo,
        [hashtable] $Cabecalhos = @{}
    )

    $parametros = @{
        Method = $Metodo
        Uri = $Url
        Headers = $Cabecalhos
        SkipHttpErrorCheck = $true
    }
    if ($null -ne $Corpo) {
        $parametros.ContentType = 'application/json'
        $parametros.Body = $Corpo
    }
    return Invoke-WebRequest @parametros
}

function Converter-Json {
    param([object] $Valor)
    return $Valor | ConvertTo-Json -Depth 10 -Compress
}

Write-Host '1/9 Conferindo saude e limite de exposicao...' -ForegroundColor Cyan
$servicosHttp = [ordered]@{
    checkout = $UrlCheckout
    estoque = $UrlEstoque
    risco = $UrlRisco
    pagamento = $UrlPagamento
    razao = $UrlRazao
    notificacao = $UrlNotificacao
    provedor = $UrlProvedor
}
foreach ($servico in $servicosHttp.GetEnumerator()) {
    $saude = Enviar-Http GET "$($servico.Value)/actuator/health" $null
    Garantir ($saude.StatusCode -eq 200) "O servico $($servico.Key) nao esta saudavel."
    $politicasRecursos = @($saude.Headers['Cross-Origin-Resource-Policy'])
    Garantir ($politicasRecursos -contains 'same-origin') `
        "O servico $($servico.Key) nao aplicou o cabecalho de isolamento de recursos."
}

$portasPublicas = docker compose ps --format json | ConvertFrom-Json |
    ForEach-Object { $_.Publishers } |
    Where-Object { $_ -and $_.URL -and $_.URL -notin @('127.0.0.1', '::1') }
Garantir (-not $portasPublicas) 'Existe porta do Compose publicada fora do loopback.'

Write-Host '2/9 Validando rejeicao de identificadores malformados...' -ForegroundColor Cyan
$malformada = Enviar-Http GET "$UrlCheckout/api/v1/compras/$([guid]::NewGuid())" $null `
    @{ 'X-Empresa-Id' = "empresa'; DROP TABLE compra; --" }
Garantir ($malformada.StatusCode -eq 400) 'Empresa malformada nao foi rejeitada com HTTP 400.'

Write-Host '3/9 Exercitando SQL injection como dado, sem executar SQL...' -ForegroundColor Cyan
$ajuste = Converter-Json @{
    quantidadeDisponivel = 10
    motivo = 'Preparacao do teste de seguranca'
}
$respostaEstoque = Enviar-Http PUT "$UrlEstoque/api/v1/estoques/$idProduto" $ajuste `
    @{ 'X-Empresa-Id' = $idEmpresa }
Garantir ($respostaEstoque.StatusCode -eq 200) 'Nao foi possivel preparar o estoque.'

$compra = [ordered]@{
    idCliente = $clienteHostil
    emailCliente = 'seguranca@exemplo.com'
    moeda = 'BRL'
    pais = 'BR'
    identificadorDispositivo = 'dispositivo-seguranca'
    tokenPagamento = $tokenPagamento
    itens = @(@{ idProduto = $idProduto; quantidade = 1; precoUnitario = 19.90 })
}
$cabecalhosCompra = @{
    'X-Empresa-Id' = $idEmpresa
    'Idempotency-Key' = $chaveIdempotencia
}
$respostaCompra = Enviar-Http POST "$UrlCheckout/api/v1/compras" `
    (Converter-Json $compra) $cabecalhosCompra
Garantir ($respostaCompra.StatusCode -eq 202) 'A compra de teste nao foi aceita.'
$compraCriada = $respostaCompra.Content | ConvertFrom-Json

$clientePersistido = docker compose exec -T banco-checkout psql `
    -U orquestrapay -d orquestrapay_checkout -tAc `
    "SELECT id_cliente FROM compra WHERE id_compra = '$($compraCriada.idCompra)'"
$tabelaExiste = docker compose exec -T banco-checkout psql `
    -U orquestrapay -d orquestrapay_checkout -tAc `
    "SELECT to_regclass('public.compra') IS NOT NULL"
Garantir ($clientePersistido.Trim() -eq $clienteHostil) 'O texto hostil nao foi armazenado literalmente.'
Garantir ($tabelaExiste.Trim() -eq 't') 'A tabela compra foi afetada pelo teste de injecao.'

Write-Host '4/9 Validando isolamento entre empresas...' -ForegroundColor Cyan
$outraEmpresa = Enviar-Http GET "$UrlCheckout/api/v1/compras/$($compraCriada.idCompra)" $null `
    @{ 'X-Empresa-Id' = $idOutraEmpresa }
Garantir ($outraEmpresa.StatusCode -eq 404) 'Outra empresa conseguiu localizar a compra.'

Write-Host '5/9 Validando conflito de idempotencia...' -ForegroundColor Cyan
$compra.idCliente = 'cliente-diferente'
$conflito = Enviar-Http POST "$UrlCheckout/api/v1/compras" `
    (Converter-Json $compra) $cabecalhosCompra
Garantir ($conflito.StatusCode -eq 409) 'A reutilizacao conflitante da chave nao retornou HTTP 409.'

Write-Host '6/9 Validando limites de corpo e cabecalho...' -ForegroundColor Cyan
$corpoGrande = '{"conteudo":"' + ('a' * 1100000) + '"}'
$corpoRejeitado = Enviar-Http POST "$UrlCheckout/api/v1/compras" $corpoGrande $cabecalhosCompra
Garantir ($corpoRejeitado.StatusCode -eq 413) 'O corpo acima de 1 MiB nao foi rejeitado.'

$cabecalhoGrande = @{
    'X-Empresa-Id' = $idEmpresa
    'X-Teste-Grande' = 'a' * 20000
}
$cabecalhoRejeitado = Enviar-Http GET "$UrlCheckout/actuator/health" $null $cabecalhoGrande
Garantir ($cabecalhoRejeitado.StatusCode -in @(400, 431)) 'O cabecalho excessivo nao foi rejeitado.'

Write-Host '7/9 Validando autenticacao interna e segredos...' -ForegroundColor Cyan
$provedorSemChave = Enviar-Http POST "$UrlProvedor/api/v1/autorizacoes" '{}' @{}
$provedorChaveErrada = Enviar-Http POST "$UrlProvedor/api/v1/autorizacoes" '{}' `
    @{ 'X-Provedor-Api-Key' = 'credencial-incorreta-com-tamanho-suficiente' }
Garantir ($provedorSemChave.StatusCode -eq 401) 'O provedor aceitou chamada sem chave.'
Garantir ($provedorChaveErrada.StatusCode -eq 401) 'O provedor aceitou chave incorreta.'

$redisSemSenha = (docker compose exec -T redis redis-cli PING 2>&1 | Out-String)
Garantir ($redisSemSenha -match 'NOAUTH') 'O Redis respondeu sem exigir autenticacao.'

Write-Host '8/9 Validando protecao do token e ausencia nos logs...' -ForegroundColor Cyan
$tokenPersistido = docker compose exec -T banco-checkout psql `
    -U orquestrapay -d orquestrapay_checkout -tAc `
    "SELECT token_pagamento FROM compra WHERE id_compra = '$($compraCriada.idCompra)'"
Garantir ($tokenPersistido.Trim() -ne $tokenPagamento) 'O token foi persistido em texto puro.'
Garantir ($tokenPersistido.Trim().StartsWith('v1:')) 'O token persistido nao usa o formato AES-GCM atual.'

$logs = docker compose logs --no-color | Out-String
Garantir ($logs -notmatch [regex]::Escape($tokenPagamento)) 'O token de pagamento apareceu nos logs.'

Write-Host '9/9 Validando respostas seguras e integridade dos bancos...' -ForegroundColor Cyan
$jsonMalformado = Enviar-Http POST "$UrlCheckout/api/v1/compras" '{"idCliente":' $cabecalhosCompra
Garantir ($jsonMalformado.StatusCode -eq 400) 'JSON malformado nao foi rejeitado com HTTP 400.'
$erroPublico = $jsonMalformado.Content.ToLowerInvariant()
Garantir ($erroPublico -notmatch 'stacktrace|java\.|org\.springframework|psqlexception|sqlstate') `
    'A resposta de erro vazou detalhes internos da aplicacao.'

$restricoes = @(
    @{ Servico = 'banco-checkout'; Banco = 'orquestrapay_checkout'; Minimo = 5 },
    @{ Servico = 'banco-estoque'; Banco = 'orquestrapay_estoque'; Minimo = 3 },
    @{ Servico = 'banco-risco'; Banco = 'orquestrapay_risco'; Minimo = 3 },
    @{ Servico = 'banco-pagamento'; Banco = 'orquestrapay_pagamento'; Minimo = 6 },
    @{ Servico = 'banco-razao'; Banco = 'orquestrapay_razao'; Minimo = 6 },
    @{ Servico = 'banco-notificacao'; Banco = 'orquestrapay_notificacao'; Minimo = 3 }
)
foreach ($alvo in $restricoes) {
    $quantidadeRestricoes = docker compose exec -T $alvo.Servico psql `
        -U orquestrapay -d $alvo.Banco -tAc `
        "SELECT COUNT(*) FROM pg_constraint WHERE contype = 'c' AND connamespace = 'public'::regnamespace"
    Garantir ([int]$quantidadeRestricoes.Trim() -ge $alvo.Minimo) `
        "As restricoes de integridade nao foram aplicadas em $($alvo.Banco)."
}

Write-Host ''
Write-Host 'Testes adversariais aprovados.' -ForegroundColor Green
[pscustomobject]@{
    compra = $compraCriada.idCompra
    empresa = $idEmpresa
    sqlInjection = 'bloqueada por parametros'
    isolamento = 'aprovado'
    idempotencia = 'aprovada'
    limitesHttp = 'aprovados'
    cabecalhosHttp = 'aplicados em todos os servicos'
    token = 'AES-GCM sem vazamento em logs'
    respostas = 'sem detalhes internos'
    integridade = 'restricoes PostgreSQL ativas'
} | Format-List
