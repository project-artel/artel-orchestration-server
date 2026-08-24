# 2026-08-21 — home 이 원격 스캔을 시킨다

- Date: 2026-08-21
- Jira: ARTEL-492
- Status: Implemented

## Goal

home 의 버튼 하나가 붙어 있는 game instance 에 근거 스캔을 시키고, 그 결과가 씬 명세로
앉기까지를 서버가 끝까지 책임진다.

```
home → POST /api/projects/{projectId}/game-builds/{gameBuildId}/content-map/scan
     → 붙어 있는 인스턴스에 scan_evidence 액션
     → SDK 가 스캔 → 기존 /api/sdk/game-builds/{id}/content-map/ticket → PUT → register
     → SDK 가 ACTION_RESULT 로 답한다
     → 서버가 그 빌드의 대기 문서를 적재한다
```

## Non-goals

- SDK 쪽 구현 (ARTEL-491)
- 브라우저 업로드 엔드포인트. 사람이 파일을 고르는 안은 폐기됐다(PR #152 를 닫았다)
- 진행률 스트리밍. 시작과 끝만 답한다
- 자동 적재 스케줄러. `findPending` 의 프로덕션 호출자는 이 이슈가 처음 만든다
- 새 마이그레이션. `ingest_failed_at` · `ingest_error` 는 V48 이 이미 만들었다

## Context / Constraints

### 이미 있는 레일 — 새로 깔지 않는다

- `sdk/service/SessionManager.kt` — `sendAction(instanceId, ActionResponseDto)`
- `sdk/service/handler/ActionResultMessageHandler.kt` — `ACTION_RESULT` 를 QA 브리지로 넘긴다
- `qa/service/QaActionDispatchService.kt` — 액션을 보내는 기존 사용례
- `contentmap/ingest/ContentMapIngestService.kt` — `ingest(document)` 는 문서 하나가 한 트랜잭션
- `contentmap/repository/ContentMapDocumentRepository.kt` — `findPending(limit)` 은 **전역**이다
- `content_map_document.ingest_failed_at` · `ingest_error` (V48). **적는 코드는 이 이슈가 만든다**

### SDK 와 맞춘 계약 (ARTEL-491 과 같은 시각에 만든다)

- 서버 → SDK 액션 이름 **`scan_evidence`**, 파라미터 없음 (SDK 가 자기 `gameBuildId` 를 안다)
- SDK → 서버 결과: 기존 `ACTION_RESULT` 프레임에
  `{ "action": "scan_evidence", "success": true|false, "error": string|null }`

### 규약

- 접근 검사는 **서비스 안**에 둔다(`EvidenceDocumentService` · `ContentMapViewService` 와 같은 자리)
- 4xx 응답에 잡은 예외의 원문 메시지를 싣지 않는다(`error-handling.md`)
- `common/error` 의 타입 예외를 쓴다. 새 `ResponseStatusException` 금지
- 설정값은 `@ConfigurationProperties`. 새 `@Value` 금지.
  **이 diff 는 설정값을 하나도 더하지 않는다** — 배치 크기와 registry 상한은 둘 다 운영 중에
  아무도 다시 고르지 않을 내부 값이라 상수로 둔다(`ContentMapIngestService.DEFAULT_BATCH` 가 이미
  그 선례다). 규약은 "설정을 `@Value` 로 흩지 마라"이지 "상수를 설정으로 올려라"가 아니다

## 정한 것과 그 근거

### 1. 비동기(202)로 간다 — 다만 구멍은 남기지 않는다

조사 결과 이 저장소에는 "보내고 답을 기다리는" 원시 도구가 **없다**:

- `QaActionDispatchService.dispatch` 는 `qa_log` 행을 남기고 `sendAction` 을 부른 뒤 그대로
  반환한다. 짝은 나중에 `ACTION_RESULT` 가 `requestId` 로 맞춘다
- `SessionManager.send` 의 KDoc 이 못 박는다 — *"이 함수가 반환됐다는 것은 '보냈다'가 아니라
  '보낼 줄에 세웠다'는 뜻"*

동기(A)로 가면 상관 id 레지스트리 · 타임아웃 · 죽은 세션 정리를 이 diff 가 떠안는다. 이슈
크기가 아니다. **(B) 202 로 간다.**

그런데 home 검토가 짚은 "요청은 갔다까지만 알고 끝난다"는 실제 문제다. 세 가지로 막는다:

1. **202 응답이 무엇을 기다리면 되는지 말한다** — 어느 인스턴스에 보냈고(`gameInstanceId` ·
   `gameInstanceName`), 무엇이 바뀌는 것을 보면 되는가(`state`)
2. **`ACTION_RESULT` 가 돌아오면 그때 적재한다.** 적재가 실패하면 `ingest_failed_at` ·
   `ingest_error` 에 적는다 → 조회 API 가 그 사유를 그대로 실어 내고, 화면이 "눌렀는데 안 됐다"를
   말할 수 있다. **이것이 폴링이 빈손으로 끝나지 않게 하는 고리다**
3. **스캔 자체의 실패도 같은 질문에 답한다** — 아래 3번

### 2. 액션 결과 라우팅은 **액션 이름**으로 가른다

`ACTION_RESULT` 는 지금 전부 `QaSdkBridgeService.routeActionResult` 로 간다. 스캔 결과를 이쪽으로
데려오되 QA 경로를 깨뜨리면 안 된다.

**가르는 축은 `payload.action == "scan_evidence"` 다.** id 로 가르지 않는 이유:

- 우리에게는 `qa_log` 같은 id 발급처가 없다. 별도 카운터를 두면 `qa_log` id 와 값이 겹칠 수 있고,
  겹치는 순간 QA 결과가 스캔으로 잘못 샌다
- 액션 이름은 **우리가 보낸 액션에만** 실린다. QA 가 보내는 액션의 method 는 agent 가 정하며
  `scan_evidence` 가 아니다

그래서 핸들러는 이렇게 된다:

```kotlin
if (scanResults.handle(instanceId.toLong(), payloadText)) return   // 스캔 결과면 여기서 끝
qaBridge.routeActionResult(instanceId.toLong(), payloadText)       // 아니면 지금까지 그대로
```

`handle` 은 `action` 이 `scan_evidence` 일 때만 `true` 다. **`action` 칸이 없는 프레임(=QA 가 지금
주고받는 모양)은 한 글자도 달라지지 않는다.** 테스트가 그 사실을 짚는다.

액션 id 는 SDK 가 echo 하라고 보내지만 **우리는 그것으로 짝을 맞추지 않는다**. 그래서 `qa_log`
id 와 겹쳐도 무해하다. 프로세스 안의 `AtomicLong` 으로 발급한다.

### 3. 스캔 자체가 실패하면 어디에 남기나 — `ScanStatusRegistry`

`success:false` 면 SDK 는 아무것도 올리지 않았다. 그래서 **`content_map_document` 행이 아예
없고**, `ingest_failed_at` 에 적을 자리가 없다. (첫 스캔이 실패하면 `content_map` 행조차 없다.)

고를 수 있는 것:

| 안 | 문제 |
|---|---|
| 로그만 남긴다 | 화면이 "눌렀는데 안 됐다"를 말할 수 없다 — 금지된 바로 그 구멍 |
| 대기 문서에 적는다 | 그 문서들은 이번 실패와 무관하다. `ingest_failed_at` 은 "적재가 깨졌다"는 뜻인데 적재는 시작도 안 했다 — 거짓말이 된다 |
| 새 칸·새 표 | 마이그레이션 금지 |
| **프로세스 안에 빌드별 마지막 스캔 상태를 둔다** | **재시작하면 사라진다** |

**마지막 안을 고른다.** 사라지는 것이 여기서는 받아들일 만하다 — 이 값이 답하는 질문은 "방금
내가 누른 버튼이 어떻게 됐나"이고, 서버가 재시작된 뒤의 옳은 답은 "다시 눌러라"다. 내구성이
필요한 것(어떤 문서가 왜 못 앉았나)은 그대로 `ingest_failed_at` · `ingest_error` 에 durable 하게
남는다. 둘은 서로 다른 질문이고 서로 다른 자리에 산다.

`ScanStatusRegistry` 는 `gameBuildId` 하나당 최신 상태 하나만 들고, **256 개 빌드**를 넘으면
가장 오래된 것부터 버린다(`LinkedHashMap` 의 `removeEldestEntry`, `synchronizedMap` 으로 감싼다).
256 은 설정값이 아니라 상수다 — 한 서버가 동시에 그만큼의 빌드를 스캔 중인 상황은 이 기능의
모양을 이미 벗어났고, 아무도 운영 중에 다시 고를 값이 아니다.

```
REQUESTED  ─ ACTION_RESULT success:true  → 적재 → SUCCEEDED (ingestedDocuments=n)
           │                                    └ 적재 실패 → FAILED + 문서에 durable 기록
           └ ACTION_RESULT success:false        → FAILED (SDK 가 준 사유)
```

조회 API 가 이것을 `lastScan` 으로 실어 낸다. **덧붙이는 칸이라 ARTEL-489(home) 가 이미 맞춘
응답 모양을 깨지 않는다.**

### 4. 빌드에서 붙어 있는 인스턴스로 가는 길

WebSocket 세션은 `gameInstanceId` 로 묶이고 근거 문서는 `gameBuildId` 로 묶인다. FK 경로가 없다.
`game_instance.last_game_build_id` 가 바로 이것 때문에 있는 칸이다(등록이 남긴다).

```sql
SELECT gi.* FROM game_instance gi
JOIN project p ON p.id = gi.project_id
JOIN project_member m ON m.project_id = gi.project_id
WHERE gi.last_game_build_id = :gameBuildId AND m.app_user_id = :userId
  AND gi.deleted_at IS NULL AND p.deleted_at IS NULL
ORDER BY gi.last_connected_at DESC NULLS LAST, gi.id DESC
```

그 중 `SessionManager.hasSession(id)` 인 첫 번째를 고른다. **여러 개면 가장 최근에 붙은 것**이다 —
같은 빌드를 두 대에서 돌리는 것은 개발 중 흔하고, 그때 사람이 지금 보고 있는 것은 방금 띄운 쪽이다.

- 하나도 없으면 **409** — `QaTryService` 가 이미 그렇게 한다
- 빌드가 없거나 경로의 `projectId` 가 그 빌드의 것과 다르면 **404**

409 와 404 를 가르는 것이 요점이다. 404 로 뭉개면 화면이 "빌드가 없다"와 "게임이 안 켜져 있다"를
구분하지 못해 버튼을 비활성으로 두고 이유를 말할 수 없다.

### 5. 같은 버튼을 두 번 눌러도 서버는 막지 않는다

겹쳐 도는 것을 막는 것은 SDK 의 몫이다(ARTEL-491 AC). 서버가 `REQUESTED` 상태로 막으면, 결과가
영영 안 오는 경우(게임이 그 사이 죽었다) 그 빌드의 스캔이 **영구히 잠긴다**. 잠금을 풀 시한을
정하는 것은 곧 타임아웃 기계장치이고, (B)를 고른 이유가 그것을 피하는 데 있었다.

## Approach (Checklist)

- [x] **Step 0: Recon**
  - 베이스가 `d873818` 인지, V48 과 엔티티 칸이 있는지 확인 — 확인함
  - `QaActionDispatchService` · `SessionManager` · `ActionResultMessageHandler` 읽음
  - 닫힌 PR #152(`bea8e23`) 의 실패 기록 조각을 **모양만** 참고 (cherry-pick 하지 않는다 —
    브라우저 업로드 컨트롤러와 묶여 있어 충돌한다)

