param(
    [string] $UrlCheckout = 'http://localhost:8080',
    [string] $UrlEstoque = 'http://localhost:8081',
    [string] $UrlRisco = 'http://localhost:8082',
    [string] $UrlPagamento = 'http://localhost:8083',
    [string] $UrlRazao = 'http://localhost:8084',
    [string] $UrlNotificacao = 'http://localhost:8085',
    [ValidateRange(10, 180)]
    [int] $TempoLimiteSegundos = 90,
    [ValidateRange(1, 10)]
    [int] $ToleranciaRelogioSegundos = 3
)

$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot
$culturaInvariante = [Globalization.CultureInfo]::InvariantCulture
$estiloData = [Globalization.DateTimeStyles]::RoundtripKind
$inicioAuditoria = [DateTimeOffset]::UtcNow
$idEmpresa = [guid]::NewGuid()
$cabecalhoEmpresa = @{ 'X-Empresa-Id' = $idEmpresa.ToString() }
$resultados = [System.Collections.Generic.List[object]]::new()
$desviosRelogios = [System.Collections.Generic.List[object]]::new()

function Garantir {
    param([bool] $Condicao, [string] $Mensagem)

    if (-not $Condicao) {
        throw $Mensagem
    }
}

function Converter-Decimal {
    param([object] $Valor)

    return [decimal]::Parse([string]$Valor, $culturaInvariante)
}

function Invocar-Http {
    param(
        [ValidateSet('GET', 'POST', 'PUT', 'PATCH', 'DELETE')]
        [string] $Metodo,
        [string] $Url,
        [hashtable] $Cabecalhos = @{},
        [object] $Corpo = $null
    )

    $parametros = @{
        Method = $Metodo
        Uri = $Url
        Headers = $Cabecalhos
        TimeoutSec = 20
        SkipHttpErrorCheck = $true
    }
    if ($null -ne $Corpo) {
        $parametros.ContentType = 'application/json'
        $parametros.Body = $Corpo | ConvertTo-Json -Depth 20 -Compress
    }

    $resposta = Invoke-WebRequest @parametros
    $corpoConvertido = $null
    if (-not [string]::IsNullOrWhiteSpace($resposta.Content)) {
        try {
            $corpoConvertido = $resposta.Content | ConvertFrom-Json -DateKind String
        }
        catch {
            $corpoConvertido = $resposta.Content
        }
    }

    return [pscustomobject]@{
        Status = [int]$resposta.StatusCode
        Corpo = $corpoConvertido
        Texto = [string]$resposta.Content
        Cabecalhos = $resposta.Headers
    }
}

function Garantir-Status {
    param([object] $Resposta, [int[]] $Esperados, [string] $Contexto)

    Garantir ($Resposta.Status -in $Esperados) `
        "$Contexto retornou HTTP $($Resposta.Status), esperado: $($Esperados -join ', '). Corpo: $($Resposta.Texto)"
}

function Garantir-Instante {
    param(
        [object] $Valor,
        [string] $Campo,
        [DateTimeOffset] $Minimo = $inicioAuditoria.AddSeconds(-$ToleranciaRelogioSegundos),
        [DateTimeOffset] $Maximo = [DateTimeOffset]::UtcNow.AddSeconds($ToleranciaRelogioSegundos),
        [switch] $PermitirNulo
    )

    if ($null -eq $Valor -or [string]::IsNullOrWhiteSpace([string]$Valor)) {
        Garantir $PermitirNulo "$Campo nao foi informado."
        return $null
    }

    $texto = [string]$Valor
    try {
        $instante = [DateTimeOffset]::Parse($texto, $culturaInvariante, $estiloData)
    }
    catch {
        throw "$Campo nao e uma data ISO-8601 valida: $texto"
    }

    Garantir ($instante.Offset -eq [TimeSpan]::Zero) `
        "$Campo nao foi serializado em UTC: $texto"
    Garantir ($instante -ge $Minimo) `
        "$Campo esta anterior ao inicio da auditoria: $texto"
    Garantir ($instante -le $Maximo) `
        "$Campo esta no futuro alem da tolerancia: $texto"
    return $instante
}

