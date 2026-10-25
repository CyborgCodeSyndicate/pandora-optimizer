#!/usr/bin/env sh
set -eu
PANDORA_BASE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PANDORA_JAR="$PANDORA_BASE/lib/pandora.jar"
if [ ! -f "$PANDORA_JAR" ]; then PANDORA_JAR="$PANDORA_BASE/cli/target/pandora.jar"; fi
if [ ! -f "$PANDORA_JAR" ]; then
  echo "Pandora has not been built. Run ./mvnw verify first." >&2
  exit 2
fi
if [ -z "${PANDORA_DOTNET_ADAPTER:-}" ]; then
  if [ -f "$PANDORA_BASE/dotnet/Pandora.Adapter.DotNet.dll" ]; then
    PANDORA_DOTNET_ADAPTER="$PANDORA_BASE/dotnet/Pandora.Adapter.DotNet.dll"
  else
    PANDORA_DOTNET_ADAPTER="$PANDORA_BASE/adapters/dotnet/bin/Debug/net8.0/Pandora.Adapter.DotNet.dll"
  fi
  export PANDORA_DOTNET_ADAPTER
fi
exec java -cp "$PANDORA_JAR:${PANDORA_EXTRA_CLASSPATH:-}" io.cyborgcode.pandora.cli.Main "$@"
