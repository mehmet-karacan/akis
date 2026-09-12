[CmdletBinding()] param()
$ErrorActionPreference="Stop"
$baselineDirectory=$PSScriptRoot
$projectRoot=Split-Path -Parent (Split-Path -Parent $baselineDirectory)
$docker="C:\Program Files\Docker\Docker\resources\bin\docker.exe"
$containerName="akis-metadata-db-1"
$testDatabase="akis_scenario_test_"+[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$created=$false
$settings=@{}; Get-Content (Join-Path $projectRoot ".env")|Where-Object{$_ -match '^[A-Za-z_][A-Za-z0-9_]*='}|ForEach-Object{$n,$v=$_ -split '=',2;$settings[$n]=$v}
$user=(& $docker exec $containerName sh -lc 'printf %s "$POSTGRES_USER"').Trim()
try {
 & $docker exec $containerName createdb -U $user $testDatabase; if($LASTEXITCODE){throw "create database failed"};$created=$true
 foreach($name in @('V001__identity_rbac_project.sql','V002__connections_and_schemas.sql','V003__folders_definitions_and_versions.sql','V004__catalog_and_schema_snapshots.sql','V005__validation_and_scenarios.sql','verify-scenarios.sql')){
  $path=Join-Path $baselineDirectory $name;& $docker cp $path "${containerName}:/tmp/$name"|Out-Null;& $docker exec $containerName psql -v ON_ERROR_STOP=1 -U $user -d $testDatabase -f "/tmp/$name";if($LASTEXITCODE){throw $name}
 }
 $env:SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:$($settings['POSTGRES_PORT'])/$testDatabase";$env:SPRING_DATASOURCE_USERNAME=$user;$env:SPRING_DATASOURCE_PASSWORD=$settings['POSTGRES_PASSWORD']
 & (Join-Path $projectRoot 'mvnw.cmd') -pl backend '-Dtest=CleanScenarioRepositoryIT' test;if($LASTEXITCODE){throw 'scenario repository test failed'}
 'Clean validation and scenario repository tests: PASS'
} finally {if($created -and $testDatabase -match '^akis_scenario_test_[0-9]+$'){& $docker exec $containerName dropdb --if-exists --force -U $user $testDatabase|Out-Null}}
