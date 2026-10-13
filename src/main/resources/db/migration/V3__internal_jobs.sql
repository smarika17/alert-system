ALTER TABLE checks ADD COLUMN run_automatically BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE checks ADD CONSTRAINT checks_internal_job_type_chk
    CHECK (NOT run_automatically OR type = 'HEARTBEAT');
CREATE TABLE scheduled_jobs
(
    check_id UUID PRIMARY KEY REFERENCES checks (id) ON DELETE CASCADE,
    ping_url TEXT NOT NULL,
    mode VARCHAR(20) NOT NULL DEFAULT 'SUCCESS',
    next_run_at TIMESTAMPTZ,
    last_run_at TIMESTAMPTZ,
    last_outcome VARCHAR(20),
    heartbeat_delivered BOOLEAN,
    successful_runs BIGINT NOT NULL DEFAULT 0,
    failed_runs BIGINT NOT NULL DEFAULT 0,
    last_error TEXT,
    report_path TEXT,
    CONSTRAINT jobs_mode_chk CHECK (mode IN ('SUCCESS', 'FAILURE')),
    CONSTRAINT jobs_outcome_chk CHECK (last_outcome IN ('SUCCESS', 'FAILURE'))
);
CREATE INDEX idx_jobs_due ON scheduled_jobs (next_run_at);
