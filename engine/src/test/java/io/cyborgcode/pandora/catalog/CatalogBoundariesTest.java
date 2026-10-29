package io.cyborgcode.pandora.catalog;

import io.cyborgcode.pandora.config.Config;
import io.cyborgcode.pandora.config.Configs;
import io.cyborgcode.pandora.model.*;
import io.cyborgcode.pandora.spi.LanguageAdapter;
import io.cyborgcode.pandora.util.Hashes;
import io.cyborgcode.pandora.util.Json;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CatalogBoundariesTest {
    @TempDir Path root;
    void config(List<Config.Module> modules, Map<String, Config.Resource> resources, List<String> catalogs) throws Exception {
        Files.createDirectories(root.resolve("pandora"));
        Json.MAPPER.writeValue(root.resolve("pandora/config.json").toFile(), new Config(1, "boundaries", modules, resources, catalogs, 50));
    }
    Config.Module module(String language) {
        return new Config.Module("main", language, List.of("src"), List.of(), List.of());
    }
    Symbol symbol(String id, Lesson lesson, String originPath) throws Exception {
        return new Symbol(id, "fake", "main", "METHOD", "run", "Example.run", "run()", null, "void", List.of(), new SourceLocation(originPath, 1, 1, Hashes.sha256(Files.readAllBytes(root.resolve(originPath)))) , lesson, new Compass("Run the API", List.of(), "AUTO", "PREFER", List.of(), List.of()), List.of(), "resolved");
    }
    LanguageAdapter adapter(int protocol, List<Symbol> symbols) {
        return new LanguageAdapter() {
            public String language() { return "fake"; }
            public ScanResult scan(ScanRequest request) { return new ScanResult(protocol, symbols, List.of()); }
        };
    }
    void source() throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Example.txt"), "line one\nline two\n");
        config(List.of(module("fake")), Map.of(), List.of());
    }
    @Test void rejectsUnknownAdapterAndProtocolMismatchBeforePublishing() throws Exception {
        source();
        assertTrue(assertThrows(IOException.class, () -> new ScanEngine(List.of()).scan(root, false, false, false)).getMessage().contains("No adapter"));
        assertTrue(assertThrows(IOException.class, () -> new ScanEngine(List.of(adapter(2, List.of()))).scan(root, false, false, false)).getMessage().contains("protocol mismatch"));
        var wrong = new Symbol("wrong", "other", "main", "CLASS", "Other", "Other", "Other", null, null, List.of(), null, null, null, List.of(), "resolved");
        assertTrue(assertThrows(IOException.class, () -> new ScanEngine(List.of(adapter(1, List.of(wrong)))).scan(root, false, false, false)).getMessage().contains("wrong module/language"));
        assertThrows(IOException.class, () -> new CatalogStore(root).read());
    }
    @Test void refusesConcurrentPublicationAndChangingInputs() throws Exception {
        source();
        Files.createDirectories(root.resolve("pandora/cache"));
        try (var channel = FileChannel.open(root.resolve("pandora/cache/scan.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.lock()) {
            assertThrows(Exception.class, () -> new ScanEngine(List.of(adapter(1, List.of()))).scan(root, false, false, false));
        }
        var changing = new LanguageAdapter() {
            public String language() { return "fake"; }
            public ScanResult scan(ScanRequest request) throws IOException {
                Files.writeString(root.resolve("src/Example.txt"), "changed");
                return new ScanResult(1, List.of(), List.of());
            }
        };
        assertTrue(assertThrows(IOException.class, () -> new ScanEngine(List.of(changing)).scan(root, false, false, false)).getMessage().contains("Inputs changed"));
    }
    @Test void referenceChangesInvalidateTheFingerprint() throws Exception {
        source();
        Files.writeString(root.resolve("reference.jar"), "first");
        config(List.of(new Config.Module("main", "fake", List.of("src"), List.of("reference.jar"), List.of("reference.jar"))), Map.of(), List.of());
        var config = Configs.read(root);
        String first = ScanEngine.localFingerprint(root, config);
        Files.writeString(root.resolve("reference.jar"), "second");
        assertNotEquals(first, ScanEngine.localFingerprint(root, config));
    }
    @Test void rejectsInvalidDependencyCatalog() throws Exception {
        Files.writeString(root.resolve("catalog.json"), "{\"symbols\":[]}");
        config(List.of(), Map.of("framework", new Config.Resource("catalog.json", null, null, null)), List.of("framework"));
        assertThrows(IOException.class, () -> new ScanEngine(List.of()).scan(root, false, false, false));
    }
    @Test void queriesFilterBadLessonsHandleAmbiguityAndReturnDetachedBundles() throws Exception {
        source();
        var good = symbol("good", new Lesson("service", "GOOD", "Preferred pattern", "", List.of("retry"), null), "src/Example.txt");
        var bad = symbol("bad", new Lesson("service", "BAD", "Avoid this pattern", "", List.of("retry"), null), "src/Example.txt");
        var excellent = symbol("excellent", new Lesson("service", "EXCELLENT", "Excellent pattern", "", List.of("retry"), null), "src/Example.txt");
        new ScanEngine(List.of(adapter(1, List.of(good, bad, excellent)))).scan(root, false, false, false);
        var query = new CatalogQuery(root);
        assertEquals(2, query.search("teacher", "pattern", "service", "retry", 5, false).path("total").asInt());
        assertEquals(3, query.search("teacher", "", "", "", 5, true).path("total").asInt());
        assertEquals("excellent", query.search("teacher", "pattern", "service", "retry", 1, false).path("items").get(0).path("id").asText());
        assertEquals(0, query.search("teacher", "", "missing", "", 5, false).path("total").asInt());
        assertEquals(0, query.search("teacher", "", "", "missing", 5, false).path("total").asInt());
        assertThrows(IOException.class, () -> query.show("teacher", "Example.run", false));
        assertThrows(IOException.class, () -> query.search("teacher", "", "", "", 51, false));
        query.bundle().removeAll();
        assertEquals(3, query.bundle().path("symbols").size());
        Files.delete(root.resolve("pandora/config.json"));
        assertEquals("stale", query.envelope().path("freshness").asText());
    }
    @Test void validatorRejectsDuplicateIdentityBadLessonAndParameterOrder() throws Exception {
        source();
        var valid = symbol("same", new Lesson("service", "GOOD", "Description", "", null, null), "src/Example.txt");
        var catalog = new ScanEngine(List.of(adapter(1, List.of(valid)))).scan(root, false, false, false);
        var symbols = (com.fasterxml.jackson.databind.node.ArrayNode) catalog.path("symbols");
        symbols.add(symbols.get(0).deepCopy());
        var first = (com.fasterxml.jackson.databind.node.ObjectNode) symbols.get(0);
        first.withObject("lesson").put("category", " ");
        first.putArray("parameters").addObject().put("index", 2).put("name", "value").put("type", "string").put("optional", false).put("variadic", false).putNull("compass");
        var codes = CatalogValidator.validate(catalog).stream().map(Diagnostic::code).toList();
        assertTrue(codes.contains("DUPLICATE_ID"));
        assertTrue(codes.contains("LESSON"));
        assertTrue(codes.contains("PARAMETERS"));
    }
    @Test void sourceDisplayIsBoundedAndNeverTreatsImportedPathsAsLocal() throws Exception {
        source();
        Files.writeString(root.resolve("src/Example.txt"), "source line\n".repeat(300));
        var valid = symbol("bounded", new Lesson("service", "GOOD", "Description", "", null, null), "src/Example.txt");
        var catalog = new ScanEngine(List.of(adapter(1, List.of(valid)))).scan(root, false, false, false);
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) catalog.path("symbols").get(0);
        ((com.fasterxml.jackson.databind.node.ObjectNode) node.path("source")).put("endLine", 300);
        var store = new CatalogStore(root);
        store.write(catalog);
        var shown = new CatalogQuery(root).show("teacher", "bounded", true);
        assertEquals("truncated", shown.path("sourceStatus").asText());
        assertEquals(200, shown.path("source").asText().split("\n").length);
        node.put("origin", "catalog:framework");
        store.write(catalog);
        assertEquals("unavailable-for-imported-catalog", new CatalogQuery(root).show("teacher", "bounded", true).path("sourceStatus").asText());
        node.put("origin", "workspace");
        Files.writeString(root.resolve("src/Example.txt"), "x".repeat(1024 * 1024 + 1));
        store.write(catalog);
        assertTrue(assertThrows(IOException.class, () -> new CatalogQuery(root).show("teacher", "bounded", true)).getMessage().contains("size limit"));
    }
}
