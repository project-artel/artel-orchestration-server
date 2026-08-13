# 2026-08-11 — 1단계: TC 전량 카탈로그 적재 (저작 Agent가 프로젝트 TestCase 전량을 프롬프트로 보유)

- Date: 2026-08-11
- Jira:
  - [ARTEL-318](https://artel-asm.atlassian.net/browse/ARTEL-318) — `orchestration-server` 카탈로그 생산·세션 주입
  - [ARTEL-319](https://artel-asm.atlassian.net/browse/ARTEL-319) — `agent-server` 카탈로그 소비·번호 조회 도구
- Status: Draft

## Goal

저작 Agent가 시나리오를 짤 때 **프로젝트의 TestCase 전량을 처음부터 알고 있게** 한다.

지금은 Agent가 `test_case_search`(벡터)를 스스로 호출해 케이스를 찾는다. 툴 예산 6회 × 회당 최대
10건이라 중복을 빼면 **실효 노출이 30~40건**이고, TC가 1000건인 프로젝트에서는 3~4%다. 게다가
벡터 검색은 순위만 돌려줄 뿐 모집단 크기를 알려주지 않아 **무엇이 빠졌는지 Agent도 우리도 모른다.**

세션을 열 때 **압축 카탈로그를 프롬프트 고정 블록으로 주입**해 이 실패 모드를 없앤다. 검색은
발견 수단에서 보조 수단으로 강등하고, 실제 조회는 **번호로 지목하는 정확 조회**로 바꾼다.

## Non-goals

이번 범위가 아니다. 각각 별도 이슈로 간다.

- **커버리지 원장 / 미커버 제안 / 시나리오 검증 상태** — 2단계
- **저장 전 린트(없는 케이스 번호 차단), 이슈 발행 자격 구조** — 3단계
- **`entry_scene` 등 TestCase 스키마 구조화 승격** — 규모 문제가 실측될 때까지 보류
- **화면 지도(`knowledge_edge` / `LEADS_TO`) 채우기** — 별개 트랙
- **FE 작업** — 1단계에는 화면 변경이 없다
- **한 번에 전량을 커버하는 시나리오 다발 생성** — 출력 길이 한계상 1단계로 해결되지 않는다

## Context / Constraints

**토큰은 제약이 아니다(실측).** 스키마(V17) 4필드 형태의 한국어 TC 8건을 `tiktoken · o200k_base`로
측정해 1000건 환산한 값:

| 형태 | TC당 | 1000건 |
|---|---|---|
| 인덱스(id+category+title) | 19.9 tok | 19.9k |
| 인덱스 + precondition | 43.5 tok | 43.5k |
| 본문 전량(4필드) | 74.4 tok | **74.4k** |

측정 스크립트는 세션 스크래치패드의 `tokcount.py`. 실제 DB 데이터로 재측정 가능.

**뒤집어야 할 기존 전제.** `app/agents/scenario/cases.py` 첫머리 docstring에 이렇게 적혀 있다:

> the agent cannot see the cases up front (they accumulate, and a first import is already large:
> injecting them all would blow the context, ARTEL-206)

이 전제가 이 작업의 대상이다. 주석도 함께 갱신한다.

**캐싱이 성립해야 비용이 의미 있다.** 카탈로그는 프롬프트 **앞쪽 고정 블록**에 놓고, **정렬을
안정적으로**(예: `id ASC`) 고정해야 한다. 매 턴 순서가 흔들리면 캐시가 깨져 74k를 매번 지불한다.
세션 오픈 시점의 스냅샷을 세션 내내 재사용한다.

**계약 변경은 양쪽 동시 배포.** `AgentSessionOpenRequest`에 필드가 추가되므로 orche와 agent가 함께
나가야 한다. 다만 아래 롤백 설계로 순서 의존을 낮춘다.

**1단계로 해결되지 않는 것.** 전량을 알아도 (a) 출력 길이 때문에 한 번에 다 쓸 수 없고
(b) 알면서 고르게 다루지 못한다. 이건 2단계(나눠 만들기 + 커버리지 세기)의 몫이다. 1단계의 성공
기준을 "전량 커버"로 잡으면 안 된다 — **"존재를 모르는 상태가 사라졌는가"**가 기준이다.

## Approach (Checklist)

- [x] **Step 0: Recon**
  - [x] `testcase/` 도메인 조회 경로 확인 (`TestCaseRepository.findByProjectIdOrderByIdDesc`)
  - [x] `TestScenarioAgentService.openSession` / `gameContext(projectId, appUserId)` 주입 지점 확인
  - [x] agent `app/agents/scenario/{schemas,prompt,cases,tools}.py` 및 `app/prompts/scenario/v3/*` 파악
  - [ ] 실제 프로젝트 TC 수·본문 길이로 토큰 재측정 (DB 시드 후)

- [x] **Step 1: Orchestration — 카탈로그 생산**
  - [x] `testcase/dto/TestCaseCatalogDtos.kt` (신규) — `id · category · title · verificationStatus`
        압축 DTO. 본문(precondition/expected)은 제외
  - [x] `testcase/repository/TestCaseRepository.kt` — `findCatalogByProjectIdOrderByIdAsc`,
        네 컬럼만 SELECT + **정렬 `id ASC` 고정**
  - [x] `testcase/service/TestCaseService.kt` — `getTestCaseCatalog(projectId, userId)`
  - [x] `testcase/controller/TestCaseController.kt` — `GET /api/projects/{projectId}/test-cases/catalog`
        (이슈 본문에는 `/api/test-cases/catalog`로 적었으나, 기존 컨트롤러가 프로젝트 하위 경로라 그쪽에 맞췄다)
  - [x] ~~`auth/config/SecurityConfig.kt`~~ — **변경 불필요.** 새 경로는 `anyExchange().authenticated()`에
        이미 걸린다. 줄을 더하면 중복 규칙만 는다

- [x] **Step 2: Orchestration — 세션 주입**
  - [x] `testscenario/dto/AgentSessionDtos.kt` — `AgentSessionOpenRequest.caseCatalog`
        (`game_context` 옆자리, 와이어명 `case_catalog`, 기본값 빈 목록 = 롤백 경로)
  - [x] `testscenario/service/TestScenarioAgentService.kt` — `caseCatalog(projectId, appUserId)`를
        `gameContext(...)`와 같은 자리·같은 방식으로 주입

- [ ] **Step 3: Agent — 소비**
  - [ ] `app/agents/scenario/schemas.py` — `ScenarioAgentRequest.case_catalog`
  - [ ] `app/agents/scenario/cases.py` — 카탈로그 렌더링 추가, **검색 예산 축소**,
        첫머리 docstring의 전제 갱신
  - [ ] `app/agents/scenario/tools.py` — **번호로 정확 조회하는 도구** 추가(검색이 아니라 지목)
  - [ ] `app/agents/scenario/prompt.py` — 카탈로그를 **앞 고정 블록**으로 배치
  - [ ] `app/prompts/scenario/v4/system.md` (신규) — 카탈로그 사용 규칙.
        "검색은 카탈로그로 부족할 때만"
  - [ ] `app/prompts/scenario/v4/human.md` (신규) — 카탈로그 슬롯
  - [ ] `app/api/sessions.py` — 새 필드 수용

- [x] **Step 4: Tests (orche)** — `TestCaseCatalogIntegrationTest` 5건
  - [x] 전량 + `id ASC` (같은 데이터를 최신순으로 내는 `list()`와 대비해 정렬 계약을 못박음)
  - [x] 한 줄에 네 필드만 · `verification_status` snake_case · id는 숫자
  - [x] 비참여자 빈 목록
  - [x] 세션 오픈 본문이 `case_catalog` 배열로 직렬화
  - [x] 미지정 시 빈 배열(롤백 경로)
  - ⚠️ 함정: 테스트 메서드를 `= runBlocking { … }` 식 본문으로 두면 마지막 `isEqualTo`의 반환값이
        메서드 반환 타입이 되어 **JUnit이 조용히 건너뛴다.** 레포 관례대로 `(): Unit = runBlocking`으로 적는다
  - [ ] **agent**: 프롬프트 조립 시 카탈로그 블록 위치·정렬 안정성, 빈 카탈로그 하위 호환 → ARTEL-319

- [ ] **Step 5: Rollout / Rollback**
  - [ ] agent 먼저 배포(빈 필드 허용) → orche 배포 순으로 무중단
  - [ ] 실측: 실제 프로젝트에서 프롬프트 토큰량·캐시 적중 확인

## Validation

- **Commands to run:**
  - orche: `./mvnw -o test -Dtest='TestScenario*,TestCase*' -DfailIfNoSpecifiedTests=false` (Maven 프로젝트다 — Gradle 아님)
  - agent: `uv run pytest tests/`
- **Expected output:**
  - 저작 세션을 열면 `POST /internal/sessions` 본문에 `case_catalog`가 실린다
  - Agent가 `test_case_search` 호출 **없이** 케이스 번호를 지목해 시나리오를 만든다
  - 프로젝트 TC 수를 늘려도(수백 건) 세션이 정상 동작하고, 2턴째부터 입력 토큰 청구가 급감한다
    (캐시 적중)
  - 존재하지 않는 케이스를 지목하는 빈도가 줄어든다 (정확 조회로 바뀌었으므로)

**성공 기준은 "전량 커버"가 아니다.** Agent가 카탈로그에 있는 케이스를 근거로 지목하고,
"찾지 못해 빠뜨리는" 실패가 사라지는 것이 기준이다.

## Risks & Rollback

- **Risks:**
  - **캐시가 안 걸리면 비용이 그대로 든다.** 정렬 불안정·블록 위치 변동이 원인이 된다.
    → 정렬 고정 + 세션 스냅샷 + 앞 블록 배치로 방어하고, 배포 후 실제 청구로 검증한다.
  - **카탈로그가 매우 커지는 프로젝트**(TC 5000+ → 370k). 지금 규모에서는 문제가 아니지만
    상한·씬별 분할이 필요해질 수 있다. → 상한 도달 시 경고 로그를 남기고 후속 이슈로 처리.
  - **계약 동시 배포.** → 아래 하위 호환 설계로 완화.
  - **전량을 줬는데도 고르게 안 쓴다.** 이건 1단계의 실패가 아니라 2단계가 필요한 이유다.
    성공 기준을 혼동하지 않는다.

- **Rollback steps:**
  - `case_catalog`를 **비워 보내면 기존 검색 경로로 동작**하도록 agent 쪽을 설계한다.
    그러면 롤백이 재배포 없이 orche 설정/플래그만으로 가능하다.
  - 프롬프트는 `v3`를 남겨두고 `v4`를 신규로 추가한다 — 버전 되돌리기가 파일 교체로 끝난다.
  - DB 스키마 변경이 없으므로 마이그레이션 롤백은 불필요하다.

## Open Questions

- 카탈로그에 `verification_status`를 포함할지. **포함 권장** — `BROKEN` 케이스를 Agent가 피하게
  하는 신호가 1자로 붙는다.
- `precondition`을 카탈로그에 넣을지. 순서·그룹핑 판단의 유일한 근거라 가치가 크지만
  (+23.6k), 1단계 성공 기준과는 무관하다. **본문 전량(74.4k)으로 시작하고 비용을 보고 조정**하는
  쪽을 제안한다.
- `test_case_search` 툴을 남길지 완전히 제거할지. **남기되 예산 축소**를 제안한다 —
  카탈로그에 제목만 있을 때 본문을 확인하는 경로가 필요하다.
- 카탈로그 상한을 둘지, 둔다면 초과 시 동작(경고 / 씬별 분할 / 최근순 절삭).
