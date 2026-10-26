# Pandora Optimizer

A local Java CLI that turns author-curated code annotations into context for **AI Teacher** and **AI Navigator**. The first implementation supports **Java source** and **C# source** through separate adapters. It makes no AI API calls.

- **AiLesson** explains good patterns and anti-patterns inside a repository.
- **AiCompass** explains how to use an API, including methods, constructors, fields/properties and parameters.
- **AiCompassOptions** supplies literal values, enum values, or values selected from a named JSON resource.
- Resources can live in the repository or at an HTTPS endpoint. The transport SPI allows additional providers.
- Published JSON catalogs carry extracted guidance, resolved options and embedded usage examples into another repository.

## Build and run

Development requirements: JDK 21+, .NET SDK 8.0.400 or later in the 8.0 feature band, and Python 3.12+ only for packaging/integration checks. Maven is pinned by the wrapper. Java annotation consumers can use Java 17+. The C# annotation package targets .NET 8.

The Maven reactor inherits `io.cyborgcode.utilities:parent-pom`. For these
development builds, `shared-static-data` and `parent-pom` use the published
utilities version `0.0.0-SNAPSHOT-a41f211-40328682308`. Configure authenticated
access to the utilities GitHub Packages repository in your Maven settings;
the shared CI setup action uses package credentials when configured and falls
back to the GitHub Actions token. The utilities packages must allow this
repository's workflows to read them.
Dependency/plugin versions and quality gates come from that parent. See
[Maven standards and pipelines](docs/maven-standards.md) for bootstrap,
inheritance checks and release prerequisites.

Windows:

```powershell
.\mvnw.cmd -B -ntp verify
dotnet restore PandoraOptimizer.sln --locked-mode
dotnet test PandoraOptimizer.sln --no-restore
python -m pip install -r tests/integration/requirements.txt
python -m unittest discover -s tests/integration -v

.\pandora.cmd scan --strict
.\pandora.cmd teacher search --category service --tag retry
.\pandora.cmd navigator search --query payment
.\pandora.cmd navigator show "java:pandora-optimizer-demo:java-demo:demo.Payments#submit(java.lang.String,java.lang.String,demo.Mode)" --include-source
```

Linux/macOS: use `./mvnw` and `./pandora.sh`. The checked-in `pandora/config.json` scans both fixture projects. Commands emit JSON; progress/errors go to stderr. `--json` is accepted explicitly.

The complete developer check is `scripts/build.ps1 -Integration` or `scripts/build.sh --integration`, after installing the integration requirements. Do not run the integration suite while rebuilding the .NET worker on Windows: the running worker holds its DLLs open.

## Use in another repository

Build once, then invoke the launcher with that repository as the workspace:

```text
pandora --root /path/to/repository init --language java,csharp
pandora --root /path/to/repository doctor
pandora --root /path/to/repository scan --if-stale
pandora --root /path/to/repository teacher search --query retry
pandora --root /path/to/repository navigator search --query payment
```

Here `pandora` means `pandora.cmd` on Windows or `pandora.sh` on Unix, optionally placed on PATH. Use only the languages present in that repository. Edit generated `pandora/config.json` to name modules and source roots. Every relative path is workspace-relative, including reference paths and command arguments. Default exclusions include .git, target, bin, obj, node_modules, generated and cache directories.

Add the small annotation SDK to the target project:

- Java: `io.cyborgcode.pandora:pandora-annotations:0.1.0`. Run `mvnw install` in this repository to install it locally; publishing to a Maven registry is a later release action.
- C#: use a project reference to `annotations/dotnet/Pandora.Annotations.csproj`, or create a package with `dotnet pack annotations/dotnet/Pandora.Annotations.csproj` and use a local NuGet feed.

Java dependencies required for symbol resolution go in a module's `references` as JAR paths. C# references are assembly paths. Scanning does not restore packages, run Maven/MSBuild, execute source generators, or load the target application. Provide generated source explicitly if it is part of the intended scan.

The launcher configures its bundled/development C# worker through `PANDORA_DOTNET_ADAPTER`. Alternatively a module can define `"command":["dotnet","/absolute/path/Pandora.Adapter.DotNet.dll"]`. A custom language adapter uses the same command array and JSON protocol.

