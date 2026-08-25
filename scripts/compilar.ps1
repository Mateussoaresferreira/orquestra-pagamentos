param(
    [switch] $PularTestes
)

$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot

function Testar-Java25 {
    param([string] $Executavel)

    if (-not $Executavel -or -not (Test-Path $Executavel)) {
        return $false
    }

    $preferenciaErroAnterior = $ErrorActionPreference
    try {
        # O Java escreve a versao em stderr; no Windows PowerShell isso nao e uma falha.
        $ErrorActionPreference = 'Continue'
        $versao = (& $Executavel -version 2>&1 | Out-String)
        $codigoSaida = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $preferenciaErroAnterior
    }

    return $codigoSaida -eq 0 -and $versao -match 'version "25(?:\.|\")'
}

if ($env:JAVA_HOME -and -not (Testar-Java25 "$env:JAVA_HOME\bin\java.exe")) {
    throw 'JAVA_HOME existe, mas nao aponta para um JDK 25.'
}

if (-not $env:JAVA_HOME) {
    $candidatos = [System.Collections.Generic.List[string]]::new()
    $javaNoPath = Get-Command java -ErrorAction SilentlyContinue
    if ($javaNoPath) {
        $candidatos.Add((Split-Path -Parent (Split-Path -Parent $javaNoPath.Source)))
    }

    @("$HOME\.jdks", "$env:ProgramFiles\Java", "$env:ProgramFiles\Eclipse Adoptium") |
        Where-Object { Test-Path $_ } |
        ForEach-Object {
            Get-ChildItem $_ -Directory -ErrorAction SilentlyContinue |
                ForEach-Object { $candidatos.Add($_.FullName) }
        }

    $jdk = $candidatos |
        Select-Object -Unique |
        Where-Object { Testar-Java25 "$_\bin\java.exe" } |
        Select-Object -First 1

    if (-not $jdk) {
        throw 'JDK 25 nao encontrado. Instale-o ou defina JAVA_HOME antes de compilar.'
    }
    $env:JAVA_HOME = $jdk
}

$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$argumentos = @('clean', 'verify')
if ($PularTestes) {
    $argumentos += '-DskipTests'
}

Push-Location $raiz
try {
    & .\mvnw.cmd @argumentos
    if ($LASTEXITCODE -ne 0) {
        throw "A compilacao terminou com codigo $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}
