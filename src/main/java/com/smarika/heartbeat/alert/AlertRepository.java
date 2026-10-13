package com.smarika.heartbeat.alert;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AlertRepository
        extends JpaRepository<Alert, UUID> {

    @Query("""
               SELECT a
                               FROM Alert a
                               JOIN FETCH a.check
                               LEFT JOIN FETCH a.outage
                               WHERE a.deliveryStatus =
                                     com.smarika.heartbeat.alert.AlertDeliveryStatus.PENDING
                               AND a.nextAttemptAt <= :now
                               ORDER BY a.nextAttemptAt ASC
            """)
    List<Alert> findDue(
            @Param("now") Instant now,
            Pageable pageable
    );


    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
                SELECT a
                FROM Alert a
                WHERE a.id = :id
            """)
    java.util.Optional<Alert> findByIdForUpdate(
            @Param("id") UUID id
    );


    @Query("""
                SELECT a
                FROM Alert a
                WHERE a.deliveryStatus = 'PROCESSING'
                AND a.processingStartedAt < :cutoff
            """)
    List<Alert> findStaleProcessing(
            @Param("cutoff") Instant cutoff
    );

    @EntityGraph(attributePaths = "check")
    Page<Alert> findAllByOrderByCreatedAtDesc(
            Pageable pageable
    );

}