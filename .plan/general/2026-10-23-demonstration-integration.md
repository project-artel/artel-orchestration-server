# TC 시연과 승인 절차의 실제 QA 검증

## 검증 환경

Windows Unity 2022.3.34f1 Development fixture, 로컬 orchestration 8096/8097, agent 8098, home 5194를 사용했다. Postgres와 Redis는 기존 개발 환경과 분리했다. 실제 WebRTC, SDK 입력, PNG 저장, OpenRouter `openai/gpt-6-luna`를 연결했으며 API mock은 사용하지 않았다.

fixture의 오른쪽 `Increment` 버튼은 정상 모드에서 Count를 1 증가시킨다. 실패 모드는 같은 build의 `-artel-fixture-fail` 옵션으로 Count를 유지한다. project/build/instance/TC는 각각 1이며, ORDERED scenario/test run은 1, FREE scenario/test run은 2다.

## 시연과 승인 검증

초기 흐름에서 Count 0 화면을 보고 버튼을 한 번 눌러 Count 1을 확인했다. session `706443ac-2823-4cab-b9bb-3f4ddeeb9b11`의 명시적 PNG event 112와 PASS annotation 269를 저장했다. 추출 후 사람이 내용을 수정하고 procedure 2/revision 2를 승인했다. 수정 전 revision 1은 SUPERSEDED로 남는다.

처음 추출된 초안이 일본어로 나와 TC 언어를 우선하도록 prompt를 수정했고, 실제 한국어 추출을 다시 확인했다. 입력 직후 자동 frame은 Count 0, 이후 pulse와 판정 frame은 Count 1을 보였다. 이를 같은 시각의 관찰로 간주하지 않는다.

사용자 피드백에 따라 관찰·기대·판정 이유의 필수 글쓰기를 제거했다. 판정과 화면만 필수이며, 기대 결과는 TC에서 읽고 agent가 관찰과 이유를 정리한다. session `cf713356-456d-4d5b-a0be-6a2aa1f3c04a`에서는 Count 0→1을 확인한 뒤 `{"outcome":"PASS","evidenceEventId":472}`만 전송했다. 자동 추출이 한국어 초안과 미해결 질문 없음으로 완료됐고, 수정 없이 procedure 3/revision 1을 승인했다. 기존 procedure 2는 SUPERSEDED로 전환됐다.

## QA 결과

| 실행 | 조건 | 결과 | 절차 사용 근거 |
| --- | --- | --- | --- |
| qaRun 1 / qaTry 1 | ORDERED 정상, procedure 2/revision 2 | COMPLETED, 1/1 PASS | 보고서가 procedure 2/revision 2를 명시하고 이번 실행의 reset → Count 0 → 단일 클릭 → Count 1과 SDK count 1을 인용 |
| qaRun 2 / qaTry 2 | FREE 정상, procedure 3/revision 1 | COMPLETED, 1/1 PASS | 이번 실행의 Count 0→1과 procedure 3/revision 1을 인용 |
| qaRun 3 / qaTry 3 | ORDERED 실패, procedure 3/revision 1 | FAILED, 0/1, issue 1건 | 클릭 후 Count 0 유지와 현재 SDK 상태로 FAIL 판정 |
| qaRun 4 / qaTry 4 | FREE 실패, procedure 3/revision 1 | FAILED, 0/1, issue 1건 | procedure 3/revision 1을 적용하고 현재 클릭 후 Count 0 유지로 FAIL 판정 |

실행 시간은 순서대로 37.11초, 50.619초, 30.875초, 54.294초다. 조건당 한 번씩 실행했으며, 기록은 통합 동작의 증거다. 같은 조건에서 절차 없는 baseline과 반복 비교하지 않았으므로 성능·정확도 개선을 입증하지 않는다. planner/executer의 실제 게임 실행은 이번 검증에 포함하지 않고 계약 테스트로 확인했다.

## 스트리밍 좌표와 연속 이동 수정

CSS video 영역의 offset과 letterbox를 제외한 위치를 0..1로 정규화하고 SDK의 현재 게임 해상도로 변환한다. 실제 video bounds가 x=520, y=464, width=530, height=298.125일 때 브라우저 (917.5, 613.0625)는 게임 버튼의 (0.75, 0.5)에 도착했고 Count가 증가했다.

자연스러운 연속 이동에서는 기존 SDK glide 때문에 pointer 명령이 쌓이는 문제가 드러났다. 시연만 즉시 이동하도록 바꾸고 기존 QA glide는 유지했다. 서버 ACK는 SDK 결과가 저장된 뒤 반환해 브라우저의 최신 위치 합치기가 게임 처리 속도를 따르도록 변경했다. SDK의 연속 이동·drag/drop·해상도/Y축 변환을 포함한 Unity 640개와 서버의 결과 전 ACK 차단·timeout 검사를 통과했다.

최종 브라우저 검증에서는 영상 좌상단 0.01→우하단 0.99→중앙 0.5를 연속 60회 이동한 뒤 자연스러운 pointer 이동을 거쳐 버튼을 클릭했다. 연결이 유지됐고 normalized (0.75, 0.5)가 SDK의 (1440, 540)에 도착해 Count 0→1이 됐다. 저장된 INPUT/ACK는 각각 33개이며 ACK 뒤 다음 INPUT이 전송되고 down/up 앞 최종 move가 유지됐다. 판정용 frame 627로 고정하고 제어를 종료했다.

## 검증 범위와 제한

숨긴 Unity 창은 영상 frame이 검게 나와 interactive window를 표시한 상태에서 검증했다. 이 fixture가 다른 게임의 rendering, input backend, 네트워크 지연을 대표하지 않는다. 과거 시연의 PASS를 새 QA의 정답으로 복사하지 않고 현재 실행의 evidence로 판정한다.

토큰·provider key·실행용 환경 파일과 raw 로그는 버전 관리에서 제외한다. 화면 검증 자료는 home PR의 `.plan/assets/2026-10-23-tc-demonstration/`에 보관한다.
