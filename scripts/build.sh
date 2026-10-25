#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
./mvnw -B -ntp verify
dotnet restore PandoraOptimizer.sln --locked-mode
dotnet test PandoraOptimizer.sln --no-restore --collect:"XPlat Code Coverage"
python3 scripts/check_skills.py
if [ "${1:-}" = "--integration" ]; then
  python3 -m unittest discover -s tests/integration -v
fi
