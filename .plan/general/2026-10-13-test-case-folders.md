# 2026-10-13 — test_case_folder 테이블과 폴더 API 추가

- Date: 2026-10-13
- Jira: ARTEL-969 (Story ARTEL-966)
- Status: Implemented (plan-review: fast NONPASS, medium PASS → revised; heavy NONPASS → revised → PASS; pair-review PASS)
- Stacked on: ARTEL-967 branch (`feat/orchestration-test-case-일괄-삭제-API-추가-ARTEL-967`, PR #290)

## Goal

test case 를 중첩 folder 로 묶는다. 사용자가 folder 를 만들고, 이름을 바꾸고, 옮기고, 지우고,
test case 를 folder 사이로 옮긴다. ARTEL-971 이 content map 에서 생성한 test case 를 넣을
folder 를 `content_map_id` 로 찾는다.

## Non-goals

- folder 정렬 순서 지정. 목록은 `id ASC` 로 낸다.
- content map folder 를 만드는 로직(ARTEL-971).
- test case 수정 요청(`PUT /test-cases/{caseId}`)으로 folder 를 바꾸는 것. 옮기기는 `/move` 하나로 한다.
- 기존 `test_case` 행의 `folder_id` 채우기. 전부 null(폴더 없음)로 시작한다.

## Context / Constraints

- 다음 migration 번호는 V99. `scripts/check-flyway-migrations.sh` 와 열린 PR 의 migration 파일을
  확인했다 (2026-10-13 기준 migration 을 가진 열린 PR 없음). push 직전에 다시 확인한다.
- `content_map` 은 build 당 하나이고(V63) 재적재해도 `content_map.id` 가 바뀌지 않는다
  (`ContentMapIngestService` 가 `document.contentMapId` 를 그대로 쓴다). 그래서 folder 의
  `content_map_id` 가 재적재 뒤에도 같은 content map 을 가리킨다.
- 테스트 DB 는 `pgvector/pgvector:pg16`. `UNIQUE NULLS NOT DISTINCT` 는 pg15 부터라 운영 DB 버전에
  기대지 않도록 partial unique index 두 개로 쓴다.
- `TestCaseEntity` 를 고치는 다른 경로(`MapTestCaseWriter`, `TestCaseService.updateTestCase`)는 모두
  `copy(...)` 를 쓴다. `folderId` 를 entity 에 추가해도 기존 갱신이 `folder_id` 를 null 로 덮지 않는다.
- DB 는 `test_case.folder_id` 의 folder 와 `parent_id` 의 folder 가 같은 프로젝트인지 강제하지
  않는다. folder 를 쓰는 경로는 이 PR 의 서비스뿐이고 모두 `requireFolder(projectId, folderId)` 를
  지난다. `MapTestCaseWriter` 와 `bulk-delete` 는 `folderId` 를 쓰지 않는다.
- `test_case.project_id` 에는 FK 가 없다(논리 참조). 프로젝트를 지우면 folder 는 cascade 로
  지워지고 남은 `test_case` 행의 `folder_id` 는 SET NULL 이 된다. 두 규칙이 부딪치지 않는다.
- 이름 비교: `trim()` 한 문자열을 대소문자 구분해 정확히 비교한다. 같은 규칙을 DB unique index 가
  쓰고, ARTEL-971 의 ` (2)` 번호 붙이기도 이 규칙으로 이름이 비었는지 본다.
- content map folder(`content_map_id` 있음)도 사용자가 이름을 바꾸고 옮기고 지울 수 있다.
  `content_map_id` 는 PUT 으로 바뀌지 않는다.
- 오류는 `common/error` 타입 예외: 400 `BadRequestException`, 404 `NotFoundException`,
  409 `ConflictException`.

## Approach (Checklist)

- [ ] **Step 1: Migration `V99__create_test_case_folder.sql`**
  ```sql
  CREATE TABLE IF NOT EXISTS test_case_folder (
      id BIGSERIAL PRIMARY KEY,
      project_id BIGINT NOT NULL REFERENCES project (id) ON DELETE CASCADE,
      parent_id BIGINT REFERENCES test_case_folder (id) ON DELETE CASCADE,
      name VARCHAR(200) NOT NULL,
      content_map_id BIGINT UNIQUE REFERENCES content_map (id) ON DELETE SET NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
  );
  CREATE UNIQUE INDEX ... ON test_case_folder (parent_id, name) WHERE parent_id IS NOT NULL;
  CREATE UNIQUE INDEX ... ON test_case_folder (project_id, name) WHERE parent_id IS NULL;
  CREATE INDEX ... ON test_case_folder (project_id);
  ALTER TABLE test_case ADD COLUMN IF NOT EXISTS folder_id BIGINT
      REFERENCES test_case_folder (id) ON DELETE SET NULL;
  CREATE INDEX ... ON test_case (folder_id);
  ```
  - `parent_id ... ON DELETE CASCADE` 가 하위 folder 를 지우고, `test_case.folder_id ... ON DELETE SET NULL`
    이 그 안의 test case 를 폴더 없음으로 돌린다. 서비스는 `DELETE` 한 문장만 실행한다.
  - `content_map_id ... ON DELETE SET NULL`: build 를 지우면 content map 이 cascade 로 지워지는데,
    그 folder 와 사용자가 옮겨 둔 test case 구성은 남긴다. folder 는 일반 folder 가 된다.
  - 같은 부모 아래 `parent_id` 는 같은 프로젝트라 `(parent_id, name)` 만으로 충분하다.
- [ ] **Step 2: Entity / Repository** (`testcase/entity`, `testcase/repository`)
  - `TestCaseFolderEntity(id, projectId, parentId, name, contentMapId, createdAt, updatedAt)`.
  - `TestCaseEntity.folderId: Long? = null` (`folder_id`).
  - `TestCaseFolderRepository : CoroutineCrudRepository`:
    - `findItemsByProjectId(projectId): Flow<TestCaseFolderRow>` — `LEFT JOIN content_map` 으로
      `game_build_id` 를 함께 읽는다. `ORDER BY f.id`.
    - `findByIdAndProjectId(id, projectId)`.
    - `existsSiblingName(projectId, parentId, name, excludeId)` — 409 를 판단한다.
      `parent_id IS NOT DISTINCT FROM :parentId` 로 써서 최상위(null)끼리도 비교한다.
    - `isSelfOrDescendant(folderId, candidateId): Boolean` — `WITH RECURSIVE ... EXISTS` 한 질의.
  - `TestCaseRepository.moveToFolder(projectId, ids, folderId): Int` —
    `UPDATE test_case SET folder_id = :folderId, updated_at = now() WHERE project_id = :projectId AND id IN (:ids)`.
- [ ] **Step 3: Service `TestCaseFolderService`** (`testcase/service`)
  - 모든 메서드 첫 줄: 비멤버면 `NotFoundException`.
  - `list` → `TestCaseFolderListResponse(items)`.
  - `create(name, parentId)`: name 검증 → parent 는 이 프로젝트 folder 여야 함(아니면 404) →
    형제 이름 중복이면 409 → save → 201.
  - `update(folderId, name, parentId)`: PUT 이라 두 칸을 모두 받는다. `parentId` 가 null 이거나 없으면
    최상위로 옮긴다. folder 와 parent 가 이 프로젝트에 없으면 404. `parentId == folderId` 이거나
    `isSelfOrDescendant(folderId, parentId)` 가 참이면 400. 형제 이름 중복이면 409 (자기 자신 제외).
    `content_map_id` 는 바꾸지 않는다.
  - `delete(folderId)`: 이 프로젝트에 없으면 404, 있으면 `DELETE` → 204.
  - **동시 요청**: create / update / delete 는 `TransactionalOperator` 트랜잭션 안에서 먼저
    `pg_advisory_xact_lock(hashtextextended('test_case_folder:' || :projectId, 0))` 를 잡는다
    (`FirstUserGate` 와 같은 방식). 한 프로젝트의 folder 쓰기가 줄을 서므로 이름 확인과 순환 확인이
    동시 요청에 뚫리지 않는다 — 두 요청이 A→B, B→A 로 옮겨 순환을 만드는 경우도 막힌다.
    그래서 unique violation 을 409 로 바꾸는 catch 는 두지 않는다. index 는 마지막 방어선으로 남는다.
    lock 함수는 `TestCaseFolderService.lockProjectFolders(projectId)` 하나로 두고 ARTEL-971 이 쓴다.
  - `requireFolder(projectId, folderId): TestCaseFolderEntity` — 이 프로젝트 folder 가 아니면 404.
    `TestCaseService`(move, create) 도 이것을 부른다. `TestCaseService` 는 folder repository 를 직접
    쓰지 않는다.
  - name 규칙: `trim()` 후 1~200자, 아니면 400.
  - id 문자열 파싱: `toLongOrNull`, 실패면 400 (ARTEL-967 과 같은 규칙). `testcase/dto/WireIds.kt` 에
    `internal fun parseWireId(value: String?, field: String): Long` 과
    `internal fun parseWireIds(values: List<String?>?, field: String): List<Long>`(1~500, distinct) 을
    두고 bulk-delete, move, folder API, test case 생성이 함께 쓴다. bulk-delete 의 동작은 그대로다.
- [ ] **Step 4: `/test-cases/move`** — `TestCaseService.moveTestCases(projectId, userId, request)`:
  비멤버 404 → `caseIds` 1~500 검증(400, `bulk-delete` 와 같은 함수) → `folderId` 가 있으면 이
  프로젝트 folder 여야 함(404) → `moveToFolder` → `{ moved }`. 다른 프로젝트 id 는 세지 않는다.
  folder 확인은 `TestCaseFolderService.requireFolder` 를 부른다. `folderId` 가 있으면
  `executeAndAwait` 안에서 `lockProjectFolders` → `requireFolder` → `UPDATE` 순서로 실행한다. 동시에
  그 folder 를 지우는 요청이 FK 위반(500)을 내지 못하게 delete 와 같은 lock 을 잡는다.
- [ ] **Step 5: Test case 응답과 생성 요청**
  - `TestCaseResponse.folderId: String?`, `TestCaseDetailResponse.folderId: String?`, mapper 갱신.
  - `TestCaseCreateRequest.folderId: String?` — 있으면 이 프로젝트 folder 여야 함(404), 형식이
    틀리면 400. `folderId` 가 있으면 move 와 같이 lock → `requireFolder` → save 를 한 트랜잭션에서 한다.
- [ ] **Step 6: Controller `TestCaseFolderController`** — `/api/projects/{projectId}/test-case-folders`
  GET(200) / POST(201) / PUT `/{folderId}`(200, 수정된 folder) / DELETE `/{folderId}`(204).
  `TestCaseController` 에 `POST /move`(200).
- [ ] **Step 7: Tests** — `TestCaseFolderIntegrationTest` (RANDOM_PORT + `WebClient`, ARTEL-967 테스트와 같은 방식)
  - 생성/목록: 응답 id 들이 문자열이고 `parentId`/`contentMapId`/`gameBuildId` 가 null 로 나온다.
  - content map 에 매단 folder(행을 직접 insert)는 목록에서 `contentMapId` 와 `gameBuildId` 를 낸다.
  - 형제 이름 중복 409 (최상위끼리, 같은 부모 아래). 다른 부모 아래 같은 이름은 201.
  - 하위 folder 를 최상위로 옮길 때 최상위에 같은 이름이 있으면 409 (null 부모 비교).
  - 순환 이동: 자기 자신 아래 400, 손자 아래 400. 형제 아래로는 200.
  - 삭제: 3단 트리(A > B > C)에서 A 를 지우면 B, C 까지 사라지고, 세 folder 안의 test case 는 남아
    `folderId` 가 null.
  - move: 다른 프로젝트 test case 와 없는 id 는 세지 않고 남는다; 중복 id 는 한 건; `folderId: null` 은
    폴더 없음으로 옮긴다; 다른 프로젝트 folder 로 옮기면 404; `caseIds: []` 는 400.
  - 비멤버 GET/POST/PUT/DELETE/move 404. 다른 프로젝트 folder 를 PUT/DELETE 하면 404.
  - test case 생성 요청의 `folderId` 가 응답과 목록에 나온다; 다른 프로젝트 folder 면 404;
    `"abc"` 면 400. 빈 문자열은 null 과 같게(폴더 없음) 본다.
  - 수정(`PUT /test-cases/{id}`)이 `folderId` 를 지우지 않는다.
- [ ] **Step 8: Docs** — `OpenApiSnapshotTest` 로 `docs/api/openapi.json` 을 다시 생성한다.

## Validation

- **Commands to run:**
  - `MAVEN_OPTS="-Xmx2g -XX:MaxMetaspaceSize=768m" ./mvnw -o test -Dkotlin.compiler.execution.strategy=in-process -Dtest='TestCaseFolderIntegrationTest,TestCaseBulkDeleteIntegrationTest,AllTestCasesIntegrationTest,MapTestCaseWriterGoldenTest,TestCaseSpecHttpIntegrationTest,OpenApiSnapshotTest' -DfailIfNoSpecifiedTests=false`
  - `./scripts/check-flyway-migrations.sh`
  - `./scripts/verify-flyway-upgrade.sh` (Docker 필요)
- **Expected output:** 모두 통과, flyway 두 스크립트 exit 0.

## Risks & Rollback

- **Risks:**
  - 깊은 folder 트리에서 `WITH RECURSIVE` 비용. 사람이 만드는 folder 라 수십 개 규모다.
  - home 이 PUT 에 `parentId` 를 빼고 이름만 보내면 최상위로 옮겨진다. PUT 계약(두 칸 전부)을
    PR 과 Jira 에 적는다.
- **Rollback steps:** `git revert`. 배포 뒤 되돌릴 때는 `test_case.folder_id` 칸과
  `test_case_folder` 테이블이 남는다(코드가 읽지 않으므로 무해). 지우려면 새 migration 으로 drop.

## Rejected feedback

- fast: 프로젝트 삭제 테스트 — 받지 않았다. `test_case.project_id` 에 FK 가 없어 부딪칠 규칙이 없고,
  Context 에 이유를 적었다.
- fast: unique violation 과 FK violation 을 나눠 409/404 로 바꾸기 — folder 쓰기(create/update/delete)와
  folder 를 가리키는 쓰기(move, `folderId` 가 있는 test case 생성)가 모두 같은 프로젝트 advisory lock 을
  잡고 확인과 쓰기를 한 트랜잭션에서 하므로, 이 서비스 안에서는 둘 다 생기지 않는다. 그래서 catch 를
  두지 않는다. (heavy review 가 move/생성이 lock 을 안 잡던 첫 수정안의 구멍을 짚어 고쳤다.)
- medium: `saveOrConflict` helper — 위와 같은 이유로 필요 없어졌다.

## Open Questions

- 없음.

## Pair review

- VERDICT: PASS, 후속 5건.
- 반영: 빈 문자열 id 를 `parentId`, `folderId`(move, 생성) 모두 null 로 읽게 `parseOptionalWireId` 하나로
  맞췄다; `@ResponseStatus` 로 OpenAPI 에 201/204 가 나오게 했다; 주석의 "폴더 없음" 을 "folder 없음" 으로,
  비유 문장을 고쳤다.
- ARTEL-971 로 넘김: content map folder 를 만드는 쪽도 `lockProjectFolders` 를 잡아야 같은 이름이 unique
  index 에 걸려 500 이 되지 않는다.
- 받지 않음: folder 삭제로 `folder_id` 가 SET NULL 된 test case 의 `updated_at` 은 바뀌지 않는다. 화면이
  `updated_at` 으로 정렬하지 않는다.
