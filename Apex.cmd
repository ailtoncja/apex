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
rem Cache de inicializacao (Java 25 ou mais novo): o JVM guarda as classes ja carregadas e o que aprendeu de como o app roda. O app abre em
rem ~1,4 s em vez de ~2,4 s e some quase toda a travadinha da primeira vez que cada tela aparece. O cache e feito uma vez por .jar: se nao
rem existe, um "treino" em segundo plano (janela fora da tela, dados a parte, fecha sozinho em ~30 s) passeia pelas telas e monta o cache; o app
rem abre normal enquanto isso. Em Java mais antigo nada disso acontece.
set "AOTOPT="
set "JBIN="
for %%I in ("%JAVA%") do set "JBIN=%%~dpI"
set "AOTOK="
if exist "%JBIN%..\release" findstr /r /c:"JAVA_VERSION=\"2[5-9]\." /c:"JAVA_VERSION=\"[3-9][0-9]\." "%JBIN%..\release" >nul 2>&1 && set "AOTOK=1"
if not defined AOTOK goto abrir

set "AOTDIR=%USERPROFILE%\.apex\aot"
for %%F in ("%JAR%") do set "AOTKEY=%%~nF-%%~zF-%%~tF"
for %%F in ("%JAVA%") do set "AOTKEY=%AOTKEY%-%%~zF-%%~tF"
set "AOTKEY=%AOTKEY:/=-%"
set "AOTKEY=%AOTKEY::=-%"
set "AOTKEY=%AOTKEY: =_%"
set "AOTFILE=%AOTDIR%\%AOTKEY%.aot"
if exist "%AOTFILE%" set "AOTOPT=-XX:AOTCache=%AOTFILE%"
if exist "%AOTFILE%" goto abrir
rem Um treino que travou ou foi cortado deixa a marca: vale por um dia.
if exist "%AOTDIR%" forfiles /p "%AOTDIR%" /m "*.treinando" /d -1 /c "cmd /c del @path" >nul 2>&1
if exist "%AOTFILE%.treinando" goto abrir
if not exist "%AOTDIR%" mkdir "%AOTDIR%"
del /q "%AOTDIR%\*.aot" >nul 2>&1
echo treino> "%AOTFILE%.treinando"
start "" /min cmd /c ""%JAVA%" "-XX:AOTCacheOutput=%AOTFILE%" "-Djava.io.tmpdir=%TMPD%" --enable-native-access=ALL-UNNAMED -Dapex.train=1 "-Dapex.data=%TMPD%\treino" -jar "%JAR%" & del "%AOTFILE%.treinando""

:abrir
start "" "%JAVA%" %AOTOPT% "-Djava.io.tmpdir=%TMPD%" --enable-native-access=ALL-UNNAMED -jar "%JAR%"
