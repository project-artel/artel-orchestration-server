# Local Stack

## Why

빠뜨린 의존성은 기동 실패로 나타나지 않는다. 서버는 멀쩡히 뜨고 **특정 기능만 500**을 낸다.
그래서 원인이 자기 코드인지 환경인지 구분하는 데 시간이 든다. 실제로 Redis를 빼고 띄웠다가
`POST /api/auth/sdk/codes`가 다섯 번 연속 500을 냈고, 서버 로그를 열기 전까지는 SDK 온보딩이
왜 안 되는지 알 수 없었다(ARTEL-435 확인 중).

## 필수

이 둘이 없으면 각각 기동 실패와 기능 실패가 난다.

| | 컨테이너 이름 | 이미지 | 포트 |
|---|---|---|---|
| PostgreSQL | `artel-local-postgres` | `pgvector/pgvector:pg16` | 5432 |
| Redis | `artel-local-redis` | `redis:7-alpine` | 6379 |

**Postgres는 pgvector 이미지여야 한다.** `V18`이 `CREATE EXTENSION vector`를 실행하므로 stock
`postgres` 이미지에서는 마이그레이션이 그 지점부터 통째로 실패한다. `PostgresTestContainer`와
`scripts/verify-flyway-upgrade.sh`가 같은 이미지를 쓰는 이유도 이것이다.

**Redis는 SDK 로그인 코드 저장소**(`auth/sdk/SdkLoginCodeStore.kt`)가 쓴다. 없으면 서버는
정상 기동하고 대부분의 API도 정상인데, `POST /api/auth/sdk/codes`만
`RedisConnectionFailureException`으로 500이 된다. **SDK 온보딩이 이 경로를 지나므로, Unity를
붙여 확인할 계획이면 반드시 켠다.**

이름이 정해진 이유는 하나다 — 이미 만들어 둔 컨테이너를 다시 만들지 않기 위해서다. 먼저
`docker ps -a | grep artel-local`로 확인하고, 있으면 `docker start`로 되살린다.

```bash
docker start artel-local-postgres artel-local-redis
```

없을 때만 만든다. 값은 `.env`의 `DB_NAME`·`DB_USERNAME`·`DB_PASSWORD`와 같아야 한다.

```bash
docker run -d --name artel-local-postgres -p 5432:5432 \
  -e POSTGRES_DB=artel -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=<.env의 DB_PASSWORD> \
  pgvector/pgvector:pg16
docker run -d --name artel-local-redis -p 6379:6379 redis:7-alpine
```

## 서버 기동

`.env`가 있어야 한다(`.env.example` 참고). Flyway가 기동 시 스키마를 최신까지 올린다.

```bash
./mvnw spring-boot:run
```

- 공개 API·WebSocket — `http://localhost:8080`
- 내부 API(`/internal/**`) — `http://localhost:8081`. 공개 포트에는 없다(`API 표면과 신뢰 경계` 참고)

기동 완료는 로그의 `Started ArtelOrchestrationApplicationKt`로 확인한다. 그 앞에
`Successfully applied N migrations`가 함께 나온다.

## 화면을 붙일 때 — CORS origin

**`ARTEL_ALLOWED_ORIGINS` 를 안 주면 화면이 서버를 하나도 못 부른다.** 서버는 멀쩡히 뜨고
`curl` 도 다 되는데 브라우저만 이렇게 막는다.

```
Access to fetch at 'http://localhost:8080/api/auth/me' from origin 'http://localhost:5174'
has been blocked by CORS policy: No 'Access-Control-Allow-Origin' header is present
```

`application.yml` 의 `artel.auth.allowed-origins` 기본값이 배포 도메인 넷(`home.stage.artel.kr`,
`artel.kr`, `www.artel.kr`, `admin.artel.kr`)뿐이고 `localhost` 가 없기 때문이다. 그 기본값은
배포에서 그대로 쓰이므로 거기에 `localhost` 를 섞지 않는다 — 로컬은 `.env` 로 덮는다.

```bash
ARTEL_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:5174
```

5173 은 artel-home, 5174 는 admin-page 다. 둘 중 하나만 띄울 것이면 그것만 적어도 된다.
`allowCredentials=true` 라 `*` 는 쓸 수 없다(`SecurityConfig.corsConfigurationSource`).

## 포트를 옮겨 띄울 때

8080 이 이미 다른 브랜치의 서버에 잡혀 있으면 포트를 옮긴다. 내부 API 포트도 함께 옮겨야
`/internal/**` 이 뜬다.

```bash
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Dserver.port=8090 -Dartel.internal-api.port=8091"
```

이때 함께 바꿀 것이 둘 있다.

- **화면 쪽 `VITE_ORCHESTRATION_URL`.** artel-home 과 admin-page 둘 다 `http://localhost:8080` 을
  기본으로 본다. `VITE_ORCHESTRATION_URL=http://localhost:8090 npm run dev` 로 띄운다
- **DB.** 브랜치마다 마이그레이션 번호가 다르므로, 다른 브랜치의 서버가 같은 DB 를 보고 있으면
  이쪽 Flyway 가 그 서버 밑에서 스키마를 올려 버린다. `CREATE DATABASE artel_<브랜치>` 로 따로
  만들고 `.env` 의 `DB_NAME` 을 그것으로 둔다

## 선택 — 무엇을 확인하느냐에 달렸다

없어도 서버는 뜬다. 해당 기능을 건드리지 않으면 켤 필요가 없다.

| | 필요한 때 | 기본 주소 |
|---|---|---|
| artel-home | 화면을 눈으로 확인할 때 | `http://localhost:5173` (`npm run dev`) |
| artel-agent-server | QA 실행·지식 추출·임베딩 경로 | `http://localhost:8000` (`artel.agent.base-url`) |
| S3 / MinIO | 기획서 업로드, 캡처 저장 | `.env`의 `ARTEL_S3_ENDPOINT` |
| TURN | WebRTC 스트리밍 | `ARTEL_TURN_URL`. 비어 있으면 끈 것이다 |

artel-home은 기본으로 `http://localhost:8080`을 본다(`src/auth/authApi.ts`의
`VITE_ORCHESTRATION_URL`). 각 레포의 실행법은 그쪽 문서를 따른다 — 여기 복제하지 않는다.

## 정리

```bash
docker stop artel-local-postgres artel-local-redis
```

`stop`이지 `rm`이 아니다. 지우면 다음에 스키마를 처음부터 다시 올려야 한다.
