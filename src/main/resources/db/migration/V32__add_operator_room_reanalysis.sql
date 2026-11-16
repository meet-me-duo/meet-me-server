-- Explicit operator commands preserve source inputs and prior analysis history.
ALTER TABLE operations_access_audits DROP CONSTRAINT operations_access_audits_route_check;
ALTER TABLE operations_access_audits ADD CONSTRAINT operations_access_audits_route_check
    CHECK (route IN ('ROOM_LIST', 'ROOM_DETAIL', 'INVOCATION_DETAIL', 'ROOM_REANALYSIS'));

CREATE TABLE operations_reanalysis_commands (
    room_id UUID NOT NULL REFERENCES meeting_rooms(id) ON DELETE CASCADE,
    request_id UUID NOT NULL,
    actor_user_id UUID NOT NULL,
    source_run_id UUID NOT NULL,
    created_run_id UUID NOT NULL UNIQUE,
    expected_state_version BIGINT NOT NULL CHECK (expected_state_version >= 0),
    accepted_state_version BIGINT NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL,
    analysis_contract_version VARCHAR(128) NOT NULL CHECK (length(analysis_contract_version) > 0),
    PRIMARY KEY (room_id, request_id),
    FOREIGN KEY (source_run_id, room_id) REFERENCES coordination_runs(id, room_id) ON DELETE CASCADE,
    FOREIGN KEY (created_run_id, room_id) REFERENCES coordination_runs(id, room_id) ON DELETE CASCADE,
    CHECK (source_run_id <> created_run_id),
    CHECK (accepted_state_version = expected_state_version + 1)
);

CREATE INDEX ix_operations_reanalysis_source ON operations_reanalysis_commands(room_id, source_run_id);
