# 2026-08-18 — evidence 문서를 받아 저장한다

- Date: 2026-08-18
- Jira: ARTEL-441
- Status: Draft

## Goal

SDK 가 만든 근거 문서(`artel-affordances.json`)를 서버가 받는 **입구**를 연다. 원본은 스토리지에
보관하고 DB 에는 포인터·해시와 문서 헤더만 남긴다.

지금 그 문서는 `Application.persistentDataPath` 에 파일로 떨어지고 거기서 끝난다. 받을 데가 없어
**파이프라인이 물리적으로 돌지 않는다.**

에픽: ARTEL-444 / 선행: ARTEL-440(스키마) — 이 브랜치는 그 위에 **스택**으로 쌓는다.

## Non-goals

- **씬·기능 적재**(ARTEL-442). 이 이슈는 문서를 받아 `content_map` 행 하나와 원본 포인터를
  만드는 데까지다. `scene`·`capability` 는 한 줄도 만들지 않는다.
- pulse 판독 수용(ARTEL-449). 스트림이라 저장 방식이 다르다.
- 의사 C# 렌더(ARTEL-443). 병렬로 진행 중이고 쓰기 범위가 겹치지 않는다.

## Context / Constraints

### 기준

`origin/feat/orchestration-content-map-스키마를-만든다-ARTEL-440` = `6dac5cd` 위에 쌓는다.
V40 이 만든 `content_map` 과 `ContentMapRepository` 를 그대로 쓴다.

마이그레이션 번호는 **V41**. V39 는 ARTEL-435, V40 은 ARTEL-440 이 가져갔다.

### 문서를 요청 본문으로 받지 않는다

실측 문서가 **1,413 KB** 이고 WebFlux 기본 `max-in-memory-size` 는 256 KB 다. 전역 설정을 올리면
모든 엔드포인트의 버퍼 상한이 함께 올라간다 — 이 엔드포인트 하나 때문에 낼 대가가 아니다.

대신 **티켓 → 업로드 → 등록** 세 단계로 간다. `ProjectDocumentService` 와 `QaCaptureService` 가
이미 쓰는 모양이고, 바이트가 우리 서버를 지나가지 않는다.

```
1) POST .../content-map/ticket     presign 업로드 URL + objectKey
2) SDK 가 스토리지에 직접 PUT
3) POST .../content-map            objectKey 를 등록 → 헤더를 읽어 content_map upsert
```

### 헤더는 SDK 의 신고가 아니라 문서에서 읽는다

3단계에서 `schema`/`capture`/`build` 를 SDK 가 알려주게 하면 그 값이 문서와 어긋나도 서버가 모른다.
**문서 앞부분을 직접 읽는다.** 근거 문서는 헤더를 맨 앞에 쓴다(실측: 앞 400 바이트 안에
`schema`·`capture`·`capabilities`·`build` 가 전부 있다).

`DocumentStorage.readPrefix` 가 "형식 검증용이라 필요한 만큼만 가져온다"는 목적으로 이미 있고,
`sha256` 은 "파일 크기와 무관하게 상수 메모리"로 스트리밍한다. 셋 다 있는 것을 쓴다.

### 모르는 schema 는 거절한다

문서 스스로가 그렇게 하라고 적어 두었다. schema 6 에서 `label` 의 뜻이 **좁아졌다** — 이전에는
"오브젝트가 보여주는 것"이었고 지금은 "누를 수 있는 것에 쓰인 글자"다. 5 로 읽으면 적의 남은
체력을 컨트롤 이름으로 읽는다(샘플 게임 22개 중 16개가 정확히 그 경우였다).

늘어나기만 하는 버전을 열어 두는 것과 **뜻이 좁아진** 버전을 열어 두는 것은 다르다. 아는 번호만
받는다.

### 신뢰 경계

SDK 는 인터넷의 게임 클라이언트다. `/internal` 이 아니라 **`/api/sdk/**` 공개 포트 + SDK 토큰**이다
(`SdkRegistrationController` 와 같은 자리). 빌드 접근은 프로젝트 참여자 확인을 거치고, 접근 불가와
부재를 **같은 404 로** 묶는다 — 구분해서 알려주면 id 를 훑어 남의 빌드 존재를 알아낼 수 있다.

## Approach (Checklist)

- [x] **Step 0: Recon** — `ProjectDocumentService`(티켓·등록·중복), `QaCaptureService`(presign),
      `DocumentStorage`(readPrefix/sha256/head), `SdkRegistrationController`(SDK 인증 자리) 확인
- [ ] **Step 1: 마이그레이션** `V41__create_content_map_document.sql`
- [ ] **Step 2: 엔티티·리포지토리** `ContentMapDocumentEntity`, `ContentMapDocumentRepository`,
      빌드 접근 확인 질의
- [ ] **Step 3: 서비스** `EvidenceDocumentService` — 티켓 발급, 등록(헤더 파싱 → content_map upsert
      → 문서 행), 멱등
- [ ] **Step 4: 컨트롤러** `/api/sdk/game-builds/{gameBuildId}/content-map{,/ticket}`
- [ ] **Step 5: 테스트** — 헤더 파싱, 모르는 schema 거절, 같은 해시 재전송 건너뛰기,
      capture 별 별개 행, 남의 빌드 404
- [ ] **Step 6: 검증** — flyway 스크립트 둘 + 전체 스위트

## Validation

- **Commands to run:**
  - `./scripts/check-flyway-migrations.sh`
  - `./scripts/verify-flyway-upgrade.sh`
  - `./mvnw clean test`
- **Expected output:** 셋 다 통과. 신규 테스트 통과.

## Risks & Rollback

- **Risks:**
  - **스택 PR.** base 가 ARTEL-440 브랜치라 그쪽이 리뷰로 바뀌면 rebase 가 필요하다. 440 이 머지되면
    base 를 develop 으로 바꾼다.
  - **번호 충돌.** V41 을 다른 브랜치가 가져갈 수 있다. 머지 직전 재확인.
  - **업로드만 하고 등록을 안 하면 고아 객체가 남는다.** `ProjectDocumentService` 와 같은 성질이고
    같은 방식으로 산다 — 등록되지 않은 객체는 문서로 취급하지 않는다. 정리는 후속.
  - **헤더 파싱이 앞 N 바이트 가정에 기댄다.** SDK 가 필드 순서를 바꾸면 깨진다. 넉넉히 읽고,
    못 찾으면 400 으로 분명히 실패한다 — 조용히 null 로 넘어가지 않는다.
- **Rollback steps:** `git revert`. 새 테이블 하나와 새 엔드포인트뿐이고 기존 경로를 건드리지 않는다.

## Open Questions

- 등록 응답에 무엇을 담을지. 지금은 `content_map` id 와 문서 id 까지. 적재 상태(ARTEL-442)가
  붙으면 `parse_status` 같은 축이 하나 더 필요해진다 — 그때 넓힌다.
