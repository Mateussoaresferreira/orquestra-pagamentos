param(
    [ValidateRange(1, 20)]
    [int] $Execucoes = 3,

    [ValidateRange(1, 5)]
    [int] $Paralelismo = 3,

    [ValidateRange(30, 900)]
    [int] $TempoLimiteSegundos = 300
)

$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot
$executor = Join-Path $PSScriptRoot 'testar-postman.ps1'
$diretorioRelatorios = Join-Path $raiz 'target/auditoria-postman'
$identificador = [DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss')
$trabalhos = [System.Collections.Generic.List[object]]::new()
$resultados = [System.Collections.Generic.List[object]]::new()

New-Item -ItemType Directory -Force -Path $diretorioRelatorios | Out-Null

function Aguardar-Lote {
    param([object[]] $Lote)

    $limite = [DateTimeOffset]::UtcNow.AddSeconds($TempoLimiteSegundos)
    while (@($Lote | Where-Object State -in @('NotStarted', 'Running')).Count -gt 0) {
        if ([DateTimeOffset]::UtcNow -gt $limite) {
            $Lote | Stop-Job -ErrorAction SilentlyContinue
            throw "A carga Postman excedeu $TempoLimiteSegundos segundos."
        }
        Start-Sleep -Milliseconds 250
    }

    foreach ($trabalho in $Lote) {
        $saida = @(Receive-Job -Job $trabalho -ErrorAction SilentlyContinue)
        if ($trabalho.State -ne 'Completed') {
            throw "A execucao Postman $($trabalho.Name) terminou como $($trabalho.State): $($saida -join ' ')"
        }
    }
}

try {
    for ($inicio = 1; $inicio -le $Execucoes; $inicio += $Paralelismo) {
        $lote = [System.Collections.Generic.List[object]]::new()
        $fim = [Math]::Min($inicio + $Paralelismo - 1, $Execucoes)

        foreach ($numero in $inicio..$fim) {
            $relatorio = Join-Path $diretorioRelatorios "$identificador-$numero.json"
            $trabalho = Start-Job -Name "postman-$numero" -ArgumentList $executor, $relatorio -ScriptBlock {
                param($CaminhoExecutor, $CaminhoRelatorio)
                & $CaminhoExecutor -RelatorioJson $CaminhoRelatorio
            }
            $trabalho | Add-Member -NotePropertyName CaminhoRelatorio -NotePropertyValue $relatorio
            $trabalhos.Add($trabalho)
            $lote.Add($trabalho)
        }

        Aguardar-Lote $lote
    }

    foreach ($trabalho in $trabalhos) {
        if (-not (Test-Path $trabalho.CaminhoRelatorio)) {
            throw "O relatorio da execucao $($trabalho.Name) nao foi gerado."
        }

        $relatorio = Get-Content $trabalho.CaminhoRelatorio -Raw | ConvertFrom-Json
        $duracao = [long]$relatorio.run.timings.completed - [long]$relatorio.run.timings.started
        $resultado = [pscustomobject]@{
            execucao = $trabalho.Name
            requisicoes = [int]$relatorio.run.stats.requests.total
            requisicoesFalhas = [int]$relatorio.run.stats.requests.failed
            verificacoes = [int]$relatorio.run.stats.assertions.total
            verificacoesFalhas = [int]$relatorio.run.stats.assertions.failed
            duracaoMs = $duracao
        }
        $resultados.Add($resultado)
    }

    $falhas = @($resultados | Where-Object {
            $_.requisicoesFalhas -gt 0 -or $_.verificacoesFalhas -gt 0
        })
    if ($falhas.Count -gt 0) {
        $falhas | Format-Table -AutoSize | Out-String | Write-Error
        throw "$($falhas.Count) execucao(oes) Postman apresentaram falhas."
    }

    Write-Host 'Carga rapida da colecao Postman aprovada.' -ForegroundColor Green
    $resultados | Format-Table -AutoSize
    [pscustomobject]@{
        execucoes = $resultados.Count
        requisicoes = ($resultados | Measure-Object requisicoes -Sum).Sum
        verificacoes = ($resultados | Measure-Object verificacoes -Sum).Sum
        falhas = 0
        maiorDuracaoMs = ($resultados | Measure-Object duracaoMs -Maximum).Maximum
        relatorios = $diretorioRelatorios
    } | Format-List
}
finally {
    $trabalhos | Remove-Job -Force -ErrorAction SilentlyContinue
}
