package com.smarika.heartbeat.scheduler;

import com.smarika.heartbeat.check.*;
import com.smarika.heartbeat.monitoring.MonitoringService;
import com.smarika.heartbeat.job.JobStateService;
import com.smarika.heartbeat.job.ReportJob;
import com.smarika.heartbeat.job.ScheduledJobRepository;
import com.smarika.heartbeat.website.WebsiteChecker;
import com.smarika.heartbeat.website.WebsiteMonitoringService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.PageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Heartbeats are passive unless registration explicitly attaches an internal Java job.
 * An internal job calls its private URL only after its work completes successfully.
 * Website checks are active: the monitor performs their HTTP GET probes.
 */
@Component
public class Sweeper {
    private static final Logger log = LoggerFactory.getLogger(Sweeper.class);
    private final CheckRepository checks;
    private final MonitoringService monitoring;
    private final WebsiteChecker websiteChecker;
    private final WebsiteMonitoringService websiteMonitoring;
    private final ThreadPoolTaskExecutor websiteExecutor;
    private final ScheduledJobRepository jobs;
    private final JobStateService jobState;
    private final ReportJob reportJob;
    private final ThreadPoolTaskExecutor jobExecutor;
    private final WebClient webClient;

    // Prevent queuing the same website multiple times while a poll is in flight.
    // Per-process only; a multi-instance deployment needs distributed leases.
    private final Set<UUID> websitesInFlight = ConcurrentHashMap.newKeySet();
    private final Set<UUID> jobsInFlight = ConcurrentHashMap.newKeySet();

    public Sweeper(CheckRepository checks,
                   MonitoringService monitoring,
                   WebsiteChecker websiteChecker,
                   WebsiteMonitoringService websiteMonitoring,
                   @Qualifier("websiteExecutor") ThreadPoolTaskExecutor websiteExecutor,
                   ScheduledJobRepository jobs, JobStateService jobState, ReportJob reportJob,
                   @Qualifier("jobExecutor") ThreadPoolTaskExecutor jobExecutor, WebClient webClient) {
        this.checks = checks;
        this.monitoring = monitoring;
        this.websiteChecker = websiteChecker;
        this.websiteMonitoring = websiteMonitoring;
        this.websiteExecutor = websiteExecutor;
        this.jobs = jobs;
        this.jobState = jobState;
        this.reportJob = reportJob;
        this.jobExecutor = jobExecutor;
        this.webClient = webClient;
    }

    @Scheduled(fixedDelayString = "${monitoring.sweeper-delay-ms:1000}")
    public void sweep() {
        Instant now = Instant.now();
        startDueJobs(now);
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

    private void startDueJobs(Instant now) {
        for (UUID id : jobs.findDue(now, PageRequest.of(0, 100))) {
            if (!jobsInFlight.add(id)) {
                continue;
            }
            try {
                jobExecutor.execute(() -> {
                    try {
                        runJob(id);
                    } catch (Exception e) {
                        log.error("Could not execute internal job {}", id, e);
                    } finally {
                        jobsInFlight.remove(id);
                    }
                });
            } catch (TaskRejectedException e) {
                jobsInFlight.remove(id);
                // No database claim has been made; try again on the next sweep.
                break;
            }
        }
    }

    private void runJob(UUID id) {
        JobStateService.Execution execution = jobState.claim(id, Instant.now());
        if (execution == null) {
            return;
        }
        Path report;
        try {
            report = reportJob.execute(execution);
        } catch (Exception e) {
            jobState.complete(id, false, false, e.getMessage(), null);
            log.warn("Job {} failed; no heartbeat sent", id);
            return;
        }

        boolean delivered = false;
        try {
            // Actual HTTP callback, exactly as the problem statement requires.
            webClient.post().uri(execution.pingUrl()).retrieve()
                    .toBodilessEntity().block(Duration.ofSeconds(5));
            delivered = true;
            log.info("Job {} wrote its report and delivered its heartbeat", id);
        } catch (Exception e) {
            // Do not expose the private callback URL in logs or public job errors.
            log.warn("Job {} completed, but its heartbeat could not be delivered", id);
        }
        jobState.complete(id, true, delivered,
                delivered ? null : "Job succeeded but heartbeat delivery failed", report.toString());
    }
}