- [x] **Step 1: 적재 쪽 레일**
  - `ContentMapDocumentRepository.findPendingByGameBuild(gameBuildId, limit)` — `content_map` 조인.
    정렬은 `ORDER BY d.ingest_failed_at ASC NULLS FIRST, d.received_at ASC` — **한 번도 시도 안 한
    문서를 먼저** 본다. 받은 순서로만 고르면 깨진 문서가 큐 머리를 잡아 새 문서에 차례가 안 온다
  - `ContentMapDocumentRepository.recordIngestFailure(id, failedAt, error)` — `@Modifying` 으로 두 칸만.
    `save(copy(...))` 는 `received_at` 처럼 엔티티에 없던 값을 null 로 덮어쓴다
  - `ContentMapIngestService.ingestBuild(gameBuildId, limit)` — **`@Transactional` 이 아니다.**
    문서마다 `ingest(document)`(그 하나가 트랜잭션)를 부르고, 깨지면 그 트랜잭션이 되돌아간 **뒤에**
    `recordIngestFailure` 를 새 트랜잭션으로 쓴다. 안에서 쓰면 롤백에 함께 쓸려 나가 문서가
    "아무 일도 없었던 것"과 똑같은 모양이 된다

    ```kotlin
    suspend fun ingestBuild(gameBuildId: Long, limit: Int = DEFAULT_BATCH): List<IngestOutcome> =
        documents.findPendingByGameBuild(gameBuildId, limit).toList().map { document ->
            runCatching { ingest(document) }.fold(
                onSuccess = { IngestOutcome.Ingested(it) },
                onFailure = { failure ->
                    if (failure is CancellationException) throw failure   // 취소는 오류가 아니다
                    logger.error("근거 문서 적재 실패 (documentId={})", document.id, failure)  // 원문은 여기만
                    val shown = clientMessageOf(failure).take(INGEST_ERROR_WIDTH)
                    // 기록이 또 깨져도 배치를 멈추지 않는다 — 사람은 사유를 못 보지만 데이터는 안 틀어진다
                    runCatching { documents.recordIngestFailure(document.id!!, Instant.now(clock), shown) }
                        .onFailure { logger.error("적재 실패 기록 실패 (documentId={})", document.id, it) }
                    IngestOutcome.Failed(document.id!!, shown)
                },
            )
        }
    ```

    **문서 하나가 깨져도 나머지를 계속한다.** 한 게임의 문서가 깨졌다고 다른 문서의 적재가
    멈추면, 고치는 사람이 깨진 문서를 찾기 전에 큐가 밀린 것부터 보게 된다.
  - `clientMessageOf(failure)` — 4xx `ApiException` 이면 그 문구, 아니면 우리가 정한 일반 문구
    ("근거 문서를 씬 명세로 앉히지 못했습니다.").

    **`ingest_error` 에 들어가는 것은 raw message 가 아니라 이 문구다.** 이 칸은 조회 API 가
    `pendingDocuments[].ingestError` 로 **그대로 브라우저에 내보내는** 값이고,
    `ContentMapViewDtos.kt` 가 이미 그렇게 못 박아 뒀다 — *"사람에게 보여 줄 사유 한 줄.
    내부 예외 원문은 로그에만 남는다"*. 컬럼 폭에 걸린 실패는 R2DBC 예외가 SQL 문과 테이블·
    컬럼 타입을 들고 오므로, 원문을 이 칸에 넣으면 그것이 곧장 화면까지 간다.
    원문은 로그에만 남기고, 칸에는 512자로 자른 우리 문구만 넣는다
  - `INGEST_ERROR_WIDTH = 512` — `ingest_error VARCHAR(512)` 와 같은 값. 넘치면 자른다

