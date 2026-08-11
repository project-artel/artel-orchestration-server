# 2026-08-11 — test_scenario의 payload JSONB를 title·description·steps 컬럼으로 승격

- Date: 2026-08-11
- Jira: ARTEL-291
- Status: Reviewed (fast/medium/heavy 자체 리뷰 통과)

## Goal

`test_scenario`가 시나리오 본문을 `payload` JSONB 한 덩어리로 들고 있는 구조를 끝내고, `title`·`description`·`steps` 세 컬럼으로 승격한다. 스키마만 봐도 시나리오가 무엇을 담는지 드러나고, 제목만 바꿀 때 스텝 전체를 다시 쓰지 않게 한다.

FE 응답 계약(`{testScenarioId, projectId, payload: {title, description, steps}}`)은 **그대로 유지**한다. 이 변경은 영속화 계층에서 끝난다.

## Non-goals

- `steps`를 개별 행 테이블로 정규화하지 않는다. JSONB 컬럼 유지.
- FE 변경 없음. 응답 DTO 필드명(`payload`)도 건드리지 않는다.
- Agent 계약(`ScenarioStep{action, case_id, hint, input}`) 변경 없음.
- `title` 인덱스 추가하지 않음 — 아래 결정 근거 참조.

## Context / Constraints

### 선행 의존 — 해소됨

이슈 작성 시점엔 PR #97(Step 모델 재설계)·#99(ARTEL-283 `test_scenario_case` DROP)가 미머지였다. 둘 다 develop에 머지되어 `payload = {title, description, steps[]}` 구조가 확정됐다. 이 위에서 진행한다.

### Flyway 번호

develop 최신은 **V31**(`drop_test_scenario_case`). 다음 번호는 **V32**. (이슈 본문의 "V30"은 develop이 그 뒤로 움직여 낡음.)

### payload 소비자 전수 (7곳)

| 위치 | 하는 일 |
|---|---|
| `TestScenarioService.createScenario` | 빈 `ScenarioDraft()` 직렬화해 INSERT |
| `TestScenarioService.getScenario` / `getScenarioInProject` | payload 파싱 → 응답 |
| `TestScenarioService.toSummary` | payload 파싱해 `title`만 추출 |
| `TestScenarioService.testScenarioUpdate` | draft 통째 덮어쓰기(자동저장) |
| `TestScenarioService.testScenarioApprove` | draft 통째 덮어쓰기(승인) |
| `RunScenarioReader.currentScenarios` | payload 파싱 → `CurrentScenario` |
| `ScenarioCompositionService.agentScenario(…, payloadJson: String)` | payload 파싱 → Agent 실행 계약 |
| `QaTryService:382` | `readTree(payload)` → 단일 시나리오 세션 컨텍스트 |
| `QaTryService:453` | `entity.payload.asString()`을 composition에 전달 |

### 결정 셋

**1. DROP을 같은 마이그레이션에서 한다 (expand/contract 아님).**

`payload`는 `NOT NULL`이고 DEFAULT가 없다. 코드가 payload 쓰기를 멈추는 순간 INSERT가 깨지므로, 컬럼을 남기려면 DEFAULT를 억지로 붙여야 한다. 죽은 컬럼에 기본값을 다는 것보다 한 번에 정리하는 편이 정직하다. 프로젝트도 V31에서 같은 판단(전방 전용, down 없음)을 이미 했다.

**2. `title` 인덱스 안 만든다.**

현재 조회는 `findByProjectId` 하나뿐이고 제목 정렬·검색 경로가 없다. 인덱스는 그 경로가 생길 때 붙인다.

**3. 응답 DTO 유지.**

`ScenarioResponse.payload: ScenarioDraft`를 그대로 둔다. FE 계약이 안 바뀌므로 이 PR의 blast radius는 서버 내부로 한정된다. 평탄화는 FE 합의가 필요한 별건.

## Approach (Checklist)

- [ ] **Step 0: Recon** — 완료. 소비자 7곳·테스트 4개 식별(위 표).

