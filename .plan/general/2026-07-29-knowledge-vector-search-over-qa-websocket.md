# 2026-07-29 — knowledge 벡터 검색과 QA WS 지식 질의 응답

- Date: 2026-07-29
- Jira: ARTEL-186
- Status: Implemented
- Base: `develop`

> 착수 시점에는 ARTEL-185 브랜치(`3cd1bd4`) 위에서 작업했다. 작업 중 185가 PR #58로 develop에
> **스쿼시 머지되고 원격 브랜치가 삭제**되어, `git rebase --onto origin/develop 3cd1bd4`로 옮겼다.
> 같은 시점에 develop에는 ARTEL-193(공통 예외 모듈, PR #57)도 들어와 있어 새 예외는 그 계층을 따른다.

## Goal

ARTEL-185가 채운 `knowledge_embedding` 벡터를 QA Agent가 실제로 꺼내 쓸 수 있게 한다.
Orchestration 쪽 절반(벡터 검색 질의 + WS 요청 처리)만 만든다.

## Non-goals

- Agent 쪽 도구(짝 이슈).
- BM25 하이브리드, RRF 융합, 재랭킹.
- CONTENT 벡터 검색(스키마에 자리만 있고 백필이 채우지 않는다).
- knowledge 개별 CRUD(ARTEL-188).

## Context / Constraints

- **HTTP를 새로 뚫지 않는다.** Agent → Orchestration 방향의 HTTP 경로가 없고, 뚫으면 base URL·인증·
  새 실패 모드가 전부 새로 생긴다. `QaAgentInboundRouter`에 이미 `KNOWLEDGE`(쓰기)가 있고
  `qaTryId → gameInstanceId → projectId` 해석도 그 자리에 있다. 읽기를 그 옆에 붙인다.
- **검색어 임베딩은 Agent에 맡긴다.** OpenRouter 키가 거기에만 있다. 검색 시점에
  `POST /embed`(ARTEL-184)를 부르는 쪽이 기존 호출 방향과 일치하고, 1536개 float가 qa_log에
  남지도 않는다.
- **라우터는 throw하지 않는다.** 프레임 하나가 throw하면 receive 파이프라인이 onError로 끊겨 WS가
  닫히고 try 전체가 fail 처리된다. 검증은 값으로 한다.
- **`CancellationException`은 삼키지 않는다**(develop `769c0b2`). suspend 호출을 감싸는 넓은
  `catch (Exception)`은 먼저 rethrow한다.

## Approach (Checklist)

- [x] **Step 0: Recon** — V18 스키마, `KnowledgeEmbeddingRepository`, `knowledge/agent/*`,
      `QaAgentInboundRouter`, agent-server `app/api/embeddings.py`(develop) 확인.
- [x] **Step 1: 검색 질의** — `KnowledgeVectorSearchRepository.searchNearest`.
      `knowledge_embedding ⋈ knowledge`, `project_id` 스코프, `deleted_at IS NULL`,
      `GROUP BY k.id` + `MIN(distance)`로 `knowledge_id` 접기.
- [x] **Step 2: 검색 서비스** — `KnowledgeSearchService`: `/embed`로 검색어 벡터화 → 질의 → 응답 매핑.
      결과 개수는 `artel.knowledge.search.max-limit`로 잘라 Agent 컨텍스트 증식을 막는다.
- [x] **Step 3: WS 타입** — `KNOWLEDGE_SEARCH`(인입) / `KNOWLEDGE_SEARCH_RESULT`(응답).
      실패는 `ERROR` 프레임으로 답한다. 성공 응답은 qa_log에 남기지 않는다.
- [x] **Step 4: 테스트** — 벡터 검색 통합 테스트(프로젝트 격리 / 접기 / 상한 / 빈 결과 / 필터 /
      소프트삭제)와 라우터 통합 테스트(잘못된 요청이 ERROR로 떨어지고 throw하지 않는지).

## 설계 결정

### 1. `knowledge_id`로 접는다 — `GROUP BY` + `MIN(distance)`

항목당 QUERY 벡터가 3개(ARTEL-184)라 같은 항목이 top-k에 여러 번 걸린다. 접지 않으면 top-10이
실질 top-3이 된다. 가장 가까운 거리를 그 항목의 점수로 삼는다.

애플리케이션에서 접지 않고 SQL에서 접는 이유는 `LIMIT`이 접은 **뒤에** 걸려야 하기 때문이다.
DB에서 30개를 받아 코드에서 접으면 몇 개가 남을지 알 수 없다.

### 2. 검색 model은 백필 설정을 그대로 읽는다

`artel.knowledge.search.model`을 따로 두지 않고 `artel.knowledge.backfill.model`을 읽는다.
두 값이 있으면 조용히 어긋나고, 그 증상은 오류가 아니라 **항상 빈 결과**다. 벡터를 쓴 쪽과 읽는 쪽이
같은 파티션을 봐야 한다는 것이 이 도메인의 사실이므로 설정도 하나여야 한다.

### 3. 빈 결과는 오류가 아니다

백필은 비동기라 방금 넣은 knowledge에 벡터가 없는 것이 정상이다. 벡터가 하나도 없거나 필터가
전부 걸러내면 `results: []`로 답한다. 오류로 답하면 Agent가 "지식창고가 고장났다"고 읽는다.

### 4. 성공 응답은 qa_log에 남기지 않는다

지식 본문이 타임라인에 통째로 실리면 안 된다(ARTEL-180이 막아 둔 컨텍스트 증식과 같은 문제).
쓰기 쪽 `KNOWLEDGE`도 같은 이유로 qa_log에 남기지 않으므로 대칭이다. 관측은
`KnowledgeSearchService`의 애플리케이션 로그(프로젝트·검색어 길이·결과 수)로 한다.
실패만 `ORCHE_INTERNAL`/`ERROR`로 타임라인에 남는다.

### 5. 실패는 ERROR 프레임 + qa_log 양쪽에

Agent 도구는 응답을 기다리고 있으므로 qa_log에만 남기면 도구가 매달린다. `correlationId`에
요청 `messageId`를 실어 `ERROR` 프레임을 돌려주고, 감사 흔적은 `ORCHE_INTERNAL`로 남긴다.
런은 실패시키지 않는다.

## Validation

- **Commands to run:**
  ```
  export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
  export TESTCONTAINERS_RYUK_DISABLED=true
  ./mvnw test
  ```
- **Expected output:** develop 기준선 160/160에서 줄지 않는다. 실제: **178/178**(신규 18건).

## Risks & Rollback

- **Risks:**
  - 검색은 WS receive 파이프라인(`concatMap`) 안에서 동기로 돈다. `/embed` 왕복 동안 그 런의
    다음 인입 프레임이 대기한다. 기존 `KNOWLEDGE` 저장·`ACTION` 디스패치도 같은 성질이라
    새로 생긴 제약은 아니지만, 임베딩 왕복은 그것들보다 길다.
  - 실제 Agent 서버와의 종단 연동은 검증하지 못했다(테스트는 `/embed` 대역을 쓴다).
  - ARTEL-188(PR #59)이 같은 라우터·서비스에 붙는다. 이 브랜치는 그 변경을 포함하지 않으므로
    머지 시점에 `QaAgentInboundRouter.kt` 충돌이 예상된다(양쪽 다 추가만 하므로 합집합이면 된다).
  - HNSW 인덱스가 없어 순차 스캔이다. V18 주석의 판단(프로젝트당 수백~수천 행)에 기댄다.
- **Rollback steps:** 마이그레이션이 없으므로 `git revert`로 끝난다. 스키마·데이터 되돌림 불필요.

## Open Questions

- `KNOWLEDGE_SEARCH` 요청의 `tag` 필터를 Agent가 단수로 보낼지 복수로 보낼지 짝 이슈와
  맞춰야 한다. 지금은 **둘 다** 받는다(`tags` 배열 + `tag` 스칼라).
