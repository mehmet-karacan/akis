[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot ".env"

if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing .env. Copy .env.example to .env and set local values."
}

Push-Location $projectRoot
try {
    docker compose --env-file $envFile up -d --wait
    docker compose --env-file $envFile ps
}
finally {
    Pop-Location
}
