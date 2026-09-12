[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$baselineDirectory = $PSScriptRoot
$projectRoot = Split-Path -Parent (Split-Path -Parent $baselineDirectory)
$envFile = Join-Path $projectRoot ".env"
$maven = Join-Path $projectRoot "mvnw.cmd"
$docker = "C:\Program Files\Docker\Docker\resources\bin\docker.exe"
$containerName = "akis-metadata-db-1"
$testDatabase = "akis_definitions_test_" + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$databaseCreated = $false

if ($testDatabase -notmatch '^akis_definitions_test_[0-9]+$') {
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
    if ($LASTEXITCODE -ne 0) { throw "Could not create the temporary definitions database." }
    $databaseCreated = $true

    $files = @(
        "V001__identity_rbac_project.sql",
        "V002__connections_and_schemas.sql",
        "V003__folders_definitions_and_versions.sql",
        "verify-definitions.sql"
    )
    foreach ($name in $files) {
        $local = Join-Path $baselineDirectory $name
        $containerPath = "/tmp/akis-clean-$name"
        & $docker cp $local "${containerName}:$containerPath"
        if ($LASTEXITCODE -ne 0) { throw "Could not copy $name." }
        & $docker exec $containerName psql -v ON_ERROR_STOP=1 -U $databaseUser -d $testDatabase -f $containerPath
        if ($LASTEXITCODE -ne 0) { throw "Definitions schema test failed in $name." }
    }


    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:$($settings['POSTGRES_PORT'])/$testDatabase"
    $env:SPRING_DATASOURCE_USERNAME = $databaseUser
    $env:SPRING_DATASOURCE_PASSWORD = $settings["POSTGRES_PASSWORD"]
    & $maven -pl backend "-Dtest=CleanDefinitionRepositoryIT" test
    if ($LASTEXITCODE -ne 0) { throw "Clean definition repository test failed." }
    Write-Output "Clean folder, definition, draft and version repository tests: PASS"
}
finally {
    if ($databaseCreated -and $testDatabase -match '^akis_definitions_test_[0-9]+$') {
        & $docker exec $containerName dropdb --if-exists --force -U $databaseUser $testDatabase | Out-Null
    }
}
