[CmdletBinding()]
param(
    [switch] $PrepareEnvironmentOnly
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot ".env"

if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing .env. Copy .env.example to .env and set local values."
}

Get-Content -LiteralPath $envFile |
    Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } |
    ForEach-Object {
        $name, $value = $_ -split '=', 2
        [Environment]::SetEnvironmentVariable($name, $value, "Process")
    }

function Set-OracleCredentialReference {
    param(
        [Parameter(Mandatory = $true)]
        [string] $ReferenceNameVariable,
        [Parameter(Mandatory = $true)]
        [string] $UsernameVariable,
        [Parameter(Mandatory = $true)]
        [string] $PasswordVariable
    )

    $referenceName = [Environment]::GetEnvironmentVariable($ReferenceNameVariable, "Process")
    if ([string]::IsNullOrWhiteSpace($referenceName)) {
        return
    }
    if ($referenceName -notmatch '^[A-Z][A-Z0-9_]*$') {
        throw "Invalid credential reference environment variable name in $ReferenceNameVariable."
    }

    $username = [Environment]::GetEnvironmentVariable($UsernameVariable, "Process")
    $password = [Environment]::GetEnvironmentVariable($PasswordVariable, "Process")
    if ([string]::IsNullOrWhiteSpace($username) -or [string]::IsNullOrWhiteSpace($password)) {
        throw "Missing local Oracle credential values required by $ReferenceNameVariable."
    }

    $credential = [ordered]@{
        username = $username
        password = $password
    } | ConvertTo-Json -Compress
    [Environment]::SetEnvironmentVariable($referenceName, $credential, "Process")
}

Set-OracleCredentialReference `
    -ReferenceNameVariable "AKIS_ORACLE_SOURCE_CREDENTIAL_REFERENCE" `
    -UsernameVariable "AKIS_ORACLE_SOURCE_USERNAME" `
    -PasswordVariable "AKIS_ORACLE_SOURCE_PASSWORD"
Set-OracleCredentialReference `
    -ReferenceNameVariable "AKIS_ORACLE_TARGET_CREDENTIAL_REFERENCE" `
    -UsernameVariable "AKIS_ORACLE_TARGET_USERNAME" `
    -PasswordVariable "AKIS_ORACLE_TARGET_PASSWORD"

if ($PrepareEnvironmentOnly) {
    return
}

Push-Location $projectRoot
try {
    & (Join-Path $projectRoot "mvnw.cmd") -pl backend spring-boot:run
}
finally {
    Pop-Location
}