- [ ] **Step 1: 마이그레이션** `src/main/resources/db/migration/V32__promote_test_scenario_payload_columns.sql`
  - `ALTER TABLE test_scenario ADD COLUMN title TEXT NOT NULL DEFAULT ''`, `description TEXT NOT NULL DEFAULT ''`, `steps JSONB NOT NULL DEFAULT '[]'::jsonb`
  - 백필: `UPDATE test_scenario SET title = COALESCE(payload->>'title', ''), description = COALESCE(payload->>'description', ''), steps = COALESCE(payload->'steps', '[]'::jsonb)`
  - `ALTER TABLE test_scenario DROP COLUMN payload`
  - 헤더 주석에 배경·되돌리기 불가 사실 명시(팀 마이그레이션 주석 관례)

- [ ] **Step 2: 엔티티** `TestScenarioEntity`
  - `payload: Json` 제거 → `title: String`, `description: String`, `steps: Json`
  - KDoc를 새 구조로

- [ ] **Step 3: 쓰기 경로**
  - `TestScenarioService`: 생성(빈 값)·자동저장·승인 세 곳에서 draft → 컬럼 셋으로 분해
  - `ScenarioReconcileService`: `ScenarioDraft` 조립해 payload 넣던 것을 컬럼 셋으로
  - 매핑 위치 확정: `testscenario/entity/ScenarioDraftMapping.kt`에 확장 함수 두 개 —
    `TestScenarioEntity.toDraft(objectMapper): ScenarioDraft`, `TestScenarioEntity.withDraft(draft, objectMapper): TestScenarioEntity`.
    읽기 4곳·쓰기 3곳이 쓰므로 중복 제거가 실증된다. 클래스·인터페이스·빈은 만들지 않는다(확장 함수만).

- [ ] **Step 4: 읽기 경로**
  - `TestScenarioService.toSummary`: JSON 파싱 제거, `entity.title` 직접 사용
  - `getScenario`/`getScenarioInProject`: 컬럼 셋 → `ScenarioDraft` 조립
  - `RunScenarioReader`: 같은 방식
  - `ScenarioCompositionService.agentScenario`: 시그니처를 `agentScenario(draft: ScenarioDraft)`로. 현재 파라미터 `testScenarioId`·`userId`는 **본문에서 전혀 쓰이지 않는 죽은 인자**라 함께 제거한다(어차피 손대는 시그니처). 호출부 `QaTryService:453` 동반 수정
  - `QaTryService:382`: `readTree(payload)` → `objectMapper.valueToTree(draft)`

- [ ] **Step 5: 테스트**
  - `TestScenarioRepositoryTest`: payload 리터럴(구형 `{"steps":[{"order":1,…}]}` 포함)을 컬럼 기반으로 교체 + steps JSONB 왕복 검증
  - `TestScenarioPipelineIntegrationTest`, `TestScenarioReconcileIntegrationTest`, `TestCaseHierarchyIntegrationTest`: 컴파일·단언 수정
  - 목록 요약이 `title` 컬럼에서 나오는지 검증. 기존 테스트가 이미 덮으면 추가하지 않는다(중복 테스트 금지)

- [ ] **Step 6: diff 리뷰 + 커밋** — 스코프 밖 변경 없는지 전수 확인

## Validation

- **Commands to run:**
  - `./mvnw -q -Dtest=TestScenarioRepositoryTest test` (집중)
  - `./mvnw test` (전체 — 스키마 변경이라 blast radius 큼)
  - `./scripts/check-flyway-migrations.sh` (버전 충돌·tangle·tampering 검사)
  - `grep -rn "scenario\.payload\|entity\.payload\|payload = Json" src/main/kotlin` → 0건 (qa_log·SDK 메시지의 무관한 `payload`와 섞이지 않게 좁힌 패턴)
- **Expected output:** 전체 그린. 테스트 스위트는 실 PostgreSQL 컨테이너에 Flyway를 처음부터 적용하므로 V32가 V5~V31 뒤에 깨끗이 올라가는 것까지 함께 검증된다.

