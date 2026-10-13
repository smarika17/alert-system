package com.smarika.heartbeat.history;

import com.smarika.heartbeat.check.Check;
import com.smarika.heartbeat.check.CheckStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "check_history")
public class CheckHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "check_id")
    private Check check;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HistoryEventType eventType;

    @Enumerated(EnumType.STRING)
    private CheckStatus status;

    @Column(nullable = false)
    private Instant occurredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String details;

    public Long getId() {
        return id;
    }

    public Check getCheck() {
        return check;
    }

    public void setCheck(Check check) {
        this.check = check;
    }

    public HistoryEventType getEventType() {
        return eventType;
    }

    public void setEventType(HistoryEventType eventType) {
        this.eventType = eventType;
    }

    public CheckStatus getStatus() {
        return status;
    }

    public void setStatus(CheckStatus status) {
        this.status = status;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }
}