# 2026-08-29 — DontDestroyOnLoad 오브젝트를 모든 scene 에 두고 agent 가 지우게 한다

- Date: 2026-08-29
- Jira: ARTEL-460
- Status: Implemented

## Goal

`DontDestroyOnLoad` 에만 있는 타입이 content map 에서 `DontDestroyOnLoad` 라는 가짜 scene 항목으로
빠지는 것을 없앤다. 실측 문서(`content-map-evidence/2/0bf60f1e-….json`, schema 7 · editor-play)를
적재하면 capability 469 개 중 64 개가 그 항목에 앉는다. 그 항목에는 아무도 갈 수 없어, 거기서 만든
테스트케이스는 사전조건이 "`DontDestroyOnLoad` scene 이 실행 중이다" 가 된다.

## 첫 시도가 틀린 지점

이 브랜치의 첫 판은 "이 오브젝트는 어느 scene 것인가" 를 풀었다. 64 개 중 57 개를 `Map_scene` 하나에
앉혔다. **그 질문 자체가 잘못됐다.**

`DontDestroyOnLoad` 는 scene load 를 넘어 살아남으라는 뜻이다. 만들어진 뒤로 그 오브젝트는 모든
scene 에 실제로 존재한다. 어디에 있나는 추론할 것이 아니라 이미 답이 나와 있다.

첫 판의 규칙 1(`persistent-also-placed`)이 그 지점에서 틀렸다. 같은 타입이 진짜 scene 오브젝트에도
놓여 있으면 persistent 사본을 그 scene 하나로 앉혔고, 같은 키를 내는 real 배치와 접혀 **행이 통째로
사라졌다** — `Core.SaveLoadController` 3 건과 `Combat.Stage.StageDataSingleton` 4 건이다. 저장이 어느
scene 에서나 된다는 사실이 그렇게 지도에서 빠졌다.

갈라야 하는 것은 둘이다.

| | 무엇인가 | 어디에 담나 |
|---|---|---|
| 존재 | 모든 scene. 확실하고 추론이 아니다 | 행을 낸다 |
| 여기서 의미가 있나 | 모른다. 확인해야 한다 | `capability.scene_presence` |

## Non-goals

