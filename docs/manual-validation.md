# Manual validation: Java and .NET

Validation date: 25 October 2026. These are local repositories retained at C:\Users\vikto\Documents\Code. They are independent of the implementation's temporary integration workspaces.

| Repository | What it verifies |
|---|---|
| pandora-optimizer-test-java | Compilable Java application, 4 unit tests, annotations in main/test source, two overloads, examples and options |
| pandora-optimizer-test-dotnet | Compilable C# application, 4 unit tests, library/test modules, annotations, overloads, examples and options |
| pandora-optimizer-test-empty-java | Initialization and strict scans with no Java source |
| pandora-optimizer-test-empty-dotnet | Initialization and strict scans with no C# source |
| pandora-optimizer-test-https | Both adapters resolving a parameter's values from real HTTPS, digest pinning and offline cache |

Each repository has README.md and Test-Pandora.ps1. None is automatically deleted.

## Build Pandora first

```powershell
Set-Location C:\Users\vikto\Documents\Code\pandora-optimizer
.\mvnw.cmd -B -ntp install
dotnet restore PandoraOptimizer.sln --locked-mode
dotnet test PandoraOptimizer.sln --no-restore
python -m pip install -r tests/integration/requirements.txt
python -m unittest discover -s tests/integration -v
```

Install places the annotation SDK in the local Maven cache for the Java test application. The C# application uses a sibling project reference. The wrapper downloads Maven if necessary. The CLI requires Java 21+; .NET scanning uses .NET 8.

## Java application

```powershell
Set-Location C:\Users\vikto\Documents\Code\pandora-optimizer-test-java
mvn -B -ntp verify
.\Test-Pandora.ps1
```

Expected: four application tests and the Pandora script pass. Use the repository README for individual queries and safe freshness/failure experiments.

## C# application

```powershell
Set-Location C:\Users\vikto\Documents\Code\pandora-optimizer-test-dotnet
dotnet test tests/Payments.Tests/Payments.Tests.csproj
.\Test-Pandora.ps1
```

Build before scanning: the test module's explicit metadata references point to the compiled library and xUnit DLLs. Main application source is scanned directly. Explicit using directives keep these source-only fixtures independent of generated implicit-usings files.

Expected: four application tests and the same Pandora checks pass.

## Empty repositories

```powershell
Set-Location C:\Users\vikto\Documents\Code\pandora-optimizer-test-empty-java
.\Test-Pandora.ps1

Set-Location C:\Users\vikto\Documents\Code\pandora-optimizer-test-empty-dotnet
.\Test-Pandora.ps1
```

Expected: zero symbols, zero query results and successful strict validation. init has already run; re-running init on an existing configuration intentionally fails rather than overwriting it.

## Real HTTPS and offline cache

```powershell
Set-Location C:\Users\vikto\Documents\Code\pandora-optimizer-test-https
.\Test-Pandora.ps1
```

Expected: both language adapters expose TypeScript, Microsoft, compiler, language, javascript as the parameter values. The script first uses HTTPS, then repeats with --offline, then leaves an online catalog. The remote JSON is TypeScript's public, versioned package metadata; this is a resource test, not a TypeScript adapter.

The hash is pinned. If the remote bytes change or GitHub is unreachable, the online scan should fail. That is not a successful refresh; offline mode must be explicitly requested and needs an existing cache.

## What the assertion scripts check

- EXCELLENT service lesson selected by category/tag.
- BAD lesson excluded by default and included with --include-bad.
- GOOD lesson discovered in test source.
- Two separate method overloads.
- All three original parameters, in order.
- Repository JSON values sandbox/live and enum values STANDARD/EXPRESS.
- The expected curated usage entry.
- Strict schema/semantic validation and fresh local inputs.
- Empty-source behavior.
- Real remote JSON, digest verification and offline provenance.

The automated integration suite also verifies importing a bundled framework catalog into a consumer, source freshness, failed scans retaining the prior manifest, worker schema conformance, and rejecting invalid imported catalogs.

## Scope of the result

This validates the implemented Java and C# source-scanning workflows on this Windows machine. CI is configured for Windows, Linux and macOS, but those hosted jobs have not been run locally. Compiled-only JAR/DLL discovery, arbitrary legacy executable rules and automatic build-system/package-manager discovery remain follow-up work, as described in README.md.
