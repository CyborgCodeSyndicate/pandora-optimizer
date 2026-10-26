package io.cyborgcode.pandora.config;

import io.cyborgcode.pandora.model.*;
import io.cyborgcode.pandora.util.Json;
import io.cyborgcode.pandora.util.SourceFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ConfigsTest {
    @TempDir Path root;
    void write(Config config) throws Exception {
        Files.createDirectories(root.resolve("pandora"));
        Json.MAPPER.writeValue(root.resolve("pandora/config.json").toFile(), config);
    }
    @Test void preservesDefaultsAndDefensivelyCopiesInputs() {
        var paths = new ArrayList<>(List.of("src"));
        var module = new Config.Module("main", "java", paths, null, null);
        paths.clear();
        assertEquals(List.of("src"), module.sourceRoots());
        assertEquals(List.of("."), new Config.Module("main", "java", null, null, null).sourceRoots());
        assertEquals(List.of(), module.references());
        assertEquals(List.of(), module.command());
        var config = new Config(1, "test", null, null, null, 0);
        assertEquals(50, config.maxOptions());
        assertTrue(config.modules().isEmpty());
        assertTrue(config.resources().isEmpty());
        assertTrue(config.catalogs().isEmpty());
        var compass = new Compass("description", null, null, null, null, null, null);
        assertEquals("AUTO", compass.creation());
        assertEquals("AUTO", compass.preference());
        assertTrue(compass.tags().isEmpty());
        assertTrue(compass.options().isEmpty());
        assertTrue(compass.extra().isEmpty());
        assertTrue(new Lesson("service", "GOOD", "pattern", "", null, null).tags().isEmpty());
        assertEquals("NAME", new OptionSpec(null, "", "", null, null, null).render());
        assertEquals("warning", Diagnostic.warning("TYPE", "missing", "source").severity());
        var symbol = new Symbol("id", "java", "main", "CLASS", "Example", "Example", "Example", null, null, null, null, null, null, null, "resolved", null);
        assertTrue(symbol.parameters().isEmpty());
        assertTrue(symbol.enumValues().isEmpty());
        assertTrue(symbol.supertypes().isEmpty());
    }
    @Test void requiresAtLeastOneModuleOrImportedCatalog() throws Exception {
        write(new Config(1, "test", List.of(), Map.of(), List.of(), 50));
        assertThrows(IOException.class, () -> Configs.read(root));
    }
    @Test void rejectsDuplicateModuleIdsAndInvalidResourceDeclarations() throws Exception {
        var module = new Config.Module("main", "java", List.of("src"), null, null);
        write(new Config(1, "test", List.of(module, module), Map.of(), List.of(), 50));
        assertThrows(IOException.class, () -> Configs.read(root));
        for (var resource : List.of(
                new Config.Resource(null, null, null, null),
                new Config.Resource("values.json", "VALUES_URI", null, null),
                new Config.Resource("values.json", null, null, "not-a-hash"))) {
            write(new Config(1, "test", List.of(module), Map.of("options", resource), List.of(), 50));
            assertThrows(IOException.class, () -> Configs.read(root));
        }
        write(new Config(1, "test", List.of(module), Map.of("options", new Config.Resource(null, "VALUES_URI", null, "a".repeat(64))), List.of(), 50));
        assertEquals("VALUES_URI", Configs.read(root).resources().get("options").uriEnv());
    }
    @Test void sourceDiscoveryRejectsEscapingAndMissingRoots() throws Exception {
        assertThrows(IOException.class, () -> SourceFiles.list(root, List.of("../escape"), ".java"));
        assertThrows(IOException.class, () -> SourceFiles.list(root, List.of("missing"), ".java"));
        Files.writeString(root.resolve("Example.java"), "class Example {}");
        assertEquals(List.of(root.resolve("Example.java").toRealPath()), SourceFiles.list(root, List.of("Example.java"), ".java"));
    }
}
