# 2026-10-13 — test case 일괄 삭제 API 추가

- Date: 2026-10-13
- Jira: ARTEL-967 (Story ARTEL-965)
- Status: Implemented (plan-review: fast NONPASS → revised, medium PASS, heavy PASS; pair-review PASS)

## Goal

`POST /api/projects/{projectId}/test-cases/bulk-delete` 를 추가한다. home 이 여러 test case 를
요청 한 번으로 지우고, 일부만 지워지는 일이 없게 한다.

Contract (home Subtask 가 그대로 쓴다):

- 요청 본문 `{ "caseIds": ["12", "13"] }`. 1건 이상 500건 이하, 벗어나면 400.
- 응답 200 `{ "deleted": 2 }` — 실제로 지운 행 수.
- 프로젝트 멤버가 아니면 404.
- 그 프로젝트에 없는 id 는 무시하고 `deleted` 에 세지 않는다.
- 한 트랜잭션으로 지운다.

## Non-goals

- 단건 `DELETE /api/projects/{projectId}/test-cases/{caseId}` 동작 변경. 단건은 비멤버에게도
  204 를 낸다. 일괄 삭제는 이슈가 정한 대로 404 를 낸다.
- 시나리오 step 이 가리키는 `case_id` 정리. 단건 삭제와 같게, 시나리오가 인용한 케이스도 지운다.
  `MapTestCaseWriter` 가 인용된 케이스를 지우지 않고 `BROKEN` 으로 바꾸는 규칙(ARTEL-578)은
  content map 에서 기능이 사라졌을 때 자동으로 지우는 경로의 규칙이다. 일괄 삭제는 사용자가 고른
  케이스를 지우는 요청이라 단건 삭제와 같은 규칙을 따른다. 인용된 케이스를 건너뛰면 `deleted` 가
  요청 건수보다 작은 이유를 응답이 설명하지 못한다.

## Context / Constraints

- `TestCaseController` / `TestCaseService` / `TestCaseRepository` 에 붙인다.
- `test_case_embedding.test_case_id` 는 `ON DELETE CASCADE` 라 따로 지울 것이 없다.
  `grep -rn "REFERENCES test_case" src/main/resources/db/migration` 결과 `test_case` 를 가리키는
  FK 는 이것 하나다 (V23).
- 오류는 `common/error` 의 `BadRequestException` / `NotFoundException` 으로 던진다.
- `/bulk-delete` 는 POST 이고 `/{caseId}` 에는 POST 가 없어 경로가 겹치지 않는다.

## Approach (Checklist)

- [ ] **Step 0: Recon** — 끝냄. 위 Context 참고.
- [ ] **Step 1: Implementation**
  - `testcase/dto/TestCaseDtos.kt`: `TestCaseBulkDeleteRequest(caseIds: List<String>? = null)`,
    `TestCaseBulkDeleteResponse(deleted: Int)`.
  - `TestCaseRepository.deleteByProjectIdAndIdIn(projectId: Long, ids: Collection<Long>): Int` (`suspend`,
    `Modifying` import 추가) — `@Modifying @Query`
    `DELETE FROM test_case WHERE project_id = :projectId AND id IN (:ids)`. 문장 하나라 Postgres 가
    한 트랜잭션으로 실행한다. `TransactionalOperator` 로 감싸지 않는다 — 감쌀 두 번째 쓰기가 없다.
  - `TestCaseService.bulkDeleteTestCases(projectId, userId, request): TestCaseBulkDeleteResponse`
    - 순서: 멤버 확인(404) → 본문 검증(400). 비멤버는 본문과 무관하게 404 다.
    - 비멤버면 `NotFoundException("프로젝트를 찾을 수 없습니다.")`. 기존 메서드는 null 을 돌려
      controller 가 404 로 바꾸지만, 새 코드는 `error-handling.md` 대로 타입 예외를 던진다.
    - `caseIds` 가 null 이거나 0건이거나 500건 초과면 `BadRequestException`.
    - 원소는 `String.toLongOrNull()` 로 읽는다(trim 없음). null 원소, 숫자가 아닌 값, `Long` 범위를
      넘는 값은 `BadRequestException`. 그 프로젝트에 있을 수 없는 값이지만, 조용히 넘기면 home 쪽
      버그가 `deleted` 수 차이로만 드러난다. 이것은 이슈 계약에 없던 결정이라 PR 과 Jira 에 적는다.
      DTO 원소 타입은 `List<String?>?` 로 두어 `[null]` 이 500 이 아니라 400 이 되게 한다.
      JSON 숫자 `[12]` 는 Jackson 이 문자열로 바꿔 받아 준다 — 막지 않는다.
    - 중복 id 는 `distinct()` 로 합친다. 500건 상한은 중복을 합치기 전 배열 길이로 센다.
  - `TestCaseController.bulkDeleteTestCases` — `@PostMapping("/bulk-delete")`, `ResponseEntity` 없이
    응답 DTO 를 그대로 돌려 200 이 된다.
