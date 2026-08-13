-- 지식 쓰기를 멱등하게 만든다(ARTEL-364).
--
-- 막는 것이 둘이고 성질이 달라서 장치도 둘이다.
--
--   1. 재전송 — 같은 논리적 쓰기가 두 번 도착한다. 프레임 messageId가 그대로 키다.
--   2. 같은 사실의 재기록 — 다른 messageId로 오는 진짜 새 호출이다. 프레임 키로는 안 잡히고
--      내용으로 판정해야 한다.
--
-- 1은 아래 원장 테이블이, 2는 knowledge.content_key가 맡는다.

--------------------------------------------------------------------------------
-- 1. qa_knowledge_write — 프레임 단위 멱등 원장
--------------------------------------------------------------------------------
-- **knowledge에 컬럼으로 달 수 없어서 별도 테이블이다.** 이유가 셋이다.
--   - knowledge에는 qa_try_id가 없다. source_id는 "만든 런"이고 DOCS 출처에서는 뜻이 아예 다르다.
--   - 운영 런의 UPDATE·DELETE는 새 행을 만들지 않고 기존 행을 고친다. 컬럼을 둔다면 한 행에 여러
--     프레임의 이력이 겹쳐 마지막 것만 남는다.
--   - LINK·UNLINK는 knowledge_edge에 쓴다. 컬럼 방식이면 같은 규칙을 두 테이블에 나눠 달게 된다.
-- 원장 하나면 다섯 타입이 한 규칙을 진다.
--
-- issue의 uk_issue_message(V12)와 같은 발상이고, 다른 점은 **삽입 시점**이다. 저쪽은 이슈 행 자신에
-- message_id가 있어 삽입 충돌이 곧 중복 차단이지만, 여기서는 쓰기가 먼저 일어나므로 원장 삽입이
-- 쓰기와 같은 트랜잭션이어야 한다. 밖에 두면 충돌을 알았을 때는 중복이 이미 만들어진 뒤다.
CREATE TABLE IF NOT EXISTS qa_knowledge_write (
    id           BIGSERIAL PRIMARY KEY,
    qa_try_id    BIGINT NOT NULL,
    -- Agent 프레임의 messageId. 라우터가 UUID 검증을 통과한 프레임만 넘기므로 실제로는 항상 UUID다.
    message_id   VARCHAR(255) NOT NULL,
    -- 무엇을 한 프레임인지. 재전송에 답할 때 응답 payload의 type으로 그대로 나간다.
    type         VARCHAR(32) NOT NULL CHECK (type IN (
        'KNOWLEDGE_CREATE', 'KNOWLEDGE_UPDATE', 'KNOWLEDGE_DELETE',
        'KNOWLEDGE_LINK', 'KNOWLEDGE_UNLINK'
    )),
    -- 둘 중 하나만 찬다. 항목 쓰기는 knowledge_id, 관계 쓰기는 edge_id.
    -- 그 런의 스코프에서 사실을 지고 있는 행이다 — 스코프 런이 baseline을 고쳤으면 그림자다.
    knowledge_id BIGINT,
    edge_id      BIGINT,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_qa_knowledge_write_target CHECK (
        (knowledge_id IS NOT NULL AND edge_id IS NULL)
            OR (knowledge_id IS NULL AND edge_id IS NOT NULL)
    )
);

-- 멱등의 본체. 같은 런이 같은 messageId를 두 번 보내면 두 번째 삽입이 여기서 걸리고, 서비스가 그
-- 충돌을 값으로 흡수해 첫 번째가 남긴 id로 답한다(IssueService가 이미 그 모양이다).
CREATE UNIQUE INDEX IF NOT EXISTS uk_qa_knowledge_write_message
    ON qa_knowledge_write (qa_try_id, message_id);

--------------------------------------------------------------------------------
-- 2. knowledge.content_key — 같은 사실의 재기록 backstop
--------------------------------------------------------------------------------
-- content_hash를 재사용하지 않는다. V13이 그 컬럼을 "멱등키 아님 — 저장만"이라고 명시했고, 지금
-- DOCS 경로가 업로드 파일 해시를 담는 데 쓴다. 의미를 겹치면 문서 인입과 QA 쓰기가 서로를 막는다.
ALTER TABLE knowledge ADD COLUMN IF NOT EXISTS content_key VARCHAR(64);

-- CREATE에만 채운다. UPDATE는 기존 행의 내용을 바꾸므로 키도 함께 바뀌어야 하는데, 그러면 이 인덱스가
-- "수정 결과가 다른 행과 같아졌다"까지 막게 되고 그것은 멱등이 아니라 병합이다. 그 판단은 별개다.
--
-- **NULLS NOT DISTINCT가 이 인덱스의 핵심이다.** 운영 스코프는 scope_id가 NULL인데, 기본 유니크
-- 인덱스는 NULL을 서로 다른 값으로 본다 — 그대로 두면 운영 행끼리는 절대 충돌하지 않아, 정확히
-- 막으려던 케이스(운영 런이 같은 사실을 두 번 기록)만 빠져나간다. 있으나 마나가 아니라 조용히
-- 틀리는 종류다. PostgreSQL 15+ 문법이고 배포·테스트 모두 pg16이다.
--
-- deleted_at을 뺀 것도 의도다. 지웠다 같은 사실을 다시 쓰는 것은 정당한 흐름이고(툼스톤을 남긴
-- 스코프 런이 그 뒤에 record하는 경우가 그렇다), 그것까지 막으면 지식을 되살릴 수 없다.
CREATE UNIQUE INDEX IF NOT EXISTS uk_knowledge_content_key
    ON knowledge (project_id, scope_id, content_key) NULLS NOT DISTINCT
    WHERE content_key IS NOT NULL AND deleted_at IS NULL;
