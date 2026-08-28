# 2026-08-28 — 새 화면을 만든 자리에서 capture 를 찍어 화면에 묶는다

- Date: 2026-08-28
- Jira: ARTEL-456
- Status: Implemented

## Goal

`ScreenObservationService` 가 `screen` 행을 **새로 insert 한 그 순간에만** SDK 로
`capture_screen` action 을 보내고, 돌아온 이미지의 object key 와 시각을 그 행의
`image_object_key` · `image_captured_at` 에 적는다.

지금 실측 DB 의 `screen` 행은 전부 `image_object_key` 가 비어 있다. 씬은 ARTEL-575 로
대표 이미지를 갖는데 화면은 하나도 없어서, 인스펙터가 화면을 골라도 보여 줄 그림이 없다.

## Non-goals

- 화면 이름 짓기 (`screen.name`).
- capture 를 보고 무엇을 판정하는 것. 여기는 찍어서 묶는 것까지다.
- 조회 응답에 화면 이미지를 싣는 것 — ARTEL-596 이 `ContentMapViewService` 에서 가져간다.
  이 이슈는 그 칸을 채우기만 하면 된다.
- agent-server 경유. PR 135 (ARTEL-595) 가 그 경로를 만들었다가 닫혔다.
  `SCREEN_CREATED` / `SCREEN_CAPTURE` frame 쌍을 되살리지 않는다.

## Context / Constraints

### 왜 orchestration 이 직접 보내는가

SDK socket 을 orchestration 이 잡고 있고(`SdkWebSocketHandler`), action 을 내보내는 것도
(`SessionManager.sendAction`), capture 를 저장하는 것도(`QaCaptureService.presignUpload`)
orchestration 이다. 그 사이에 agent 의 판단이 하나도 없다 — "찍어라" 가 전부다.
agent 의 capture 예산은 agent 가 자기 도구 호출을 세는 것이고 orchestration 은 그 장부에 없다.

### 이미 있는 왕복

```
orchestration → capture_screen action → SDK
SDK → POST /api/sdk/qa-captures/tickets → QaCaptureService.issueTicket
        (objectKey 를 만들고 SCREENSHOT qa_log 행에 적어 둔다)
SDK → S3 PUT (presigned)
SDK → ACTION_RESULT { requestId, results:[{ action:"capture_screen",
        returnValue:{ captureId, url, mimeType } }] } → orchestration
```

`objectKey` 는 이미 `SCREENSHOT` 행의 payload 에 있다. 그래서 우리는 `captureId` 만 받으면
되고, key 를 다시 조립하지 않는다 — 조립하면 key 규칙이 두 곳이 된다.

### 결과 frame 을 어떻게 우리 것으로 가려내나

`ScanResultRouter` 는 action 이름(`scan_evidence`)으로 가른다. `capture_screen` 은 그 방법을
쓸 수 없다 — **agent 도 같은 이름의 action 을 보낸다.** 이름으로 가르면 agent 의 capture 를
가로채 agent 의 vision 이 멎는다.

그래서 우리가 보낸 outer action id 로 가른다. 그 id 가 `qa_log.id` 와 겹치면 agent 의
ACTION_RESULT 를 우리 것으로 오인하므로, id 를 **`qa_log` 의 시퀀스에서 뽑는다**
(`nextval(pg_get_serial_sequence('qa_log','id'))`). 행은 만들지 않는다. 같은 발급기에서 나온
값이라 어떤 `qa_log` 행과도 겹칠 수 없다.

### insert 와 update 를 가르는 법

`ScreenRepository.observe` 는 upsert 라 둘을 구분하지 못한다. `RETURNING id, (xmax = 0) AS
inserted` 로 가른다. 실제 Postgres 16 에서 확인했다 — 같은 문장을 반복해 돌리면 첫 번째만
`t`, 이후는 `f` 이고, 한 트랜잭션 안에서 두 번 돌려도 같다.

### pulse 를 막지 않는다

`SessionManager.send` 는 **보낸 것이 아니라 보낼 줄에 세운 것**이다(그 KDoc 그대로). 그래서
dispatch 자체는 왕복이 아니고 pulse 처리를 잡아 두지 않는다. 시간이 걸리는 쪽은 **결과**이고,
그것은 나중에 별도 `ACTION_RESULT` frame 으로 와서 `ActionResultMessageHandler` 가 처리한다.
따라서 background task 도 queue 도 필요 없다 — 요청과 결과가 이미 다른 frame 이다.

