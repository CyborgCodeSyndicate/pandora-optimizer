package io.cyborgcode.pandora.catalog;

import static org.junit.jupiter.api.Assertions.*;

import io.cyborgcode.pandora.config.Config;
import io.cyborgcode.pandora.model.*;
import io.cyborgcode.pandora.spi.LanguageAdapter;
import io.cyborgcode.pandora.util.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ScanEngineTest {
    @TempDir Path root;
    final AtomicInteger calls = new AtomicInteger();
    boolean fail;

    @BeforeEach
    void setup() throws Exception {
        Files.createDirectories(root.resolve("pandora"));
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Example.java"), "line1\nline2\nline3\n");
        Files.writeString(root.resolve("options.json"), "{\"values\":[\"one\",\"two\",\"three\"]}");
        Files.writeString(
                root.resolve("examples.json"),
                "{\"items\":[{\"id\":\"demo.Example.run\",\"usages\":[{\"code\":\"run(key, mode)\"}]}]}");
        Config c =
                new Config(
                        1,
                        "sample",
                        List.of(
                                new Config.Module(
                                        "main", "fake", List.of("src"), List.of(), List.of())),
                        Map.of(
                                "options",
                                new Config.Resource("options.json", null, null, null),
                                "examples",
                                new Config.Resource("examples.json", null, null, null)),
                        List.of(),
                        2);
        Json.MAPPER.writeValue(root.resolve("pandora/config.json").toFile(), c);
    }

    LanguageAdapter adapter() {
        return new LanguageAdapter() {
            public String language() {
                return "fake";
            }

            public ScanResult scan(ScanRequest request) throws Exception {
                calls.incrementAndGet();
                Compass parameterGuidance =
                        new Compass(
                                "mode",
                                List.of(),
                                "AUTO",
                                "AUTO",
                                List.of(),
                                List.of(new OptionSpec(List.of(), "options", "/values", null)));
                Compass guidance =
                        new Compass(
                                "run operation",
                                List.of("api"),
                                "AUTO",
                                "PREFER",
                                List.of("examples"),
                                List.of());
                var location =
                        new SourceLocation(
                                "src/Example.java",
                                1,
                                3,
                                Hashes.sha256(
                                        Files.readAllBytes(root.resolve("src/Example.java"))));
                var symbol =
                        new Symbol(
                                "fake:sample:main:demo.Example#run(String,String)",
                                "fake",
                                "main",
                                "METHOD",
                                "run",
                                "demo.Example.run",
                                "run(String,String)",
                                null,
                                "void",
                                List.of(
                                        new Parameter(0, "key", "String", false, false, null),
                                        new Parameter(
                                                1,
                                                "mode",
                                                "String",
                                                false,
                                                false,
                                                parameterGuidance)),
                                location,
                                new Lesson(
                                        "service",
                                        "EXCELLENT",
                                        "good",
                                        "always",
                                        List.of("retry"),
                                        List.of()),
                                guidance,
                                List.of(),
                                "resolved");
                return new ScanResult(
                        1,
                        List.of(symbol),
                        fail ? List.of(Diagnostic.error("BROKEN", "bad source", null)) : List.of());
            }
        };
    }

    @Test
    void enrichesWithoutChangingParameterOrder() throws Exception {
        var catalog = new ScanEngine(List.of(adapter())).scan(root, false, false, false);
        var symbol = catalog.path("symbols").get(0);
        assertEquals("key", symbol.path("parameters").get(0).path("name").asText());
        assertEquals(1, symbol.path("parameters").get(1).path("index").asInt());
        var values = symbol.path("parameters").get(1).path("resolvedOptions");
        assertEquals("truncated", values.path("status").asText());
        assertEquals(3, values.path("total").asInt());
        assertEquals(2, values.path("values").size());
        assertEquals(1, symbol.path("usageExamples").size());
    }

    @Test
    void unchangedInputsSkipAdapterButChangedSourcesRescan() throws Exception {
        var engine = new ScanEngine(List.of(adapter()));
        engine.scan(root, false, true, false);
        engine.scan(root, false, true, false);
        assertEquals(1, calls.get());
        Files.writeString(root.resolve("src/Example.java"), "changed\n");
        engine.scan(root, false, true, false);
        assertEquals(2, calls.get());
    }

    @Test
    void failedScanPreservesLastSuccessfulManifest() throws Exception {
        var engine = new ScanEngine(List.of(adapter()));
        engine.scan(root, false, false, false);
        String manifest = Files.readString(root.resolve("pandora/generated/manifest.json"));
        fail = true;
        assertThrows(ScanEngine.ScanFailure.class, () -> engine.scan(root, false, false, false));
        assertEquals(manifest, Files.readString(root.resolve("pandora/generated/manifest.json")));
    }

    @Test
    void detectsChangedLocalExamplesAsStale() throws Exception {
        new ScanEngine(List.of(adapter())).scan(root, false, false, false);
        Files.writeString(root.resolve("examples.json"), "{\"items\":[]}");
        assertEquals("stale", new CatalogQuery(root).envelope().path("freshness").asText());
    }

    @Test
    void sourceDisplayRefusesOutdatedSource() throws Exception {
        new ScanEngine(List.of(adapter())).scan(root, false, false, false);
        Files.writeString(root.resolve("src/Example.java"), "different");
        assertEquals(
                "stale",
                new CatalogQuery(root)
                        .show("teacher", "demo.Example.run", true)
                        .path("sourceStatus")
                        .asText());
    }

    @Test
    void queryReadsBoundedSourceAndFiltersTags() throws Exception {
        new ScanEngine(List.of(adapter())).scan(root, false, false, false);
        var query = new CatalogQuery(root);
        assertEquals(
                1, query.search("teacher", "", "service", "retry", 3, false).path("items").size());
        assertEquals(
                0,
                query.search("teacher", "", "service", "missing", 3, false).path("items").size());
        assertEquals(
                "complete",
                query.show("teacher", "demo.Example.run", true).path("sourceStatus").asText());
        assertThrows(
                java.io.IOException.class, () -> query.search("teacher", "", "", "", 0, false));
    }

    @Test
    void removedSymbolsDisappearOnSuccessfulEmptyScan() throws Exception {
        new ScanEngine(List.of(adapter())).scan(root, false, false, false);
        LanguageAdapter empty =
                new LanguageAdapter() {
                    public String language() {
                        return "fake";
                    }

                    public ScanResult scan(ScanRequest r) {
                        return new ScanResult(1, List.of(), List.of());
                    }
                };
        new ScanEngine(List.of(empty)).scan(root, false, false, false);
        assertTrue(new CatalogStore(root).read().path("symbols").isEmpty());
    }

    @Test
    void invalidOptionPointerFailsInsteadOfProducingEmptySuccess() throws Exception {
        Files.writeString(root.resolve("options.json"), "{\"different\":[]}");
        assertThrows(
                ScanEngine.ScanFailure.class,
                () -> new ScanEngine(List.of(adapter())).scan(root, false, false, false));
        assertFalse(Files.exists(root.resolve("pandora/generated/manifest.json")));
    }
}
