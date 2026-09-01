# Deployment Environment Variables

The Jenkins pipeline deploys `artel-orchestration-server` as a Docker container. The
application's environment variables come from a `.env` file registered in Jenkins as a
**Secret file** credential. The pipeline reads it at deploy time and passes it to
`docker run --env-file`, so no secret is ever committed to this repository or baked into
an image layer.

Locally the same variables come from a `.env` in the working directory, loaded by
`me.paulschwarz:spring-dotenv`. `.env` is gitignored, so it exists only on a developer
machine — never in the built image. In the container the values arrive as real process
environment variables instead, and Spring resolves them directly. The two paths are not
strictly equivalent: `spring-dotenv` populates only the Spring `Environment`, while
`--env-file` sets real process variables that any library reading the process
environment — the AWS SDK's default credential provider chain, for one — can also see.

`SPRING_PROFILES_ACTIVE` is the one variable the pipeline sets itself, derived from the
branch rather than from the secret file. Do not put it in the `.env`; see the format
rules below.

## Ports

The application listens on **two** ports, and which port an endpoint answers on is the
trust boundary (ARTEL-266).

| Port | Env var | Serves | Reachable from |
|---|---|---|---|
| 8080 | `SERVER_PORT` | `/api/**`, `/oauth2/**`, `/login/oauth2/**`, `/ws/**`, Swagger | the internet, through Nginx Proxy Manager |
| 8081 | `ARTEL_INTERNAL_API_PORT` | `/internal/**` only | `app-net` containers only |

The split is enforced in the application: 8080 answers `404` for `/internal/**`, and the
internal port answers `404` for everything else. Two `HttpHandler` chains are assembled
from the same application context, and a request is routed by *which server accepted the
connection* — see `config/InternalApiConfig.kt`. A `404` rather than a `401` is
deliberate: `401` would reveal that the endpoint exists and only needs credentials.

`/internal/**` is unauthenticated by design — the callers are servers, not people, and
they hold no end-user JWT. That is safe only while the port stays off the public network.

### Never publish the internal port

**Do not add `-p` to the `docker run` in `Jenkinsfile`, and do not point the reverse proxy
at 8081.** The container joins `app-net` and publishes nothing to the host, so the
internal port is reachable only from sibling containers. That absence is the actual
control — the application-level split makes the boundary reviewable, but it cannot stop a
port mapping someone adds later.

`EXPOSE 8080 8081` in the `Dockerfile` is documentation only. It does not publish
anything, and it is not what keeps 8081 private.

Nginx Proxy Manager needs no change, but note what it is: the proxy sits on `app-net`
too — it has to, in order to reach 8080 at all — so it is the one container that can
*both* reach 8081 and accept traffic from the internet. Nothing stops it at the network
layer. The guarantee is that it is not **configured** to route there, and must not be.
What ARTEL-266 removes is the need for a `/internal/` deny rule on the public host: that
path no longer exists on 8080, so a mistake in the proxy's public-host configuration can
no longer expose it.

### Deployment checklist

Internal callers reach this server by container name and internal port, so they never
traverse the proxy. Getting there takes one coordinated window:

1. Update `ORCHESTRATION_BASE_URL` in the stage/operation `.env` to the `app-net` address
   **including the internal port** — `http://artel-orchestration-server-<env>:8081` — and
   re-upload the Secret file (see "Registering a Secret file").
2. Deploy orchestration (ARTEL-265 + ARTEL-266 together).
3. Deploy agent-server with **ARTEL-267**, which changes its `USAGE_PATH` to
   `/internal/llm-usage`. Without it the old path 404s no matter what the URL says.
4. Confirm `docker port <container>` prints **nothing**. If 8081 appears, stop — the
   internal API is on the host network.
5. Confirm the public host does not serve internal paths:

   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' \
     'https://stage-orch.artel.kr/internal/knowledge?projectId=1'   # expect 404
   ```

6. From another `app-net` container, confirm the internal port does:

   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' \
     'http://artel-orchestration-server-<env>:8081/internal/knowledge?projectId=1'  # expect 200
   curl -s -o /dev/null -w '%{http_code}\n' \
     'http://artel-orchestration-server-<env>:8081/api/projects'                    # expect 404
   ```

   Use this read-only path rather than `/internal/llm-usage`, which is POST-only and
   would answer `405` to a probe. **Treat the deploy as complete only after step 6
   passes** — waiting on row counts finds the failure far later.
7. Confirm new rows appear in `llm_usage`.

Steps 1 and 3 are the ones that fail silently. Miss either and the container looks
healthy while usage reporting dies: `usage.py` buffers without retrying, so whatever it
sends during the gap is lost. Nothing else in agent-server calls this server, which is
why one variable plus one deploy covers it.

## CORS allowed origins

The browser origins allowed to call this server come from two places, and they are added
together rather than one overriding the other:

- `AuthProperties.FIRST_PARTY_ORIGINS` — `https://artel.kr`, `https://www.artel.kr`, and
  `https://admin.artel.kr`, held in code
- `ARTEL_ALLOWED_ORIGINS` in the `.env`, plus `ARTEL_HOME_URL`

**`ARTEL_ALLOWED_ORIGINS` adds; it never replaces.** Leave it unset unless a deployment
needs an origin the code does not know, such as a preview host. Wildcard patterns are
accepted there (`https://*.example.com`), because the value is applied as
`allowedOriginPatterns` — `allowedOrigins` cannot hold a wildcard while
`allowCredentials` is true.

