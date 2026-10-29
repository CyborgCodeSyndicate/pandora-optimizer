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

class UsageCatalogTransferTest {
    @TempDir Path producer;
    @TempDir Path consumer;
    final String symbol = "java:library:framework:demo.Framework#request(java.lang.String)";

    JsonNode run(Path workspace, String... args) throws Exception {
        var output = new StringWriter();
        var error = new StringWriter();
        var command = Main.command();
        command.setOut(new PrintWriter(output));
        command.setErr(new PrintWriter(error));
        var arguments = new ArrayList<>(List.of("--root", workspace.toString()));
        arguments.addAll(List.of(args));
        assertEquals(0, command.execute(arguments.toArray(String[]::new)), error.toString());
        return Json.MAPPER.readTree(output.toString());
    }

    Path usages() { return producer.resolve("pandora/usages/requests.json"); }

    void writeUsages(String code) throws Exception {
        var document = Json.MAPPER.createObjectNode();
        var entry = document.putArray("items").addObject();
        entry.put("id", "demo.Framework.request");
        entry.put("summary", "Use a declared endpoint");
        entry.putArray("usages").addObject().put("language", "java").put("code", code)
                .put("description", "A library-authored usage example").put("level", "GOOD");
        Json.MAPPER.writeValue(usages().toFile(), document);
    }

    void configure() throws Exception {
        Files.createDirectories(producer.resolve("src/demo"));
        Files.createDirectories(producer.resolve("pandora/usages"));
        Files.writeString(producer.resolve("src/demo/Framework.java"), """
            package demo;
            import io.cyborgcode.pandora.annotations.*;
            public class Framework {
                @AiCompass(description="Execute a request", examples={"request-usages"})
                public void request(@AiCompass(examples={"request-usages"}) String endpoint) {}
            }
            """);
        var configuration = new Config(1, "library",
                List.of(new Config.Module("framework", "java", List.of("src"), List.of(), List.of())),
                Map.of("request-usages", new Config.Resource("pandora/usages/requests.json", null, null, null)),
                List.of(), 50);
        Json.MAPPER.writeValue(producer.resolve("pandora/config.json").toFile(), configuration);
        Files.createDirectories(consumer.resolve("pandora"));
        configuration = new Config(1, "application", List.of(),
                Map.of("framework", new Config.Resource("pandora/catalogs/framework.json", null, null, null)),
                List.of("framework"), 50);
        Json.MAPPER.writeValue(consumer.resolve("pandora/config.json").toFile(), configuration);
    }

    void publish() throws Exception {
        run(producer, "scan", "--if-stale", "--strict");
        Path bundle = consumer.resolve("pandora/catalogs/framework.json");
        // Bundle refuses to overwrite; replace only this test-owned output between publications.
        Files.deleteIfExists(bundle);
        run(producer, "navigator", "bundle", "--output", bundle.toString());
    }

    String code(JsonNode shown) {
        return shown.path("item").path("usageExamples").get(0).path("usages").get(0).path("code").asText();
    }

    @Test void consumerReadsEmbeddedMethodAndParameterUsagesWithoutProducerFiles() throws Exception {
        configure();
        writeUsages("framework.request(endpoint);");
        publish();
        Files.delete(usages());
        Files.delete(producer.resolve("src/demo/Framework.java"));
        run(consumer, "scan", "--offline", "--strict");
        assertEquals(1, run(consumer, "navigator", "search", "--query", "request").path("total").asInt());
        var shown = run(consumer, "navigator", "show", symbol, "--include-source");
        assertEquals("catalog:framework", shown.path("item").path("origin").asText());
        assertEquals("unavailable-for-imported-catalog", shown.path("sourceStatus").asText());
        assertEquals("framework.request(endpoint);", code(shown));
        assertEquals("framework.request(endpoint);", shown.path("item").path("parameters").get(0)
                .path("usageExamples").get(0).path("usages").get(0).path("code").asText());
        assertEquals(0, shown.path("diagnosticCount").asInt());
    }

    @Test void usageFileEditsReachConsumersAfterRebuildingAndReplacingTheBundle() throws Exception {
        configure();
        writeUsages("framework.request(firstEndpoint);");
        publish();
        run(consumer, "scan", "--if-stale");
        assertEquals("framework.request(firstEndpoint);", code(run(consumer, "navigator", "show", symbol)));
        writeUsages("framework.request(secondEndpoint);");
        publish();
        run(consumer, "scan", "--if-stale");
        assertEquals("framework.request(secondEndpoint);", code(run(consumer, "navigator", "show", symbol)));
    }
}
