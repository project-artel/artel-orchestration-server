# TC 시연 기록과 승인 절차 추가

Scenario에서 사람이 게임을 조작하며 TC 검증 방법을 기록한다. 사람은 판정할 화면과 성공·실패를 선택하고, 필요한 경우에만 설명을 덧붙인다. 기대 결과는 TC를 사용한다. Agent가 화면·입력·관찰을 검증 절차로 정리하고, 사람이 승인한 revision을 새 QA 실행의 TC context에 첨부한다. 시연 판정은 TC의 QA 결과를 갱신하지 않는다.

## 연결과 제어권 처리

SDK의 `DEMONSTRATION_CAPABILITIES` protocolVersion 1이 확인된 instance만 시연을 시작할 수 있다. QA 실행과 시연 시작은 같은 `game_instance` 행을 잠가 충돌을 막는다. `SessionManager.sendAction`도 제어권을 검사한다.

시연 소유자만 `/ws/demonstration?sessionId=...`에 연결할 수 있다. 브라우저 연결은 하나만 허용하며, SDK `ACQUIRE` 응답 이후 입력을 받는다. 시연의 `move_mouse`는 왼쪽 위 기준 0..1 좌표이며 SDK가 실행 시점의 게임 해상도로 바꾼다. 기존 QA 좌표 계약은 유지한다.

제어권은 8초마다 만료되며 갱신이 필요하다. SDK는 queue 밖에서도 만료를 확인하고 눌린 입력을 해제한다. 서버가 `RELEASE`를 보내더라도 기존 lease 만료 전까지 새 QA를 허용하지 않는다. 연결 유실 후 입력을 자동 재전송하지 않는다.

사람의 시연에서는 커서를 보간하지 않고 전달된 영상 위치로 즉시 옮긴다. 서버는 SDK 결과가 저장된 뒤 ACK를 보내고, 브라우저는 그동안 최신 pointer move를 합친다. FAILED도 ACK로 다음 입력을 허용하므로 성공 여부는 ACTION_RESULT로 확인한다. UNKNOWN 또는 2.5초 내 미응답은 제어를 종료한다.

## 원본과 절차 저장

- `demonstration_session`: TC 내용 snapshot/hash, 실제 build, 사용자, SDK 연결과 generation, 상태.
- `demonstration_event`: 입력, SDK 결과, `pulse` 참조, 화면, 사람 판정. 입력 requestId는 `qa_log`와 같은 sequence에서 할당한다.
- Object storage의 `demonstrations/{sessionId}/`: 원본 PNG와 `pulse` JSON. DB에 signed URL을 저장하지 않는다.
- `demonstration_source_revision`: 정리 시점의 원본 hash와 마지막 event ID.
- `verification_procedure`: DRAFT/APPROVED/SUPERSEDED/REVOKED revision.
- `qa_procedure_usage`: 실행에 첨부한 절차 snapshot. 철회 후에도 과거 실행 기록은 유지한다.

종료 후 10초 동안 결과를 기다린 뒤 미응답 입력은 UNKNOWN으로 남긴다. 조회·정리 요청이 종료 처리를 확정한다. 뒤늦은 SDK 결과는 LATE_RESULT로 기록하며 봉인한 원본을 변경하지 않는다. 종료 후 화면이나 판정을 추가하면 새 원본 revision을 만들고 기존 절차 승인을 무효화한다.

정리 API는 원본 `pulse` digest를 검증하고, 사람이 판정한 화면을 우선 첨부한다. 근거가 부족한 초안은 미해결 질문을 포함할 수 있다. UNKNOWN 입력, 누락된 화면, 미해결 질문, 변경된 TC가 있으면 승인하지 않는다. 같은 TC의 승인 요청은 직렬화한다.

## QA 적용과 기능 설정

`ARTEL_DEMONSTRATION_ENABLED=true`로 신규 시연을 열고 `ARTEL_DEMONSTRATION_QA_ENABLED=true`로 승인 절차 첨부를 연다. 두 값 모두 기본값은 false다. 기록 조회는 flag가 꺼져도 가능하다.

QA는 실제 game build와 TC 내용 hash가 일치하는 승인본만 사용한다. Editor 판정용 build fallback은 이 조회에 사용하지 않는다. 한 run에서 같은 TC는 같은 revision snapshot을 사용한다. `DEMONSTRATION` knowledge projection은 사람의 지식 화면에 표시하지만 범용 vector/graph 검색에는 섞지 않는다. QA의 update/forget으로 승인본을 변경할 수 없다.

ORDERED의 step 순서와 FREE의 선택 규칙은 유지한다. 이전 시연의 FAIL은 당시 관찰 결과이며, 새 실행의 성공·실패는 새 evidence로 판단한다.

## 첫 버전 제한

한 시연은 TC 하나다. 입력 event는 최대 10,000개, 미응답 입력은 최대 32개다. PNG는 2 MiB와 1920×1080 이내이며 실제 PNG decoding을 검사한다. 정리 요청은 원본 JSON 총 2,000,000자와 선택 화면 12장 이내다. 한도를 넘으면 검증 구간을 나누어 새 시연을 기록한다.

브라우저 frame과 SDK `pulse`의 시각은 같다고 가정하지 않는다. 처음 받은 `pulse`가 delta라면 전체 상태의 근거로 사용하지 않는다. 시연은 모델 재학습이나 좌표 macro 저장이 아니라 사람이 검토하는 절차 지식의 출처다.

되돌릴 때는 두 flag를 끄고 활성 lease 종료를 기다린다. 추가한 테이블과 원본은 삭제하지 않는다.
