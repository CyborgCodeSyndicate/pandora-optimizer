package io.cyborgcode.pandora.model;

/**
 * Ordered native parameter signature and its optional API guidance.
 *
 * @author Cyborg Code Syndicate 💍👨💻
 */
public record Parameter(
      int index, String name, String type, boolean optional, boolean variadic, Compass compass) {}
