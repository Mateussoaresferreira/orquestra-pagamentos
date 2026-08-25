param(
    [int] $EsperaSegundos = 10
)

$ErrorActionPreference = 'Stop'

function Garantir {
    param([bool] $Condicao, [string] $Mensagem)
    if (-not $Condicao) {
        throw $Mensagem
    }
}

function Consultar-Banco {
    param(
        [string] $Servico,
        [string] $Banco,
        [string] $Consulta
    )

    $linhas = docker compose exec -T $Servico psql `
        -U orquestrapay -d $Banco -Atc $Consulta
    Garantir ($LASTEXITCODE -eq 0) "Falha ao consultar o banco $Banco."
    return @($linhas | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}

function Criar-Indice {
    param(
        [string[]] $Linhas,
        [string] $Origem
    )

    $indice = @{}
    foreach ($linha in $Linhas) {
        $colunas = @($linha -split '\|')
        $chave = $colunas[0]
        Garantir (-not $indice.ContainsKey($chave)) `
            "Registro duplicado para a compra $chave em $Origem."
        $indice[$chave] = $colunas
    }
    return $indice
}

function Garantir-MesmasChaves {
    param(
        [hashtable] $Esperado,
        [hashtable] $Atual,
        [string] $Mensagem
    )

    $diferencas = @(Compare-Object `
            @($Esperado.Keys | Sort-Object) `
            @($Atual.Keys | Sort-Object))
    Garantir ($diferencas.Count -eq 0) $Mensagem
}

function Converter-Decimal {
    param([string] $Valor)
    return [decimal]::Parse(
        $Valor,
        [Globalization.NumberStyles]::Number,
        [Globalization.CultureInfo]::InvariantCulture)
}

if ($EsperaSegundos -gt 0) {
    Write-Host "Aguardando $EsperaSegundos segundos para estabilizar os eventos..." -ForegroundColor Cyan
    Start-Sleep -Seconds $EsperaSegundos
}