## Author guidance

Java:

```java
@AiLesson(category = "service", level = LessonLevel.EXCELLENT,
    description = "Use the same idempotency key across retries.",
    whenToUse = "When retrying payment submissions.", tags = {"retry"})
@AiCompass(description = "Submit a payment.", preference = Preference.PREFER,
    examples = {"payment-examples"})
public Receipt submit(
    @AiCompass(description = "Stable retry key.") String key,
    @AiCompassOptions(source = "payment-options", pointer = "/environments")
    String environment) { /* ... */ }
```

C#:

```csharp
[AiLesson(Category="service", Level=LessonLevel.EXCELLENT,
    Description="Use the same idempotency key across retries.", Tags=new[]{"retry"})]
[AiCompass(Description="Submit a payment.", Preference=Preference.PREFER,
    Examples=new[]{"payment-examples"})]
public Receipt Submit(
    [AiCompass(Description="Stable retry key.")] string key,
    [AiCompassOptions(Source="payment-options", Pointer="/environments")]
    string environment) { /* ... */ }
```

Examples use the proposed application type `Receipt`; complete runnable scan fixtures are under `fixtures/`. Import `io.cyborgcode.pandora.annotations.*` in Java or `Pandora.Optimizer.Annotations` in C#.

Literal options: Java `@AiCompassOptions(values={"fast","slow"})`; C# `[AiCompassOptions(Values=new[]{"fast","slow"})]`.

Enum options: Java `@AiCompassOptions(enumType=Mode.class)`; C# `[AiCompassOptions(EnumType=typeof(Mode))]`. The enum's source must be included in that module in this first release.

## Repository files and HTTPS resources

Annotations refer to resource IDs, not physical URLs. These settings can move without changing application code:

```json
{
  "resources": {
    "payment-options": {
      "uri": "pandora/examples/payment-options.json"
    },
    "payment-examples": {
      "uri": "https://artifacts.example.com/payments/1.2.0/examples.json",
      "bearerTokenEnv": "PANDORA_ARTIFACT_TOKEN"
    }
  }
}
```

This is a configuration fragment to merge into `pandora/config.json`. The host is illustrative; replace it with your own artifact endpoint.

Options JSON:

```json
{"environments":["sandbox","live"]}
```

Example JSON retains the familiar `items` / `usages` structure:

```json
{
  "items": [{
    "id": "demo.Payments.submit",
    "summary": "Use a stable key.",
    "usages": [{
      "language": "java",
      "code": "client.submit(key, \"sandbox\");",
      "description": "Reuse key when retrying.",
      "level": "GOOD",
      "contextHint": "Payment retry"
    }]
  }]
}
```

An entry ID can match an exact symbol ID, qualified name, or signature; omit it only for examples intentionally shared by every annotation referencing that resource. Use exact IDs for overloaded methods.

For a private or presigned endpoint, set `uriEnv` instead of `uri`. Add `sha256` to pin the exact UTF-8 response bytes. Credentials are resolved from the environment and are not written into catalogs. URLs are not included in resource errors. See [resource providers](docs/resources.md) for CI publication, cache behavior and extension points.

## Output and queries

`pandora/generated/manifest.json` points to an immutable generation containing:

- `catalog.json`: canonical normalized and enriched metadata.
- `teacher/index.json` and category files.
- `navigator/index.json`.
- `diagnostics.json`.

Generation switches are atomic. Scan errors preserve the last good generation. Old generations remain on disk; automated retention cleanup is not implemented. Never commit generated/cache directories by default.

`scan --if-stale` refreshes configured remote resources, hashes inputs, and skips extraction when unchanged. `scan --offline` uses previously validated remote cache entries; local resources are still read directly. Search/show do not access the network. Their `freshness` field checks local inputs; `remoteFreshness: as-of-last-scan` deliberately does not claim a remote URL is still current.

`teacher search` excludes BAD lessons unless `--include-bad` is supplied. Search is lexical with category/tag filters; no semantic model is involved. `show --include-source` verifies the source hash and returns at most 200 lines. Related references are preserved but are not automatically followed.

