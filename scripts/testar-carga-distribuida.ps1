param(
    [ValidateRange(2, 20)]
    [int] $Trabalhadores = 2,
    [ValidateRange(1, 2000)]
    [int] $TaxaPico = 50,
    [string] $DuracaoSustentacao = '2m',
    [ValidateRange(20, 5000)]
    [int] $VusMaximos = 200,
    [ValidateRange(1, 10000000)]
    [int] $MinimoComprasAceitasTotal = 1000,
    [ValidateRange(30, 3600)]
    [int] $TempoMaximoConvergenciaSegundos = 600,
    [ValidateRange(1, 60)]
    [int] $IntervaloConsultaSegundos = 5,
    [string] $DuracaoAquecimento = '20s',
    [string] $DuracaoSubida = '40s',
    [string] $DuracaoReducao = '20s'
)

$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot
$pastaAuditoria = Join-Path $raiz '.auditoria\carga-distribuida'
$sequencia = @('0') + (1..($Trabalhadores - 1) | ForEach-Object { "$_/$Trabalhadores" }) + @('1')
$sequenciaTexto = $sequencia -join ','
$minimoPorTrabalhador = [Math]::Max(1, [Math]::Floor($MinimoComprasAceitasTotal / $Trabalhadores))
$vusPorTrabalhador = [Math]::Max(20, [Math]::Ceiling($VusMaximos / $Trabalhadores))