- [x] **Step 2: 스캔을 시키는 자리**
  - `GameInstanceRepository.findByLastGameBuildIdForMember(gameBuildId, userId)` — 위 4번의 질의.
    **붙어 있는지는 이 질의가 보지 않는다** — 그것은 DB 가 아니라 `SessionManager` 가 아는 사실이라
    서비스가 걸러 낸다.
    `ORDER BY gi.last_connected_at DESC NULLS LAST, gi.id DESC` — 한 번도 안 붙어 본 인스턴스가
    최근에 붙은 것을 앞지르지 않게 한다
  - `contentmap/scan/ScanStatus.kt` — `ScanState { REQUESTED, SUCCEEDED, FAILED }` 와 상태 한 줄
  - `contentmap/scan/ScanStatusRegistry.kt` — 빌드별 최신 상태, 256 개 상한
  - `contentmap/scan/ContentMapScanService.kt`
    1. `gameBuilds.findAccessibleById(gameBuildId, projectId, userId)` → null 이면 컨트롤러가 404.
       **경로의 `projectId` 를 보는 질의다** — 안 보면 그 값이 장식이 된다
    2. 붙어 있는 인스턴스 고르기 → 없으면 `ConflictException`(409)
    3. `sessionManager.sendAction(instanceId, ActionResponseDto(id = requestId,
       actions = listOf(ActionItemDto(id = requestId, method = "scan_evidence", params = emptyList()))))`
    4. `REQUESTED` 를 registry 에 남기고 응답을 돌려준다
  - `ProjectContentMapController` 에 `@PostMapping("/scan")` + `@ResponseStatus(HttpStatus.ACCEPTED)`
  - 응답 DTO — **202 가 "무엇을 기다리면 되는지"를 말하는 자리다**

    ```kotlin
    data class StartContentMapScanResponse(
        /** 어느 인스턴스가 받았나. 여러 대가 같은 빌드를 물고 있을 때 화면이 이것을 보여 준다 */
        val gameInstanceId: Long,
        val gameInstanceName: String,
        /** 늘 REQUESTED 다. 화면은 조회 API 의 `lastScan.state` 가 이 값에서 움직이기를 기다린다 */
        val state: ScanState,
        val requestedAt: Instant,
    )
    ```
  - `requestId` 는 프로세스 안의 `AtomicLong` 이다. **우리는 이 값으로 짝을 맞추지 않으므로**
    (라우팅은 액션 이름으로 한다) `qa_log` id 와 겹쳐도 무해하다. SDK 가 echo 하든 안 하든
    동작이 같다 — 로그에서 한 번의 누름을 따라가는 용도로만 쓴다

