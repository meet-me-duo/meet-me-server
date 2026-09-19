ALTER TABLE coordination_runs
    ADD COLUMN resume_stage VARCHAR(16);

ALTER TABLE coordination_runs
    ADD CONSTRAINT ck_coordination_resume_stage
        CHECK (resume_stage IS NULL OR resume_stage IN ('STRUCTURING', 'MATCHING'));

ALTER TABLE coordination_runs
    ADD CONSTRAINT uq_coordination_run_id_room UNIQUE (id, room_id);

ALTER TABLE coordination_runs
    ADD CONSTRAINT uq_coordination_run_id_batch UNIQUE (id, batch_id);

ALTER TABLE participants
    ADD CONSTRAINT uq_participant_id_room UNIQUE (id, room_id);

CREATE TABLE normalized_places (
    id UUID PRIMARY KEY,
    coordination_run_id UUID NOT NULL REFERENCES coordination_runs(id) ON DELETE CASCADE,
    batch_id UUID NOT NULL,
    submission_version_id UUID NOT NULL REFERENCES submission_versions(id),
    condition_index INTEGER NOT NULL DEFAULT 0,
    query VARCHAR(500) NOT NULL,
    radius_meters INTEGER NOT NULL DEFAULT 1000,
    status VARCHAR(32) NOT NULL DEFAULT 'RESOLVED',
    provider VARCHAR(32),
    provider_place_id VARCHAR(255),
    display_name VARCHAR(500),
    latitude NUMERIC(9, 6),
    longitude NUMERIC(9, 6),
    CONSTRAINT uq_normalized_place_query_owner
        UNIQUE (coordination_run_id, submission_version_id, query),
    CONSTRAINT uq_normalized_place_condition
        UNIQUE (coordination_run_id, submission_version_id, condition_index),
    CONSTRAINT fk_normalized_place_batch_item
        FOREIGN KEY (batch_id, submission_version_id)
        REFERENCES submission_batch_items(batch_id, submission_version_id),
    CONSTRAINT fk_normalized_place_run_batch
        FOREIGN KEY (coordination_run_id, batch_id)
        REFERENCES coordination_runs(id, batch_id),
    CONSTRAINT ck_normalized_place_condition_index CHECK (condition_index >= 0),
    CONSTRAINT ck_normalized_place_radius CHECK (radius_meters BETWEEN 100 AND 50000),
    CONSTRAINT ck_normalized_place_status
        CHECK (status IN ('RESOLVED', 'NO_EXACT_MATCH', 'AMBIGUOUS')),
    CONSTRAINT ck_normalized_place_snapshot CHECK (
        (status = 'RESOLVED'
            AND provider IS NOT NULL
            AND provider_place_id IS NOT NULL
            AND display_name IS NOT NULL
            AND latitude IS NOT NULL
            AND longitude IS NOT NULL)
        OR
        (status <> 'RESOLVED'
            AND provider_place_id IS NULL
            AND display_name IS NULL
            AND latitude IS NULL
            AND longitude IS NULL)
    ),
    CONSTRAINT ck_normalized_place_latitude CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_normalized_place_longitude CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180)
);

ALTER TABLE candidates
    ADD COLUMN plan_type VARCHAR(16) NOT NULL DEFAULT 'PLAN_A',
    ADD COLUMN meeting_mode VARCHAR(16) NOT NULL DEFAULT 'IN_PERSON',
    ADD COLUMN attendance_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN total_participants INTEGER NOT NULL DEFAULT 0;

ALTER TABLE candidates
    ADD CONSTRAINT ck_candidate_plan_type CHECK (plan_type IN ('PLAN_A', 'PLAN_B', 'PLAN_C')),
    ADD CONSTRAINT ck_candidate_meeting_mode CHECK (meeting_mode IN ('IN_PERSON', 'REMOTE')),
    ADD CONSTRAINT ck_candidate_attendance_count CHECK (attendance_count >= 0),
    ADD CONSTRAINT ck_candidate_total_participants CHECK (total_participants >= attendance_count);

CREATE TABLE candidate_participants (
    candidate_id UUID NOT NULL,
    participant_id UUID NOT NULL,
    coordination_run_id UUID NOT NULL,
    room_id UUID NOT NULL,
    PRIMARY KEY (candidate_id, participant_id),
    CONSTRAINT fk_candidate_participant_candidate_run
        FOREIGN KEY (candidate_id, coordination_run_id)
        REFERENCES candidates(id, coordination_run_id) ON DELETE CASCADE,
    CONSTRAINT fk_candidate_participant_run_room
        FOREIGN KEY (coordination_run_id, room_id)
        REFERENCES coordination_runs(id, room_id),
    CONSTRAINT fk_candidate_participant_room_member
        FOREIGN KEY (participant_id, room_id)
        REFERENCES participants(id, room_id)
);

CREATE INDEX ix_normalized_places_run ON normalized_places(coordination_run_id);
CREATE INDEX ix_candidate_participants_participant ON candidate_participants(participant_id);
