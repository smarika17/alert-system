package com.smarika.heartbeat.alert;

import java.time.Instant;
import java.util.UUID;

public record AlertResponse(

        UUID id,

        UUID checkId,

        String checkName,

        AlertType alertType,

        AlertDeliveryStatus deliveryStatus,

        int attemptCount,

        String lastError,

        Instant createdAt,

        Instant deliveredAt
) {
}