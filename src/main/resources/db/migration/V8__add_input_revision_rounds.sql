ALTER TABLE meeting_rooms
    ADD COLUMN active_run_id UUID,
    ADD COLUMN revision_generation BIGINT NOT NULL DEFAULT 0 CHECK (revision_generation >= 0),
    ADD COLUMN active_revision_round_id UUID,
    ADD COLUMN correction_analysis_count INTEGER NOT NULL DEFAULT 0 CHECK (correction_analysis_count BETWEEN 0 AND 3),
    ADD CONSTRAINT fk_room_active_run FOREIGN KEY (active_run_id, id)
        REFERENCES coordination_runs(id, room_id);

-- Never silently choose between two already confirmed results.
DO $$
BEGIN
    IF EXISTS (
        SELECT r.room_id FROM final_confirmations f JOIN coordination_runs r ON r.id = f.coordination_run_id
        GROUP BY r.room_id HAVING count(DISTINCT r.id) > 1
    ) THEN
        RAISE EXCEPTION 'Multiple confirmed coordination runs require explicit reconciliation';
    END IF;
END $$;

UPDATE meeting_rooms m SET active_run_id = (
    SELECT r.id FROM coordination_runs r
    LEFT JOIN final_confirmations f ON f.coordination_run_id = r.id
    WHERE r.room_id = m.id
    ORDER BY (f.coordination_run_id IS NOT NULL) DESC, r.created_at DESC, r.id DESC LIMIT 1
);

CREATE TABLE input_revision_rounds (
    id UUID PRIMARY KEY,
    room_id UUID NOT NULL REFERENCES meeting_rooms(id),
    generation BIGINT NOT NULL CHECK (generation > 0),
    source_run_id UUID NOT NULL,
    reopen_request_id UUID NOT NULL,
    expected_generation BIGINT NOT NULL CHECK (expected_generation >= 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('OPEN', 'CONSUMED')),
    opened_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    analyze_request_id UUID,
    force_reparse BOOLEAN,
    resolved_run_id UUID,
    outcome VARCHAR(16),
    UNIQUE (id, room_id),
    UNIQUE (room_id, generation),
    UNIQUE (room_id, reopen_request_id),
    UNIQUE (room_id, analyze_request_id),
    FOREIGN KEY (source_run_id, room_id) REFERENCES coordination_runs(id, room_id),
    FOREIGN KEY (resolved_run_id, room_id) REFERENCES coordination_runs(id, room_id),
    CHECK (generation = expected_generation + 1),
    CHECK (
        (status = 'OPEN' AND consumed_at IS NULL AND analyze_request_id IS NULL
         AND force_reparse IS NULL AND resolved_run_id IS NULL AND outcome IS NULL)
        OR
        (status = 'CONSUMED' AND consumed_at IS NOT NULL AND analyze_request_id IS NOT NULL
         AND force_reparse IS NOT NULL AND resolved_run_id IS NOT NULL
         AND outcome IS NOT NULL AND outcome IN ('QUEUED', 'REUSED'))
    )
);
CREATE UNIQUE INDEX uq_open_input_revision_round ON input_revision_rounds(room_id) WHERE status = 'OPEN';
ALTER TABLE meeting_rooms ADD CONSTRAINT fk_room_active_revision_round
    FOREIGN KEY (active_revision_round_id, id) REFERENCES input_revision_rounds(id, room_id);
