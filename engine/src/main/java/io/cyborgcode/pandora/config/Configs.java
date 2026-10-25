package io.cyborgcode.pandora.config;

import io.cyborgcode.pandora.util.Json;
import io.cyborgcode.pandora.util.Schemas;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class Configs {
    private Configs() {}

    public static Config read(Path root) throws IOException {
        var document = Json.MAPPER.readTree(root.resolve("pandora/config.json").toFile());
        if (!Schemas.valid("config", document))
            throw new IOException("Configuration does not satisfy schema v1");
        Config c = Json.MAPPER.treeToValue(document, Config.class);
        if (c.schemaVersion() != 1
                || c.workspaceId() == null
                || !c.workspaceId().matches("[A-Za-z0-9._-]+"))
            throw new IOException(
                    "Expected schemaVersion 1 and a nonblank workspaceId containing letters, digits, dots, dashes or underscores");
        if (c.modules().isEmpty() && c.catalogs().isEmpty())
            throw new IOException("Configure at least one module or catalog");
        if (c.maxOptions() < 1 || c.maxOptions() > 10000)
            throw new IOException("maxOptions must be between 1 and 10000");
        Set<String> ids = new HashSet<>();
        for (var m : c.modules()) {
            if (m.id() == null || !m.id().matches("[A-Za-z0-9._-]+") || !ids.add(m.id()))
                throw new IOException("Module IDs must be valid and unique");
            if (m.language() == null || m.language().isBlank() || m.sourceRoots().isEmpty())
                throw new IOException("Each module needs language and sourceRoots");
        }
        for (var entry : c.resources().entrySet()) {
            var r = entry.getValue();
            if (r == null || (present(r.uri()) == present(r.uriEnv())))
                throw new IOException(
                        "Resource " + entry.getKey() + " needs exactly one of uri or uriEnv");
            if (present(r.sha256()) && !r.sha256().matches("[0-9a-fA-F]{64}"))
                throw new IOException("Invalid SHA-256 for resource " + entry.getKey());
        }
        return c;
    }

    public static boolean present(String s) {
        return s != null && !s.isBlank();
    }
}
