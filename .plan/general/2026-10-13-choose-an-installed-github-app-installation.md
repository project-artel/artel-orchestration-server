# 2026-10-13 — GitHub App installation 목록 조회와 기존 installation 연결 API 추가

- Date: 2026-10-13
- Jira: ARTEL-980 (스토리 ARTEL-979)
- Status: Implemented

## Goal

프로젝트 OWNER 가 GitHub App 사용자 인가를 한 번 거치면, 자신이 접근할 수 있는 installation 중
하나를 골라 프로젝트에 연결할 수 있게 한다. 이미 App 이 설치된 organization 이나 user 계정을 두 번째
프로젝트에 연결할 때 GitHub "Configure" 화면을 거치지 않는다.

이 문서가 정하는 API 계약은 home Subtask(ARTEL-981)가 그대로 쓴다.

## Non-goals

- game instance 단위 연결.
- Jira provider.
- `GET /user/installations` 결과 100개 초과 페이지 처리. 기존 `REPOSITORY_PAGE_SIZE` 와 같은 제한이다.
- user-to-server token 저장. 교환한 token 은 요청 하나 안에서만 쓰고 버린다.
- 스키마 변경. migration 이 없다.

## Context / Constraints

### 지금 흐름이 막히는 곳

- `GET /api/projects/{projectId}/tracker/github/install-url` 은 항상
  `https://github.com/apps/<slug>/installations/new?state=...` 를 준다.
- 이미 App 이 설치된 계정을 고르면 GitHub 은 "Configure" 화면을 연다. 그 화면에서 저장하지 않거나
  App 의 "Redirect on update" 가 꺼져 있으면 `/api/tracker/github/setup` 으로 돌아오지 않는다.
- 이미 있는 installation 을 프로젝트에 연결하는 경로가 없다.
- `project_tracker_link` 는 `(project_id, provider)` 단위라 저장 구조는 이미 프로젝트마다 다른
  installation 을 담을 수 있다.

### ARTEL 로그인 token 을 재사용할 수 없는 이유

- 로그인은 GitHub OAuth App 과 이메일 로그인 두 가지이고, GitHub OAuth token 은 저장하지 않는다.
- 로그인용 OAuth App 과 tracker 용 GitHub App 은 서로 다른 App 이다. `GET /user/installations` 는
  **해당 GitHub App 의** user-to-server token 으로만 그 App 의 installation 을 돌려준다.

그래서 GitHub App 의 사용자 인가(`/login/oauth/authorize?client_id=<App client id>`)를 한 번 거친다.
2026-08-28 계획대로 App 의 Callback URL 과 Setup URL 은 둘 다 `/api/tracker/github/setup` 이므로,
인가가 끝나면 같은 경로로 `code` 와 `state` 만 실려 돌아온다(`installation_id` 없음).

### 지켜야 하는 보안 성질

1. 서명된 `state` 는 "이 사람이 이 프로젝트의 OWNER 이고 지금 돌아온 세션과 같다" 를 증명한다. 기존
   `TrackerSetupStateService` 를 그대로 쓴다.
2. 호출한 사람이 **인가 시점에** GitHub 에서 접근할 수 있었던 installation 만 연결된다. `PUT` 은
   클라이언트가 보낸 `installationId` 를 믿지 않고, 인가 복귀에서 서버가 GitHub 에서 직접 읽어 Redis 에
   둔 목록과 대조한다. 인가 뒤 10분(목록 TTL) 안에 GitHub 에서 권한이 빠져도 `PUT` 은 통과한다.
   user token 을 저장하지 않으므로 `PUT` 시점에 다시 확인할 수 없고, 기존 설치 복귀의 소유 확인도
   같은 시점 확인이다. 이 차이는 받아들인다.
   - GitHub 의 `GET /user/installations` 는 그 사람이 저장소 하나에라도 read 이상 권한을 가진 installation 을
     모두 돌려준다. organization admin 일 필요는 없다. 연결한 뒤에는 installation token 으로 그 installation 의
     모든 저장소를 읽고 쓸 수 있다. 기존 설치 복귀도 같은 기준이고 이번 요구를 충족하므로 받아들인다.
     저장소 선택 목록을 `GET /user/installations/{id}/repositories` 로 거르는 것은 후속 작업이다.
