package com.smarika.heartbeat.ping;

import com.smarika.heartbeat.alert.AlertService;
import com.smarika.heartbeat.check.Check;
import com.smarika.heartbeat.check.CheckRepository;
import com.smarika.heartbeat.check.CheckStatus;
import com.smarika.heartbeat.common.TokenService;
import com.smarika.heartbeat.history.CheckHistory;
import com.smarika.heartbeat.history.CheckHistoryRepository;
import com.smarika.heartbeat.history.HistoryEventType;
import com.smarika.heartbeat.outage.Outage;
import com.smarika.heartbeat.outage.OutageRepository;
import com.smarika.heartbeat.scheduler.ScheduleCalculator;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class PingService {

    private final CheckRepository checkRepository;

    private final TokenService tokenService;

    private final ScheduleCalculator scheduleCalculator;

    private final CheckHistoryRepository historyRepository;

    private final OutageRepository outageRepository;

    private final AlertService alertService;

    public PingService(
            CheckRepository checkRepository,
            TokenService tokenService,
            ScheduleCalculator scheduleCalculator,
            CheckHistoryRepository historyRepository,
            OutageRepository outageRepository,
            AlertService alertService
    ) {
        this.checkRepository = checkRepository;
        this.tokenService = tokenService;
        this.scheduleCalculator = scheduleCalculator;
        this.historyRepository = historyRepository;
        this.outageRepository = outageRepository;
        this.alertService = alertService;
    }


    @Transactional
    public PingResponse processPing( String rawToken) {

        String hash = tokenService.hashToken(rawToken);

        Check check = checkRepository
            .findByPingTokenHashForUpdate(hash)
            .orElseThrow(() ->
                new IllegalArgumentException(
                        "Invalid ping token"
                )
            );

        if (check.getStatus() == CheckStatus.PAUSED) {
            throw new IllegalStateException(
                "Check is paused"
            );
        }

        Instant now = Instant.now();

        CheckStatus oldStatus = check.getStatus();
        check.setLastSeenAt(now);

        check.setNextExpectedAt(
            scheduleCalculator.next(
                check.getScheduleType(),
                check.getScheduleExpression(),
                now
            )
        );

        check.setNextActionAt(check.getNextExpectedAt());
        check.setStatus(CheckStatus.UP);

        check.setUpdatedAt(now);

        checkRepository.save(check);

        saveHistory(
            check,
            HistoryEventType.PING,
            CheckStatus.UP,
            now,
            "{\"message\":\"Heartbeat received\"}"
        );


        if (oldStatus != CheckStatus.UP) {

            saveHistory(
                    check,
                    HistoryEventType.STATUS_CHANGED,
                    CheckStatus.UP,
                    now,
                    "{\"from\":\""
                            + oldStatus
                            + "\",\"to\":\"UP\"}"
            );
        }


        if (oldStatus == CheckStatus.DOWN) {

            Outage outage =
                    outageRepository
                            .findFirstByCheckIdAndEndedAtIsNull(
                                    check.getId()
                            )
                            .orElse(null);

            if (outage != null) {

                outage.setEndedAt(now);

                outageRepository.save(outage);

                alertService.createRecoveryAlert(
                        check,
                        outage,
                        now
                );
            }
        }


        return new PingResponse(
                check.getId(),
                "Heartbeat received",
                check.getStatus(),
                now,
                check.getNextExpectedAt()
        );
    }


    private void saveHistory(
            Check check,
            HistoryEventType eventType,
            CheckStatus status,
            Instant now,
            String details
    ) {

        CheckHistory history =
                new CheckHistory();

        history.setCheck(check);

        history.setEventType(eventType);

        history.setStatus(status);

        history.setOccurredAt(now);

        history.setDetails(details);

        historyRepository.save(history);
    }
}