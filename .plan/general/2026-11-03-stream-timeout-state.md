# 2026-11-03 — 스트림 응답 시간 초과의 SDK 미지원 판정 수정

- Date: 2026-11-03
- GitHub Issue: None (Jira: ARTEL-1335, story ARTEL-1334)
- Status: Implemented; draft PR pending

## Goal

첫 응답이 10초 늦은 스트림은 `unsupported` 대신 `failed`로 반환하여 기존 relay 복구 경로를 사용한다. 늦게 도착한 정상 frame을 미지원으로 고정하여 버리지 않는다.

## Non-goals

명시적인 WebRTC 메시지 처리, wire contract, SDK, 녹화 구조, 배포 변경.

## Context / Constraints

`QaMediaRelay.lease`는 failed ingress를 교체하고 START를 다시 보낸다. 시간 초과 상태는 계산값으로만 두고 failed flag를 고정하지 않는다.

## Approach (Checklist)
- [x] **Step 0: Recon** — InProcessMediaService와 QaMediaRelay 복구 경로 확인.
- [x] **Step 1: Implementation** — 첫 응답 timeout 반환값 수정. 테스트의 시간 경과를 제어하기 위한 monotonic clock 주입.
- [x] **Step 2: Tests** — 10초 경계, 늦은 frame 수신 복구, 명시적 구 SDK 오류 유지 및 relay 회귀 테스트.
- [x] **Step 3: Rollout / Rollback** — home ARTEL-1336과 독립 적용 가능. draft PR 생성; 배포하지 않는다.

## Validation
- **Commands to run:** `./mvnw -Dtest=InProcessMediaServiceTest,QaMediaRelayTest test`
- **Expected output:** 시간 초과는 failed이며 SDK_UPDATE_REQUIRED를 발송하지 않는다. 늦은 정상 JPEG는 connected로 복구된다.

## Risks & Rollback
- **Risks:** 응답이 없는 SDK는 기존 maintenance 주기로 START를 재시도한다.
- **Rollback steps:** 이 변경 commit을 revert한다.

## Open Questions
- 없음.

## 검증 결과

- main/test Kotlin 컴파일 통과.
- InProcessMediaServiceTest 10개와 QaMediaRelayTest 9개, 총 19개 통과.
- mvnw test는 공통 Docker listener에서 중단됨. 단위 테스트 실행에만 생성된 target/test-classes의 listener 등록을 임시 제외하고 mvnw -Dtest=InProcessMediaServiceTest,QaMediaRelayTest surefire:test 실행 후 원복. source와 통합 테스트 구성은 변경하지 않음.
- Docker/DB 통합 검증과 배포는 실행하지 않음.
- FAST/MEDIUM/HEAVY plan review와 pair-review VERDICT: PASS.
