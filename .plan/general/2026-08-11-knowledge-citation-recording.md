# 2026-08-11 — knowledge 인용을 기록해 검색된 지식과 실제로 쓰인 지식을 가른다

- Date: 2026-08-11
- Jira: ARTEL-293
- Status: Draft

## Goal

`knowledge_usage.cited`와 `.step`을 실제로 채우고, 기록의 **출처**를 가른다.

셋을 갈라야 한다. **검색됨**은 관측 가능하고 V27이 이미 기록한다. **읽고 고려됨**은
관측 불가이며 재려 하지 않는다. **행동에 반영됨**은 인용으로만 잡힌다 — 이 작업이
잡는 것은 세 번째다.

1. `retrieval_kind` — usage 행이 어느 경로로 나갔는지(DIRECT / SEARCH_NEIGHBOR / EXPAND)
2. `step` — 검색이 몇 번째 스텝에서 났는지(Agent가 payload에 실어 준다)
3. `cited = true` — 스텝 판정 STATUS의 `used_knowledge_ids`
4. `cited = false` — qa_try 종료 시 미인용 행 확정

## Non-goals

- `knowledge_entry_facts` view 수정. `citation_count` / `citation_known_count`가 이미
  `cited`를 읽으므로 이 작업이 끝나면 view는 손대지 않고도 채워진다.
  (`retrieval_kind`별 분해가 필요해지면 그것은 별건이다.)
- `knowledge.replaces_id` 계보.
- 축별 롤업 질의(`KnowledgeStatsRepository`), 대시보드 UI.
- qa_try 점수, 실험 엔티티.
- 검색·그래프·임베딩 **동작** 변경. 검색 결과가 이 작업 전후로 달라지면 안 된다.

## Context / Constraints

### 왜 `retrieval_kind`가 필요한가

usage 행이 지금 세 출처로 섞여 들어오는데 뒤의 둘이 `rank IS NULL`로 뭉개진다.

| 출처 | rank | 신호 |
| --- | --- | --- |
| 질의에 직접 걸린 벡터 히트 | 1..N | 안 쓰면 검색이 빗나간 것 |
| 히트에 딸려온 1홉 이웃 | null | **밀어넣은 것.** 안 쓰는 것이 정상 |
| `expand_knowledge`로 직접 요청한 이웃 | null | 요청해 놓고 안 쓴 것 — 강한 부정 신호 |

인용률의 분모가 이 구분에 달려 있다. **`rank`로 유추하지 않는다** — 지금은 rank null이
이웃과 일치하지만 새 검색 경로가 생기면 그 유추는 조용히 틀린다. 값은 만드는 자리에서 싣는다.

### 지켜야 할 경계

- `recordRetrievals`는 공용 싱크다. 거기서 종류를 추측하면 안 된다 —
  `KnowledgeRetrieval`에 필드를 얹어 세 구성 지점이 각각 채운다.
- **cited 매칭은 런 스코프다**: 같은 `qa_try_id`, 같은 `knowledge_id`, **판정 시점 이전에
  검색된 행**. `step`은 조인 키가 아니라 기록되는 메타데이터다 — 2번 스텝에서 검색한 것을
  3번 스텝에서 인용하는 것은 정상 동작이고, step을 키로 삼으면 그 인용이 증발한다.
  `case_id`도 키가 아니다(`QaStep.case_id`는 nullable).
- **경계는 qa_try이지 WS 세션이 아니다.** QA_Run 재설계로 세션 하나가 여러 시나리오를 순차
  실행하고 qa_try는 시나리오당이다. 세션 종료에 걸면 앞선 시나리오들의 확정이 늦거나 누락된다.
- 종료 경로가 하나가 아니다 — COMPLETED / FAILED / CANCELLED, 그리고 소켓 사망으로 실패
  처리되는 경로. 정상 종료에만 걸면 실패한 런은 영영 NULL로 남는다.
