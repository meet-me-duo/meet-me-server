CREATE TABLE account_users (
    id UUID PRIMARY KEY,
    display_name VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_account_display_name CHECK (length(trim(display_name)) BETWEEN 1 AND 50)
);

CREATE TABLE account_identities (
    provider VARCHAR(16) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    user_id UUID NOT NULL REFERENCES account_users(id),
    PRIMARY KEY (provider, subject),
    CONSTRAINT ck_account_identity_provider CHECK (provider IN ('GOOGLE', 'KAKAO')),
    CONSTRAINT ck_account_identity_subject CHECK (length(trim(subject)) BETWEEN 1 AND 255)
);

CREATE INDEX ix_account_identity_user ON account_identities(user_id);

ALTER TABLE participants
    ALTER COLUMN guest_session_id DROP NOT NULL,
    ADD COLUMN user_id UUID REFERENCES account_users(id),
    ADD CONSTRAINT ck_participant_exclusive_owner CHECK (
        (guest_session_id IS NOT NULL AND user_id IS NULL)
        OR (guest_session_id IS NULL AND user_id IS NOT NULL)
    ),
    ADD CONSTRAINT uq_participant_room_user UNIQUE (room_id, user_id);

CREATE INDEX ix_account_host_rooms ON participants(user_id, room_id) WHERE role = 'HOST';
