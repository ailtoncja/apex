@echo off
rem Gera o que distribuir, em dist\ (todos trazem o Java e o VLC dentro; o usuario nao instala nada):
rem   Apex-X.Y.Z-windows-x64.zip   versao portatil (extrair e abrir)
rem   Apex-X.Y.Z.msi / Apex-X.Y.Z.exe   instaladores (atalho no menu Iniciar e na area de trabalho)  [precisam do WiX]
rem   SHA256SUMS.txt e SHA256SUMS.txt.sig   hashes dos arquivos e a assinatura deles (o app so instala atualizacoes com a assinatura certa)
rem A versao e a do app: appVersion em composeApp\build.gradle.kts.
rem Precisa de um JDK 21 COMPLETO (com jpackage), como o Temurin 21: procura em %APEX_PACKAGE_JDK% e em %USERPROFILE%\.apex\jdk\jdk-21*.
rem Para assinar: a chave privada em %APEX_SIGNING_KEY% ou %USERPROFILE%\.apex\signing\apex-update.key.enc (protegida por senha; gerada com
rem tools\GenKey.java ou convertida da antiga apex-update.key com tools\ProtegerChave.java; NUNCA vai para o GitHub). A senha e pedida no fim,
rem depois que o Gradle parou; APEX_SIGN_PASSPHRASE evita a pergunta, mas e menos seguro (veja docs\PUBLICAR.md).
rem Para os instaladores, o WiX Toolset 3.x (candle.exe): procura no PATH e em %APEX_WIX% ou %USERPROFILE%\.apex\wix.
setlocal
cd /d "%~dp0"

set "JDK=%APEX_PACKAGE_JDK%"
if not defined JDK for /d %%D in ("%USERPROFILE%\.apex\jdk\jdk-21*") do set "JDK=%%~fD"
if not defined JDK goto semjdk
if not exist "%JDK%\bin\jpackage.exe" goto semjdk

rem Prefere a chave protegida por senha (.enc); a sem senha so e usada (com aviso) se ainda nao foi convertida.
if not defined APEX_SIGNING_KEY if exist "%USERPROFILE%\.apex\signing\apex-update.key.enc" set "APEX_SIGNING_KEY=%USERPROFILE%\.apex\signing\apex-update.key.enc"
if not defined APEX_SIGNING_KEY set "APEX_SIGNING_KEY=%USERPROFILE%\.apex\signing\apex-update.key"
if not exist "%APEX_SIGNING_KEY%" if not exist "%APEX_SIGNING_KEY%.enc" goto semchave

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

rem Compila o Kotlin dentro do daemon do Gradle (sem o daemon separado do Kotlin), para que o --stop abaixo encerre todo o codigo do build.
call "%~dp0gradlew.bat" %TASKS% "-Papex.jdk=%JDK%" -Pkotlin.compiler.execution.strategy=in-process --console=plain
if errorlevel 1 (
  call "%~dp0gradlew.bat" --stop >nul 2>nul
  exit /b 1
)
rem Para o daemon do Gradle ANTES de abrir a chave: nenhum codigo do build (plugins, dependencias) continua rodando enquanto ela esta na memoria.
call "%~dp0gradlew.bat" --stop
if errorlevel 1 ( echo Nao consegui parar o daemon do Gradle; a chave nao foi aberta. & exit /b 1 )

rem A versao vem do app (appVersion em composeApp\build.gradle.kts, gerada em BuildInfo.kt).
set "INFO=%~dp0composeApp\build\generated\buildinfo\app\apex\BuildInfo.kt"
for /f "tokens=2 delims==" %%V in ('findstr /c:"const val VERSION" "%INFO%"') do set "VERSION=%%V"
set "VERSION=%VERSION: =%"
set "VERSION=%VERSION:"=%"
if not defined VERSION ( echo Nao consegui ler a versao do app. & exit /b 1 )
echo Versao: %VERSION%

if not exist "%OUT%" mkdir "%OUT%"
if exist "%OUT%\Apex-*" del /q "%OUT%\Apex-*"
if exist "%OUT%\SHA256SUMS.txt*" del /q "%OUT%\SHA256SUMS.txt*"
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

rem Assina a lista de hashes (o app confere esta assinatura antes de instalar qualquer atualizacao). Pede a senha da chave.
rem Sem JAVA_TOOL_OPTIONS aqui: nada de opcoes extras injetadas no Java que abre a chave.
set "JAVA_TOOL_OPTIONS="
"%JDK%\bin\java.exe" "%~dp0tools\Sign.java" "%APEX_SIGNING_KEY%" "%OUT%\SHA256SUMS.txt"
if errorlevel 1 exit /b 1
echo.
echo Pronto. Arquivos em %OUT%:
dir /b "%OUT%"
exit /b 0

:semjdk
echo Nao encontrei um JDK 21 completo (com jpackage).
echo Baixe o Temurin 21 (zip) em https://adoptium.net, extraia em %USERPROFILE%\.apex\jdk\ ou defina APEX_PACKAGE_JDK.
echo Para os instaladores, extraia o WiX 3.14 (wix314-binaries.zip, em github.com/wixtoolset/wix3) em %USERPROFILE%\.apex\wix\.
exit /b 1

:semchave
echo Nao encontrei a chave que assina as atualizacoes: %APEX_SIGNING_KEY%
echo Gere uma com:  java tools\GenKey.java "%USERPROFILE%\.apex\signing\apex-update.key.enc"  e cole a chave publica em UpdateKeys.kt.
echo (Sem a assinatura nenhum app instalado aceita a atualizacao.)
exit /b 1
