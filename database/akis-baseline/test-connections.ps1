[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$baselineDirectory = $PSScriptRoot
$v001 = Join-Path $baselineDirectory "V001__identity_rbac_project.sql"
$v002 = Join-Path $baselineDirectory "V002__connections_and_schemas.sql"
$verification = Join-Path $baselineDirectory "verify-connections.sql"
$projectRoot = Split-Path -Parent (Split-Path -Parent $baselineDirectory)
$envFile = Join-Path $projectRoot ".env"
$maven = Join-Path $projectRoot "mvnw.cmd"
$docker = "C:\Program Files\Docker\Docker\resources\bin\docker.exe"
$containerName = "akis-metadata-db-1"
$testDatabase = "akis_connections_test_" + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$databaseCreated = $false

if ($testDatabase -notmatch '^akis_connections_test_[0-9]+$') {
    throw "Unsafe temporary database name."
}

$settings = @{}
Get-Content -LiteralPath $envFile |
    Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } |
    ForEach-Object {
        $name, $value = $_ -split '=', 2
        $settings[$name] = $value
    }

$databaseUser = (& $docker exec $containerName sh -lc 'printf %s "$POSTGRES_USER"').Trim()
if ([string]::IsNullOrWhiteSpace($databaseUser)) {
    throw "PostgreSQL container is not available."
}

try {
    & $docker exec $containerName createdb -U $databaseUser $testDatabase
    if ($LASTEXITCODE -ne 0) { throw "Could not create the temporary connection database." }
    $databaseCreated = $true

    $files = @(
        @{ Local = $v001; Container = "/tmp/akis-clean-v001.sql" },
        @{ Local = $v002; Container = "/tmp/akis-clean-v002.sql" },
        @{ Local = $verification; Container = "/tmp/akis-clean-connections-verify.sql" }
    )
    foreach ($file in $files) {
        & $docker cp $file.Local "${containerName}:$($file.Container)"
        if ($LASTEXITCODE -ne 0) { throw "Could not copy a connection test SQL file." }
        & $docker exec $containerName psql -v ON_ERROR_STOP=1 -U $databaseUser -d $testDatabase -f $file.Container
        if ($LASTEXITCODE -ne 0) { throw "Connection schema test failed." }
    }

    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:$($settings['POSTGRES_PORT'])/$testDatabase"
    $env:SPRING_DATASOURCE_USERNAME = $databaseUser
    $env:SPRING_DATASOURCE_PASSWORD = $settings["POSTGRES_PASSWORD"]
    & $maven -pl backend "-Dtest=CleanTopologyRepositoryIT,CleanConnectionLifecycleRepositoryIT" test
    if ($LASTEXITCODE -ne 0) { throw "Clean connection repository test failed." }
    Write-Output "Clean connection and lifecycle repository tests: PASS"
}
finally {
    if ($databaseCreated -and $testDatabase -match '^akis_connections_test_[0-9]+$') {
        & $docker exec $containerName dropdb --if-exists --force -U $databaseUser $testDatabase | Out-Null
    }
}
