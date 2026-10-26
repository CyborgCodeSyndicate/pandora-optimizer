package io.cyborgcode.pandora.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cyborgcode.pandora.util.Hashes;
import io.cyborgcode.pandora.util.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Immutable generations with a single manifest switch. Failed writes retain the previous
 * generation.
 *
 * @author Cyborg Code Syndicate 💍👨💻
 */
public final class CatalogStore {

   private final Path output;

   /** Selects the generated catalog directory for a workspace. */
   public CatalogStore(Path root) {
      output = root.resolve("pandora/generated");
   }

   /** Atomically publishes the catalog and its Teacher and Navigator indexes. */
   public void write(ObjectNode catalog) throws IOException {
      Files.createDirectories(output.resolve("generations"));
      String generation = UUID.randomUUID().toString();
      Path dir = output.resolve("generations").resolve(generation);
      Files.createDirectory(dir);
      Json.MAPPER.writeValue(dir.resolve("catalog.json").toFile(), catalog);
      ArrayNode teacher = Json.MAPPER.createArrayNode();
      ArrayNode navigator = Json.MAPPER.createArrayNode();
      Map<String, ArrayNode> categories = new TreeMap<>();
      for (JsonNode symbol : catalog.path("symbols")) {
         if (symbol.hasNonNull("lesson")) {
            teacher.add(summary(symbol));
            categories
                  .computeIfAbsent(
                        symbol.path("lesson").path("category").asText(),
                        k -> Json.MAPPER.createArrayNode())
                  .add(symbol);
         }
         if (symbol.hasNonNull("compass") || hasParameterGuidance(symbol)) {
            navigator.add(summary(symbol));
         }
      }
      Files.createDirectories(dir.resolve("teacher/categories"));
      Files.createDirectories(dir.resolve("navigator"));
      ObjectNode categoryIndex = Json.MAPPER.createObjectNode();
      for (var entry : categories.entrySet()) {
         String file = Hashes.sha256(entry.getKey()) + ".json";
         categoryIndex.put(entry.getKey(), "categories/" + file);
         Json.MAPPER.writeValue(
               dir.resolve("teacher/categories").resolve(file).toFile(), entry.getValue());
      }
      ObjectNode teacherIndex = Json.MAPPER.createObjectNode();
      teacherIndex.set("categories", categoryIndex);
      teacherIndex.set("items", teacher);
      Json.MAPPER.writeValue(dir.resolve("teacher/index.json").toFile(), teacherIndex);
      Json.MAPPER.writeValue(dir.resolve("navigator/index.json").toFile(), navigator);
      Json.MAPPER.writeValue(
            dir.resolve("diagnostics.json").toFile(), catalog.path("diagnostics"));
      ObjectNode manifest = Json.MAPPER.createObjectNode();
      manifest.put("schemaVersion", 1);
      manifest.put("generation", generation);
      manifest.put("catalog", "generations/" + generation + "/catalog.json");
      manifest.put("fingerprint", catalog.path("fingerprint").asText());
      manifest.put("localFingerprint", catalog.path("localFingerprint").asText());
      manifest.put("generatedAt", catalog.path("generatedAt").asText());
      Path tmp = Files.createTempFile(output, "manifest-", ".tmp");
      try {
         Json.MAPPER.writeValue(tmp.toFile(), manifest);
         Files.move(
               tmp,
               output.resolve("manifest.json"),
               StandardCopyOption.ATOMIC_MOVE,
               StandardCopyOption.REPLACE_EXISTING);
      } finally {
         Files.deleteIfExists(tmp);
      }
   }

   /** Reads the catalog selected by its validated, workspace-confined manifest. */
   public ObjectNode read() throws IOException {
      JsonNode manifest = Json.MAPPER.readTree(output.resolve("manifest.json").toFile());
      String generation = manifest.path("generation").asText();
      if (!generation.matches("[0-9a-f-]{36}")) {
         throw new IOException("Invalid generation");
      }
      Path path = output.resolve("generations").resolve(generation).resolve("catalog.json");
      JsonNode node = Json.MAPPER.readTree(path.toFile());
      if (!(node instanceof ObjectNode object)) {
         throw new IOException("Invalid catalog");
      }
      return object;
   }

   /** Reports whether any parameter carries API guidance. */
   public static boolean hasParameterGuidance(JsonNode symbol) {
      for (JsonNode parameter : symbol.path("parameters")) {
         if (parameter.hasNonNull("compass")) {
            return true;
         }
      }
      return false;
   }

   private static ObjectNode summary(JsonNode symbol) {
      ObjectNode n = Json.MAPPER.createObjectNode();
      for (String key :
            List.of(
                  "id",
                  "language",
                  "module",
                  "name",
                  "kind",
                  "qualifiedName",
                  "signature",
                  "lesson",
                  "compass")) {
         if (symbol.hasNonNull(key)) {
            n.set(key, symbol.get(key));
         }
      }
      return n;
   }
}
