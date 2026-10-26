package io.cyborgcode.pandora.spi;

import io.cyborgcode.pandora.model.ScanRequest;
import io.cyborgcode.pandora.model.ScanResult;

/**
 * Implement in Java or bridge an external process that implements protocol v1.
 *
 * @author Cyborg Code Syndicate 💍👨💻
 */
public interface LanguageAdapter {

   /** Returns the stable language identifier handled by this adapter. */
   String language();

   /** Extracts symbol facts without executing application code. */
   ScanResult scan(ScanRequest request) throws Exception;
}
