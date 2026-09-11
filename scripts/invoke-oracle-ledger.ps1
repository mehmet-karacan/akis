[CmdletBinding()]
param(
    [ValidateSet("Validate", "Install", "NegativeTest")]
    [string]$Mode = "Validate"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot ".env"
$oracleScriptRoot = Join-Path $projectRoot "database\oracle"

if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing .env. Copy .env.example to .env and set local values."
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

$username = $values["AKIS_ORACLE_TARGET_USERNAME"]
$owner = $values["AKIS_ORACLE_TARGET_OWNER"]
if (-not $username.Equals($owner, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Ledger installation requires the configured target username to match the target owner."
}

$url = $values["AKIS_ORACLE_TARGET_URL"]
$urlMatch = [regex]::Match($url, '^jdbc:oracle:thin:@(?<connect>//[^\s]+)$')
if (-not $urlMatch.Success) {
    throw "AKIS_ORACLE_TARGET_URL must use the jdbc:oracle:thin:@//host:port/service EZConnect form."
}
$connectIdentifier = $urlMatch.Groups["connect"].Value

$endpointMatch = [regex]::Match(
    $connectIdentifier,
    '^//(?<host>[^:/]+):(?<port>\d+)/[^\s]+$')
if (-not $endpointMatch.Success) {
    throw "The target EZConnect value must include host, port and service name."
}

$hostName = $endpointMatch.Groups["host"].Value
$port = [int]$endpointMatch.Groups["port"].Value
$network = Test-NetConnection `
    -ComputerName $hostName `
    -Port $port `
    -WarningAction SilentlyContinue
if (-not $network.TcpTestSucceeded) {
    throw "Oracle target is unreachable. Connect the corporate VPN and run this script again."
}

$sqlcl = Get-Command sql -ErrorAction SilentlyContinue
if (-not $sqlcl) {
    throw "Oracle SQLcl executable 'sql' was not found on PATH."
}

$scriptName = switch ($Mode) {
    "Install" { "install.sql" }
    "NegativeTest" { "negative-test.sql" }
    default { "validate.sql" }
}

Write-Output "Oracle ledger $Mode preflight: target reachable; starting SQLcl."
Push-Location $oracleScriptRoot
try {
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $sqlcl.Source
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.WorkingDirectory = $oracleScriptRoot
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    foreach ($argument in @(
        "-S", "-L", "-nohistory", "-noupdates",
        "$username@$connectIdentifier")) {
        [void]$startInfo.ArgumentList.Add($argument)
    }

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    [void]$process.Start()
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $process.StandardInput.WriteLine($values["AKIS_ORACLE_TARGET_PASSWORD"])
    $process.StandardInput.WriteLine("@$scriptName")
    $process.StandardInput.WriteLine("EXIT")
    $process.StandardInput.Flush()
    $process.StandardInput.Close()
    $process.WaitForExit()

    $stdout = $stdoutTask.GetAwaiter().GetResult()
    $stderr = $stderrTask.GetAwaiter().GetResult()
    $cleanStdout = (($stdout -split '[\r\n]+') |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) `
        -join [Environment]::NewLine
    $cleanStderr = (($stderr -split '[\r\n]+') |
        Where-Object {
            $_ -notmatch '^Exception in thread "JLine Mask Thread"' -and
            $_ -notmatch '^\s+at org\.jline\.' -and
            -not [string]::IsNullOrWhiteSpace($_)
        }) -join [Environment]::NewLine
    if (-not [string]::IsNullOrWhiteSpace($cleanStdout)) {
        Write-Output $cleanStdout
    }
    if (-not [string]::IsNullOrWhiteSpace($cleanStderr)) {
        Write-Warning $cleanStderr
    }
    if ($process.ExitCode -ne 0 -or $cleanStdout -match '(?m)^SP2-') {
        throw "Oracle ledger $Mode failed with SQLcl exit code $($process.ExitCode)."
    }
}
finally {
    Pop-Location
    Remove-Variable process, startInfo, stdoutTask, stderrTask `
        -ErrorAction SilentlyContinue
}

Write-Output "Oracle ledger $Mode`: SUCCESS"
