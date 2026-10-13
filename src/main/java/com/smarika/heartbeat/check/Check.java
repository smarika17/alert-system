package com.smarika.heartbeat.check;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "checks")
public class Check {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CheckType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CheckStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScheduleType scheduleType;

    @Column(nullable = false)
    private String scheduleExpression;

    @Column(nullable = false)
    private long gracePeriodSeconds;

    @Column(columnDefinition = "TEXT")
    private String targetUrl;

    @Column(length = 128, unique = true)
    private String pingTokenHash;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String alertUrl;

    private Instant lastSeenAt;

    private Instant nextExpectedAt;

    // Internal next action; distinct from the externally visible expected time.
    private Instant nextActionAt;

    private Instant pausedAt;

    // Scheduled time of the first failed website probe in the current episode.
    private Instant failureStartedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public CheckType getType() {
        return type;
    }

    public void setType(CheckType type) {
        this.type = type;
    }

    public CheckStatus getStatus() {
        return status;
    }

    public void setStatus(CheckStatus status) {
        this.status = status;
    }

    public ScheduleType getScheduleType() {
        return scheduleType;
    }

    public void setScheduleType(ScheduleType scheduleType) {
        this.scheduleType = scheduleType;
    }

    public String getScheduleExpression() {
        return scheduleExpression;
    }

    public void setScheduleExpression(String scheduleExpression) {
        this.scheduleExpression = scheduleExpression;
    }

    public long getGracePeriodSeconds() {
        return gracePeriodSeconds;
    }

    public void setGracePeriodSeconds(long gracePeriodSeconds) {
        this.gracePeriodSeconds = gracePeriodSeconds;
    }

    public String getTargetUrl() {
        return targetUrl;
    }

    public void setTargetUrl(String targetUrl) {
        this.targetUrl = targetUrl;
    }

    public String getPingTokenHash() {
        return pingTokenHash;
    }

    public void setPingTokenHash(String pingTokenHash) {
        this.pingTokenHash = pingTokenHash;
    }

    public String getAlertUrl() {
        return alertUrl;
    }

    public void setAlertUrl(String alertUrl) {
        this.alertUrl = alertUrl;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public Instant getNextExpectedAt() {
        return nextExpectedAt;
    }

    public void setNextExpectedAt(Instant nextExpectedAt) {
        this.nextExpectedAt = nextExpectedAt;
    }

    public Instant getNextActionAt() {
        return nextActionAt;
    }

    public void setNextActionAt(Instant nextActionAt) {
        this.nextActionAt = nextActionAt;
    }

    public Instant getPausedAt() {
        return pausedAt;
    }

    public void setPausedAt(Instant pausedAt) {
        this.pausedAt = pausedAt;
    }

    public Instant getFailureStartedAt() {
        return failureStartedAt;
    }

    public void setFailureStartedAt(Instant failureStartedAt) {
        this.failureStartedAt = failureStartedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}