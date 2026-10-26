package io.cyborgcode.pandora.cli;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cyborgcode.pandora.catalog.CatalogQuery;
import io.cyborgcode.pandora.catalog.CatalogValidator;
import io.cyborgcode.pandora.catalog.ScanEngine;
import io.cyborgcode.pandora.config.Config;
import io.cyborgcode.pandora.config.Configs;
import io.cyborgcode.pandora.java.JavaAdapter;
import io.cyborgcode.pandora.util.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;
import picocli.CommandLine;

/**
 * Command-line entry point for scanning and retrieving Teacher and Navigator guidance.
 *
 * @author Cyborg Code Syndicate 💍👨💻
 */
@Command(
      name = "pandora",
      description = "Pandora Optimizer: local AI Teacher and AI Navigator catalogs",
      mixinStandardHelpOptions = true,
      version = "Pandora Optimizer 0.1.0",
      subcommands = {Main.Init.class, Main.Scan.class, Main.Doctor.class, Main.Validate.class})
public final class Main implements Runnable {

   private static final String DIAGNOSTICS = "diagnostics";

   private static final String NAVIGATOR = "navigator";

   private static final String STATUS = "status";

   @Spec CommandSpec spec;

   @Option(
         names = "--root",
         description = "Workspace containing pandora/config.json",
         scope = ScopeType.INHERIT)
   Path root = Path.of(".");

   @Option(
         names = "--json",
         description = "JSON output (all data commands already emit JSON)",
         scope = ScopeType.INHERIT)
   boolean json;

   @Override
   public void run() {
      spec.commandLine().usage(spec.commandLine().getOut());
   }

   /** Executes the CLI and exits with the command's status. */
   public static void main(String[] args) {
      System.exit(command().execute(args));
   }

   /** Creates the command tree with structured JSON execution errors. */
   public static CommandLine command() {
      CommandLine cli = new CommandLine(new Main());
      cli.addSubcommand("teacher", domain("teacher"))
            .addSubcommand(NAVIGATOR, domain(NAVIGATOR));
      cli.setExecutionExceptionHandler(
            (e, c, p) -> {
               ObjectNode error = Json.MAPPER.createObjectNode();
               error.put(STATUS, "error");
               if (e instanceof ScanEngine.ScanFailure failure) {
                  error.put("message", failure.getMessage());
                  error.set(DIAGNOSTICS, Json.MAPPER.valueToTree(failure.diagnostics()));
               } else {
                  error.put(
                        "message",
                        e instanceof java.io.IOException
                              ? e.getMessage()
                              : "Operation failed: " + e.getClass().getSimpleName());
               }
               c.getErr().println(Json.MAPPER.writeValueAsString(error));
               return e instanceof ScanEngine.ScanFailure ? 3 : 2;
            });
      return cli;
   }

   private static CommandLine domain(String name) {
      CommandLine command = new CommandLine(new Domain()).setCommandName(name);
      command.addSubcommand("search", new Search(name)).addSubcommand("show", new Show(name));
      if (name.equals(NAVIGATOR)) {
         command.addSubcommand("bundle", new Bundle());
      }
      return command;
   }

   static String workspaceId(Path workspace) {
      Path name = workspace.getFileName();
      return (name == null ? "workspace" : name.toString()).replaceAll("[^A-Za-z0-9._-]", "-");
   }

   @Command(mixinStandardHelpOptions = true, description = "Search curated metadata")
   static class Domain implements Runnable {

      @Spec CommandSpec spec;

      @Override
      public void run() {
         spec.commandLine().getOut().println("Use search or show; navigator also supports bundle.");
      }
   }

   abstract static class Action implements Callable<Integer> {

      @Spec CommandSpec spec;

      Path root() {
         return ((Main) spec.root().userObject()).root.toAbsolutePath().normalize();
      }

      void print(Object value) throws Exception {
         spec.commandLine().getOut().println(Json.MAPPER.writeValueAsString(value));
      }
   }

   @Command(
         name = "init",
         description = "Create workspace configuration",
         mixinStandardHelpOptions = true)
   static class Init extends Action {

      @Option(
            names = "--language",
            split = ",",
            defaultValue = "java",
            description = "Initial languages: java,csharp")
      List<String> languages;

      @Override
      public Integer call() throws Exception {
         Path workspace = super.root();
         Path directory = workspace.resolve("pandora");
         Path file = directory.resolve("config.json");
         if (Files.exists(file)) {
            throw new java.io.IOException("Configuration already exists");
         }
         if (languages.stream().anyMatch(l -> !Set.of("java", "csharp").contains(l))) {
            throw new java.io.IOException("Initial languages must be java or csharp");
         }
         Files.createDirectories(directory);
         String name = workspaceId(workspace);
         var modules =
               languages.stream()
                     .distinct()
                     .map(l -> new Config.Module(l, l, List.of("."), List.of(), List.of()))
                     .toList();
         Config config = new Config(1, name, modules, Map.of(), List.of(), 50);
         Files.write(file, Json.MAPPER.writeValueAsBytes(config), StandardOpenOption.CREATE_NEW);
         print(Map.of(STATUS, "created", "config", file.toString()));
         return 0;
      }
   }

