# 2026-08-28 — 이슈 트래커 연결과 GitHub 이슈 내보내기

- Date: 2026-08-28
- Jira: ARTEL-671
- Status: Draft

## Goal

QA agent 가 런 도중 보고한 결함(`issue` 행)을 외부 이슈 트래커로 내보낸다. GitHub 이 첫 구현체이고,
Jira 가 다음 트래커로 붙을 수 있도록 provider 를 **값**으로 갖는 구조로 만든다. 트래커 이름이 테이블
이름·서비스 이름·endpoint 경로에 박히지 않는다.

전달물:

1. 마이그레이션 `V64` — `project_tracker_link`, `issue_tracker_link` 두 테이블.
2. 프로젝트 트래커 연결 API (조회·설정·해제) + GitHub 설치 흐름 (install-url, setup 콜백, 저장소 목록).
3. `recordAgentIssue` 뒤에 붙는 자동 내보내기, 수동 내보내기 endpoint, `resolve`/`reopen` 반영.
4. `IssueResponse.tracker` 필드.

## Non-goals

- GitHub webhook 수신(역방향 동기화).
- Jira 구현체. 포트와 registry 만 열어 두고 구현은 후속.
- 중복 결함 합치기.
- 코멘트·첨부·스크린샷 동기화.
- 실패한 내보내기의 자동 재시도 스케줄러. 재시도는 사람이 수동 endpoint 로 누른다.
- 설치가 취소된 것을 서버가 먼저 알아채는 것. 취소는 다음 내보내기가 `FAILED` 로 드러낸다.
- 저장소 목록의 페이지 넘김. 한 installation 에 저장소가 100개를 넘으면 첫 100개만 보인다
  (GitHub `GET /installation/repositories` 한 장). 그보다 큰 조직은 이 버전의 범위 밖이다.

## Context / Constraints

### 얼어 있는 계약 (artel-home ARTEL-672 가 동시에 이 모양으로 짠다)

| Method | Path | Response |
|---|---|---|
| GET | `/api/projects/{projectId}/tracker-link` | `200 {"link": TrackerLink \| null}` |
| PUT | `/api/projects/{projectId}/tracker-link` | `200 {"link": TrackerLink}` |
| DELETE | `/api/projects/{projectId}/tracker-link` | `204` |
| GET | `/api/projects/{projectId}/tracker/github/install-url` | `200 {"url": string}` |
| GET | `/api/projects/{projectId}/tracker/github/repositories` | `200 {"items": [{"workspace","repository","htmlUrl","private"}]}` |
| GET | `/api/tracker/github/setup` | `302 → <frontendOrigin>/projects/{projectId}/settings?tracker=connected\|failed` |
| POST | `/api/issues/{issueId}/tracker-sync` | `202 {"tracker": IssueTracker}` |

- `PUT` body: `{"provider","workspace","repository","autoSyncSeverities"}`
- `TrackerLink` = `provider`, `installed`(boolean), `workspace`(string\|null), `repository`(string\|null),
  `htmlUrl`(string\|null), `autoSyncSeverities`(string[]), `updatedAt`(string)
- `IssueTracker` = `provider`, `externalKey`(string\|null), `url`(string\|null),
  `syncState`(`PENDING`\|`SYNCED`\|`FAILED`), `syncError`(string\|null), `syncedAt`(string\|null)
- `IssueResponse.tracker`: 연결이 없거나 아직 내보내지 않았으면 `null`. 프로젝트 이슈 목록과 QA 실행
  이슈 목록 **양쪽**에 나간다.
- 심각도 사다리는 기존 `IssueSeverity` 를 그대로 쓴다. 자동 내보내기 기본값은 `BLOCKER`, `CRITICAL`.

⚠️ 스토리 ARTEL-670 본문은 PUT body 를 `repositoryOwner`/`repositoryName` 으로 적었지만, 이 작업이
따르는 것은 위 표의 `workspace`/`repository` 다. **얼어 있는 계약이 이긴다** — artel-home 의 짝 작업
ARTEL-672 가 지금 그 이름으로 짜고 있으므로, 여기서 스토리 쪽 이름으로 되돌리면 두 저장소가 어긋난다.
스토리 본문이 낡았다는 사실만 최종 보고에 남기고 구현은 얼어 있는 계약을 따른다. 이것은 blocker 가
아니다.

### 저장소 규약

- 오류는 `common/error` 의 타입 예외(`.agents/docs/error-handling.md`). 신규 `ResponseStatusException` 금지.
- 설정은 `@ConfigurationProperties`(`.agents/docs/configuration.md`). 신규 `@Value` 금지.
- 경계를 넘는 payload 는 선언된 타입(`.agents/docs/coding-style.md` Data Shapes). `JsonNode` 를 깊이 끌지 않는다.
- 주석은 한국어(`coding-style.md` Comments).
- 무인증 라우트를 `/api/` 아래 두지 않는다(`.agents/docs/project.md`). `/api/tracker/github/setup` 은
  브라우저 네비게이션이라 세션 쿠키가 실린다 — **인증 대상으로 남긴다.**

### Flyway 번호

`origin/develop` 최신은 `V60`. 미머지 peer 브랜치가 `V61`, `V62`, `V63` 을 이미 claim 했다
(`V63` 은 ARTEL-642, 2026-08-29 확인). 그래서 이 작업은 **`V64`** 를 쓴다.
`scripts/check-flyway-migrations.sh` 가 `OK` 를 줄 때까지 올린다 — `V61`~`V63` 의 빈칸은 그 peer
브랜치들의 것이므로 "번호를 당겨 메우는" 정리를 하면 안 된다.

### 기존 코드에서 붙잡을 지점

- `IssueService.recordAgentIssue` — `DataIntegrityViolationException` catch 로 재전송을 흡수한다.
  그 분기가 곧 "새 행인가"의 판정이다.
- `IssueService.resolve`/`reopen` — 조건부 UPDATE 의 영향 행 수를 이미 받는다.
- `IssueService.page`/`toResponse` — 두 목록이 같은 조립 경로를 지난다. `tracker` 를 여기 한 곳에 붙이면
  프로젝트 목록과 실행 목록 양쪽이 함께 따라온다.
