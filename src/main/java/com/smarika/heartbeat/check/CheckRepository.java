package com.smarika.heartbeat.check;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CheckRepository
        extends JpaRepository<Check, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
                SELECT c
                FROM Check c
                WHERE c.pingTokenHash = :pingTokenHash
            """)
    Optional<Check> findByPingTokenHashForUpdate(
            @Param("pingTokenHash") String pingTokenHash
    );

    List<Check>
    findTop100ByStatusNotAndNextActionAtLessThanEqualOrderByNextActionAtAsc(
            CheckStatus status,
            Instant now
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
                SELECT c
                FROM Check c
                WHERE c.id = :id
            """)
    Optional<Check> findByIdForUpdate(
            @Param("id") UUID id
    );

    @Query("""
                SELECT c
                FROM Check c
                WHERE :status IS NULL OR c.status = :status
                ORDER BY c.createdAt DESC
            """)
    Page<Check> findByStatus(
            @Param("status") CheckStatus status,
            Pageable pageable
    );
}