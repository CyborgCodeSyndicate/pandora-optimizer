package io.cyborgcode.pandora.catalog;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.cyborgcode.pandora.config.*;
import io.cyborgcode.pandora.model.*;
import io.cyborgcode.pandora.resources.ResourceLoader;
import io.cyborgcode.pandora.spi.*;
import io.cyborgcode.pandora.util.*;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

public final class ScanEngine {
    public static final String VERSION = "0.1.0";
    private final Map<String, LanguageAdapter> adapters = new HashMap<>();

    public ScanEngine(List<LanguageAdapter> adapters) {
        adapters.forEach(a -> this.adapters.put(a.language(), a));
        ServiceLoader.load(LanguageAdapter.class).forEach(a -> this.adapters.put(a.language(), a));
    }

    public ObjectNode scan(Path root, boolean offline, boolean ifStale, boolean strict)
            throws Exception {
        root = root.toRealPath();
        Config config = Configs.read(root);
        Files.createDirectories(root.resolve("pandora/cache"));
        try (FileChannel channel =
                        FileChannel.open(
                                root.resolve("pandora/cache/scan.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Another scan is running");
            String local = localFingerprint(root, config);
            ResourceLoader resources = new ResourceLoader(root, config.resources(), offline);
            for (String resource : new TreeSet<>(config.resources().keySet()))
                resources.load(resource);
            String fingerprint =
                    Hashes.sha256(local + Json.MAPPER.writeValueAsString(resources.fingerprints()));
            CatalogStore store = new CatalogStore(root);
            if (ifStale)
                try {
                    ObjectNode old = store.read();
                    if (old.path("fingerprint").asText().equals(fingerprint)
                            && (!strict || old.path("diagnostics").isEmpty())) return old;
                } catch (IOException ignored) {
                    /* first scan */
                }
            List<Diagnostic> diagnostics = new ArrayList<>();
            ArrayNode symbols = Json.MAPPER.createArrayNode();
            for (var module : config.modules()) {
                LanguageAdapter adapter = adapter(module);
                ScanResult result =
                        adapter.scan(
                                new ScanRequest(
                                        1,
                                        config.workspaceId(),
                                        module.id(),
                                        root.toString(),
                                        module.sourceRoots(),
                                        module.references()));
                if (result.protocolVersion() != 1)
                    throw new IOException("Adapter protocol mismatch");
                diagnostics.addAll(result.diagnostics());
                for (Symbol symbol : result.symbols()) {
                    if (!symbol.language().equals(module.language())
                            || !symbol.module().equals(module.id()))
                        throw new IOException("Adapter returned the wrong module/language");
                    ObjectNode node = Json.MAPPER.valueToTree(symbol);
                    node.put("origin", "workspace");
                    symbols.add(node);
                }
            }
            for (String resource : config.catalogs()) {
                JsonNode catalog = resources.load(resource);
                List<Diagnostic> errors = CatalogValidator.validate(catalog);
                if (!errors.isEmpty())
                    throw new IOException("Invalid dependency catalog: " + resource);
                for (JsonNode diagnostic : catalog.path("diagnostics")) {
                    diagnostics.add(Json.MAPPER.treeToValue(diagnostic, Diagnostic.class));
                }
                for (JsonNode symbol : catalog.path("symbols")) {
                    ObjectNode imported = symbol.deepCopy();
                    imported.put("origin", "catalog:" + resource);
                    symbols.add(imported);
                }
            }
            // Imported contracts are resolved against consumer facts as well as producer facts.
            new Enricher(resources, config.maxOptions(), diagnostics).enrich(symbols);
            List<JsonNode> sorted = new ArrayList<>();
            symbols.forEach(sorted::add);
            sorted.sort(Comparator.comparing(s -> s.path("id").asText()));
            symbols.removeAll();
            sorted.forEach(symbols::add);
            ObjectNode catalog = Json.MAPPER.createObjectNode();
            catalog.put("schemaVersion", 1);
            catalog.put("producerVersion", VERSION);
            catalog.put("workspaceId", config.workspaceId());
            catalog.put("generatedAt", Instant.now().toString());
            catalog.put("localFingerprint", local);
            catalog.put("fingerprint", fingerprint);
            catalog.put("offline", offline);
            catalog.set("resourceFingerprints", Json.MAPPER.valueToTree(resources.fingerprints()));
            catalog.set("symbols", symbols);
            catalog.set("diagnostics", Json.MAPPER.valueToTree(diagnostics));
            diagnostics.addAll(CatalogValidator.validate(catalog));
            catalog.set("diagnostics", Json.MAPPER.valueToTree(diagnostics));
            if (diagnostics.stream().anyMatch(d -> d.severity().equals("error"))
                    || strict && !diagnostics.isEmpty()) {
                throw new ScanFailure(diagnostics);
            }
            if (!local.equals(localFingerprint(root, config)))
                throw new IOException("Inputs changed during scanning; retry");
            store.write(catalog);
            return catalog;
        }
    }

    private LanguageAdapter adapter(Config.Module module) throws IOException {
        if (!module.command().isEmpty())
            return new ProcessAdapter(module.language(), module.command());
        if (adapters.containsKey(module.language())) return adapters.get(module.language());
        if (module.language().equals("csharp")) {
            String worker = System.getenv("PANDORA_DOTNET_ADAPTER");
            if (worker != null && !worker.isBlank())
                return new ProcessAdapter("csharp", List.of("dotnet", worker));
        }
        throw new IOException(
                "No adapter for "
                        + module.language()
                        + ". Configure module.command; C# also supports PANDORA_DOTNET_ADAPTER.");
    }

    public static String localFingerprint(Path root, Config config) throws IOException {
        SortedMap<String, String> inputs = new TreeMap<>();
        inputs.put("engine", VERSION);
        inputs.put("javaRuntime", System.getProperty("java.version"));
        try {
            Path artifact =
                    Path.of(
                            ScanEngine.class
                                    .getProtectionDomain()
                                    .getCodeSource()
                                    .getLocation()
                                    .toURI());
            if (Files.isRegularFile(artifact))
                inputs.put("engineArtifact", Hashes.sha256(Files.readAllBytes(artifact)));
        } catch (java.net.URISyntaxException e) {
            throw new IOException("Cannot fingerprint engine artifact", e);
        }
        inputs.put(
                "config", Hashes.sha256(Files.readAllBytes(root.resolve("pandora/config.json"))));
        for (var module : config.modules()) {
            // Unknown adapters still invalidate on all files under their configured roots.
            String ext =
                    module.language().equals("java")
                            ? ".java"
                            : module.language().equals("csharp") ? ".cs" : "";
            for (Path file : SourceFiles.list(root, module.sourceRoots(), ext))
                inputs.put(
                        root.relativize(file).toString(), Hashes.sha256(Files.readAllBytes(file)));
            for (String reference : module.references())
                inputs.put(
                        "reference:" + reference,
                        Hashes.sha256(Files.readAllBytes(root.resolve(reference))));
            for (String argument : module.command()) {
                Path p = root.resolve(argument);
                if (Files.isRegularFile(p))
                    inputs.put("adapter:" + argument, Hashes.sha256(Files.readAllBytes(p)));
            }
            if (module.language().equals("csharp") && module.command().isEmpty()) {
                String path = System.getenv("PANDORA_DOTNET_ADAPTER");
                if (path != null)
                    inputs.put("dotnet-worker", Hashes.sha256(Files.readAllBytes(Path.of(path))));
            }
        }
        ResourceLoader localResources = new ResourceLoader(root, config.resources(), true);
        for (var entry : config.resources().entrySet()) {
            var resource = entry.getValue();
            String uri =
                    Configs.present(resource.uriEnv())
                            ? System.getenv(resource.uriEnv())
                            : resource.uri();
            if (uri != null) inputs.put("resource-endpoint:" + entry.getKey(), Hashes.sha256(uri));
            if (uri != null && (!uri.contains(":") || uri.startsWith("file:"))) {
                localResources.load(entry.getKey());
                inputs.put(
                        "resource:" + entry.getKey(),
                        localResources.fingerprints().get(entry.getKey()));
            }
        }
        return Hashes.sha256(Json.MAPPER.writeValueAsString(inputs));
    }

    public static final class ScanFailure extends IOException {
        private final List<Diagnostic> diagnostics;

        public ScanFailure(List<Diagnostic> diagnostics) {
            super("Scan failed validation; previous generation retained");
            this.diagnostics = List.copyOf(diagnostics);
        }

        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }
    }
}
