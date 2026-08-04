-- LLM 호출 한 건의 사용량·비용 기록(ARTEL-233).
--
-- **왜 orchestration에 있나.** 실제 provider 호출은 전부 agent-server(Python)에서 일어나지만,
-- 그쪽 저장소는 TTL 1시간짜리 Redis뿐이라 월말에 "이번 달 얼마 썼나"를 물을 곳이 없다. 영속
-- 저장소(PostgreSQL)를 가진 쪽은 orchestration 하나뿐이므로, 테이블과 수집 엔드포인트
-- (`POST /api/orchestration/llm-usage`)를 여기에 두고 agent가 배치로 밀어 넣는다.
--
-- **왜 FK를 걸지 않나.** reference_id는 service 값에 따라 서로 다른 테이블을 가리키는 다형
-- 참조다: QA_RUN→qa_try.id, SCENARIO→test_scenario.id, GAME_CONTEXT→project_document.id,
-- KNOWLEDGE_QUERY/EMBEDDING→project.id. 대상 테이블이 하나가 아니라 FK를 걸 수 없고, 걸 수
-- 있더라도 걸지 않는다 — 지출 기록은 회계 사실이라 원본 행이 지워져도 남아야 한다(qa_try를
-- 지웠다고 그 달에 쓴 돈이 사라지지는 않는다). 그래서 reference_id는 nullable이고, 대상이
-- 사라진 기록은 "무엇에 썼는지 모르는 지출"로 그대로 남는다. 집계는 service 단위로도 성립한다.
--
-- **called_at vs created_at.** called_at은 agent가 provider를 실제로 호출한 시각이고,
-- created_at은 우리가 그 배치를 받아 저장한 수신 시각이다. agent는 배치로 모아 보내고 실패하면
-- 다음 배치에 묻어 오므로 둘은 분 단위로 벌어질 수 있고, 월말·월초 경계에서는 날짜가 바뀐다.
-- **월별/일별 집계는 반드시 called_at 기준으로 한다** — 돈이 나간 시점은 호출 시점이지 우리가
-- 그 사실을 알게 된 시점이 아니다. created_at은 수집 파이프라인 지연을 보는 감사 컬럼으로만 쓴다.
--
-- **왜 유니크 제약이 없나.** 이 계약에는 멱등키가 없다. 보내는 쪽(agent)이 전송 실패를
-- 재시도하지 않기 때문이다 — 사용량 기록은 유실돼도 서비스가 멈추지 않는 부가 데이터라,
-- 재시도 큐를 붙이는 비용보다 몇 건 비는 편이 싸다는 판단이다. 재시도가 없으면 중복도 없으므로
-- 막을 것이 없고, 유니크 인덱스는 배치 INSERT를 느리게 만들기만 한다. 나중에 보내는 쪽이
-- 재시도를 갖게 되면 그때 멱등키 컬럼과 부분 유니크 인덱스를 함께 추가한다.
--
-- **토큰 컬럼.** cached_input_tokens/reasoning_tokens는 provider·모델마다 없을 수 있어 요청에서
-- 생략 가능하고 기본 0이다(nullable로 두면 SUM 앞에 COALESCE가 계속 붙는다). output_tokens는
-- service='EMBEDDING'에서 **항상 0**이다 — 임베딩은 텍스트를 벡터로 바꿀 뿐 토큰을 생성하지
-- 않는다. 그래서 "출력 토큰이 0인 행"은 이상 데이터가 아니며, 출력 토큰 기준으로 필터링하면
-- 임베딩 지출이 통째로 사라진다. cost_usd는 provider가 단가를 안 알려주는 경우가 있어 nullable로
-- 두고, NUMERIC(12,6)은 호출 한 건의 달러 비용(마이크로달러 단위)을 부동소수점 오차 없이 담는다.

CREATE TABLE IF NOT EXISTS llm_usage (
    id BIGSERIAL PRIMARY KEY,
    service VARCHAR(30) NOT NULL
        CHECK (service IN ('QA_RUN', 'SCENARIO', 'KNOWLEDGE_QUERY', 'GAME_CONTEXT', 'EMBEDDING')),
    reference_id BIGINT,
    provider VARCHAR(40) NOT NULL,
    model VARCHAR(80) NOT NULL,
    input_tokens INT NOT NULL,
    output_tokens INT NOT NULL,
    cached_input_tokens INT NOT NULL DEFAULT 0,
    reasoning_tokens INT NOT NULL DEFAULT 0,
    cost_usd NUMERIC(12, 6),
    latency_ms INT NOT NULL,
    called_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 기간 집계("이번 달 총 지출", "최근 7일 추이")를 받친다. 위에 적은 대로 기준 컬럼은 called_at이며
-- DESC인 것은 조회가 항상 최신 구간을 자르기 때문이다.
CREATE INDEX IF NOT EXISTS idx_llm_usage_called_at ON llm_usage (called_at DESC);

-- 대상별 집계("이 QA 실행 한 번에 얼마 썼나")를 받친다. reference_id 단독으로는 의미가 없고
-- (다형 참조라 service가 있어야 어느 테이블의 id인지 정해진다) service 선두라 service만으로
-- 거르는 조회("임베딩 지출 합계")도 같은 인덱스가 받는다.
CREATE INDEX IF NOT EXISTS idx_llm_usage_service_ref ON llm_usage (service, reference_id);
