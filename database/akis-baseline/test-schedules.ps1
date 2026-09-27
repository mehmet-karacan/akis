[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$baselineDirectory = $PSScriptRoot
$projectRoot = Split-Path -Parent (Split-Path -Parent $baselineDirectory)
$docker = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
$container = 'akis-metadata-db-1'
$database = 'akis_schedule_test_' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$created = $false
$settings = @{}

Get-Content -LiteralPath (Join-Path $projectRoot '.env') |
    Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } |
    ForEach-Object {
        $name, $value = $_ -split '=', 2
        $settings[$name] = $value.Trim('"')
    }

if (-not (Test-Path -LiteralPath $docker -PathType Leaf)) {
    throw "Docker CLI is unavailable: $docker"
}
$javaHome = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'
if (-not (Test-Path -LiteralPath (Join-Path $javaHome 'bin\java.exe'))) {
    throw "Java 25 is required for the schedule integration test: $javaHome"
}
$databaseUser = (& $docker exec $container sh -lc 'printf %s "$POSTGRES_USER"').Trim()
if ([string]::IsNullOrWhiteSpace($databaseUser)) {
    throw "PostgreSQL container '$container' is unavailable."
}

function Invoke-TestSqlFile([string] $Path) {
    Get-Content -LiteralPath $Path -Raw |
        & $docker exec -i $container psql -q -v ON_ERROR_STOP=1 -U $databaseUser -d $database
    if ($LASTEXITCODE -ne 0) {
        throw "SQL verification failed: $(Split-Path -Leaf $Path)"
    }
}

try {
    & $docker exec $container createdb -U $databaseUser $database
    if ($LASTEXITCODE -ne 0) { throw 'Could not create isolated schedule test database.' }
    $created = $true

    $migrations = Get-ChildItem -LiteralPath $baselineDirectory -Filter 'V*.sql' |
        Where-Object { $_.Name -match '^V[0-9]+__' } |
        Sort-Object {
            [int]([regex]::Match($_.Name, '^V([0-9]+)__').Groups[1].Value)
        }
    foreach ($migration in $migrations) {
        Invoke-TestSqlFile $migration.FullName
    }

    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:$($settings['POSTGRES_PORT'])/$database"
    $env:SPRING_DATASOURCE_USERNAME = $databaseUser
    $env:SPRING_DATASOURCE_PASSWORD = $settings['POSTGRES_PASSWORD']
    $env:JAVA_HOME = $javaHome
    $env:Path = "$javaHome\bin;$env:Path"

    & (Join-Path $projectRoot 'mvnw.cmd') -pl backend '-Dtest=ScheduleRepositoryIT,ScheduleTwoWorkerUniquenessTest' test
    if ($LASTEXITCODE -ne 0) { throw 'Schedule repository integration test failed.' }
    'Schedule repository and two-worker uniqueness tests: PASS'
}
finally {
    if ($created -and $database -match '^akis_schedule_test_[0-9]+$') {
        & $docker exec $container dropdb --if-exists --force -U $databaseUser $database | Out-Null
    }
}
