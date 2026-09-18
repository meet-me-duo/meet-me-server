CREATE TABLE guest_browser_sessions (
    id UUID PRIMARY KEY,
    credential_digest VARCHAR(128) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_guest_session_expiry CHECK (expires_at > created_at)
);

CREATE TABLE meeting_rooms (
    id UUID PRIMARY KEY,
    purpose VARCHAR(500) NOT NULL,
    duration_minutes INTEGER NOT NULL,
    meeting_mode VARCHAR(16) NOT NULL,
    time_zone_id VARCHAR(64) NOT NULL,
    search_start_date DATE NOT NULL,
    search_end_date DATE NOT NULL,
    search_range_source VARCHAR(16) NOT NULL,
    expected_participants INTEGER,
    submission_deadline TIMESTAMPTZ,
    manual_only BOOLEAN NOT NULL,
    collection_status VARCHAR(16) NOT NULL,
    closure_reason VARCHAR(32),
    closed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_room_purpose CHECK (length(trim(purpose)) > 0),
    CONSTRAINT ck_room_duration CHECK (duration_minutes > 0),
    CONSTRAINT ck_room_mode CHECK (meeting_mode IN ('IN_PERSON', 'REMOTE', 'EITHER')),
    CONSTRAINT ck_room_search_range CHECK (search_end_date > search_start_date AND search_end_date <= search_start_date + 31),
    CONSTRAINT ck_room_range_source CHECK (search_range_source IN ('HOST_SPECIFIED', 'DEFAULTED')),
    CONSTRAINT ck_room_expected_participants CHECK (expected_participants IS NULL OR expected_participants >= 2),
    CONSTRAINT ck_room_closure_policy CHECK (
        (manual_only AND expected_participants IS NULL AND submission_deadline IS NULL)
        OR (NOT manual_only AND (expected_participants IS NOT NULL OR submission_deadline IS NOT NULL))
    ),
    CONSTRAINT ck_room_collection_status CHECK (collection_status IN ('COLLECTING', 'CLOSED')),
    CONSTRAINT ck_room_closure_reason CHECK (closure_reason IS NULL OR closure_reason IN ('EXPECTED_PARTICIPANTS', 'DEADLINE', 'MANUAL')),
    CONSTRAINT ck_room_closure_fields CHECK (
        (collection_status = 'COLLECTING' AND closure_reason IS NULL AND closed_at IS NULL)
        OR (collection_status = 'CLOSED' AND closure_reason IS NOT NULL AND closed_at IS NOT NULL)
    )
);

CREATE TABLE participants (
    id UUID PRIMARY KEY,
    room_id UUID NOT NULL REFERENCES meeting_rooms(id),
    guest_session_id UUID NOT NULL REFERENCES guest_browser_sessions(id),
    role VARCHAR(16) NOT NULL,
    joined_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_participant_room_session UNIQUE (room_id, guest_session_id),
    CONSTRAINT ck_participant_role CHECK (role IN ('HOST', 'MEMBER'))
);

CREATE UNIQUE INDEX uq_room_host ON participants(room_id) WHERE role = 'HOST';

CREATE TABLE submission_heads (
    id UUID PRIMARY KEY,
    room_id UUID NOT NULL REFERENCES meeting_rooms(id),
    participant_id UUID NOT NULL REFERENCES participants(id),
    latest_version_id UUID,
    CONSTRAINT uq_submission_participant UNIQUE (participant_id)
);

CREATE TABLE submission_versions (
    id UUID PRIMARY KEY,
    submission_id UUID NOT NULL REFERENCES submission_heads(id),
    revision INTEGER NOT NULL,
    raw_text VARCHAR(500),
    locale VARCHAR(35) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_submission_revision UNIQUE (submission_id, revision),
    CONSTRAINT uq_submission_version_owner UNIQUE (id, submission_id),
    CONSTRAINT ck_submission_revision CHECK (revision > 0),
    CONSTRAINT ck_submission_raw_text CHECK (raw_text IS NULL OR length(trim(raw_text)) > 0)
);

