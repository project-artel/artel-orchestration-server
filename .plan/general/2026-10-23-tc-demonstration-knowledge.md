# TC 시연 기록과 검증 지식 활용 추가

작성일: 2026-10-23. 상태: 사용자 승인 후 구현 진행. Story: ARTEL-1258.

## 구현 전 검토 반영

- SDK requestId는 기존 qa_log sequence에서 할당하고 (instanceId, requestId) 소유권을 영구 기록한다. 종료된 요청의 결과도 시연 router에서 소비하여 QA fallback으로 전달하지 않는다.
- SessionManager의 공통 action 전송 경계에서 시연 제어권을 검사하여 QA, reset, 직접 SDK action 등 우회를 차단한다. QA 시작과 시연 시작은 같은 DB instance 잠금 아래 상호 배타적으로 처리한다.
- SDK는 protocolVersion=1인 DEMONSTRATION_CAPABILITIES로 지원을 알린다. 연결별 capability를 보관하고 시작·dispatch 모두 검사한다. 재접속은 기존 lease를 무효화한다.
- DEMONSTRATION_CONTROL 메시지는 action queue 밖에서 처리한다. SDK 매 frame에서 만료를 확인하고 ReleaseAgentInput을 호출한다. queue 대기 및 실행 중 coroutine도 generation/expiry를 확인하여 이전 입력을 중단한다.
- STOPPED 후 결과와 업로드가 남았으면 FINALIZING으로 두고, 제한 시간 뒤 미응답은 UNKNOWN으로 확정한다. 원본 revision을 봉인한 뒤 추출한다. 봉인 후 도착한 결과는 감사 기록에 남기고 승인된 원본을 바꾸지 않는다. 추가 증거로 재추출하려면 새 원본 revision이 필요하다.
- 시연 승인 시 UNKNOWN 입력·누락 evidence에서 성공 사실을 추론하지 못하게 하고, 미해결 항목을 사람이 확인하도록 한다.
- 구현 첫 단위는 TC 하나의 연결→조작→증거→초안 승인→새 QA 실행을 관통하는 경로다. 영상 전체 분석이나 범용 workflow 엔진은 추가하지 않는다.

## 목표와 범위

Scenario 작성 화면에서 사람이 TC와 game instance를 선택하고 streaming 화면을 조작한다. SDK가 적용한 입력과 관찰 결과를 기록하고, 사람은 판정할 장면과 성공·실패를 선택한다. 설명은 선택 입력이며 기대 결과는 TC에서 가져온다. Agent가 관찰과 판정 근거를 포함한 검증 지식 초안을 만들고 사람이 확인한 버전만 이후 QA 실행에서 사용한다.

2026-10-23 구현 중 사용자 피드백에 따라 필수 글쓰기를 없앴다. 화면 판정 후 자동 정리하고, 기본 화면은 읽기 요약과 승인으로 구성한다. 상세 수정·다른 장면·영역 지정은 접어 둔다. 근거가 부족하거나 TC와 판정이 충돌하면 질문을 남기며, TC가 요구하지 않는 정확한 처리 시간 같은 부가 정보를 필수 질문으로 만들지 않는다.

첫 버전은 한 시연 세션에 한 TC, 마우스·키보드 입력, 텍스트 설명과 화면 영역 지정, 지식 검토, 새 QA 실행에서의 재사용까지 포함한다. ORDERED와 FREE의 기존 실행 의미를 유지한다. 시연은 TC에 연결하고 scenario는 작성 진입점으로 기록한다.

음성 해석, 모델 재학습, 여러 TC에 걸친 시연 자동 분할, 공통 절차 자동 승격, 모든 gamepad·touch 입력, 영상 전체 자동 분석은 후속 범위다. 모델 재학습 없이 구조화된 지식을 실행 context에 제공한다.

## 조사 범위와 현재 구조

Windows 작업 디렉터리의 artel-home, artel-orchestration-server, artel-agent-server, artel-sdk 소스를 읽었다. 별도 worktree와 원격 최신 브랜치까지 대조한 결과는 아니다. Hindsight는 서버 이름 해석 오류로 조회하지 못했다. 실행 검증은 하지 않았다.