function Garantir-Ordem {
    param([DateTimeOffset] $Anterior, [DateTimeOffset] $Atual, [string] $Contexto)

    Garantir ($Atual -ge $Anterior.AddMilliseconds(-5)) `
        "Ordem temporal invalida em ${Contexto}: $($Anterior.ToString('O')) > $($Atual.ToString('O'))."
}

function Criar-CorpoCompra {
    param(
        [guid] $IdProduto,
        [int] $Quantidade,
        [decimal] $Preco,
        [string] $Token,
        [string] $Moeda = 'BRL',
        [string] $Pais = 'BR',
        [string] $Cliente = 'cliente-auditoria',
        [string] $Dispositivo = 'dispositivo-auditoria',
        [string] $MetodoPagamento = 'CARTAO',
        [int] $Parcelas = 1
    )

    return [ordered]@{
        idCliente = $Cliente
        emailCliente = "$Cliente@orquestrapay.local"
        moeda = $Moeda
        pais = $Pais
        identificadorDispositivo = $Dispositivo
        tokenPagamento = $Token
        metodoPagamento = $MetodoPagamento
        parcelas = $Parcelas
        itens = @(
            [ordered]@{
                idProduto = $IdProduto
                quantidade = $Quantidade
                precoUnitario = $Preco
            }
        )
    }
}

function Preparar-Estoque {
    param([guid] $IdProduto, [int] $Quantidade)

    $resposta = Invocar-Http PUT "$UrlEstoque/api/v1/estoques/$IdProduto" `
        $cabecalhoEmpresa @{
            quantidadeDisponivel = $Quantidade
            motivo = 'Auditoria automatizada de retornos e tempos'
        }
    Garantir-Status $resposta @(200) 'Preparacao de estoque'
    Garantir ([string]$resposta.Corpo.idEmpresa -eq $idEmpresa.ToString()) `
        'O estoque respondeu com outra empresa.'
    Garantir ([string]$resposta.Corpo.idProduto -eq $IdProduto.ToString()) `
        'O estoque respondeu com outro produto.'
    Garantir ([int]$resposta.Corpo.quantidadeDisponivel -eq $Quantidade) `
        'O saldo preparado diverge da quantidade solicitada.'
    Garantir-Instante $resposta.Corpo.atualizadoEm 'estoque.atualizadoEm' | Out-Null
}

function Iniciar-Compra {
    param([object] $Corpo, [string] $Chave)

    $cabecalhos = @{
        'X-Empresa-Id' = $idEmpresa.ToString()
        'Idempotency-Key' = $Chave
    }
    return Invocar-Http POST "$UrlCheckout/api/v1/compras" $cabecalhos $Corpo
}

function Aguardar-Compra {
    param([guid] $IdCompra, [string[]] $EstadosEsperados)

    $limite = [DateTimeOffset]::UtcNow.AddSeconds($TempoLimiteSegundos)
    do {
        $resposta = Invocar-Http GET "$UrlCheckout/api/v1/compras/$IdCompra" $cabecalhoEmpresa
        Garantir-Status $resposta @(200) "Consulta da compra $IdCompra"
        if ($resposta.Corpo.status -in $EstadosEsperados) {
            return $resposta.Corpo
        }
        Start-Sleep -Milliseconds 250
    } while ([DateTimeOffset]::UtcNow -lt $limite)

    throw "A compra $IdCompra nao chegou a $($EstadosEsperados -join ', ') em $TempoLimiteSegundos segundos. Ultimo estado: $($resposta.Corpo.status)."
}

function Aguardar-Notificacao {
    param([guid] $IdCompra)

    $limite = [DateTimeOffset]::UtcNow.AddSeconds($TempoLimiteSegundos)
    do {
        $resposta = Invocar-Http GET "$UrlNotificacao/api/v1/notificacoes/compras/$IdCompra" $cabecalhoEmpresa
        Garantir-Status $resposta @(200) "Consulta da notificacao $IdCompra"
        $notificacoes = @($resposta.Corpo)
        $enviada = $notificacoes | Where-Object status -eq 'ENVIADA' | Select-Object -First 1
        if ($enviada) {
            return $enviada
        }
        Start-Sleep -Milliseconds 250
    } while ([DateTimeOffset]::UtcNow -lt $limite)

    throw "A notificacao da compra $IdCompra nao foi enviada em $TempoLimiteSegundos segundos."
}

function Consultar-Banco {
    param([string] $Servico, [string] $Banco, [string] $Consulta)

    $saida = @(& docker compose exec -T $Servico psql -U orquestrapay -d $Banco -Atc $Consulta)
    Garantir ($LASTEXITCODE -eq 0) "Falha ao consultar $Banco."
    $linhas = @($saida | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    return ,$linhas
}

function Converter-Epoca {
    param([string] $Valor)

    $segundos = [double]::Parse($Valor, $culturaInvariante)
    return [DateTimeOffset]::FromUnixTimeMilliseconds([long][Math]::Round($segundos * 1000))
}

function Garantir-Mesmo-Instante {
    param([DateTimeOffset] $Api, [DateTimeOffset] $Banco, [string] $Campo)

    $diferenca = [Math]::Abs(($Api - $Banco).TotalMilliseconds)
    Garantir ($diferenca -le 2) `
        "$Campo divergiu entre API e banco em $([Math]::Round($diferenca, 3)) ms."
}