- [ ] **Step 2: Tests** — `TestCaseBulkDeleteIntegrationTest` (RANDOM_PORT + `WebClient`,
  `jwtService.issue(user)` 로 쿠키 인증; HTTP 상태와 본문 모양을 같이 확인한다)
  - 멤버가 자기 케이스 2건 + 다른 프로젝트 케이스 1건 + 없는 id 1건을 보낸다 → 200 `deleted=2`,
    다른 프로젝트 케이스는 남는다.
  - 빈 배열 → 400. 501건 → 400. 500건 → 200. `caseIds` 누락 → 400. 숫자가 아닌 id → 400.
    `[null]` → 400. `Long` 범위를 넘는 id → 400.
  - 비멤버 → 404, 케이스는 남는다.
  - 시나리오가 인용한 케이스도 지워진다(단건 삭제와 같은 규칙).
  - `"caseIds": "12"` (배열이 아님) → 400. WebFlux 가 `ServerWebInputException` 으로 막아 code 가
    `"error"` 다. home 은 code 로 분기하지 않는다고 PR 에 적는다.
  - 비멤버가 잘못된 본문을 보내도 404 다.
- [ ] **Step 3: Rollout / Rollback** — 스키마 변경 없음. `OpenApiSnapshotTest` 는 비교하지 않고
  `docs/api/openapi.json` 을 덮어쓴다. 실행 후 diff 를 commit 한다.

## Validation

- **Commands to run:**
  - `MAVEN_OPTS="-Xmx2g -XX:MaxMetaspaceSize=768m" ./mvnw -o test -Dkotlin.compiler.execution.strategy=in-process -Dtest=TestCaseBulkDeleteIntegrationTest,AllTestCasesIntegrationTest,OpenApiSnapshotTest -DfailIfNoSpecifiedTests=false`
  - `./scripts/check-flyway-migrations.sh` (migration 없음 확인)
- **Expected output:** 세 클래스 통과, snapshot 에 `/bulk-delete` 경로 1개 추가.

## Risks & Rollback

- **Risks:** 500건 `IN (...)` 은 bind 변수 500개다. Postgres 상한(32767)보다 충분히 작다.
- **Rollback steps:** `git revert`. 데이터 모양 변경이 없다.

## Rejected feedback

- fast #1 (인용된 케이스는 지우지 말고 `BROKEN`): 받지 않았다. Non-goals 에 이유를 적었다.
- medium: `Int`/`Long` 반환형은 `Int` 로 정했다(옆의 `TrackerRepositories` 와 같다).

## Open Questions

- 없음.

## Pair review

- VERDICT: PASS. 반영: `BULK_DELETE_LIMIT` 를 `private` 로, 500건 테스트의 없는 id 에 주석.
- 받지 않음: `test_case_embedding` cascade 테스트 — V23 의 `ON DELETE CASCADE` 를 확인했고 단건
  삭제도 같은 FK 에 기댄다. controller KDoc 에 400/404 나열 — 옆 endpoint 와 같은 수준으로 둔다.
