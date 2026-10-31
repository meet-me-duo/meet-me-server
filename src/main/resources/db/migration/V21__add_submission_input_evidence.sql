-- Existing room protocols and immutable submission versions retain their original meaning.
-- The V2 experiment is selected explicitly for new rooms by application configuration.
SET lock_timeout = '5s';
SET statement_timeout = '30s';

ALTER TABLE meeting_rooms DROP CONSTRAINT meeting_rooms_submission_protocol_check;
ALTER TABLE meeting_rooms ADD CONSTRAINT meeting_rooms_submission_protocol_check
    CHECK (submission_protocol IN ('LEGACY', 'PRE_SUBMIT_V1', 'PRE_SUBMIT_V2'));

ALTER TABLE precheck_checks DROP CONSTRAINT precheck_checks_status_check;
ALTER TABLE precheck_checks ADD CONSTRAINT precheck_checks_status_check
    CHECK (status IN ('QUEUED','CHECKING','NEEDS_CHOICE','NEEDS_EDIT','CLEAR','RECEIVED','UNRESOLVED',
        'FAILED','FAILED_QUEUE','TIMED_OUT','LIMIT_REACHED','CANCELLED','SUPERSEDED','CLOSED_BEFORE_COMPLETION'));

ALTER TABLE precheck_candidates ADD COLUMN input_evidence JSONB
    CHECK (input_evidence IS NULL OR (jsonb_typeof(input_evidence) = 'object' AND octet_length(input_evidence::text) <= 16384));
ALTER TABLE submission_versions ADD COLUMN input_evidence JSONB
    CHECK (input_evidence IS NULL OR (jsonb_typeof(input_evidence) = 'object' AND octet_length(input_evidence::text) <= 16384));

RESET statement_timeout;
RESET lock_timeout;