| 영역 | 현재 소스와 동작 | 변경점 |
| --- | --- | --- |
| Scenario | `artel-home/src/testScenarios/TestScenarioPage.tsx`, `FreeScenarioEditor.tsx`, `ScenarioStepEditor.tsx`: TC/step 편집과 RunChat | TC 선택에 시연 세션 연결. 기존 run chat의 수명을 유지 |
| 영상 | `artel-home/src/streaming/GameStreamView.tsx`, `streamTypes.ts`: video와 overlay, WebRTC signaling | 입력 overlay와 시연 상태 추가. 영상은 P2P이므로 서버 기록을 별도로 마련 |
| SDK 입력 | `artel-sdk/Packages/kr.artel.sdk/Runtime/ActionExecutor.cs`: move_mouse, mouse_down/up, key_down/up, button_click, enter_text 등 | 기존 명령 재사용. 제어권 만료 시 입력 해제와 오래된 입력 차단 보강 |
| 대상 지정 | `Runtime/Pointer/PointerAimParser.cs`: 좌표, instanceId, selector 지원 | 실제 식별 근거가 있을 때만 selector로 정리. 런타임 instanceId를 다음 실행에 재사용하지 않음 |
| 명령 전송 | `qa/service/QaActionDispatchService.kt`: 활성 qa_try 및 qa_log에 의존 | 사람 시연 전용 dispatch와 request 소유자 라우팅 추가 |
| SDK 결과 | `sdk/service/handler/ActionResultMessageHandler.kt`: scan, screen capture, QA로 전달 | 시연 requestId를 소유한 결과만 시연으로 전달 |
| 관찰 | `sdk/service/handler/PulseMessageHandler.kt`: QA 중계와 screen 관찰 | 시연 중 pulse 구독·저장, whole/delta 순서와 누락 처리 |
| 화면 저장 | `qa/service/QaCaptureService.kt`: 활성 qa_try로 업로드 ticket 및 evidence 생성 | 시연 소유의 업로드·evidence 경로 지원. 가짜 QA run을 만들지 않음 |
| 지식 | `knowledge/entity/KnowledgeEntity.kt`, `KnowledgeSource.kt`, `KnowledgeAnchorEntity.kt`: DOCS/QA, 버전·이력·scope·화면 anchor | 시연 출처, TC/build 연결, 검토 상태, 구조화된 절차 추가 |
| QA 실행 | `artel-agent-server/app/qa/schemas.py`: QaCaseRef와 QaScenario; `app/agents/qa/runner.py`: ORDERED/FREE context 구성 | 해당 TC에 승인된 절차를 명시적으로 첨부하고 사용 버전을 기록 |

`ScenarioStep.expected_passed`는 benchmark 정답 라벨이다. 시연의 실패 판정이나 기대 결과 저장에 사용하지 않는다. `TestCaseVersionEntity`가 TC/build별 내용을 보유하므로 TC ID만 연결해서는 다른 build에 잘못 적용할 수 있다.

## 설계 결정

### 시연 세션과 제어권 추가

신규 개념명은 제안이다. `DemonstrationSession`은 project, TC, TC 내용 snapshot/hash, build, scenario, game instance, 사용자, 상태, 시작·종료 시각을 보유한다. 세션 상태는 RECORDING → FINALIZING → SEALED이며 연결 유실은 INTERRUPTED로 남긴다. 중단 이후 재개는 별도 시연으로 기록한다.

영상 시청 lease와 게임 입력 제어권은 분리한다. `GameControlLease`는 instance마다 HUMAN 또는 QA 한 소유자만 허용한다. 획득은 원자적으로 처리하고 QA의 시작·다음 scenario 전환·실제 action 전송·reset 경로에서도 확인한다. 첫 버전은 실행 중인 QA를 자동 인계하지 않고 시연 시작을 거절한다. scan 등 게임 상태를 바꾸는 다른 경로도 충돌 여부를 검사한다.

세션 종료, 탭 이탈, focus 상실, viewer takeover, SDK 연결 유실, heartbeat 만료 시 새 입력을 막고 눌린 키·버튼을 해제한다. 브라우저 cleanup에만 의존하지 않고 SDK 측 만료 처리도 둔다. 제어권 세대 번호를 사용해 이전 세션의 지연 입력이 새 소유자에게 적용되지 않게 한다.

### 입력과 증거 기록 추가

영상은 현재 WebRTC를 유지한다. 입력은 인증된 시연 전용 WebSocket을 통해 orchestration으로 보내고 기존 SDK ACTION으로 전달한다. 기존 viewer signaling에 임의로 입력을 섞지 않는다.

각 입력에는 session, control generation, client sequence, command ID, 수신 시각을 기록한다. SDK 결과의 requestId와 연결해 REQUESTED/APPLIED/FAILED/UNKNOWN을 구분한다. 서버 전송 성공을 게임 적용 성공으로 취급하지 않는다. 재접속 시 ACK가 없던 구매 같은 명령을 자동 재실행하지 않는다. pointer move는 합칠 수 있지만 down/up 순서는 보존하고 queue 상한과 backpressure를 둔다.

