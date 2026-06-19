package com.sevenewf.workflow.backend.health;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class HealthEndpointTest {
  private HttpServer server;
  private URI healthUri;

  @BeforeEach
  void startServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/health", new HealthEndpoint());
    server.start();
    healthUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/health");
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Test
  void returnsHealthyStatusWithoutExternalDependencies() throws Exception {
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(healthUri).GET().build(),
                HttpResponse.BodyHandlers.ofString());

    Assertions.assertEquals(200, response.statusCode());
    Assertions.assertEquals(
        "{\"status\":\"UP\",\"service\":\"workflow-backend\",\"dependencies\":[]}",
        response.body());
  }
}
