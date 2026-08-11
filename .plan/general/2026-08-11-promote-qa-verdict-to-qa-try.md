# 2026-08-11 — QA 판정을 qa_try에 승격하고 채점 이력 자리를 만든다

- Date: 2026-08-11
- Jira: ARTEL-299
- Status: Draft

## Goal

QA 런의 **판정**을 축별 집계가 읽을 수 있는 자리에 올린다. 지금 대시보드가 세는
`qa_try.status`는 런 생명주기이지 품질이 아니다 — `COMPLETED`는 "끝까지 돌았다"이지
"제대로 했다"가 아니다.

판정 데이터 자체는 이미 있다. Agent가 종단 STATUS에 2단 요약(`steps` / `cases`)을 싣고
(`QaRunState.build_summary`), `routeStatus`가 그것을 payload 통째로 `qa_log`에 넣는다.
**영속화는 이미 되어 있고 빠진 것은 꺼내 쓰는 길이다.** `qa_log`는 이 시스템에서 제일 큰
테이블이고(모든 LOG/ACTION/ACTION_RESULT가 쌓인다) `type` 인덱스도 없어서, 여러 런을 축으로
접는 집계가 그 테이블 전체 스캔 + JSONB 경로 필터가 된다.

2연속 PR 중 첫 번째다. 이 PR은 **그릇**을 만들고, 후속 PR이 기대 라벨과 채점을 채운다.

## Non-goals

- `artel-agent-server` 수정. `build_summary`는 이미 필요한 것을 다 낸다.
- 채점 로직과 grader 구현 — 후속 PR.
- 기대 라벨(`expected_passed`), 저작 화면 — 후속 PR.
- LLM 심판. 이 트랙은 결정적 채점만 쓴다.
- 대시보드 UI, 실험 엔티티.
- `qa_log` 스키마 변경. 진실은 거기 그대로 남는다.
- `qa_try_score`의 지표 컬럼 승격 — 첫 채점자가 무엇을 내는지 보고 후속에서 정한다.

## Context / Constraints

### 판정이 들어오는 자리

`QaAgentInboundRouter.routeStatus`는 **2-scope**다. 스텝 판정 STATUS는 `result=null`이라 런을
끝내지 않고, 종단 프레임만 `result`(PASSED|FAILED)를 싣는다(`status=CANCELLED`는 항상 종단).
승격은 **종단 프레임에서만** 일어나야 한다.

Agent가 싣는 요약의 모양:

```json
{"summary": {"steps": {"total": 5, "passed": 4, "failed": 1, "items": [...]},
             "cases": {"total": 2, "passed": 1, "failed": 1, "items": [...]}}}
```

`cases`는 구간의 검증 스텝에서 파생된다(`case_units()`). `case_id`가 없는 스텝이 존재하므로
(저작 Step 모델에서 `case_id`는 nullable) **cases는 steps에서 유도되지 않는다.** 한쪽만 두면
케이스 없는 시나리오와 케이스 있는 시나리오를 같은 지표로 못 본다.

### 지켜야 할 것

- 프레임 처리 중 예외가 WebSocket 수신 체인 밖으로 나가면 소켓이 닫히고 런 전체가 실패한다
  (`onDisconnect` → `failActiveById`). **승격 실패가 런을 죽이면 안 된다.**
- 승격 경계는 `qa_try`이지 WS 세션이 아니다. QA_Run 재설계(ARTEL-259)로 세션 하나가 여러
  시나리오를 순차 실행하고 `qa_try`는 시나리오당이다.
- 마이그레이션은 기존 데이터가 있는 상태에서 올라간다. 기존 행은 전부 NULL이고 그게 맞다.
- 오류는 `common/error` 타입 예외로만 던진다(`.agents/docs/error-handling.md`). 이 경로는
  애초에 throw하지 않으므로 새 예외 타입이 필요 없다.

## 설계 판단

### 1. 네 컬럼 전부 nullable, 기본값 없음

