CREATE TABLE recommendation_analyses (
    coordination_run_id UUID PRIMARY KEY,
    room_id UUID NOT NULL,
    protocol VARCHAR(32) NOT NULL CHECK (protocol = 'diverse-time-v1'),
    UNIQUE (coordination_run_id, room_id),
    FOREIGN KEY (coordination_run_id, room_id) REFERENCES coordination_runs(id, room_id) ON DELETE CASCADE
);

CREATE TABLE recommendation_options (
    id UUID PRIMARY KEY,
    coordination_run_id UUID NOT NULL,
    room_id UUID NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    primary_rank INTEGER CHECK (primary_rank BETWEEN 1 AND 3),
    start_at TIMESTAMPTZ NOT NULL,
    end_at TIMESTAMPTZ NOT NULL,
    CHECK (start_at < end_at),
    UNIQUE (id, coordination_run_id, room_id),
    UNIQUE (coordination_run_id, ordinal),
    UNIQUE (coordination_run_id, primary_rank),
    UNIQUE (coordination_run_id, start_at, end_at),
    FOREIGN KEY (coordination_run_id, room_id) REFERENCES recommendation_analyses(coordination_run_id, room_id) ON DELETE CASCADE
);

CREATE TABLE recommendation_variants (
    id UUID PRIMARY KEY,
    option_id UUID NOT NULL,
    coordination_run_id UUID NOT NULL,
    room_id UUID NOT NULL,
    meeting_mode VARCHAR(16) NOT NULL CHECK (meeting_mode IN ('IN_PERSON', 'REMOTE')),
    area_key VARCHAR(64),
    place_name VARCHAR(500),
    latitude NUMERIC(9, 6),
    longitude NUMERIC(9, 6),
    preference_count INTEGER NOT NULL CHECK (preference_count >= 0),
    CHECK (meeting_mode <> 'REMOTE' OR (area_key IS NULL AND place_name IS NULL AND latitude IS NULL AND longitude IS NULL)),
    CHECK (meeting_mode <> 'IN_PERSON' OR place_name IS NOT NULL),
    CHECK ((latitude IS NULL) = (longitude IS NULL)),
    UNIQUE (id, option_id, coordination_run_id, room_id),
    FOREIGN KEY (option_id, coordination_run_id, room_id) REFERENCES recommendation_options(id, coordination_run_id, room_id) ON DELETE CASCADE
);

CREATE TABLE recommendation_variant_participants (
    variant_id UUID NOT NULL,
    option_id UUID NOT NULL,
    coordination_run_id UUID NOT NULL,
    room_id UUID NOT NULL,
    participant_id UUID NOT NULL,
    PRIMARY KEY (variant_id, participant_id),
    FOREIGN KEY (variant_id, option_id, coordination_run_id, room_id)
        REFERENCES recommendation_variants(id, option_id, coordination_run_id, room_id) ON DELETE CASCADE,
    FOREIGN KEY (participant_id, room_id) REFERENCES participants(id, room_id)
);

CREATE TABLE recommendation_selections (
    coordination_run_id UUID PRIMARY KEY,
    room_id UUID NOT NULL UNIQUE,
    option_id UUID NOT NULL,
    variant_id UUID NOT NULL,
    start_at TIMESTAMPTZ NOT NULL,
    end_at TIMESTAMPTZ NOT NULL,
    confirmed_at TIMESTAMPTZ NOT NULL,
    CHECK (start_at < end_at),
    FOREIGN KEY (variant_id, option_id, coordination_run_id, room_id)
        REFERENCES recommendation_variants(id, option_id, coordination_run_id, room_id) ON DELETE CASCADE
);

-- Old candidate writers must not bypass actual-time selection for a published new protocol.
CREATE FUNCTION guard_recommendation_legacy_confirmation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target_room UUID;
BEGIN
    SELECT room_id INTO target_room FROM coordination_runs WHERE id = NEW.coordination_run_id;
    PERFORM id FROM meeting_rooms WHERE id = target_room FOR UPDATE;
    PERFORM id FROM coordination_runs WHERE id = NEW.coordination_run_id FOR UPDATE;
    IF EXISTS (SELECT 1 FROM recommendation_analyses WHERE coordination_run_id = NEW.coordination_run_id) THEN
        RAISE EXCEPTION 'Recommendation selection required' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER recommendation_legacy_confirmation_guard BEFORE INSERT OR UPDATE ON final_confirmations
    FOR EACH ROW EXECUTE FUNCTION guard_recommendation_legacy_confirmation();

