# 2026-07-28 — P1: TestSuite→TestScenario→TestCase 상하관계 & DB 스키마

- Date: 2026-07-28
- Jira: None
- Status: Draft
- Branch: (신규) `feat/testcase-run-hierarchy-schema-*`
- 개요: `2026-07-28-testcase-scenario-run-redesign-overview.md`

## Goal
계층(TestSuite→TestScenario→TestCase)의 **엔티티·관계·마이그레이션**을 정의한다. Agent가 주는 **TestCase(구조 정규화, 값은 자연어)** 를 관계형으로 저장하고, 조합(scenario↔case)과 묶음(suite↔scenario)을 표현한다. 실행 인스턴스(TestRun=qa_try 계열) 배선은 **P4** — 여기선 데이터 구조까지.

## Non-goals
- QA 실행 파이프라인 변경(P4), 리셋 최적화(P2), FE(P3).
- 빌드타임 메타데이터 수집 파이프라인(dep) — 단, 레지스트리를 담을 자리는 고려.

## Context / Constraints
- 현재: `test_scenario(payload JSONB=ScenarioDraft{steps})`, `test_scenario_message`, `qa_try(test_scenario_id)`. `ScenarioStep{step,title,state,action,expected}`가 사실상 TestCase 원형.
- Agent 산출 = 정규화 TestCase JSON → 스칼라는 컬럼, **술어 리스트(pre/expected/post)는 JSONB 또는 자식 테이블**(기계-비교 가능 구조 필수, P2 알고리즘이 읽음).
- action 어휘 = JSON-RPC `{interactable, method, params}`. pre/expected = `{scene, observables[name], states, recentActions}`에 대한 술어.

## Approach (Checklist)
- [ ] **Step 0: Recon** — `testscenario/entity`, `dto/ScenarioModels.kt`, `qa/entity`(qa_try), 마이그레이션 최신 버전(현 V15 → 다음 V16), knowledge 스키마 패턴 참고.
- [ ] **Step 1: 스키마(마이그레이션 V16+)**
  - `test_case`(Agent 실제 출력에 맞춤 — **전부 자연어, 구조화 action 없음**): `id, project_id, category(대분류), title(제목), precondition(TEXT/NL, 사전조건), expected(TEXT/NL, 기대효과), verification_status(DRAFT|VERIFIED|BROKEN 기본 DRAFT), last_verified_build_id?(nullable), source(AGENT), created_at, updated_at`. 프로젝트 스코프 재사용.
    - ⚠️ **action(어떤 SDK 메서드를 호출해 이 케이스를 수행하나)은 작성 산출물에 없음** — 실행 시점에 QA Agent가 NL을 보고 결정(P4). 즉 P1은 "무엇을 검증하나"(NL)만 저장.
    - (선택) **구조화 술어 컬럼**(정규화된 precondition/expected)은 P2 최적화가 필요로 할 때 **추가**한다(NL→구조화 정규화 결과). P1 기본은 NL TEXT.
  - `test_scenario`: `title, description` 컬럼 승격. (기존 `payload`는 전환기 유지 후 폐기 — dep 마이그레이션)
  - `test_scenario_case`(조합, 순서): `test_scenario_id, test_case_id, position`. **의미적 순서 = position**. (스냅샷 채택 시 케이스 내용 사본 컬럼 추가)
  - `test_suite`: `id, project_id, name, description?, created_at`. (검증 세트=정의)
  - `test_suite_scenario`(묶음, 순서): `test_suite_id, test_scenario_id, position`.
  - `qa_try`(=TestRun 실행 인스턴스): `test_suite_id`(nullable) 추가만(실제 배선은 P4).
- [ ] **Step 2: 엔티티/repository/DTO** — R2DBC 엔티티, 조합 조회(순서 보존), 프로젝트 격리 쿼리.
- [ ] **Step 3: 적재 API** — Agent 완성 TestCase JSON 수신→저장(정규화 매핑). knowledge `store` 패턴 참고(무효 항목 스킵).

## Validation
- **Commands:** `./mvnw test -Dtest=TestCaseIntegrationTest,TestScenarioCompositionTest`
- **Expected:** 케이스 저장/조회, 시나리오-케이스 순서 보존, run-scenario 묶음, 프로젝트 격리, 무효 술어 처리.

## Risks & Rollback
- **Risks:** 기존 `test_scenario.payload` 사용처(FE·QA)와의 하위호환. Flyway 버전 충돌(develop 최신 재확인 후 V16).
- **Rollback:** 코드 revert + 신규 테이블 DROP(down 없음). 기존 payload 경로는 전환기 병존이라 즉시 롤백 가능.

## Open Questions
- **스냅샷 vs 참조**: `test_scenario_case`에 케이스 사본을 박제(회귀 안정) vs 라이브 참조(즉시 반영)? → 이 결정이 조합 테이블 컬럼을 좌우.
- **술어 저장형**: JSONB 단일 컬럼 vs 자식 테이블(SQL 질의 가능). P2가 술어를 읽으니 **구조 일관성**이 우선.
- **qa_try↔TestSuite/TestRun**: P1에서 `test_suite_id` 컬럼만 두고 P4에서 배선? qa_try를 TestRun(실행 인스턴스)으로 정식 개념화할지.
- 기존 payload 시나리오 데이터 전환/마이그레이션 시점(P1 포함 vs 별도 dep).
