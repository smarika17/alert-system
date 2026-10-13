package com.smarika.heartbeat.outage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OutageRepository
        extends JpaRepository<Outage, UUID> {

    Optional<Outage>
    findFirstByCheckIdAndEndedAtIsNull(
            UUID checkId
    );
}