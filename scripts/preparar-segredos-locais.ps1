param(
    [string] $Destino = (Join-Path (Split-Path -Parent $PSScriptRoot) '.env')
)

$ErrorActionPreference = 'Stop'

function Novo-SegredoLocal {
    param([int] $QuantidadeBytes = 32)

    $bytes = [byte[]]::new($QuantidadeBytes)
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToBase64String($bytes)
}

if (Test-Path $Destino) {
    Write-Host 'O arquivo local de segredos ja existe; nenhum valor foi alterado.' -ForegroundColor DarkGray
    return
}

$conteudo = @(
    "CHAVE_CRIPTOGRAFIA_TOKEN=$(Novo-SegredoLocal 32)"
    "REDIS_SENHA=$(Novo-SegredoLocal 24)"
    "PROVEDOR_CHAVE_API=$(Novo-SegredoLocal 32)"
)
[System.IO.File]::WriteAllLines(
    $Destino,
    $conteudo,
    [System.Text.UTF8Encoding]::new($false))
Write-Host 'Segredos locais aleatorios criados sem expor os valores.' -ForegroundColor DarkGray
