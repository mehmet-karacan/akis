[CmdletBinding()]
param(
    [switch]$EnableCopy
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot ".env"

if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing .env. Copy .env.example to .env and set local values."
}

$values = @{}
Get-Content -LiteralPath $envFile |
    Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } |
    ForEach-Object {
        $name, $value = $_ -split '=', 2
        $values[$name] = $value
        [Environment]::SetEnvironmentVariable($name, $value, "Process")
    }

if ($EnableCopy) {
    [Environment]::SetEnvironmentVariable(
        "AKIS_ORACLE_COPY_ENABLED",
        "true",
        "Process")
}

$required = @(
    "AKIS_ORACLE_EXPECTED_MAJOR",
    "AKIS_ORACLE_SOURCE_URL",
    "AKIS_ORACLE_SOURCE_USERNAME",
    "AKIS_ORACLE_SOURCE_PASSWORD",
    "AKIS_ORACLE_TARGET_URL",
    "AKIS_ORACLE_TARGET_USERNAME",
    "AKIS_ORACLE_TARGET_PASSWORD",
    "AKIS_ORACLE_CONNECT_TIMEOUT_MS",
    "AKIS_ORACLE_READ_TIMEOUT_MS",
    "AKIS_ORACLE_SOURCE_OWNER",
    "AKIS_ORACLE_SOURCE_TABLE",
    "AKIS_ORACLE_TARGET_OWNER",
    "AKIS_ORACLE_TARGET_TABLE",
    "AKIS_ORACLE_COPY_ENABLED",
    "AKIS_ORACLE_BATCH_SIZE"
)
$missing = $required | Where-Object {
    -not $values.ContainsKey($_) -or [string]::IsNullOrWhiteSpace($values[$_])
}
if ($missing) {
    throw "Fill these local-only .env values first: $($missing -join ', ')"
}

foreach ($role in @("SOURCE", "TARGET")) {
    $url = $values["AKIS_ORACLE_${role}_URL"]
    $match = [regex]::Match($url, '@(?://)?(?<host>[^:/?]+)(?::(?<port>\d+))?')
    if (-not $match.Success) {
        Write-Warning "Skipping $role TCP preflight because URL is not EZConnect format."
        continue
    }

    $hostName = $match.Groups["host"].Value
    $port = if ($match.Groups["port"].Success) {
        [int]$match.Groups["port"].Value
    }
    else {
        1521
    }

    $network = Test-NetConnection -ComputerName $hostName -Port $port -WarningAction SilentlyContinue
    if (-not $network.TcpTestSucceeded) {
        $endpoint = "{0}:{1}" -f $hostName, $port
        throw "Oracle $role endpoint $endpoint is unreachable. Connect the corporate VPN and run this script again."
    }
    Write-Output ("Oracle {0} network preflight: reachable ({1}:{2})" -f $role, $hostName, $port)
}

Push-Location $projectRoot
try {
    & (Join-Path $projectRoot "mvnw.cmd") -pl spikes/oracle-jdbc -am -DskipTests package
    if ($LASTEXITCODE -ne 0) {
        throw "Maven build failed. If the corporate Nexus host is unreachable, connect the VPN and retry."
    }

    & java -jar "spikes/oracle-jdbc/target/oracle-jdbc-spike-0.1.0-SNAPSHOT.jar"
    if ($LASTEXITCODE -ne 0) {
        throw "Oracle JDBC probe failed. Review the sanitized probe output above."
    }
}
finally {
    Pop-Location
}
