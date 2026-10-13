package com.smarika.heartbeat.website;

import java.time.Duration;
import java.time.Instant;

public record WebsiteCheckResult(

        boolean successful,

        int statusCode,

        Instant checkedAt,

        Duration responseTime,

        String error
) {
}