3. user token 은 저장하지 않는다. Redis 에는 installation id, 계정 이름, 계정 종류만 둔다.

### 기존 코드

- `GitHubInstallationService` 에 `installUrl`, `verifyInstallationBelongsToCaller`,
  `exchangeUserToken`(private), `listRepositories` 가 있다.
- `GitHubTrackerSetupCallbackTest` 는 user token 교환이 실제 GitHub 을 부른다는 이유로 성공 경로를
  검증하지 못한다. 이번 작업에서 교환과 목록 조회를 interface 뒤로 옮겨 fake 로 대체할 수 있게 한다.
- Redis 사용 선례는 `SdkLoginCodeStore`(`ReactiveStringRedisTemplate`, 키 TTL 로 만료) 다.
- 설정은 `@ConfigurationProperties` 로만 받는다(`configuration.md`). 오류는 `common/error` 의 타입
  예외로 던진다(`error-handling.md`).

## API 계약

모든 `/api/projects/{projectId}/tracker/github/**` 경로는 OWNER 만 부른다. 참여자가 아니면 404,
참여자지만 OWNER 가 아니면 403 이다. 기존 `ProjectTrackerLinkService.requireOwner` 규칙과 같다.

| Method | Path | 응답 | 오류 |
| --- | --- | --- | --- |
| GET | `/api/projects/{projectId}/tracker/github/authorize-url` | `{ "url": "<webBaseUrl>/login/oauth/authorize?client_id=...&state=..." }` | App 미설정 503 `tracker_not_configured` |
| GET | `/api/projects/{projectId}/tracker/github/installations` | `{ "items": [{ "installationId": "123", "account": "project-artel", "accountType": "ORGANIZATION" }] }` | 목록이 없거나 만료 409 `tracker_authorization_required` |
| PUT | `/api/projects/{projectId}/tracker/github/installation` | `{ "link": TrackerLinkResponse }` (`GET /tracker-link` 와 같은 봉투) | 목록 없음 409 `tracker_authorization_required`, 목록에 없는 id 400 `tracker_installation_not_verified` |

- `installationId` 는 문자열이다. `installation_ref` 컬럼과 기존 콜백 파라미터가 문자열이다.
- `accountType` 은 `USER` 또는 `ORGANIZATION` 이다. GitHub 의 `account.type` 이 `User` 이면 `USER`,
  그 밖의 값(`Organization`, `Enterprise`)이면 `ORGANIZATION` 으로 읽는다.
- `items` 가 빈 배열일 수 있다. App 이 아직 어디에도 설치되지 않은 사람이다.
- `PUT` 본문은 `{ "installationId": "123" }` 이다.

`/api/tracker/github/setup` 의 복귀 주소에 값이 하나 늘어난다.

| 조건 | 복귀 주소 |
| --- | --- |
| `installation_id` 가 있고 소유 확인 성공 | `.../settings?tracker=connected` (기존) |
| `installation_id` 가 없고 `code` 로 목록을 읽음 | `.../settings?tracker=choose` (새로 추가) |
| 그 밖의 실패 | `.../settings?tracker=failed` 또는 `/projects?tracker=failed` (기존) |

## Approach (Checklist)

- [ ] **Step 0: Recon** — 끝났다. 위 Context 에 정리했다.

