# 2026-08-26 — 씬마다 조작 단계와 조건을 함께 낸다

- Date: 2026-08-26
- Jira: ARTEL-496
- Status: Implemented

## Goal

`GET /api/projects/{projectId}/game-builds/{gameBuildId}/content-map` 의 `scenes[]` 가 지금은
**개수만** 낸다 — `{total, runnable, needsProbe, notAStep, unreachablePrecondition}`. 화면은 "이 씬에
조작 단계가 14개 있다"까지만 말하고 **그게 무엇인지는 말하지 못한다.**

그 목록을 싣는다. 그리고 목록의 각 줄이 **조건 트리**를 든다.

```
scenes: [{ id, name, walked, capabilities: {...}, steps: [ ... ] }]
```

## Non-goals

- `not-a-step` 기능의 목록. 개수로 충분하다. 골든 문서 기준 440건을 실으면 응답이 아홉 배가 된다
- 페이지네이션. 실측 51건 · 조건 JSON 합계 14.9KB 다. 규모가 달라지면 그때 상한과 `omitted` 를 더한다
- 효과(`capability_effect`) 노출. 기능당 여러 행이라 이 응답에 접지 않는다(V45 가 뷰에서 뺀 것과 같은 판단)
- `givenText` 를 실제로 채우는 일. ARTEL-447 이다. 여기서는 칸만 낸다(지금은 전부 null)
- `EvidenceParser.toCondition` 의 관대함을 넓히는 일. 대문자 `EVERY` 와 이름표 없는 노드를 읽는 것은
  **ARTEL-495** 가 같은 시각에 하고 있고, 이 브랜치는 그 위로 리베이스된다. 중복해서 고치지 않는다

## Context / Constraints

- 응답 모양은 Notion 에 **이미 발행된 계약**이다. 발명하지 않는다. 더할 수는 있고 뺄 수는 없다
- `common/error` 타입 예외를 쓴다. 새 `ResponseStatusException` 금지 — 이 diff 는 읽기만 하므로
  새 예외를 던질 자리가 없다
- 접근 검사는 이미 `ContentMapViewService.read` 안에 있다. 새 창구를 만들지 않으므로 그대로다
- **이 diff 는 읽기 전용이다.** 마이그레이션이 없다

## 정해야 하는 것

### 1. 무엇이 "조작 단계"인가 — **이미 있는 창구를 그대로 읽는다**

단계의 정의는 이미 있다. `v_content_map_capability` 가 그것이고, 읽는 코드도 이미 있다 —
`ContentMapRepository.findCapabilityRows(contentMapId)` 가 그 뷰를 `ContentMapCapabilityRow` 로
내주며, 이 계약이 필요로 하는 칸(`scene_id` · `summary` · `status` · `interaction` · `input_key` ·
`control_label` · `control_path` · `given_text` · `condition_tree`)을 **전부 이미 든다.**

새 질의도, 새 행 DTO 도 만들지 않는다. 만들면 단계의 정의가 두 곳이 되고, 다음에 뷰가 필터를 하나
더 걸 때 한쪽만 낡는다. 그 뷰가 "TC 생성기가 읽는 유일한 창구"이므로, 같은 창구를 읽는다는 것은
**화면이 보는 단계와 TC 생성기가 받는 단계가 갈릴 수 없다**는 뜻이기도 하다.

뷰가 무엇을 거르는지 확인했다(V42 가 낸 현재 정의):

```sql
WHERE c.merged_into IS NULL
  AND c.status <> 'not-a-step';
```

접힌 행(`merged_into`)도 걸러진다. 목록에 뜨면 안 되는 두 가지를 뷰가 이미 다 걸러 준다.

**반대로 `notAStep` 카운트는 그대로 `capability` 직접 집계다.** 뷰가 그 행을 걸러 내므로 뷰로 세면
구조적으로 0 이 된다. 이미 `CapabilityRepository.countByScene` 이 그렇게 하고 있고 건드리지 않는다.
두 출처가 만나는 등식이 그대로 남는다:

```
씬의 steps.size  ==  total - notAStep  ==  그 씬의 뷰 행 수
```

