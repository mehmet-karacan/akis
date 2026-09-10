[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot ".env"
$migrationDirectory = Join-Path $PSScriptRoot "migrations"
$maven = Join-Path $projectRoot "mvnw.cmd"

if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing .env."
}

Get-Content -LiteralPath $envFile |
    Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } |
    ForEach-Object {
        $name, $value = $_ -split '=', 2
        [Environment]::SetEnvironmentVariable($name, $value, "Process")
    }

$env:SPRING_FLYWAY_ENABLED = "true"
$env:SPRING_FLYWAY_LOCATIONS = "filesystem:" + $migrationDirectory.Replace('\', '/')
$env:SPRING_MAIN_WEB_APPLICATION_TYPE = "none"

Push-Location $projectRoot
try {
    & $maven -q -pl backend spring-boot:run "-Dspring-boot.run.arguments=--spring.main.banner-mode=off"
    if ($LASTEXITCODE -ne 0) {
        throw "Metadata migration failed."
    }
}
finally {
    Pop-Location
}
