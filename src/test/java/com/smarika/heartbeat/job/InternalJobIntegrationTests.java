package com.smarika.heartbeat.job;

import com.jayway.jsonpath.JsonPath;
import com.smarika.heartbeat.HeartbeatMonitorApplication;
import com.smarika.heartbeat.alert.AlertRepository;
import com.smarika.heartbeat.check.CheckRepository;
import com.smarika.heartbeat.check.CheckStatus;
import com.smarika.heartbeat.check.CheckLifecycleService;
import com.smarika.heartbeat.history.CheckHistoryRepository;
import com.smarika.heartbeat.history.HistoryEventType;
import com.smarika.heartbeat.outage.OutageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = HeartbeatMonitorApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {"monitoring.sweeper-delay-ms=100", "alerts.retry-delay-ms=100",
                "alerts.retry-initial-delay-ms=100"})
class InternalJobIntegrationTests {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final int PORT = freePort();
    private static final String BASE = "http://127.0.0.1:" + PORT;
    private static final Path REPORTS = temporaryDirectory();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("server.port", () -> PORT);
        registry.add("monitoring.public-base-url", () -> BASE);
        registry.add("monitoring.job-report-directory", REPORTS::toString);
    }

    @Autowired CheckRepository checks;
    @Autowired ScheduledJobRepository jobs;
    @Autowired AlertRepository alerts;
    @Autowired CheckHistoryRepository history;
    @Autowired OutageRepository outages;
    @Autowired CheckLifecycleService lifecycle;
    @Autowired @Qualifier("jobExecutor") ThreadPoolTaskExecutor jobExecutor;

    @BeforeEach
    void cleanDatabase() {
        alerts.deleteAll();
        history.deleteAll();
        outages.deleteAll();
        jobs.deleteAll();
        checks.deleteAll();
    }

    @AfterEach
    void stopJobs() throws InterruptedException {
        for (ScheduledJob job : jobs.findAll()) lifecycle.pause(job.getCheckId());
        await(() -> jobExecutor.getActiveCount() == 0, "In-flight jobs to finish");
    }

    @Test
    void automaticReportSuccessFailureAlertAndRecoveryUseRealHttpCallbacks() throws Exception {
        String created = post("/api/checks", """
                {"name":"automatic-report","type":"HEARTBEAT","scheduleType":"INTERVAL",
                 "scheduleExpression":"1","gracePeriodSeconds":1,"runAutomatically":true}
                """, 200);
        UUID id = UUID.fromString(read(created, "$.id"));
        String pingUrl = read(created, "$.pingUrl");
        assertTrue(pingUrl.startsWith(BASE + "/api/ping/"));
        assertFalse(pingUrl.contains(id.toString()));
        assertEquals(BASE + "/api/alerts/receive", read(created, "$.alertUrl"));
        assertNotEquals(pingUrl.substring(pingUrl.lastIndexOf('/') + 1),
                checks.findById(id).orElseThrow().getPingTokenHash());
        assertEquals(pingUrl, jobs.findById(id).orElseThrow().getPingUrl());

        await(() -> jobs.findById(id).orElseThrow().getSuccessfulRuns() >= 1, "First automatic run");
        var success = jobs.findById(id).orElseThrow();
        assertTrue(success.getHeartbeatDelivered());
        assertEquals(JobMode.SUCCESS, success.getLastOutcome());
        assertEquals(CheckStatus.UP, checks.findById(id).orElseThrow().getStatus());
        assertNotNull(checks.findById(id).orElseThrow().getLastSeenAt());
        assertTrue(Files.readString(Path.of(success.getReportPath())).contains("Job: automatic-report"));
        assertTrue(history.findByCheckIdOrderByOccurredAtDesc(id,
                org.springframework.data.domain.PageRequest.of(0, 100)).stream()
                .anyMatch(event -> event.getEventType() == HistoryEventType.PING));
        assertFalse(get("/api/checks/" + id + "/job").contains(pingUrl));

        post("/api/checks/" + id + "/job/mode", "{\"mode\":\"FAILURE\"}", 200);
        await(() -> jobs.findById(id).orElseThrow().getFailedRuns() >= 1, "A failed run");
        var lastSeen = checks.findById(id).orElseThrow().getLastSeenAt();
        assertFalse(jobs.findById(id).orElseThrow().getHeartbeatDelivered());
        await(() -> checks.findById(id).orElseThrow().getStatus() == CheckStatus.DOWN, "Missed-heartbeat DOWN");
        assertEquals(lastSeen, checks.findById(id).orElseThrow().getLastSeenAt(),
                "Failed work must not send a success heartbeat");
        await(() -> deliveredCount("DOWN") == 1, "DOWN webhook delivery");
        assertTrue(outages.findFirstByCheckIdAndEndedAtIsNull(id).isPresent());
        assertNotNull(jobs.findById(id).orElseThrow().getNextRunAt(), "DOWN must not stop future job runs");

        post("/api/checks/" + id + "/job/mode", "{\"mode\":\"SUCCESS\"}", 200);
        await(() -> checks.findById(id).orElseThrow().getStatus() == CheckStatus.UP, "Successful recovery");
        await(() -> deliveredCount("RECOVERY") == 1, "Recovery webhook delivery");
        assertTrue(outages.findFirstByCheckIdAndEndedAtIsNull(id).isEmpty());
        assertEquals(2, alerts.count(), "One DOWN and one RECOVERY for this outage");

        post("/api/checks/" + id + "/pause", "", 200);
        assertNull(jobs.findById(id).orElseThrow().getNextRunAt());
        assertEquals(CheckStatus.PAUSED, checks.findById(id).orElseThrow().getStatus());
        // Allow any already-started execution to finish, then verify pause stops scheduling.
        Thread.sleep(300);
        long runs = jobs.findById(id).orElseThrow().getSuccessfulRuns();
        Thread.sleep(1300);
        assertEquals(runs, jobs.findById(id).orElseThrow().getSuccessfulRuns());
        post("/api/checks/" + id + "/resume", "", 200);
        await(() -> jobs.findById(id).orElseThrow().getSuccessfulRuns() > runs, "Run after resume");
        post("/api/checks/" + id + "/pause", "", 200);
    }

    @Test
    void passiveChecksStayPassiveAndWebsiteCannotAttachAnInternalJob() throws Exception {
        String created = post("/api/checks", """
                {"name":"external-job","type":"HEARTBEAT","scheduleType":"INTERVAL",
                 "scheduleExpression":"1","gracePeriodSeconds":0}
                """, 200);
        UUID id = UUID.fromString(read(created, "$.id"));
        assertFalse((Boolean) read(created, "$.runAutomatically"));
        assertFalse(jobs.existsById(id));
        post("/api/checks/" + id + "/job/mode", "{\"mode\":\"SUCCESS\"}", 400);
        post("/api/checks", """
                {"name":"website","type":"WEBSITE","targetUrl":"http://localhost:8080",
                 "scheduleType":"INTERVAL","scheduleExpression":"10",
                 "gracePeriodSeconds":5,"runAutomatically":true}
                """, 400);
        await(() -> checks.findById(id).orElseThrow().getStatus() == CheckStatus.DOWN, "Passive check timeout");
        assertNull(checks.findById(id).orElseThrow().getLastSeenAt());
        await(() -> deliveredCount("DOWN") == 1, "Passive-check alert delivery");
    }

    private long deliveredCount(String type) {
        java.util.List<?> matches = read(get("/api/alerts"),
                "$.content[?(@.alertType == '" + type + "' && @.deliveryStatus == 'DELIVERED')]");
        return matches.size();
    }

    private String get(String path) {
        try {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(BASE + path))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            return response.body();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String post(String path, String body, int status) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode(), response.body());
        return response.body();
    }

    private static <T> T read(String json, String path) {
        return JsonPath.read(json, path);
    }

    private static void await(BooleanSupplier condition, String description) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(50);
        }
        fail("Timed out: " + description);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("internal-job-test-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
