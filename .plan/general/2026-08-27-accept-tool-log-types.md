# 2026-08-27 — TOOL 과 TOOL_RESULT 로그 타입을 받는다

- Date: 2026-08-27
- GitHub Issue: None (Jira: ARTEL-608, 우산 ARTEL-607)
- Status: Draft

## Goal

Agent 가 보내는 `TOOL` / `TOOL_RESULT` 프레임을 거절 없이 `qa_log` 에 적재하고 SSE 로
발행한다. 우산 작업의 1단계이며, 계약을 정의하므로 셋 중 먼저 머지한다.

## Non-goals

- 새 라우팅 분기. 두 타입 모두 기존 `else` 분기로 적재만 한다.
- payload 스키마 검증. `LOG` 와 같은 통짜 통과다.
- 기존 타입의 동작 변경.

## Context / Constraints

`qa_log.type` 을 지키는 게이트가 셋이다.

1. `QaAgentInboundRouter.SUPPORTED_TYPES` — 모르는 타입은 프레임을 거절하고 ERROR 를 남긴다.
2. `QaLogService.TYPES` — `require` 로 적재를 막는다.
3. `qa_log_type_check` — INSERT 에서 막는다.

2와 3이 어긋나면 `QaLogTypeGateParityTest` 가 실패한다. ARTEL-414 가 그 실패를 실제로
겪어서 생긴 테스트다.

라우터의 `payload.message` non-blank 가드는 `ISSUE` 와 knowledge 계열만 비껴간다. 그래서
tool 프레임도 `message` 를 실어야 하고, Agent 가 거기에 tool 이름을 싣는 것이 계약이다.

프레임 계약

```
TOOL         payload: message, tool, tool_call_id, args, step
TOOL_RESULT  payload: message, tool, tool_call_id, content, step
             correlationId: 짝이 되는 TOOL 프레임의 messageId
```

## Approach (Checklist)

- [x] **Step 0: Recon** — 세 게이트와 `QaLogTypeGateParityTest`, 라우터의 `else` 분기 확인.
- [x] **Step 1: 마이그레이션** — `V57__add_qa_log_tool_types.sql`. develop 의 직전 번호는
      V54 지만, V55 와 V56 은 아직 병합되지 않은 다른 브랜치가 이미 선점했다.
- [x] **Step 2: 코틀린 게이트** — `QaLogService.TYPES` 와 `SUPPORTED_TYPES` 에 두 타입 추가.
- [x] **Step 3: 테스트** — 라우터가 두 타입을 적재하고 payload 를 그대로 통과시키는지,
      `TOOL_RESULT` 가 correlation 을 들고 오는지.

## Validation

- **Commands to run:** `./mvnw -o test -Dtest='QaLogTypeGateParityTest,QaRunInboundActivationIntegrationTest'`
- **Expected output:** 8 tests, 0 failures. 파리티 테스트가 새 목록으로 통과한다.

## Risks & Rollback

- **Risks:** 게이트를 하나만 열면 통과한 프레임이 INSERT 에서 죽는다. 파리티 테스트가 그것을
  막는다. 라우터 집합은 그 테스트 밖이라, 새 라우팅 테스트가 그 자리를 맡는다.
- **Rollback steps:** `git revert`. 마이그레이션은 되돌리지 않아도 무해하다 — 제약이
  넓어질 뿐이고, 넓은 제약에 어긋나는 기존 행은 없다.

## Open Questions

- 없음.
