# 2026-08-19 — 근거의 두 반쪽을 씬·기능 후보로 조인한다

- Date: 2026-08-19
- Jira: ARTEL-485
- Status: Implemented

## Goal

근거 문서(`types` = 코드가 아는 절반, `objects` = 씬이 아는 절반)를 이어 **기능 후보 목록**을 만든다.
DB 를 켜지 않는 순수 계산이고, 골든 문서의 실측 수치로 검증한다.

## Non-goals

- 적재 · upsert · 재적재 보존 (ARTEL-442)
- 씬 전이 후보 (ARTEL-445) · 설명 생성 (ARTEL-447)
- 조건 트리 평탄화. 원본 JSON 을 그대로 들고 다닌다

## Context / Constraints

- 실측 기준 문서: `src/test/resources/contentmap/wv-editor-latest.json` (schema 6, ARTEL-443 이 들여온 것)
- `coding-style.md` — 경계에서 한 번 파싱해 **타입 있는 모델**로 바꾼다. `JsonNode` 를 계산 코드로 흘리지 않는다.
  `render/EvidenceDocument` 는 이미 있는 `JsonNode` 판독기이고 그대로 둔다(렌더러 전용, 문서화된 예외)
- 근거에 없는 규칙을 지어내지 않는다 — 제네릭 소거·별칭 정규화 금지

## 실측이 정정한 전제

| 이슈 본문 | 실측 |
|---|---|
| 배선 조인은 `entryId` 하나 | 길 셋. `owner`+`methodId` 6쌍 · `alsoReachedBy[].entryId` 2쌍 · `handles[].handlerId` 1쌍. 합쳐야 7쌍 |
| `confidence` 가 스키마 어휘 | 문서는 `verified` / `derived` / `partial` |
| `binding` 키가 있다 | 없다. `handles[].channel` 과 `objects[].components[].calls[].event` 가 그 자리 |
| `createdBy` 가 객체 | schema 6 은 문자열 `"<OwnerType>.<field>"` |
| dead code 4타입 | 3타입. `Cards.Util` 은 `calledBy` 가 있다 |
| `inputs` ↔ gesture 1:1 | 아니다. 마우스 2건은 gesture 가 없다(`input-not-branched`) |

## 구현하며 데이터가 다시 정정한 것

계획 단계의 실측표도 두 곳이 부정확했다. 문서가 이겼다.

- **`alsoReachedBy` 는 쌍을 늘리지 않는다.** `owner` + `methodId` 절이 `LoadStoryScene` 을 이미
  잡아 배선 쌍은 그 길 없이도 7이다. 다만 **레코드 수준에서는 살아 있다** — 배선 3건이 그 길로만
  걸린다(`Core.SaveLoadController` 가 `Canvas/continue` 에 닿는 경로). 회귀 방어를 쌍이 아니라
  레코드 바인딩에 걸었다
- **`either` 68건 중 입력을 가르는 것은 4건뿐이다.** 나머지 64건은 순수 상태 논리합이고, 42건은
  다른 `either` 안에 중첩돼 있다. 전부 펼치면 같은 행이 곱해지고(9중첩 레코드가 폭발한다)
  `input_key` 가 단일 값이라는 쪼개기의 이유와도 무관하다. **입력을 가르는 것만** 쪼갠다 —
  446 레코드가 450 갈래가 된다
- **문서에 `GameOverScene` 조건은 없다.** 씬 이름 조건 12건이 전부 `"GameClearScene"` 과 비교하고,
  `GameOverScene` 쪽은 그중 `!=` 갈래 하나다. 필터가 두 연산자를 함께 판정한다
- **한 오브젝트가 같은 타입을 두 번 일 수 있다.** 컴포넌트 35개가 자리 33개가 된다
  (`CombineZone/Zone1` · `Zone2` 가 `DropZone` 을 둘씩 인다)
- **스폰 모호성은 씬 단위다.** `Cards.Card` 계열은 후보 3개가 `GameClearScene` 2 · `TurnBattleScene`
  1 로 갈려, 한쪽은 모호하고 다른 쪽은 깨끗하게 정해진다. 타입 단위로 판정했다면 좋은 귀속까지 버렸다

## Approach (Checklist)

- [x] **Step 1: 모델과 파서** — `contentmap/evidence/EvidenceModel.kt` · `EvidenceParser.kt`
  경계에서 한 번 파싱한다. `condition` 은 타입 있는 트리(`ConditionNode`)와 **원본 JSON 문자열**을 함께 든다 —
  전자는 gesture·씬 이름 조건을 찾는 데 쓰고, 후자가 `capability_evidence.condition_tree` 에 그대로 간다
- [x] **Step 2: 배치 색인** — `join/PlacementIndex.kt`. 컴포넌트 타입 → `(scene, path, selector)` 목록
- [x] **Step 3: 배선 색인** — `join/SceneWiringIndex.kt`. 씬 쪽 `calls[]` 를 코드 쪽 레코드에 잇는 **길 셋**
- [x] **Step 4: 스폰 귀속** — `join/SpawnAttribution.kt`. `createdBy` → `refs[].field` + 컴포넌트 타입 → `carries` →
  씬 경로. 못 찾으면 오너 타입의 배치에서 씬만. 한 씬에 후보 둘 이상이면 비우고 gap
- [x] **Step 5: 갈래와 어휘** — `join/ConditionBranches.kt` · `RecordTranslation.kt`.
  `either` 갈래 쪼개기 · 씬 이름 조건 필터 · interaction 변환 · confidence 번역 · gap 수집
- [x] **Step 6: 조립** — `join/EvidenceJoin.kt`. 순서와 우선순위만 정한다 —
  배선이 있으면 컨트롤이 주소, 없으면 오너의 씬, 프리팹 위 타입은 스폰으로 붙되 조작인 척하지 않는다
- [x] **Step 7: 테스트** — 골든 문서로 수치를 고정한다. 배선은 길을 빼면 쌍이 7→6(HANDLE), 레코드
  바인딩이 3건 사라진다(ARRIVAL)는 것을 각각 건다

## 병렬 트랙

Step 1 이 끝난 뒤 Step 2·3 / Step 4 / Step 5 는 **쓰는 파일이 겹치지 않아** 나눠 진행한다.
모델이 고정된 뒤에만 나눈다 — 그 전에 나누면 세 벌의 서로 다른 모델이 나온다.

## Validation

- **Commands to run:** `./mvnw -Dtest='*Join*,*Evidence*' test`
- **Expected output:** 배선 7쌍 · `alsoReachedBy` 57건 · 스폰 10타입/111건 · dead code 3타입

## Risks & Rollback

- **Risks:** 실측 수치를 테스트에 박으면 문서가 바뀔 때 깨진다. 그것이 의도다 — 수치가 바뀌면
  조인 규칙을 다시 봐야 한다. 다만 **왜 그 수가 나오는지**를 주석에 남겨 다음 사람이 숫자만 고치지 않게 한다
- **Rollback steps:** `git revert`. DB 도 API 도 건드리지 않아 되돌려도 남는 것이 없다

## Open Questions

- `capability_key` 산식(V42 가 적재기에 위임했다)은 ARTEL-442 에서 정한다. 조인 단계는 그 입력
  (`entry_id` · `branch_offset` · 씬 · 정규화한 조건)만 후보에 실어 둔다
