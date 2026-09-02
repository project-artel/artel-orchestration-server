# 2026-09-01 — app_user 에 platform 등급을 두고 통계 조회를 전체 프로젝트로 넓힌다

- Date: 2026-09-01
- Jira: ARTEL-742
- Status: Implemented

## Goal

`app_user` 에 platform 단위 등급을 두고, `DEVELOPER` 등급에게 참여하지 않은 프로젝트의 통계
조회를 연다. admin-page 는 우리 개발자들이 쓰는 도구인데 지금은 다섯 탭이 전부 로그인한 사람이
참여한 프로젝트로 좁혀진다.

## Non-goals

- 등급을 부여하는 화면과 API. 운영 DB 직접 UPDATE 로 하고 그 방법을 문서에 남긴다
- 쓰기 경로 확장. `PUT /api/test-scenario/:testScenarioId/expected-labels`,
  프로젝트 삭제, 기획서 업로드는 전부 지금의 멤버십을 그대로 요구한다
- admin-page 전용 audience 와 별도 로그인
- `OWNER` 와 `MEMBER` 말고 새 프로젝트 역할
- admin-page 변경. 그쪽은 ARTEL-743 이 한다

## Context / Constraints

### 지금 경계가 어디에 있나

인가 판단은 두 자리에 흩어져 있다.

집계 쿼리 안의 `JOIN project_member` — 이쪽이 다수다.

| 파일 | 줄 | 무엇을 좁히나 |
|---|---|---|
| `qa/repository/QaStatsRepository.kt` | 72 | `GET /api/qa-stats` |
| `qa/repository/QaRepositories.kt` | 108 | `GET /api/qa-tries` (`QaTryRepository.findByProject`) |
| `llmusage/repository/LlmUsageStatsRepository.kt` | 97, 233 | `GET /api/llm-usage/stats`, `/qa-runs` — **develop 에 없다, 아래 참조** |
| `knowledge/repository/KnowledgeStatsRepository.kt` | 83 | `GET /api/knowledge-stats` |
| `project/repository/ProjectRepository.kt` | 16, 27 | `GET /api/projects` 목록과 개수 |

서비스 안의 명시적 확인 — 한 자리다.

| 파일 | 줄 | 무엇을 좁히나 |
|---|---|---|
| `knowledge/service/KnowledgeGraphViewService.kt` | 42 | `GET /api/projects/:projectId/knowledge-graph` |
| `testscenario/service/TestScenarioService.kt` | 61 | `GET /api/projects/:projectId/test-scenario` |
| `testscenario/service/TestScenarioService.kt` | 72 | `GET /api/projects/:projectId/test-scenario/:testScenarioId` |

`ProjectRepository` 의 KDoc 이 이 배치의 근거를 적어 두었다 — 조건을 서비스가 아니라 질의에 두어야
빠뜨렸을 때 남의 행이 조용히 새어나가지 않는다. 그 배치를 뒤집지 않는다.

### 손대지 않는 자리

`ProjectAccessService.requireMember` 는 건드리지 않는다. 프로젝트 삭제, 기획서 업로드, 시나리오
수정이 전부 그 함수를 거쳐 가므로, 거기서 `DEVELOPER` 를 통과시키면 읽기를 열려다 쓰기가 함께
열린다.

같은 함정이 `TestScenarioAccessService.accessibleScenario` 에 하나 더 있다. 호출부가 일곱인데 그중
넷이 쓰기다 — `testScenarioUpdate`, `testScenarioApprove`, `updateExpectedLabels`, `delete`. 그래서
이 함수도 넓히지 않고, 읽기 전용 형제를 따로 두어 읽는 두 자리만 그것을 부른다.

이 작업이 넓히는 것은 위 표의 여덟 개 질의와 세 개 확인뿐이다.

### 등급을 어디서 읽나

JWT claim 이 아니라 요청마다 DB 에서 읽는다. claim 에 실으면 등급을 내려도 access token 이
만료될 때까지(15분) 유효하다. `AuthUserResponse` 의 KDoc 이 프로필을 토큰이 아니라 DB 에서 읽는
이유로 이미 같은 논증을 폈다.

읽는 비용은 넓히는 경로에만 든다. `@CurrentUserId` 리졸버에 DB 조회를 넣으면 그 애너테이션을 쓰는
모든 엔드포인트가 질의 하나씩을 더 내게 되므로, 리졸버는 그대로 두고 별도 서비스를 둔다.

### `토큰 사용량` 탭 때문에 base 가 develop 이 아니다