- [x] **Step 3: 결과를 받는 자리**
  - `contentmap/scan/ScanResultRouter.kt`

    ```kotlin
    /** 스캔 결과였으면 true. **그 밖의 모든 프레임에 대해 false 이고, 아무것도 하지 않는다.** */
    suspend fun handle(gameInstanceId: Long, payloadText: String): Boolean {
        // 파싱 실패도 false 다 — 스캔이 아닌 프레임의 처리를 이 분기가 바꾸지 않게 한다.
        val payload = runCatching { objectMapper.readTree(payloadText) }.getOrNull() ?: return false
        if (payload.path("action").asText(null) != SCAN_EVIDENCE) return false
        ...
        return true
    }
    ```

    - `success:true` → 인스턴스의 `last_game_build_id` 로 빌드를 찾아 `ingestBuild` →
      `SUCCEEDED(ingestedDocuments = n)`. 적재가 하나라도 깨졌으면 `FAILED` 로 두고 사유를 싣는다
      (durable 한 기록은 이미 문서 행에 남았다)
    - `success:false` → `FAILED` + SDK 가 준 `error`(512자로 자른다). 이것은 **게임 클라이언트가
      쓴 문장이지 서버 예외 원문이 아니다** — 그래서 그대로 화면에 낸다
    - `last_game_build_id` 가 비어 있으면(등록 전) 적재할 곳이 없다 → `FAILED` 와 그 사유
  - **적재는 이 프레임을 처리하는 코루틴 안에서 그대로 돈다.** `SdkWebSocketHandler` 가
    `concatMap` 이라 그동안 **그 세션의 다음 프레임이 기다린다**. 1.4MB 파싱만큼 늦어진다는 뜻이다.
    사람이 눌러야 생기는 드문 일이고, 떼어내면 순서와 오류 귀속이 흐려진다 — 문제가 되면 답은
    큐이지 이 diff 가 아니다
  - `ActionResultMessageHandler` 에 한 줄 분기. **QA 경로는 그대로 뒤에 남는다**

    ```kotlin
    if (scanResults.handle(instanceId.toLong(), payloadText)) return
    qaBridge.routeActionResult(instanceId.toLong(), payloadText)
    ```
  - `ContentMapResponse` 에 `lastScan: LastScanResponse?` 를 더하고 `ContentMapViewService` 가 채운다

    ```kotlin
    data class LastScanResponse(
        val state: ScanState,
        val gameInstanceId: Long,
        val requestedAt: Instant,
        val finishedAt: Instant?,
        /** SUCCEEDED 일 때 이번 스캔이 앉힌 문서 수. 0 이면 올라온 것이 없다는 뜻이다 */
        val ingestedDocuments: Int?,
        val error: String?,
    )
    ```

    **덧붙이는 nullable 칸이라 ARTEL-489(home) 가 이미 맞춘 응답 모양을 깨지 않는다.**
  - `ingestPending`(전역 배치)은 이 이슈에서 손대지 않는다. 그쪽 실패는 지금처럼 로그만 남는다 —
    프로덕션 호출자가 없어서다. 스케줄러를 만드는 이슈가 같은 `ingestBuild` 모양으로 옮겨 간다

