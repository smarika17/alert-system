package com.smarika.heartbeat.alert;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {
    private static final Logger log = LoggerFactory.getLogger(AlertController.class);

    private final AlertRepository alertRepository;

    public AlertController(
            AlertRepository alertRepository
    ) {
        this.alertRepository =
                alertRepository;
    }

    @PostMapping("/receive")
    public void receive(@RequestBody AlertPayload payload) {
        Alert alert = alertRepository.findById(payload.alertId())
                .orElseThrow(() -> new IllegalArgumentException("Alert not found"));
        if (!alert.getCheck().getId().equals(payload.checkId()) || alert.getAlertType() != payload.alertType()) {
            throw new IllegalArgumentException("Alert payload does not match the stored alert");
        }
        log.info("Received {} alert for check {}", payload.alertType(), payload.checkId());
    }


    @GetMapping
    public Page<AlertResponse> getAlerts(
            Pageable pageable
    ) {

        return alertRepository
                .findAllByOrderByCreatedAtDesc(pageable)
                .map(alert ->
                        new AlertResponse(
                                alert.getId(),
                                alert.getCheck().getId(),
                                alert.getCheck().getName(),
                                alert.getAlertType(),
                                alert.getDeliveryStatus(),
                                alert.getAttemptCount(),
                                alert.getLastError(),
                                alert.getCreatedAt(),
                                alert.getDeliveredAt()
                        )
                );
    }
}
