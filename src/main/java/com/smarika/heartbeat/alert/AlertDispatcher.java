package com.smarika.heartbeat.alert;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;

@Service
public class AlertDispatcher {

    private final AlertStateService alertStateService;
    private final WebClient webClient;

    public AlertDispatcher(
            AlertStateService alertStateService,
            WebClient webClient
    ) {
        this.alertStateService = alertStateService;
        this.webClient = webClient;
    }

    public boolean claim(Alert alert) {

        return alertStateService.claim(
                alert.getId()
        );
    }

    public void releaseClaim(Alert alert) {
        alertStateService.releaseClaim(alert.getId());
    }

    public void send(Alert alert) {

        try {

            AlertPayload payload;

            if (alert.getAlertType() == AlertType.DOWN) {

                payload = AlertPayload.down(
                        alert,
                        Instant.now()
                );

            } else {

                payload = AlertPayload.recovery(
                        alert,
                        Instant.now()
                );
            }

            webClient
                    .post()
                    .uri(alert.getCheck().getAlertUrl())
                    .header("X-Alert-Id", alert.getId().toString())
                    .bodyValue(payload)
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(Duration.ofSeconds(5))
                    .block();

            alertStateService.markDelivered(
                    alert.getId()
            );

        } catch (Exception e) {

            alertStateService.markFailed(
                    alert.getId(),
                    e.getMessage()
            );
        }
    }
}