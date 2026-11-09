-- Explicit C5 selection only. Existing kinds, fingerprints and data remain unchanged.
ALTER TABLE final_analysis_routes DROP CONSTRAINT ck_final_analysis_route_shape;
ALTER TABLE final_analysis_routes ADD CONSTRAINT ck_final_analysis_route_shape CHECK (
    route_kind IN ('LEGACY','DSL_CONTEXT_V2','DSL_APPROVED_TERMS_V3','DSL_CONTEXT_MODE_V4','DSL_STRUCTURAL_V5')
    AND format_version > 0
    AND route_fingerprint ~ '^[a-f0-9]{64}$'
    AND jsonb_typeof(selection) = 'object'
    AND (route_kind NOT IN ('DSL_APPROVED_TERMS_V3','DSL_CONTEXT_MODE_V4','DSL_STRUCTURAL_V5') OR (
        selection ?& ARRAY['identity','policy','context','meetingMode','vocabularyVersion']
        AND selection - ARRAY['identity','policy','context','meetingMode','vocabularyVersion'] = '{}'::jsonb
    ))
);
