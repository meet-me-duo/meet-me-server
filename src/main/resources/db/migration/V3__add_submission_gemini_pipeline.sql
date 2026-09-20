ALTER TABLE meeting_rooms
    ADD CONSTRAINT ck_room_expected_participants_max
        CHECK (expected_participants IS NULL OR expected_participants <= 50);

CREATE TABLE structured_submission_results (
    batch_id UUID NOT NULL REFERENCES submission_batches(id),
    submission_version_id UUID NOT NULL REFERENCES submission_versions(id),
    conditions JSONB NOT NULL DEFAULT '[]'::jsonb,
    rejection_code VARCHAR(64),
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (batch_id, submission_version_id),
    CONSTRAINT ck_structured_result_shape CHECK (
        jsonb_typeof(conditions) = 'array'
        AND (jsonb_array_length(conditions) > 0 OR rejection_code IS NOT NULL)
        AND jsonb_array_length(conditions) <= 32
    )
);

ALTER TABLE coordination_attempts
    ADD COLUMN input_tokens BIGINT,
    ADD COLUMN output_tokens BIGINT,
    ADD COLUMN response_bytes INTEGER,
    ADD COLUMN estimated_cost_usd NUMERIC(12, 8),
    ADD COLUMN failure_kind VARCHAR(32),
    ADD CONSTRAINT ck_attempt_usage CHECK (
        (input_tokens IS NULL OR input_tokens >= 0)
        AND (output_tokens IS NULL OR output_tokens >= 0)
        AND (response_bytes IS NULL OR response_bytes >= 0)
        AND (estimated_cost_usd IS NULL OR estimated_cost_usd >= 0)
    );

CREATE UNIQUE INDEX uq_outbox_pending_business_event
    ON outbox_events(aggregate_id, event_type)
    WHERE status = 'PENDING';

CREATE INDEX ix_submission_heads_room_latest
    ON submission_heads(room_id, latest_version_id);

CREATE INDEX ix_rooms_due_deadline
    ON meeting_rooms(submission_deadline)
    WHERE collection_status = 'COLLECTING' AND submission_deadline IS NOT NULL;
