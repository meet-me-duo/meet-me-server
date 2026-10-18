-- Global rows contain structural configuration metadata only; request-derived evidence stays room-owned.
CREATE TABLE ai_budget_profiles (
    profile_snapshot_fingerprint VARCHAR(64) PRIMARY KEY CHECK (profile_snapshot_fingerprint ~ '^[0-9a-f]{64}$'),
    profile_fingerprint VARCHAR(64) NOT NULL CHECK (profile_fingerprint ~ '^[0-9a-f]{64}$'),
    policy_fingerprint VARCHAR(64) NOT NULL CHECK (policy_fingerprint ~ '^[0-9a-f]{64}$'),
    unit_code VARCHAR(32) NOT NULL CHECK (length(trim(unit_code)) > 0),
    unit_scale SMALLINT NOT NULL CHECK (unit_scale BETWEEN 0 AND 38)
);

CREATE TABLE ai_room_budgets (
    room_id UUID PRIMARY KEY REFERENCES meeting_rooms(id) ON DELETE CASCADE,
    state VARCHAR(32) NOT NULL CHECK (state IN ('UNCONFIGURED','HISTORICAL_UNKNOWN','UNTRACKED_EPOCH','READY','FROZEN')),
    epoch_id UUID,
    unit_code VARCHAR(32) CHECK (unit_code IS NULL OR length(trim(unit_code)) > 0),
    unit_scale SMALLINT CHECK (unit_scale BETWEEN 0 AND 38),
    cap NUMERIC(38,0) CHECK (cap > 0),
    policy_fingerprint VARCHAR(64) CHECK (policy_fingerprint ~ '^[0-9a-f]{64}$'),
    coverage_started_at TIMESTAMPTZ,
    settled NUMERIC(38,0) NOT NULL DEFAULT 0 CHECK (settled >= 0),
    held NUMERIC(38,0) NOT NULL DEFAULT 0 CHECK (held >= 0),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    CHECK (state <> 'READY' OR (epoch_id IS NOT NULL AND unit_code IS NOT NULL AND unit_scale IS NOT NULL
        AND cap IS NOT NULL AND policy_fingerprint IS NOT NULL AND coverage_started_at IS NOT NULL))
);

