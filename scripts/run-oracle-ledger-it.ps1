[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot ".env"
$maven = Join-Path $projectRoot "mvnw.cmd"

if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing .env. Copy .env.example to .env and set local Oracle values."
}

$values = @{}
Get-Content -LiteralPath $envFile |
    Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } |
    ForEach-Object {
        $name, $value = $_ -split '=', 2
        $values[$name] = $value
    }

$required = @(
    "AKIS_ORACLE_TARGET_URL",
    "AKIS_ORACLE_TARGET_USERNAME",
    "AKIS_ORACLE_TARGET_PASSWORD",
    "AKIS_ORACLE_TARGET_OWNER"
)
$missing = $required | Where-Object {
    -not $values.ContainsKey($_) -or [string]::IsNullOrWhiteSpace($values[$_])
}
if ($missing) {
    throw "Fill these local-only .env values first: $($missing -join ', ')"
}

$previous = @{}
try {
    foreach ($name in $required) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
        [Environment]::SetEnvironmentVariable($name, $values[$name], "Process")
    }
    Push-Location $projectRoot
    try {
        & $maven -pl backend "-Dtest=OracleTargetLedgerIT" test
        if ($LASTEXITCODE -ne 0) {
            throw "Oracle target ledger integration test failed."
        }
    }
    finally {
        Pop-Location
    }
}
finally {
    foreach ($name in $required) {
        [Environment]::SetEnvironmentVariable($name, $previous[$name], "Process")
    }
}

Write-Output "Oracle target ledger integration test: SUCCESS"