**백필은 자동 테스트로 덮이지 않는다.** 스위트는 빈 DB에서 시작하므로 `UPDATE … payload->>'title'` 구문이 실행되긴 해도 대상 행이 0건이다. 따라서 백필은 수동 검증한다:

```bash
# 스크래치 DB에 V31까지 올리고 구/신 payload 행을 심은 뒤 V32 적용, 세 컬럼 값 확인
docker run -d --rm --name pg-backfill -e POSTGRES_PASSWORD=postgres -p 55432:5432 postgres:16
```

절차: V31까지 마이그레이션 → `test_scenario`에 신형/구형 payload 행 각 1건 INSERT → V32 적용 → `SELECT id, title, description, steps FROM test_scenario`로 세 컬럼이 기대대로 채워졌는지 확인. 결과를 PR 본문 Validation에 붙인다.

## Risks & Rollback

- **Risks:**
  - `payload` DROP은 되돌릴 수 없다. 백필이 틀리면 데이터 손실. → 백필 SQL을 `COALESCE`로 방어하고, 마이그레이션 적용 전 운영 DB 백업을 전제로 한다.
  - 스테이징/운영에 payload 스키마가 어긋난 낡은 행(예: 재설계 이전 `steps[].{step,title,state,action,expected}` 형태)이 있으면 `steps` 컬럼에 구형 구조가 그대로 들어간다. 백필은 구조 변환을 하지 않는다 — Agent 재설계(#97) 이후 저장된 행만 신형이다. **적용 전 구형 행 존재 여부를 실제로 세어 확인한다.**
  - 롤링 배포 중 구버전 파드가 살아 있으면 payload를 못 찾아 500. 단일 배포 전제.
- **Rollback steps:** 코드 `git revert` + 백업에서 `payload` 복원 마이그레이션 별도 작성. 마이그레이션 down은 없다(프로젝트 관례).

## 구형 payload 행 — 결정

리뷰에서 Open Question으로 남겼던 "구형 `steps` 구조 행을 어떻게 할 것인가"를 결정으로 바꾼다. 여기서 운영 DB를 조회할 수 없고, 답을 기다리면 착수가 막히기 때문이다.

**결정: 백필은 구조 변환을 하지 않고 `steps`를 그대로 옮긴다.**

근거 — 구형 행은 이 변경이 만드는 문제가 아니라 **이미 존재하는 문제**다. Step 모델 재설계(#97)가 `ScenarioStep`을 `{step,title,state,action,expected}`에서 `{action,case_id,hint,input}`으로 바꿨고, Spring Boot 기본 ObjectMapper는 `FAIL_ON_UNKNOWN_PROPERTIES`가 꺼져 있다(이 저장소에 커스텀 ObjectMapper 빈 없음). 그래서 구형 행은 지금도 예외 없이 읽히되 `action=""`인 빈 스텝으로 조용히 퇴화한다. 컬럼 승격은 이 동작을 바꾸지 않는다 — 같은 JSON이 다른 컬럼에 있을 뿐이다.

구형 행 정리는 별개 관심사이므로 이 PR에 넣지 않는다. 배포 담당자가 적용 전 아래로 실태만 센다:

```sql
SELECT count(*) FROM test_scenario
WHERE jsonb_path_exists(payload, '$.steps[*].expected');
```

0이 아니면 별도 이슈로 데이터 정리를 잡는다. 이 사실을 PR 본문에 명시한다.

## Rejected feedback

- **expand/contract 2단계 릴리스로 쪼개기** — 거절. `payload`가 `NOT NULL` + DEFAULT 없음이라 컬럼을 남기려면 죽은 컬럼에 기본값을 달아야 한다. 프로젝트는 V31에서 이미 전방 전용·down 없음을 택했다.
- **`title` 인덱스 선반영** — 거절. 제목 정렬·검색 경로가 아직 없다. YAGNI.
- **백필 전용 통합 테스트 추가** — 거절. 스위트가 빈 DB에서 시작해 대상 행이 0건이라 테스트가 아무것도 증명하지 못한다. 대신 위 수동 절차로 검증하고 결과를 PR에 남긴다.
