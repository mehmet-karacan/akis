[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot ".env"
$maven = Join-Path $projectRoot "mvnw.cmd"
$dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
$docker = if ($dockerCommand) {
    $dockerCommand.Source
}
else {
    "C:\Program Files\Docker\Docker\resources\bin\docker.exe"
}

if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing .env."
}
if (-not (Test-Path -LiteralPath $docker)) {
    throw "Docker CLI was not found."
}

$settings = @{}
Get-Content -LiteralPath $envFile |
    Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } |
    ForEach-Object {
        $name, $value = $_ -split '=', 2
        $settings[$name] = $value
    }

$container = (& $docker compose --env-file $envFile ps -q metadata-db).Trim()
if ([string]::IsNullOrWhiteSpace($container)) {
    throw "metadata-db container is not running. Run scripts\dev-up.ps1 first."
}

$listener = [System.Net.Sockets.TcpListener]::new(
    [System.Net.IPAddress]::Loopback, 0)
$listener.Start()
$port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
$listener.Stop()

$databaseUser = $settings["POSTGRES_USER"]
$testDatabase = "akis_api_test_" + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$appProcess = $null
$logDirectory = Join-Path ([System.IO.Path]::GetTempPath()) $testDatabase
$stdoutLog = Join-Path $logDirectory "backend.out.log"
$stderrLog = Join-Path $logDirectory "backend.err.log"

function Invoke-AkisJson {
    param(
        [Parameter(Mandatory)] [string] $Method,
        [Parameter(Mandatory)] [string] $Path,
        [object] $Body
    )

    $parameters = @{
        Method = $Method
        Uri = "http://127.0.0.1:$port$Path"
    }
    if ($null -ne $Body) {
        $parameters["ContentType"] = "application/json"
        $parameters["Body"] = $Body | ConvertTo-Json -Depth 20 -Compress
    }
    Invoke-RestMethod @parameters
}

