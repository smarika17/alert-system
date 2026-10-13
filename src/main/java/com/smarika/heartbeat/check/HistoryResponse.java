package com.smarika.heartbeat.check;

import com.smarika.heartbeat.history.HistoryEventType;

import java.time.Instant;

public record HistoryResponse(

        Long id,

        HistoryEventType eventType,

        CheckStatus status,

        Instant occurredAt,

        String details
) {
}