param(
    [string] $UrlCheckout = 'http://localhost:8080',
    [string] $UrlEstoque = 'http://localhost:8081',
    [string] $UrlPagamento = 'http://localhost:8083',
    [int] $TempoLimiteSegundos = 120
)

$ErrorActionPreference = 'Stop'

function Garantir {
    param([bool] $Condicao, [string] $Mensagem)

    if (-not $Condicao) {
        throw $Mensagem
    }
}

function Enviar-Json {
    param(
        [string] $Metodo,
        [string] $Url,
        [object] $Corpo,
        [hashtable] $Cabecalhos = @{}
    )

    return Invoke-RestMethod -Method $Metodo -Uri $Url -Headers $Cabecalhos `
        -ContentType 'application/json' -Body ($Corpo | ConvertTo-Json -Depth 10)
}

function Ler-SegredoLocal {
    param([string] $Nome)

    $arquivoAmbiente = Join-Path $PSScriptRoot '..\.env'
    $linha = Get-Content $arquivoAmbiente | Where-Object { $_ -match "^$([regex]::Escape($Nome))=" } | Select-Object -First 1
    if (-not $linha) {
        throw "A variavel $Nome nao foi encontrada em .env. Execute scripts/iniciar.ps1 primeiro."
    }
    return ($linha -split '=', 2)[1].Trim().Trim('"').Trim("'")
}

function Calcular-Crc16Ccitt {
    param([string] $Conteudo)

    $crc = 0xFFFF
    foreach ($valor in [Text.Encoding]::UTF8.GetBytes($Conteudo)) {
        $crc = $crc -bxor ([int] $valor -shl 8)
        for ($bit = 0; $bit -lt 8; $bit++) {
            if (($crc -band 0x8000) -ne 0) {
                $crc = (($crc -shl 1) -bxor 0x1021) -band 0xFFFF
            } else {
                $crc = ($crc -shl 1) -band 0xFFFF
            }
        }
    }
    return '{0:X4}' -f $crc
}

function Aguardar-Pagamento {
    param([guid] $IdCompra, [string[]] $Estados, [hashtable] $Cabecalhos)

    $limite = (Get-Date).AddSeconds($TempoLimiteSegundos)
    do {
        Start-Sleep -Milliseconds 500
        try {
            $pagamento = Invoke-RestMethod -Uri "$UrlPagamento/api/v1/pagamentos/compras/$IdCompra" `
                -Headers $Cabecalhos
            if ($pagamento.status -in $Estados) {
                return $pagamento
            }
        } catch {
            if ([int] $_.Exception.Response.StatusCode -ne 404) {
                throw
            }
        }
    } while ((Get-Date) -lt $limite)

    throw "O pagamento da compra $IdCompra nao chegou a $($Estados -join ', ')."
}

function Aguardar-Compra {
    param([guid] $IdCompra, [string[]] $Estados, [hashtable] $Cabecalhos)

    $limite = (Get-Date).AddSeconds($TempoLimiteSegundos)
    do {
        Start-Sleep -Milliseconds 500
        $compra = Invoke-RestMethod -Uri "$UrlCheckout/api/v1/compras/$IdCompra" -Headers $Cabecalhos
        if ($compra.status -in $Estados) {
            return $compra
        }
    } while ((Get-Date) -lt $limite)

    throw "A compra $IdCompra nao chegou a $($Estados -join ', ')."
}

$idEmpresa = [guid]::NewGuid()
$idProduto = [guid]::NewGuid()
$cabecalhoEmpresa = @{ 'X-Empresa-Id' = $idEmpresa }
$cabecalhosCompra = @{
    'X-Empresa-Id' = $idEmpresa
    'Idempotency-Key' = "pix-tardio-$([guid]::NewGuid())"
}

