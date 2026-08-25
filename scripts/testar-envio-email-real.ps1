param(
    [int] $TempoLimiteSegundos = 120
)

$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot
Set-Location $raiz

function Garantir([bool] $Condicao, [string] $Mensagem) {
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

function Executar-Sql([string] $Sql) {
    $resultado = docker compose exec -T banco-notificacao `
        psql -U orquestrapay -d orquestrapay_notificacao -v ON_ERROR_STOP=1 -tAc $Sql
    if ($LASTEXITCODE -ne 0) {
        throw 'Falha ao consultar o banco de notificacoes.'
    }
    return ($resultado | Out-String).Trim()
}

function Obter-Notificacao([guid] $IdCompra) {
    $linha = Executar-Sql @"
SELECT status || '|' || tentativas || '|' || COALESCE(ultimo_erro, '') || '|' ||
       id_notificacao || '|' || COALESCE(enviada_em::text, '')
  FROM notificacao
 WHERE id_compra = '$IdCompra'
 ORDER BY criada_em DESC
 LIMIT 1;
"@
    if ([string]::IsNullOrWhiteSpace($linha)) {
        return $null
    }

    $campos = $linha -split '\|', 5
    return [pscustomobject]@{
        status = $campos[0]
        tentativas = [int] $campos[1]
        ultimoErro = $campos[2]
        idNotificacao = [guid] $campos[3]
        enviadaEm = $campos[4]
    }
}

function Aguardar-Notificacao {
    param(
        [guid] $IdCompra,
        [scriptblock] $Condicao,
        [string] $Descricao
    )

    $limite = (Get-Date).AddSeconds($TempoLimiteSegundos)
    do {
        Start-Sleep -Milliseconds 500
        $notificacao = Obter-Notificacao $IdCompra
        if ($null -ne $notificacao -and (& $Condicao $notificacao)) {
            return $notificacao
        }
    } while ((Get-Date) -lt $limite)

    throw "Tempo esgotado aguardando $Descricao para a compra $IdCompra."
}

function Aguardar-Mailpit {
    $limite = (Get-Date).AddSeconds($TempoLimiteSegundos)
    do {
        try {
            $saude = Invoke-RestMethod 'http://localhost:8025/api/v1/info'
            if ($saude.Version) {
                return
            }
        } catch {
            Start-Sleep -Milliseconds 500
        }
    } while ((Get-Date) -lt $limite)

    throw 'O Mailpit nao ficou disponivel dentro do tempo limite.'
}

function Aguardar-Mensagem {
    param([string] $Destinatario)

    $limite = (Get-Date).AddSeconds($TempoLimiteSegundos)
    do {
        $lista = Invoke-RestMethod 'http://localhost:8025/api/v1/messages?limit=200'
        foreach ($mensagem in @($lista.messages)) {
            $destinos = @($mensagem.PSObject.Properties['To'].Value)
            if (@($destinos | ForEach-Object { $_.Address }) -contains $Destinatario) {
                return $mensagem
            }
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $limite)

    throw "O Mailpit nao recebeu uma mensagem para $Destinatario."
}

function Validar-Mensagem {
    param(
        [object] $Mensagem,
        [object] $Notificacao,
        [guid] $IdCompra,
        [string] $Destinatario,
        [string] $Assunto
    )

    Garantir ($Mensagem.Subject -eq $Assunto) 'O assunto entregue por SMTP esta incorreto.'
    $destinos = @($Mensagem.PSObject.Properties['To'].Value)
    Garantir (@($destinos | ForEach-Object { $_.Address }) -contains $Destinatario) `
        'O destinatario SMTP esta incorreto.'
    Garantir ($Mensagem.MessageID -eq "$($Notificacao.idNotificacao)@orquestrapay.local") `
        'O Message-ID estavel nao corresponde a notificacao persistida.'

    $cabecalhos = Invoke-RestMethod `
        "http://localhost:8025/api/v1/message/$($Mensagem.ID)/headers"
    Garantir ($cabecalhos.'X-Orquestra-Notificacao-Id'[0] -eq $Notificacao.idNotificacao.ToString()) `
        'O cabecalho de rastreabilidade da notificacao esta incorreto.'
    Garantir ($cabecalhos.'X-Orquestra-Compra-Id'[0] -eq $IdCompra.ToString()) `
        'O cabecalho de rastreabilidade da compra esta incorreto.'

    $instanteMensagem = ([DateTimeOffset] $Mensagem.Created).ToUniversalTime()
    Garantir ([Math]::Abs(($instanteMensagem - [DateTimeOffset]::UtcNow).TotalMinutes) -lt 2) `
        'O horario da mensagem nao corresponde ao processamento atual.'
}

$idEmpresa = [guid]::NewGuid()
$idProduto = [guid]::NewGuid()
$emailSucesso = "smtp-sucesso-$([guid]::NewGuid())@orquestrapay.local"
$idCompraFalha = [guid]::NewGuid()
$idNotificacaoFalha = [guid]::NewGuid()
$idEventoFalha = [guid]::NewGuid()
$idEmpresaFalha = [guid]::NewGuid()
$emailRecuperacao = "smtp-recuperacao-$([guid]::NewGuid())@orquestrapay.local"

try {
    Write-Host '1/7 Criando uma compra aprovada para exercitar Kafka e SMTP de ponta a ponta...'
    $cabecalhoEmpresa = @{ 'X-Empresa-Id' = $idEmpresa.ToString() }
    Enviar-Json PUT "http://localhost:8081/api/v1/estoques/$idProduto" @{
        quantidadeDisponivel = 10
        motivo = 'Validacao do envio real de email'
    } $cabecalhoEmpresa | Out-Null

    $compra = Enviar-Json POST 'http://localhost:8080/api/v1/compras' @{
        idCliente = 'cliente-validacao-smtp'
        emailCliente = $emailSucesso
        moeda = 'BRL'
        pais = 'BR'
        identificadorDispositivo = "smtp-$([guid]::NewGuid())"
        tokenPagamento = 'tok_aprovado'
        metodoPagamento = 'CARTAO'
        parcelas = 1
        itens = @(@{
            idProduto = $idProduto
            quantidade = 1
            precoUnitario = 39.90
        })
    } @{
        'X-Empresa-Id' = $idEmpresa.ToString()
        'Idempotency-Key' = "smtp-$([guid]::NewGuid())"
    }

    $limiteCompra = (Get-Date).AddSeconds($TempoLimiteSegundos)
    do {
        Start-Sleep -Milliseconds 500
        $estado = Invoke-RestMethod "http://localhost:8080/api/v1/compras/$($compra.idCompra)" `
            -Headers $cabecalhoEmpresa
        if ($estado.status -in @('RECUSADA', 'COMPENSADA')) {
            throw "A compra do teste SMTP terminou em $($estado.status): $($estado.motivo)"
        }
    } while ($estado.status -ne 'CONCLUIDA' -and (Get-Date) -lt $limiteCompra)
    Garantir ($estado.status -eq 'CONCLUIDA') 'A compra nao concluiu dentro do tempo limite.'

    Write-Host '2/7 Validando persistencia, conteudo e rastreabilidade do email entregue...'
    $notificacaoSucesso = Aguardar-Notificacao $compra.idCompra `
        { param($item) $item.status -eq 'ENVIADA' } 'o envio inicial'
    Garantir ($notificacaoSucesso.tentativas -eq 1) 'O envio inicial exigiu mais de uma tentativa.'
    Garantir ([string]::IsNullOrEmpty($notificacaoSucesso.ultimoErro)) `
        'Uma notificacao enviada preservou erro anterior indevidamente.'
    $mensagemSucesso = Aguardar-Mensagem $emailSucesso
    Validar-Mensagem $mensagemSucesso $notificacaoSucesso $compra.idCompra `
        $emailSucesso 'Sua compra foi concluida'

    Write-Host '3/7 Interrompendo o SMTP e criando uma notificacao pendente controlada...'
    docker compose stop mailpit | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Nao foi possivel interromper o Mailpit.'
    }
    Executar-Sql @"
INSERT INTO notificacao (
    id_notificacao, id_evento, id_empresa, id_compra,
    canal, destinatario, assunto, mensagem, status,
    tentativas, criada_em, proxima_tentativa_em
) VALUES (
    '$idNotificacaoFalha', '$idEventoFalha', '$idEmpresaFalha', '$idCompraFalha',
    'EMAIL', '$emailRecuperacao', 'Teste de recuperacao SMTP',
    'Mensagem preservada durante indisponibilidade', 'PENDENTE',
    0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);
"@ | Out-Null

    Write-Host '4/7 Confirmando retry persistente sem falso positivo de entrega...'
    $notificacaoFalha = Aguardar-Notificacao $idCompraFalha `
        { param($item) $item.status -eq 'PENDENTE' -and $item.tentativas -ge 1 } `
        'a primeira falha SMTP'
    Garantir ($notificacaoFalha.status -ne 'ENVIADA') `
        'A notificacao foi marcada como enviada mesmo com o SMTP indisponivel.'
    Garantir ($notificacaoFalha.ultimoErro -match '^Falha de transporte SMTP \([A-Za-z0-9.$]+\)$') `
        'O erro persistido nao esta sanitizado.'

    Write-Host '5/7 Restaurando o SMTP e aguardando a recuperacao automatica...'
    docker compose up -d mailpit | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Nao foi possivel restaurar o Mailpit.'
    }
    Aguardar-Mailpit
    $notificacaoRecuperada = Aguardar-Notificacao $idCompraFalha `
        { param($item) $item.status -eq 'ENVIADA' } 'a recuperacao automatica'
    Garantir ($notificacaoRecuperada.tentativas -ge 2) `
        'A recuperacao nao registrou a tentativa que falhou.'
    Garantir ([string]::IsNullOrEmpty($notificacaoRecuperada.ultimoErro)) `
        'O erro anterior nao foi limpo depois da entrega.'

    Write-Host '6/7 Conferindo o email recuperado e as metricas operacionais...'
    $mensagemRecuperada = Aguardar-Mensagem $emailRecuperacao
    Validar-Mensagem $mensagemRecuperada $notificacaoRecuperada $idCompraFalha `
        $emailRecuperacao 'Teste de recuperacao SMTP'

    $metricas = (Invoke-WebRequest 'http://localhost:8085/actuator/prometheus' `
        -UseBasicParsing).Content -split "`n"
    Garantir (($metricas | Where-Object {
        $_ -match '^orquestrapay_notificacoes_smtp_total\{' -and $_ -match 'resultado="enviada"'
    }).Count -ge 1) 'A metrica de emails enviados nao foi publicada.'
    Garantir (($metricas | Where-Object {
        $_ -match '^orquestrapay_notificacoes_smtp_total\{' -and $_ -match 'resultado="falha"'
    }).Count -ge 1) 'A metrica de falhas SMTP nao foi publicada.'

    Write-Host '7/7 Envio real, falha, retry e recuperacao validados.' -ForegroundColor Green
    [pscustomobject]@{
        compra = $compra.idCompra
        notificacaoInicial = $notificacaoSucesso.idNotificacao
        tentativasIniciais = $notificacaoSucesso.tentativas
        notificacaoRecuperada = $notificacaoRecuperada.idNotificacao
        tentativasAteRecuperar = $notificacaoRecuperada.tentativas
        smtp = 'entrega real confirmada'
        horario = 'UTC atual confirmado'
        metricas = 'enviada e falha presentes'
    } | Format-List
}
finally {
    docker compose up -d mailpit | Out-Null
    try {
        Executar-Sql "DELETE FROM notificacao WHERE id_notificacao = '$idNotificacaoFalha';" | Out-Null
    }
    catch {
        Write-Warning "Nao foi possivel remover a notificacao sintetica $idNotificacaoFalha."
    }
}
