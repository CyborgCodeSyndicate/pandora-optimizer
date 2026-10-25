package io.cyborgcode.pandora.catalog;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.cyborgcode.pandora.model.Diagnostic;
import io.cyborgcode.pandora.resources.ResourceLoader;
import java.io.IOException;
import java.util.*;

/** Resolves declarative options and examples; never rewrites callable parameter order. */
public final class Enricher {
    private final ResourceLoader resources;
    private final int maxOptions;
    private final List<Diagnostic> diagnostics;
    private final Map<String, JsonNode> types = new HashMap<>();

    public Enricher(ResourceLoader resources, int maxOptions, List<Diagnostic> diagnostics) {
        this.resources = resources;
        this.maxOptions = maxOptions;
        this.diagnostics = diagnostics;
    }

    public void enrich(ArrayNode symbols) {
        for (JsonNode symbol : symbols)
            if (Set.of("CLASS", "INTERFACE", "RECORD", "STRUCT", "ENUM", "ANNOTATION")
                    .contains(symbol.path("kind").asText()))
                types.put(key(symbol, symbol.path("qualifiedName").asText()), symbol);
        for (JsonNode node : symbols) {
            ObjectNode symbol = (ObjectNode) node;
            apply(symbol, symbol, symbol.path("returnType").asText(), false);
            for (JsonNode parameter : symbol.path("parameters"))
                apply((ObjectNode) parameter, symbol, parameter.path("type").asText(), true);
        }
    }

    private String key(JsonNode context, String type) {
        return context.path("language").asText()
                + ":"
                + context.path("module").asText()
                + ":"
                + type;
    }

    private void apply(ObjectNode target, ObjectNode context, String type, boolean parameter) {
        JsonNode guidance = target.path("compass");
        JsonNode typeSymbol = types.get(key(context, type));
        boolean canInherit =
                parameter || Set.of("FIELD", "PROPERTY").contains(context.path("kind").asText());
        if (canInherit && (guidance.isNull() || guidance.isMissingNode()) && typeSymbol != null) {
            guidance = typeSymbol.path("compass");
            if (!guidance.isMissingNode() && !guidance.isNull()) {
                target.set("compass", guidance.deepCopy());
                target.put("guidanceOrigin", typeSymbol.path("id").asText());
            }
        }
        if (guidance.isNull() || guidance.isMissingNode()) return;
        LinkedHashSet<String> values = new LinkedHashSet<>();
        boolean failed = false;
        for (JsonNode rule : guidance.path("options")) {
            for (JsonNode value : rule.path("values")) values.add(value.asText());
            String resource = rule.path("source").asText(""),
                    enumType = rule.path("enumType").asText("");
            if (!resource.isBlank())
                try {
                    JsonNode document = resources.load(resource);
                    String pointer = rule.path("pointer").asText("");
                    JsonNode selected = pointer.isEmpty() ? document : document.at(pointer);
                    if (!selected.isArray())
                        throw new IOException("Options pointer must select an array");
                    for (JsonNode value : selected) {
                        if (!value.isTextual()) throw new IOException("Options must be strings");
                        values.add(value.asText());
                    }
                } catch (Exception e) {
                    failed = true;
                    diagnostics.add(
                            Diagnostic.error(
                                    "OPTIONS_RESOURCE",
                                    "Unable to resolve options from resource '" + resource + "'",
                                    context.path("source").path("path").asText()));
                }
            if (!enumType.isBlank()) {
                JsonNode enumeration = types.get(key(context, enumType));
                if (enumeration == null || !enumeration.path("kind").asText().equals("ENUM")) {
                    failed = true;
                    diagnostics.add(
                            Diagnostic.error(
                                    "OPTIONS_ENUM",
                                    "Enum is not available in this module: " + enumType,
                                    context.path("source").path("path").asText()));
                } else enumeration.path("enumValues").forEach(v -> values.add(v.asText()));
            }
        }
        var state = target.putObject("resolvedOptions");
        state.put(
                "status",
                failed ? "failed" : values.size() > maxOptions ? "truncated" : "complete");
        state.put("total", values.size());
        state.put("truncated", values.size() > maxOptions);
        ArrayNode available = state.putArray("values");
        values.stream().limit(maxOptions).forEach(available::add);
        ArrayNode examples = target.putArray("usageExamples");
        for (JsonNode reference : guidance.path("examples")) {
            String resource = reference.asText();
            try {
                JsonNode document = resources.load(resource),
                        items = document.isArray() ? document : document.path("items");
                if (!items.isArray()) throw new IOException("Expected example items");
                int count = 0;
                for (JsonNode entry : items) {
                    String id = entry.path("id").asText("");
                    if (id.isEmpty()
                            || id.equals(context.path("id").asText())
                            || id.equals(context.path("qualifiedName").asText())
                            || id.equals(context.path("signature").asText())) {
                        if (++count > 50)
                            throw new IOException("Too many matching example entries");
                        examples.add(entry.deepCopy());
                    }
                }
            } catch (Exception e) {
                diagnostics.add(
                        Diagnostic.error(
                                "EXAMPLES_RESOURCE",
                                "Unable to resolve examples from resource '" + resource + "'",
                                context.path("source").path("path").asText()));
            }
        }
    }
}
