# 2026-08-24 — QA 런이 시작할 때 판독을 켜고 끝날 때 끈다

- Date: 2026-08-24
- Jira: ARTEL-507
- Status: Reviewed (fast · medium · heavy 자체 리뷰 반영)

## Goal

QA 런이 시작하면 그 게임 인스턴스에 `start_readings` 를 보내고, 런이 끝나면 `stop_readings` 를
보낸다. 이것 하나로 판독 사슬 전체가 처음으로 실제로 흐른다.

## Non-goals

- `GAME_STATE` 의 `states` 제거 — ARTEL-400
- 판독을 DB 에 저장하기 — ARTEL-449
- 런 설정으로 판독을 켤지 고르게 하기. 지금은 언제나 켠다
- SDK · agent 변경. 양쪽 다 이미 끝나 있다

## Context / Constraints

### 지금 상태

세 레포에 판독이 다 들어왔다 (ARTEL-399 · 414 · 401, 전부 머지). 그런데 `start_readings` 를
보내는 쪽이 orchestration · agent · home 어디에도 없어 한 줄도 흐르지 않는다. 보내는 것은 SDK
자체 테스트 페이지(`ArtelTestPage.cs:129`)와 개발용 뷰어(`tools/watch-readings.py:481`)뿐이다.

QA 에이전트 도구는 29개인데 판독을 켜는 것이 없다. 에이전트는 켤 방법 자체가 없다.

### 왜 런 시작인가

ARTEL-417 이 정했고 실측이 근거다.

```
순회 중 시작   8초에 125,548 바이트, 가 본 적 없는 세 화면
순회 뒤 시작   전량 한 줄 4,369 바이트, 그 뒤 14초 침묵
```

연결 시점에 켜면 전체 씬 순회와 겹쳐, 아무도 걸어간 적 없는 화면의 값 변화가 채널을 덮는다.

### 붙일 자리 (코드로 확인)

켜는 곳 — 둘 다 이미 `sessionManager.hasSession(gameInstanceId)` 로 막고 있다:

- `QaTryService.create()` — 시나리오 하나
- `QaTryService.createRun()` — 시나리오 N개

끄는 곳 — 시도가 종단되는 자리가 넷이다:

- `QaAgentInboundRouter:389` — 정상 종단 뒤 rollup
- `QaExecutionFailureService:32` · `:60` — 실패 두 경로
- `QaTryService.cancel()` · `cancelRun()`

### 제약

- SDK · agent 를 건드리지 않는다
- 오류는 `common/error` 의 타입 예외로 던진다 (신규 `ResponseStatusException` 금지)
- 판독 전송 실패를 런 실패로 승격하지 않는다. 판독은 관측 채널이지 런의 전제가 아니다
- 마이그레이션 없음. 스키마를 건드리지 않는다

## Approach (Checklist)

- [ ] **Step 0: Recon** — 완료. 위 Context 가 결과다

- [ ] **Step 1: `QaReadingsService` (신규)** — `qa/service/`
  - `start(gameInstanceId)` — `SessionManager.sendAction` 으로 `start_readings` 를 보낸다.
    `ContentMapScanService.startScan` 이 `scan_evidence` 를 보내는 것과 같은 모양
  - `stopIfIdle(gameInstanceId)` — 그 인스턴스에 살아 있는 시도·런이 없을 때만 `stop_readings`
    를 보낸다
  - **둘 다 실패를 삼킨다.** 판독을 못 켠 것이 런을 되돌릴 이유는 아니다. `CancellationException`
    만 되던지고 나머지는 로그로 남긴다
  - 액션 id 는 `ContentMapScanService` 처럼 프로세스 안의 `AtomicLong`. 짝을 맞추는 데 쓰지
    않으므로 `qa_log` 의 id 와 겹쳐도 무해하다

- [ ] **Step 2: 왜 `stopIfIdle` 인가**
  - 종단 지점이 넷인데 각자 "이제 꺼도 되나"를 따로 판단하면 넷이 어긋난다. 특히 시나리오 N개
    짜리 런은 시도 하나가 끝나도 아직 끄면 안 된다
  - 판단을 한 곳에 둔다: `tryRepository.findActiveByGameInstanceId` 와
    `runRepository.findActiveByGameInstanceId` 가 둘 다 비면 끈다. 두 메서드 다 이미 있다
  - 그래서 호출부는 넷 다 같은 한 줄이 된다

- [ ] **Step 3: 켜는 자리 배선** — `QaTryService`
  - `create()` · `createRun()` 에서 런 행을 만든 뒤에 `readings.start(gameInstanceId)`
  - 시나리오 N개짜리도 한 번만 부른다 (`createRun` 이 한 번 도므로 자연히 그렇다)

