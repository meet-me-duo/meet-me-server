-- Existing execution rows retain an unknown first decision. No historical inference or backfill.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

ALTER TABLE precheck_executions ADD COLUMN classification_snapshot JSONB
    CONSTRAINT ck_precheck_classification_snapshot CHECK (
        classification_snapshot IS NULL OR (
            jsonb_typeof(classification_snapshot) = 'object'
            AND (classification_snapshot -> 'version' = '1'::jsonb) IS TRUE
            AND octet_length(classification_snapshot::text) <= 4096
        )
    );
