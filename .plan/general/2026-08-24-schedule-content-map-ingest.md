# 2026-08-24 — 등록된 근거 문서를 주기 배치로 적재한다

- Date: 2026-08-24
- Jira: ARTEL-502
- Status: Reviewed (fast · medium · heavy 자체 리뷰 반영)

## Goal

`ContentMapIngestService.ingestPending()` 에 호출자를 붙여 등록 → 적재 사슬을 잇는다.
스케줄러가 주기적으로 큐를 비우고, 못 넘긴 문서는 장부에 남아 영영 되풀이되지 않는다.

## Non-goals

- 등록 직후 즉시 적재 (검토했고 버렸다 — 아래 Context 참조)
- 재적재 트리거. `ingested_by` 가 그것을 위해 있지만 별건이다
- 큐 상태 조회 API 나 화면
- 실패한 문서를 사람이 다시 밀어 넣는 경로
- 적재 로직 자체의 변경

## Context / Constraints

### 지금 상태

`ingestPending()` 의 프로덕션 호출자가 0이다. 부르는 것은
`ContentMapIngestTransactionTest:106` 뿐이다. 등록은 `content_map_document` 행을
`ingested_at IS NULL` 로 남기고, `findPending` 도 `idx_content_map_document_pending` 도
그 큐를 읽으라고 있는데 읽는 쪽이 없다.

`ingest()` 가 `@Transactional` 대신 `TransactionalOperator` 를 쓰는 이유를 적으며
"그 경로가 나중에 트리거가 붙을 유일한 입구다" 라고 그 자리를 지목한다.

### 왜 즉시가 아니라 주기 배치인가

실측 문서가 1,413 KB 이고 적재는 그것을 통째로 파싱해 씬 7 · 기능 491 행을 앉힌다.
등록 응답에 매달면 SDK 가 게임 실행마다 그 시간을 기다린다. 등록이 SDK 에 돌려주는 것은
문서를 받았다는 사실뿐이고 그것은 이미 참이다.

이 저장소의 다른 큐 소비자 셋이 전부 주기 배치다 — `KnowledgeBackfillScheduler`,
`SdkPerformanceRetentionScheduler`, `TestCaseEmbeddingScheduler`. 넷째를 다른 모양으로
만들 이유가 없다.

### 왜 재시도 장부가 이 이슈 안에 있나

`findPending` 은 `ingested_at IS NULL` 만 본다. 시도 횟수도 마지막 오류도 적을 칸이 없다.

지금은 그것이 비용을 만들지 않는다 — 부르는 쪽이 없으니 깨진 문서가 있어도 아무 일도
일어나지 않는다. **트리거가 붙는 순간 달라진다.** 파싱에서 죽는 문서 하나가 매 tick 마다
스토리지에서 1.4 MB 를 다시 읽고 다시 파싱하고, `ORDER BY received_at ASC` 라 언제나 큐의
앞자리를 차지한다. 영영 성공하지 못하면서 영영 재시도된다.

적재기 자신이 이미 그 되풀이를 알고 있다. `writeEvidence` 의 주석이
"그 거절은 문서 하나를 통째로 되돌린 뒤 **다음 tick 에 똑같이 되풀이된다**" 고 적는다.
그 "다음 tick" 을 만드는 것이 이 이슈다. 장부 없이 트리거만 붙이면 그 문장이 사실이 된다.

### 시도 횟수를 언제 올리나 — 집는 시점에 올린다

`EmbeddingQueueRepository.claimPending` 이 이 물음에 이미 답해 두었다:

> `attempts` 를 실패 시점이 아니라 이 시점에 올린다. 프로세스가 임베딩 도중 죽어도 시도가
> 계산되어, 워커를 죽이는 항목이 매 tick 되살아나 큐를 점유하는 일이 없다.

여기서 그 구멍이 더 크다. 1.4 MB 파싱은 프로세스를 죽일 수 있는 종류의 일이고, 실패
시점에만 올리면 **적재기를 죽이는 문서는 시도가 한 번도 기록되지 않아** 상한에 닿지 못한다.
장부를 달아 놓고도 무한 재시도가 남는다.

그래서 같은 모양을 쓴다 — 집는 문장 하나가 `FOR UPDATE SKIP LOCKED` 로 행을 잡고 그 자리에서
`ingest_attempts` 를 올린다. 그 UPDATE 는 적재 트랜잭션 **밖**에서 먼저 커밋된다.
적재 트랜잭션 안에 두면 롤백이 장부까지 되돌려 횟수가 영영 0이다.

