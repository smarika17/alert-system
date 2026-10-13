package com.smarika.heartbeat.job;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "scheduled_jobs")
public class ScheduledJob {
    @Id
    private UUID checkId;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String pingUrl;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobMode mode = JobMode.SUCCESS;
    private Instant nextRunAt;
    private Instant lastRunAt;
    @Enumerated(EnumType.STRING)
    private JobMode lastOutcome;
    private Boolean heartbeatDelivered;
    @Column(nullable = false)
    private long successfulRuns;
    @Column(nullable = false)
    private long failedRuns;
    @Column(columnDefinition = "TEXT")
    private String lastError;
    @Column(columnDefinition = "TEXT")
    private String reportPath;

    public UUID getCheckId() { return checkId; }
    public void setCheckId(UUID checkId) { this.checkId = checkId; }
    public String getPingUrl() { return pingUrl; }
    public void setPingUrl(String pingUrl) { this.pingUrl = pingUrl; }
    public JobMode getMode() { return mode; }
    public void setMode(JobMode mode) { this.mode = mode; }
    public Instant getNextRunAt() { return nextRunAt; }
    public void setNextRunAt(Instant nextRunAt) { this.nextRunAt = nextRunAt; }
    public Instant getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(Instant lastRunAt) { this.lastRunAt = lastRunAt; }
    public JobMode getLastOutcome() { return lastOutcome; }
    public void setLastOutcome(JobMode lastOutcome) { this.lastOutcome = lastOutcome; }
    public Boolean getHeartbeatDelivered() { return heartbeatDelivered; }
    public void setHeartbeatDelivered(Boolean heartbeatDelivered) { this.heartbeatDelivered = heartbeatDelivered; }
    public long getSuccessfulRuns() { return successfulRuns; }
    public void setSuccessfulRuns(long successfulRuns) { this.successfulRuns = successfulRuns; }
    public long getFailedRuns() { return failedRuns; }
    public void setFailedRuns(long failedRuns) { this.failedRuns = failedRuns; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public String getReportPath() { return reportPath; }
    public void setReportPath(String reportPath) { this.reportPath = reportPath; }
}
