[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$baselineDirectory = $PSScriptRoot
$migration = Join-Path $baselineDirectory "V001__identity_rbac_project.sql"
$bootstrap = Join-Path $baselineDirectory "bootstrap-local-admin.sql"
$verification = Join-Path $baselineDirectory "verify-bootstrap.sql"
$docker = "C:\Program Files\Docker\Docker\resources\bin\docker.exe"
$containerName = "akis-metadata-db-1"
$testDatabase = "akis_bootstrap_test_" + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$databaseCreated = $false

if ($testDatabase -notmatch '^akis_bootstrap_test_[0-9]+$') {
    throw "Unsafe temporary database name."
}

$databaseUser = (& $docker exec $containerName sh -lc 'printf %s "$POSTGRES_USER"').Trim()
if ([string]::IsNullOrWhiteSpace($databaseUser)) {
    throw "The PostgreSQL container does not define POSTGRES_USER."
}

try {
    foreach ($file in @($migration, $bootstrap, $verification)) {
        & $docker cp $file "${containerName}:/tmp/$([IO.Path]::GetFileName($file))"
        if ($LASTEXITCODE -ne 0) { throw "Could not copy $file into the container." }
    }

    & $docker exec $containerName createdb -U $databaseUser $testDatabase
    if ($LASTEXITCODE -ne 0) { throw "Could not create the bootstrap test database." }
    $databaseCreated = $true

    & $docker exec $containerName psql -X -v ON_ERROR_STOP=1 -U $databaseUser -d $testDatabase `
        -f /tmp/V001__identity_rbac_project.sql
    if ($LASTEXITCODE -ne 0) { throw "Clean identity migration failed." }

    foreach ($attempt in 1..2) {
        & $docker exec $containerName psql -X -v ON_ERROR_STOP=1 -U $databaseUser -d $testDatabase `
            -v local_user=bootstrap-admin -v "display_name=Bootstrap Administrator" `
            -v email=bootstrap@example.invalid -f /tmp/bootstrap-local-admin.sql
        if ($LASTEXITCODE -ne 0) { throw "Local administrator bootstrap attempt $attempt failed." }
    }

    & $docker exec $containerName psql -X -v ON_ERROR_STOP=1 -U $databaseUser -d $testDatabase `
        -f /tmp/verify-bootstrap.sql
    if ($LASTEXITCODE -ne 0) { throw "Local administrator bootstrap verification failed." }
}
finally {
    if ($databaseCreated -and $testDatabase -match '^akis_bootstrap_test_[0-9]+$') {
        & $docker exec $containerName dropdb --if-exists -U $databaseUser $testDatabase | Out-Null
    }
}
