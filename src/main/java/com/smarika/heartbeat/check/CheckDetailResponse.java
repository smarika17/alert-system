package com.smarika.heartbeat.check;

import java.time.Instant;
import java.util.UUID;

public record CheckDetailResponse(

        UUID id,

        String name,

        CheckType type,

        CheckStatus status,

        ScheduleType scheduleType,

        String scheduleExpression,

        long gracePeriodSeconds,

        String targetUrl,

        String alertUrl,

        Instant lastSeenAt,

        Instant nextExpectedAt,

        Instant pausedAt,

        Instant createdAt,

        Instant updatedAt,

        boolean runAutomatically
) {
}
