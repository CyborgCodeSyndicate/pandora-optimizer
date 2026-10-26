package io.cyborgcode.pandora.model;

import java.util.List;

/**
 * Versioned adapter response containing immutable symbol and diagnostic lists.
 *
 * @author Cyborg Code Syndicate 💍👨💻
 */
public record ScanResult(int protocolVersion, List<Symbol> symbols, List<Diagnostic> diagnostics) {

   /** Normalizes optional values and defensively copies collection inputs. */
   public ScanResult {
      symbols = List.copyOf(symbols);
      diagnostics = List.copyOf(diagnostics);
   }
}
