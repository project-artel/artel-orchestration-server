# 2026-09-03 — 초대 대상을 계정으로도 지정한다

- Date: 2026-09-03
- GitHub Issue: None (Jira: ARTEL-774, Story ARTEL-773)
- Status: Implemented

## Goal

이메일이 없는 계정도 초대 대상 후보에 나오고, 초대를 받고, 수락할 수 있게 한다.

`project_invitation` 이 대상을 `email` 하나로만 가리키는 것을 `email` 과 `app_user_id` 둘 중
하나로 넓힌다. 미가입자는 이메일로, 가입한 사람은 계정으로 부른다.

## Non-goals

- 메일 발송 provider. `LoggingMailSender` 는 그대로 둔다
- home 의 화면 변경. ARTEL-775 가 한다
- 기존 PENDING 행을 계정 대상으로 옮기는 backfill
- 역할 변경이나 멤버 관리 API

## Context / Constraints

### 지금 막혀 있는 곳

`InvitationSuggestionRepository.searchSql` 의 `WHERE` 가 `u.email IS NOT NULL` 과
`u.email_verified_at IS NOT NULL` 을 요구한다. GitHub 이 공개 이메일을 주지 않은 계정은
`OAuthUserService.newIdentityFor` 가 `email` 을 NULL 로 두므로 후보에서 빠진다.

필터만 풀면 후보에는 보이지만 고르는 순간 `ProjectInvitationService.resolveTarget` 이
`reachableEmailOf` 에서 막는다. 대상 지정 방식 자체를 넓혀야 한다.

### 지키는 것

- 이메일 전체 일치 검색은 확인을 마친 주소로만 남긴다. 미확인 주소까지 열면 주소 소유자가
  누구인지 확인하는 통로가 된다
- 초대 수락에서 이메일 경로의 규칙(ARTEL-732)은 바뀌지 않는다. 확인하지 않은 주소로는 수락할
  수 없다
- 한 초대의 대상은 정확히 하나다. CHECK 제약이 강제한다
- 소유자에게 후보의 이메일 주소를 보여 주지 않는다

### 마이그레이션 번호

base(`origin/develop`)의 최신은 `V85` 다. `V86` 과 `V87` 은 열려 있는 ARTEL-767 브랜치가
이미 쓰고 있으므로 `V88` 을 잡는다. 번호 사이가 비는 것은 Flyway 에 문제가 되지 않는다 —
`check-flyway-migrations.sh` 가 막는 것은 중복, 이미 적용된 것보다 낮은 번호, 그리고 병합된
파일의 수정이다.

ARTEL-767 이 먼저 merge 되면 push 전에 base 를 따라잡는다.

## Approach (Checklist)

### Step 0: 기반 — 스키마와 entity (직렬, 두 track 이 함께 딛는다)

- [ ] `V88__invite_by_app_user.sql`
  - `app_user_id BIGINT REFERENCES app_user (id) ON DELETE CASCADE` 추가
  - `email` 을 nullable 로 내린다
  - `CHECK ((email IS NULL) <> (app_user_id IS NULL))` 로 대상을 하나로 강제
  - `(project_id, app_user_id) WHERE status = 'PENDING'` partial unique index
  - `(app_user_id, status)` index — 초대함이 읽는 순서
  - `uk_project_invitation_pending` 은 건드리지 않는다. `lower(email)` 이 NULL 인 행은
    Postgres unique index 가 서로 충돌로 보지 않으므로 계정 초대가 걸리지 않는다
- [ ] `ProjectInvitationEntity`: `email: String?`, `appUserId: Long?` 추가

### Step 1: Track A — 후보 검색 (`InvitationSuggestionRepository.kt`)

- [ ] `searchSql` 의 `WHERE` 에서 `u.email IS NOT NULL AND u.email_verified_at IS NOT NULL` 제거
- [ ] 이메일 전체 일치는 `lower(u.email) = :query AND u.email_verified_at IS NOT NULL` 로 좁힌다.
      `match_rank` 의 이메일 항도 같이 좁힌다
- [ ] 대기 중 초대 제외를 두 대상 종류 모두로 넓힌다 — `lower(v.email) = lower(u.email)` 이거나
      `v.app_user_id = u.id`
- [ ] `reachableEmailOf` 삭제 (Track B 가 호출을 걷어낸다)

### Step 2: Track B — 초대 대상과 초대함

- [ ] `resolveTarget` 이 `String`(이메일) 대신 sealed `InvitationTarget` 을 낸다.
      `appUserId` 경로는 계정 실재만 확인하고 그 계정을 대상으로 삼는다
- [ ] `create`: 대상 종류에 따라 `email` 또는 `appUserId` 를 채운 행을 넣는다.
      `alreadyMember` 는 계정 대상이면 `findByProjectIdAndAppUserId` 를 바로 본다
- [ ] `InvitationTargetUnreachableException` 의 뜻을 "그런 계정이 없다"로 바꾼다
- [ ] `ProjectInvitationRepository.findPendingForAppUserId` 추가.
      `listForUser` 가 이메일 결과와 합쳐 `createdAt DESC, id DESC` 로 낸다
- [ ] `requireAddressedTo`: `invitation.appUserId == userId` 면 통과. 이메일 경로는 그대로
- [ ] `ProjectInvitationResponse.email` 을 nullable 로 내리고 `nickname`, `userTag`,
      `displayName` 을 싣는다. 계정 대상이면 그 계정에서, 이메일 대상이면 null

### Step 3: Tests

- [ ] `InvitationSuggestionIntegrationTest` (Track A): 주소 없는 계정이 후보에 나온다,
      미확인 주소는 이메일 전체 일치로 걸리지 않는다, 계정 초대가 나간 사람은 후보에서 빠진다
