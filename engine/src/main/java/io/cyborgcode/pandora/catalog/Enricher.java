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

   private static final String COMPASS = "compass";

   private static final String LANGUAGE = "language";

   private static final String OPTIONS = "options";

   private static final String QUALIFIED_NAME = "qualifiedName";

   private static final String RESOLVED_OPTIONS = "resolvedOptions";

   private static final String SOURCE = "source";

   private static final String TRUNCATED = "truncated";

   private static final String VALUES = "values";

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
            types.put(key(symbol, symbol.path(QUALIFIED_NAME).asText()), symbol);
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
      return context.path(LANGUAGE).asText()
            + ":"
            + context.path("module").asText()
            + ":"
            + type;
   }

   private void apply(ObjectNode target, ObjectNode context, String type, boolean parameter) {
      JsonNode guidance = inheritedGuidance(target, context, type, parameter);
      boolean imported = context.path("origin").asText().startsWith("catalog:");
      if (guidance.isNull() || guidance.isMissingNode()) {
         return;
      }
      LinkedHashSet<String> values = new LinkedHashSet<>();
      boolean snapshotTruncated =
            imported
                  && target.path(RESOLVED_OPTIONS).path(TRUNCATED).asBoolean()
                  && java.util.stream.StreamSupport.stream(
                              guidance.path(OPTIONS).spliterator(), false)
                        .anyMatch(rule -> !rule.path(SOURCE).asText("").isBlank());
      int snapshotTotal =
            snapshotTruncated ? target.path(RESOLVED_OPTIONS).path("total").asInt() : 0;
      boolean failed = false;
      boolean deferred = false;
      for (JsonNode rule : guidance.path(OPTIONS)) {
         for (JsonNode value : rule.path(VALUES)) {
            values.add(value.asText());
         }
         String resource = rule.path(SOURCE).asText("");
         String enumType = rule.path("enumType").asText("");
         failed |= !addResourceOptions(target, context, rule, resource, imported, values);
         failed |= !addEnumOptions(context, rule, enumType, values);
         deferred |= addImplementationOptions(context, rule, values);
      }
      var state = target.putObject(RESOLVED_OPTIONS);
      boolean truncated = snapshotTruncated || values.size() > maxOptions;
      state.put("status", optionStatus(failed, truncated, deferred && values.isEmpty()));
      state.put("total", Math.max(snapshotTotal, values.size()));
      state.put(TRUNCATED, snapshotTruncated || values.size() > maxOptions);
      ArrayNode available = state.putArray(VALUES);
      values.stream().limit(maxOptions).forEach(available::add);
      if (imported) {
         // Producer examples are already embedded and keep their producer origin.
         return;
      }
      addExamples(target, context, guidance);
   }

   private void addExamples(ObjectNode target, ObjectNode context, JsonNode guidance) {
      ArrayNode examples = target.putArray("usageExamples");
      for (JsonNode reference : guidance.path("examples")) {
         String resource = reference.asText();
         try {
            JsonNode document = resources.load(resource);
            JsonNode items = document.isArray() ? document : document.path("items");
            if (!items.isArray()) {
               throw new IOException("Expected example items");
            }
            addMatchingExamples(examples, items, context);
         } catch (Exception e) {
            diagnostics.add(
                  Diagnostic.error(
                        "EXAMPLES_RESOURCE",
                        "Unable to resolve examples from resource '" + resource + "'",
                        context.path(SOURCE).path("path").asText()));
         }
      }
   }

   private JsonNode inheritedGuidance(
         ObjectNode target, ObjectNode context, String type, boolean parameter) {
      JsonNode guidance = target.path(COMPASS);
      JsonNode typeSymbol = findType(context, type);
      boolean canInherit =
            parameter || Set.of("FIELD", "PROPERTY").contains(context.path("kind").asText());
      if (canInherit && (guidance.isNull() || guidance.isMissingNode()) && typeSymbol != null) {
         guidance = typeSymbol.path(COMPASS);
         if (!guidance.isMissingNode() && !guidance.isNull()) {
            target.set(COMPASS, guidance.deepCopy());
            target.put("guidanceOrigin", typeSymbol.path("id").asText());
         }
      }
      if (canInherit
            && guidance.isObject()
            && typeSymbol != null
            && typeSymbol.path(COMPASS).isObject()) {
         ObjectNode merged = guidance.deepCopy();
         JsonNode defaults = typeSymbol.path(COMPASS);
         mergeDefaults(merged, defaults);
         target.set(COMPASS, merged);
         guidance = merged;
      }
      return guidance;
   }

   private static void mergeDefaults(ObjectNode merged, JsonNode defaults) {
      for (String field : List.of(OPTIONS, "extra", "tags")) {
         if ((merged.path(field).isMissingNode() || merged.path(field).isEmpty())
               && defaults.hasNonNull(field)) {
            merged.set(field, defaults.get(field).deepCopy());
         }
      }
   }

   private boolean addResourceOptions(ObjectNode target, ObjectNode context, JsonNode rule,
         String resource, boolean imported, Set<String> values) {
      if (resource.isBlank()) {
         return true;
      }
      if (imported) {
         target.path(RESOLVED_OPTIONS).path(VALUES).forEach(v -> values.add(v.asText()));
         return true;
      }
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
         return true;
      } catch (IOException | RuntimeException e) {
         diagnostics.add(Diagnostic.error("OPTIONS_RESOURCE",
               "Unable to resolve options from resource '" + resource + "'",
               context.path(SOURCE).path("path").asText()));
         return false;
      }
   }

   private boolean addEnumOptions(JsonNode context, JsonNode rule, String enumType,
         Set<String> values) {
      if (enumType.isBlank()) {
         return true;
      }
      JsonNode enumeration = findType(context, enumType);
      if (enumeration == null || !enumeration.path("kind").asText().equals("ENUM")) {
         diagnostics.add(Diagnostic.error("OPTIONS_ENUM",
               "Enum is not available in this module: " + enumType,
               context.path(SOURCE).path("path").asText()));
         return false;
      }
      enumeration.path("enumValues").forEach(v -> values.add(render(rule, enumeration, v.asText())));
      return true;
   }

   private boolean addImplementationOptions(JsonNode context, JsonNode rule, Set<String> values) {
      String contract = rule.path("implementationsOf").asText("");
      if (contract.isBlank()) {
         return false;
      }
      int before = values.size();
      allTypes.stream()
            .filter(t -> t.path(LANGUAGE).asText().equals(context.path(LANGUAGE).asText()))
            .filter(t -> t.path("kind").asText().equals("ENUM"))
            .filter(t -> implementsContract(t, contract, new HashSet<>()))
            .sorted(Comparator.comparing(t -> t.path("id").asText()))
            .forEach(t -> t.path("enumValues")
                  .forEach(v -> values.add(render(rule, t, v.asText()))));
      return before == values.size();
   }

   private static String optionStatus(boolean failed, boolean truncated, boolean deferred) {
      if (failed) {
         return "failed";
      }
      if (truncated) {
         return TRUNCATED;
      }
      return deferred ? "deferred" : "complete";
   }

   private static void addMatchingExamples(ArrayNode examples, JsonNode items, JsonNode context)
         throws IOException {
      int count = 0;
      for (JsonNode entry : items) {
         String id = entry.path("id").asText("");
         if (id.isEmpty()
               || id.equals(context.path("id").asText())
               || id.equals(context.path(QUALIFIED_NAME).asText())
               || id.equals(context.path("signature").asText())) {
            if (++count > 50) {
               throw new IOException("Too many matching example entries");
            }
            examples.add(entry.deepCopy());
         }
      }
   }

   private String render(JsonNode rule, JsonNode enumeration, String value) {
      return rule.path("render").asText("NAME").equals("QUALIFIED")
            ? enumeration.path(QUALIFIED_NAME).asText() + "." + value
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
                              t.path(LANGUAGE)
                                          .asText()
                                          .equals(context.path(LANGUAGE).asText())
                                    && t.path(QUALIFIED_NAME)
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