이 등식을 테스트가 못 박는다. 깨지면 목록이나 카운트 한쪽이 거짓말을 시작한 것이다.

### 2. 근거 다건 — **뷰는 접지 않는다. 우리가 접는다**

뷰가 근거를 붙이는 방식을 확인했다:

```sql
LEFT JOIN capability_evidence ce ON ce.capability_id = c.id
```

**평범한 `LEFT JOIN` 이고, 뷰 안에 접는 장치가 없다.** 오늘 `capability_evidence.capability_id` 는
PK 라(V40) 실제로 1:1 이고 실측 465건도 전부 정확히 1건이지만, **그 가정을 이 코드에 두지 않는다.**
근거가 기능당 여러 행이 되는 순간 뷰 행이 곱해지고, 그러면

- 씬의 `steps` 에 같은 기능이 두 줄 서고
- `steps.size == total - notAStep` 등식이 조용히 깨진다

서비스에서 **기능 하나당 한 줄로 접는다.** 그냥 `distinctBy` 로 접지 않는 이유: 뷰의 `ORDER BY` 는
`(scene_name, capability_id)` 라 같은 `capability_id` 를 든 행들 사이의 순서가 정의되지 않고, 그러면
**어느 근거가 남는지가 우연**이 된다. 접기 전에 `(capabilityId, entryId, branchOffset)` 로 다시
정렬해 그 선택을 코드가 적어 둔다.

`findCapabilityRows` 에 `DISTINCT ON` 을 넣지 않는 이유: 그 질의는 TC 생성기가 쓰는 것이고 KDoc 이
`ORDER BY scene_name ASC, capability_id ASC` 를 **프롬프트 캐시 때문에** 못 박아 두었다.
`DISTINCT ON` 은 정렬 선두를 자기 키로 요구하므로 그 약속을 깬다. 남의 창구의 정렬 계약을 이 화면의
사정으로 바꾸지 않는다.

이 저장소가 효과를 조인하지 않는 것과 같은 이유의 다른 처방이다 — 효과는 곱해질 것이 확실해서 아예
빼고, 근거는 하나여야 맞는 것이라 하나로 좁힌다.

### 3. 조건 정규화 — **`EvidenceParser` 를 재사용한다. 파서를 고치지 않는다**

`capability_evidence.condition_tree` 는 SDK 가 준 JSONB 원본이다. 그것을 그대로 응답에 실으면
계약이 요구하는 정규화(`kind` 는 늘 소문자, 이름표 없는 노드 없음)가 성립하지 않는다.

정규화를 새로 쓰지 않는다. 적재기가 이미 같은 트리를 `EvidenceParser.toCondition` 으로 읽고 있고,
두 벌을 만들면 **두 곳이 서로 다르게 관대해진다** — 한쪽만 대문자 `EVERY` 를 읽게 되는 식으로.

`toCondition` 이 지금 `private` 이라, **동작을 건드리지 않고 창구만 연다:**

```kotlin
fun parseCondition(node: JsonNode): ConditionNode = node.toCondition()
```

`toCondition` 본문에서 멀리, `parse(root)` 바로 아래에 둔다. ARTEL-495 가 같은 파일의 `toCondition`
과 `GroupKind.from` 을 고치는 중이라 리베이스에서 같은 줄을 다투지 않게 하려는 것이다. 그쪽이 파서를
더 관대하게 만들면 **이 엔드포인트도 자동으로 그만큼 관대해진다.** 그것이 재사용의 값이다.

`toCondition` 은 던지지 않는다(확인함 — `EvidenceParser` 의 `throw` 여섯 개는 전부 문서 수준
필수 키 검사이고 조건 트리 쪽에는 없다). 입력도 JSONB 컬럼이라 파싱이 실패할 수 없다. 그래서 이
경로에는 새 예외가 없다.

### 4. 조건 트리의 wire 모양 — 새 DTO 한 벌

`ConditionNode` 는 내부 모델이라 그대로 직렬화하면 `Group(kind=EVERY, parts=[...])` 처럼
**계약에 없는 모양**이 나간다(대문자 enum, `group` 이라는 이름표). 경계용 DTO 를 따로 둔다.

