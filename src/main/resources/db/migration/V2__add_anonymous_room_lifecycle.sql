ALTER TABLE meeting_rooms
    ADD COLUMN invite_code VARCHAR(22);

UPDATE meeting_rooms
SET invite_code = rtrim(
    translate(
        encode(decode(md5(id::text || ':meet-me-invite-v2'), 'hex'), 'base64'),
        '+/',
        '-_'
    ),
    '='
);

ALTER TABLE meeting_rooms
    ALTER COLUMN invite_code SET NOT NULL,
    ADD CONSTRAINT uq_meeting_room_invite_code UNIQUE (invite_code),
    ADD CONSTRAINT ck_meeting_room_invite_code
        CHECK (invite_code ~ '^[A-Za-z0-9_-]{22}$');

ALTER TABLE participants
    ADD COLUMN display_name VARCHAR(50);

UPDATE participants
SET display_name =
    CASE role
        WHEN 'HOST' THEN '주최자-'
        ELSE '참여자-'
    END || substr(replace(id::text, '-', ''), 1, 8);

ALTER TABLE participants
    ALTER COLUMN display_name SET NOT NULL,
    ADD CONSTRAINT ck_participant_display_name
        CHECK (length(trim(display_name)) BETWEEN 1 AND 50);
