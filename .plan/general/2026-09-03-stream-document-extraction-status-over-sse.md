# 문서 추출 상태를 SSE로 흘린다 (ARTEL-760)

## Why

`project_document.parse_status`는 백그라운드 추출이 진행되면서 바뀌지만, 화면이 그 변화를 알
방법은 목록을 다시 부르는 것뿐이다. ARTEL-759 cross-repository 계약(Notion, 검토자 정윤성
2026-09-03)이 이미 경로·이벤트 모양·`stale` 규칙을 확정했다. 이 계획은 그 계약을
`artel-orchestration-server`에 그대로 구현하는 순서만 정리한다.

## What Changed (계획)

1. `project/dto/DocumentDtos.kt`에 `DocumentStreamEvent`, `DocumentParseStatusResponse` 추가
   (계약 문서의 필드·타입 그대로).
2. `project/service/DocumentEventStreamManager.kt` 신규.
   `TestScenarioStreamManager`를 본뜨되 key가 `projectId`(사용자별이 아님)라 구독자 하나가
   나가도 entry를 지우지 않는다 — `ScanStatusRegistry`처럼 `LinkedHashMap` +
   `removeEldestEntry`로 LRU 256을 건다. 계약 문서는 `ConcurrentHashMap`을 언급하지만 LRU
   축출은 `ConcurrentHashMap`으로 표현할 수 없으므로, "LRU 256 로 막는다"는 명시적 요구를
   따라 `ScanStatusRegistry`와 같은 자료구조를 쓴다(아래 `## 계약 해석` 참고).
3. `knowledge/service/DocumentKnowledgeExtractionService.kt`:
   - `inFlightDocumentIds: MutableSet<Long>`(`ConcurrentHashMap.newKeySet()`)을 추가해 이 서버가
     지금 들고 있는 추출을 추적한다.
   - `extractAndStoreForDocument`가 시작할 때 넣고 `finally`에서 뺀다.
   - `isStale(documentId, parseStatus)` 공개 함수 — `parseStatus == EXTRACTING && documentId
     !in inFlightDocumentIds`. 스냅샷과 실시간 push가 같은 함수를 쓴다.
   - `markStatus`가 DB 갱신 직후 `DocumentEventStreamManager.emit`으로 `document` 프레임을
     publish한다.
4. `project/service/ProjectDocumentService.kt`에 `events(userId, projectId)` 추가.
   `ProjectAccessService.requireMember`로 먼저 인가(Flow를 만들기 전에 호출 → 접근 불가면
   Flow가 열리지 않고 404) → `flow { emit(snapshot); emitAll(streamManager.stream(projectId)) }`.
5. `project/controller/ProjectDocumentController.kt`에
   `GET /api/projects/{projectId}/documents/events` (`produces =
   [MediaType.TEXT_EVENT_STREAM_VALUE]`, `suspend fun`, `Flow<ServerSentEvent<DocumentStreamEvent>>`).
6. 통합 테스트: snapshot 프레임, 실시간 `document` 프레임(EXTRACTING→FAILED, 실제 Agent 없이
   자연 발생), `stale=true`(수동으로 EXTRACTING을 박아 in-flight 집합 밖에 두는 방식으로
   재시작 유실을 흉내), 비참여자 404(스트림 자체가 안 열림).
7. `docs/api/openapi.json` 재생성(`OpenApiSnapshotTest`).

## 계약 해석

- **`ConcurrentHashMap` vs LRU**: 계약 문서 6절은 자료구조로 `ConcurrentHashMap`을 언급하면서
  같은 절에서 "지우지 말고 LRU 256으로 막는다"고 요구한다. `ConcurrentHashMap`은 삽입 순서를
  보존하지 않아 LRU 축출을 표현할 수 없다. `orch-facts.md`가 명시적으로 "`ScanStatusRegistry`처럼
  LRU 256으로 막는다"고 가리키므로, 실제 자료구조는 `ScanStatusRegistry`와 같은
  `Collections.synchronizedMap(LinkedHashMap(...))` + `removeEldestEntry`로 구현한다. 동시성
  안전성은 유지된다(래핑된 맵의 각 연산이 동기화된다) — `ConcurrentHashMap`이라는 단어보다
  "LRU 256, 지우지 않는다"는 명시적 acceptance 항목을 우선한다.
- **`extractionEnabled = false`일 때 `stale`**: 이 스위치가 꺼지면 문서는 애초에 `EXTRACTING`으로
  전이되지 않는다(`triggerExtractionInBackground`가 launch 자체를 안 함). `stale`은
  `parseStatus == EXTRACTING`을 전제하므로 이 모드에서는 정의상 절대 `true`가 되지 않는다 —
  별도 분기를 추가하지 않고 KDoc으로만 남긴다.

## Non-goals

재추출 트리거, 진행률, 새 `parse_status` 값 — 모두 계약과 이슈에 이미 명시.

## Validation

`MAVEN_OPTS="-Xmx2g -XX:MaxMetaspaceSize=768m" ./mvnw -o test
-Dkotlin.compiler.execution.strategy=in-process -Dtest=<클래스>
-DfailIfNoSpecifiedTests=false`로 새 테스트 클래스 + `ProjectDocumentIntegrationTest` +
`OpenApiSnapshotTest`를 돌린다. 전체 suite는 돌리지 않는다(이미 `develop`에 ~100건 무관한
실패가 있다).

Jira: ARTEL-760
