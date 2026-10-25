package io.cyborgcode.pandora.spi;

import io.cyborgcode.pandora.model.*;

/** Implement in Java or bridge an external process that implements protocol v1. */
public interface LanguageAdapter {
    String language();

    ScanResult scan(ScanRequest request) throws Exception;
}
