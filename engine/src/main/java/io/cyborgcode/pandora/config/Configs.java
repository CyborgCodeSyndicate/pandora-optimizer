package io.cyborgcode.pandora.config;

import io.cyborgcode.pandora.util.Json;
import io.cyborgcode.pandora.util.Schemas;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Reads and validates workspace configuration before adapters run.
 *
 * @author Cyborg Code Syndicate 💍👨💻
 */
public final class Configs {

   private Configs() {}

   /** Reads pandora/config.json and validates its schema and module/resource declarations. */
   public static Config read(Path root) throws IOException {
      var document = Json.MAPPER.readTree(root.resolve("pandora/config.json").toFile());
      if (!Schemas.valid("config", document)) {
         throw new IOException("Configuration does not satisfy schema v1");
      }
      Config c = Json.MAPPER.treeToValue(document, Config.class);
      if (c.schemaVersion() != 1
            || c.workspaceId() == null
            || !c.workspaceId().matches("[A-Za-z0-9._-]+")) {
         throw new IOException(
               "Expected schemaVersion 1 and a nonblank workspaceId containing letters,"
                     + " digits, dots, dashes or underscores");
      }
      if (c.modules().isEmpty() && c.catalogs().isEmpty()) {
         throw new IOException("Configure at least one module or catalog");
      }
      if (c.maxOptions() < 1 || c.maxOptions() > 10000) {
         throw new IOException("maxOptions must be between 1 and 10000");
      }
      Set<String> ids = new HashSet<>();
      for (var m : c.modules()) {
         validateModule(m, ids);
      }
      for (var entry : c.resources().entrySet()) {
         validateResource(entry.getKey(), entry.getValue());
      }
      return c;
   }

   private static void validateModule(Config.Module m, Set<String> ids) throws IOException {
      if (m.id() == null || !m.id().matches("[A-Za-z0-9._-]+") || !ids.add(m.id())) {
         throw new IOException("Module IDs must be valid and unique");
      }
      if (m.language() == null || m.language().isBlank() || m.sourceRoots().isEmpty()) {
         throw new IOException("Each module needs language and sourceRoots");
      }
   }

   private static void validateResource(String id, Config.Resource r) throws IOException {
      if (r == null || (present(r.uri()) == present(r.uriEnv()))) {
         throw new IOException("Resource " + id + " needs exactly one of uri or uriEnv");
      }
      if (present(r.sha256()) && !r.sha256().matches("[0-9a-fA-F]{64}")) {
         throw new IOException("Invalid SHA-256 for resource " + id);
      }
   }

   /** Reports whether a configuration string contains non-whitespace text. */
   public static boolean present(String s) {
      return s != null && !s.isBlank();
   }
}
