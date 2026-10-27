package com.fixflow.desktop.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class FixFlowGateway {
  private final ApiClient client;
  private final java.util.concurrent.ConcurrentHashMap<String, String> pendingKeys =
      new java.util.concurrent.ConcurrentHashMap<>();

  public FixFlowGateway(ApiClient client) {
    this.client = client;
  }

  public CompletableFuture<JsonNode> login(String email, String password) {
    return client.login(email, password);
  }

  public CompletableFuture<JsonNode> logout() {
    return client.logout();
  }

  public void clear() {
    client.clear();
    pendingKeys.clear();
  }

  public CompletableFuture<JsonNode> changePassword(Map<String, Object> body) {
    return client.mutate("POST", "/api/v1/auth/change-password", body);
  }

  public CompletableFuture<JsonNode> settings() {
    return client.get("/api/v1/shop-settings");
  }

  public CompletableFuture<JsonNode> saveSettings(Map<String, Object> body) {
    return client.mutate("PUT", "/api/v1/shop-settings", body);
  }

  private String query(Map<String, String> filters) {
    return String.join(
        "&",
        filters.entrySet().stream()
            .map(e -> ApiClient.encode(e.getKey()) + "=" + ApiClient.encode(e.getValue()))
            .toList());
  }

  public CompletableFuture<JsonNode> report(String type, Map<String, String> filters) {
    return client.get("/api/v1/reports/" + type + "?" + query(filters));
  }

  public CompletableFuture<String> reportCsv(String type, Map<String, String> filters) {
    return client.text("/api/v1/reports/" + type + "/csv?" + query(filters));
  }

  public CompletableFuture<JsonNode> markRead(String id) {
    return client.mutate("PATCH", "/api/v1/notifications/" + id + "/read", Map.of());
  }

  public CompletableFuture<JsonNode> unread() {
    return client.get("/api/v1/notifications/unread-count");
  }

  public CompletableFuture<JsonNode> revokeTracking(String id) {
    return client.mutate("DELETE", "/api/v1/repair-orders/" + id + "/tracking", Map.of());
  }

  public String publicUrl(JsonNode issued) {
    return client.absolute(issued.path("path").asText());
  }

  public CompletableFuture<JsonNode> document(String order, String kind, String payment) {
    return client.get(
        "/api/v1/repair-orders/"
            + order
            + "/documents/"
            + kind
            + (payment == null ? "" : "?payment_id=" + ApiClient.encode(payment)));
  }

  public CompletableFuture<JsonNode> list(String resource, Map<String, String> filters) {
    String query =
        String.join(
            "&",
            filters.entrySet().stream()
                .map(e -> ApiClient.encode(e.getKey()) + "=" + ApiClient.encode(e.getValue()))
                .toList());
    return client.get("/api/v1/" + resource + (query.isEmpty() ? "" : "?" + query));
  }

  public CompletableFuture<JsonNode> details(String resource, String id) {
    return client.get("/api/v1/" + resource + "/" + id);
  }

  public CompletableFuture<JsonNode> save(String resource, String id, Map<String, Object> values) {
    String path = "/api/v1/" + resource + (id == null ? "" : "/" + id);
    return id == null ? post(path, values) : client.mutate("PUT", path, values);
  }

  public CompletableFuture<JsonNode> command(
      String resource, String id, String action, Map<String, Object> values) {
    return post("/api/v1/" + resource + "/" + id + "/" + action, values);
  }

  public CompletableFuture<JsonNode> updateWorkPrice(
      String order, String work, Map<String, Object> values) {
    return client.mutate(
        "PUT", "/api/v1/repair-orders/" + order + "/works/" + work + "/price", values);
  }

  public CompletableFuture<JsonNode> updatePurchaseItem(
      String purchase, String item, Map<String, Object> values) {
    return client.mutate("PUT", "/api/v1/purchase-orders/" + purchase + "/items/" + item, values);
  }

  private CompletableFuture<JsonNode> post(String path, Map<String, Object> values) {
    String operation = path + new TreeMap<>(values);
    String key = pendingKeys.computeIfAbsent(operation, k -> UUID.randomUUID().toString());
    return client
        .mutate("POST", path, values, key)
        .thenApply(
            result -> {
              pendingKeys.remove(operation, key);
              return result;
            });
  }

  public CompletableFuture<Map<String, List<JsonNode>>> lookups(boolean manager) {
    return lookups(manager, false);
  }

  public CompletableFuture<Map<String, List<JsonNode>>> lookups(boolean manager, boolean owner) {
    var resources =
        new ArrayList<>(
            manager
                ? List.of(
                    "customers",
                    "devices",
                    "technicians",
                    "parts",
                    "suppliers",
                    "repair-orders",
                    "notification-recipients")
                : List.of("technicians", "parts"));
    if (owner) resources.addAll(List.of("purchase-orders", "users"));
    var tasks = new LinkedHashMap<String, CompletableFuture<List<JsonNode>>>();
    for (String name : resources) tasks.put(name, all(name, 0, new ArrayList<>()));
    return CompletableFuture.allOf(tasks.values().toArray(CompletableFuture[]::new))
        .thenApply(
            v -> {
              var map = new HashMap<String, List<JsonNode>>();
              tasks.forEach((k, f) -> map.put(k, f.join()));
              return map;
            });
  }

  private CompletableFuture<List<JsonNode>> all(String resource, int page, List<JsonNode> rows) {
    return list(resource, Map.of("page", String.valueOf(page), "size", "100"))
        .thenCompose(
            result -> {
              result.path("content").forEach(rows::add);
              return rows.size() < result.path("totalElements").asLong()
                  ? all(resource, page + 1, rows)
                  : CompletableFuture.completedFuture(rows);
            });
  }

  public void stop() {
    client.stop();
  }
}
