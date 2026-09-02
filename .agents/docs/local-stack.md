# Local Stack

## Why

A missing dependency does not show up as a startup failure. The server comes up fine and
**only certain features return 500**. That costs time, because you cannot tell whether the cause is
your own code or the environment. This actually happened: with Redis left out,
`POST /api/auth/sdk/codes` returned 500 five times in a row, and there was no way to know why SDK
onboarding was broken until someone opened the server log (found while working on ARTEL-435).

## Required

Without these two you get a startup failure and a feature failure, respectively.

| | Container name | Image | Port |
|---|---|---|---|
| PostgreSQL | `artel-local-postgres` | `pgvector/pgvector:pg16` | 5432 |
| Redis | `artel-local-redis` | `redis:7-alpine` | 6379 |

**Postgres must be the pgvector image.** `V18` runs `CREATE EXTENSION vector`, so on the stock
`postgres` image the migration fails wholesale from that point on. This is also why
`PostgresTestContainer` and `scripts/verify-flyway-upgrade.sh` use the same image.

**Redis backs the SDK login code store** (`auth/sdk/SdkLoginCodeStore.kt`). Without it the server
starts normally and most APIs work, but `POST /api/auth/sdk/codes` alone returns 500 with
`RedisConnectionFailureException`. **SDK onboarding goes through that path, so turn Redis on
whenever you plan to check something with Unity attached.**

The names are fixed for one reason — so that a container you already created is not created again.
Check with `docker ps -a | grep artel-local` first, and bring back what is there with `docker start`.

```bash
docker start artel-local-postgres artel-local-redis
```

Create them only when they are missing. The values must match `DB_NAME`, `DB_USERNAME` and
`DB_PASSWORD` in `.env`.

```bash
docker run -d --name artel-local-postgres -p 5432:5432 \
  -e POSTGRES_DB=artel -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=<DB_PASSWORD from .env> \
  pgvector/pgvector:pg16
docker run -d --name artel-local-redis -p 6379:6379 redis:7-alpine
```

## Starting the server

You need a `.env` (see `.env.example`). Flyway brings the schema up to the latest version at startup.

```bash
./mvnw spring-boot:run
```

- Public API and WebSocket — `http://localhost:8080`
- Internal API (`/internal/**`) — `http://localhost:8081`. It is not on the public port (see
  `API 표면과 신뢰 경계`)

Startup is complete when the log prints `Started ArtelOrchestrationApplicationKt`. Just before it,
`Successfully applied N migrations` shows up as well.

## Attaching a frontend — CORS origin

**Without `ARTEL_ALLOWED_ORIGINS` the frontend cannot call the server at all.** The server comes up
fine and `curl` works for everything; only the browser is blocked, like this:

```
Access to fetch at 'http://localhost:8080/api/auth/me' from origin 'http://localhost:5174'
has been blocked by CORS policy: No 'Access-Control-Allow-Origin' header is present
```

The reason is that the `artel.auth.allowed-origins` default in `application.yml` holds only the four
deployment domains (`home.stage.artel.kr`, `artel.kr`, `www.artel.kr`, `admin.artel.kr`) and no
`localhost`. That default is used as is in deployments, so do not mix `localhost` into it — override
it locally through `.env`.

```bash
ARTEL_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:5174
```

5173 is artel-home and 5174 is admin-page. If you are only running one of them, listing that one is
enough. `allowCredentials=true` means `*` cannot be used
(`SecurityConfig.corsConfigurationSource`).

## Running on a different port

If 8080 is already taken by a server from another branch, move the port. The internal API port has
to move with it, or `/internal/**` will not come up.

```bash
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Dserver.port=8090 -Dartel.internal-api.port=8091"
```

Two more things have to change along with it.

- **`VITE_ORCHESTRATION_URL` on the frontend side.** Both artel-home and admin-page look at
  `http://localhost:8080` by default. Start them with
  `VITE_ORCHESTRATION_URL=http://localhost:8090 npm run dev`
- **The database.** Migration numbers differ per branch, so if a server from another branch is
  looking at the same database, this side's Flyway will migrate the schema out from under that
  server. Create a separate one with `CREATE DATABASE artel_<branch>` and point `DB_NAME` in `.env`
  at it

## Optional — depends on what you are checking

The server starts without these. If you are not touching the corresponding feature, there is no need
to turn them on.

| | When it is needed | Default address |
|---|---|---|
| artel-home | Checking the frontend visually | `http://localhost:5173` (`npm run dev`) |
| artel-agent-server | QA runs, knowledge extraction, embedding paths | `http://localhost:8000` (`artel.agent.base-url`) |
| S3 / MinIO | Design document uploads, screen capture storage | `ARTEL_S3_ENDPOINT` in `.env` |
| TURN | WebRTC streaming | `ARTEL_TURN_URL`. Empty means it is off |

artel-home looks at `http://localhost:8080` by default (`VITE_ORCHESTRATION_URL` in
`src/auth/authApi.ts`). For how to run each repository, follow that repository's own documentation —
it is not duplicated here.

## Shutting down

```bash
docker stop artel-local-postgres artel-local-redis
```

`stop`, not `rm`. If you delete them, the schema has to be built again from scratch next time.
