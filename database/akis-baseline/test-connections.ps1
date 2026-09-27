[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$baselineDirectory = $PSScriptRoot
$verification = Join-Path $baselineDirectory "verify-connections.sql"
$projectRoot = Split-Path -Parent (Split-Path -Parent $baselineDirectory)
$envFile = Join-Path $projectRoot ".env"
$maven = Join-Path $projectRoot "mvnw.cmd"
$docker = "C:\Program Files\Docker\Docker\resources\bin\docker.exe"
$containerName = "akis-metadata-db-1"
$testDatabase = "akis_connections_test_" + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$databaseCreated = $false
$javaHome = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'

if ($testDatabase -notmatch '^akis_connections_test_[0-9]+$') {
    throw "Unsafe temporary database name."
}
if (-not (Test-Path -LiteralPath (Join-Path $javaHome 'bin\java.exe'))) {
    throw "Java 25 is required for the connection integration test: $javaHome"
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

    $migrations = Get-ChildItem -LiteralPath $baselineDirectory -Filter 'V*.sql' |
        Where-Object { $_.Name -match '^V[0-9]+__' } |
        Sort-Object { [int]([regex]::Match($_.Name, '^V([0-9]+)__').Groups[1].Value) }
    foreach ($migration in $migrations) {
        $containerPath = "/tmp/akis-clean-$($migration.Name)"
        & $docker cp $migration.FullName "${containerName}:$containerPath"
        if ($LASTEXITCODE -ne 0) { throw "Could not copy $($migration.Name)." }
        & $docker exec $containerName psql -v ON_ERROR_STOP=1 -U $databaseUser -d $testDatabase -f $containerPath
        if ($LASTEXITCODE -ne 0) { throw "Connection schema test failed in $($migration.Name)." }
    }

    & $docker cp $verification "${containerName}:/tmp/akis-clean-connections-verify.sql"
    if ($LASTEXITCODE -ne 0) { throw "Could not copy verify-connections.sql." }
    & $docker exec $containerName psql -v ON_ERROR_STOP=1 -U $databaseUser -d $testDatabase -f /tmp/akis-clean-connections-verify.sql
    if ($LASTEXITCODE -ne 0) { throw "Connection schema verification failed." }

    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:$($settings['POSTGRES_PORT'])/$testDatabase"
    $env:SPRING_DATASOURCE_USERNAME = $databaseUser
    $env:SPRING_DATASOURCE_PASSWORD = $settings["POSTGRES_PASSWORD"]
    $env:JAVA_HOME = $javaHome
    $env:Path = "$javaHome\bin;$env:Path"
    & $maven -pl backend "-Dtest=CleanTopologyRepositoryIT,CleanConnectionLifecycleRepositoryIT" test
    if ($LASTEXITCODE -ne 0) { throw "Clean connection repository test failed." }
    Write-Output "Clean connection and lifecycle repository tests: PASS"
}
finally {
    if ($databaseCreated -and $testDatabase -match '^akis_connections_test_[0-9]+$') {
        & $docker exec $containerName dropdb --if-exists --force -U $databaseUser $testDatabase | Out-Null
    }
}
