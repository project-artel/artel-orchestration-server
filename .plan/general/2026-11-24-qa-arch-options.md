# 2026-11-24 — QA 고급 설정 목록 조회 API 추가

- Date: 2026-11-24
- Jira: ARTEL-1473

## Goal

Home이 현재 Agent 서버에서 공개한 QA 실행 설정 목록을 조회한다.

## Non-goals

메타데이터의 작성과 화면 표시는 각각 Agent 서버와 Home에서 맡는다.

## Context / Constraints

Orchestration은 Home 세션 인증을 적용한다. 공개할 필드와 선택값은 Agent가 정한다.

## Approach (Checklist)

- `GET /api/qa-arch-options`에서 설정된 Agent의 `GET /internal/qa-arch-options`를 호출한다.
- 응답의 `options` 배열을 확인하고 각 필드는 그대로 전달한다.
- Agent 응답 실패, 시간 초과, 계약 오류는 503으로 처리한다.

## Validation

테스트용 Agent와 실제 HTTP 경로에서 세션 인증, 미등록 필드 전달, upstream 오류를 확인한다. OpenAPI 스냅샷을 갱신한다.

## Risks & Rollback

Agent, Orchestration, Home 순서로 배포한다. Agent가 경로를 제공하기 전에는 503이 반환된다. 기존 QA 실행 API는 독립적으로 동작한다.

## Open Questions

없음.