- `ProjectAccessService.member(projectId, userId)` — 역할까지 돌려준다. OWNER 판정은 이것으로 한다.
- `RefreshTokenService` — `JwtEncoder` + audience 전용 `NimbusReactiveJwtDecoder` 로 상태 없는 서명
  토큰을 발급·검증하는 기성 패턴. 설치 콜백의 `state` 는 이 패턴을 그대로 복제한다.
- `AuthProperties` 는 이미 audience 들의 등기소다(`audience`, `sdkAudience`, `refreshAudience`).

## Approach (Checklist)

### Step 0: Recon — 완료

- `issue/`, `project/`, `qa/service/QaAgentInboundRouter.routeIssue`, `auth/config/AuthProperties.kt`,
  `auth/service/JwtService.kt`/`RefreshTokenService.kt`, `auth/config/SecurityConfig.kt`,
  `src/main/resources/db/migration/` 최신 번호, `application.yml`, `.env.example` 를 읽었다.
- 마이그레이션 번호: develop `V60`, peer claim `V61`/`V62`/`V63` → **`V64`**.

### Step 1: 마이그레이션

`src/main/resources/db/migration/V64__link_projects_and_issues_to_trackers.sql`

```sql
CREATE TABLE IF NOT EXISTS project_tracker_link (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    provider VARCHAR(32) NOT NULL CHECK (provider IN ('GITHUB')),
    external_workspace VARCHAR(255),
    external_repository VARCHAR(255),
    installation_ref VARCHAR(255),
    auto_sync_severities VARCHAR(255) NOT NULL DEFAULT 'BLOCKER,CRITICAL',
    connected_by BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_project_tracker_link_provider
    ON project_tracker_link (project_id, provider);

CREATE TABLE IF NOT EXISTS issue_tracker_link (
    id BIGSERIAL PRIMARY KEY,
    issue_id BIGINT NOT NULL REFERENCES issue (id) ON DELETE CASCADE,
    provider VARCHAR(32) NOT NULL CHECK (provider IN ('GITHUB')),
    external_key VARCHAR(255),
    external_url TEXT,
    sync_state VARCHAR(16) NOT NULL CHECK (sync_state IN ('PENDING', 'SYNCED', 'FAILED')),
    sync_error TEXT,
    synced_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_issue_tracker_link_provider
    ON issue_tracker_link (issue_id, provider);
```

결정 근거를 SQL 주석(한국어)으로 남긴다:

- `issue` 에 컬럼을 더하지 않는 이유 — 트래커가 늘면 컬럼이 배로 는다.
- `provider` CHECK 를 두는 이유와, Jira 를 붙일 때 값 하나만 추가하면 되는 구조.
- `auto_sync_severities` 를 쉼표 구분 VARCHAR 로 두는 이유 — R2DBC 의 `text[]` 매핑을 도입할 만큼
  질의에서 배열 연산을 하지 않는다. 값은 항상 서버가 `IssueSeverity` 로 검증해 쓰고 읽는다.
- `installation_ref` 가 nullable 인 이유 — GitHub App 설치 콜백이 저장소 선택보다 먼저 온다. 그리고
  GitHub 외 트래커는 설치 개념이 없다.
- **비밀은 여기 없다.** installation access token 은 메모리 캐시에만 산다.

### Step 2: 도메인 타입과 저장소 (`tracker/` 패키지 신설)

`src/main/kotlin/kr/artel/orchestration/tracker/`

- `entity/TrackerProvider.kt` — `enum class TrackerProvider { GITHUB }` + `NAMES`.
- `entity/TrackerSyncState.kt` — `enum class TrackerSyncState { PENDING, SYNCED, FAILED }`.
- `entity/ProjectTrackerLinkEntity.kt`, `entity/IssueTrackerLinkEntity.kt`.
- `repository/ProjectTrackerLinkRepository.kt`
  - `suspend fun findByProjectIdAndProvider(projectId: Long, provider: String): ProjectTrackerLinkEntity?`
  - `suspend fun deleteByProjectIdAndProvider(...): Int`
  - 설치 콜백용 upsert(아래 Step 5).
- `repository/IssueTrackerLinkRepository.kt`
  - `fun findByIssueIdIn(issueIds: Collection<Long>): Flow<IssueTrackerLinkEntity>` — 목록 조립용 배치 읽기.
  - `suspend fun findByIssueIdAndProvider(issueId: Long, provider: String): IssueTrackerLinkEntity?`
  - **`claim`** — 아래 멱등 선점.
  - `markSynced`, `markFailed`.
- `repository/IssueProjectRepository.kt` 대신 `IssueRepository` 에 질의 한 줄 추가:
  `SELECT ts.project_id FROM issue i JOIN qa_try qt ... JOIN test_scenario ts ... WHERE i.id = :issueId`

#### 멱등 선점 — 단일 문장

```sql
INSERT INTO issue_tracker_link (issue_id, provider, sync_state, sync_error, created_at, updated_at)
VALUES (:issueId, :provider, 'PENDING', NULL, :now, :now)
ON CONFLICT (issue_id, provider) DO UPDATE
SET sync_state = 'PENDING', sync_error = NULL, updated_at = :now
WHERE issue_tracker_link.sync_state = 'FAILED'
   OR (issue_tracker_link.sync_state = 'PENDING'
       AND issue_tracker_link.updated_at < :staleBefore)
RETURNING id
```

**반환값은 선점한 행의 id(`Long?`)다. null 이면 선점하지 못한 것이다.** `@Modifying` 의 영향 행 수가
아니라 `RETURNING id` 를 쓰는 이유가 둘이다: 뒤이은 `markSynced`/`markFailed` 가 그 id 를 그대로
쓰므로 다시 조회할 필요가 없고, `INSERT ... ON CONFLICT DO UPDATE ... WHERE` 의 영향 행 수 의미를
드라이버에 맡기지 않아도 된다 — 행이 돌아오면 선점, 안 돌아오면 실패로 읽는 것이 애매하지 않다.

- 행이 없으면 INSERT 가 `claim` 이다. 있으면 `FAILED` 일 때만(재시도) 또는 **오래 매달린 `PENDING`**
  일 때만 다시 `claim` 한다. `SYNCED` 는 절대 다시 `claim` 되지 않는다.