요약이 없는 종료 경로가 있다 — 소켓 사망, 취소(`_send_terminal`이 `state=None`으로 부른다),
state 없이 끝나는 경로. 그런 런은 값을 **모르는** 것이지 0점이 아니다. `NOT NULL DEFAULT 0`으로
두면 잘 죽는 모델이 전부 0점으로 보이고 그 오류는 조용히 지나간다. V27의 `knowledge_usage.cited`가
nullable인 것과 같은 규율이다.

### 2. `cases_total`이 0인가 NULL인가 — **0으로 못박는다**

`case_id`가 하나도 없는 시나리오에서 `case_units()`는 `[]`를 돌려주고 `cases.total`은 `0`으로
실려 온다. 즉 Agent는 **측정된 0**을 보고한 것이다. 그대로 0을 승격한다.

NULL로 뭉개면 3상태가 무너진다: "요약을 못 받았다"(NULL)와 "케이스 없이 저작된
시나리오"(0)가 같은 값이 되고, 커버리지 집계가 후자를 미측정으로 세어 커버리지를 실제보다
낮게 보고한다. 테스트로 못박는다.

### 2-1. `failed`는 승격하지 않는다

`build_summary`가 내는 `failed`는 정의상 `total - passed`다(steps도 cases도). 파생값을 컬럼으로
들고 있으면 세 값이 어긋날 수 있는 상태가 생긴다. 두 개만 올리고 나머지는 뺄셈으로 낸다.

### 2-2. 요약이 부분적으로만 읽힐 때

네 값을 각각 독립으로 읽는다. 하나가 없거나 정수가 아니면 그 컬럼만 NULL이고 나머지는 채운다.
넷 다 못 읽으면 UPDATE 자체를 보내지 않는다. "요약 전체가 온전할 때만 쓴다"로 하면 필드 하나가
빠졌다는 이유로 나머지 셋까지 미지가 되어, 실제보다 커버리지가 낮게 보고된다.

### 2-3. 범위를 코틀린에서 검사하지 않는다 — 컬럼 타입이 검증자다

값은 `Long?`으로 읽어 INT 컬럼에 그대로 바인딩한다. `asInt()`로 접으면 INT를 넘는 수가 조용히
**다른 수로** 저장된다 — 판정 지표에서 그것은 못 읽은 것보다 나쁘다. 안 들어가는 보고는
DB가 거절하고, 아래 §3의 삼킴이 그것을 로그로 떨어뜨린다.

덤으로 이 경로가 §3의 "승격 실패가 런을 죽이지 않는다"를 목 없이(mock 없이) 테스트할 수 있는
유일한 결정적 실패 지점이다.

### 2-4. 운영자 취소는 NULL로 남는다

`QaTryService.cancelRun` / `cancelActiveById` 경로는 STATUS 프레임을 거치지 않으므로 승격이
일어나지 않는다. Agent 쪽 취소도 `_send_terminal(CANCELLED, state=None)`이라 summary가 없다.
어느 쪽이든 NULL이고, 그것이 "판정을 모른다"의 정확한 표현이다.

### 3. 승격은 종단 전이 **뒤에**, 별도 UPDATE로

`transition`을 확장해 한 문장으로 합칠 수도 있지만, 그 메서드는 취소·실패·런 종료 등 여러
호출부가 공유한다. 판정을 실을 수 있는 것은 종단 STATUS 하나뿐이라 공유 메서드에 컬럼 4개를
더하면 나머지 호출부 전부가 그 파라미터를 NULL로 끌고 다닌다.

대신 `promoteVerdict(id, ...)`를 따로 두고 전이가 성공한 뒤에 부른다. 두 문장 사이에 "종단인데
판정은 아직 없는" 창이 생기지만, **컬럼은 GROUP BY용 사본이고 진실은 `qa_log`의 payload다** —
그 창은 재구성 가능하다. 반대로 전이 전에 쓰면 종단되지 않은 런에 판정이 새겨질 수 있다.

승격 전체를 `try/catch`로 감싸 실패를 삼키고 감사 로그만 남긴다. `CancellationException`은
오류가 아니라 취소 신호이므로 넓은 catch 앞에서 먼저 rethrow한다.

### 4. 커버리지를 `QaStatsRepository`에 **이번 PR에서** 노출한다

