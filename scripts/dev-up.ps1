[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot ".env"
$dockerCommand = Get-Command docker -ErrorAction SilentlyContinue

if ($dockerCommand) {
    $docker = $dockerCommand.Source
}
else {
    $docker = "C:\Program Files\Docker\Docker\resources\bin\docker.exe"
}

if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing .env. Copy .env.example to .env and set local values."
}
if (-not (Test-Path -LiteralPath $docker)) {
    throw "Docker CLI was not found. Install or start Docker Desktop."
}

Push-Location $projectRoot
try {
    & $docker compose --env-file $envFile up -d --wait
    & $docker compose --env-file $envFile ps
}
finally {
    Pop-Location
}
