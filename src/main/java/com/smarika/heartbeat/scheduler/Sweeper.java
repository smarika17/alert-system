package com.smarika.heartbeat.scheduler;

import com.smarika.heartbeat.check.*;
import com.smarika.heartbeat.monitoring.MonitoringService;
import com.smarika.heartbeat.website.WebsiteChecker;
import com.smarika.heartbeat.website.WebsiteMonitoringService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Heartbeats are passive: only the external job can POST its private token.
 * Website checks are active: the monitor performs their HTTP GET probes.
 */
@Component
public class Sweeper {
    private final CheckRepository checks;
    private final MonitoringService monitoring;
    private final WebsiteChecker websiteChecker;
    private final WebsiteMonitoringService websiteMonitoring;
    private final ThreadPoolTaskExecutor websiteExecutor;

    // Prevent queuing the same website multiple times while a poll is in flight.
    // Per-process only; a multi-instance deployment needs distributed leases.
    private final Set<UUID> websitesInFlight = ConcurrentHashMap.newKeySet();

    public Sweeper(CheckRepository checks,
                   MonitoringService monitoring,
                   WebsiteChecker websiteChecker,
                   WebsiteMonitoringService websiteMonitoring,
                   @Qualifier("websiteExecutor") ThreadPoolTaskExecutor websiteExecutor) {
        this.checks = checks;
        this.monitoring = monitoring;
        this.websiteChecker = websiteChecker;
        this.websiteMonitoring = websiteMonitoring;
        this.websiteExecutor = websiteExecutor;
    }

    @Scheduled(fixedDelayString = "${monitoring.sweeper-delay-ms:10000}")
    public void sweep() {
        Instant now = Instant.now();
        for (Check check : checks.findTop100ByStatusNotAndNextActionAtLessThanEqualOrderByNextActionAtAsc(
                CheckStatus.PAUSED, now)) {
            if (check.getType() == CheckType.HEARTBEAT) {
                monitoring.processHeartbeat(check.getId(), now);
                continue;
            }
            UUID id = check.getId();
            if (!websitesInFlight.add(id)) {
                continue;
            }
            Instant scheduledAt = check.getNextExpectedAt();
            String targetUrl = check.getTargetUrl();
            try {
                websiteExecutor.execute(() -> {
                    try {
                        websiteMonitoring.processResult(id, scheduledAt, websiteChecker.check(targetUrl));
                    } finally {
                        websitesInFlight.remove(id);
                    }
                });
            } catch (TaskRejectedException rejected) {
                websitesInFlight.remove(id);
                // Keep nextExpectedAt unchanged so the database retries later.
                break;
            }
        }
    }
}
