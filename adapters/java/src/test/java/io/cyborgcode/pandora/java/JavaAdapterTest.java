package io.cyborgcode.pandora.java;

import static org.junit.jupiter.api.Assertions.*;

import io.cyborgcode.pandora.model.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class JavaAdapterTest {
    @TempDir Path root;

    ScanResult scan(String source) throws Exception {
        Files.createDirectories(root.resolve("src/demo"));
        Files.writeString(root.resolve("src/demo/Example.java"), source);
        return new JavaAdapter()
                .scan(
                        new ScanRequest(
                                1, "test", "main", root.toString(), List.of("src"), List.of()));
    }

    String header = "package demo; import io.cyborgcode.pandora.annotations.*;\n";

    @Test
    void discoversLessonsConstructorsAndFullParameterOrder() throws Exception {
        var result =
                scan(
                        header
                                + """
            @AiCompass(description="client")
            class Example {
                static { if (true) throw new RuntimeException("must never execute"); }
                @AiCompass(description="construct") public Example() {}
                @AiLesson(category="service", level=LessonLevel.EXCELLENT, description="retry carefully",tags={"retry"})
                @AiCompass(description="run")
                void run(String key, @AiCompassOptions(values={"fast","slow"}) String mode) {}
            }
            """);
        assertTrue(
                result.diagnostics().stream().noneMatch(d -> d.severity().equals("error")),
                result.diagnostics().toString());
        var method =
                result.symbols().stream()
                        .filter(s -> s.name().equals("run"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(2, method.parameters().size());
        assertEquals("java.lang.String", method.parameters().get(0).type());
        assertEquals(1, method.parameters().get(1).index());
        assertEquals(
                List.of("fast", "slow"),
                method.parameters().get(1).compass().options().getFirst().values());
        assertEquals("EXCELLENT", method.lesson().level());
        assertTrue(method.source().path().equals("src/demo/Example.java"));
        assertTrue(result.symbols().stream().anyMatch(s -> s.kind().equals("CONSTRUCTOR")));
    }

    @Test
    void overloadsUseQualifiedTypes() throws Exception {
        var result =
                scan(
                        header
                                + """
            class Example {
                @AiCompass void run(java.util.Date date) {}
                @AiCompass void run(java.sql.Date date) {}
            }
            """);
        var methods = result.symbols().stream().filter(s -> s.kind().equals("METHOD")).toList();
        assertEquals(2, methods.size());
        assertNotEquals(methods.get(0).id(), methods.get(1).id());
    }

    @Test
    void nestedAndTestSourceLocationsAreActualFiles() throws Exception {
        var result =
                scan(
                        header
                                + "class Example { @AiLesson(category=\"test\",description=\"nested\") static class Nested {} }");
        var nested =
                result.symbols().stream()
                        .filter(s -> s.name().equals("Nested"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("demo.Example.Nested", nested.qualifiedName());
        assertEquals("src/demo/Example.java", nested.source().path());
    }

    @Test
    void sameNamedUnrelatedAnnotationIsIgnored() throws Exception {
        var result = scan("package demo; @interface AiCompass {} @AiCompass class Example {}");
        assertTrue(result.symbols().stream().allMatch(s -> s.compass() == null));
    }

    @Test
    void discoversEnumOptionsAndRepeatableValues() throws Exception {
        var result =
                scan(
                        header
                                + """
            enum Mode { FAST, SLOW }
            class Example {
                @AiCompass void run(@AiCompassOptions(enumType=Mode.class) @AiCompassOptions(values={"OTHER"}) Mode mode) {}
            }
            """);
        var p =
                result.symbols().stream()
                        .filter(s -> s.name().equals("run"))
                        .findFirst()
                        .orElseThrow()
                        .parameters()
                        .getFirst();
        assertEquals(2, p.compass().options().size());
        assertEquals("demo.Mode", p.compass().options().getFirst().enumType());
        assertEquals(
                List.of("FAST", "SLOW"),
                result.symbols().stream()
                        .filter(s -> s.kind().equals("ENUM"))
                        .findFirst()
                        .orElseThrow()
                        .enumValues());
    }

    @Test
    void malformedSourceProducesError() throws Exception {
        assertTrue(
                scan("class Example { !!!").diagnostics().stream()
                        .anyMatch(d -> d.code().equals("JAVA_PARSE")));
    }

    @Test
    void unresolvedMetadataDoesNotSilentlyBecomeGoodGuidance() throws Exception {
        var result =
                scan(header + "@AiLesson(category=\"x\",description=unknown()) class Example {}");
        assertTrue(
                result.diagnostics().stream()
                        .anyMatch(d -> d.code().equals("JAVA_METADATA_UNRESOLVED")));
    }

    @Test
    void fieldsDoNotRequireAnnotatedOwner() throws Exception {
        var result =
                scan(header + "class Example { @AiCompass(description=\"field\") String value; }");
        assertTrue(
                result.symbols().stream()
                        .anyMatch(s -> s.kind().equals("FIELD") && s.compass() != null));
    }

    @Test
    void legacyExecutableRulesGiveMigrationDiagnostic() throws Exception {
        var result =
                scan(
                        "package demo; import io.cyborgcode.pandora.annotation.*; class Example { @PandoraOptions(availableOptionsRule=Custom.class) String value; }");
        assertTrue(
                result.diagnostics().stream()
                        .anyMatch(d -> d.code().equals("RUNTIME_RULE_UNSUPPORTED")));
    }

    @Test
    void extractsDeclarativeInterfaceRulesAndMetadata() throws Exception {
        var result =
                scan(
                        header
                                + """
            @AiCompassOptions(implementationsOf=Endpoint.class,
                meta={@AiCompassOptions.Meta(key="type",value="endpoint")})
            interface Endpoint<T> {}
            enum AppEndpoints implements Endpoint<AppEndpoints> { LOGIN, USERS }
            class Example {}
            """);
        var contract =
                result.symbols().stream()
                        .filter(s -> s.name().equals("Endpoint"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("demo.Endpoint", contract.compass().options().getFirst().implementationsOf());
        assertEquals("endpoint", contract.compass().extra().get("type"));
        var implementation =
                result.symbols().stream()
                        .filter(s -> s.name().equals("AppEndpoints"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(List.of("demo.Endpoint"), implementation.supertypes());
    }
    @Test
    void capturesRecordPropertiesAnnotationMembersAndInheritedPublicSurface() throws Exception {
        var result = scan(header + """
            interface Contract {}
            @AiCompass(description="record")
            record Example(@AiCompass(description="component") String key) implements Contract {}
            @AiCompass(description="settings")
            @interface Settings { String value(); }
            @AiCompass(description="client")
            class Client extends java.util.ArrayList<String> implements Contract {
                public String first, second;
                public Client(String key) {}
                protected void execute(String... values) {}
                private void internal() {}
            }
            """);
        assertTrue(result.symbols().stream().anyMatch(s -> s.kind().equals("PROPERTY") && s.name().equals("key")));
        assertTrue(result.symbols().stream().anyMatch(s -> s.kind().equals("ANNOTATION")));
        assertTrue(result.symbols().stream().anyMatch(s -> s.qualifiedName().equals("demo.Settings.value")));
        assertEquals(2, result.symbols().stream().filter(s -> s.kind().equals("FIELD")).count());
        var method = result.symbols().stream().filter(s -> s.name().equals("execute")).findFirst().orElseThrow();
        assertTrue(method.parameters().getFirst().variadic());
        assertEquals("java.lang.String[]", method.parameters().getFirst().type());
        assertTrue(result.symbols().stream().noneMatch(s -> s.name().equals("internal")));
        assertEquals(List.of("java.util.ArrayList", "demo.Contract"), result.symbols().stream().filter(s -> s.name().equals("Client")).findFirst().orElseThrow().supertypes());
    }

    @Test
    void resolvesSourceConstantsSingularValuesAndTextBlocksWithoutExecution() throws Exception {
        var result = scan(header + """
            class Constants { static final String DESCRIPTION = "preserve " + "retries"; }
            @AiCompass(description=Constants.DESCRIPTION, creation=CreationKind.BUILDER,
                preference=Preference.PREFER, tags="retry", examples="usage")
            class Example {
                static final String CATEGORY = "ser" + "vice";
                @AiLesson(category=CATEGORY, description="safe " + "operation", tags="retry", related="Other")
                @AiCompassOptions(values="fast", source="modes", pointer="/values",
                    implementationsOf=external.Contract.class, meta=@AiCompassOptions.Meta(key="kind",value="mode"))
                void run() {}
            }
            """);
        var type = result.symbols().stream().filter(s -> s.name().equals("Example")).findFirst().orElseThrow();
        assertEquals("preserve retries", type.compass().description());
        assertEquals("BUILDER", type.compass().creation());
        assertEquals("PREFER", type.compass().preference());
        assertEquals(List.of("retry"), type.compass().tags());
        var method = result.symbols().stream().filter(s -> s.name().equals("run")).findFirst().orElseThrow();
        assertEquals("service", method.lesson().category());
        assertEquals("safe operation", method.lesson().description());
        assertEquals(List.of("Other"), method.lesson().related());
        assertEquals("external.Contract", method.compass().options().getFirst().implementationsOf());
        assertEquals("mode", method.compass().extra().get("kind"));
        String block = "\"\"\"\nblock description\n\"\"\"";
        result = scan(header + "@AiCompass(description=" + block + ") class Example {}");
        assertEquals("block description\n", result.symbols().getFirst().compass().description());
    }

    @Test
    void recognizesQualifiedExplicitAndSamePackageMarkers() throws Exception {
        assertNotNull(scan("@io.cyborgcode.pandora.annotations.AiCompass class Example {}").symbols().getFirst().compass());
        assertNotNull(scan("package demo; import io.cyborgcode.pandora.annotations.AiCompass; @AiCompass class Example {}").symbols().getFirst().compass());
        assertNotNull(scan("package io.cyborgcode.pandora.annotations; @AiCompass class Example {}").symbols().getFirst().compass());
        assertNull(scan("package demo; @another.AiCompass class Example {}").symbols().getFirst().compass());
        var result = scan("import io.cyborgcode.pandora.annotations.*; @AiCompassOptions(implementationsOf=Contract.class) interface Example extends Missing {} ");
        assertEquals("Contract", result.symbols().getFirst().compass().options().getFirst().implementationsOf());
        assertEquals(List.of("Missing"), result.symbols().getFirst().supertypes());
    }

    @Test
    void diagnosesConflictingAndUnsupportedMetadataExpressions() throws Exception {
        var result = scan(header + """
            @AiCompass @AiCompass(description="duplicate")
            @AiLesson(category="service", description=UNKNOWN, level=123)
            class Example {
                static final int NUMBER = 42;
                @AiCompass(description=NUMBER, preference=123) void run() {}
                @AiCompassOptions(meta={@AiCompassOptions.Meta(key="", value="ignored")}) String value;
            }
            """);
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("DUPLICATE_GUIDANCE")));
        assertTrue(result.diagnostics().stream().filter(d -> d.code().equals("JAVA_METADATA_UNRESOLVED")).count() >= 3);
        assertTrue(result.symbols().stream().filter(s -> s.name().equals("value")).findFirst().orElseThrow().compass().extra().isEmpty());
    }

    @Test
    void retainsLegacyResourceReferencesAndReportsUnresolvedNativeTypes() throws Exception {
        var result = scan("""
            package demo;
            import io.cyborgcode.pandora.annotation.*;
            @Pandora(description="legacy")
            class Example {
                @PandoraOptions(exampleFilesPath="examples") Missing value;
                @Pandora Missing run(Missing first, Missing second) { return null; }
            }
            """);
        var field = result.symbols().stream().filter(s -> s.name().equals("value")).findFirst().orElseThrow();
        assertEquals("partial", field.resolution());
        assertEquals(List.of("examples"), field.compass().examples());
        assertEquals(1, result.diagnostics().stream().filter(d -> d.code().equals("JAVA_TYPE_UNRESOLVED")).count());
        assertEquals("partial", result.symbols().stream().filter(s -> s.name().equals("run")).findFirst().orElseThrow().resolution());
    }
}
