-- #110: access metadata only; no business snapshots or credentials.
-- No target/account FK: existing room/account retention must not be blocked.
CREATE TABLE operations_access_audits (
    id UUID PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL,
    actor_user_id UUID,
    route VARCHAR(32) NOT NULL CHECK (route IN ('ROOM_LIST', 'ROOM_DETAIL', 'INVOCATION_DETAIL')),
    target_id UUID,
    outcome VARCHAR(32) NOT NULL CHECK (outcome IN ('ALLOWED', 'DENIED', 'NOT_FOUND', 'INVALID_QUERY', 'UNAVAILABLE'))
);

CREATE INDEX ix_operations_access_audits_retention ON operations_access_audits(occurred_at, id);
