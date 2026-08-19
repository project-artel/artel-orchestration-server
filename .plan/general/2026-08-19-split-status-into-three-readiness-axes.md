# 2026-08-19 — status 를 실행·관측·적용 세 축으로 가른다

- Date: 2026-08-19
- Jira: ARTEL-479
- Status: Implemented

## Goal

`capability.status` 한 값에 눌려 있던 세 질문을 가른다.

| 축 | 묻는 것 |
|---|---|
| 실행 가능성 | 이 조작을 실제로 할 수 있는가 |
| 관측 가능성 | 그 결과를 볼 수 있는가 |
| 적용 가능성 | 이 빌드에 이 규칙이 적용되는가 |

**"실행은 되는데 관측이 안 됨"** 과 **"이 빌드엔 적용 안 됨"** 이 지금은 둘 다 `needs-probe` 한
통에 들어간다. 앞은 조작 스텝으로는 쓸 수 있고 판정 근거로만 못 쓴다. 뒤는 아예 쓸 수 없다.

## Non-goals

- 적재기의 축 판정. ARTEL-442 몫이다.
- `status` 값 집합 변경. 그대로 둔다 — 읽던 소비자가 바뀐 것을 느끼면 안 된다.
- `idx_capability_scene` 변경. 축으로 거르는 **질의가 실제로 생긴 뒤** 다시 본다. 지금 넓히면
  쓰이지 않는 인덱스를 유지비만 내고 든다.

## Context / Constraints

기획: `.plan/general/2026-08-18-extend-content-map-schema-v7.md` (P2 `readiness`)

**V45 를 새로 낸다. V40 본문은 건드리지 않는다.** base 브랜치가 이미 V40 을 갖고 있어, 본문을
고치면 `check-flyway-migrations.sh` 가 checksum 변경으로 막는다. V41~V44 는 각각
ARTEL-441 · 470 · 473 · 478 이 쓰고 있어 다음 빈 번호가 45 다.
이 브랜치는 ARTEL-478 위에 스택된다. 병합 순서: 440 → 441 → 470 → 473 → 478 → **479**.

### 이슈가 착수 시 정하라고 남긴 것 — DB 생성 컬럼으로 강제한다

`status` 를 **`GENERATED ALWAYS AS ... STORED`** 로 바꿨다. 적재기에서 계산하는 쪽과 견줬을 때:

- 생성 컬럼이면 어긋난 행이 **존재할 수 없다.** 완료 조건("어긋난 행이 0건임을 질의로 확인")이
  질의가 아니라 구조로 보장된다.
- 적재기 계산이면 두 벌(축·status)을 같이 써야 하고, 언젠가 갈라진다. 갈라진 뒤에는 어느 쪽이
  참인지 아무도 모른다.
- 치르는 값: `status` 가 **쓰기 불가**가 된다. 지금 그 값을 쓰는 코드는 테스트뿐이고 적재기는
  아직 없다 — 바꾸기 가장 싼 시점이다.

유도 순서가 뜻을 정한다.

```text
actionability = not-a-step              → not-a-step        조작이 아니면 그것이 먼저다
applicability = not-applicable          → unreachable-...   기존 어휘에 "이 빌드엔 없다"가 없다
actionability = unreachable-precondition→ unreachable-...
actionability = needs-probe             → needs-probe
observability <> observable             → needs-probe       그 칸이 원래 뜻하던 바다
그 외                                    → runnable
```

### v_spec_gap 과의 관계

**축은 상태이고 gap 은 사유다.** `v_spec_gap` 은 건드리지 않았다 — 그 뷰가 답하는 질문("무엇을
못 채웠나")과 축이 답하는 질문("지금 쓸 수 있나")이 다르다. 축이 내려간 이유는
`capability_evidence.gaps` 가 들고, 뷰가 이미 그 둘을 나란히 낸다.

## Approach (Checklist)

- [x] **Step 1: V45 신규 마이그레이션** — `V45__split_status_into_three_readiness_axes.sql`
  - `actionability` · `observability` · `applicability` + 각 CHECK
  - 버리기 전에 기존 `status` 를 축으로 옮긴다. `status='runnable'` 이던 행만
    `observability='observable'` 로 세운다 — 기본값 `unknown` 그대로면 유도 규칙이 그 행들을
    전부 `needs-probe` 로 뒤집는다
  - `status` 를 생성 컬럼으로. 기존 컬럼을 고쳐 만들 수 없어 버리고 다시 만든다
  - `status` 를 읽는 `v_content_map_capability` · `v_spec_gap` 을 먼저 떼고 뒤에 다시 낸다.
    `v_spec_gap` 은 V44 의 정의 그대로다 — 뜻이 바뀐 것이 아니라 컬럼을 떼느라 함께 떨어졌다
- [x] **Step 2: 엔티티** — `Actionability` · `Observability` · `Applicability` enum,
      `SpecStatus.derive(...)`, `CapabilityEntity` 의 `status` 를 `@ReadOnlyProperty` 로
- [x] **Step 3: 테스트** — 관측 불가와 적용 불가가 갈린다 · `status` 를 어긋나게 못 쓴다 ·
      코드 유도 규칙이 DB 와 같은 답을 낸다(7 조합)
- [x] **Step 4: 검증**

## Validation

- **Commands to run:**
  - `./scripts/check-flyway-migrations.sh` → `OK: no version collisions.`
  - `./mvnw -o -Dtest='ContentMapSchemaTest' test` → 30 tests, 0 failures
  - `./mvnw -o test` → 571 tests, 0 failures

## Risks & Rollback

- **`status` 가 쓰기 불가가 됐다.** 적재기가 나중에 `status` 를 직접 쓰려 하면 INSERT 가 거절된다.
  의도한 것이지만, 그 사실을 모르고 짜면 실패로 만난다 — 엔티티 KDoc 과 이 문서가 근거다.
- 유도 규칙이 **두 벌**이다(DB 생성 컬럼과 `SpecStatus.derive`). 코드 쪽은 적재기가 미리 보는
  용도이고 권위는 DB 에 있다. 어긋나면 테스트가 잡는다(7 조합 대조).
- `applicability = not-applicable` 을 `unreachable-precondition` 으로 접는 것은 **손실 있는 매핑**
  이다. 기존 `status` 어휘에 "이 빌드엔 없다"가 없어서다. 그 구분이 필요한 소비자는 축을 읽어야
  한다 — 그것이 이 이슈의 요지이기도 하다.
- 스택 전체가 각자 자기 마이그레이션을 낸다: 470=V42 · 473=V43 · 478=V44 · **479=V45**.
  앞이 하나 밀리면 뒤 번호가 전부 밀린다.
- `v_spec_gap` 정의가 V40 · V44 · V45 세 곳에 실려 있다. 뜻을 바꿀 때 마지막 것만 고치면
  되지만, 어느 것이 마지막인지는 번호를 봐야 안다.

- **Rollback steps:** `git revert`. V45 는 신규 파일이고 배포된 적이 없다.

## Open Questions

- 축으로 거르는 질의가 실제로 나오면 `idx_capability_scene` 을 다시 본다. 지금은 근거 없이
  넓히지 않는다.
