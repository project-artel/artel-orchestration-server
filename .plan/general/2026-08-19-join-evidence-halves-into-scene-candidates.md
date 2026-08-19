# 2026-08-19 — 근거의 두 반쪽을 씬·기능 후보로 조인한다

- Date: 2026-08-19
- Jira: ARTEL-485
- Status: Draft

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

## Approach (Checklist)

- [ ] **Step 1: 모델과 파서** — `contentmap/evidence/EvidenceModel.kt` · `EvidenceParser.kt`
  경계에서 한 번 파싱한다. `condition` 은 타입 있는 트리(`ConditionNode`)와 **원본 JSON 문자열**을 함께 든다 —
  전자는 gesture·씬 이름 조건을 찾는 데 쓰고, 후자가 `capability_evidence.condition_tree` 에 그대로 간다
- [ ] **Step 2: 배치 색인** — `join/PlacementIndex.kt`. 컴포넌트 타입 → `(scene, path, selector)` 목록
- [ ] **Step 3: 배선 색인** — `join/SceneWiringIndex.kt`. 씬 쪽 `calls[]` 를 코드 쪽 레코드에 잇는 **길 셋**
- [ ] **Step 4: 스폰 귀속** — `join/SpawnAttribution.kt`. `createdBy` → `refs[].field` + 컴포넌트 타입 → `carries` →
  씬 경로. 못 찾으면 오너 타입의 배치에서 씬만. 한 씬에 후보 둘 이상이면 비우고 gap
- [ ] **Step 5: 후보 조립** — `join/CapabilityCandidate.kt` · `CandidateAssembler.kt`.
  interaction 변환 · `either` 갈래 쪼개기 · 씬 이름 조건 필터 · confidence 번역 · gap 수집
- [ ] **Step 6: 테스트** — 골든 문서로 실측 수치를 고정한다. 길을 하나씩 빼면 7→6·5·6 으로 떨어지는 것 포함

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
