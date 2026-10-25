package io.cyborgcode.pandora.resources;

import io.cyborgcode.pandora.spi.ResourceProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.Map;

public final class FileResourceProvider implements ResourceProvider {
    public boolean supports(URI uri) {
        return uri.getScheme() == null || uri.getScheme().equals("file");
    }

    public byte[] read(URI uri, Path workspace, Map<String, String> headers, int maxBytes)
            throws IOException {
        Path root = workspace.toRealPath();
        Path path =
                (uri.getScheme() == null ? root.resolve(uri.getPath()) : Path.of(uri)).toRealPath();
        if (!path.startsWith(root))
            throw new IOException("Local resources must be inside the workspace");
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) throw new IOException("Resource exceeds size limit");
            return bytes;
        }
    }
}
