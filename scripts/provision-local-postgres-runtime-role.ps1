[CmdletBinding()]
param(
    [ValidatePattern('^[a-z][a-z0-9_]{2,62}$')]
    [string] $RoleName = "akis_runtime",
    [switch] $Apply,
    [switch] $RotatePassword
)

$ErrorActionPreference = "Stop"

if (-not $Apply) {
    Write-Output "Dry run. Re-run with -Apply to create or rotate the local runtime role."
    return
}

$projectRoot = if ([string]::IsNullOrWhiteSpace($PSScriptRoot)) {
    (Get-Location).Path
}
else {
    Split-Path -Parent $PSScriptRoot
}
$envFile = Join-Path $projectRoot ".env"
if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing .env."
}

$settings = @{}
Get-Content -LiteralPath $envFile |
    Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } |
    ForEach-Object {
        $name, $value = $_ -split '=', 2
        $settings[$name] = $value
    }

$bootstrapUser = $settings['POSTGRES_USER']
$databaseName = $settings['POSTGRES_DB']
if ([string]::IsNullOrWhiteSpace($bootstrapUser) -or [string]::IsNullOrWhiteSpace($databaseName)) {
    throw "POSTGRES_USER and POSTGRES_DB must be configured."
}

$passwordBytes = [byte[]]::new(32)
[System.Security.Cryptography.RandomNumberGenerator]::Fill($passwordBytes)
$runtimePassword = [Convert]::ToBase64String($passwordBytes)
$passwordLiteral = $runtimePassword.Replace("'", "''")

$roleExists = docker compose exec -T metadata-db psql -U $bootstrapUser -d $databaseName -Atqc "select exists(select 1 from pg_roles where rolname = '$RoleName')"
$roleAlreadyExists = $roleExists.Trim() -eq "t"
if ($roleAlreadyExists -and -not $RotatePassword) {
    throw "Role '$RoleName' already exists. Re-run with -RotatePassword only when a credential rotation is intended."
}

$roleStatement = if ($roleAlreadyExists) {
    "ALTER ROLE $RoleName LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '$passwordLiteral';"
}
else {
    "CREATE ROLE $RoleName LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '$passwordLiteral';"
}

$sql = @"
$roleStatement
GRANT CONNECT ON DATABASE $databaseName TO $RoleName;
GRANT USAGE ON SCHEMA akis TO $RoleName;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA akis TO $RoleName;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA akis TO $RoleName;
ALTER DEFAULT PRIVILEGES FOR ROLE $bootstrapUser IN SCHEMA akis GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO $RoleName;
ALTER DEFAULT PRIVILEGES FOR ROLE $bootstrapUser IN SCHEMA akis GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO $RoleName;
"@

$sql | docker compose exec -T metadata-db psql -v ON_ERROR_STOP=1 -U $bootstrapUser -d $databaseName | Out-Null

$updated = Get-Content -LiteralPath $envFile | ForEach-Object {
    if ($_ -match '^AKIS_DB_USERNAME=') { "AKIS_DB_USERNAME=$RoleName" }
    elseif ($_ -match '^AKIS_DB_PASSWORD=') { "AKIS_DB_PASSWORD=$runtimePassword" }
    else { $_ }
}
[System.IO.File]::WriteAllLines($envFile, [string[]] $updated, [System.Text.UTF8Encoding]::new($false))

$env:PGPASSWORD = $runtimePassword
try {
    $verifiedRole = docker compose exec -T -e "PGPASSWORD=$runtimePassword" metadata-db psql -h 127.0.0.1 -U $RoleName -d $databaseName -Atqc "select current_user"
}
finally {
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
}

if ($verifiedRole.Trim() -ne $RoleName) {
    throw "Runtime role login verification failed."
}

Write-Output "Local runtime PostgreSQL role '$RoleName' is configured and verified."