- ⚠️ **이것이 막는 것과 막지 못하는 것을 정확히 적어 둔다.** 동시 요청 · 재시도 · agent 프레임
  재전송에서 이슈가 둘 생기는 일은 없다. 막지 못하는 경우는 하나다 — 외부 호출이 성공하고
  `markSynced` 가 실행되기 **전에** 프로세스가 죽으면 행이 `PENDING` 으로 남고, 유예가 지난 뒤의
  재시도가 두 번째 이슈를 만든다. 그 창을 닫으려면 재`claim` 마다 저장소를 검색해 marker 를 찾아야
  하는데(포트에 메서드 하나 추가, GitHub search 의 rate limit 과 지연 일관성이라는 새 실패 축),
  창의 폭(HTTP 201 수신과 UPDATE 한 문장 사이)에 비해 값이 맞지 않는다. **열어 두되 숨기지 않는다** —
  재`claim` 이 일어날 때 issue id 를 실어 warn 로그를 남겨, 드문 중복을 사람이 찾을 수 있게 한다.
- 동시 요청 둘: 하나는 INSERT, 다른 하나는 conflict → `WHERE` 가 거짓(상태가 `PENDING`, 그리고
  `updated_at` 이 방금이라 stale 아님) → 영향 행 0 → 조용히 끝낸다.
- `staleBefore` 가 필요한 이유: 프로세스가 외부 호출 도중 죽으면 행이 `PENDING` 으로 굳는다. 그 행을
  영원히 되살릴 수 없으면 "미동기화·실패를 다시 시도한다"는 AC 를 사람이 만족시킬 수 없다.
  기본 유예는 `artel.tracker.claim-stale-after=PT5M`.
- 락을 잡지 않는다 — R2DBC 경로에서 외부 HTTP 호출을 트랜잭션 안에 묶지 않기 위해서다.

### Step 3: 포트와 registry

`tracker/client/IssueTrackerClient.kt`

```kotlin
/** 어느 트래커의 어느 저장소로 내보내는가. provider 별 구현체가 이 값만 보고 움직인다. */
data class TrackerTarget(
    val workspace: String,
    val repository: String,
    val installationRef: String?
)
data class TrackerIssueDraft(val title: String, val body: String)
data class TrackerIssueRef(val externalKey: String, val url: String)
data class TrackerRepository(
    val workspace: String,
    val repository: String,
    val htmlUrl: String,
    val private: Boolean
)

interface IssueTrackerClient {
    val provider: TrackerProvider
    suspend fun verifyRepositoryAccess(target: TrackerTarget)
    suspend fun createIssue(target: TrackerTarget, draft: TrackerIssueDraft): TrackerIssueRef
    suspend fun closeIssue(target: TrackerTarget, externalKey: String)
    suspend fun reopenIssue(target: TrackerTarget, externalKey: String)
}
```

`tracker/client/IssueTrackerClientRegistry.kt` — `List<IssueTrackerClient>` 주입, provider 로 조회.
없으면 `BadRequestException("지원하지 않는 이슈 트래커입니다.")`.

설치·저장소 목록은 GitHub 고유라 포트에 올리지 않는다. `GitHubInstallationService` 가 따로 들고 있고
`/tracker/github/**` 경로만 그것을 부른다 — 경로에 provider 가 박히는 것은 **여기뿐**이고, 그 이유는
설치 흐름 자체가 provider 마다 다르기 때문이다. 계약이 그렇게 정해져 있다.

### Step 4: GitHub 자격증명과 클라이언트

`tracker/config/GitHubAppProperties.kt`

```kotlin
@ConfigurationProperties("artel.tracker.github")
data class GitHubAppProperties(
    val appId: String = "",
    val appSlug: String = "",
    /** PKCS#8 PEM. 환경변수로 실을 때 개행이 \n 으로 이스케이프되는 것을 허용한다. */
    val privateKey: String = "",
    /** installation 소유 확인용 user-to-server 교환에 쓴다. App 설정 화면의 Client ID/secret. */
    val clientId: String = "",
    val clientSecret: String = "",
    val apiBaseUrl: String = "https://api.github.com",
    val webBaseUrl: String = "https://github.com",
    /** 만료 직전 재발급 여유. GitHub installation token 수명은 1시간이다. */
    val tokenRefreshSkew: Duration = Duration.ofMinutes(5),
    val requestTimeout: Duration = Duration.ofSeconds(15)
) {
    val configured: Boolean
        get() = appId.isNotBlank() && appSlug.isNotBlank() && privateKey.isNotBlank() &&
            clientId.isNotBlank() && clientSecret.isNotBlank()
}
```

- 값이 비어 있어도 **기동은 성공한다**. `init { require(...) }` 를 걸지 않는 이유가 여기 있다 — 걸면
  GitHub App 을 아직 등록하지 않은 환경(로컬·테스트·기존 배포)에서 서버가 아예 뜨지 않아 기존 QA 경로가
  같이 죽는다. 대신 연동 endpoint 가 부를 때 `TrackerNotConfiguredException`(503, `tracker_not_configured`)
  으로 명확히 거절한다.
`tracker/config/TrackerProperties.kt` — provider 와 무관한 트래커 공통 설정.

```kotlin
@ConfigurationProperties("artel.tracker")
data class TrackerProperties(
    /**
     * 굳은 PENDING 을 다시 선점할 수 있게 되기까지의 유예. 내보내는 도중 프로세스가 죽으면 행이
     * PENDING 으로 남는데, 이 유예가 없으면 그 행은 영원히 되살아나지 못한다.
     * ⚠️ GitHubAppProperties.requestTimeout 보다 훨씬 커야 한다 — 정상 호출이 유예 안에 끝나지
     * 않으면 두 요청이 같은 결함을 동시에 내보내 외부 이슈가 둘 생긴다.
     */
    val claimStaleAfter: Duration = Duration.ofMinutes(5)
)
```

`claim-stale-after` 는 GitHub 것이 아니라 provider 공통이라 `GitHubAppProperties` 의 필드가 될 수
없다. 그렇다고 `@Value` 로 받으면 `configuration.md` 위반이다 — 그래서 클래스를 따로 둔다.

- `application.yml` 에 `artel.tracker.github.*` 블록과 `artel.tracker.claim-stale-after` 를 추가하고,
  `.env.example` 에 `ARTEL_GITHUB_APP_ID`, `ARTEL_GITHUB_APP_SLUG`, `ARTEL_GITHUB_APP_PRIVATE_KEY` 를
  비운 채로 추가한다(왜 비어 있으면 연동만 꺼지는지 주석으로).

`tracker/github/GitHubAppTokenService.kt`