- [ ] **Step 1: user token 교환과 목록 조회를 interface 뒤로 이동**
  - `tracker/github/GitHubUserInstallationReader.kt`(새 파일):
    ```kotlin
    data class GitHubUserInstallation(val id: Long, val account: String, val accountType: TrackerAccountType)
    interface GitHubUserInstallationReader {
        /** code 를 user token 으로 바꿔 그 사람의 installation 을 읽는다. 어느 단계든 실패하면 null. */
        suspend fun installationsOf(code: String): List<GitHubUserInstallation>?
    }
    ```
    `TrackerAccountType { USER, ORGANIZATION }` 은 응답 DTO 도 쓰므로 `tracker/dto/TrackerDtos.kt` 에 둔다.
    GitHub `account.type` 이 `Enterprise` 인 경우도 `ORGANIZATION` 으로 읽는다는 것을 주석에 적는다.
  - 구현 `GitHubUserInstallationClient`(`@Component`)는 `GitHubInstallationService` 의
    `exchangeUserToken` 과 `GET /user/installations` 호출을 그대로 옮긴다. 주석(`client_secret` 을
    query 에 싣지 않는 이유, `Accept: application/json` 을 덮어쓰는 이유)도 함께 옮긴다.
    `properties.configured` 가 false 이면 네트워크를 부르지 않고 null 이다. null 을 돌려주는 자리마다
    이유를 `warn` 로그로 남긴다(token 교환 거절, 교환 실패, 목록 조회 실패).
    응답의 `total_count` 가 받은 항목 수보다 크면 목록이 잘렸다는 `warn` 로그를 남긴다. 페이지 처리는
    Non-goal 이다.
  - private `exchangeUserToken` 은 `GitHubInstallationService` 에서 지운다. user token 을 다루는 경로는
    이 구현 하나만 남는다.
  - `GitHubDtos.kt`: `GitHubInstallationRef` 에 `account: GitHubAccount?` 를 더한다
    (`login`, `type`). `account` 가 없는 항목은 목록에서 뺀다.
  - `GitHubInstallationService.verifyInstallationBelongsToCaller` 는 reader 를 써서
    `installationsOf(code)?.any { it.id == installationId } == true` 로 줄인다. 동작은 바뀌지 않는다.

- [ ] **Step 2: 인가 주소**
  - `GitHubInstallationService.authorizeUrl(state)`:
    `${webBaseUrl}/login/oauth/authorize?client_id=<URL encode>&state=<URL encode>`.
    `redirect_uri` 는 싣지 않는다. App 에 등록된 Callback URL 이 쓰인다.
    `properties.configured` 가 false 이면 `TrackerNotConfiguredException`.
  - `GitHubTrackerController` 에 `GET /authorize-url` 을 추가한다. `install-url` 과 같은 순서
    (`requireOwnerForSetup` → `stateService.issue` → URL)이므로, 두 handler 가 URL 을 만드는 함수만 넘기는
    private helper 하나를 같이 쓴다. 응답 DTO 는 `TrackerInstallUrlResponse(url)` 를 재사용한다.
  - `state` TTL 은 기존 `AuthProperties.trackerSetupStateTtl`(15분)을 그대로 쓴다. 만료되면 기존처럼
    `/projects?tracker=failed` 로 가고, 화면에서 다시 "GitHub 연결"을 누르면 된다.

- [ ] **Step 3: 확인된 installation 목록 저장소**
  - `tracker/service/TrackerInstallationChoiceStore.kt`(새 파일, `@Component`).
    - 키: `artel:tracker:github:installations:<projectId>:<userId>`.
    - 값: `List<GitHubUserInstallation>` 의 JSON(Jackson `ObjectMapper` bean). 읽을 때는
      `jacksonTypeRef<List<GitHubUserInstallation>>()` 를 쓴다. `List::class.java` 로 읽으면 map 목록이 된다. 필드가 셋이고 목록이라
      `SdkLoginCodeStore` 처럼 구분자로 잇는 방식은 쓰지 않는다.
    - `save(projectId, userId, items)`: `SET` + TTL. 같은 키는 덮어쓴다.
    - `read(projectId, userId): List<GitHubUserInstallation>?`: 없으면 null.
    - `delete(projectId, userId)`.
    - Redis 장애는 예외로 그대로 올린다(`SdkLoginCodeStore.consume` 과 같은 판단).
    - `PUT` 은 `read` 와 `delete` 를 따로 부르므로 동시에 온 `PUT` 두 개가 모두 통과할 수 있다. 둘 다 같은
      사람의 확인된 목록 안의 id 이고 `attachInstallation` 은 upsert 라 결과가 망가지지 않는다. `GETDEL`
      을 쓰지 않는 이유는 목록에 없는 id 로 온 잘못된 `PUT` 하나가 목록을 지워 버리면 안 되기 때문이다.
  - TTL 은 `GitHubAppProperties.installationChoiceTtl: Duration = Duration.ofMinutes(10)` 으로 받는다.
    `application.yml` 에 `installation-choice-ttl: ${ARTEL_GITHUB_INSTALLATION_CHOICE_TTL:PT10M}` 를,
    `.env.example` 에 같은 변수를 추가한다.

