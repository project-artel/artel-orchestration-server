# 2026-08-19 — 결론에 이르는 사슬을 단계별로 남긴다

- Date: 2026-08-19
- Jira: ARTEL-478
- Status: Implemented

## Goal

기능이 **어떻게 도출됐는지**를 단계별로 남긴다. 지금은 등급 하나(`analysis_confidence`)뿐이라
"이 결론이 틀렸다"까지만 말할 수 있고, 호출을 잘못 따라간 것인지 · 필드 쓰기를 잘못 읽은 것인지 ·
조건을 잘못 붙인 것인지 가릴 수 없다. **적재기 규칙을 고치려면 그 구분이 먼저다.**

## Non-goals

- 적재기가 사슬을 쌓는 것. ARTEL-442 몫이다.
- 사슬 적재 on/off 스위치. 켜고 끄는 주체가 적재기라 그쪽과 함께 가야 한다. 여기서는 **켰을 때의
  대가를 재서 기록**하는 데까지 한다(아래 Validation).
- `v_content_map_capability` 확장. 이 뷰는 사슬을 **조인하지 않는다**.

## Context / Constraints

기획: `.plan/general/2026-08-18-extend-content-map-schema-v7.md` (P2)

**V44 를 새로 낸다.** V40 본문은 건드리지 않는다 — 번호가 붙은 파일의 checksum 이 곧 계약이라,
base 브랜치가 이미 가진 파일을 고치면 `check-flyway-migrations.sh` 가 막는다.
V41 · V42 · V43 은 각각 ARTEL-441 · 470 · 473 이 쓰고 있어 다음 빈 번호가 44 다.
이 브랜치는 ARTEL-473 위에 스택된다. 병합 순서: 440 → 441 → 470 → 473 → **478** → 479.

### enum 충돌 — 이 이슈에서 정한 것

두 어휘가 어긋나 있었다.

```text
V40      capability_evidence.analysis_confidence   verified | derived | partial
specs_v2 Resolution                                exact | derived | ambiguous | unresolved
```

**specs_v2 어휘로 통일한다**(사용자 결정). 근거:

- `analysis_confidence` 를 "사슬 `resolution` 의 최솟값"으로 정의하려면 두 값이 **같은 순서
  집합**이어야 한다. 어휘가 다르면 계산한 값과 저장된 값을 비교할 수 없고, 이 이슈의 완료 조건
  ("어긋나지 않는다")을 확인할 방법 자체가 없다.
- V40 이 아직 배포된 적이 없다. 지금이 통일하는 유일하게 싼 시점이다. 다만 어휘 교체는 V40
  본문이 아니라 V44 의 `ALTER` 로 낸다 — CHECK 제약을 갈아 끼우고 기존 행을 `UPDATE` 로 옮긴다.
- 옮김: `verified → exact`, `derived → derived`, `partial → ambiguous`. `unresolved` 는 새로 생긴
  칸으로, 지금까지 `partial` 에 뭉쳐 있던 "아예 못 풀었다"를 가른다.

`v_spec_gap` 의 `given-incomplete` 분기가 `analysis_confidence = 'partial'` 을 읽고 있었다.
`IN ('ambiguous', 'unresolved')` 로 옮겼다 — 둘 다 조건을 단정할 수 없다는 뜻이라 사유는 같다.

## Approach (Checklist)

- [x] **Step 1: V44 신규 마이그레이션** — `V44__record_capability_proof_chain.sql`
  - `capability_proof` 신규 테이블. 한 단계 = 한 행. `capability_id` CASCADE · `effect_id` CASCADE ·
    `seq` · `source` · `relation` · `target` · `resolution` · `rule`
  - 순서 유일성은 부분 유니크 인덱스 둘로 건다. `UNIQUE` 가 NULL 을 서로 다르게 보아 한 제약으로는
    `effect_id IS NULL` 쪽이 안 걸린다
  - `capability_effect.resolution` 컬럼 추가 + `ck_capability_effect_resolution`
  - `analysis_confidence` — 제약을 떼고 `verified → exact` · `partial → ambiguous` 로 행을 옮긴 뒤
    specs_v2 어휘의 CHECK 을 다시 건다
  - `v_spec_gap` 의 `given-incomplete` 분기를 새 어휘로. `CREATE OR REPLACE VIEW` 가 컬럼 목록과
    순서의 일치를 요구해 본문 전체를 다시 싣는다
  - `v_capability_proof` — 사슬의 별도 조회 창구. `chain_rank` 를 윈도우 함수로 함께 낸다
- [x] **Step 2: 엔티티** — `CapabilityProofEntity` · `CapabilityProofRepository` 신규,
      `CapabilityEffectEntity.resolution`, `AnalysisConfidence` 어휘 교체
- [x] **Step 3: 테스트** — 사슬이 흐려진 단계를 말한다 · 사슬을 쌓아도 TC 창구 행 수가 그대로다 ·
      순서 중복 거절 · 크기 대가 측정
- [x] **Step 4: 검증**

## Validation

- **Commands to run:**
  - `./scripts/check-flyway-migrations.sh` → `OK: no version collisions.`
  - `./mvnw -o -Dtest='ContentMapSchemaTest' test` → 26 tests, 0 failures
  - `./scripts/verify-flyway-upgrade.sh <base>` → base 마이그레이션 위에 V44 적용, validate 통과
- **크기 대가(측정값):** 골든 맵 규모(기능 18 × 효과 2 × 단계 3 = **108 행**) 기준
  `sum(pg_column_size(p.*)) = 23,652 bytes`, **행당 219 bytes**, 전체 약 **23 KB**.
  같은 규모의 원본 근거 문서가 1,413 KB 이므로 사슬을 전부 실어도 **1.6% 수준**이다.
  이 숫자가 적재 on/off 기본값을 정할 때의 근거다.

## Risks & Rollback

- **어휘 교체가 cross-repo 계약이다.** agent-server `specs_v2` 와 맞췄지만, 그쪽이 이 값을 읽는
  경로가 생기면 두 저장소가 같은 순서 집합을 쓴다는 것을 계속 지켜야 한다.
- `analysis_confidence` 가 사슬 최솟값과 어긋나지 않는 것은 **DB 가 강제하지 못한다**(교차 행
  제약). 적재기가 지켜야 하고, `v_capability_proof.chain_rank` 로 대조 질의를 쓸 수 있다.
- 사슬이 켜지면 행이 기능당 수 배로 는다. 측정값은 위에 있고, 켜고 끄는 스위치는 적재기와 함께
  간다.
- 스택 전체가 각자 자기 마이그레이션을 낸다: 470=V42 · 473=V43 · **478=V44** · 479=V45.
  앞이 하나 밀리면 뒤 번호가 전부 밀린다.

- **Rollback steps:** `git revert`. V44 는 신규 파일이고 배포된 적이 없다.

## Open Questions

- 사슬 적재 기본값(on/off). 측정값(23 KB / 1.6%)은 켜도 될 만하다고 말하지만, 결정은 적재기가
  실제로 쌓기 시작하는 ARTEL-442 에서 함께 내리는 것이 맞다.
