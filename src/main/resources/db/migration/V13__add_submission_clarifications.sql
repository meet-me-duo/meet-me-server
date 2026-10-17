CREATE TABLE submission_clarifications (
    question_id UUID PRIMARY KEY,
    source_version_id UUID NOT NULL UNIQUE REFERENCES submission_versions(id) ON DELETE CASCADE,
    proposal JSONB NOT NULL CHECK (jsonb_typeof(proposal) = 'object'),
    created_at TIMESTAMPTZ NOT NULL,
    request_id UUID,
    revision_round_id UUID REFERENCES input_revision_rounds(id) ON DELETE CASCADE,
    expected_revision INTEGER,
    answer_choice VARCHAR(8) CHECK (answer_choice IN ('A', 'B', 'DIRECT', 'CANCEL')),
    direct_text TEXT,
    saved_version_id UUID REFERENCES submission_versions(id) ON DELETE CASCADE,
    response JSONB,
    answered_at TIMESTAMPTZ,
    CHECK (
        (request_id IS NULL AND revision_round_id IS NULL AND expected_revision IS NULL
         AND answer_choice IS NULL AND direct_text IS NULL AND saved_version_id IS NULL AND response IS NULL AND answered_at IS NULL)
        OR
        (request_id IS NOT NULL AND revision_round_id IS NOT NULL AND expected_revision > 0
         AND answer_choice IS NOT NULL AND response IS NOT NULL AND answered_at IS NOT NULL
         AND ((answer_choice = 'DIRECT' AND direct_text IS NOT NULL) OR (answer_choice <> 'DIRECT' AND direct_text IS NULL))
         AND ((answer_choice = 'CANCEL' AND saved_version_id IS NULL) OR (answer_choice <> 'CANCEL' AND saved_version_id IS NOT NULL)))
    )
);
CREATE INDEX ix_clarification_saved_version ON submission_clarifications(saved_version_id) WHERE saved_version_id IS NOT NULL;
