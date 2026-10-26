package io.cyborgcode.pandora.model;

/**
 * Source line range and content hash used to detect stale guidance.
 *
 * @author Cyborg Code Syndicate 💍👨💻
 */
public record SourceLocation(String path, int startLine, int endLine, String sha256) {}