```
{ kind: "always" }
{ kind: "test",    left, operator, right, context|null, subjectLost|null, offset }
{ kind: "gesture", input, offset }
{ kind: "every"|"either", parts: [...] }
{ kind: "unknown", reason, unread|null }
```

`Group` 이 `every` / `either` 로 **펴져서** 나간다. 화면이 `kind` 하나만 보고 갈래를 정할 수 있어야
하는데, `{kind:"group", groupKind:"every"}` 는 두 칸을 봐야 한다.

`ConditionNode.Unknown.reason` 은 모델에서 nullable 이지만 계약은 값을 요구한다. 파서가
`reason ?: kind` 로 채우므로 실제로는 늘 값이 있고, 그래도 null 이면 `"unknown"` 으로 떨어뜨린다 —
이름표 없는 노드가 나가면 화면이 그릴 수 없다.

`sealed interface` + 변종마다 `kind` 를 고정 문자열 프로퍼티로 든다. Jackson 이 프로퍼티를 그대로
쓰므로 `@JsonTypeInfo` 가 필요 없고, 응답 전용이라 역직렬화 배선도 필요 없다.

`GroupKind.wire` 를 그대로 쓴다. 매핑표를 따로 적으면 ARTEL-495 가 어휘를 넓힐 때 두 곳이 갈린다.

### 5. `given` 이 null 인 때

`origin` 이 `observed` · `inferred` · `human` 인 기능은 `capability_evidence` 행이 없다(그 표는
evidence 출신에만 붙는다 — 복합 FK 가 강제한다). 뷰의 `LEFT JOIN` 이 `condition_tree` 를 null 로
내주고, 그때 `given` 은 **null 이다.** `{kind:"always"}` 로 채우지 않는다 — "조건이 없다"와
"근거가 없어 조건을 모른다"는 다른 말이고, 뒤쪽을 앞쪽으로 적으면 TC 가 없는 근거를 지어낸다.

`condition_tree` 가 `{}` 인 경우는 다르다. 파서가 그것을 `Always` 로 읽고, `{kind:"always"}` 가
나간다. 근거가 "조건 없음"이라고 말한 것이라 맞는 값이다.

### 6. 순서

씬 안에서 `capability.id` 오름차순이다. 적재 순서이자 문서 순서라 같은 문서를 다시 적재해도 흔들리지
않는다. 화면이 정렬을 다시 하지 않아도 되게 서버가 순서를 정하는 것은 `scenes` 를 이름 오름차순으로
내는 것과 같은 판단이다.

## 왜 조건이 필수인가

실측 GameClearScene 의 `runnable` 6개는 `summary` · `inputKey` · `status` 가 **전부 같다:**

```
`any` 키 → Scenes.GameClearController.ShowGettedCard()   runnable   × 4
`any` 키 → Scenes.GameClearController.Update()           runnable   × 2
```

`given_text` 는 51건 전부 null 이다(ARTEL-447 미완). 실제로는 `condition_tree` 해시가 전부 달라
**조건만 다른 여섯 갈래**다. 조건을 싣지 않으면 화면이 똑같은 줄 여섯 개를 그리고, 사람은 그것을
중복 버그로 읽는다.

## Approach (Checklist)

- [x] **Step 0: Recon** — 기준선 241건 · 전부 녹색. 뷰의 `WHERE` 와 근거 조인을 확인했다.
      `findCapabilityRows` 가 필요한 칸을 이미 전부 든다는 것을 확인했다
- [x] **Step 1: 파서 창구** — `evidence/EvidenceParser.kt` 에 `parseCondition(node)` 한 줄.
      `toCondition` 본문은 건드리지 않는다
- [x] **Step 2: wire DTO** — `dto/ConditionNodeResponse.kt` (신규).
      `ConditionNode` → 계약 모양. `Group` 을 `every`/`either` 로 편다
- [x] **Step 3: 응답 DTO** — `dto/ContentMapViewDtos.kt` 에 `SceneStepResponse`,
      `ContentMapSceneResponse.steps`
- [x] **Step 4: 서비스** — `ContentMapViewService` 가 `ObjectMapper` 를 받아 파서를 만들고,
      `findCapabilityRows` 를 기능당 한 줄로 접어 씬별로 묶는다. **새 저장소 메서드 없음**
