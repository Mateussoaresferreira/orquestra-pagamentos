param(
    [long] $TotalCompras = 0,
    [int] $TamanhoLote = 0,
    [int] $TaxaAlvo = 0,
    [int] $Usuarios = 0,
    [int] $QuantidadeEmpresas = 0,
    [string] $IdExecucao = '',
    [string] $DuracaoMaximaLote = '',
    [int] $LimiteP95Ms = 0,
    [int] $TempoMaximoConvergenciaSegundos = 3600,
    [int] $IntervaloConsultaSegundos = 10,
    [int] $AuditarACadaLotes = 10,
    [int] $MaximoLotesNestaExecucao = 0,
    [switch] $AmbienteDedicado
)

$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot
$pastaAuditoria = Join-Path $raiz '.auditoria'
$utf8SemBom = New-Object System.Text.UTF8Encoding($false)

function Garantir {
    param([bool] $Condicao, [string] $Mensagem)
    if (-not $Condicao) {
        throw $Mensagem
    }
}

function Consultar-Linha {
    param(
        [string] $Servico,
        [string] $Banco,
        [string] $Consulta
    )

    $resultado = docker compose exec -T $Servico psql `
        -U orquestrapay -d $Banco -Atc $Consulta
    Garantir ($LASTEXITCODE -eq 0) "Falha ao consultar o banco $Banco."
    return [string]($resultado | Select-Object -First 1)
}

function Separar-Numeros {
    param([string] $Linha)
    return @($Linha -split '\|' | ForEach-Object { [long]$_ })
}

function Obter-ContagensIniciais {
    $checkout = Separar-Numeros (Consultar-Linha banco-checkout orquestrapay_checkout @'
SELECT (SELECT COUNT(*) FROM compra),
       (SELECT COUNT(*) FROM compra WHERE status = 'CONCLUIDA'),
       (SELECT COUNT(*) FROM item_compra),
       (SELECT COUNT(*) FROM requisicao_idempotente);
'@)
    $estoque = Separar-Numeros (Consultar-Linha banco-estoque orquestrapay_estoque @'
SELECT (SELECT COUNT(*) FROM reserva_estoque),
       (SELECT COUNT(*) FROM reserva_estoque WHERE status = 'RESERVADA'),
       (SELECT COUNT(*) FROM item_reserva);
'@)
    $risco = Separar-Numeros (Consultar-Linha banco-risco orquestrapay_risco @'
SELECT COUNT(*), COUNT(*) FILTER (WHERE aprovada) FROM analise_risco;
'@)
    $pagamento = Separar-Numeros (Consultar-Linha banco-pagamento orquestrapay_pagamento @'
SELECT (SELECT COUNT(*) FROM pagamento),
       (SELECT COUNT(*) FROM pagamento WHERE status = 'AUTORIZADO'),
       (SELECT COUNT(*) FROM operacao_pagamento),
       (SELECT COUNT(*) FROM operacao_pagamento WHERE status = 'CONCLUIDA'),
       (SELECT COUNT(*) FROM operacao_pagamento WHERE status = 'FALHA_DEFINITIVA');
'@)
    $razao = Separar-Numeros (Consultar-Linha banco-razao orquestrapay_razao @'
SELECT (SELECT COUNT(*) FROM transacao_contabil),
       (SELECT COUNT(*) FROM transacao_contabil WHERE status = 'REGISTRADA'),
       (SELECT COUNT(*) FROM lancamento_contabil);
'@)
    $notificacao = Separar-Numeros (Consultar-Linha banco-notificacao orquestrapay_notificacao @'
SELECT COUNT(*),
       COUNT(*) FILTER (WHERE status = 'ENVIADA'),
       COUNT(*) FILTER (WHERE status = 'FALHA_DEFINITIVA')
FROM notificacao;
'@)

    return [ordered]@{
        compras = $checkout[0]
        comprasConcluidas = $checkout[1]
        itensCompra = $checkout[2]
        requisicoesIdempotentes = $checkout[3]
        reservas = $estoque[0]
        reservasAtivas = $estoque[1]
        itensReserva = $estoque[2]
        analisesRisco = $risco[0]
        analisesAprovadas = $risco[1]
        pagamentos = $pagamento[0]
        pagamentosAutorizados = $pagamento[1]
        operacoesPagamento = $pagamento[2]
        operacoesConcluidas = $pagamento[3]
        operacoesFalhas = $pagamento[4]
        transacoesContabeis = $razao[0]
        transacoesRegistradas = $razao[1]
        lancamentosContabeis = $razao[2]
        notificacoes = $notificacao[0]
        notificacoesEnviadas = $notificacao[1]
        notificacoesFalhas = $notificacao[2]
    }
}

function Obter-TamanhoBancos {
    $bancos = @(
        @('banco-checkout', 'orquestrapay_checkout'),
        @('banco-estoque', 'orquestrapay_estoque'),
        @('banco-risco', 'orquestrapay_risco'),
        @('banco-pagamento', 'orquestrapay_pagamento'),
        @('banco-razao', 'orquestrapay_razao'),
        @('banco-notificacao', 'orquestrapay_notificacao')
    )
    $total = 0L
    foreach ($banco in $bancos) {
        $total += [long](Consultar-Linha $banco[0] $banco[1] `
            'SELECT pg_database_size(current_database());')
    }
    return $total
}

