-- 시작 값을 어느 build 에서 알게 됐는지(이름 → game_build id). 사람 답 · QA 관측으로 정해진 값만 들어간다.
-- 새 버전의 저작은 앞 버전에서 알게 된 시작 값을 버리지 않고 표시해 쓴다.
ALTER TABLE case_glossary ADD COLUMN start_seen JSONB NOT NULL DEFAULT '{}'::jsonb;
