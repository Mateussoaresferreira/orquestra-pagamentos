param(
    [Parameter(Mandatory = $true)]
    [string] $ArquivoEstado,
    [long] $QuantidadeEsperada = -1,
    [switch] $SemInvariantesPesadas
)

$ErrorActionPreference = 'Stop'

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

function Diferenca {
    param([long] $Atual, [long] $Inicial)
    return $Atual - $Inicial
}

$caminhoEstado = [IO.Path]::GetFullPath($ArquivoEstado)
Garantir (Test-Path -LiteralPath $caminhoEstado) "Arquivo de estado nao encontrado: $caminhoEstado"
$estado = Get-Content -LiteralPath $caminhoEstado -Raw | ConvertFrom-Json
$esperado = if ($QuantidadeEsperada -ge 0) { $QuantidadeEsperada } else { [long]$estado.proximoOffset }
$base = $estado.contagensIniciais

Garantir ($esperado -ge 0 -and $esperado -le [long]$estado.totalCompras) `
    'A quantidade esperada esta fora dos limites da execucao.'

Write-Host "Auditando $esperado transacoes da execucao $($estado.idExecucao)..." -ForegroundColor Cyan

$checkout = Separar-Numeros (Consultar-Linha banco-checkout orquestrapay_checkout @'
SELECT (SELECT COUNT(*) FROM compra),
       (SELECT COUNT(*) FROM compra WHERE status = 'CONCLUIDA'),
       (SELECT COUNT(*) FROM compra WHERE status NOT IN ('CONCLUIDA', 'RECUSADA', 'COMPENSADA')),
       (SELECT COUNT(*) FROM item_compra),
       (SELECT COUNT(*) FROM requisicao_idempotente);
'@)

$estoque = Separar-Numeros (Consultar-Linha banco-estoque orquestrapay_estoque @'
SELECT (SELECT COUNT(*) FROM reserva_estoque),
       (SELECT COUNT(*) FROM reserva_estoque WHERE status = 'RESERVADA'),
       (SELECT COUNT(*) FROM item_reserva);
'@)

$risco = Separar-Numeros (Consultar-Linha banco-risco orquestrapay_risco @'
SELECT COUNT(*), COUNT(*) FILTER (WHERE aprovada)
FROM analise_risco;
'@)

$pagamento = Separar-Numeros (Consultar-Linha banco-pagamento orquestrapay_pagamento @'
SELECT (SELECT COUNT(*) FROM pagamento),
       (SELECT COUNT(*) FROM pagamento WHERE status = 'AUTORIZADO'),
       (SELECT COUNT(*) FROM operacao_pagamento),
       (SELECT COUNT(*) FROM operacao_pagamento WHERE status = 'CONCLUIDA'),
       (SELECT COUNT(*) FROM operacao_pagamento WHERE status IN ('PENDENTE', 'PROCESSANDO')),
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
       COUNT(*) FILTER (WHERE status IN ('PENDENTE', 'PROCESSANDO')),
       COUNT(*) FILTER (WHERE status = 'FALHA_DEFINITIVA')
FROM notificacao;
'@)

Garantir ((Diferenca $checkout[0] $base.compras) -eq $esperado) `
    'A quantidade de compras persistidas diverge do volume esperado.'
Garantir ((Diferenca $checkout[1] $base.comprasConcluidas) -eq $esperado) `
    'Nem todas as compras do volume foram concluidas.'
Garantir ($checkout[2] -eq 0) 'Ainda existem compras em andamento.'
Garantir ((Diferenca $checkout[3] $base.itensCompra) -eq $esperado) `
    'A quantidade de itens de compra diverge do volume esperado.'
Garantir ((Diferenca $checkout[4] $base.requisicoesIdempotentes) -eq $esperado) `
    'A quantidade de chaves idempotentes diverge do volume esperado.'

Garantir ((Diferenca $estoque[0] $base.reservas) -eq $esperado) `
    'A quantidade de reservas diverge do volume esperado.'
Garantir ((Diferenca $estoque[1] $base.reservasAtivas) -eq $esperado) `
    'Nem todas as reservas do volume permaneceram ativas.'
Garantir ((Diferenca $estoque[2] $base.itensReserva) -eq $esperado) `
    'A quantidade de itens reservados diverge do volume esperado.'

Garantir ((Diferenca $risco[0] $base.analisesRisco) -eq $esperado) `
    'A quantidade de analises de risco diverge do volume esperado.'
Garantir ((Diferenca $risco[1] $base.analisesAprovadas) -eq $esperado) `
    'Alguma transacao do volume foi reprovada pelo risco.'

Garantir ((Diferenca $pagamento[0] $base.pagamentos) -eq $esperado) `
    'A quantidade de pagamentos diverge do volume esperado.'
Garantir ((Diferenca $pagamento[1] $base.pagamentosAutorizados) -eq $esperado) `
    'Nem todos os pagamentos do volume foram autorizados.'