function Verificar-Relogios-Bancos {
    $bancos = @(
        @('banco-checkout', 'orquestrapay_checkout'),
        @('banco-estoque', 'orquestrapay_estoque'),
        @('banco-risco', 'orquestrapay_risco'),
        @('banco-pagamento', 'orquestrapay_pagamento'),
        @('banco-razao', 'orquestrapay_razao'),
        @('banco-notificacao', 'orquestrapay_notificacao'),
        @('banco-registro', 'orquestrapay_registro')
    )

    foreach ($banco in $bancos) {
        $antes = [DateTimeOffset]::UtcNow
        $linhas = Consultar-Banco $banco[0] $banco[1] `
            'SELECT EXTRACT(EPOCH FROM clock_timestamp());'
        $depois = [DateTimeOffset]::UtcNow
        Garantir ($linhas.Count -eq 1) "O relogio de $($banco[1]) nao retornou uma linha."
        $instanteBanco = Converter-Epoca $linhas[0]
        $meio = $antes.AddTicks([long](($depois - $antes).Ticks / 2))
        $desvio = [Math]::Abs(($instanteBanco - $meio).TotalMilliseconds)
        Garantir ($desvio -le ($ToleranciaRelogioSegundos * 1000)) `
            "O relogio de $($banco[1]) diverge do host em $([Math]::Round($desvio)) ms."
        $desviosRelogios.Add([pscustomobject]@{
                banco = $banco[1]
                desvioMs = [Math]::Round($desvio, 2)
            })
    }
}

