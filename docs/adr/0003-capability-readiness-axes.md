# ADR 0003 — capability 준비도를 세 축으로 가르고 `status` 를 생성 컬럼으로 둔다

- 상태: 확정
- 근거: `db/migration/V45__split_status_into_three_readiness_axes.sql`,
  `db/migration/V40__create_content_map.sql`,
  [`.plan/general/2026-08-18-create-content-map-schema.md`](../../.plan/general/2026-08-18-create-content-map-schema.md),
  `contentmap/entity/CapabilityEntity.kt`

## 결정

- `capability.status` 한 컬럼이 들고 있던 준비도를 축 셋으로 나눕니다.

| 축 | 값 |
| --- | --- |
| `actionability` | `runnable`, `needs-probe`, `unreachable-precondition`, `not-a-step` |
| `observability` | `observable`, `unobservable`, `unknown` |
| `applicability` | `applies`, `not-applicable`, `unknown` |

- `status` 는 지우지 않고 **세 축에서 유도되는 생성 컬럼**으로 남깁니다.

```sql
ALTER TABLE capability
    ADD COLUMN status VARCHAR(32) GENERATED ALWAYS AS (
        CASE
            WHEN actionability = 'not-a-step' THEN 'not-a-step'
            WHEN applicability = 'not-applicable' THEN 'unreachable-precondition'
            WHEN actionability = 'unreachable-precondition' THEN 'unreachable-precondition'
            WHEN actionability = 'needs-probe' THEN 'needs-probe'
            WHEN observability <> 'observable' THEN 'needs-probe'
            ELSE 'runnable'
        END
    ) STORED;
```

## 왜

축 셋을 하나로 누르면 서로 다른 두 사실이 같은 값이 됩니다.

> `-- 축이 셋이다. 하나로 눌러 두면 "실행은 되는데 관측이 안 됨"과 "이 빌드엔 적용 안 됨"이`
> `-- 같은 needs-probe 한 통에 들어가 소비자가 가릴 수 없다. 앞은 조작 스텝으로는 쓸 수 있고`
> `-- 판정 근거로만 못 쓴다. 뒤는 아예 쓸 수 없다.`

- 앞의 것은 조작 스텝으로 쓸 수 있고 판정에만 못 씁니다. 뒤의 것은 아예 쓸 수 없습니다. 소비자가 그 둘을
  가려야 하는데 값이 같으면 가릴 수가 없습니다.

두 번째 이유는 쓰는 쪽입니다.

> `-- 축을 나누면 각자 자기 축만 정한다. watchable 판정(ARTEL-452)은 관측 축만 건드리고, gap`
> `-- 판정(ARTEL-461)은 실행 축만 건드린다 — 지금은 둘이 한 컬럼에서 서로를 덮어쓴다.`

- 판정기 둘이 한 컬럼을 나눠 쓰면 나중에 도는 쪽이 앞의 결론을 지웁니다. 축을 나누면 그 자리가 없어집니다.

**생성 컬럼인 이유는 축과 어긋난 행이 존재할 수 없게 하기 위해서입니다.** 유도 값을 손으로 쓰게 두면
언젠가 세 축이 `runnable` 인데 `status` 는 `needs-probe` 인 행이 생기고, 그때 무엇이 참인지 아무도
모릅니다.

## 축은 다섯이고 준비도는 그중 셋이다

- `origin` 과 `verification` 은 V40 이 만든 별개의 축이고 V45 가 건드리지 않았습니다.

| 축 | 값 | 무엇을 말하나 |
| --- | --- | --- |
| `origin` | `evidence`, `observed`, `inferred`, `human` | 이 행이 어디서 왔나 |
| `verification` | `unverified`, `confirmed`, `contradicted` | 실제로 확인됐나 |

- 준비도 셋은 "지금 이걸로 무엇을 할 수 있나" 를 말하고, 이 둘은 "이 말을 얼마나 믿나" 를 말합니다.
  섞으면 안 되는 이유가 그것입니다.

## `capability_evidence` 를 따로 뗀 이유

- `evidence` 에서만 나오는 컬럼들은 `capability` 가 아니라 1:1 하위 테이블에 있습니다 —
  `entry_id`, `owner_type`, `method`, `method_id`, `record_kind`, `trigger_kind`,
  `analysis_confidence`, `condition_tree`, `binding_event`, `binding_receiver`, `call_path`, `gaps`.

> `-- 서브테이블로 뗀 이유: 이 컬럼들을 capability 에 두면 QA 가 관측으로 배운 기능이 NOT NULL 에`
> `-- 막혀 더미값을 넣게 되고, 그 순간 두 종류가 구분 불가능해진다.`

- `entry_id`·`record_kind`·`trigger_kind`·`condition_tree` 는 IL 분석을 전제합니다. 관측으로 배운
  capability 에는 넣을 값이 없습니다.
- 더미값을 한 번 넣으면 "분석이 그렇게 말했다" 와 "빈칸을 채웠다" 가 같은 모양이 됩니다.

## 거절한 대안

| 대안 | 왜 거절했나 |
| --- | --- |
| `status` 한 컬럼을 그대로 두고 값을 늘린다 | 축이 셋인데 값 하나로 조합을 표현하면 값 목록이 곱으로 늘고 소비자가 파싱해야 함 |
| `status` 를 지우고 소비자마다 세 축을 조합하게 한다 | 화면이 묻는 것은 "TC 로 만들 수 있나" 하나이고, 그 조합 규칙이 소비자마다 흩어짐 |
| `status` 를 보통 컬럼으로 두고 애플리케이션이 맞춰 쓴다 | 축과 어긋난 행이 존재할 수 있게 됨 |
| `evidence` 전용 컬럼을 `capability` 에 둔다 | 관측으로 배운 capability 가 NOT NULL 에 막혀 더미값을 넣게 됨 |

## 대가

| 대가 | 무엇이 일어나나 |
| --- | --- |
| `status` 를 쓸 수 없음 | 값을 담아 보내면 INSERT 가 거절됨. Kotlin 쪽은 `@ReadOnlyProperty` 로 막아 둠 |
| 유도 규칙이 SQL 안에 있음 | 규칙을 바꾸려면 마이그레이션이 필요하고, 기존 행이 전부 다시 계산됨 |
| 축 셋을 세는 것과 `status` 를 세는 것이 다름 | 화면 숫자와 축별 숫자가 어긋나 보일 수 있음 |
| 행 하나를 읽으려면 조인이 하나 늘 수 있음 | `evidence` 출신 컬럼이 필요하면 `capability_evidence` 를 함께 읽어야 함 |

```kotlin
/**
 * [SpecStatus] 중 하나. **세 축에서 유도된 값이라 쓰지 않는다.**
 *
 * DB 생성 컬럼이므로 여기에 값을 담아 보내면 INSERT 가 거절된다. ...
 */
@ReadOnlyProperty
@Column("status")
val status: String? = null,
```

- `status` 로 세는 것과 축 셋으로 각각 세는 것은 다릅니다. 화면이 묻는 것은 "TC 로 만들 수 있나" 이고 그
  답이 유도 컬럼인 `status` 입니다 — 그래서 유도 컬럼을 남겼습니다.
- 지금 `status` 를 읽는 자리는 `ContentMapRepository` 의 `not-a-step` 제외 조회, `CapabilityRepository`
  의 씬별 집계, `SceneContextService` 의 분류입니다.
