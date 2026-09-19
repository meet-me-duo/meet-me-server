ALTER TABLE meeting_rooms
    DROP CONSTRAINT ck_room_duration,
    DROP COLUMN duration_minutes;
