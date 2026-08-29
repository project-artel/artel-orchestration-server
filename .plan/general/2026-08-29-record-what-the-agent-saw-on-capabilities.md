# 2026-08-29 — agent 가 본 것을 capability 에 적는 경로를 연다

- Date: 2026-08-29
- Jira: ARTEL-644
- Status: Draft

## Goal

QA agent 가 런 도중에 capability 하나에 대해 본 것을 적을 수 있게 한다. 셋이다.

1. 이 capability 가 되는 것을 봤다 → `verification = confirmed`
2. 안 되더라 → `verification = contradicted`
3. `evidence` 에 없던 capability 를 찾았다 → `origin = observed` 또는 `inferred` 로 새 행

frame 을 정하고 문서로 남긴다. ARTEL-645 가 그 문서를 읽고 agent 쪽 tool 을 만든다.

## Non-goals

- agent 쪽 tool (ARTEL-645)
- `observation` 이 쌓인 뒤 verification 을 자동으로 올리는 규칙 — agent 가 말한 것을 그대로 적는 데까지다
- `evidence` 와 `observation` 이 어긋날 때의 처리 (ARTEL-646)
- `evidence` 에 없는 **`scene`** 을 agent 가 만드는 것. 모르는 `scene` 은 거절한다
- `screen_capability` 갱신. `observation` 행의 `screen_id` 까지만 적는다

## Context / Constraints

실측: capability 472 개 중 `verification = confirmed` 가 1 개. `interaction = none` 이 418 개
(89 퍼센트) 라 누를 수 있는 것은 51 개뿐이고, action 전후 `pulse` 를 비교하는 ARTEL-450 방식으로는
나머지 421 개를 영원히 못 본다. 그 421 개는 일어나는 일이고, 일어났는지는 `screen` 을 본 쪽이 안다.

- **verdict 만 받지 않는다.** 무엇을 보고 그렇게 말했는지(`rationale`)를 같이 받아 저장한다.
  캡처가 있으면 그 id 도 받는다.
- **agent 가 정적 분석을 덮지 않는다.** `origin = evidence` 행에 쓰는 UPDATE 는 `verification` 과
  그것을 되짚는 포인터 두 칸뿐이고, 그 문장 하나 말고는 이 경로에 UPDATE 가 없다.
- **런이 끝나기 전에 쓴다.** frame 하나가 한 트랜잭션이라 런이 중간에 끊겨도 그때까지 배운 것이 남는다.
- **멱등.** 같은 문장을 두 번 보내도 행이 두 개가 되지 않는다. DB 유니크가 강제한다.
- **거절은 답이 온다.** 조용히 버리지 않는다.

기존 frame 등록 방식을 따른다. `QaAgentInboundRouter.SUPPORTED_TYPES` 에 인입 타입을 더하고, 성공은
`KNOWLEDGE_WRITE_RESULT` 와 같은 모양의 결과 frame, 거절은 요청의 correlation 을 문 `ERROR`
frame 이다(ARTEL-331 이 정한 계약).

## Approach (Checklist)

- [ ] **Step 0: Recon** — `QaAgentInboundRouter` 의 쓰기 경로(`routeKnowledgeMutation` ·
      `answerWrite` · `rejectWrite`), `docs/screen-selector-frames.md` 의 문서 모양,
      `capability` · `capability_observation` · `capability_inference` 의 제약
- [ ] **Step 1: 마이그레이션 V71**
  - `capability_observation` 에 agent 의 문장이 앉을 자리 — `source` · `verdict` · `rationale` ·
    `capture_id` · `qa_try_id` · `agent_message_id`
  - `action_method` · `fired` 의 NOT NULL 해제 + `source = 'pulse-diff'` 행에는 그대로 요구하는 CHECK
  - `uk_capability_observation_agent_statement (qa_run_id, capability_id, verdict)` — agent 행만
  - `capability.verification_observation_id` — 이 verification 을 만든 문장으로 되짚는 포인터
  - `uk_capability_agent_statement` — agent 가 적은 행의 멱등 키
  - `v_content_map_capability` 에 `verification_observation_id` 를 싣는다
- [ ] **Step 2: frame 과 서비스**
  - `contentmap/observe/CapabilityWriteFrames.kt` — frame 타입 이름 상수와 payload DTO
  - `contentmap/observe/AgentCapabilityWriteService.kt` — 해석·검증·쓰기, 결과는 sealed
  - `QaAgentInboundRouter` — 두 인입 타입을 라우팅하고 답한다
  - 리포지토리 — 좁은 UPDATE 하나, 멱등 조회 둘
- [ ] **Step 3: 문서** — `docs/capability-write-frames.md`
- [ ] **Step 4: 테스트** — frame 별 수용·거절, 멱등, `evidence` 행 불변, 실측 사본 대조

## Validation

- **Commands to run:** `./mvnw test`
- **Expected output:** 신규 테스트 전부 통과. 전체 실패 수는 ARTEL-661 때문에 실행 순서 의존이라
  기준선이 아니다 — base 를 직접 재고 두 수를 함께 적는다.
- **실측 사본:** `artel_integration` 의 `pg_dump` 사본에 agent 의 문장 10 개를 넣고, 그 10 개만
  바뀌고 나머지 462 개가 그대로인지 본다.

## Risks & Rollback

- **Risks:**
  - `capability_observation` 의 NOT NULL 을 푸는 것이 ARTEL-450 이 되살아났을 때의 보장을 약하게
    한다 → `source = 'pulse-diff'` 행에 같은 제약을 CHECK 로 다시 건다
  - `origin = observed` 행의 멱등 키가 `summary` 문자열이라, 같은 것을 다른 말로 적으면 행이
    둘이 된다 → 재전송(흔한 경우)은 흡수되고 말이 달라지는 것은 ARTEL-646 이 합친다
- **Rollback steps:** 마이그레이션은 컬럼·인덱스 추가와 NOT NULL 해제뿐이라 되돌려도 기존 행이
  깨지지 않는다. 코드는 `git revert`.

## Open Questions

- 없음
