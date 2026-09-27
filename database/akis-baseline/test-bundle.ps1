$ErrorActionPreference = 'Stop'
$baselineDirectory = $PSScriptRoot
$root = Split-Path -Parent (Split-Path -Parent $baselineDirectory)
$docker = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
$container = 'akis-metadata-db-1'
$database = 'akis_bundle_test_' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$roundtripDatabase = $database + '_roundtrip'
$created = $false
$roundtripCreated = $false
$settings = @{}

Get-Content (Join-Path $root '.env') |
    Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } |
    ForEach-Object {
        $name, $value = $_ -split '=', 2
        $settings[$name] = $value
    }

$databaseUser = (& $docker exec $container sh -lc 'printf %s "$POSTGRES_USER"').Trim()

try {
    & $docker exec $container createdb -U $databaseUser $database
    if ($LASTEXITCODE) { throw 'create failed' }
    $created = $true

    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:$($settings['POSTGRES_PORT'])/$database"
    $env:SPRING_DATASOURCE_USERNAME = $databaseUser
    $env:SPRING_DATASOURCE_PASSWORD = $settings['POSTGRES_PASSWORD']
    $env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"

    & (Join-Path $root 'mvnw.cmd') -pl backend '-Dtest=CleanProjectBundleRepositoryIT,CleanAuditRepositoryIT,PasswordSetupServiceIT' test
    if ($LASTEXITCODE) { throw 'repository test failed' }
    'Clean bundle and audit repository tests: PASS'

    # Keep the repository fixture and the round-trip fixture isolated. The
    # repository test intentionally creates ROLLBACK_TARGET; the round-trip
    # test creates the same deterministic code to prove rollback semantics.
    & $docker exec $container createdb -U $databaseUser $roundtripDatabase
    if ($LASTEXITCODE) { throw 'roundtrip database create failed' }
    $roundtripCreated = $true
    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:$($settings['POSTGRES_PORT'])/$roundtripDatabase"

    & (Join-Path $root 'mvnw.cmd') -pl backend '-Dit.test=ProjectBundleRoundTripIT' verify
    if ($LASTEXITCODE) { throw 'project bundle roundtrip test failed' }
    'Project bundle export/import roundtrip: PASS'
}
finally {
    if ($created -and $database -match '^akis_bundle_test_[0-9]+$') {
        & $docker exec $container dropdb --if-exists --force -U $databaseUser $database | Out-Null
    }
    if ($roundtripCreated -and $roundtripDatabase -match '^akis_bundle_test_[0-9]+_roundtrip$') {
        & $docker exec $container dropdb --if-exists --force -U $databaseUser $roundtripDatabase | Out-Null
    }
}
