package com.sevenewf.workflow.backend;

import com.sevenewf.workflow.backend.health.HealthEndpoint;
import com.sevenewf.workflow.backend.logging.StructuredLogger;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;

public final class BackendApplication {
  private static final StructuredLogger LOGGER = StructuredLogger.forComponent("backend");

  private BackendApplication() {}

  public static void main(String[] args) throws IOException {
    int port = Integer.parseInt(System.getProperty("workflow.http.port", "8080"));
    HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/health", new HealthEndpoint());
    server.start();
    LOGGER.info("backend_started", "port=" + port);
  }
}
