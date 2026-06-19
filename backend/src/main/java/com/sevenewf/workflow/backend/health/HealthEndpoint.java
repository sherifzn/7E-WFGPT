package com.sevenewf.workflow.backend.health;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class HealthEndpoint implements HttpHandler {
  private static final byte[] RESPONSE =
      """
      {"status":"UP","service":"workflow-backend","dependencies":[]}
      """
          .trim()
          .getBytes(StandardCharsets.UTF_8);

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    if (!"GET".equals(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(405, -1);
      return;
    }

    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(200, RESPONSE.length);
    exchange.getResponseBody().write(RESPONSE);
    exchange.close();
  }
}
