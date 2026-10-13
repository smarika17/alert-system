package com.smarika.heartbeat.job;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScheduledJobRepository extends JpaRepository<ScheduledJob, UUID> {
    @Query("""
            SELECT j.checkId FROM ScheduledJob j, Check c
            WHERE j.checkId = c.id AND c.status <> com.smarika.heartbeat.check.CheckStatus.PAUSED
              AND j.nextRunAt <= :now
            ORDER BY j.nextRunAt
            """)
    List<UUID> findDue(Instant now, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM ScheduledJob j WHERE j.checkId = :id")
    Optional<ScheduledJob> findByIdForUpdate(UUID id);
}
