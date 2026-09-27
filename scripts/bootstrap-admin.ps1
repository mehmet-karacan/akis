param()

$ErrorActionPreference = 'Stop'
$javaHome = $env:JAVA_HOME
if ([string]::IsNullOrWhiteSpace($javaHome)) {
    throw 'JAVA_HOME tanımlı olmalıdır.'
}

& "$PSScriptRoot\..\mvnw.cmd" -pl backend spring-boot:run '-Dspring-boot.run.main-class=tr.com.innova.akis.security.BootstrapAdminCommand'
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
