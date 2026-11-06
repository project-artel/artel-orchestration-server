# 2026-08-27 — `pulse` 에서 화면을 가르고 화면 전이를 남긴다

- Date: 2026-08-27
- Jira: ARTEL-453
- Status: Implemented

## Goal

QA 런이 흘리는 `PULSE` 만으로 `screen` · `screen_capability` ·
`screen_transition` 행을 만들고, 씬을 넘는 전이가 `scene_edge` 를 검증됨으로 올리게 한다.
정적 후보에 없던 전이는 `source='runtime'` 으로 들어온다.

세 가지가 이 행들을 기다린다 — 읽기 API(ARTEL-596), 다이어그램(ARTEL-597),
화면 캡처(ARTEL-595 · ARTEL-456). 지금은 `screen` 이 0행이라 셋 다 빈 화면을 그린다.

## Non-goals

- 화면 이름 짓기. `screen.name` 은 표시용이고 조인 키가 아니다. LLM 이 나중에 짓는다.
- 화면 캡처 발행(ARTEL-595 · ARTEL-456).
- 액션-`pulse` 시간축 부착(ARTEL-450). **선행이 안 끝났다** — 그래서
  `screen_transition.capability_id` 는 이번에 전부 null 이고 `fired_count` 는 늘 0 이다.
- 읽기 API · 뷰 DTO. ARTEL-596 이 가져간다.

## Context / Constraints

`pulse` 문서의 모양(agent-server `app/qa/pulse.py` 와 SDK `LiveState.cs` 가 같은 것을 읽는다):

```json
{ "type":"PULSE", "schema":2, "reading":12, "frame":3401, "scene":"TitleScene",
  "whole":true, "changed":[...], "statics":[...],
  "active":  [{ "scene":..., "id":26168, "path":"Canvas/continue",
                "selector":"Canvas[2]/continue[1]", "offers":{...}, "members":[...] }],
  "deactive":[{ "selector":"Canvas[2]/GameOver[4]", ... }],
  "unwatchable":1126 }
```

- `whole=true` 는 교체, `whole=false` 는 델타다. **말하지 않은 객체는 있던 자리를 지킨다.**
  agent-server 의 `PulseMemory.apply` 와 같은 규칙을 써야 한다 — 두 소비자가 델타를
  다르게 읽으면 어느 쪽이 틀렸는지 가릴 수 없다.
- 어느 목록에 실려 왔는가가 곧 켜짐/꺼짐이다. 객체의 필드가 아니라서 자기모순이 없다.
- `screen_transition` 은 **관측으로만 생긴다.** 정적으로 만들지 않는다.
- `screen_capability` 는 씬 기능 목록의 **부분집합**이다. 화면이 목록을 따로 갖지 않는다.

## Approach (Checklist)

- [x] **Step 0: Recon** — V40 의 `screen` · `screen_capability` · `screen_transition` ·
      `scene_edge` 절, `QaSdkBridgeService.routePulse`, `PulseMessageHandler`,
      `ScreenRepositories.kt`, agent-server `pulse.py` 의 `fold` 규칙.

- [x] **Step 1: 임계값** — 무엇을 다른 화면으로 볼지.
      - 규칙: **조작 가능한 객체의 켜짐/꺼짐만** `discriminator` 에 담는다. 값 변화도, 일반 오브젝트의
        생멸도 담지 않는다.
      - 조작 가능한 객체 = `pulse` 가 `offers` 를 실어 준 객체 ∪ 그 씬 `capability.control_selector`
        가 지목하는 객체.
      - 정착(settling): 같은 `discriminator` 가 **연속 2회** 관측돼야 화면이 된다. 전이 중간 프레임이
        유령 화면을 만들지 않게.
      - 안전판: 한 씬당 화면 **32개**를 넘으면 더 만들지 않고 WARN 을 남긴다.