ALTER TABLE submission_heads
    ADD CONSTRAINT fk_submission_latest_version
    FOREIGN KEY (latest_version_id, id) REFERENCES submission_versions(id, submission_id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE manual_availability_intervals (
    id UUID PRIMARY KEY,
    submission_version_id UUID NOT NULL REFERENCES submission_versions(id),
    interval_order INTEGER NOT NULL,
    interval_kind VARCHAR(16) NOT NULL,
    local_date DATE,
    day_of_week INTEGER,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    CONSTRAINT uq_manual_interval_order UNIQUE (submission_version_id, interval_order),
    CONSTRAINT ck_manual_interval_kind CHECK (interval_kind IN ('DATED', 'WEEKLY')),
    CONSTRAINT ck_manual_interval_shape CHECK (
        (interval_kind = 'DATED' AND local_date IS NOT NULL AND day_of_week IS NULL)
        OR (interval_kind = 'WEEKLY' AND local_date IS NULL AND day_of_week BETWEEN 1 AND 7)
    ),
    CONSTRAINT ck_manual_interval_time CHECK (end_time > start_time)
);

CREATE TABLE submission_batches (
    id UUID PRIMARY KEY,
    room_id UUID NOT NULL REFERENCES meeting_rooms(id),
    fixed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_submission_batch_room UNIQUE (id, room_id)
);

CREATE TABLE submission_batch_items (
    batch_id UUID NOT NULL REFERENCES submission_batches(id),
    submission_version_id UUID NOT NULL REFERENCES submission_versions(id),
    PRIMARY KEY (batch_id, submission_version_id)
);

CREATE TABLE coordination_runs (
    id UUID PRIMARY KEY,
    room_id UUID NOT NULL REFERENCES meeting_rooms(id),
    batch_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    candidate_quality VARCHAR(16),
    created_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_coordination_status CHECK (status IN ('QUEUED', 'STRUCTURING', 'MATCHING', 'COMPLETED', 'ANALYSIS_DELAYED', 'DEAD_LETTERED')),
    CONSTRAINT ck_candidate_quality CHECK (candidate_quality IS NULL OR candidate_quality IN ('COMPLETE', 'PARTIAL')),
    CONSTRAINT ck_coordination_quality_state CHECK (
        (status = 'COMPLETED' AND candidate_quality IS NOT NULL)
        OR (status <> 'COMPLETED' AND candidate_quality IS NULL)
    ),
    CONSTRAINT fk_coordination_batch_room FOREIGN KEY (batch_id, room_id) REFERENCES submission_batches(id, room_id)
);

CREATE TABLE coordination_attempts (
    id UUID PRIMARY KEY,
    coordination_run_id UUID NOT NULL REFERENCES coordination_runs(id),
    attempt_number INTEGER NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    failure_code VARCHAR(64),
    CONSTRAINT uq_coordination_attempt UNIQUE (coordination_run_id, attempt_number),
    CONSTRAINT ck_coordination_attempt_number CHECK (attempt_number > 0)
);

CREATE TABLE candidates (
    id UUID PRIMARY KEY,
    coordination_run_id UUID NOT NULL REFERENCES coordination_runs(id),
    rank INTEGER NOT NULL,
    place_name VARCHAR(500),
    latitude NUMERIC(9, 6),
    longitude NUMERIC(9, 6),
    CONSTRAINT uq_candidate_rank UNIQUE (coordination_run_id, rank),
    CONSTRAINT uq_candidate_run UNIQUE (id, coordination_run_id),
    CONSTRAINT ck_candidate_rank CHECK (rank BETWEEN 1 AND 3),
    CONSTRAINT ck_candidate_coordinate_pair CHECK ((latitude IS NULL) = (longitude IS NULL)),
    CONSTRAINT ck_candidate_latitude CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_candidate_longitude CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180)
);

CREATE TABLE candidate_time_ranges (
    candidate_id UUID NOT NULL REFERENCES candidates(id),
    range_order INTEGER NOT NULL,
    start_at TIMESTAMPTZ NOT NULL,
    end_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (candidate_id, range_order),
    CONSTRAINT ck_candidate_time_range CHECK (end_at > start_at)
);

CREATE TABLE final_confirmations (
    coordination_run_id UUID PRIMARY KEY REFERENCES coordination_runs(id),
    candidate_id UUID NOT NULL,
    confirmed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_confirmation_candidate_run
        FOREIGN KEY (candidate_id, coordination_run_id) REFERENCES candidates(id, coordination_run_id)
);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    occurred_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED')),
    CONSTRAINT ck_outbox_publication CHECK (
        (status = 'PENDING' AND published_at IS NULL)
        OR (status = 'PUBLISHED' AND published_at IS NOT NULL)
    )
);

CREATE INDEX ix_participants_room ON participants(room_id);
CREATE INDEX ix_submission_versions_submission ON submission_versions(submission_id, revision DESC);
CREATE INDEX ix_coordination_runs_room ON coordination_runs(room_id);
CREATE INDEX ix_outbox_pending ON outbox_events(occurred_at) WHERE status = 'PENDING';
