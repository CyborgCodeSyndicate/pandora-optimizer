package io.cyborgcode.pandora.model;

public record SourceLocation(String path, int startLine, int endLine, String sha256) {}
