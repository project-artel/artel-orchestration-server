# 2026-08-19 — 근거 위치(offset)를 실어 한 메서드 안의 갈래를 가른다

- Date: 2026-08-19
- Jira: ARTEL-470
- Status: Implemented

## Goal

한 메서드 안의 서로 다른 IL 위치에서 나온 기능을 **서로 다른 기능으로 표현할 수 있는 자리**를
content_map 스키마에 만든다. 그리고 재적재를 넘어 살아남는 **내용 기반 안정 키**를 싣는다.

근거 원본은 `effects[]` 와 `calls[]` 마다 IL `offset` 을 이미 준다. 새로 분석할 것이 없고,
받을 자리가 없어 버려지고 있다.

## Non-goals

- 적재기 구현. 이 컬럼들을 실제로 채우는 것은 ARTEL-442 이고 아직 착수 전이다. 여기서는
  **자리와 제약**만 만든다.
- `method_id` · `call_path` 컬럼 추가. **V40 에 이미 있다.** 여기서 하는 것은 "비면 사유가
  남는다"를 DB 가 강제하게 만드는 것뿐이다.
- `capability.id` 폐기. 표시용·조인용으로 그대로 둔다.

## Context / Constraints

기획: `.plan/general/2026-08-18-extend-content-map-schema-v7.md` (P0 (a) · (c))

**V42 를 새로 낸다. V40 본문은 건드리지 않는다.** 처음에는 V40 본문을 고쳤으나, base 를
ARTEL-441 로 잡는 순간 `check-flyway-migrations.sh` 가 "already-merged migration modified" 로
막는다 — base 브랜치가 이미 V40 을 갖고 있어 본문 수정이 checksum 변경으로 잡히기 때문이다.
V41 은 ARTEL-441 의 `content_map_document` 가 쓰고 있어 다음 빈 번호가 42 다.
뒤따르는 ARTEL-473 · 478 · 479 도 각자 V43 · V44 · V45 로 낸다.

병합 순서: 440 → 441 → 470 → 473 → 478 → 479.

V40 실측 대조 — 이슈의 `대상/변경` 표를 스키마 현재 상태와 맞춘 것:

| 대상 | V40 현재 | 이 이슈에서 |
|---|---|---|
| `capability_effect` | `il_offset` 없음 | `il_offset INT` 추가 |
| `capability_evidence` | `branch_offset` 없음 | `branch_offset INT` 추가 |
| `capability_evidence.method_id` | nullable, 강제 없음 | 비면 `gaps` 에 `method-id-missing` 을 요구하는 CHECK |
| `capability_evidence.call_path` | `DEFAULT '[]'` | 비면 `gaps` 에 `call-path-missing` 을 요구하는 CHECK |
| `capability` | 안정 키 없음 | `capability_key VARCHAR(64)` + content_map 범위 UNIQUE |
| `v_content_map_capability` | 안정 키 없음 | `capability_key` 노출 |

결정 셋:

1. **컬럼명은 `il_offset`.** `offset` 은 PostgreSQL 예약어라 매번 따옴표를 요구한다.
2. **안정 키의 유일성 범위는 content_map 단위.** 이슈가 지정한 범위다. 그런데 `capability` 는
   `scene_id` 만 들고 있어 그대로는 DB 가 강제할 수 없다. `capability.content_map_id` 를 함께 두고
   `(scene_id, content_map_id) → scene (id, content_map_id)` 복합 FK 로 두 값이 어긋날 수 없게
   묶는다. V40 이 `capability_evidence` 에 이미 쓰는 패턴(`(capability_id, origin)`)과 같다 —
   새 패턴을 들이는 것이 아니다.
3. **`capability_key` 는 nullable 로 시작한다.** evidence 출신이 아닌 기능(observed · inferred ·
   human)은 `entry_id` 도 `branch_offset` 도 없어 산식의 입력이 없다. 적재기가 evidence 전건을
   채우기 시작하면 후속 이슈에서 조인다.

## Approach (Checklist)

- [x] **Step 0: Recon** — V40 의 `capability` · `capability_evidence` · `capability_effect` ·
      `v_content_map_capability` 정의 확인. `method_id` · `call_path` 가 이미 있음을 확인.
