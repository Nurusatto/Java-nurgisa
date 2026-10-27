package com.fixflow.desktop.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public final class ApiClient {
  public static class Failure extends RuntimeException {
    private final int status;
    private final Map<String, String> fields;

    public Failure(int status, String message) {
      this(status, message, Map.of());
    }

    public Failure(int status, String message, Map<String, String> fields) {
      super(message);
      this.status = status;
      this.fields = Map.copyOf(fields);
    }

    public int status() {
      return status;
    }

    public Map<String, String> fields() {
      return fields;
    }
  }

  private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
  private final CookieManager cookies =
      new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
  private final HttpClient http =
      HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(10)).build();
  private final URI base;
  private volatile String csrf;
  private volatile String csrfHeader = "X-CSRF-TOKEN";

  public ApiClient(String address) {
    base = validateAddress(address);
  }

  public static URI validateAddress(String address) {
    try {
      var uri = URI.create(address);
      if (!Set.of("http", "https").contains(uri.getScheme())
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null
          || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
        throw new IllegalArgumentException();
      return uri;
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "Адрес сервера должен иметь вид http://localhost:8080 или https://example.com");
    }
  }

  public CompletableFuture<JsonNode> get(String path) {
    return send("GET", path, null, null);
  }

  public CompletableFuture<String> text(String path) {
    return http.sendAsync(
            HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString())
        .thenApplyAsync(
            response -> {
              if (response.statusCode() >= 400)
                throw new Failure(response.statusCode(), "Не удалось получить документ");
              return response.body();
            });
  }

  public String absolute(String path) {
    return base.resolve(path).toString();
  }

  public CompletableFuture<JsonNode> mutate(String method, String path, Object body) {
    try {
      return send(method, path, json.writeValueAsString(body), "application/json");
    } catch (Exception e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  public CompletableFuture<JsonNode> mutate(String method, String path, Object body, String key) {
    try {
      return send(method, path, json.writeValueAsString(body), "application/json", key);
    } catch (Exception e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  private CompletableFuture<JsonNode> csrf() {
    return get("/api/v1/auth/csrf")
        .thenApply(
            node -> {
              csrf = node.path("token").asText();
              csrfHeader = node.path("headerName").asText();
              return node;
            });
  }

  public CompletableFuture<JsonNode> login(String email, String password) {
    return csrf()
        .thenCompose(
            ignored ->
                send(
                    "POST",
                    "/api/v1/auth/login",
                    "email="
                        + encode(email.toLowerCase(Locale.ROOT))
                        + "&password="
                        + encode(password),
                    "application/x-www-form-urlencoded"))
        .thenCompose(user -> csrf().thenApply(ignored -> user));
  }

  public CompletableFuture<JsonNode> logout() {
    return mutate("POST", "/api/v1/auth/logout", Map.of()).whenComplete((v, e) -> clear());
  }

  public void clear() {
    cookies.getCookieStore().removeAll();
    csrf = null;
  }

  public void stop() {
    http.shutdownNow();
  }

  private CompletableFuture<JsonNode> send(
      String method, String path, String body, String contentType) {
    return send(method, path, body, contentType, null);
  }

  private CompletableFuture<JsonNode> send(
      String method, String path, String body, String contentType, String key) {
    var builder =
        HttpRequest.newBuilder(base.resolve(path))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json");
    if (contentType != null) builder.header("Content-Type", contentType);
    if (key != null) builder.header("Idempotency-Key", key);
    if (!method.equals("GET") && csrf != null) builder.header(csrfHeader, csrf);
    builder.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    return http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
        .thenApplyAsync(
            response -> {
              try {
                JsonNode result =
                    response.body().isBlank()
                        ? json.createObjectNode()
                        : json.readTree(response.body());
                if (response.statusCode() >= 400) {
                  var fields = new LinkedHashMap<String, String>();
                  result
                      .path("fieldErrors")
                      .fields()
                      .forEachRemaining(e -> fields.put(e.getKey(), e.getValue().asText()));
                  throw new Failure(
                      response.statusCode(),
                      result.path("message").asText("Сервер отклонил запрос"),
                      fields);
                }
                return result;
              } catch (Failure e) {
                throw e;
              } catch (Exception e) {
                throw new Failure(response.statusCode(), "Сервер вернул некорректный ответ");
              }
            })
        .exceptionally(
            error -> {
              Throwable cause = error;
              while (cause instanceof CompletionException && cause.getCause() != null)
                cause = cause.getCause();
              if (cause instanceof Failure f) throw f;
              throw new Failure(0, "Нет соединения с сервером. Проверьте адрес и повторите запрос");
            });
  }

  public static String encode(String text) {
    return URLEncoder.encode(text, StandardCharsets.UTF_8);
  }
}
