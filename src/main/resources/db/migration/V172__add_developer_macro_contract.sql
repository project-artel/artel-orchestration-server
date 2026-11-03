--------------------------------------------------------------------------------
-- V149. 개발자 macro 에 행동 계약을 둔다
--------------------------------------------------------------------------------
-- 계약은 `artel/docs/architecture/2026-11-01-pe-planner-guidance-only-design.md` 다.
--
-- planner–executer 엔진에서 행동의 대상·준비·효과를 런타임 planner 가 쓰지 않고, macro 를 등록하는 코딩 에이전트가
-- 함께 낸다. 이 칸은 agent-server 검사가 통과시킨 계약(JSON)을 그대로 담는다. 이 서버는 내용을 읽지 않는다.
--
-- NULL 이면 계약 없이 올라온 macro 다. 기존 행은 모두 NULL 이고, 계약을 읽지 않는 엔진은 예전처럼 쓴다.

ALTER TABLE developer_macro ADD COLUMN IF NOT EXISTS contract JSONB;
