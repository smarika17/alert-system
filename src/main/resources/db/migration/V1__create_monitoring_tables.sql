CREATE TABLE checks
(
    id                   UUID PRIMARY KEY,

    name                 VARCHAR(255) NOT NULL,

    type                 VARCHAR(20)  NOT NULL,

    status               VARCHAR(20)  NOT NULL DEFAULT 'UP',

    schedule_type        VARCHAR(20)  NOT NULL,

    schedule_expression  VARCHAR(255) NOT NULL,

    grace_period_seconds BIGINT       NOT NULL,

    target_url           TEXT,

    ping_token_hash      VARCHAR(128) UNIQUE,

    alert_url            TEXT         NOT NULL,

    last_seen_at         TIMESTAMPTZ,

    next_expected_at     TIMESTAMPTZ,

    paused_at            TIMESTAMPTZ,

    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT checks_type_chk
        CHECK (type IN ('HEARTBEAT', 'WEBSITE')),

    CONSTRAINT checks_status_chk
        CHECK (status IN ('UP', 'LATE', 'DOWN', 'PAUSED')),

    CONSTRAINT checks_schedule_type_chk
        CHECK (schedule_type IN ('INTERVAL', 'CRON')),

    CONSTRAINT checks_grace_chk
        CHECK (grace_period_seconds >= 0),

    CONSTRAINT checks_type_configuration_chk
        CHECK (
            (
                type = 'HEARTBEAT'
                    AND ping_token_hash IS NOT NULL
                    AND target_url IS NULL
                )
                OR
            (
                type = 'WEBSITE'
                    AND target_url IS NOT NULL
                    AND ping_token_hash IS NULL
                )
            )
);


CREATE TABLE outages
(
    id         UUID PRIMARY KEY,

    check_id   UUID        NOT NULL,

    started_at TIMESTAMPTZ NOT NULL,

    ended_at   TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT outages_check_fk
        FOREIGN KEY (check_id)
            REFERENCES checks (id)
            ON DELETE CASCADE
);


CREATE TABLE check_history
(
    id          BIGSERIAL PRIMARY KEY,

    check_id    UUID        NOT NULL,

    event_type  VARCHAR(30) NOT NULL,

    status      VARCHAR(20),

    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    details     JSONB,

    CONSTRAINT history_check_fk
        FOREIGN KEY (check_id)
            REFERENCES checks (id)
            ON DELETE CASCADE
);


CREATE TABLE alerts
(
    id                    UUID PRIMARY KEY,

    check_id              UUID        NOT NULL,

    outage_id             UUID,

    alert_type            VARCHAR(20) NOT NULL,

    delivery_status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',

    attempt_count         INTEGER     NOT NULL DEFAULT 0,

    next_attempt_at       TIMESTAMPTZ,

    last_attempt_at       TIMESTAMPTZ,

    delivered_at          TIMESTAMPTZ,

    last_error            TEXT,

    processing_started_at TIMESTAMPTZ,

    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT alerts_check_fk
        FOREIGN KEY (check_id)
            REFERENCES checks (id)
            ON DELETE CASCADE,

    CONSTRAINT alerts_outage_fk
        FOREIGN KEY (outage_id)
            REFERENCES outages (id)
            ON DELETE CASCADE,

    CONSTRAINT alerts_type_chk
        CHECK (alert_type IN ('DOWN', 'RECOVERY')),

    CONSTRAINT alerts_delivery_status_chk
        CHECK (
            delivery_status IN
            ('PENDING', 'PROCESSING', 'DELIVERED', 'FAILED')
            )
);


CREATE INDEX idx_checks_due
    ON checks (next_expected_at) WHERE status <> 'PAUSED';


CREATE INDEX idx_checks_type
    ON checks (type);


CREATE INDEX idx_check_history_check_id
    ON check_history (check_id, occurred_at DESC);


CREATE INDEX idx_outages_check_id
    ON outages (check_id);


CREATE INDEX idx_alerts_pending
    ON alerts (next_attempt_at) WHERE delivery_status = 'PENDING';


CREATE INDEX idx_alerts_check_id
    ON alerts (check_id, created_at DESC);