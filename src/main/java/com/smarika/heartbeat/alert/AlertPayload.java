package com.smarika.heartbeat.alert;

import java.time.Instant;
import java.util.UUID;

public record AlertPayload(

        UUID alertId,

        UUID checkId,

        String checkName,

        AlertType alertType,

        String status,

        Instant occurredAt,

        Instant outageStartedAt,

        Instant outageEndedAt
) {

    public static AlertPayload down(
            Alert alert,
            Instant occurredAt
    ) {

        return new AlertPayload(
                alert.getId(),
                alert.getCheck().getId(),
                alert.getCheck().getName(),
                AlertType.DOWN,
                "DOWN",
                occurredAt,
                alert.getOutage().getStartedAt(),
                null
        );
    }


    public static AlertPayload recovery(
            Alert alert,
            Instant occurredAt
    ) {

        return new AlertPayload(
                alert.getId(),
                alert.getCheck().getId(),
                alert.getCheck().getName(),
                AlertType.RECOVERY,
                "UP",
                occurredAt,
                alert.getOutage().getStartedAt(),
                alert.getOutage().getEndedAt()
        );
    }
}