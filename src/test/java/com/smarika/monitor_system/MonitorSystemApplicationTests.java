package com.smarika.heartbeat;

import com.smarika.heartbeat.alert.*;
import com.smarika.heartbeat.check.*;
import com.smarika.heartbeat.check.CheckQueryService;
import com.smarika.heartbeat.common.TokenService;
import com.smarika.heartbeat.history.CheckHistoryRepository;
import com.smarika.heartbeat.monitoring.MonitoringService;
import com.smarika.heartbeat.outage.Outage;
import com.smarika.heartbeat.outage.OutageRepository;
import com.smarika.heartbeat.ping.PingService;
import com.smarika.heartbeat.scheduler.ScheduleCalculator;
import com.smarika.heartbeat.scheduler.Sweeper;
import com.smarika.heartbeat.check.CheckLifecycleService;
import com.smarika.heartbeat.website.WebsiteCheckResult;
import com.smarika.heartbeat.website.WebsiteChecker;
import com.smarika.heartbeat.website.WebsiteMonitoringService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(
        classes = HeartbeatMonitorApplication.class,
        properties = {
                "monitoring.sweeper-delay-ms=3600000",
                "alerts.retry-delay-ms=3600000"
        }
)
class MonitorSystemApplicationTests {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private CheckRepository checkRepository;

    @Autowired
    private CheckQueryService checkQueryService;

    @Autowired
    private CheckService checkService;

    @Autowired
    private OutageRepository outageRepository;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private CheckHistoryRepository historyRepository;

    @Autowired
    private MonitoringService monitoringService;

    @Autowired
    private Sweeper sweeper;

    @Autowired
    private CheckLifecycleService lifecycleService;

    @Autowired
    private PingService pingService;

    @Autowired
    private ScheduleCalculator scheduleCalculator;

    @Autowired
    private AlertRetryService alertRetryService;

    @Autowired
    private AlertStateService alertStateService;

    @Autowired
    private WebsiteChecker websiteChecker;

    @Autowired
    private WebsiteMonitoringService websiteMonitoringService;

    @Autowired
    private TokenService tokenService;

    private HttpServer server;
    private ExecutorService serverWorkers;

    @BeforeEach
    void clearDatabase() {
        alertRepository.deleteAll();
        historyRepository.deleteAll();
        outageRepository.deleteAll();
        checkRepository.deleteAll();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (serverWorkers != null) {
            serverWorkers.shutdownNow();
            serverWorkers = null;
        }
    }

    @Test
    void oneOutageCreatesOneDownAlertAndOneRecoveryAlert() {
        String token = "heartbeat-token";
        Instant expected = Instant.now().minusSeconds(60);
        Check check = saveCheck(
                CheckType.HEARTBEAT,
                expected,
                5,
                null,
                "http://127.0.0.1:1/alerts"
        );
        check.setPingTokenHash(tokenService.hashToken(token));
        checkRepository.save(check);

        monitoringService.processHeartbeat(check.getId(), expected.plusSeconds(3));
        assertEquals(CheckStatus.LATE, checkRepository.findById(check.getId()).orElseThrow().getStatus());
        assertTrue(alertRepository.findAll().isEmpty());

        Instant downAt = expected.plusSeconds(6);
        monitoringService.processHeartbeat(check.getId(), downAt);
        for (int sweep = 0; sweep < 10; sweep++) {
            monitoringService.processHeartbeat(check.getId(), downAt.plusSeconds(sweep + 1));
        }
        assertEquals(1, alertRepository.findAll().size());
        assertEquals(AlertType.DOWN, alertRepository.findAll().getFirst().getAlertType());

        pingService.processPing(token);

        List<Alert> alerts = alertRepository.findAll();
        assertEquals(2, alerts.size());
        assertEquals(1, alerts.stream().filter(a -> a.getAlertType() == AlertType.DOWN).count());
        assertEquals(1, alerts.stream().filter(a -> a.getAlertType() == AlertType.RECOVERY).count());
        assertEquals(CheckStatus.UP, checkRepository.findById(check.getId()).orElseThrow().getStatus());
    }

