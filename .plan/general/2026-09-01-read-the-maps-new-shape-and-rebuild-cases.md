# 2026-09-01 — 저작이 지도의 새 구조를 읽게 하고 TC 를 다시 짠다

- Date: 2026-09-01
- Jira: None
- Status: Draft

## Goal

**QA 런을 한 번도 안 돌린 상태(콜드플레이)에서** 저작이 실행 가능한 시나리오를 내게 한다.

이 갈래는 2026-08-26 의 develop 위에서 자랐고, 그 뒤 develop 과 SDK 가 `content_map` 을
크게 바꿨다. 우리 코드는 그 전 계약을 읽는다. 아래 다섯 자리가 그 어긋남이고, 전부
**QA 런 없이** 고칠 수 있다.

## Non-goals

- QA 런이 채우는 `screen` · `screen_transition` · `capability_observation` 을 쓰는 일.
  2회차 이야기다. 이번에는 정적 분석만으로 얼마나 되는지를 올린다.
- 순서를 정하는 점수식을 손보는 일. 근거가 없는데 점수를 더하면 게임 하나에 맞춘 규칙이
  된다(런 242 · 243 에서 두 번 겪었다).
- `capability.given_text` 를 채우는 일(ARTEL-447). **저작이 끝나면 우리가 채우기로 했다**
  (2026-09-01 결정). 지금은 아니다 — 저작은 `condition_tree` 를 직접 읽으면 되고, 문장을
  먼저 채우면 누군가 그것을 다시 파싱한다.

## Context / Constraints

### 실측 (2026-09-01, 새 SDK 로 스캔한 word-venture, DB 새로 만든 뒤)

```
scene                   7        정적 분석
scene_edge             19        전부 source=static · verified 0 · capability_id 19/19
screen                  0        QA 런이 채운다
capability            419        placed 401 · persistent-unconfirmed 18
capability_evidence   419        condition_tree 419/419
capability.given_text   0/419
test_case              87
```

### 다섯 자리

**① `given_text` 는 죽은 칸인데 저작만 그것을 읽는다**

`ContentMapViewDtos` 가 이미 적어 뒀다 — *"`givenText` 조건을 한 줄로 옮긴 사람용 글.
**오늘은 전부 null 이다**(ARTEL-447 미완). 화면은 `givenText ?? given` 으로 고른다"*.
조회 화면은 이미 트리로 옮겼고, `ScenarioPathService` 다섯 자리와
`ScenarioCaseFactService` 하나만 옛 칸에 남았다.

```kotlin
// ScenarioPathService.kt:371, 374, 420, 703, 758
ScenarioStateReader.violated(capability.givenText, state)   // givenText 가 늘 null
```

`violated()` 는 비교가 하나도 없으면 `null`(위반 없음)을 돌려준다. 그래서 길찾기가
*"이 조작은 지금 상태에서 못 한다"* 를 **한 번도 못 짚는다.**

**② agent 는 조건을 아예 못 받는다**

`SceneCapabilityView` 에 `givenText`(null)만 있고 조건 트리 칸이 없다.

**③ 서로 다른 조작이 `또는` 으로 합쳐진다**

```
`Canvas/MapSceneButton` 또는 `Canvas/continue` 을(를) 클릭한다 (LoadPlayData() == -1 일 때)
TitleScene 에 진입해 관찰한다 (LoadPlayData() == -1 일 때) → `Canvas/continue` 의 표시 상태가 `false`
```

같은 표 안에서 **없는 버튼을 누르라고 한다.** 지도는 둘 다 말해 주고 있다.

**④ 전제가 실행자가 만들 수 없는 말이다**

구버전 66건(프로젝트 3, `oldsnap` 에 복원해 직접 조회)과 나란히 놓으면 셋이 다르다.

| | 구버전 66건 | 지금 87건 |
|---|---|---|
| **가르는 축** | **조작(버튼)** | **조건 갈래** |
| 존재 확인 | `표시 상태를 확인한다` → `활성/표시` · `비활성/숨김` | `화면에 있는지 확인한다` → `있다` |
| 구별 꼬리 | 0건 | 64건 (74%) |
| 같은 씬 문구 겹침 | 39건 (59%) | 74건 (85%) |
| 관측 | 23건 | 47건 |

