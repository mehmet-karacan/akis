[CmdletBinding()]
param(
    [ValidateSet('ProjectBundleRoundTripIT', 'PendingRecipeImportWriterIT', 'CleanProjectBundleRepositoryIT', 'ExportMaintenanceIT', 'RunExportPostgresScaleIT', 'PostgresDiscoveryIntegrationTest')]
    [string]$TestName = 'ProjectBundleRoundTripIT',
    [ValidateRange(1, 1000000)]
    [int]$RunCount = 100000,
    [ValidateRange(64, 4096)]
    [int]$MaxHeapMb = 128
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root '.env'
$docker = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
$maven = Join-Path $root 'mvnw.cmd'
if (-not (Test-Path -LiteralPath $envFile)) { throw 'Local .env is required.' }

$settings = @{}
Get-Content -LiteralPath $envFile |
    Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } |
    ForEach-Object { $pair = $_ -split '=', 2; $settings[$pair[0]] = $pair[1] }
$container = (& $docker compose --env-file $envFile ps -q metadata-db).Trim()
if (-not $container) { throw 'metadata-db container is not running.' }
$database = 'akis_bundle_test_' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
if ($database -notmatch '^akis_bundle_test_[0-9]+$') { throw 'Invalid generated test database name.' }
$created = $false

try {
    & $docker exec $container createdb -U $settings['POSTGRES_USER'] $database
    if ($LASTEXITCODE -ne 0) { throw 'Test database creation failed.' }
    $created = $true
    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:$($settings['POSTGRES_PORT'])/$database"
    $env:SPRING_DATASOURCE_USERNAME = $settings['POSTGRES_USER']
    $env:SPRING_DATASOURCE_PASSWORD = $settings['POSTGRES_PASSWORD']
    Push-Location $root
    try {
        $mavenArgs = @('-q', '-pl', 'backend', "-Dtest=$TestName")
        if ($TestName -eq 'RunExportPostgresScaleIT') {
            $mavenArgs += '-Dakis.export.pgscale=true'
            $mavenArgs += "-Dakis.export.pgscale.runs=$RunCount"
            $mavenArgs += "-DargLine=-Xmx$($MaxHeapMb)m"
        }
        $mavenArgs += 'test'
        & $maven @mavenArgs
        if ($LASTEXITCODE -ne 0) { throw 'Bundle round-trip test failed.' }
    }
    finally { Pop-Location }
    Write-Output "Fresh migration and ${TestName}: PASS"
}
finally {
    if ($created -and $database -match '^akis_bundle_test_[0-9]+$') {
        & $docker exec $container dropdb --if-exists --force -U $settings['POSTGRES_USER'] $database | Out-Null
    }
}
