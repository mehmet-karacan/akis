[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$baselineDirectory = $PSScriptRoot
$taskRoot = Split-Path -Parent (Split-Path -Parent $baselineDirectory)
$docker = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
$container = 'akis-metadata-db-1'
$testDatabase = 'akis_execution_test_' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$created = $false
$settings = @{}
Get-Content -LiteralPath (Join-Path $taskRoot '.env') |
    Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } |
    ForEach-Object { $name, $value = $_ -split '=', 2; $settings[$name] = $value.Trim('"') }
$databaseUser = (& $docker exec $container sh -lc 'printf %s "$POSTGRES_USER"').Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($databaseUser)) { throw 'PostgreSQL container is unavailable.' }
if ($testDatabase -notmatch '^akis_execution_test_[0-9]+$') { throw 'Unsafe test database name.' }
$previous = @{}
foreach ($name in @('SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME', 'SPRING_DATASOURCE_PASSWORD')) {
    $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

function Invoke-TestSqlFile([string] $Path) {
    # Stream into the generated database; do not overwrite shared container files.
    Get-Content -LiteralPath $Path -Raw | & $docker exec -i $container psql -q -v ON_ERROR_STOP=1 -U $databaseUser -d $testDatabase
    if ($LASTEXITCODE -ne 0) { throw "SQL verification failed: $(Split-Path -Leaf $Path)" }
}

try {
    & $docker exec $container createdb -U $databaseUser $testDatabase
    if ($LASTEXITCODE -ne 0) { throw 'Could not create isolated execution test database.' }
    $created = $true
    $migrations = Get-ChildItem -LiteralPath $baselineDirectory -Filter 'V*.sql' |
        Where-Object { $_.Name -match '^V[0-9]+__' } | Sort-Object Name
    foreach ($migration in $migrations) {
        Invoke-TestSqlFile $migration.FullName
        if ($migration.Name -like 'V007__*') {
            Invoke-TestSqlFile (Join-Path $baselineDirectory 'verify-execution.sql')
        }
    }
    # Repository queries require the current journal schema, not only V001–V007.
    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:$($settings['POSTGRES_PORT'])/$testDatabase"
    $env:SPRING_DATASOURCE_USERNAME = $databaseUser
    $env:SPRING_DATASOURCE_PASSWORD = $settings['POSTGRES_PASSWORD']
    & (Join-Path $taskRoot 'mvnw.cmd') -pl backend '-Dtest=CleanExecutionRepositoryIT,CleanVariableHistoryIT' test
    if ($LASTEXITCODE -ne 0) { throw 'Execution repository integration test failed.' }
    Write-Output 'Clean execution control-plane repository tests: PASS'
}
finally {
    foreach ($name in $previous.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
    }
    if ($created -and $testDatabase -match '^akis_execution_test_[0-9]+$') {
        & $docker exec $container dropdb --if-exists --force -U $databaseUser $testDatabase | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Warning "Temporary test database could not be removed: $testDatabase" }
    }
}
