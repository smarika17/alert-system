package com.smarika.heartbeat.check;

import com.smarika.heartbeat.history.CheckHistory;
import com.smarika.heartbeat.history.CheckHistoryRepository;
import com.smarika.heartbeat.history.HistoryEventType;
import com.smarika.heartbeat.scheduler.ScheduleCalculator;
import com.smarika.heartbeat.outage.Outage;
import com.smarika.heartbeat.outage.OutageRepository;
import com.smarika.heartbeat.job.JobStateService;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.UUID;

@Service
public class CheckLifecycleService {

    private final CheckRepository checkRepository;

    private final CheckHistoryRepository historyRepository;

    private final ScheduleCalculator scheduleCalculator;
    private final OutageRepository outageRepository;
    private final JobStateService jobs;


    public CheckLifecycleService(
            CheckRepository checkRepository,
            CheckHistoryRepository historyRepository,
            ScheduleCalculator scheduleCalculator,
            OutageRepository outageRepository,
            JobStateService jobs
    ) {

        this.checkRepository =
                checkRepository;

        this.historyRepository =
                historyRepository;

        this.scheduleCalculator =
                scheduleCalculator;
        this.outageRepository = outageRepository;
        this.jobs = jobs;
    }


    @Transactional
    public void pause(UUID id) {

        Check check =
                checkRepository
                        .findByIdForUpdate(id)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Check not found"
                                )
                        );


        if (
                check.getStatus()
                        == CheckStatus.PAUSED
        ) {
            return;
        }


        Instant now =
                Instant.now();

        CheckStatus old =
                check.getStatus();

        check.setStatus(
                CheckStatus.PAUSED
        );

        // End an existing outage without a recovery alert: pause is not recovery.
        // This prevents a stale open outage from suppressing a future DOWN alert.
        Outage open = outageRepository.findFirstByCheckIdAndEndedAtIsNull(id).orElse(null);
        if (open != null) {
            open.setEndedAt(now);
            outageRepository.save(open);
        }
        check.setFailureStartedAt(null);
        check.setPausedAt(now);

        check.setNextExpectedAt(null);
        check.setNextActionAt(null);
        jobs.pause(id);

        check.setUpdatedAt(now);

        checkRepository.save(check);


        saveHistory(
                check,
                old,
                CheckStatus.PAUSED,
                now
        );
    }


    @Transactional
    public void resume(
            UUID id
    ) {

        Check check =
                checkRepository
                        .findByIdForUpdate(id)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Check not found"
                                )
                        );


        if (
                check.getStatus()
                        != CheckStatus.PAUSED
        ) {
            return;
        }


        Instant now =
                Instant.now();


        check.setStatus(
                CheckStatus.UP
        );

        check.setPausedAt(null);
        check.setFailureStartedAt(null);

        check.setNextExpectedAt(
                scheduleCalculator.next(
                        check.getScheduleType(),
                        check.getScheduleExpression(),
                        now
                )
        );

        check.setNextActionAt(check.getNextExpectedAt());
        jobs.resume(check);
        check.setUpdatedAt(now);

        checkRepository.save(check);


        saveHistory(
                check,
                CheckStatus.PAUSED,
                CheckStatus.UP,
                now
        );
    }


    private void saveHistory(
            Check check,
            CheckStatus oldStatus,
            CheckStatus newStatus,
            Instant now
    ) {

        CheckHistory history =
                new CheckHistory();

        history.setCheck(check);

        history.setEventType(
                HistoryEventType.STATUS_CHANGED
        );

        history.setStatus(newStatus);

        history.setOccurredAt(now);

        history.setDetails(
                "{\"from\":\""
                        + oldStatus
                        + "\",\"to\":\""
                        + newStatus
                        + "\"}"
        );

        historyRepository.save(history);
    }
}
