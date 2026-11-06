# 2026-10-13 — content map 적재에서 test case 생성 제거와 빌드별 생성 API 추가

- Date: 2026-10-13
- Jira: ARTEL-971 (Story ARTEL-964). Related: ARTEL-864
- Status: Implemented (plan-review: fast NONPASS, medium NONPASS → revised; heavy PASS; pair-review PASS)
- Stacked on: ARTEL-969 branch (`feat/orchestration-test_case_folder-테이블과-폴더-API-추가-ARTEL-969`)

## Goal

1. content map 적재가 test case 를 만들거나 바꾸거나 지우지 않는다.
2. 사용자가 build 하나를 골라 `POST /api/projects/{projectId}/game-builds/{gameBuildId}/content-map/test-cases`
   로 그 build 의 content map 에서 test case 를 생성한다. 응답 200
   `{ folderId, created, updated, deleted, broken }` (`folderId` 는 문자열).
3. 생성은 그 content map 에서 나온 test case 만 비교 · 갱신 · 삭제 · `BROKEN` 처리한다.
4. 새 test case 는 그 content map 의 folder 에 들어간다.

## Non-goals

- `MapTestCaseGenerator` 가 무엇을 만드는지 바꾸지 않는다.
- 이미 있는 test case 의 `folder_id` 를 채우지 않는다(사용자가 옮긴 folder 를 유지).
- 프로젝트에 이미 있는 content map test case 를 이번 배포에서 folder 로 옮기지 않는다.

## Context / Constraints

- `ContentMapIngestService.ingestInTransaction` 이 적재 끝에서 `mapTestCases.rewrite(contentMapId)` 를
  부르고, 결과를 `IngestResult.testCases` 에 싣는다. main 코드에서 `IngestResult.testCases` 를 읽는
  곳은 없다. 테스트 4개(`MapTestCaseWriterGoldenTest`, `IngestRealMapIntoProjectTest`,
  `AuthoringStateAfterGoldenTest`, `ScenarioFlowGoldenTest`)가 적재 뒤 test case 가 있다고 기대한다.
- `MapTestCaseWriter.isMine` 은 `metadata.origin == "content-map"` 만 본다. 그래서 프로젝트의 모든
  content map 의 test case 가 `mine` 에 들어가고, build A 로 생성하면 build B 의 test case 가 사라진
  것으로 판정되어 지워지거나 `BROKEN` 이 된다.
- `metadataOf(contentMapId, case)` 는 writer 가 처음 생긴 커밋(5eb3d05, 2026-09-01)부터
  `content_map_id` 를 적었다. content map 을 build 당 하나로 합친 V63(2026-08-29)이 그보다 먼저라,
  지금 있는 content map test case 의 `content_map_id` 는 모두 살아 있는 content map 을 가리키거나
  (build 가 지워졌으면) 아무것도 가리키지 않는다.
- `/internal/test-cases/content-maps/{contentMapId}/regenerate` 는 트랜잭션 없이 `rewrite` 를 부른다.
- `content_map` 행은 evidence 적재 없이도 있을 수 있다(V63). "적재된 content map" 은
  `content_map_document.ingested_at IS NOT NULL` 인 문서가 하나 이상 있는 content map 이다.
- folder 쓰기는 `TestCaseFolderService.lockProjectFolders` 를 잡고 해야 한다(ARTEL-969 pair-review).
  안 잡으면 동시에 만든 같은 이름이 unique index 에 걸려 500 이 된다.
- `ContentMapViewService.read` 의 접근 확인: `gameBuilds.findAccessibleById(gameBuildId, projectId, userId)`
  가 null 이면 404 (build 없음, 비멤버, 다른 프로젝트 build 를 구분하지 않는다).

## Decisions

- **`IngestResult.testCases` 를 지운다.** 적재가 test case 를 더는 건드리지 않으므로 늘 0 인 칸은
  "적재가 test case 를 확인했고 바뀐 것이 없다" 로 잘못 읽힌다. main 에 읽는 곳이 없다.
- **legacy 행**: `metadata.origin == "content-map"` 인데 `metadata.content_map_id` 가 없거나 숫자가 아닌
  행은 어느 content map 의 것도 아니다 — 비교하지도, 갱신하지도, 지우지도 않는다. 위 Context 대로
  실제로는 그런 행이 없다. 있다면 사람이 metadata 를 고친 것이고, 지우는 것보다 남기는 쪽이 안전하다.
