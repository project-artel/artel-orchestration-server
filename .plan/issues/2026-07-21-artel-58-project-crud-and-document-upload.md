# 2026-07-21 — ARTEL-58 Project CRUD and Planning-Document Upload

- Date: 2026-07-21
- Jira Issue: ARTEL-58
- Status: Draft
- Repository: `artel-orchestration-server`
- Work Type: `feat`
- Branch: `ARTEL-58-프로젝트-crud-및-project-plan-s-3-저장-구현`
  (rebased onto `develop` @ `9556dfc` on 2026-07-21, after ARTEL-44 merged)
- Client counterpart: `artel-home` ARTEL-66 (project creation and editing UI)

## Goal

Let an authenticated user own projects. A project carries a name, a description,
and a genre, and accumulates versioned planning documents (기획서) stored in S3.
The server owns the metadata and the object lifecycle; **file bytes never pass
through the orchestration server** — the client uploads directly to S3 with a
presigned URL and the server records the result. Every QA session, run, and bug
report will eventually hang off a project, so this schema and this ownership
rule are the foundation for everything after it.

## Non-goals

- Project deletion, archiving, or soft-delete. Nothing depends on removal yet.
- Sharing, membership, roles, or any authorization model beyond single-owner.
- Parsing, indexing, or feeding the planning document into the agent pipeline.
  This issue stores and serves the file; interpreting it is separate work.
- Pagination, sorting, or search on the project list.
- Migrating the orphaned `sdk_session_log` / `action_execution_log` tables into
  entities.

## Context / Constraints

Verified against this worktree after the rebase, not assumed:

