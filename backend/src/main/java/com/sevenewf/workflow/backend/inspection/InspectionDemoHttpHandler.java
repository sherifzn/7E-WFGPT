package com.sevenewf.workflow.backend.inspection;

import com.sevenewf.workflow.domain.inspection.InspectionProcessStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class InspectionDemoHttpHandler implements HttpHandler {
  private final InspectionDemoService service;

  public InspectionDemoHttpHandler(Path dataDirectory) {
    service = new InspectionDemoService(dataDirectory);
  }

  public InspectionDemoHttpHandler(Path dataDirectory, InspectionProcessStore store) {
    service = new InspectionDemoService(dataDirectory, store);
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath().substring("/api/inspections".length());
    String query = exchange.getRequestURI().getRawQuery();
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    InspectionDemoService.ApiResponse response =
        service.handle(exchange.getRequestMethod(), path, parameters(query, body));
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
    exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    if ("OPTIONS".equals(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
      return;
    }
    byte[] payload = response.body().getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(response.status(), payload.length);
    exchange.getResponseBody().write(payload);
    exchange.close();
  }

  private static Map<String, String> parameters(String query, String body) {
    Map<String, String> values = new LinkedHashMap<>();
    addParameters(values, query);
    addParameters(values, body);
    return values;
  }

  private static void addParameters(Map<String, String> values, String encoded) {
    if (encoded == null || encoded.isBlank()) return;
    for (String pair : encoded.split("&")) {
      String[] parts = pair.split("=", 2);
      values.put(decode(parts[0]), parts.length == 2 ? decode(parts[1]) : "");
    }
  }

  private static String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }
}