- App JWT: RS256, `iss=appId`, `iat=now-60s`(GitHub 이 시계 오차를 허용하도록), `exp=now+9m`.
  nimbus-jose-jwt 로 서명한다(이미 `spring-security-oauth2-jose` 로 클래스패스에 있다).
- PEM 파싱: 헤더/푸터 제거 → Base64 디코드 → `KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec)`.
  `\n` 리터럴을 실제 개행으로 바꾼다.
- **파싱 시점은 기동이 아니라 첫 사용이다.** `by lazy` 로 한 번만 파싱해 들고 있고, 파싱이 실패하면
  `TrackerNotConfiguredException`(503, `tracker_not_configured`) 을 던진다. 기동에서 검증하지 않는
  이유는 `configured` 를 기동에서 강제하지 않는 이유와 같다 — 키 오타 하나가 서버를 못 뜨게 하면
  트래커를 쓰지 않는 QA 경로까지 함께 죽는다. 대신 오타는 **연동 endpoint 를 부르는 순간 명확한
  503 으로** 드러나고 원인은 `cause` 로 로그에 남는다. 조용히 실패하는 경로는 없다.
- installation token: `POST {api}/app/installations/{id}/access_tokens` → `GitHubInstallationTokenResponse(token, expiresAt)`.
- 캐시: `ConcurrentHashMap<String, CachedInstallationToken>` + 발급 경합을 막는 `Mutex`(installation 별).
  `expiresAt - tokenRefreshSkew` 를 지나면 다시 발급한다. **DB 에 쓰지 않는다.**

`tracker/github/GitHubIssueTrackerClient.kt` — `IssueTrackerClient` 구현체.

- `WebClient` 하나(`GitHubAppProperties.requestTimeout` 적용). 응답은 전부 선언된 DTO 로 받는다:
  `GitHubIssueResponse(number: Long, htmlUrl: String)`, `GitHubRepositoryResponse(...)`.
- `verifyRepositoryAccess` → `GET /repos/{owner}/{repo}` (404/403 → `TrackerRepositoryUnavailableException`, 400).
- `createIssue` → `POST /repos/{owner}/{repo}/issues`.
- `closeIssue`/`reopenIssue` → `PATCH /repos/{owner}/{repo}/issues/{number}` with `{"state":"closed"|"open"}`.
- `installationRef` 가 null 이면 `TrackerNotInstalledException`(400).
- 실패는 `UpstreamUnavailableException`(503) 으로 감싸 `cause` 를 남긴다.

`tracker/github/GitHubInstallationService.kt`

- `installUrl(state)` = `{web}/apps/{slug}/installations/new?state={state}`.
- `listRepositories(installationRef)` → `GET /installation/repositories` (installation token) → 페이지 100 한 장.
- **`verifyInstallationBelongsToCaller(code, installationRef)`** — 아래 참고.

#### ⚠️ `installation_id` 는 공격자가 고르는 값이다

서명된 `state` 는 "이 사람이 이 프로젝트의 OWNER 인가"만 증명한다. `installation_id` 는 GitHub 이
붙여 주는 쿼리 파라미터이고 작은 순차 정수라, 서명만으로는 **남의 installation 을 내 프로젝트에**
붙이는 것을 막지 못한다. 그렇게 붙이면 App private key 가 그 installation 의 token 을 발급할 수
있으므로, `GET .../tracker/github/repositories` 가 피해자의 private 저장소 목록을 그대로 내준다.

그래서 App 에 **Request user authorization (OAuth) during installation 을 켜고**, 콜백이 함께 싣고
오는 `code` 로 소유를 확인한다:

1. `POST {web}/login/oauth/access_token` (`client_id`, `client_secret`, `code`) → user access token
2. `GET {api}/user/installations` → 그 사람이 접근할 수 있는 installation 목록
3. `installation_id` 가 그 목록에 없으면 **저장하지 않고** `?tracker=failed`

`code` 가 아예 없는 콜백도 거절한다 — 있으면 파라미터를 빼는 것만으로 확인을 건너뛸 수 있다.

이 때문에 App 설정에서 **Callback URL 과 Setup URL 을 둘 다** `<서버>/api/tracker/github/setup` 으로
맞춘다. 계약이 정한 복귀 지점은 하나이고, 두 URL 이 같은 곳을 가리키면 사용자 인가를 켜도 그 지점이
바뀌지 않는다.

### Step 5: 설치 state 서명

`AuthProperties` 에 두 값을 더한다(이미 audience 등기소다):

```kotlin
val trackerSetupAudience: String = "artel-tracker-setup",
val trackerSetupStateTtl: Duration = Duration.ofMinutes(15)
```

`SecurityConfig` 에 `trackerSetupJwtDecoder` 빈을 더한다(`decoderFor(properties, trackerSetupAudience)`).

`tracker/service/TrackerSetupStateService.kt` — `RefreshTokenService` 를 그대로 본뜬다.

- `issue(projectId, userId, provider): String` — `sub=userId`, `claim("project_id", projectId)`,
  `claim("provider", provider.name)`.
- `verify(state): TrackerSetupState?` — 서명·만료·issuer·audience 중 하나라도 어긋나면 null.
  실패 이유는 구분하지 않는다.

`JwtService`/`RefreshTokenService` 와 발급·검증 boilerplate 가 겹치는 것은 **의도한 선택**이다. 공용
서명기를 뽑아내려면 `auth/` 의 기존 두 클래스를 이 기능 때문에 고쳐야 하는데, `coding-style.md` 의
"unnecessary rewrites / unrelated cleanup" 가 말리는 쪽이 그것이다. 서명 방식이 세 번째로 필요해지면
그때 뽑는다.

서명이 없으면 남의 프로젝트에 설치를 붙일 수 있다는 것이 이 조각의 존재 이유다. 콜백은 `state` 가
가리키는 projectId 를 쓰고, **요청한 사용자가 그 프로젝트의 OWNER 인지 다시 확인한다** — state 를
발급받은 사람과 콜백을 태우고 온 브라우저 세션이 같은 사람인지 한 번 더 보는 것이다.

### Step 6: 연결 API

`tracker/dto/TrackerDtos.kt` — 위 계약 표 그대로. `TrackerLinkResponse`, `TrackerLinkEnvelope(link)`,
`TrackerLinkUpsertRequest`, `TrackerInstallUrlResponse(url)`, `TrackerRepositoryPageResponse(items)`,
`IssueTrackerResponse`, `IssueTrackerEnvelope(tracker)`.

