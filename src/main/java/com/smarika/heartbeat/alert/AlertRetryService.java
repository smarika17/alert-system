package com.smarika.heartbeat.alert;

import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class AlertRetryService {

    private final AlertRepository alertRepository;

    private final AlertDispatcher dispatcher;

    private final ThreadPoolTaskExecutor alertExecutor;

    private final AlertStateService alertStateService;

    public AlertRetryService(
            AlertRepository alertRepository,
            AlertDispatcher dispatcher,
            AlertStateService alertStateService,
            @Qualifier("alertExecutor") ThreadPoolTaskExecutor alertExecutor
    ) {
        this.alertRepository = alertRepository;
        this.dispatcher = dispatcher;
        this.alertStateService = alertStateService;
        this.alertExecutor = alertExecutor;
    }


    @Scheduled(
            fixedDelayString = "${alerts.retry-delay-ms:2000}",
            initialDelayString = "${alerts.retry-initial-delay-ms:2000}"
    )
    public void processAlerts() {

        resetStaleProcessing();

        List<Alert> alerts =
                alertRepository.findDue(
                        Instant.now(),
                        PageRequest.of(0, 100)
                );

        for (Alert alert : alerts) {

            if (dispatcher.claim(alert)) {
                try {
                    alertExecutor.execute(() -> dispatcher.send(alert));
                } catch (TaskRejectedException e) {
                    dispatcher.releaseClaim(alert);
                    break;
                }
            }
        }
    }


    private void resetStaleProcessing() {

        Instant cutoff =
                Instant.now().minusSeconds(60);

        List<Alert> stale =
                alertRepository.findStaleProcessing(
                        cutoff
                );

        for (Alert alert : stale) {
            // Re-check under row lock: an earlier read may now be stale.
            alertStateService.resetIfStale(alert.getId(), cutoff);
        }
    }
}
