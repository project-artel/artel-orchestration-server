# 2026-08-13 — 지식 쓰기 프레임에 RESULT로 답한다

- Date: 2026-08-13
- Jira: ARTEL-331
- Status: Implemented

## Goal

지식 쓰기 프레임 다섯 개(KNOWLEDGE_CREATE / UPDATE / DELETE / LINK / UNLINK)가 성공하면 RESULT
프레임 하나로, 거절되면 요청의 correlation을 문 ERROR로 답하게 한다. CREATE의 RESULT는 만들어진
항목의 id를 싣는다.

지금은 셋 다 없다. `routeKnowledgeMutation`과 `routeKnowledgeGraph`는 서비스 결과를 거절 판정에만
쓰고, 성공은 조용하며, 거절은 운영자 타임라인의 ERROR 행으로만 남는다. 그래서 Agent 쪽에서는
거절이 성공으로 보이고(ARTEL-332), 런이 자기가 방금 만든 항목의 id를 모른다.

## Non-goals

- Agent 쪽 수신 처리와 툴 문구 — ARTEL-332.
- 배치 인입 `KNOWLEDGE`. 아래 결정 2를 볼 것.
- `ISSUE` 프레임. 같은 결함을 지지만 지식 쓰기가 아니다.
- ARTEL-317의 인덱싱 지연.
- 지식 스키마, 검색 랭킹, 임베딩 변경.

## Context / Constraints

### 이미 있는 것

왕복 계약은 이 파일 안에 이미 산다. `routeKnowledgeSearch`/`routeKnowledgeExpand`가
`sendToAgent`로 `*_RESULT`를, `failSearch`로 correlated ERROR를 보낸다. Agent의
`QaChannel.on_error`가 그 ERROR로 대기 중인 future를 푼다.

`sendToAgent`는 이미 전송 실패를 삼키고 ORCHE_INTERNAL 로그만 남긴다 — 그대로 쓸 수 있다.

**id도 이미 있다.** `KnowledgeMutation.Applied(knowledgeId)`와
`KnowledgeGraphMutation.Applied(edgeId)`가 이미 그것을 진다. 서비스 변경은 필요 없다.
스코프 런에서 `Applied`가 무엇을 지는지도 확인했다 — 그림자/툼스톤이 만들어지면 **그 행의 id**다
(`updateFromQaTry`, `softDeleteFromQaTry`). 즉 항상 "그 런에서 유효한 id"이고, Agent에 돌려줄
것으로 정확히 맞다. baseline id를 돌려주면 그 런에서 다시 지목할 수 없는 id를 주게 된다.

### 지켜야 할 것

- **실패를 새로 발명하지 않는다.** RESULT에 outcome 필드를 더하지 않는다. 성공=RESULT,
  거절=correlated ERROR.
- **예외가 receive 체인 밖으로 나가면 소켓이 닫히고 런이 죽는다.** 새 경로도 전부 값으로 처리한다.
- **쓰기는 검색의 순서를 그대로 쓸 수 없다.** 검색은 답할 세션이 없으면 일을 시작조차 하지 않지만,
  쓰기가 그러면 지식이 저장되지 않는다. 쓰기는 하고 답만 못 하는 쪽이 맞다.
- 프로젝트·스코프는 계속 런에서만 나온다.

### 새로 드러난 것 — `allowKnowledgeWrite`

`knowledge_mode`가 `learning`이 아닌 런은 쓰기 프레임을 게이트에서 거부한다. 그 함수의 주석이
지금 이렇게 말한다:

> 쓰기 프레임은 애초에 응답을 기다리지 않는 단방향이라 Agent에 따로 알릴 것도 없다.

이 변경으로 그 문장이 거짓이 된다. 여기서 답하지 않으면 `frozen`/`off` 런의 모든 쓰기가 Agent
쪽 타임아웃을 통째로 태운다 — 실험용 arm이 가장 느려지는, 눈에 잘 안 띄는 회귀다. 게이트 거부도
ERROR로 답해야 한다.

