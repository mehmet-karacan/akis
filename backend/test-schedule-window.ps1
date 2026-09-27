[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root '.env'
$docker = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
$maven = Join-Path $root 'mvnw.cmd'
if (-not (Test-Path -LiteralPath $envFile)) { throw 'Local .env is required.' }

$settings = @{}
Get-Content -LiteralPath $envFile |
    Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } |
    ForEach-Object { $pair = $_ -split '=', 2; $settings[$pair[0]] = $pair[1] }

$container = (& $docker compose --env-file $envFile ps -q metadata-db).Trim()
if (-not $container) { throw 'metadata-db container is not running.' }
$database = 'akis_schedule_test_' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
if ($database -notmatch '^akis_schedule_test_[0-9]+$') { throw 'Invalid generated test database name.' }
$created = $false

try {
    & $docker exec $container createdb -U $settings['POSTGRES_USER'] $database
    if ($LASTEXITCODE -ne 0) { throw 'Test database creation failed.' }
    $created = $true

    $migrations = Get-ChildItem (Join-Path $root 'database/akis-baseline/V0*.sql') |
        Sort-Object { [int]([regex]::Match($_.Name, '^V([0-9]+)__').Groups[1].Value) }
    foreach ($migration in $migrations) {
        & $docker cp $migration.FullName "${container}:/tmp/$($migration.Name)" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Migration copy failed: $($migration.Name)" }
        & $docker exec $container psql -q --single-transaction -v ON_ERROR_STOP=1 `
            -U $settings['POSTGRES_USER'] -d $database -f "/tmp/$($migration.Name)" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Migration failed: $($migration.Name)" }
    }

    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:$($settings['POSTGRES_PORT'])/$database"
    $env:SPRING_DATASOURCE_USERNAME = $settings['POSTGRES_USER']
    $env:SPRING_DATASOURCE_PASSWORD = $settings['POSTGRES_PASSWORD']
    Push-Location $root
    try {
        & $maven -q -pl backend '-Dtest=ScheduleRepositoryIT,SchedulePreviewWindowTest' test
        if ($LASTEXITCODE -ne 0) { throw 'Schedule window integration tests failed.' }
    }
    finally { Pop-Location }
    Write-Output 'Fresh migration and schedule window tests: PASS'
}
finally {
    if ($created -and $database -match '^akis_schedule_test_[0-9]+$') {
        & $docker exec $container dropdb --if-exists --force -U $settings['POSTGRES_USER'] $database | Out-Null
    }
}