CREATE TABLE ai_call_reservations (
    id UUID PRIMARY KEY,
    room_id UUID NOT NULL REFERENCES ai_room_budgets(room_id) ON DELETE CASCADE,
    execution_kind VARCHAR(24) NOT NULL CHECK (execution_kind IN ('PRECHECK','FINAL_ANALYSIS')),
    execution_id UUID NOT NULL,
    generation BIGINT NOT NULL CHECK (generation >= 0),
    execution_version BIGINT NOT NULL CHECK (execution_version >= 0),
    owner_token UUID NOT NULL,
    source_fingerprint VARCHAR(64) NOT NULL CHECK (source_fingerprint ~ '^[0-9a-f]{64}$'),
    context_fingerprint VARCHAR(64) NOT NULL CHECK (context_fingerprint ~ '^[0-9a-f]{64}$'),
    operation VARCHAR(32) NOT NULL CHECK (operation IN ('PRECHECK_CLASSIFICATION','PRECHECK_COUNT','PRECHECK_GENERATION','FINAL_PRIMARY','FINAL_FALLBACK')),
    ordinal INTEGER NOT NULL CHECK (ordinal > 0),
    attempt_id UUID,
    profile_snapshot_fingerprint VARCHAR(64) NOT NULL REFERENCES ai_budget_profiles(profile_snapshot_fingerprint),
    quote_fingerprint VARCHAR(64) NOT NULL CHECK (quote_fingerprint ~ '^[0-9a-f]{64}$'),
    whole_payload_fingerprint VARCHAR(64) NOT NULL CHECK (whole_payload_fingerprint ~ '^[0-9a-f]{64}$'),
    profile_fingerprint VARCHAR(64) NOT NULL CHECK (profile_fingerprint ~ '^[0-9a-f]{64}$'),
    policy_fingerprint VARCHAR(64) NOT NULL CHECK (policy_fingerprint ~ '^[0-9a-f]{64}$'),
    evidence_fingerprint VARCHAR(64) NOT NULL CHECK (evidence_fingerprint ~ '^[0-9a-f]{64}$'),
    maximum_amount NUMERIC(38,0) NOT NULL CHECK (maximum_amount >= 0),
    unit_code VARCHAR(32) NOT NULL CHECK (length(trim(unit_code)) > 0),
    unit_scale SMALLINT NOT NULL CHECK (unit_scale BETWEEN 0 AND 38),
    quote_valid_until TIMESTAMPTZ NOT NULL,
    epoch_id UUID NOT NULL,
    state VARCHAR(24) NOT NULL CHECK (state IN ('RESERVED','DISPATCH_INTENT','UNKNOWN','SETTLED')),
    hold_amount NUMERIC(38,0) NOT NULL CHECK (hold_amount >= 0 AND hold_amount <= maximum_amount),
    dispatch_token UUID UNIQUE,
    dispatched_at TIMESTAMPTZ,
    dispatch_deadline_at TIMESTAMPTZ,
    lease_until TIMESTAMPTZ,
    settled_amount NUMERIC(38,0) CHECK (settled_amount >= 0),
    settled_evidence_fingerprint VARCHAR(64) CHECK (settled_evidence_fingerprint ~ '^[0-9a-f]{64}$'),
    reserved_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (room_id, execution_kind, execution_id, generation, operation, ordinal),
    CHECK ((execution_kind = 'PRECHECK' AND generation > 0 AND execution_version > 0 AND attempt_id IS NULL
            AND operation IN ('PRECHECK_CLASSIFICATION','PRECHECK_COUNT','PRECHECK_GENERATION') AND ordinal = 1)
        OR (execution_kind = 'FINAL_ANALYSIS' AND attempt_id IS NOT NULL AND generation = execution_version
            AND ((operation = 'FINAL_PRIMARY' AND ordinal BETWEEN 1 AND 4) OR (operation = 'FINAL_FALLBACK' AND ordinal = 1)))),
    CHECK ((state = 'RESERVED' AND dispatch_token IS NULL AND dispatched_at IS NULL AND dispatch_deadline_at IS NULL)
        OR (state <> 'RESERVED' AND dispatch_token IS NOT NULL AND dispatched_at IS NOT NULL AND dispatch_deadline_at IS NOT NULL)),
    CHECK (state <> 'SETTLED' OR (settled_amount IS NOT NULL AND settled_evidence_fingerprint IS NOT NULL AND hold_amount = 0))
);
CREATE INDEX ix_ai_call_reservations_room_state ON ai_call_reservations(room_id, state);

CREATE TABLE ai_call_observations (
    reservation_id UUID NOT NULL REFERENCES ai_call_reservations(id) ON DELETE CASCADE,
    event_key VARCHAR(128) NOT NULL CHECK (length(event_key) > 0),
    event_fingerprint VARCHAR(64) NOT NULL CHECK (event_fingerprint ~ '^[0-9a-f]{64}$'),
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('USAGE','BILL')),
    outcome VARCHAR(16) CHECK (outcome IN ('SUCCEEDED','FAILED','REFUSED','TIMED_OUT','UNKNOWN')),
    failure VARCHAR(24) CHECK (failure IN ('CONFIGURATION','AUTHENTICATION','PERMISSION','QUOTA','RATE_LIMIT','NETWORK',
        'TIMEOUT','INVALID_RESPONSE','PROVIDER','ACCOUNTING','STALE')),
    input_tokens BIGINT CHECK (input_tokens >= 0),
    output_tokens BIGINT CHECK (output_tokens >= 0),
    reasoning_tokens BIGINT CHECK (reasoning_tokens >= 0),
    cached_input_tokens BIGINT CHECK (cached_input_tokens >= 0),
    usage_complete BOOLEAN NOT NULL DEFAULT false,
    bill_amount NUMERIC(38,0) CHECK (bill_amount >= 0),
    evidence_fingerprint VARCHAR(64) CHECK (evidence_fingerprint ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (reservation_id, event_key)
);

-- Existing counters/usage do not prove historical complete billing. No existing room becomes READY.
INSERT INTO ai_room_budgets(room_id, state)
SELECT id, 'HISTORICAL_UNKNOWN' FROM meeting_rooms;

CREATE FUNCTION initialize_ai_room_budget() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO ai_room_budgets(room_id, state) VALUES (NEW.id, 'UNCONFIGURED');
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_init_ai_room_budget AFTER INSERT ON meeting_rooms
FOR EACH ROW EXECUTE FUNCTION initialize_ai_room_budget();
