$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot

Push-Location $raiz
try {
    docker compose ps
}
finally {
    Pop-Location
}

$servicos = [ordered]@{
    'Checkout'   = 'http://localhost:8080/actuator/health/readiness'
    'Estoque'    = 'http://localhost:8081/actuator/health/readiness'
    'Risco'      = 'http://localhost:8082/actuator/health/readiness'
    'Pagamento'  = 'http://localhost:8083/actuator/health/readiness'
    'Razao'      = 'http://localhost:8084/actuator/health/readiness'
    'Notificacao'= 'http://localhost:8085/actuator/health/readiness'
    'Provedor'   = 'http://localhost:8090/actuator/health/readiness'
}

Write-Host ''
Write-Host 'Servicos da aplicacao' -ForegroundColor Cyan
foreach ($servico in $servicos.GetEnumerator()) {
    try {
        $resposta = Invoke-RestMethod -Uri $servico.Value -TimeoutSec 3
        Write-Host ("{0,-12} {1}" -f $servico.Key, $resposta.status) -ForegroundColor Green
    }
    catch {
        Write-Host ("{0,-12} INDISPONIVEL" -f $servico.Key) -ForegroundColor Red
    }
}

$infraestrutura = [ordered]@{
    'Apicurio'   = 'http://localhost:8088/apis/registry/v3/system/info'
    'Prometheus' = 'http://localhost:9090/-/ready'
    'Grafana'    = 'http://localhost:3010/api/health'
    'Tempo'      = 'http://localhost:3200/ready'
    'Loki'       = 'http://localhost:3100/ready'
    'Alloy'      = 'http://localhost:12345/-/ready'
}

Write-Host ''
Write-Host 'Infraestrutura e observabilidade' -ForegroundColor Cyan
foreach ($componente in $infraestrutura.GetEnumerator()) {
    try {
        $resposta = Invoke-WebRequest -Uri $componente.Value -TimeoutSec 3 -UseBasicParsing
        if ($resposta.StatusCode -ge 200 -and $resposta.StatusCode -lt 300) {
            Write-Host ("{0,-12} PRONTO" -f $componente.Key) -ForegroundColor Green
        }
        else {
            Write-Host ("{0,-12} HTTP {1}" -f $componente.Key, $resposta.StatusCode) -ForegroundColor Yellow
        }
    }
    catch {
        Write-Host ("{0,-12} INDISPONIVEL" -f $componente.Key) -ForegroundColor Red
    }
}
