# 2026-08-29 — 프로젝트 초대와 멤버 API

- Date: 2026-08-29
- Jira: ARTEL-685 (Story ARTEL-684, Epic ARTEL-683)
- Status: Reviewed (fast · medium 1차 반영 완료)

## Goal

프로젝트 `OWNER` 가 이메일로 사람을 초대하고, 초대받은 사람이 로그인해 수락하면 `project_member` 행이 생긴다. 멤버 목록을 조회하고 멤버를 내보내는 경로도 함께 연다.

`project_member` 는 V3 부터 있었지만 행을 만드는 경로가 `ProjectService.create` 하나뿐이라, 프로젝트는 만든 사람 한 명의 것으로 남는다.

## Non-goals

- 초대 메일 발송. 서버에 메일 의존성이 없다. 초대받은 사람은 다른 경로로 소식을 듣고 직접 로그인한다
- 역할 변경 API. 초대할 때 정한 역할이 그대로 간다
- 멤버가 스스로 나가기
- 초대 재발송
- `EXPIRED` 를 status 로 저장하는 배치
- `home` 화면. ARTEL-686 이 맡는다

## API 표면

| 메서드 | 경로 | 누가 | 응답 |
| --- | --- | --- | --- |
| GET | `/api/projects/:projectId/members` | 멤버 누구나 | `ProjectMemberResponse` 목록 |
| DELETE | `/api/projects/:projectId/members/:appUserId` | `OWNER` | 204 |
| POST | `/api/projects/:projectId/invitations` | `OWNER` | 201 `ProjectInvitationResponse` |
| GET | `/api/projects/:projectId/invitations` | `OWNER` | `PENDING` 초대 목록 |
| DELETE | `/api/projects/:projectId/invitations/:invitationId` | `OWNER` | 204 |
| GET | `/api/invitations` | 로그인한 사람 | 자기 이메일로 온 유효한 `PENDING` 목록 |
| POST | `/api/invitations/:invitationId/accept` | 이메일이 맞는 사람 | 200 `ProjectInvitationResponse` |
| POST | `/api/invitations/:invitationId/decline` | 이메일이 맞는 사람 | 200 `ProjectInvitationResponse` |

수락과 거절은 프로젝트 경로가 아니라 `/api/invitations` 아래다. 수락하는 사람은 아직 그 프로젝트의 멤버가 아니라서, 프로젝트 경로에 두면 "멤버가 아니면 404" 규칙과 정면으로 부딪힌다. `projectId` 는 초대 행에서 읽는다.

## Context / Constraints

### 수락 자격은 이메일 일치 하나다

`app_user.email` 에 unique 제약이 없다. 그래서 수락은 이메일로 사용자를 찾지 않는다 — 로그인한 사용자의 `app_user` 행을 읽어 그 `email` 이 초대의 `email` 과 맞는지 비교한다. 방향이 이쪽이면 같은 이메일을 가진 행이 여럿이어도 남의 초대를 가져갈 수 없다. 비교는 대소문자를 무시한다.

`OAuthUserService` 는 "제공자가 이메일 소유를 보장하지 않는다"는 이유로 이메일이 같아도 계정을 자동으로 잇지 않는다. 초대 수락은 같은 값을 믿으므로 같은 약점을 물려받는다. 다만 성공했을 때 얻는 것이 계정 탈취가 아니라 초대받은 프로젝트에 들어가기뿐이다.

`app_user.email` 이 null 인 사용자는 어떤 초대도 자기 것이 아니다. 목록은 빈 배열이 되고 수락은 403 이다. 그 사실을 사용자에게 말하는 것은 `home` 의 몫이다.

### 이메일 정규화는 서비스가 한다

`CreateInvitationRequest.email` 을 서비스가 `trim().lowercase()` 해서 저장한다. DTO 역직렬화에서 하지 않는 것은, 그러면 정규화 규칙이 요청 모델에 숨어 조회 쪽과 어긋날 수 있어서다.

경합은 정규화가 아니라 index 가 막는다. unique index 가 `lower(email)` 위에 있으므로 `User@x.com` 과 `user@x.com` 은 서비스가 무엇을 하든 같은 키로 충돌한다.

### 중복 초대와 중복 수락은 제약과 조건부 UPDATE 가 막는다

중복 초대: `(project_id, lower(email))` 에 `status = 'PENDING'` 조건을 건 partial unique index. 조회로 먼저 확인하면 두 요청 사이에 경합이 나므로, 제약으로 충돌을 만들고 잡아서 409 로 옮긴다.

