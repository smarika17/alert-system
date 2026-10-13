package com.smarika.heartbeat.check;

import java.time.Instant;
import java.util.UUID;

public record CreateCheckResponse(

        UUID id,

        String name,

        CheckType type,

        CheckStatus status,

        ScheduleType scheduleType,

        String scheduleExpression,

        long gracePeriodSeconds,

        String targetUrl,

        String pingUrl,

        String alertUrl,

        Instant nextExpectedAt,

        boolean runAutomatically
) {
}
