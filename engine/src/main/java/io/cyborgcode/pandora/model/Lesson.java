package io.cyborgcode.pandora.model;

import java.util.List;

public record Lesson(
        String category,
        String level,
        String description,
        String whenToUse,
        List<String> tags,
        List<String> related) {
    public Lesson {
        tags = tags == null ? List.of() : List.copyOf(tags);
        related = related == null ? List.of() : List.copyOf(related);
    }
}
