package com.smarika.heartbeat.alert;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class AlertStateService {

    private final AlertRepository alertRepository;

    public AlertStateService(
            AlertRepository alertRepository
    ) {
        this.alertRepository = alertRepository;
    }

    @Transactional
    public void resetIfStale(UUID alertId, Instant cutoff) {
        Alert alert = alertRepository.findByIdForUpdate(alertId).orElse(null);
        if (alert == null || alert.getDeliveryStatus() != AlertDeliveryStatus.PROCESSING
                || alert.getProcessingStartedAt() == null
                || !alert.getProcessingStartedAt().isBefore(cutoff)) {
            return;
        }
        // In-flight attempts have a 5s HTTP timeout; recovery lease is 60s.
        alert.setDeliveryStatus(AlertDeliveryStatus.PENDING);
        alert.setProcessingStartedAt(null);
        alert.setNextAttemptAt(Instant.now());
    }

    @Transactional
    public boolean claim(UUID alertId) {

        Alert alert =
                alertRepository.findByIdForUpdate(alertId)
                        .orElse(null);

        if (alert == null) {
            return false;
        }

        if (alert.getDeliveryStatus()
                != AlertDeliveryStatus.PENDING) {

            return false;
        }

        Instant now = Instant.now();

        alert.setDeliveryStatus(
                AlertDeliveryStatus.PROCESSING
        );

        alert.setProcessingStartedAt(now);
        alert.setLastAttemptAt(now);

        alert.setAttemptCount(
                alert.getAttemptCount() + 1
        );

        return true;
    }

    @Transactional
    public void releaseClaim(UUID alertId) {
        Alert alert =
                alertRepository.findByIdForUpdate(alertId)
                        .orElseThrow();

        if (alert.getDeliveryStatus() != AlertDeliveryStatus.PROCESSING) {
            return;
        }

        alert.setDeliveryStatus(AlertDeliveryStatus.PENDING);
        alert.setProcessingStartedAt(null);
        alert.setLastAttemptAt(null);
        alert.setAttemptCount(Math.max(0, alert.getAttemptCount() - 1));
        alert.setNextAttemptAt(Instant.now());
    }

    @Transactional
    public void markDelivered(UUID alertId) {

        Alert alert =
                alertRepository.findByIdForUpdate(alertId)
                        .orElseThrow();

        alert.setDeliveryStatus(
                AlertDeliveryStatus.DELIVERED
        );

        alert.setDeliveredAt(Instant.now());
        alert.setProcessingStartedAt(null);
        alert.setNextAttemptAt(null);
    }

    @Transactional
    public void markFailed(
            UUID alertId,
            String error
    ) {

        Alert alert =
                alertRepository.findByIdForUpdate(alertId)
                        .orElseThrow();

        int attempts = alert.getAttemptCount();

        alert.setLastError(
                error == null
                        ? "Unknown error"
                        : error
        );

        alert.setProcessingStartedAt(null);

        if (attempts >= 6) {

            alert.setDeliveryStatus(
                    AlertDeliveryStatus.FAILED
            );

            alert.setNextAttemptAt(null);

        } else {

            alert.setDeliveryStatus(
                    AlertDeliveryStatus.PENDING
            );

            alert.setNextAttemptAt(
                    Instant.now().plusSeconds(
                            retryDelay(attempts)
                    )
            );
        }
    }

    private long retryDelay(int attempt) {

        return switch (attempt) {
            case 1 -> 10;
            case 2 -> 30;
            case 3 -> 60;
            case 4 -> 120;
            default -> 300;
        };
    }
}