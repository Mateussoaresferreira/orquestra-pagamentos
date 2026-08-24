param(
    [string] $UrlCheckout = 'http://localhost:8080',
    [string] $UrlEstoque = 'http://localhost:8081',
    [string] $UrlRisco = 'http://localhost:8082',
    [string] $UrlPagamento = 'http://localhost:8083',
    [string] $UrlRazao = 'http://localhost:8084',
    [int] $TempoLimiteSegundos = 120
)

$ErrorActionPreference = 'Stop'
$idEmpresa = [guid]::NewGuid()
$cabecalhoEmpresa = @{ 'X-Empresa-Id' = $idEmpresa }
$resultados = [System.Collections.Generic.List[object]]::new()

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

function Preparar-Estoque {
    param([guid] $IdProduto, [int] $Quantidade)

    Enviar-Json PUT "$UrlEstoque/api/v1/estoques/$IdProduto" `
        @{ quantidadeDisponivel = $Quantidade; motivo = 'Teste automatizado de cenarios' } `
        $cabecalhoEmpresa | Out-Null
}

function Criar-CorpoCompra {
    param(
        [guid] $IdProduto,
        [int] $Quantidade,
        [decimal] $Preco,
        [string] $Token,
        [string] $Moeda = 'BRL',
        [string] $Pais = 'BR',
        [string] $Cliente = 'cliente-automatizado',
        [string] $Dispositivo = 'dispositivo-automatizado'
    )

    return [ordered]@{
        idCliente = $Cliente
        emailCliente = "$Cliente@orquestrapay.local"
        moeda = $Moeda
        pais = $Pais
        identificadorDispositivo = $Dispositivo
        tokenPagamento = $Token
        itens = @(
            @{ idProduto = $IdProduto; quantidade = $Quantidade; precoUnitario = $Preco }
        )
    }
}

function Iniciar-Compra {
    param([object] $Corpo, [string] $Chave)

    $cabecalhos = @{
        'X-Empresa-Id' = $idEmpresa
        'Idempotency-Key' = $Chave
    }
    return Enviar-Json POST "$UrlCheckout/api/v1/compras" $Corpo $cabecalhos
}

