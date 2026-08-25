param(
    [ValidateRange(5, 250)]
    [int] $TaxaPico = 20,
    [string] $DuracaoSustentacao = '60s',
    [ValidateRange(1, 120)]
    [int] $AguardarAntesInterrupcaoSegundos = 15,
    [ValidateRange(1, 120)]
    [int] $DuracaoInterrupcaoSegundos = 10,
    [ValidateRange(1, 10000000)]
    [int] $MinimoComprasAceitas = 500,
    [ValidateRange(0, 10000000)]
    [int] $MaximoRequisicoesLimitadas = 50,
    [ValidateRange(5, 2000)]
    [int] $VusMaximos = 80,
    [string] $DuracaoAquecimento = '20s',
    [string] $DuracaoSubida = '40s',
    [string] $DuracaoReducao = '20s'
)

$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot
$servicoInterrompido = 'servico-pagamento'
$carga = $null
$servicoFoiInterrompido = $false

function Consultar-Pagamentos {
    $resultado = docker compose exec -T banco-pagamento psql `
        -U orquestrapay -d orquestrapay_pagamento -Atc 'SELECT COUNT(*) FROM pagamento;'
    if ($LASTEXITCODE -ne 0) {
        throw 'Nao foi possivel contar os pagamentos.'
    }
    return [int]($resultado | Select-Object -First 1)
}

Push-Location $raiz
try {
    $estado = docker compose ps --status running --services
    if ($estado -notcontains $servicoInterrompido) {
        throw "O servico $servicoInterrompido precisa estar em execucao."
    }
    $pagamentosAntes = Consultar-Pagamentos

    Write-Host '1/4 Iniciando carga em segundo plano...' -ForegroundColor Cyan
    $scriptCarga = Join-Path $PSScriptRoot 'testar-carga.ps1'
    $carga = Start-Job -Name 'carga-interrupcao-orquestrapay' -ScriptBlock {
        param(
            $ScriptCarga,
            $Taxa,
            $Duracao,
            $Minimo,
            $Vus,
            $DuracaoAquecimento,
            $DuracaoSubida,
            $DuracaoReducao,
            $MaximoLimitadas
        )
        & $ScriptCarga `
            -TaxaPico $Taxa `
            -DuracaoSustentacao $Duracao `
            -TempoMaximoConvergenciaSegundos 600 `
            -IntervaloConsultaSegundos 5 `
            -MinimoComprasAceitas $Minimo `
            -VusPreAlocados ([Math]::Max(10, [Math]::Floor($Vus / 2))) `
            -VusMaximos $Vus `
            -MaximoIteracoesDescartadas 20 `
            -MaximoRequisicoesLimitadas $MaximoLimitadas `
            -MinimoTaxaAceitacao 0.99 `
            -IdTrabalhador 'interrupcao' `
            -DuracaoAquecimento $DuracaoAquecimento `
            -DuracaoSubida $DuracaoSubida `
            -DuracaoReducao $DuracaoReducao
    } -ArgumentList @(
            $scriptCarga,
            $TaxaPico,
            $DuracaoSustentacao,
            $MinimoComprasAceitas,
            $VusMaximos,
            $DuracaoAquecimento,
            $DuracaoSubida,
            $DuracaoReducao,
            $MaximoRequisicoesLimitadas
        )

    Start-Sleep -Seconds $AguardarAntesInterrupcaoSegundos
    if ($carga.State -eq 'Failed') {
        Receive-Job $carga
        throw 'A carga falhou antes da interrupcao planejada.'
    }

    Write-Host '2/4 Interrompendo o consumidor sem encerramento gracioso...' -ForegroundColor Yellow
    docker compose kill $servicoInterrompido | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Nao foi possivel interromper $servicoInterrompido."
    }
    $servicoFoiInterrompido = $true

    Start-Sleep -Seconds $DuracaoInterrupcaoSegundos
    Write-Host '3/4 Recolocando o consumidor no grupo Kafka...' -ForegroundColor Cyan
    docker compose up -d --wait $servicoInterrompido | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Nao foi possivel reiniciar $servicoInterrompido."
    }
    $servicoFoiInterrompido = $false

    Wait-Job $carga | Out-Null
    Receive-Job $carga
    if ($carga.State -ne 'Completed') {
        throw 'A carga nao concluiu depois da interrupcao.'
    }

    $pagamentosDepois = Consultar-Pagamentos
    if ($pagamentosDepois -le $pagamentosAntes) {
        throw 'A carga nao gerou novos pagamentos durante a interrupcao.'
    }

    Write-Host '4/4 Confirmando ausencia de efeitos financeiros duplicados...' -ForegroundColor Cyan
    $duplicados = docker compose exec -T banco-pagamento psql `
        -U orquestrapay -d orquestrapay_pagamento -Atc @'
SELECT COUNT(*)
FROM (
    SELECT id_compra
    FROM pagamento
    GROUP BY id_compra
    HAVING COUNT(*) > 1
) repetidos;
'@
    if ($LASTEXITCODE -ne 0 -or [int]($duplicados | Select-Object -First 1) -ne 0) {
        throw 'A interrupcao produziu pagamentos duplicados.'
    }

    & (Join-Path $PSScriptRoot 'auditar-consistencia.ps1')
    if ($LASTEXITCODE -ne 0) {
        throw 'A auditoria final falhou depois da interrupcao.'
    }

    $pagamentosCriados = $pagamentosDepois - $pagamentosAntes
    Write-Host "Interrupcao, redelivery e recuperacao aprovadas com $pagamentosCriados novos pagamentos." -ForegroundColor Green
}
finally {
    if ($servicoFoiInterrompido) {
        docker compose up -d --wait $servicoInterrompido | Out-Null
    }
    if ($null -ne $carga) {
        Remove-Job $carga -Force -ErrorAction SilentlyContinue
    }
    Pop-Location
}
