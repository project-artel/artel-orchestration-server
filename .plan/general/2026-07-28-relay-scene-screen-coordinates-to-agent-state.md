# 2026-07-28 — 씬 화면 좌표를 Agent 게임 상태까지 릴레이

- Date: 2026-07-28
- Jira: ARTEL-170
- Status: Implemented

## Goal

SDK가 블록마다 싣는 `transform.rect`(픽셀, 원점 좌상단)와 `transform.onScreen`, 씬마다 싣는 `screen`(게임 화면 크기)을 `AgentGameState`까지 그대로 전달한다. ARTEL-168에서 추가된 QA 에이전트의 포인터 도구(`move_pointer`, `drag_pointer`)가 겨눌 숫자를 갖게 하는 것이 목적이다.

## Non-goals

- 좌표 변환. 뒤집기, 중심점 환산, 리스케일 모두 하지 않는다. SDK의 `move_mouse`가 이 좌상단 픽셀값을 그대로 받아 내부에서 변환하므로, 여기서 한 번 더 변환하면 이중 적용이 된다.
- `transform.world`(3D 월드 좌표) 릴레이. 포인터 조준에 쓰이지 않는다.
- `GameStateTransformer`의 축약 규칙 변경. 잠긴 버튼 제외, 라벨 흡수, `recentActions` 상한은 그대로 둔다.
- 에이전트 서버 쪽 소비(ARTEL-171). 이 작업은 계약을 내보내는 쪽만 담당한다.

## Context / Constraints

- `SdkBlock`은 씬 루트와 블록을 같은 클래스로 표현한다. SDK 계약상 `screen`은 씬 루트에만, `transform`은 블록에만 실린다(`SceneDto.screen`, `SceneBlockDto.transform`). 한 클래스에 둘 다 nullable로 붙인다.
- SDK가 보내는 rect는 ARTEL-153 이후 정수 픽셀이다(`ScreenRectDto` = `{x,y,w,h}: int`, 원점 좌상단). `screen`은 `{w,h}: int`.
- 계약은 병렬로 작업 중인 에이전트 서버(ARTEL-171)와 맞춰야 하므로 필드명을 그대로 쓴다: `Interactable.rect`, `Interactable.onScreen`, `AgentGameState.screen`.
- 배포 순서가 보장되지 않는다. `transform`/`screen`을 싣지 않는 구버전 SDK 페이로드도 유효한 `AgentGameState`를 만들어야 한다. 새 필드는 전부 additive + nullable이고, `onScreen`만 Kotlin 기본값 `true`로 하위 호환을 담당한다.
- `rect`가 없을 때 0으로 채우면 "화면 좌상단 1픽셀"과 구분되지 않는다. 반드시 `null`(직렬화 시 키 자체 부재)이어야 한다.
- `Interactable`은 이미 `@JsonInclude(NON_NULL)`이다. `AgentGameState`에도 같은 애너테이션이 필요하다. 그러지 않으면 `screen`이 없는 상태에서 `"screen": null`이 나간다.

## Approach (Checklist)

- [x] **Step 0: Recon** — `sdk/dto/GameStateDto.kt`, `sdk/service/GameStateTransformer.kt`, `GameStateTransformerTest`, SDK 쪽 `ScreenRectDto`/`ScreenSizeDto`/`SceneDto`/`SceneBlockDto` 확인.
- [x] **Step 1: Implementation** (`src/main/kotlin/kr/artel/orchestration/sdk/dto/GameStateDto.kt`)
  - 수신 DTO: `SdkScreenRect(x,y,w,h: Int)`, `SdkScreenSize(w,h: Int)`, `SdkBlockTransform(rect: SdkScreenRect? = null, onScreen: Boolean = true)` 추가. `SdkBlock`에 `screen: SdkScreenSize? = null`, `transform: SdkBlockTransform? = null` 추가. SDK가 함께 보내는 `world`/`active`는 매핑하지 않는다(런타임 매퍼가 미지의 필드를 무시하므로 지금과 동일).
  - 송신 DTO: `AgentRect(x,y,w,h: Int)`, `AgentScreenSize(w,h: Int)` 추가. `Interactable`에 `rect: AgentRect? = null`, `onScreen: Boolean = true` 추가. `AgentGameState`에 `screen: AgentScreenSize? = null` 추가하고 클래스에 `@JsonInclude(NON_NULL)`.
- [x] **Step 1b: Transformer** (`sdk/service/GameStateTransformer.kt`)
  - `toAgentGameState`에서 루트 노드의 `screen`을 `AgentScreenSize`로 옮긴다.
  - `traverse`에서 노드의 `transform`을 한 번 읽어 그 노드가 만드는 모든 `Interactable`(button/editText/커스텀)에 `rect`와 `onScreen`을 그대로 싣는다. 값은 손대지 않는다.
- [x] **Step 2: Tests** (`GameStateTransformerTest`, 기존 스타일 유지)
  - rect를 가진 블록이 숫자를 그대로 유지하는지.
  - `transform`이 없는 블록의 `rect`가 0이 아니라 `null`인지, `onScreen`이 `true`인지.
  - 씬의 `screen` 크기가 전달되는지, 없으면 `null`인지.
  - 화면 밖 블록이 `onScreen = false`로 나오는지(rect 값은 유지).
  - JSON 역직렬화 경로로 구버전(좌표 없는) 페이로드 하위 호환 확인.
- [x] **Step 3: Rollout / Rollback** — 플래그 없음. 마이그레이션 없음. 필드 추가뿐이라 SDK/에이전트 서버 배포 순서와 무관하다.

## Validation

- **Commands to run:** `./mvnw test -Dtest=GameStateTransformerTest`
- **Expected output:** 신규 테스트 통과, 기존 변환 테스트 회귀 없음. (통합 테스트는 로컬 PostgreSQL이 필요하고 ~15분 걸리므로 이 변경 범위에서는 돌리지 않는다.)
- **실행 결과:** `./mvnw test -Dtest=GameStateTransformerTest -DfailIfNoSpecifiedTests=false` → `Tests run: 13, Failures: 0, Errors: 0, Skipped: 0` (기존 8건 + 신규 5건). 통합 테스트는 실행하지 않았다.
- **직렬화 형태 확인(임시 테스트로 1회 출력 후 제거):**
  - 좌표 있음: `{"scene":"Lobby","screen":{"w":1920,"h":1080},"interactables":[{"id":1,"name":"StartButton","type":"button","rect":{"x":100,"y":200,"w":300,"h":40},"onScreen":true}],"observables":{},"recentActions":[]}`
  - 좌표 없음: `{"scene":"Lobby","interactables":[{"id":1,"name":"StartButton","type":"button","onScreen":true}],"observables":{},"recentActions":[]}`

## Risks & Rollback

- **Risks:**
  - 계약 불일치. 에이전트 서버가 병렬로 같은 필드명을 기대하고 있어 이름이 어긋나면 좌표가 조용히 사라진다. 필드명을 SDK/에이전트 쪽과 문자 그대로 맞춘다.
  - payload 증가. 조작 후보마다 rect 4개 + bool 1개가 붙는다. 후보 수는 씬 전체 블록 수보다 훨씬 적으므로 수용한다.
  - `onScreen = false`인 후보를 에이전트가 그대로 겨눌 수 있다. 필터링은 소비 쪽(ARTEL-171) 판단이며 여기서는 사실만 전달한다.
- **Rollback steps:** `git revert`. 추가 필드뿐이라 소비 측 되돌림 없이 안전하다.

## Open Questions

- 없음.