function Aguardar-Estado {
    param([guid] $IdCompra, [string[]] $Esperados)

    $limite = (Get-Date).AddSeconds($TempoLimiteSegundos)
    do {
        Start-Sleep -Milliseconds 500
        $compra = Invoke-RestMethod -Uri "$UrlCheckout/api/v1/compras/$IdCompra" `
            -Headers $cabecalhoEmpresa
        if ($compra.status -in $Esperados) {
            return $compra
        }
    } while ((Get-Date) -lt $limite)

    throw "A compra $IdCompra nao chegou a $($Esperados -join ', ') em $TempoLimiteSegundos segundos. Ultimo estado: $($compra.status)"
}

function Aguardar-Estoque-Livre {
    param([guid] $IdProduto, [int] $QuantidadeEsperada)

    $limite = (Get-Date).AddSeconds($TempoLimiteSegundos)
    do {
        Start-Sleep -Milliseconds 300
        $estoque = Invoke-RestMethod -Uri "$UrlEstoque/api/v1/estoques/$IdProduto" `
            -Headers $cabecalhoEmpresa
        if ($estoque.quantidadeDisponivel -eq $QuantidadeEsperada `
                -and $estoque.quantidadeReservada -eq 0) {
            return $estoque
        }
    } while ((Get-Date) -lt $limite)

    throw "O estoque do produto $IdProduto nao foi liberado."
}

function Registrar-Resultado {
    param([string] $Cenario, [guid] $IdCompra, [string] $Estado)
    $resultados.Add([pscustomobject]@{
        cenario = $Cenario
        idCompra = $IdCompra
        estado = $Estado
    })
}

Write-Host '1/6 Compra aprovada e idempotencia...' -ForegroundColor Cyan
$produtoAprovado = [guid]::NewGuid()
Preparar-Estoque $produtoAprovado 20
$corpoAprovado = Criar-CorpoCompra $produtoAprovado 2 79.90 'tok_aprovado' `
    -Cliente 'cliente-aprovado' -Dispositivo "disp-$([guid]::NewGuid())"
$chaveAprovada = "aprovada-$([guid]::NewGuid())"
$aprovada = Iniciar-Compra $corpoAprovado $chaveAprovada
$repetida = Iniciar-Compra $corpoAprovado $chaveAprovada
Garantir ($aprovada.idCompra -eq $repetida.idCompra) 'O replay idempotente criou outra compra.'
$estadoAprovado = Aguardar-Estado $aprovada.idCompra @('CONCLUIDA')
$razao = Invoke-RestMethod -Uri "$UrlRazao/api/v1/transacoes-contabeis/compras/$($aprovada.idCompra)" `
    -Headers $cabecalhoEmpresa
Garantir ($razao.totalDebitos -eq $razao.totalCreditos) 'A transacao contabil aprovada ficou desbalanceada.'
Registrar-Resultado 'Compra aprovada e replay idempotente' $aprovada.idCompra $estadoAprovado.status

Write-Host '2/6 Provedor instavel recuperado por retentativa...' -ForegroundColor Cyan
$produtoInstavel = [guid]::NewGuid()
Preparar-Estoque $produtoInstavel 10
$corpoInstavel = Criar-CorpoCompra $produtoInstavel 1 49.90 'tok_instavel' `
    -Cliente 'cliente-instavel' -Dispositivo "disp-$([guid]::NewGuid())"
$instavel = Iniciar-Compra $corpoInstavel "instavel-$([guid]::NewGuid())"
$estadoInstavel = Aguardar-Estado $instavel.idCompra @('CONCLUIDA')
Registrar-Resultado 'Retentativa do provedor' $instavel.idCompra $estadoInstavel.status

Write-Host '3/6 Recusa por estoque insuficiente...' -ForegroundColor Cyan
$produtoSemSaldo = [guid]::NewGuid()
Preparar-Estoque $produtoSemSaldo 1
$corpoSemSaldo = Criar-CorpoCompra $produtoSemSaldo 2 39.90 'tok_aprovado' `
    -Cliente 'cliente-sem-saldo' -Dispositivo "disp-$([guid]::NewGuid())"
$semSaldo = Iniciar-Compra $corpoSemSaldo "sem-saldo-$([guid]::NewGuid())"
$estadoSemSaldo = Aguardar-Estado $semSaldo.idCompra @('RECUSADA')
Registrar-Resultado 'Estoque insuficiente' $semSaldo.idCompra $estadoSemSaldo.status

Write-Host '4/6 Recusa por risco e liberacao de estoque...' -ForegroundColor Cyan
$produtoRisco = [guid]::NewGuid()
Preparar-Estoque $produtoRisco 10
$corpoRisco = Criar-CorpoCompra $produtoRisco 1 6001.00 'tok_aprovado' `
    -Pais 'US' -Cliente 'cliente-risco' -Dispositivo "disp-$([guid]::NewGuid())"
$risco = Iniciar-Compra $corpoRisco "risco-$([guid]::NewGuid())"
$estadoRisco = Aguardar-Estado $risco.idCompra @('RECUSADA')
$analise = Invoke-RestMethod -Uri "$UrlRisco/api/v1/analises-risco/compras/$($risco.idCompra)" `
    -Headers $cabecalhoEmpresa
Garantir (-not $analise.aprovada) 'O motor de risco aprovou o cenario que deveria recusar.'
Aguardar-Estoque-Livre $produtoRisco 10 | Out-Null
Registrar-Resultado 'Risco reprovado e estoque liberado' $risco.idCompra $estadoRisco.status

Write-Host '5/6 Recusa do emissor e liberacao de estoque...' -ForegroundColor Cyan
$produtoRecusado = [guid]::NewGuid()
Preparar-Estoque $produtoRecusado 10
$corpoRecusado = Criar-CorpoCompra $produtoRecusado 1 89.90 'tok_recusado' `
    -Cliente 'cliente-recusado' -Dispositivo "disp-$([guid]::NewGuid())"
$recusada = Iniciar-Compra $corpoRecusado "recusada-$([guid]::NewGuid())"
$estadoRecusado = Aguardar-Estado $recusada.idCompra @('RECUSADA')
$pagamentoRecusado = Invoke-RestMethod -Uri "$UrlPagamento/api/v1/pagamentos/compras/$($recusada.idCompra)" `
    -Headers $cabecalhoEmpresa
Garantir ($pagamentoRecusado.status -eq 'RECUSADO') 'O pagamento nao registrou a recusa do emissor.'
Aguardar-Estoque-Livre $produtoRecusado 10 | Out-Null
Registrar-Resultado 'Pagamento recusado e estoque liberado' $recusada.idCompra $estadoRecusado.status

Write-Host '6/6 Falha contabil e compensacao completa...' -ForegroundColor Cyan
$produtoCompensado = [guid]::NewGuid()
Preparar-Estoque $produtoCompensado 10
$corpoCompensado = Criar-CorpoCompra $produtoCompensado 1 129.90 'tok_aprovado' `
    -Moeda 'XXX' -Cliente 'cliente-compensado' -Dispositivo "disp-$([guid]::NewGuid())"
$compensada = Iniciar-Compra $corpoCompensado "compensada-$([guid]::NewGuid())"
$estadoCompensado = Aguardar-Estado $compensada.idCompra @('COMPENSADA')
$pagamentoEstornado = Invoke-RestMethod -Uri "$UrlPagamento/api/v1/pagamentos/compras/$($compensada.idCompra)" `
    -Headers $cabecalhoEmpresa
Garantir ($pagamentoEstornado.status -eq 'ESTORNADO') 'A compensacao nao estornou o pagamento.'
Aguardar-Estoque-Livre $produtoCompensado 10 | Out-Null
Registrar-Resultado 'Falha contabil compensada' $compensada.idCompra $estadoCompensado.status

Write-Host ''
Write-Host 'Todos os cenarios foram aprovados.' -ForegroundColor Green
$resultados | Format-Table -AutoSize
