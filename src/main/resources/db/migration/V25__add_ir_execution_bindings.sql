-- No legacy or V24 envelope backfill: a historical result is not proof of a preselected contract/policy.
CREATE TABLE schedule_ir_execution_bindings (
    run_id UUID PRIMARY KEY,
    batch_id UUID NOT NULL,
    room_id UUID NOT NULL,
    response_contract_version VARCHAR(64) NOT NULL,
    semantic_version VARCHAR(64) NOT NULL,
    policy_version VARCHAR(64) NOT NULL,
    meeting_mode VARCHAR(16) NOT NULL,
    format_version INTEGER NOT NULL,
    selection_fingerprint VARCHAR(64) NOT NULL,
    selection JSONB NOT NULL,
    prepared_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_ir_execution_binding_run FOREIGN KEY (run_id,batch_id,room_id)
        REFERENCES coordination_runs(id,batch_id,room_id) ON DELETE CASCADE,
    CONSTRAINT ck_ir_execution_binding_shape CHECK (
        length(response_contract_version) > 0
        AND length(semantic_version) > 0
        AND length(policy_version) > 0
        AND meeting_mode IN ('REMOTE','IN_PERSON','EITHER')
        AND format_version > 0
        AND selection_fingerprint ~ '^[a-f0-9]{64}$'
        AND jsonb_typeof(selection) = 'object'
    )
);
