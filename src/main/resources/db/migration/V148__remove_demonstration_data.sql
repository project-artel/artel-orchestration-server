-- 삭제할 storage key를 먼저 보존한다. 실패한 객체 삭제는 서버가 다시 시도한다.
CREATE TABLE retired_demonstration_object (
    object_key TEXT NOT NULL,
    version_id TEXT NOT NULL DEFAULT '',
    deleted_at TIMESTAMPTZ,
    last_attempted_at TIMESTAMPTZ,
    PRIMARY KEY (object_key, version_id),
    CHECK (object_key LIKE 'demonstrations/%')
);
INSERT INTO retired_demonstration_object(object_key)
SELECT DISTINCT payload_json::jsonb->>'objectKey'
FROM demonstration_event
WHERE kind IN ('EVIDENCE', 'PULSE')
  AND payload_json::jsonb->>'objectKey' LIKE 'demonstrations/%'
ON CONFLICT DO NOTHING;

-- snapshot의 다른 QA 정보는 유지한다.
CREATE FUNCTION remove_retired_demonstration_fields(value JSONB) RETURNS JSONB
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE result JSONB;
BEGIN
    IF jsonb_typeof(value) = 'object' THEN
        SELECT COALESCE(jsonb_object_agg(key, remove_retired_demonstration_fields(val)), '{}'::jsonb)
          INTO result FROM jsonb_each(value) AS item(key,val)
          WHERE key NOT IN ('verification_procedure', 'verificationProcedure');
        RETURN result;
    ELSIF jsonb_typeof(value) = 'array' THEN
        SELECT COALESCE(jsonb_agg(remove_retired_demonstration_fields(val) ORDER BY position), '[]'::jsonb)
          INTO result FROM jsonb_array_elements(value) WITH ORDINALITY AS item(val,position);
        RETURN result;
    END IF;
    RETURN value;
END $$;
DO $$
DECLARE target RECORD;
BEGIN
    FOR target IN SELECT table_name,column_name FROM information_schema.columns
        WHERE table_schema='public' AND data_type='jsonb'
          AND (table_name LIKE 'qa\_%' ESCAPE '\' OR table_name IN ('test_scenario','test_scenario_draft'))
    LOOP
        EXECUTE format('UPDATE %I SET %I=remove_retired_demonstration_fields(%I) WHERE %I::text LIKE ''%%verification_procedure%%'' OR %I::text LIKE ''%%verificationProcedure%%''',
            target.table_name,target.column_name,target.column_name,target.column_name,target.column_name);
    END LOOP;
END $$;

DELETE FROM knowledge_embedding WHERE knowledge_id IN (SELECT id FROM knowledge WHERE source='DEMONSTRATION');
DELETE FROM knowledge_usage WHERE knowledge_id IN (SELECT id FROM knowledge WHERE source='DEMONSTRATION');
DELETE FROM knowledge_event WHERE knowledge_id IN (SELECT id FROM knowledge WHERE source='DEMONSTRATION');
DELETE FROM knowledge_anchor WHERE knowledge_id IN (SELECT id FROM knowledge WHERE source='DEMONSTRATION');
DELETE FROM knowledge_edge WHERE from_knowledge_id IN (SELECT id FROM knowledge WHERE source='DEMONSTRATION')
    OR to_knowledge_id IN (SELECT id FROM knowledge WHERE source='DEMONSTRATION');
DELETE FROM knowledge WHERE source='DEMONSTRATION';
ALTER TABLE knowledge DROP CONSTRAINT knowledge_source_check;
ALTER TABLE knowledge ADD CONSTRAINT knowledge_source_check CHECK (source IN ('DOCS','QA'));

DROP TABLE qa_procedure_usage;
DROP TABLE verification_procedure;
DROP TABLE demonstration_source_revision;
DROP TABLE demonstration_event;
DROP TABLE demonstration_session;
