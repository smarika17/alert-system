package com.smarika.heartbeat.history;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CheckHistoryRepository
        extends JpaRepository<CheckHistory, Long> {

    Page<CheckHistory>
    findByCheckIdOrderByOccurredAtDesc(
            UUID checkId,
            Pageable pageable
    );
}