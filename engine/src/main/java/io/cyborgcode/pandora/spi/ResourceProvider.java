package io.cyborgcode.pandora.spi;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

/** Transport SPI only: content interpretation belongs to the engine. */
public interface ResourceProvider {
    boolean supports(URI uri);

    byte[] read(URI uri, Path workspace, Map<String, String> headers, int maxBytes)
            throws IOException;
}
