param(
    [string] $UrlCheckout = 'http://localhost:8080',
    [string] $UrlEstoque = 'http://localhost:8081',
    [string] $UrlPagamento = 'http://localhost:8083',
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

function Aguardar-CompraConcluida {
    param([guid] $IdCompra, [hashtable] $Cabecalhos)

    $limite = (Get-Date).AddSeconds($TempoLimiteSegundos)
    do {
        Start-Sleep -Milliseconds 500
        $compra = Invoke-RestMethod -Uri "$UrlCheckout/api/v1/compras/$IdCompra" `
            -Headers $Cabecalhos
        if ($compra.status -in @('RECUSADA', 'COMPENSADA')) {
            throw "A compra $IdCompra terminou indevidamente em $($compra.status): $($compra.motivo)"
        }
        if ($compra.status -eq 'CONCLUIDA') {
            return $compra
        }
    } while ((Get-Date) -lt $limite)

    throw "A compra $IdCompra nao concluiu em $TempoLimiteSegundos segundos."
}

function Criar-CompraAprovada {
    param(
        [guid] $IdEmpresa,
        [guid] $IdProduto,
        [decimal] $Valor,
        [string] $Sufixo
    )

    $cabecalhos = @{
        'X-Empresa-Id' = $IdEmpresa.ToString()
        'Idempotency-Key' = "conciliacao-$Sufixo-$([guid]::NewGuid())"
    }
    $compra = Enviar-Json POST "$UrlCheckout/api/v1/compras" @{
        idCliente = "cliente-$Sufixo"
        emailCliente = "$Sufixo@orquestrapay.local"
        moeda = 'BRL'
        pais = 'BR'
        identificadorDispositivo = "dispositivo-$Sufixo"
        tokenPagamento = 'tok_aprovado'
        metodoPagamento = 'CARTAO'
        parcelas = 1
        itens = @(
            @{
                idProduto = $IdProduto
                quantidade = 1
                precoUnitario = $Valor
            }
        )
    } $cabecalhos
    Aguardar-CompraConcluida $compra.idCompra @{
        'X-Empresa-Id' = $IdEmpresa.ToString()
    } | Out-Null
    return Invoke-RestMethod `
        -Uri "$UrlPagamento/api/v1/pagamentos/compras/$($compra.idCompra)" `
        -Headers @{ 'X-Empresa-Id' = $IdEmpresa.ToString() }
}

$idEmpresa = [guid]::NewGuid()
$idProduto = [guid]::NewGuid()
$idPagamentoFantasma = [guid]::NewGuid()
$cabecalhoEmpresa = @{ 'X-Empresa-Id' = $idEmpresa.ToString() }

Write-Host '1/6 Criando dois pagamentos reais para a mesma empresa e provedor...'
Enviar-Json PUT "$UrlEstoque/api/v1/estoques/$idProduto" @{
    quantidadeDisponivel = 10
    motivo = 'Teste de conciliacao bidirecional'
} $cabecalhoEmpresa | Out-Null
$pagamentoNoExtrato = Criar-CompraAprovada $idEmpresa $idProduto 49.90 'presente'
$pagamentoOmitido = Criar-CompraAprovada $idEmpresa $idProduto 29.90 'omitido'

Garantir ($pagamentoNoExtrato.status -eq 'AUTORIZADO') 'O primeiro pagamento nao foi autorizado.'
Garantir ($pagamentoOmitido.status -eq 'AUTORIZADO') 'O segundo pagamento nao foi autorizado.'
Garantir ($pagamentoNoExtrato.provedor -eq $pagamentoOmitido.provedor) `
    'Os pagamentos reais seguiram para provedores diferentes; repita o teste com os circuitos fechados.'

$agora = (Get-Date).ToUniversalTime()
$identificadorExtrato = "extrato-e2e-$([guid]::NewGuid())"
$registroCorreto = [ordered]@{
    idPagamento = $pagamentoNoExtrato.idPagamento
    valor = $pagamentoNoExtrato.valor
    moeda = $pagamentoNoExtrato.moeda
    status = $pagamentoNoExtrato.status
    idTransacaoProvedor = $pagamentoNoExtrato.idAutorizacao
    ocorridoEm = $pagamentoNoExtrato.atualizadoEm
}
$registroFantasma = [ordered]@{
    idPagamento = $idPagamentoFantasma
    valor = 15.00
    moeda = 'BRL'
    status = 'AUTORIZADO'
    idTransacaoProvedor = "aut-fantasma-$([guid]::NewGuid())"
    ocorridoEm = $agora.ToString('o')
}
$extrato = [ordered]@{
    provedor = $pagamentoNoExtrato.provedor
    identificadorExtrato = $identificadorExtrato
    periodoInicio = $agora.AddHours(-1).ToString('o')
    periodoFim = $agora.AddHours(1).ToString('o')
    moeda = 'BRL'
    registros = @($registroCorreto, $registroCorreto, $registroFantasma)
}

Write-Host '2/6 Enviando extrato com duplicidade, registro fantasma e pagamento local omitido...'
$resultado = Enviar-Json POST "$UrlPagamento/api/v1/conciliacoes" $extrato $cabecalhoEmpresa
Garantir ($resultado.status -eq 'CONCLUIDA_COM_DIVERGENCIAS') 'A conciliacao nao indicou divergencias.'
Garantir ($resultado.registrosProvedor -eq 3) 'A quantidade de registros do provedor esta incorreta.'
Garantir ($resultado.registrosLocais -eq 2) 'A quantidade de pagamentos locais esta incorreta.'
Garantir ($resultado.registrosDuplicados -eq 1) 'A duplicidade do extrato nao foi contabilizada.'
Garantir ($resultado.registrosAnalisados -eq 4) 'A analise bidirecional nao contabilizou os dois lados.'
Garantir ($resultado.divergenciasEncontradas -eq 3) 'A conciliacao nao encontrou exatamente tres divergencias.'
$tipos = $resultado.divergencias -join '|'
Garantir ($tipos.Contains('REGISTRO_DUPLICADO_PROVEDOR')) 'A duplicidade nao foi identificada.'
Garantir ($tipos.Contains('AUSENTE_LOCALMENTE')) 'O pagamento fantasma nao foi identificado.'
Garantir ($tipos.Contains('AUSENTE_NO_PROVEDOR')) 'O pagamento local omitido nao foi identificado.'

Write-Host '3/6 Repetindo exatamente o mesmo extrato...'
$repeticao = Enviar-Json POST "$UrlPagamento/api/v1/conciliacoes" $extrato $cabecalhoEmpresa
Garantir $repeticao.reaproveitada 'A repeticao do extrato nao reaproveitou o resultado.'
Garantir ($repeticao.idConciliacao -eq $resultado.idConciliacao) `
    'A repeticao criou outra execucao de conciliacao.'

Write-Host '4/6 Tentando adulterar um extrato ja processado...'
$extratoAlterado = [ordered]@{
    provedor = $extrato.provedor
    identificadorExtrato = $extrato.identificadorExtrato
    periodoInicio = $extrato.periodoInicio
    periodoFim = $extrato.periodoFim
    moeda = $extrato.moeda
    registros = @(
        [ordered]@{
            idPagamento = $registroCorreto.idPagamento
            valor = 49.91
            moeda = $registroCorreto.moeda
            status = $registroCorreto.status
            idTransacaoProvedor = $registroCorreto.idTransacaoProvedor
            ocorridoEm = $registroCorreto.ocorridoEm
        }
    )
}
$statusConflito = 0
$codigoConflito = ''
try {
    Enviar-Json POST "$UrlPagamento/api/v1/conciliacoes" $extratoAlterado $cabecalhoEmpresa | Out-Null
} catch {
    $statusConflito = [int] $_.Exception.Response.StatusCode
    if ($_.ErrorDetails.Message) {
        $codigoConflito = ($_.ErrorDetails.Message | ConvertFrom-Json).codigo
    }
}
Garantir ($statusConflito -eq 409) 'O extrato alterado nao foi rejeitado com HTTP 409.'
Garantir ($codigoConflito -eq 'extrato-conciliacao-alterado') `
    'O conflito nao retornou o codigo de negocio esperado.'

Write-Host '5/6 Conferindo evidencias diretamente no PostgreSQL...'
$consulta = "SELECT " +
    "(SELECT COUNT(*) FROM conciliacao WHERE id_empresa = '$idEmpresa' AND identificador_extrato = '$identificadorExtrato')," +
    "(SELECT COUNT(*) FROM ocorrencia_conciliacao WHERE id_conciliacao = '$($resultado.idConciliacao)')," +
    "(SELECT COUNT(*) FROM divergencia_conciliacao WHERE id_empresa = '$idEmpresa' AND status = 'ABERTA');"
$auditoria = docker compose exec -T banco-pagamento psql `
    -U orquestrapay -d orquestrapay_pagamento -tA -F ',' -c $consulta
if ($LASTEXITCODE -ne 0) {
    throw 'Nao foi possivel consultar as evidencias da conciliacao no PostgreSQL.'
}
$contagens = ($auditoria | Select-Object -Last 1).Trim() -split ','
Garantir ([int] $contagens[0] -eq 1) 'A idempotencia persistente permitiu mais de uma conciliacao.'
Garantir ([int] $contagens[1] -eq 3) 'As ocorrencias da execucao nao foram auditadas corretamente.'
Garantir ([int] $contagens[2] -eq 3) 'As divergencias operacionais nao foram abertas corretamente.'

Write-Host '6/6 Conciliacao bidirecional validada de ponta a ponta.' -ForegroundColor Green
[pscustomobject]@{
    idEmpresa = $idEmpresa
    idConciliacao = $resultado.idConciliacao
    registrosProvedor = $resultado.registrosProvedor
    registrosLocais = $resultado.registrosLocais
    registrosDuplicados = $resultado.registrosDuplicados
    divergencias = $resultado.divergenciasEncontradas
    repeticaoReaproveitada = $repeticao.reaproveitada
    extratoAlteradoHttp = $statusConflito
    ocorrenciasAuditadas = [int] $contagens[1]
} | Format-List
