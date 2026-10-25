package io.cyborgcode.pandora.model;

import java.util.List;
import java.util.Map;

public record Compass(
        String description,
        List<String> tags,
        String creation,
        String preference,
        List<String> examples,
        List<OptionSpec> options,
        Map<String, Object> extra) {
    public Compass(
            String description,
            List<String> tags,
            String creation,
            String preference,
            List<String> examples,
            List<OptionSpec> options) {
        this(description, tags, creation, preference, examples, options, Map.of());
    }

    public Compass {
        extra = extra == null ? Map.of() : Map.copyOf(extra);
        tags = tags == null ? List.of() : List.copyOf(tags);
        examples = examples == null ? List.of() : List.copyOf(examples);
        options = options == null ? List.of() : List.copyOf(options);
        creation = creation == null ? "AUTO" : creation;
        preference = preference == null ? "AUTO" : preference;
    }
}
