-- Local client lifecycle metadata only. No request, response or credential columns.
ALTER TABLE coordination_attempts ADD CONSTRAINT uq_attempt_transport_identity UNIQUE (invocation_id, id, provider);

CREATE TABLE parser_transport_observations (
    attempt_id UUID PRIMARY KEY,
    invocation_id UUID NOT NULL,
    provider VARCHAR(16) NOT NULL CHECK (provider IN ('GEMINI', 'OPENAI')),
    parser_dispatched BOOLEAN NOT NULL,
    call_count INTEGER NOT NULL CHECK (call_count BETWEEN 0 AND 256),
    CHECK (parser_dispatched OR call_count = 0),
    FOREIGN KEY (invocation_id, attempt_id, provider)
        REFERENCES coordination_attempts(invocation_id, id, provider) ON DELETE CASCADE
);

CREATE INDEX ix_parser_transport_invocation ON parser_transport_observations(invocation_id);

CREATE TABLE parser_transport_client_calls (
    attempt_id UUID NOT NULL REFERENCES parser_transport_observations(attempt_id) ON DELETE CASCADE,
    call_number INTEGER NOT NULL CHECK (call_number BETWEEN 1 AND 256),
    elapsed_nanos BIGINT NOT NULL CHECK (elapsed_nanos >= 0),
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('COMPLETED', 'FAILED')),
    http_status INTEGER CHECK (http_status BETWEEN 100 AND 599),
    PRIMARY KEY (attempt_id, call_number)
);
