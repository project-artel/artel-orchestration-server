# 2026-07-30 — QA 모델 catalog 캐시와 런 설정 전달

- Date: 2026-07-30
- Jira: ARTEL-209
- Status: In Progress

## Goal

Agent Server 모델 catalog를 단일 원천으로 조회·캐시·노출하고 QA 런의 model/reasoning 선택값을 전달한다.

## Non-goals

- capability 하드코딩
- 자동 모델 라우팅
- 영구 저장

## Context / Constraints

- 기존 `WebSocketQaAgentAdapter` HTTP 경계를 재사용한다.
- 짧은 in-memory TTL 캐시로 Agent 호출을 줄인다. 다중 인스턴스 공용 캐시는 요구 전까지 추가하지 않는다.

## Approach (Checklist)
- [x] **Step 0: Recon** QA 생성/Agent adapter/API 흐름 확인
- [ ] **Step 1: Implementation** catalog DTO/서비스/API, TTL 캐시, QA request model/reasoning 전달
- [ ] **Step 2: Tests** catalog/cache/전달 focused test와 전체 테스트
- [ ] **Step 3: Rollout / Rollback** 코드 revert

## Validation
- **Commands to run:** `./mvnw test`
- **Expected output:** 모든 테스트 통과

## Risks & Rollback
- **Risks:** Agent Server catalog 계약 drift
- **Rollback steps:** 변경 커밋 revert

## Open Questions
- None