- [x] **Step 1: V42 신규 마이그레이션** — `V42__carry_il_offset_and_stable_capability_key.sql`
  - `capability.content_map_id` + 복합 FK, `capability.capability_key` + `UNIQUE (content_map_id, capability_key)`
  - `scene` 에 `UNIQUE (id, content_map_id)` — 위 복합 FK 가 참조할 대상
  - `capability_evidence.branch_offset INT`
  - `capability_evidence` CHECK 둘 — `method_id` · `call_path` 가 비면 `gaps` 에 사유 요구
  - `capability_effect.il_offset INT`
  - `content_map_id` 는 넣고 · 씬에서 채우고 · 조인다. 이미 행이 있는 데이터베이스는 기본값 없는
    `NOT NULL` 을 거절한다
  - CHECK 둘을 걸기 전에 사유 토큰을 `UPDATE` 로 채운다. 안 채우면 기존 행이 새 규칙을 못 지켜
    마이그레이션이 통째로 멈춘다
  - `v_content_map_capability` 는 `DROP` 후 `CREATE`. 새 칸이 목록 가운데 들어가는데
    `CREATE OR REPLACE VIEW` 는 끝에 덧붙이는 것만 허용한다
  - `idx_capability_evidence_entry` 를 `(entry_id, branch_offset)` 으로 넓힌다 — 조인이 이제
    메서드가 아니라 갈래 단위다
  - `v_content_map_capability` 에 `capability_key` 노출
- [x] **Step 2: 엔티티** — `CapabilityEntity.contentMapId` · `capabilityKey`,
      `CapabilityEvidenceEntity.branchOffset`, `CapabilityEffectEntity.ilOffset`,
      `EvidenceGap` 에 `METHOD_ID_MISSING` · `CALL_PATH_MISSING` 추가.
      `CapabilityEvidenceRepository.upsert` 의 손으로 쓴 INSERT 에도 `branch_offset` 을 싣는다 —
      엔티티에만 더하면 컬럼이 조용히 null 로 들어간다(실제로 테스트가 이것을 잡았다)
- [x] **Step 3: 테스트** — `ContentMapSchemaTest`
  - 같은 `entry_id` 가 `branch_offset` 이 다르면 서로 다른 기능으로 공존한다
  - 같은 content_map 안에서 `capability_key` 가 중복되면 거절된다
  - `method_id` 가 비었는데 `gaps` 에 사유가 없으면 거절된다 (`call_path` 도 같이)
  - `capability.content_map_id` 가 씬의 것과 다르면 복합 FK 가 거절한다
- [x] **Step 4: 검증** — `./scripts/check-flyway-migrations.sh`, 스키마 테스트

## Validation

- **Commands to run:**
  - `./scripts/check-flyway-migrations.sh` → `OK: no version collisions.`
  - `./mvnw -o -Dtest='ContentMapSchemaTest' test` → 19 tests, 0 failures
  - `./mvnw -o test` → 560 tests, 0 failures
- **Expected output:** 위 셋 모두 통과했다. V40 번호가 ARTEL-440 브랜치와 같은 번호로 보고되는
  것은 정상이다 — 새 파일이 아니라 같은 마이그레이션을 이어 고치는 것이므로.

## Risks & Rollback

- **Risks:**
  - V40 본문을 안 건드리므로 ARTEL-440 의 리뷰와 같은 파일에서 부딪히지 않는다. 대신 V42 가
    V40 의 결과 위에서만 성립한다 — 440 이 리뷰 중 컬럼 이름을 바꾸면 이 파일이 깨진다.
  - `capability_key` 산식(`(entry_id, branch_offset, 정규화한 condition_tree)`)은 스키마가 값의
    모양만 정하고 산식 자체는 ARTEL-442 에서 확정된다. 그 전까지 이 컬럼은 비어 있다.
  - `content_map_id` 를 `capability` 에 두는 것은 `scene` 을 통해 이미 알 수 있는 값의 중복이다.
    복합 FK 가 드리프트를 막지만, 컬럼 하나가 늘어나는 값은 치른다.
  - `method_id` / `call_path` CHECK 는 적재기가 사유 토큰을 안 남기면 적재를 실패시킨다.
    의도한 것이다 — 조용히 비는 것이 지금 문제다.
- **Rollback steps:** `git revert`. V42 는 신규 파일이고 배포된 적이 없다.

## Open Questions

- `capability_key` 를 `NOT NULL` 로 올리는 시점. 적재기가 evidence 전건을 채우기 시작한 뒤,
  후속 이슈에서.
