-- Extend explicit selection only. Existing LEGACY/C2 rows and fingerprints are unchanged.
ALTER TABLE final_analysis_routes DROP CONSTRAINT ck_final_analysis_route_shape;
ALTER TABLE final_analysis_routes ADD CONSTRAINT ck_final_analysis_route_shape CHECK (
    route_kind IN ('LEGACY','DSL_CONTEXT_V2','DSL_APPROVED_TERMS_V3')
    AND format_version > 0
    AND route_fingerprint ~ '^[a-f0-9]{64}$'
    AND jsonb_typeof(selection) = 'object'
    AND (route_kind <> 'DSL_APPROVED_TERMS_V3' OR (
        selection ?& ARRAY['identity','policy','context','meetingMode','vocabularyVersion']
        AND selection - ARRAY['identity','policy','context','meetingMode','vocabularyVersion'] = '{}'::jsonb
    ))
);
