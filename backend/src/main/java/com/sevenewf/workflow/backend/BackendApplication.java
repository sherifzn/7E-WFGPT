package com.sevenewf.workflow.backend;

import com.sevenewf.workflow.backend.health.HealthEndpoint;
import com.sevenewf.workflow.backend.keyhandover.KeyHandoverDemoHttpHandler;
import com.sevenewf.workflow.backend.logging.StructuredLogger;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;

public final class BackendApplication {
  private static final StructuredLogger LOGGER = StructuredLogger.forComponent("backend");

  private BackendApplication() {}

  public static void main(String[] args) throws IOException {
    int port = Integer.parseInt(System.getProperty("workflow.http.port", "8080"));
    Path dataDirectory = Path.of(System.getProperty("workflow.local.data.dir", "local-data"));
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
    server.createContext("/health", new HealthEndpoint());
    server.createContext("/api/key-handovers", new KeyHandoverDemoHttpHandler(dataDirectory));
    server.start();
    LOGGER.info("backend_started", "port=" + port);
  }
}
