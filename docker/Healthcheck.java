import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** A JRE-only probe, compiled separately so curl is not needed in the runtime image. */
public final class Healthcheck {
  public static void main(String[] args) {
    try {
      URI uri =
          URI.create(
              args.length == 0 ? "http://127.0.0.1:8080/actuator/health/readiness" : args[0]);
      try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() == 200) return;
      }
    } catch (Exception ignored) {
      // Container health only needs an exit code; never print application data or credentials.
    }
    System.exit(1);
  }
}
