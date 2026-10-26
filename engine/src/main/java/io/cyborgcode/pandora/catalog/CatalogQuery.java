package io.cyborgcode.pandora.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cyborgcode.pandora.config.Configs;
import io.cyborgcode.pandora.util.Hashes;
import io.cyborgcode.pandora.util.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Searches and retrieves guidance from a validated workspace catalog.
 *
 * @author Cyborg Code Syndicate 💍👨💻
 */
public final class CatalogQuery {

   private static final String COMPASS = "compass";

   private static final String DIAGNOSTICS = "diagnostics";

   private static final String LESSON = "lesson";

   private static final String QUALIFIED_NAME = "qualifiedName";

   private static final String SOURCE_STATUS = "sourceStatus";

   private static final String TEACHER = "teacher";

   private final Path root;

   private final ObjectNode catalog;

   /** Opens and validates the most recent catalog in the workspace. */
   public CatalogQuery(Path root) throws IOException {
      this.root = root.toRealPath();
      catalog = new CatalogStore(this.root).read();
   }

   /** Returns ranked lexical matches with freshness and diagnostic metadata. */
   public ObjectNode search(
         String domain, String query, String category, String tag, int limit, boolean includeBad)
         throws IOException {
      if (limit < 1 || limit > 50) {
         throw new IOException("limit must be between 1 and 50");
      }
      String[] words = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
      List<JsonNode> matches = new ArrayList<>();
      for (JsonNode symbol : catalog.path("symbols")) {
         if (matches(symbol, domain, words, category, tag, includeBad)) {
            matches.add(symbol);
         }
      }
      matches.sort(
            Comparator.<JsonNode>comparingInt(s -> rank(s, domain))
                  .thenComparing(s -> s.path("id").asText()));
      ObjectNode result = envelope();
      result.put("total", matches.size());
      result.put("truncated", matches.size() > limit);
      ArrayNode items = result.putArray("items");
      matches.stream()
            .limit(limit)
            .forEach(
                  s -> {
                     ObjectNode summary = Json.MAPPER.createObjectNode();
                     for (String field :
                           List.of(
                                 "id",
                                 "language",
                                 "module",
                                 "kind",
                                 QUALIFIED_NAME,
                                 "signature",
                                 LESSON,
                                 COMPASS,
                                 "origin")) {
                        if (s.hasNonNull(field)) {
                           summary.set(field, s.get(field));
                        }
                     }
                     items.add(summary);
                  });
      return result;
   }

   private static boolean matches(
         JsonNode symbol, String domain, String[] words, String category, String tag,
         boolean includeBad) {
      if (!belongs(symbol, domain)) {
         return false;
      }
      JsonNode guidance = symbol.path(domain.equals(TEACHER) ? LESSON : COMPASS);
      if (!includeBad && domain.equals(TEACHER)
            && guidance.path("level").asText().equals("BAD")) {
         return false;
      }
      if ((!category.isBlank() && !guidance.path("category").asText().equals(category))
            || (!tag.isBlank() && !contains(guidance.path("tags"), tag))) {
         return false;
      }
      String searchable = (symbol.path(QUALIFIED_NAME).asText() + " " + guidance)
            .toLowerCase(Locale.ROOT);
      return Arrays.stream(words).allMatch(searchable::contains);
   }

   /** Returns one exact symbol and optionally verifies and reads its local source. */
   public ObjectNode show(String domain, String id, boolean source) throws IOException {
      List<JsonNode> found = new ArrayList<>();
      for (JsonNode s : catalog.path("symbols")) {
         if (belongs(s, domain)
               && (s.path("id").asText().equals(id)
                     || s.path(QUALIFIED_NAME).asText().equals(id))) {
            found.add(s);
         }
      }
      if (found.size() != 1) {
         throw new IOException(
               found.isEmpty()
                     ? "Symbol not found"
                     : "Ambiguous name; use the exact symbol ID");
      }
      ObjectNode result = envelope();
      ObjectNode symbol = found.getFirst().deepCopy();
      result.set("item", symbol);
      if (source) {
         addSource(result, symbol);
      }
      return result;
   }

   private void addSource(ObjectNode result, JsonNode symbol) throws IOException {
      if (!symbol.path("origin").asText().equals("workspace")) {
         result.put(SOURCE_STATUS, "unavailable-for-imported-catalog");
         return;
      }
      JsonNode location = symbol.path("source");
      Path path = root.resolve(location.path("path").asText()).normalize().toRealPath();
      if (!path.startsWith(root)) {
         throw new IOException("Source path escapes workspace");
      }
      if (Files.size(path) > 1024 * 1024) {
         throw new IOException("Source exceeds display size limit");
      }
      byte[] bytes = Files.readAllBytes(path);
      if (!Hashes.sha256(bytes).equals(location.path("sha256").asText())) {
         result.put(SOURCE_STATUS, "stale");
         return;
      }
      List<String> lines = Files.readAllLines(path);
      int start = location.path("startLine").asInt() - 1;
      int end = Math.min(location.path("endLine").asInt(), start + 200);
      if (start < 0 || end > lines.size() || end < start) {
         throw new IOException("Invalid source range");
      }
      result.put("source", String.join("\n", lines.subList(start, end)));
      result.put(SOURCE_STATUS, end < location.path("endLine").asInt() ? "truncated" : "complete");
   }

   /** Reports catalog freshness, offline state and bounded diagnostics. */
   public ObjectNode envelope() throws IOException {
      ObjectNode n = Json.MAPPER.createObjectNode();
      n.put("schemaVersion", 1);
      n.put("generatedAt", catalog.path("generatedAt").asText());
      boolean stale;
      try {
         stale =
               !catalog.path("localFingerprint")
                     .asText()
                     .equals(ScanEngine.localFingerprint(root, Configs.read(root)));
      } catch (IOException e) {
         stale = true;
      }
      n.put("freshness", stale ? "stale" : "current-local-inputs");
      n.put("remoteFreshness", "as-of-last-scan");
      n.put("offline", catalog.path("offline").asBoolean());
      n.put("diagnosticCount", catalog.path(DIAGNOSTICS).size());
      ArrayNode diagnostics = n.putArray(DIAGNOSTICS);
      for (int i = 0; i < Math.min(20, catalog.path(DIAGNOSTICS).size()); i++) {
         diagnostics.add(catalog.path(DIAGNOSTICS).get(i));
      }
      return n;
   }

   /** Returns a detached catalog suitable for importing into another workspace. */
   public ObjectNode bundle() {
      return catalog.deepCopy();
   }

   private static boolean belongs(JsonNode s, String domain) {
      return domain.equals(TEACHER)
            ? s.hasNonNull(LESSON)
            : s.hasNonNull(COMPASS) || CatalogStore.hasParameterGuidance(s);
   }

   private static boolean contains(JsonNode array, String text) {
      for (JsonNode value : array) {
         if (value.asText().equals(text)) {
            return true;
         }
      }
      return false;
   }

   private static int rank(JsonNode s, String domain) {
      if (domain.equals(TEACHER)) {
         return switch (s.path(LESSON).path("level").asText()) {
            case "EXCELLENT" -> 0;
            case "GOOD" -> 1;
            default -> 2;
         };
      }
      return switch (s.path(COMPASS).path("preference").asText()) {
         case "PREFER" -> 0;
         case "AVOID" -> 2;
         case "FORBID" -> 3;
         default -> 1;
      };
   }
}