- [ ] **Step 4: 설치 복귀 경로의 분기 추가**
  - `GitHubTrackerSetupController.setup`:
    - `state` 검증과 세션 일치 판정은 그대로 먼저 한다. 지금은 `installationId.isNullOrBlank()` 이면
      바로 실패인데, 이 조건을 나눈다.
    - `installation_id` 가 있으면 기존 경로를 그대로 탄다.
    - `installation_id` 가 없고 `code` 가 있으면
      `linkService.requireOwnerForSetup` → `reader.installationsOf(code)` → null 이면 `failed`,
      아니면 `choiceStore.save(...)` 후 `settings?tracker=choose` 로 보낸다.
    - 둘 다 없으면 `failed`. 사용자가 GitHub 인가 화면에서 취소하면 `error=access_denied` 만 실려 오므로
      이 분기로 들어간다. 화면은 실패 이유를 구분하지 않는다(기존 판단과 같다).
    - 예외를 밖으로 내보내지 않는다는 기존 규칙(try/catch 후 302)을 새 분기도 따른다.

- [ ] **Step 5: 목록 조회와 installation 연결 endpoint**
  - `GitHubTrackerController`:
    - `GET /installations`: `requireOwnerForSetup` → `choiceStore.read` → null 이면
      `TrackerAuthorizationRequiredException`(409, `tracker_authorization_required`).
      응답 `TrackerInstallationPageResponse(items: List<TrackerInstallationResponse>)`.
    - `PUT /installation`: 본문 `TrackerInstallationSelectRequest(@field:NotBlank installationId)`.
      **먼저** `requireOwnerForSetup` 으로 404 와 403 을 판정한다. 그다음 `installationId.trim().toLongOrNull()`
      이 null 이면 400 `tracker_installation_not_verified`. `choiceStore.read` → null 이면 409. 목록에 없으면 `TrackerInstallationNotVerifiedException`
      (400, `tracker_installation_not_verified`). 있으면
      `linkService.attachInstallation(...)` → `choiceStore.delete` → `linkService.read(...)` 로 다시 읽어
      `TrackerLinkEnvelope(link)` 를 돌려준다.
  - 새 예외 둘은 `IssueTrackerClient.kt` 의 기존 tracker 예외 옆에 둔다.

- [ ] **Step 6: installation 이 바뀌면 고른 저장소를 비움**
  - `ProjectTrackerLinkRepository.attachInstallation` 의 `ON CONFLICT DO UPDATE` 에서
    `external_workspace`, `external_repository` 를
    `CASE WHEN project_tracker_link.installation_ref IS NOT NULL AND project_tracker_link.installation_ref <> EXCLUDED.installation_ref THEN NULL ELSE project_tracker_link.<column> END`
    로 바꾼다. 기존 값이 NULL 인 행은 비우지 않는다. 지금 흐름에서는 `upsertTarget` 이
    `verifyRepositoryAccess` 를 먼저 부르고, GitHub 구현은 installation 이 없으면 거절하므로 그런 행이
    생기지 않는다. 그래도 NULL 을 "다른 값" 으로 읽어 저장소를 지우면 안 된다.
  - 같은 installation 으로 재설치하면 저장소를 유지한다는 기존 성질은 그대로다. 다른 installation 이
    붙으면 이전 저장소는 새 installation token 으로 접근할 수 없을 수 있으므로 비운다. 이 규칙은 설치
    복귀와 `PUT /installation` 두 경로에 똑같이 적용된다.
  - KDoc 을 이 규칙에 맞게 고친다.