-- Every insert locks room then run, matching application confirmation/revision/publication order.
CREATE FUNCTION guard_recommendation_selection() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE room_record meeting_rooms%ROWTYPE;
DECLARE run_record coordination_runs%ROWTYPE;
DECLARE option_record recommendation_options%ROWTYPE;
DECLARE eligible_count INTEGER;
DECLARE selected_count INTEGER;
DECLARE batch_fixed_at TIMESTAMPTZ;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Recommendation selection is immutable' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO room_record FROM meeting_rooms WHERE id = NEW.room_id FOR UPDATE;
    SELECT * INTO run_record FROM coordination_runs WHERE id = NEW.coordination_run_id FOR UPDATE;
    SELECT * INTO option_record FROM recommendation_options WHERE id = NEW.option_id;
    IF room_record.id IS NULL OR run_record.room_id IS DISTINCT FROM NEW.room_id
       OR room_record.active_run_id IS DISTINCT FROM NEW.coordination_run_id
       OR room_record.collection_status <> 'CLOSED' OR run_record.status <> 'COMPLETED'
       OR EXISTS (SELECT 1 FROM input_revision_rounds WHERE room_id = NEW.room_id AND status = 'OPEN')
       OR EXISTS (SELECT 1 FROM final_confirmations WHERE coordination_run_id = NEW.coordination_run_id)
       OR option_record.coordination_run_id IS DISTINCT FROM NEW.coordination_run_id
       OR NEW.start_at < option_record.start_at OR NEW.end_at > option_record.end_at THEN
        RAISE EXCEPTION 'Invalid or stale recommendation selection' USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO eligible_count FROM submission_batch_items WHERE batch_id = run_record.batch_id;
    SELECT count(*) INTO selected_count FROM recommendation_variant_participants WHERE variant_id = NEW.variant_id;
    SELECT fixed_at INTO batch_fixed_at FROM submission_batches WHERE id = run_record.batch_id;
    IF selected_count < GREATEST(2, eligible_count - 2) OR selected_count > eligible_count
       OR NEW.confirmed_at < batch_fixed_at
       OR EXISTS (
           SELECT 1 FROM recommendation_variant_participants vp WHERE vp.variant_id = NEW.variant_id
           AND NOT EXISTS (
               SELECT 1 FROM submission_batch_items bi
               JOIN submission_versions sv ON sv.id = bi.submission_version_id
               JOIN submission_heads sh ON sh.id = sv.submission_id
               WHERE bi.batch_id = run_record.batch_id AND sh.participant_id = vp.participant_id
           )
       ) THEN
        RAISE EXCEPTION 'Recommendation participants must belong to the frozen batch' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER recommendation_selection_guard BEFORE INSERT OR UPDATE ON recommendation_selections
    FOR EACH ROW EXECUTE FUNCTION guard_recommendation_selection();

CREATE FUNCTION guard_recommendation_confirmed_run() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM recommendation_selections WHERE coordination_run_id = OLD.id)
       AND (NEW.status IS DISTINCT FROM OLD.status OR NEW.candidate_quality IS DISTINCT FROM OLD.candidate_quality
            OR NEW.batch_id IS DISTINCT FROM OLD.batch_id OR NEW.room_id IS DISTINCT FROM OLD.room_id) THEN
        RAISE EXCEPTION 'Confirmed recommendation run cannot change' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER recommendation_confirmed_run_guard BEFORE UPDATE ON coordination_runs
    FOR EACH ROW EXECUTE FUNCTION guard_recommendation_confirmed_run();

CREATE FUNCTION guard_recommendation_confirmed_room() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM recommendation_selections WHERE room_id = OLD.id)
       AND (NEW.active_run_id IS DISTINCT FROM OLD.active_run_id
            OR NEW.active_revision_round_id IS DISTINCT FROM OLD.active_revision_round_id
            OR NEW.revision_generation IS DISTINCT FROM OLD.revision_generation) THEN
        RAISE EXCEPTION 'Confirmed recommendation room cannot reopen or replace its analysis' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER recommendation_confirmed_room_guard BEFORE UPDATE ON meeting_rooms
    FOR EACH ROW EXECUTE FUNCTION guard_recommendation_confirmed_room();

CREATE FUNCTION guard_recommendation_revision_round() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM id FROM meeting_rooms WHERE id = NEW.room_id FOR UPDATE;
    IF EXISTS (SELECT 1 FROM recommendation_selections WHERE room_id = NEW.room_id) THEN
        RAISE EXCEPTION 'Confirmed recommendation cannot enter revision' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER recommendation_revision_round_guard BEFORE INSERT OR UPDATE ON input_revision_rounds
    FOR EACH ROW EXECUTE FUNCTION guard_recommendation_revision_round();

-- Published windows/variants and their identities are stable, including while a revision is OPEN.
-- DELETE is deliberately allowed for existing retention; run/room CASCADE owns the lifecycle.
CREATE FUNCTION guard_recommendation_projection_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Published recommendation projection is immutable' USING ERRCODE = '23514';
END $$;
CREATE TRIGGER recommendation_analysis_update_guard BEFORE UPDATE ON recommendation_analyses
    FOR EACH ROW EXECUTE FUNCTION guard_recommendation_projection_update();
CREATE TRIGGER recommendation_option_update_guard BEFORE UPDATE ON recommendation_options
    FOR EACH ROW EXECUTE FUNCTION guard_recommendation_projection_update();
CREATE TRIGGER recommendation_variant_update_guard BEFORE UPDATE ON recommendation_variants
    FOR EACH ROW EXECUTE FUNCTION guard_recommendation_projection_update();
CREATE TRIGGER recommendation_participant_update_guard BEFORE UPDATE ON recommendation_variant_participants
    FOR EACH ROW EXECUTE FUNCTION guard_recommendation_projection_update();

CREATE INDEX ix_recommendation_variant_option ON recommendation_variants(option_id);
CREATE INDEX ix_recommendation_participant_option ON recommendation_variant_participants(option_id);
