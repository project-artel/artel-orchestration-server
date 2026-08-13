# 2026-08-13 — 지식 쓰기를 멱등하게 만든다

- Date: 2026-08-13
- Jira: ARTEL-364
- Status: Implemented

## Goal

같은 지식 쓰기가 두 번 도착해도 한 번만 적용되고, 두 번째도 성공 응답과 첫 번째와 같은 id를 받는다.
그래야 ARTEL-367의 재시도가 안전해지고, 툴 문구를 "다시 보내지 마라"(순응 의존)에서 "확실치 않으면
다시 보내도 된다"(보장 의존)로 뒤집을 수 있다.

## Non-goals

- Agent의 재시도 — ARTEL-367.
- 툴 문구 뒤집기 — ARTEL-368.
- ARTEL-317의 색인 지연. 이것은 backstop이지 원인 수정이 아니다.
- 기존 중복 행 정리.
- 배치 인입(`KNOWLEDGE`)의 멱등 — 아래 결정 4.

## 결정

### 결정 1 — 프레임 키는 전용 원장 테이블 `qa_knowledge_write`

`issue`의 `uk_issue_message`가 본보기지만 컬럼 방식을 그대로 베낄 수 없다. 이유가 셋이다.

- `knowledge`에 `qa_try_id`가 없다. `source_id`는 "만든 런"이고 DOCS 출처에서는 뜻이 아예 다르다.
- 운영 런의 UPDATE·DELETE는 **새 행을 만들지 않는다.** 컬럼을 둔다면 한 행에 여러 프레임의 이력이
  겹쳐 마지막 것만 남는다.
- LINK·UNLINK는 `knowledge_edge`에 쓴다. 컬럼 방식이면 같은 규칙을 두 테이블에 나눠 달게 된다.

원장 하나면 다섯 타입이 한 규칙을 진다.

### 결정 2 — 원장 삽입은 쓰기와 **같은 트랜잭션**

`issue`와 다른 점이고, 이 원장이 성립하는 조건이다. 저쪽은 `message_id`가 이슈 행 자신에 있어
삽입 충돌이 곧 중복 차단이지만, 여기서는 지식 쓰기가 먼저 일어난다 — 원장이 트랜잭션 밖이면
충돌을 알았을 때 중복은 **이미 만들어져 있다.**

`KnowledgeGraphService`에는 `TransactionalOperator`가 없었다. 링크 저장이 한 문장이라 필요가
없었던 것인데, 원장이 두 번째 쓰기가 되므로 넣었다.

순차 재전송은 쓰기 전 `ledger.previous()` 조회에서 끝난다. 유일 제약까지 가는 것은 동시 재전송뿐이고,
그때는 위반이 쓰기까지 되돌린 뒤 원장을 다시 읽어 먼저 이긴 쪽의 결과로 답한다.

### 결정 3 — 내용 키는 CREATE에만

UPDATE·DELETE·LINK·UNLINK를 내용으로 덮지 않는다. 이미 지워진 항목의 DELETE에 돌아가는
"not found"는 **틀린 답이 아니라 맞는 답이다.** 성공으로 덮으면 "이미 되어 있다"와 "없는 id를
지목했다"가 한 응답으로 뭉개진다. 둘을 가르려면 원장을 의미 축(대상 id + 타입)으로도 조회해야 하고,
그 순간 멱등 장치가 아니라 런 단위 동작 이력이 된다.

CREATE만 예외인 이유는 거기서만 반복이 **조용한 중복 행**이 되기 때문이다.

`content_hash`를 재사용하지 않는다. V13이 그 컬럼을 "멱등키 아님 — 저장만"이라고 명시했고 DOCS
경로가 업로드 파일 해시로 쓴다. 의미를 겹치면 문서 인입과 QA 쓰기가 서로를 막는다.

**`NULLS NOT DISTINCT`가 이 인덱스의 핵심이다.** 운영 스코프는 `scope_id`가 NULL인데 기본 유니크
인덱스는 NULL을 서로 다른 값으로 본다 — 그대로 두면 운영 행끼리는 절대 충돌하지 않아, 정확히
막으려던 케이스만 조용히 빠져나간다. 조회 쪽 `IS NOT DISTINCT FROM`도 같은 이유다. 테스트에
`운영 스코프에서도 내용 키가 실제로 걸린다`를 따로 둔 것이 이 함정을 지키기 위해서다.

