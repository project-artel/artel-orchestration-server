-- QA 관측으로 쌓은 화면 출구(어느 씬에서 무엇을 하면 어느 씬으로). 지도가 없는 프로젝트의 저작이 읽는다.
ALTER TABLE case_glossary ADD COLUMN exits JSONB NOT NULL DEFAULT '[]'::jsonb;
