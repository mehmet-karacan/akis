[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$kmDocker = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
$kmContainer = 'akis-metadata-db-1'
$kmJavaHome = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'
$kmDatabase = 'akis_km_test_' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$kmCreated = $false
$kmSavedEnvironment = @{}
foreach ($kmKey in @('SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD')) { $kmSavedEnvironment[$kmKey] = [Environment]::GetEnvironmentVariable($kmKey, 'Process') }
if ($kmDatabase -notmatch '^akis_km_test_[0-9]+$') { throw 'Unsafe temporary database name.' }
if (-not (Test-Path -LiteralPath (Join-Path $kmJavaHome 'bin\java.exe'))) { throw 'Java 25 is required for the KM integration test.' }
$env:JAVA_HOME = $kmJavaHome
$env:Path = "$kmJavaHome\bin;$env:Path"
$kmRunning = & $kmDocker inspect --format '{{.State.Running}}' $kmContainer
if ($LASTEXITCODE -ne 0 -or $kmRunning -ne 'true') { throw 'Local PostgreSQL container is not running.' }
$kmUser = (& $kmDocker exec $kmContainer sh -lc 'printf %s "$POSTGRES_USER"').Trim()
if ([string]::IsNullOrWhiteSpace($kmUser)) { throw 'Missing PostgreSQL user.' }
try {
    & $kmDocker exec $kmContainer createdb -U $kmUser $kmDatabase
    if ($LASTEXITCODE -ne 0) { throw 'Temporary database creation failed.' }
    $kmCreated = $true
    foreach ($kmFile in (Get-ChildItem -LiteralPath $PSScriptRoot -Filter 'V*.sql' | Where-Object { $_.Name -cmatch '^V[0-9]{3}__.*\.sql$' } | Sort-Object Name)) {
        $kmRemote = '/tmp/' + $kmDatabase + '_' + $kmFile.Name
        & $kmDocker cp $kmFile.FullName "${kmContainer}:$kmRemote"
        if ($LASTEXITCODE -ne 0) { throw 'Migration copy failed.' }
        & $kmDocker exec $kmContainer psql -v ON_ERROR_STOP=1 -U $kmUser -d $kmDatabase -f $kmRemote
        if ($LASTEXITCODE -ne 0) { throw "Migration failed: $($kmFile.Name)" }
    }
    # V033+ stores the authoritative work-table prefixes on fiziksel_sema;
    # calisma_nesnesi_prefix is intentionally removed during the topology cutover.
    & $kmDocker exec $kmContainer psql -v ON_ERROR_STOP=1 -U $kmUser -d $kmDatabase -c "SELECT count(*) FROM akis.fiziksel_sema; SELECT count(*) FROM akis.km_work_object;"
    if ($LASTEXITCODE -ne 0) { throw 'KM tables are unavailable.' }
    $kmRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
    $kmSettings = @{}
    Get-Content -LiteralPath (Join-Path $kmRoot '.env') | Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } | ForEach-Object {
        $kmName, $kmValue = $_ -split '=', 2
        $kmSettings[$kmName] = $kmValue
    }
    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:$($kmSettings['POSTGRES_PORT'])/$kmDatabase"
    $env:SPRING_DATASOURCE_USERNAME = $kmUser
    $env:SPRING_DATASOURCE_PASSWORD = $kmSettings['POSTGRES_PASSWORD']
    & (Join-Path $kmRoot 'mvnw.cmd') -pl backend '-Dtest=CleanKnowledgeRepositoryIT' test -q
    if ($LASTEXITCODE -ne 0) { throw 'KM repository integration tests failed.' }
    Write-Output 'Knowledge Module clean migration chain: PASS'
} finally {
    foreach ($kmKey in $kmSavedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($kmKey, $kmSavedEnvironment[$kmKey], 'Process') }
    if ($kmCreated -and $kmDatabase -match '^akis_km_test_[0-9]+$') {
        & $kmDocker exec $kmContainer dropdb --if-exists -U $kmUser $kmDatabase
        if ($LASTEXITCODE -ne 0) { Write-Warning "Temporary test database remains: $kmDatabase" }
    }
}