## 결정

### 결정 1 — RESULT 타입은 하나. `KNOWLEDGE_WRITE_RESULT`, 요청 타입을 echo한다.

payload:

```json
{ "type": "KNOWLEDGE_CREATE", "knowledge_id": "1024" }
{ "type": "KNOWLEDGE_LINK",   "edge_id": "77" }
```

- 다섯 타입의 응답이 id 필드 하나만 다르다. 타입을 다섯 개로 쪼개면 같은 모양을 다섯 번 적는다.
- 하나면 **다음 쓰기 타입이 계약을 자동으로 물려받는다.** 이 이슈의 Impact가 정확히 그것이다 —
  KNOWLEDGE_UPDATE와 LINK/UNLINK가 각각 "답이 있나 없나"를 다시 정했다.
- correlation이 messageId 기준이라 타입 하나로도 매칭에 문제가 없다.
- `KNOWLEDGE_SEARCH_RESULT`/`KNOWLEDGE_EXPAND_RESULT` 작명과 어긋나지만, 저 둘은 1:1 요청-응답이고
  이쪽은 다섯이 한 가족이다. echo한 `type`이 로그를 읽는 사람에게 무엇의 답인지 말해 준다.
- id는 문자열. 프로젝트 규칙이다(64비트 정밀도 손실 방지, `KnowledgeResponse` 주석).

### 결정 2 — 배치 인입 `KNOWLEDGE`는 이 계약에 넣지 않는다.

- Agent 쪽에 그것을 기다리는 호출부가 없다. 툴이 아니라 런 초기의 일괄 적재다.
- 응답 모양이 다르다 — id N개다. 하나를 싣는 위 payload에 억지로 맞추면 두 모양이 한 타입에 산다.
- 대신 **게이트 거부의 응답 대상에서도 빼야 한다.** `ANSWERED_WRITE_TYPES` 집합을 새로 두고,
  `KNOWLEDGE`는 거기 들지 않는다.

넣게 되는 날의 신호: Agent가 배치 인입 결과를 쓰게 될 때. 그때 payload를 `knowledge_ids` 배열로
확장하는 것이 자연스럽다.

### 답하지 않는 경로가 남는다 — 알고 남긴다

`handle`은 라우팅 **전에** 프레임을 버리는 자리가 셋 있다: `qa_try_id`가 숫자가 아님,
`messageId`가 UUID가 아님, `activeTry`가 없음(모르는 런이거나 이미 끝난 런). 여기서는 응답할
세션을 알아낼 수도 없고, 지금도 검색·확장이 똑같이 답 없이 버려진다.

이 이슈에서 고치지 않는다. 고치려면 라우팅 이전 단계가 세션을 해석해야 하고, 그 단계는 지금
"이 프레임이 이 런의 것인지"를 판정하는 곳이라 책임이 섞인다. 대신 **ARTEL-332가 무응답을
'실패'가 아니라 '모름'으로 다루는 것**이 이 구멍의 대응이다. 그쪽 제약에 이미 적혀 있다.

### 결정 3 — 성공 RESULT는 qa_log에 남기지 않는다.

`routeKnowledgeExpand`가 같은 판단을 한다. 변이 사실은 이미 `knowledge_event`에 남고, RESULT는
id만 진 파생물이다. 거절은 지금처럼 `appendError`로 남는다 — 그쪽은 사람이 봐야 하는 사실이다.

## Approach (Checklist)

- [x] **Step 0: Recon**
  - `QaAgentInboundRouter` — `handle`, `allowKnowledgeWrite`, `routeKnowledgeMutation`,
    `routeKnowledgeGraph`, `routeKnowledgeExpand`, `failSearch`, `sendToAgent`
  - `KnowledgeService` — `createFromQaTry` / `updateFromQaTry` / `softDeleteFromQaTry`가 지는 id
  - `KnowledgeGraphService.KnowledgeGraphMutation`
  - 테스트 대역: `KnowledgeSearchRouterIntegrationTest.RecordingAgentPort`

