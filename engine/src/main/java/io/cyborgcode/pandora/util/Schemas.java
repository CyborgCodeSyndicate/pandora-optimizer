package io.cyborgcode.pandora.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Only bundled, trusted schemas are loaded; input catalogs never supply a schema to execute.
 *
 * @author Cyborg Code Syndicate 💍👨💻
 */
public final class Schemas {

   private static final Map<String, Schema> CACHE = new ConcurrentHashMap<>();

   private Schemas() {}

   /** Checks JSON data against the named bundled schema. */
   public static boolean valid(String name, JsonNode data) {
      Schema schema =
            CACHE.computeIfAbsent(
                  name,
                  key -> {
                     try (InputStream stream =
                           Schemas.class.getResourceAsStream(
                                 "/schemas/" + key + ".schema.json")) {
                        if (stream == null) {
                           throw new IllegalStateException(
                                 "Missing bundled schema " + key);
                        }
                        return SchemaRegistry.withDefaultDialect(
                                    SpecificationVersion.DRAFT_2020_12)
                              .getSchema(stream);
                     } catch (IOException e) {
                        throw new UncheckedIOException(e);
                     }
                  });
      return schema.validate(data).isEmpty();
   }
}
