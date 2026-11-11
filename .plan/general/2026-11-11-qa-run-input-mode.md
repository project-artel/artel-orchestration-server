# 2026-11-11 — QA run 입력 모드 저장과 SDK 설정 전달 추가

- Date: 2026-11-11
- Jira: ARTEL-1422
- Status: Implemented

## Goal

`inputMode: virtual | os`를 run 시작 API에서 받고 backend 소유 설정으로 고정한다. `os`는 SDK 확인 응답 뒤에만 Agent를 시작한다.

## Non-goals

SDK 네이티브 입력 구현, CLI·MCP 옵션, 빌드 설정, QA Agent 도구 변경은 이 저장소 범위가 아니다.

## Context / Constraints

기본값은 `virtual`이다. 구 SDK의 virtual 실행을 유지한다. `input_mode`는 Agent 스냅샷에 덮어써지지 않으며 모든 QA ACTION에 authoritative `inputMode`를 싣는다. preflight 결과는 instance, requestId, 현재 WebSocket session을 함께 확인한다.

## Approach (Checklist)

- [x] **Step 0: Recon** — 시작 API, run_config 병합, ACTION delivery 및 결과 router 확인.
- [x] **Step 1: Implementation** — typed input mode, 요청 파싱·저장·carry-over, SDK preflight 및 envelope 전달 추가. 시작마다 preflight를 하고, OS ACTION과 재전송은 확인된 session에 고정한다.
- [x] **Step 2: Tests** — 기본값, 잘못된 값, Agent 덮어쓰기, SDK 거절·timeout·취소·session 교체 및 JSON 전달 검증.
- [ ] **Step 3: Rollout / Rollback** — SDK ARTEL-1421 지원 후 사용. 문제가 있으면 virtual로 실행하고 이 PR을 revert한다.

## Validation

- **Commands to run:** `./mvnw -Dtest=QaInputModeTest,QaInputPreflightTest,ActionResultDeliveryRoutingTest,SdkActionDeliveryTest,SdkActionReconnectTest,QaRunConfigPersistenceIntegrationTest,GameFunctionsIntegrationTest test`.
- **Expected output:** focused tests 통과. WSL Java 21에서 89건 통과, 실패·건너뜀 0건. 실제 네이티브 입력은 SDK 변경에서 별도 검증한다.

## Risks & Rollback

- **Risks:** 구 SDK의 미지원·권한 오류는 os run 시작 실패로 반환한다. 시작 요청 취소와 timeout에서도 STARTING 예약 및 pending 응답을 정리해야 한다. 재연결 액션에도 mode를 계속 싣는다.
- **Rollback steps:** 기본 virtual 사용 또는 commit revert. DB migration은 없다.

## Open Questions

없음. `configure_input`은 `[{"mode":"os"}]`를 받고 일반 ACTION_RESULT로 결과를 반환한다.

## Plan Review

FAST REVIEW: PASS — 부모 검토의 session 매칭, 구 SDK 실패, 재연결, carried config 지적을 반영했다.

MEDIUM REVIEW: PASS — 기존 ACTION과 run_config를 사용하고 별도 DB 컬럼·Agent 도구를 추가하지 않는다.

HEAVY REVIEW: PASS — 시작 예약 후 SDK 결과를 기다리고 실패·취소에서 예약을 끝낸다. SDK capability를 추정하거나 가상 입력으로 fallback하지 않는다.