try {
    New-Item -ItemType Directory -Path $logDirectory | Out-Null
    & $docker exec $container createdb -U $databaseUser $testDatabase
    if ($LASTEXITCODE -ne 0) {
        throw "Could not create temporary API test database."
    }

    & $maven -q -pl backend -DskipTests package
    if ($LASTEXITCODE -ne 0) {
        throw "Backend package failed."
    }

    $jar = Get-ChildItem (Join-Path $PSScriptRoot "target") -Filter "akis-backend-*.jar" |
        Where-Object { $_.Name -notlike "*.original" } |
        Select-Object -First 1
    if (-not $jar) {
        throw "Backend jar was not produced."
    }

    $previousEnvironment = @{
        AKIS_DB_URL = $env:AKIS_DB_URL
        AKIS_DB_USERNAME = $env:AKIS_DB_USERNAME
        AKIS_DB_PASSWORD = $env:AKIS_DB_PASSWORD
        AKIS_SERVER_PORT = $env:AKIS_SERVER_PORT
    }
    $env:AKIS_DB_URL = "jdbc:postgresql://127.0.0.1:$($settings['POSTGRES_PORT'])/$testDatabase"
    $env:AKIS_DB_USERNAME = $databaseUser
    $env:AKIS_DB_PASSWORD = $settings["POSTGRES_PASSWORD"]
    $env:AKIS_SERVER_PORT = $port

    $appProcess = Start-Process java -ArgumentList "-jar", $jar.FullName `
        -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $stdoutLog -RedirectStandardError $stderrLog

    $healthy = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($appProcess.HasExited) {
            throw "Backend stopped before becoming healthy."
        }
        try {
            $health = Invoke-RestMethod "http://127.0.0.1:$port/actuator/health"
            if ($health.status -eq "UP") {
                $healthy = $true
                break
            }
        }
        catch {
            Start-Sleep -Milliseconds 500
        }
    }
    if (-not $healthy) {
        throw "Backend health check timed out."
    }

    $types = Invoke-AkisJson GET "/api/v1/definition-types"
    if ($types.Count -ne 9) {
        throw "Expected 9 definition types, found $($types.Count)."
    }

    $project = Invoke-AkisJson POST "/api/v1/projects" @{
        code = "API_TEST"
        name = "API Test Project"
    }
    if ($project.PSObject.Properties.Name -contains "id") {
        throw "Internal database id leaked through the project API."
    }
    $folder = Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/folders" @{
        code = "DEVELOPMENT"
        name = "Development"
        type = "GELISTIRME"
    }

    $secretReference = Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/secret-references" @{
        code = "TEST_ORACLE_CREDENTIAL"
        referencePath = "AKIS_TEST_ORACLE_CREDENTIAL"
        provider = "ENV"
        name = "Test Oracle credential reference"
    }
    $connection = Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/connections" @{
        code = "TEST_ORACLE"
        databaseType = "ORACLE"
        name = "Test Oracle"
    }
    $connectionVersion = Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/connections/$($connection.uuid)/versions" @{
        driverReference = "oracle.jdbc.OracleDriver"
        host = "db-host.invalid"
        sid = "TESTDB"
        port = 1521
        tlsMode = "DISABLED"
        policy = @{ connectTimeoutMs = 10000 }
        secretReferenceUuid = $secretReference.uuid
        secretRole = "KIMLIK"
    }
    if ($connectionVersion.versionNumber -ne 1) {
        throw "First connection version number is invalid."
    }
    if ($connectionVersion.PSObject.Properties.Name -contains "secretValue") {
        throw "Secret value leaked through the connection version API."
    }

    $physicalSchema = Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/physical-schemas" @{
        connectionUuid = $connection.uuid
        code = "TEST_PHYSICAL"
        schemaReference = "APP_OWNER"
        name = "Test physical schema"
    }
    $logicalSchema = Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/logical-schemas" @{
        code = "APP_LOGICAL"
        name = "Application logical schema"
    }
    $environment = Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/environments" @{
        code = "TEST"
        risk = "DUSUK"
        policy = @{ destructiveWritesAllowed = $false }
        name = "Test"
    }
    $schemaBinding = Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/schema-bindings" @{
        logicalSchemaUuid = $logicalSchema.uuid
        environmentUuid = $environment.uuid
        physicalSchemaUuid = $physicalSchema.uuid
        connectionVersionUuid = $connectionVersion.uuid
    }
    if ($schemaBinding.connectionVersionUuid -ne $connectionVersion.uuid) {
        throw "Schema binding did not pin the connection version."
    }

    $otherConnection = Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/connections" @{
        code = "OTHER_ORACLE"
        databaseType = "ORACLE"
        name = "Other Oracle"
    }
    $otherVersion = Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/connections/$($otherConnection.uuid)/versions" @{
        driverReference = "oracle.jdbc.OracleDriver"
        host = "other-host.invalid"
        serviceName = "OTHER_SERVICE"
        port = 1521
    }
    $otherLogical = Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/logical-schemas" @{
        code = "OTHER_LOGICAL"
        name = "Other logical schema"
    }
    $otherEnvironment = Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/environments" @{
        code = "OTHER_TEST"
        name = "Other test"
    }
    try {
        Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/schema-bindings" @{
            logicalSchemaUuid = $otherLogical.uuid
            environmentUuid = $otherEnvironment.uuid
            physicalSchemaUuid = $physicalSchema.uuid
            connectionVersionUuid = $otherVersion.uuid
        }
        throw "Cross-connection schema binding was accepted."
    }
    catch {
        if ($_.Exception.Response.StatusCode.value__ -ne 422) {
            throw
        }
    }

    try {
        Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/connections/$($connection.uuid)/versions" @{
            driverReference = "oracle.jdbc.OracleDriver"
            host = "db-host.invalid"
            serviceName = "SERVICE"
            sid = "SID"
            port = 1521
        }
        throw "Ambiguous Oracle endpoint was accepted."
    }
    catch {
        if ($_.Exception.Response.StatusCode.value__ -ne 422) {
            throw
        }
    }

    try {
        Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/connections/$($connection.uuid)/versions" @{
            driverReference = "oracle.jdbc.OracleDriver"
            host = "db-host.invalid"
            serviceName = "SERVICE"
            port = 1521
            policy = @{ password = "must-not-be-stored" }
        }
        throw "Secret-bearing connection policy was accepted."
    }
    catch {
        if ($_.Exception.Response.StatusCode.value__ -ne 422) {
            throw
        }
    }

    $contracts = @(
        @{ type = "MAPPING"; folder = $true; content = @{ datasets = @(); columnMappings = @(); writeStrategy = @{} } },
        @{ type = "REUSABLE_MAPPING"; folder = $true; content = @{ inputs = @(); outputs = @(); nodes = @() } },
        @{ type = "PACKAGE"; folder = $true; content = @{ firstStepId = "START"; steps = @(); transitions = @() } },
        @{ type = "PROCEDURE"; folder = $true; content = @{ tasks = @() } },
        @{ type = "VARIABLE"; folder = $false; content = @{ dataType = "STRING"; scope = "PROJECT"; historyMode = "NONE"; valueSource = "INPUT" } },
        @{ type = "SEQUENCE"; folder = $false; content = @{ implementation = "REPOSITORY"; start = 1; increment = 1; cycle = $false } },
        @{ type = "USER_FUNCTION"; folder = $false; content = @{ returnType = "STRING"; parameters = @(); implementations = @() } },
        @{ type = "KNOWLEDGE_MODULE"; folder = $false; content = @{ kmType = "IKM"; tasks = @(); options = @() } },
        @{ type = "LOAD_PLAN"; folder = $false; content = @{ steps = @(); restartPolicy = "FAILED_STEP" } }
    )

    foreach ($contract in $contracts) {
        $request = @{
            type = $contract.type
            code = "TEST_$($contract.type)"
            name = "Test $($contract.type)"
        }
        if ($contract.folder) {
            $request["folderUuid"] = $folder.uuid
        }
        $definition = Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/definitions" $request
        $draft = Invoke-AkisJson PUT "/api/v1/projects/$($project.uuid)/definitions/$($definition.uuid)/draft" @{
            expectedVersion = 0
            schemaVersion = 1
            content = $contract.content
        }
        $version = Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/definitions/$($definition.uuid)/versions" @{
            expectedDraftVersion = $draft.version
            description = "API contract test"
        }
        if ($version.versionNumber -ne 1 -or $version.contentHash.Length -ne 64) {
            throw "Version contract failed for $($contract.type)."
        }
    }

    $definitions = Invoke-AkisJson GET "/api/v1/projects/$($project.uuid)/definitions"
    if ($definitions.Count -ne 9) {
        throw "Expected 9 definitions, found $($definitions.Count)."
    }

    $globalTypes = @(
        "REUSABLE_MAPPING",
        "VARIABLE",
        "SEQUENCE",
        "USER_FUNCTION",
        "KNOWLEDGE_MODULE"
    )
    foreach ($globalType in $globalTypes) {
        $contract = $contracts | Where-Object type -eq $globalType | Select-Object -First 1
        $content = $contract.content.Clone()
        if ($globalType -eq "VARIABLE") {
            $content.scope = "GLOBAL"
        }
        $definition = Invoke-AkisJson POST "/api/v1/global-definitions" @{
            type = $globalType
            code = "GLOBAL_$globalType"
            name = "Global $globalType"
        }
        if ($null -ne $definition.projectId) {
            throw "Global $globalType unexpectedly has a project owner."
        }
        $draft = Invoke-AkisJson PUT "/api/v1/global-definitions/$($definition.uuid)/draft" @{
            expectedVersion = 0
            schemaVersion = 1
            content = $content
        }
        Invoke-AkisJson POST "/api/v1/global-definitions/$($definition.uuid)/versions" @{
            expectedDraftVersion = $draft.version
        } | Out-Null
    }
    $globalDefinitions = Invoke-AkisJson GET "/api/v1/global-definitions"
    if ($globalDefinitions.Count -ne 5) {
        throw "Expected 5 global definition types, found $($globalDefinitions.Count)."
    }

    try {
        Invoke-AkisJson POST "/api/v1/global-definitions" @{
            type = "PACKAGE"
            code = "INVALID_GLOBAL_PACKAGE"
            name = "Invalid global package"
        }
        throw "Invalid global Package was accepted."
    }
    catch {
        if ($_.Exception.Response.StatusCode.value__ -ne 422) {
            throw
        }
    }

    try {
        Invoke-AkisJson POST "/api/v1/global-definitions" @{
            type = "UNKNOWN_TYPE"
            code = "INVALID_TYPE"
            name = "Invalid type"
        }
        throw "Unknown definition type was accepted."
    }
    catch {
        if ($_.Exception.Response.StatusCode.value__ -ne 400) {
            throw
        }
    }

    $variable = $definitions | Where-Object type -eq "VARIABLE" | Select-Object -First 1
    $updatedDraft = Invoke-AkisJson PUT "/api/v1/projects/$($project.uuid)/definitions/$($variable.uuid)/draft" @{
        expectedVersion = 1
        schemaVersion = 1
        content = @{ dataType = "STRING"; scope = "PROJECT"; historyMode = "LATEST"; valueSource = "INPUT" }
    }
    if ($updatedDraft.version -ne 2) {
        throw "Optimistic draft version did not increment."
    }

    try {
        Invoke-AkisJson PUT "/api/v1/projects/$($project.uuid)/definitions/$($variable.uuid)/draft" @{
            expectedVersion = 1
            schemaVersion = 1
            content = @{ dataType = "STRING"; scope = "PROJECT"; historyMode = "ALL"; valueSource = "INPUT" }
        }
        throw "Stale draft write was accepted."
    }
    catch {
        if ($_.Exception.Response.StatusCode.value__ -ne 412) {
            throw
        }
    }

    Write-Output "Backend API test: PASS (topology binding, 9 project types, 5 global types, version locks)"
}
catch {
    if (Test-Path -LiteralPath $stdoutLog) {
        Get-Content -LiteralPath $stdoutLog -Tail 60
    }
    if (Test-Path -LiteralPath $stderrLog) {
        Get-Content -LiteralPath $stderrLog -Tail 60
    }
    throw
}
finally {
    if ($appProcess -and -not $appProcess.HasExited) {
        Stop-Process -Id $appProcess.Id -Force
        $appProcess.WaitForExit()
    }
    if ($previousEnvironment) {
        foreach ($entry in $previousEnvironment.GetEnumerator()) {
            [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
        }
    }
    & $docker exec $container dropdb --if-exists --force -U $databaseUser $testDatabase | Out-Null
    if (Test-Path -LiteralPath $logDirectory) {
        Remove-Item -LiteralPath $logDirectory -Recurse -Force
    }
}
