[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$launcher = Join-Path $PSScriptRoot "start-dev.cmd"
$portWaiter = Join-Path $PSScriptRoot "wait-for-port.ps1"

if (-not (Test-Path -LiteralPath $portWaiter)) {
    throw "Port waiter is missing."
}

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$listener.Start()
try {
    $openPort = ([System.Net.IPEndPoint] $listener.LocalEndpoint).Port
    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    & $portWaiter -Port $openPort -TimeoutSeconds 2
    $stopwatch.Stop()
    if ($stopwatch.Elapsed.TotalSeconds -ge 2) {
        throw "Port waiter did not return promptly for an open listener."
    }
}
finally {
    $listener.Stop()
}

$output = & $env:ComSpec /d /c "`"$launcher`" -check" 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "start-dev.cmd -check failed with exit code $LASTEXITCODE.`n$($output -join [Environment]::NewLine)"
}
if (($output -join [Environment]::NewLine) -notmatch "Kontrol basarili") {
    throw "start-dev.cmd -check did not report a successful validation."
}
if (-not (Test-Path -LiteralPath (Join-Path $projectRoot "scripts\run-backend.ps1"))) {
    throw "Backend launcher is missing."
}

$backendRunner = Join-Path $projectRoot "scripts\run-backend.ps1"
$escapedRunner = $backendRunner.Replace("'", "''")
$escapedRoot = $projectRoot.Replace("'", "''")
$fallbackCommand = "& ([ScriptBlock]::Create((Get-Content -Raw -LiteralPath '$escapedRunner'))) -PrepareEnvironmentOnly -ProjectRoot '$escapedRoot'"
$fallbackOutput = & powershell.exe -NoLogo -NoProfile -Command $fallbackCommand 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "Windows PowerShell fallback failed with exit code $LASTEXITCODE.`n$($fallbackOutput -join [Environment]::NewLine)"
}

$hiddenRunner = Join-Path $projectRoot "scripts\start-hidden-process.ps1"
$probeDirectory = Join-Path $projectRoot "logs\dev"
New-Item -ItemType Directory -Path $probeDirectory -Force | Out-Null
$probe = & $hiddenRunner `
    -FilePath (Get-Command ping.exe).Source `
    -Arguments @("127.0.0.1", "-n", "4") `
    -WorkingDirectory $projectRoot `
    -StandardOutputPath (Join-Path $probeDirectory "hidden-probe.out.log") `
    -StandardErrorPath (Join-Path $probeDirectory "hidden-probe.err.log") `
    -PassThru
try {
    Start-Sleep -Milliseconds 250
    $probe.Refresh()
    if ($probe.HasExited) { throw "Hidden process probe exited before its window state could be checked." }
    if ($probe.MainWindowHandle -ne 0) { throw "Background process opened a visible window." }
}
finally {
    if ($probe -and -not $probe.HasExited) { Stop-Process -Id $probe.Id -Force }
}

Write-Host "start-dev.cmd validation contract passed."