잡을 예외는 `DataIntegrityViolationException` 이다. `DuplicateKeyException` 이 아니다 — R2DBC 의 예외 변환은 unique 위반을 넓은 쪽으로 올리고, 이 저장소는 `ProjectDocumentService.kt:268`, `IssueService.kt:99`, `SdkRegistrationService.kt:114` 가 전부 `DataIntegrityViolationException` 을 잡는다. 좁은 타입으로 잡으면 실제 예외가 빠져나가 500 이 된다.

중복 수락: 두 요청이 같은 초대를 동시에 수락하는 경합은 `WHERE id = :id AND status = 'PENDING'` 조건부 UPDATE 로 막는다. 영향받은 행이 0 이면 이미 누가 처리한 것이라 409 다. 읽고 나서 쓰면 그 사이에 상태가 바뀐다.

### 만료는 저장하지 않는다

`status` 는 `PENDING`, `ACCEPTED`, `DECLINED`, `REVOKED` 넷이다. 만료는 `expires_at` 과 현재 시각을 비교해 조회할 때 정한다. `EXPIRED` 를 저장하면 행을 뒤집어 줄 배치가 필요해지고, 그 배치가 멈춘 동안 status 가 거짓말을 한다.

만료된 초대는 목록에 나오지 않고, 수락하면 409 다. 404 가 아닌 이유는 그 사람이 초대의 존재를 이미 알기 때문이다 — 숨길 것이 없고, "만료됐다"가 정확한 답이다.

`responded_at` 은 `ACCEPTED`, `DECLINED`, `REVOKED` 로 갈 때만 채운다. 감사 기록이고 어떤 판단에도 쓰지 않는다.

### 삭제된 프로젝트의 초대는 수락되지 않는다

프로젝트가 soft delete 돼도 `PENDING` 초대 행은 그대로 둔다. 되돌릴 수 있게 남기는 것이 `deleted_at` 의 취지이고, 초대까지 `REVOKED` 로 바꾸면 되돌려도 복구되지 않는다.

대신 수락 경로가 `projectRepository.findActiveById` 로 프로젝트가 살아 있는지 확인한다. 없으면 404 다. 받은 초대 목록도 같은 조인을 걸어 삭제된 프로젝트의 초대를 내보내지 않는다.

### `OWNER` 확인은 한 곳에서 하고, 그 확인은 프로젝트를 거친다

`ProjectService.delete` 가 세운 규칙 — 참여자가 아니면 404, 참여자지만 `OWNER` 가 아니면 403 — 을 이 작업이 다섯 자리에서 더 필요로 한다(멤버 목록, 멤버 내보내기, 초대 생성, 초대 취소, 보낸 초대 목록). `ProjectTrackerLinkService.kt:150-157` 이 이미 같은 분기를 한 벌 더 갖고 있다.

분기를 `ProjectAccessService` 로 모은다. 둘 다 프로젝트 엔티티를 돌려준다.

- `requireMember(projectId, userId): ProjectEntity`
- `requireOwner(projectId, userId): ProjectEntity`

**둘 다 `projectRepository.findAccessibleById` 를 거친다.** membership 만 보면 안 된다 — `project_member` 행은 `deleted_at` 이 채워져도 남으므로, 멤버십만 보는 확인은 soft delete 된 프로젝트의 `OWNER` 를 통과시킨다. 그러면 삭제된 프로젝트에 초대를 보내고 멤버를 내보내는 일이 201 과 200 으로 성공한다. `findAccessibleById` 는 `deleted_at IS NULL` 을 함께 걸어(`ProjectRepository.kt:38`) 그 구멍을 닫는다.

그래서 `ProjectAccessService` 가 `ProjectRepository` 를 의존하게 된다. 지금까지 `ProjectMemberRepository` 만 봤지만, "접근 권한의 답이 한 곳에서만 나온다"는 이 클래스의 취지에 삭제 여부까지 포함되는 것이 맞다.

`ProjectService.delete` 도 `requireOwner` 로 바꾼다. 앞 문단대로 정의하면 그쪽과 완전히 같은 동작이 되고, 이 hoist 를 정당화한 중복이 실제로 사라진다. `delete` 는 더 이상 null 을 돌려주지 않으므로 `ProjectController.delete` 의 `?: throw projectNotFound()` 도 함께 걷어낸다. 응답은 그대로다 — `requireOwner` 가 던지는 `NotFoundException` 의 message 와 code 를 컨트롤러가 쓰던 것과 같게 맞춘다.

