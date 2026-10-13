package com.smarika.heartbeat.monitoring;

import com.smarika.heartbeat.alert.AlertService;
import com.smarika.heartbeat.check.*;
import com.smarika.heartbeat.history.CheckHistory;
import com.smarika.heartbeat.history.CheckHistoryRepository;
import com.smarika.heartbeat.history.HistoryEventType;
import com.smarika.heartbeat.outage.Outage;
import com.smarika.heartbeat.outage.OutageRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class MonitoringService {

    private final CheckRepository checkRepository;

    private final CheckHistoryRepository historyRepository;

    private final OutageRepository outageRepository;

    private final AlertService alertService;

    public MonitoringService(CheckRepository checkRepository, CheckHistoryRepository historyRepository, OutageRepository outageRepository, AlertService alertService) {
        this.checkRepository = checkRepository;
        this.historyRepository = historyRepository;
        this.outageRepository = outageRepository;
        this.alertService = alertService;
    }


    @Transactional
    public void processHeartbeat(UUID checkId, Instant now) {

        Check check = checkRepository.findByIdForUpdate(checkId).orElse(null);

        if (check == null) {
            return;
        }


        if (check.getType() != CheckType.HEARTBEAT) {
            return;
        }


        if (check.getStatus() == CheckStatus.PAUSED) {
            return;
        }


        Instant expected = check.getNextExpectedAt();

        if (expected == null) {
            return;
        }


        if (!now.isAfter(expected)) {
            return;
        }


        Instant graceEnd = expected.plusSeconds(check.getGracePeriodSeconds());


        if (!now.isAfter(graceEnd)) {

            if (check.getStatus() != CheckStatus.LATE) {

                CheckStatus old = check.getStatus();

                check.setStatus(CheckStatus.LATE);
                // Do not make LATE heartbeats consume all 100 query slots.
                check.setNextActionAt(graceEnd);

                check.setUpdatedAt(now);

                checkRepository.save(check);

                saveStatusChange(check, old, CheckStatus.LATE, now);
            }

            return;
        }


        if (check.getStatus() == CheckStatus.DOWN) {
            return;
        }


        CheckStatus old = check.getStatus();

        check.setStatus(CheckStatus.DOWN);

        /*
         * Do not keep sweeping this
         * heartbeat indefinitely.
         */
        check.setNextExpectedAt(null);
        check.setNextActionAt(null);

        check.setUpdatedAt(now);

        checkRepository.save(check);


        saveStatusChange(check, old, CheckStatus.DOWN, now);


        Outage outage = outageRepository.findFirstByCheckIdAndEndedAtIsNull(check.getId()).orElse(null);


        if (outage == null) {

            outage = new Outage();

            outage.setId(UUID.randomUUID());

            outage.setCheck(check);

            outage.setStartedAt(graceEnd);

            outage.setCreatedAt(now);

            outage = outageRepository.save(outage);


            alertService.createDownAlert(check, outage, now);
        }
    }


    private void saveStatusChange(Check check, CheckStatus oldStatus, CheckStatus newStatus, Instant now) {

        CheckHistory history = new CheckHistory();

        history.setCheck(check);

        history.setEventType(HistoryEventType.STATUS_CHANGED);

        history.setStatus(newStatus);

        history.setOccurredAt(now);

        history.setDetails("{\"from\":\"" + oldStatus + "\",\"to\":\"" + newStatus + "\"}");

        historyRepository.save(history);
    }
}