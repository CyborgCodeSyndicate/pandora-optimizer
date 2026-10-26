package io.cyborgcode.pandora.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.cyborgcode.pandora.model.Diagnostic;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScanFailureTest {
    @Test
    void serializedFailurePreservesItsDiagnosticSnapshot() throws Exception {
        var diagnostics = new ArrayList<>(List.of(
                Diagnostic.error("OPTIONS_ENUM", "Missing enum", "src/Example.java")));
        var failure = new ScanEngine.ScanFailure(diagnostics);
        diagnostics.clear();
        var bytes = new ByteArrayOutputStream();
        try (var output = new ObjectOutputStream(bytes)) {
            output.writeObject(failure);
        }
        try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            var restored = (ScanEngine.ScanFailure) input.readObject();
            assertEquals(failure.getMessage(), restored.getMessage());
            assertEquals(failure.diagnostics(), restored.diagnostics());
            assertEquals(1, restored.diagnostics().size());
        }
    }
}
