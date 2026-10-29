-- 테스트 런 맵(ARTEL-1244). 런마다 하나: TS 프레임 배치(자리 · 접힘)와 메모 · 글 · 도형 · 그림 주석.
-- TS 의 스텝 · TC 는 여기 두지 않는다. 그건 test_scenario 의 몫이다.
-- version 은 나중 저장을 막는 데 쓴다: 읽은 version 과 다르면 저장하지 않는다.
CREATE TABLE test_run_map (
    test_run_id BIGINT      PRIMARY KEY REFERENCES test_run (id) ON DELETE CASCADE,
    layout      JSONB       NOT NULL DEFAULT '{}',
    annotations JSONB       NOT NULL DEFAULT '[]',
    version     INT         NOT NULL DEFAULT 0,
    updated_by  BIGINT,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
