package io.cyborgcode.pandora.model;

import java.util.List;

/** Data-only rule; enumType is adapter-qualified, source is a configured resource ID. */
public record OptionSpec(List<String> values, String source, String pointer, String enumType) {
    public OptionSpec {
        values = values == null ? List.of() : List.copyOf(values);
    }
}
