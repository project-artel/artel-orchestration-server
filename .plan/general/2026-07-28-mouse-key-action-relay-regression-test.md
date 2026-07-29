# 2026-07-28 — 신규 마우스/키 ACTION 릴레이 회귀 테스트 추가

- Date: 2026-07-28
- Jira: ARTEL-169
- Status: Implemented

## Goal

Unity SDK가 ARTEL-154에서 추가하는 5개 ACTION 메서드(`move_mouse`, `mouse_down`,
`mouse_up`, `key_down`, `key_up`)가 오케스트레이션을 그대로 통과한다는 사실을
`ArtelWebSocketIntegrationTest`의 회귀 케이스로 못 박는다. 지금은 "메서드를
해석하지 않는다"는 성질이 코드에 암묵적으로만 존재해서, 누군가 메서드 화이트리스트나
enum을 넣어도 아무 테스트도 깨지지 않는다.

## Non-goals

- 프로덕션 코드 변경. 릴레이는 이미 메서드에 무관하게 동작한다.
- 각 메서드의 의미 검증(좌표계, 버튼 인덱스, KeyCode 해석). 그건 SDK 책임이다.
- Agent -> Orchestrator 인입 경로(`QaAgentInboundRouter`/`QaActionDispatchService`)의
  별도 테스트 추가. 기존 릴레이 테스트와 같은 HTTP 진입점을 쓴다.

## Context / Constraints

- `ActionItemDto.method`는 그냥 `String`이다(`sdk/dto/ActionDto.kt:18`). enum도 sealed도 아니다.
- `src/main` 전체에서 `.method`를 읽는 곳은 `QaActionDispatchService.kt:41`의
  `require(item.method.isNotBlank())` 한 곳뿐이다. 메서드 이름으로 분기하는 코드는 없다.
- `SessionManager.send`는 DTO를 Jackson으로 직렬화해 소켓에 흘릴 뿐이라, 메서드 이름과
  `params`의 내용/순서를 건드리지 않는다.
- 따라서 회귀 테스트의 대상은 "전달 여부"가 아니라 **메서드명·params·배치 순서가
  무손실로 보존되는가**이다.

### 프로토콜 계약(SDK와 합의된 형태)

| method | params | 비고 |
|---|---|---|
| `move_mouse` | `[x, y]` | 스크린 픽셀, Unity 스크린 좌표계(원점 좌하단) |
| `mouse_down` | `[]` 또는 `[button]` | button은 int, 0=left/1=right/2=middle, 기본 0 |
| `mouse_up` | `[]` 또는 `[button]` | 동일 |
| `key_down` | `[keyCode]` | Unity KeyCode 이름(예: `"Space"`) 또는 숫자 값 |
| `key_up` | `[keyCode]` | 동일 |

## Approach (Checklist)

- [x] **Step 0: Recon** — `ActionDto.kt`, `QaActionDispatchService.kt`,
      `QaAgentInboundRouter.kt`, `SessionManager.kt`에 메서드 화이트리스트/enum/분기가
      없음을 확인했다. `src/main` 전체에서 `.method` 참조는 한 곳뿐이다.
- [x] **Step 1: Implementation** — 프로덕션 변경 없음.
- [x] **Step 2: Tests** — `ArtelWebSocketIntegrationTest`에 케이스 하나 추가.
  - 기존 `testWebSocketActionForwardingFlow`의 구조(웹소켓 목 클라이언트 연결 →
    `/api/orchestration/action/{instanceId}` POST → 수신 단언)를 그대로 따른다.
    테스트 구조를 재편하지 않는다.
  - 드래그 앤 드롭 순서로 배치를 만든다: `mouse_down` → `move_mouse` → `move_mouse` →
    `mouse_up`, 이어서 `key_down` → `key_up`.
  - `mouse_down`은 `[0]`(명시), `mouse_up`은 `[]`(생략, SDK 기본값 경로)로 두어 두 형태를
    모두 태운다. 빈 배열도 손실 없이 건너가는지가 계약의 일부다.
  - 단언은 (1) 6개 메서드명이 **순서 그대로**, (2) 각 항목의 `params` 값과 타입,
    (3) 배치 크기. 순서를 별도로 단언해야 배치 재정렬 회귀가 잡힌다.
  - 이 테스트가 왜 존재하는지(메서드 무관 릴레이를 고정한다)를 KDoc에 남긴다.
- [x] **Step 3: Rollout / Rollback** — 테스트 전용 변경. 플래그·마이그레이션 없음.

## Validation

- **Commands to run:** `./mvnw test -Dtest=ArtelWebSocketIntegrationTest`
- **Expected output:** 신규 케이스 통과, 기존 케이스 회귀 없음.
- **실행 결과:** `./mvnw test -Dtest=ArtelWebSocketIntegrationTest` → `Tests run: 7,
  Failures: 0, Errors: 0, Skipped: 0` (223.9s), `BUILD SUCCESS`. 신규 케이스 포함 7건
  전부 통과했고, 2026-07-26 플랜에서 보고된 간헐적 정리 단계 실패는 이번 실행에서 재현되지
  않았다. 테스트 프로파일은 로컬 PostgreSQL(5432)에 붙는다.

## Risks & Rollback

- **Risks:**
  - `ArtelWebSocketIntegrationTest`는 통합 테스트라 기존에도 정리 단계에서
    간헐적 실패가 보고된 적이 있다(2026-07-26 플랜 참조). 신규 케이스 실패와
    기존 실패를 구분해서 기록한다.
  - 소켓 연결 대기를 `Thread.sleep`에 의존하는 기존 방식을 그대로 쓰므로 느린 환경에서
    흔들릴 수 있다. 기존 케이스와 동일한 리스크이며 이번 범위에서 바꾸지 않는다.
- **Rollback steps:** `git revert`. 프로덕션 영향 없음.

## Open Questions

- 없음.
