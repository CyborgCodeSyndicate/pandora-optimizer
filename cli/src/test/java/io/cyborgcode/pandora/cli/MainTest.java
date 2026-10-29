package io.cyborgcode.pandora.cli;

import com.fasterxml.jackson.databind.JsonNode;
import io.cyborgcode.pandora.config.Config;
import io.cyborgcode.pandora.util.Json;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MainTest {
    @TempDir Path root;
    record Result(int status, String output, String error) {
        JsonNode json() throws Exception { return Json.MAPPER.readTree(output); }
    }
    Result run(String... args) {
        var command = Main.command();
        var output = new StringWriter();
        var error = new StringWriter();
        command.setOut(new PrintWriter(output));
        command.setErr(new PrintWriter(error));
        var arguments = new ArrayList<>(List.of("--root", root.toString()));
        arguments.addAll(List.of(args));
        return new Result(command.execute(arguments.toArray(String[]::new)), output.toString(), error.toString());
    }
    void initializeSource() throws Exception {
        assertEquals(0, run("init").status());
        Files.writeString(root.resolve("Example.java"), """
            import io.cyborgcode.pandora.annotations.*;
            @AiLesson(category="service", description="Reuse the local service pattern")
            @AiCompass(description="Example API")
            public class Example {
                @AiCompass(description="Execute with a known mode")
                public void run(@AiCompassOptions(values={"fast","slow"}) String mode) {}
            }
            """);
    }
    @Test void helpAndVersionExitSuccessfully() {
        var rootHelp = run();
        assertEquals(0, rootHelp.status());
        assertTrue(rootHelp.output().contains("Usage: pandora"));
        assertTrue(run("--help").output().contains("navigator"));
        assertTrue(run("--version").output().contains("Pandora Optimizer"));
        for (String domain : List.of("teacher", "navigator")) {
            var help = run(domain);
            assertEquals(0, help.status());
            assertTrue(help.output().contains("Use search or show"));
            assertEquals("", help.error());
        }
    }
    @Test void workspaceNamesHandleFilesystemRootsAndUnsupportedCharacters() {
        assertEquals("workspace", Main.workspaceId(root.toAbsolutePath().getRoot()));
        assertEquals("project-with-spaces", Main.workspaceId(root.resolve("project with spaces")));
    }
    @Test void initializesDistinctLanguagesAndRefusesToOverwriteConfiguration() throws Exception {
        assertEquals("created", run("init", "--language", "java,csharp,java").json().path("status").asText());
        var config = Json.MAPPER.readTree(root.resolve("pandora/config.json").toFile());
        assertEquals(2, config.path("modules").size());
        var second = run("init");
        assertEquals(2, second.status());
        assertTrue(second.error().contains("already exists"));
    }
    @Test void rejectsUnsupportedInitialLanguageWithoutCreatingConfiguration() {
        var result = run("init", "--language", "unknown");
        assertEquals(2, result.status());
        assertFalse(Files.exists(root.resolve("pandora/config.json")));
    }
    @Test void retrievesLessonsAndApiOptionsThroughTheCommandTree() throws Exception {
        initializeSource();
        assertEquals("configured", run("doctor").json().path("status").asText());
        assertEquals(0, run("scan", "--strict").status());
        assertEquals(0, run("scan", "--if-stale", "--offline").status());
        var lessons = run("teacher", "search", "--category", "service", "--query", "pattern", "--limit", "1");
        assertEquals(0, lessons.status(), lessons.error());
        assertEquals(1, lessons.json().path("total").asInt());
        String lessonId = lessons.json().path("items").get(0).path("id").asText();
        assertEquals("complete", run("teacher", "show", lessonId, "--include-source").json().path("sourceStatus").asText());
        var apis = run("navigator", "search", "--query", "run");
        String apiId = apis.json().path("items").get(0).path("id").asText();
        var api = run("navigator", "show", apiId).json().path("item");
        assertEquals("fast", api.path("parameters").get(0).path("resolvedOptions").path("values").get(0).asText());
        assertEquals(0, run("validate", "--strict").status());
        assertEquals(0, run("navigator", "bundle", "--output", "export/catalog.json").status());
        assertTrue(Files.isRegularFile(root.resolve("export/catalog.json")));
        assertEquals(2, run("navigator", "bundle", "--output", "export/catalog.json").status());
        Files.writeString(root.resolve("Example.java"), "class Example {}");
        assertEquals(3, run("validate").status());
        assertEquals(2, run("navigator", "bundle", "--output", "export/stale.json").status());
    }
    @Test void reportsMissingCatalogAndUnknownSymbolsAsStructuredErrors() throws Exception {
        initializeSource();
        assertEquals(2, run("validate").status());
        assertEquals(0, run("scan").status());
        assertEquals(2, run("teacher", "show", "missing-id").status());
        assertEquals(2, run("teacher", "search", "--limit", "0").status());
    }
    @Test void scanFailuresExposeDiagnosticsAndPreserveExitCode() throws Exception {
        initializeSource();
        Files.writeString(root.resolve("Example.java"), "class Example { !!!");
        var result = run("scan", "--strict");
        assertEquals(3, result.status());
        assertTrue(Json.MAPPER.readTree(result.error()).path("diagnostics").size() > 0);
    }
    @Test void doctorChecksCustomAdapterConfigurationWithoutExecutingIt() throws Exception {
        Files.createDirectories(root.resolve("pandora"));
        var config = new Config(1, "doctor", List.of(new Config.Module("custom", "custom", List.of("."), List.of(), List.of())), Map.of(), List.of(), 50);
        Json.MAPPER.writeValue(root.resolve("pandora/config.json").toFile(), config);
        assertEquals(2, run("doctor").status());
        config = new Config(1, "doctor", List.of(new Config.Module("custom", "custom", List.of("."), List.of(), List.of("nonexistent-command"))), Map.of(), List.of(), 50);
        Json.MAPPER.writeValue(root.resolve("pandora/config.json").toFile(), config);
        assertEquals(0, run("doctor").status());
    }
}
