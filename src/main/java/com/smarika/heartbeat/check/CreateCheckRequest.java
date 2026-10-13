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

        @NotBlank
        String alertUrl
) {
}