- [ ] `ProjectInvitationIntegrationTest` (Track B): 주소 없는 계정을 `appUserId` 로 초대하고
      그 계정의 초대함에 보이고 수락된다, 이메일 초대의 기존 동작이 그대로다,
      같은 계정을 두 번 부르면 409

### Step 4: Rollout

- [ ] `V88` 은 전진만 한다. 되돌리려면 CHECK 와 index 를 떨어뜨리고 `email` 을 다시
      `NOT NULL` 로 올려야 하는데, 그 사이에 만들어진 계정 초대가 있으면 올라가지 않는다.
      되돌릴 일이 생기면 계정 초대 행을 먼저 지운다

## 병렬 분할

Step 0 이 끝난 뒤 Track A 와 Track B 는 파일이 겹치지 않는다.

- Track A 쓰기 범위: `repository/InvitationSuggestionRepository.kt`,
  `test/.../InvitationSuggestionIntegrationTest.kt`
- Track B 쓰기 범위: `service/ProjectInvitationService.kt`,
  `repository/ProjectInvitationRepository.kt`, `dto/InvitationDtos.kt`,
  `test/.../ProjectInvitationIntegrationTest.kt`

경계에 하나 있다. `reachableEmailOf` 는 Track A 의 파일에 있고 부르는 쪽은 Track B 에 있다.
Track A 가 지우고 Track B 가 호출을 걷어낸다. 순서는 상관없고 합칠 때 만난다.

## Validation

- **Commands to run:**
  - `bash scripts/check-flyway-migrations.sh`
  - `./mvnw -Dtest=InvitationSuggestionIntegrationTest test`
  - `./mvnw -Dtest=ProjectInvitationIntegrationTest test`
  - `./mvnw -Dtest=ProjectMemberIntegrationTest test` (수락이 멤버 행을 만드는 경로)
- **Expected output:** 위 세 통합 테스트가 모두 통과. Flyway check 는 exit 0

## Risks & Rollback

- **Risks:**
  - `email` 을 nullable 로 내리면 그 컬럼을 non-null 로 읽던 자리가 NPE 를 낸다. entity 와
    DTO 를 함께 내리고, 읽는 자리를 전부 훑어야 한다
  - 같은 사람을 이메일로 한 번, 계정으로 한 번 부르면 PENDING 초대가 둘이 된다. 두 unique
    index 가 서로를 모르기 때문이다. 치명적이지는 않다 — `accept` 가 멤버 행을 두 번 넣지
    않으므로 둘째 초대는 수락돼도 멤버십을 바꾸지 않는다. 이번 범위에서는 받아들이고, 거슬리면
    후속으로 `create` 가 확인된 주소의 주인을 찾아 계정 초대로 접는 길이 있다
  - `ON DELETE CASCADE` 는 초대받은 계정이 지워지면 초대 기록을 함께 지운다. `invited_by` 의
    `SET NULL` 과 다른 선택인데, 대상이 사라진 초대는 남길 뜻이 없어서다
- **Rollback steps:** `git revert`. DB 는 위 Step 4 참고

## Open Questions

- 없음. 이메일로 부른 미가입자가 나중에 그 주소로 가입해도 초대는 이메일 경로로 그대로
  보인다 — 이 부분은 바뀌지 않는다

## 리뷰 반영 (2026-09-03)

`/code-review high` 가 낸 네 건을 모두 고쳤다.

1. **`resolveTarget` 이 `requireOwner` 앞에 있었다.** 프로젝트와 무관한 아무 로그인 사용자가
   `appUserId` 를 넣어, 계정이 없으면 404 `invitation_target_not_found` 를, 있으면 requireOwner
   의 404 `not_found` 를 받았다. 두 code 가 갈리므로 번호를 올려 가며 어느 `app_user` id 가
   실재하는지 흔적 없이 훑을 수 있었다. `resolveTarget` 을 트랜잭션 안, `requireOwner` 뒤로
   옮겼다.
2. **OpenAPI snapshot 을 재생성하지 않았다.** `docs/api/openapi.json` 이 여전히 `email` 을
   required 로, 그리고 사라진 409 `invitation_target_unreachable` 을 적고 있었다.
   `OpenApiSnapshotTest` 로 재생성했다.
3. **만료된 채 `PENDING` 인 초대가 재초대를 영영 막았다.** partial unique index 가 만료를 보지
   않으므로 그 행이 자리를 차지하는데, 만료된 초대는 보낸 목록에 안 나와 revoke 로 치울 id 를
   얻을 수도 없었다. `create` 가 다시 부르기 직전에 그 행을 REVOKED 로 거둔다. 이메일 경로에도
   같은 결함이 있었고 같은 수정으로 함께 풀린다.
4. **`DataIntegrityViolationException` catch 의 주석이 사실과 달랐다.** `app_user_id` foreign
   key 가 새 실패 경로로 늘었다. 주석을 실제와 맞추고, 그 창이 왜 좁은지와 언제 제약 이름으로
   갈라야 하는지를 적었다.

### snapshot 재생성이 부른 무관한 delta

`develop` 의 `docs/api/openapi.json` 에 `AuthUserResponse` 가 두 번 정의돼 있었다(ARTEL-742 가
재생성 없이 merge 된 결과다). 재생성이 그것을 하나로 합치므로 이 PR 의 snapshot diff 에 그
변경이 함께 들어간다. 떼어낼 수 없다 — snapshot 은 생성기의 산출물이고, 손으로 일부만 남기면
다음 재생성에서 다시 어긋난다.