- **The R2DBC convention already exists — follow it, do not invent one.**
  ARTEL-44 (PR #16, merged as `9556dfc`) converted the auth layer to R2DBC in
  commit `0df3ab5`, so `auth/` is now the reference implementation for every
  point below. No JPA remains anywhere: `jakarta.persistence`, `JpaRepository`,
  and the `boundedElastic()` bridges are all gone.
  - Entities are `data class` with `@Table("app_user")`, `@Id val id: Long? = null`,
    and an **explicit `@Column("...")` on every field** — including fields whose
    derived snake_case name would already be correct. Match that, even though it
    is more verbose than strictly required.
  - Repositories are bare `ReactiveCrudRepository<E, Long>` with derived queries
    returning `Mono`/`Flux` and Korean KDoc on the non-obvious ones.
  - R2DBC has no relation mapping, so a foreign key is a plain column value
    (`val appUserId: Long`) plus a second query — `OAuthIdentityEntity`
    documents this decision in its KDoc.
  - `@Transactional` (and `@Transactional(readOnly = true)`) on `Mono`-returning
    service methods works and is already in use in `OAuthUserService`.
  - Timestamps are set explicitly from an injected `Clock` (`Instant.now(clock)`),
    not left to the SQL `DEFAULT`.
- **The codebase is Reactor, not coroutines.** Zero `suspend` functions, and
  `kotlinx-coroutines` is not a dependency. Introducing `CoroutineCrudRepository`
  would make this feature the only coroutine island in the repo and is
  explicitly rejected.
- **Auth is in place.** `SessionUserResolver`, the `app_user` table, and
  `anyExchange().authenticated()` all exist now, so ownership is enforceable and
  `/api/projects/**` is protected the moment it is added. The sequencing gate
  that blocked this plan is resolved.
- **Flyway: `V3` is confirmed free.** `db/migration/` holds exactly
  `V1__init_schema.sql` and `V2__create_app_user_and_oauth_identity.sql`.
- **Reactive transactions do not roll back in tests.** A reactive transaction is
  bound to the subscription context, not the thread, so `@Transactional` on a
  test method does nothing. `OAuthUserServiceIntegrationTest` documents this and
  clears state itself with `@BeforeEach { repository.deleteAll().block() }`
  against the shared in-memory H2. New tests must do the same.
- **No object storage exists.** No AWS SDK, no MinIO, no multipart handling, no
  storage abstraction, no bucket configuration. All of it is greenfield.
- **No validation and no centralized error handling exist.**
  `spring-boot-starter-validation` is not a dependency and there is no
  `@RestControllerAdvice` anywhere. Those two remain first-of-their-kind here.
- Schema house style (`V1`/`V2` + the flyway skill): `snake_case`,
  `BIGSERIAL`/`BIGINT` primary keys — never UUID, `TIMESTAMP WITH TIME ZONE
  NOT NULL DEFAULT CURRENT_TIMESTAMP`, `IF NOT EXISTS` on tables and indexes,
  `uk_<table>_<name>` for unique constraints, and `idx_<table>_<column>` **without**
  an `_id` suffix (`idx_oauth_identity_app_user`, not `..._app_user_id`).
  SQL comments are Korean.
- API paths are kebab-case under `/api` since commit `656920c`
  (`api/testscenario` → `api/test-scenario`), so: `/api/projects`.
- Tests share one in-memory H2 instance (`DB_CLOSE_DELAY=-1`) migrated by
  Flyway-over-JDBC and read by the app over R2DBC. `DatabaseConnectionTest`
  documents that the DB is shared and asserts `count >= 0` rather than exact
  counts — new repository tests must not assume an empty table.

## Architecture

1. A request arrives at `ProjectController` with `@AuthenticationPrincipal jwt: Jwt`.
   The controller calls `SessionUserResolver.resolve(jwt)` to obtain
   `SessionUser(userId: Long)` — the exact pattern `AuthController.me` uses. No
   `@CurrentUser` argument resolver is introduced here; see Open Questions.
2. `ProjectService` is the only place that knows the ownership rule. Every read
   and every write is scoped by `ownerId`, at the query level rather than by
   post-filtering, so a missing scope cannot silently leak rows.
3. `ProjectRepository : ReactiveCrudRepository<ProjectEntity, Long>` and
   `ProjectDocumentRepository : ReactiveCrudRepository<ProjectDocumentEntity, Long>`
   provide derived queries (`findAllByOwnerIdOrderByUpdatedAtDesc`,
   `findByIdAndOwnerId`, `findAllByProjectIdOrderByVersionDesc`) plus one
   `@Query` for the version high-water mark.
4. Entities copy `AppUserEntity` exactly: `data class`, `@Table`, `@Id val id:
   Long? = null`, explicit `@Column` per field. With a DB-generated `BIGSERIAL`
   key, a null id makes `save()` insert and a non-null id makes it update, so
   `Persistable` is not needed. `createdAt` / `updatedAt` come from the injected
   `Clock`, matching `OAuthUserService`.
5. Upload is a three-call presigned flow:
   1. `POST /api/projects/{id}/documents/upload-url` — verify ownership, require
      `application/pdf` with a `.pdf` file name and a declared size in
      `(0, 50 MB]`, mint an object key, and return a presigned `PUT` URL with
      `Content-Type` **bound into the signature**, so S3 itself rejects a PUT
      that declares anything else. `S3Presigner` performs no network I/O
      (signing is local HMAC), so calling it on the event loop is safe.
   2. The client `PUT`s the bytes straight to S3. The server is not involved.
   3. `POST /api/projects/{id}/documents` — `S3AsyncClient.headObject` confirms
      the object exists and that its real byte count is within the limit, then
      the next version number is allocated and the row is inserted. **The
      document does not exist until this call succeeds.**
   - Note that `headObject` proves nothing about the *content*: S3 stores and
     echoes whatever `Content-Type` the client sent, which the signature already
     pinned. The only real format check is the file's own magic number, so the
     register step also issues a `GetObject` with `Range: bytes=0-4` and requires
     `%PDF-`. Five bytes is cheap enough to make this unconditional.
6. Because the transfer is presigned, the server needs no multipart handling and
   no `spring.codec.multipart.*` tuning, and a large 기획서 never occupies server
   memory or an event-loop thread.
7. `S3DocumentStorage` implements a narrow `DocumentStorage` port
   (`presignUpload`, `presignDownload`, `head`, `delete`). Tests bind a fake
   implementation, which is why no S3 mock server or Testcontainer is required.
8. Downloads are never permanent links: `GET .../download-url` mints a
   short-lived presigned `GET` on each request, after re-checking ownership.

## Schema — `V3__create_project_and_project_document.sql`

```sql
CREATE TABLE IF NOT EXISTS project (
    id          BIGSERIAL PRIMARY KEY,
    owner_id    BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    name        VARCHAR(80) NOT NULL,
    description VARCHAR(2000),
    genre       VARCHAR(32) NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_project_owner ON project (owner_id);

CREATE TABLE IF NOT EXISTS project_document (
    id           BIGSERIAL PRIMARY KEY,
    project_id   BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    version      INTEGER NOT NULL,
    object_key   VARCHAR(1024) NOT NULL,
    file_name    VARCHAR(255) NOT NULL,
    content_type VARCHAR(255) NOT NULL,
    size_bytes   BIGINT NOT NULL,
    uploaded_by  BIGINT NOT NULL REFERENCES app_user(id),
    uploaded_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_project_document_version UNIQUE (project_id, version)
);
CREATE INDEX IF NOT EXISTS idx_project_document_project ON project_document (project_id);
```

- `genre` is a `VARCHAR` holding a Kotlin enum name, not a PostgreSQL enum type:
  H2-in-PostgreSQL-mode support for native enums is uneven and adding a value
  would otherwise need its own migration.
- `uk_project_document_version` is the real defence for version allocation. The
  read-then-insert of `MAX(version) + 1` is racy; the constraint turns the race
  into a retryable conflict instead of two rows claiming `v3`.
- Both FKs target `app_user(id)`, created by `V2` and present since `9556dfc`.

## API Contract (agreed with ARTEL-66)

```text
GET    /api/projects                                          200 ProjectSummary[]
POST   /api/projects                                          201 ProjectDetail
GET    /api/projects/{projectId}                              200 ProjectDetail
PATCH  /api/projects/{projectId}                              200 ProjectDetail
POST   /api/projects/{projectId}/documents/upload-url         200 UploadTicketResponse
POST   /api/projects/{projectId}/documents                    201 ProjectDocumentResponse
GET    /api/projects/{projectId}/documents                    200 ProjectDocumentResponse[]
GET    /api/projects/{projectId}/documents/{documentId}/download-url  200 DownloadTicketResponse
```

- IDs serialize as **strings**, matching `AuthUserResponse.id` (`appUser.id.toString()`).
  The client treats them as opaque.
- `ProjectSummary`: `id, name, genre, description, documentCount, latestDocument, updatedAt`.
  `ProjectDetail` adds `createdAt`.
- `ProjectDocumentResponse`: `id, version, fileName, contentType, sizeBytes,
  uploadedAt, uploadedBy { id, displayName }`. `objectKey` is **never** exposed.
- `UploadTicketResponse`: `uploadUrl, objectKey, requiredHeaders, expiresAt`.
- A project that exists but is owned by someone else returns **404, not 403**,
  so the API does not confirm the existence of other users' projects.
- Uploading adds a version; it never replaces one. Version numbers are 1-based
  and monotonic per project.
- Errors return `{ code, message, fields? }`, extending the shape
  `SecurityConfig` already hand-writes for 401 (`{"code":"unauthorized",...}`).

### Decided 2026-07-21

- **`genre` is a closed enum**, stored as its name in a `VARCHAR(32)`:
  `ACTION, RPG, PUZZLE, SIMULATION, STRATEGY, SPORTS, SHOOTER, CASUAL, OTHER`.
  A closed set lets the agent pipeline key behaviour off it later; `OTHER` is
  the escape hatch so an unlisted genre can never block project creation. Adding
  a value costs a code change on both sides — that is the accepted trade.
  ARTEL-66 renders a `<select>` over exactly these values.
- **PDF is the only accepted format.** `application/pdf` and a `.pdf` extension,
  both required. A single format removes the content-type/extension mismatch
  matrix entirely and means the agent pipeline has one parser to write.
- **Size limit is 50 MB** (`52428800` bytes), and a declared size of `0` is
  rejected. The bytes never traverse the server, so the ceiling is about storage
  cost and honest client feedback, not server capacity.

## Approach (Checklist)

- [x] **Step 0: Recon / unblock** (2026-07-21) — PR #16 merged as `9556dfc`;
      this branch is rebased onto it. Verified: `app_user` and `oauth_identity`
      exist via `V2`, the auth layer was converted to R2DBC in `0df3ab5` with no
      JPA left in `src/main`, `SessionUserResolver` is available, and `V3` is the
      next free Flyway version. The Context section above was rewritten against
      the merged code rather than the pre-merge assumptions.
- [ ] **Step 1: Implementation**
  - [ ] `pom.xml` — add `software.amazon.awssdk:s3` and `:s3-transfer-manager`
        is *not* needed; add `s3` (which includes `S3Presigner`) via the AWS SDK
        v2 BOM. Add `spring-boot-starter-validation`.
  - [ ] `project/entity/ProjectEntity.kt`, `project/entity/ProjectDocumentEntity.kt`
        — `data class`, `@Table`, `@Id val id: Long? = null`, explicit `@Column`
        on every field, mirroring `AppUserEntity`.
  - [ ] `project/repository/ProjectRepository.kt`,
        `project/repository/ProjectDocumentRepository.kt` —
        `ReactiveCrudRepository`, derived queries, and
        `@Query("SELECT COALESCE(MAX(version), 0) FROM project_document WHERE project_id = :projectId")`.
  - [ ] `project/dto/` — one `data class` per file, following the newer
        `testscenario/dto` style: `CreateProjectRequest`, `UpdateProjectRequest`,
        `ProjectSummaryResponse`, `ProjectDetailResponse`, `UploadTicketRequest`,
        `UploadTicketResponse`, `RegisterDocumentRequest`, `ProjectDocumentResponse`,
        `DownloadTicketResponse`, and `Genre` (enum).
  - [ ] `project/service/ProjectService.kt` — owner-scoped CRUD, `Clock`-based
        timestamps, `Mono`-returning.
  - [ ] `project/service/ProjectDocumentService.kt` — ticket minting, object-key
        generation (`projects/{projectId}/documents/{uuid}/{sanitizedFileName}`),
        `headObject` verification, version allocation with retry on
        `DataIntegrityViolationException`.
  - [ ] `project/storage/DocumentStorage.kt` (port) +
        `project/storage/S3DocumentStorage.kt` (adapter, `S3AsyncClient` +
        `S3Presigner`).
  - [ ] `project/config/StorageProperties.kt` — `@ConfigurationProperties("artel.storage")`
        with `require(...)` validation in `init`, mirroring `AuthProperties`.
  - [ ] `project/controller/ProjectController.kt` and
        `ProjectDocumentController.kt` — `@AuthenticationPrincipal jwt: Jwt`,
        `Mono<T>` returns, `@Tag`/`@Operation` springdoc annotations (the repo
        applies these inconsistently; this feature applies them).
  - [ ] `config/ApiExceptionHandler.kt` — the repository's **first**
        `@RestControllerAdvice`, mapping validation failures to `400` with
        `fields`, `ResponseStatusException` to its status, and everything else to
        `500` without leaking internals.
  - [ ] `src/main/resources/db/migration/V3__create_project_and_project_document.sql`
        with Korean SQL comments, matching `V1`/`V2` style.
  - [ ] `application.yml` — `artel.storage.*` block wired to `${...}` env vars;
        `.env.example` — add `ARTEL_S3_BUCKET`, `ARTEL_S3_REGION`,
        `ARTEL_S3_ENDPOINT` (optional, for local MinIO),
        `ARTEL_S3_UPLOAD_URL_TTL`, `ARTEL_S3_DOWNLOAD_URL_TTL`,
        `ARTEL_UPLOAD_MAX_BYTES`.
  - [ ] `SecurityConfig` — no route change needed (`anyExchange().authenticated()`
        already covers `/api/projects/**`); confirm the CORS config allows
        `PATCH` for the Home origin.
- [ ] **Step 2: Tests**
  - [ ] Every DB-touching test clears its tables in `@BeforeEach` with
        `deleteAll().block()`, as `OAuthUserServiceIntegrationTest` does —
        reactive `@Transactional` rollback does not work and H2 is shared.
  - [ ] `ProjectCrudIntegrationTest` — `@SpringBootTest(RANDOM_PORT)` +
        `@LocalServerPort` + `WebClient`, authenticating by minting a real token
        with `JwtService.issue(...)` and attaching the `artel_access_token`
        cookie, exactly as `ArtelWebSocketIntegrationTest` does for
        `/api/auth/me`. Cases: create → list → get → patch → unauthenticated 401
        → other user's project 404 → validation 400 with `fields`.
  - [ ] `ProjectDocumentIntegrationTest` with a fake `DocumentStorage`
        `@TestConfiguration` bean. Cases: ticket → register → version increments
        to 2 → list newest-first → register without a matching object 400 →
        oversized object 400 → zero-byte object 400 → non-PDF content type
        rejected at ticket time 400 → object whose first bytes are not `%PDF-`
        rejected at register time 400 → download-url returns a URL.
  - [ ] `ProjectDocumentServiceTest` — version allocation under a simulated
        unique-constraint conflict retries and succeeds.
  - [ ] Extend `DatabaseConnectionTest`'s hardcoded table list with `project` and
        `project_document`, per existing convention.
  - [ ] Extend `OpenApiDocumentationIntegrationTest` with `contains(...)`
        assertions for the new paths.
  - [ ] Add a companion test plan at
        `.plan/general/2026-07-21-project-crud-test-plan.md` in the established
        `## Purpose / ## Identifiers / ## Environment & Commands / ## Risk-Based
        Coverage / ## Test Cases / ## Reporting` format.
- [ ] **Step 3: Rollout / Rollback**
  - [ ] Provision the bucket and its CORS rule (`PUT` from the Home origin,
        `Content-Type` in `AllowedHeaders`) before merge; the client half of
        ARTEL-66 cannot be verified without it.
  - [ ] No feature flag: the endpoints are additive and nothing calls them until
        Home ships. Rollback is `git revert` of the merge plus a manual
        `DROP TABLE project_document, project` — Flyway has no `undo` on the
        community edition, so a revert alone leaves the tables in place
        (harmless, but must be stated).
  - [ ] Update `docs/api-documentation.md` "Documented API surface" with the new
        endpoints. Note it is already stale — it omits `/api/test-scenario` —
        so fix that omission in the same pass.

## Configuration

- `ARTEL_S3_BUCKET` — required, no default. Startup fails if absent.
- `ARTEL_S3_REGION` — default `ap-northeast-2`.
- `ARTEL_S3_ENDPOINT` — optional override for local MinIO development.
- `ARTEL_S3_UPLOAD_URL_TTL` — default `PT10M`.
- `ARTEL_S3_DOWNLOAD_URL_TTL` — default `PT5M`.
- `ARTEL_UPLOAD_MAX_BYTES` — default `52428800` (50 MB), enforced both when
  minting the ticket and when verifying with `headObject`. The accepted content
  type (`application/pdf`) is a constant, not configuration: making it tunable
  would let a deployment accept a format no downstream parser can read.
- Credentials come from the default AWS provider chain (instance role in
  deployment, `~/.aws` or env vars locally). No access key is ever read from
  `application.yml`.

## Validation

- **Commands to run:**
  - `./mvnw clean test`
  - `./mvnw -Dtest=ProjectCrudIntegrationTest test`
  - `./mvnw -Dtest=ProjectDocumentIntegrationTest test`
  - `./mvnw -Dtest=OpenApiDocumentationIntegrationTest test`
  - `./mvnw clean package`
- **Expected output:** Flyway applies `V3` against H2 in PostgreSQL mode with no
  duplicate-version error; all suites green; `/v3/api-docs` lists every new path.
- **Manual:** run against a real bucket, drive the full flow from a locally
  running `artel-home` on ARTEL-66, and confirm a browser `PUT` to the presigned
  URL succeeds (this is the only way to prove the bucket CORS rule is right).

## Risks & Rollback

- **Risks:**
  - ~~**Ordering dependency on PR #16.**~~ Resolved 2026-07-21: ARTEL-44 merged
    as `9556dfc` and this branch is rebased onto it. Ownership,
    `SessionUserResolver`, and the `app_user` FK are all available.
  - **Duplicate Flyway version.** `V3` is free today, but
    `feat/define-testscenario-domain-ARTEL-68` is open and could claim it first;
    two `V3` files make Flyway fail at startup. Mitigation: re-check
    `db/migration/` immediately before writing the file, and again before the PR
    is marked ready.
  - **Orphaned S3 objects.** A client can obtain a ticket, upload bytes, and
    never call the register endpoint — for example when its access token expires
    mid-upload. Mitigation: unregistered objects are inert (no row, so no way to
    read them); add an S3 lifecycle rule expiring objects under an
    `incoming/` prefix, or accept the leak and record it. Do not pretend this is
    solved by the code alone.
  - **Version-allocation race.** Two concurrent uploads both reading
    `MAX(version)` see the same value. Mitigation: the unique constraint plus a
    bounded retry; asserted by a dedicated test rather than assumed.
  - **First-of-its-kind code.** The R2DBC shape is now settled by `auth/`, but
    this still introduces the repository's first bean validation, first
    `@RestControllerAdvice`, and first object storage. Whatever shape those take
    becomes the house convention by default. Mitigation: keep each piece minimal
    and boring, follow `auth/` wherever it already answers the question, and
    flag the argument-resolver question below rather than inventing broadly.
  - **`headObject` is a real network call.** Unlike presigning, it hits S3 and
    can be slow or fail. Mitigation: `S3AsyncClient` (never the blocking client)
    with an explicit timeout, mapped to a `503`-style error rather than hanging.
- **Rollback steps:** `git revert` the merge commit; drop `project_document`
  then `project` manually; remove the `artel.storage.*` env vars. The Home side
  degrades to an empty list, not a crash, because ARTEL-66 renders an error
  state for a failed fetch.

## Open Questions

- **Should a `@CurrentUser` argument resolver be introduced now?** ARTEL-44
  deliberately kept `@AuthenticationPrincipal jwt: Jwt` + an explicit
  `sessionUserResolver.resolve(jwt)` call in the controller. This feature adds
  eight endpoints that all repeat those two lines. A
  `HandlerMethodArgumentResolver` would remove the repetition but changes a
  convention set one PR earlier. Proposed default: repeat the existing pattern
  for now, and raise the resolver as a follow-up once the duplication is real
  and visible in the diff.
- **Is MinIO wanted for local development?** `ARTEL_S3_ENDPOINT` is included so
  it is possible, but no MinIO service is defined anywhere in the repo and there
  is no `docker-compose.yml`. If local development is expected to work without
  AWS credentials, that is additional infrastructure work outside this issue.
- **Should `uploaded_by` be enforced as an owner-only action?** Today only the
  owner can reach the project at all, so the question is moot — but recording
  the uploader now means the column is already right when sharing arrives.