Garantir ((Diferenca $pagamento[2] $base.operacoesPagamento) -eq $esperado) `
    'A quantidade de operacoes de pagamento diverge do volume esperado.'
Garantir ((Diferenca $pagamento[3] $base.operacoesConcluidas) -eq $esperado) `
    'Nem todas as operacoes de pagamento foram concluidas.'
Garantir ($pagamento[4] -eq 0) 'Ainda existem operacoes de pagamento em andamento.'
Garantir ((Diferenca $pagamento[5] $base.operacoesFalhas) -eq 0) `
    'O volume gerou operacao de pagamento em falha definitiva.'

Garantir ((Diferenca $razao[0] $base.transacoesContabeis) -eq $esperado) `
    'A quantidade de transacoes contabeis diverge do volume esperado.'
Garantir ((Diferenca $razao[1] $base.transacoesRegistradas) -eq $esperado) `
    'Nem todas as transacoes contabeis foram registradas.'
Garantir ((Diferenca $razao[2] $base.lancamentosContabeis) -eq (2 * $esperado)) `
    'Cada transacao deve produzir exatamente um debito e um credito.'

Garantir ((Diferenca $notificacao[0] $base.notificacoes) -eq $esperado) `
    'A quantidade de notificacoes diverge do volume esperado.'
Garantir ((Diferenca $notificacao[1] $base.notificacoesEnviadas) -eq $esperado) `
    'Nem todas as notificacoes do volume foram enviadas.'
Garantir ($notificacao[2] -eq 0) 'Ainda existem notificacoes em andamento.'
Garantir ((Diferenca $notificacao[3] $base.notificacoesFalhas) -eq 0) `
    'O volume gerou notificacao em falha definitiva.'

$idProduto = ([guid]$estado.idProduto).ToString()
$saldoProduto = Separar-Numeros (Consultar-Linha banco-estoque orquestrapay_estoque @"
SELECT COUNT(*),
       COALESCE(SUM(quantidade_disponivel), 0),
       COALESCE(SUM(quantidade_reservada), 0),
       COUNT(*) FILTER (WHERE quantidade_disponivel < 0 OR quantidade_reservada < 0)
FROM saldo_estoque
WHERE id_produto = '$idProduto'::uuid;
"@)
$reservasProduto = Separar-Numeros (Consultar-Linha banco-estoque orquestrapay_estoque @"
SELECT COUNT(*),
       COUNT(*) FILTER (WHERE r.status = 'RESERVADA'),
       COALESCE(SUM(i.quantidade), 0)
FROM reserva_estoque r
JOIN item_reserva i USING (id_reserva)
WHERE i.id_produto = '$idProduto'::uuid;
"@)
$saldoInicialTotal = [long]$estado.quantidadeEmpresas * [long]$estado.estoquePorEmpresa
Garantir ($saldoProduto[0] -eq [long]$estado.quantidadeEmpresas) `
    'O produto de volume nao possui saldo em todas as empresas.'
Garantir ($saldoProduto[1] -eq ($saldoInicialTotal - $esperado)) `
    'O saldo disponivel do produto nao corresponde as compras realizadas.'
Garantir ($saldoProduto[2] -eq $esperado) `
    'O saldo reservado do produto nao corresponde as compras realizadas.'
Garantir ($saldoProduto[3] -eq 0) 'Foi encontrado saldo de estoque negativo.'
Garantir ($reservasProduto[0] -eq $esperado -and
          $reservasProduto[1] -eq $esperado -and
          $reservasProduto[2] -eq $esperado) `
    'As reservas do produto de volume estao inconsistentes.'

if (-not $SemInvariantesPesadas) {
    Write-Host 'Conferindo partidas dobradas e estoque global...' -ForegroundColor Cyan
    $estoquesDivergentes = [long](Consultar-Linha banco-estoque orquestrapay_estoque @'
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
    Garantir ($estoquesDivergentes -eq 0) `
        'O saldo reservado global nao corresponde as reservas ativas.'

    $partidasDivergentes = [long](Consultar-Linha banco-razao orquestrapay_razao @'
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
    Garantir ($partidasDivergentes -eq 0) `
        'Existem transacoes contabeis desbalanceadas ou incoerentes.'
}

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
    Garantir ($fila[0] -eq 0) "Ha eventos pendentes em $($banco[1])."
    Garantir ($fila[1] -eq 0) "Ha eventos em quarentena em $($banco[1])."
}

Write-Host "Auditoria agregada aprovada: $esperado transacoes exatas e consistentes." -ForegroundColor Green
[pscustomobject]@{
    idExecucao = $estado.idExecucao
    compras = $esperado
    estadosFinais = 'CONCLUIDA / AUTORIZADO / REGISTRADA / ENVIADA'
    estoqueReservado = $saldoProduto[2]
    lancamentosContabeis = 2 * $esperado
    filas = 'sem pendencias ou quarentena'
} | Format-List