- **content map 사이의 접기(같은 네 칸)가 사라진다.** `mine` 이 한 content map 으로 좁아지므로, build A
  와 build B 가 같은 test case 를 내면 각자 자기 folder 에 한 행씩 갖는다. build 별 folder 의 목적에 맞다.
- **역할 나누기** (medium review):
  - `MapTestCaseWriter.rewrite(projectId, contentMapId, folderId)` 는 비교와 쓰기만 한다. folder, lock,
    트랜잭션을 모른다. 새 행에 `folderId` 를 넣는다.
  - `TestCaseFolderService.findOrCreateContentMapFolder(projectId, contentMapId, buildName)` 가 folder 를
    찾거나 만든다. 이름 충돌 규칙이 `existsSiblingName` 옆에 있게 된다. 호출하는 쪽이 lock 과 트랜잭션을
    잡고 있어야 한다.
  - 새 `ContentMapTestCaseService` 가 한 트랜잭션 안에서 `lockProjectFolders` → folder → `writer.rewrite`
    를 부른다. 경로 둘: `generate(userId, projectId, gameBuildId)` (`/api`, 404/409 확인) 와
    `regenerate(contentMapId)` (`/internal`, 멤버 확인 없음). `/internal` controller 도 이 서비스를
    부르도록 바꿔, 두 경로가 따로 놀지 않게 한다.
  - build 이름(`label` 이 공백이 아니면 `label.trim()`, 아니면 `version`)은 서비스가 정해 문자열로 넘긴다.
- **lock**: ARTEL-969 의 `pg_advisory_xact_lock`(프로젝트별, commit 이나 rollback 때 풀림). 같은 프로젝트의
  다른 build 생성도 순서대로 실행된다 — 생성 한 번이 수 초라 받아들인다. 같은 build 를 두 요청이 동시에
  생성해도 두 번째는 첫 번째가 commit 한 행을 보고 `created = 0` 이 된다.
- **`/internal/.../regenerate` 의 동작**: 같은 `rewrite` 를 부르므로 이제 content map 범위와 folder 를 따른다.
  content map 의 project 를 못 찾으면 예전처럼 0건 결과를 낸다(`folderId` null). 응답은 예전과 같이
  `{ contentMapId, result }` 문자열 map 이고, 이 경로를 부르는 스크립트나 문서는 `openapi.json` 밖에 없다.
- **사용자가 content map folder 를 지웠을 때**: `test_case.folder_id` 는 `ON DELETE SET NULL`(V99)이라 그
  안의 test case 는 folder 없음으로 남는다. 다음 생성은 새 folder 를 만들고, 기존 행은 갱신만 한다
  (`folder_id` 를 바꾸지 않는다).
- **사용자가 test case 를 다른 content map 의 folder 로 옮겨도** 그 행은 `metadata.content_map_id` 로
  자기 content map 에 속한다. folder 는 정리용이고 출처가 아니다.
- **생성기가 0건을 내면** 이 content map 의 행이 모두 사라진 것으로 판정된다(인용된 것은 `BROKEN`).
  지금의 `rewrite` 동작 그대로이고, 범위가 이 content map 으로 좁아졌을 뿐이다.
- folder 이름: `label` 이 공백이 아니면 `label.trim()`, 아니면 `version`, 뒤에 ` content map`.
  같은 최상위 이름이 있으면 ` (2)`, ` (3)` … 를 붙여 처음 빈 이름을 쓴다(ARTEL-969 와 같은 trim 후
  대소문자 구분 비교). label 80자 + 접미사라 200자를 넘지 않는다.
- folder 를 찾을 때는 `test_case_folder.content_map_id` 만 본다. 사용자가 그 folder 를 옮기거나
  이름을 바꿨어도 같은 folder 를 쓴다. 사용자가 지웠으면 새로 만든다.
- 응답 `folderId` 는 이번 생성이 쓴 folder 다. 새 test case 가 0건이어도 folder 는 찾거나 만든다
  (화면이 생성 뒤 그 folder 를 열 수 있게).

## Approach (Checklist)

- [ ] **Step 1: 적재에서 생성 제거** — `ContentMapIngestService`: `mapTestCases.rewrite` 호출과
  `MapTestCaseWriter` 의존, `IngestResult.testCases` 칸을 지운다. 그 자리 주석을 "적재는 test case 를
  만들지 않는다. 생성은 사용자가 build 별로 요청한다(ARTEL-971)" 로 바꾼다.
