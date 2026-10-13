package com.smarika.heartbeat.check;

import jakarta.validation.constraints.*;

public record CreateCheckRequest(

        @NotBlank
        String name,

        @NotNull
        CheckType type,

        @NotNull
        ScheduleType scheduleType,

        @NotBlank
        String scheduleExpression,

        @Min(0)
        long gracePeriodSeconds,

        String targetUrl,

        String alertUrl,

        Boolean runAutomatically
) {
    public CreateCheckRequest {
        runAutomatically = Boolean.TRUE.equals(runAutomatically);
    }

    public CreateCheckRequest(String name, CheckType type, ScheduleType scheduleType,
                              String scheduleExpression, long gracePeriodSeconds,
                              String targetUrl, String alertUrl) {
        this(name, type, scheduleType, scheduleExpression, gracePeriodSeconds, targetUrl, alertUrl, false);
    }
}
