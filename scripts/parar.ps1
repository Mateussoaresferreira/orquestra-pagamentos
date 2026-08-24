param(
    [switch] $RemoverDados
)

$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot

Push-Location $raiz
try {
    if ($RemoverDados) {
        docker compose down --volumes --remove-orphans
    }
    else {
        docker compose down --remove-orphans
    }
}
finally {
    Pop-Location
}
