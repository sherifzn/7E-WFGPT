package com.sevenewf.workflow.backend;

import com.sevenewf.workflow.adapters.inspection.synthetic.InspectionProcessAdapters;
import com.sevenewf.workflow.backend.health.HealthEndpoint;
import com.sevenewf.workflow.backend.inspection.InspectionDemoHttpHandler;
import com.sevenewf.workflow.backend.keyhandover.KeyHandoverDemoHttpHandler;
import com.sevenewf.workflow.backend.logging.StructuredLogger;
import com.sevenewf.workflow.domain.inspection.InspectionProcessStore;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class BackendApplication {
  private static final StructuredLogger LOGGER = StructuredLogger.forComponent("backend");

  private BackendApplication() {}

  public static void main(String[] args) throws IOException {
    int port = Integer.parseInt(System.getProperty("workflow.http.port", "8080"));
    Path dataDirectory = Path.of(System.getProperty("workflow.local.data.dir", "local-data"));
    InspectionProcessStore inspectionStore =
        new InspectionProcessAdapters.InspectionProcessSnapshotStore(
            dataDirectory.resolve("inspection-state.bin"));
    KeyHandoverDemoHttpHandler keyHandoverHandler =
        new KeyHandoverDemoHttpHandler(dataDirectory, inspectionStore);
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
    server.createContext("/health", new HealthEndpoint());
    server.createContext("/api/health", new HealthEndpoint());
    server.createContext("/api/key-handovers", keyHandoverHandler);
    server.createContext(
        "/api/inspections", new InspectionDemoHttpHandler(dataDirectory, inspectionStore));
    server.start();
    ScheduledExecutorService drainer =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "inspection-resume-drainer");
              thread.setDaemon(true);
              return thread;
            });
    drainer.scheduleAtFixedRate(
        () -> keyHandoverHandler.resumeHandler().drainPendingEvents(), 2, 2, TimeUnit.SECONDS);
    LOGGER.info("backend_started", "port=" + port);
  }
}
