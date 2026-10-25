package io.cyborgcode.pandora.util;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Shared exclusions and canonical containment; no symlink traversal. */
public final class SourceFiles {
    private static final Set<String> EXCLUDED =
            Set.of(
                    ".git",
                    ".idea",
                    ".vs",
                    "target",
                    "bin",
                    "obj",
                    "node_modules",
                    "generated",
                    "cache");

    private SourceFiles() {}

    public static List<Path> list(Path root, List<String> roots, String extension)
            throws IOException {
        Path realRoot = root.toRealPath();
        Set<Path> result = new TreeSet<>();
        for (String source : roots) {
            Path start = realRoot.resolve(source).normalize();
            if (!Files.exists(start))
                throw new IOException("Source root does not exist: " + source);
            if (!start.toRealPath().startsWith(realRoot))
                throw new IOException("Source root escapes workspace");
            try (var stream = Files.walk(start)) {
                for (Path p :
                        stream.filter(Files::isRegularFile)
                                .filter(p -> p.toString().endsWith(extension))
                                .toList()) {
                    Path relative = realRoot.relativize(p);
                    if (java.util.stream.StreamSupport.stream(relative.spliterator(), false)
                            .anyMatch(s -> EXCLUDED.contains(s.toString()))) continue;
                    if (!p.toRealPath().startsWith(realRoot))
                        throw new IOException("Source symlink escapes workspace");
                    result.add(p);
                }
            }
        }
        return List.copyOf(result);
    }
}