- [ ] **Step 2: `MapTestCaseWriter.rewrite(projectId, contentMapId, folderId)`**
  - `isMine(row, contentMapId)`: origin 이 content-map 이고 `metadata.content_map_id` 가 이 id 인 행만.
  - `entityOf` 가 `folderId` 를 받는다. 기존 행 갱신은 `copy(...)` 그대로라 `folder_id` 를 바꾸지 않는다.
  - projectId 조회(`contentMaps.findProjectId`)는 서비스로 옮긴다.
- [ ] **Step 3: `TestCaseFolderService.findOrCreateContentMapFolder(projectId, contentMapId, buildName)`**
  — `folders.findByContentMapId` 가 있으면 그것, 없으면 `"$buildName content map"`, 그다음 ` (2)`, ` (3)`
  … 중 `existsSiblingName(projectId, null, candidate, null)` 이 거짓인 첫 이름으로 최상위에 만든다.
  만든 folder 는 `contentMapId` 를 채워 저장한다. 새 repository 함수: `TestCaseFolderRepository.findByContentMapId`.
- [ ] **Step 4: `ContentMapTestCaseService`** (`testcase/service`)
  - `generate(userId, projectId, gameBuildId)`: `findAccessibleById` null → `NotFoundException`;
    `contentMaps.findByGameBuildId` null 이거나 `documents.existsByContentMapIdAndIngestedAtIsNotNull`
    거짓 → `ConflictException`; `generateInTransaction(projectId, contentMapId, build)` →
    `ContentMapTestCaseGenerationResponse(folderId: String, created, updated, deleted, broken)`.
  - 새 repository 함수: `ContentMapDocumentRepository.existsByContentMapIdAndIngestedAtIsNotNull`.
  - `regenerate(contentMapId)`: `contentMaps.findById` → build → project. 못 찾으면 0건 `Result`.
  - `generateInTransaction`: `executeAndAwait { lockProjectFolders; findOrCreateContentMapFolder; writer.rewrite }`.
  - `MapTestCaseWriter.Result` 에 `folderId: Long? = null` 을 더한다(못 찾은 경우 null). `/internal` 응답
    문자열에 함께 찍힌다.
- [ ] **Step 5: Controllers** — 새 `ContentMapTestCaseController`
  `POST /api/projects/{projectId}/game-builds/{gameBuildId}/content-map/test-cases`. `MapTestCaseRegenerateController`
  는 `ContentMapTestCaseService.regenerate` 를 부른다(경로와 응답 모양은 그대로). KDoc 의 "케이스는 적재될 때
  함께 앉는다" 를 고친다.
- [ ] **Step 6: Tests**
  - `MapTestCaseWriterGoldenTest`: 적재 뒤 `ContentMapTestCaseService.regenerate(contentMapId)` 를 불러 같은 수치(88건,
    두 번째 0건 …)를 확인하도록 바꾼다. 적재만 했을 때 test case 수가 그대로인지 확인하는 시험 추가.
  - `IngestRealMapIntoProjectTest`, `AuthoringStateAfterGoldenTest`, `ScenarioFlowGoldenTest`: 적재 뒤
    `ContentMapTestCaseService.regenerate(contentMapId)` 한 줄을 넣는다.
  - 새 `ContentMapTestCaseGenerationIntegrationTest` (RANDOM_PORT + `WebClient`, 실측 문서
    `wv-play-2026-09-01.json` 을 build 두 개에 각각 적재):
    - 적재 뒤 test case 수가 변하지 않는다(0 → 0, 손으로 쓴 1건 그대로).
    - build A 생성 → 200, `created > 0`, 새 행이 모두 A folder 에 있다, folder 이름 `"<label> content map"`.
    - build B 생성 → B folder 에 따로 생긴다. 다시 A 를 생성해도 `deleted = 0`, `broken = 0`, B 의 행 수 그대로.
    - 사용자가 A 의 test case 하나를 다른 folder 로 옮기고 A 를 다시 생성해도 그 행의 `folder_id` 가 그대로다.
    - 최상위에 `"<label> content map"` 이 이미 있으면 새 folder 이름은 `"<label> content map (2)"`.
      label 이 없으면 version 을 쓴다.
    - 404: 없는 build, 실제 build 에 대한 비멤버 요청(409 가 아니라 404), 다른 프로젝트 build.
      409: content map 이 없는 build, 문서를 적재하지 않은 content map.
    - 같은 build 에 두 요청을 동시에 보내도 둘 다 200 이고 test case 가 두 벌 생기지 않는다.
    - 사용자가 content map folder 를 지운 뒤 다시 생성하면 새 folder 가 생기고, 기존 행은 folder 없음으로 남는다.
    - `/internal/.../regenerate` 도 같은 folder 와 범위를 쓴다.
