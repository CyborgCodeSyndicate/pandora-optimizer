package io.cyborgcode.pandora.spi;

import io.cyborgcode.pandora.model.ScanRequest;
import io.cyborgcode.pandora.util.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProcessAdapterTest {
    @TempDir Path root;

    ProcessAdapter worker(String response, int exit) throws Exception {
        Path source = root.resolve("Worker.java");
        Files.writeString(source, """
            import java.nio.file.*;
            public class Worker {
                public static void main(String[] args) throws Exception {
                    String request = new String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    Files.writeString(Path.of("received.json"), request);
                    System.out.println(new String(java.util.Base64.getDecoder().decode(args[0]), java.nio.charset.StandardCharsets.UTF_8));
                    System.exit(Integer.parseInt(args[1]));
                }
            }
            """);
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return new ProcessAdapter("custom", List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(), source.toString(), java.util.Base64.getEncoder().encodeToString(response.getBytes(java.nio.charset.StandardCharsets.UTF_8)), Integer.toString(exit)));
    }

    ScanRequest request() {
        return new ScanRequest(1, "workspace", "main", root.toString(), List.of("src with spaces"), List.of());
    }

    @Test void exchangesOneVersionedRequestAndResponseInTheWorkspace() throws Exception {
        var adapter = worker("{\"protocolVersion\":1,\"symbols\":[],\"diagnostics\":[]}", 0);
        assertEquals("custom", adapter.language());
        var result = adapter.scan(request());
        assertEquals(1, result.protocolVersion());
        assertTrue(result.symbols().isEmpty());
        assertEquals("src with spaces", Json.MAPPER.readTree(root.resolve("received.json").toFile()).path("sourceRoots").get(0).asText());
    }

    @Test void rejectsNonzeroExitEvenWhenOutputLooksValid() throws Exception {
        var adapter = worker("{\"protocolVersion\":1,\"symbols\":[],\"diagnostics\":[]}", 7);
        assertTrue(assertThrows(IOException.class, () -> adapter.scan(request())).getMessage().contains("exit code 7"));
    }

    @Test void rejectsMalformedAndSchemaInvalidResponses() throws Exception {
        for (String response : List.of("not JSON", "{}", "{\"protocolVersion\":2,\"symbols\":[],\"diagnostics\":[]}")) {
            var adapter = worker(response, 0);
            assertThrows(IOException.class, () -> adapter.scan(request()));
        }
    }

    @Test void reportsAnUnavailableExecutableAndCopiesCommandArguments() {
        var command = new ArrayList<>(List.of(root.resolve("missing-worker").toString()));
        var adapter = new ProcessAdapter("custom", command);
        command.clear();
        assertThrows(IOException.class, () -> adapter.scan(request()));
    }
}
