param(
    [string] $UrlPagamento = 'http://localhost:8083'
)

$ErrorActionPreference = 'Stop'

function Garantir {
    param([bool] $Condicao, [string] $Mensagem)

    if (-not $Condicao) {
        throw $Mensagem
    }
}
function Executar-Sql {
    param([string] $Comando)

    $resultado = docker compose exec -T banco-pagamento psql `
        -U orquestrapay -d orquestrapay_pagamento -tA -c $Comando
    if ($LASTEXITCODE -ne 0) {
        throw 'Falha ao preparar ou consultar a quarentena no PostgreSQL.'
    }
    return $resultado
}

function Tratar-Evento {
    param(
        [string] $Acao,
        [guid] $IdEmpresa,
        [guid] $IdEvento,
        [string] $Motivo
    )

    Invoke-RestMethod -Method POST `
        -Uri "$UrlPagamento/api/v1/admin/quarentena/$IdEvento/$Acao" `
        -Headers @{ 'X-Empresa-Id' = $IdEmpresa.ToString() } `
        -ContentType 'application/json' `
        -Body (@{ motivo = $Motivo } | ConvertTo-Json) | Out-Null
}

$idEmpresa = [guid]::NewGuid()
$outraEmpresa = [guid]::NewGuid()
$idEventoReprocessar = [guid]::NewGuid()
$idEventoDescartar = [guid]::NewGuid()
$idCompraReprocessar = [guid]::NewGuid()
$idCompraDescartar = [guid]::NewGuid()
$idCorrelacao = [guid]::NewGuid()
$cabecalhos = @{ 'X-Empresa-Id' = $idEmpresa.ToString() }

try {
    Write-Host '1/5 Preparando dois incidentes reais na quarentena do outbox...'
    $insercao = @"
INSERT INTO evento_saida (
    id_evento, tipo, versao, id_correlacao, id_empresa,
    id_compra, origem, conteudo, ocorrido_em,
    tentativas, ultimo_erro, proxima_tentativa_em, descartado_em
) VALUES
(
    '$idEventoReprocessar', 'TIPO_TESTE_QUARENTENA', 1, '$idCorrelacao', '$idEmpresa',
    '$idCompraReprocessar', 'teste-e2e', '{}', CURRENT_TIMESTAMP,
    12, 'Falha controlada para reprocessamento', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
),
(
    '$idEventoDescartar', 'TIPO_TESTE_QUARENTENA', 1, '$idCorrelacao', '$idEmpresa',
    '$idCompraDescartar', 'teste-e2e', '{}', CURRENT_TIMESTAMP,
    12, 'Falha controlada para descarte', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);
"@
    Executar-Sql $insercao | Out-Null

    Write-Host '2/5 Consultando a quarentena ativa com isolamento por empresa...'
    $ativos = Invoke-RestMethod `
        -Uri "$UrlPagamento/api/v1/admin/quarentena?status=ATIVA" `
        -Headers $cabecalhos
    Garantir ($ativos.total -eq 2) 'A API nao listou os dois incidentes ativos.'
    Garantir (-not ($ativos.itens[0].PSObject.Properties.Name -contains 'conteudo')) `
        'A API de quarentena expos indevidamente o payload do evento.'
    $isolada = Invoke-RestMethod `
        -Uri "$UrlPagamento/api/v1/admin/quarentena?status=TODAS" `
        -Headers @{ 'X-Empresa-Id' = $outraEmpresa.ToString() }
    Garantir ($isolada.total -eq 0) 'Uma empresa conseguiu enxergar a quarentena de outra.'

    Write-Host '3/5 Reprocessando com motivo e conferindo a auditoria pela API...'
    Tratar-Evento 'reprocessar' $idEmpresa $idEventoReprocessar `
        'Infraestrutura Kafka normalizada e validada pelo operador'
    $auditoriaReprocessamento = @(
        Invoke-RestMethod `
            -Uri "$UrlPagamento/api/v1/admin/quarentena/$idEventoReprocessar/auditoria" `
            -Headers $cabecalhos
    )
    Garantir ($auditoriaReprocessamento.Count -eq 1) `
        'O reprocessamento nao criou exatamente uma auditoria.'
    Garantir ($auditoriaReprocessamento[0].acao -eq 'REPROCESSAR') `
        'A acao de reprocessamento foi auditada incorretamente.'
    Garantir ($auditoriaReprocessamento[0].tentativasAnteriores -eq 12) `
        'A auditoria nao preservou as tentativas anteriores.'

    Write-Host '4/5 Descartando definitivamente e consultando o historico resolvido...'
    $motivoDescarte = 'Evento invalido confirmado pelo responsavel do dominio'
    Tratar-Evento 'descartar' $idEmpresa $idEventoDescartar $motivoDescarte
    $resolvidos = Invoke-RestMethod `
        -Uri "$UrlPagamento/api/v1/admin/quarentena?status=RESOLVIDA" `
        -Headers $cabecalhos
    $resolvido = @($resolvidos.itens) | Where-Object { $_.idEvento -eq $idEventoDescartar }
    Garantir ($null -ne $resolvido) 'O descarte definitivo nao apareceu no historico.'
    Garantir ($resolvido.status -eq 'RESOLVIDA') 'O incidente nao foi marcado como resolvido.'
    Garantir ($resolvido.motivoResolucao -eq $motivoDescarte) 'O motivo do descarte nao foi preservado.'
    $auditoriaDescarte = @(
        Invoke-RestMethod `
            -Uri "$UrlPagamento/api/v1/admin/quarentena/$idEventoDescartar/auditoria" `
            -Headers $cabecalhos
    )
    Garantir ($auditoriaDescarte[0].acao -eq 'DESCARTAR_DEFINITIVAMENTE') `
        'A decisao de descarte nao foi auditada.'

    Write-Host '5/5 Validando estado persistente e contador de quarentena ativa...'
    $consulta = "SELECT " +
        "(SELECT reprocessamentos FROM evento_saida WHERE id_evento = '$idEventoReprocessar')," +
        "(SELECT COUNT(*) FROM evento_saida WHERE id_empresa = '$idEmpresa' AND descartado_em IS NOT NULL AND resolvido_em IS NULL)," +
        "(SELECT COUNT(*) FROM auditoria_quarentena WHERE id_evento IN ('$idEventoReprocessar', '$idEventoDescartar'));"
    $linha = (Executar-Sql $consulta | Select-Object -Last 1).Trim() -split '\|'
    Garantir ([int] $linha[0] -eq 1) 'O contador de reprocessamentos nao foi incrementado.'
    Garantir ([int] $linha[1] -eq 0) 'Ainda existe incidente ativo depois das duas decisoes.'
    Garantir ([int] $linha[2] -eq 2) 'A quantidade de auditorias persistidas esta incorreta.'

    Write-Host 'Quarentena operacional validada de ponta a ponta.' -ForegroundColor Green
    [pscustomobject]@{
        idEmpresa = $idEmpresa
        reprocessamentos = [int] $linha[0]
        quarentenasAtivas = [int] $linha[1]
        auditorias = [int] $linha[2]
        isolamentoEntreEmpresas = 'OK'
        payloadExposto = $false
    } | Format-List
} finally {
    $limpeza = "DELETE FROM auditoria_quarentena WHERE id_evento IN ('$idEventoReprocessar', '$idEventoDescartar'); " +
        "DELETE FROM evento_saida WHERE id_evento IN ('$idEventoReprocessar', '$idEventoDescartar');"
    Executar-Sql $limpeza | Out-Null
}