- [ ] **Step 7: Docs** — `OpenApiSnapshotTest` 로 `docs/api/openapi.json` 갱신.

## Validation

- **Commands to run:**
  - `MAVEN_OPTS="-Xmx2g -XX:MaxMetaspaceSize=768m" ./mvnw -o test -Dkotlin.compiler.execution.strategy=in-process -Dtest='ContentMapTestCaseGenerationIntegrationTest,MapTestCaseWriterGoldenTest,IngestRealMapIntoProjectTest,AuthoringStateAfterGoldenTest,ScenarioFlowGoldenTest,ContentMapIngestGoldenTest,ContentMapReingestTest,ContentMapIngestTransactionTest,TestCaseFolderIntegrationTest,OpenApiSnapshotTest' -DfailIfNoSpecifiedTests=false`
  - `./scripts/check-flyway-migrations.sh` (migration 없음)
  - 배포 전 운영 DB 확인용 질의(PR 에 적는다, 여기서 실행하지 않는다): `metadata` 의 `content_map_id` 가
    없는 content map test case 수 —
    `SELECT count(*) FROM test_case WHERE metadata->>'origin' = 'content-map' AND (metadata->>'content_map_id') IS NULL`.
    0 이 아니면 그 행들은 이 변경 뒤 어떤 생성도 건드리지 않는다.
- **Expected output:** 모두 통과.

## Risks & Rollback

- **Risks:**
  - 배포 뒤에는 SDK 가 새 evidence 를 올려도 test case 가 자동으로 생기지 않는다. 사용자가 build 별로
    생성 버튼을 눌러야 한다 — 이 Story 의 의도다. `qa-run-local` 같은 로컬 절차가 적재만으로 test case 가
    생긴다고 가정하면 `/internal/.../regenerate` 나 새 API 를 한 번 더 불러야 한다.
  - 기존 content map test case 는 folder 가 없다. 첫 생성은 그 행들을 그대로 두고(갱신만) 새 행만
    folder 에 넣는다.
- **Follow-up (이 레포 밖)**: 상위 workspace 의 `qa-run-local` skill 절차가 적재만으로 test case 가
  생긴다고 가정한다. 이 worktree 밖이라 여기서 고치지 않고 보고한다.
- **Rollback steps:** `git revert`. 스키마 변경이 없다.

## Rejected feedback

- fast #7 (대소문자 다른 이름 테스트): ARTEL-969 의 `TestCaseFolderIntegrationTest` 가 이미 같은 비교
  규칙을 확인한다. 여기서 다시 하지 않는다.
- fast: 재적재와 생성이 겹치는 경우 — 적재는 test case 와 folder 를 쓰지 않으므로 겹쳐도 각자의 행만
  쓴다. 생성이 적재 도중의 content map 을 읽으면 그 시점에 commit 된 상태를 쓴다. 다음 생성이 다시 맞춘다.
- medium #4 ("멤버 확인 없는 `/internal`"): 받았다. `regenerate` 는 멤버 확인을 하지 않는다.

## Open Questions

- 없음.

## Pair review

- VERDICT: PASS. 반영: 주석의 "입구" 를 "경로" 로 고쳤다.
- 이미 있음: 없는 content map 의 `regenerate` 는 `internal 재생성도 같은 folder 와 범위를 쓴다` 테스트가
  `regenerate(999_999_999).folderId == null` 로 확인한다.
- 받지 않음: `findByContentMapId` 에 `projectId` 조건 추가 — `content_map_id UNIQUE` 와 content map 의
  project 가 이미 한 프로젝트로 묶는다.

## Test note

`ScenarioFlowGoldenTest` 는 혼자 돌리면 통과하고, `MapTestCaseWriterGoldenTest`,
`AuthoringStateAfterGoldenTest` 뒤에 같은 JVM 에서 돌리면 실패한다. ARTEL-969 branch(이 변경 전)에서도
같은 조합으로 똑같이 실패하므로 이 변경과 무관한 실행 순서 문제다.