### 결정 4 — 배치 인입 `KNOWLEDGE`는 넣지 않는다

ARTEL-331이 그것을 응답 대상에서 뺀 것과 같은 이유다. 기다리는 호출부가 없고, 원장의 한 행이
id 하나를 지는 모양과도 맞지 않는다(배치는 N개다).

### 결정 5 — `applyOnce`를 두 서비스에 따로 둔다

돌려주는 타입(`KnowledgeMutation` / `KnowledgeGraphMutation`)과 원장에서 읽을 id 필드가 다르다.
공용으로 만들면 "재현할 결과", "타입이 어긋났을 때의 결과", "실제 쓰기" 세 람다를 받는 함수가 되고,
그것은 각각 열두 줄인 지금보다 읽기 어렵다. 서로를 가리키는 주석을 남겼다.

## Approach (Checklist)

- [x] **Step 0: Recon** — `issue`/`qa_log`의 멱등 패턴, V13의 `content_hash` 주석, V28의 스코프
      인덱스, PG 버전(pgvector/pgvector:pg16)
- [x] **Step 1: 마이그레이션** — `V37__add_knowledge_write_idempotency.sql`.
      `check-flyway-migrations.sh`가 V36을 ARTEL-329가 이미 가져갔다고 잡아 V37로 옮겼다
- [x] **Step 2: 원장** — `QaKnowledgeWriteEntity`, `QaKnowledgeWriteRepository`,
      `KnowledgeWriteLedger`(+`contentKeyOf`)
- [x] **Step 3: 서비스** — 다섯 진입점이 `messageId`를 받고 `applyOnce`로 감싼다. 원장 삽입을
      각 쓰기 트랜잭션 안에 넣었다
- [x] **Step 4: 라우터** — `envelope.messageId`를 다섯 호출에 넘긴다
- [x] **Step 5: 테스트** — `KnowledgeWriteIdempotencyIntegrationTest`

## Validation

- `./scripts/check-flyway-migrations.sh` — OK (V36 충돌을 잡아 V37로 옮긴 뒤)
- `./scripts/verify-flyway-upgrade.sh` — develop 위에 얹고 validate 통과
- `./mvnw -o test -Dtest=KnowledgeWriteIdempotencyIntegrationTest` — 8/8
- `./mvnw -o test -Dtest="Knowledge*"` — 155/155
- `./mvnw -o test` — 전체

기존 지식 스위트가 **수정 없이** 통과하는 것이 회귀 방어다. 특히 `KnowledgeEventIntegrationTest`는
서비스를 직접 부르므로 `messageId = null` 경로(멱등 없음)를 그대로 탄다.

## Risks & Rollback

- **Risks:**
  - 마이그레이션이 있다. 되돌리려면 revert만으로 부족하고 컬럼·테이블·인덱스를 지우는 후속
    마이그레이션이 필요하다. 다만 추가만 하므로 되돌리지 않아도 기능은 꺼진 채 남는다.
  - `KnowledgeGraphService`에 트랜잭션이 새로 생겼다. 링크 저장이 이제 트랜잭션 경계 안이다 —
    동작은 같지만 실패 모드가 바뀐다(부분 실패가 없어진다).
  - 내용 키가 **너무 세게** 걸릴 수 있다. 같은 문장을 의도적으로 두 번 쓰려던 흐름이 있다면
    막힌다. 그런 흐름을 알지 못하고, tag를 키에 넣어 분류가 다르면 갈라지게 해 뒀다.
- **Rollback steps:** `git revert` + 필요 시 drop 마이그레이션.

## 구현 결과

계획대로 갔다. 계획 밖에서 나온 것은 둘이다.

- **V36이 이미 임자가 있었다.** `check-flyway-migrations.sh`가 ARTEL-329의
  `V36__align_test_case_with_spec_schema.sql`을 찾아냈다. 워킹 트리만 봐서는 안 보이는 종류이고,
  스킬이 그 스텝을 왜 필수로 두는지가 여기서 드러났다.
- **`KnowledgeGraphService`에 트랜잭션이 없었다.** 결정 2를 지키려면 넣어야 했다. 원래 쓰기가
  한 문장이라 없었던 것이고, 두 번째 쓰기가 생긴 지금은 있어야 한다.

## Open Questions

- 없음.