`tracker/service/ProjectTrackerLinkService.kt`

- `read(projectId, userId)` — member 아니면 404. 링크 없으면 `null`.
- `upsert(projectId, userId, request)` — OWNER 아니면: member 면 403, 비member 면 404.
  - `provider` 는 `TrackerProvider` 로 파싱(모르면 400).
  - `autoSyncSeverities` 는 `IssueSeverity` 로 전부 검증(모르면 400). 빈 배열은 허용 — 자동 내보내기를 끈 것이다.
  - 기준을 바꿔도 **이미 만들어진 `issue_tracker_link` 행은 건드리지 않는다.** 기준은 앞으로 저장될
    결함의 자동 내보내기 여부만 정한다. 기준을 넓혔다고 과거 결함을 소급해 내보내면, 오래된 런의
    결함 수십 건이 한꺼번에 GitHub 이슈가 된다 — 사람이 누르는 수동 endpoint 가 그 자리를 맡는다.
  - 기존 행의 `installation_ref` 를 읽어 `verifyRepositoryAccess` 를 부른다. 설치가 없거나 접근할 수
    없으면 저장하지 않고 거절한다 — 저장된 뒤 첫 결함에서야 실패를 알게 되는 것을 막는다.
- `delete(projectId, userId)` — OWNER 만. 행만 지운다. `issue_tracker_link` 는 **건드리지 않는다**
  (이미 나간 외부 이슈와 그 링크는 증거다).
- `attachInstallation(state, installationRef)` — 콜백 경로. 행이 없으면 만들고, 있으면 갱신한다.

`tracker/controller/ProjectTrackerLinkController.kt` — `/api/projects/{projectId}/tracker-link`
`tracker/controller/GitHubTrackerController.kt` — `/api/projects/{projectId}/tracker/github/**`
`tracker/controller/GitHubTrackerSetupController.kt` — `/api/tracker/github/setup`

- 콜백은 `installation_id`, `setup_action`, `state` 를 쿼리로 받는다.
- 성공 → `302 <frontendOrigin>/projects/{projectId}/settings?tracker=connected`
- 실패(state 무효/만료, 권한 없음, 저장 실패) → `?tracker=failed`. state 가 아예 무효라 projectId 를
  모르면 `<frontendOrigin>/projects?tracker=failed` 로 되돌린다.
- **콜백은 예외를 밖으로 내보내지 않는다.** 브라우저가 JSON 오류를 보는 대신 항상 home 으로 돌아간다.
- ⚠️ 그 약속은 controller 안에서만 지켜서는 성립하지 않는다. access 쿠키 수명이 15분인데 GitHub
  설치는 조직 선택과 관리자 승인까지 그보다 오래 걸릴 수 있고, 만료된 채 돌아오면
  `SecurityConfig` 의 `jsonAuthenticationEntryPoint` 가 **controller 보다 먼저** raw JSON 401 을 뱉는다.
  그래서 `SecurityConfig` 에 이 경로 하나만 잡는 `@Order(0)` 체인을 두고, 그 체인의
  `authenticationEntryPoint` 가 `302 <frontendOrigin>/projects?tracker=failed` 로 되돌린다.
  경로는 여전히 인증 대상이다 — 무인증으로 여는 것이 아니라 실패의 **모양**만 바꾼다.
- 그래서 이 체인은 브라우저 체인의 `oauth2ResourceServer` + `cookieTokenConverter(properties)` +
  `@Primary jwtDecoder` + `NoOpServerSecurityContextRepository` 를 **그대로 옮겨 싣는다.** 그 셋을
  빠뜨리면 유효한 세션 쿠키를 들고 온 정상 설치까지 전부 `?tracker=failed` 로 튕기는데, "쿠키 없이
  오면 리다이렉트"만 확인하는 테스트는 그 고장을 초록으로 통과시킨다.

### Step 7: 내보내기 서비스

`tracker/service/IssueTrackerSyncService.kt` — **provider 를 모른다.**

```kotlin
suspend fun syncAutomatically(issueId: Long)          // severity 기준에 들 때만
suspend fun syncManually(issueId: Long, userId: Long): IssueTrackerResponse   // 202 본문
suspend fun reflectResolved(issueId: Long)            // 닫기
suspend fun reflectReopened(issueId: Long)            // 다시 열기
fun launchAutoSync(issueId: Long): Job                // fire-and-forget wrapper. Job 을 돌려준다
```

`launchAutoSync` 가 `Job` 을 돌려주는 이유는 호출부가 기다리기 위해서가 아니라 **테스트가 기다릴 수
있어야 하기 때문**이다. `IssueService` 는 그 `Job` 을 버린다. scope 는 주입받는다(`@Bean` 하나가
`CoroutineScope(SupervisorJob() + Dispatchers.IO)` 를 준다) — 서비스가 scope 를 스스로 만들면
테스트가 그것을 붙잡을 방법이 없다.

#### `syncManually` 의 세 갈래 — 계약이 `202 {"tracker": IssueTracker}` 이고 `tracker` 는 nullable 이 아니다

| 상황 | 응답 |
|---|---|
| 프로젝트에 `link` 가 없거나 저장소 미지정 | `400 tracker_not_connected` — 조용한 202 를 주지 않는다 |
| `claim` 실패(이미 `SYNCED` 이거나 다른 요청이 진행 중) | 기존 행을 읽어 그대로 `202` — 여기서 멱등이 성립한다 |
| `claim` 성공 후 외부 호출 실패 | `markFailed` 하고 그 상태로 `202` (`syncState="FAILED"`, `syncError` 채움) |
| `claim` 성공 후 외부 호출 성공 | 새 상태로 `202` |

외부 호출 실패를 503 으로 밀어 올리지 않는 이유는, `FAILED` + `syncError` 를 실어 보내는 것이
`IssueTracker` 모양이 존재하는 목적 그대로이기 때문이다. 화면은 같은 자리에서 실패를 읽고 다시
누른다.

- 공통 흐름: 이슈 → 프로젝트 → `project_tracker_link` 조회. 링크가 없거나 저장소가 안 정해졌으면 조용히 끝.
- `syncAutomatically` 는 여기에 심각도 기준 하나를 더 건다.
- 선점(Step 2 의 `claim`) → 0행이면 종료 → registry 에서 client 를 꺼내 `createIssue` →
  `markSynced` / 실패 시 `markFailed(sync_error)`.
