-- Existing rooms and their V13 history retain the legacy protocol.
ALTER TABLE meeting_rooms ADD COLUMN submission_protocol VARCHAR(24) NOT NULL DEFAULT 'LEGACY'
    CHECK (submission_protocol IN ('LEGACY', 'PRE_SUBMIT_V1'));
ALTER TABLE meeting_rooms ALTER COLUMN submission_protocol SET DEFAULT 'PRE_SUBMIT_V1';
ALTER TABLE guest_reaccess_grants ADD COLUMN expected_candidate_revision INTEGER CHECK (expected_candidate_revision >= 0),
    ADD COLUMN grant_generation UUID;

CREATE TABLE precheck_candidates (
    id UUID PRIMARY KEY,
    room_id UUID NOT NULL REFERENCES meeting_rooms(id) ON DELETE CASCADE,
    participant_id UUID NOT NULL,
    revision INTEGER NOT NULL CHECK (revision > 0),
    raw_text TEXT NOT NULL CHECK (length(raw_text) BETWEEN 1 AND 500),
    locale VARCHAR(35) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    interpretation_reference_at TIMESTAMPTZ NOT NULL,
    owner_binding TEXT NOT NULL,
    source_candidate_id UUID REFERENCES precheck_candidates(id) ON DELETE CASCADE,
    source_question_id UUID,
    selected_choice_id VARCHAR(32),
    accepted_version_id UUID UNIQUE REFERENCES submission_versions(id) ON DELETE SET NULL,
    UNIQUE (participant_id, revision),
    FOREIGN KEY (room_id, participant_id) REFERENCES participants(room_id, id) ON DELETE CASCADE
);
CREATE TABLE precheck_checks (
    id UUID PRIMARY KEY,
    candidate_id UUID NOT NULL UNIQUE REFERENCES precheck_candidates(id) ON DELETE CASCADE,
    status VARCHAR(32) NOT NULL CHECK (status IN ('QUEUED','CHECKING','NEEDS_CHOICE','NEEDS_EDIT','CLEAR','FAILED','FAILED_QUEUE',
        'TIMED_OUT','LIMIT_REACHED','CANCELLED','SUPERSEDED','CLOSED_BEFORE_COMPLETION')),
    version BIGINT NOT NULL CHECK (version > 0),
    generation INTEGER NOT NULL CHECK (generation > 0),
    retry_count INTEGER NOT NULL CHECK (retry_count BETWEEN 0 AND 1),
    deadline_at TIMESTAMPTZ NOT NULL,
    question JSONB CHECK (question IS NULL OR jsonb_typeof(question) = 'object'),
    owner_token UUID,
    claimed_at TIMESTAMPTZ,
    failure_code VARCHAR(64),
    queue_sequence BIGSERIAL NOT NULL
);
CREATE INDEX ix_precheck_queue ON precheck_checks(queue_sequence) WHERE status = 'QUEUED';
CREATE INDEX ix_precheck_current ON precheck_candidates(participant_id, revision DESC);
CREATE TABLE precheck_executions (
    check_id UUID NOT NULL REFERENCES precheck_checks(id) ON DELETE CASCADE,
    generation INTEGER NOT NULL CHECK (generation > 0),
    queued_at TIMESTAMPTZ NOT NULL,
    deadline_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    owner_token UUID,
    provider VARCHAR(16),
    model VARCHAR(64),
    input_tokens BIGINT CHECK (input_tokens >= 0),
    output_tokens BIGINT CHECK (output_tokens >= 0),
    response_bytes INTEGER CHECK (response_bytes >= 0),
    failure_code VARCHAR(64),
    purpose VARCHAR(32) NOT NULL DEFAULT 'PRE_SUBMIT_CHECK' CHECK (purpose = 'PRE_SUBMIT_CHECK'),
    prompt_version VARCHAR(32) NOT NULL DEFAULT 'precheck-v1',
    schema_version VARCHAR(32) NOT NULL DEFAULT 'precheck-v1',
    PRIMARY KEY(check_id, generation),
    CHECK (deadline_at = queued_at + INTERVAL '30 seconds')
);
CREATE INDEX ix_precheck_execution_inflight ON precheck_executions(deadline_at) WHERE started_at IS NOT NULL AND finished_at IS NULL;
CREATE TABLE precheck_operations (
    participant_id UUID NOT NULL REFERENCES participants(id) ON DELETE CASCADE,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('CREATE','ANSWER','RETRY','CANCEL')),
    request_id UUID NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    outcome JSONB NOT NULL CHECK (jsonb_typeof(outcome) = 'object'),
    PRIMARY KEY(participant_id, kind, request_id)
);
