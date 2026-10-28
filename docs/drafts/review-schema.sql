-- Draft only. Assign a Flyway version after coordinating concurrent migrations.
CREATE TABLE review_user_policies (
    owner_key VARCHAR(44) PRIMARY KEY,
    user_id UUID UNIQUE REFERENCES account_users(id) ON DELETE CASCADE,
    guest_session_id UUID UNIQUE REFERENCES guest_browser_sessions(id) ON DELETE CASCADE,
    satisfied_at TIMESTAMPTZ,
    hidden_until TIMESTAMPTZ,
    CONSTRAINT ck_review_owner CHECK (
        (user_id IS NOT NULL AND guest_session_id IS NULL AND owner_key = 'ACCOUNT:' || user_id::text)
        OR (user_id IS NULL AND guest_session_id IS NOT NULL AND owner_key = 'GUEST:' || guest_session_id::text)
    )
);

CREATE FUNCTION review_choices_are_unique(INTEGER[]) RETURNS BOOLEAN
    LANGUAGE SQL IMMUTABLE STRICT AS $$
        SELECT count(*) = count(DISTINCT choice) FROM unnest($1) AS choice;
    $$;

CREATE TABLE review_prompts (
    id UUID PRIMARY KEY,
    participant_id UUID NOT NULL UNIQUE,
    room_id UUID NOT NULL REFERENCES meeting_rooms(id) ON DELETE CASCADE,
    owner_key VARCHAR(44) NOT NULL REFERENCES review_user_policies(owner_key) ON DELETE CASCADE,
    request_id UUID NOT NULL,
    state VARCHAR(16) NOT NULL,
    choices INTEGER[],
    other_text VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    hide_for_week BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_review_participant_room FOREIGN KEY (participant_id, room_id)
        REFERENCES participants(id, room_id) ON DELETE CASCADE,
    CONSTRAINT ck_review_state CHECK (state IN ('PENDING', 'SUBMITTED', 'CLOSED', 'SUPPRESSED')),
    CONSTRAINT ck_review_terminal CHECK (
        (state = 'PENDING' AND completed_at IS NULL)
        OR (state <> 'PENDING' AND completed_at IS NOT NULL AND completed_at >= created_at)
    ),
    CONSTRAINT ck_review_answer CHECK (
        (state = 'SUBMITTED' AND choices IS NOT NULL AND cardinality(choices) BETWEEN 1 AND 5
            AND choices <@ ARRAY[1,2,3,4,5] AND array_position(choices, NULL) IS NULL AND review_choices_are_unique(choices)
            AND (NOT (1 = ANY(choices)) OR choices = ARRAY[1])
            AND (other_text IS NULL OR 5 = ANY(choices)))
        OR (state <> 'SUBMITTED' AND choices IS NULL AND other_text IS NULL)
    ),
    CONSTRAINT ck_review_hide CHECK (NOT hide_for_week OR state = 'CLOSED')
);

-- Existing explicit account claim already verifies both owners under the room lock.
-- Copy policy only on that verified ownership transition; never infer identity from a name.
CREATE FUNCTION review_policy_after_verified_claim() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.guest_session_id IS NOT NULL AND OLD.user_id IS NULL
        AND NEW.guest_session_id IS NULL AND NEW.user_id IS NOT NULL THEN
        PERFORM 1 FROM review_user_policies
            WHERE owner_key = 'GUEST:' || OLD.guest_session_id::text FOR UPDATE;
        INSERT INTO review_user_policies(owner_key, user_id, satisfied_at, hidden_until)
            SELECT 'ACCOUNT:' || NEW.user_id::text, NEW.user_id, satisfied_at, hidden_until
            FROM review_user_policies WHERE owner_key = 'GUEST:' || OLD.guest_session_id::text
            ON CONFLICT(owner_key) DO UPDATE SET
                satisfied_at = COALESCE(review_user_policies.satisfied_at, EXCLUDED.satisfied_at),
                hidden_until = GREATEST(review_user_policies.hidden_until, EXCLUDED.hidden_until);
        UPDATE review_prompts SET owner_key = 'ACCOUNT:' || NEW.user_id::text
            WHERE participant_id = NEW.id;
    END IF;
    RETURN NEW;
EXCEPTION WHEN OTHERS THEN
    -- The caller is the existing account adapter, outside the review storage wrapper.
    -- A failing review row must never escape as PostgreSQL DETAIL with private text.
    RAISE EXCEPTION 'Review policy transfer unavailable' USING ERRCODE = 'P0001';
END;
$$;

CREATE TRIGGER review_verified_claim AFTER UPDATE OF guest_session_id, user_id ON participants
    FOR EACH ROW EXECUTE FUNCTION review_policy_after_verified_claim();