- [x] **Step 1: Implementation** (`QaAgentInboundRouter.kt` 한 파일)
  - `ANSWERED_WRITE_TYPES` 상수 추가 (= `KNOWLEDGE_MUTATION_TYPES + KNOWLEDGE_GRAPH_TYPES`)
  - `failSearch` → `answerWithError`로 이름 변경. 검색 전용이 아니게 되므로 이름이 거짓이 된다.
    호출부 두 곳(검색·확장)과 새 호출부가 같은 함수를 쓴다.
  - `answerWithError`/RESULT 전송을 **세션이 없으면 건너뛰는** 작은 헬퍼로 감싼다. 쓰기 경로는
    세션 없음이 정상이므로(위 제약) 그 분기가 라우팅 본문에 흩어지면 안 된다.
  - `allowKnowledgeWrite`: 거부 시 `envelope.type in ANSWERED_WRITE_TYPES`면 ERROR도 보낸다.
    주석의 "알릴 것도 없다"를 사실에 맞게 고친다.
  - `routeKnowledgeMutation`: `Applied` → RESULT(`knowledge_id`), `Rejected` → ERROR.
    파싱 실패와 예외도 ERROR로 답한다(지금은 `appendError`만).
  - `routeKnowledgeGraph`: 같은 처리, `edge_id`.

- [x] **Step 2: Tests**
  - 새 파일 `KnowledgeWriteResultRouterIntegrationTest` — `RecordingAgentPort`로 나간 프레임을 본다.
    - CREATE 성공 → `KNOWLEDGE_WRITE_RESULT`, `type=KNOWLEDGE_CREATE`, `knowledge_id`가 저장된 행
    - UPDATE/DELETE/LINK/UNLINK 성공 → 각 id 필드
    - 거절 4종(모르는 tag, 빈 summary, 없는 knowledge_id, 잘못된 relation) → ERROR + correlation
    - `frozen` 런의 쓰기 → ERROR로 답하고 저장은 안 된다
    - 스코프 런의 UPDATE → RESULT의 id가 **그림자 행**이다(baseline이 아니다)
    - 세션 없는 런 → 프레임은 안 나가고 저장은 된다
    - `sendFails=true` → 런이 RUNNING인 채 남는다
  - 기존 `KnowledgeMutationInboundIntegrationTest`는 세션 없이 시드하므로 그대로 통과해야 한다.
    그것이 곧 "구버전 Agent와 함께 돌아도 안 깨진다"의 회귀 방어다.

- [x] **Step 3: Rollout / Rollback**
  - 플래그 없음. 프레임을 더 보낼 뿐이고, 읽지 않는 Agent는 `deliver`가 `False`를 돌려주며 흘린다.
  - 되돌리기는 `git revert` 한 번. 스키마·마이그레이션 없음.

## Validation

빌드는 Maven이다(`pom.xml`, `mvnw`). 통합 테스트는 `PostgresTestContainer`가 스위트 시작 시
PostgreSQL 컨테이너를 띄워 Flyway로 스키마를 만든다 — H2가 아니라 실 DB여야 하는 이유는 JSONB와
pgvector다. Docker가 있어야 돈다(확인함: `docker info` OK, JDK 25).

- **Commands to run:**
  - `./mvnw test -Dtest=KnowledgeWriteResultRouterIntegrationTest`
  - `./mvnw test -Dtest=KnowledgeMutationInboundIntegrationTest`
  - `./mvnw test -Dtest='Knowledge*IntegrationTest'` (계약 공유 범위)
- **Expected output:** 전부 통과. 특히 기존 mutation 인입 테스트가 **수정 없이** 통과해야 한다 —
  고쳐야 통과한다면 단방향 계약을 깬 것이다.

## Risks & Rollback

