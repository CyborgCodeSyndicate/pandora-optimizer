package io.cyborgcode.pandora.config;

import java.util.List;
import java.util.Map;

/**
 * Workspace configuration for modules, catalog resources and option limits.
 *
 * @author Cyborg Code Syndicate 💍👨💻
 */
public record Config(
      int schemaVersion,
      String workspaceId,
      List<Module> modules,
      Map<String, Resource> resources,
      List<String> catalogs,
      int maxOptions) {

   /** Normalizes optional values and defensively copies collection inputs. */
   public Config {
      modules = modules == null ? List.of() : List.copyOf(modules);
      resources = resources == null ? Map.of() : Map.copyOf(resources);
      catalogs = catalogs == null ? List.of() : List.copyOf(catalogs);
      if (maxOptions == 0) {
         maxOptions = 50;
      }
   }

   /**
    * Source roots, references and adapter command for one language module.
    *
    * @author Cyborg Code Syndicate 💍👨💻
    */
   public record Module(
         String id,
         String language,
         List<String> sourceRoots,
         List<String> references,
         List<String> command) {

      /** Normalizes optional values and defensively copies collection inputs. */
      public Module {
         sourceRoots = sourceRoots == null ? List.of(".") : List.copyOf(sourceRoots);
         references = references == null ? List.of() : List.copyOf(references);
         command = command == null ? List.of() : List.copyOf(command);
      }
   }

   /**
    * Configured resource URI and optional bearer-token environment variable.
    *
    * @author Cyborg Code Syndicate 💍👨💻
    */
   public record Resource(String uri, String uriEnv, String bearerTokenEnv, String sha256) {}
}
