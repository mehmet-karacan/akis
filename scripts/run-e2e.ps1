[CmdletBinding()]
param(
    [switch] $Headed,
    [switch] $Ui,
    [ValidateRange(0, 5000)]
    [int] $SlowMoMs = 0
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$frontendRoot = Join-Path $projectRoot "frontend"
$envFile = Join-Path $projectRoot ".env"

if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing .env. Copy .env.example to .env and set local values."
}

$settings = @{}
Get-Content -LiteralPath $envFile |
    Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } |
    ForEach-Object {
        $name, $value = $_ -split '=', 2
        $settings[$name] = $value.Trim('"')
    }

$required = @("AKIS_DEV_USERNAME", "AKIS_DEV_PASSWORD")
foreach ($name in $required) {
    if ([string]::IsNullOrWhiteSpace($settings[$name])) {
        throw "Missing $name in .env."
    }
}

$baseUrl = if ($env:AKIS_E2E_BASE_URL) { $env:AKIS_E2E_BASE_URL } else { "http://127.0.0.1:5173" }
foreach ($healthUrl in @("http://127.0.0.1:8080/actuator/health", $baseUrl)) {
    try {
        $response = Invoke-WebRequest -Uri $healthUrl -Method Get -SkipHttpErrorCheck -TimeoutSec 5
        if ([int] $response.StatusCode -ge 400) { throw "HTTP $([int] $response.StatusCode)" }
    }
    catch {
        throw "AKIS local service is not ready at $healthUrl. Start the local backend and frontend first."
    }
}

$previousUsername = $env:AKIS_E2E_USERNAME
$previousPassword = $env:AKIS_E2E_PASSWORD
$previousSlowMo = $env:AKIS_E2E_SLOW_MO
try {
    $env:AKIS_E2E_USERNAME = $settings["AKIS_DEV_USERNAME"]
    $env:AKIS_E2E_PASSWORD = $settings["AKIS_DEV_PASSWORD"]
    $env:AKIS_E2E_BASE_URL = $baseUrl
    $env:AKIS_E2E_SLOW_MO = if ($SlowMoMs -gt 0) { [string] $SlowMoMs } elseif ($Headed) { "300" } else { "0" }
    Push-Location $frontendRoot
    try {
        $arguments = @("playwright", "test")
        if ($Headed) { $arguments += "--headed" }
        if ($Ui) { $arguments += "--ui" }
        & npx @arguments
        if ($LASTEXITCODE -ne 0) { throw "Playwright E2E tests failed with exit code $LASTEXITCODE." }
    }
    finally { Pop-Location }
}
finally {
    $env:AKIS_E2E_USERNAME = $previousUsername
    $env:AKIS_E2E_PASSWORD = $previousPassword
    $env:AKIS_E2E_SLOW_MO = $previousSlowMo
}