```
구버전  `Canvas/MapSceneButton`를 클릭한다   (전제 없음)             → StoryScene
        `Canvas/continue`를 클릭한다        (LoadPlayData() != -1)  → Map_scene

우리    `MapSceneButton` 또는 `continue` 클릭 (LoadPlayData() == -1 일 때) → StoryScene
        `MapSceneButton` 또는 `continue` 클릭 (LoadPlayData() != -1 일 때) → Map_scene
```

**실행하는 사람은 버튼을 고르지 갈래를 고르지 않는다.** 구버전은 조작마다 한 줄이고 조건은
전제 칸에 둔다. 우리는 조작을 합치고 갈래로 갈라, 그 결과 *"없는 버튼을 누르라"* 는 줄이 나왔다.

**전제 표현은 구버전도 같다.** 66건도 `TitleSceneManager.saveLoadController.LoadPlayData() == -1`
을 쓴다 — 호출을 값으로 푸는 것은 구버전도 안 했다. (이전 초안에서 `MapMove.StagePosition == -1`
과 대조한 것은 153건짜리 editor capture 파일을 잘못 인용한 것이다.)

그 밖에 지금 표에 실제로 나가는 것들:

```
TurnBattleScene 에 진입해 관찰한다 (_ == 3 일 때)                      ← 매개변수가 안 풀림
`Combine` 을(를) 클릭한다 (combineZone != null, tag) != 0 일 때)       ← 괄호가 깨진 문장
TurnBattleScene 에 머무르며 관찰한다 (Count != 1, Count == 1 일 때)     ← 스스로 모순
`CombineSystem/CombineZone/Button.gameObject` 의 표시 상태가 `true`     ← 코드 꼬리가 남음
```

**⑤ `DontDestroyOnLoad` 가 케이스로 샌다**

가짜 `DontDestroyOnLoad` 씬은 develop 이 없앴다(씬 7개 전부 진짜다). 대신 그 오브젝트의
`capability` 를 **real scene 전부**에 앉히고 `scene_presence` 로 표시한다.
`ContentMapCapabilityRow` 의 칸 주석이 우리를 콕 집어 경고한다 — *"`placed` 와 같은 줄로
읽으면 **TC 생성기가 확인된 적 없는 것을 그 `scene` 의 사실로 쓴다**"*.

지금 6건이 샌다. 여섯 화면에 같은 줄이 하나씩이고 전부
`this.gameObject 이(가) 사라진다`(싱글턴 중복 제거)다.

### 곁에 있는 제약

- **SDK 가 조건 모양을 바꿨다**(ARTEL-700). `IsStreaming != 0` → `streamingCoroutine != 0`.
  `schema` 번호는 6 그대로다. 케이스 정체가 조건 구조를 포함하므로 **재적재하면 옛 케이스를
  못 알아본다.** 이번 작업 중에 정체 규칙을 건드리면 그 영향이 겹친다.
- `given_text` 를 되살리는 방향은 택하지 않는다. 문장 되읽기는 V78 에서 이미 걷어낸 길이다.

## Approach (Checklist)

### Step 0: Recon — 끝났다

- [x] `given_text` 를 읽는 자리 전수(`ScenarioPathService` 5 · `ScenarioCaseFactService` 1)
- [x] `ScenarioStateReader.guardsIn(ConditionNode)` 가 **이미 있다** — 트리에서 `Guard` 를 뽑는 길이 뚫려 있다
- [x] `capability_evidence.capability_id` 가 **PK** 다(`V40:183`). `findById(capabilityId)` 로 트리를 바로 얻는다
- [x] `CaseConditionReader` 가 케이스에 대해 같은 일을 이미 한다 — `capability` 판을 그 옆에 세운다

### Step 1: 조건을 트리로 읽는다 (①②)

- [ ] `ScenarioStateReader.violated(condition: ConditionNode?, state)` 오버로드를 더한다.
      본문은 `guardsIn(condition).firstOrNull { … }` 로, 문자열 판과 **같은 규칙**을 쓴다
      (모르는 값은 위반이 아니다).
- [ ] `CapabilityConditionReader` 를 `CaseConditionReader` 옆에 만든다.
      `capability_evidence` 를 `capabilityId` 로 읽어 `EvidenceParser.parseCondition` 한다.
      **읽는 곳을 하나로 못 박는다** — `CaseConditionReader` 가 그렇게 만들어진 것과 같은 이유다.
- [ ] `ScenarioPathService` 다섯 자리를 그것으로 바꾼다. 트리를 못 얻으면 지금처럼
      `givenText` 로 물러선다(구버전 엑셀 행 대비).