새 화면 하나당 늘어나는 동기 작업은 시퀀스 한 번과 활성 `qa_try` 조회 한 번이다. 새 화면은
런 하나에 수십 개 수준이라 pulse 마다 드는 비용이 아니다.

### 런이 끝나거나 socket 이 닫히면

대기표는 프로세스 메모리에만 있고 아무도 그것을 claim 하지 않은 채 만료된다. `screen` 행은
그대로 남고 그림만 없다 — **그림 없는 화면이 화면 없는 지도보다 낫다.** SDK 쪽에서도
런이 끝난 뒤의 ticket 요청은 `issueTicket` 이 409 로 막으므로 올라간 이미지가 애초에 없다.

## Approach (Checklist)

- [x] **Step 0: Recon** — `ScreenObservationService`, `ScreenRepositories.kt`,
      `QaCaptureService`, `QaSdkBridgeService.routeActionResult`, `ScanResultRouter`,
      `ContentMapScanService`, `SessionManager`, agent-server `app/agents/qa/tools.py`
      의 `capture_screen`.

- [x] **Step 1: insert 를 가른다**
      - `ScreenRepository.observe` 가 `ScreenObservationRow(id, inserted)` 를 돌려준다.
      - `ScreenObservationService.upsertScreen` 이 그 값을 트랜잭션 밖으로 들고 나온다.

- [x] **Step 2: capture 를 청구한다**
      - `contentmap/capture/ScreenCaptureService` — 활성 `qa_try` 를 확인하고 id 를 뽑아
        대기표를 남기고 `capture_screen` 을 보낸다. 실패는 전부 삼킨다.
      - `PendingScreenCaptureRegistry` — id → (screenId, qaTryId, gameInstanceId, 시각).
        상한과 TTL 을 둔다.

- [x] **Step 3: 결과를 화면에 묶는다**
      - `ScreenCaptureResultRouter.handle` — 대기표에 있는 id 의 frame 만 claim 한다.
      - `captureId` → `SCREENSHOT` qa_log 행 → `payload.objectKey`.
      - `ScreenRepository.attachImageIfAbsent` — `WHERE image_object_key IS NULL`.
        **처음 것만 남긴다를 SQL 이 강제한다.**
      - `ActionResultMessageHandler` 에 `scanResults` 다음, `qaBridge` 앞으로 끼운다.

- [x] **Step 4: 테스트**
      - 같은 화면을 여러 번 observe 해도 dispatch 가 **정확히 한 번**.
      - 결과가 `image_object_key` · `image_captured_at` 을 채운다.
      - 두 번째 결과가 기존 이미지를 덮지 않는다.
      - dispatch 가 실패해도(붙은 socket 이 없어도) `screen` 행이 남는다.
      - agent 가 보낸 `capture_screen` 결과를 가로채지 않는다.

- [x] **Step 5: 마이그레이션** — 없다. `image_object_key` · `image_captured_at` 은 V40 부터
      있고, 이 branch 의 최고 번호는 V60 이다.

## Validation

- **Commands to run:** `./mvnw test`
- **Expected output:** 새 테스트 전부 통과. 이 저장소의 실패 수는 실행 순서에 따라 달라진다
  (ARTEL-661) — `qa_run` 외래키에 걸린 전역 삭제만이 기존 실패의 모양이다.
- 실측: `SELECT count(*), count(image_object_key) FROM screen` 이 지금 `(3, 0)` 이다
  (V60 병합 전에는 30 행이었다). 살아 있는 스택에서 QA 런을 돌릴 수 있으면 두 번째 수가
  0 이 아니게 되는 것을 본다.

## Risks & Rollback

- **Risks:**
  - outer action id 를 `qa_log` 시퀀스에서 뽑는 것이 agent 의 ACTION_RESULT 를 가로채지
    않는 근거다. 이 규칙이 깨지면 agent 의 capture 가 조용히 멎는다 — 테스트로 고정한다.
  - SDK 가 `capture_screen` 을 지원하지 않는 빌드면 결과가 `success:false` 로 오고 화면에
    그림이 없다. 그것이 정상 동작이다.
- **Rollback steps:** `ActionResultMessageHandler` 의 한 줄과
  `ScreenObservationService` 의 청구 한 줄을 되돌리면 관측은 그대로 돌고 그림만 없어진다.

## Open Questions

- 살아 있는 스택에 게임이 붙어 있지 않으면 실제 QA 런으로 끝까지 확인할 수 없다.
  그 경우 PR 의 Validation 에 확인하지 못한 항목으로 남긴다.
