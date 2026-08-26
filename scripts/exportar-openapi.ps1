param(
    [string] $DiretorioSaida = "docs/openapi"
)

$ErrorActionPreference = "Stop"

$resumosOperacoes = @{
    listar = "Lista eventos em quarentena com paginação e filtro de status"
    reprocessar = "Reprocessa um evento em quarentena de forma auditável"
    descartarDefinitivamente = "Descarta definitivamente um evento em quarentena"
    listarAuditoria = "Lista o histórico de tratamento de um evento em quarentena"
}

$servicos = @(
    @{
        Nome = "checkout"
        Arquivo = "contrato-checkout.json"
        Porta = 8080
        Titulo = "API de Checkout | Orquestra de Pagamentos"
        Descricao = "Recepção de compras, idempotência, histórico e coordenação da saga."
    },
    @{
        Nome = "estoque"
        Arquivo = "contrato-estoque.json"
        Porta = 8081
        Titulo = "API de Estoque | Orquestra de Pagamentos"
        Descricao = "Saldo, reserva, liberação e administração das filas do domínio de estoque."
    },
    @{
        Nome = "risco"
        Arquivo = "contrato-risco.json"
        Porta = 8082
        Titulo = "API de Risco | Orquestra de Pagamentos"
        Descricao = "Consulta da análise de risco e operação das filas do domínio."
    },
    @{
        Nome = "pagamento"
        Arquivo = "contrato-pagamento.json"
        Porta = 8083
        Titulo = "API de Pagamento | Orquestra de Pagamentos"
        Descricao = "Cartão, PIX, callbacks, conciliação, divergências e filas operacionais."
    },
    @{
        Nome = "razao"
        Arquivo = "contrato-razao.json"
        Porta = 8084
        Titulo = "API da Razão Contábil | Orquestra de Pagamentos"
        Descricao = "Partidas dobradas, recebíveis e administração das filas contábeis."
    },
    @{
        Nome = "notificacao"
        Arquivo = "contrato-notificacao.json"
        Porta = 8085
        Titulo = "API de Notificação | Orquestra de Pagamentos"
        Descricao = "Entrega de email, webhooks empresariais e operação das filas duráveis."
    }
)

$destino = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\$DiretorioSaida"))
New-Item -ItemType Directory -Path $destino -Force | Out-Null

foreach ($servico in $servicos) {
    $url = "http://localhost:$($servico.Porta)/v3/api-docs"
    try {
        $contrato = Invoke-RestMethod -Uri $url -TimeoutSec 15
    } catch {
        throw "Não foi possível exportar o contrato de $($servico.Nome) em ${url}: $($_.Exception.Message)"
    }

    $contrato.info | Add-Member -MemberType NoteProperty -Name title -Value $servico.Titulo -Force
    $contrato.info | Add-Member -MemberType NoteProperty -Name description -Value $servico.Descricao -Force
    $contrato.info | Add-Member -MemberType NoteProperty -Name version -Value "1.0.0" -Force
    $contrato.info | Add-Member -MemberType NoteProperty -Name license -Value ([ordered]@{
        name = "MIT"
        url = "https://github.com/Mateussoaresferreira/orquestra-pagamentos/blob/main/LICENSE"
    }) -Force

    $contrato.components | Add-Member -MemberType NoteProperty -Name securitySchemes -Value ([ordered]@{
        portadorJwt = [ordered]@{
            type = "http"
            scheme = "bearer"
            bearerFormat = "JWT"
            description = "Token OAuth2 exigido no perfil cloud; o perfil local desabilita autenticação."
        }
    }) -Force
    $contrato | Add-Member -MemberType NoteProperty -Name security -Value @(
        [ordered]@{ portadorJwt = @() }
    ) -Force

    foreach ($rota in $contrato.paths.PSObject.Properties) {
        foreach ($metodo in $rota.Value.PSObject.Properties) {
            $operacao = $metodo.Value
            if (-not $operacao.summary) {
                $identificador = ($operacao.operationId -split "_")[0]
                $resumo = $resumosOperacoes[$identificador]
                if (-not $resumo) {
                    throw "A operação $($operacao.operationId) em $($rota.Name) não possui resumo."
                }
                $operacao | Add-Member -MemberType NoteProperty -Name summary -Value $resumo -Force
            }

            if ($operacao.responses.PSObject.Properties.Name -notcontains "401") {
                $operacao.responses | Add-Member -MemberType NoteProperty -Name "401" -Value ([ordered]@{
                    description = "Credenciais ausentes ou inválidas"
                })
            }
            if ($operacao.responses.PSObject.Properties.Name -notcontains "403") {
                $operacao.responses | Add-Member -MemberType NoteProperty -Name "403" -Value ([ordered]@{
                    description = "Escopo ou empresa não autorizados"
                })
            }
        }
    }

    $contrato | Add-Member -MemberType NoteProperty -Name servers -Value @(
        [ordered]@{
            url = "http://localhost:$($servico.Porta)"
            description = "Ambiente local"
        }
    ) -Force

    $caminho = Join-Path $destino $servico.Arquivo
    $contrato | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $caminho -Encoding utf8
    Write-Host "Contrato exportado: $caminho"
}