    @Test
    void simultaneousPingAndSweepLeaveAConsistentCheckAndOutage() throws Exception {
        String token = "concurrent-heartbeat-token";
        Instant expected = Instant.now().minusSeconds(20);
        Check check = saveCheck(
                CheckType.HEARTBEAT,
                expected,
                0,
                null,
                "http://127.0.0.1:1/alerts"
        );
        check.setPingTokenHash(tokenService.hashToken(token));
        checkRepository.save(check);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> sweep = workers.submit(() -> {
                await(start);
                monitoringService.processHeartbeat(check.getId(), Instant.now());
            });
            Future<?> ping = workers.submit(() -> {
                await(start);
                pingService.processPing(token);
            });

            start.countDown();
            sweep.get(10, TimeUnit.SECONDS);
            ping.get(10, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }

        Check persisted = checkRepository.findById(check.getId()).orElseThrow();
        List<Alert> alerts = alertRepository.findAll();
        if (persisted.getStatus() == CheckStatus.UP) {
            assertTrue(alerts.isEmpty() || alerts.size() == 2);
            if (alerts.size() == 2) {
                assertEquals(1, alerts.stream().filter(a -> a.getAlertType() == AlertType.DOWN).count());
                assertEquals(1, alerts.stream().filter(a -> a.getAlertType() == AlertType.RECOVERY).count());
            }
        } else {
            assertEquals(CheckStatus.DOWN, persisted.getStatus());
            assertEquals(1, alerts.size());
            assertEquals(AlertType.DOWN, alerts.getFirst().getAlertType());
        }
    }

    @Test
    void weekdayCronMovesFridayOccurrenceToMonday() {
        Instant friday = LocalDateTime.of(2026, 10, 9, 9, 5)
                .toInstant(ZoneOffset.UTC);

        Instant next = scheduleCalculator.next(
                ScheduleType.CRON,
                "0 0 9 * * MON-FRI",
                friday
        );

        assertEquals(
                LocalDateTime.of(2026, 10, 12, 9, 0).toInstant(ZoneOffset.UTC),
                next
        );
    }

    @Test
    void checkListCanFilterByStatusAndPaginate() {
        Instant nextExpected = Instant.now().plusSeconds(60);
        Check down = saveCheck(
                CheckType.HEARTBEAT,
                nextExpected,
                5,
                null,
                "http://127.0.0.1:1/alerts"
        );
        down.setStatus(CheckStatus.DOWN);
        checkRepository.save(down);
        saveCheck(
                CheckType.HEARTBEAT,
                nextExpected,
                5,
                null,
                "http://127.0.0.1:1/alerts"
        );

        var page = checkQueryService.list(CheckStatus.DOWN, PageRequest.of(0, 1));

        assertEquals(1, page.getTotalElements());
        assertEquals(CheckStatus.DOWN, page.getContent().getFirst().status());
    }

