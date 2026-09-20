ALTER TABLE outbox_events
    DROP CONSTRAINT ck_outbox_status,
    DROP CONSTRAINT ck_outbox_publication,
    ADD COLUMN processed_at TIMESTAMPTZ,
    ADD COLUMN processing_lease_until TIMESTAMPTZ,
    ADD COLUMN delivery_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_failure_kind VARCHAR(128),
    ADD COLUMN dead_lettered_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_outbox_status
        CHECK (status IN ('PENDING', 'PUBLISHED', 'PROCESSED', 'DEAD_LETTERED')),
    ADD CONSTRAINT ck_outbox_delivery_count CHECK (delivery_count >= 0),
    ADD CONSTRAINT ck_outbox_processing_state CHECK (
        (status = 'PENDING'
            AND published_at IS NULL
            AND processed_at IS NULL
            AND processing_lease_until IS NULL
            AND dead_lettered_at IS NULL)
        OR (status = 'PUBLISHED'
            AND published_at IS NOT NULL
            AND processed_at IS NULL
            AND dead_lettered_at IS NULL)
        OR (status = 'PROCESSED'
            AND published_at IS NOT NULL
            AND processed_at IS NOT NULL
            AND processing_lease_until IS NULL
            AND dead_lettered_at IS NULL)
        OR (status = 'DEAD_LETTERED'
            AND published_at IS NOT NULL
            AND processed_at IS NULL
            AND processing_lease_until IS NULL
            AND dead_lettered_at IS NOT NULL)
    );

CREATE INDEX ix_outbox_recoverable
    ON outbox_events(published_at)
    WHERE status = 'PUBLISHED';

CREATE TABLE coordination_worker_failures (
    stream_record_id VARCHAR(128) PRIMARY KEY,
    event_id UUID,
    submission_batch_id UUID,
    failure_kind VARCHAR(128) NOT NULL,
    delivery_count INTEGER NOT NULL,
    failed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_worker_failure_kind CHECK (length(trim(failure_kind)) > 0),
    CONSTRAINT ck_worker_failure_delivery_count CHECK (delivery_count >= 5)
);

CREATE INDEX ix_worker_failures_event ON coordination_worker_failures(event_id);
CREATE INDEX ix_worker_failures_batch ON coordination_worker_failures(submission_batch_id);