`SKIP LOCKED` 는 덤이 아니다. 인스턴스가 둘이면 같은 문서를 동시에 집어 같은 1.4 MB 를 두 번
파싱한다.

### 제약

- 적재 로직·등록 경로를 건드리지 않는다. 더하는 것은 부르는 자리와 장부뿐이다
- `content_map` 행은 여전히 등록 경로가 소유한다
- 오류는 `common/error` 타입 예외로 던진다 (신규 `ResponseStatusException` 금지)
- 설정은 `@ConfigurationProperties` 로 받는다 (신규 `@Value` 금지)
- 마이그레이션 번호: develop 최고 `V46`, 미머지 PR 이 `V47`(#151) 과 `V49`(#155) 를 쥐고
  있다. `V50` 을 쓴다 — base 최고치 위이고 어느 peer 와도 겹치지 않는다

## Approach (Checklist)

- [ ] **Step 0: Recon** — 완료
  - `ContentMapIngestService` · `ContentMapDocumentRepository` · `V41` DDL 확인
  - 기존 스케줄러 셋의 모양 (`runBlocking` + `fixedDelay` + `@ConditionalOnProperty`)
  - `EmbeddingQueueRepository.claimPending` 의 claim-then-increment 패턴
  - `@ConfigurationPropertiesScan` 이 없어 `@EnableConfigurationProperties` 로 등록해야 함

- [ ] **Step 1: 장부 칸 — `V50__track_content_map_ingest_attempts.sql`**
  - `content_map_document` 에 두 칸을 더한다:
    - `ingest_attempts INT NOT NULL DEFAULT 0`
    - `last_error TEXT`
  - **인덱스는 건드리지 않는다.** `idx_content_map_document_pending` 은
    `(received_at) WHERE ingested_at IS NULL` 이고, 새 조건 `ingest_attempts < :max` 는
    범위 조건이라 선행 칸으로 넣으면 `ORDER BY received_at` 의 정렬을 오히려 깨뜨린다.
    걸러 낼 행이 몇 개 되지도 않는다 — 상한을 넘긴 문서는 드물어야 정상이다
  - 기존 행은 `DEFAULT 0` 으로 채워져 그대로 큐에 남는다 (backward-compatible)

- [ ] **Step 2: 엔티티·리포지토리**
  - `ContentMapDocumentEntity` 에 `ingestAttempts: Int = 0` · `lastError: String? = null` 추가
  - `findPending` → `claimPending(limit, maxAttempts)` 로 바꾼다. 한 문장이다:
    ```sql
    WITH claimed AS (
        SELECT d.id FROM content_map_document d
         WHERE d.ingested_at IS NULL AND d.ingest_attempts < :maxAttempts
         ORDER BY d.received_at ASC
         LIMIT :limit
         FOR UPDATE SKIP LOCKED
    )
    UPDATE content_map_document d
       SET ingest_attempts = d.ingest_attempts + 1
      FROM claimed c
     WHERE d.id = c.id
    RETURNING d.*
    ```
    `CoroutineCrudRepository` 의 `@Query` 로 `Flow<ContentMapDocumentEntity>` 를 받는다.
    RETURNING 매핑이 걸리면 `EmbeddingQueueRepository` 처럼 `DatabaseClient` 로 내린다
  - `recordFailure(id, lastError)` 추가 — `stampIngested` 와 같은 이유로 해당 칸만 UPDATE
    한다 (`save(copy())` 는 `received_at` 을 null 로 덮는다)

- [ ] **Step 3: `ingestPending` 이 실패 사유를 적는다**
  - 시그니처: `ingestPending(limit: Int = DEFAULT_BATCH, maxAttempts: Int = DEFAULT_MAX_ATTEMPTS)`
    — 기본값을 둬야 기존 호출부(`ContentMapIngestTransactionTest:106`)가 그대로 컴파일된다
  - `findPending` 대신 `claimPending` 을 부른다. 횟수는 거기서 이미 올랐다
  - 지금 `runCatching { }.onFailure { logger.error }.getOrNull()` 로 삼키는 자리에서
    `recordFailure` 를 부른다. **사유는 잘라서 싣는다** — Jackson 파싱 오류는 문서 원문을
    인용해 수 KB 가 되고, 그 값이 그대로 컬럼에 앉으면 장부가 문서 사본이 된다
    (`EFFECT_DETAIL_WIDTH` 를 자르는 것과 같은 이유). `ERROR_WIDTH = 1000`
  - `recordFailure` 자체가 던져도 배치가 멈추지 않게 감싼다. 장부를 못 적는 것이 큐를
    세울 이유는 아니다
  - 상한에 닿은 문서는 `logger.warn` 으로 한 번 알린다. 조용히 큐에서 빠지면 아무도 모른다
  - 한 문서의 실패가 나머지를 멈추지 않는 기존 동작은 그대로

- [ ] **Step 4: 설정 — `ContentMapIngestProperties`**
  - prefix `artel.content-map.ingest`
  - `enabled: Boolean = false` · `batchSize: Int = 5` · `maxAttempts: Int = 5` ·
    `intervalMillis: Long = 60_000`
  - `init { require(...) }` 로 하한 검증 (`KnowledgeBackfillProperties` 와 같은 모양)
  - `application.yml` 에 `ARTEL_CONTENT_MAP_INGEST_*` 환경변수로 뚫는다

- [ ] **Step 5: 스케줄러 — `ContentMapIngestScheduler`**
  - `@Configuration @EnableScheduling @EnableConfigurationProperties`
    `@ConditionalOnProperty(prefix="artel.content-map.ingest", name=["enabled"], havingValue="true")`
  - `@Scheduled(fixedDelayString=..., initialDelayString=...)` 의 `tick()` 이
    `runBlocking { ingestPending(batchSize, maxAttempts) }`
  - `CancellationException` 은 되던지고 나머지 `Exception` 은 로그만 — tick 이 죽어도
    스케줄은 살아 있어야 한다 (기존 둘과 같은 이유, 같은 주석)
  - 적재 건수가 0보다 크면 `logger.info`

- [ ] **Step 6: 테스트**
  - `ContentMapIngestTransactionTest` 를 **넓힌다.** 새 파일을 만들지 않는다 — 깨진 문서와
    멀쩡한 문서를 한 배치에 넣는 픽스처(`documentWithOverlongInputKey` ·
    `minimalDocument`)가 이미 거기 있다. 더할 케이스:
    - 실패한 문서에 `ingest_attempts = 1` 과 `last_error` 가 남는다
    - 상한을 넘긴 문서는 다음 `ingestPending` 이 더는 내주지 않는다
    - 그때도 멀쩡한 문서는 계속 적재된다
    - 성공한 문서는 기존대로 도장이 찍히고 큐에서 빠진다
  - `ContentMapIngestPropertiesTest` (신규) — 기본값이 꺼짐이고, 0 이하의 batch-size ·
    max-attempts · interval-millis 가 기동 시점에 거절된다
    (`SdkPerformanceRetentionPropertiesTest` 와 같은 모양)

  **스케줄러 단위 테스트는 쓰지 않는다.** 이 저장소에 목 라이브러리가 없고, 기존 스케줄러
  셋(`KnowledgeBackfillScheduler` · `SdkPerformanceRetentionScheduler` ·
  `TestCaseEmbeddingScheduler`) 중 어느 것도 단위 테스트를 갖고 있지 않다. `tick()` 하나를
  덮자고 mockk 를 들이는 것은 이 이슈가 살 범위가 아니다 — 그 자리에서 검증되지 않는 것은
  "서비스가 던져도 tick 이 예외를 밖으로 내지 않는다" 한 줄이고, 기존 둘과 같은 모양·같은
  주석으로 쓴 catch 블록이다. 리뷰에서 눈으로 본다

- [ ] **Step 7: Rollout / Rollback**
  - 기본 `enabled: false`. 배포의 부수 효과로 큐가 돌기 시작하지 않는다
  - 로컬·스테이지에서 켜 실측한 뒤 사람이 운영에서 켠다
  - 되돌리기는 플래그만 끄면 된다. 마이그레이션은 칸을 더하기만 해 되돌릴 필요가 없다

## Validation

- **Commands to run:**
  ```bash
  /opt/homebrew/bin/bash ./scripts/check-flyway-migrations.sh
  ./scripts/verify-flyway-upgrade.sh
  ./mvnw test -Dtest='ContentMapIngest*'
  ./mvnw clean test
  ```
  로컬 스택은 `colima start` + `artel-local-postgres` · `artel-local-minio`.

- **Expected output:**
  - flyway 정적 검사 exit 0. `V50` 은 어느 브랜치도 쥐지 않았다
  - 전체 스위트: develop 베이스라인과 **같은 6건**만 실패
    (`TestScenarioPipelineIntegrationTest:223`, `ContentMapIngestTransactionTest:99`,
    `SdkPerformanceIntegrationTest` × 4)
  - **`ContentMapIngestTransactionTest:99` 를 조심해서 읽는다.** 이번에 넓히는 바로 그
    테스트이고, 동시에 베이스라인 실패 6건 중 하나다 — 단독으로는 통과하고 전체
    스위트에서만 깨진다(간섭). 내가 깨뜨린 것과 원래 깨져 있던 것을 헷갈리지 않으려면
    `-Dtest='ContentMapIngest*'` 단독 실행과 전체 실행을 **둘 다** 돌려 대조한다
  - 손수 확인 (end-to-end): `soma/output-json/wv-editor-latest.json` (1,446,875 bytes) 을
    MinIO 에 올리고 등록해 둔 뒤, `enabled=true` 로 서버를 띄운다. 아무것도 부르지 않은 채
    `scenes=7 capabilities=491` 이 차고 `ingested_at` 도장이 찍히면 사슬이 이어진 것이다.
    지난 세션은 이 자리를 임시 컨트롤러로 손수 돌렸다. 그 컨트롤러가 필요 없어지는 것이
    이 이슈의 결과다

## Risks & Rollback

- **Risks:**
  - **`RETURNING d.*` 매핑** — `CoroutineCrudRepository` 의 `@Query` 가 CTE + UPDATE +
    RETURNING 을 엔티티로 매핑하지 못할 수 있다. 그때는 `DatabaseClient` 로 내린다
    (`EmbeddingQueueRepository` 가 그렇게 한다). 설계가 아니라 배선의 문제라 대안이 확실하다
  - **`runBlocking` 이 스케줄러 스레드를 문다** — 1.4 MB 파싱 5건이면 tick 하나가 길다.
    `fixedDelay` 라 겹치지는 않지만, 스케줄 풀을 다른 잡과 나눠 쓰는 만큼 간격 기본값을
    60초로 넉넉히 둔다
  - **상한을 넘긴 문서가 조용히 사라진다** — 큐에서 빠지되 행과 `last_error` 는 남고
    넘긴 순간 `warn` 을 찍는다. 조회 경로가 없는 것은 Non-goal
  - **마이그레이션 번호 경합** — `V47`·`V49` 를 쥔 PR 이 먼저 머지되면 develop 최고치가
    올라간다. `V50` 은 그 위라 여전히 안전하다. push 전마다 재확인

- **Rollback steps:**
  - `artel.content-map.ingest.enabled=false` 로 끈다. 코드는 남아도 아무 일도 하지 않는다
  - 코드까지 되돌리려면 `git revert`. `V50` 은 칸을 더하기만 해 남겨 두어도 무해하다

## Rejected feedback

- **`batchSize` 를 설정으로 빼지 말고 `DEFAULT_BATCH = 5` 상수를 그대로 쓰자** — 기존 세
  스케줄러가 전부 `batch-size` 를 노출한다. 넷째만 다르게 두는 편이 더 비싸다
- **`last_error` 없이 `ingest_attempts` 만 두자** — 횟수만으로는 "다섯 번 실패했다" 까지만
  알고 왜인지는 로그를 뒤져야 한다. 로그는 돌고 행은 남는다. `artel.knowledge.backfill` 도
  둘 다 둔다
- **재시도 장부를 별건 이슈로 떼자** — 장부 없는 트리거는 켤 수 없다. 켜는 순간 깨진 문서
  하나가 매 tick 1.4 MB 를 다시 읽는다. 트리거와 장부는 같은 이슈다

## Open Questions

- 상한을 넘긴 문서를 누가 어떻게 보나. 이 이슈는 `last_error` 를 남기고 `warn` 을 찍는
  데서 멈춘다. 조회 경로가 필요하면 별건으로 뗀다
- 간격 60초가 맞나. 근거 문서는 게임 실행마다 오므로 훨씬 드물다. 실측 뒤 조정
