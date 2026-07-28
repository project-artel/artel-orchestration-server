# 2026-07-28 — 이미지/스프라이트 전용 블록을 Agent 게임 상태에 노출

- Date: 2026-07-28
- Jira: ARTEL-174
- Status: Implemented

## Goal

`GameStateTransformer`가 버리고 있는 순수 시각 요소(uGUI Image, SpriteRenderer)를 `AgentGameState.visuals`로 내보낸다. 지금은 조작 후보(button/editText/actions 보유 커스텀)와 관찰값(`content` 또는 `states` 보유)만 살아남으므로, 컴포넌트가 이미지/스프라이트뿐인 블록은 통째로 사라진다. 에이전트는 그 블록을 볼 수도, 따라서 겨눌 수도 없다.

## Non-goals

- 좌표 변환. 블록이 조작 후보에 대해 이미 계산해 둔 `rect`/`onScreen`을 그대로 복사한다. 뒤집기, 중심점 환산, 리스케일 모두 하지 않는다.
- `GameStateTransformer`의 구조 변경, 기존 필드 이름 변경, 직전 커밋(ARTEL-172)의 관찰값 키 경로 로직 변경.
- 스프라이트 에셋의 내용 해석. `sprite`는 SDK가 준 에셋 이름 문자열을 그대로 옮길 뿐이다.
- 에이전트 서버 쪽 소비. 이 작업은 계약을 내보내는 쪽만 담당한다.

## Context / Constraints

- 병렬 진행 중인 Unity SDK 작업(ARTEL-173)이 다음 컴포넌트를 싣기 시작한다.
  - `{"type": "image", "name": "...", "sprite": "<에셋 이름, 없으면 키 자체가 없음>", "states": [], "actions": []}` — uGUI Image
  - `{"type": "sprite", ...동일 형태...}` — SpriteRenderer
- `SdkComponent`에는 `sprite` 필드가 없다. 받아 두지 않으면 어떤 그림인지가 사라진다.
- **중복 금지.** 버튼은 대개 Image를 함께 달고 있다. 같은 블록이 `interactables`와 `visuals`에 모두 나오면 에이전트가 하나를 둘로 읽는다. 그 블록에서 조작 후보가 하나라도 나왔다면 시각 항목은 싣지 않는다.
- 배포 순서가 보장되지 않는다. 이미지 컴포넌트를 전혀 보내지 않는 SDK도 유효한 `AgentGameState`를 만들어야 한다. `visuals`는 그때 `null`이 아니라 빈 리스트다.
- `rect`가 없을 때 0으로 채우면 "화면 좌상단"이라는 유효 좌표와 구분되지 않는다. 반드시 `null`이어야 하므로 `Visual`도 `@JsonInclude(NON_NULL)`이다.

## Approach (Checklist)

- [x] **Step 0: Recon** — `sdk/dto/GameStateDto.kt`, `sdk/service/GameStateTransformer.kt`, `GameStateTransformerTest`, `ArtelWebSocketIntegrationTest`의 변환 단언 확인.
- [x] **Step 1: DTO** (`src/main/kotlin/kr/artel/orchestration/sdk/dto/GameStateDto.kt`)
  - 수신: `SdkComponent`에 `sprite: String? = null` 추가.
  - 송신: `Visual(id: Int, name: String, type: String, sprite: String? = null, rect: AgentRect? = null, onScreen: Boolean = true)` 추가, `@JsonInclude(NON_NULL)`.
  - `AgentGameState`에 `visuals: List<Visual> = emptyList()` 추가. `recentActions` 뒤에 붙여 기존 필드 순서를 건드리지 않는다.
- [x] **Step 2: Transformer** (`sdk/service/GameStateTransformer.kt`)
  - `VISUAL_TYPES = setOf("image", "sprite")` 상수 추가.
  - `traverse`에 `visuals` 파라미터를 추가한다. 기존 컴포넌트 루프는 손대지 않고, 루프가 끝난 뒤 **이 블록이 조작 후보를 하나도 만들지 않았을 때만** 시각 컴포넌트를 `visuals`에 싣는다. 판정은 루프 진입 전 `interactables.size`를 기억해 두고 비교하는 방식이다. 이러면 커스텀 컴포넌트의 actions까지 포함해 모든 후보 경로를 자동으로 덮는다.
  - `rect`/`onScreen`은 블록이 이미 계산해 둔 값을 그대로 쓴다.
- [x] **Step 3: Tests** (`GameStateTransformerTest`, 기존 스타일 유지)
  - 이미지뿐인 블록이 `visuals`에 rect와 함께 남는다.
  - 스프라이트 블록도 마찬가지다.
  - Image를 단 버튼 블록은 `interactables`에만 나오고 `visuals`에는 없다.
  - `transform`이 없는 시각 블록의 `rect`는 0이 아니라 `null`이다.
  - 시각 요소가 없는 씬의 `visuals`는 `null`이 아니라 빈 리스트다.
- [x] **Step 4: Rollout / Rollback** — 플래그 없음. 마이그레이션 없음. 필드 추가뿐이라 SDK/에이전트 서버 배포 순서와 무관하다.

## Validation

- **Commands to run:** `./mvnw -o test -Dtest=GameStateTransformerTest -DfailIfNoSpecifiedTests=false`, `./mvnw -o test -Dtest=ArtelWebSocketIntegrationTest -DfailIfNoSpecifiedTests=false`
- **Expected output:** 신규 테스트 통과, 기존 변환 테스트 회귀 없음.
- **실행 결과:** `GameStateTransformerTest` → `Tests run: 20, Failures: 0, Errors: 0, Skipped: 0` (기존 15건 + 신규 5건). 두 클래스 동시 실행 → `Tests run: 26, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.
- **직렬화 형태 확인(임시 테스트로 1회 출력 후 제거):**
  - 시각 요소 있음: `{"scene":"Lobby","screen":{"w":1920,"h":1080},"interactables":[{"id":1,"name":"StartButton","type":"button","rect":{"x":100,"y":200,"w":300,"h":40},"onScreen":true}],"observables":{},"recentActions":[],"visuals":[{"id":11,"name":"HeartIcon","type":"image","sprite":"heart_full","rect":{"x":20,"y":30,"w":64,"h":64},"onScreen":true},{"id":12,"name":"Background","type":"sprite","onScreen":true}]}`
  - 시각 요소 없음: `{"scene":"Lobby","interactables":[{"id":1,"name":"StartButton","type":"button","onScreen":true}],"observables":{},"recentActions":[],"visuals":[]}`

## Risks & Rollback

- **Risks:**
  - payload 증가. 시각 블록은 조작 후보보다 훨씬 많을 수 있다. 중복 제거로 버튼/입력 블록은 빠지지만, 배경 이미지가 많은 씬에서는 `visuals`가 길어진다. 상한은 실제 씬으로 관측한 뒤 필요하면 별도로 다룬다.
  - 타입 문자열 불일치. SDK가 `image`/`sprite` 이외의 문자열을 쓰면 조용히 아무것도 안 나온다. ARTEL-173의 계약과 문자 그대로 맞춘다.
  - 중복 판정이 "블록 단위"다. 한 블록이 버튼 + 무관한 별도 이미지를 함께 갖는 드문 경우 그 이미지는 빠진다. 두 번 읽히는 쪽이 더 해롭다고 보고 감수한다.
- **Rollback steps:** `git revert`. 추가 필드뿐이라 소비 측 되돌림 없이 안전하다.

## Open Questions

- 없음.
