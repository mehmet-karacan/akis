@echo off
setlocal EnableExtensions DisableDelayedExpansion

rem Akis yerel gelistirme ortamini tek komutla baslatir.
rem   scripts\start-dev.cmd          PostgreSQL + backend + frontend
rem   scripts\start-dev.cmd -check   Gereksinimleri kontrol eder, servis baslatmaz

for %%I in ("%~dp0..") do set "ROOT=%%~fI"
set "FRONTEND=%ROOT%\frontend"
set "ENV_FILE=%ROOT%\.env"
set "BACKEND_RUNNER=%ROOT%\scripts\run-backend.ps1"
set "HIDDEN_RUNNER=%ROOT%\scripts\start-hidden-process.ps1"
set "PORT_WAITER=%ROOT%\scripts\wait-for-port.ps1"

if /i "%~1"=="-check" goto :check_only
if not "%~1"=="" (
  echo HATA: Bilinmeyen parametre: %~1
  echo Kullanim: %~nx0 [-check]
  exit /b 2
)

call :check_requirements
if errorlevel 1 exit /b 1

docker info >nul 2>&1
if errorlevel 1 (
  echo HATA: Docker calismiyor. Docker Desktop'i acip tekrar deneyin.
  exit /b 1
)

echo [1/3] PostgreSQL hazirlaniyor...
pushd "%ROOT%"
docker compose --env-file "%ENV_FILE%" up -d --wait
set "COMPOSE_EXIT=%ERRORLEVEL%"
popd
if not "%COMPOSE_EXIT%"=="0" (
  echo HATA: PostgreSQL baslatilamadi.
  exit /b %COMPOSE_EXIT%
)

echo [2/3] Backend baslatiliyor...
call :port_listening 8080
if not errorlevel 1 (
  echo       Port 8080 zaten dinleniyor; backend acik sayildi.
) else (
  powershell.exe -NoLogo -NoProfile -Command "& ([ScriptBlock]::Create((Get-Content -Raw -LiteralPath '%HIDDEN_RUNNER%'))) -Role Backend -ProjectRoot '%ROOT%'"
  if errorlevel 1 exit /b 1
)

echo [3/3] Frontend baslatiliyor...
call :port_listening 5173
if not errorlevel 1 (
  echo       Port 5173 zaten dinleniyor; frontend acik sayildi.
) else (
  powershell.exe -NoLogo -NoProfile -Command "& ([ScriptBlock]::Create((Get-Content -Raw -LiteralPath '%HIDDEN_RUNNER%'))) -Role Frontend -ProjectRoot '%ROOT%'"
  if errorlevel 1 exit /b 1
)

powershell.exe -NoLogo -NoProfile -Command "& ([ScriptBlock]::Create((Get-Content -Raw -LiteralPath '%PORT_WAITER%'))) -Port 8080 -TimeoutSeconds 120"
if errorlevel 1 (
  echo HATA: Backend 120 saniye icinde 8080 portunda hazir olmadi.
  exit /b 1
)
powershell.exe -NoLogo -NoProfile -Command "& ([ScriptBlock]::Create((Get-Content -Raw -LiteralPath '%PORT_WAITER%'))) -Port 5173 -TimeoutSeconds 60"
if errorlevel 1 (
  echo HATA: Frontend 60 saniye icinde 5173 portunda hazir olmadi.
  exit /b 1
)

echo.
echo Hazir:
echo   Frontend : http://127.0.0.1:5173
echo   Backend  : http://127.0.0.1:8080
echo   Saglik   : http://127.0.0.1:8080/actuator/health
exit /b 0

:check_only
call :check_requirements
if errorlevel 1 exit /b 1
echo Kontrol basarili. Gerekli dosyalar ve komutlar mevcut.
exit /b 0

:check_requirements
if not exist "%ENV_FILE%" (
  echo HATA: %ENV_FILE% bulunamadi. .env.example dosyasini .env olarak kopyalayip yerel degerleri girin.
  exit /b 1
)
if not exist "%ROOT%\compose.yaml" (
  echo HATA: %ROOT%\compose.yaml bulunamadi.
  exit /b 1
)
if not exist "%ROOT%\mvnw.cmd" (
  echo HATA: %ROOT%\mvnw.cmd bulunamadi.
  exit /b 1
)
if not exist "%BACKEND_RUNNER%" (
  echo HATA: %BACKEND_RUNNER% bulunamadi.
  exit /b 1
)
if not exist "%HIDDEN_RUNNER%" (
  echo HATA: %HIDDEN_RUNNER% bulunamadi.
  exit /b 1
)
if not exist "%PORT_WAITER%" (
  echo HATA: %PORT_WAITER% bulunamadi.
  exit /b 1
)
if not exist "%FRONTEND%\package.json" (
  echo HATA: %FRONTEND%\package.json bulunamadi.
  exit /b 1
)
call :require_command docker.exe Docker
if errorlevel 1 exit /b 1
call :require_command java.exe Java
if errorlevel 1 exit /b 1
call :require_command npm.cmd Node.js-npm
if errorlevel 1 exit /b 1
exit /b 0

:require_command
where %~1 >nul 2>&1
if errorlevel 1 (
  echo HATA: %~2 komutu PATH icinde bulunamadi ^(%~1^).
  exit /b 1
)
exit /b 0

:port_listening
netstat -ano | findstr /r /c:":%~1 .*LISTENING" >nul
exit /b %ERRORLEVEL%
