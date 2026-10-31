-- Retain uncertainty per time-eligible participant, including after organizer selection.
-- Existing published/selected projections keep their historical interpretation.
-- Match V21's bounded DDL budget; transaction-local settings restore on success or rollback.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

ALTER TABLE candidate_participants ADD COLUMN place_unverified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE recommendation_variant_participants ADD COLUMN place_unverified BOOLEAN NOT NULL DEFAULT FALSE;

-- A proposed in-person time can have no verified place yet. No coordinate/name is invented.
-- This is V9's second table CHECK, not its REMOTE-null/coordinate/identity constraints.
ALTER TABLE recommendation_variants DROP CONSTRAINT recommendation_variants_check1;

-- Pending place verification applies only to in-person participation, never an online claim.
CREATE FUNCTION guard_pending_place_participant() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE actual_mode VARCHAR(16);
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.place_unverified IS DISTINCT FROM OLD.place_unverified THEN
        RAISE EXCEPTION 'Published place verification is immutable' USING ERRCODE = '23514';
    END IF;
    IF NEW.place_unverified THEN
        IF TG_TABLE_NAME = 'candidate_participants' THEN
            SELECT meeting_mode INTO actual_mode FROM candidates WHERE id = NEW.candidate_id;
        ELSE
            SELECT meeting_mode INTO actual_mode FROM recommendation_variants WHERE id = NEW.variant_id;
        END IF;
        IF actual_mode IS DISTINCT FROM 'IN_PERSON' THEN
            RAISE EXCEPTION 'Only in-person participation can have unverified place' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER candidate_pending_place_guard BEFORE INSERT OR UPDATE ON candidate_participants
    FOR EACH ROW EXECUTE FUNCTION guard_pending_place_participant();
CREATE TRIGGER recommendation_pending_place_guard BEFORE INSERT OR UPDATE ON recommendation_variant_participants
    FOR EACH ROW EXECUTE FUNCTION guard_pending_place_participant();