The split exists because the earlier arrangement broke admin-page twice. The list was once
the `.env` value alone, so a Secret file naming one origin dropped every host the code knew
about. ARTEL-295 answered the first outage by adding `https://admin.artel.kr` to the
`application.yml` default; the stage `.env` overrode that default, and admin-page broke the
same way again (ARTEL-702). A missing origin shows up only in a browser, as a bodyless 403
with no `Access-Control-Allow-Origin` header, and the frontend reads that 403 as a login
boundary — so the report that reaches you is "I log in and it asks me to log in again", not
a CORS error.

A frontend that differs per deployment does not belong in `FIRST_PARTY_ORIGINS`. Stage's
`https://home.stage.artel.kr` is allowed because it is that deployment's `ARTEL_HOME_URL`;
listing it in code would open it on operation as well.

Adding a new artel.kr host means adding a line to `FIRST_PARTY_ORIGINS`, not to a Secret
file. Retiring one means deleting that line — an origin left in the list after its host is
gone lets whoever registers that name next call this server with credentials.

## Credential IDs

The pipeline derives the credential ID as `${APP_NAME}-env-${TARGET_ENV}`.

| Branch | `TARGET_ENV` | Credential ID |
|---|---|---|
| `main`, `operation` | `operation` | `artel-orchestration-server-env-operation` |
| `develop`, `stage` | `stage` | `artel-orchestration-server-env-stage` |

Because the ID is composed rather than hardcoded, `Jenkinsfile` has no per-environment
branching for credential lookup. The branch-to-environment mapping still lives in
`resolveTargetEnv` at the bottom of `Jenkinsfile`.

A missing credential fails the build at `withCredentials`. The deploy never silently
proceeds with an unconfigured container.

## Registering a Secret file

1. Copy `.env.example`.
2. Replace every placeholder with the real value for that environment. Section comment
   lines can stay — see the format rules below.
3. Verify the file has no quotes, no `export` prefix, and no inline comments.
4. Check how Docker actually parses it:

   ```bash
   docker run --rm --env-file <your-file> alpine env
   ```

   Read the output and confirm each value is exactly what you intended. This step is the
   real safeguard; the format hazards below are invisible otherwise. Run it on your own
   machine — it prints every secret in plain text, so never run it inside a CI job.
5. In Jenkins, go to **Manage Jenkins → Credentials**, add a credential of kind
   **Secret file**, upload the file, and set the ID from the table above. Either global
   or folder scope works — the pipeline references the ID only.

The uploaded filename does not affect behavior. Jenkins copies the file to a temporary
path per build — keeping the original name — and binds that path to `$ENV_FILE`. Naming
the uploads `stage.env` and `operation.env` only helps humans tell them apart in the
Jenkins UI and in build logs.

## `--env-file` format rules

`docker` parses this file itself, and its rules are narrower than a shell's. It is not
a shell script and is never sourced.

| Input | Result |
|---|---|
| `KEY=value` | Value is `value`. |
| `# section header` | Ignored. Whole-line comments are fine. |
| *(blank line)* | Ignored. |
| `KEY="value"` | Value is `"value"` — quotes included. **Silent corruption.** |
| `KEY=value # note` | Value is `value # note`. **Silent corruption.** |
| `export KEY=value` | Build fails: `variable 'export KEY' contains whitespaces`. |
| `KEY` (no `=`) | Passes through the Jenkins agent's own `KEY`. Never use this. |
| Value containing a newline | Cannot be expressed. |
| `SPRING_PROFILES_ACTIVE=…` | Ignored. The pipeline passes it with `-e`, and Docker applies `-e` after the env file regardless of flag order, so `-e` always wins. **Silently** a no-op. |

The two silent cases are why step 4 above exists. Docker emits no warning; the container
starts and then behaves incorrectly.

## Secrets in the build log

The `file()` credential binding does **not** mask the file's contents. Never `cat`,
`echo`, or otherwise print `$ENV_FILE` from a pipeline step.

The file's path does appear in the log, because Jenkins runs `sh` steps under `sh -xe`
and xtrace prints the expanded command. Keeping the path out of the visible log depends
entirely on the credentials-binding console filter, not on how the step is quoted.

## Adding a new environment

1. Add the branch-to-environment mapping in `resolveTargetEnv` in `Jenkinsfile`.
2. Add the same branch to the `when { anyOf { ... } }` guard on the `Deploy Pipeline`
   stage. The guard is what keeps PR and feature-branch builds out of deployment, so a
   branch missing from it is built but never deployed.
3. Register a Secret file credential named `artel-orchestration-server-env-<new-env>`.

No other pipeline change is needed.

## Prerequisites

- The Credentials Binding plugin must be installed on the Jenkins instance. It ships
  with a default install, but `withCredentials` is unresolvable without it.
- Both credentials above must exist before a deploy runs on that branch.
- A Redis container must be running on `app-net`. It stores the one-time SDK login
  codes, so without it every SDK sign-in fails:

  ```bash
  docker run -d --name artel-redis --restart unless-stopped --network app-net redis:7-alpine
  ```

  Redis 6.2 or newer is required — the code exchange uses `GETDEL`. Persistence stays
  off on purpose: the only thing stored is a five-minute one-time code, so losing it on
  restart just means the user clicks sign-in again, while writing it to disk would leak
  live code hashes into backups.
- **The already-registered `-stage` and `-operation` Secret files must be re-uploaded
  with `REDIS_URL=redis://artel-redis:6379` before the image that needs them is
  deployed.** Adding the key to `.env.example` does not touch a credential that already
  exists, and "Registering a Secret file" above only runs when adding a new environment.
  Miss this and `REDIS_URL` falls back to `redis://localhost:6379`, which inside the
  container is the app itself — the container starts healthy and only SDK login breaks.

Order matters: start Redis, re-upload the Secret files, then deploy. Lettuce connects
lazily, so skipping either of the first two steps still produces a container that boots
normally and fails only on SDK login.
