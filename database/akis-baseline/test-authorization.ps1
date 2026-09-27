[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$baselineDirectory = $PSScriptRoot
$projectRoot = Split-Path -Parent (Split-Path -Parent $baselineDirectory)
$envFile = Join-Path $projectRoot ".env"
$docker = "C:\Program Files\Docker\Docker\resources\bin\docker.exe"
$maven = Join-Path $projectRoot "mvnw.cmd"
$javaHome = "C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot"
$testDatabase = "akis_rbac_test_" + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$databaseCreated = $false

if ($testDatabase -notmatch '^akis_rbac_test_[0-9]+$') {
    throw "Unsafe temporary database name."
}
if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing .env."
}
if (-not (Test-Path -LiteralPath (Join-Path $javaHome "bin\java.exe"))) {
    throw "Java 25 is required for the backend integration test."
}
$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:Path"

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
    & $docker exec $container createdb -U $databaseUser $testDatabase
    if ($LASTEXITCODE -ne 0) {
        throw "Could not create the temporary RBAC database."
    }
    $databaseCreated = $true
    $migrations = Get-ChildItem (Join-Path $baselineDirectory 'V0*.sql') | Sort-Object {
        $match = [regex]::Match($_.Name, '^V([0-9]+)__')
        if ($match.Success) { [int]$match.Groups[1].Value } else { [int]::MaxValue }
    }
    foreach ($migration in $migrations) {
        & $docker cp $migration.FullName "${container}:/tmp/$($migration.Name)" | Out-Null
        & $docker exec $container psql -q --single-transaction -v ON_ERROR_STOP=1 `
            -U $databaseUser -d $testDatabase -c 'SET search_path TO akis, public' `
            -f "/tmp/$($migration.Name)"
        if ($LASTEXITCODE -ne 0) {
            throw "Migration failed: $($migration.Name)"
        }
    }

    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:$($settings['POSTGRES_PORT'])/$testDatabase"
    $env:SPRING_DATASOURCE_USERNAME = $databaseUser
    $env:SPRING_DATASOURCE_PASSWORD = $settings["POSTGRES_PASSWORD"]
    & $maven -pl backend "-Dtest=AuthorizationRepositoryIT,JdbcIdentityStoreIT,MetadataProjectRepositoryIT" test
    if ($LASTEXITCODE -ne 0) {
        throw "Clean RBAC repository test failed."
    }
    Write-Output "Clean identity, RBAC and project repository tests: PASS"
}
finally {
    if ($databaseCreated -and $testDatabase -match '^akis_rbac_test_[0-9]+$') {
        & $docker exec $container dropdb --if-exists --force -U $databaseUser $testDatabase | Out-Null
    }
}
