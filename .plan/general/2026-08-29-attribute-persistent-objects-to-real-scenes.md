# 2026-08-29 — DontDestroyOnLoad 오브젝트를 실제 실행 scene 에 앉힌다

- Date: 2026-08-29
- Jira: ARTEL-460
- Status: Implemented

## Goal

`DontDestroyOnLoad` 에만 있는 타입이 content map 에서 `DontDestroyOnLoad` 라는 가짜 scene 항목으로
빠지는 것을 없앤다. 실측 문서(`content-map-evidence/2/0bf60f1e-….json`, schema 7 · editor-play)를
적재하면 capability 469 개 중 64 개가 그 항목에 앉는다. 그 항목에는 아무도 갈 수 없어, 거기서 만든
테스트케이스는 사전조건이 "`DontDestroyOnLoad` scene 이 실행 중이다" 가 된다.

## Non-goals

- SDK 가 `persistentObjects` 를 굽는 방식. 이 작업은 이미 구워진 문서만 읽는다.
- prefab 위에만 사는 타입의 귀속. ARTEL-459 · ARTEL-442 가 `createdBy` 로 이미 한다.
- 조회 API 에 귀속 근거를 싣는 것. `capability_proof` 와 `v_capability_proof` 가 이미 읽는 창구다.

## Context / Constraints

base 는 `develop` 이 아니라 ARTEL-642 브랜치(PR #215)다. 그 PR 이 content map 을 빌드당 하나로
모으고 `capture` 를 scene 으로 내렸으며 `scene.origin` 을 넣었다. 이 작업이 고치는 join 과 적재는
그 PR 이 방금 만진 경로다.

제약:

- **타입 이름으로 판정하지 않는다.** `TutorialController` · `SaveLoadController` 는 이 게임 한 벌의
  클래스 이름이고 SDK 는 임의의 Unity 게임에 붙는다. 이번 주에만 화면 판정 규칙 셋이 같은 이유로
  버려졌다.
- **`DontDestroyOnLoad` 라는 문자열도 규칙에 넣지 않는다.** 문서의 `scenes` 배열에 없는 이름이면
  scene 이 아니라고 본다. 그래야 엔진이 다른 이름을 쓰거나 수집기가 또 다른 가짜 scene 을 만들어도
  같은 규칙이 걸린다.
- **정하지 못한 것을 추측으로 메우지 않는다.** 틀린 scene 에 앉히면 QA agent 가 없는 control 을
  찾으러 가고, 그 실패가 지도가 아니라 게임이 깨진 것처럼 읽힌다.
- 마이그레이션은 필요 없다. `capability_proof`(V44)가 "이 결론이 어떻게 나왔나" 를 담으려고 이미
  있는 표이고 아직 아무도 쓰지 않는다.

## Approach (Checklist)

- [x] **Step 0: Recon** — 실측 문서의 `persistentObjects` 는 넷(`SaveLoadController` ·
      `TutorialController` · `TutorialController/ChatWindow` · `GameObject`)이고, `PlacementIndex` 가
      그 `scene` 값(`DontDestroyOnLoad`)을 그대로 받아 candidate 64 개가 그 이름에 앉는다.
- [x] **Step 1: `join/PersistentSceneAttribution.kt`** — 오브젝트 root 마다 실행 scene 을 정하고
      그 근거를 사슬로 낸다. anchor 세 단계, 위가 이긴다:
      1. `persistent-also-placed` — 같은 컴포넌트 타입이 진짜 scene 오브젝트에도 있다 (`exact`)
      2. `persistent-active-scene-test` — 조건이 `SceneManager.GetActiveScene().name` 을 scene 이름과
         `==` 로 맞댄다 (`exact`)
      3. `persistent-condition-subject-placed` — 조건이 읽는 상태의 주인 타입이 **딱 한 scene 에**
         있다 (`derived`)
- [x] **Step 2: join 배선** — `PlacementIndex` · `SceneWiringIndex` · `SpawnAttribution` 이 전부
      귀속 결과 위에서 자리를 만든다. 정하지 못한 root 는 자리를 하나도 내지 않는다.
- [x] **Step 3: 근거를 값으로** — `ScenePlacement.anchors` → `CapabilityCandidate.sceneAnchors` →
      `capability_proof` 행(한 단계 = 한 행). scene 이 둘 이상이면 `persistent-scene-ambiguous` gap 과
      `analysis_confidence = 'ambiguous'`.
- [x] **Step 4: 옛 가짜 scene 행 청소** — `SceneRepository.retireVanishedScenes`. 이번 문서가 더는
      말하지 않고 capability · screen · scene_edge · selector 가 하나도 없으며 QA 런이 서 보지도
      캡처를 찍지도 않은 `origin='evidence'` 행만 지운다.
- [x] **Step 5: 테스트** — `PersistentSceneAttributionTest`(실측 문서 + 실측에 없는 세 경우),
      `ContentMapReingestTest` 에 적재 두 건.

## Validation

- **Commands to run:**
  - `./mvnw test`
  - 실측 문서 재적재 — S3 의 `content-map-evidence/2/0bf60f1e-6484-4273-a811-ff1e63b56b6a.json` 을
    Testcontainers 위에서 적재하고 scene 별 capability 수를 base 와 비교
- **Expected output:** scene 8 → 7, `DontDestroyOnLoad` 항목 없음. capability 469 → 462.
  `Map_scene` 46 → 103.

## 실측 결과

| | base (ARTEL-642) | 이 작업 |
|---|---|---|
| scene | 8 (`DontDestroyOnLoad` 포함) | 7 |
| capability | 469 | 462 |
| `DontDestroyOnLoad` capability | 64 | 0 |
| `Map_scene` capability | 46 | 103 |

`DontDestroyOnLoad` 에 있던 64 개의 행방:

| | 수 | 어디로 |
|---|---|---|
| `Tutorial.TutorialController` | 53 | `Map_scene`. `MapMove.StagePosition` 을 읽고 `Map.MapMove` 는 `Map_scene` 에만 있다 |
| `Tutorial.TutorialChatWindow` | 4 | `Map_scene`. 같은 root 의 판정을 따른다 |
| `Core.SaveLoadController` | 3 | 사라진다. 같은 record 가 `Map_scene` 행을 이미 냈던 중복이다 |
| `Combat.Stage.StageDataSingleton` | 4 | 사라진다. 같은 이유의 중복이다 |

- 한 scene 으로: 57
- 여러 scene 으로: 0
- gap: 0 (이 문서에서는)

`capability_proof` 는 57 개 capability 에 171 행(한 capability 당 3 단계). gap 경로는 이 문서에서
걸리지 않지만 리포지토리 픽스처(`wv-editor-play-schema7.json`, 같은 게임의 다른 capture)에서는
`Combat.Stage.StageDataSingleton` 의 record 4 건이 gap 이 된다 — 그 capture 에는 `Map_scene` 사본이
없어 anchor 가 하나도 없다.

## Risks & Rollback

- **Risks:** 3 단계 규칙은 유도다. 조건이 읽는 타입이 한 scene 에만 있다는 것이 그 오브젝트가 그
  scene 에서 돈다는 증명은 아니다 — static 필드는 scene 을 넘어 살아 있을 수 있다. 그래서 결론을
  `derived` 로 내리고 근거를 `capability_proof` 에 남긴다. 틀린 판정은 되짚어 고칠 수 있다.
- **Rollback steps:** 코드만 되돌리면 된다. 스키마 변경이 없고, `capability_proof` 행은 재적재가
  `deleteCapabilityChain` 으로 지운다.

## Open Questions

- 없음.
