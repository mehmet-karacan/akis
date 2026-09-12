[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot ".env"
$maven = Join-Path $projectRoot "mvnw.cmd"
$dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
$docker = if ($dockerCommand) { $dockerCommand.Source } else { "C:\Program Files\Docker\Docker\resources\bin\docker.exe" }
if (-not (Test-Path -LiteralPath $envFile)) { throw "Missing .env." }
if (-not (Test-Path -LiteralPath $docker)) { throw "Docker CLI was not found." }
$settings = @{}
Get-Content -LiteralPath $envFile | Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } | ForEach-Object { $name, $value = $_ -split '=', 2; $settings[$name] = $value }
$container = (& $docker compose --env-file $envFile ps -q metadata-db).Trim()
if ([string]::IsNullOrWhiteSpace($container)) { throw "metadata-db container is not running. Run scripts\dev-up.ps1 first." }

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$listener.Start(); $port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port; $listener.Stop()
$databaseUser = $settings["POSTGRES_USER"]
$testDatabase = "akis_api_test_" + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$logDirectory = Join-Path ([System.IO.Path]::GetTempPath()) $testDatabase
$stdoutLog = Join-Path $logDirectory "backend.out.log"
$stderrLog = Join-Path $logDirectory "backend.err.log"
$appProcess = $null
$previousEnvironment = @{}

function Invoke-AkisJson {
    param([Parameter(Mandatory)] [string] $Method, [Parameter(Mandatory)] [string] $Path, [object] $Body)
    $parameters = @{ Method = $Method; Uri = "http://127.0.0.1:$port$Path"; Headers = @{ Authorization = $script:authorizationHeader } }
    if ($null -ne $Body) { $parameters.ContentType = "application/json"; $parameters.Body = $Body | ConvertTo-Json -Depth 20 -Compress }
    Invoke-RestMethod @parameters
}

try {
    New-Item -ItemType Directory -Path $logDirectory | Out-Null
    & $docker exec $container createdb -U $databaseUser $testDatabase
    if ($LASTEXITCODE -ne 0) { throw "Could not create temporary API test database." }
    & $maven -q -pl backend -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw "Backend package failed." }
    $jar = Get-ChildItem (Join-Path $PSScriptRoot "target") -Filter "akis-backend-*.jar" | Where-Object { $_.Name -notlike "*.original" } | Select-Object -First 1
    if (-not $jar) { throw "Backend jar was not produced." }

    foreach ($name in @("AKIS_DB_URL", "AKIS_DB_USERNAME", "AKIS_DB_PASSWORD", "AKIS_SERVER_PORT", "AKIS_SECURITY_MODE", "AKIS_DEV_USERNAME", "AKIS_DEV_PASSWORD")) { $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, "Process") }
    $env:AKIS_DB_URL = "jdbc:postgresql://127.0.0.1:$($settings['POSTGRES_PORT'])/$testDatabase"
    $env:AKIS_DB_USERNAME = $databaseUser; $env:AKIS_DB_PASSWORD = $settings["POSTGRES_PASSWORD"]; $env:AKIS_SERVER_PORT = $port
    $env:AKIS_SECURITY_MODE = "development"; $env:AKIS_DEV_USERNAME = "api-test"; $env:AKIS_DEV_PASSWORD = [Guid]::NewGuid().ToString("N")
    $credentialBytes = [Text.Encoding]::UTF8.GetBytes("$($env:AKIS_DEV_USERNAME):$($env:AKIS_DEV_PASSWORD)")
    $script:authorizationHeader = "Basic " + [Convert]::ToBase64String($credentialBytes)
    $appProcess = Start-Process java -ArgumentList "-jar", $jar.FullName -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdoutLog -RedirectStandardError $stderrLog

    $healthy = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($appProcess.HasExited) { throw "Backend stopped before becoming healthy." }
        try { if ((Invoke-RestMethod "http://127.0.0.1:$port/actuator/health").status -eq "UP") { $healthy = $true; break } } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $healthy) { throw "Backend health check timed out." }

    try { Invoke-RestMethod "http://127.0.0.1:$port/api/v1/projects" | Out-Null; throw "Anonymous API request was accepted." } catch { if ($_.Exception.Response.StatusCode.value__ -ne 401) { throw } }
    $types = Invoke-AkisJson GET "/api/v1/definition-types"
    if ($types.Count -ne 9) { throw "Definition type contract is incomplete." }
    $project = Invoke-AkisJson POST "/api/v1/projects" @{ code = "API_TEST"; name = "API Test Project" }
    if ($project.PSObject.Properties.Name -contains "id") { throw "Internal database id leaked through the project API." }
    $access = Invoke-AkisJson GET "/api/v1/projects/$($project.uuid)/access"
    if ($access.roles -notcontains "GELISTIRICI" -or $access.permissions -notcontains "TANIM_DUZENLE") { throw "Role-aware project access projection failed." }
    $folder = Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/folders" @{ code = "FINANCE"; name = "Finance" }
    $definition = Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/definitions" @{ folderUuid = $folder.uuid; type = "PROCEDURE"; code = "LOAD_DAILY"; name = "Load Daily" }
    $saved = Invoke-AkisJson PUT "/api/v1/projects/$($project.uuid)/definitions/$($definition.uuid)/draft" @{ expectedVersion = 0; schemaVersion = 2; content = @{ tasks = @(@{ id = "READ_SOURCE"; name = "Read source"; type = "SQL"; connectionRole = "SOURCE"; riskClass = "READ_ONLY"; command = "select 1 from dual"; onError = "STOP" }) } }
    if ($saved.version -ne 1) { throw "Initial optimistic draft creation failed." }
    $draft = Invoke-AkisJson GET "/api/v1/projects/$($project.uuid)/definitions/$($definition.uuid)/draft"
    if ($draft.uuid -ne $saved.uuid) { throw "Saved draft could not be read back." }
    $schemaCount = (& $docker exec $container psql -X -U $databaseUser -d $testDatabase -Atc "select count(*) from information_schema.tables where table_schema = 'akis'").Trim()
    if ([int]$schemaCount -lt 1) { throw "The current akis schema was not created." }
    Write-Output "Backend API test: PASS (authentication, akis schema, role profile, folders, definitions, optimistic drafts)"
}
catch {
    if (Test-Path -LiteralPath $stdoutLog) { Get-Content -LiteralPath $stdoutLog -Tail 200 }
    if (Test-Path -LiteralPath $stderrLog) { Get-Content -LiteralPath $stderrLog -Tail 200 }
    throw
}
finally {
    if ($appProcess -and -not $appProcess.HasExited) { Stop-Process -Id $appProcess.Id -Force; $appProcess.WaitForExit() }
    foreach ($entry in $previousEnvironment.GetEnumerator()) { [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process") }
    & $docker exec $container dropdb --if-exists --force -U $databaseUser $testDatabase | Out-Null
    $resolvedTemp = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    $resolvedLogs = [System.IO.Path]::GetFullPath($logDirectory)
    if ((Test-Path -LiteralPath $resolvedLogs) -and $resolvedLogs.StartsWith($resolvedTemp, [StringComparison]::OrdinalIgnoreCase) -and (Split-Path -Leaf $resolvedLogs) -eq $testDatabase) { Remove-Item -LiteralPath $resolvedLogs -Recurse -Force }
}