- **Risks:**
  - `failSearch` 이름 변경이 diff를 넓힌다. 호출부 2곳이라 감당 가능하고, 이름이 거짓인 채 두는
    것이 더 나쁘다.
  - 게이트 거부에 ERROR를 더하면 구버전 Agent에 미결 요청과 안 맞는 ERROR가 내려간다. Agent의
    `deliver`는 ERROR를 항상 받아들이고 `on_error`가 `False`를 돌려 경고 로그만 남긴다 — 런에
    영향 없음. 확인하고 주석에 남긴다.
  - RESULT의 id가 그림자 id라는 점이 소비자에게 낯설 수 있다. ARTEL-332가 그 id를
    `knowledge_seen`에 넣으므로 오히려 그쪽이 맞다.
- **Rollback steps:** `git revert`.

## Plan review

fast / medium / heavy 세 역할로 순차 자체 검토(서브에이전트 미사용).

반영한 지적:

- (fast, must-fix) 검증 명령이 Gradle이었다. 이 저장소는 Maven이고 통합 테스트는 Testcontainers로
  PostgreSQL을 띄운다. 명령과 전제를 실제 설정에서 확인해 고쳤다.
- (fast, must-fix) 라우팅 전에 버려지는 프레임이 응답 없이 사라지는 경로가 남는다. 위 절에
  명시하고 ARTEL-332의 '모름' 처리로 대응한다고 적었다.
- (medium, should-fix) 세션 없음 분기를 라우팅 본문에 흩지 말 것. 작은 private 함수 하나로 모은다.

거절한 지적:

- (medium) "`ANSWERED_WRITE_TYPES` 대신 `type != "KNOWLEDGE"` 한 줄로 충분하다." — 거절. 이 파일은
  이미 인입 타입 분류를 이름 붙인 집합으로 표현하고, 그 이름들이 "새 타입이 생기면 어디에 넣어야
  하는가"를 알려 준다. 부정형 한 줄은 그 안내를 지운다.
- (medium) "`failSearch` 이름을 그대로 두고 쓰기용 함수를 따로 만들자." — 거절. 본문이 같아 중복이
  되고, 검색 전용이 아닌 함수가 검색 이름을 단 채 남는다.

heavy 검토 결과: 통과. 변경이 한 파일에 갇히고, 스키마·플래그가 없으며, 되돌리기가 revert
한 번이다. 기존 인입 테스트가 수정 없이 통과해야 한다는 조건이 회귀 방어로 충분하다.

## 구현 결과

diff 검토(critic 역할 자체 수행 — 이 환경에서 서브에이전트를 쓰지 않았다. 스킬이 요구하는
독립 critic이 아니므로 사람 리뷰가 그만큼 더 필요하다)에서 나온 두 가지를 고쳤다.

- **KDoc이 사실보다 셌다.** "거두기(UNLINK)의 id는 툼스톤 행의 것이다"라고 썼는데,
  `KnowledgeGraphService.unlink`는 스코프 런이 baseline 간선을 거둘 때만 툼스톤을 만든다. 운영
  런은 그 간선을 직접 지우고 그 id를 돌려준다. 문장을 사실에 맞게 고쳤다.
- **`when`의 `else` 갈래가 테스트에 없었다.** DELETE와 UNLINK가 각 라우터의 `else`를 타는데,
  성공 응답을 한 갈래에만 다는 실수는 컴파일로 드러나지 않는다. 두 경로를 함께 보는 테스트를
  더했다(그 김에 운영 런의 UNLINK가 툼스톤이 아니라 그 간선 id로 답하는 것도 못 박았다).

계획 대비 달라진 것은 없다. 서비스 변경이 필요 없다는 Recon 단계의 판단도 그대로 유지됐다.

검증:

```
./mvnw -o test -Dtest=KnowledgeWriteResultRouterIntegrationTest   9/9
./mvnw -o test -Dtest="Knowledge*"                                154/154
./mvnw -o test                                                    461/461
```

`KnowledgeMutationInboundIntegrationTest`는 **수정 없이** 통과했다 — 구버전 Agent 호환의 회귀
방어가 실제로 걸렸다는 뜻이다.

## Open Questions

- 없음. 결정 1~3으로 닫았다. ARTEL-332는 이 결정을 그대로 따른다.
