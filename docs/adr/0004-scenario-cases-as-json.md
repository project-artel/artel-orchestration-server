# ADR 0004 — TestScenario 가 case 를 junction 이 아니라 `steps` JSONB 안 참조로 든다

- 상태: 뒤집힘 (V17 이 junction 을 만들고 V31 이 지움, ARTEL-283)
- 근거: `db/migration/V17__create_testcase_run_hierarchy.sql`,
  `db/migration/V31__drop_test_scenario_case.sql`, `db/migration/V32__promote_test_scenario_payload_columns.sql`,
  [`.plan/general/2026-07-28-run-scenario-case-hierarchy-and-schema.md`](../../.plan/general/2026-07-28-run-scenario-case-hierarchy-and-schema.md),
  [`.plan/general/2026-08-07-qa-step-model-redesign-followups.md`](../../.plan/general/2026-08-07-qa-step-model-redesign-followups.md),
  `testscenario/dto/ScenarioModels.kt`, `testscenario/service/ScenarioCompositionService.kt`

## 결정

- 시나리오는 순서 있는 step 리스트이고, 각 step 이 검증 대상 test case 를 `case_id` 로 **선택적으로**
  참조합니다.
- 그 리스트는 `test_scenario.steps` JSONB 한 컬럼에 삽니다.

```kotlin
data class ScenarioStep(
    val action: String = "",
    @JsonProperty("case_id") val caseId: Long? = null,
    val hint: String? = null,
    val input: String? = null,
    ...
)
```

- 연속된 같은 `case_id` 가 한 test case 의 검증 구간입니다 — precondition → action 들 → expected.
- **복사가 아니라 참조입니다.** 시나리오는 case 의 본문을 복사해 두지 않고 id 만 듭니다.
- 두 단계의 관계 모양이 다릅니다.

| 관계 | 어떻게 | 남았나 |
| --- | --- | --- |
| `test_run` ↔ `test_scenario` | junction `test_run_scenario`, `position` 으로 순서 | 남음 |
| `test_scenario` ↔ `test_case` | `steps` JSONB 안 `case_id` | junction 은 지워짐 |

## 왜 처음에는 junction 이었나

V17 은 이유를 적었고 그 이유는 지금도 유효합니다.

> `-- 관계는 junction 테이블로 둔다(역방향 질의 "케이스 X 쓰는 시나리오 다 찾기" + 무결성/순서 제약).`

- 역방향 질의 전용 인덱스까지 함께 만들었습니다 — `idx_test_scenario_case_case ON test_scenario_case (test_case_id)`.
- `UNIQUE (test_scenario_id, position)` 이 순서의 중복을 막았습니다.
- **역방향 조회는 junction 이 잘하는 바로 그 일입니다.** 그것이 이 결정이 내준 것입니다.

## 왜 뒤집었나

- step 모델 재설계가 시나리오의 단위를 "케이스의 조합" 에서 "행위의 나열" 로 바꿨습니다.
  - 한 step 은 행위 하나이고, case 는 그 step 이 무엇을 검증하는지 말하는 **선택적** 꼬리표입니다.
  - case 없는 step 이 정상입니다 — 이동이나 준비 동작에는 검증할 case 가 없습니다.
- junction 은 "케이스가 시나리오를 이룬다" 를 전제합니다. 그 전제가 사라지자 테이블이 표현할 것이
  없어졌습니다.
- 순서는 이미 배열이 들고 있습니다. `position` 컬럼이 두 번째 진실이 됩니다.
- 코드에서는 먼저 폐기됐고(Controller·Entity·Repository 삭제) 테이블만 DB 에 남아 있었습니다. V31 은
  스키마를 실제 사용에 맞춘 정리입니다.

**정직하게 적어 둘 것 하나.** `.plan` 문서 둘이 이 결정을 다루는데, 뒤의 것이 앞의 질문에 이름을 불러
답하지 않습니다.

| 문서 | 이 결정에 대해 무엇을 말하나 |
| --- | --- |
| `2026-07-28-run-scenario-case-hierarchy-and-schema.md` | "스냅샷 vs 참조" 를 **열린 질문**으로 남김. 그 결정이 조합 테이블 컬럼을 좌우한다고 적음 |
| `2026-08-07-qa-step-model-redesign-followups.md` | 새 모델을 사실로 서술함. 열린 질문에 답한다고는 적지 않음 |

- 그래서 "참조를 골랐다" 를 명시적으로 말하는 곳은 plan 문서가 아니라 코드입니다 —
  `TestRunScenarioEntity` 의 `참조 방식(시나리오 복사 안 함)`.
- 결론은 확실하지만 그것이 결정으로 기록된 자리는 없습니다. 이 ADR 이 그 자리입니다.

## 거절한 대안

| 대안 | 왜 거절했나 |
| --- | --- |
| junction 을 유지한다 | 시나리오의 단위가 케이스가 아니게 되어 표현할 것이 없어짐 |
| case 본문을 시나리오에 복사해 둔다 | 회귀는 안정되지만 case 를 고쳐도 옛 시나리오가 옛 문장을 계속 검증함 |
| junction 과 JSONB 를 함께 둔다 | 순서와 참조의 진실이 둘이 되고, 어긋날 때 어느 쪽이 맞는지 규칙이 없음 |
| `case_id` 를 필수로 만든다 | 이동·준비 동작처럼 검증할 case 가 없는 step 이 정상임 |

## 대가

| 대가 | 무엇이 일어나나 |
| --- | --- |
| **역방향 조회를 잃음** | "케이스 X 를 쓰는 시나리오" 를 돌려주는 메서드가 지금 없음 |
| 무결성 제약이 없음 | 지워진 case 를 가리키는 `case_id` 가 JSONB 안에 남을 수 있음 |
| 순서 제약이 DB 에 없음 | 배열이 순서이므로 중복 `position` 이라는 개념 자체가 없어졌지만, DB 가 막아 주던 것도 함께 사라짐 |
| 인덱스가 안 붙음 | JSONB 를 훑는 질의가 `jsonb_array_elements` 스캔임. GIN 인덱스는 없음 |

- 역방향 조회의 재료는 있습니다. 같은 패턴이 이미 돌고 있습니다.

```sql
SELECT DISTINCT (step->>'case_id')::BIGINT AS id
FROM test_scenario
CROSS JOIN LATERAL jsonb_array_elements(steps) AS step
WHERE project_id = :projectId
  AND jsonb_typeof(steps) = 'array'
  AND step->>'case_id' IS NOT NULL
```

- 이것은 방향이 반대입니다 — 시나리오가 인용한 case 를 모읍니다. `SELECT` 대상을 바꾸고 `case_id` 로
  거르면 원하는 질의가 되지만, 그 메서드는 아직 없습니다.
- 이 계층에는 **SQL 외래키가 하나도 없습니다.** V17 이 그것도 함께 정했습니다 —
  `FK는 두지 않는다(레포 관례: 프로젝트/시나리오/케이스는 논리 참조)`. junction 을 지운 것이 무결성을
  약하게 만든 것이 아니라, 원래 약했습니다.
- `case_id` 를 case 본문으로 푸는 것은 `ScenarioCompositionService.agentScenario()` 입니다. 저장 시점이
  아니라 agent 에게 넘기는 시점에 풉니다 — 그것이 "참조" 가 실제로 뜻하는 바입니다.
