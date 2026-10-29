-- Post-deployment metadata only. Execute once in the approved runtime DB after parent coordination.
-- No participant/meeting data is read. This script does not build, repair or modify any index.
WITH expected(name) AS (
    VALUES ('ix_account_rooms'), ('ix_account_host_rooms'), ('uq_room_host'), ('uq_participant_room_user')
)
SELECT e.name AS expected_index,
       i.indexrelid IS NOT NULL AS present_on_participants,
       i.indisvalid, i.indisready, i.indislive, i.indisunique,
       pg_get_indexdef(i.indexrelid) AS index_definition,
       pg_get_expr(i.indpred, i.indrelid) AS predicate
FROM expected e
LEFT JOIN pg_class c
       ON c.relname = e.name AND c.relnamespace = 'public'::regnamespace AND c.relkind = 'i'
LEFT JOIN pg_index i
       ON i.indexrelid = c.oid AND i.indrelid = 'public.participants'::regclass
ORDER BY e.name;

SELECT version, script, checksum, success
FROM public.flyway_schema_history
WHERE version = '20'
ORDER BY installed_rank;
