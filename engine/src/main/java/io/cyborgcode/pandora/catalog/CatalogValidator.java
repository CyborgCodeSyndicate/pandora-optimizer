package io.cyborgcode.pandora.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import io.cyborgcode.pandora.model.Diagnostic;
import io.cyborgcode.pandora.util.Schemas;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates catalog schemas, symbol identities and parameter ordering.
 *
 * @author Cyborg Code Syndicate 💍👨💻
 */
public final class CatalogValidator {

   private static final String SCHEMA = "SCHEMA";

   private CatalogValidator() {}

   /** Returns schema and semantic diagnostics for a catalog without modifying it. */
   public static List<Diagnostic> validate(JsonNode catalog) {
      List<Diagnostic> errors = new ArrayList<>();
      if (!Schemas.valid("catalog", catalog)) {
         errors.add(
               Diagnostic.error(
                     SCHEMA, "Catalog does not satisfy the bundled v1 JSON Schema", null));
         return errors;
      }
      if (catalog.path("schemaVersion").asInt() != 1 || !catalog.path("symbols").isArray()) {
         errors.add(
               Diagnostic.error(
                     SCHEMA, "Expected catalog schemaVersion 1 and symbols array", null));
         return errors;
      }
      Set<String> ids = new HashSet<>();
      for (JsonNode symbol : catalog.path("symbols")) {
         String id = symbol.path("id").asText();
         if (id.isBlank() || !ids.add(id)) {
            errors.add(
                  Diagnostic.error("DUPLICATE_ID", "Missing or duplicate symbol ID", null));
         }
         validateFields(symbol, errors);
         validateSource(symbol, id, errors);
         validateParameters(symbol, id, errors);
         validateLesson(symbol, id, errors);
      }
      return errors;
   }

   private static void validateFields(JsonNode symbol, List<Diagnostic> errors) {
      for (String field :
            List.of(
                  "language",
                  "module",
                  "kind",
                  "name",
                  "qualifiedName",
                  "signature",
                  "resolution")) {
         if (!symbol.path(field).isTextual() || symbol.path(field).asText().isBlank()) {
            errors.add(Diagnostic.error(SCHEMA, "Missing symbol field " + field, null));
         }
      }
   }

   private static void validateSource(JsonNode symbol, String id, List<Diagnostic> errors) {
      JsonNode source = symbol.path("source");
      if (!source.isNull()
            && !source.isMissingNode()
            && (source.path("startLine").asInt() < 1
                  || source.path("endLine").asInt() < source.path("startLine").asInt())) {
         errors.add(
               Diagnostic.error("SOURCE_RANGE", "Invalid source range for " + id, null));
      }
   }

   private static void validateParameters(JsonNode symbol, String id, List<Diagnostic> errors) {
      int i = 0;
      for (JsonNode parameter : symbol.path("parameters")) {
         if (parameter.path("index").asInt(-1) != i++
               || parameter.path("name").asText().isBlank()) {
            errors.add(
                  Diagnostic.error(
                        "PARAMETERS",
                        "Parameter order/name is invalid for " + id,
                        null));
         }
      }
   }

   private static void validateLesson(JsonNode symbol, String id, List<Diagnostic> errors) {
      JsonNode lesson = symbol.path("lesson");
      if (!lesson.isMissingNode() && !lesson.isNull()
            && (lesson.path("category").asText().isBlank()
               || lesson.path("description").asText().isBlank()
               || !Set.of("EXCELLENT", "GOOD", "BAD")
                     .contains(lesson.path("level").asText()))) {
         errors.add(
               Diagnostic.error(
                     "LESSON",
                     "Lesson requires category, description and valid level: " + id,
                     null));
      }
   }
}
