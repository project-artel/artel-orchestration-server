# 2026-08-27 — 씬별 capability 와 앵커 지식을 한 번에 내주는 내부 창구

- Date: 2026-08-27
- Jira: ARTEL-611
- Status: Draft

## Goal

agent-server 가 **런 시작에 한 번** 부르고 메모리에 들고 있을 창구를 만든다. 응답은 게임
빌드 하나에 대해 **씬 이름을 키로** 두 가지를 함께 낸다.

- 그 씬에서 **무엇을 할 수 있는가** — `v_content_map_capability` 가 내주는 것과 같은 기준의
  capability
- 그 씬에서**만** 참인 사실 — `knowledge_anchor` 가 그 씬에 묶은 지식의 id 와 요약

ARTEL-612 가 이것을 한 번 받아 두고 매 턴 **현재 씬의 조각만** 프롬프트에 그린다. 그래서
설계 목표가 둘이다: **런당 왕복 한 번**, 그리고 **프롬프트가 감당할 수 있는 부피**.

## Non-goals

- `screen` 단위 묶음. 화면 판정은 ARTEL-453 이 먼저다. 이 응답은 **씬 단위**다.
- 커버리지 지표(ARTEL-454).
- 쓰기 경로. 이 티켓은 조회 하나다.
- 브라우저 조회(ARTEL-446, `/api/projects/{id}/game-builds/{id}/content-map`) 변경. 겸하지
  않는다 — 겸하면 사람이 볼 것의 요구가 프롬프트 부피를 정한다.
- 앵커 없는 지식. 그것은 게임 전체의 사실이고 지식창고의 대부분이라, 검색으로 찾는 것으로
  남긴다.
- 효과(`then`)·조건 트리·`evidence` 주소. capability 한 줄이 프롬프트 한 줄이라, 프롬프트에 그릴
  수 없는 것은 싣지 않는다.

## Context / Constraints

### 씬 이름이 유일한 조인 키다

앵커는 content map 과 **대조하지 않고** 저장된다(ARTEL-591, V55 — content map 이 없는
프로젝트도 씬 이름은 있기 때문이다). 그래서 content map 이 들어 본 적 없는 씬 이름을 든
앵커가 정상적으로 존재한다. 그런 씬도 응답에 담고, capability 는 빈 목록으로 낸다. 빼면
agent 는 자기가 아는 사실을 잃는다.

반대쪽도 같다 — capability 가 한 줄도 없는 content map 의 씬도 목록에 남긴다. "이 씬은
아는데 할 게 없다"와 "이 씬을 모른다"는 다른 답이고, 뭉개면 agent 는 지도에 있는 씬을
미지의 씬으로 읽는다.

### `control_selector` 는 조준 키가 아니다

`control_selector` 는 형제 인덱스가 박힌 경로라 런마다 흔들리고, 액션 프로토콜은 애초에
int instance id 를 받는다. 그것을 조준용 키처럼 내면 agent 가 그 문자열로 맞추려 하고,
빗나간 이유가 응답 어디에도 안 남는다. 실어야 한다면 **힌트라고 이름표를 붙여**
(`controlSelectorHint`) 낸다. 조준은 `controlPath` · `controlLabel` 과 게임 상태 프레임이
맡는다.

### 지식 스코프는 술어 하나로만 지난다

`KnowledgeScopeSql.VISIBLE` 을 그대로 지난다. 이 술어를 손으로 다시 적으면 언젠가 한 곳이
빠지고, 빠진 격리는 조용히 틀린 결과를 낸다(KnowledgeScope.kt). 스코프 런에서 가려진
지식은 앵커도 요약도 나가면 안 된다.

**스코프를 무엇으로 받는가.** `knowledgeScopeId` 를 쿼리 파라미터로 열지 않는다 —
`KnowledgeController` 가 같은 이유로 그것을 거절했다(스코프 id 가 API 표면에 먼저 생기면
실험 엔티티가 그 형식을 따라가는 순서가 된다). 대신 **`qaTryId`** 를 받아
`qa_try.knowledge_scope_id` 에서 스코프를 읽는다. agent 는 세션 개설 payload 로
`qa_try_id` 를 이미 받는다(`QaAgentScenario.qaTryId`). 생략하면 운영 스코프다.

### 본문(description)은 내지 않는다

앵커 지식은 매 모델 호출마다 다시 그려지는 프롬프트 블록으로 간다. 본문을 실으면 그
블록이 런 내내 매 턴 비용을 다시 낸다. id 와 요약이면 agent 가 필요할 때 검색으로 본문을
가져올 수 있다.

### 내부 포트, `/internal` 접두사

`.plan/general/2026-08-05-unify-internal-api-paths.md` 와 `2026-08-06-...separate-port.md` 가
정한 그대로다: 서버-투-서버 경로는 `/internal` 아래에만 두고, 그 접두사는 내부 포트에서만
뜬다(`InternalApiConfig`). `SecurityConfig` 의 permitAll 목록에 새 줄을 추가하지 않는다 —
`/internal/**` 한 줄이 이미 덮는다.

경로에 `projectId` 를 지나게 두고 **실제로 검사한다**. 검사가 없으면 그 값은 장식이 되고,
아무 프로젝트 id 나 끼워도 통과한다. 검사는 컨트롤러가 아니라 서비스 안에 둔다 —
`ContentMapViewService.read` 와 같은 자리이고, 그래야 다음 진입점이 빠뜨릴 수 없다.

### 한 번에 읽는다

