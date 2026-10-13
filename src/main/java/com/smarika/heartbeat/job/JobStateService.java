package com.smarika.heartbeat.job;

import com.smarika.heartbeat.check.Check;
import com.smarika.heartbeat.check.CheckRepository;
import com.smarika.heartbeat.check.CheckStatus;
import com.smarika.heartbeat.scheduler.ScheduleCalculator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class JobStateService {
    private final ScheduledJobRepository jobs;
    private final CheckRepository checks;
    private final ScheduleCalculator schedules;

    public JobStateService(ScheduledJobRepository jobs, CheckRepository checks, ScheduleCalculator schedules) {
        this.jobs = jobs;
        this.checks = checks;
        this.schedules = schedules;
    }

    @Transactional
    public void register(Check check, String pingUrl) {
        ScheduledJob job = new ScheduledJob();
        job.setCheckId(check.getId());
        job.setPingUrl(pingUrl);
        job.setNextRunAt(check.getNextExpectedAt());
        jobs.save(job);
    }

    @Transactional(readOnly = true)
    public JobResponse get(UUID id) {
        return JobResponse.from(requireJob(id));
    }

    @Transactional
    public JobResponse setMode(UUID id, JobMode mode) {
        // Consistent lock order with claim/resume: check first, then job.
        checks.findByIdForUpdate(id).orElseThrow(() -> new IllegalArgumentException("Check not found"));
        ScheduledJob job = jobs.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("Check has no internal job"));
        job.setMode(mode);
        return JobResponse.from(job);
    }

    @Transactional
    public Execution claim(UUID id, Instant now) {
        Check check = checks.findByIdForUpdate(id).orElse(null);
        if (check == null || check.getStatus() == CheckStatus.PAUSED) {
            return null;
        }
        ScheduledJob job = jobs.findByIdForUpdate(id).orElse(null);
        if (job == null || job.getNextRunAt() == null || job.getNextRunAt().isAfter(now)) {
            return null;
        }
        // The execution schedule remains active when monitoring turns DOWN.
        job.setNextRunAt(schedules.next(check.getScheduleType(), check.getScheduleExpression(), now));
        return new Execution(id, check.getName(), job.getMode(), job.getPingUrl());
    }

    @Transactional
    public void complete(UUID id, boolean success, boolean heartbeatDelivered, String error, String reportPath) {
        jobs.findByIdForUpdate(id).ifPresent(job -> {
            job.setLastRunAt(Instant.now());
            job.setLastOutcome(success ? JobMode.SUCCESS : JobMode.FAILURE);
            job.setHeartbeatDelivered(heartbeatDelivered);
            job.setLastError(error);
            if (success) {
                job.setSuccessfulRuns(job.getSuccessfulRuns() + 1);
                job.setReportPath(reportPath);
            } else {
                job.setFailedRuns(job.getFailedRuns() + 1);
            }
        });
    }

    // Called inside the lifecycle transaction while its check lock is held.
    @Transactional
    public void pause(UUID id) {
        jobs.findByIdForUpdate(id).ifPresent(job -> job.setNextRunAt(null));
    }

    @Transactional
    public void resume(Check check) {
        jobs.findByIdForUpdate(check.getId()).ifPresent(job -> job.setNextRunAt(check.getNextExpectedAt()));
    }

    private ScheduledJob requireJob(UUID id) {
        return jobs.findById(id).orElseThrow(() -> new IllegalArgumentException("Check has no internal job"));
    }

    public record Execution(UUID checkId, String name, JobMode mode, String pingUrl) {}
}