- [ ] **Step 7: 이미 내보낸 이슈는 건드리지 않음**
  - `issue_tracker_link` 행과 GitHub 에 이미 만든 이슈는 그대로 둔다. 연결 해제(`delete`)와 같은 판단이다.
  - ⚠️ `IssueTrackerSyncService` 는 close 와 reopen 을 할 때 **지금** 연결된 저장소(`SyncContext.target`)에
    예전 `external_key` 를 보낸다. 저장소가 바뀐 뒤에는 새 저장소의 같은 번호 이슈를 닫거나 다시 열 수 있다.
    이 문제는 기존 `PUT /tracker-link` 로 저장소를 바꿀 때도 이미 있다. 이번 작업은 고치지 않고 별도 Jira
    작업으로 등록한다(`issue_tracker_link` 에 내보낸 저장소를 기록하고, 다르면 sync 를 건너뛰는 방향).

- [ ] **Step 8: 문서**
  - `docs/api/openapi.json` 을 갱신한다(기존 생성 방식을 따른다).
  - `.env.example` 과 `application.yml` 주석에 "App 의 Callback URL 이 `/api/tracker/github/setup` 이어야
    `authorize-url` 흐름이 돌아온다" 를 적는다.

## Tests

`tracker/GitHubInstallationChoiceHttpIntegrationTest.kt`(새 파일). `@TestConfiguration` 에서
`@Primary` fake `GitHubUserInstallationReader` 를 등록한다. fake 는 `code` 별로 돌려줄 목록을 들고,
모르는 `code` 에는 null 을 돌려준다. Redis 는 기존 테스트 환경의 것을 쓰고 각 테스트 전후에 키를 지운다.

| 테스트 | 확인하는 것 |
| --- | --- |
| `code` 만 실린 복귀는 목록을 저장하고 `tracker=choose` 로 보낸다 | 302 Location, `GET /installations` 결과 |
| fake 가 null 을 주면 `tracker=failed` 이고 목록이 저장되지 않는다 | 302 Location, `GET /installations` 409 |
| `installation_id` 도 `code` 도 없으면 `tracker=failed` | 302 Location |
| 목록에 있는 id 로 `PUT` 하면 link 가 `installed=true` 이고 목록이 지워진다 | 응답 봉투, 두 번째 `PUT` 409 |
| 목록에 없는 id 로 `PUT` 하면 400 `tracker_installation_not_verified` | 오류 code, 행 없음 |
| 목록 없이 `PUT` 하면 409 `tracker_authorization_required` | 오류 code |
| 다른 사용자의 목록으로는 `PUT` 할 수 없다 | 같은 프로젝트의 다른 OWNER 가 409 |
| 저장소가 고른 상태에서 다른 installation 을 붙이면 `repository` 가 null 이 된다 | `GET /tracker-link` |
| 같은 installation 을 다시 붙이면 `repository` 가 유지된다 | `GET /tracker-link` |
| MEMBER 는 세 endpoint 모두 403, 참여자가 아니면 404 | status |
| 설치 복귀(`installation_id` 경로)로 다른 installation 이 붙어도 `repository` 가 null 이 된다 | `GET /tracker-link` |
| `installation_ref` 가 NULL 이고 저장소가 있는 행에 installation 을 붙여도 저장소가 유지된다 | 행을 직접 넣고 `GET /tracker-link` |
| `error=access_denied` 만 실린 복귀는 `tracker=failed` | 302 Location |
| OWNER 가 아닌 사람의 `code` 복귀는 `tracker=failed` 이고 목록이 저장되지 않는다 | 302 Location |
| `accountType` 이 `User` → `USER`, `Organization` 과 `Enterprise` → `ORGANIZATION` | `GET /installations` |
| 공백뿐인 `installationId` 로 `PUT` 하면 400 | status |
| `authorize-url` 은 `client_id` 와 `state` 를 싣는다 | 테스트 profile 에 App 설정을 채운 경우에만. 아니면 503 `tracker_not_configured` 를 확인한다 |

