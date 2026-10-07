@echo off
rem Gera o pacote para distribuir:  dist\Apex-1.0.0-windows-x64.zip  (traz o Java dentro; o usuario so precisa do VLC).
rem Precisa de um JDK 21 COMPLETO (com jpackage), como o Temurin 21. Procura em %APEX_PACKAGE_JDK% e em %USERPROFILE%\.apex\jdk\jdk-21*.
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
set "JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=%TMPD:\=/%"

call "%~dp0gradlew.bat" :composeApp:createDistributable "-Papex.jdk=%JDK%" --console=plain
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
echo.
echo Pronto: %ZIP%
exit /b 0

:semjdk
echo Nao encontrei um JDK 21 completo (com jpackage).
echo Baixe o Temurin 21 (zip) em https://adoptium.net, extraia em %USERPROFILE%\.apex\jdk\ ou defina APEX_PACKAGE_JDK.
exit /b 1
