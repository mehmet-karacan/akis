[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot ".env"

if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing .env. Copy .env.example to .env and set local values."
}

Get-Content -LiteralPath $envFile |
    Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } |
    ForEach-Object {
        $name, $value = $_ -split '=', 2
        [Environment]::SetEnvironmentVariable($name, $value, "Process")
    }

Push-Location $projectRoot
try {
    & (Join-Path $projectRoot "mvnw.cmd") -pl backend spring-boot:run
}
finally {
    Pop-Location
}
