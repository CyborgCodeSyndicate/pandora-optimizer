package io.cyborgcode.pandora.resources;

import io.cyborgcode.pandora.spi.ResourceProvider;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.TimeUnit;

/**
 * Bounded response and total timeout; redirects are intentionally not followed.
 *
 * @author Cyborg Code Syndicate 💍👨💻
 */
public final class HttpsResourceProvider implements ResourceProvider {

   private final HttpClient client;

   private final boolean allowLocalHttp;

   /** Creates a bounded HTTPS client that rejects redirects. */
   public HttpsResourceProvider() {
      this(false);
   }

   /**
    * Local HTTP exists for integration tests only. Production uses the no-argument constructor.
    */
   public HttpsResourceProvider(boolean allowLocalHttp) {
      this.allowLocalHttp = allowLocalHttp;
      client =
            HttpClient.newBuilder()
                  .connectTimeout(Duration.ofSeconds(10))
                  .followRedirects(HttpClient.Redirect.NEVER)
                  .build();
   }

   @Override
   public boolean supports(URI uri) {
      return "https".equals(uri.getScheme())
            || (allowLocalHttp
                  && "http".equals(uri.getScheme())
                  && Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost()));
   }

   @Override
   public byte[] read(URI uri, Path workspace, Map<String, String> headers, int maxBytes)
         throws IOException {
      if (!supports(uri) || uri.getUserInfo() != null || uri.getHost() == null) {
         throw new IOException("Invalid HTTPS endpoint");
      }
      var builder =
            HttpRequest.newBuilder(uri)
                  .timeout(Duration.ofSeconds(20))
                  .header("Accept", "application/json")
                  .GET();
      headers.forEach(builder::header);
      try {
         var future = client.sendAsync(builder.build(), info -> new LimitedSubscriber(maxBytes));
         var response = future.orTimeout(20, TimeUnit.SECONDS).get();
         if (response.statusCode() != 200) {
            throw new IOException("Resource returned HTTP " + response.statusCode());
         }
         return response.body();
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         throw new IOException("Resource request interrupted");
      } catch (ExecutionException | RuntimeException e) {
         // Do not disclose endpoint query strings, bearer tokens or underlying request
         // exceptions.
         throw new IOException("Resource request failed or exceeded its time/size limit");
      }
   }

   private static final class LimitedSubscriber implements HttpResponse.BodySubscriber<byte[]> {

      private final HttpResponse.BodySubscriber<byte[]> delegate =
            HttpResponse.BodySubscribers.ofByteArray();

      private final int limit;

      private int count;

      private Subscription subscription;

      LimitedSubscriber(int limit) {
         this.limit = limit;
      }

      @Override
      public CompletionStage<byte[]> getBody() {
         return delegate.getBody();
      }

      @Override
      public void onSubscribe(Subscription s) {
         subscription = s;
         delegate.onSubscribe(s);
      }

      @Override
      public void onNext(List<ByteBuffer> buffers) {
         long incoming = buffers.stream().mapToLong(ByteBuffer::remaining).sum();
         if (incoming > limit - count) {
            subscription.cancel();
            delegate.onError(new IOException("Resource exceeds size limit"));
            return;
         }
         count += (int) incoming;
         delegate.onNext(buffers);
      }

      @Override
      public void onError(Throwable t) {
         delegate.onError(t);
      }

      @Override
      public void onComplete() {
         delegate.onComplete();
      }
   }
}
