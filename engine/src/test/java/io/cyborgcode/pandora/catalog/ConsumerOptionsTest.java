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
    @Test
    void resolvesDirectEnumRulesAndReportsUnavailableEnumTypes() {
        var symbols = Json.MAPPER.createArrayNode();
        symbols.add(Json.MAPPER.valueToTree(type("demo.Mode", "ENUM", "library", List.of(), List.of("FAST", "SLOW"))));
        var api = symbols.addObject().put("language", "java").put("module", "library").put("kind", "METHOD");
        api.putObject("compass").putArray("options").addObject().put("enumType", "demo.Mode").put("render", "QUALIFIED");
        var missing = symbols.addObject().put("language", "java").put("module", "library").put("kind", "METHOD");
        missing.putObject("compass").putArray("options").addObject().put("enumType", "demo.Missing");
        List<Diagnostic> diagnostics = new ArrayList<>();
        new Enricher(new ResourceLoader(root, Map.of(), true), 50, diagnostics).enrich(symbols);
        assertEquals("demo.Mode.FAST", api.path("resolvedOptions").path("values").get(0).asText());
        assertEquals("failed", missing.path("resolvedOptions").path("status").asText());
        assertEquals(List.of("OPTIONS_ENUM"), diagnostics.stream().map(Diagnostic::code).toList());
    }

    @Test
    void validatesResourceValuesAndFiltersOptionalUsageEntries() throws Exception {
        java.nio.file.Files.writeString(root.resolve("values.json"), "[\"fast\",\"slow\"]");
        java.nio.file.Files.writeString(root.resolve("examples.json"), "[{\"id\":\"unrelated\",\"code\":\"ignored\"},{\"id\":\"api\",\"code\":\"matched\"},{\"code\":\"general\"}]");
        java.nio.file.Files.writeString(root.resolve("bad-values.json"), "[42]");
        var specs = Map.of(
            "values", new io.cyborgcode.pandora.config.Config.Resource("values.json", null, null, null),
            "examples", new io.cyborgcode.pandora.config.Config.Resource("examples.json", null, null, null),
            "bad", new io.cyborgcode.pandora.config.Config.Resource("bad-values.json", null, null, null));
        var symbols = Json.MAPPER.createArrayNode();
        var api = symbols.addObject().put("id", "api").put("language", "java").put("module", "library").put("kind", "METHOD");
        var guidance = api.putObject("compass");
        guidance.putArray("options").addObject().put("source", "values");
        guidance.putArray("examples").add("examples");
        var invalid = symbols.addObject().put("language", "java").put("module", "library").put("kind", "METHOD");
        var invalidGuidance = invalid.putObject("compass");
        invalidGuidance.putArray("options").addObject().put("source", "bad");
        invalidGuidance.putArray("examples").add("missing-resource");
        List<Diagnostic> diagnostics = new ArrayList<>();
        new Enricher(new ResourceLoader(root, specs, false), 50, diagnostics).enrich(symbols);
        assertEquals(2, api.path("resolvedOptions").path("values").size());
        assertEquals(2, api.path("usageExamples").size());
        assertEquals("matched", api.path("usageExamples").get(0).path("code").asText());
        assertEquals("failed", invalid.path("resolvedOptions").path("status").asText());
        assertEquals(Set.of("OPTIONS_RESOURCE", "EXAMPLES_RESOURCE"), new HashSet<>(diagnostics.stream().map(Diagnostic::code).toList()));
    }

    @Test
    void boundsUsageEntriesAndRejectsMissingExampleArrays() throws Exception {
        var examples = Json.MAPPER.createArrayNode();
        for (int i = 0; i < 51; i++) examples.addObject().put("code", "example " + i);
        Json.MAPPER.writeValue(root.resolve("many.json").toFile(), examples);
        java.nio.file.Files.writeString(root.resolve("invalid.json"), "{\"other\":[]}");
        var specs = Map.of(
            "many", new io.cyborgcode.pandora.config.Config.Resource("many.json", null, null, null),
            "invalid", new io.cyborgcode.pandora.config.Config.Resource("invalid.json", null, null, null));
        var symbols = Json.MAPPER.createArrayNode();
        for (String name : List.of("many", "invalid")) {
            var api = symbols.addObject().put("language", "java").put("module", "library").put("kind", "METHOD");
            api.putObject("compass").putArray("examples").add(name);
        }
        List<Diagnostic> diagnostics = new ArrayList<>();
        new Enricher(new ResourceLoader(root, specs, false), 50, diagnostics).enrich(symbols);
        assertEquals(50, symbols.get(0).path("usageExamples").size());
        assertEquals(0, symbols.get(1).path("usageExamples").size());
        assertEquals(2, diagnostics.size());
        assertTrue(diagnostics.stream().allMatch(d -> d.code().equals("EXAMPLES_RESOURCE")));
    }

    @Test
    void handlesCyclicContractsAndInheritsFieldGuidance() {
        var symbols = Json.MAPPER.createArrayNode();
        var contract = (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.valueToTree(type("demo.A", "INTERFACE", "library", List.of("demo.B"), List.of()));
        contract.putObject("compass").putArray("tags").add("contract");
        symbols.add(contract);
        symbols.add(Json.MAPPER.valueToTree(type("demo.B", "INTERFACE", "library", List.of("demo.A"), List.of())));
        symbols.add(Json.MAPPER.valueToTree(type("demo.Mode", "ENUM", "library", List.of("demo.A"), List.of("FAST"))));
        var field = symbols.addObject().put("language", "java").put("module", "library").put("kind", "FIELD").put("returnType", "demo.A");
        var api = symbols.addObject().put("language", "java").put("module", "library").put("kind", "METHOD");
        api.putObject("compass").putArray("options").addObject().put("implementationsOf", "demo.Other");
        new Enricher(new ResourceLoader(root, Map.of(), true), 50, new ArrayList<>()).enrich(symbols);
        assertEquals("contract", field.path("compass").path("tags").get(0).asText());
        assertEquals("deferred", api.path("resolvedOptions").path("status").asText());
    }
}