- [ ] **Step 4: 끄는 자리 배선**
  - `QaAgentInboundRouter` · `QaExecutionFailureService` · `QaTryService.cancel` · `cancelRun`
    에서 `readings.stopIfIdle(gameInstanceId)`
  - **트랜잭션 밖에서 부른다.** `QaExecutionFailureService` 의 rollup 은
    `transactionalOperator.executeAndAwait` 안이다. 소켓 전송을 그 안에 넣으면 네트워크 I/O 동안
    DB 커넥션을 쥐고, 게다가 커밋 전이라 `findActive…` 가 방금 끝낸 시도를 아직 살아 있다고
    읽어 영영 끄지 못한다

- [ ] **Step 5: 테스트**
  - 런 시작이 `start_readings` 를 한 번 보낸다
  - 시나리오 N개짜리 런도 한 번만 보낸다
  - 시도 하나가 끝나도 같은 런에 남은 시도가 있으면 끄지 않는다
  - 마지막 시도가 끝나면 `stop_readings` 가 간다
  - 취소도 끈다
  - `sendAction` 이 던져도 런 생성이 성공한다

- [ ] **Step 6: 실측**
  - 로컬 스택에 게임을 붙이고 런을 돌려 `qa_log` 의 `PULSE` 행을 센다
  - ARTEL-400 이 쓸 숫자를 함께 남긴다 — `[ArtelState]` 값 개수 대 pulse 값 개수

## Validation

- **Commands to run:**
  ```bash
  /opt/homebrew/bin/bash ./scripts/check-flyway-migrations.sh
  ./mvnw test -Dtest='Qa*'
  ./mvnw clean test
  ```

- **Expected output:**
  - flyway — 이 브랜치는 마이그레이션을 더하지 않으므로 무변화
  - 전체 스위트: develop 베이스라인과 같은 실패만. 직전 실측 베이스라인은
    `794 tests / 5 failures` (`TestScenarioPipelineIntegrationTest:223`,
    `ContentMapIngestTransactionTest:99`, `SdkPerformanceIntegrationTest` × 3).
    **`ContentMapIngestTransactionTest:99` 는 간섭성 flaky 라 실행마다 들락거린다** — 새 실패로
    오인하지 않도록 단독 실행과 대조한다
  - 손수 확인: 런 중 `qa_log` 에 `PULSE` 가 두 방향으로 쌓이고, 런 종료 뒤 멈춘다

## Risks & Rollback

- **Risks:**
  - **끄지 못하고 새는 경우.** 종단 경로를 하나라도 빠뜨리면 판독이 런 뒤에도 돈다.
    `stopIfIdle` 이 멱등이라 여러 번 불러도 무해하므로, 의심스러운 자리에는 넣는 쪽으로 판단한다.
    최후 방어선은 ARTEL-417 이 적어 둔 것 — 연결이 끊기면 `EndDiscovery` 가 멈춘다
  - **런 사이 경쟁.** 런 A 가 끝나는 순간 런 B 가 시작하면, `stopIfIdle` 이 B 의 시작을 못 보고
    끌 수 있다. `findActive…` 를 stop 직전에 읽으므로 창이 좁고, B 의 `start` 가 뒤이어
    다시 켠다. 다만 순서가 뒤집히면 B 가 판독 없이 돈다 — 실측에서 확인할 자리로 남긴다
  - **판독 트래픽.** 이제 모든 런이 판독을 흘린다. 실측 전량 한 줄이 4,369 바이트이고 1초
    배치다. 문제가 되면 런 설정으로 끄는 스위치를 다는 것이 답이고 그것은 별건이다

- **Rollback steps:**
  - `git revert`. 스키마도 설정도 건드리지 않으므로 코드만 되돌리면 끝이다

## Rejected feedback

- **종단 지점마다 직접 `stop_readings` 를 보내자** — 넷이 각자 "꺼도 되나"를 판단하게 되고,
  시나리오 N개짜리 런에서 첫 시도가 끝나자마자 꺼진다. 판단은 한 곳이어야 한다
- **런 설정에 판독 on/off 를 지금 넣자** — 켜는 쪽이 아예 없는 상태에서 스위치부터 만드는 것은
  순서가 뒤집힌 것이다. 트래픽이 실제로 문제가 된 뒤에 단다
- **`stop_readings` 를 생략하고 연결 종료에 맡기자** — 인스턴스는 런 사이에도 붙어 있다.
  아무도 읽지 않는 판독이 계속 흐른다

## Open Questions

- 런 사이 경쟁(위 Risks)이 실제로 관측되는지. 실측에서 런 두 개를 연달아 돌려 본다
