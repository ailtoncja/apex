@echo off
rem Abre o Apex. Antes, gere o app uma vez:  gradlew.bat :composeApp:packageUberJarForCurrentOS
setlocal
rem Usa o .jar mais novo (o nome leva a versao do app).
set "JAR="
for %%F in ("%~dp0composeApp\build\compose\jars\Apex-windows-x64-*.jar") do set "JAR=%%~fF"
if not defined JAR (
  echo O app ainda nao foi gerado. Rode:  gradlew.bat :composeApp:packageUberJarForCurrentOS
  pause
  exit /b 1
)

set "JAVA=%JAVA_HOME%\bin\javaw.exe"
if not exist "%JAVA%" set "JAVA=C:\Program Files\Android\Android Studio\jbr\bin\javaw.exe"
if not exist "%JAVA%" for %%I in (javaw.exe) do set "JAVA=%%~$PATH:I"
if not exist "%JAVA%" (
  echo Java 21 ou mais novo nao encontrado. Instale um JDK ou o Android Studio.
  pause
  exit /b 1
)

rem Pasta temporaria simples (o Java 25 falha ao criar sockets internos em caminhos curtos 8.3 ou virtualizados).
set "TMPD=%USERPROFILE%\.apex\tmp"
if not exist "%TMPD%" mkdir "%TMPD%"
set "TEMP=%TMPD%"
set "TMP=%TMPD%"
start "" "%JAVA%" "-Djava.io.tmpdir=%TMPD%" --enable-native-access=ALL-UNNAMED -jar "%JAR%"
