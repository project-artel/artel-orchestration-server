# 2026-08-13 — SDK 성능 보고 수신·저장과 런/빌드 집계

- Date: 2026-08-13
- Jira: ARTEL-372, ARTEL-378
- Status: Complete

## Goal

1. (ARTEL-372) `/ws/sdk`로 오는 `PERFORMANCE`(1초마다)·`DEVICE_CONTEXT`(연결당 1회)를 수신·저장한다.
2. (ARTEL-378) 런 요약을 사전 집계해 저장하고, 런 상세·빌드 추세 조회 API 두 개를 연다.

## Non-goals

- 대시보드·시각화 (ARTEL-379)
- 성능 회귀 자동 판정·알림
- 원본 롤업·삭제 정책 (원본은 전량 보존)

## Context / Constraints

### 선행 확인 — 모르는 타입 안전성 (ARTEL-372 Scope 1)

`sdk/service/SdkWebSocketHandler.kt:123-126`이 이미 **안전한 무시**다.
`handlerMap[base.type]`가 null이면 WARN 로그 후 `Mono.empty()`를 돌려주고, 수신
파이프라인은 계속 산다. 파싱 예외도 `catch (e: Exception)`(127-130)이 잡아 같은
자리로 보낸다. `BaseMessage`는 `type` 한 필드뿐이지만 커스텀 ObjectMapper 설정이
없어 Spring Boot 기본값(`FAIL_ON_UNKNOWN_PROPERTIES=false`)이 적용되므로
`frameTimes` 같은 미지 필드가 있어도 파싱 자체가 성공한다.

→ **SDK를 먼저 배포해도 연결이 끊기지 않는다. 고칠 것이 없다.**
남는 것은 로그 소음뿐이다(세션당 초당 1건 WARN). 이 작업이 핸들러를 추가하면
그 소음도 사라진다.

### 저장 제약

- `process`는 필드째 빠질 수 있다. **없음과 0을 구분**해야 하므로 컬럼을 전부
  nullable로 두고 결측은 NULL로 저장한다. SQL 집계 함수가 NULL을 건너뛰므로
  평균이 0으로 끌려가지 않는다.
- `sampledMs`는 전송 주기가 아니다. 시간 가중 지표는 이 값을 쓰고, 커버리지
  (`sampledMs` 합 / 벽시계)를 응답에 함께 싣는다.
- 조회 시점에 원본을 훑지 않는다. 샘플 저장 트랜잭션에서 `qa_run` 요약과 1초 시계열 셀을 증분 갱신한다.

## Approach (Checklist)

- [x] **Step 0: Recon** — 완료. R2DBC + Flyway(최신 V35) + `CoroutineCrudRepository`,
      컨트롤러는 `/api/projects/{projectId}/...`, 응답 id는 String, 목록은 `{items:[...]}`.
- [x] **Step 1: 마이그레이션 V38**
  - `game_instance.last_game_build_id` 추가 — 세션을 빌드에 붙일 경로가 지금 없다.
    `SdkRegistrationService`가 이미 같은 트랜잭션에서 빌드를 알고 있으므로 거기서 쓴다.
  - `sdk_device_context` — 연결별 최신 DEVICE_CONTEXT.
  - `sdk_performance_sample` — 원본 전량. 도착 시 활성 `qa_run_id`, 없으면 NULL.
  - `sdk_performance_run_summary` — 샘플마다 증분 갱신하는 런 사전 집계.
  - `sdk_performance_run_series` — 1초 단위 파생 셀. 조회는 원본 대신 이 셀만 접는다.
- [x] **Step 2: 수신** — 기존 `SdkMessageHandler` 전략에 두 타입 추가.
- [x] **Step 3: 집계** — 저장 트랜잭션에서 런 요약과 시계열 셀 증분 갱신.
- [x] **Step 4: 조회 API 2개** — Notion 확정 경로와 본문 구조 그대로.
- [x] **Step 5: 테스트** — 행동 테스트로 미지 타입 무해성, 두 메시지 저장,
      `process` 결측/0 구분, 길이 다른 두 런의 분당 hitch 비교, `isEditor` 제외를 고정한다.

## Validation

- `./scripts/check-flyway-migrations.sh` — 통과, 충돌 없음
- `./scripts/verify-flyway-upgrade.sh` — 통과, develop 위 V38 적용·validate 성공
- `./mvnw -q clean -Dtest=SdkPerformanceContractTest,SdkPerformanceIntegrationTest,SdkWebSocketHandlerUnknownTypeTest test` — 통과
- `./mvnw -q test` — 전체 스위트 통과

## Risks & Rollback

- **Risks:**
  - 세션 종료 훅이 서버 크래시 때 안 돈다 → 요약 없는 런이 남는다. 상세 조회에서
    종료된 세션에 요약이 없으면 그때 1회 계산해 저장하는 자기치유 경로를 둔다.
  - 초당 1건 × 동시 세션의 INSERT 부하. 단건 INSERT로 시작하고 테스트로 실측한다.
- **Rollback steps:** `git revert`. 마이그레이션은 신규 테이블 추가 + nullable 컬럼
  1개라 이전 코드와 호환된다(되돌려도 기존 경로가 깨지지 않는다).

## Known limitation

- 런 요약의 p95/p99는 창별 p95/p99의 프레임 수 가중 평균이다. 원본 프레임 분포가
  없어 정확한 런 전체 백분위는 복원할 수 없다. 응답 필드명과 문서에 근사임을 밝히고,
  정확한 값인 `maxFrameMs`(창별 max의 max)를 함께 싣는다.