   @Command(
         name = "scan",
         description = "Extract and enrich both catalogs",
         mixinStandardHelpOptions = true)
   static class Scan extends Action {

      @Option(names = "--offline")
      boolean offline;

      @Option(names = "--if-stale")
      boolean ifStale;

      @Option(names = "--strict")
      boolean strict;

      @Override
      public Integer call() throws Exception {
         var catalog =
               new ScanEngine(List.of(new JavaAdapter()))
                     .scan(super.root(), offline, ifStale, strict);
         print(
               Map.of(
                     STATUS,
                     "complete",
                     "symbols",
                     catalog.path("symbols").size(),
                     "fingerprint",
                     catalog.path("fingerprint"),
                     DIAGNOSTICS,
                     catalog.path(DIAGNOSTICS)));
         return 0;
      }
   }

   @Command(
         name = "doctor",
         description = "Inspect configuration and adapter availability",
         mixinStandardHelpOptions = true)
   static class Doctor extends Action {

      @Override
      public Integer call() throws Exception {
         Config config = Configs.read(super.root());
         ObjectNode result = Json.MAPPER.createObjectNode();
         result.put("javaRuntime", System.getProperty("java.version"));
         result.put("workspaceId", config.workspaceId());
         boolean missing = false;
         ArrayNode adapters = result.putArray("adapters");
         for (var m : config.modules()) {
            boolean configured =
                  m.language().equals("java")
                        || !m.command().isEmpty()
                        || m.language().equals("csharp")
                              && System.getenv("PANDORA_DOTNET_ADAPTER") != null;
            var a = adapters.addObject();
            a.put("module", m.id());
            a.put("language", m.language());
            a.put("configured", configured);
            if (!configured) {
               missing = true;
            }
         }
         result.put(STATUS, missing ? "missing-adapter-configuration" : "configured");
         result.put("note", "Configured commands are not executed by doctor.");
         print(result);
         return missing ? 2 : 0;
      }
   }

   @Command(
         name = "validate",
         description = "Validate the last generated catalog",
         mixinStandardHelpOptions = true)
   static class Validate extends Action {

      @Option(names = "--strict")
      boolean strict;

      @Override
      public Integer call() throws Exception {
         var query = new CatalogQuery(super.root());
         var catalog = query.bundle();
         var errors = CatalogValidator.validate(catalog);
         var status = query.envelope();
         status.set("validationErrors", Json.MAPPER.valueToTree(errors));
         print(status);
         return !errors.isEmpty()
                     || status.path("freshness").asText().equals("stale")
                     || strict && !catalog.path(DIAGNOSTICS).isEmpty()
               ? 3
               : 0;
      }
   }

   @Command(
         name = "search",
         description = "Find relevant guidance",
         mixinStandardHelpOptions = true)
   static class Search extends Action {

      final String domain;

      Search(String domain) {
         this.domain = domain;
      }

      @Option(names = "--query", defaultValue = "")
      String query;

      @Option(names = "--category", defaultValue = "")
      String category;

      @Option(names = "--tag", defaultValue = "")
      String tag;

      @Option(names = "--limit", defaultValue = "3")
      int limit;

      @Option(names = "--include-bad")
      boolean includeBad;

      @Override
      public Integer call() throws Exception {
         print(new CatalogQuery(super.root()).search(domain, query, category, tag, limit, includeBad));
         return 0;
      }
   }

   @Command(
         name = "show",
         description = "Read one symbol and its guidance",
         mixinStandardHelpOptions = true)
   static class Show extends Action {

      final String domain;

      Show(String domain) {
         this.domain = domain;
      }

      @Parameters(index = "0")
      String id;

      @Option(names = "--include-source")
      boolean includeSource;

      @Override
      public Integer call() throws Exception {
         print(new CatalogQuery(super.root()).show(domain, id, includeSource));
         return 0;
      }
   }

   @Command(
         name = "bundle",
         description =
               "Export a self-contained JSON catalog, including resolved examples/options",
         mixinStandardHelpOptions = true)
   static class Bundle extends Action {

      @Option(names = "--output", required = true)
      Path output;

      @Override
      public Integer call() throws Exception {
         var query = new CatalogQuery(super.root());
         if (query.envelope().path("freshness").asText().equals("stale")) {
            throw new java.io.IOException("Catalog is stale; scan before bundling");
         }
         Path destination = super.root().resolve(output).normalize();
         Path directory = destination.getParent();
         if (directory != null) {
            Files.createDirectories(directory);
         }
         Files.write(
               destination,
               Json.MAPPER.writeValueAsBytes(query.bundle()),
               StandardOpenOption.CREATE_NEW);
         print(Map.of(STATUS, "written", "output", destination.toString()));
         return 0;
      }
   }
}