Write-Host '1/5 Carregando os registros dos seis dominios...' -ForegroundColor Cyan
$compras = Criar-Indice (Consultar-Banco banco-checkout orquestrapay_checkout @'
SELECT id_compra::text || '|' || id_empresa::text || '|' || valor_total::text || '|' || status || '|' ||
       id_reserva::text || '|' || COALESCE(id_pagamento::text, '') || '|' ||
       COALESCE(id_transacao_contabil::text, '')
FROM compra
ORDER BY id_compra;
'@) 'checkout'

$reservas = Criar-Indice (Consultar-Banco banco-estoque orquestrapay_estoque @'
SELECT id_compra::text || '|' || id_empresa::text || '|' || status || '|' || id_reserva::text
FROM reserva_estoque
ORDER BY id_compra;
'@) 'estoque'

$analises = Criar-Indice (Consultar-Banco banco-risco orquestrapay_risco @'
SELECT id_compra::text || '|' || id_empresa::text || '|' || valor_total::text || '|' || aprovada::text
FROM analise_risco
ORDER BY id_compra;
'@) 'risco'

$pagamentos = Criar-Indice (Consultar-Banco banco-pagamento orquestrapay_pagamento @'
SELECT id_compra::text || '|' || id_empresa::text || '|' || valor::text || '|' || status || '|' || id_pagamento::text
       || '|' || metodo_pagamento
FROM pagamento
ORDER BY id_compra;
'@) 'pagamento'

$transacoes = Criar-Indice (Consultar-Banco banco-razao orquestrapay_razao @'
SELECT id_compra::text || '|' || id_empresa::text || '|' || valor::text || '|' || status || '|' ||
       id_transacao::text || '|' || id_pagamento::text
FROM transacao_contabil
ORDER BY id_compra;
'@) 'razao contabil'

$notificacoes = Criar-Indice (Consultar-Banco banco-notificacao orquestrapay_notificacao @'
SELECT id_compra::text || '|' || id_empresa::text || '|' || status
FROM notificacao
ORDER BY id_compra;
'@) 'notificacao'

$comprasFinais = @{}
foreach ($idCompra in $compras.Keys) {
    if ($compras[$idCompra][3] -in @('CONCLUIDA', 'RECUSADA', 'COMPENSADA')) {
        $comprasFinais[$idCompra] = $compras[$idCompra]
    }
}

Write-Host '2/5 Comparando compras, empresas, valores e vinculos...' -ForegroundColor Cyan
Garantir-MesmasChaves $compras $reservas 'As compras e as reservas nao possuem os mesmos identificadores.'
Garantir-MesmasChaves $comprasFinais $notificacoes 'As compras finalizadas e as notificacoes nao possuem os mesmos identificadores.'

foreach ($idCompra in $compras.Keys) {
    $compra = $compras[$idCompra]
    $reserva = $reservas[$idCompra]
    $idEmpresa = $compra[1]
    $valor = Converter-Decimal $compra[2]
    $finalizada = $comprasFinais.ContainsKey($idCompra)

    Garantir ($reserva[1] -eq $idEmpresa) "Empresa divergente no estoque da compra $idCompra."
    Garantir ($reserva[3] -eq $compra[4]) "Reserva divergente na compra $idCompra."
    if ($finalizada) {
        $notificacao = $notificacoes[$idCompra]
        Garantir ($notificacao[1] -eq $idEmpresa) "Empresa divergente na notificacao da compra $idCompra."
        Garantir ($notificacao[2] -eq 'ENVIADA') `
            "Notificacao da compra $idCompra terminou com status $($notificacao[2]), em vez de ENVIADA."
    }
    else {
        Garantir (-not $notificacoes.ContainsKey($idCompra)) `
            "Compra em andamento $idCompra recebeu notificacao final prematuramente."
    }

    if ($reserva[2] -eq 'RECUSADA') {
        Garantir ($compra[3] -eq 'RECUSADA') "Compra $idCompra deveria estar recusada por estoque."
        Garantir (-not $analises.ContainsKey($idCompra)) "Compra $idCompra chegou ao risco sem estoque."
        Garantir (-not $pagamentos.ContainsKey($idCompra)) "Compra $idCompra chegou ao pagamento sem estoque."
        Garantir (-not $transacoes.ContainsKey($idCompra)) "Compra $idCompra chegou a razao sem estoque."
        continue
    }

    Garantir ($analises.ContainsKey($idCompra)) "Compra $idCompra passou pelo estoque, mas nao chegou ao risco."
    $analise = $analises[$idCompra]
    Garantir ($analise[1] -eq $idEmpresa) "Empresa divergente no risco da compra $idCompra."
    Garantir ((Converter-Decimal $analise[2]) -eq $valor) "Valor divergente no risco da compra $idCompra."
    Garantir ($analise[3] -in @('true', 'false')) "Decisao de risco invalida na compra $idCompra."

    if ($analise[3] -eq 'false') {
        Garantir ($compra[3] -eq 'RECUSADA') "Compra $idCompra deveria estar recusada por risco."
        Garantir ($reserva[2] -eq 'LIBERADA') "Estoque da compra $idCompra nao foi liberado apos recusa de risco."
        Garantir (-not $pagamentos.ContainsKey($idCompra)) "Compra $idCompra chegou ao pagamento apos recusa de risco."
        Garantir (-not $transacoes.ContainsKey($idCompra)) "Compra $idCompra chegou a razao apos recusa de risco."
        continue
    }

    Garantir ($pagamentos.ContainsKey($idCompra)) "Compra $idCompra aprovada no risco nao chegou ao pagamento."
    $pagamento = $pagamentos[$idCompra]
    Garantir ($pagamento[1] -eq $idEmpresa) "Empresa divergente no pagamento da compra $idCompra."
    Garantir ((Converter-Decimal $pagamento[2]) -eq $valor) "Valor divergente no pagamento da compra $idCompra."

    if ($pagamento[3] -in @(
            'PENDENTE',
            'PROCESSANDO',
            'CONFIRMACAO_PENDENTE',
            'AGUARDANDO_CONFIRMACAO',
            'FALHA_TECNICA')) {
        Garantir ($compra[3] -eq 'AGUARDANDO_PAGAMENTO') `
            "Pagamento em andamento da compra $idCompra nao corresponde ao estado do checkout."
        Garantir ($reserva[2] -eq 'RESERVADA') `
            "Compra em pagamento $idCompra nao manteve a reserva ativa."
        Garantir (-not $transacoes.ContainsKey($idCompra)) `
            "Compra em pagamento $idCompra chegou prematuramente a razao."
        continue
    }

    if ($pagamento[3] -in @('ESTORNO_PENDENTE', 'ESTORNANDO')) {
        if ($transacoes.ContainsKey($idCompra)) {
            Garantir ($compra[3] -eq 'COMPENSANDO') `
                "Estorno contabil em andamento da compra $idCompra nao corresponde ao checkout."
            Garantir ($reserva[2] -in @('RESERVADA', 'LIBERADA')) `
                "Reserva em estado invalido durante a compensacao da compra $idCompra."
        }
        else {
            Garantir ($pagamento[5] -eq 'PIX') `
                "Estorno sem tentativa contabil na compra $idCompra nao e PIX."
            Garantir ($compra[3] -eq 'RECUSADA') `
                "PIX tardio em estorno na compra $idCompra nao permaneceu recusado."
            Garantir ($reserva[2] -eq 'LIBERADA') `
                "Estoque do PIX tardio $idCompra nao foi liberado."
        }
        Garantir ($compra[5] -eq $pagamento[4]) `
            "Pagamento em estorno divergente na compra $idCompra."
        continue
    }

    switch ($pagamento[3]) {
        'RECUSADO' {
            Garantir ($compra[3] -eq 'RECUSADA') "Compra $idCompra deveria estar recusada pelo emissor."
            Garantir ($reserva[2] -eq 'LIBERADA') "Estoque da compra $idCompra nao foi liberado apos recusa do emissor."
            Garantir (-not $transacoes.ContainsKey($idCompra)) "Pagamento recusado da compra $idCompra chegou a razao."
        }
        'EXPIRADO' {
            Garantir ($pagamento[5] -eq 'PIX') "Pagamento expirado da compra $idCompra nao e PIX."
            Garantir ($compra[3] -eq 'RECUSADA') "Compra com PIX expirado $idCompra nao foi recusada."
            Garantir ($reserva[2] -eq 'LIBERADA') "Estoque da compra com PIX expirado $idCompra nao foi liberado."
            Garantir ($compra[5] -eq $pagamento[4]) "Pagamento PIX expirado divergente na compra $idCompra."
            Garantir (-not $transacoes.ContainsKey($idCompra)) "PIX expirado da compra $idCompra chegou a razao."
        }
        'AUTORIZADO' {
            Garantir ($compra[3] -eq 'CONCLUIDA') "Compra autorizada $idCompra nao foi concluida."
            Garantir ($reserva[2] -eq 'RESERVADA') "Reserva da compra concluida $idCompra nao permaneceu ativa."
            Garantir ($compra[5] -eq $pagamento[4]) "Pagamento divergente no checkout da compra $idCompra."
            Garantir ($transacoes.ContainsKey($idCompra)) "Compra autorizada $idCompra nao chegou a razao."
        }
        'ESTORNADO' {
            if ($transacoes.ContainsKey($idCompra)) {
                Garantir ($compra[3] -eq 'COMPENSADA') `
                    "Compra estornada $idCompra com tentativa contabil nao foi compensada."
            }
            else {
                Garantir ($pagamento[5] -eq 'PIX') `
                    "Pagamento estornado sem tentativa contabil na compra $idCompra nao e PIX."
                Garantir ($compra[3] -eq 'RECUSADA') `
                    "PIX estornado antes da contabilidade na compra $idCompra nao permaneceu recusado."
            }
            Garantir ($reserva[2] -eq 'LIBERADA') "Estoque da compra compensada $idCompra nao foi liberado."
            Garantir ($compra[5] -eq $pagamento[4]) "Pagamento estornado divergente na compra $idCompra."
        }
        default {
            throw "Status de pagamento inesperado na compra ${idCompra}: $($pagamento[3])."
        }
    }

    if ($transacoes.ContainsKey($idCompra)) {
        $transacao = $transacoes[$idCompra]
        Garantir ($transacao[1] -eq $idEmpresa) "Empresa divergente na razao da compra $idCompra."
        Garantir ((Converter-Decimal $transacao[2]) -eq $valor) "Valor divergente na razao da compra $idCompra."
        Garantir ($transacao[5] -eq $pagamento[4]) "Pagamento divergente na razao da compra $idCompra."

        if ($pagamento[3] -eq 'AUTORIZADO') {
            Garantir ($transacao[3] -eq 'REGISTRADA') "Razao da compra $idCompra deveria estar registrada."
            Garantir ($compra[6] -eq $transacao[4]) "Transacao contabil divergente na compra $idCompra."
        } else {
            Garantir ($transacao[3] -eq 'REJEITADA') "Razao da compra compensada $idCompra deveria estar rejeitada."
        }
    }
}

Write-Host '3/5 Procurando registros orfaos nos estagios posteriores...' -ForegroundColor Cyan
foreach ($indice in @($analises, $pagamentos, $transacoes)) {
    foreach ($idCompra in $indice.Keys) {
        Garantir ($compras.ContainsKey($idCompra)) "Registro orfao encontrado para a compra $idCompra."
    }
}

Write-Host '4/5 Validando estoque e partidas dobradas...' -ForegroundColor Cyan
$estoquesDivergentes = @(Consultar-Banco banco-estoque orquestrapay_estoque @'
WITH reservas_ativas AS (
    SELECT r.id_empresa, i.id_produto, SUM(i.quantidade) AS quantidade
    FROM reserva_estoque r
    JOIN item_reserva i USING (id_reserva)
    WHERE r.status = 'RESERVADA'
    GROUP BY r.id_empresa, i.id_produto
)
SELECT COUNT(*)
FROM saldo_estoque s
FULL JOIN reservas_ativas a USING (id_empresa, id_produto)
WHERE s.id_empresa IS NULL
   OR s.quantidade_disponivel < 0
   OR s.quantidade_reservada < 0
   OR s.quantidade_reservada <> COALESCE(a.quantidade, 0);
'@)
Garantir ([int]$estoquesDivergentes[0] -eq 0) 'O saldo reservado nao corresponde as reservas ativas.'

$partidasDivergentes = @(Consultar-Banco banco-razao orquestrapay_razao @'
WITH totais AS (
    SELECT id_transacao,
           COUNT(*) AS quantidade_lancamentos,
           COALESCE(SUM(valor) FILTER (WHERE natureza = 'DEBITO'), 0) AS debitos,
           COALESCE(SUM(valor) FILTER (WHERE natureza = 'CREDITO'), 0) AS creditos
    FROM lancamento_contabil
    GROUP BY id_transacao
)
SELECT COUNT(*)
FROM transacao_contabil t
LEFT JOIN totais x USING (id_transacao)
WHERE (t.status = 'REGISTRADA' AND (
           COALESCE(x.quantidade_lancamentos, 0) <> 2
        OR COALESCE(x.debitos, 0) <> t.valor
        OR COALESCE(x.creditos, 0) <> t.valor))
   OR (t.status = 'REJEITADA' AND COALESCE(x.quantidade_lancamentos, 0) <> 0);
'@)
Garantir ([int]$partidasDivergentes[0] -eq 0) 'Existem transacoes contabeis desbalanceadas ou incoerentes.'

Write-Host '5/5 Conferindo outboxes e quarentenas...' -ForegroundColor Cyan
$bancos = @(
    @('banco-checkout', 'orquestrapay_checkout'),
    @('banco-estoque', 'orquestrapay_estoque'),
    @('banco-risco', 'orquestrapay_risco'),
    @('banco-pagamento', 'orquestrapay_pagamento'),
    @('banco-razao', 'orquestrapay_razao'),
    @('banco-notificacao', 'orquestrapay_notificacao')
)
foreach ($banco in $bancos) {
    $fila = @(Consultar-Banco $banco[0] $banco[1] @'
SELECT COUNT(*) FILTER (WHERE publicado_em IS NULL AND descartado_em IS NULL) || '|' ||
       COUNT(*) FILTER (WHERE descartado_em IS NOT NULL)
FROM evento_saida;
'@)
    $contagens = @($fila[0] -split '\|')
    Garantir ([int]$contagens[0] -eq 0) "Ha eventos pendentes em $($banco[1])."
    Garantir ([int]$contagens[1] -eq 0) "Ha eventos em quarentena em $($banco[1])."
}

Write-Host ''
Write-Host 'Consistencia distribuida aprovada.' -ForegroundColor Green
[pscustomobject]@{
    compras = $compras.Count
    comprasFinalizadas = $comprasFinais.Count
    comprasEmAndamento = $compras.Count - $comprasFinais.Count
    reservas = $reservas.Count
    analisesRisco = $analises.Count
    pagamentos = $pagamentos.Count
    transacoesContabeis = $transacoes.Count
    notificacoes = $notificacoes.Count
    estoque = 'saldos conferidos'
    partidasDobradas = 'balanceadas'
    outboxes = 'sem pendencias ou quarentena'
} | Format-List
