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
  docker run -d --name redis --restart unless-stopped --network app-net redis:7-alpine
  ```

  Redis 6.2 or newer is required — the code exchange uses `GETDEL`. Persistence stays
  off on purpose: the only thing stored is a five-minute one-time code, so losing it on
  restart just means the user clicks sign-in again, while writing it to disk would leak
  live code hashes into backups.
- **The already-registered `-stage` and `-operation` Secret files must be re-uploaded
  with `REDIS_HOST` and `REDIS_PORT` before the image that needs them is deployed.**
  Adding the keys to `.env.example` does not touch a credential that already exists, and
  "Registering a Secret file" above only runs when adding a new environment. Miss this
  and `REDIS_HOST` falls back to `localhost`, which inside the container is the app
  itself — the container starts healthy and only SDK login breaks.

Order matters: start Redis, re-upload the Secret files, then deploy. Lettuce connects
lazily, so skipping either of the first two steps still produces a container that boots
normally and fails only on SDK login.