- `sync_error` 에 담는 것은 **우리가 쓴 요약 문구 + 상태 코드**다. 외부 응답 본문 원문을 넣지 않는다
  (`error-handling.md` 의 4xx 규약과 같은 이유 — 이 값은 화면으로 나간다).
- `launchAutoSync` 는 `CoroutineScope(SupervisorJob() + Dispatchers.IO)` 에 던지고 예외를 전부 로그로만
  남긴다. **`recordAgentIssue` 는 이것을 기다리지 않는다.** GitHub 이 죽어 있어도 QA 런은 그대로 돈다.
- `reflectResolved`/`reflectReopened` 는 `SYNCED` 이고 `external_key` 가 있는 링크에만 동작한다. 실패는
  로그만 남기고 `resolve`/`reopen` 응답을 바꾸지 않는다 — 사람이 누른 상태 전이가 GitHub 때문에
  실패하면 안 된다. `sync_state` 도 되돌리지 않는다: 이슈는 이미 저쪽에 있으므로 `FAILED` 로 내리면
  재시도가 이슈를 하나 더 만든다.
- **굳은 `PENDING` 을 다시 `claim` 한 자리에서는 issue id 를 실어 warn 로그를 남긴다.** 열어 둔 창을
  숨기지 않기 위한 유일한 장치라, 코드에서 빠지면 중복을 사람이 찾을 방법이 없다.

`tracker/service/TrackerIssueBodyWriter.kt` — 본문 조립.

- agent payload 는 **경계에서 선언된 타입으로 파싱한다**:
  ```kotlin
  @JsonIgnoreProperties(ignoreUnknown = true)
  data class AgentIssueDetail(
      val expected: String? = null,
      val actual: String? = null,
      val steps: List<String>? = null
  )
  ```
