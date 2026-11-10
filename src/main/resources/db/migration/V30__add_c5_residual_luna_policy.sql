-- New residual C5 invocations only; existing invocation/attempt rows retain their policy.
ALTER TABLE analysis_invocations DROP CONSTRAINT analysis_invocations_policy_version_check;
ALTER TABLE analysis_invocations ADD CONSTRAINT analysis_invocations_policy_version_check CHECK (
    policy_version IN ('bounded-luna-v1', 'c5-residual-luna-v2')
);

-- Preserve the original (invocation,run) FK and add exact policy ownership for every bounded attempt.
ALTER TABLE analysis_invocations ADD CONSTRAINT uq_invocation_run_policy UNIQUE (id, coordination_run_id, policy_version);
ALTER TABLE coordination_attempts DROP CONSTRAINT ck_attempt_policy;
ALTER TABLE coordination_attempts ADD CONSTRAINT ck_attempt_policy CHECK (
    (invocation_id IS NULL AND policy_version = 'gemini-v1' AND provider = 'GEMINI')
    OR (invocation_id IS NOT NULL AND policy_version IN ('bounded-luna-v1', 'c5-residual-luna-v2'))
);
ALTER TABLE coordination_attempts ADD CONSTRAINT fk_attempt_invocation_run_policy
    FOREIGN KEY (invocation_id, coordination_run_id, policy_version)
    REFERENCES analysis_invocations(id, coordination_run_id, policy_version) ON DELETE CASCADE;