- [ ] `ScenarioCaseFactService:119` `given = capability.givenText` 도 같이 옮긴다.
- [ ] `SceneCapabilityView` 에 조건 트리 칸을 더하고 `SceneContextService:167` 에서 싣는다.
      직렬화 모양은 `ConditionNodeResponse` 를 재사용한다(조회 API 가 이미 그것으로 낸다).

> **경계**: `ScenarioPathService` 는 `capabilityRepository.findById` 로 `CapabilityEntity` 를
> 들고 있다. 트리는 다른 표에 있으므로 조회가 한 번 더 는다. 지금 경로 계산은
> 전건 N² 를 미리 푸는 구조라(`ScenarioFlowMatrix`) 이 추가 조회가 곱해진다 —
> `contentMapId` 단위로 한 번 읽어 메모리에 들고 도는 편이 맞다.

### Step 2: `DontDestroyOnLoad` 를 안 쓴다 (⑤)

- [ ] `ContentMapObservationRow` 에 이미 `scenePresence` 가 실려 있다(이번 갈래에서 더했다).
      `MapTestCaseGenerator.keptAsObservation` 에서 `persistent-unconfirmed` 를 뺀다.
- [ ] 조작 행 쪽(`findStepCapabilityRows`)도 같은 잣대를 쓸지 정한다.
      지금 지도에서는 그쪽에 `persistent-unconfirmed` 가 없어 실측으로는 차이가 없다.

### Step 3: TC 를 다시 짠다 (③④)

- [ ] **`또는` 으로 합치는 조건을 좁힌다**(`withInterchangeableInputs`).
      지금 열쇠는 `(scene, precondition, expected, 관측인가)` 다. 여기에
      *"그 전제 아래에서 두 대상이 모두 화면에 있나"* 를 더한다 — 지도가 `active-state`
      효과로 그것을 말해 준다. 없는 것을 누르라고 하지 않는 것이 규칙이고,
      게임 하나에 맞춘 규칙이 아니다.
- [ ] ~~호출을 그 호출이 쓰는 값으로 읽는다~~ — **드롭.** 구버전 66건도 안 했고, SDK 의
      `PredicateConditions`(ARTEL-700)가 이미 그 방향으로 간다. 지도가 주는 것에 맞춘다.
- [ ] **존재 확인을 표시 상태까지 말하게 한다**(`screenElements`).
      지금은 `화면에 있다`뿐이라 꺼져 있는 상태를 못 말한다. 구버전은
      `활성/표시 상태다` · `비활성/숨김 상태다` 를 갈랐다.
- [ ] **깨진 문장을 고친다.** `(combineZone != null, tag) != 0` 같은 줄은 렌더가 아니라
      조건을 문장으로 옮기는 자리의 결함이다. 먼저 몇 건인지 센다.
- [ ] **안 풀린 매개변수(`_ == 3`)** 는 `MapTestCaseLocals` 가 이미 사유를 남긴다
      (`unsettable-precondition`). 문장에 `_` 가 남는 것만 막는다.

### Step 4: 진행도를 올리는 것을 케이스로 (열어 둔 질문)

`MapMove.StagePosition` 은 케이스 31건이 요구하고, 그것을 올리는 자리가 지도에 있다:

```
TurnBattleScene | Combat.Enemies.BattleWaveController.MoveNext() (조작 없음)
                | actionability=not-a-step · observability=unknown · trigger_root=Start1
                | write · MapMove.StagePosition · +1
```

**세 관문에 걸려 있다.**

1. `findObservationRows` 가 `observability='observable'` 만 낸다 — 이 행은 `unknown` 이다
2. `WATCHED_ROOTS` 에 `Start1` 이 없다 — 코루틴이라 컴파일러가 이름을 바꿨다
   (`MapTestCaseSiblings.sourceMethod` 가 하는 정규화를 `trigger_root` 에는 안 한다)
3. `VISIBLE` 에 `write` 가 없다 — 값만 바뀌는 것은 눈으로 확인할 수 없다

- [ ] 셋 중 **무엇을 여는 것이 맞는지 정한다.** 3번을 그냥 열면 "값이 바뀐다"를 확인하라는
      케이스가 쏟아진다(`write` 212건). 관측 케이스가 아니라 **경로 재료**로 쓰는 편이
      맞을 수도 있다 — 그때는 `ScenarioPathService.Writer.Automatic` 자리다.
