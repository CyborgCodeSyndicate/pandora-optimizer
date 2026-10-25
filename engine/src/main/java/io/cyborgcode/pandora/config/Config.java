package io.cyborgcode.pandora.config;

import java.util.*;

public record Config(
        int schemaVersion,
        String workspaceId,
        List<Module> modules,
        Map<String, Resource> resources,
        List<String> catalogs,
        int maxOptions) {
    public Config {
        modules = modules == null ? List.of() : List.copyOf(modules);
        resources = resources == null ? Map.of() : Map.copyOf(resources);
        catalogs = catalogs == null ? List.of() : List.copyOf(catalogs);
        if (maxOptions == 0) maxOptions = 50;
    }

    public record Module(
            String id,
            String language,
            List<String> sourceRoots,
            List<String> references,
            List<String> command) {
        public Module {
            sourceRoots = sourceRoots == null ? List.of(".") : List.copyOf(sourceRoots);
            references = references == null ? List.of() : List.copyOf(references);
            command = command == null ? List.of() : List.copyOf(command);
        }
    }

    public record Resource(String uri, String uriEnv, String bearerTokenEnv, String sha256) {}
}
