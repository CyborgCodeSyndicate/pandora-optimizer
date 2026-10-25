package io.cyborgcode.pandora.model;

import java.util.List;

/** Language-neutral API shape; native type syntax is intentionally retained. */
public record Symbol(
        String id,
        String language,
        String module,
        String kind,
        String name,
        String qualifiedName,
        String signature,
        String parentId,
        String returnType,
        List<Parameter> parameters,
        SourceLocation source,
        Lesson lesson,
        Compass compass,
        List<String> enumValues,
        String resolution) {
    public Symbol {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
    }
}
