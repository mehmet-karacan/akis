[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot ".env"
$docker = "C:\Program Files\Docker\Docker\resources\bin\docker.exe"
$maven = Join-Path $projectRoot "mvnw.cmd"

if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing .env."
}

$settings = @{}
Get-Content -LiteralPath $envFile |
    Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } |
    ForEach-Object {
        $name, $value = $_ -split '=', 2
        $settings[$name] = $value
    }

$container = (& $docker compose --env-file $envFile ps -q metadata-db).Trim()
if ([string]::IsNullOrWhiteSpace($container)) {
    throw "metadata-db container is not running. Run scripts\dev-up.ps1 first."
}

$databaseUser = $settings["POSTGRES_USER"]
$testDatabase = "akis_worker_lease_test_" + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

try {
    & $docker exec $container createdb -U $databaseUser $testDatabase
    if ($LASTEXITCODE -ne 0) {
        throw "Could not create worker lease test database."
    }

    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:$($settings['POSTGRES_PORT'])/$testDatabase"
    $env:SPRING_DATASOURCE_USERNAME = $databaseUser
    $env:SPRING_DATASOURCE_PASSWORD = $settings["POSTGRES_PASSWORD"]
    $env:SPRING_FLYWAY_ENABLED = "true"
    $env:SPRING_MAIN_WEB_APPLICATION_TYPE = "none"
    $env:AKIS_EXECUTION_ACCEPT_MANUAL_REQUESTS = "false"
    $env:AKIS_EXECUTION_WORKER_ENABLED = "false"
    $env:AKIS_SECURITY_MODE = "fail-closed"

    & $maven -pl backend "-Dtest=JdbcRunLeaseStoreIT" test
    if ($LASTEXITCODE -ne 0) {
        throw "Worker lease JDBC integration test failed."
    }

    Write-Output "Worker lease JDBC integration test: PASS"
}
finally {
    & $docker exec $container dropdb --if-exists --force -U $databaseUser $testDatabase | Out-Null
}
