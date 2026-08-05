# 2026-08-05 — QA 실행 설정 집계 통계 API

- Date: 2026-08-05
- Jira: None (ARTEL-239 후속, 이슈 미생성)
- Status: Implemented

## Goal

ARTEL-239(PR #72)이 `qa_try`에 남기기 시작한 실행 설정을 **읽는 쪽**을 만든다. admin-page 대시보드가
한 번의 호출로 다음을 모두 그릴 수 있어야 한다.

- 축별 런 수와 완주율 — `model` / `reasoning_effort` / `prompt_version` / `agent_arch`
- 축 조합 비교 매트릭스 (임의의 두 축 교차)
- 축별 LLM 토큰·비용 (ARTEL-233 `llm_usage`)
- 전체 합계

## Non-goals

- 최근 런 목록. 이미 `GET /api/qa-tries?projectId&size`가 있고, 이 티켓에서 다시 만들지 않는다.
- 크로스 프로젝트 집계. 시스템에 관리자 역할이 없다(`app_user`에 role 컬럼 없음). 지금 있는 권한
  모델은 `project_member` 하나뿐이라 프로젝트 단위 집계만 만든다.
- 시계열 추이(일별/주별). 축 비교가 먼저고, 추이는 축이 자리잡은 뒤에 붙인다.
- `run_config` JSONB 안쪽 축(예: `language`, vision knob)으로 그룹핑. 승격 컬럼 4개만 쓴다.

## Context / Constraints

**데이터 원천.** `V25__add_qa_try_run_config.sql`이 승격 컬럼 5개 + `run_config` JSONB를 추가했고
`idx_qa_try_config (model, prompt_version, agent_arch, reasoning_effort)`가 GROUP BY를 받친다.
전 컬럼 nullable이다 — 마이그레이션 이전 런과 `run_config`를 안 돌려주는 구버전 Agent가 둘 다
NULL로 남는다. 집계는 NULL을 버리지 않고 "미상" 그룹으로 보여야 한다. 버리면 대시보드 합계가
프로젝트 실제 런 수와 어긋나고, 그 차이를 설명할 방법이 없다.

**비용 원천.** `llm_usage`는 `service='QA_RUN'`일 때 `reference_id = qa_try.id`다(V24 주석).
FK가 없고 nullable이라 매칭 안 되는 행이 정상적으로 존재한다.

**엔드포인트를 하나로 두는 이유.** 런은 4-튜플 `(model, reasoning_effort, prompt_version,
agent_arch)`로 **분할**된다. 그래서 4축 전부로 GROUP BY 한 결과만 있으면 단일 축 분해도, 두 축
매트릭스도, 전체 합계도 클라이언트에서 부분합으로 나온다 — 서버에 축 이름을 파라미터로 받는
동적 SQL을 둘 필요가 없다(화이트리스트를 틀리면 그대로 주입 지점이 된다). 조합 수는 실험
공간이라 작다. 대신 폭주를 대비해 상한과 `truncated` 플래그를 둔다.

**완주율 ≠ 테스트 통과율.** `qa_try.status`는 런 생명주기이지 QA 판정이 아니다. `COMPLETED`는
"에이전트가 끝까지 돌았다"이지 "테스트가 통과했다"가 아니다. 응답 필드 이름과 UI 라벨 둘 다
completion으로 쓴다. `CANCELLED`는 운영자 행동이라 실패와 섞지 않고 따로 센다.

**권한.** `QaTryRepository.findByProject`와 같은 방식 — `project_member` 조인을 쿼리 안에 둔다.
참여자가 아니면 빈 결과이고 403이 아니다. 기존 동작과 같게 유지한다.

**경로.** `/api/qa-stats`. `/api/qa-tries/stats`로 두면 `/{qaTryId}`와 같은 자리를 다투고, 나중에
경로 변수 매칭 순서에 기대는 코드가 된다.

## Approach (Checklist)

- [x] **Step 0: Recon**
  - `V25__add_qa_try_run_config.sql`, `V24__create_llm_usage.sql` — 컬럼·인덱스 확인
  - `QaTryRepository.findByProject` — 멤버십 조인 패턴
  - `TestCaseVectorSearchRepository` — `DatabaseClient` 선례
  - `QaCaptureIntegrationTest` — 실 PostgreSQL 통합 테스트 픽스처 패턴

- [x] **Step 1: Implementation**
  - `qa/repository/QaStatsRepository.kt` — `DatabaseClient` 한 문장. `scoped`(멤버십·기간 필터)
    → `usage`(qa_try별 사전 집계) → 4축 GROUP BY. `llm_usage`를 `qa_try`에 바로 조인하면 런 하나가
    호출 수만큼 복제돼 런 수가 부풀므로 반드시 사전 집계 후 LEFT JOIN.
  - `qa/dto/QaStatsDtos.kt` — `QaStatsResponse`(기간·상한·`truncated`·`cells`),
    `QaRunConfigCell`(4축 값 + 상태별 카운트 + 토큰 4종 + `costUsd` + 평균 소요 ms)
  - `qa/service/QaStatsService.kt` — 파라미터 검증, 엔티티→DTO
  - `qa/controller/QaStatsController.kt` — `GET /api/qa-stats`

- [x] **Step 2: Tests**
  - `QaStatsIntegrationTest` — 실 PostgreSQL
    - 4축 조합별 그룹핑과 상태별 카운트
    - NULL 축이 "미상" 셀로 남고 셀 합계 = 전체 런 수
    - `llm_usage` 다건이 붙어도 런 수가 부풀지 않는다 (핵심 회귀)
    - `service`가 `QA_RUN`이 아닌 사용량은 섞이지 않는다
    - 비참여자는 빈 결과
    - 기간 경계(`from` 포함, `to` 배타)
    - 상한 초과 시 `truncated=true`

- [ ] **Step 3: Rollout / Rollback**
  - 읽기 전용 추가라 마이그레이션 없음. 배포 순서 제약 없음.
  - ARTEL-239 머지 후에만 의미가 있다(그 전에는 축이 전부 NULL이라 셀 하나로 뭉친다).

## Validation

- **Commands to run:** `./mvnw -o test -Dtest=QaStatsIntegrationTest`, 이어서 `./mvnw -o clean test`
- **Expected output:** 신규 테스트 전건 통과, 기존 278건 회귀 없음

## Risks & Rollback

- **Risks:**
  - 조합 폭주. `agent_fingerprint`를 축에 넣지 않은 이유이기도 하다 — 지문은 변경마다 갈리므로
    그룹 키로 쓰면 셀이 런 수만큼 늘어난다. 라벨(`agent_arch`)만 축으로 쓴다.
  - `cost_usd`가 nullable이라 SUM이 NULL일 수 있다. 0으로 뭉개면 "공짜"와 "단가 미상"이 같아지므로
    nullable 그대로 내보낸다.
  - 기간 필터 기준은 `qa_try.started_at`이고 `llm_usage.called_at`이 아니다. 런에 귀속시키는
    집계라 런의 시작 시각이 기준이며, 월 경계에 걸친 런의 비용은 그 런의 달에 계산된다.
- **Rollback steps:** `git revert`. 스키마 변경 없음.

## Open Questions

- 관리자 역할이 생기면 프로젝트 선택 없이 전체 집계를 열지 여부. 지금은 프로젝트 필수.
