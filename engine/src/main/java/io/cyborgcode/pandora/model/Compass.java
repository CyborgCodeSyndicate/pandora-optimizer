package io.cyborgcode.pandora.model;

import java.util.List;

public record Compass(
        String description,
        List<String> tags,
        String creation,
        String preference,
        List<String> examples,
        List<OptionSpec> options) {
    public Compass {
        tags = tags == null ? List.of() : List.copyOf(tags);
        examples = examples == null ? List.of() : List.copyOf(examples);
        options = options == null ? List.of() : List.copyOf(options);
        creation = creation == null ? "AUTO" : creation;
        preference = preference == null ? "AUTO" : preference;
    }
}
