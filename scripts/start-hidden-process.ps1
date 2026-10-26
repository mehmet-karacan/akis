[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, ParameterSetName = "Role")]
    [ValidateSet("Backend", "Frontend")]
    [string] $Role,
    [Parameter(Mandatory = $true, ParameterSetName = "Role")]
    [string] $ProjectRoot,
    [Parameter(Mandatory = $true, ParameterSetName = "Command")]
    [string] $FilePath,
    [Parameter(ParameterSetName = "Command")]
    [string[]] $Arguments = @(),
    [Parameter(Mandatory = $true, ParameterSetName = "Command")]
    [string] $WorkingDirectory,
    [Parameter(Mandatory = $true, ParameterSetName = "Command")]
    [string] $StandardOutputPath,
    [Parameter(Mandatory = $true, ParameterSetName = "Command")]
    [string] $StandardErrorPath,
    [switch] $PassThru
)

$ErrorActionPreference = "Stop"
if ($PSCmdlet.ParameterSetName -eq "Role") {
    $ProjectRoot = [System.IO.Path]::GetFullPath($ProjectRoot)
    $logDirectory = Join-Path $ProjectRoot "logs\dev"
    $WorkingDirectory = if ($Role -eq "Frontend") { Join-Path $ProjectRoot "frontend" } else { $ProjectRoot }
    $StandardOutputPath = Join-Path $logDirectory "$($Role.ToLowerInvariant()).out.log"
    $StandardErrorPath = Join-Path $logDirectory "$($Role.ToLowerInvariant()).err.log"
    if ($Role -eq "Frontend") {
        $FilePath = $env:ComSpec
        $Arguments = @("/d", "/c", "call npm.cmd run dev")
    }
    else {
        $backendRunner = Join-Path $ProjectRoot "scripts\run-backend.ps1"
        $pwsh = Get-Command pwsh.exe -ErrorAction SilentlyContinue
        if ($pwsh) {
            $FilePath = $pwsh.Source
            $Arguments = @("-NoLogo", "-NoProfile", "-File", $backendRunner)
        }
        else {
            $escapedRunner = $backendRunner.Replace("'", "''")
            $escapedRoot = $ProjectRoot.Replace("'", "''")
            $command = "& ([ScriptBlock]::Create((Get-Content -Raw -LiteralPath '$escapedRunner'))) -ProjectRoot '$escapedRoot'"
            $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
            $FilePath = "powershell.exe"
            $Arguments = @("-NoLogo", "-NoProfile", "-EncodedCommand", $encoded)
        }
    }
}

$logDirectory = Split-Path -Parent $StandardOutputPath
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null

$process = Start-Process `
    -FilePath $FilePath `
    -ArgumentList $Arguments `
    -WorkingDirectory $WorkingDirectory `
    -WindowStyle Hidden `
    -RedirectStandardOutput $StandardOutputPath `
    -RedirectStandardError $StandardErrorPath `
    -PassThru

if ($PassThru) {
    return $process
}
