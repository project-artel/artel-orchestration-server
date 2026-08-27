# 2026-08-27 — LEADS_TO 새 간선을 거절하고 기존 간선은 남긴다

- Date: 2026-08-27
- Jira: ARTEL-594
- Status: Draft

## Goal

`LEADS_TO` 를 **읽기 전용**으로 만든다. 화면 지도의 소유는 `content_map` 의
`screen_transition` 과 `scene_edge` 로 넘어갔고, 그쪽은 관측이 근거다. 지도가 둘인데 양쪽 다
쓰기를 받으면 영원히 갈라지므로 지식창고 쪽 사본을 얼린다.

- `KNOWLEDGE_LINK` 가 `LEADS_TO` 를 받으면 **사유를 담아** 거절한다.
- `KNOWLEDGE_UNLINK` 도 같은 이유로 거절한다.
- 이미 저장된 `LEADS_TO` 행은 검색·이웃·확장·그래프 조회에 그대로 나온다.
- `KnowledgeRelation` KDoc 이 얼린 사유와 대체처를 진다.

## Non-goals

- enum 값 삭제. 지우면 저장된 행이 역직렬화되지 않는다.
- DB CHECK 축소. 기존 행이 제약을 통과해야 한다.
- 기존 간선의 마이그레이션·삭제.
- 잘못 저장된 간선을 떼어내는 정리 경로. 필요하면 사람이 일회성으로 한다.

## Context / Constraints

- **거절은 예외가 아니라 값이다.** `KnowledgeGraphMutation.Rejected` 가 이미 그 자리이고,
  라우터가 그것을 `KNOWLEDGE_WRITE_RESULT` 계약의 거절 응답과 `ERROR` qa_log 로 바꾼다.
  새 `ResponseStatusException` 은 금지이고, `common/error` 타입 예외도 여기서는 틀린 도구다 —
  던지면 receive 파이프라인이 끊겨 프레임 하나가 QA 런 전체를 실패시킨다.
- **라우터는 안 고친다.** `routeKnowledgeGraph` 가 이미 `Rejected` 를
  `"${envelope.type} rejected: ${reason}"` 로 답한다. 거절은 서비스 층에 붙는다.
- **agent 쪽이 먼저 나갔다**(ARTEL-590). 쓰기 어휘에서 `LEADS_TO` 가 빠져 도구가 링크도
  언링크도 못 보낸다. 서버가 언링크를 계속 받아 주면 두 층의 규칙이 어긋난다.
- **언링크까지 막는 대가**: 잘못 저장된 경로 간선을 이 경로로는 못 뗀다. 남은 간선은 과거
  런이 알아낸 것의 기록으로만 읽히므로 받아들인다(2026-08-27 결정, 이슈 본문).
- `KnowledgeRelation.NAMES` 에서도 빼지 않는다. 빼면 `relation must be one of ...` 가
  `LEADS_TO` 를 **모르는 이름**이라고 말하게 되는데, 그것은 사실이 아니다 — 아는 이름이고
  쓰기만 막힌 것이다.

## Approach (Checklist)

- [ ] **Step 0: Recon** — 쓰기 경로(`KnowledgeGraphService.link`/`unlink`), 라우터의 거절
      배선, 기존 `LEADS_TO` 를 쓰는 테스트 전수 확인
- [ ] **Step 1: Implementation**
  - `knowledge/entity/KnowledgeRelation.kt` — KDoc 에 얼린 값 절을 넣고 `LEADS_TO` 항목을
    고쳐 쓴다
  - `knowledge/service/KnowledgeGraphService.kt` — `link`/`unlink` 가 relation 을 읽은
    직후 거절한다. 사유 문구는 companion 상수 둘
- [ ] **Step 2: Tests**
  - `KnowledgeEdgeIntegrationTest` — 링크 거절, 언링크 거절(기존 행 무변화), 나머지 네
    관계의 링크·언링크
  - `KnowledgeExpandRouterIntegrationTest` — 저장된 `LEADS_TO` 가 확장에 나온다(기존 테스트,
    이제 얼린 값의 읽기 증거라는 주석을 단다)
  - `KnowledgeVectorSearchIntegrationTest` — 저장된 `LEADS_TO` 가 검색 히트의 이웃으로 나온다
  - `KnowledgeGraphViewIntegrationTest` — 저장된 `LEADS_TO` 가 그래프 조회에 나온다.
    쓰기 경로로 `LEADS_TO` 를 만들던 두 호출은 다른 관계로 바꾼다
- [ ] **Step 3: Validation** — `./mvnw test -Dtest='Knowledge*,Qa*'` 를 실제 게이트로 돌린다
- [ ] **Step 4: Review** — 전체 diff 를 스코프 이탈 관점에서 훑는다

## Risks

- `KnowledgeGraphViewIntegrationTest` 는 ARTEL-605 작업자와 같은 영역이라 충돌 여지가 있다.
  손대는 범위를 최소로 둔다.
- 전체 스위트에는 이 작업과 무관한 기존 실패가 둘 있다(`OpenApiDocumentationIntegrationTest`
  의 5s blocking-read 타임아웃, `TestScenarioReconcileIntegrationTest` 의 부하 flake).

## Rollback

서비스 층의 거절 두 줄과 KDoc 을 되돌리면 끝이다. 스키마도 데이터도 건드리지 않는다.
