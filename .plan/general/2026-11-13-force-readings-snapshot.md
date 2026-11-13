# 2026-11-13 — start_readings 강제 whole 전송과 QA 시작 복구

- Date: 2026-11-13
- Jira: ARTEL-1451
- Status: Implemented; validation recorded

## Goal
start_readings [{force:true}]로 관찰 중에도 현재 whole PULSE를 즉시 전송한다. 새 QA 시작 시 이 옵션을 사용한다.

## Non-goals
자동 재연결 정책, 게임 카드 데이터, 운영 배포, 유료 QA 재실행은 변경하지 않는다.

## Context / Constraints
동일 SDK PlayMode에서 재연결 후 반복 start_readings는 성공 응답만 내고 신규 PULSE 0건이었다. resync_readings 후 whole 수신을 확인했다. SDK와 orchestration 원본 체크아웃에 다른 작업이 있으므로 분리 worktree를 사용한다.

## Approach (Checklist)
- [x] **Step 0: Recon**: SDK 465e18c와 orchestration 33b9e0fe 기준. 기존 params는 [] 또는 [{interval?,delivery?,minDelivery?}].
- [x] **Step 1: Implementation**: SDK PaceReader에 force bool(기본 false)을 해석한다. 잘못된 타입은 부작용 없이 거절한다. IReadingChannel/ArtelManager에 기존 overload를 유지하고 StartReadings(Pace,bool force)를 추가한다. 이미 관찰 중이며 force=true이면 ResyncReadings와 FlushReadings로 현재 whole을 즉시 보내고 stream/seq는 유지한다. 처음 시작하면 기존 첫 whole만 전송한다. false/생략은 기존 멱등 동작을 유지하고 이미 관찰 중인 pace는 변경하지 않는다. ActionExecutor는 검증된 force를 전달한다. 프로토콜 문서와 SDK 지침에 재연결/새 소비자 첫 snapshot 규칙을 기록한다. QA 서버는 기존 start_readings에 typed 옵션 DTO force=true를 싣는다. stop_readings params는 []로 유지한다.
- [x] **Step 2: Tests**: SDK PlayMode에서 기존 재연결 후 기본 시작은 PULSE 0건인 baseline, force=true는 whole 수신, force=false와 생략은 baseline 재전송 없음, cold force start는 whole 한 건, invalid force는 실패하며 stream 유지. 속도 옵션 호환 확인. 기존 EditMode/PlayMode suite 실행. QA 서버 QaReadingsTest에서 실제 직렬화 force=true와 종단 동작 보존 확인, 관련 reconnect 테스트 실행. 서버 필수 suite/CI 확인.
- [ ] **Step 3: Rollout / Rollback**: SDK PR 이후 서버 PR. 이전 SDK는 미지의 옵션을 무시하므로 시작은 호환되지만 force 효과에는 새 SDK가 필요하다. 두 draft PR은 담당자와 bug 라벨, 테스트 결과와 API 예제를 담는다. 배포와 머지는 이번 작업에 포함하지 않는다.

## Validation
- **Commands to run:** Unity 2022.3.34f1 isolated testable package EditMode/PlayMode, orchestration Maven focused/full tests, git diff --check, latest-head GitHub CI.
- **Expected output:** 안정된 씬에서도 강제 시작 직후 whole PULSE가 나오며 기존 기본 호출은 유지된다. QA 시작 payload는 [{force:true}], 종료는 [].

## Risks & Rollback
- **Risks:** whole을 중복 전송하거나 기본 멱등 호출을 바꾸는 회귀. cold/warm과 기본/강제 경로를 각각 검증한다.
- **Rollback steps:** 각 PR 커밋을 되돌린다. DB와 사용자 데이터 변경 없음.

## Open Questions
- 없음. force는 whole 즉시 재전송이며 pace 재설정이나 입력/행동 초기화가 아니다.
## Plan review
Fast, medium, heavy PASS. force 문자열/숫자/null 거절과 JObject/Dictionary 검증, warm stream/seq/pace 보존, cold whole 한 건을 테스트한다. Flush는 로컬 전송 시도이며 서버 ACK로 표현하지 않는다.


## Naming update
사용자 요청으로 canonical 명령을 subscribe_pulse/unsubscribe_pulse와 C# SubscribePulse/UnsubscribePulse로 바꾼다. start_readings/stop_readings와 기존 C# API는 호환 별칭으로 유지한다. QA 서버는 구형 SDK 호환을 위해 start_readings [{force:true}]와 stop_readings []를 전송한다. 상수/DTO/설명은 subscription 용어를 사용한다. 새 SDK에서는 force가 적용되며 이전 SDK는 모르는 옵션을 무시한다.


## Compatibility review
Heavy 재검토의 legacy SDK 호환 지적을 반영했다. capabilities 협상이나 미지원 응답 fallback은 추가하지 않는다. 신규 canonical 명령은 SDK 공개 계약이며 기존 게임에 요청하는 서버 wire는 호환 별칭을 사용한다.

## Validation results
- Java 21 baseline QaReadingsTest/SdkActionReconnectTest: 9 passed.
- Updated focused Maven tests on WSL native filesystem: 10 passed, 0 failures/errors.
- Pair review PASS. The SDK companion PR is #206; legacy wire keeps older games functional.