`steps_passed` 평균을 값이 있는 런만으로 내면 그 평균은 "깔끔하게 종료된 런"에 조건부라 위로
편향되고, **잘 죽는 모델일수록 자기 최악 런이 빠져서 편향 크기가 축마다 다르다.**

후속 PR로 미루지 않는 이유: 승격 컬럼이 이 PR에서 들어오는 순간 누구든 평균을 낼 수 있고,
그 사이 분모 없는 조건부 평균이 화면에 뜬다. 비용은 0이다 — 이미 있는 `scoped` CTE에
집계 함수 몇 개를 더하는 것뿐이라 새 조인도 새 스캔도 없다.

**평균이 아니라 합계와 known 카운트를 낸다.** 평균은 분모를 구조적으로 숨긴다. 합계
(`steps_total` / `steps_passed` / `cases_total` / `cases_passed`)와 `verdict_known`을 같은 줄에
놓으면 비율을 낼 때 그것이 몇 개의 런에 얹힌 값인지 함께 읽히고, 숨길 방법이 없다.

### 5. `qa_try_score` — 자리만 만든다

이번 PR에서 쓰는 곳이 없다. V18이 ARTEL-188의 `deleted_at`을, V27이 `replaces_id`를 미리
만들어 둔 것과 같은 판단이다.

`qa_try`에 점수 컬럼으로 박지 않는 이유: 채점 기준이 바뀌면 **재채점**해야 하고, 컬럼이면
덮어써서 이력이 죽는다. `(grader, grader_version)`으로 새 판정을 옆에 쌓고 옛 판정을 보존한다.
채점자가 여러 종류라 한 런에 여러 줄이 붙는 것이 이 키의 목적이다 — 서로 대조할 수 있어야 한다.

지표 컬럼은 승격하지 않고 `detail`(JSONB)만 둔다. 첫 채점자가 무엇을 내는지 보고 후속에서
필요한 것만 올린다.

**FK는 건다 — 하드 FK + `ON DELETE CASCADE`** (`qa_log`·`issue`와 같은 쪽).

점수는 특정 런에 **대한** 파생 판정이라 런 없이는 주어가 없다. `grader` / `grader_version` /
`detail`만 남은 행은 무엇에 대한 점수인지 말하지 못하고, 재도출도 불가능하다 — 근거인
`qa_log`가 같은 CASCADE로 이미 사라졌기 때문이다. 고아 점수는 조인에서 조용히 빠져 축 평균만
낮춘다.

지식창고 쪽이 논리참조인 이유는 반대다. 거기는 원본이 소프트삭제되고 **이력이 원본보다 오래
남는 것이 목적**이라 하드 FK가 그 목적을 깬다. `qa_try`는 하드삭제된다
(`deleteByTestScenarioId` — 시나리오 강제 삭제 경로). CASCADE는 그 정리를 막지도 않는다.

### 6. Flyway 번호 = V33

`develop`은 V31까지. V32는 미머지 브랜치 ARTEL-291
(`V32__promote_test_scenario_payload_columns.sql`)이 이미 잡았다 — `check-flyway-migrations.sh`의
peer 스캔으로 확인했다. 충돌 경고를 만들지 않으려고 V33을 쓴다. ARTEL-291이 끝내 머지되지
않으면 V32가 비지만, Flyway에서 번호 공백은 무해하다.

## Approach (Checklist)

- [x] **Step 0: Recon** — V25/V27 주석의 판단, `routeStatus`의 2-scope 규칙,
      `QaStatsRepository.aggregateByRunConfig`, agent 쪽 `build_summary` / `_send_terminal`,
      `check-flyway-migrations.sh` peer 스캔
- [ ] **Step 1: 마이그레이션** — `V33__promote_qa_verdict_and_score.sql`
      - `qa_try`에 `steps_total` / `steps_passed` / `cases_total` / `cases_passed` (INT, nullable)
        + `COMMENT ON COLUMN`으로 "GROUP BY용 사본, 진실은 qa_log payload" 명시
      - `qa_try_score` 테이블 + `uq_qa_try_score (qa_try_id, grader, grader_version)`.
        `qa_try_id` 단독 인덱스는 만들지 않는다 — 유니크 인덱스의 선두 컬럼이 이미 그것이다.
