-- Separate migration: do not edit V1 after it has been applied.
ALTER TABLE checks ADD COLUMN failure_started_at TIMESTAMPTZ;
ALTER TABLE checks ADD COLUMN next_action_at TIMESTAMPTZ;
UPDATE checks SET next_action_at = next_expected_at;
CREATE INDEX idx_checks_due_action ON checks (next_action_at);
DROP INDEX IF EXISTS idx_checks_due;

-- Enforce at most one currently open outage per check, even across instances.
CREATE UNIQUE INDEX idx_outages_one_open_per_check
    ON outages (check_id) WHERE ended_at IS NULL;

-- Enforce a DOWN and at most one RECOVERY notification for each outage.
CREATE UNIQUE INDEX idx_alerts_outage_type
    ON alerts (outage_id, alert_type);
