@echo off
setlocal
set "PANDORA_BASE=%~dp0"
set "PANDORA_JAR=%PANDORA_BASE%lib\pandora.jar"
if not exist "%PANDORA_JAR%" set "PANDORA_JAR=%PANDORA_BASE%cli\target\pandora.jar"
if not exist "%PANDORA_JAR%" (
  echo Pandora has not been built. Run mvnw.cmd verify first. 1>&2
  exit /b 2
)
if not defined PANDORA_DOTNET_ADAPTER (
  if exist "%PANDORA_BASE%dotnet\Pandora.Adapter.DotNet.dll" (
    set "PANDORA_DOTNET_ADAPTER=%PANDORA_BASE%dotnet\Pandora.Adapter.DotNet.dll"
  ) else (
    set "PANDORA_DOTNET_ADAPTER=%PANDORA_BASE%adapters\dotnet\bin\Debug\net8.0\Pandora.Adapter.DotNet.dll"
  )
)
java -cp "%PANDORA_JAR%;%PANDORA_EXTRA_CLASSPATH%" io.cyborgcode.pandora.cli.Main %*
exit /b %ERRORLEVEL%
