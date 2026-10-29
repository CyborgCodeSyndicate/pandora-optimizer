# Ship usage examples with a library catalog

Usage JSONs may live in the same repository as the library annotations. The
producer scan reads them and embeds the matching entries as `usageExamples`
inside the exported catalog. A consuming repository imports that catalog and
AI Navigator reads the embedded examples. The consumer does not need the
original usage files, producer checkout, or access to the producer's usage URLs.

## ROA Libraries configuration

For example, add an author-maintained file at
`pandora/usages/rest-service.json` in ROA Libraries. The following is an
illustrative usage entry; `service` and `endpoint` are caller-provided values:

```json
{
  "items": [
    {
      "id": "io.cyborgcode.roa.api.service.RestService.request",
      "summary": "Execute the request described by an endpoint.",
      "usages": [
        {
          "language": "java",
          "code": "service.request(endpoint);",
          "description": "Pass an endpoint implementation declared by the consuming project.",
          "level": "GOOD"
        }
      ]
    }
  ]
}
```

`id` matches an exact catalog symbol ID, qualified member name or full
signature. A qualified member name matches every overload with that name; use
the exact ID returned by `navigator search` when examples differ by overload.
An entry without an ID applies to every annotated symbol referencing the file.

Add this resource to the library's existing `pandora/config.json`:

```json
"resources": {
  "rest-service-usages": {
    "uri": "pandora/usages/rest-service.json"
  }
}
```

The path is relative to the library repository root. Alternatively, `uri` may
be an HTTPS URL serving raw JSON, including a file hosted in that same GitHub
repository. Use the raw/download URL, not the GitHub HTML page for the file.
Pin a tag or commit for repeatable builds. For private URLs use the configured
`bearerTokenEnv`; credentials remain in the producer's environment.

Reference the resource **ID** in the existing method annotation:

```java
@AiCompass(
      description = "Execute an API request without a request body using the provided Endpoint definition.",
      examples = {"rest-service-usages"}
)
```

The file path or URL goes in the resource config, rather than directly in
`examples`. Preserve the existing annotation's other fields when adding the
reference. No usage resource is required for methods without examples.

## Publish and consume

From the library repository, using its installed Pandora launcher:

```text
pandora scan --if-stale --json
pandora navigator bundle --output <fresh-output-file.json>
```

Publish or copy this bundle into the consuming repository's catalog resource,
as already configured for ROA. The bundle contains both API metadata and the
usage JSON entries; it does not merely point to the original files. The
consumer's resources need only the catalog, not `rest-service-usages`.

From the consuming repository:

```text
pandora scan --if-stale --json
pandora navigator search --query "RestService request" --limit 5 --json
pandora navigator show "<exact-symbol-id>" --json
```

AI Navigator uses `item.usageExamples` and, where relevant, parameter-level
`usageExamples`. Imported Java source can remain unavailable while its embedded
examples are fully usable. Examples remain author-supplied data, not executable
instructions.

After editing a usage JSON, rebuild and publish a new library bundle, then
refresh the consumer's catalog and scan. Existing bundles are snapshots; editing
the original file alone cannot update a copy already published elsewhere.