씬 수·지식 수와 무관하게 질의 수가 고정이어야 한다. 이 응답을 만드는 질의는 **최대 다섯**,
전부 씬 수와 무관하다.

| # | 무엇 | 조건 |
|---|---|---|
| 1 | `qa_try` | `qaTryId` 가 왔을 때만 |
| 2 | `game_build` | 항상 (존재 + 프로젝트 대조) |
| 3 | `content_map` 고르기 | 항상 |
| 4 | `scene` 목록 | 지도가 있을 때만 |
| 5 | `v_content_map_capability` 전량 | 지도가 있을 때만 |
| 6 | 앵커 + 지식 요약 | 항상 |

씬별 묶기는 전부 Kotlin 메모리에서 한다. 이미 있는 조회
(`ContentMapRepository.findCapabilityRows`, `SceneRepository.findByContentMapIdOrderByNameAsc`)
가 지도 단위 한 방이라 새 질의를 만들 필요가 없다.

### 기존 파일을 건드리지 않는다

같은 레포에서 ARTEL-594 · 605 · 596 · 453 이 동시에 돌고 있다. 새 패키지
`kr.artel.orchestration.scenecontext` 안에서만 쓴다. 앵커 조회도 새 리포지토리를 이 패키지에
두어 `KnowledgeAnchorRepository` 를 건드리지 않는다 — 스코프 술어는
`KnowledgeScopeSql.VISIBLE` 상수를 공유하므로 드리프트하지 않는다.

## Approach (Checklist)

- [ ] **Step 1: DTO** — `scenecontext/dto/SceneContextDtos.kt`. 응답 루트 · 씬 · capability ·
      앵커 지식. id 계열은 문자열(64비트 정밀도).
- [ ] **Step 2: 앵커 조회** — `scenecontext/repository/AnchoredKnowledgeRepository.kt`.
      `SELECT DISTINCT a.scene_name, k.id, k.summary` + `VISIBLE`. `DISTINCT` 인 이유: 한
      지식이 같은 씬의 화면 둘에 걸리면 씬 단위 응답에서 같은 줄이 두 번 나온다.
- [ ] **Step 3: 서비스** — `scenecontext/service/SceneContextService.kt`. 접근 검사·스코프
      해석·두 반쪽 합치기. 빌드가 없거나 경로의 프로젝트와 다르면 null(→404).
- [ ] **Step 4: 컨트롤러** —
      `scenecontext/controller/InternalSceneContextController.kt`,
      `GET /internal/projects/{projectId}/game-builds/{gameBuildId}/scene-context`.
- [ ] **Step 5: 테스트** — `src/test/kotlin/.../scenecontext/SceneContextIntegrationTest.kt`.
- [ ] **Step 6: OpenAPI 스냅샷 재생성** — 새 경로가 `docs/api/openapi.json` 에 들어간다.

## Validation

- **Commands to run:**
  - `./mvnw test -Dtest=SceneContextIntegrationTest` (실제 게이트)
  - `./mvnw test -Dtest=OpenApiSnapshotTest` (스냅샷 재생성)
  - `./mvnw test` (전체)
- **Expected output:** 전체는 `OpenApiDocumentationIntegrationTest` 와
  `TestScenarioReconcileIntegrationTest` 둘이 선재 실패로 남는다(깨끗한 `origin/develop`
  워크트리에서 확인된 것). 나머지 통과.

### 테스트가 봐야 하는 것

1. capability 와 앵커가 다 있는 빌드 — 둘이 같은 씬 키 아래 붙는다
2. 앵커에만 있는 씬 — 응답에 있고 capability 가 빈 목록이다
3. content map 이 없는 빌드 — 404 가 아니라 200, 씬 목록은 비고 앵커 씬만 나온다
4. `not-a-step` 이 나가지 않는다
5. `description` 이 payload 어디에도 없다
6. 스코프에 가려진 지식이 앵커째 빠진다
7. 씬 수가 늘어도 질의 수가 그대로다 — 리포지토리 스파이로 호출 횟수를 못박는다
8. 경로의 `projectId` 가 빌드의 것과 다르면 null(→404)

## Risks & Rollback

- **Risks:**
  - agent 는 세션 개설 payload 로 `projectId` · `gameBuildId` 를 받지 않는다
    (`QaAgentSessionContext` 에 없다). ARTEL-612 가 그 두 값을 어디서 얻을지는 이 티켓 밖의
    문제이고, 필요하면 세션 payload 확장이 따로 필요하다. **PR 과 Jira 에 남긴다.**
  - `capture` 를 고르는 규칙이 브라우저 조회와 같아야 한다(가장 최근에 알게 된 것 = id
    내림차순). 두 곳이 갈리면 사람이 보는 지도와 agent 가 쓰는 지도가 달라진다. 지금은
    같은 리포지토리 메서드를 부르므로 갈릴 수 없다.
  - 응답이 빌드 전체라 씬이 많은 게임에서 한 번의 payload 가 크다. 런당 한 번이라
    감당한다고 보지만, 커지면 씬 필터를 나중에 더한다(지금 더하면 매 턴 부르는 길이
    열린다).
- **Rollback steps:** `git revert`. 순수 추가라 기존 경로가 이 코드를 부르지 않는다.

## Open Questions

- 스코프 입구를 `qaTryId` 로 둔 것 — 실험 엔티티가 생기면 그때 다시 본다. 지금은 agent 가
  확실히 아는 값이 그것뿐이다.
