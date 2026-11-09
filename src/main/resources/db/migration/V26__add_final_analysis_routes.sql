-- No historical backfill. Route selection is not provider admission or a stored winner.
CREATE TABLE final_analysis_routes (
    run_id UUID PRIMARY KEY,
    batch_id UUID NOT NULL,
    room_id UUID NOT NULL,
    route_kind VARCHAR(32) NOT NULL,
    format_version INTEGER NOT NULL,
    route_fingerprint VARCHAR(64) NOT NULL,
    selection JSONB NOT NULL,
    selected_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_final_analysis_route_run FOREIGN KEY (run_id,batch_id,room_id)
        REFERENCES coordination_runs(id,batch_id,room_id) ON DELETE CASCADE,
    CONSTRAINT ck_final_analysis_route_shape CHECK (
        route_kind IN ('LEGACY','DSL_CONTEXT_V2')
        AND format_version > 0
        AND route_fingerprint ~ '^[a-f0-9]{64}$'
        AND jsonb_typeof(selection) = 'object'
    )
);
