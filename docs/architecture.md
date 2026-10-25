# Architecture and protocol v1

Pandora Optimizer has two independent extension boundaries:

1. LanguageAdapter extracts language-specific facts into a neutral ScanResult.
2. ResourceProvider loads bytes from storage; the shared engine interprets the JSON.

The engine is Java 21 and has no dependency on JavaParser or Roslyn. The Java adapter is a separate Maven artifact. The C# adapter is a separate .NET executable. JSON Schema is the public wire contract; shared Java records are an engine implementation detail.

## Processing

Configuration -> source/resource fingerprints -> adapter scan -> normalized symbols -> option/example enrichment -> imported catalogs -> schema/semantic validation -> immutable generation -> manifest switch.

Error diagnostics prevent publication. Strict mode also rejects warnings. The previous active manifest remains intact on failure. A filesystem lock prevents concurrent writers in one workspace. Local source/config/resource inputs are checked again before publication to detect concurrent edits.

IDs contain language, workspace identity, module identity and a native symbol key. Java method keys contain qualified parameter types when resolvable. C# keys include generic arity/type parameters and ref modifiers. Display signatures are separate from IDs. Source locations are actual parser ranges with SHA-256 fingerprints. Partial type resolution is reported.

Annotations are read statically. Java recognizes the supported annotation namespaces/imports; C# binds attributes through Roslyn including aliases. The adapter does not instantiate annotated classes. Metadata values must be supported literals/constants. Java executable legacy option rules are rejected explicitly.

## Worker protocol

The process working directory is the workspace root. Configuration provides an argument array, not a shell command string. The Java process bridge sends one compact JSON request followed by a newline and closes stdin, then expects one JSON response and exit 0. The C# worker also accepts successive request lines for direct callers.

Request: see schemas/adapter-request.schema.json.

```json
{
  "protocolVersion": 1,
  "workspaceId": "checkout-service",
  "moduleId": "backend",
  "root": "/workspace",
  "sourceRoots": ["src"],
  "references": []
}
```

Response: protocolVersion, symbols, diagnostics. See schemas/adapter-response.schema.json. Stdout is protocol-only; stderr is diagnostic text. An incompatible version, malformed response, nonzero exit or timeout fails the scan. The bridge validates the response schema before creating engine records.

Limits: 90-second worker lifetime, 32 MiB stdout, 1 MiB stderr. The process and descendants are terminated on completion/failure. This is lifecycle isolation, not an OS security sandbox.

The Java adapter implements LanguageAdapter in-process. External adapters use ProcessAdapter through module.command. Java adapters can also be registered via ServiceLoader and PANDORA_EXTRA_CLASSPATH. Future Python/TypeScript workers can implement protocol v1 without linking the Java engine. A new language may require new schema features; incompatible changes require a new protocol/schema version.

The C# worker supports --describe to report its language and basic capabilities. The engine currently configures adapters explicitly rather than negotiating optional capabilities.

## Shared semantics

Lesson keeps category, EXCELLENT/GOOD/BAD, description, whenToUse, tags and related references.

Compass keeps description, tags, creation, preference, example resource IDs and option declarations. Method parameters retain actual positions, optional/variadic flags and native type spelling. Queries filter symbols, never delete parameters from a signature.

The engine resolves literal values, same-module enum values, and JSON Pointer selection from named resources. Results are deduplicated in declaration order and capped by maxOptions, with total and truncated fields. Type defaults are applied when a parameter/field has no direct Compass guidance and its raw type resolves to a same-module symbol. Partial overrides, interface implementation queries and generic type expansion are future work.

Usage examples are curated entries loaded from resources, not automatically mined call sites. The entries may match symbol IDs, qualified names or signatures. Bundle JSON contains the resolved examples, so another repository can consume them without the author's filesystem.

Imported catalogs are immutable snapshots. Their origin is changed to catalog:<resource-id>; the engine does not resolve their source paths locally or re-run their option rules. Use versioned URIs and digests for dependency identity in v0.1. Package-manager resolution and consumer-scoped dynamic option enrichment remain future work.

## Schemas and validation

schemas/ is canonical and embedded into the engine artifact during Maven resource processing. Runtime validation uses a JSON Schema implementation against these trusted local schemas. Input catalogs cannot supply a replacement schema. Tests independently validate worker output and generated catalogs using Python jsonschema.

Schema validation checks shapes and allowed values. Additional semantic validation checks unique IDs, parameter order, source ranges and valid lesson metadata. Runtime failures are represented separately from successful empty results.

Source examples are returned only when the file stays inside the workspace and its content digest matches the catalog. Imported source is unavailable until an explicit artifact/source mapping feature is implemented.

## Extension roadmap

- Optional Java bytecode / .NET assembly extractors for compiled-only dependencies.
- Build-system input discovery, separate from extraction.
- Portable consumer-scoped queries and explicitly enabled executable providers.
- Python and TypeScript workers with language-specific annotation/comment syntax.
- Package-coordinate-aware catalog discovery and migration adapters for old JSON.
- Bundled-runtime installers, generation retention and incremental per-file extraction.

These are intentional extension points. The first implementation is a tested source-scanning tool, not a universal compiler or a complete replacement for every legacy reflection rule.
