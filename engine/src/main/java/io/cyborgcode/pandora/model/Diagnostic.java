package io.cyborgcode.pandora.model;

/**
 * An extraction or enrichment finding with its severity and source path.
 *
 * @author Cyborg Code Syndicate 💍👨💻
 */
public record Diagnostic(String code, String severity, String message, String path)
      implements java.io.Serializable {

   /** Creates an error diagnostic associated with a path. */
   public static Diagnostic error(String code, String message, String path) {
      return new Diagnostic(code, "error", message, path);
   }

   /** Creates a warning diagnostic associated with a path. */
   public static Diagnostic warning(String code, String message, String path) {
      return new Diagnostic(code, "warning", message, path);
   }
}
