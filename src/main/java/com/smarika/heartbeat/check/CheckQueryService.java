package com.smarika.heartbeat.check;

import com.smarika.heartbeat.history.CheckHistoryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class CheckQueryService {

    private final CheckRepository checkRepository;

    private final CheckHistoryRepository historyRepository;

    public CheckQueryService(
            CheckRepository checkRepository,
            CheckHistoryRepository historyRepository
    ) {
        this.checkRepository = checkRepository;
        this.historyRepository = historyRepository;
    }


    public Page<CheckSummaryResponse> list(
            CheckStatus status,
            Pageable pageable
    ) {

        return checkRepository
                .findByStatus(status, pageable)
                .map(this::summary);
    }


    public CheckDetailResponse get(
            UUID id
    ) {

        Check check =
                checkRepository.findById(id)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Check not found"
                                )
                        );

        return detail(check);
    }


    public Page<HistoryResponse> history(
            UUID id,
            Pageable pageable
    ) {

        if (!checkRepository.existsById(id)) {

            throw new IllegalArgumentException(
                    "Check not found"
            );
        }

        return historyRepository
                .findByCheckIdOrderByOccurredAtDesc(
                        id,
                        pageable
                )
                .map(history ->
                        new HistoryResponse(
                                history.getId(),
                                history.getEventType(),
                                history.getStatus(),
                                history.getOccurredAt(),
                                history.getDetails()
                        )
                );
    }


    private CheckSummaryResponse summary(
            Check check
    ) {

        return new CheckSummaryResponse(
                check.getId(),
                check.getName(),
                check.getType(),
                check.getStatus(),
                check.getScheduleType(),
                check.getScheduleExpression(),
                check.getGracePeriodSeconds(),
                check.getTargetUrl(),
                check.getLastSeenAt(),
                check.getNextExpectedAt(),
                check.getPausedAt(),
                check.getCreatedAt()
        );
    }


    private CheckDetailResponse detail(
            Check check
    ) {

        return new CheckDetailResponse(
                check.getId(),
                check.getName(),
                check.getType(),
                check.getStatus(),
                check.getScheduleType(),
                check.getScheduleExpression(),
                check.getGracePeriodSeconds(),
                check.getTargetUrl(),
                check.getAlertUrl(),
                check.getLastSeenAt(),
                check.getNextExpectedAt(),
                check.getPausedAt(),
                check.getCreatedAt(),
                check.getUpdatedAt()
        );
    }
}