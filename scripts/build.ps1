param([switch]$Integration)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    & .\mvnw.cmd -B -ntp verify
    if ($LASTEXITCODE -ne 0) { throw 'Java verification failed' }
    & dotnet restore PandoraOptimizer.sln --locked-mode
    if ($LASTEXITCODE -ne 0) { throw '.NET locked restore failed' }
    & dotnet test PandoraOptimizer.sln --no-restore --collect:'XPlat Code Coverage'
    if ($LASTEXITCODE -ne 0) { throw '.NET verification failed' }
    & python scripts/check_skills.py
    if ($LASTEXITCODE -ne 0) { throw 'Skill validation failed' }
    if ($Integration) {
        & python -m unittest discover -s tests/integration -v
        if ($LASTEXITCODE -ne 0) { throw 'Integration tests failed' }
    }
} finally {
    Pop-Location
}
