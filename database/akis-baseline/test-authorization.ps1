[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$baselineDirectory = $PSScriptRoot
$projectRoot = Split-Path -Parent (Split-Path -Parent $baselineDirectory)
$migration = Join-Path $baselineDirectory "V001__identity_rbac_project.sql"
$envFile = Join-Path $projectRoot ".env"
$docker = "C:\Program Files\Docker\Docker\resources\bin\docker.exe"
$maven = Join-Path $projectRoot "mvnw.cmd"
$testDatabase = "akis_rbac_test_" + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$containerMigration = "/tmp/akis-rbac-v001.sql"
$databaseCreated = $false

if ($testDatabase -notmatch '^akis_rbac_test_[0-9]+$') {
    throw "Unsafe temporary database name."
}
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
    throw "metadata-db container is not running."
}

$databaseUser = $settings["POSTGRES_USER"]
try {
    & $docker cp $migration "${container}:$containerMigration"
    if ($LASTEXITCODE -ne 0) {
        throw "Could not copy the clean baseline migration."
    }
    & $docker exec $container createdb -U $databaseUser $testDatabase
    if ($LASTEXITCODE -ne 0) {
        throw "Could not create the temporary RBAC database."
    }
    $databaseCreated = $true
    & $docker exec $container psql -v ON_ERROR_STOP=1 -U $databaseUser -d $testDatabase -f $containerMigration
    if ($LASTEXITCODE -ne 0) {
        throw "Clean baseline migration failed."
    }

    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:$($settings['POSTGRES_PORT'])/$testDatabase"
    $env:SPRING_DATASOURCE_USERNAME = $databaseUser
    $env:SPRING_DATASOURCE_PASSWORD = $settings["POSTGRES_PASSWORD"]
    & $maven -pl backend "-Dtest=AuthorizationRepositoryIT,JdbcIdentityStoreIT" test
    if ($LASTEXITCODE -ne 0) {
        throw "Clean RBAC repository test failed."
    }
    Write-Output "Clean identity and RBAC repository tests: PASS"
}
finally {
    if ($databaseCreated -and $testDatabase -match '^akis_rbac_test_[0-9]+$') {
        & $docker exec $container dropdb --if-exists --force -U $databaseUser $testDatabase | Out-Null
    }
}