브라우저 좌표를 영상의 실제 표시 영역·원본 해상도·Unity 좌표계로 변환한다. letterbox, resize, DPI, drag, key repeat, focus를 검증한다. 지원되지 않는 입력은 초기에 제한한다.

`DemonstrationEvent`에 입력, SDK 결과, pulse, 설명, 판정, 증거 참조를 시간순으로 저장한다. 큰 원본과 이미지는 object storage에 저장하고 DB는 object key와 메타데이터를 보유한다. signed URL을 영구 참조로 사용하지 않는다.

사람이 판정할 때 보고 있던 video frame을 브라우저에서 즉시 고정·저장하고 영역 좌표를 그 이미지 기준으로 기록한다. 뒤늦게 요청한 SDK screen capture와 같은 장면이라고 간주하지 않는다. SDK screen capture와 pulse는 별도 관찰 시각·frame/sequence 정보와 함께 붙이고 정확한 동기화가 불가능하면 그 차이를 표시한다. 입력 전후 주요 화면도 남겨 추출 agent가 절차를 이해할 수 있게 한다. 일시적인 실패는 지나간 뒤 복원할 수 없으므로 시연 중 장면 표시가 첫 버전의 제약이다.

기존 screen capture 업로드가 qa_try에 의존하는 부분을 시연 evidence에서도 사용할 수 있게 분리한다. 업로드 소유권, project 접근권, 크기 제한과 누락 상태를 검증한다.

### 검증 지식 초안과 승인 추가

`VerificationProcedure`는 시연 원본과 분리된 버전 문서다. 다음을 구조화한다.

- 적용 범위: project, TC, TC 내용 hash, build, 필요한 scene/조건.
- 시작 조건과 준비 방법.
- 단계별 의도, 대상, 수행 방법, 완료·대기 조건, 근거 event ID.
- 확인 항목, 정상·실패 조건, 확인 시점과 판정 불가 조건.
- 해당 시연에서 관찰한 결과와 사용자가 설명한 기대 결과.
- 확인된 사실, agent 추론, 미해결 질문.

Agent 추출은 `/internal` 작업으로 orchestration이 요청한다. 동일 원본과 추출 버전의 재시도로 중복 초안이 생기지 않게 한다. 시연 중에는 초안 추출 agent가 게임 입력을 실행하지 않는다.

상태는 DRAFT → APPROVED → SUPERSEDED/REVOKED로 둔다. 승인은 특정 revision과 원본 hash에 적용하고 수정 시 새 초안을 만든다. 시연의 FAIL은 정상 기대 결과로 승격하지 않는다. TC 기대 결과와 사람 설명이 충돌하면 승인 전에 충돌을 해결한다. 시연 판정만으로 기존 TC의 QA verification_status를 갱신하지 않는다.

기존 knowledge를 검색·표시 창구로 재사용하되 구조화된 절차 원본은 별도 테이블에 둔다. source에 DEMONSTRATION을 추가하고 DB CHECK, DTO, agent/UI enum을 함께 확장한다. 기존 CONTROL/OBJECTIVE 등 topic tag를 사용하고 purpose만을 이유로 새 tag를 만들지 않는다. 승인 revision과 knowledge projection을 일관되게 연결하며, QA의 update/forget 도구가 사람이 승인한 절차를 직접 덮어쓰지 못하게 한다. 수정은 새 제안으로 남긴다.

첫 버전은 같은 TC 내용·build에 승인된 절차만 사용한다. 다른 build에는 검토 후 재승인한다. 승인 revision 철회는 이후 실행부터 적용하며 이미 시작한 실행에는 당시 snapshot과 사용 이력을 남긴다.

### QA 실행 context 확장

Orchestration이 실행 시작 시 TC별 승인 절차를 조회해 revision snapshot을 확정한다. 벡터 검색에서 우연히 발견되기를 기다리지 않고 `QaCaseRef` 또는 별도 case-keyed 계약에 첨부한다. 공유 검색에는 승인된 범위의 projection만 노출한다.

ORDERED에서는 기존 step 순서를 보존하면서 TC 검증 방법으로 참고한다. FREE에서는 순서 선택을 유지하면서 해당 TC의 수행·판정 기준으로 사용한다. 절차가 현 상태와 맞지 않으면 관찰과 함께 적용 불가로 보고하고, 기록된 좌표를 무조건 반복하지 않는다. 판정 근거는 이번 실행에서 관찰한 evidence여야 하며, 과거 시연 이미지를 현재 결과로 인용하지 않는다.

