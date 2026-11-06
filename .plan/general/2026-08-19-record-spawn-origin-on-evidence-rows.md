# 2026-08-19 — 스폰 출처를 적을 자리를 만든다

- Date: 2026-08-19
- Jira: ARTEL-484
- Status: Implemented

## Goal

`spawning()` 이 씬에 귀속시킨 기능이 **무엇이 만들었는지**를 잃지 않게 자리를 만들고,
그 주소가 **조준 대상으로 새지 못하게** DB 가 막는다.

- `capability.spawned_by_field` — `unplaced[type].createdBy` 항목 원문. 예: `Cards.CardManager.cardPrefab`
- `capability.spawned_by_scene_path` — 그 필드를 쥔 씬 오브젝트 경로. 예: `CardSystem/CardManager`

## Non-goals

- 적재 로직. 두 컬럼을 채우는 규칙은 ARTEL-442 가 정한다
- 관측 축 · 적용 축을 CHECK 로 묶는 것. 그 둘은 판정이고 나중에 다른 이슈가 옮긴다
  (ARTEL-452 · ARTEL-461). 실행 축만 묶는다 — 만들어지는 쪽은 스텝이 될 수 없다는 것은
  판정이 아니라 정의고, 기본값이 `runnable` 이라 안 묶으면 TC 창구로 샌다
- `createdBy` 가 객체 배열이 되는 schema 7 대응 (ARTEL-459). 그때는 flat 컬럼을 늘리지 않고
  `spawn_origin JSONB` 한 칸으로 바꾼다 — 세 칸째가 붙는 순간이 JSONB 가 값을 하는 지점이다
- 인덱스. 111행짜리 테이블에 한 번 쓰는 검증 질의를 위한 인덱스는 넣지 않는다.
  `spawned_by_field` 는 `Type.field` 를 붙여 담아 "만드는 쪽으로 묶기"가 접두사 매칭이라
  기본 콜레이션 btree 가 쓰이지도 않는다. 실제 `EXPLAIN` 을 보고 나중에 붙인다

## Context / Constraints

- V40 은 이미 번호가 붙었고 checksum 이 계약이다. V42~V45 와 같이 **새 번호로 얹는다** → `V46`
- 실측(`wv-editor-latest.json`, schema 6): `unplaced` 14타입 중 `createdBy` 가 찬 것 10타입 ·
  evidence 111건. `refs[].carries` 는 70개 참조 중 3건뿐이라 `spawned_by_scene_path` 는 대개 NULL
- `createdBy` 항목은 schema 6 에서 **문자열** `"<OwnerType>.<field>"` 다. 객체가 되는 것은 ARTEL-459 이후

## Approach (Checklist)

- [x] **Step 0: Recon** — V42 · V45 의 얹기 방식, `CapabilityEntity`, `ContentMapSchemaTest` 패턴 확인
- [x] **Step 1: 마이그레이션** — `V46__record_spawn_origin_on_capability.sql`
  - `capability.spawned_by_field VARCHAR(1024)` (폭은 `entry_id` 와 같다)
  - `capability.spawned_by_scene_path VARCHAR(512)` (폭은 `control_path` 와 같다)
  - `ck_capability_spawn_path_needs_field` — 경로만 있고 필드가 없는 행을 막는다
  - `ck_capability_spawn_needs_evidence` — `origin='evidence'` 인 행만 스폰 출처를 갖는다
  - `ck_capability_spawn_has_no_control` — 스폰 행은 `control_path` · `control_selector` ·
    `control_label` 이 비고 `interaction='none'` · `input_phase IS NULL` ·
    `actionability='not-a-step'` 이다. **이 이슈의 본체**
  - `v_spec_gap` 재생성 — `when-missing` 분기에서 스폰 행을 뺀다
- [x] **Step 2: 엔티티** — `CapabilityEntity.spawnedByField` · `spawnedByScenePath` (기본값 `null`)
- [x] **Step 3: 테스트** — `ContentMapSchemaTest` 에 다섯 가지
  - 스폰 행이 왕복하고 `status` 가 `not-a-step` 이다
  - 조준 대상 넷(`control_path` · `control_selector` · `control_label` · `interaction`)과
    축을 안 정한 경우가 각각 거절된다
  - 관측 출신 기능에는 스폰 출처를 적을 수 없다
  - 필드 없이 경로만 적을 수 없다
  - 스폰 행이 `when-missing` 에서 빠지고 나머지 분기로 간다
