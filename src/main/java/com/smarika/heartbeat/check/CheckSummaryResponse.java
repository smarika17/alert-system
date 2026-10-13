package com.smarika.heartbeat.check;

import java.time.Instant;
import java.util.UUID;

public record CheckSummaryResponse(

        UUID id,

        String name,

        CheckType type,

        CheckStatus status,

        ScheduleType scheduleType,

        String scheduleExpression,

        long gracePeriodSeconds,

        String targetUrl,

        Instant lastSeenAt,

        Instant nextExpectedAt,

        Instant pausedAt,

        Instant createdAt
) {
}