`LlmUsageStatsRepository`, `LlmUsageStatsService`, `LlmUsageStatsController` 는 `develop` 에 없다.
셋 다 ARTEL-715(PR #236)가 새로 더하는 파일이고 그 PR 은 아직 열려 있다. `develop` 의 `llmusage`
패키지에는 적재 경로(`LlmUsageRepository`, `LlmUsageController`)만 있다.

**이 브랜치의 base 는 `develop` 이 아니라 ARTEL-715 의 브랜치다.** 사용자가 그렇게 정했다. 그러지
않으면 ARTEL-741 의 Acceptance Criteria 중 `토큰 사용량` 탭 한 줄이 이 작업으로 만족되지 않고,
그 탭만 자기 프로젝트로 좁은 채 남는다.

대가는 두 가지다. ARTEL-715 가 머지되기 전에는 이 PR 도 머지할 수 없고, 리뷰 범위가 두 작업만큼
커진다. PR 의 base 를 ARTEL-715 브랜치로 두어 diff 가 이 작업의 변경만 보이게 한다.

### 마이그레이션 번호

`develop` 의 최고 번호는 `V79` 이고, 열린 PR 셋(#228, #236, #237) 중 마이그레이션을 더하는 것은
없다. 그래서 이 브랜치는 `develop` 에서 그대로 잘랐다.

번호는 `V82` 다. 두 번 비켰다.

- `V80` 은 `ARTEL-730`(PR #238)이 `V80__add_app_user_nickname_and_battle_tag.sql` 로 가져갔다
- `V81` 은 `ARTEL-732`(PR #240)가 `V81__create_email_verification.sql` 로 가져갔다.
  `ARTEL-732` 는 `ARTEL-730` 위에 얹혀 있어 `V80` 과 `V81` 을 함께 든다

둘 다 열린 PR 이고 먼저 push 됐으므로 번호를 두고 이쪽이 비켰다. 두 브랜치가 끝내 머지되지 않으면
`V80` 과 `V81` 자리가 비는데, Flyway 는 번호 사이의 빈칸을 문제 삼지 않는다.

`ARTEL-730` 도 `app_user` 를 고치지만 더하는 컬럼이 `nickname` 과 `battle_tag` 라 이 브랜치의
`platform_role` 과 겹치지 않는다. 부딪치는 것은 번호뿐이다.

## Approach (Checklist)

- [x] **Step 0: Recon** — 위 표의 아홉 자리를 확인했다. `develop` 의 마이그레이션 번호와 열린 PR 의
      마이그레이션 여부를 확인했다

- [x] **Step 1: 등급 컬럼과 읽는 경로**
  - `src/main/resources/db/migration/V84__add_platform_role_to_app_user.sql` — `app_user` 에
    `platform_role VARCHAR(32) NOT NULL DEFAULT 'USER'`. 사람 이름은 넣지 않는다
  - `auth/entity/AppUserEntity.kt` — `platformRole` 컬럼 추가
  - `auth/entity/AppUserEntity.kt` 안에 `PlatformRole` enum(`USER`, `DEVELOPER`)을 함께 둔다.
    `ProjectRole` 이 `ProjectMemberEntity.kt` 안에 사는 것과 같은 배치이고, 컬럼은 그쪽 `role` 처럼
    `String` 으로 든다
  - `auth/service/JwtService.kt` 의 `UserProfile` 과 `auth/dto/AuthUserResponse.kt` — 등급을 싣는다
  - `auth/service/OAuthUserService.kt` — 프로필을 만들 때 등급을 옮긴다
  - `auth/service/PlatformAccessService.kt` (신규) — `suspend fun seesAllProjects(userId: Long): Boolean`
    하나만 둔다

- [x] **Step 2: 여덟 개 질의를 boolean 하나로 연다**
  - 각 질의의 `JOIN project_member pm ON <조건> AND pm.app_user_id = :userId` 를
    `WHERE ... AND (:seesAllProjects OR EXISTS (SELECT 1 FROM project_member pm WHERE <조건> AND pm.app_user_id = :userId))`
    로 바꾼다. `JOIN` 이 아니라 `EXISTS` 로 가는 이유는 두 가지다 — 넓힐 때 멤버 행의 존재 자체를
    요구하지 않게 되고, 한 사람이 같은 프로젝트에 멤버 행을 둘 가졌을 때 행이 겹쳐 세지지 않는다
  - `ProjectRepository` 만 예외로 둔다. 넓힌 목록은 조인이 아예 없는 다른 질의라, 매개변수를
    더하는 것보다 `findActivePage` 와 `countActive` 두 메서드를 더하는 쪽이 읽힌다.
    이름에 `Active` 가 붙은 것은 `CoroutineCrudRepository.count` 가 이미 있고 그쪽은 삭제된 행까지
    세기 때문이다
  - `QaStatsService`, `LlmUsageStatsService`, `KnowledgeStatsService`,
    `QaTryService` (`findByProject` 를 부르는 `QaTryService.kt:557`) 가 `PlatformAccessService` 를
    물어 그 boolean 을 질의에 넘긴다
  - `LlmUsageStatsService.qaRun` (단건)도 함께 넓힌다. `qaRuns` 와 같은 질의를 부르므로, 한쪽만
    넓히면 목록에 뜬 런을 열었을 때 없다고 답한다
  - `LlmUsageStatsRepository.countUnattributedCalls` 는 넓히지 않는다. 프로젝트를 못 푼 행이라
    등급으로 열고 닫을 대상이 없다
  - `KnowledgeGraphViewService.kt:42` — `isMember` 확인에 `seesAllProjects` 를 더한다

- [x] **Step 3: `기대 판정 라벨` 탭의 읽기**
  - `TestScenarioAccessService` 에 읽기 전용 형제를 더한다 — `readableScenario(testScenarioId, appUserId)`.
    `accessibleScenario` 를 그대로 두고, 멤버가 아니어도 `DEVELOPER` 면 통과시킨다
  - `TestScenarioService.listScenarios` (61) 와 `getScenarioInProject` (72) 만 그것을 부른다.
    나머지 다섯 호출부는 `accessibleScenario` 그대로다
  - `updateExpectedLabels` 의 KDoc 에 있는 경고를 갱신한다. 지금 "이 리포에는 프로젝트 밖의 관리자
    개념이 없어 역할로 강제하지 못한다" 라고 적혀 있는데, 이 작업이 그 개념을 만든다. 그러면서도
    이 경로는 여전히 멤버십까지라는 것이 요점이므로, 없어서 못 하는 것이 아니라 하지 않기로 한
    것이라고 고쳐 적는다

- [x] **Step 4: `GET /api/projects` 의 `scope`**
  - `ProjectController.list` 에 `@RequestParam(defaultValue = "MINE") scope: ProjectScope`.
    문자열이 아니라 enum 이라 값의 목록이 `openapi.json` 에 그대로 실린다
  - `ProjectService.list` 가 `ALL` 이면 `DEVELOPER` 인지 확인하고 전 프로젝트를, 아니면 지금 동작
  - `USER` 가 `ALL` 을 주면 403. `common/error` 의 타입 예외로 던진다.
    `ProjectAccessDeniedException` 이 `ForbiddenException` 을 상속하는 그 자리와 같은 꼴이다
  - `MINE` 과 `ALL` 말고 다른 값은 Spring 의 enum 변환이 400 으로 떨어뜨린다

- [x] **Step 5: Tests**
  - 통계 다섯 경로 각각에 대해 `DEVELOPER` 는 남의 프로젝트에서 값을 받고 `USER` 는 빈 집계를 받는
    integration test. 기존 `QaStatsIntegrationTest`, `LlmUsageStatsIntegrationTest`,
    `KnowledgeStatsIntegrationTest`, `KnowledgeGraphViewIntegrationTest` 안의 비참여자 테스트 바로
    옆에 붙인다 — 두 방향을 나란히 두지 않으면 조건을 통째로 지워도 한쪽은 녹색이다
  - `GET /api/qa-tries` (`QaTryService.listByProject`)는 원래 어느 테스트도 지나지 않던 자리라
    두 방향을 새로 만든다
  - `GET /api/projects?scope=all` 이 `DEVELOPER` 에게 전 프로젝트, `USER` 에게 403
  - `GET /api/auth/me` 가 `platformRole` 을 싣는다
  - 회귀 — `USER` 등급의 응답이 이 변경 전과 같다. 등급 컬럼이 기본값이므로 기존 테스트가 전부
    그대로 도는 것이 이 확인이다
  - 쓰기가 열리지 않았다는 확인 — `DEVELOPER` 가 남의 프로젝트의 시나리오를 **읽을 수는 있고**
    `PUT expected-labels` 는 지금처럼 막힌다. 한 테스트 안에서 두 방향을 함께 본다. Step 3 이
    같은 접근 서비스에 읽기와 쓰기 두 함수를 나란히 두므로, 나중에 호출부를 잘못 바꾸면 여기서 잡힌다

- [x] **Step 6: 문서와 스냅샷**
  - `docs/` 에 등급 부여 방법을 남긴다. 어떤 SQL 을 치는지, 왜 화면이 없는지, 등급이 무엇을
    열어주고 무엇을 열어주지 않는지
  - `docs/api/openapi.json` — 손으로 고치지 않는다. `OpenApiSnapshotTest` 가 파일을 검증하지 않고
    다시 쓰므로 `./mvnw test` 가 갱신하고, 그 diff 를 함께 커밋한다

## Validation

- **Commands to run:**
  - `./scripts/check-flyway-migrations.sh` — 다른 브랜치가 번호를 먼저 가져갔는지
  - `./scripts/verify-flyway-upgrade.sh` — `develop` 의 마이그레이션 위에 얹어 `validate`
  - `./mvnw clean test` — Testcontainers 가 Postgres 를 띄우므로 docker 필요
- **Expected output:** 앞의 둘은 exit code `0`. 테스트는 전부 통과하고, 새로 붙인 등급별 테스트가
  `DEVELOPER` 와 `USER` 의 응답 차이를 보인다

## Risks & Rollback

- **Risks:**
  - 여덟 개 질의를 `JOIN` 에서 `EXISTS` 로 옮기는 것이 이 작업의 유일한 위험이다. 하나라도 조건을
    잘못 옮기면 `USER` 에게 남의 행이 새어나간다. 그래서 등급별 회귀 테스트가 Step 4 의 첫 항목이다
  - PR #237 이 `QaStatsRepository.kt` 와 `QaStatsService.kt` 를 함께 고치고 있다. 그쪽이 먼저
    머지되면 충돌한다. 머지 순서를 보고 base 를 따라잡는다
  - base 가 ARTEL-715 브랜치이므로 그 PR 이 리뷰에서 `LlmUsageStatsRepository` 의 두 질의를
    고치면 이쪽이 따라잡아야 한다
  - `ARTEL-730`(PR #238)과 `ARTEL-732`(PR #240)가 `V80` 과 `V81` 을 들고 있다. 셋 중 무엇이
    먼저 머지되든 번호는 겹치지 않지만, 또 다른 브랜치가 `V82` 를 집으면 다시 비켜야 한다
  - admin-page 와 artel-home 이 같은 세션을 쓰므로 `DEVELOPER` 는 artel-home 에서도
    `DEVELOPER` 다. 넓히는 범위를 조회로 묶어 두는 것이 그 사실을 감당하는 방법이고, 그래서
    `requireMember` 를 손대지 않는 것이 이 계획에서 타협 대상이 아니다
- **Rollback steps:** 되돌리기는 `git revert` 하나다. 마이그레이션이 컬럼 추가뿐이라 남아 있어도
  아무 코드가 읽지 않는다. 급하면 `UPDATE app_user SET platform_role = 'USER'` 로 등급을 전부
  내리는 것만으로 이전 동작이 된다

## Decisions

2026-09-01 에 정한 것들.

- **범위는 읽기 전부, 쓰기 없음.** `기대 판정 라벨` 탭의 읽기도 넓힌다. 그러지 않으면 개발자가 남의
  프로젝트를 고른 순간 다섯 탭 중 넷은 값이 나오고 이 탭만 빈다. 쓰기는 `PUT expected-labels` 를
  포함해 전부 지금의 멤버십을 그대로 요구한다
- **`GET /api/projects` 는 `scope` 파라미터로 넓힌다.** 별도 `/api/admin/projects` 를 내지 않는다.
  그쪽으로 가면 페이지네이션과 응답 DTO 가 갈라져 둘이 어긋난다
- **등급 부여는 운영 DB 직접 UPDATE 다.** 화면도 API 도 만들지 않고 방법을 문서로 남긴다
- **plan-review 와 pair-review 는 subagent 없이 돈다.** 이 세션 설정이 subagent 를 막고 있어,
  두 리뷰를 같은 agent 가 계획과 diff 를 다시 읽어 수행한다. 스스로 놓친 것을 스스로 찾는 구조라는
  약점이 그대로 남으므로, Step 5 의 등급별 회귀 테스트가 그 약점을 메우는 자리다

## Rejected feedback

- **`PlatformAccessService` 에 `requireSeesAllProjects` 를 함께 두자.** `ProjectAccessService` 가
  `isMember` 와 `requireMember` 를 짝으로 두므로 모양은 맞다. 그런데 예외를 던져야 하는 자리가
  `GET /api/projects?scope=all` 하나뿐이라, 짝을 맞추려고 호출부 없는 함수를 만드는 것이 된다.
  `seesAllProjects` 하나만 두고 그 한 자리에서 직접 던진다

## Open Questions

- 없다.
