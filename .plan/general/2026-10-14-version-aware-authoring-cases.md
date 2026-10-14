# 2026-10-14 — 시나리오 작성이 TR 빌드 버전의 TC 내용을 쓰게 한다

- Date: 2026-10-14
- Jira: ARTEL-1048 (후속 작업은 새 Jira 로 쪼갠다)
- Status: Draft

## Goal

TR 은 빌드 버전 하나를 따른다(#313). 그 런에서 시나리오를 쓸 때 에이전트와 서버 검수가 보는 TC 를 **그 버전의 것으로 끝까지 맞춘다.**

- 맞출 범위: TC 집합, 각 TC 의 내용(장면 · 스텝 · 사전조건 · 조건 · 기대값 · 메타데이터), 그 TC 가 이어지는 지도 위 능력.
- 대상: 세션을 열 때 보내는 목록, 턴 중 도구의 답(경로 · 케이스 설명 · 검색 · 미커버), 저장 전 검수.

## Non-goals

- 에이전트 서버(Python) 계약 변경. 보내는 모양은 그대로이고 **값만** 버전 것으로 바뀐다.
- QA 판정 쪽(이미 게임 빌드 기준이다).
- 임베딩을 버전별로 따로 두는 일. 검색 순위가 옛 문구 기준인 문제(아래 R5)는 따로 다룬다.

## Context / Constraints

### 지금 어디까지 맞춰져 있나 (#313 기준)

| 자리 | 지금 | 맞춰졌나 |
|---|---|---|
| 지도 고르기(`contentMapIdOf` 두 곳) | TR 버전의 지도 | O |
| 세션을 열 때 보내는 TC **집합**, 검색 · 미커버 결과 | TR 버전에 몫이 있는 케이스만 | O |
| 입구 씬(세션) | TR 버전 지도 | O |
| 각 TC 의 **내용** | `test_case`(대표 행) = **가장 최근 버전** 의 내용 | X |
| 능력과 잇는 SQL 9개(`tc.capability_key` 조인) | 프로젝트의 **모든 지도**(심지어 같은 게임을 올린 다른 프로젝트까지) | X |
| 저장 전 검수의 기준 집합(`findIdsByProjectId`) | **프로젝트 전체** | X ← 결함 |
| 흐름 계획의 입구 씬(`ScenarioFlowPlanner:43`), 시작값 | 최신 지도 · 프로젝트 전체 | X |
| 저장 뒤 남은 미커버 권유(`recommendRemaining`) | 프로젝트 전체 | X |
| `projectFacts` 캐시 | 프로젝트 id 로만 키 | X |

### 규모 (조사 결과)

- 버전을 알아야 하는 서비스 메서드 · 호출 자리: **약 34곳**
  - 세션 열기 7, 턴 중 도구 8, 검수 12, 공용 판독기(`CaseConditionReader` · `ScenarioStateReader`) 7
- 바꿀 SQL: **17개** (`TestCaseRepository` 사용자 질의 14, 벡터 검색 1, `findById` · `findAllById`)
- 걸리는 줄 수: 약 1,000줄
  - `TestCaseRepository` ~300, `ScenarioReconcileService` ~250, `TestCaseService` ~200
  - `TestScenarioAgentService` ~80, `ScenarioFlowPlanner` ~75, `ScenarioPathService` ~60, `ScenarioCaseFactService` ~60
- 겹침: 지금 열린 원격 브랜치 중 이 파일들을 바꾸는 곳은 없다(최근 40개 기준). 메인 폴더도 develop 이다.

### 실측: 지금 데이터에서 내용이 갈렸나

클론 DB(로컬)에서 두 빌드에 같이 든 케이스는 87건이고, **버전마다 내용이 다른 케이스는 0건**이다. 같은 지도를 두 번 적재한 프로젝트라서다. 즉 내용 불일치(R2)는 아직 드러나지 않았고, 게임이 바뀐 빌드가 들어오면 그때부터 생긴다. 반면 R1 은 데이터와 상관없이 지금 생긴다.

## 현 상태의 리스크

| # | 리스크 | 언제 생기나 | 심각도 |
|---|---|---|---|
| **R1** | **버전이 있는 런의 시나리오 저장이 전부 막힌다.** 검수는 프로젝트 전체 TC 중 판정이 없는 것을 "검토 안 함" 으로 세고(`ScenarioCoverageAudit:137`), 1건이라도 있으면 거절한다(`:76`). 그런데 에이전트는 그 버전의 TC 만 받는다. | 프로젝트에 그 버전 밖 TC 가 하나라도 있으면(버전 없는 사용자 TC, 앞 버전에만 있던 TC). **클론 DB project 2: 버전 87건 / 전체 90건 → 3건 때문에 매번 거절.** 새 TR 은 기본으로 최신 빌드를 따르므로 거의 모든 런이 해당한다. | **치명. #313 이 만든 회귀다. 머지 전에 고쳐야 한다.** |
| R2 | 옛 버전 TR 에서 쓰면 지도는 옛 버전인데 TC 문구 · 조건은 최신 버전이다. 경로 · 흐름 계산이 엇갈린 조건으로 돈다. | 버전 사이에 TC 내용이 바뀐 뒤. 지금 데이터에서는 0건. | 중 |
| R3 | 능력을 잇는 SQL 9개가 `capability_key`(내용 해시)로 모든 지도의 능력을 잡는다. 값을 올리는 자리 · 입력 · 이동 같은 사실에 다른 빌드의 것이 섞인다. | 빌드가 둘 이상이고 능력 구성이 다를 때. #313 이전부터 있던 문제다. | 중 |
| R4 | 저장 뒤 "남은 미커버" 권유가 다른 버전 TC 까지 센다(`recommendRemaining`). 흐름 계획의 입구 씬 · 시작값이 최신 지도 · 전체 빌드다. | 버전이 있는 런. | 하 |
| R5 | 검색은 버전 필터를 LIMIT **뒤에** 걸어 결과가 limit 보다 적다. 임베딩은 처음 넣을 때의 문구로 고정되고 내용이 바뀌어도 다시 만들지 않는다. | 검색할 때. 임베딩 쪽은 버전과 상관없이 원래 있던 문제다. | 하 |
| R6 | `projectFacts` 캐시가 프로젝트로만 키를 잡아, 같은 프로세스에서 다른 버전 런의 사실이 섞일 수 있다. | 한 프로젝트에서 버전이 다른 두 런을 번갈아 쓸 때. | 하 |

## Approach (Checklist)

설계 원칙: **판독기(`CaseConditionReader` · `ScenarioStateReader`)와 검수 로직은 그대로 두고, TC 를 읽어 오는 자리만 바꾼다.** 버전 몫(`test_case_version`)을 대표 행과 같은 모양(`TestCaseEntity`, id 는 대표 id)으로 비춰 넘기면, 34곳 중 판독기 7곳과 순수 로직은 손대지 않아도 된다. 버전은 이미 있는 `AuthoringVersion` 맥락에서 읽는다.

- [ ] **Step 0: R1 막기 (#313 에 넣는다, 머지 전 필수)**
  - 검수 기준 집합을 맥락의 버전 케이스로 바꾼다(`ScenarioReconcileService:296`). 버전 없는 런은 지금처럼 프로젝트 전체다.
  - `recommendRemaining` 의 미커버도 같은 집합으로 거른다.
  - 검색은 버전이 있을 때 limit 를 넉넉히 가져온 뒤 거르고 자른다.
  - 테스트: 버전 밖 TC 가 있는 프로젝트에서 버전 런 저장이 거절되지 않는다. 버전 없는 런은 예전과 같다.
  - 규모: 약 40줄 + 통합 테스트 1개.
- [ ] **Step 1: 버전을 아는 TC 공급원 `AuthoringCases`**
  - 맥락에 버전이 있으면 그 빌드의 `test_case_version` 을 `TestCaseEntity` 모양(id = 대표 id, 내용 = 몫)으로 비춘다. 없으면 지금처럼 `test_case`.
  - 메서드: `all(projectId)` · `byId(id)` · `ids(projectId)`.
  - 바꿀 호출 자리(약 10곳):
    - `getAuthoringCases`, `ScenarioPathService.findPath`, `ScenarioCaseFactService.explain`
    - `ScenarioFlowPlanner.of`, `caseFacts`, `openingFacts`, 미커버 장면 세기, 검수 기준 집합
  - `projectFacts` 캐시 키를 `(projectId, gameBuildId)` 로 바꾼다.
  - 규모: 서비스 1개 신설 + 호출 자리 ~150줄.
- [ ] **Step 2: 능력과 잇는 SQL 을 그 빌드 지도로 좁힌다**
  - `tc.capability_key` 를 쓰는 9개 질의에 빌드판을 둔다(`test_case_version v` 의 `capability_key` 를 쓰고, 능력은 그 빌드의 `content_map` 으로 한정): `findValuesChangedByCases` · `findSceneExits` · `findGroundedSceneEdges` · `findCaseInputs` · `findWrittenValues` · `findStrayWrittenValues` · `findValueRaisers` · `findValueMoves` · `findCaseIdByCapability`.
  - `findStartingValues`, 흐름 계획의 입구 씬(`findEntrySceneNameOfBuild`)도 빌드판으로.
  - 호출하는 쪽은 맥락에서 빌드를 읽어 빌드판 · 프로젝트판을 고른다(한 곳에 모은다).
  - 규모: SQL 11개 ~300줄, 호출 자리 ~60줄.
- [ ] **Step 3: 테스트**
  - 갈린 내용 픽스처: 같은 케이스가 빌드 A 와 B 에서 스텝 · 조건 · 능력이 다르다(B 의 지도에서 능력 하나를 빼거나 조건을 바꾼다).
  - 단언:
    - A 런의 세션 목록 · `explain` · `findPath` · 검수가 A 의 내용을 쓴다.
    - B 런은 B 의 내용을 쓴다.
    - 버전 없는 런은 예전과 같다.
  - 회귀: 작성 쪽 기존 묶음(Coverage · Bridge · Absorb · OpeningNote · QuestionBuilder · FlowMatrix · PathService · AuthoringTrace · AllTestCases · ExpectedLabelLeak · AuthoringStateAfterGolden · CaseFact) 전부.
  - 측정: 같은 런 하나로 바꾸기 전후 세션 오픈 요청(`cases.json`)을 떠서 비교한다. 같은 지도 프로젝트는 **바뀐 것이 없어야** 한다.
- [ ] **Step 4: Rollout / Rollback**
  - 스키마 변경이 없다. Step 1 · 2 는 기능 스위치 없이 맥락이 있을 때만 갈린다(버전 없는 런은 지금 경로).
  - 되돌리기: 커밋 revert.

## Validation
- **Commands to run:**
  - `./mvnw -o test -Dkotlin.compiler.execution.strategy=in-process -Dtest='ScenarioReuseAcrossBuildsIntegrationTest,<작성 회귀 묶음>,OpenApiSnapshotTest'`
  - 로컬 스택: 클론 DB 에서 빌드 4 를 따르는 런으로 작성 한 번(에이전트 연결 필요).
- **Expected output:**
  - Step 0 뒤: 버전 밖 TC 가 있어도 저장이 거절되지 않는다.
  - Step 1 · 2 뒤: 갈린 픽스처에서 각 런이 자기 버전 내용을 쓴다. 같은 지도 프로젝트의 세션 요청은 바뀌지 않는다.

## Risks & Rollback
- **Risks:**
  - 검수 기준 집합을 좁히면, 다른 버전에만 있는 케이스는 "검토 안 함" 대상에서 빠진다. 의도한 동작이지만, 버전 없는 사용자 TC 를 그 런에서 쓰려면 먼저 그 버전에 넣어야 한다. 이 점을 화면에서 안내해야 할 수 있다.
  - 비춘 엔티티는 `verificationStatus` 도 그 버전 것이다. BROKEN 거르기가 버전 기준이 된다(맞는 방향).
  - 작성 코드가 깊어 빠뜨린 읽기가 남을 수 있다. Step 3 의 갈린 픽스처가 잡는다.
- **Rollback steps:** `git revert`(스키마 변경 없음).

## Open Questions
- Step 0 을 #313 에 바로 넣고, Step 1~3 은 새 Jira 로 따로 갈까?
- 버전 밖 사용자 TC(버전 없음)를 버전 런 작성에 넣을지? 넣는다면 "버전 + 버전 없음" 을 기준 집합으로 삼는다.
- 임베딩 갱신(R5)은 이번 범위에 넣을지?
