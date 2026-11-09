-- Explicit C5 diagnostic provider selection only; historical fallback policies keep Gemini first.
-- No existing rows or counters are changed. The runtime fence additionally requires a sole OPENAI attempt #1.
ALTER TABLE analysis_invocations DROP CONSTRAINT analysis_invocations_check3;
ALTER TABLE analysis_invocations ADD CONSTRAINT ck_invocation_provider_order CHECK (
    luna_attempts = 0 OR gemini_attempts > 0
    OR (policy_version = 'c5-residual-luna-v2' AND gemini_attempts = 0 AND luna_attempts = 1)
);