`navigator bundle --output framework-v1.json` exports a catalog with resolved examples/options embedded. It refuses stale input and existing output files. Consumers configure the bundle as a resource and list its ID in `catalogs`:

```json
{
  "schemaVersion": 1,
  "workspaceId": "consumer",
  "modules": [],
  "resources": {"payments-1.2.0": {"uri": "pandora/framework-v1.json"}},
  "catalogs": ["payments-1.2.0"],
  "maxOptions": 50
}
```

Pin remote bundles with SHA-256 and use immutable versioned URLs. This version does not resolve package registries automatically or execute arbitrary producer code. Declarative implementationsOf rules are re-resolved against consumer enum facts. Catalog origin is preserved, and imported source paths are never treated as local paths.

Exit codes: 0 success; 2 invalid configuration, unavailable adapter/resource or command error; 3 scan/strict validation failure. `validate` also returns 3 for stale local inputs. Warnings are allowed unless `--strict` is supplied.

## Modules and extension points

| Directory | Responsibility |
|---|---|
| annotations/java, annotations/dotnet | Application-facing annotation SDKs |
| engine | Neutral model, config, schemas, resource SPI, enrichment, persistence and queries |
| adapters/java | JavaParser extraction |
| adapters/dotnet | Roslyn extraction and JSON worker |
| cli | Java command-line entrypoint |
| schemas | Canonical versioned JSON contracts, embedded into the engine at build time |
| skills | Provider-independent AI Teacher and AI Navigator instructions |
| fixtures, tests | Java/C# samples, unit and cross-process integration tests |

See [architecture and protocol](docs/architecture.md). A new language implements the protocol; a new storage system implements `ResourceProvider`. Neither requires changes to the annotation semantics or CLI workflows.

## Skills and distribution

Install both provider-neutral packages with `python scripts/install_skills.py --target <repository> --providers codex,claude`. Other hosts can copy the same skill folders and set PANDORA_HOME to the runtime directory. The packages contain no provider API calls or embedded credentials.

After verification, `python scripts/package.py` creates `dist/pandora-optimizer-0.1.0.zip`, including the CLI, C# worker, schemas, documentation and skills. This first distribution requires an installed Java 21+ runtime and .NET 8 runtime. It does not bundle a JVM or a self-contained .NET runtime.

## Current boundaries

Implemented: Java/C# source extraction, curated lessons, rich method/parameter guidance, constructors, Java fields and C# properties, literal/enum/resource options, examples, file/HTTPS providers, offline cache, digest pinning, schemas, catalog export/import, freshness, query CLI and skills.

Deliberate follow-up work: compiled-only JAR/DLL extraction; Maven/Gradle/MSBuild dependency discovery; arbitrary executable rules; complete cross-language type-default merging; structured comment annotations; Python/TypeScript adapters; reference traversal; package-coordinate resolution; runtime-bundled installers.

Java recognizes legacy `@Pandora` / `@PandoraOptions` / `@AiLesson` names from the old packages. It does not guarantee complete binary or JSON compatibility. Executable legacy rules produce an explicit migration error. Map old `exampleFilesPath` values to configured resource IDs or migrate them to `AiCompass.examples`.

The initial C# syntax level is C# 12. Source-only semantic analysis can be partial when references or generated code are absent. Read diagnostics; use strict scanning in producer CI.

## ROA integration

See [the migrated framework, example setup and real agent tests](docs/roa-integration.md).

## Development

For retained, separately built Java/.NET example repositories and exact manual checks, see [manual validation](docs/manual-validation.md).

Java formatting is checked by Spotless during Maven validation; apply it with `mvnw spotless:apply`. Format C# with `dotnet format whitespace PandoraOptimizer.sln`. NuGet lockfiles are committed.

CI verifies Java, C#, schema conformance and end-to-end workflows on Windows, Linux and macOS. Java coverage reports are under each module's `target/site/jacoco`; .NET reports are under `tests/dotnet/TestResults`. Local verification on one OS does not imply the hosted CI matrix has run.