function Obter-Pendencias {
    $checkout = Separar-Numeros (Consultar-Linha banco-checkout orquestrapay_checkout @'
SELECT COUNT(*),
       COUNT(*) FILTER (WHERE status NOT IN ('CONCLUIDA', 'RECUSADA', 'COMPENSADA')),
       COUNT(*) FILTER (WHERE status = 'CONCLUIDA')
FROM compra;
'@)
    $pagamento = Separar-Numeros (Consultar-Linha banco-pagamento orquestrapay_pagamento @'
SELECT COUNT(*) FILTER (WHERE status IN ('PENDENTE', 'PROCESSANDO')),
       COUNT(*) FILTER (WHERE status = 'FALHA_DEFINITIVA')
FROM operacao_pagamento;
'@)
    $notificacao = Separar-Numeros (Consultar-Linha banco-notificacao orquestrapay_notificacao @'
SELECT COUNT(*),
       COUNT(*) FILTER (WHERE status IN ('PENDENTE', 'PROCESSANDO')),
       COUNT(*) FILTER (WHERE status = 'FALHA_DEFINITIVA')
FROM notificacao;
'@)

    $eventosPendentes = 0L
    $eventosQuarentena = 0L
    $bancos = @(
        @('banco-checkout', 'orquestrapay_checkout'),
        @('banco-estoque', 'orquestrapay_estoque'),
        @('banco-risco', 'orquestrapay_risco'),
        @('banco-pagamento', 'orquestrapay_pagamento'),
        @('banco-razao', 'orquestrapay_razao'),
        @('banco-notificacao', 'orquestrapay_notificacao')
    )
    foreach ($banco in $bancos) {
        $fila = Separar-Numeros (Consultar-Linha $banco[0] $banco[1] @'
SELECT COUNT(*) FILTER (WHERE publicado_em IS NULL AND descartado_em IS NULL),
       COUNT(*) FILTER (WHERE descartado_em IS NOT NULL)
FROM evento_saida;
'@)
        $eventosPendentes += $fila[0]
        $eventosQuarentena += $fila[1]
    }

    return [pscustomobject]@{
        compras = $checkout[0]
        comprasEmAndamento = $checkout[1]
        comprasConcluidas = $checkout[2]
        operacoesPagamentoPendentes = $pagamento[0]
        operacoesPagamentoFalhas = $pagamento[1]
        notificacoes = $notificacao[0]
        notificacoesPendentes = $notificacao[1]
        notificacoesFalhas = $notificacao[2]
        eventosPendentes = $eventosPendentes
        eventosQuarentena = $eventosQuarentena
    }
}

function Escrever-JsonAtomico {
    param([string] $Caminho, [object] $Valor)
    $temporario = "$Caminho.tmp"
    $conteudo = $Valor | ConvertTo-Json -Depth 8
    [IO.File]::WriteAllText($temporario, $conteudo, $utf8SemBom)
    Move-Item -LiteralPath $temporario -Destination $Caminho -Force
}

function Preparar-Estoque {
    param([object] $Estado)

    Write-Host "Preparando o produto $($Estado.idProduto) em $($Estado.quantidadeEmpresas) empresas..." -ForegroundColor Cyan
    $corpo = @{
        quantidadeDisponivel = [int]$Estado.estoquePorEmpresa
        motivo = "Teste de volume $($Estado.idExecucao)"
    } | ConvertTo-Json

    for ($indice = 0; $indice -lt [int]$Estado.quantidadeEmpresas; $indice++) {
        $empresa = '10000000-0000-0000-0000-{0:D12}' -f ($indice + 101)
        Invoke-RestMethod `
            -Method Put `
            -Uri "http://localhost:8081/api/v1/estoques/$($Estado.idProduto)" `
            -Headers @{ 'X-Empresa-Id' = $empresa } `
            -ContentType 'application/json' `
            -Body $corpo | Out-Null
        if ((($indice + 1) % 20) -eq 0 -or ($indice + 1) -eq [int]$Estado.quantidadeEmpresas) {
            Write-Host "  estoque preparado em $($indice + 1)/$($Estado.quantidadeEmpresas) empresas"
        }
    }
}

function Executar-LoteK6 {
    param(
        [object] $Estado,
        [long] $Offset,
        [int] $Quantidade,
        [string] $PastaExecucao
    )

    $prefixo = 'lote-{0:D12}-{1:D8}' -f $Offset, $Quantidade
    $tentativa = 1
    do {
        $nomeBase = '{0}-tentativa-{1:D2}' -f $prefixo, $tentativa
        $relatorioTexto = Join-Path $PastaExecucao "$nomeBase.txt"
        $relatorioJson = Join-Path $PastaExecucao "$nomeBase.json"
        $tentativa++
    } while ((Test-Path -LiteralPath $relatorioTexto) -or (Test-Path -LiteralPath $relatorioJson))
    $cronometro = [Diagnostics.Stopwatch]::StartNew()
    $preferenciaErroAnterior = $ErrorActionPreference
    try {
        # O Docker escreve progresso em stderr mesmo quando termina com sucesso.
        $ErrorActionPreference = 'Continue'
        docker compose --profile carga run --rm -T `
            -e "TOTAL_COMPRAS_LOTE=$Quantidade" `
            -e "OFFSET_COMPRAS=$Offset" `
            -e "ID_EXECUCAO=$($Estado.idExecucao)" `
            -e "ID_PRODUTO=$($Estado.idProduto)" `
            -e "QUANTIDADE_EMPRESAS=$($Estado.quantidadeEmpresas)" `
            -e "USUARIOS=$($Estado.usuarios)" `
            -e "TAXA_ALVO=$($Estado.taxaAlvo)" `
            -e "DURACAO_MAXIMA_LOTE=$($Estado.duracaoMaximaLote)" `
            -e "LIMITE_P95_MS=$LimiteP95Ms" `
            -v "${PastaExecucao}:/relatorios" `
            k6 run --summary-export "/relatorios/$nomeBase.json" `
            /testes/volume-compras.js 2>&1 |
            Tee-Object -FilePath $relatorioTexto |
            ForEach-Object { Write-Host $_ }
        $codigoSaida = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $preferenciaErroAnterior
        $cronometro.Stop()
    }

    if ($codigoSaida -ne 0) {
        throw "O k6 terminou com codigo $codigoSaida. O lote pode ser retomado com o mesmo IdExecucao."
    }
    Garantir (Test-Path -LiteralPath $relatorioJson) 'O k6 nao gerou o resumo JSON esperado.'
    $resumo = Get-Content -LiteralPath $relatorioJson -Raw | ConvertFrom-Json
    Garantir ([long]$resumo.metrics.compras_aceitas.count -eq $Quantidade) `
        'O k6 nao recebeu 202 para todas as transacoes do lote.'

    return [pscustomobject]@{
        duracaoSegundos = [math]::Round($cronometro.Elapsed.TotalSeconds, 2)
        mediaMs = [math]::Round([double]$resumo.metrics.http_req_duration.avg, 2)
        p95Ms = [math]::Round([double]$resumo.metrics.http_req_duration.'p(95)', 2)
        maximoMs = [math]::Round([double]$resumo.metrics.http_req_duration.max, 2)
        aceitas = [long]$resumo.metrics.compras_aceitas.count
        reprocessadas = if ($null -eq $resumo.metrics.compras_reprocessadas) { 0L } else { [long]$resumo.metrics.compras_reprocessadas.count }
        falhasHttp = [long]$resumo.metrics.http_req_failed.passes
        relatorioTexto = $relatorioTexto
        relatorioJson = $relatorioJson
    }
}

function Acrescentar-ResultadoCsv {
    param(
        [string] $Caminho,
        [object] $Estado,
        [long] $Offset,
        [int] $Quantidade,
        [object] $Metrica,
        [int] $ConvergenciaSegundos
    )

    if (-not (Test-Path -LiteralPath $Caminho)) {
        [IO.File]::WriteAllText(
            $Caminho,
            "lote;offset;quantidade;duracao_ingressao_s;convergencia_s;media_ms;p95_ms;maximo_ms;aceitas;reprocessadas`r`n",
            $utf8SemBom)
    }
    $cultura = [Globalization.CultureInfo]::InvariantCulture
    $linha = [string]::Format(
        $cultura,
        "{0};{1};{2};{3:F2};{4};{5:F2};{6:F2};{7:F2};{8};{9}`r`n",
        ([int]$Estado.lotesConcluidos + 1), $Offset, $Quantidade,
        $Metrica.duracaoSegundos, $ConvergenciaSegundos,
        $Metrica.mediaMs, $Metrica.p95Ms, $Metrica.maximoMs,
        $Metrica.aceitas, $Metrica.reprocessadas)
    [IO.File]::AppendAllText($Caminho, $linha, $utf8SemBom)
}

New-Item -ItemType Directory -Path $pastaAuditoria -Force | Out-Null
Push-Location $raiz
try {
    Garantir ($TempoMaximoConvergenciaSegundos -gt 0) `
        'TempoMaximoConvergenciaSegundos deve ser positivo.'
    Garantir ($IntervaloConsultaSegundos -gt 0) 'IntervaloConsultaSegundos deve ser positivo.'
    Garantir ($AuditarACadaLotes -gt 0) 'AuditarACadaLotes deve ser positivo.'
    Garantir ($MaximoLotesNestaExecucao -ge 0) 'MaximoLotesNestaExecucao nao pode ser negativo.'

    if ([string]::IsNullOrWhiteSpace($IdExecucao)) {
        $IdExecucao = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + ([guid]::NewGuid().ToString('N').Substring(0, 8))
    }
    Garantir ($IdExecucao -match '^[a-zA-Z0-9-]{1,40}$') `
        'IdExecucao aceita apenas letras, numeros e hifens, com no maximo 40 caracteres.'

    $pastaExecucao = Join-Path $pastaAuditoria "volume-$IdExecucao"
    $arquivoEstado = Join-Path $pastaExecucao 'estado.json'
    $arquivoResultados = Join-Path $pastaExecucao 'resultados.csv'
    New-Item -ItemType Directory -Path $pastaExecucao -Force | Out-Null

    if (Test-Path -LiteralPath $arquivoEstado) {
        $estado = Get-Content -LiteralPath $arquivoEstado -Raw | ConvertFrom-Json
        Garantir ($estado.idExecucao -eq $IdExecucao) 'O checkpoint pertence a outra execucao.'
        if ($null -eq $estado.PSObject.Properties['limiteP95Ms']) {
            $estado | Add-Member -NotePropertyName limiteP95Ms -NotePropertyValue 1000
        }
        if ($TotalCompras -gt 0) {
            Garantir ($TotalCompras -eq [long]$estado.totalCompras) `
                'TotalCompras difere do checkpoint existente.'
        }
        if ($TamanhoLote -gt 0) {
            Garantir ($TamanhoLote -eq [int]$estado.tamanhoLote) `
                'TamanhoLote difere do checkpoint existente.'
        }
        if ($TaxaAlvo -gt 0) {
            Garantir ($TaxaAlvo -eq [int]$estado.taxaAlvo) 'TaxaAlvo difere do checkpoint existente.'
        }
        if ($Usuarios -gt 0) {
            Garantir ($Usuarios -eq [int]$estado.usuarios) 'Usuarios difere do checkpoint existente.'
        }
        if ($QuantidadeEmpresas -gt 0) {
            Garantir ($QuantidadeEmpresas -eq [int]$estado.quantidadeEmpresas) `
                'QuantidadeEmpresas difere do checkpoint existente.'
        }
        if (-not [string]::IsNullOrWhiteSpace($DuracaoMaximaLote)) {
            Garantir ($DuracaoMaximaLote -eq [string]$estado.duracaoMaximaLote) `
                'DuracaoMaximaLote difere do checkpoint existente.'
        }
        if ($LimiteP95Ms -gt 0) {
            Garantir ($LimiteP95Ms -eq [int]$estado.limiteP95Ms) `
                'LimiteP95Ms difere do checkpoint existente.'
        }
        $TotalCompras = [long]$estado.totalCompras
        $TamanhoLote = [int]$estado.tamanhoLote
        $TaxaAlvo = [int]$estado.taxaAlvo
        $Usuarios = [int]$estado.usuarios
        $QuantidadeEmpresas = [int]$estado.quantidadeEmpresas
        $DuracaoMaximaLote = [string]$estado.duracaoMaximaLote
        $LimiteP95Ms = [int]$estado.limiteP95Ms
        Write-Host "Retomando $IdExecucao a partir da transacao $($estado.proximoOffset)." -ForegroundColor Yellow
    }
    else {
        if ($TotalCompras -eq 0) { $TotalCompras = 1000000 }
        if ($TamanhoLote -eq 0) { $TamanhoLote = 10000 }
        if ($TaxaAlvo -eq 0) { $TaxaAlvo = 25 }
        if ($Usuarios -eq 0) { $Usuarios = 20 }
        if ($QuantidadeEmpresas -eq 0) { $QuantidadeEmpresas = 100 }
        if ([string]::IsNullOrWhiteSpace($DuracaoMaximaLote)) { $DuracaoMaximaLote = '2h' }
        if ($LimiteP95Ms -eq 0) { $LimiteP95Ms = 1000 }

        Garantir ($TotalCompras -gt 0) 'TotalCompras deve ser positivo.'
        Garantir ($TamanhoLote -gt 0 -and $TamanhoLote -le $TotalCompras) `
            'TamanhoLote deve ser positivo e nao pode superar TotalCompras.'
        Garantir ($TaxaAlvo -gt 0 -and $Usuarios -gt 0 -and $QuantidadeEmpresas -gt 0) `
            'TaxaAlvo, Usuarios e QuantidadeEmpresas devem ser positivos.'
        Garantir ($QuantidadeEmpresas -le 999899) 'QuantidadeEmpresas excede a faixa de UUIDs reservada ao teste.'
        Garantir ($LimiteP95Ms -gt 0) 'LimiteP95Ms deve ser positivo.'
        $estoquePorEmpresa = [long][math]::Ceiling($TotalCompras / [double]$QuantidadeEmpresas) + 10L
        Garantir ($estoquePorEmpresa -le [int]::MaxValue) `
            'O estoque por empresa ultrapassa o limite INTEGER do PostgreSQL.'
        if ($TotalCompras -ge 100000) {
            Garantir ($AmbienteDedicado.IsPresent) @'
Volumes a partir de 100000 exigem -AmbienteDedicado. Nao execute trafego manual em paralelo
e reserve espaco para bancos, Kafka, WAL, logs e metricas antes de continuar.
'@
        }

        $pendencias = Obter-Pendencias
        Garantir ($pendencias.comprasEmAndamento -eq 0) 'Existem compras em andamento antes do teste.'
        Garantir ($pendencias.operacoesPagamentoPendentes -eq 0) 'Existem pagamentos em andamento antes do teste.'
        Garantir ($pendencias.notificacoesPendentes -eq 0) 'Existem notificacoes em andamento antes do teste.'
        Garantir ($pendencias.eventosPendentes -eq 0) 'Existem eventos pendentes antes do teste.'
        Garantir ($pendencias.eventosQuarentena -eq 0) 'Existem eventos em quarentena antes do teste.'

        $estado = [ordered]@{
            versao = 1
            idExecucao = $IdExecucao
            totalCompras = $TotalCompras
            tamanhoLote = $TamanhoLote
            taxaAlvo = $TaxaAlvo
            usuarios = $Usuarios
            quantidadeEmpresas = $QuantidadeEmpresas
            duracaoMaximaLote = $DuracaoMaximaLote
            limiteP95Ms = $LimiteP95Ms
            idProduto = [guid]::NewGuid().ToString()
            estoquePorEmpresa = $estoquePorEmpresa
            proximoOffset = 0L
            lotesConcluidos = 0
            preparacaoConcluida = $false
            concluida = $false
            concluidaEmUtc = $null
            iniciadaEmUtc = [DateTime]::UtcNow.ToString('o')
            atualizadaEmUtc = [DateTime]::UtcNow.ToString('o')
            tempoIngressaoSegundos = 0.0
            tempoConvergenciaSegundos = 0L
            ambienteDedicado = $AmbienteDedicado.IsPresent
            bytesBancosIniciais = Obter-TamanhoBancos
            bytesBancosFinais = $null
            bytesBancoPorCompra = $null
            contagensIniciais = Obter-ContagensIniciais
        }
        Escrever-JsonAtomico $arquivoEstado $estado
    }

    if (-not [bool]$estado.preparacaoConcluida) {
        Preparar-Estoque $estado
        $estado.preparacaoConcluida = $true
        $estado.atualizadaEmUtc = [DateTime]::UtcNow.ToString('o')
        Escrever-JsonAtomico $arquivoEstado $estado
        & (Join-Path $PSScriptRoot 'auditar-volume.ps1') `
            -ArquivoEstado $arquivoEstado -QuantidadeEsperada 0
    }

    if ([bool]$estado.concluida) {
        Write-Host 'A execucao ja esta concluida. Revalidando o resultado final...' -ForegroundColor Yellow
        & (Join-Path $PSScriptRoot 'auditar-volume.ps1') `
            -ArquivoEstado $arquivoEstado -QuantidadeEsperada ([long]$estado.totalCompras)
        return
    }

    $segundosMinimos = [math]::Ceiling(([long]$estado.totalCompras - [long]$estado.proximoOffset) / [double]$estado.taxaAlvo)
    $estimativaMinima = [TimeSpan]::FromSeconds($segundosMinimos)
    Write-Host ''
    Write-Host "Execucao: $IdExecucao" -ForegroundColor Cyan
    Write-Host "Restante: $([long]$estado.totalCompras - [long]$estado.proximoOffset) transacoes em lotes de $($estado.tamanhoLote)"
    Write-Host "Taxa-alvo: $($estado.taxaAlvo)/s; tempo minimo apenas de ingresso: $($estimativaMinima.ToString())"
    Write-Host "Checkpoint: $arquivoEstado"
    Write-Host 'A conclusao ponta a ponta sera medida separadamente depois de cada lote.'

    $lotesNestaExecucao = 0
    $auditoriaFinalExecutada = $false
    while ([long]$estado.proximoOffset -lt [long]$estado.totalCompras) {
        if ($MaximoLotesNestaExecucao -gt 0 -and $lotesNestaExecucao -ge $MaximoLotesNestaExecucao) {
            Write-Host "Pausa solicitada depois de $lotesNestaExecucao lote(s)." -ForegroundColor Yellow
            break
        }

        $offset = [long]$estado.proximoOffset
        $restante = [long]$estado.totalCompras - $offset
        $quantidade = [int][math]::Min([long]$estado.tamanhoLote, $restante)
        $esperadoAposLote = $offset + $quantidade
        Write-Host ''
        Write-Host "Lote $([int]$estado.lotesConcluidos + 1): indices $offset a $($esperadoAposLote - 1)" -ForegroundColor Cyan
        $metrica = Executar-LoteK6 $estado $offset $quantidade $pastaExecucao

        Write-Host 'Aguardando a saga e todas as filas convergirem...' -ForegroundColor Cyan
        $inicioConvergencia = Get-Date
        while ($true) {
            $pendencias = Obter-Pendencias
            $comprasCriadas = [long]$pendencias.compras - [long]$estado.contagensIniciais.compras
            $comprasConcluidas = [long]$pendencias.comprasConcluidas - [long]$estado.contagensIniciais.comprasConcluidas
            $notificacoesCriadas = [long]$pendencias.notificacoes - [long]$estado.contagensIniciais.notificacoes
            $decorrido = [int]((Get-Date) - $inicioConvergencia).TotalSeconds
            Write-Host "  ${decorrido}s: compras=$comprasCriadas/$esperadoAposLote, concluidas=$comprasConcluidas, em-andamento=$($pendencias.comprasEmAndamento), notificacoes=$notificacoesCriadas, outbox=$($pendencias.eventosPendentes)"

            Garantir ($pendencias.eventosQuarentena -eq 0) 'Um evento foi enviado para quarentena durante o volume.'
            Garantir ($pendencias.operacoesPagamentoFalhas -eq [long]$estado.contagensIniciais.operacoesFalhas) `
                'Uma operacao de pagamento terminou em falha definitiva.'
            Garantir ($pendencias.notificacoesFalhas -eq [long]$estado.contagensIniciais.notificacoesFalhas) `
                'Uma notificacao terminou em falha definitiva.'

            if ($comprasCriadas -eq $esperadoAposLote -and
                $comprasConcluidas -eq $esperadoAposLote -and
                $pendencias.comprasEmAndamento -eq 0 -and
                $notificacoesCriadas -eq $esperadoAposLote -and
                $pendencias.notificacoesPendentes -eq 0 -and
                $pendencias.operacoesPagamentoPendentes -eq 0 -and
                $pendencias.eventosPendentes -eq 0) {
                break
            }
            Garantir ($comprasCriadas -le $esperadoAposLote) `
                'Outro trafego criou compras durante o teste; use um ambiente dedicado.'
            if ($decorrido -ge $TempoMaximoConvergenciaSegundos) {
                throw "O lote nao convergiu em $TempoMaximoConvergenciaSegundos segundos. Retome com -IdExecucao $IdExecucao."
            }
            Start-Sleep -Seconds $IntervaloConsultaSegundos
        }
        $tempoConvergencia = [int]((Get-Date) - $inicioConvergencia).TotalSeconds

        $proximoNumeroLote = [int]$estado.lotesConcluidos + 1
        $auditoriaPesada = ($proximoNumeroLote % $AuditarACadaLotes -eq 0) -or `
            ($esperadoAposLote -eq [long]$estado.totalCompras)
        if ($auditoriaPesada) {
            & (Join-Path $PSScriptRoot 'auditar-volume.ps1') `
                -ArquivoEstado $arquivoEstado -QuantidadeEsperada $esperadoAposLote
            if ($esperadoAposLote -eq [long]$estado.totalCompras) {
                $auditoriaFinalExecutada = $true
            }
        }
        else {
            & (Join-Path $PSScriptRoot 'auditar-volume.ps1') `
                -ArquivoEstado $arquivoEstado -QuantidadeEsperada $esperadoAposLote `
                -SemInvariantesPesadas
        }

        Acrescentar-ResultadoCsv $arquivoResultados $estado $offset $quantidade $metrica $tempoConvergencia
        $estado.proximoOffset = $esperadoAposLote
        $estado.lotesConcluidos = $proximoNumeroLote
        $estado.tempoIngressaoSegundos = [double]$estado.tempoIngressaoSegundos + [double]$metrica.duracaoSegundos
        $estado.tempoConvergenciaSegundos = [long]$estado.tempoConvergenciaSegundos + $tempoConvergencia
        $estado.atualizadaEmUtc = [DateTime]::UtcNow.ToString('o')
        Escrever-JsonAtomico $arquivoEstado $estado
        $lotesNestaExecucao++
        Write-Host "Lote aprovado e checkpoint atualizado: $esperadoAposLote/$($estado.totalCompras)." -ForegroundColor Green
    }

    if ([long]$estado.proximoOffset -eq [long]$estado.totalCompras) {
        if (-not $auditoriaFinalExecutada) {
            & (Join-Path $PSScriptRoot 'auditar-volume.ps1') `
                -ArquivoEstado $arquivoEstado -QuantidadeEsperada ([long]$estado.totalCompras)
        }
        $estado.bytesBancosFinais = Obter-TamanhoBancos
        $crescimentoBancos = [math]::Max(
            0L,
            [long]$estado.bytesBancosFinais - [long]$estado.bytesBancosIniciais)
        $estado.bytesBancoPorCompra = if ([long]$estado.totalCompras -eq 0) {
            0.0
        } else {
            [math]::Round($crescimentoBancos / [double]$estado.totalCompras, 2)
        }
        $estado.concluida = $true
        $estado.concluidaEmUtc = [DateTime]::UtcNow.ToString('o')
        $estado.atualizadaEmUtc = $estado.concluidaEmUtc
        Escrever-JsonAtomico $arquivoEstado $estado
        $tempoAtivo = [TimeSpan]::FromSeconds(
            [double]$estado.tempoIngressaoSegundos + [double]$estado.tempoConvergenciaSegundos)
        Write-Host ''
        Write-Host "Volume comprovado: $($estado.totalCompras) transacoes reais, exatas e consistentes." -ForegroundColor Green
        Write-Host "Tempo ativo medido: $($tempoAtivo.ToString()). Resultados: $arquivoResultados"
        Write-Host "Crescimento dos seis bancos: $crescimentoBancos bytes ($($estado.bytesBancoPorCompra) bytes/compra nesta amostra)."
        Write-Host 'Kafka, WAL temporario, logs e metricas precisam de margem adicional.'
    }
    else {
        Write-Host ''
        Write-Host "Execucao pausada em $($estado.proximoOffset)/$($estado.totalCompras)." -ForegroundColor Yellow
        Write-Host "Para retomar: .\scripts\testar-volume.ps1 -IdExecucao $IdExecucao"
    }
}
finally {
    Pop-Location
}