- [ ] **Step 2: 승격 배선**
      - `QaTryEntity`에 네 필드 추가(`Int?`, 기본값 null)
      - `QaTryRepository.promoteVerdict` (`@Modifying`)
      - `QaAgentInboundRouter.routeStatus` — 종단 분기에서만, 전이 성공 뒤, 실패는 삼키고 로그
- [ ] **Step 3: 커버리지 노출**
      - `QaStatsRepository`: `verdict_known` + 네 합계를 `scoped`에서 집계
      - `QaStatsRow` / `QaRunConfigStatsCell` / `QaStatsTotals`에 필드 추가
- [ ] **Step 4: 테스트** — 아래 Validation
- [ ] **Step 5: 롤아웃** — 플래그 없음. 마이그레이션은 additive이고 기존 행은 NULL로 남는다.

## Validation

- **Commands to run:**
  - `./scripts/check-flyway-migrations.sh` (0 기대)
  - `./scripts/verify-flyway-upgrade.sh` (develop 마이그레이션 위에 V33 적용 + validate)
  - `./mvnw test -Dtest=QaVerdictPromotionIntegrationTest`
  - `./mvnw clean test`
- **Expected output:** 위 전부 통과. 특히 다음이 테스트로 못박혀야 한다.
  - 종단 STATUS에 summary가 있으면 네 컬럼이 채워진다
  - summary 없이 끝난 런은 NULL로 남는다 (0이 되면 안 된다)
  - 스텝 판정 STATUS(`result=null`)는 승격도 종단도 일으키지 않는다
  - `case_id` 없는 스텝만 있는 시나리오는 `cases_total = 0` (NULL 아님)
  - 승격 실패가 런을 죽이지 않는다
  - 한 세션에 시나리오가 여럿일 때 각 `qa_try`가 자기 값을 갖는다

## Risks & Rollback

- **Risks:**
  - 승격이 별도 UPDATE라 종단 전이와 원자적이지 않다. 그 사이 프로세스가 죽으면 판정이 비지만
    진실은 `qa_log` payload에 남아 재구성 가능하다.
  - `QaStatsResponse`에 필드가 늘어난다. 추가라 하위호환이지만 대시보드가 새 필드를 쓰기 전까지
    커버리지는 응답에만 있고 화면에는 없다.
  - Flyway V32를 ARTEL-291이 먼저 머지하지 않으면 번호가 하나 빈다(무해).
- **Rollback steps:** 코드 `git revert`. 마이그레이션은 되돌리지 않는다 — 컬럼과 테이블은
  전부 nullable/미사용이라 남아 있어도 아무 동작도 바꾸지 않는다.

## Plan review notes

subagent가 없는 세션이라 fast / medium / heavy 역할을 순차 self-review로 돌렸다.

- **fast** — 부분 요약 처리(§2-2), `failed` 승격 여부(§2-1), 운영자 취소 경로(§2-4),
  커버리지의 기준 컬럼(`steps_total IS NOT NULL`)이 비어 있었다. 전부 반영.
- **medium** — `idx_qa_try_score_try`는 유니크 인덱스의 선두 컬럼과 겹쳐 YAGNI. 제거.
  승격 컬럼에 인덱스를 걸자는 안은 거절 — 이 컬럼들은 필터가 아니라 집계 대상이라
  술어로 들어가지 않는다.
- **heavy** — "승격 실패가 런을 죽이지 않는다"를 mock 없이 어떻게 재현하나가 유일한 블로커였다.
  §2-3의 결정(범위 검사를 DB에 맡긴다)이 그 실패 지점을 실제 경로로 만들어 해소했다.
  `@SpyBean`으로 repository를 던지게 만드는 안은 거절 — 이 리포의 테스트는 mock을 쓰지 않고,
  suspend 함수 스터빙은 mockito-kotlin 없이는 지저분하다.

## Open Questions

- 없음. `cases_total`의 0/NULL 판단은 위 §2에서 확정하고 테스트로 못박는다.