- **cited 갱신 실패는 삼키고 감사 로그만 남긴다.** `QaAgentInboundRouter`는 프레임 처리 중
  예외가 WebSocket 수신 체인 밖으로 나가면 소켓이 닫히고 런 전체가 실패한다. 기록이 런을
  죽이면 안 된다(V27의 검색 로깅이 같은 규칙을 따른다).
- `knowledge_mode=off`인 런은 usage 행 자체가 없다. 확정이 그 런에서 아무것도 하지 않고
  정상 종료해야 한다.
- 스코프 런(`qa_try.knowledge_scope_id` 있음)도 usage 행을 남긴다. view는 운영 스코프만
  담지만 cited 기록은 스코프와 무관하게 동작해야 한다.

### 설계 판단

**CHECK 제약을 걸지 않는다.** 리포는 값 강제에 VARCHAR + CHECK를 쓰지만(`qa_try.status`,
`knowledge.tag`, `knowledge_event.event`), 그것들은 전부 **Agent가 보낸 값이 흘러드는**
컬럼이다. `retrieval_kind`는 서버가 코드에서 만드는 값이고 wire에서 오지 않는다 — 오타는
Kotlin enum이 컴파일에서 잡는다. 반대로 후속 검색 경로가 늘면 값도 늘어, CHECK가 있으면 새
경로마다 마이그레이션이 코드 변경에 묶인다.

**"인용을 보고할 수 있었던 런"은 기록으로 가른다.** Agent가 세션 개설 응답의 `run_config`에
`citation_reporting: true`를 실어 주고, Orchestration은 그것을 그대로 `qa_try.run_config`에
저장한다(V25 주석 — 요청값이 아니라 해석값을 저장한다). 확정 질의는
`run_config ->> 'citation_reporting' = 'true'`를 술어로 쓴다. 값이 없으면 NULL로 남는다.
`prompt_version` 문자열 비교로 가르지 않는 이유는 그 비교가 버전 체계에 종속되고, 값이
NULL인 런(설정을 보고하지 않은 Agent)에서 판정이 불가능하기 때문이다.

**4번(표시)을 5번(확정)보다 먼저 만든다.** 확정만 있고 표시가 없으면 전 행이 false가 되어
데이터가 망가진다.

## Approach (Checklist)

- [ ] **Step 0: Recon** — V27/V28 주석, `KnowledgeSearchService`, `KnowledgeGraphService.expand`,
      `KnowledgeUsageEntity/Repository`, `QaAgentInboundRouter.routeStatus`,
      `QaExecutionFailureService`, `QaTryService.failRun/cancelRun`
- [ ] **Step 1: 마이그레이션** — `V32__add_knowledge_usage_citation.sql`
      - `ALTER TABLE knowledge_usage ADD COLUMN IF NOT EXISTS retrieval_kind VARCHAR(20)`
      - nullable, 기본값 없음, 백필 없음. 기존 행은 출처를 **모르는** 것이지 DIRECT가 아니다.
      - `step` / `cited` 컬럼 COMMENT 갱신(V27이 "이번 범위에서는 항상 NULL"이라 적어 둔 것을
        지금 채운다는 사실로 고친다).
      - 번호는 `./scripts/check-flyway-migrations.sh`로 확정(ARTEL-262 절차).
- [ ] **Step 2: retrieval_kind 배선**
      - `KnowledgeRetrievalKind` enum (DIRECT / SEARCH_NEIGHBOR / EXPAND)
      - `KnowledgeRetrieval`에 `kind` + `step` 필드
      - `KnowledgeSearchService.search` 직접 히트 → DIRECT
      - `KnowledgeSearchService.expandHits` → SEARCH_NEIGHBOR
      - `KnowledgeSearchService.expand`(툴 경로) → EXPAND
      - `KnowledgeGraphService.expand(kind = ...)` 파라미터로 받는다 — 두 호출자가 서로 다른
        종류를 만들고, 그래프 서비스는 자기가 왜 불렸는지 알 수 없다.
      - `recordRetrievals`는 실린 값을 그대로 쓴다(추측 금지).
