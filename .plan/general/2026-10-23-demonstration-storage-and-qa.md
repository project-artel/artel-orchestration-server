# 2026-10-23 — 시연 제어권과 승인 절차 저장 추가

- Date: 2026-10-23
- Jira: ARTEL-1259
- Status: 구현 및 통합 검증 완료

## Goal

TC 시연 입력·관찰·사람 판정을 저장하고, 승인한 검증 절차만 같은 TC 내용과 실제 build의 새 QA에 첨부한다.

## Non-goals

음성 분석, 모델 재학습, 여러 TC 자동 분할, 범용 macro 생성은 포함하지 않는다.

## Context / Constraints

사용자 승인 범위와 4개 저장소 계약은 같은 폴더의 `2026-10-23-tc-demonstration-knowledge.md` 및 `2026-10-23-tc-demonstration-contract.md`에 있다. 기존 QA와 streaming을 유지한다. 신규 기능과 QA 첨부 flag는 기본 false다.

## Approach (Checklist)

- [x] **Step 0: Recon** Scenario, QA 생성, SDK ACTION/PULSE, knowledge scope 및 object storage 경로 확인.
- [x] **Step 1: Implementation** 시연 소유권·8초 lease, 입력 requestId 영구 소유권, 원본 revision, 증거 저장, 추출/편집/승인/철회 API, QA snapshot 적용.
- [x] **Step 2: Tests** 실제 Postgres 통합 테스트, 기존 QA/knowledge 회귀, Unity 게임과 browser의 실제 흐름 검증.
- [x] **Step 3: Rollout / Rollback** 지원 SDK만 허용하고 flag로 신규 시작과 QA 적용을 각각 중단. 원본과 이력 유지.

## Validation

- **Commands to run:** JDK21 `mvn test`의 demonstration, SessionManager, Knowledge, QA 대상; Unity EditMode/PlayMode; browser UI와 실제 extraction.
- **Expected output:** 권한·제어권 경쟁·stale revision·누락 증거·UNKNOWN 입력 거절. 사람이 승인한 절차가 새 QA snapshot에만 적용됨.
- Kotlin main/test compile 통과. 첫 실제 startup에서 nullable default constructor를 추가한 WebSocketConfig의 Spring 주입 실패를 발견해 `@Autowired`를 명시했고, 수정한 서버로 실제 시연과 QA를 확인했다.
- 최신 DemonstrationIntegrationTest 16/16, DemonstrationValidationTest 7/7 통과. 설명 없는 annotation, 입력 경쟁·중복·지연 결과, 증거 변경과 승인 무효화, revision·TC/build 일치와 SDK 결과 전 ACK 차단을 검사했다.
- SessionManager 5/5, QaTryDetail 13/13, QaTryResponseRunId 4/4 및 scenario composition 55/55 통과. KnowledgeSource에 DEMONSTRATION을 추가한 데 맞춰 enum 기대 목록을 갱신했다.
- 실제 브라우저에서 Count 0→1, PNG·PASS 판정·추출·수정·승인과 승인본을 사용한 ORDERED QA 1/1 PASS를 확인했다. 상세 기록은 `2026-10-23-demonstration-integration.md`를 따른다.
- Knowledge 22/22를 포함해 backend focused 122개가 통과했다. 실제 최소 입력 흐름과 ORDERED/FREE 정상·실패 4회는 각 예상 결과와 일치했다.

## Risks & Rollback

- **Risks:** SDK와 영상 frame 시각이 정확히 같지는 않다. 초기 delta pulse만 있으면 완전한 상태로 추정하지 않는다. 정리 요청은 2,000,000자/12화면 한도다.
- **Rollback steps:** `ARTEL_DEMONSTRATION_ENABLED`와 `ARTEL_DEMONSTRATION_QA_ENABLED`를 끄고 lease 만료를 기다린다. 추가 테이블은 보존한다.

## Open Questions

첫 버전의 구현과 fixture 통합 검증을 완료했다. 반복 baseline 비교와 planner/executer 실제 게임 검증은 수행하지 않았으며, 단일 로컬 실행을 성능 개선의 근거로 해석하지 않는다.