    @Test
    void failedAlertDestinationDoesNotHoldUpAnotherAlert() throws Exception {
        startServer();
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(8_000);
                exchange.sendResponseHeaders(204, -1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // The monitor times out and closes this connection first.
            } finally {
                exchange.close();
            }
        });
        server.createContext("/fast", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        Instant now = Instant.now();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        Alert slow = saveDownAlert(
                saveCheck(CheckType.HEARTBEAT, now.plusSeconds(60), 5, null, baseUrl + "/slow"),
                now.minusSeconds(2)
        );
        Alert fast = saveDownAlert(
                saveCheck(CheckType.HEARTBEAT, now.plusSeconds(60), 5, null, baseUrl + "/fast"),
                now.minusSeconds(1)
        );

        long started = System.nanoTime();
        alertRetryService.processAlerts();
        awaitStatus(fast.getId(), AlertDeliveryStatus.DELIVERED, Duration.ofSeconds(3));
        long fastDeliverySeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);

        assertTrue(fastDeliverySeconds < 5, "The fast alert waited for the slow request");
        assertEquals(
                AlertDeliveryStatus.PROCESSING,
                alertRepository.findById(slow.getId()).orElseThrow().getDeliveryStatus()
        );

        awaitStatus(slow.getId(), AlertDeliveryStatus.PENDING, Duration.ofSeconds(8));
        Alert failedAttempt = alertRepository.findById(slow.getId()).orElseThrow();
        assertEquals(1, failedAttempt.getAttemptCount());
        assertNotNull(failedAttempt.getLastError());
        assertTrue(failedAttempt.getNextAttemptAt().isAfter(Instant.now()));
    }

    @Test
    void websiteTimeoutIsRecordedAsDown() throws Exception {
        startServer();
        server.createContext("/timeout", exchange -> {
            try {
                Thread.sleep(7_000);
                exchange.sendResponseHeaders(200, -1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // The checker times out and closes this connection first.
            } finally {
                exchange.close();
            }
        });
        server.start();

        Instant scheduledAt = Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Check check = saveCheck(
                CheckType.WEBSITE,
                scheduledAt,
                5,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/timeout",
                "http://127.0.0.1:1/alerts"
        );

        WebsiteCheckResult result = websiteChecker.check(check.getTargetUrl());
        assertFalse(result.successful());

        websiteMonitoringService.processResult(check.getId(), scheduledAt, result);

        assertEquals(CheckStatus.DOWN, checkRepository.findById(check.getId()).orElseThrow().getStatus());
        assertEquals(1, alertRepository.findAll().stream()
                .filter(alert -> alert.getAlertType() == AlertType.DOWN)
                .count());
    }

    @Test
    void creationReturnsUnguessablePrivateUrlAndAcceptsItsToken() {
        CreateCheckResponse response = checkService.createCheck(new CreateCheckRequest(
                "hourly-backup", CheckType.HEARTBEAT, ScheduleType.INTERVAL,
                "60", 5, null, "http://127.0.0.1:1/alerts"));
        assertTrue(response.pingUrl().startsWith("http://localhost:8080/api/ping/"));
        assertFalse(response.pingUrl().contains(response.id().toString()));
        String rawToken = response.pingUrl().substring(response.pingUrl().lastIndexOf('/') + 1);
        assertEquals(64, rawToken.length());
        assertNotEquals(rawToken,
                checkRepository.findById(response.id()).orElseThrow().getPingTokenHash());
        pingService.processPing(rawToken);
        assertNotNull(checkRepository.findById(response.id()).orElseThrow().getLastSeenAt());
    }

    @Test
    void permanentlyFailedAlertRemainsInspectableAfterSixAttempts() {
        Instant now = Instant.now();
        Alert alert = saveDownAlert(saveCheck(CheckType.HEARTBEAT,
                        now.plusSeconds(60), 5, null, "http://127.0.0.1:1/alerts"), now);
        for (int attempt = 1; attempt <= 6; attempt++) {
            assertTrue(alertStateService.claim(alert.getId()));
            alertStateService.markFailed(alert.getId(), "destination unreachable");
            Alert persisted = alertRepository.findById(alert.getId()).orElseThrow();
            assertEquals(attempt, persisted.getAttemptCount());
            if (attempt < 6) {
                assertEquals(AlertDeliveryStatus.PENDING, persisted.getDeliveryStatus());
                assertNotNull(persisted.getNextAttemptAt());
            } else {
                assertEquals(AlertDeliveryStatus.FAILED, persisted.getDeliveryStatus());
                assertNull(persisted.getNextAttemptAt());
                assertEquals("destination unreachable", persisted.getLastError());
            }
        }
    }

    @Test
    void sweeperNeverSendsSyntheticHeartbeatPings() {
        Instant expected = Instant.now().minusSeconds(60);
        Check check = saveCheck(CheckType.HEARTBEAT, expected, 0,
                null, "http://127.0.0.1:1/alerts");

        sweeper.sweep();

        Check persisted = checkRepository.findById(check.getId()).orElseThrow();
        assertEquals(CheckStatus.DOWN, persisted.getStatus());
        assertNull(persisted.getLastSeenAt(), "Monitor must not pretend to be the external job");
        assertEquals(1, alertRepository.findAll().size());
        assertTrue(historyRepository.findAll().stream()
                .noneMatch(event -> event.getEventType()
                        == com.smarika.heartbeat.history.HistoryEventType.PING));
    }

    @Test
    void websiteFailureTransitionsLateThenDownAfterGraceAndRecovers() {
        Instant scheduled = Instant.now().minusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Check check = saveCheck(CheckType.WEBSITE, scheduled, 30,
                "http://127.0.0.1:1/site", "http://127.0.0.1:1/alerts");
        WebsiteCheckResult failure = new WebsiteCheckResult(false, 503,
                scheduled.plusSeconds(3), Duration.ofMillis(12), "HTTP 503");
        websiteMonitoringService.processResult(check.getId(), scheduled, failure);

        Check late = checkRepository.findById(check.getId()).orElseThrow();
        assertEquals(CheckStatus.LATE, late.getStatus());
        assertEquals(scheduled, late.getFailureStartedAt());
        assertEquals(scheduled.plusSeconds(30), late.getNextExpectedAt());
        assertEquals(0, alertRepository.count(), "Grace period must suppress DOWN alert");

        Instant nextProbe = late.getNextExpectedAt();
        websiteMonitoringService.processResult(check.getId(), nextProbe,
                new WebsiteCheckResult(false, 503, nextProbe.plusSeconds(1),
                        Duration.ofMillis(12), "HTTP 503"));
        Check down = checkRepository.findById(check.getId()).orElseThrow();
        assertEquals(CheckStatus.DOWN, down.getStatus());
        assertEquals(1, alertRepository.findAll().stream()
                .filter(a -> a.getAlertType() == AlertType.DOWN).count());

        // A delayed result from the old poll must not undo the new status.
        websiteMonitoringService.processResult(check.getId(), scheduled,
                new WebsiteCheckResult(true, 200, nextProbe.plusSeconds(2),
                        Duration.ofMillis(3), null));
        assertEquals(CheckStatus.DOWN,
                checkRepository.findById(check.getId()).orElseThrow().getStatus());

        Instant recoveryPoll = down.getNextExpectedAt();
        websiteMonitoringService.processResult(check.getId(), recoveryPoll,
                new WebsiteCheckResult(true, 200, recoveryPoll.plusSeconds(1),
                        Duration.ofMillis(3), null));
        Check recovered = checkRepository.findById(check.getId()).orElseThrow();
        assertEquals(CheckStatus.UP, recovered.getStatus());
        assertNull(recovered.getFailureStartedAt());
        assertEquals(1, alertRepository.findAll().stream()
                .filter(a -> a.getAlertType() == AlertType.RECOVERY).count());
    }

    @Test
    void pausingDownCheckClosesOutageAndAllowsFutureDistinctOutage() {
        Instant expected = Instant.now().minusSeconds(60);
        Check check = saveCheck(CheckType.HEARTBEAT, expected, 0,
                null, "http://127.0.0.1:1/alerts");
        monitoringService.processHeartbeat(check.getId(), Instant.now());
        assertEquals(1, alertRepository.count());
        assertTrue(outageRepository.findFirstByCheckIdAndEndedAtIsNull(check.getId()).isPresent());

        lifecycleService.pause(check.getId());
        assertEquals(CheckStatus.PAUSED, checkRepository.findById(check.getId()).orElseThrow().getStatus());
        assertTrue(outageRepository.findFirstByCheckIdAndEndedAtIsNull(check.getId()).isEmpty());
        assertEquals(1, alertRepository.count(), "Pausing is not a successful recovery");

        lifecycleService.resume(check.getId());
        Check resumed = checkRepository.findById(check.getId()).orElseThrow();
        assertEquals(CheckStatus.UP, resumed.getStatus());
        assertTrue(resumed.getNextExpectedAt().isAfter(Instant.now()));

        resumed.setNextExpectedAt(Instant.now().minusSeconds(30));
        resumed.setNextActionAt(resumed.getNextExpectedAt());
        checkRepository.save(resumed);
        monitoringService.processHeartbeat(check.getId(), Instant.now());
        assertEquals(2, alertRepository.findAll().stream()
                .filter(a -> a.getAlertType() == AlertType.DOWN).count(),
                "A second outage after resume must produce its own DOWN alert");
    }

    @Test
    void websiteRecoversFromLateWithoutSendingRecoveryAlert() {
        Instant scheduled = Instant.now().minusSeconds(5).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Check check = saveCheck(CheckType.WEBSITE, scheduled, 30,
                "http://127.0.0.1:1/site", "http://127.0.0.1:1/alerts");
        websiteMonitoringService.processResult(check.getId(), scheduled,
                new WebsiteCheckResult(false, 503, scheduled.plusSeconds(1),
                        Duration.ofMillis(12), "HTTP 503"));
        Instant next = checkRepository.findById(check.getId()).orElseThrow().getNextExpectedAt();
        websiteMonitoringService.processResult(check.getId(), next,
                new WebsiteCheckResult(true, 200, next.plusSeconds(1),
                        Duration.ofMillis(5), null));
        assertEquals(CheckStatus.UP,
                checkRepository.findById(check.getId()).orElseThrow().getStatus());
        assertEquals(0, alertRepository.count());
    }

    private Check saveCheck(
            CheckType type,
            Instant nextExpectedAt,
            long graceSeconds,
            String targetUrl,
            String alertUrl
    ) {
        Instant now = Instant.now();
        Check check = new Check();
        check.setId(UUID.randomUUID());
        check.setName("test-" + UUID.randomUUID());
        check.setType(type);
        check.setStatus(CheckStatus.UP);
        check.setScheduleType(ScheduleType.INTERVAL);
        check.setScheduleExpression("60");
        check.setGracePeriodSeconds(graceSeconds);
        check.setTargetUrl(targetUrl);
        if (type == CheckType.HEARTBEAT) {
            check.setPingTokenHash(UUID.randomUUID().toString());
        }
        check.setAlertUrl(alertUrl);
        check.setCreatedAt(now);
        check.setUpdatedAt(now);
        check.setNextExpectedAt(nextExpectedAt);
        check.setNextActionAt(nextExpectedAt);
        return checkRepository.save(check);
    }

    private Alert saveDownAlert(Check check, Instant nextAttemptAt) {
        Instant now = Instant.now();
        Outage outage = new Outage();
        outage.setId(UUID.randomUUID());
        outage.setCheck(check);
        outage.setStartedAt(now.minusSeconds(30));
        outage.setCreatedAt(now);
        outage = outageRepository.save(outage);

        Alert alert = new Alert();
        alert.setId(UUID.randomUUID());
        alert.setCheck(check);
        alert.setOutage(outage);
        alert.setAlertType(AlertType.DOWN);
        alert.setDeliveryStatus(AlertDeliveryStatus.PENDING);
        alert.setAttemptCount(0);
        alert.setNextAttemptAt(nextAttemptAt);
        alert.setCreatedAt(now);
        return alertRepository.save(alert);
    }

    private void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverWorkers = Executors.newCachedThreadPool();
        server.setExecutor(serverWorkers);
    }

    private void awaitStatus(
            UUID alertId,
            AlertDeliveryStatus expected,
            Duration timeout
    ) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (alertRepository.findById(alertId)
                    .map(Alert::getDeliveryStatus)
                    .orElse(null) == expected) {
                return;
            }
            Thread.sleep(50);
        }
        assertEquals(
                expected,
                alertRepository.findById(alertId).orElseThrow().getDeliveryStatus()
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to start concurrent operations");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
