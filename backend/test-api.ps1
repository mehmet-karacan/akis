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
        [object] $Body,
        [hashtable] $Headers
    )

    $requestHeaders = @{ Authorization = $script:authorizationHeader }
    if ($Headers) {
        foreach ($header in $Headers.GetEnumerator()) {
            $requestHeaders[$header.Key] = $header.Value
        }
    }
    $parameters = @{
        Method = $Method
        Uri = "http://127.0.0.1:$port$Path"
        Headers = $requestHeaders
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
        AKIS_SECURITY_MODE = $env:AKIS_SECURITY_MODE
        AKIS_DEV_USERNAME = $env:AKIS_DEV_USERNAME
        AKIS_DEV_PASSWORD = $env:AKIS_DEV_PASSWORD
        AKIS_EXECUTION_ACCEPT_MANUAL_REQUESTS = $env:AKIS_EXECUTION_ACCEPT_MANUAL_REQUESTS
        AKIS_EXECUTION_WORKER_ENABLED = $env:AKIS_EXECUTION_WORKER_ENABLED
    }
    $env:AKIS_DB_URL = "jdbc:postgresql://127.0.0.1:$($settings['POSTGRES_PORT'])/$testDatabase"
    $env:AKIS_DB_USERNAME = $databaseUser
    $env:AKIS_DB_PASSWORD = $settings["POSTGRES_PASSWORD"]
    $env:AKIS_SERVER_PORT = $port
    $env:AKIS_SECURITY_MODE = "development"
    $env:AKIS_DEV_USERNAME = "api-test"
    $env:AKIS_DEV_PASSWORD = [Guid]::NewGuid().ToString("N")
    $env:AKIS_EXECUTION_ACCEPT_MANUAL_REQUESTS = "true"
    $env:AKIS_EXECUTION_WORKER_ENABLED = "false"
    $credentialBytes = [Text.Encoding]::UTF8.GetBytes(
        "$($env:AKIS_DEV_USERNAME):$($env:AKIS_DEV_PASSWORD)")
    $script:authorizationHeader = "Basic " + [Convert]::ToBase64String($credentialBytes)

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

    & $docker exec $container psql -v ON_ERROR_STOP=1 -U $databaseUser -d $testDatabase `
        -c "insert into entegrasyon.kullanici(oidc_saglayici, oidc_ozne, ad) values ('LOCAL_BASIC', 'api-test', 'API Test Runner')" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Could not provision the temporary LOCAL_BASIC run actor."
    }

    try {
        Invoke-RestMethod "http://127.0.0.1:$port/api/v1/projects" | Out-Null
        throw "Anonymous API request was accepted."
    }
    catch {
        if ($_.Exception.Response.StatusCode.value__ -ne 401) {
            throw
        }
    }

    $correlationProbe = Invoke-WebRequest "http://127.0.0.1:$port/api/v1/definition-types" `
        -Headers @{
            Authorization = $script:authorizationHeader
            "X-Correlation-Id" = "api-contract-test"
        }
    if ($correlationProbe.Headers["X-Correlation-Id"] -ne "api-contract-test") {
        throw "Correlation id was not preserved in the response."
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
    $oidcUser = Invoke-AkisJson POST "/api/v1/identity/users" @{
        issuer = "https://identity.example/realms/akis"
        subject = "api-smoke-user"
        name = "API Smoke User"
        email = "api-smoke@example.invalid"
    }
    $membership = Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/memberships" @{
        userUuid = $oidcUser.uuid
        role = "IZLEYICI"
    }
    $memberships = Invoke-AkisJson GET "/api/v1/projects/$($project.uuid)/memberships"
    if ($membership.userUuid -ne $oidcUser.uuid -or $memberships.Count -ne 1 `
            -or $membership.roles[0].code -ne "IZLEYICI") {
        throw "OIDC user and project membership provisioning failed."
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
    $activeConnection = Invoke-AkisJson GET "/api/v1/projects/$($project.uuid)/connections/$($connection.uuid)"
    if ($activeConnection.status -ne "AKTIF") {
        throw "A valid first connection version did not activate its draft connection."
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
    $model = Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/models" @{
        logicalSchemaUuid = $logicalSchema.uuid
        code = "APP_MODEL"
        name = "Application model"
    }
    $submodel = Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/models/$($model.uuid)/submodels" @{
        code = "REFERENCE"
        name = "Reference data"
    }
    $tableObject = Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/models/$($model.uuid)/data-objects" @{
        submodelUuid = $submodel.uuid
        code = "SOURCE_TABLE"
        objectReference = "SOURCE_TABLE"
        type = "TABLO"
        name = "Source table"
    }
    $targetTableObject = Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/models/$($model.uuid)/data-objects" @{
        submodelUuid = $submodel.uuid
        code = "TARGET_TABLE"
        objectReference = "TARGET_TABLE"
        type = "TABLO"
        name = "Target table"
    }
    Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/models/$($model.uuid)/data-objects" @{
        code = "SOURCE_VIEW"
        objectReference = "SOURCE_VIEW"
        type = "VIEW"
        name = "Source view"
    } | Out-Null
    Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/models/$($model.uuid)/data-objects" @{
        code = "CONTROLLED_QUERY"
        objectReference = "CONTROLLED_QUERY"
        type = "SORGU"
        querySchemaVersion = 1
        queryDefinition = @{ sql = "SELECT ID FROM APP_OWNER.SOURCE_TABLE" }
        name = "Controlled query"
    } | Out-Null
    $dataObjects = Invoke-AkisJson GET "/api/v1/projects/$($project.uuid)/models/$($model.uuid)/data-objects"
    if ($dataObjects.Count -ne 4) {
        throw "Expected 4 model data objects, found $($dataObjects.Count)."
    }

    $snapshot = Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/data-objects/$($tableObject.uuid)/schema-snapshots" @{
        physicalSchemaUuid = $physicalSchema.uuid
        connectionVersionUuid = $connectionVersion.uuid
        engineVersion = "Oracle Database 19c"
        discoveredAt = [DateTimeOffset]::UtcNow.ToString("o")
        propertyVersion = 1
        properties = @{ source = "api-smoke" }
        columns = @(
            @{
                reference = "ID"
                producerType = "NUMBER(19)"
                canonicalType = "INTEGER"
                ordinal = 1
                precision = 19
                scale = 0
                nullable = $false
                name = "ID"
            },
            @{
                reference = "NAME"
                producerType = "VARCHAR2(100)"
                canonicalType = "STRING"
                ordinal = 2
                length = 100
                nullable = $true
                name = "NAME"
            }
        )
        constraints = @(
            @{
                externalReference = "PK_SOURCE_TABLE"
                type = "PK"
                enabled = $true
                detailVersion = 1
                details = @{}
                name = "PK_SOURCE_TABLE"
                columnReferences = @("ID")
            }
        )
    }
    if ($snapshot.fingerprint.Length -ne 64 -or $snapshot.columns.Count -ne 2) {
        throw "Immutable schema snapshot contract failed."
    }
    $snapshots = Invoke-AkisJson GET "/api/v1/projects/$($project.uuid)/data-objects/$($tableObject.uuid)/schema-snapshots"
    if ($snapshots.Count -ne 1 -or $snapshots[0].uuid -ne $snapshot.uuid) {
        throw "Schema snapshot list contract failed."
    }
    $targetSnapshot = Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/data-objects/$($targetTableObject.uuid)/schema-snapshots" @{
        physicalSchemaUuid = $physicalSchema.uuid
        connectionVersionUuid = $connectionVersion.uuid
        engineVersion = "Oracle Database 19c"
        discoveredAt = [DateTimeOffset]::UtcNow.ToString("o")
        propertyVersion = 1
        properties = @{ source = "api-smoke" }
        columns = @(
            @{
                reference = "ID"
                producerType = "NUMBER(19)"
                canonicalType = "INTEGER"
                ordinal = 1
                precision = 19
                scale = 0
                nullable = $false
                name = "ID"
            }
        )
        constraints = @()
    }

    try {
        Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/models/$($model.uuid)/data-objects" @{
            code = "INVALID_QUERY"
            objectReference = "INVALID_QUERY"
            type = "SORGU"
            querySchemaVersion = 1
            queryDefinition = @{ sql = "DELETE FROM APP_OWNER.SOURCE_TABLE" }
            name = "Invalid query"
        }
        throw "Non-read-only controlled query was accepted."
    }
    catch {
        if ($_.Exception.Response.StatusCode.value__ -ne 422) {
            throw
        }
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

    try {
        Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/connections/$($connection.uuid)/versions" @{
            driverReference = "oracle.jdbc.OracleDriver"
            host = "db-host.invalid"
            serviceName = "SERVICE"
            port = 1521
            policy = @{ jdbcUrl = "jdbc:oracle:thin:user/must-not-be-stored@//db-host.invalid:1521/SERVICE" }
        }
        throw "Credential-bearing scalar connection policy was accepted."
    }
    catch {
        if ($_.Exception.Response.StatusCode.value__ -ne 422) {
            throw
        }
    }

    $contracts = @(
        @{ type = "MAPPING"; folder = $true; content = @{
            datasets = @(
                @{ id = "source"; role = "SOURCE" },
                @{ id = "target"; role = "TARGET" }
            )
            columnMappings = @(
                @{
                    source = @{ dataset = "source"; column = "ID" }
                    target = @{ dataset = "target"; column = "ID" }
                }
            )
            writeStrategy = @{ kind = "ATOMIC_DELETE_INSERT" }
        } },
        @{ type = "REUSABLE_MAPPING"; folder = $true; content = @{ inputs = @(); outputs = @(); nodes = @() } },
        @{ type = "PACKAGE"; folder = $true; content = @{
            firstStepId = "START"
            steps = @( @{ id = "START"; type = "MAPPING" } )
            transitions = @()
        } },
        @{ type = "PROCEDURE"; folder = $true; content = @{
            tasks = @(
                @{
                    id = "READ"
                    type = "SQL"
                    connectionRole = "SOURCE"
                    riskClass = "READ_ONLY"
                    command = "SELECT 1 FROM DUAL"
                }
            )
        } },
        @{ type = "VARIABLE"; folder = $false; content = @{ dataType = "STRING"; scope = "PROJECT"; historyMode = "NONE"; valueSource = "INPUT" } },
        @{ type = "SEQUENCE"; folder = $false; content = @{ implementation = "REPOSITORY"; start = 1; increment = 1; cycle = $false } },
        @{ type = "USER_FUNCTION"; folder = $false; content = @{ returnType = "STRING"; parameters = @(); implementations = @() } },
        @{ type = "KNOWLEDGE_MODULE"; folder = $false; content = @{ kmType = "IKM"; tasks = @(); options = @() } },
        @{ type = "LOAD_PLAN"; folder = $false; content = @{
            steps = @( @{ id = "SCENARIO"; type = "SCENARIO"; scenarioVersionUuid = "test-version" } )
            restartPolicy = "FAILED_STEP"
        } }
    )

    $mappingDefinition = $null
    $mappingVersion = $null
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
            schemaVersion = if ($contract.type -eq "MAPPING") { 2 } else { 1 }
            content = $contract.content
        }
        $version = Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/definitions/$($definition.uuid)/versions" @{
            expectedDraftVersion = $draft.version
            description = "API contract test"
        }
        if ($version.versionNumber -ne 1 -or $version.contentHash.Length -ne 64) {
            throw "Version contract failed for $($contract.type)."
        }
        $activeDefinition = Invoke-AkisJson GET "/api/v1/projects/$($project.uuid)/definitions/$($definition.uuid)"
        if ($activeDefinition.status -ne "AKTIF") {
            throw "A definition with an immutable version was not activated."
        }
        if ($contract.type -eq "MAPPING") {
            $mappingDefinition = $definition
            $mappingVersion = $version
        }
    }

    $scenarioPath = "/api/v1/projects/$($project.uuid)/definitions/$($mappingDefinition.uuid)/versions/$($mappingVersion.uuid)/scenarios"
    $scenario = Invoke-AkisJson POST "$scenarioPath/compile"
    $sameScenario = Invoke-AkisJson POST "$scenarioPath/compile"
    $scenarios = Invoke-AkisJson GET $scenarioPath
    if ($scenario.uuid -ne $sameScenario.uuid -or $scenario.planHash.Length -ne 64 -or $scenarios.Count -ne 1) {
        throw "Deterministic idempotent scenario compilation failed."
    }
    $bindingPath = "/api/v1/projects/$($project.uuid)/definitions/$($mappingDefinition.uuid)/versions/$($mappingVersion.uuid)/data-bindings"
    $definitionBinding = Invoke-AkisJson POST $bindingPath @{
        nodeCode = "source"
        role = "KAYNAK"
        dataObjectUuid = $tableObject.uuid
        schemaSnapshotUuid = $snapshot.uuid
    }
    $targetDefinitionBinding = Invoke-AkisJson POST $bindingPath @{
        nodeCode = "target"
        role = "HEDEF"
        dataObjectUuid = $targetTableObject.uuid
        schemaSnapshotUuid = $targetSnapshot.uuid
    }
    $definitionBindings = Invoke-AkisJson GET $bindingPath
    if ($definitionBinding.schemaSnapshotUuid -ne $snapshot.uuid `
            -or $targetDefinitionBinding.schemaSnapshotUuid -ne $targetSnapshot.uuid `
            -or $definitionBindings.Count -ne 2) {
        throw "Immutable definition data binding failed."
    }
    & $docker exec $container psql -v ON_ERROR_STOP=1 -U $databaseUser -d $testDatabase `
        -c "update entegrasyon.ortam_sema_eslemesi set baglanti_surumu_id = (select id from entegrasyon.baglanti_surumu where uuid = '$($otherVersion.uuid)') where uuid = '$($schemaBinding.uuid)'" | Out-Null
    try {
        Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/publications" @{
            scenarioUuid = $scenario.uuid
            environmentUuid = $environment.uuid
        }
        throw "Mismatched physical schema and connection version was published."
    }
    catch {
        if ($_.Exception.Response.StatusCode.value__ -ne 422) {
            throw
        }
    }
    & $docker exec $container psql -v ON_ERROR_STOP=1 -U $databaseUser -d $testDatabase `
        -c "update entegrasyon.ortam_sema_eslemesi set baglanti_surumu_id = (select id from entegrasyon.baglanti_surumu where uuid = '$($connectionVersion.uuid)') where uuid = '$($schemaBinding.uuid)'" | Out-Null
    $publication = Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/publications" @{
        scenarioUuid = $scenario.uuid
        environmentUuid = $environment.uuid
    }
    $samePublication = Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/publications" @{
        scenarioUuid = $scenario.uuid
        environmentUuid = $environment.uuid
    }
    $publications = Invoke-AkisJson GET "/api/v1/projects/$($project.uuid)/publications"
    if ($publication.uuid -ne $samePublication.uuid -or $publication.status -ne "AKTIF" `
            -or $publication.releaseHash.Length -ne 64 -or $publications.Count -ne 1) {
        throw "Context-pinned idempotent publication failed."
    }

    $runKey = "api-run-" + [Guid]::NewGuid().ToString("N")
    $runHeaders = @{ "Idempotency-Key" = $runKey }
    $run = Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/runs" `
        @{ publicationUuid = $publication.uuid } $runHeaders
    $sameRun = Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/runs" `
        @{ publicationUuid = $publication.uuid } $runHeaders
    $runs = Invoke-AkisJson GET "/api/v1/projects/$($project.uuid)/runs"
    $runDetail = Invoke-AkisJson GET "/api/v1/projects/$($project.uuid)/runs/$($run.runUuid)"
    $runEvents = Invoke-AkisJson GET "/api/v1/projects/$($project.uuid)/runs/$($run.runUuid)/events"
    if ($run.status -ne "BEKLIYOR" -or $sameRun.runUuid -ne $run.runUuid `
            -or $runs.Count -ne 1 -or $runDetail.runUuid -ne $run.runUuid `
            -or $run.releaseHash -ne $publication.releaseHash `
            -or $run.planHash -ne $scenario.planHash `
            -or $run.releaseHash -eq $run.planHash `
            -or $runEvents.Count -ne 1 -or $runEvents[0].eventNumber -ne 1 `
            -or $run.PSObject.Properties.Name -contains "id") {
        throw "Queued idempotent manual run contract failed."
    }
    try {
        Invoke-AkisJson POST "/api/v1/projects/$($project.uuid)/runs" `
            @{ publicationUuid = [Guid]::NewGuid() } $runHeaders
        throw "Changed request reused an Idempotency-Key."
    }
    catch {
        if ($_.Exception.Response.StatusCode.value__ -ne 409) {
            throw
        }
    }
    $cancelledRun = Invoke-AkisJson POST `
        "/api/v1/projects/$($project.uuid)/runs/$($run.runUuid)/cancel"
    $sameCancelledRun = Invoke-AkisJson POST `
        "/api/v1/projects/$($project.uuid)/runs/$($run.runUuid)/cancel"
    $cancelledEvents = Invoke-AkisJson GET `
        "/api/v1/projects/$($project.uuid)/runs/$($run.runUuid)/events"
    if ($cancelledRun.status -ne "IPTAL" `
            -or $sameCancelledRun.runUuid -ne $cancelledRun.runUuid `
            -or $cancelledEvents.Count -ne 2 `
            -or $cancelledEvents[1].eventNumber -ne 2 `
            -or $cancelledEvents[1].type -ne "RUN_CANCELLED") {
        throw "Queued run cancellation contract failed."
    }

    $definitions = Invoke-AkisJson GET "/api/v1/projects/$($project.uuid)/definitions"
    if ($definitions.Count -ne 9) {
        throw "Expected 9 definitions, found $($definitions.Count)."
    }

    $bundle = Invoke-AkisJson GET "/api/v1/projects/$($project.uuid)/bundle/export"
    if ($bundle.format -ne "akis.project-bundle" -or $bundle.formatVersion -ne 1 `
            -or $bundle.schemaVersion -ne 1 -or $bundle.checksum.Length -ne 64 `
            -or -not $bundle.topology.sanitized -or $bundle.definitions.Count -ne 9) {
        throw "Portable project bundle export contract failed."
    }
    $validation = Invoke-AkisJson POST "/api/v1/project-bundles/validate" $bundle
    if (-not $validation.valid -or $validation.counts.definitions -ne 9) {
        throw "Exported project bundle did not pass standalone validation."
    }
    $dryRun = Invoke-AkisJson POST "/api/v1/project-bundles/import?conflict=RENAME&dryRun=true" $bundle
    if (-not $dryRun.dryRun -or $dryRun.imported `
            -or $dryRun.projectCode -ne "API_TEST_IMPORT_1") {
        throw "Project bundle deterministic rename dry-run failed."
    }
    $bundleImport = Invoke-AkisJson POST "/api/v1/project-bundles/import?conflict=RENAME&dryRun=false" $bundle
    if (-not $bundleImport.imported -or $bundleImport.dryRun `
            -or $bundleImport.projectCode -ne $dryRun.projectCode) {
        throw "Project bundle import did not match its dry-run plan."
    }
    $importedDefinitions = Invoke-AkisJson GET "/api/v1/projects/$($bundleImport.projectUuid)/definitions"
    $importedConnections = Invoke-AkisJson GET "/api/v1/projects/$($bundleImport.projectUuid)/connections"
    if ($importedDefinitions.Count -ne 9 -or $importedConnections.Count -ne 0) {
        throw "Portable design import count or sanitized topology boundary failed."
    }
    $bundle.project.name = "Tampered bundle"
    $tamperedValidation = Invoke-AkisJson POST "/api/v1/project-bundles/validate" $bundle
    if ($tamperedValidation.valid -or -not ($tamperedValidation.issues.code -contains "BUNDLE_CHECKSUM_MISMATCH")) {
        throw "Tampered project bundle checksum was accepted."
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

    $auditCount = (& $docker exec $container psql -U $databaseUser -d $testDatabase `
        -Atc "select count(*) from entegrasyon.denetim_olayi").Trim()
    if ([int]$auditCount -lt 1) {
        throw "Mutating API requests did not produce audit events."
    }
    $userAuditCount = (& $docker exec $container psql -U $databaseUser -d $testDatabase `
        -Atc "select count(*) from entegrasyon.denetim_olayi where aktor_turu = 'KULLANICI' and ayrinti->>'principal' = 'api-test'").Trim()
    if ([int]$userAuditCount -lt 1) {
        throw "Authenticated mutating requests were not attributed in audit events."
    }

    Write-Output "Backend API test: PASS (auth, RBAC, audit, topology, catalog, snapshots, definitions, bundles, bindings, scenarios, publications, queued runs, locks)"
}
catch {
    if (Test-Path -LiteralPath $stdoutLog) {
        Get-Content -LiteralPath $stdoutLog -Tail 200
    }
    if (Test-Path -LiteralPath $stderrLog) {
        Get-Content -LiteralPath $stderrLog -Tail 200
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
