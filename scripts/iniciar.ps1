param(
    [switch] $SemCompilar,
    [switch] $SemObservabilidade,
    [switch] $SemConstruirImagens
)

$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot
$composeBakeAnterior = $env:COMPOSE_BAKE
$composeParallelAnterior = $env:COMPOSE_PARALLEL_LIMIT

function Novo-SegredoLocal {
    param([int] $QuantidadeBytes = 32)

    $bytes = [byte[]]::new($QuantidadeBytes)
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToBase64String($bytes)
}

function Preparar-SegredosLocais {
    $caminho = Join-Path $raiz '.env'
    if (Test-Path $caminho) {
        return
    }

    $conteudo = @(
        "CHAVE_CRIPTOGRAFIA_TOKEN=$(Novo-SegredoLocal 32)"
        "REDIS_SENHA=$(Novo-SegredoLocal 24)"
        "PROVEDOR_CHAVE_API=$(Novo-SegredoLocal 32)"
    )
    [System.IO.File]::WriteAllLines(
        $caminho,
        $conteudo,
        [System.Text.UTF8Encoding]::new($false))
    Write-Host 'Segredos locais aleatorios criados em .env.' -ForegroundColor DarkGray
}

function Aguardar-Endereco {
    param(
        [Parameter(Mandatory)]
        [string] $Nome,

        [Parameter(Mandatory)]
        [string] $Endereco,

        [int] $TempoLimiteSegundos = 120
    )

    $limite = (Get-Date).AddSeconds($TempoLimiteSegundos)

    do {
        try {
            $resposta = Invoke-WebRequest -Uri $Endereco -TimeoutSec 3 -UseBasicParsing
            if ($resposta.StatusCode -ge 200 -and $resposta.StatusCode -lt 300) {
                Write-Host ("  {0} pronto" -f $Nome) -ForegroundColor Green
                return
            }
        }
        catch {
            # O componente ainda esta iniciando.
        }

        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $limite)

    throw "O componente $Nome nao respondeu em $Endereco dentro do prazo."
}

Preparar-SegredosLocais

if (-not $SemCompilar) {
    & "$PSScriptRoot\compilar.ps1"
}

$aplicacoes = @(
    'simulador-provedor', 'simulador-provedor-contingencia',
    'servico-checkout', 'servico-estoque',
    'servico-risco', 'servico-pagamento', 'servico-razao',
    'servico-notificacao'
)
$infraestrutura = @(
    'banco-checkout', 'banco-estoque', 'banco-risco', 'banco-pagamento',
    'banco-razao', 'banco-notificacao', 'banco-registro', 'redis', 'kafka',
    'criador-topicos', 'registro-esquemas', 'receptor-webhook'
)
$observabilidade = @(
    'tempo', 'loki', 'prometheus', 'coletor-otel', 'alloy', 'grafana'
)

Push-Location $raiz
try {
    if (-not $SemConstruirImagens) {
        $env:COMPOSE_BAKE = 'false'
        $env:COMPOSE_PARALLEL_LIMIT = '1'
        docker compose build @aplicacoes
        if ($LASTEXITCODE -ne 0) {
            throw 'Nao foi possivel construir as imagens da aplicacao.'
        }
    }

    docker compose up -d --wait @infraestrutura
    if ($LASTEXITCODE -ne 0) {
        throw 'Nao foi possivel iniciar a infraestrutura distribuida.'
    }

    Aguardar-Endereco -Nome 'Registro de esquemas' -Endereco 'http://localhost:8088/apis/registry/v3/system/info'

    docker compose up -d --wait @aplicacoes
    if ($LASTEXITCODE -ne 0) {
        throw 'Nao foi possivel iniciar os servicos da aplicacao.'
    }

    if ($SemObservabilidade) {
        docker compose stop @observabilidade | Out-Null
    }
    else {
        docker compose up -d --wait @observabilidade
        if ($LASTEXITCODE -ne 0) {
            throw 'O nucleo iniciou, mas a observabilidade nao ficou saudavel.'
        }

        $enderecosObservabilidade = [ordered]@{
            'Prometheus' = 'http://localhost:9090/-/ready'
            'Grafana'    = 'http://localhost:3010/api/health'
            'Tempo'      = 'http://localhost:3200/ready'
            'Loki'       = 'http://localhost:3100/ready'
            'Alloy'      = 'http://localhost:12345/-/ready'
        }

        foreach ($componente in $enderecosObservabilidade.GetEnumerator()) {
            Aguardar-Endereco -Nome $componente.Key -Endereco $componente.Value
        }
    }
}
finally {
    $env:COMPOSE_BAKE = $composeBakeAnterior
    $env:COMPOSE_PARALLEL_LIMIT = $composeParallelAnterior
    Pop-Location
}

Write-Host ''
Write-Host 'Orquestra de Pagamentos esta pronta:' -ForegroundColor Green
Write-Host '  Swagger:    http://localhost:8080/swagger-ui.html'
if (-not $SemObservabilidade) {
    Write-Host '  Grafana:    http://localhost:3010  (admin / orquestrapay)'
    Write-Host '  Prometheus: http://localhost:9090'
}
Write-Host '  Apicurio:   http://localhost:8088'
