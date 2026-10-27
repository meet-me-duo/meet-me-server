-- ADR-060: a published time option may include any frozen attendee set of at least two.
-- Replace only the old N-2 attendance floor; keep all selection and frozen-cohort guards.
-- Existing projections, selections, foreign keys and triggers remain unchanged.
CREATE OR REPLACE FUNCTION guard_recommendation_selection() RETURNS trigger LANGUAGE plpgsql AS $$
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
    IF selected_count < 2 OR selected_count > eligible_count
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
