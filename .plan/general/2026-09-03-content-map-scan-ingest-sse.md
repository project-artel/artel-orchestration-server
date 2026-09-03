# content map 스캔 상태와 문서 적재 진행을 SSE 로 흘린다 (ARTEL-763)

## Why

화면이 스캔과 적재의 진행을 알려면 조회를 되풀이하는 수밖에 없다. `GET .../content-map/events`
를 열어 push 방식으로 흘린다. 계약은
`/tmp/claude-1000/-home-yunseong-dev-artel/abca79a4-fa70-43f2-a3a8-aa541396e8b8/scratchpad/contract-contentmap.md`
(ARTEL-762) 에 이미 못 박혀 있다.

## What

1. **DTO** (`contentmap/dto/ContentMapViewDtos.kt`): `ContentMapStreamEvent`, `IngestProgressResponse`,
   `ContentMapDocumentEventResponse`. 기존 `LastScanResponse` 를 그대로 재사용한다.
2. **`ContentMapEventStreamManager`** (`contentmap/scan/`): `TestScenarioStreamManager` 를 본뜨되
   key 가 `gameBuildId` 라 구독자가 여럿이다. `onCompletion` 에서 지우지 않고 `ScanStatusRegistry`
   처럼 LRU 256 으로 상한만 둔다. `closed` sentinel 없음 — 서버가 먼저 끊지 않는다.
3. **repository**: `ContentMapDocumentRepository.countIngestProgressByGameBuild(gameBuildId)` —
   `received`/`ingested`/`failed` 세 수. `ingest` 이벤트가 문서 하나마다 되풀이해 묻는 값이라
   가벼운 집계 하나로 둔다.
4. **publish 지점**:
   - `ContentMapScanService.startScan` — `REQUESTED` 로 바뀔 때 `scan` 이벤트(scope 문서가 명시한
     두 파일 밖이지만, 계약 3절 "ScanStatus 가 바뀔 때마다"를 문자 그대로 satisfy 하려면 필요.
     PR 에 명시적으로 남긴다).
   - `ScanResultRouter` — `fail()` 헬퍼와 `ingestFor` 의 성공/실패 분기에서 `scan` 이벤트.
   - `ContentMapIngestService.ingestBuild` — 문서 하나를 처리할 때마다 `ingest` + `document` 이벤트.
     `IngestOutcome` 만으로는 저장된 시각을 모르므로 `documents.findById` 로 다시 읽어 정확한 값을
     싣는다.
5. **`ContentMapViewService.events(userId, projectId, gameBuildId)`**: `read()` 와 같은 접근 검사
   (`gameBuilds.findAccessibleById`) 를 그대로 쓴다 — 계약 4절이 "404 접근할 수 없는 project 또는
   game build" 라 명시했고, `ProjectAccessService.requireMember` 하나만으로는 gameBuildId 가
   다른 프로젝트 것이어도 통과한다. snapshot 을 만들어 emit 한 뒤 `streamManager.stream(gameBuildId)`
   를 이어 붙인다.
6. **controller**: `GET .../content-map/events`, `produces = TEXT_EVENT_STREAM_VALUE`, `suspend fun`.
7. **OpenAPI**: `OpenApiSnapshotTest` 재실행.

## Non-goals

SDK 스캔 진행 보고, 실패 문서 자동 재적재, 서버가 계산하는 퍼센트.

## Validation

`MAVEN_OPTS=... ./mvnw -o test -Dkotlin.compiler.execution.strategy=in-process -Dtest=...` 로
새 테스트 클래스 + 기존 content map 테스트 클래스만. 전체 suite 는 돌리지 않는다(이미 ~100건
무관하게 깨져 있음).
