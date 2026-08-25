param(
    [string] $Colecao = 'postman/orquestrapay-fluxo-completo.postman_collection.json',
    [string] $Ambiente = 'postman/orquestrapay-ambiente-local.postman_environment.json',
    [string] $RelatorioJson = ''
)

$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot
$arquivoSegredos = Join-Path $raiz '.env'
$caminhoColecao = Join-Path $raiz $Colecao
$caminhoAmbiente = Join-Path $raiz $Ambiente
$ambienteTemporario = Join-Path ([System.IO.Path]::GetTempPath()) `
    "orquestrapay-postman-$([guid]::NewGuid()).json"

if (-not (Test-Path $arquivoSegredos)) {
    throw 'Arquivo .env ausente. Execute scripts/iniciar.ps1 para gerar os segredos locais.'
}

$linhaChave = Get-Content $arquivoSegredos |
    Where-Object { $_.StartsWith('PROVEDOR_CHAVE_API=') } |
    Select-Object -First 1
if (-not $linhaChave) {
    throw 'PROVEDOR_CHAVE_API nao foi encontrada no arquivo .env.'
}

$chaveApi = $linhaChave.Substring($linhaChave.IndexOf('=') + 1)
if ([string]::IsNullOrWhiteSpace($chaveApi)) {
    throw 'PROVEDOR_CHAVE_API esta vazia no arquivo .env.'
}

try {
    $configuracao = Get-Content $caminhoAmbiente -Raw | ConvertFrom-Json
    $variavel = $configuracao.values |
        Where-Object { $_.key -eq 'chaveApiProvedor' } |
        Select-Object -First 1
    if (-not $variavel) {
        throw 'A variavel chaveApiProvedor nao existe no ambiente Postman.'
    }

    $variavel.value = $chaveApi
    $jsonAmbiente = $configuracao | ConvertTo-Json -Depth 20
    [System.IO.File]::WriteAllText(
        $ambienteTemporario,
        $jsonAmbiente,
        [System.Text.UTF8Encoding]::new($false)
    )

    $argumentosNewman = @(
        '--yes',
        'newman',
        'run',
        $caminhoColecao,
        '-e',
        $ambienteTemporario,
        '--color',
        'off'
    )

    if ([string]::IsNullOrWhiteSpace($RelatorioJson)) {
        $argumentosNewman += @('--reporters', 'cli')
    }
    else {
        $diretorioRelatorio = Split-Path -Parent $RelatorioJson
        if ($diretorioRelatorio) {
            New-Item -ItemType Directory -Force -Path $diretorioRelatorio | Out-Null
        }
        $argumentosNewman += @(
            '--reporters', 'json',
            '--reporter-json-export', $RelatorioJson
        )
    }

    & npx @argumentosNewman
    if ($LASTEXITCODE -ne 0) {
        throw "A colecao Postman terminou com codigo $LASTEXITCODE."
    }
}
finally {
    if (Test-Path $ambienteTemporario) {
        Remove-Item -LiteralPath $ambienteTemporario -Force
    }
}