`ProjectTrackerLinkService` 는 이번에 건드리지 않는다. 그쪽 helper 는 이미 그 파일 안에서 factor 돼 있고, 바꾸려면 이 변경이 열 이유가 없는 파일을 여는 것이라 별도 정리다. `coding-style.md` 의 "Stop at the edge of the change" 가 가리키는 경우다.

`ProjectAccessDeniedException` 을 `ProjectService.kt` 에서 `ProjectAccessService.kt` 로 옮긴다. 이제 던지는 주체가 `ProjectAccessService` 이고, 패키지가 같아 `ProjectTrackerLinkService.kt:8` 의 import 는 그대로 유효하다. `.agents/docs/error-handling.md:73` 이 이 클래스의 위치를 파일 경로 주석으로 적고 있어 그 한 줄도 고친다.

### 이미 멤버인 사람을 초대하는 경우

partial unique index 의 조건이 `status = 'PENDING'` 이라 `ACCEPTED` 행은 새 초대를 막지 못한다. 그대로 두면 이미 멤버인 사람에게 초대가 만들어지고, 수락이 `project_member` 에 두 번째 행을 넣다가 `uk_project_member_project_user`(`V3__create_project_and_project_document.sql:28`)를 때려 500 이 난다.

양쪽을 다 막는다.

- **초대 생성**: 그 이메일을 가진 `app_user` 중 이미 이 프로젝트의 멤버가 있으면 409. `app_user.email` 이 unique 가 아니라 이 확인은 최선을 다하는 것일 뿐 보장이 아니다. 확실한 방어선은 아래다.
- **수락**: `project_member` 행이 이미 있으면 새로 넣지 않고 초대만 `ACCEPTED` 로 바꿔 200 을 준다. 멱등하게 끝나는 쪽이 맞다 — 요청한 사람이 원한 상태("나는 이 프로젝트의 멤버다")가 이미 참이다.

이메일로 `app_user` 를 찾는 것은 여기 한 곳뿐이고, 수락 자격 판정에는 쓰지 않는다. 수락은 여전히 로그인한 사용자의 `email` 을 초대의 `email` 과 맞춰 보는 방향이다.

**수락은 한 트랜잭션이고 순서가 정해져 있다.** `transactionalOperator.executeAndAwait` 로 감싸고, 조건부 UPDATE 를 먼저 한 다음 멤버 행을 확인하고 넣는다. 조건부 UPDATE 가 직렬화 지점이라 동시 요청 중 하나만 1 행을 받는다. 트랜잭션이 없으면 진 쪽이 409 를 받고도 넣어 둔 `project_member` 행이 남는다.

### 초대 유효기간은 설정이 아니라 상수다

14일을 `ProjectInvitationService` 의 상수로 둔다. `@ConfigurationProperties` 클래스를 새로 만들지 않는다.

`StorageProperties` 가 허용 형식을 두고 쓴 논증이 그대로 적용된다 — 배포마다 다르게 둘 이유가 없고, 다르게 두면 "내 초대가 왜 사흘 만에 죽었나"를 설정을 열어보기 전에는 아무도 답할 수 없다. 값 하나를 위해 properties 클래스와 그것을 등록할 `@Configuration` 클래스 둘을 만드는 것도 얻는 것에 비해 비싸다.

### 마이그레이션 번호