- [x] **Step 4: Tests** (아래 Validation)

- [x] **Step 5: `insomnia-sync`** 로 새 엔드포인트를 컬렉션에 반영

## Validation

- **기준선 (변경 전, 이미 잼):**
  `./mvnw -o test -Dtest='kr.artel.orchestration.contentmap.**,kr.artel.orchestration.sdk.**,kr.artel.orchestration.qa.**'`
  → **Tests run: 319, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS**
- **변경 후:** 같은 명령. 319 개가 그대로 통과하고 아래 새 테스트가 붙는다. **전후 수를 보고한다**

새로 짚는 것:

| # | 무엇을 | 왜 |
|---|---|---|
| 1 | 붙어 있는 인스턴스에 `scan_evidence` 가 가고, 응답이 어느 인스턴스인지 말한다 | AC 1·4 |
| 2 | 안 붙어 있으면 409 (빌드는 있다) | AC 2 — 404 와 구분되어야 화면이 버튼을 비활성으로 두고 이유를 말할 수 있다 |
| 3 | 경로의 `projectId` 가 빌드의 것과 다르면 404. **사용자는 두 프로젝트 모두의 멤버다** | AC 3 — 권한이 아니라 경로가 어긋난 것을 잡는지 본다. 권한으로만 막으면 한 사람이 여러 프로젝트에 속한 흔한 경우에 검사가 무력해진다 |
| 4 | `success:true` 인 `ACTION_RESULT` 가 그 빌드의 대기 문서를 적재한다 | 폴링이 빈손으로 끝나지 않게 하는 고리 |
| 5 | 적재가 깨지면 `ingest_failed_at` · `ingest_error` 가 남고 조회 API 가 그것을 실어 낸다 | V48 칸에 적는 코드가 이 이슈의 몫 |
| 6 | `ingest_error` 에 **내부 예외 원문이 안 들어간다** | 이 칸은 브라우저로 그대로 나간다 |
| 7 | `success:false` 면 `lastScan.state == FAILED` 이고 사유가 실린다 | 스캔 자체의 실패도 화면이 말할 수 있어야 한다 |
| 8 | **`action` 칸이 없는 `ACTION_RESULT`(QA 가 지금 쓰는 모양)는 그대로 QA 브리지로 간다** | 라우팅을 갈랐어도 QA 가 안 깨진다는 증거 |

