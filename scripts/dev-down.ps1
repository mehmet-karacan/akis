[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot ".env"

Push-Location $projectRoot
try {
    docker compose --env-file $envFile down
}
finally {
    Pop-Location
}
