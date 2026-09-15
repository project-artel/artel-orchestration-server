# ADR 0007 — `qa_run` 을 자식이 모두 끝날 때 롤업해 닫는다

- 상태: 확정 (ARTEL-259, 뒤에 ARTEL-571 이 취소 경로를 보강)
- 근거: `qa/service/QaRunRollupService.kt`, `qa/service/QaTryService.kt`,
  `qa/service/QaAgentInboundRouter.kt`, `qa/service/QaExecutionFailurePersistence.kt`,
  `src/test/kotlin/kr/artel/orchestration/support/QaRunCleanup.kt`, commit `2fdc739`, commit `4a88eb1`
- **이 결정에는 `.plan` 문서가 없습니다.** 이유는 위 두 commit 메시지에만 남아 있습니다.

## 결정

- `qa_try` 가 종단될 때마다 그 부모 `qa_run` 의 자식을 전부 다시 읽고, 전부 종단이면 `qa_run` 을
  `RUNNING` 에서 terminal 로 옮깁니다.

```kotlin
val runStatus = when {
    tries.any { it.status == "FAILED" } -> "FAILED"
    tries.any { it.status == "CANCELLED" } -> "CANCELLED"
    else -> "COMPLETED"
}
```

- 우선순위는 **FAILED > CANCELLED > COMPLETED** 입니다.
- 상태 집합은 부모와 자식이 다릅니다.

| 테이블 | 상태 |
| --- | --- |
| `qa_run` | `STARTING`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED` |
| `qa_try` | 위 다섯에 `PENDING` 을 더한 여섯 |

- `PENDING` 은 여러 시나리오짜리 런에서 아직 차례가 오지 않은 시나리오입니다. 부모에는 그런 상태가
  없습니다.
- "자식이 전부 끝났나" 를 판정하는 곳은 `QaRunRollupService` 하나이고, "자식 하나가 방금 끝났다" 를
  알리는 자리가 넷입니다 — 정상 종료, SDK 끊김, agent 끊김, 운영자 취소.

## 왜

- 종료 전이가 아예 빠져 있었습니다. 취소든 실패든 정상 완료든 **`qa_try` 만 종단되고 부모 `qa_run` 은
  `STARTING`/`RUNNING` 으로 남았습니다.**
- 재실행 가드가 `qa_run` 을 읽습니다. `createRun` 은 게임 인스턴스로 활성 런과 활성 try 를 둘 다 찾고,
  하나라도 있으면 거절합니다.

```sql
SELECT * FROM qa_run WHERE game_instance_id = :gameInstanceId AND status IN ('STARTING', 'RUNNING')
```

- 그래서 한 번 돌린 런은 **그 게임 인스턴스의 다음 런을 영구 차단했습니다 — 성공한 런조차 그랬습니다.**
- `qa_try` 를 DB 에서 지워도 풀리지 않았습니다. 남아 있는 것은 `qa_run` 이었습니다.
- 차단의 단위는 프로젝트도 게임 빌드도 아니고 **게임 인스턴스 하나**입니다. 한 인스턴스에 활성 런은
  하나라는 규칙이 그 위에 서 있습니다.

## 취소는 활성 try 와 무관하게 세션을 끊는다

- 처음 구현은 활성 `qa_try` 의 `agent_session_id` 로만 agent 세션을 끊었습니다.
- 활성 try 가 없는 창이 둘 있습니다.

| 창 | 무엇이 벌어지나 |
| --- | --- |
| 런이 `STARTING` 이라 try 가 전부 `PENDING` | 끊을 세션 id 를 가진 try 가 아직 없음 |
| 시나리오 N 이 끝나고 N+1 의 첫 프레임이 오기 전 | 그 사이 agent 가 게임을 리셋하고 있어 창이 긺 |

- 그 창에서 취소하면 **DB 만 닫히고 agent 는 다음 시나리오를 계속 돌렸습니다.** 운영자가 보기에는
  종료가 안 먹히는 것이었습니다.
- 세션 id 는 `qa_run` 이 들고 있으므로 그것으로 끊습니다. 이미 끊긴 세션이면 아무 일도 하지 않습니다.
- 진행 중인 런을 이어받는 `force` 도 이때 붙었습니다. 배포로 이 서버가 재시작하면 소켓만 죽고 DB 의 런은
  `RUNNING` 으로 남아 게임을 영구 점유합니다.
  - 기본값이 `false` 인 것이 중요합니다. 남의 런을 끊는 것은 되돌릴 수 없어 요청이 명시적으로 말해야
    합니다.
  - 취소는 기존 `cancelRun` 을 그대로 쓰므로 접근 검사와 인용 확정과 채점을 전부 탑니다.

## 거절한 대안

| 대안 | 왜 거절했나 |
| --- | --- |
| 자식마다 부모를 바로 닫는다 | 시나리오 여럿짜리 런에서 첫 시나리오가 끝나는 순간 런이 닫힘 |
| 재실행 가드가 `qa_try` 만 보게 한다 | 부모가 열린 채 남는 사실 자체는 그대로. 인스턴스 점유를 푸는 것은 가드가 아니라 종료임 |
| 주기적으로 오래된 런을 쓸어 닫는다 | 닫히는 시점이 실제 종료와 무관해지고, 그 사이 인스턴스가 잠김 |
| 운영자가 DB 에서 `qa_run` 을 지운다 | `qa_try.qa_run_id` 가 막아 거절됨. 그리고 지우는 것은 닫는 것이 아님 |
| 종료 사유를 message 산문으로만 구분한다 | 거절 셋이 다 409 라 클라이언트가 산문으로 갈랐고, "시나리오가 없는 테스트 런" 이 "이미 진행 중인 QA" 로 표시됨 |

## 대가

| 대가 | 무엇이 일어나나 |
| --- | --- |
| 자식이 끝날 때마다 형제를 전부 다시 읽음 | 시나리오 수만큼의 조회가 종료마다 한 번 더 붙음 |
| 알리는 자리가 넷임 | 새 종료 경로를 더하는 사람이 롤업 호출을 빠뜨릴 수 있음 |
| 우선순위가 값 하나로 눌림 | 실패 하나와 취소 하나가 섞인 런은 `FAILED` 로만 남음. 무엇이 몇 건인지는 자식을 봐야 함 |
| `qa_try.qa_run_id` 가 CASCADE 가 아님 | 테스트가 `qa_try` 를 먼저 지워야 `qa_run` 을 지울 수 있음 |

**`qa_try.qa_run_id` 를 CASCADE 로 바꾸지 않은 것은 의도입니다.**

- `qa_run` 을 참조하는 외래키 12 개 중 11 개가 `ON DELETE CASCADE` 나 `SET NULL` 이고,
  `qa_try.qa_run_id` 하나만 절이 없어 `NO ACTION` 입니다.
- 운영에는 `qa_run` 을 지우는 코드가 없습니다. 그 절이 지금 건드리는 것은 테스트뿐입니다.
- 바꾸면 실수로 부른 `DELETE FROM qa_run` 이 QA 이력을 조용히 지웁니다. 지금은 거절당합니다.
- 그 대신 테스트는 `qa_try` 를 먼저 지우는 도우미를 씁니다 (`support/QaRunCleanup.kt`). 전체 suite 가
  원래 백 건쯤 깨져 있는 이유의 하나가 이 정리 순서입니다.
