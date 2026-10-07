@echo off
rem Sobe o servidor de contas do Apex no seu PC (modo desenvolvimento: banco PostgreSQL embutido).
rem Deixe esta janela aberta enquanto usa o app. Para parar, feche a janela ou aperte Ctrl+C.
setlocal
if not exist "%JAVA_HOME%\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"

rem O Java 25 falha ao criar sockets internos em pastas temporarias com nome curto (8.3) ou virtualizadas.
set "TMPD=%USERPROFILE%\.apex\tmp"
if not exist "%TMPD%" mkdir "%TMPD%"
set "TEMP=%TMPD%"
set "TMP=%TMPD%"
set "JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=%TMPD%"

echo Servidor do Apex em http://127.0.0.1:8080
echo Banco: se existir %USERPROFILE%\.apex-server\.env (criado pelo Configurar-Neon.cmd) usa o Neon; senao, um banco de teste embutido.
call "%~dp0gradlew.bat" :server:run --console=plain
