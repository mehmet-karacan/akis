[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$baselineDirectory = Join-Path $projectRoot 'database/akis-baseline'
$envFile = Join-Path $projectRoot '.env'
$docker = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'

if (-not (Test-Path -LiteralPath $envFile)) { throw 'Missing .env.' }

$settings = @{}
Get-Content -LiteralPath $envFile |
    Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } |
    ForEach-Object {
        $name, $value = $_ -split '=', 2
        $settings[$name] = $value.Trim('"')
    }

$container = (& $docker compose --env-file $envFile ps -q metadata-db).Trim()
if ([string]::IsNullOrWhiteSpace($container)) {
    throw 'metadata-db container is not running. Run scripts\dev-up.ps1 first.'
}

$databaseUser = $settings['POSTGRES_USER']
$testDatabase = 'akis_worker_test_' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$created = $false

try {
    & $docker exec $container createdb -U $databaseUser $testDatabase
    if ($LASTEXITCODE -ne 0) { throw 'Could not create worker lease test database.' }
    $created = $true

    # Validate current akis.* repositories against the current production
    # baseline. The legacy database/migrations bundle is historical-only.
    foreach ($file in Get-ChildItem -LiteralPath $baselineDirectory -Filter 'V0*.sql' | Sort-Object Name) {
        & $docker cp $file.FullName "${container}:/tmp/$($file.Name)" | Out-Null
        & $docker exec $container psql -q -v ON_ERROR_STOP=1 -U $databaseUser -d $testDatabase -f "/tmp/$($file.Name)"
        if ($LASTEXITCODE -ne 0) { throw "Baseline migration failed: $($file.Name)" }
    }

    $verify = Join-Path $baselineDirectory 'verify-worker.sql'
    & $docker cp $verify "${container}:/tmp/verify-worker.sql" | Out-Null
    & $docker exec $container psql -v ON_ERROR_STOP=1 -U $databaseUser -d $testDatabase -f '/tmp/verify-worker.sql'
    if ($LASTEXITCODE -ne 0) { throw 'Worker baseline verification failed.' }

    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:$($settings['POSTGRES_PORT'])/$testDatabase"
    $env:SPRING_DATASOURCE_USERNAME = $databaseUser
    $env:SPRING_DATASOURCE_PASSWORD = $settings['POSTGRES_PASSWORD']
    & (Join-Path $projectRoot 'mvnw.cmd') -pl backend '-Dtest=CleanWorkerLeaseRepositoryIT,CleanProcedureExecutionJournalIT' test
    if ($LASTEXITCODE -ne 0) { throw 'Current worker repository tests failed.' }

    Write-Output 'Current worker lease repository tests: PASS'
}
finally {
    if ($created -and $testDatabase -match '^akis_worker_test_[0-9]+$') {
        & $docker exec $container dropdb --if-exists --force -U $databaseUser $testDatabase | Out-Null
    }
}
