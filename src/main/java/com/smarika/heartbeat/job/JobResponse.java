package com.smarika.heartbeat.job;

import java.time.Instant;
import java.util.UUID;

// Deliberately excludes the private ping URL, which is returned only at registration.
public record JobResponse(UUID checkId, JobMode mode, Instant nextRunAt, Instant lastRunAt,
                          JobMode lastOutcome, Boolean heartbeatDelivered,
                          long successfulRuns, long failedRuns, String lastError, String reportPath) {
    public static JobResponse from(ScheduledJob job) {
        return new JobResponse(job.getCheckId(), job.getMode(), job.getNextRunAt(), job.getLastRunAt(),
                job.getLastOutcome(), job.getHeartbeatDelivered(), job.getSuccessfulRuns(),
                job.getFailedRuns(), job.getLastError(), job.getReportPath());
    }
}
