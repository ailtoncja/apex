@echo off
rem Gera o que distribuir, em dist\ (todos trazem o Java dentro; o usuario so precisa do VLC):
rem   Apex-1.0.0-windows-x64.zip   versao portatil (extrair e abrir)
rem   Apex-1.0.0.msi / Apex-1.0.0.exe   instaladores (atalho no menu Iniciar e na area de trabalho)  [precisam do WiX]
rem   SHA256SUMS.txt   hashes para conferir os downloads
rem Precisa de um JDK 21 COMPLETO (com jpackage), como o Temurin 21: procura em %APEX_PACKAGE_JDK% e em %USERPROFILE%\.apex\jdk\jdk-21*.
rem Para os instaladores, o WiX Toolset 3.x (candle.exe): procura no PATH e em %APEX_WIX% ou %USERPROFILE%\.apex\wix.
setlocal
cd /d "%~dp0"

set "JDK=%APEX_PACKAGE_JDK%"
if not defined JDK for /d %%D in ("%USERPROFILE%\.apex\jdk\jdk-21*") do set "JDK=%%~fD"
if not defined JDK goto semjdk
if not exist "%JDK%\bin\jpackage.exe" goto semjdk

set "VERSION=1.0.0"
set "OUT=%~dp0dist"
set "STAGE=%OUT%\stage"

rem Pasta temporaria simples (o Java falha ao criar sockets internos em caminhos curtos 8.3 ou virtualizados).
set "TMPD=%USERPROFILE%\.apex\tmp"
if not exist "%TMPD%" mkdir "%TMPD%"
set "TEMP=%TMPD%"
set "TMP=%TMPD%"
set "JAVA_HOME=%JDK%"
if defined APEX_WIX if exist "%APEX_WIX%\candle.exe" set "PATH=%APEX_WIX%;%PATH%"
if exist "%USERPROFILE%\.apex\wix\candle.exe" set "PATH=%USERPROFILE%\.apex\wix;%PATH%"
set "WIX_OK="
where candle.exe >nul 2>nul && set "WIX_OK=1"
set "JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=%TMPD:\=/%"

set "TASKS=:composeApp:createDistributable"
if defined WIX_OK set "TASKS=%TASKS% :composeApp:packageMsi :composeApp:packageExe"
if not defined WIX_OK echo Aviso: WiX nao encontrado, so o zip sera gerado (instaladores .msi/.exe precisam do WiX 3.x).

call "%~dp0gradlew.bat" %TASKS% "-Papex.jdk=%JDK%" --console=plain
if errorlevel 1 exit /b 1

if exist "%STAGE%" rmdir /s /q "%STAGE%"
mkdir "%STAGE%"
xcopy /e /i /q /y "%~dp0composeApp\build\compose\binaries\main\app\Apex" "%STAGE%\Apex" >nul
copy /y "%~dp0composeApp\packaging\LEIA-ME.txt" "%STAGE%\LEIA-ME.txt" >nul
copy /y "%~dp0LICENSE" "%STAGE%\LICENSE" >nul
copy /y "%~dp0NOTICE.md" "%STAGE%\NOTICE.md" >nul

set "ZIP=%OUT%\Apex-%VERSION%-windows-x64.zip"
if exist "%ZIP%" del "%ZIP%"
tar -a -c -f "%ZIP%" -C "%STAGE%" .
if errorlevel 1 exit /b 1
rmdir /s /q "%STAGE%"

if defined WIX_OK (
  copy /y "%~dp0composeApp\build\compose\binaries\main\msi\Apex-%VERSION%.msi" "%OUT%\" >nul
  copy /y "%~dp0composeApp\build\compose\binaries\main\exe\Apex-%VERSION%.exe" "%OUT%\" >nul
)

rem Hashes SHA-256 de tudo que foi gerado.
powershell -NoProfile -Command "Get-ChildItem '%OUT%' -File | Where-Object { $_.Name -like 'Apex-*' } | ForEach-Object { '{0} *{1}' -f (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower(), $_.Name } | Set-Content -Encoding ASCII '%OUT%\SHA256SUMS.txt'"
echo.
echo Pronto. Arquivos em %OUT%:
dir /b "%OUT%"
exit /b 0

:semjdk
echo Nao encontrei um JDK 21 completo (com jpackage).
echo Baixe o Temurin 21 (zip) em https://adoptium.net, extraia em %USERPROFILE%\.apex\jdk\ ou defina APEX_PACKAGE_JDK.
echo Para os instaladores, extraia o WiX 3.14 (wix314-binaries.zip, em github.com/wixtoolset/wix3) em %USERPROFILE%\.apex\wix\.
exit /b 1