`origin/develop` 이 `V71__record_what_the_agent_saw_on_a_capability.sql` 까지 와 있어 다음은 `V72` 다. 열린 PR 중 `db/migration/` 을 건드리는 셋(#193, #186, #184)은 전부 `V52`–`V55` 라 지금은 부딪히지 않는다. 다만 이 브랜치가 열려 있는 동안 다른 사람이 `V72` 를 먼저 머지할 수 있으므로, push 전마다 base 를 확인하고 필요하면 번호를 올린다.

### 테스트 DB

`application-test.yml` 이 말하듯 스위트는 실제 PostgreSQL 컨테이너를 띄운다. `ProjectCrudIntegrationTest` 의 `clean()` 주석이 H2 를 말하는 것은 낡은 문장이다. partial unique index 와 `lower(email)` 이 그대로 검증된다.

## Approach (Checklist)

- [x] **Step 0: Recon**
  - `project_member`, `ProjectRole`, `ProjectAccessService`, `ProjectService` 를 읽었다
  - `app_user.email` 이 nullable 이고 unique 가 아님을 V2 에서 확인했다
  - `DataIntegrityViolationException` 을 서비스에서 잡는 것이 이 저장소의 방식임을 확인했다
  - 서버에 메일 의존성이 없음을 `pom.xml` 에서 확인했다

- [ ] **Step 1: 스키마** — `db/migration/V72__create_project_invitation.sql`
  - `project_invitation`: `id`, `project_id`, `email`, `role`, `status`, `invited_by`, `created_at`, `expires_at`, `responded_at`
  - partial unique index 와 `(project_id)`, `(lower(email))` 조회 index

- [ ] **Step 2: entity 와 repository**
  - `project/entity/ProjectInvitationEntity.kt` — `ProjectInvitationStatus` enum 을 같은 파일에
  - `project/repository/ProjectInvitationRepository.kt` — 조건부 UPDATE 를 `@Modifying @Query` 로

- [ ] **Step 3: 인가를 `ProjectAccessService` 로 모은다**
  - `requireMember` 와 `requireOwner` 추가. 둘 다 `findAccessibleById` 를 거쳐 `ProjectEntity` 를 돌려준다
  - `ProjectAccessDeniedException` 을 `ProjectService.kt` 에서 `ProjectAccessService.kt` 로 옮긴다
  - `ProjectService.delete` 를 `requireOwner` 로 바꾸고 `ProjectController.delete` 의 null 분기를 걷어낸다
  - `.agents/docs/error-handling.md:73` 의 파일 경로 주석 수정

- [ ] **Step 4: DTO 와 이메일 조회**
  - `project/dto/MemberDtos.kt` — `ProjectMemberResponse`
  - `project/dto/InvitationDtos.kt` — `CreateInvitationRequest`, `ProjectInvitationResponse`
  - `AppUserRepository.findByEmailIgnoreCase` 추가. 이메일이 unique 가 아니라 여러 행을 돌려준다

- [ ] **Step 5: service**
  - `project/service/ProjectMemberService.kt` — 목록, 내보내기. 마지막 `OWNER` 를 막는다
  - `project/service/ProjectInvitationService.kt` — 생성, 취소, 프로젝트별 목록, 받은 목록, 수락, 거절

- [ ] **Step 6: controller** — 전부 `@Tag` 와 `@Operation` 을 단다
  - `project/controller/ProjectMemberController.kt`
  - `project/controller/ProjectInvitationController.kt`
  - `project/controller/InvitationController.kt`

- [ ] **Step 7: 테스트**
  - `ProjectMemberIntegrationTest` — 목록 인가, 내보내기, 마지막 `OWNER`, 비멤버 404, 삭제된 프로젝트 404
  - `ProjectInvitationIntegrationTest` — 생성, 중복 409, 대소문자 다른 중복 409, 이미 멤버인 이메일 409, 수락, 수락이 멱등한지, 거절, 취소, 만료 409, 이메일 불일치 403, 이메일 없는 계정, 삭제된 프로젝트의 초대는 수락되지 않음
  - `ProjectCrudIntegrationTest` 의 삭제 관련 테스트가 그대로 통과하는지 — `requireOwner` 전환이 동작을 바꾸지 않았다는 증거다

- [ ] **Step 8: OpenAPI 스냅샷**
  - `OpenApiSnapshotTest` 는 검증이 아니라 `docs/api/openapi.json` 을 다시 쓴다. CI 가 그 파일의 diff 를 보고 어긋나면 PR 을 떨어뜨린다
  - 새 컨트롤러 셋이 경로 여덟 개를 더하므로 다시 생성한 스냅샷을 이 PR 에 함께 커밋한다

- [ ] **Step 9: Rollout**
  - 마이그레이션은 테이블 추가뿐이라 기존 경로에 영향이 없다
  - `/api/projects` 응답 필드를 바꾸지 않으므로 `home` 이 옛 계약을 읽는 동안에도 배포된다

## Validation

- **Commands to run:**
  - `./mvnw test -Dtest='Project*IntegrationTest'` — 구현 중 focused
  - `./mvnw test` — PR 전 전체. Testcontainers 가 PostgreSQL 을 띄우므로 docker 필요
  - `scripts/check-flyway-migrations.sh` — push 전
- **Expected output:** 전부 통과. 새 통합 테스트 둘이 위 인수 조건을 덮는다

## Risks & Rollback

- **Risks:**
  - 이메일 소유를 ARTEL 이 확인하지 않는다. GitHub 이 준 이메일을 믿는 것이라, 제공자가 검증하지 않은 이메일을 가진 계정이 그 이메일로 온 초대를 수락할 수 있다. 얻는 것이 초대받은 프로젝트 접근뿐이라 받아들이고, 소유 확인 메일은 발송 인프라가 생긴 뒤에 붙인다
  - partial unique index 의 `WHERE status = 'PENDING'` 은 PostgreSQL 문법이다. 운영과 테스트가 모두 PostgreSQL 이라 문제가 없지만, 다른 엔진으로 옮기면 이 줄이 먼저 깨진다
  - `expires_at` 을 조회 시각으로 판정하므로 서버 시계가 어긋나면 만료 판정이 어긋난다. `Clock` 주입으로 테스트는 고정한다
  - 인가를 모으느라 이 작업 밖의 파일 셋(`ProjectService.kt`, `ProjectController.kt`, `error-handling.md`)이 diff 에 들어온다. 패키지가 같아 import 는 안 깨지고 응답도 그대로지만, 리뷰어가 왜 이 파일들이 있는지 묻게 되므로 PR 의 Code Walkthrough 에 이유를 적는다. `ProjectCrudIntegrationTest` 가 무변경 통과하는 것이 그 증거다
  - 초대 생성의 "이미 멤버" 확인은 `app_user.email` 이 unique 가 아니라 최선을 다하는 것일 뿐이다. 같은 이메일을 가진 계정이 둘일 때 그중 하나만 멤버면 초대가 만들어진다. 그래도 수락이 멱등해서 500 은 나지 않는다
  - 같은 `app_user.email` 을 가진 사람 둘이 같은 초대를 수락하면 먼저 온 쪽이 이기고 뒤쪽은 409 다. 조건부 UPDATE 가 그것을 정한다
  - `findByEmailIgnoreCase` 가 내는 `UPPER(email)` 을 받쳐 줄 index 가 `app_user` 에 없다(V2 는 `email VARCHAR(320)` 만 선언한다). 지금 행 수에서는 문제가 아니지만, 초대 생성이 잦아지면 이 조회가 먼저 느려진다
  - `ProjectTrackerLinkService` 의 private helper 가 이제 위치만 다르고 내용은 같은 확인을 한 벌 더 갖게 된다. 따로 정리하지 않으면 둘이 어긋난다. 후속 이슈로 남긴다
- **Rollback steps:**
  - 컨트롤러를 되돌리면 새 경로가 사라진다. `project_invitation` 테이블은 남지만 아무것도 읽지 않는다
  - 테이블까지 지우려면 `DROP TABLE project_invitation` 마이그레이션을 새로 더한다. 되돌리는 마이그레이션을 쓰지 않고 앞으로만 가는 것이 이 저장소의 방식이다

## Rejected feedback

- **fast 8 — 마이그레이션 번호 충돌 위험을 별도 항목으로**: 이미 `## 마이그레이션 번호` 가 담고 있어 항목을 더하지 않았다. 대신 "이 브랜치가 열려 있는 동안 남이 V72 를 먼저 머지할 수 있다"는 문장을 그 절에 넣었다.
- **medium 3 — `InvitationProperties` 를 `StorageConfig` 의 등록 목록에 넣기**: 초대 유효기간을 설정으로 두지 않기로 해 properties 클래스 자체가 없어졌다. `StorageConfig` 에 저장소와 무관한 클래스를 등록하면 그 파일 이름이 거짓말을 하게 되는 문제도 함께 사라진다.
- ~~**medium 1 의 일부 — `ProjectService.delete` 도 `requireOwner` 로 바꾸기**~~: **철회한다.** 1차에서 "`requireOwner` 는 멤버십만 보므로 삭제된 프로젝트에 403 이 나간다"는 이유로 거절했는데, 그것은 `requireOwner` 를 예외로 두어야 할 이유가 아니라 `requireOwner` 정의가 틀렸다는 증거였다. `findAccessibleById` 를 거치게 고치니 `delete` 와 완전히 같아졌고, 이제 바꾼다.
- **heavy — `ProjectTrackerLinkService` 의 helper 도 `requireOwner` 로 바꾸기**: 이번에는 안 바꾼다. 동작은 같아지지만 이 변경이 열 이유가 없는 파일을 여는 것이고, `coding-style.md` 가 그런 확장을 별도 커밋과 별도 이슈로 미루라고 적었다. 별도 정리로 남긴다.

## Open Questions

- 없음. 초대 모델, 메일 발송 범위, 진행 방식은 착수 전에 정해졌다
