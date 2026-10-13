package com.smarika.heartbeat.alert;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertRepository alertRepository;

    public AlertController(
            AlertRepository alertRepository
    ) {
        this.alertRepository =
                alertRepository;
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