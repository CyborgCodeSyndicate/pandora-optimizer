package io.cyborgcode.pandora.catalog;

import static org.junit.jupiter.api.Assertions.*;

import io.cyborgcode.pandora.model.*;
import io.cyborgcode.pandora.resources.ResourceLoader;
import io.cyborgcode.pandora.util.Json;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConsumerOptionsTest {
    @TempDir Path root;

    Symbol type(String name, String kind, String module, List<String> bases, List<String> values) {
        return new Symbol(
                "java:test:" + module + ":" + name,
                "java",
                module,
                kind,
                name,
                name,
                name,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                values,
                "resolved",
                bases);
    }

    @Test
    void importedContractsDiscoverTransitiveConsumerEnumsAndKeepEmbeddedExamples() {
        var symbols = Json.MAPPER.createArrayNode();
        symbols.add(
                Json.MAPPER.valueToTree(
                        type("demo.Endpoint", "INTERFACE", "library", List.of(), List.of())));
        symbols.add(
                Json.MAPPER.valueToTree(
                        type(
                                "demo.Child",
                                "INTERFACE",
                                "library",
                                List.of("demo.Endpoint"),
                                List.of())));
        symbols.add(
                Json.MAPPER.valueToTree(
                        type(
                                "app.Endpoints",
                                "ENUM",
                                "consumer",
                                List.of("demo.Child"),
                                List.of("LOGIN", "USERS"))));
        var api = Json.MAPPER.createObjectNode();
        api.put("language", "java");
        api.put("module", "library");
        api.put("kind", "METHOD");
        api.put("origin", "catalog:framework");
        var param = api.putArray("parameters").addObject();
        param.put("type", "java.lang.String");
        var guidance = param.putObject("compass");
        guidance.putArray("options")
                .addObject()
                .put("implementationsOf", "demo.Endpoint")
                .put("render", "QUALIFIED");
        guidance.putArray("examples").add("producer-only-resource");
        param.putArray("usageExamples").addObject().put("code", "embedded()");
        symbols.add(api);
        List<Diagnostic> diagnostics = new ArrayList<>();
        new Enricher(new ResourceLoader(root, Map.of(), true), 50, diagnostics).enrich(symbols);
        assertTrue(diagnostics.isEmpty(), diagnostics.toString());
        assertEquals("complete", param.path("resolvedOptions").path("status").asText());
        assertEquals(
                "app.Endpoints.LOGIN",
                param.path("resolvedOptions").path("values").get(0).asText());
        assertEquals(2, param.path("resolvedOptions").path("total").asInt());
        assertEquals("embedded()", param.path("usageExamples").get(0).path("code").asText());
    }

    @Test
    void producerRuleWithoutConsumerImplementationsIsExplicitlyDeferred() {
        var symbols = Json.MAPPER.createArrayNode();
        var api = symbols.addObject();
        api.put("language", "java");
        api.put("module", "library");
        api.put("kind", "METHOD");
        var guidance = api.putObject("compass");
        guidance.putArray("options").addObject().put("implementationsOf", "demo.Endpoint");
        new Enricher(new ResourceLoader(root, Map.of(), true), 50, new ArrayList<>())
                .enrich(symbols);
        assertEquals("deferred", api.path("resolvedOptions").path("status").asText());
    }

    @Test
    void parameterDescriptionDoesNotSuppressTypeOptions() {
        var symbols = Json.MAPPER.createArrayNode();
        com.fasterxml.jackson.databind.node.ObjectNode contract =
                Json.MAPPER.valueToTree(
                        type("demo.Endpoint", "INTERFACE", "library", List.of(), List.of()));
        contract.putObject("compass")
                .putArray("options")
                .addObject()
                .put("implementationsOf", "demo.Endpoint");
        symbols.add(contract);
        symbols.add(
                Json.MAPPER.valueToTree(
                        type(
                                "app.Endpoints",
                                "ENUM",
                                "consumer",
                                List.of("demo.Endpoint"),
                                List.of("LOGIN"))));
        var api = symbols.addObject();
        api.put("language", "java");
        api.put("module", "library");
        api.put("kind", "METHOD");
        var param = api.putArray("parameters").addObject();
        param.put("type", "demo.Endpoint<?>");
        var direct = param.putObject("compass");
        direct.put("description", "Keep this parameter description");
        direct.putArray("options");
        new Enricher(new ResourceLoader(root, Map.of(), true), 50, new ArrayList<>())
                .enrich(symbols);
        assertEquals("LOGIN", param.path("resolvedOptions").path("values").get(0).asText());
        assertEquals(
                "Keep this parameter description",
                param.path("compass").path("description").asText());
    }

    @Test
    void importedResourceSnapshotsKeepProducerTruncation() {
        var symbols = Json.MAPPER.createArrayNode();
        var api = symbols.addObject();
        api.put("language", "java");
        api.put("module", "library");
        api.put("kind", "METHOD");
        api.put("origin", "catalog:producer");
        api.putObject("compass")
                .putArray("options")
                .addObject()
                .put("source", "producer-only-values");
        var state = api.putObject("resolvedOptions");
        state.put("truncated", true);
        state.put("total", 100);
        state.putArray("values").add("ONE").add("TWO");
        new Enricher(new ResourceLoader(root, Map.of(), true), 50, new ArrayList<>())
                .enrich(symbols);
        assertEquals("truncated", api.path("resolvedOptions").path("status").asText());
        assertEquals(100, api.path("resolvedOptions").path("total").asInt());
    }
}
