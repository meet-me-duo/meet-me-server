ALTER TABLE participants
    ADD COLUMN account_archive_hidden_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_participant_account_archive_hidden_owner CHECK (
        account_archive_hidden_at IS NULL OR user_id IS NOT NULL
    );
