package com.fixflow.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.fixflow.desktop.api.ApiClient;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ApiClientTest {
  @Test
  void decodesRealHttpResponsesAndPreservesBusinessErrors() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/ok",
        exchange -> {
          byte[] body =
              "{\"status\":\"IN_REPAIR\",\"amount\":123.45}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.createContext(
        "/conflict",
        exchange -> {
          byte[] body = "{\"message\":\"Недостаточно деталей\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(409, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    var client = new ApiClient("http://127.0.0.1:" + server.getAddress().getPort());
    try {
      assertEquals("IN_REPAIR", client.get("/ok").get(5, TimeUnit.SECONDS).path("status").asText());
      var error =
          assertThrows(
              ExecutionException.class, () -> client.get("/conflict").get(5, TimeUnit.SECONDS));
      assertInstanceOf(ApiClient.Failure.class, error.getCause());
      assertEquals(409, ((ApiClient.Failure) error.getCause()).status());
      assertEquals("Недостаточно деталей", error.getCause().getMessage());
    } finally {
      client.stop();
      server.stop(0);
    }
  }

  @Test
  void unavailableServerProducesAConnectionState() throws Exception {
    var socket = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    int port = socket.getLocalPort();
    socket.close();
    var client = new ApiClient("http://127.0.0.1:" + port);
    try {
      var error =
          assertThrows(
              ExecutionException.class, () -> client.get("/health").get(5, TimeUnit.SECONDS));
      assertInstanceOf(ApiClient.Failure.class, error.getCause());
      assertEquals(0, ((ApiClient.Failure) error.getCause()).status());
    } finally {
      client.stop();
    }
  }
}
