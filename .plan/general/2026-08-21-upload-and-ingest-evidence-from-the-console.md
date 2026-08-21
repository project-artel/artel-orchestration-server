# 2026-08-21 — 사람이 올린 근거 문서를 받아 적재까지 한다

- Date: 2026-08-21
- Jira: ARTEL-488
- Status: Reviewed (fast · medium 반영)

## Goal

브라우저에서 근거 문서를 올려 **씬·기능 행이 앉는 데까지** 한 번에 간다. 지금은 어떤 경로로도 표가
채워지지 않는다 — SDK 는 문서를 로컬 파일로만 떨어뜨리고, 적재기는 프로덕션에서 아무도 부르지 않는다.

```
[home] 파일 선택 → ticket → PUT → register → ingest → 행이 앉는다
```

## Non-goals

- 조회 API (ARTEL-446) · home 화면 (ARTEL-489)
- SDK 가 스스로 올리는 것 (ARTEL-490) · 원격 스캔 (ARTEL-491·492)
- 스케줄러. 사람이 버튼을 누르는 것이 이 단계의 트리거다

## Context / Constraints

- `/api/sdk/**` 는 `aud=artel-sdk` 만 받는 별도 체인이라(`SecurityConfig.kt:67-88`) **브라우저 토큰이
  거절된다.** 같은 서비스를 쓰되 `/api/` 아래 경로를 따로 낸다
- `EvidenceDocumentService.createUploadTicket` · `register` 는 이미 `userId` 를 받아
  `findAccessibleByIdForMember` 로 접근을 검사한다. **다만 projectId 를 안 본다** — 새 경로는
  `/projects/{projectId}/` 를 포함하므로 `findAccessibleById(id, projectId, userId)` 로 검사해야
  경로의 projectId 가 장식이 되지 않는다
- 적재기(`ContentMapIngestService`)는 `ingest(document)` · `ingestPending(limit)` 둘 다 있다.
  `findPending` 은 **전역**이라 이 빌드의 문서만 고르는 조회가 필요하다
- 문서 1.4MB 파싱이 요청 스레드를 문다. 이 단계에서는 그대로 받는다 — 사람이 버튼을 누르고
  결과를 기다리는 화면이고, 비동기로 만들면 "언제 끝났나"를 물을 창구가 또 필요하다

## 정해야 하는 것

### 1. 적재 트리거의 대상

`content_map_document` 에는 `game_build_id` 가 없다. 빌드 단위 조회는 `content_map` 을 조인해야 한다.
기존 부분 인덱스는 `(received_at) WHERE ingested_at IS NULL` 이라 빌드 필터를 덮지 않는다 —
지금 행 수에서는 문제가 아니라 **인덱스를 새로 만들지 않는다.**


`POST .../content-map/ingest` 가 **이 빌드의 대기 문서**를 적재한다. 전역 큐를 도는 `ingestPending`
을 그대로 쓰지 않는 이유: 남의 프로젝트 문서가 이 요청 시간에 섞여 들어가고, 실패도 이 응답에 섞인다.

`ContentMapDocumentRepository` 에 `findPendingByGameBuild(gameBuildId, limit)` 를 더한다.

### 2. 실패한 문서를 어떻게 남기나

**마이그레이션 V48** 로 `content_map_document` 에 두 칸을 더한다:

| 칸 | 왜 |
|---|---|
| `ingest_failed_at TIMESTAMPTZ` | 마지막 실패 시각 |
| `ingest_error VARCHAR(512)` | 마지막 실패 사유. 화면이 사람에게 보여 줄 유일한 단서 |

