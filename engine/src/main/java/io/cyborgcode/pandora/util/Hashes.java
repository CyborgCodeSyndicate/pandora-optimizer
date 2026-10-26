package io.cyborgcode.pandora.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes UTF-8 SHA-256 fingerprints for source and resource freshness.
 *
 * @author Cyborg Code Syndicate 💍👨💻
 */
public final class Hashes {

   private Hashes() {}

   /** Returns the lowercase hexadecimal SHA-256 digest of the supplied content. */
   public static String sha256(byte[] bytes) {
      try {
         return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      } catch (NoSuchAlgorithmException e) {
         throw new IllegalStateException(e);
      }
   }

   /** Returns the lowercase hexadecimal SHA-256 digest of the supplied content. */
   public static String sha256(String text) {
      return sha256(text.getBytes(StandardCharsets.UTF_8));
   }
}
