-- knowledge.tag를 topic 축으로 세분화한다. (Phase 2 논의 결정 2026-07-28)
--
-- V13은 최소 집합(CONTROL/INFO/MISC)으로 시작했다. 소비자가 QA뿐 아니라 시나리오 작성·보고서·
-- Issue 작성까지 넓어지면서, 너무 넓던 INFO를 RULE(규칙/수치)과 OBJECTIVE(목표/성공조건)로 쪼갠다.
--   CONTROL   : 입력·조작 방식
--   RULE      : 시스템·규칙·수치·제약 ("게임이 어떻게 굴러가나")
--   OBJECTIVE : 목표·성공/실패 조건·진행 ("무엇이 일어나야 하나" — QA 판정/Issue 기대동작의 핵심)
--   UI        : 화면·HUD·메뉴 요소
--   MISC      : 기타 fallback
--
-- tag는 여전히 **단일축(topic) enum, 항목당 1개**. 쓰임새(purpose)는 문서가 아니라 질의에서 매핑한다.
-- 확장이 더 필요하면 값을 늘리기보다 직교 facet 컬럼을 추가한다.
--
-- ⚠️ V13은 이미 적용된 마이그레이션이라 수정 금지(체크섬). CHECK 제약만 교체하는 새 버전으로 넓힌다.
-- 인라인 컬럼 CHECK는 Postgres가 <table>_<column>_check 규칙으로 자동 명명한다(knowledge_tag_check).
-- INFO를 쓰던 기존 행은 없다(Phase 1 직후라 실데이터 없음). 있었다면 사전 UPDATE가 필요하다.

ALTER TABLE knowledge DROP CONSTRAINT IF EXISTS knowledge_tag_check;
ALTER TABLE knowledge ADD CONSTRAINT knowledge_tag_check
    CHECK (tag IN ('CONTROL', 'RULE', 'OBJECTIVE', 'UI', 'MISC'));
