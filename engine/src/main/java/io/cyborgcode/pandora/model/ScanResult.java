package io.cyborgcode.pandora.model;

import java.util.List;

public record ScanResult(int protocolVersion, List<Symbol> symbols, List<Diagnostic> diagnostics) {
    public ScanResult {
        symbols = List.copyOf(symbols);
        diagnostics = List.copyOf(diagnostics);
    }
}
