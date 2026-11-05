ALTER TABLE coordination_runs
    ADD CONSTRAINT uq_schedule_ir_run_batch_room UNIQUE (id, batch_id, room_id);

CREATE TABLE schedule_ir_envelopes (
    run_id UUID PRIMARY KEY,
    batch_id UUID NOT NULL,
    room_id UUID NOT NULL,
    semantic_version VARCHAR(64) NOT NULL,
    format_version INTEGER NOT NULL DEFAULT 1,
    context_fingerprint VARCHAR(64) NOT NULL,
    envelope JSONB NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_schedule_ir_run_batch UNIQUE (run_id, batch_id),
    CONSTRAINT fk_schedule_ir_run FOREIGN KEY (run_id, batch_id, room_id)
        REFERENCES coordination_runs(id, batch_id, room_id) ON DELETE CASCADE,
    CONSTRAINT ck_schedule_ir_envelope_shape CHECK (
        jsonb_typeof(envelope) = 'object'
        AND length(semantic_version) > 0
        AND format_version > 0
        AND context_fingerprint ~ '^[a-f0-9]{64}$'
    )
);

CREATE TABLE schedule_ir_inputs (
    run_id UUID NOT NULL,
    batch_id UUID NOT NULL,
    submission_version_id UUID NOT NULL,
    PRIMARY KEY (run_id, submission_version_id),
    CONSTRAINT fk_schedule_ir_input_envelope FOREIGN KEY (run_id, batch_id)
        REFERENCES schedule_ir_envelopes(run_id, batch_id) ON DELETE CASCADE,
    CONSTRAINT fk_schedule_ir_frozen_input FOREIGN KEY (batch_id, submission_version_id)
        REFERENCES submission_batch_items(batch_id, submission_version_id)
);
