param(
    [int] $TaxaPico = 25,
    [string] $DuracaoSustentacao = '40s',
    [int] $TempoMaximoConvergenciaSegundos = 300,
    [int] $IntervaloConsultaSegundos = 5,
    [int] $MinimoComprasAceitas = 500,
    [int] $VusPreAlocados = 20,
    [int] $VusMaximos = 80,
    [int] $MaximoIteracoesDescartadas = 5,
    [int] $MaximoRequisicoesLimitadas = 0,
    [double] $MinimoTaxaAceitacao = 0.99,
    [string] $IdTrabalhador = 'local',
    [string] $DuracaoAquecimento = '20s',
    [string] $DuracaoSubida = '40s',
    [string] $DuracaoReducao = '20s'
)

$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot
$pastaAuditoria = Join-Path $raiz '.auditoria'
$relatorioTexto = Join-Path $pastaAuditoria 'k6-carga-checkout.txt'
$relatorioJson = Join-Path $pastaAuditoria 'k6-resumo-checkout.json'

function Consultar-Contagem {
    param(
        [string] $Servico,
        [string] $Banco,
        [string] $Consulta
    )

    $resultado = docker compose exec -T $Servico psql `
        -U orquestrapay -d $Banco -Atc $Consulta
    if ($LASTEXITCODE -ne 0) {
        throw "Falha ao consultar o banco $Banco."
    }
    return [int]($resultado | Select-Object -First 1)
}

New-Item -ItemType Directory -Path $pastaAuditoria -Force | Out-Null
Push-Location $raiz
try {
    $comprasAntes = Consultar-Contagem banco-checkout orquestrapay_checkout @'
SELECT COUNT(*) FROM compra;
'@
    $emAndamentoAntes = Consultar-Contagem banco-checkout orquestrapay_checkout @'
SELECT COUNT(*)
FROM compra
WHERE status NOT IN ('CONCLUIDA', 'RECUSADA', 'COMPENSADA');
'@
    if ($emAndamentoAntes -ne 0) {
        throw "Existem $emAndamentoAntes compras em andamento. Aguarde a convergencia antes de iniciar outra carga."
    }

    Write-Host '1/3 Executando carga HTTP controlada...' -ForegroundColor Cyan
    $preferenciaErroAnterior = $ErrorActionPreference
    try {
        # O Docker escreve mensagens de progresso em stderr mesmo quando termina com sucesso.
        $ErrorActionPreference = 'Continue'
        docker compose --profile carga run --rm -T `
            -e "TAXA_PICO=$TaxaPico" `
            -e "DURACAO_AQUECIMENTO=$DuracaoAquecimento" `
            -e "DURACAO_SUBIDA=$DuracaoSubida" `
            -e "DURACAO_SUSTENTACAO=$DuracaoSustentacao" `
            -e "DURACAO_REDUCAO=$DuracaoReducao" `
            -e "MINIMO_COMPRAS_ACEITAS=$MinimoComprasAceitas" `
            -e "VUS_PRE_ALOCADOS=$VusPreAlocados" `
            -e "VUS_MAXIMOS=$VusMaximos" `
            -e "MAXIMO_ITERACOES_DESCARTADAS=$MaximoIteracoesDescartadas" `
            -e "MAXIMO_REQUISICOES_LIMITADAS=$MaximoRequisicoesLimitadas" `
            -e "MINIMO_TAXA_ACEITACAO=$MinimoTaxaAceitacao" `
            -e "ID_TRABALHADOR=$IdTrabalhador" `
            -v "${pastaAuditoria}:/relatorios" `
            k6 run --summary-export /relatorios/k6-resumo-checkout.json `
            /testes/carga-checkout.js 2>&1 |
            Tee-Object -FilePath $relatorioTexto
        $codigoSaidaK6 = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $preferenciaErroAnterior
    }
    if ($codigoSaidaK6 -ne 0) {
        throw "O k6 terminou com codigo $codigoSaidaK6."
    }

    Write-Host '2/3 Aguardando convergencia da saga...' -ForegroundColor Cyan
    $inicio = Get-Date
    while ($true) {
        $emAndamento = Consultar-Contagem banco-checkout orquestrapay_checkout @'
SELECT COUNT(*)
FROM compra
WHERE status NOT IN ('CONCLUIDA', 'RECUSADA', 'COMPENSADA');
'@
        $finalizadas = Consultar-Contagem banco-checkout orquestrapay_checkout @'
SELECT COUNT(*)
FROM compra
WHERE status IN ('CONCLUIDA', 'RECUSADA', 'COMPENSADA');
'@
        $notificacoes = Consultar-Contagem banco-notificacao orquestrapay_notificacao @'
SELECT COUNT(*) FROM notificacao;
'@
        $notificacoesPendentes = Consultar-Contagem banco-notificacao orquestrapay_notificacao @'
SELECT COUNT(*) FROM notificacao WHERE status <> 'ENVIADA';
'@
        $decorrido = [int]((Get-Date) - $inicio).TotalSeconds
        Write-Host "  ${decorrido}s: em andamento=$emAndamento, finalizadas=$finalizadas, notificacoes=$notificacoes, pendentes=$notificacoesPendentes"

        if ($emAndamento -eq 0 -and
            $finalizadas -eq $notificacoes -and
            $notificacoesPendentes -eq 0) {
            break
        }
        if ($decorrido -ge $TempoMaximoConvergenciaSegundos) {
            throw "A saga nao convergiu em $TempoMaximoConvergenciaSegundos segundos."
        }
        Start-Sleep -Seconds $IntervaloConsultaSegundos
    }
    $tempoConvergencia = [int]((Get-Date) - $inicio).TotalSeconds

    Write-Host '3/3 Auditando os seis dominios depois da carga...' -ForegroundColor Cyan
    & (Join-Path $PSScriptRoot 'auditar-consistencia.ps1') -EsperaSegundos 0
    if ($LASTEXITCODE -ne 0) {
        throw 'A auditoria de consistencia falhou depois da carga.'
    }

    $comprasDepois = Consultar-Contagem banco-checkout orquestrapay_checkout @'
SELECT COUNT(*) FROM compra;
'@
    $comprasCriadas = $comprasDepois - $comprasAntes
    Write-Host ''
    Write-Host "Carga e consistencia aprovadas: $comprasCriadas compras criadas e convergencia em ${tempoConvergencia}s." -ForegroundColor Green
    Write-Host "Relatorios: $relatorioTexto e $relatorioJson"
}
finally {
    Pop-Location
}
