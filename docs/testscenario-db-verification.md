# TestScenario DB 저장 — 수동 검증 플로우

Agent WebSocket으로 수신한 시나리오(SCENARIO_STEP)가 R2DBC를 통해 PostgreSQL의
`test_scenario` 테이블에 저장되는지 로컬에서 직접 확인하는 절차.

## 구성 요소

```
[curl/Postman (FE 역할)] --HTTP POST /message--> [App :8080] --WS--> [Mock Agent :8000]
       ▲                                            │                      │
       └────── SSE /stream ─────────────────────────┘   ◀── SCENARIO_STEP ─┘
                                                     │
                                                     └── R2DBC save ──> [PostgreSQL :5432]
```

- App → Agent: `ws://localhost:8000/testscenario?clientId=...` (`.env`의 `ARTEL_AGENT_WS_BASE_URL`로 변경 가능)
- App → DB: R2DBC (`spring.r2dbc.url`), Flyway 마이그레이션은 JDBC로 실행

## 사전 준비

### 1. PostgreSQL (Docker)
```bash
docker run --name artel-pg \
  -e POSTGRES_DB=postgres -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 -d postgres:16
```
`.env`가 이 값과 일치하는지 확인(`DB_HOST=localhost / DB_PORT=5432 / DB_NAME=postgres / postgres/postgres`).

### 2. 목(mock) Agent WebSocket 서버
실제 Agent 서버가 없을 때 사용. `mock_agent.py`:
```python
import asyncio, json, websockets
from urllib.parse import urlparse, parse_qs

async def handler(ws):
    q = parse_qs(urlparse(ws.request.path).query)
    print(f"[mock-agent] 접속: clientId={q.get('clientId', ['?'])[0]}", flush=True)
    async for msg in ws:
        print(f"[mock-agent] 수신: {msg}", flush=True)
        await ws.send(json.dumps({
            "type": "SCENARIO_STEP",
            "agentSessionId": "agent-sid-verify-1",
            "payload": {"steps": [
                {"order": 1, "description": "로그인 페이지로 이동"},
                {"order": 2, "description": "이메일/비밀번호 입력 후 로그인"},
            ]},
        }, ensure_ascii=False))

async def main():
    async with websockets.serve(handler, "localhost", 8000):
        print("[mock-agent] ws://localhost:8000/testscenario", flush=True)
        await asyncio.Future()

asyncio.run(main())
```
실행:
```bash
python3 -m venv venv && ./venv/bin/pip install websockets
./venv/bin/python mock_agent.py
```

### 3. 앱 실행
```bash
./mvnw spring-boot:run
```
부팅 로그 확인:
- `Found 1 R2DBC repository interface`
- `Migrating schema "public" to version "2 - create test scenario"` → `Successfully applied 2 migrations`
- `Netty started on port 8080`

## 검증 절차

`clientId`는 아무 값이나 정하되 SSE와 POST에서 동일하게 사용(예: `verify-client-1`).

### Step 1 — SSE 스트림 먼저 구독
```bash
curl -N -H "Accept: text/event-stream" \
  http://localhost:8080/api/test-scenario/verify-client-1/stream
```
> ⚠️ 반드시 POST보다 **먼저** 열 것. 스트림이 없을 때 도착한 이벤트는 드롭된다.
> Postman에서는 이 요청이 "무한 로딩"으로 보이는 게 정상(연결을 열고 이벤트 대기 중).

### Step 2 — (다른 터미널/탭) 메시지 전송
```bash
curl -s -X POST http://localhost:8080/api/test-scenario/verify-client-1/message \
  -H "Content-Type: application/json" \
  -d '{"type":"USER_MESSAGE","testScenarioMessage":"로그인 시나리오 만들어줘"}'
# → 200 "메시지 전송 완료"
```

### Step 3 — 결과 확인

**(a) SSE 스트림**(Step 1)에 이벤트 도착:
```
event:SCENARIO_STEP
data:{"steps":[{"order":1,"description":"로그인 페이지로 이동"}, ...]}
```

**(b) DB 저장 확인:**
```bash
docker exec artel-pg psql -U postgres -c \
  "SELECT id, client_id, agent_session_id, left(payload,60) AS payload, created_at, updated_at FROM test_scenario;"
```
기대: 1 row, `client_id=verify-client-1`, `agent_session_id=agent-sid-verify-1`, payload에 steps, created/updated 채워짐.

**(c) 앱 로그:** `시나리오 저장 완료 [clientId=verify-client-1, id=1]`

### Step 4 — Upsert & 세션 재사용 확인 (선택)
같은 clientId로 한 번 더 전송:
```bash
curl -s -X POST http://localhost:8080/api/test-scenario/verify-client-1/message \
  -H "Content-Type: application/json" \
  -d '{"type":"USER_MESSAGE","testScenarioMessage":"다음 단계도 추가"}'
```
- 목 Agent 수신 로그에 `"agentSessionId":"agent-sid-verify-1"` 가 실려야 함(첫 턴 null → 매핑 재사용).
- DB는 여전히 **1 row**, `updated_at`만 갱신됨(upsert, `UNIQUE(client_id)`).

## 정리(cleanup)
```bash
lsof -ti:8080 | xargs kill   # 앱
lsof -ti:8000 | xargs kill   # 목 Agent
docker rm -f artel-pg
```

## 참고: 실제 Agent 서버로 붙일 때
`.env`에 `ARTEL_AGENT_WS_BASE_URL=ws://<agent-host>:<port>` 추가.
Agent는 `/testscenario?clientId=...` 로 접속을 받고, 응답은 `{ type, agentSessionId?, payload }`
형식이어야 한다(계약: `docs/testscenario-agent-ws-contract.md`).
