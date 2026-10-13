package com.smarika.heartbeat.alert;

import com.smarika.heartbeat.check.Check;
import com.smarika.heartbeat.outage.Outage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class AlertService {

    private final AlertRepository alertRepository;

    public AlertService(
            AlertRepository alertRepository
    ) {
        this.alertRepository = alertRepository;
    }


    @Transactional
    public Alert createDownAlert(
            Check check,
            Outage outage,
            Instant now
    ) {

        Alert alert = new Alert();

        alert.setId(UUID.randomUUID());

        alert.setCheck(check);

        alert.setOutage(outage);

        alert.setAlertType(AlertType.DOWN);

        alert.setDeliveryStatus(
                AlertDeliveryStatus.PENDING
        );

        alert.setAttemptCount(0);

        alert.setNextAttemptAt(now);

        alert.setCreatedAt(now);

        return alertRepository.save(alert);
    }


    @Transactional
    public Alert createRecoveryAlert(
            Check check,
            Outage outage,
            Instant now
    ) {

        Alert alert = new Alert();

        alert.setId(UUID.randomUUID());

        alert.setCheck(check);

        alert.setOutage(outage);

        alert.setAlertType(AlertType.RECOVERY);

        alert.setDeliveryStatus(
                AlertDeliveryStatus.PENDING
        );

        alert.setAttemptCount(0);

        alert.setNextAttemptAt(now);

        alert.setCreatedAt(now);

        return alertRepository.save(alert);
    }
}