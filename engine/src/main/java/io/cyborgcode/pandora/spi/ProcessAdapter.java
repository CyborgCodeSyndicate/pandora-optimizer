package io.cyborgcode.pandora.spi;

import io.cyborgcode.pandora.model.ScanRequest;
import io.cyborgcode.pandora.model.ScanResult;
import io.cyborgcode.pandora.util.Json;
import io.cyborgcode.pandora.util.Schemas;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Exchanges versioned JSON with a language adapter in a bounded child process.
 *
 * @author Cyborg Code Syndicate 💍👨💻
 */
public final class ProcessAdapter implements LanguageAdapter {

   private final String language;

   private final List<String> command;

   /** Configures the language identifier and explicit adapter command arguments. */
   public ProcessAdapter(String language, List<String> command) {
      this.language = language;
      this.command = List.copyOf(command);
   }

   @Override
   public String language() {
      return language;
   }

   @Override
   public ScanResult scan(ScanRequest request) throws Exception {
      Process process = new ProcessBuilder(command).directory(new File(request.root())).start();
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
         Future<byte[]> output =
               executor.submit(() -> bounded(process.getInputStream(), 32 * 1024 * 1024));
         Future<byte[]> errors =
               executor.submit(() -> bounded(process.getErrorStream(), 1024 * 1024));
         Future<?> write =
               executor.submit(
                     () -> {
                        try (var input = process.getOutputStream()) {
                           input.write(
                                 Json.MAPPER
                                       .writer()
                                       .without(
                                             com.fasterxml.jackson.databind
                                                   .SerializationFeature
                                                   .INDENT_OUTPUT)
                                       .writeValueAsBytes(request));
                           input.write('\n');
                        } catch (IOException e) {
                           throw new UncheckedIOException(e);
                        }
                     });
         try {
            if (!process.waitFor(90, TimeUnit.SECONDS)) {
               throw new IOException("Adapter timed out");
            }
            write.get(5, TimeUnit.SECONDS);
            byte[] bytes = output.get(5, TimeUnit.SECONDS);
            errors.get(5, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
               throw new IOException(
                     "Adapter failed with exit code "
                           + process.exitValue()
                           + "; check its configured command and references");
            }
            var document = Json.MAPPER.readTree(bytes);
            if (!Schemas.valid("adapter-response", document)) {
               throw new IOException("Adapter response does not satisfy protocol v1");
            }
            ScanResult result = Json.MAPPER.treeToValue(document, ScanResult.class);
            if (result.protocolVersion() != 1) {
               throw new IOException("Unsupported adapter protocol");
            }
            return result;
         } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            write.cancel(true);
            output.cancel(true);
            errors.cancel(true);
         }
      }
   }

   private static byte[] bounded(InputStream stream, int limit) throws IOException {
      try (stream) {
         byte[] bytes = stream.readNBytes(limit + 1);
         if (bytes.length > limit) {
            throw new IOException("Adapter output exceeded limit");
         }
         return bytes;
      }
   }
}
