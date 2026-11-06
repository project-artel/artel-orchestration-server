# 2026-09-01 — try·issue·지식 응답에 qa_run_id 를 싣는다

- Date: 2026-09-01
- Jira: ARTEL-722
- Status: Reviewed (fast NONPASS→revised, medium PASS, heavy PASS)

## Goal

`qa_try.qa_run_id` 는 DB(`V30__create_qa_run.sql`)와 `QaTryEntity.qaRunId` 에 이미 있는데, 어느
응답도 그것을 내지 않는다. 화면이 QA 실행 링크를 만드는 다섯 자리 — `QaTryResponse`,
`QaRunResponse.tries`, `IssueResponse`, `KnowledgeGraphNode` — 가 전부 try id 만 들고 있어 run
콘솔로 올라갈 재료가 없다. `ARTEL-720` 의 `artel-home` 몫이 이 계약을 기다리고 있다.

세 응답에 `qa_run_id` 를 문자열로 싣는다:

- `QaTryResponse.qaRunId: String?`
- `IssueResponse.qaRunId: String?`
- `KnowledgeGraphNode.createdByQaRunId: String?`

## Non-goals

- `qa_try.qa_run_id` 를 `NOT NULL` 로 바꾸지 않는다. 단독 실행(하위호환) try 행이 남아 있다.
- 옛 try 에 `qa_run` 행을 소급해 만들지 않는다. `qa_run.test_run_id` 가 `NOT NULL` 이라 채울 값이
  없다.
- `POST /api/qa-tries` 단독 실행 endpoint 를 없애지 않는다.

## Context / Constraints

- `QaTryResponse` 는 `QaTryService.toResponse()` 한 곳에서만 만들어진다(`QaTryService.kt:707`).
  `GET /api/qa-tries/{id}`, `GET /api/qa-tries?projectId=`, `QaRunResponse.tries` 모두 이 함수를
  거치므로 여기 한 번만 고치면 셋 다 덮인다.
- `IssueEntity` 는 `qaTryId` 만 들고 `qaRunId` 는 없다 — run id 는 조회로 얻어야 한다.
  `IssueService.page()`(`IssueService.kt:214`)가 한 페이지의 tracker 상태를
  `trackerSync.trackersOf(...)` 로 이미 배치 조회하고 있고, `listByQaTry`·`listByProject` 둘 다
  이 함수 하나로 모인다 — 같은 자리에 qa_try 배치 조회를 하나 더 붙인다. `IssueController` 의
  전이(`resolve`/`reopen`/`tracker-sync`) 는 `IssueResponse` 를 만들지 않으므로 손대지 않는다.
- `KnowledgeGraphViewService.graph()` 는 `source = "QA"` 인 노드의 `sourceId` 를 `qaTryId` 로 읽어
  `createdByQaTryId` 에 싣는다(`KnowledgeGraphViewService.kt:118`, `toNode` 확장 함수). 같은
  `sourceId` 로 `qa_run_id` 를 얻으려면 QA 노드의 `sourceId` 집합을 모아 한 번에 조회해야 한다 —
  노드 상한이 500(`MAX_NODES`)이라 노드마다 조회하면 페이지 하나가 최대 500 질의가 된다.
- 셋 다 배치 조회 재료는 같다: `qaTryId` 집합 → `qa_try.qa_run_id`. `QaTryRepository` 는
  `CoroutineCrudRepository` 라 `findAllById(ids): Flow<QaTryEntity>` 를 새로 만들 필요 없이 그대로
  쓸 수 있다. `SimpleR2dbcRepository.findAllById` 는 빈 컬렉션이면 DB 를 부르지 않고
  `Flux.empty()` 를 바로 돌려주므로(`anchorsFor` 가 쓰는 커스텀 `@Query IN (...)` 과 달리 이
  메서드 자체는 빈 입력에 안전하다) `IssueService.page()` 쪽엔 별도 빈 컬렉션 방어가 필요 없다.
  다만 `KnowledgeGraphViewService.graph()` 쪽은 QA 소스 노드가 하나도 없을 때 조회 자체를
  건너뛰는 편이 의도를 분명히 하고, `anchorsFor` 와 같은 모양을 유지한다.
- id 는 이 repository의 다른 응답과 같은 이유로 문자열로 낸다(64비트 값이 JSON 숫자로 나가면
  정밀도를 잃는다).
- `KnowledgeGraphViewService` 가 `QaTryRepository` 를 물게 되어 `knowledge` 패키지가 `qa` 패키지
  리포지토리에 의존한다. 새로운 종류의 결합이 아니다 — `IssueService` 가 이미 `qa.repository.
  QaTryRepository` 를 물고, `qa` 쪽 여러 서비스도 이미 `knowledge.service`/`knowledge.entity` 를
  문다. 두 패키지는 이미 서로 리포지토리·서비스를 자유롭게 참조하는 관계다.
