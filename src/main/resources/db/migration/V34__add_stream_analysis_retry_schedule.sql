-- Existing policies and their historical attempt rows retain their semantics.
ALTER TABLE analysis_invocations DROP CONSTRAINT analysis_invocations_policy_version_check;
ALTER TABLE analysis_invocations ADD CONSTRAINT analysis_invocations_policy_version_check CHECK (
    policy_version IN ('bounded-luna-v1', 'c5-residual-luna-v2', 'c5-stream-retry-v3')
);
ALTER TABLE coordination_attempts DROP CONSTRAINT ck_attempt_policy;
ALTER TABLE coordination_attempts ADD CONSTRAINT ck_attempt_policy CHECK (
    (invocation_id IS NULL AND policy_version = 'gemini-v1' AND provider = 'GEMINI')
    OR (invocation_id IS NOT NULL AND policy_version IN ('bounded-luna-v1', 'c5-residual-luna-v2', 'c5-stream-retry-v3'))
);
ALTER TABLE analysis_invocations
    ADD COLUMN next_attempt_at TIMESTAMPTZ,
    ADD COLUMN next_provider VARCHAR(16),
    ADD CONSTRAINT ck_invocation_retry_schedule CHECK (
        (next_attempt_at IS NULL AND next_provider IS NULL)
        OR (next_attempt_at IS NOT NULL AND next_provider IS NOT NULL
            AND next_provider IN ('GEMINI', 'OPENAI') AND policy_version = 'c5-stream-retry-v3'
            AND finished_at IS NULL AND winner_attempt_id IS NULL
            AND next_attempt_at >= started_at AND next_attempt_at < deadline_at)
    );
ALTER TABLE outbox_events
    ADD COLUMN next_attempt_at TIMESTAMPTZ,
    ADD COLUMN last_attempt_at TIMESTAMPTZ,
    ADD COLUMN unexpected_failures INTEGER NOT NULL DEFAULT 0 CHECK (unexpected_failures >= 0),
    ADD CONSTRAINT ck_outbox_retry_schedule CHECK (next_attempt_at IS NULL OR status = 'PUBLISHED');
