# 2026-08-29 — agent 가 이 빌드의 capability 를 다 보게 한다

- Date: 2026-08-29
- Jira: ARTEL-680
- Status: Done

## Goal

QA agent 가 런 시작에 받는 씬별 목록(`/internal/scene-context`)에 이 빌드의 capability 가 전부
들어가게 한다. 지금은 `v_content_map_capability` 가 `status <> 'not-a-step'` 을 들고 있어 실측
472 행 중 54 행만 나간다.

그 418 행이 이 이슈의 전부다. 기계로 검증하는 길을 막았던 것도 그것들이고(ARTEL-450 코멘트,
`interaction = none` 이라 누를 수가 없다), 그래서 agent 에게 판단을 넘기기로 했는데(ARTEL-644)
agent 도 못 본다. 적을 대상을 모르니 키를 지목할 수 없다.

## Non-goals

- `status` 판정 규칙을 바꾸지 않는다. `runnable` · `needs-probe` · `not-a-step` 은 V45 가 가른
  실행 가능성 축이고 그 구분 자체는 맞다
- agent 쪽 tool 은 ARTEL-645 다
- 테스트케이스 생성기가 받는 것을 바꾸지 않는다. 지금 받는 51 행을 그대로 받는 것이 이 작업의 조건이다

## Context / Constraints

실측(`artel_integration`, 읽기만):

| | 수 |
|---|---|
| `capability` (`merged_into IS NULL`) | 472 |
| `v_content_map_capability` | 54 |
| 잘리는 것 (`status = 'not-a-step'`) | 418 |

- **이 뷰를 읽는 곳이 둘이다.** TC 생성기(`ContentMapViewService.stepsByScene`)도 읽고, 거기서
  `not-a-step` 을 거른 이유가 있다 — 누를 수 없는 것으로는 실행 가능한 테스트 케이스를 못 만든다.
  두 소비자가 서로 다른 것을 원한다는 사실이 코드에 드러나야 한다
- **418 행을 한 목록에 쏟으면 안 된다.** 누를 수 있는 51 개가 418 개 사이에 묻히면 agent 가 무엇을
  시도해야 할지 흐려진다
- 목록이 커져도 조회 질의 수가 늘지 않아야 한다. `SceneContextService` 는 씬 수와 무관하게 질의
  수를 고정한 서비스다

## Approach

**뷰를 넓히고 소비자를 가른다.** 뷰를 하나 더 만들지 않는다 — 뷰가 컬럼 30 개를 들고 있어 둘로
나누면 다음에 컬럼이 하나 늘 때 고칠 자리가 둘이 되고, 그 충돌이 이 뷰에서 방금 일어났다
(ARTEL-644 의 V71 과 ARTEL-460 의 V70 이 각자 이 뷰를 통째로 다시 냈다).

- [x] **V72** — `v_content_map_capability` 에서 `AND c.status <> 'not-a-step'` 한 줄을 뺀다.
      `merged_into IS NULL` 은 남긴다 — 접힌 행은 중복이라 어느 소비자도 원하지 않는다.
      V71 의 정의를 그대로 옮겼고, V71 이 develop 에서 이미 V70 의 `scene_presence` 를 들고
      있으므로 따로 합칠 것이 없다
- [x] **`ContentMapRepository`** — 필터를 질의로 내려 두 창구를 이름으로 가른다
  - `findStepCapabilityRows` — `AND status <> 'not-a-step'`. TC 생성기가 읽는다
  - `findStepCapabilityRowsByScene` — 같은 필터를 씬 하나로 좁힌 것
  - `findAllCapabilityRows` — 필터 없음. agent 가 읽는다
- [x] **`SceneContextService`** — `findAllCapabilityRows` 를 읽고 한 씬의 행을 `status` 로
      `partition` 한다. 질의를 하나 더 돌리지 않는다
- [x] **`SceneContextEntry.notAStepCapabilities`** — 새 칸. `capabilities` 는 오늘 나가는 것을 그대로
      낸다(agent-server 가 이미 읽는 이름이라 건드리지 않는다). 칸 이름에 `status` 값을 그대로 박아,
      payload 만 보는 쪽도 무슨 축으로 갈렸는지 알 수 있게 한다
- [x] **문서 주석 정리** — "뷰가 `not-a-step` 을 거른다" 고 적힌 자리를 고친다
      (`ContentMapCapabilityRow` · `CapabilityRepository` · `ContentMapViewRows` ·
      `AgentCapabilityWriteService` · `InternalSceneContextController`)

## Validation

- [x] `./mvnw test`
- [x] `./scripts/check-flyway-migrations.sh develop`
- [x] 실측 — `artel_integration` 의 pg_dump 사본에 V72 까지 올리고, agent 창구와 TC 창구의 행 수를
      각각 센다. TC 창구가 54 그대로여야 한다

## Risks

- `SceneContextResponse` 가 커진다. 실측 build 2 에서 씬별 목록이 51 줄에서 469 줄이 된다. 프롬프트에
  그리는 쪽(ARTEL-612)이 씬 하나의 조각만 그리므로 한 턴의 부피는 그 씬의 몫만큼 는다. 두 목록으로
  갈라 냈으므로 그리는 쪽이 필요하면 한쪽만 그릴 수 있다
- 뷰를 읽는 새 코드가 필터를 잊으면 TC 쪽이 조용히 `not-a-step` 을 받는다. 그 자리를 좁히려고
  `ContentMapRepository` 의 세 메서드 말고는 뷰를 읽는 곳을 만들지 않았다