function Consultar-Contagem {
    param([string] $Servico, [string] $Banco, [string] $Consulta)

    $resultado = docker compose exec -T $Servico psql `
        -U orquestrapay -d $Banco -Atc $Consulta
    if ($LASTEXITCODE -ne 0) {
        throw "Falha ao consultar o banco $Banco."
    }
    return [int]($resultado | Select-Object -First 1)
}

New-Item -ItemType Directory -Path $pastaAuditoria -Force | Out-Null
$localizacaoOriginal = (Get-Location).Path
$tarefas = @()
try {
    Set-Location $raiz
    $emAndamentoAntes = Consultar-Contagem banco-checkout orquestrapay_checkout @'
SELECT COUNT(*) FROM compra
WHERE status NOT IN ('CONCLUIDA', 'RECUSADA', 'COMPENSADA');
'@
    if ($emAndamentoAntes -ne 0) {
        throw "Existem $emAndamentoAntes compras em andamento antes da carga distribuida."
    }
    $comprasAntes = Consultar-Contagem banco-checkout orquestrapay_checkout `
        'SELECT COUNT(*) FROM compra;'

    $tarefas = for ($indice = 0; $indice -lt $Trabalhadores; $indice++) {
        $inicioSegmento = if ($indice -eq 0) { '0' } else { "$indice/$Trabalhadores" }
        $fimSegmento = if ($indice -eq ($Trabalhadores - 1)) { '1' } else { "$(($indice + 1))/$Trabalhadores" }
        $segmento = "${inicioSegmento}:${fimSegmento}"
        $nomeRelatorio = "trabalhador-$indice.json"

        Start-Job -Name "k6-orquestrapay-$indice" -ScriptBlock {
            param(
                $Raiz,
                $Indice,
                $Segmento,
                $Sequencia,
                $TaxaPico,
                $Duracao,
                $Vus,
                $Minimo,
                $PastaRelatorios,
                $NomeRelatorio,
                $DuracaoAquecimento,
                $DuracaoSubida,
                $DuracaoReducao
            )

            Push-Location $Raiz
            try {
                docker compose --profile carga run --rm -T `
                    -e "ID_TRABALHADOR=distribuido-$Indice" `
                    -e "TAXA_PICO=$TaxaPico" `
                    -e "DURACAO_AQUECIMENTO=$DuracaoAquecimento" `
                    -e "DURACAO_SUBIDA=$DuracaoSubida" `
                    -e "DURACAO_SUSTENTACAO=$Duracao" `
                    -e "DURACAO_REDUCAO=$DuracaoReducao" `
                    -e "VUS_PRE_ALOCADOS=$([Math]::Max(10, [Math]::Floor($Vus / 2)))" `
                    -e "VUS_MAXIMOS=$Vus" `
                    -e "MINIMO_COMPRAS_ACEITAS=$Minimo" `
                    -v "${PastaRelatorios}:/relatorios" `
                    k6 run `
                    --execution-segment $Segmento `
                    --execution-segment-sequence $Sequencia `
                    --summary-export "/relatorios/$NomeRelatorio" `
                    /testes/carga-checkout.js
                if ($LASTEXITCODE -ne 0) {
                    throw "O trabalhador $Indice terminou com codigo $LASTEXITCODE."
                }
            }
            finally {
                Pop-Location
            }
        } -ArgumentList @(
            $raiz,
            $indice,
            $segmento,
            $sequenciaTexto,
            $TaxaPico,
            $DuracaoSustentacao,
            $vusPorTrabalhador,
            $minimoPorTrabalhador,
            $pastaAuditoria,
            $nomeRelatorio,
            $DuracaoAquecimento,
            $DuracaoSubida,
            $DuracaoReducao
        )
    }

    $tarefas | Wait-Job | Out-Null
    $falhas = @($tarefas | Where-Object State -ne 'Completed')
    $tarefas | Receive-Job -ErrorAction Continue
    if ($falhas.Count -gt 0) {
        throw "$($falhas.Count) trabalhador(es) de carga falharam."
    }

    Write-Host 'Aguardando a convergencia de todos os eventos...' -ForegroundColor Cyan
    $inicioConvergencia = Get-Date
    while ($true) {
        $emAndamento = Consultar-Contagem banco-checkout orquestrapay_checkout @'
SELECT COUNT(*) FROM compra
WHERE status NOT IN ('CONCLUIDA', 'RECUSADA', 'COMPENSADA');
'@
        $outboxesPendentes = 0
        foreach ($banco in @(
                @('banco-checkout', 'orquestrapay_checkout'),
                @('banco-estoque', 'orquestrapay_estoque'),
                @('banco-risco', 'orquestrapay_risco'),
                @('banco-pagamento', 'orquestrapay_pagamento'),
                @('banco-razao', 'orquestrapay_razao'),
                @('banco-notificacao', 'orquestrapay_notificacao')
            )) {
            $outboxesPendentes += Consultar-Contagem $banco[0] $banco[1] `
                "SELECT COUNT(*) FROM evento_saida WHERE publicado_em IS NULL;"
        }
        $notificacoesPendentes = Consultar-Contagem banco-notificacao orquestrapay_notificacao `
            "SELECT COUNT(*) FROM notificacao WHERE status <> 'ENVIADA';"
        $decorrido = [int]((Get-Date) - $inicioConvergencia).TotalSeconds
        Write-Host "  ${decorrido}s: compras em andamento=$emAndamento, outboxes pendentes=$outboxesPendentes, notificacoes pendentes=$notificacoesPendentes"
        if ($emAndamento -eq 0 -and $outboxesPendentes -eq 0 -and $notificacoesPendentes -eq 0) {
            break
        }
        if ($decorrido -ge $TempoMaximoConvergenciaSegundos) {
            throw "A carga distribuida nao convergiu em $TempoMaximoConvergenciaSegundos segundos."
        }
        Start-Sleep -Seconds $IntervaloConsultaSegundos
    }

    $comprasDepois = Consultar-Contagem banco-checkout orquestrapay_checkout `
        'SELECT COUNT(*) FROM compra;'
    $comprasCriadas = $comprasDepois - $comprasAntes
    if ($comprasCriadas -lt $MinimoComprasAceitasTotal) {
        throw "Foram criadas $comprasCriadas compras; o minimo era $MinimoComprasAceitasTotal."
    }

    & (Join-Path $PSScriptRoot 'auditar-consistencia.ps1') -EsperaSegundos 0
    if ($LASTEXITCODE -ne 0) {
        throw 'A consistencia distribuida falhou depois da carga.'
    }

    Write-Host "Carga distribuida aprovada com $Trabalhadores geradores e $comprasCriadas compras." -ForegroundColor Green
    Write-Host "Relatorios: $pastaAuditoria"
}
finally {
    Set-Location $localizacaoOriginal
    $tarefas | Remove-Job -Force -ErrorAction SilentlyContinue
}
