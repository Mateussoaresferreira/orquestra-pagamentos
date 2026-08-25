param(
    [int]$TempoLimiteSegundos = 60
)

$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot
Set-Location $raiz

function Garantir([bool]$Condicao, [string]$Mensagem) {
    if (-not $Condicao) {
        throw $Mensagem
    }
}

function Executar-Sql([string]$Servico, [string]$Banco, [string]$Sql) {
    $resultado = docker compose exec -T $Servico `
        psql -U orquestrapay -d $Banco -v ON_ERROR_STOP=1 -tAc $Sql
    if ($LASTEXITCODE -ne 0) {
        throw "Falha ao executar SQL em $Servico."
    }
    return ($resultado | Out-String).Trim()
}

function Obter-OffsetDlt {
    $linhas = docker compose exec -T kafka `
        /opt/kafka/bin/kafka-get-offsets.sh `
        --bootstrap-server kafka:29092 `
        --topic orquestrapay.estoque.v1.dlt
    if ($LASTEXITCODE -ne 0) {
        throw 'Nao foi possivel consultar os offsets do topico DLT.'
    }

    $total = 0L
    foreach ($linha in $linhas) {
        if ($linha -match ':(\d+)$') {
            $total += [long]$Matches[1]
        }
    }
    return $total
}

$idEvento = [guid]::NewGuid()
$idEmpresa = [guid]::NewGuid()
$idCompra = [guid]::NewGuid()
$idCorrelacao = [guid]::NewGuid()
$offsetInicial = Obter-OffsetDlt

try {
    Write-Host '1/3 Publicando um comando de estoque com versao propositalmente desconhecida...'
    $sql = @"
INSERT INTO evento_saida (
    id_evento, tipo, versao, id_correlacao, id_empresa,
    id_compra, origem, conteudo, ocorrido_em
) VALUES (
    '$idEvento', 'RESERVAR_ESTOQUE', 99, '$idCorrelacao', '$idEmpresa',
    '$idCompra', 'teste-contrato-dlt', '{}', CURRENT_TIMESTAMP
);
"@
    Executar-Sql 'banco-checkout' 'orquestrapay_checkout' $sql | Out-Null

    Write-Host '2/3 Aguardando retries e encaminhamento automatico para a DLT...'
    $limite = (Get-Date).AddSeconds($TempoLimiteSegundos)
    $offsetAtual = $offsetInicial
    do {
        Start-Sleep -Seconds 2
        $offsetAtual = Obter-OffsetDlt
    } while ($offsetAtual -le $offsetInicial -and (Get-Date) -lt $limite)

    Garantir ($offsetAtual -eq ($offsetInicial + 1)) `
        "A DLT deveria receber exatamente um evento; offsets: $offsetInicial -> $offsetAtual."

    Write-Host '3/3 Confirmando que o evento invalido nao alterou o estoque nem a idempotencia...'
    $publicado = Executar-Sql 'banco-checkout' 'orquestrapay_checkout' `
        "SELECT COUNT(*) FROM evento_saida WHERE id_evento = '$idEvento' AND publicado_em IS NOT NULL;"
    $reservas = Executar-Sql 'banco-estoque' 'orquestrapay_estoque' `
        "SELECT COUNT(*) FROM reserva_estoque WHERE id_compra = '$idCompra';"
    $processados = Executar-Sql 'banco-estoque' 'orquestrapay_estoque' `
        "SELECT COUNT(*) FROM evento_processado WHERE id_evento = '$idEvento';"

    Garantir ($publicado -eq '1') 'O produtor nao confirmou a publicacao no Kafka.'
    Garantir ($reservas -eq '0') 'O consumidor alterou o estoque com contrato incompativel.'
    Garantir ($processados -eq '0') 'O evento incompativel entrou indevidamente na idempotencia do dominio.'

    Write-Host "OK: versao 99 rejeitada; DLT $offsetInicial -> $offsetAtual; dominio intacto."
}
finally {
    Executar-Sql 'banco-checkout' 'orquestrapay_checkout' `
        "DELETE FROM evento_saida WHERE id_evento = '$idEvento';" | Out-Null
}
