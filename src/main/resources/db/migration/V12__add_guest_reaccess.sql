ALTER TABLE participants ADD CONSTRAINT uq_participant_room_id UNIQUE (room_id, id);

CREATE TABLE guest_participant_passwords (
    participant_id UUID PRIMARY KEY REFERENCES participants(id) ON DELETE CASCADE,
    password_hash VARCHAR(128) NOT NULL,
    CONSTRAINT ck_guest_password_encoding CHECK (password_hash ~ '^pbkdf2-sha256\$600000\$[A-Za-z0-9_-]{22}\$[A-Za-z0-9_-]{43}$')
);

CREATE TABLE guest_reaccess_grants (
    room_id UUID NOT NULL,
    guest_session_id UUID NOT NULL REFERENCES guest_browser_sessions(id) ON DELETE CASCADE,
    participant_id UUID NOT NULL,
    level VARCHAR(24) NOT NULL CHECK (level IN ('VERIFIED_GUEST', 'REPLACE_ONLY')),
    expected_revision INTEGER NOT NULL CHECK (expected_revision >= 0),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (room_id, guest_session_id),
    FOREIGN KEY (room_id, participant_id) REFERENCES participants(room_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_guest_grant_expiry CHECK (expires_at > created_at)
);
