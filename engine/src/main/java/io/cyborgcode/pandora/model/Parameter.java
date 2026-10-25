package io.cyborgcode.pandora.model;

public record Parameter(
        int index, String name, String type, boolean optional, boolean variadic, Compass compass) {}
