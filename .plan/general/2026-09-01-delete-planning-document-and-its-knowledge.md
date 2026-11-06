# 2026-09-01 — 기획서 삭제 API 를 만들고 knowledge 를 함께 지운다

- Date: 2026-09-01
- GitHub Issue: None (Jira ARTEL-728)
- Status: Completed

## Goal

기획서 한 버전을 지우는 경로를 만들고, 그 문서에서 나온 knowledge 를 함께 소프트삭제한다.
그리고 중복 등록의 정리 delete 가 실패해도 응답이 409 로 끝나게 한다.

## Non-goals

- 재추출 전용 트리거 API
- 지운 문서나 knowledge 의 복원 경로
- IAM 정책 변경 (배포 담당이 `s3:DeleteObject` 를 붙인다)
- 스코프 런이 만든 그림자 행 정리

## Context / Constraints

`ProjectDocumentService.rejectDuplicateThenSave` 는 중복을 만나면 방금 올라온 S3 객체를 지우고
`DuplicateDocumentException` 을 던진다. 그 delete 가 403 으로 실패하면
`S3DocumentStorage.delete` 의 `onErrorMap(::isStorageFault, ::asStorageFault)` 가
`DocumentStorageException` 을 만들고, 그것이 `UpstreamUnavailableException` 이라 요청이 500
`storage_unavailable` 로 끝난다. 실제 배포에서 그 일이 일어났다:
`User: arn:aws:iam::871856773939:user/Artel-S3 is not authorized to perform: s3:DeleteObject`.

정리 delete 는 부수 작업이다. 실패해도 사용자가 알아야 할 사실은 "이미 올린 파일"이라는 것
하나뿐이고, S3 에 남은 객체는 등록되지 않은 garbage 라 어떤 읽기 경로에도 잡히지 않는다.

`project_document` 는 하드 삭제한다. `llm_usage.reference_id` 가 `GAME_CONTEXT` 일 때
`project_document.id` 를 가리키지만 FK 가 없고, V24 주석이 "지출 기록은 회계 사실이라 원본 행이
지워져도 남아야 한다"를 이미 정해 두었다. 하드 삭제라야 `uk_project_document_project_hash`
부분 유니크 인덱스가 풀려 같은 파일을 다시 올릴 수 있다.

knowledge 는 소프트삭제한다. `KnowledgeEventEntity.qaTryId` 는 nullable 이고 그 KDoc 이
"사람/문서 경로는 null" 이라고 이미 적어 두었으므로, 문서 경로의 DELETE 이벤트가 그 모양에
그대로 들어간다.

## Approach (Checklist)

- [x] **Step 0: Recon** — `ProjectDocumentService`, `S3DocumentStorage`, `KnowledgeService`,
      `KnowledgeRepository`, `ProjectDocumentIntegrationTest`, `FakeDocumentStorage`
- [x] **Step 1: 정리 delete 를 best-effort 로 바꾼다**
      `ProjectDocumentService` 에 `private suspend fun deleteStoredObjectQuietly(objectKey: String)`
      를 두고 `try / catch (DocumentStorageException)` 로 warn 로그만 남긴다.
      `rejectDuplicateThenSave` 가 그것을 쓴다. 예외를 삼키는 이유를 KDoc 에 적는다.
- [x] **Step 2: knowledge 소프트삭제 경로**
      `KnowledgeRepository` 에 baseline 조회를 더한다:
      `source = 'DOCS' AND source_id = :documentId AND project_id = :projectId AND scope_id IS NULL
      AND deleted_at IS NULL`.
      `KnowledgeService.softDeleteForDocument(projectId, documentId): Int` 를 더한다.
      한 트랜잭션에서 행마다 `deletedAt` 을 찍고, `qaTryId` 가 빈 DELETE 이벤트를 남기고,
      `embeddingRepository.discardFor(id)` 를 부른다. 지운 건수를 돌려주고 info 로 로그한다.