- 본문에 싣는 것: 심각도, 기대 동작, 실제 동작, 재현 절차, 원본 런으로 돌아가는 home 링크,
  그리고 아는 키로 덮이지 않은 원본 payload 를 접힌 ```json 블록으로. 파싱이 실패해도 원본 블록은 남는다.
- home 링크는 `<frontendOrigin>/projects/{projectId}/qa-tries/{qaTryId}` 로 만든다. ⚠️ home 의 실제
  라우트는 이 저장소에서 확인할 수 없다(이 작업의 소유 범위는 orchestration-server 한 곳이다).
  링크가 틀려도 정보를 잃지 않도록 본문에 **프로젝트 id·QA 실행 id·이슈 id 를 글자로도 함께 적는다.**
  라우트 확인은 수동 검증 항목이자 최종 보고의 확인 요청으로 올린다.

### Step 8: 기존 경로에 붙이기

- `IssueService.recordAgentIssue` — `DataIntegrityViolationException` 으로 흡수된 재전송이면 **부르지
  않는다**. 새 행일 때만 `trackerSync.launchAutoSync(id)`. 반환 타입은 그대로 `Long` 이라
  `QaAgentInboundRouter` 는 손대지 않는다.
- `IssueService.resolve`/`reopen` — 영향 행이 1일 때만 `reflectResolved`/`reflectReopened`. 0행(이미 그
  상태)이면 외부 호출도 하지 않는다.
- `IssueService.page` — 응답 조립 직전에 `IssueTrackerLinkRepository.findByIssueIdIn(ids)` 로 한 번에
  읽어 `toResponse` 에 넘긴다(N+1 을 만들지 않는다).
- `IssueResponse` 에 `val tracker: IssueTrackerResponse? = null` 추가.
- `IssueController` 에 `POST /{issueId}/tracker-sync` 추가 → `202 {"tracker": ...}`.
  권한 판정은 기존 `requireAccessible` 과 같다(실행 접근 = 프로젝트 참여).

의존 방향: `IssueService → IssueTrackerSyncService`. 역방향이 없어(sync 는 repository 만 본다) 순환이 없다.

### Step 9: 테스트

`src/test/kotlin/kr/artel/orchestration/tracker/`

- `FakeIssueTrackerClient.kt` — `@TestConfiguration` 으로 등록하는 대역. 생성/닫기/열기 호출을 기록하고,
  `failNext` 로 실패를 흉내낸다. 실제 GitHub 에 절대 닿지 않는다.
  - **게이트를 함께 둔다**: `createIssue` 가 `CompletableDeferred` 를 `await` 할 수 있게 하고, 테스트가
    두 코루틴이 모두 그 안에 들어온 것을 확인한 뒤 풀어 준다. 게이트가 없으면 첫 요청이 `markSynced`
    까지 끝낸 뒤 둘째가 도는 순서가 되어, **conflict 분기를 한 번도 지나지 않고 테스트가 통과한다** —
    그 분기가 이 테스트의 존재 이유다.
- `ProjectTrackerLinkHttpIntegrationTest` — 계약 표의 경로·상태 코드·JSON 모양.
  - 소유자: GET/PUT/DELETE 모두 통과, `TrackerLink` 필드가 계약대로.
  - 참여자(비소유자): GET 200, PUT/DELETE 403.
  - 비참여자: 전부 404.
  - 연결 없음: `{"link": null}`.
  - App 설정 없음: install-url/repositories 가 503 `tracker_not_configured`.
- `IssueTrackerSyncTest` — 서비스 규칙.
  - 심각도 기준: `BLOCKER` 는 나가고 `MINOR` 는 안 나간다. 기준을 `[]` 로 두면 아무것도 안 나간다.
  - 멱등: 게이트를 닫아 둔 채 `syncAutomatically` 를 동시에 두 번(`async` 둘) 태우고, 둘 다 진입한
    뒤 게이트를 풀어 `awaitAll` → 대역의 생성 호출 **정확히 1회**, `link` 는 1행.
  - 재전송 흡수: 같은 `messageId` 로 `recordAgentIssue` 를 두 번 → 각 호출이 돌려준 `Job` 을 `join`
    한 뒤 생성 호출 1회.
  - 실패 기록: 대역이 던지면 `sync_state='FAILED'`, `sync_error` 채워짐. 그 뒤 `syncManually` 가 다시
    `claim` 해 성공하면 `SYNCED`.
  - 굳은 `PENDING`: `updated_at` 을 과거로 밀어 두면 `syncManually` 가 다시 `claim` 한다.
  - 수동 경로의 세 갈래: `link` 없음 → 400, 이미 `SYNCED` → 기존 상태로 202(생성 호출 0회 추가),
    외부 실패 → `FAILED` 를 실은 202.
  - **fire-and-forget 을 기다리는 방법은 하나로 통일한다** — `launchAutoSync` 가 돌려주는 `Job` 을
    `join` 한다. `delay` 로 재우지 않는다(`testing.md`: sleep 금지).
  - `resolve` → 대역의 close 1회, `reopen` → reopen 1회. 링크 없는 이슈에는 아무 호출도 없다.
  - GitHub 이 죽어 있어도(`recordAgentIssue` 에서 대역이 던져도) 저장은 성공하고 id 가 돌아온다.
- **기존 `IssueHttpIntegrationTest` 를 확장한다**(새 파일을 만들지 않는다) — 그 파일이 이미 이슈 API 의
  HTTP 계약을 소유하고 seeding helper 를 들고 있다. 프로젝트 목록과 실행 목록 양쪽에 `tracker` 가
  실리는지, 내보내지 않은 이슈는 `null` 인지 확인한다.
- `TrackerSetupStateTest` — 서명한 state 만 통과한다. 위조·만료·다른 audience 는 거절.
  콜백에서 남의 프로젝트 state 를 들고 오면 붙지 않는다.
- 콜백 보안 테스트:
  - `code` 없이 온 콜백은 행을 만들지 않고 `?tracker=failed` 로 되돌린다.
  - `code` 가 가리키는 사람의 installation 목록에 없는 `installation_id` 는 저장되지 않는다
    (대역이 목록을 돌려준다).
  - 세션 쿠키 없이 콜백을 부르면 JSON 401 이 아니라 `?tracker=failed` 리다이렉트다.
  - **유효한 세션 쿠키**로 부른 콜백은 새 체인을 지나 controller 에 닿아 `?tracker=connected` 로
    되돌아온다 — 인증을 옮겨 싣지 않으면 여기서 깨진다.
  - token 교환이 `Accept: application/json` 으로 나간다.

`OpenApiSnapshotTest` 가 `docs/api/openapi.json` 을 다시 떨구므로 그 파일도 함께 커밋한다.

### Step 10: Rollout / Rollback

- 롤아웃: 마이그레이션이 테이블 둘을 더할 뿐이고 기존 테이블을 건드리지 않는다. `artel.tracker.github.*`
  가 비어 있으면 연동 endpoint 만 503 이고 QA 경로는 그대로다 — 배포와 App 등록의 순서를 강제하지 않는다.
- installation access token 캐시는 메모리에만 있다. 재기동하면 비고 다음 내보내기에서 한 번 더
  발급받는다. DB 에 두지 않는 것이 요점이므로(비밀을 남기지 않는다) 이 비용은 의도된 것이다.
- 롤백: `git revert`. 테이블은 남지만 아무도 읽지 않는다. 이미 만들어진 GitHub 이슈는 되돌리지 않는다
  (되돌릴 이유도 없다 — 사람이 처리 중일 수 있다).

## Validation

- **Commands to run:**
  - `./scripts/check-flyway-migrations.sh` — `V64` 이 충돌하지 않는지.
  - `./mvnw test` — 전체 스위트(Testcontainers 가 Postgres 를 띄우므로 docker 필요).
- **Expected output:** 마이그레이션 체크 `OK` 또는 peer 경고만(exit 2), 테스트 스위트 초록.
- **수동 확인(사람이 해야 함, PR 에 적는다):**
  1. GitHub App 을 등록하고 `ARTEL_GITHUB_APP_ID` / `ARTEL_GITHUB_APP_SLUG` /
     `ARTEL_GITHUB_APP_PRIVATE_KEY` / `ARTEL_GITHUB_APP_CLIENT_ID` / `ARTEL_GITHUB_APP_CLIENT_SECRET`
     을 채운다.
     - 권한: Repository permissions → Issues: **Read & write**, Metadata: **Read-only**.
     - **Request user authorization (OAuth) during installation: 켠다.** 이것이 켜져 있어야 콜백에
       `code` 가 실리고, 그 `code` 없이는 남의 `installation_id` 를 막을 방법이 없다.
     - **Callback URL 과 Setup URL 을 둘 다** `<서버>/api/tracker/github/setup` 으로 맞춘다.
     - private key 는 PKCS#8 로 변환해서 넣는다:
       `openssl pkcs8 -topk8 -inform PEM -outform PEM -nocrypt -in key.pem`.
  2. 테스트 저장소에 설치 → 콜백이 `?tracker=connected` 로 되돌아오는지.
  3. 저장소 목록이 나오는지, `PUT` 으로 연결되는지.
  4. `BLOCKER` 결함 한 건을 흘려 GitHub 이슈가 생기는지, 본문에 심각도·기대·실제·재현 절차·런 링크가
     들어 있는지.
  5. home 에서 해결로 표시하면 그 이슈가 닫히고, 재개하면 다시 열리는지.
  6. 이슈 본문의 home 링크가 실제로 그 QA 실행을 연다(라우트 확인). 틀리면 후속으로 경로만 고친다.
  7. 다른 계정으로 만든 프로젝트의 설치 주소를 받아, 그 `state` 에 **내 조직의 `installation_id`** 를
     붙여 콜백을 열어 본다. 붙지 않고 `?tracker=failed` 로 되돌아와야 한다.

## Risks & Rollback

- **Risks:**
  - GitHub App private key 를 환경변수로 싣는 경로가 처음이다. 개행 이스케이프를 잘못 다루면 서명이
    조용히 실패한다 — 파싱 실패를 `tracker_not_configured` 로 명시적으로 드러내 침묵을 막는다.
  - 굳은 `PENDING` 을 유예 시간으로 되살리는 설계라, 유예보다 오래 걸리는 GitHub 호출이 있으면 이론상
    이슈가 둘 생길 수 있다. 요청 타임아웃(15초)이 유예(5분)보다 훨씬 짧아 실제로는 닫혀 있다.
    두 값의 관계를 `GitHubAppProperties`/`TrackerProperties` 주석에 남긴다.
  - home 의 런 상세 라우트를 이 저장소에서 확인할 수 없다. 링크가 틀리면 이슈 본문의 링크만 죽는다
    (기능은 산다). 보고에 확인 항목으로 남긴다.
  - `IssueResponse` 에 필드가 하나 늘어 `docs/api/openapi.json` 스냅샷이 바뀐다. 의도된 변경이다.
  - **외부 호출 성공과 `markSynced` 사이에 프로세스가 죽으면** 유예가 지난 뒤의 재시도가 같은 결함으로
    두 번째 외부 이슈를 만든다. 창이 한 문장 폭이라 열어 두기로 했고(위 멱등 절 참고), 재`claim` 은
    warn 로그로 흔적을 남긴다.
  - 자동 `sync` 가 아직 `PENDING` 인 동안 사람이 resolve 하면 `reflectResolved` 가 `SYNCED` `link` 를
    찾지 못해 아무 일도 하지 않고, 직후 만들어진 외부 이슈는 열린 채 남는다. 창이 좁고 사람이 다시
    resolve 를 누르면 풀린다.
  - App 에 user authorization 을 켜야 하므로, 이미 설치된 App 이 있다면 설정을 바꾸고 **Callback URL 과
    Setup URL 을 같은 값으로** 맞춰야 한다. 맞지 않으면 설치 후 복귀 지점이 갈린다.
- **Rollback steps:** `git revert` 후 재배포. 설정을 비우는 것만으로도 연동은 즉시 꺼진다.

## Open Questions

- home 의 런 상세 경로가 `/projects/{projectId}/qa-tries/{qaTryId}` 가 맞는가. 이 저장소에서 확인할 수
  없어 최종 보고의 확인 요청으로 올린다. 틀려도 얼어 있는 계약은 바뀌지 않고 이슈 본문의 링크만
  고치면 된다(본문이 id 를 글자로도 싣기 때문에 정보 자체는 잃지 않는다).

## Rejected feedback

- **"PUT body 필드 이름을 ARTEL-670 스토리 본문의 `repositoryOwner`/`repositoryName` 으로 맞추라"** —
  거절. 얼어 있는 계약이 `workspace`/`repository` 이고 artel-home 워커가 지금 그 이름으로 짜고 있다.
  스토리 본문이 낡은 것이며, 사실만 보고에 남긴다.
- **"installation access token 을 DB 에 캐시하라"** — 거절. DB 에 어떤 비밀도 쓰지 않는 것이 이
  설계의 전제다. 재기동 후 한 번 더 발급받는 비용이 비밀을 저장하는 위험보다 싸다.
- **"취소된 설치를 감지하는 별도 endpoint·백그라운드 확인"** — 거절(범위 밖). 취소는 다음 내보내기가
  `FAILED` + `sync_error` 로 드러내고, 화면이 그것을 보여준다.

## Pair review 결과 (구현 후)

`pair-review` 의 지적을 반영한 것:

- **client secret 이 query 에 실려 로그로 샐 수 있었다** — token 교환을 form body(POST)로 옮겼다.
  `GitHubAppProperties` 가 private key 에 대해 약속한 것과 같은 규칙을 client secret 에도 적용한다.
- **테스트가 실제로 github.com 을 불렀다** — `verifyInstallationBelongsToCaller` 가 App 설정이 없으면
  네트워크에 나가기 전에 `false` 로 끝낸다. 이것이 운영에서도 옳다(빈 `client_id` 로는 교환이 성립하지
  않는다). `testing.md` 의 network reliance 금지도 함께 지켜진다.
- **5xx 의 message 가 `syncError` 로 화면에 나갔다** — `summarize` 가 `status.is4xxClientError` 일 때만
  message 를 싣는다. 실제로 새어 나갈 뻔한 것이 installation id 와 upstream status 다.
- **`resolve`/`reopen` 이 GitHub 왕복을 기다렸다** — 자동 경로와 같은 scope 로 던진다. 실패가 응답을
  바꾸지 않는 것만으로는 부족했다. 사람은 15초 매달린 버튼을 먹통으로 읽는다.
- **`htmlUrl` 을 서비스가 `https://github.com/...` 로 조립했다** — 포트에 `webUrlOf` 를 더해 provider 가
  만든다. GitHub Enterprise 처럼 host 가 다른 설치에서 틀린 주소가 나가던 자리다.