- 배치 조회로 얻는 `qaTryId -> qaRunId` 맵은 조회되지 않은 id 에 대해 키가 없다 — 지워졌거나
  존재하지 않는 `qa_try` 를 가리키는 `sourceId` 라도 `map[id]` 가 `null` 을 주므로 별도 처리 없이
  `createdByQaRunId` 가 그냥 `null` 로 떨어진다. 이는 요구사항(옛 try 는 `null`)과 같은 모양이라
  안전하다.

## Approach (Checklist)

- [ ] **Step 0: Recon** — 끝남. 세 응답의 조립 경로를 확인했다(`QaTryService.toResponse`,
      `IssueService.page`, `KnowledgeGraphViewService.graph`/`toNode`). `IssueController` 의
      전이 endpoint 는 `IssueResponse` 를 만들지 않는다.
- [ ] **Step 1: Implementation**
  - `QaDtos.kt` — `QaTryResponse` 에 `qaRunId: String? = null` 추가.
  - `QaTryService.kt:707` — `toResponse()` 에 `qaRunId = qaRunId?.toString()` 추가.
  - `IssueDtos.kt` — `IssueResponse` 에 `qaRunId: String?` 추가.
  - `IssueService.kt` — `page()` 에서 `qaTryRepository.findAllById(items.map { it.qaTryId }.distinct())`
    로 qa_try 를 한 번에 읽어 `qaTryId -> qaRunId` 맵을 만들고, `toResponse()` 에
    `qaRunId: Long?` 파라미터를 추가해 문자열로 실어 보낸다.
  - `KnowledgeGraphViewDtos.kt` — `KnowledgeGraphNode` 에 `createdByQaRunId: String?` 추가.
  - `KnowledgeGraphViewService.kt` — `KnowledgeGraphViewService` 생성자에 `QaTryRepository` 주입.
    `graph()` 에서 `source == "QA"` 인 노드들의 `sourceId` 를 모아
    `qaTryRepository.findAllById(...)` 로 한 번에 읽고 `qaTryId -> qaRunId` 맵을 만든다. `toNode`
    확장 함수에 그 맵을 전달해 `source == "QA"` 일 때만 `createdByQaRunId` 를 채운다. QA 노드가
    하나도 없으면 조회를 건너뛴다(빈 컬렉션으로 `IN ()` 이 되는 문제 방지, 기존 `anchorsFor` 와
    같은 패턴).
- [ ] **Step 2: Tests** — 세 응답 각각, run 에 속한 try 와 속하지 않은 try(= `qaRunId = null`)를
      함께 세우고 두 값을 단언하는 test 를 추가한다.
  - `QaTryService` 를 부르는 기존 통합 test 자리(`QaRunConfigPersistenceIntegrationTest` 류)에
    맞춰, run 에 속한 `qa_try` 와 단독 `qa_try` 를 만들고 `QaTryResponse.qaRunId` 를 확인.
  - `IssueHttpIntegrationTest` 또는 `IssueIntegrationTest` 에 두 실행(하나는 run 소속, 하나는
    단독)이 각각 남긴 이슈를 만들고 `IssueResponse.qaRunId` 를 확인.
  - `KnowledgeGraphViewIntegrationTest` 에 `source = "QA"` 이고 `sourceId` 가 run 소속 try 인
    지식과, `sourceId` 가 단독 try 인 지식을 만들어 `createdByQaRunId` 를 확인. 기존
    `만든 런은 QA 항목에만 실린다` test 는 실제 qa_try 행이 없는 임의 sourceId 를 쓰므로 그대로
    두고, run id 검증은 새 test 로 추가한다.
- [ ] **Step 3: Rollout / Rollback** — 순수 조회 추가라 마이그레이션이 없다. 되돌릴 때는 이 커밋을
      revert 하면 된다. `home` 은 이 필드가 없으면 이전처럼 링크를 만들지 못할 뿐이라 롤백이
      깨지는 소비자는 없다.

## Validation

- **Commands to run:** `./mvnw test` (project.md 의 명령. Jira 의 Validation Notes 는
  `./gradlew test` 라고 적혀 있지만 이 저장소는 Maven — 문서 오기다.)
- **Expected output:** 전체 test 통과, 새로 추가한 세 test 포함.

## Risks & Rollback

- **Risks:** `IssueService.page` 와 `KnowledgeGraphViewService.graph` 에 배치 조회를 하나씩 더
  얹으므로 각 페이지의 질의 수가 하나씩 늘어난다 — N+1 은 아니지만 페이지당 상수 비용은 커진다.
  기존 `trackersOf`/`anchorsFor` 와 같은 급이라 새로운 종류의 위험은 아니다.
- **Rollback steps:** `git revert` — 스키마 변경이 없어 앞뒤 호환이 그대로 유지된다.

## Open Questions
- (없음)