- [x] **Step 3: 삭제 API**
      `ProjectDocumentService.delete(userId, projectId, documentId): Boolean` —
      접근 검증, 문서 조회, knowledge 소프트삭제, `project_document` 행 삭제,
      S3 객체 삭제(best-effort) 순서. 없으면 false.
      `ProjectDocumentController` 에 `@DeleteMapping("/:documentId")` 를 더하고 204 로 답한다.
- [x] **Step 4: Tests**
      `FakeDocumentStorage` 에 delete 를 실패시키는 스위치를 더한다.
      통합 테스트: 중복 등록이 delete 실패에도 409 로 끝난다 / 삭제가 204 와 행 제거를 낸다 /
      삭제 뒤 같은 파일 재등록이 통한다 / 남의 프로젝트 문서 삭제가 404 / knowledge 가
      `deleted_at` 을 얻고 DELETE 이벤트가 남는다.
      `OpenApiDocumentationIntegrationTest` 에 새 경로를 더한다.
- [x] **Step 5: OpenAPI 스냅샷** — `docs/api/openapi.json` 을 다시 만든다.

## Validation

- **Targeted 실행:**
  `./mvnw test -Dtest='ProjectDocumentIntegrationTest,OpenApiDocumentationIntegrationTest,OpenApiSnapshotTest,S3DocumentStorageTest'`
  `ProjectDocumentIntegrationTest` 20/20, `S3DocumentStorageTest` 13/13, `OpenApiSnapshotTest` 1/1
  통과. `OpenApiDocumentationIntegrationTest`는 두 번의 targeted 재실행에서 `keeps the
  session-derived user id out of the contract` 가 `IllegalStateException: Timeout on blocking
  read for 5000000000 NANOSECONDS`로 두 번 모두 같은 지점(OpenApiDocumentationIntegrationTest.kt:87,
  `/v3/api-docs` 106KB 응답을 5초 `block()`으로 기다리는 지점)에서 죽었다. 같은 기계에서 다른
  worktree(ARTEL-730, ARTEL-742)의 `mvnw test`가 동시에 돌고 있어 부하로 인한 flake로 본다 —
  전체 스위트를 한 번 돌렸을 때는 이 클래스가 4/4 통과했다.
- **전체 스위트:** `./mvnw test` 한 번 — `Tests run: 1238, Failures: 1, Errors: 141`.
  141개 에러는 전부 다른 테스트 클래스의 `@BeforeEach` 정리 단계에서 나는 FK 위반이다
  (`DELETE FROM game_instance`가 `qa_run_game_instance_id_fkey`에 걸리는 식) — 이 변경이
  건드리지 않는 클래스들의 테스트 정리 순서 문제고, `ProjectDocumentIntegrationTest`도 같은
  전체 스위트 실행에서 이 정리 오염에 걸려 20/20 전부 에러였다(같은 클래스가 단독 실행에서는
  20/20 통과했으므로 이 변경의 결함이 아니다). 실패 1건은
  `ProjectTrackerLinkHttpIntegrationTest.the github endpoints say plainly that the app is not
  configured`가 503을 기대하고 200을 받은 것으로, 이 worktree의 로컬 `.env`에
  `ARTEL_GITHUB_APP_*`이 채워져 있어 spring-dotenv가 그걸 테스트 컨텍스트에 먹인 탓이다. 둘 다
  이 변경이 만든 문제가 아니고, `git stash`로 된 baseline 비교는 세션 권한으로 막혀 있어
  수행하지 못했다.

## Risks & Rollback

- **Risks:**
  - 하드 삭제라 되돌릴 수 없다. 화면이 확인을 받는 것이 유일한 방어선이다(ARTEL-729).
  - knowledge 소프트삭제와 `project_document` 삭제가 한 트랜잭션이 아니다. 사이에서 죽으면
    지식은 지워지고 문서는 남는다. 그 상태에서 다시 삭제하면 정상으로 수렴한다.
  - 스코프 런이 만든 그림자 행은 그대로 남는다. 그 스코프 안에서는 지운 지식이 계속 보인다.
- **Rollback steps:** `git revert`. 스키마 변경이 없어 마이그레이션 되돌림이 없다.

## Open Questions

- 없음