- [x] **Step 2: 구현**
      - `contentmap/observe/PulseReading.kt` — `pulse` 문서를 경계에서 한 번 타입으로 판다.
      - `contentmap/observe/ScreenFold.kt` — 인스턴스별 `fold` + `discriminator` 산출 + 정착.
      - `contentmap/observe/ScreenObservationService.kt` — 화면 · 화면 기능 · 전이 · 씬 간선 적재.
      - `PulseMessageHandler` 에서 중계 **뒤에** 부른다. 중계는 그대로 원문이다.
      - `ScreenRepositories.kt` 에 upsert 질의를 **덧붙인다**(재구성 금지 — ARTEL-596 이 같은
        파일에 읽기 질의를 붙인다).
      - `V59__unique_screen_discriminator.sql` — `(scene_id, discriminator)` 유니크.
        멱등을 코드의 희망이 아니라 DB 가 강제하게 한다.

- [x] **Step 3: 테스트** — `ScreenObservationTest`
      - `Canvas/continue` 켜짐·꺼짐이 한 씬에서 화면 2개로 갈린다
      - `screen_capability` 가 씬 목록의 부분집합이다
      - 씬을 넘는 전이가 `scene_edge` 를 검증됨으로 올린다
      - 정적 후보에 없던 전이가 `source='runtime'` 으로 들어온다
      - 같은 화면 재방문이 행을 늘리지 않고 `observed_count` 만 올린다
      - **임계값 고정** — 감시 멤버 값만 바뀐 `pulse` 는 화면을 가르지 않는다

- [x] **Step 4: Rollout** — 스키마 추가 하나뿐이고 기존 행을 건드리지 않는다.
      QA 런 없이 온 `pulse` 는 무시하므로 스캔 순회는 화면을 만들지 않는다.

## Validation

- **Commands to run:**
  - `./mvnw test -Dtest=ScreenObservationTest`
  - `./mvnw test -Dtest='ContentMapSchemaTest,ContentMapViewGoldenTest,QaLogTypeGateParityTest'`
  - `./mvnw test` (전체 — 기존 실패 2건 확인)
- **Expected output:** 새 테스트 전부 통과. 전체에서는 `OpenApiDocumentationIntegrationTest`
  와 `TestScenarioReconcileIntegrationTest` 가 기존대로 실패(clean `origin/develop` 에서 확인).
- **결과:** `ScreenObservationTest` 9건 통과. 인접 슬라이스
  (`ContentMapSchemaTest` · `ContentMapViewGoldenTest` · `ArtelWebSocketIntegrationTest` ·
  `QaReadingsTest` · `QaLogTypeGateParityTest` · `ProjectContentMapAccessTest` ·
  `SceneCaptureRegistrationTest`) 합계 86건 통과.

## Risks & Rollback

- **Risks:**
  - 임계값이 너무 민감하면 화면이 폭발하고 캡처 작업이 행마다 튄다. 안전판(32)이 그것을
    소리 내게 하지만 근본 해결은 아니다.
  - 임계값이 너무 둔하면 오버레이가 안 갈린다. 기능이 하나도 없는 씬은 `discriminator` 가 비어
    화면이 하나로 남는다 — 화면 이전과 같은 상태라 더 나빠지지는 않는다.
  - 씬 간선 검증이 **기능 단위로는 과다 주장**한다. 같은 씬 쌍으로 가는 정적 간선이 여럿이면
    전부 검증됨이 된다. ARTEL-450 이 그것을 하나로 좁힌다.
  - `fold` 상태가 프로세스 메모리다. 재시작하면 다음 전량 `pulse` 가 복구한다.
- **Rollback steps:** `PulseMessageHandler` 의 관측 호출 한 줄을 되돌리면 적재가 멈춘다.
  V59 는 유니크 인덱스 하나라 남아도 무해하다.

## Open Questions

- `kind` 를 무엇으로 두나. ARTEL-450 없이는 `action` 을 정직하게 말할 수 없다.
  → 같은 씬은 `state`, 씬을 넘으면 `action` + `capability_id=null`. `auto` 는 내지 않는다.
  `auto` 는 "TC 가 지시할 수 없다"는 주장이라 틀렸을 때 가장 비싸다.