- [ ] 구버전 대조(진짜 66건, 프로젝트 3): TurnBattleScene 24건에도 **값을 올리는 케이스는
      없다.** 대신 **전투 중 사건을 12건 낸다**(`충돌` · `Attack` · `이벤트`). 우리는
      `WATCHED_ROOTS` 로 그것을 통째로 뺐다 — 어느 쪽이 옳은지가 Open Question 이다.

      씬별 분포(구버전 66 / 우리 87):
      TurnBattleScene 24 · Map_scene 21 · EndingScene 7 · StoryScene 6 · TitleScene 5 ·
      GameClearScene 3 — 구버전에는 **GameOverScene 이 아예 없다.**

## Validation

- **Commands to run:**
  ```
  ./mvnw -o -Dkotlin.compiler.execution.strategy=in-process test
  # 지도를 다시 앉히고 표를 눈으로 본다
  curl -X POST localhost:8081/internal/test-cases/content-maps/1/regenerate
  ```
  ```sql
  -- ① 길찾기가 조건을 보게 됐나
  select count(*) from capability c join capability_evidence e on e.capability_id=c.id
  where e.condition_tree is not null;              -- 419/419 이어야 한다
  -- ⑤ DDOL 이 케이스로 나가나
  select count(*) from test_case t join capability c on c.capability_key=t.capability_key
  where c.scene_presence='persistent-unconfirmed'; -- 6 → 0
  -- ③ 서로 다른 조작이 합쳐졌나
  select count(*) from test_case where step like '%또는%';
  ```

- **Expected output:**
  - 골든 87 → 81 (⑤ 로 6건이 빠진다)
  - `또는` 으로 합쳐진 줄이 줄고, 그중 "없는 대상을 누르라"는 줄이 0이 된다
  - 저작 한 판에서 `Writer.Blocked` 가 처음으로 나온다 — 지금은 조건을 안 봐서 0이다

## Risks & Rollback

- **Risks:**
  - **정체가 바뀌면 시나리오가 상한다.** 케이스 정체는 조건 구조를 포함한다. 문장만 고치는
    항목(③④)은 `identity` 를 안 건드리지만, 조건 트리 자체를 고쳐 쓰면 정체가 갈린다.
    Step 3 에서 조건을 **다시 쓰는** 항목은 정체 계산 전에 적용되는지 확인해야 한다.
  - **SDK 재스캔이 이미 정체를 흔든다**(ARTEL-700). 이 작업과 겹치면 원인을 가르기 어렵다 —
    지도를 고정해 두고 코드만 바꿔 재는 편이 낫다.
  - **경로 계산 비용.** Step 1 이 조회를 하나 더 붙이는데 전건 N² 에 곱해진다.
    케이스 83건 = 6,806칸이다. 미리 읽어 들고 도는 것으로 막는다.
  - **`또는` 을 좁히면 케이스가 는다.** 실행하는 사람이 볼 표가 길어진다. 몇 건이나
    갈라지는지 먼저 센다.

- **Rollback steps:**
  - Step 1 은 `givenText` 물러서기를 남겨 두므로 오버로드를 안 부르면 원래대로다.
  - Step 2·3 은 생성기 안이라 되돌리면 다음 적재부터 원래 표가 나온다. 이미 앉은 행은
    `MapTestCaseWriter` 가 갈아 끼운다.

## Open Questions

- **`LoadPlayData() == -1` 을 값으로 푸는 일이 오케 것인가 SDK 것인가.**
  SDK 가 bool 반환에 대해 이미 같은 일을 한다(ARTEL-700). 숫자 반환도 SDK 가 읽으면
  지도가 한 번만 정확해지고 소비자 전부가 덕을 본다.
- **진행도를 올리는 것을 "케이스"로 낼 것인가 "경로 재료"로 쓸 것인가.**
  구버전도 케이스로는 안 냈다. 사람에게 *"웨이브를 다 깨라"* 를 시키는 것은 스텝이지
  검증이 아닐 수 있다.
- **전투 중 사건(`OnTriggerEnter2D` 등)을 케이스로 낼 것인가.** 구버전은 낸다.
  우리는 *"사람이 시점을 잡을 수 없다"* 는 이유로 `WATCHED_ROOTS` 에서 뺐고, 그 판단이
  아직 유효한지 다시 봐야 한다. 지금 `not-a-step + observable` 중
  `OnTriggerEnter2D` 12 · `TakeHit` 9 · `Attack` 6 이 여기 걸린다.
- **`scene_presence` 를 조작 행에도 적용할 것인가.** 이번 지도에서는 차이가 0이라
  실측으로 못 가른다.
