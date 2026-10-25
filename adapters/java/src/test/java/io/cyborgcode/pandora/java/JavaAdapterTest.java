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
}
