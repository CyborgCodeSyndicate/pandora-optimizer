package io.cyborgcode.pandora.resources;

import com.fasterxml.jackson.databind.JsonNode;
import io.cyborgcode.pandora.config.*;
import io.cyborgcode.pandora.spi.ResourceProvider;
import io.cyborgcode.pandora.util.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

public final class ResourceLoader {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    private final Path root;
    private final Map<String, Config.Resource> specs;
    private final List<ResourceProvider> providers;
    private final Function<String, String> environment;
    private final boolean offline;
    private final Map<String, JsonNode> loaded = new HashMap<>();
    private final SortedMap<String, String> fingerprints = new TreeMap<>();

    public ResourceLoader(Path root, Map<String, Config.Resource> specs, boolean offline) {
        this(root, specs, offline, defaults(), System::getenv);
    }

    public ResourceLoader(
            Path root,
            Map<String, Config.Resource> specs,
            boolean offline,
            List<ResourceProvider> providers,
            Function<String, String> environment) {
        this.root = root;
        this.specs = specs;
        this.offline = offline;
        this.providers = List.copyOf(providers);
        this.environment = environment;
    }

    private static List<ResourceProvider> defaults() {
        List<ResourceProvider> p =
                new ArrayList<>(List.of(new FileResourceProvider(), new HttpsResourceProvider()));
        ServiceLoader.load(ResourceProvider.class).forEach(p::add);
        return p;
    }

    public SortedMap<String, String> fingerprints() {
        return Collections.unmodifiableSortedMap(fingerprints);
    }

    public JsonNode load(String id) throws IOException {
        if (loaded.containsKey(id)) return loaded.get(id);
        var spec = specs.get(id);
        if (spec == null) throw new IOException("Unknown resource ID: " + id);
        try {
            String value =
                    Configs.present(spec.uriEnv()) ? environment.apply(spec.uriEnv()) : spec.uri();
            if (value == null || value.isBlank())
                throw new IOException("Resource endpoint is missing");
            URI uri = URI.create(value);
            String key =
                    Hashes.sha256(
                            id
                                    + "\n"
                                    + value
                                    + "\n"
                                    + Objects.toString(spec.sha256(), "")
                                    + "\n"
                                    + Objects.toString(spec.bearerTokenEnv(), ""));
            Path cache = root.resolve("pandora/cache/resources").resolve(key + ".json");
            byte[] bytes;
            boolean local = uri.getScheme() == null || "file".equals(uri.getScheme());
            if (offline && !local) {
                if (!Files.isRegularFile(cache)) throw new IOException("No offline cache");
                if (Files.size(cache) > MAX_BYTES)
                    throw new IOException("Cached resource exceeds size limit");
                bytes = Files.readAllBytes(cache);
            } else {
                Map<String, String> headers = new HashMap<>();
                if (Configs.present(spec.bearerTokenEnv())) {
                    if (local)
                        throw new IOException(
                                "Bearer authentication is only valid for remote resources");
                    String token = environment.apply(spec.bearerTokenEnv());
                    if (token == null || token.isBlank())
                        throw new IOException("Authentication environment variable is missing");
                    headers.put("Authorization", "Bearer " + token);
                }
                ResourceProvider provider =
                        providers.stream()
                                .filter(p -> p.supports(uri))
                                .findFirst()
                                .orElseThrow(() -> new IOException("Unsupported resource scheme"));
                bytes = provider.read(uri, root, headers, MAX_BYTES);
                if (bytes.length > MAX_BYTES) throw new IOException("Resource exceeds size limit");
            }
            String hash = Hashes.sha256(bytes);
            if (Configs.present(spec.sha256()) && !hash.equalsIgnoreCase(spec.sha256()))
                throw new IOException("SHA-256 mismatch");
            JsonNode json = Json.MAPPER.readTree(bytes);
            if (json == null || (!json.isArray() && !json.isObject()))
                throw new IOException("Expected a JSON object or array");
            if (!local && !offline) {
                Files.createDirectories(cache.getParent());
                Path temp = Files.createTempFile(cache.getParent(), "resource-", ".tmp");
                try {
                    Files.write(temp, bytes);
                    Files.move(temp, cache, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(temp);
                }
            }
            fingerprints.put(id, hash);
            loaded.put(id, json);
            return json;
        } catch (Exception e) {
            // Resource IDs are safe diagnostics; URIs and parser excerpts may contain secrets.
            String reason =
                    (e instanceof IOException
                                    && !(e instanceof com.fasterxml.jackson.core.JacksonException))
                            ? e.getMessage()
                            : "Invalid resource data or endpoint";
            throw new IOException("Resource '" + id + "': " + reason);
        }
    }
}
