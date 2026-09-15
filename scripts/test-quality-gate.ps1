[CmdletBinding()]
param(
    # Requires an already running frontend. Uses mocked API, not Oracle or backend acceptance.
    [switch] $SqlBrowserComponent
)

$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskStarted = Get-Date
$taskResults = [System.Collections.Generic.List[object]]::new()

function Invoke-QualityCommand {
    param([string] $Name, [string] $Directory, [string] $Command, [string[]] $Arguments)
    Write-Host "Running: $Name"
    Push-Location $Directory
    try {
        & $Command @Arguments
        if ($LASTEXITCODE -ne 0) { throw "$Name failed (exit $LASTEXITCODE)." }
        $taskResults.Add([pscustomobject]@{ check = $Name; status = 'passed' })
    }
    catch {
        $taskResults.Add([pscustomobject]@{ check = $Name; status = 'failed' })
        throw
    }
    finally { Pop-Location }
}

try {
    Invoke-QualityCommand 'Backend unit tests' $taskRoot (Join-Path $taskRoot 'mvnw.cmd') @('-pl', 'backend', 'test', '-q')
    # Surefire directories may contain old integration reports. Never count them as current evidence.
    $taskReports = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'backend/target/surefire-reports') -Filter 'TEST-*Test.xml' |
        Where-Object { $_.LastWriteTime -ge $taskStarted.AddSeconds(-2) })
    if ($taskReports.Count -eq 0) { throw 'No fresh backend unit test reports were produced.' }
    $taskTests = 0
    foreach ($taskReport in $taskReports) {
        [xml] $taskXml = Get-Content -LiteralPath $taskReport.FullName -Raw
        $taskSuite = $taskXml.testsuite
        if ([int] $taskSuite.failures -gt 0 -or [int] $taskSuite.errors -gt 0 -or [int] $taskSuite.skipped -gt 0) {
            throw "Backend report includes failures, errors or skipped tests: $($taskReport.Name)"
        }
        $taskTests += [int] $taskSuite.tests
    }
    if ($taskTests -eq 0) { throw 'Backend test count is zero.' }
    Write-Host "Fresh backend evidence: $taskTests tests in $($taskReports.Count) classes. Integration tests excluded."

    $taskFrontend = Join-Path $taskRoot 'frontend'
    Invoke-QualityCommand 'Frontend unit tests' $taskFrontend 'npm.cmd' @('test', '--', '--reporter=dot')
    Invoke-QualityCommand 'Frontend lint' $taskFrontend 'npm.cmd' @('run', 'lint')
    Invoke-QualityCommand 'Frontend production build' $taskFrontend 'npm.cmd' @('run', 'build')
    if ($SqlBrowserComponent) {
        Invoke-QualityCommand 'SQL browser component (mocked API)' $taskFrontend 'npx.cmd' @('playwright', 'test', 'e2e/sql-policy.component.spec.ts')
    }
    else {
        $taskResults.Add([pscustomobject]@{ check = 'SQL browser component'; status = 'not_run' })
    }
    $taskResults.Add([pscustomobject]@{ check = 'PostgreSQL integration, isolated Oracle fault injection, full application E2E'; status = 'not_run' })
    Write-Host 'Local quality checks passed. This is NOT full release acceptance.'
}
finally {
    $taskResults | Format-Table -AutoSize | Out-Host
}
