package io.cyborgcode.pandora.spi;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

/**
 * Transport SPI only: content interpretation belongs to the engine.
 *
 * @author Cyborg Code Syndicate 💍👨💻
 */
public interface ResourceProvider {

   /** Reports whether this transport handles the resource URI. */
   boolean supports(URI uri);

   /** Reads resource bytes within the supplied size and workspace constraints. */
   byte[] read(URI uri, Path workspace, Map<String, String> headers, int maxBytes)
         throws IOException;
}
