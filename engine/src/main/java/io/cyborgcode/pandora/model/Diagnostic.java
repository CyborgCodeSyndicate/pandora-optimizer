package io.cyborgcode.pandora.model;

public record Diagnostic(String code, String severity, String message, String path) {
    public static Diagnostic error(String code, String message, String path) {
        return new Diagnostic(code, "error", message, path);
    }

    public static Diagnostic warning(String code, String message, String path) {
        return new Diagnostic(code, "warning", message, path);
    }
}
