package io.cyborgcode.pandora.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cyborgcode.pandora.model.Diagnostic;
import io.cyborgcode.pandora.resources.ResourceLoader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves declarative options and examples; never rewrites callable parameter order.
 *
 * @author Cyborg Code Syndicate 💍👨💻
 */
public final class Enricher {

   private final ResourceLoader resources;

   private final int maxOptions;

   private final List<Diagnostic> diagnostics;

   private final Map<String, JsonNode> types = new HashMap<>();

   private final List<JsonNode> allTypes = new ArrayList<>();

   /** Configures resource resolution, option limits and the destination for diagnostics. */
   public Enricher(ResourceLoader resources, int maxOptions, List<Diagnostic> diagnostics) {
      this.resources = resources;
      this.maxOptions = maxOptions;
      this.diagnostics = diagnostics;
   }

   /** Resolves options and examples against local and imported symbol facts. */
   public void enrich(ArrayNode symbols) {
      for (JsonNode symbol : symbols) {
         if (Set.of("CLASS", "INTERFACE", "RECORD", "STRUCT", "ENUM", "ANNOTATION")
               .contains(symbol.path("kind").asText())) {
            types.put(key(symbol, symbol.path("qualifiedName").asText()), symbol);
            allTypes.add(symbol);
         }
      }
      for (JsonNode node : symbols) {
         ObjectNode symbol = (ObjectNode) node;
         apply(symbol, symbol, symbol.path("returnType").asText(), false);
         for (JsonNode parameter : symbol.path("parameters")) {
            apply((ObjectNode) parameter, symbol, parameter.path("type").asText(), true);
         }
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
      JsonNode typeSymbol = findType(context, type);
      boolean imported = context.path("origin").asText().startsWith("catalog:");
      boolean canInherit =
            parameter || Set.of("FIELD", "PROPERTY").contains(context.path("kind").asText());
      if (canInherit && (guidance.isNull() || guidance.isMissingNode()) && typeSymbol != null) {
         guidance = typeSymbol.path("compass");
         if (!guidance.isMissingNode() && !guidance.isNull()) {
            target.set("compass", guidance.deepCopy());
            target.put("guidanceOrigin", typeSymbol.path("id").asText());
         }
      }
      if (canInherit
            && guidance.isObject()
            && typeSymbol != null
            && typeSymbol.path("compass").isObject()) {
         ObjectNode merged = guidance.deepCopy();
         JsonNode defaults = typeSymbol.path("compass");
         for (String field : List.of("options", "extra", "tags")) {
            if (merged.path(field).isMissingNode() || merged.path(field).isEmpty()) {
               if (defaults.hasNonNull(field)) {
                  merged.set(field, defaults.get(field).deepCopy());
               }
            }
         }
         target.set("compass", merged);
         guidance = merged;
      }
      if (guidance.isNull() || guidance.isMissingNode()) {
         return;
      }
      LinkedHashSet<String> values = new LinkedHashSet<>();
      boolean snapshotTruncated =
            imported
                  && target.path("resolvedOptions").path("truncated").asBoolean()
                  && java.util.stream.StreamSupport.stream(
                              guidance.path("options").spliterator(), false)
                        .anyMatch(rule -> !rule.path("source").asText("").isBlank());
      int snapshotTotal =
            snapshotTruncated ? target.path("resolvedOptions").path("total").asInt() : 0;
      boolean failed = false;
      boolean deferred = false;
      for (JsonNode rule : guidance.path("options")) {
         for (JsonNode value : rule.path("values")) {
            values.add(value.asText());
         }
         String resource = rule.path("source").asText("");
         String enumType = rule.path("enumType").asText("");
         if (!resource.isBlank() && imported) {
            target.path("resolvedOptions").path("values").forEach(v -> values.add(v.asText()));
         } else if (!resource.isBlank()) {
            try {
               JsonNode document = resources.load(resource);
               String pointer = rule.path("pointer").asText("");
               JsonNode selected = pointer.isEmpty() ? document : document.at(pointer);
               if (!selected.isArray()) {
                  throw new IOException("Options pointer must select an array");
               }
               for (JsonNode value : selected) {
                  if (!value.isTextual()) {
                     throw new IOException("Options must be strings");
                  }
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
         }
         if (!enumType.isBlank()) {
            JsonNode enumeration = findType(context, enumType);
            if (enumeration == null || !enumeration.path("kind").asText().equals("ENUM")) {
               failed = true;
               diagnostics.add(
                     Diagnostic.error(
                           "OPTIONS_ENUM",
                           "Enum is not available in this module: " + enumType,
                           context.path("source").path("path").asText()));
            } else {
               enumeration
                     .path("enumValues")
                     .forEach(v -> values.add(render(rule, enumeration, v.asText())));
            }
         }
         String contract = rule.path("implementationsOf").asText("");
         if (!contract.isBlank()) {
            int before = values.size();
            allTypes.stream()
                  .filter(
                        t ->
                              t.path("language")
                                    .asText()
                                    .equals(context.path("language").asText()))
                  .filter(t -> t.path("kind").asText().equals("ENUM"))
                  .filter(t -> implementsContract(t, contract, new HashSet<>()))
                  .sorted(Comparator.comparing(t -> t.path("id").asText()))
                  .forEach(
                        t ->
                              t.path("enumValues")
                                    .forEach(
                                          v ->
                                                values.add(
                                                      render(
                                                            rule,
                                                            t,
                                                            v.asText()))));
            if (before == values.size()) {
               deferred = true;
            }
         }
      }
      var state = target.putObject("resolvedOptions");
      state.put(
            "status",
            failed
                  ? "failed"
                  : snapshotTruncated || values.size() > maxOptions
                        ? "truncated"
                        : deferred && values.isEmpty() ? "deferred" : "complete");
      state.put("total", Math.max(snapshotTotal, values.size()));
      state.put("truncated", snapshotTruncated || values.size() > maxOptions);
      ArrayNode available = state.putArray("values");
      values.stream().limit(maxOptions).forEach(available::add);
      if (imported) {
         // Producer examples are already embedded and keep their producer origin.
         return;
      }
      ArrayNode examples = target.putArray("usageExamples");
      for (JsonNode reference : guidance.path("examples")) {
         String resource = reference.asText();
         try {
            JsonNode document = resources.load(resource);
            JsonNode items = document.isArray() ? document : document.path("items");
            if (!items.isArray()) {
               throw new IOException("Expected example items");
            }
            int count = 0;
            for (JsonNode entry : items) {
               String id = entry.path("id").asText("");
               if (id.isEmpty()
                     || id.equals(context.path("id").asText())
                     || id.equals(context.path("qualifiedName").asText())
                     || id.equals(context.path("signature").asText())) {
                  if (++count > 50) {
                     throw new IOException("Too many matching example entries");
                  }
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

   private String render(JsonNode rule, JsonNode enumeration, String value) {
      return rule.path("render").asText("NAME").equals("QUALIFIED")
            ? enumeration.path("qualifiedName").asText() + "." + value
            : value;
   }

   private JsonNode findType(JsonNode context, String name) {
      name = name.replaceAll("<.*>", "");
      final String qualified = name;
      JsonNode local = types.get(key(context, name));
      if (local != null) {
         return local;
      }
      List<JsonNode> matches =
            allTypes.stream()
                  .filter(
                        t ->
                              t.path("language")
                                          .asText()
                                          .equals(context.path("language").asText())
                                    && t.path("qualifiedName")
                                          .asText()
                                          .equals(qualified))
                  .toList();
      return matches.size() == 1 ? matches.getFirst() : null;
   }

   private boolean implementsContract(JsonNode type, String contract, Set<String> visited) {
      if (!visited.add(type.path("id").asText())) {
         return false;
      }
      for (JsonNode parent : type.path("supertypes")) {
         String name = parent.asText().replaceAll("<.*>", "");
         if (name.equals(contract)) {
            return true;
         }
         JsonNode base = findType(type, name);
         if (base != null && implementsContract(base, contract, visited)) {
            return true;
         }
      }
      return false;
   }
}