- [ ] **Step 4: PR** — base 는 `feat/...-ARTEL-479`. 스택 위에 얹는다

## Validation

- **Commands to run:**
  - `./mvnw -Dtest=ContentMapSchemaTest test` (이 저장소는 Maven 이다. Gradle 아님)
  - `./scripts/check-flyway-migrations.sh feat/orchestration-status-를-실행-관측-적용-세-축으로-가른다-ARTEL-479`
    — PR 을 열기 직전에 한 번 더 돌린다. 스택 위에 있어 다른 미머지 브랜치가 46 을 집을 수 있다
- **Expected output:** `Tests run: 34, Failures: 0, Errors: 0` · `OK: no version collisions`

## 적재기(ARTEL-442)가 이어받는 규칙

스칼라 컬럼이 규칙을 못 박는다. 여기서 정하고 442 가 지킨다.

- `createdBy` 는 목록이다. 이 칸에는 **씬 귀속을 실제로 결정한 하나**만 담는다
- 한 씬에 후보가 둘 이상이면 (실측: `Cards.Card` 3건 · `Cards.Order` 3건 · `DraggableCard` 3건)
  비우고 근거의 `gaps` 에 사유를 남긴다. 첫 항목을 조용히 집으면 "누가 만드는지 안다"와
  "여럿 중 하나를 골랐다"가 구분되지 않는다
- `spawned_by_scene_path` 가 비었다고 `gaps` 에 사유를 남기지 않는다. 되짚기 축(`method_id` ·
  `call_path`)과 달리 이 값은 근거가 줄 때만 있는 덤이다

## Risks & Rollback

- **Risks:**
  - V46 번호를 다른 미머지 브랜치가 집을 수 있다 → PR 직전에 정적 검사 재실행
  - `interaction='none'` 을 CHECK 로 묶어 두면, 나중에 스폰된 오브젝트가 실제로 눌린다는 것을
    알게 됐을 때 이 행을 고쳐 쓸 수 없다. 의도한 대가다 — 그 지식은 `origin='observed'` 인
    **다른 행**으로 들어오고, 스캔이 덮어쓰는 행과 섞이면 안 된다
- **Rollback steps:** `git revert`. 컬럼은 nullable 이고 읽는 코드가 없다. `v_spec_gap` 은 V45
  정의로 돌아간다

## Rejected feedback

- **"`spawned_by_scene_path` 를 이 이슈에서 빼라"** (medium) — 뺐다면 귀속이 *정확*(문서가 경로를
  줬다)한지 *유도*(오너 타입의 배치에서 씬만 얻었다)인지가 사라진다. 적재기가
  `analysis_confidence` 를 그 둘로 가르므로 같은 이슈에서 나온다
- **"`v_content_map_capability` 에도 두 칸을 노출하라"** (fast) — 스폰 행은 `not-a-step` 이라 그 뷰의
  `WHERE status <> 'not-a-step'` 에 걸러진다. 창구에 안 나오는 행의 컬럼을 창구에 더하는 것은
  소비자에게 죽은 칸을 주는 것이다.

  단, 구현 검토(pair-review)가 이 근거의 구멍을 짚었다: `actionability` 기본값이 `runnable` 이라
  축을 명시하지 않고 넣은 스폰 행은 `status` 가 `needs-probe` 로 유도돼 **창구를 통과한다.**
  그래서 축을 CHECK 에 묶었고, 그 뒤에야 이 거절이 성립한다

## 구현 검토에서 고친 것

- `control_label` · `input_phase` · `actionability` 를 조준 누출 CHECK 에 함께 묶었다
- `EvidenceGap.SPAWN_ORIGIN_AMBIGUOUS` 를 어휘에 더했다. 후보가 둘 이상일 때 남길 사유가
  닫힌 enum 에 없으면 `v_spec_gap` 이 영영 그 분기를 못 만난다
- `spawned_by_scene_path` 의 정밀도를 `analysis_confidence` 로 직접 정한다고 쓴 주석을 고쳤다.
  V44 가 그 값을 사슬 최솟값으로 정의했으므로, 귀속 단계는 `capability_proof.resolution` 으로 들어간다
- 다시 낸 `v_spec_gap` 에 V45 가 들고 있던 분기 순서·대가 설명을 그대로 옮겼다