실행 이력에 procedure ID/revision과 실제 사용 근거를 남긴다. 기능이 꺼졌거나 승인 절차가 없을 때 기존 동작을 유지한다. context/tool 구조가 바뀌면 AGENTS.md 규칙에 따라 QA_ARCH_LABEL과 관련 테스트를 갱신하고 prompt 버전은 별도로 관리한다.

## 구현 순서와 완료 기준

| 단계 | 주요 변경 | 완료 기준 |
| --- | --- | --- |
| 1. 계약과 저장 | orchestration에 세션·event·절차 revision·lease·증거 API, SDK 만료 계약 정의 | 같은 instance에 QA와 사람이 동시에 제어권을 얻지 못하고 이전 입력을 거절 |
| 2. 조작과 기록 | SDK 입력 해제/만료, 시연 dispatch/result routing, pulse와 evidence 저장 | UI 입력이 게임에 적용되고 결과가 정확한 세션·명령에 연결됨 |
| 3. Scenario 화면 | TC별 연결, instance 선택, GameStreamView 입력 overlay, 기록·판정·영역 지정 | 화면에서 TC 하나를 시연하고 새로고침 후 증거와 설명을 다시 확인 |
| 4. 지식 초안과 검토 | agent 추출, 초안 편집·승인, knowledge projection | 사람 설명과 추론을 구분하고 승인하지 않은 지식은 QA에 노출되지 않음 |
| 5. QA 활용 | 승인 revision resolve, agent context, 사용 이력 | 새로운 게임 실행에서 절차를 활용하고 현재 evidence로 판정 |
| 6. 통합 검증 | 실제 게임 정상/실패 fixture, 회귀 검증 | 아래 검증 항목 통과 후 feature flag 활성화 |

Backend 계약과 migration을 먼저 배포하고 새 SDK 기능을 지원하는 instance에만 조작 기능을 연다. 다음으로 시연 UI와 추출 경로, 마지막으로 QA 활용을 활성화한다. 이전 SDK와 agent-server는 기존 QA 경로를 유지한다. 구현 시 여러 repository의 Story와 repository별 Subtask로 나누고 API 생산자부터 병합한다. 사용자 승인 후 ARTEL-1258 및 저장소별 ARTEL-1259~1262로 구현한다. Feature flag는 기본으로 끈다.

## 검증 계획

- 계약·권한: 다른 project의 instance/TC/evidence 접근 거절, 다른 사용자의 제어권 탈취 거절, build/TC 불일치 거절.
- 제어: QA 시작과 시연 시작의 경쟁, 탭 두 개, lease 만료, 서버 재시작, 지연 명령, key-down 뒤 연결 유실. SDK에서 입력 해제가 확인되어야 한다.
- 입력: 화면 비율과 해상도 변화, letterbox, 클릭·drag·키 누르기/떼기, queue 초과. 구매 중복 실행이 없어야 한다.
- 증거: 시연 결과가 QA/scan 결과로 잘못 라우팅되지 않음, pulse whole/delta 누락 표시, 영상 장면과 SDK 관찰 시간 차이 보존, 업로드 실패 복구.
- 지식: 실패 시연에서 실패 현상을 기대 결과로 생성하지 않음, 모든 사실의 원본 event 추적, 추론 구분, 동시 수정·승인 충돌, 철회·revision 교체·TC 변경 후 적용 차단.
- QA: ORDERED/FREE 각각 정상 fixture와 의도한 실패 fixture를 구분. 승인 절차 없음/있음 조건을 같은 build·model·비교 가능한 prompt 조건에서 반복 실행하고 판정 정확도·완료 TC 수·입력 수·시간·지식 사용 근거를 기록.
- Agent 구조 변경 검증은 qa-run-local skill의 실제 QA 실행으로 수행하고 전후 run_config의 model, prompt_version, agent_arch, agent_fingerprint를 보존한다. 한 번의 실행을 개선 효과로 일반화하지 않는다.
- UI: 기존 Blueprint Paper tokens, light/dark, 좁은 화면, keyboard focus, 기록 중 상태와 연결 유실 상태. 기존 run chat과 scenario autosave 회귀 확인.

## 남은 확인과 되돌리기

구현 첫 단계에서 SDK action queue의 처리 지연, 영상과 SDK frame 식별자의 연결 가능성, 게임별 input backend 지원, reset 후 시작 조건 재현을 실제 게임으로 확인한다. 이 확인 전에는 연속 입력의 지연이나 정확한 frame 동기화를 보장하지 않는다.

Feature flag로 신규 시연 시작과 QA 절차 첨부를 각각 끌 수 있게 한다. 끌 때 활성 제어권과 눌린 입력을 해제한다. 기존 QA·streaming은 유지하고 시연 원본과 승인 이력은 삭제하지 않는다. migration은 추가 방식으로 적용한다.
