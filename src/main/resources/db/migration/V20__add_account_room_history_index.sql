-- Account equality excludes guest rows and supports both HOST and MEMBER history.
-- Keep the selective HOST index and all ownership/uniqueness constraints unchanged.
-- Flyway executes this migration in a transaction; do not leave waits or index work unbounded.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';
CREATE INDEX ix_account_rooms ON participants(user_id, room_id) WHERE user_id IS NOT NULL;
