# QA 판정 코드 검사와 검토 대상 (ARTEL-1252)

## 목표
AI 판정마다 코드 결과(TC `case_key` 효과 × `PULSE` 기록)를 붙이고, 사람이 다시 볼 판정을 고른다. 판정은 덮어쓰지 않는다.

## 근거
`measurements/2026-10-20-code-vs-ai-poc` 의 POC(코드 확정 43/44 정답)와 2026-10-23 재실행(qa_run 32~34)에서 결과가 같은 모양으로 나왔다.

## 한 것
- `qa/verdict/VerdictCodeCheck.kt`: `case_key` 를 효과로 읽고, `PULSE` 를 접고, 효과를 확인한다. POC `codecheck.py` 를 옮긴 것이다.
- `QaVerdictCodeCheckService`: 시도 하나의 SDK `PULSE` 를 한 번 읽어 판정 순서대로 접는다. 따로 저장하지 않는다.
- 검토 기준 `needsReview`: AI 통과인데 코드 PASS 가 아님, 또는 AI 실패인데 코드 PASS.
- `GET /api/qa-runs/{qaRunId}/verdicts` 에 `codeCheck` · `needsReview` · `reviewCount` 를 추가했다.

## 범위 밖
- 사전조건 평가: 원자의 상당수가 메서드 호출이라 기록에 값이 없다. 이번에는 보류했다.
- 결과 저장과 QA 종료 알림: 알림 작업에서 한다.

## 검증
- 단위 테스트 5건, 통합 테스트 1건(`CaseVerdictFromQaIntegrationTest`).
- 실제 데이터 qa_run 32~34: 검증 판정 121건이 POC 스크립트 결과와 모두 같다. 검토 대상은 20건이다.
