package io.cyborgcode.pandora.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Only bundled, trusted schemas are loaded; input catalogs never supply a schema to execute. */
public final class Schemas {
    private static final Map<String, Schema> CACHE = new ConcurrentHashMap<>();

    private Schemas() {}

    public static boolean valid(String name, JsonNode data) {
        Schema schema =
                CACHE.computeIfAbsent(
                        name,
                        key -> {
                            try (InputStream stream =
                                    Schemas.class.getResourceAsStream(
                                            "/schemas/" + key + ".schema.json")) {
                                if (stream == null)
                                    throw new IllegalStateException(
                                            "Missing bundled schema " + key);
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