번호가 V47 이 아니라 V48 인 이유: ARTEL-466(PR #143)이 V47 을 이미 집었다. 이 브랜치가 스택 아래라
나중에 머지되므로 이쪽이 비킨다.

**시도 횟수 칸과 `findPending` 의 상한 조건은 넣지 않는다.** 계획 검토가 짚었다 — 이 이슈에는
스케줄러가 없고(`findPending` 은 프로덕션 호출자가 0이다), 버튼 경로는 상한을 일부러 무시한다.
그러면 그 칸은 **이 diff 가 내보내는 어떤 경로에도 쓰이지 않는다.** 포기 임계값은 실제 실패를 보기
전에 아무도 못 고르는 정책 숫자이기도 하다.

큐 순서·백오프(`ORDER BY ingest_failed_at NULLS FIRST` 나 재시도 간격)는 **스케줄러를 만드는 이슈**가
가져간다. 그 조건은 자동 경로와 공유되는 것이라 이 diff 에 있을 자리가 아니다.

### 3. 실패를 기록하는 자리

적재는 문서 하나가 한 트랜잭션이고, 실패하면 그 트랜잭션이 통째로 되돌아간다. **실패 기록도 같이
되돌아가면 아무것도 안 남는다.** 그래서 기록은 그 트랜잭션 **밖에서** 따로 쓴다.

**`ContentMapIngestService.ingestPending` 안의 기존 `onFailure` 가 그 자리다.** 새 루프를 따로 쓰지
않는다 — 그러면 자동 경로는 로그만 남기고 버튼 경로만 기록하는, 같은 사고를 두 곳이 다르게 다루는
모양이 된다. 두 입구가 `private suspend fun ingestEach(documents)` 하나를 공유하고, 그 안에서
`runCatching { ingest(it) }.onFailure { recordIngestFailure(...) }` 한다.

**제약: 문서를 도는 루프는 `@Transactional` 이면 안 된다.** 바깥 트랜잭션이 있으면 catch 뒤에 쓰는
기록이 rollback-only 에 걸려 조용히 사라지거나 커밋에서 터진다. `ingest` 하나만 트랜잭션이다.

### 4. 응답

```
POST .../content-map/ingest
→ { "documents": [ { documentId, contentMapId, scenes, capabilities,
                     collapsed, deleted, markedNotApplicable } ],
    "failed":    [ { documentId, error } ] }
```

대기 문서가 없으면 둘 다 빈 배열. **전부 실패해도 200 이다** — 요청은 정상 처리됐고 결과가 실패인
것이라, `error-handling.md` 가 말하는 "예상된 부분 실패"는 예외가 아니라 구조화된 본문으로 답한다.

**id 는 문자열로 낸다.** `IngestResult` 를 그대로 직렬화하면 `Long` 이 숫자로 나가는데, 브라우저용
경로는 `game` 모듈처럼 문자열 규약이다(`GameBuildDtos.kt:29-39`) — home 의 관대한 파서도 id 를
문자열로 읽는다. 얇은 응답 DTO 하나를 두고 그 자리에서만 옮긴다.

## Approach (Checklist)

- [ ] **Step 1: 마이그레이션** — V48, 위 두 칸. `findPending` 은 건드리지 않는다
- [ ] **Step 2: 저장소** — `findPendingByGameBuild`, `recordIngestFailure`
- [ ] **Step 3: 서비스 — 새 클래스를 만들지 않는다**
  - `EvidenceDocumentService` 의 `createUploadTicket` · `register` 와 private `requireAccessibleBuild`
    에 `projectId: Long?` 를 더한다. null 이면 지금처럼 `findAccessibleByIdForMember`,
    있으면 `findAccessibleById(id, projectId, userId)` — 여섯 줄쯤이다
  - 빌드 단위 적재는 `ContentMapIngestService` 에 붙인다. 배치 순회와 문서별 오류 격리를 이미 그쪽이
    소유한다
- [ ] **Step 4: 컨트롤러** — `ProjectContentMapController`
      (`/api/projects/{projectId}/game-builds/{buildId}/content-map`).
      `SdkContentMapController` 의 거울이다 — `@CurrentUserId` + `@PathVariable` + 위임 + `?: throw
      NotFoundException`. 두 컨트롤러인 이유는 인증 체인이 경로 접두사로 갈리기 때문이고(`@Order(1)`
      의 `/api/sdk/**` 매처), 실제로 다른 것은 **경로와 projectId 검사뿐**이다
- [ ] **Step 5: 테스트** — 아래 Validation
- [ ] **Step 6: Insomnia 컬렉션** (`insomnia-sync`)

## Validation

- **Commands:** `./mvnw -Dtest='*ContentMap*' test`
- **Cases:**
  - 브라우저 토큰으로 ticket → PUT → register → ingest 가 끝까지 돈다
  - 남의 프로젝트 빌드면 404 (부재와 권한 없음을 가르지 않는다)
  - **경로의 projectId 가 빌드의 것과 다르면 404** — 이것이 없으면 projectId 는 장식이다
  - 같은 문서를 두 번 등록하면 `alreadyRegistered=true` (기존 로직의 배선 확인용)
  - **적재가 실패하면 `ingest_failed_at` 과 `ingest_error` 가 남고 행은 안 남는다.**
    실패는 결정적으로 만든다 — 등록만 하고 스토리지에서 객체를 지우면 적재가 그 자리에서 실패한다
  - **자동 경로(`ingestPending`)도 같은 기록을 남긴다.** 두 입구가 한 루프를 공유한다는 증거
  - 빌드 단위 조회가 다른 빌드의 대기 문서를 집지 않는다

## Risks & Rollback

- **1.4MB 파싱이 요청 스레드를 문다.** 실측 적재는 1초 안쪽이고 사람 하나가 버튼 하나를 누르는
  경로라 지금은 이대로 간다. 늘어나면 같은 엔드포인트가 고른 문서 id 만 즉시 답하고 화면이
  조회 API(ARTEL-446)를 폴링하는 모양이 된다 — 그때 `documents[]`·`failed[]` 는 **옮겨지는 것이
  아니라 버려진다.** 작지만 다시 쓰는 일이고, 그 점을 알고 고른다
- 실패 기록을 별도 트랜잭션에서 쓰므로, 적재 실패와 기록 실패가 따로 일어날 수 있다. 기록이 실패하면
  로그만 남는다 — 사람은 화면에서 사유를 못 보고 다시 눌러 보게 된다. 기록이 없다고 데이터가 틀어지지는
  않으므로 감수한다
- **Rollback:** `git revert`. 두 칸 다 nullable 이고 읽는 코드가 이 diff 밖에 없다

## Open Questions

- 등록 직후 자동으로 적재까지 이어붙일까? 지금 계획은 **아니오** — 화면이 두 걸음을 따로 보여 주면
  "올라갔는데 적재가 실패했다"를 사람이 구분할 수 있다. 한 번에 묶으면 그 구분이 사라진다
