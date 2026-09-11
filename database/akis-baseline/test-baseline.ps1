[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$baselineDirectory = $PSScriptRoot
$migration = Join-Path $baselineDirectory "V001__identity_rbac_project.sql"
$verification = Join-Path $baselineDirectory "verify.sql"
$docker = "C:\Program Files\Docker\Docker\resources\bin\docker.exe"
$containerName = "akis-metadata-db-1"
$testDatabase = "akis_baseline_test_" + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$containerMigration = "/tmp/akis-baseline-v001.sql"
$containerVerification = "/tmp/akis-baseline-verify.sql"
$databaseCreated = $false

if (-not (Test-Path -LiteralPath $migration)) {
    throw "Missing clean baseline migration."
}
if (-not (Test-Path -LiteralPath $verification)) {
    throw "Missing clean baseline verification script."
}
if ($testDatabase -notmatch '^akis_baseline_test_[0-9]+$') {
    throw "Unsafe temporary database name."
}

$running = (& $docker inspect --format '{{.State.Running}}' $containerName 2>$null).Trim()
if ($running -ne "true") {
    throw "Local PostgreSQL container '$containerName' is not running."
}

$databaseUser = (& $docker exec $containerName sh -lc 'printf %s "$POSTGRES_USER"').Trim()
if ([string]::IsNullOrWhiteSpace($databaseUser)) {
    throw "The PostgreSQL container does not define POSTGRES_USER."
}

try {
    & $docker cp $migration "${containerName}:$containerMigration"
    if ($LASTEXITCODE -ne 0) {
        throw "Could not copy the baseline migration into the container."
    }

    & $docker cp $verification "${containerName}:$containerVerification"
    if ($LASTEXITCODE -ne 0) {
        throw "Could not copy the verification script into the container."
    }

    & $docker exec $containerName createdb -U $databaseUser $testDatabase
    if ($LASTEXITCODE -ne 0) {
        throw "Could not create the temporary baseline database."
    }
    $databaseCreated = $true

    & $docker exec $containerName psql -v ON_ERROR_STOP=1 -U $databaseUser -d $testDatabase -f $containerMigration
    if ($LASTEXITCODE -ne 0) {
        throw "Clean baseline migration failed."
    }

    & $docker exec $containerName psql -v ON_ERROR_STOP=1 -U $databaseUser -d $testDatabase -f $containerVerification
    if ($LASTEXITCODE -ne 0) {
        throw "Clean baseline verification failed."
    }
}
finally {
    if ($databaseCreated -and $testDatabase -match '^akis_baseline_test_[0-9]+$') {
        & $docker exec $containerName dropdb --if-exists -U $databaseUser $testDatabase | Out-Null
    }
}