## Risks & Rollback

- **`ACTION_RESULT` 가 등록보다 먼저 도착하면** 적재할 문서가 없어 `SUCCEEDED(ingestedDocuments=0)`
  이 된다. SDK 는 올린 뒤에 답하도록 돼 있으므로(ARTEL-491) 정상 경로에서는 안 생긴다. 생기면
  화면이 `ingestedDocuments == 0` 을 보고 다시 누를 수 있다. 재시도 기계장치는 안 만든다
- **`ScanStatusRegistry` 는 재시작하면 비어 있다.** 위 3번에서 고른 값이다. 내구성이 필요한 것
  (어떤 문서가 왜 못 앉았나)은 `ingest_failed_at` · `ingest_error` 에 그대로 남는다
- **적재가 그 세션의 다음 프레임을 기다리게 한다**(`concatMap`). 위 Step 3 에서 고른 값이다
- **`last_game_build_id` 가 스캔 도중 바뀌면** 결과가 다른 빌드로 적재될 수 있다. SDK 가 그 사이
  다른 빌드로 재등록해야 생기는 일이다. 상태를 DB 에 두는 것은 (A) 를 고르지 않은 이유와 같은 크기다
- **라우팅 분기가 QA 를 삼킬 위험**은 `action` 칸이 없는 프레임을 건드리지 않는 것으로 막고,
  테스트 8 이 그것을 짚는다. 파싱 실패조차 `false` 라 QA 쪽 동작이 한 글자도 안 바뀐다