Write-Host '1/7 Preparando estoque e criando uma compra PIX...'
Enviar-Json PUT "$UrlEstoque/api/v1/estoques/$idProduto" `
    @{ quantidadeDisponivel = 10; motivo = 'Teste de confirmacao PIX tardia' } `
    $cabecalhoEmpresa | Out-Null
$compra = Enviar-Json POST "$UrlCheckout/api/v1/compras" @{
    idCliente = 'cliente-pix-tardio'
    emailCliente = 'pix.tardio@orquestrapay.local'
    moeda = 'BRL'
    pais = 'BR'
    identificadorDispositivo = "dispositivo-$([guid]::NewGuid())"
    metodoPagamento = 'PIX'
    parcelas = 1
    itens = @(
        @{ idProduto = $idProduto; quantidade = 1; precoUnitario = 49.90 }
    )
} $cabecalhosCompra

Write-Host '2/7 Aguardando a cobranca PIX ficar disponivel...'
$pagamentoPendente = Aguardar-Pagamento $compra.idCompra @('AGUARDANDO_CONFIRMACAO') $cabecalhoEmpresa
Garantir (-not [string]::IsNullOrWhiteSpace($pagamentoPendente.txid)) 'A cobranca PIX nao possui txid.'
Garantir ($pagamentoPendente.copiaColaPix.StartsWith('000201')) 'O copia e cola nao e um payload BR Code.'
Garantir ($pagamentoPendente.copiaColaPix -match '6304[0-9A-F]{4}$') 'O BR Code nao termina com um CRC valido.'
$crcRecebido = $pagamentoPendente.copiaColaPix.Substring($pagamentoPendente.copiaColaPix.Length - 4)
$conteudoParaCrc = $pagamentoPendente.copiaColaPix.Substring(0, $pagamentoPendente.copiaColaPix.Length - 4)
Garantir ($crcRecebido -eq (Calcular-Crc16Ccitt $conteudoParaCrc)) 'O CRC do BR Code divergiu no transporte.'
$imagemQr = [Convert]::FromBase64String($pagamentoPendente.imagemQrCodeBase64)
Garantir ($imagemQr.Length -gt 8) 'A imagem do QR Code esta vazia.'
Garantir ($imagemQr[0] -eq 0x89 -and $imagemQr[1] -eq 0x50 `
        -and $imagemQr[2] -eq 0x4E -and $imagemQr[3] -eq 0x47) 'A imagem do QR Code nao e um PNG.'

Write-Host '3/7 Antecipando o relogio da cobranca no banco para exercitar a expiracao...'
$comandoExpiracao = "UPDATE pagamento SET expira_em = CURRENT_TIMESTAMP - INTERVAL '1 minute' " +
    "WHERE id_pagamento = '$($pagamentoPendente.idPagamento)' AND status = 'AGUARDANDO_CONFIRMACAO';"
$linhasExpiradas = docker compose exec -T banco-pagamento psql `
    -U orquestrapay -d orquestrapay_pagamento -tAc $comandoExpiracao
if ($LASTEXITCODE -ne 0) {
    throw 'Nao foi possivel preparar a expiracao controlada no PostgreSQL.'
}

Write-Host '4/7 Aguardando a saga recusar a compra e liberar o estoque...'
$pagamentoExpirado = Aguardar-Pagamento $compra.idCompra @('EXPIRADO') $cabecalhoEmpresa
$compraRecusada = Aguardar-Compra $compra.idCompra @('RECUSADA') $cabecalhoEmpresa

Write-Host '5/7 Confirmando no provedor depois da expiracao local...'
$urlProvedor = if ($pagamentoExpirado.provedor -eq 'contingencia') {
    'http://localhost:8091'
} else {
    'http://localhost:8090'
}
Invoke-RestMethod -Method POST `
    -Uri "$urlProvedor/api/v1/cobrancas/pix/$($pagamentoExpirado.txid)/confirmacoes" `
    -Headers @{ 'X-Provedor-Api-Key' = Ler-SegredoLocal 'PROVEDOR_CHAVE_API' } | Out-Null

Write-Host '6/7 Aguardando a devolucao automatica pelo mesmo provedor...'
$pagamentoDevolvido = Aguardar-Pagamento $compra.idCompra @('ESTORNADO') $cabecalhoEmpresa
$compraFinal = Invoke-RestMethod -Uri "$UrlCheckout/api/v1/compras/$($compra.idCompra)" `
    -Headers $cabecalhoEmpresa
$estoqueFinal = Invoke-RestMethod -Uri "$UrlEstoque/api/v1/estoques/$idProduto" `
    -Headers $cabecalhoEmpresa

Write-Host '7/7 Conferindo auditoria, idempotencia e consistencia financeira...'
$consultaAuditoria = "SELECT " +
    "(SELECT COUNT(*) FROM divergencia_conciliacao WHERE id_pagamento = '$($pagamentoPendente.idPagamento)' AND tipo = 'PIX_CONFIRMADO_APOS_EXPIRACAO')," +
    "(SELECT COUNT(*) FROM operacao_pagamento WHERE id_pagamento = '$($pagamentoPendente.idPagamento)' AND tipo = 'ESTORNAR' AND status = 'CONCLUIDA')," +
    "(SELECT COUNT(*) FROM tentativa_pagamento WHERE id_pagamento = '$($pagamentoPendente.idPagamento)' AND resultado = 'CONFIRMADO_APOS_EXPIRACAO')," +
    "(SELECT COUNT(*) FROM webhook_provedor_recebido WHERE id_pagamento = '$($pagamentoPendente.idPagamento)' AND status_processamento = 'PROCESSADO');"
$auditoria = docker compose exec -T banco-pagamento psql `
    -U orquestrapay -d orquestrapay_pagamento -tA -F ',' -c $consultaAuditoria
if ($LASTEXITCODE -ne 0) {
    throw 'Nao foi possivel consultar as evidencias do teste no PostgreSQL.'
}
$contagens = ($auditoria | Select-Object -Last 1).Trim() -split ','

Garantir ($compraRecusada.status -eq 'RECUSADA') 'A expiracao nao recusou a compra.'
Garantir ($compraFinal.status -eq 'RECUSADA') 'A confirmacao tardia reabriu indevidamente a compra.'
Garantir ($pagamentoDevolvido.status -eq 'ESTORNADO') 'O PIX tardio nao foi devolvido.'
Garantir ($pagamentoDevolvido.provedor -eq $pagamentoPendente.provedor) 'A devolucao mudou indevidamente de provedor.'
Garantir ($estoqueFinal.quantidadeDisponivel -eq 10) 'O estoque nao foi devolvido integralmente.'
Garantir ($estoqueFinal.quantidadeReservada -eq 0) 'Ainda existe estoque reservado para a compra recusada.'
Garantir ([int] $contagens[0] -eq 1) 'A divergencia de PIX tardio nao foi registrada exatamente uma vez.'
Garantir ([int] $contagens[1] -eq 1) 'A devolucao automatica nao foi concluida exatamente uma vez.'
Garantir ([int] $contagens[2] -eq 1) 'A confirmacao tardia nao foi auditada exatamente uma vez.'
Garantir ([int] $contagens[3] -eq 1) 'O webhook tardio nao foi processado exatamente uma vez.'

Write-Host 'PIX tardio detectado, auditado e devolvido sem reabrir a compra.' -ForegroundColor Green
[pscustomobject]@{
    idEmpresa = $idEmpresa
    idCompra = $compra.idCompra
    txid = $pagamentoPendente.txid
    estadoCompra = $compraFinal.status
    estadoPagamento = $pagamentoDevolvido.status
    provedor = $pagamentoDevolvido.provedor
    divergenciasAtivas = [int] $contagens[0]
    devolucoesConcluidas = [int] $contagens[1]
    estoqueDisponivel = $estoqueFinal.quantidadeDisponivel
} | Format-List