기존 `GitHubTrackerSetupCallbackTest` 와 `ProjectTrackerLinkHttpIntegrationTest` 는 그대로 통과해야 한다.

## Validation

- **Commands to run:**
  - `./mvnw test -Dtest='GitHubInstallationChoiceHttpIntegrationTest,GitHubTrackerSetupCallbackTest,ProjectTrackerLinkHttpIntegrationTest,IssueTrackerSyncTest'`
  - 전체 suite 는 원래 약 100개가 실패하므로, 이번 변경 전후의 실패 목록을 비교한다.
  - Kotlin daemon 이 시작되지 않으면 in-process 로 실행한다.
- **Expected output:** 위 네 클래스 전부 통과. 전체 suite 의 실패 목록이 변경 전과 같다.
- **Manual (merge 전 필수):** 실제 GitHub App 으로 인가 → `tracker=choose` → 목록 → 연결 흐름은 자동화할 수
  없다. PR 본문에 "staging App 에서 한 번 실행" 을 merge 전 확인 항목으로 적는다. 함께 확인할 App 설정은
  Callback URL 과 Setup URL 이 모두 `/api/tracker/github/setup` 인지, "Request user authorization (OAuth)
  during installation" 이 켜져 있는지다.

## Risks & Rollback

- **Risks:**
  - App 의 Callback URL 이 `/api/tracker/github/setup` 이 아니면 인가 복귀가 다른 곳으로 간다. 배포 전
    App 설정을 확인해야 한다.
  - 사용자가 이미 App 을 인가한 적이 있으면 GitHub 은 확인 화면 없이 바로 돌려보낸다. 기대한 동작이다.
  - installation 이 바뀔 때 저장소를 비우는 규칙은 기존 행에도 적용된다. 같은 id 의 재설치는 영향이 없다.
- **Rollback steps:** `git revert`. 스키마와 데이터 변경이 없다. Redis 키는 TTL 로 사라진다.

## Review notes

plan-review(fast, medium)에서 받은 의견 가운데 반영하지 않은 것과 이유.

- Redis 대신 서명된 grant 를 URL 로 넘기자는 제안: 화면에 계정 이름을 보여 주려면 grant 가 목록 전체를
  실어야 하고 URL 이 길어진다. Redis 는 이 서버의 필수 구성 요소이고 `SdkLoginCodeStore` 선례가 있다.
- TTL 을 설정값 대신 상수로 두자는 제안: 선례인 `SdkLoginCodeStore` 도 `AuthProperties.sdkLoginCodeTtl`
  설정값을 쓴다. 그대로 설정값으로 둔다.
- 저장소 변경 시 기존 `issue_tracker_link` 를 막거나 표시하자는 제안: Step 7 에 적은 대로 별도 작업으로 뺀다.

## Implementation notes

구현하면서 계획과 달라진 것.

- `GitHubAppProperties.installationChoiceTtl` 에 0 보다 커야 한다는 `init { require(...) }` 를 두었다. Redis 는
  TTL 0 을 거절한다.
- `TrackerInstallationChoiceStore.delete` 만 Redis 장애를 삼키고 `warn` 로그를 남긴다. 부르는 시점에는 연결이
  이미 저장되어 있어, 예외를 올리면 성공한 `PUT` 이 500 으로 보인다(pair review 의견).
- 계정 종류 변환은 fake reader 를 거치지 않으므로, 실제 `GitHubUserInstallationClient` 를 네트워크 없는
  `WebClient` stub 으로 부르는 테스트를 하나 더했다.

## Open Questions

- 없음.
