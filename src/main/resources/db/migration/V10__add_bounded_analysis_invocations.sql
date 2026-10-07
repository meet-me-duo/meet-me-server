CREATE TABLE analysis_invocations (
    id UUID PRIMARY KEY,
    coordination_run_id UUID NOT NULL REFERENCES coordination_runs(id) ON DELETE CASCADE,
    run_version BIGINT NOT NULL CHECK (run_version >= 0),
    started_at TIMESTAMPTZ NOT NULL,
    deadline_at TIMESTAMPTZ NOT NULL,
    owner_token UUID NOT NULL,
    gemini_attempts INTEGER NOT NULL DEFAULT 0 CHECK (gemini_attempts BETWEEN 0 AND 4),
    luna_attempts INTEGER NOT NULL DEFAULT 0 CHECK (luna_attempts BETWEEN 0 AND 1),
    finished_at TIMESTAMPTZ,
    winner_attempt_id UUID,
    policy_version VARCHAR(32) NOT NULL CHECK (policy_version = 'bounded-luna-v1'),
    UNIQUE (coordination_run_id, run_version),
    UNIQUE (id, coordination_run_id),
    CHECK (deadline_at > started_at AND deadline_at <= started_at + INTERVAL '60 seconds'),
    CHECK (finished_at IS NULL OR finished_at >= started_at),
    CHECK (winner_attempt_id IS NULL OR finished_at IS NOT NULL),
    CHECK (luna_attempts = 0 OR gemini_attempts > 0)
);

CREATE INDEX ix_analysis_invocations_deadline ON analysis_invocations(deadline_at) WHERE finished_at IS NULL;

ALTER TABLE coordination_attempts
    ADD COLUMN provider VARCHAR(16) NOT NULL DEFAULT 'GEMINI',
    ADD COLUMN model VARCHAR(64) NOT NULL DEFAULT 'gemini-3.8-flash',
    ADD COLUMN policy_version VARCHAR(32) NOT NULL DEFAULT 'gemini-v1',
    ADD COLUMN invocation_id UUID,
    ADD CONSTRAINT ck_attempt_provider CHECK (provider IN ('GEMINI', 'OPENAI')),
    ADD CONSTRAINT ck_attempt_model CHECK (
        (provider = 'GEMINI' AND model = 'gemini-3.8-flash') OR (provider = 'OPENAI' AND model = 'gpt-6-luna')
    ),
    ADD CONSTRAINT ck_attempt_policy CHECK (
        (invocation_id IS NULL AND policy_version = 'gemini-v1' AND provider = 'GEMINI')
        OR (invocation_id IS NOT NULL AND policy_version = 'bounded-luna-v1')
    ),
    ADD CONSTRAINT fk_attempt_invocation_run FOREIGN KEY (invocation_id, coordination_run_id)
        REFERENCES analysis_invocations(id, coordination_run_id) ON DELETE CASCADE;

ALTER TABLE coordination_attempts ADD CONSTRAINT uq_attempt_invocation_id UNIQUE (invocation_id, id);
ALTER TABLE analysis_invocations ADD CONSTRAINT fk_invocation_winner FOREIGN KEY (id, winner_attempt_id)
    REFERENCES coordination_attempts(invocation_id, id) DEFERRABLE INITIALLY DEFERRED;