- **Rollback:** 새 파일이 대부분이고 기존 파일 변경은 `ActionResultMessageHandler` 한 줄 분기,
  `ProjectContentMapController` 의 `@PostMapping`, `ContentMapResponse` 의 덧붙인 칸,
  `ContentMapIngestService` 의 `ingestBuild` 추가뿐이다. `git revert` 로 되돌아간다.
  **마이그레이션이 없어 스키마 롤백이 필요 없다**

## Review 에서 받은 것과 물리친 것

첫 통과: fast = NONPASS, medium = PASS.

받은 것:

- **응답 DTO 를 실제로 정의하라**(fast must-fix) — `StartContentMapScanResponse` ·
  `LastScanResponse` 를 Step 2·3 에 적었다
- **잘라내는 길이를 정하라**(fast must-fix) — `INGEST_ERROR_WIDTH = 512`, 컬럼 폭과 같은 값
- **적재 실패 흐름을 적어라**(fast must-fix) — `ingestBuild` 의 코드를 그대로 넣었다.
  문서 하나가 깨져도 계속하고, 기록은 트랜잭션 밖이며, `CancellationException` 을 먼저 rethrow 한다
- **registry 상한을 정하라**(fast must-fix) — 256, `removeEldestEntry`
- **`ScanResultRouter` 의 계약**(fast should-fix) — 파싱 실패도 `false`, 그래서 스캔이 아닌
  프레임의 처리가 한 글자도 안 바뀐다. 적재가 어느 코루틴에서 도는지도 적었다
- **`ingestedDocuments` 를 상태에 실어라**(fast question 7) — `LastScanResponse` 에 넣었다
- **`trackedBuilds` 를 설정이 아니라 상수로**(medium question) — 받아들여 `ContentMapScanProperties`
  를 **통째로 뺐다.** `batchSize` 도 같은 이유로 상수다
- **`ingestPending` 과의 갈림을 한 줄 적어라**(medium question) — Step 3 끝에 적었다

물리친 것:

- **fast 6 "`NULLS LAST` 를 명시하라"** — 이미 명시돼 있었다(4번의 질의). 그대로 둔다
- **fast 8 "`requestId` echo 를 계약에 넣어라"** — 넣지 않는다. 라우팅을 액션 이름으로 하기로 한
  이상 echo 여부가 동작을 바꾸지 않는다. 계약에 넣으면 SDK 가 지켜야 할 것이 하나 늘고, 그것이
  아무것도 보장하지 않는다. 로그 추적용으로만 보낸다는 것을 Step 2 에 적어 뒀다
- **fast 7 "`ingestedDocuments == 0` 이면 재시도하게 하라"** — 서버가 정할 일이 아니다. 수를
  실어 주는 데까지가 서버의 몫이고, 다시 누를지는 화면이 정한다. 자동 재시도는 (A) 를 고르지
  않은 이유와 같은 크기의 기계장치다

## Open Questions

- `lastScan` 을 ARTEL-489(home) 가 쓸지 — 덧붙이는 nullable 칸이라 안 쓰면 그만이지만, 쓰면
  "눌렀는데 안 됐다"를 화면에 띄울 수 있다. 붙이고 나서 home 쪽에 알린다
