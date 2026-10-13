package com.smarika.heartbeat.ping;

import com.smarika.heartbeat.check.CheckStatus;

import java.time.Instant;
import java.util.UUID;

public record PingResponse(

        UUID checkId,

        String message,

        CheckStatus status,

        Instant receivedAt,

        Instant nextExpectedAt
) {
}