- SDK 가 `persistentObjects` 를 굽는 방식. 이 작업은 이미 구워진 문서만 읽는다.
- prefab 위에만 사는 타입의 귀속. ARTEL-459 · ARTEL-442 가 `createdBy` 로 이미 한다.
- agent 가 `persistent-unconfirmed` 행을 내리는 쓰기 경로. [ARTEL-644](https://artel-asm.atlassian.net/browse/ARTEL-644) 다.
- `persistent-unconfirmed` 가 오래 남은 행을 자동으로 정리하는 규칙.

## Context / Constraints

base 는 `develop` 이 아니라 ARTEL-642 브랜치(PR #215)다. 그 PR 이 content map 을 빌드당 하나로
모으고 `capture` 를 scene 으로 내렸으며 `scene.origin` 을 넣었다. 이 작업이 고치는 join 과 적재는
그 PR 이 방금 만진 경로다.

제약:

- **타입 이름으로 판정하지 않는다.** `TutorialController` · `SaveLoadController` 는 이 게임 한 벌의
  클래스 이름이고 SDK 는 임의의 Unity 게임에 붙는다. `DontDestroyOnLoad` 라는 문자열도 규칙에 넣지
  않는다 — 문서의 `scenes` 배열에 없는 이름이면 scene 이 아니라고 본다. 첫 판이 이 점은 제대로 했고
  그대로 가져간다.
- **존재와 의미를 한 값에 담지 않는다.** 모든 scene 에 둔다는 것은 존재에 대한 사실이고, 어디서
  되는지는 별개다. 둘을 한 칸에 담으면 다시 애매해진다.
- **노이즈가 공짜가 아니다.** 64 개를 7 개 scene 에 두면 448 행이 되고 전체가 469 에서 846 으로
  는다. `TurnBattleScene` 을 읽는 agent 의 목록에 거기서 안 먹는 tutorial capability 57 개가 딸려
  온다. 그래서 "확인 안 됨" 표시가 **저장된 행에도 읽는 창구에도** 보여야 한다.

## Approach (Checklist)

- [x] **Step 1: `entity/ContentMapEnums.kt`** — `ScenePresence` 세 값. 선언 순서가 강함의 순서다.
      `placed` · `persistent-evidenced` · `persistent-unconfirmed`.
- [x] **Step 2: `join/PersistentSceneAttribution.kt`** — persistent 오브젝트를 real scene 마다 자리
      하나씩으로 펼치고, 근거가 지목한 scene 에만 anchor 를 붙인다. anchor 규칙 둘이고 **우선순위가
      없다**:
      1. `persistent-active-scene-test` — 조건이 `SceneManager.GetActiveScene().name` 을 scene
         이름과 `==` 로 맞댄다 (`exact`)
      2. `persistent-condition-subject-placed` — 조건이 읽는 상태의 주인 타입이 **딱 한 scene 에**
         있다 (`derived`)
      옛 규칙 1(`persistent-also-placed`)은 버린다. 그 scene 에 실제로 놓인 오브젝트가 이미 `placed`
      자리를 내고, 같은 scene 의 자리를 합칠 때 강한 값이 이기므로 규칙 없이 같은 답이 나온다.
- [x] **Step 3: join 배선** — `ScenePlacement.presence` → `CapabilityCandidate.scenePresence`.
      `EvidenceJoin.foldByScene` 이 같은 scene 의 자리 여럿을 합치고 가장 강한 값을 남긴다.
- [x] **Step 4: `V66`** — `capability.scene_presence` 와 `v_content_map_capability` 재생성.
- [x] **Step 5: 읽는 창구** — `ContentMapCapabilityRow` · `SceneCapabilityView`(agent 의 scene
      맥락) · `SceneCapabilityResponse`(인스펙터). 저장만 가르고 읽는 쪽이 못 가르면 이 변경은
      지도를 나쁘게 만든 것이다.
- [x] **Step 6: `capability_proof`** — 지목받은 행에만 사슬을 남긴다(한 단계 = 한 행).
- [x] **Step 7: 옛 가짜 scene 행 청소** — `SceneRepository.retireVanishedScenes`. 이번 문서가 더는
      말하지 않고 capability · screen · scene_edge · selector 가 하나도 없으며 QA 런이 서 보지도
      캡처를 찍지도 않은 `origin='evidence'` 행만 지운다.
- [x] **Step 8: 테스트** — `PersistentSceneAttributionTest` 12 건, `ContentMapReingestTest` 에 적재
      두 건.

## Validation

- **Commands to run:**
  - `./mvnw test`
  - 실측 문서 재적재 — S3 의 `content-map-evidence/2/0bf60f1e-6484-4273-a811-ff1e63b56b6a.json` 을
    Testcontainers 위에서 base 와 이 브랜치에 각각 적재하고 비교

## 실측 결과

| | base (ARTEL-642) | 이 작업 |
|---|---|---|
| scene 행 | 8 (`DontDestroyOnLoad` 포함) | 7 |
| capability | 469 | 846 |
| `DontDestroyOnLoad` 의 capability | 64 | — (항목 자체가 없다) |
| `placed` | 469 | 405 |
| `persistent-evidenced` | — | 60 |
| `persistent-unconfirmed` | — | 381 |
| `capability_proof` 사슬 | 0 행 | 60 capability · 180 행 |

`441 = 448 − 7`. 64 개가 scene 7 개로 펼쳐지면 448 인데, `Map_scene` 에서 real 배치와 같은 키를 내는
7 행(`StageDataSingleton` 4 + `SaveLoadController` 3)이 접혀 `placed` 가 된다. `405 + 441 = 846`.

scene 별 capability:

| scene | base | 이 작업 |
|---|---|---|
| `TurnBattleScene` | 232 | 296 |
| `EndingScene` | 48 | 112 |
| `StoryScene` | 48 | 112 |
| `Map_scene` | 46 | 103 |
| `TitleScene` | 16 | 80 |
| `GameClearScene` | 12 | 76 |
| `GameOverScene` | 3 | 67 |
| `DontDestroyOnLoad` | 64 | — |

`DontDestroyOnLoad` 에 있던 64 개의 행방:

| owner | base | 이 작업 |
|---|---|---|
| `Tutorial.TutorialController` | 57 | 399 (`Map_scene` 57 evidenced + 나머지 6 scene 342 unconfirmed) |
| `Combat.Stage.StageDataSingleton` | 4 | 28 (`Map_scene` 4 placed + 나머지 6 scene 24 unconfirmed) |
| `Core.SaveLoadController` | 3 | 21 (`TitleScene` 3 evidenced + `Map_scene` 3 placed + 나머지 15 unconfirmed) |

**첫 판이 지운 두 타입이 살아 있다.** `SaveLoadController` 3 건과 `StageDataSingleton` 4 건은 첫
판에서 `Map_scene` 사본과 접혀 사라졌는데, 이제 real scene 일곱에 전부 앉는다.

anchor 규칙별 사슬: `persistent-condition-subject-placed` 180 행(60 capability × 3 단계), 전부
`derived`. 이 문서에는 `persistent-active-scene-test` 가 걸리는 자리가 없다 — 활성 scene 이름 조건
12 건은 전부 real scene 에 놓인 오브젝트의 것이라 애초에 이 규칙을 지나지 않는다.

## Risks & Rollback

- **Risks:**
  - **부피가 는다.** 469 → 846. agent 의 scene 맥락 payload 와 인스펙터 목록이 그만큼 길어진다.
    그 값이 성립하려면 `scene_presence` 가 실제로 읽히고, [ARTEL-644](https://artel-asm.atlassian.net/browse/ARTEL-644) 의 지우는 경로가 돌아야 한다.
  - 2 번 규칙은 유도다. 조건이 읽는 타입이 한 scene 에만 있다는 것이 그 오브젝트가 그 scene 에서
    돈다는 증명은 아니다 — static 필드는 scene 을 넘어 살아 있을 수 있다. 그래서 결론을 `derived`
    로 내리고 근거를 `capability_proof` 에 남긴다. 틀린 지목은 되짚어 고칠 수 있다.
  - `V66` 이 `v_content_map_capability` 를 통째로 다시 낸다. ARTEL-644 의 `V65` 도 같은 뷰를 다시
    내므로, 둘 중 나중에 merge 되는 쪽이 상대의 칸을 접어 넣어야 한다.
- **Rollback steps:** `scene_presence` 컬럼을 떨구고 뷰를 V63 정의로 되돌린다. `capability_proof`
  행은 재적재가 `deleteCapabilityChain` 으로 지운다.

## Open Questions

- 없음.
