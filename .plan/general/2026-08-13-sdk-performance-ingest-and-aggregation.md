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

→ **SDK를 먼저 배포해도 연결이 끊기지 않는다.**
남는 것은 로그 소음뿐이다(세션당 초당 1건 WARN). 이 작업이 핸들러를 추가하면
그 소음도 사라진다.

단, 위 안전성이 **Spring Boot의 기본 설정 하나에 걸려 있다**는 점이 그대로 남는다.
`FAIL_ON_UNKNOWN_PROPERTIES`가 설정 한 줄로 켜지면 `BaseMessage`는 본문이 딸린 모든
프레임에서 파싱 예외를 던지고, SDK가 초당 한 번 보내는 `PERFORMANCE`가 "모르는 타입"이
아니라 "깨진 프레임"으로 떨어진다. 연결은 유지되지만 진단이 뒤집힌다.
→ `BaseMessage`에 `@JsonIgnoreProperties(ignoreUnknown = true)`를 명시하고, 미지 필드에
엄격한 매퍼로도 통과하는 테스트로 고정한다. SDK와 서버는 따로 배포되므로 명시가 곧 계약이다.

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
  - `sdk_performance_run_budget` — 런별 `budget_ms` 도수분포. 최빈값을 원본 스캔 없이
    증분으로 구하기 위한 것이다(아래 Step 3 참조).
- [x] **Step 2: 수신** — 기존 `SdkMessageHandler` 전략에 두 타입 추가.
- [x] **Step 3: 집계** — 저장 트랜잭션에서 런 요약과 시계열 셀 증분 갱신.
      `budget_ms` 최빈값만은 증분으로 접히지 않아, 런별 도수분포 테이블을 한 번 더 둔다.
      (첫 구현은 표본마다 원본을 `GROUP BY`로 훑어 런당 O(n²)였다.)
- [x] **Step 4: 조회 API 2개** — Notion 확정 경로와 본문 구조 그대로.
      접근 불가 리소스는 403이 아니라 404로 답한다. 문서의 404 항목이 이 경우를 덮는다.
- [x] **Step 5: 테스트** — 행동 테스트로 미지 타입 무해성, 두 메시지 저장,
      `process` 결측/0 구분, 길이 다른 두 런의 분당 hitch 비교, `isEditor` 제외를 고정한다.

## 계약 교정 (검토 중 발견)

빌드 추세 조회가 `s.is_editor = FALSE`로 적혀 있었다. SQL에서 `NULL = FALSE`는 참이
아니므로 `DEVICE_CONTEXT`를 못 받은 런까지 함께 빠진다. 확정 계약은 `isEditor`"인" 런만
제외하라고 하고, 에디터라고 단정할 근거가 없는 런을 조용히 빼면 화면에서 런이 이유 없이
사라진다. → `IS DISTINCT FROM TRUE`로 고치고 부분 인덱스 조건도 맞췄다. 회귀 테스트로
(에디터 런은 빠지고 장치 미상 런은 남는지) 고정했다.

## Validation

실행한 명령과 실제 결과.

- `./scripts/check-flyway-migrations.sh` — `OK: no version collisions.`
  (develop V35, 열린 다른 브랜치가 V36·V37, 이 브랜치가 V38)
- `./scripts/verify-flyway-upgrade.sh` — develop의 V1~V35 적용 후 V38을 얹고 `validate` 통과
- `./mvnw -o test -Dtest=SdkPerformanceContractTest,SdkWebSocketHandlerUnknownTypeTest,SdkPerformanceIntegrationTest`
  — Tests run: 17, Failures: 0, Errors: 0
- `./mvnw -o test` — Tests run: 500, Failures: 0, Errors: 0

돌리지 않은 것:

- 실제 Unity SDK를 붙인 종단 확인. 소켓 프레임은 테스트가 만든 것이다.
- "초당 1건 × 동시 세션"의 쓰기 부하 실측. 표본당 쓰기 쿼리가 4개(원본·요약·시계열·
  budget 도수)라는 사실만 확인했다.

## Risks & Rollback

- **Risks:**
  - 초당 1건 × 동시 세션의 INSERT 부하. 표본 하나가 쓰기 쿼리 4개를 낸다. 부하 실측은
    하지 않았다.
  - 요약이 표본 도착마다 갱신되므로 세션 종료 훅에 의존하지 않는다. 서버가 중간에
    죽어도 그 시점까지의 요약은 이미 저장돼 있다(자기치유 경로가 필요 없어진 이유).
- **Rollback steps:** `git revert`. 마이그레이션은 신규 테이블 추가 + nullable 컬럼
  1개라 이전 코드와 호환된다(되돌려도 기존 경로가 깨지지 않는다).

## Known limitation

- 런 요약의 p95/p99는 창별 p95/p99의 프레임 수 가중 평균이다. 원본 프레임 분포가
  없어 정확한 런 전체 백분위는 복원할 수 없다. 확정 계약에 런 요약 수준의 최대
  프레임타임 필드가 없으므로 요약에는 싣지 않고, 시계열 점의 `frameMaxMs`(그 버킷의
  창별 max의 max)가 정확한 값으로 남는다.