- [ ] **Step 3: step 기록** — `KnowledgeSearchRequest.step` / `KnowledgeExpandRequest.step`을
      받아 서비스로 넘기고 usage 행에 남긴다. Agent가 안 보내면 null(지금과 같다).
- [ ] **Step 4: cited = true** — `routeStatus`의 스텝 판정 분기(`resolved == null`)에서
      `payload.used_knowledge_ids`를 읽어 런 스코프로 표시. 실패는 삼키고 ERROR 로그.
- [ ] **Step 5: cited = false 확정** — `KnowledgeCitationService.finalize*`를 종료 경로 전부에
      건다: `routeStatus` 종단, `QaExecutionFailureService`(sdkDisconnected / fail / cancelled),
      `QaTryService.failRun` / `cancelRun`(런 단위 bulk).
- [ ] **Step 6: 리포지토리 주석** — `KnowledgeUsageRepository`가 덧붙이기 전용이 아니게 된다.
      cited만 갱신되고 나머지는 불변이라는 것을 주석에 명시.
- [ ] **Step 7: 테스트**

## Validation

- **Commands to run:**
  - `./scripts/check-flyway-migrations.sh`
  - `./scripts/verify-flyway-upgrade.sh`
  - `./mvnw -q -Dtest=KnowledgeSearchRouterIntegrationTest,KnowledgeCitationIntegrationTest test`
  - `./mvnw clean test`
- **Expected output:** 전부 통과. 특히
  - 직접 히트 / 검색 이웃 / expand 이웃이 각각 맞는 `retrieval_kind`로 기록된다
  - 기존 행의 `retrieval_kind`는 NULL로 남는다
  - 인용된 항목의 `cited`가 true가 된다
  - glimpsed로만 본 이웃을 인용해도 통과한다(Orchestration은 id만 본다)
  - 앞선 스텝에서 검색한 것을 뒤 스텝에서 인용해도 찍힌다
  - 판정 이후에 검색된 행이 소급해서 true가 되지 않는다
  - qa_try 종료 시 미인용 행이 false로 확정된다(COMPLETED / FAILED / CANCELLED 각각)
  - 한 세션에 시나리오가 여럿일 때 각 qa_try가 자기 종료 시점에 확정된다
  - 인용을 보고하지 않는 구버전 런의 행은 NULL로 남는다
  - 스텝 판정 STATUS(result=null)가 여전히 런을 끝내지 않는다
  - cited 갱신 실패가 런을 죽이지 않는다

## Risks & Rollback

- **Risks:**
  - 인용은 **자기신고**다. `knowledge_event`(관측)와 성격이 다르고, 모델이 빠뜨리므로
    과소보고 방향으로 치우친다. 안전한 방향이지만 인용률로 모델을 줄 세울 때 "정직도" 차이가
    섞인다. 코드 주석과 PR 본문에 남긴다.
  - 한 항목이 한 런에서 여러 번 검색됐으면 그 행들이 **모두** true가 된다. 그래서 비율은 행
    단위가 아니라 **(런, 항목) 단위**로 세어야 한다 — 집계에서 distinct로 접어야 한다는 것을
    주석에 남긴다.
  - 종료 경로를 하나라도 빠뜨리면 그 런은 영영 NULL이다. 경로 목록을 테스트로 못박는다.
- **Rollback steps:** 컬럼은 순수 추가라 코드만 revert하면 기록이 멈춘다(스키마는 남아도
  무해하다). 이미 기록된 cited는 그대로 두는 편이 낫다 — 지운다고 되살아나지 않는다.

## Open Questions

- 없음. Agent 쪽 짝은 ARTEL-294이며, 배포 순서에 의존하지 않는다: Orchestration이 먼저 나가면
  `used_knowledge_ids`가 오지 않아 cited가 NULL로 남고, Agent가 먼저 나가면 필드가 무시된다.