function Validar-Historico {
    param([guid] $IdCompra, [string] $StatusFinal)

    $resposta = Invocar-Http GET "$UrlCheckout/api/v1/compras/$IdCompra/historico" $cabecalhoEmpresa
    Garantir-Status $resposta @(200) "Historico da compra $IdCompra"
    $historico = @($resposta.Corpo)
    Garantir ($historico.Count -ge 2) "O historico da compra $IdCompra esta incompleto."

    $anterior = $null
    foreach ($registro in $historico) {
        $atual = Garantir-Instante $registro.registradoEm 'historico.registradoEm'
        if ($null -ne $anterior) {
            Garantir-Ordem $anterior $atual 'historico da saga'
        }
        $anterior = $atual
    }

    Garantir ($historico[-1].statusAtual -eq $StatusFinal) `
        "O ultimo historico da compra $IdCompra nao representa $StatusFinal."
    return $historico
}

function Validar-Estoque {
    param([guid] $IdProduto, [int] $Disponivel, [int] $Reservado)

    $resposta = Invocar-Http GET "$UrlEstoque/api/v1/estoques/$IdProduto" $cabecalhoEmpresa
    Garantir-Status $resposta @(200) "Consulta do estoque $IdProduto"
    Garantir ([int]$resposta.Corpo.quantidadeDisponivel -eq $Disponivel) `
        "Disponivel incorreto no produto ${IdProduto}: $($resposta.Corpo.quantidadeDisponivel)."
    Garantir ([int]$resposta.Corpo.quantidadeReservada -eq $Reservado) `
        "Reservado incorreto no produto ${IdProduto}: $($resposta.Corpo.quantidadeReservada)."
    Garantir-Instante $resposta.Corpo.atualizadoEm 'estoque.atualizadoEm' | Out-Null
    return $resposta.Corpo
}

function Registrar-Resultado {
    param([string] $Cenario, [guid] $IdCompra, [string] $Estado, [string] $Decisao)

    $resultados.Add([pscustomobject]@{
            cenario = $Cenario
            idCompra = $IdCompra
            estado = $Estado
            decisao = $Decisao
        })
}

Push-Location $raiz
try {
    Write-Host '1/7 Validando os relogios dos sete bancos...' -ForegroundColor Cyan
    Verificar-Relogios-Bancos

    Write-Host '2/7 Validando compra aprovada, dados, idempotencia e cronologia...' -ForegroundColor Cyan
    $produtoAprovado = [guid]::NewGuid()
    Preparar-Estoque $produtoAprovado 20
    $corpoAprovado = Criar-CorpoCompra $produtoAprovado 2 ([decimal]'37.45') 'tok_aprovado' `
        -Cliente 'cliente-aprovado-auditoria' `
        -Dispositivo "disp-aprovado-$([guid]::NewGuid())" `
        -Parcelas 3
    $chaveAprovada = "auditoria-aprovada-$([guid]::NewGuid())"
    $momentoEnvio = [DateTimeOffset]::UtcNow
    $criacaoAprovada = Iniciar-Compra $corpoAprovado $chaveAprovada
    Garantir-Status $criacaoAprovada @(202) 'Criacao da compra aprovada'
    Garantir ($criacaoAprovada.Corpo.status -eq 'RECEBIDA') 'A compra nao iniciou como RECEBIDA.'
    Garantir ([string]$criacaoAprovada.Corpo.idEmpresa -eq $idEmpresa.ToString()) 'A compra respondeu com outra empresa.'
    Garantir ((Converter-Decimal $criacaoAprovada.Corpo.valorTotal) -eq [decimal]'74.90') 'O total inicial deveria ser 74,90.'
    Garantir ($criacaoAprovada.Corpo.moeda -eq 'BRL') 'A moeda inicial deveria ser BRL.'
    Garantir ($criacaoAprovada.Corpo.metodoPagamento -eq 'CARTAO') 'O metodo deveria ser CARTAO.'
    Garantir ([int]$criacaoAprovada.Corpo.parcelas -eq 3) 'A compra deveria possuir tres parcelas.'
    Garantir ($criacaoAprovada.Texto -notmatch 'tok_aprovado') 'O token de pagamento vazou na resposta.'
    $criadaEm = Garantir-Instante $criacaoAprovada.Corpo.criadoEm 'compra.criadoEm' $momentoEnvio.AddSeconds(-$ToleranciaRelogioSegundos)
    $atualizadaInicial = Garantir-Instante $criacaoAprovada.Corpo.atualizadoEm 'compra.atualizadoEm'
    Garantir-Ordem $criadaEm $atualizadaInicial 'criacao da compra'

    $repetida = Iniciar-Compra $corpoAprovado $chaveAprovada
    Garantir-Status $repetida @(202) 'Replay idempotente'
    Garantir ($repetida.Corpo.idCompra -eq $criacaoAprovada.Corpo.idCompra) `
        'O replay idempotente criou outra compra.'
    $cabecalhoReplay = [string](@($repetida.Cabecalhos['Idempotency-Replayed'])[0])
    Garantir ($cabecalhoReplay -eq 'true') 'O replay nao foi identificado no cabecalho HTTP.'

    $corpoAlterado = Criar-CorpoCompra $produtoAprovado 2 ([decimal]'37.46') 'tok_aprovado' `
        -Cliente 'cliente-aprovado-auditoria' `
        -Dispositivo $corpoAprovado.identificadorDispositivo `
        -Parcelas 3
    $conflito = Iniciar-Compra $corpoAlterado $chaveAprovada
    Garantir-Status $conflito @(409) 'Reutilizacao de chave com outro corpo'

    $idCompraAprovada = [guid]$criacaoAprovada.Corpo.idCompra
    $outraEmpresa = @{ 'X-Empresa-Id' = [guid]::NewGuid().ToString() }
    $isolamento = Invocar-Http GET "$UrlCheckout/api/v1/compras/$idCompraAprovada" $outraEmpresa
    Garantir-Status $isolamento @(404) 'Isolamento entre empresas'

    $compraAprovada = Aguardar-Compra $idCompraAprovada @('CONCLUIDA')
    Garantir ((Converter-Decimal $compraAprovada.valorTotal) -eq [decimal]'74.90') 'O total final da compra aprovada divergiu.'
    $atualizadaFinal = Garantir-Instante $compraAprovada.atualizadoEm 'compra.atualizadoEm final'
    Garantir-Ordem $criadaEm $atualizadaFinal 'processamento da compra aprovada'
    $historicoAprovado = Validar-Historico $idCompraAprovada 'CONCLUIDA'

    $riscoAprovado = Invocar-Http GET "$UrlRisco/api/v1/analises-risco/compras/$idCompraAprovada" $cabecalhoEmpresa
    Garantir-Status $riscoAprovado @(200) 'Risco da compra aprovada'
    Garantir ($riscoAprovado.Corpo.aprovada -eq $true) 'O risco gerou falso negativo para a compra legitima.'
    Garantir ($riscoAprovado.Corpo.idCompra -eq $idCompraAprovada.ToString()) 'O risco respondeu com outra compra.'
    $analisadaEm = Garantir-Instante $riscoAprovado.Corpo.analisadaEm 'risco.analisadaEm'

    $pagamentoAprovado = Invocar-Http GET "$UrlPagamento/api/v1/pagamentos/compras/$idCompraAprovada" $cabecalhoEmpresa
    Garantir-Status $pagamentoAprovado @(200) 'Pagamento da compra aprovada'
    Garantir ($pagamentoAprovado.Corpo.status -eq 'AUTORIZADO') 'O pagamento legitimo nao foi autorizado.'
    Garantir ((Converter-Decimal $pagamentoAprovado.Corpo.valor) -eq [decimal]'74.90') 'O valor do pagamento divergiu.'
    Garantir ([int]$pagamentoAprovado.Corpo.parcelas -eq 3) 'O parcelamento nao chegou ao pagamento.'
    Garantir ($pagamentoAprovado.Texto -notmatch 'tok_aprovado') 'O token vazou na consulta do pagamento.'
    $pagamentoAtualizadoEm = Garantir-Instante $pagamentoAprovado.Corpo.atualizadoEm 'pagamento.atualizadoEm'

    $razaoAprovada = Invocar-Http GET "$UrlRazao/api/v1/transacoes-contabeis/compras/$idCompraAprovada" $cabecalhoEmpresa
    Garantir-Status $razaoAprovada @(200) 'Razao da compra aprovada'
    Garantir ($razaoAprovada.Corpo.status -eq 'REGISTRADA') 'A razao nao registrou a compra aprovada.'
    Garantir ((Converter-Decimal $razaoAprovada.Corpo.valor) -eq [decimal]'74.90') 'O valor contabil divergiu.'
    Garantir ((Converter-Decimal $razaoAprovada.Corpo.totalDebitos) -eq (Converter-Decimal $razaoAprovada.Corpo.totalCreditos)) `
        'As partidas dobradas ficaram desbalanceadas.'
    Garantir (@($razaoAprovada.Corpo.lancamentos).Count -eq 2) 'A transacao deveria possuir dois lancamentos.'
    Garantir (@($razaoAprovada.Corpo.parcelas).Count -eq 3) 'A agenda deveria possuir tres parcelas.'
    $somaParcelas = [decimal]0
    foreach ($parcela in @($razaoAprovada.Corpo.parcelas)) {
        $somaParcelas += Converter-Decimal $parcela.valor
        Garantir-Instante $parcela.criadaEm 'parcela.criadaEm' | Out-Null
    }
    Garantir ($somaParcelas -eq [decimal]'74.90') 'A soma das parcelas diverge do valor da compra.'
    $razaoCriadaEm = Garantir-Instante $razaoAprovada.Corpo.criadaEm 'razao.criadaEm'

    $estoqueAprovado = Validar-Estoque $produtoAprovado 18 2
    $estoqueAtualizadoEm = Garantir-Instante $estoqueAprovado.atualizadoEm 'estoque.aprovado.atualizadoEm'
    $notificacaoAprovada = Aguardar-Notificacao $idCompraAprovada
    $notificacaoCriadaEm = Garantir-Instante $notificacaoAprovada.criadaEm 'notificacao.criadaEm'
    $notificacaoEnviadaEm = Garantir-Instante $notificacaoAprovada.enviadaEm 'notificacao.enviadaEm'

    Garantir-Ordem $criadaEm $estoqueAtualizadoEm 'compra para reserva de estoque'
    Garantir-Ordem $estoqueAtualizadoEm $analisadaEm 'estoque para risco'
    Garantir-Ordem $analisadaEm $pagamentoAtualizadoEm 'risco para pagamento'
    Garantir-Ordem $pagamentoAtualizadoEm $razaoCriadaEm 'pagamento para razao'
    Garantir-Ordem $razaoCriadaEm $atualizadaFinal 'razao para conclusao'
    Garantir-Ordem $atualizadaFinal $notificacaoCriadaEm 'conclusao para notificacao'
    Garantir-Ordem $notificacaoCriadaEm $notificacaoEnviadaEm 'criacao para envio da notificacao'

    Write-Host '3/7 Validando decisao negativa real do motor de risco...' -ForegroundColor Cyan
    $produtoRisco = [guid]::NewGuid()
    Preparar-Estoque $produtoRisco 10
    $corpoRisco = Criar-CorpoCompra $produtoRisco 1 ([decimal]'6001.00') 'tok_aprovado' `
        -Pais 'US' -Cliente 'cliente-risco-auditoria' `
        -Dispositivo "disp-risco-$([guid]::NewGuid())"
    $criacaoRisco = Iniciar-Compra $corpoRisco "auditoria-risco-$([guid]::NewGuid())"
    Garantir-Status $criacaoRisco @(202) 'Criacao da compra de risco'
    $idCompraRisco = [guid]$criacaoRisco.Corpo.idCompra
    $compraRisco = Aguardar-Compra $idCompraRisco @('RECUSADA')
    $analiseNegativa = Invocar-Http GET "$UrlRisco/api/v1/analises-risco/compras/$idCompraRisco" $cabecalhoEmpresa
    Garantir-Status $analiseNegativa @(200) 'Analise de risco negativa'
    Garantir ($analiseNegativa.Corpo.aprovada -eq $false) 'O risco gerou falso positivo para o cenario suspeito.'
    Garantir ([int]$analiseNegativa.Corpo.pontuacao -ge 50) 'A pontuacao do cenario suspeito ficou abaixo do limiar esperado.'
    Garantir-Instante $analiseNegativa.Corpo.analisadaEm 'risco.negativo.analisadaEm' | Out-Null
    $semPagamento = Invocar-Http GET "$UrlPagamento/api/v1/pagamentos/compras/$idCompraRisco" $cabecalhoEmpresa
    Garantir-Status $semPagamento @(404) 'Bloqueio do pagamento apos risco negativo'
    $semRazao = Invocar-Http GET "$UrlRazao/api/v1/transacoes-contabeis/compras/$idCompraRisco" $cabecalhoEmpresa
    Garantir-Status $semRazao @(404) 'Bloqueio contabil apos risco negativo'
    Validar-Estoque $produtoRisco 10 0 | Out-Null
    Validar-Historico $idCompraRisco 'RECUSADA' | Out-Null
    Aguardar-Notificacao $idCompraRisco | Out-Null
    Registrar-Resultado 'Aprovacao legitima' $idCompraAprovada $compraAprovada.status 'risco=true'
    Registrar-Resultado 'Recusa por risco' $idCompraRisco $compraRisco.status 'risco=false'

    Write-Host '4/7 Validando recusa do emissor e compensacao completa...' -ForegroundColor Cyan
    $produtoRecusado = [guid]::NewGuid()
    Preparar-Estoque $produtoRecusado 10
    $corpoRecusado = Criar-CorpoCompra $produtoRecusado 1 ([decimal]'29.90') 'tok_recusado' `
        -Cliente 'cliente-emissor-auditoria' `
        -Dispositivo "disp-emissor-$([guid]::NewGuid())"
    $criacaoRecusada = Iniciar-Compra $corpoRecusado "auditoria-emissor-$([guid]::NewGuid())"
    Garantir-Status $criacaoRecusada @(202) 'Criacao da recusa do emissor'
    $idCompraRecusada = [guid]$criacaoRecusada.Corpo.idCompra
    $compraRecusada = Aguardar-Compra $idCompraRecusada @('RECUSADA')
    $riscoEmissor = Invocar-Http GET "$UrlRisco/api/v1/analises-risco/compras/$idCompraRecusada" $cabecalhoEmpresa
    Garantir-Status $riscoEmissor @(200) 'Risco da recusa do emissor'
    Garantir ($riscoEmissor.Corpo.aprovada -eq $true) 'O risco deveria aprovar antes da recusa do emissor.'
    $pagamentoRecusado = Invocar-Http GET "$UrlPagamento/api/v1/pagamentos/compras/$idCompraRecusada" $cabecalhoEmpresa
    Garantir-Status $pagamentoRecusado @(200) 'Pagamento recusado pelo emissor'
    Garantir ($pagamentoRecusado.Corpo.status -eq 'RECUSADO') 'O emissor nao registrou RECUSADO.'
    Garantir ((Converter-Decimal $pagamentoRecusado.Corpo.valor) -eq [decimal]'29.90') 'O valor da recusa divergiu.'
    Garantir-Instante $pagamentoRecusado.Corpo.atualizadoEm 'pagamento.recusa.atualizadoEm' | Out-Null
    Validar-Estoque $produtoRecusado 10 0 | Out-Null
    Validar-Historico $idCompraRecusada 'RECUSADA' | Out-Null
    Aguardar-Notificacao $idCompraRecusada | Out-Null
    Registrar-Resultado 'Recusa do emissor' $idCompraRecusada $compraRecusada.status 'pagamento=RECUSADO'

    $produtoCompensado = [guid]::NewGuid()
    Preparar-Estoque $produtoCompensado 10
    $corpoCompensado = Criar-CorpoCompra $produtoCompensado 1 ([decimal]'49.90') 'tok_aprovado' `
        -Moeda 'XXX' -Cliente 'cliente-compensacao-auditoria' `
        -Dispositivo "disp-compensacao-$([guid]::NewGuid())"
    $criacaoCompensada = Iniciar-Compra $corpoCompensado "auditoria-compensacao-$([guid]::NewGuid())"
    Garantir-Status $criacaoCompensada @(202) 'Criacao da compra compensada'
    $idCompraCompensada = [guid]$criacaoCompensada.Corpo.idCompra
    $compraCompensada = Aguardar-Compra $idCompraCompensada @('COMPENSADA')
    $pagamentoEstornado = Invocar-Http GET "$UrlPagamento/api/v1/pagamentos/compras/$idCompraCompensada" $cabecalhoEmpresa
    Garantir-Status $pagamentoEstornado @(200) 'Pagamento compensado'
    Garantir ($pagamentoEstornado.Corpo.status -eq 'ESTORNADO') 'A compensacao nao estornou o pagamento.'
    $razaoRejeitada = Invocar-Http GET "$UrlRazao/api/v1/transacoes-contabeis/compras/$idCompraCompensada" $cabecalhoEmpresa
    Garantir-Status $razaoRejeitada @(200) 'Razao da compra compensada'
    Garantir ($razaoRejeitada.Corpo.status -eq 'REJEITADA') 'A falha contabil controlada nao foi registrada.'
    Garantir (@($razaoRejeitada.Corpo.lancamentos).Count -eq 0) 'Uma transacao rejeitada nao pode possuir lancamentos.'
    Validar-Estoque $produtoCompensado 10 0 | Out-Null
    Validar-Historico $idCompraCompensada 'COMPENSADA' | Out-Null
    Aguardar-Notificacao $idCompraCompensada | Out-Null
    Registrar-Resultado 'Compensacao financeira' $idCompraCompensada $compraCompensada.status 'pagamento=ESTORNADO'

    Write-Host '5/7 Validando entrada hostil, contrato e ausencia de efeitos colaterais...' -ForegroundColor Cyan
    $quantidadeAntes = [int](Consultar-Banco banco-checkout orquestrapay_checkout `
            "SELECT COUNT(*) FROM compra WHERE id_empresa = '$idEmpresa';")[0]
    $produtoInvalido = [guid]::NewGuid()
    $corpoInvalido = Criar-CorpoCompra $produtoInvalido 1 ([decimal]'10.00') 'tok_aprovado' `
        -Moeda "BRL' OR '1'='1" `
        -Cliente 'cliente-entrada-hostil' `
        -Dispositivo "disp-hostil-$([guid]::NewGuid())"
    $entradaHostil = Iniciar-Compra $corpoInvalido "auditoria-hostil-$([guid]::NewGuid())"
    Garantir-Status $entradaHostil @(400) 'Moeda malformada com tentativa de injecao'
    $quantidadeDepois = [int](Consultar-Banco banco-checkout orquestrapay_checkout `
            "SELECT COUNT(*) FROM compra WHERE id_empresa = '$idEmpresa';")[0]
    Garantir ($quantidadeDepois -eq $quantidadeAntes) 'A entrada hostil produziu efeito no banco.'

    Write-Host '6/7 Comparando os timestamps das APIs com os registros persistidos...' -ForegroundColor Cyan
    $checkoutBanco = (Consultar-Banco banco-checkout orquestrapay_checkout @"
SELECT EXTRACT(EPOCH FROM criado_em) || '|' || EXTRACT(EPOCH FROM atualizado_em)
FROM compra WHERE id_compra = '$idCompraAprovada';
"@)[0] -split '\|'
    $estoqueBanco = Converter-Epoca (Consultar-Banco banco-estoque orquestrapay_estoque @"
SELECT EXTRACT(EPOCH FROM atualizado_em)
FROM saldo_estoque WHERE id_empresa = '$idEmpresa' AND id_produto = '$produtoAprovado';
"@)[0]
    $riscoBanco = Converter-Epoca (Consultar-Banco banco-risco orquestrapay_risco @"
SELECT EXTRACT(EPOCH FROM analisada_em)
FROM analise_risco WHERE id_compra = '$idCompraAprovada';
"@)[0]
    $pagamentoBanco = Converter-Epoca (Consultar-Banco banco-pagamento orquestrapay_pagamento @"
SELECT EXTRACT(EPOCH FROM atualizado_em)
FROM pagamento WHERE id_compra = '$idCompraAprovada';
"@)[0]
    $razaoBanco = Converter-Epoca (Consultar-Banco banco-razao orquestrapay_razao @"
SELECT EXTRACT(EPOCH FROM criada_em)
FROM transacao_contabil WHERE id_compra = '$idCompraAprovada';
"@)[0]
    $notificacaoBanco = (Consultar-Banco banco-notificacao orquestrapay_notificacao @"
SELECT EXTRACT(EPOCH FROM criada_em) || '|' || EXTRACT(EPOCH FROM enviada_em)
FROM notificacao WHERE id_compra = '$idCompraAprovada' ORDER BY criada_em LIMIT 1;
"@)[0] -split '\|'

    Garantir-Mesmo-Instante $criadaEm (Converter-Epoca $checkoutBanco[0]) 'compra.criadoEm'
    Garantir-Mesmo-Instante $atualizadaFinal (Converter-Epoca $checkoutBanco[1]) 'compra.atualizadoEm'
    Garantir-Mesmo-Instante $estoqueAtualizadoEm $estoqueBanco 'estoque.atualizadoEm'
    Garantir-Mesmo-Instante $analisadaEm $riscoBanco 'risco.analisadaEm'
    Garantir-Mesmo-Instante $pagamentoAtualizadoEm $pagamentoBanco 'pagamento.atualizadoEm'
    Garantir-Mesmo-Instante $razaoCriadaEm $razaoBanco 'razao.criadaEm'
    Garantir-Mesmo-Instante $notificacaoCriadaEm (Converter-Epoca $notificacaoBanco[0]) 'notificacao.criadaEm'
    Garantir-Mesmo-Instante $notificacaoEnviadaEm (Converter-Epoca $notificacaoBanco[1]) 'notificacao.enviadaEm'

    Write-Host '7/7 Executando a auditoria distribuida final...' -ForegroundColor Cyan
    & (Join-Path $PSScriptRoot 'auditar-consistencia.ps1') -EsperaSegundos 2

    $fimAuditoria = [DateTimeOffset]::UtcNow
    Write-Host ''
    Write-Host 'Retornos, decisoes, valores e tempos aprovados.' -ForegroundColor Green
    $resultados | Format-Table -AutoSize
    [pscustomobject]@{
        empresaAuditoria = $idEmpresa
        cenarios = $resultados.Count
        decisoesRisco = 'true e false confirmados'
        idempotencia = 'replay confirmado e conflito rejeitado'
        isolamentoEmpresas = 'confirmado'
        partidasDobradas = 'balanceadas'
        compensacao = 'pagamento estornado e estoque liberado'
        timestamps = 'UTC, ISO-8601, ordenados e iguais aos bancos'
        maiorDesvioRelogioMs = ($desviosRelogios | Measure-Object desvioMs -Maximum).Maximum
        horarioBrasiliaInicio = $inicioAuditoria.ToOffset([TimeSpan]::FromHours(-3)).ToString('yyyy-MM-dd HH:mm:ss zzz')
        horarioBrasiliaFim = $fimAuditoria.ToOffset([TimeSpan]::FromHours(-3)).ToString('yyyy-MM-dd HH:mm:ss zzz')
    } | Format-List
}
finally {
    Pop-Location
}
