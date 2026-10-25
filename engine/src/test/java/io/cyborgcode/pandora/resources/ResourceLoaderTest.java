package io.cyborgcode.pandora.resources;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.cyborgcode.pandora.config.Config;
import io.cyborgcode.pandora.spi.ResourceProvider;
import io.cyborgcode.pandora.util.Hashes;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ResourceLoaderTest {
    @TempDir Path root;
    HttpServer server;

    @AfterEach
    void close() {
        if (server != null) server.stop(0);
    }

    ResourceLoader loader(
            Config.Resource resource, boolean offline, List<ResourceProvider> providers) {
        return new ResourceLoader(
                root, Map.of("test", resource), offline, providers, k -> "secret");
    }

    Config.Resource spec(String uri, String hash) {
        return new Config.Resource(uri, null, null, hash);
    }

    @Test
    void readsLocalJsonAndChecksDigest() throws Exception {
        byte[] bytes = "{\"values\":[\"one\"]}".getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve("values.json"), bytes);
        var loader =
                loader(
                        spec("values.json", Hashes.sha256(bytes)),
                        false,
                        List.of(new FileResourceProvider()));
        assertEquals("one", loader.load("test").path("values").get(0).asText());
        assertEquals(Hashes.sha256(bytes), loader.fingerprints().get("test"));
    }

    @Test
    void rejectsWrongDigest() throws Exception {
        Files.writeString(root.resolve("values.json"), "[]");
        assertThrows(
                IOException.class,
                () ->
                        loader(
                                        spec("values.json", "0".repeat(64)),
                                        false,
                                        List.of(new FileResourceProvider()))
                                .load("test"));
    }

    @Test
    void rejectsPathEscape() throws Exception {
        Path outside = Files.createTempFile("pandora-outside-", ".json");
        try {
            Files.writeString(outside, "[]");
            assertThrows(
                    IOException.class,
                    () ->
                            loader(
                                            spec(outside.toUri().toString(), null),
                                            false,
                                            List.of(new FileResourceProvider()))
                                    .load("test"));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void rejectsInvalidJsonWithoutEchoingContent() throws Exception {
        Files.writeString(root.resolve("bad.json"), "super-secret-invalid");
        Exception e =
                assertThrows(
                        IOException.class,
                        () ->
                                loader(
                                                spec("bad.json", null),
                                                false,
                                                List.of(new FileResourceProvider()))
                                        .load("test"));
        assertFalse(e.getMessage().contains("super-secret"));
    }

    String endpoint(String body, int status) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/data",
                exchange -> {
                    exchange.getResponseHeaders().add("Location", "/elsewhere");
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, bytes.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/data";
    }

    @Test
    void supportsAuthenticatedHttpTransportAndOfflineCache() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/data",
                exchange -> {
                    byte[] bytes = "[\"remote\"]".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(
                            "Bearer secret"
                                            .equals(
                                                    exchange.getRequestHeaders()
                                                            .getFirst("Authorization"))
                                    ? 200
                                    : 401,
                            bytes.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                });
        server.start();
        var spec =
                new Config.Resource(
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/data",
                        null,
                        "TOKEN",
                        null);
        var providers = List.<ResourceProvider>of(new HttpsResourceProvider(true));
        assertEquals("remote", loader(spec, false, providers).load("test").get(0).asText());
        server.stop(0);
        assertEquals("remote", loader(spec, true, providers).load("test").get(0).asText());
    }

    @Test
    void rejectsRedirectRatherThanForwardingCredentials() throws Exception {
        String url = endpoint("[]", 302);
        assertThrows(
                IOException.class,
                () ->
                        loader(spec(url, null), false, List.of(new HttpsResourceProvider(true)))
                                .load("test"));
    }

    @Test
    void limitsStreamedResponse() throws Exception {
        String url = endpoint("x".repeat(2048), 200);
        assertThrows(
                IOException.class,
                () -> new HttpsResourceProvider(true).read(URI.create(url), root, Map.of(), 32));
    }

    @Test
    void productionProviderRejectsPlainHttp() {
        assertFalse(new HttpsResourceProvider().supports(URI.create("http://example.com/data")));
    }

    @Test
    void providersCanBeExtendedWithoutChangingLoader() throws Exception {
        ResourceProvider custom =
                new ResourceProvider() {
                    public boolean supports(URI uri) {
                        return "memory".equals(uri.getScheme());
                    }

                    public byte[] read(
                            URI uri, Path workspace, Map<String, String> headers, int max) {
                        return "[\"extension\"]".getBytes(StandardCharsets.UTF_8);
                    }
                };
        assertEquals(
                "extension",
                loader(spec("memory://data", null), false, List.of(custom))
                        .load("test")
                        .get(0)
                        .asText());
    }

    @Test
    void offlineMissIsExplicit() {
        assertThrows(
                IOException.class,
                () ->
                        loader(spec("https://example.com/data?secret=yes", null), true, List.of())
                                .load("test"));
    }
}