- [x] **Step 5: 테스트** — 아래 Validation
- [x] **Step 6: `insomnia-sync` 는 하지 않는다.** 경로가 그대로다

## Validation

- **Commands:** `./mvnw -Dtest='kr.artel.orchestration.contentmap.**' test` — 기준선 241건에서
  전후를 비교한다
- **Cases**
  - `ContentMapViewGoldenTest` (기존 파일에 추가)
    - **씬마다 `steps.size == total - notAStep`.** 목록과 카운트가 같은 표를 본다는 증거
    - **`steps` 총수 == `v_content_map_capability` 행 수.** 근거 조인이 행을 곱하지 않는다는 증거
    - `capabilityId` 가 응답 전체에서 유일하다 — 곱해졌다면 여기서 걸린다
    - `steps` 에 `not-a-step` 이 한 줄도 없다. `status` 는 계약의 세 값 안이다
    - 조건 노드의 `kind` 가 전부 소문자이고 계약 어휘 안이다(트리를 재귀로 훑는다)
    - **골든 문서에서 직접 센 값**으로 씬별 목록 길이를 못 박는다. 실측(`editor-play`) 수치를
      골든에 하드코딩하지 않는다 — 다른 문서다
    - 같은 `summary`·`inputKey`·`status` 를 든 줄이 여럿일 때 **`given` 이 그들을 가른다**
  - `ContentMapStepContractTest` (신규, DB 없음)
    - 다섯 `kind` 의 wire 모양을 못 박는다. `ScanResponseContractTest` 와 같은 결 —
      home 화면이 이 JSON 을 보고 만들어지므로 칸 이름이 바뀌면 조용히 못 알아듣는다
    - `every` / `either` 가 중첩해도 모양이 유지된다
    - 이름표 없는 노드가 나가지 않는다

## Risks & Rollback

- **읽기 전용이다.** 마이그레이션도, 쓰기 경로 변경도 없다. 기존 칸을 지우거나 이름을 바꾸지
  않으므로 지금 응답을 읽는 화면은 아무것도 못 느낀다
- 응답이 커진다. 골든 문서 기준 51 단계 · 조건 JSON 합계 14.9KB 이고, 씬 수가 아니라 **조작 있는
  기능 수**에 선형이다. 상용 게임에서 이 수가 수천이 되면 자를 자리는 `steps` 이고, 그때 더할 것은
  상한과 `omitted` 수다
- `findCapabilityRows` 가 `SELECT *` 라 응답이 안 쓰는 칸까지 실어 온다. 51행이라 지금은 값이
  없는 최적화이고, 새 질의를 만드는 대가(단계 정의가 두 곳이 된다)가 더 크다
- 리베이스 위험: ARTEL-495 가 같은 파일(`EvidenceParser.kt`)을 고친다. 새 메서드를 `toCondition`
  본문에서 멀리 두어 줄이 겹치지 않게 한다
- **Rollback:** `git revert`. `steps` 를 안 읽는 소비자는 영향이 없다

## Rejected feedback

- **뷰에 `DISTINCT ON` 을 넣자** — 거절. `findCapabilityRows` 의 정렬은 TC 생성기의 프롬프트 캐시
  계약이고 `DISTINCT ON` 이 그것을 깬다. 접기는 이 화면의 사정이므로 이 화면 쪽에서 한다
- **접기 자체를 빼자** — 거절. `capability_evidence.capability_id` 가 오늘 PK 라 구조적으로 1:1 이니
  접는 코드가 현재 스키마에서는 죽은 코드라는 지적이고, 사실관계는 맞다. 그래도 접는다. 이 응답이
  거는 등식(`steps.size == total - notAStep`)이 그 PK 하나에 매달려 있고, PK 가 사라지는 날 깨지는
  것은 **조용히 틀린 화면**이지 컴파일 오류가 아니다. 두 줄로 그 의존을 끊을 수 있으면 끊는다.
  대신 그 두 줄이 왜 있는지를 주석이 적어 두어, 다음 사람이 "필요 없는데?"로 지우지 않게 한다

## Open Questions

- 없음. 계약은 발행됐고, 실측 수치는 이슈 본문에 있다
