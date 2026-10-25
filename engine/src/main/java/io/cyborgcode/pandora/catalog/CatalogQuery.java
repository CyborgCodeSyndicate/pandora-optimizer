package io.cyborgcode.pandora.catalog;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.cyborgcode.pandora.config.Configs;
import io.cyborgcode.pandora.util.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class CatalogQuery {
    private final Path root;
    private final ObjectNode catalog;

    public CatalogQuery(Path root) throws IOException {
        this.root = root.toRealPath();
        catalog = new CatalogStore(this.root).read();
    }

    public ObjectNode search(
            String domain, String query, String category, String tag, int limit, boolean includeBad)
            throws IOException {
        if (limit < 1 || limit > 50) throw new IOException("limit must be between 1 and 50");
        String[] words = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode symbol : catalog.path("symbols")) {
            if (!belongs(symbol, domain)) continue;
            JsonNode guidance = symbol.path(domain.equals("teacher") ? "lesson" : "compass");
            if (!includeBad
                    && domain.equals("teacher")
                    && guidance.path("level").asText().equals("BAD")) continue;
            if (!category.isBlank() && !guidance.path("category").asText().equals(category))
                continue;
            if (!tag.isBlank() && !contains(guidance.path("tags"), tag)) continue;
            String searchable =
                    (symbol.path("qualifiedName").asText() + " " + guidance.toString())
                            .toLowerCase(Locale.ROOT);
            if (Arrays.stream(words).allMatch(searchable::contains)) matches.add(symbol);
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
                                            "qualifiedName",
                                            "signature",
                                            "lesson",
                                            "compass",
                                            "origin"))
                                if (s.hasNonNull(field)) summary.set(field, s.get(field));
                            items.add(summary);
                        });
        return result;
    }

    public ObjectNode show(String domain, String id, boolean source) throws IOException {
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode s : catalog.path("symbols"))
            if (belongs(s, domain)
                    && (s.path("id").asText().equals(id)
                            || s.path("qualifiedName").asText().equals(id))) found.add(s);
        if (found.size() != 1)
            throw new IOException(
                    found.isEmpty()
                            ? "Symbol not found"
                            : "Ambiguous name; use the exact symbol ID");
        ObjectNode result = envelope();
        ObjectNode symbol = found.getFirst().deepCopy();
        result.set("item", symbol);
        if (source) {
            if (!symbol.path("origin").asText().equals("workspace"))
                result.put("sourceStatus", "unavailable-for-imported-catalog");
            else {
                JsonNode location = symbol.path("source");
                Path path = root.resolve(location.path("path").asText()).normalize().toRealPath();
                if (!path.startsWith(root)) throw new IOException("Source path escapes workspace");
                if (Files.size(path) > 1024 * 1024)
                    throw new IOException("Source exceeds display size limit");
                byte[] bytes = Files.readAllBytes(path);
                if (!Hashes.sha256(bytes).equals(location.path("sha256").asText())) {
                    result.put("sourceStatus", "stale");
                    return result;
                }
                List<String> lines = Files.readAllLines(path);
                int start = location.path("startLine").asInt() - 1,
                        end = Math.min(location.path("endLine").asInt(), start + 200);
                if (start < 0 || end > lines.size() || end < start)
                    throw new IOException("Invalid source range");
                result.put("source", String.join("\n", lines.subList(start, end)));
                result.put(
                        "sourceStatus",
                        end < location.path("endLine").asInt() ? "truncated" : "complete");
            }
        }
        return result;
    }

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
        n.set("diagnostics", catalog.path("diagnostics"));
        return n;
    }

    public ObjectNode bundle() {
        return catalog.deepCopy();
    }

    private static boolean belongs(JsonNode s, String domain) {
        return domain.equals("teacher")
                ? s.hasNonNull("lesson")
                : s.hasNonNull("compass") || CatalogStore.hasParameterGuidance(s);
    }

    private static boolean contains(JsonNode array, String text) {
        for (JsonNode value : array) if (value.asText().equals(text)) return true;
        return false;
    }

    private static int rank(JsonNode s, String domain) {
        if (domain.equals("teacher"))
            return switch (s.path("lesson").path("level").asText()) {
                case "EXCELLENT" -> 0;
                case "GOOD" -> 1;
                default -> 2;
            };
        return switch (s.path("compass").path("preference").asText()) {
            case "PREFER" -> 0;
            case "AVOID" -> 2;
            case "FORBID" -> 3;
            default -> 1;
        };
    }
}