- **`installation` 이 있어야 한다는 전제를 호출부가 판정했다** — provider 가 판정한다. 설치 개념이 없는
  다음 provider 를 붙일 때 `upsert` 를 고쳐야 했던 자리다.
- **`WebClient` 를 셋 만들었다** — 빈 하나로 합쳤다. 같은 host 로 나가는 Netty pool 이 셋일 이유가 없다.
- **같은 사용자 오류에 `code` 가 둘이었다** — `unsupported_tracker` 하나로 모았다. artel-home 이 `code`
  로 분기하므로 둘이면 분기를 두 벌 만든다.
- **불가능한 내부 상태를 400 으로 위장했다** — `checkNotNull` 로 500 이 되게 두었다.
- **`IssueTrackerEnvelope.tracker` 가 nullable 이었다** — 계약이 non-null 이므로 맞췄다.
- 빠진 테스트를 더했다: 비참여자의 수동 내보내기(404), `PENDING` 인 동안의 `resolve`(아무 일 없음).

거절한 것은 없다.

## 스위트 전체를 초록으로 만드는 데 필요했던 무관한 수정

`./mvnw test` 는 이 branch 이전에 이미 빨간색이었다 — `origin/develop` 을 그대로 돌리면 **64건**이
실패한다(2026-08-29 확인). 원인은 네 개의 테스트 클래스가 `qa_run` · `qa_try` 를 만들고 치우지 않는
것이고, 실패는 **뒤에 도는 남의 클래스**의 `DELETE FROM app_user` · `DELETE FROM project` 에서 난다.
클래스 실행 순서에 따라 피해자가 달라지므로, 이 branch 가 테스트를 더한 것만으로 피해자 집합이 바뀌었다.

검증 게이트가 `./mvnw test` 라 그대로 둘 수 없어 각 파일에 빠진 정리를 더했다. 기능 변경은 없다:

- `ScreenObservationTest` — 정리 자체가 없었다(`qa_run`).
- `QaReadingsTest` — 정리 자체가 없었다(`qa_try`, `qa_run`).
- `TestScenarioPipelineIntegrationTest` — `qa_try` · `game_instance` 를 남겼다.
- `SceneContextIntegrationTest` — SQL 로 세운 `qa_try` 를 남겼다.
