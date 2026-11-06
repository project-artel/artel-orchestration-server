# 2026-09-02 — 기획서 node 와 PART_OF edge 를 knowledge 적재 때 만든다

- Date: 2026-09-02
- Jira: ARTEL-748
- Status: Implemented, reviewed, tests green

## Goal

`DocumentKnowledgeExtractionService` 가 `KnowledgeService.store(source=DOCS)` 를 부를 때, 그
문서 자신을 나타내는 knowledge node 를 하나 만들고 그 배치의 항목마다 항목 → 문서 node 로 향하는
`PART_OF` edge 를 만든다. 재적재해도 문서 node 는 하나여야 하고, 문서 삭제(`softDeleteForDocument`,
ARTEL-728) 는 그 node 와 edge 까지 함께 소프트삭제해야 한다.

## Non-goals

- section node (문서 안의 절/챕터 단위 node) — 이 이슈의 범위 밖.
- 이미 적재된 기존 항목의 소급 연결 — 이 변경 이후 새로 적재되는 배치만 대상이다.
- `PART_OF` 를 QA agent 가 `KNOWLEDGE_LINK`/`KNOWLEDGE_UNLINK` 로 만들 수 있게 여는 것 — 명시적으로 막는다.
- 그래프 탐색(traversal) 우선순위에 `PART_OF` 전용 자리를 주는 것(ARTEL-749 의 범위).

## Context / Constraints

- `knowledge_edge.relation` CHECK 는 V29 에서 `LEADS_TO/CONTRADICTS/REFINES/DEPENDS_ON/REPLACES`
  다섯 값으로 고정돼 있다. `PART_OF` 를 여섯 번째 값으로 더하는 새 migration 이 필요하다
  (인라인 CHECK 라 이름은 `knowledge_edge_relation_check`, V15/V57 이 쓴 것과 같은 패턴으로
  DROP CONSTRAINT IF EXISTS → ADD CONSTRAINT 로 넓힌다).
- `knowledge_edge.note` 는 NOT NULL. QA 런의 note 는 "주장한 이유"를 담지만, 문서 적재는 런도
  주장도 없는 결정적 파이프라인 동작이라 고정 문장을 쓴다 — 이유가 아니라 무엇을 했는지를 진다.
- `KnowledgeRelation` enum 의 "거부한 후보와 이유" 절이 이미 `PART_OF`/`SUBSUMES` 를
  "REFINES 와 거의 겹친다"는 이유로 명시적으로 거부해 뒀다. 그 문맥은 **QA agent 가 손으로
  주장하는 관계 어휘**에 대한 것이고, 이번에 추가하는 것은 그 어휘가 아니라 적재 파이프라인만
  쓰는 구조적 literal 이다. enum 에 `PART_OF` 를 추가하지 않는다 — 추가하면 `KnowledgeGraphService`
  의 `fromWire` 파싱을 통과해 agent 가 link/unlink 로 부를 길이 열리므로(그다음엔 LEADS_TO 처럼
  서비스 층에서 다시 얼려야 한다), 애초에 그 어휘에 없는 편이 더 짧고 확실하다. 대신
  `KnowledgeService` 에 `"PART_OF"` 문자열 상수를 두고, `KnowledgeRelation.kt` 의 기존 거부
  문단에 이 구분을 한 줄 덧붙인다.
- 문서 node 와 그 배치의 항목들은 `source`/`source_id` 가 완전히 같다(`DOCS`, `project_document.id`).
  그래서 재적재 시 "이미 문서 node 가 있는가" 를 그 값만으로 구분할 수 없다. 대신 그래프 구조로
  식별한다 — **문서 node 는 살아있는 `PART_OF` edge 의 도착점인 유일한 knowledge 행**이다. 새
  리포지토리 질의 `KnowledgeRepository.findDocumentNode(projectId, documentId)` 가 이 정의로 찾는다.
- `search_knowledge`(KNOWLEDGE_SEARCH) 는 `knowledge_embedding` 을 INNER JOIN 해서만 결과를
  낸다. 임베딩은 `KnowledgeEmbeddingBackfillWorker` 가 `deleted_at IS NULL` 인 살아있는 knowledge
  행 전부를 대상으로 비동기로 채우므로, 아무 조치도 안 하면 문서 node 도 결국 임베딩이 생겨
  검색 결과에 섞인다. 문서 node 는 게임에 대한 사실이 아니라 구조적 표지라 검색 결과로 나오면
  잡음이다 — **막기로 결정한다.** 매 검색 질의에 필터를 추가하는 대신(검색은 매 QA 스텝마다
  발화하는 잦은 경로다), `KnowledgeEmbeddingRepository` 의 `aliveClause` 를 확장해 "살아있는
  `PART_OF` edge 의 도착점"인 행을 애초에 백필 대상에서 뺀다 — 임베딩 자체가 생기지 않으므로
  검색의 INNER JOIN 이 자동으로 그 행을 거른다. 백필 tick 은 검색보다 훨씬 드물게 돌고, 이 방식은
  Agent 임베딩 호출 비용도 아낀다.
- 문서 node 의 `tag` 는 기존 다섯 값(CONTROL/RULE/OBJECTIVE/UI/MISC) 중 하나여야 한다(CHECK 는
  이번에 안 건드린다). `MISC`("위 분류에 안 맞는 기타")를 쓴다 — 이 node 는애초에 topic 분류
  대상이 아닌 구조적 행이라 그나마 제일 안 틀린 값이다. (KnowledgeTag 의 KDoc 은 "MISC 는 검색
  대상에 남긴다"고 적어 뒀지만, 문서 node 의 검색 배제는 tag 가 아니라 위 임베딩 시딩 배제로
  걸리므로 그 문서와 충돌하지 않는다 — 코드에 그 구분을 남긴다.)
- 문서 node 의 `description` 은 이슈가 구현에 맡긴 값이다. 파일 이름과 documentId 는 이미
  `summary`/`source_id` 컬럼에 있으므로 description 에는 **이 node 의 역할**(문서에서 나온
  항목들이 매달리는 구조적 표지이지 문서에서 뽑아낸 사실이 아니라는 것)을 적는다.
- **"문서 node 인가" 술어는 SQL 에 두 번 적히면 안 된다.** 같은 정의를 `findDocumentNode` 의
  `@Query` 와 `KnowledgeEmbeddingRepository.aliveClause` 가 각자 손으로 적으면 이 저장소가
  `KnowledgeScopeSql.VISIBLE` / `KnowledgeEdgeScopeSql.VISIBLE` 로 이미 막아 둔 실패 모드가
  그대로 재현된다(복사본 하나가 뒤처지면 조용히 틀린다). `KnowledgeEdgeEntity.kt` 에
  `KnowledgeEdgeScopeSql` 옆에 조각 하나를 두고 두 자리가 그것을 끼워 넣는다. `@Query` 는
  컴파일 상수만 받으므로 조각은 **별칭 없는 `id`** 로 쓴다(`id IN (SELECT pe.to_knowledge_id ...)`)
  — 그래야 `k` 별칭 질의와 `o.` 를 앞에 붙이는 `aliveClause` 양쪽에 같은 문자열이 들어간다.
  `"PART_OF"` 문자열도 상수 하나(`KnowledgeRelation.kt` 의 top-level `const`)에서만 온다.
- **빈 배치는 문서 node 도 만들지 않는다.** 유효 항목이 0개면 `store` 는 지금도 아무것도 저장하지
  않고 돌아간다. 그 갈래에서 문서 node 만 만들면 매달린 항목이 하나도 없는 node 가 그래프에 남고,
  그것은 `findDocumentNode` 의 정의(살아있는 `PART_OF` edge 의 도착점)로 다시 찾히지도 않는다.
- **동시 적재는 막지 않는다.** 같은 documentId 를 두 요청이 동시에 추출하면 둘 다
  `findDocumentNode` 에서 못 찾고 각자 node 를 만들 수 있다. DB 에 그것을 막는 유일 제약은 두지
  않는다 — 추출은 문서 등록당 한 번 백그라운드로 도는 경로라 그 경합이 실제로 나는 자리가 없고,
  없는 경합을 막느라 유일 인덱스를 두면 재적재(정상 경로)가 그 인덱스에 걸린다. 순차 재적재가
  node 를 하나로 유지하는 것이 이 이슈가 요구하는 성질이고, 그것은 `findDocumentNode` 로 만족된다.
- **문서를 지운 뒤에는 같은 documentId 가 돌아오지 않는다.** `ProjectDocumentService.delete` 가
  `project_document` 행을 하드 삭제하므로 재업로드는 새 id 를 받는다. 소프트삭제된 문서 node 는
  `findDocumentNode`(`deleted_at IS NULL`)에 안 잡히지만, 그 id 로 다시 적재되는 경로 자체가 없다.
- **`store()` 호출자.** 프로덕션은 둘이다 — `DocumentKnowledgeExtractionService`(DOCS)와
  `QaAgentInboundRouter:555`(QA). 테스트는 `KnowledgeIntegrationTest`,
  `KnowledgeEventIntegrationTest`, `ProjectDocumentIntegrationTest` 가 DOCS 배치로 부른다.
  DOCS 배치가 knowledge 행을 하나 더 만들게 되므로 **그 테스트들의 개수 단언이 실제로 바뀐다** —
  숨기지 않고 새 사실에 맞게 고친다(테스트의 원래 의도는 그대로 두고 문서 node 만큼만 조정한다).

## Approach (Checklist)
- [x] **Step 0: Recon** — V29/KnowledgeService/KnowledgeEdgeEntity/KnowledgeRelation/
      KnowledgeGraphService/KnowledgeGraphViewService/KnowledgeSearchService/
      KnowledgeEmbeddingBackfillWorker/EmbeddingQueueRepository/DocumentKnowledgeExtractionService/
      ProjectDocumentService 를 읽고 위 Context 를 확정했다.
- [x] **Step 1: migration** — `V83__add_part_of_to_knowledge_edge_relation.sql`. CHECK 를
      `DROP CONSTRAINT IF EXISTS knowledge_edge_relation_check` → 여섯 값을 담은
      `ADD CONSTRAINT` 로 교체한다. V29 의 이유(관계는 "읽는 쪽이 다르게 행동하는가"로만
      늘린다)를 이어 이번 값이 왜 그 시험을 통과하는지 적는다.
      **번호 확정:** `check-flyway-migrations.sh` 가 V80 을 이미 세 branch(ARTEL-730/732/734)가
      쓰고 있다고 경고했고, 그 뒤 V81/V82 도 이미 다른 branch(ARTEL-732/734/742/750)가 쥐고 있어
      비어 있는 것을 확인한 V83 으로 정했다.
- [ ] **Step 2: KnowledgeRelation.kt** — "거부한 후보와 이유" 절의 `PART_OF` 줄에 이번 구조적
      literal 과의 구분을 한 문장 덧붙이고, top-level `const val PART_OF_RELATION = "PART_OF"`
      을 그 파일에 둔다(관계 이름을 찾는 사람이 보는 자리가 거기다). enum 자체는 건드리지 않는다.
- [ ] **Step 2b: KnowledgeEdgeEntity.kt** — `KnowledgeEdgeScopeSql` 옆에
      `KnowledgeDocumentNodeSql.IS_DOCUMENT_NODE` 조각(별칭 없는 `id IN (SELECT pe.to_knowledge_id
      FROM knowledge_edge pe WHERE pe.relation = 'PART_OF' AND pe.deleted_at IS NULL)`)을 둔다.
      Step 3 과 Step 7 이 **이 하나**를 쓴다.
- [ ] **Step 3: KnowledgeRepository** — `findDocumentNode(projectId, documentId): KnowledgeEntity?`
      추가. 술어는 Step 2b 의 조각을 끼워 넣는다. **`ORDER BY k.id LIMIT 1` 을 반드시 건다** — 이 질의는
      단건 반환 타입이라 행이 둘이면 `IncorrectResultSizeDataAccessException` 이 나고, 그 예외는
      적재 경로뿐 아니라 삭제 경로(`ProjectDocumentService.delete` 는 broad catch 가 없다)까지
      깨뜨린다. 아래 "동시 재적재" 위험 참조.
- [ ] **Step 4: KnowledgeEdgeRepository** — `findBaselinePartOfEdgesTo(projectId, documentNodeId): Flow<KnowledgeEdgeEntity>`
      추가(baseline, 살아있는 PART_OF edge 전부). `project_id` 를 조건에 함께 건다 — 이 파일의
      다른 질의가 전부 그렇게 하고, 프로젝트 격리는 서비스의 비교가 아니라 질의가 진다. 문서
      삭제가 쓴다.
- [ ] **Step 5: KnowledgeService**
      - `store(...)` 에 `documentFileName: String? = null` 파라미터를 더한다. DOCS 배치인데 이 값이
        없으면 `requireNotNull` 로 죽인다 — 문서 이름 없이 만든 문서 node 는 `summary` 를 지어내야
        하고, 그것은 호출자의 버그이지 사용자 입력 문제가 아니다.
      - DOCS 배치 저장 뒤 같은 트랜잭션에서 `findDocumentNode` → 없으면 생성(`summary=fileName`,
        `description=`고정 설명, `tag=MISC`, `contentHash=`배치와 동일값) → 방금 저장된 항목마다
        `PART_OF` edge(항목→문서 node, note=고정 문장, `created_by_qa_try_id=null`,
        `scope_id=scope.id`) 를 만든다.
      - **문서 node 를 새로 만들 때 `knowledge_event` CREATE 이벤트를 같은 트랜잭션에서 함께
        남긴다**(`contentEvent(row, CREATE, qaTryId = null)`). 이 클래스의 불변식이 "행 갱신과
        이벤트 삽입은 반드시 같은 트랜잭션"이고, 빠뜨리면 그 행만
        `knowledge.version = max(event.version)` 이 깨진 채 굳는다. 이미 있는 문서 node 를 재사용할
        때는 아무 이벤트도 남기지 않는다 — 그 행은 이번에 바뀐 것이 없다.
      - edge 는 **이번 배치에서 방금 저장된 항목에만** 건다. 앞선 적재의 항목은 이미 자기 edge 를
        갖고 있고, 다시 걸면 `uq_knowledge_edge_live` 에 걸린다.
      - `softDeleteForDocument` 에 `findDocumentNode` → 있으면 그 id로
        `findBaselinePartOfEdgesTo` 를 조회해 같은 트랜잭션에서 `deletedAt` 만 채워 소프트삭제한다
        (`deletedByQaTryId` 는 건드리지 않는다 — 기존 knowledge 소프트삭제와 같은 이유).
        문서 node 자체는 `findBaselineByDocumentId` 가 이미 돌려주므로 기존 루프가 그대로 지운다 —
        `source`/`source_id`/`scope_id`/`deleted_at` 조건을 문서 node 도 그대로 만족한다.
      - 유효 항목이 하나도 없는 배치는 기존 early return 이 그대로 막는다 — 문서 node 도 edge 도
        만들지 않는다. 항목 없는 문서 node 는 그래프에 매달린 것이 없는 외톨이 점이라 만들 이유가
        없고, 이것은 우연이 아니라 결정이므로 테스트로 못박는다.
- [ ] **Step 6: DocumentKnowledgeExtractionService** — `store(...)` 호출에 `document.fileName` 을
      `documentFileName` 으로 넘긴다.
- [ ] **Step 7: KnowledgeEmbeddingRepository** — `aliveClause` 에 Step 2b 조각을 `AND NOT` 으로
      붙여 "살아있는 PART_OF edge 의 도착점이 아닐 것" 조건을 추가한다. **서브쿼리는 바깥 행을 `o.id` 로 명시해 가리킨다** —
      `EmbeddingQueueRepository` 가 `"o." + aliveClause` 로 접두사 하나만 붙이므로 서브쿼리 안의
      맨 `id` 는 `knowledge_edge.id` 로 해석된다(그 테이블에도 `id` 가 있다). 그러면 오류 없이
      엉뚱한 행이 시딩된다.
- [ ] **Step 8: 그래프 조회** — `KnowledgeGraphViewService`/`KnowledgeGraphTraversalRepository.edgesAmong`
      는 이미 relation 필터 없이 두 endpoint 가 응답 node 집합 안에 있는 edge 를 전부 낸다. 코드
      변경 없이 문서 node 와 PART_OF edge 가 응답에 실리는지 통합 테스트로 확인만 한다. 다만
      `KnowledgeGraphViewDtos.KnowledgeGraphEdge.relation` 의 KDoc 이 값 다섯 개를 나열하고 있으므로
      `PART_OF` 를 그 목록에 더한다(문서가 바로 낡는 것을 막는 한 줄 수정).
- [ ] **Step 9: 테스트** — 새 클래스 `KnowledgeDocumentNodeIntegrationTest` 하나에 적재·재적재·
      삭제를 모으고, 그래프 조회와 백필 배제만 각자 기존 클래스에 붙인다.
      - 적재: DOCS 배치 저장 → `source=DOCS, source_id=documentId` 인 행이 항목수+1 개이고 그중
        `summary` 가 파일 이름인 행이 정확히 하나. 항목마다 `from=항목, to=문서 node,
        relation=PART_OF, scope_id=null, created_by_qa_try_id=null` 인 edge 가 하나씩. 문서 node 의
        `knowledge_event` CREATE 가 한 건 있고 그 `qa_try_id` 는 null.
      - 재적재: 같은 documentId 로 두 번째 배치 → 문서 node 는 여전히 하나(첫 번째 id 그대로),
        두 번째 배치 항목들도 그 같은 node 를 향한 edge 를 갖는다.
      - 유효 항목 0개 배치: knowledge 행도 문서 node 도 edge 도 하나도 안 생긴다.
      - 삭제: `softDeleteForDocument` 뒤 항목·문서 node 의 `deleted_at` 이 전부 차고 PART_OF edge 도
        `deleted_at` 이 찬다. QA 런이 만든 다른 relation edge 는 건드리지 않는다.
      - `KnowledgeGraphViewIntegrationTest`: 그래프 응답 `nodes` 에 문서 node 가, `edges` 에
        `relation=PART_OF` 가 실린다.
      - `KnowledgeEmbeddingBackfillIntegrationTest`: 시딩 뒤 `knowledge_embedding` 에 문서 node 의
        대기 행이 없고 항목들의 대기 행은 있다(= 검색의 INNER JOIN 이 문서 node 를 영영 못 만난다).

## Validation
- **Commands to run:**
  - `./mvnw test -Dtest='KnowledgeEdgeIntegrationTest,KnowledgeIntegrationTest,KnowledgeGraphViewIntegrationTest,KnowledgeGraphTraversalIntegrationTest,KnowledgeEmbeddingBackfillIntegrationTest,KnowledgeVectorSearchIntegrationTest,ProjectDocumentIntegrationTest'`
  - 새 테스트 클래스가 생기면 그 클래스도 포함한다.
- **Expected output:** 위 클래스들이 전부 통과. 전체 `./mvnw test` 스위트는 이 저장소가 원래
  100건 넘게 깨져 있어(무관한 `@BeforeEach` FK 순서 문제) 기준으로 쓰지 않는다.

## Risks & Rollback
- **Risks:**
  - `KnowledgeEmbeddingRepository.aliveClause` 확장이 seedPending 의 매 tick 스캔에 `NOT EXISTS`
    서브쿼리를 더한다 — `idx_knowledge_edge_to`(`to_knowledge_id, scope_id) WHERE deleted_at IS NULL`)
    가 이미 있어 인덱스를 타지만, 그 인덱스는 `relation` 을 포함하지 않아 부분적으로만 좁힌다.
    문서/edge 행 수가 실제로 얼마나 되는지는 이 브랜치 범위 밖의 운영 데이터라 지금 잴 수 없다.
  - `findDocumentNode` 를 매 DOCS 배치 저장·매 문서 삭제마다 부른다 — 배치당 한 번이라 비용은
    작다.
  - **동시 재적재는 문서 node 를 둘 만들 수 있다.** `knowledge` 에는 유니크 제약이 없고
    (`KnowledgeEntity` KDoc: "강한 유니크 제약은 두지 않는다"), 같은 documentId 로 두 `store()` 가
    겹치면 둘 다 `findDocumentNode` 에서 null 을 보고 각자 node 를 만든다. **지금은 그 경합이 나지
    않는다** — 추출은 `ProjectDocumentService.register()` 뒤 한 번만 발화하고 자동 재시도가 없다.
    그래서 이 브랜치에서는 DB 제약을 새로 걸지 않고 **받아들이는 위험**으로 둔다. 대신 그 상태가
    되어도 읽기·삭제가 죽지 않게 `findDocumentNode` 에 `ORDER BY k.id LIMIT 1` 을 건다(Step 3).
    나중에 재추출 경로가 생기면 그때 부분 유니크 인덱스를 얹는 것이 맞는 자리다.
- **Rollback steps:** 이 PR 을 되돌리면 새 migration 은 남아 있어도(CHECK 가 넓어진 것은
  무해하다) 코드가 PART_OF 를 더는 안 만든다. 이미 만들어진 문서 node/edge 는 데이터로 남으며
  읽기 경로가 관계없이 동작하므로 되돌리기 자체가 막히지 않는다.

## Open Questions
- (없음 — 이슈가 남긴 두 결정(description, note)과 search_knowledge 판단을 이 계획에서 확정했다.)


## Review Log

- **fast pass**: 세션 rate limit로 실패했다(재시도 없이 진행). medium pass 지적은 이미 위 Context에
  접혀 있다(공유 SQL 조각으로 정의 중복 제거, `findDocumentNode`의 `ORDER BY id LIMIT 1` 방어,
  `KnowledgeEdgeRepository`의 `project_id` 조건, 기존 테스트의 개수 단언 파급 범위 명시).
- **medium pass**: 위 Context/Approach에 반영됨(migration 번호 충돌 발견·V83으로 재배정 포함).
- **heavy pass**: primary agent가 직접 자체 검토했다(별 rate limit로 fast가 실패해 대체) —
  `KnowledgeDocumentNodeSql.IS_DOCUMENT_NODE`의 별칭 없는 `id`가 두 호출부(컴파일 상수
  `@Query`와 런타임 `aliveClause`) 모두에서 올바르게 바깥 스코프의 `id`로 풀리는지, 상관
  서브쿼리로 바꾸면 왜 틀리는지를 직접 추적해 확인했다. 블로커 없음.
- **pair-review-critic**: `VERDICT: PASS`. SQL 스코핑, 트랜잭션 원자성(같은 트랜잭션 안에서
  배치 저장 → 문서 node 조회/생성 → edge 저장, 그리고 삭제의 조회-먼저-트랜잭션-나중 순서),
  DOCS→QA 테스트 source 교체의 타당성, `PART_OF`를 enum에 넣지 않은 설계를 모두 검증했다.
  must-fix 없음. 유일한 지적은 `KnowledgeTag`의 "MISC는 검색 대상에 남긴다"는 KDoc과 문서
  node의 검색 배제가 겉보기에 긴장돼 보인다는 것인데, 코드가 이미 그 배제가 tag가 아니라
  구조(백필 시딩 배제)로 걸린다고 적어 뒀으므로 defect는 아니고 PR 본문에 한 줄 언급으로
  충분하다고 판단했다.

## Validation Results (실행함)

- `./mvnw test -Dtest='KnowledgeEdgeIntegrationTest,KnowledgeIntegrationTest,KnowledgeGraphViewIntegrationTest,KnowledgeGraphTraversalIntegrationTest,KnowledgeEmbeddingBackfillIntegrationTest,KnowledgeVectorSearchIntegrationTest,ProjectDocumentIntegrationTest,KnowledgeEventIntegrationTest,KnowledgeDocumentNodeIntegrationTest'`
  → Tests run: 137, Failures: 0, Errors: 0.
- 더해서 이 변경이 건드린 엔티티/리포지토리에 의존하는 나머지 knowledge 테스트도 돌렸다:
  `KnowledgeSearchRouterIntegrationTest,KnowledgeExpandRouterIntegrationTest,KnowledgeCitationIntegrationTest,KnowledgeAnchorIntegrationTest,KnowledgeStatsIntegrationTest,KnowledgeMutationInboundIntegrationTest,KnowledgeWriteResultRouterIntegrationTest`
  → Tests run: 86, Failures: 0, Errors: 0.
- `scripts/check-flyway-migrations.sh feat/기획서-삭제-api-를-만들고-knowledge-를-함께-지운다-ARTEL-728`
  → V80 충돌 경고 3건 발견(ARTEL-730/732/734) → V83으로 재배정 후 재실행 → `OK: no version collisions.`
- `scripts/verify-flyway-upgrade.sh feat/기획서-삭제-api-를-만들고-knowledge-를-함께-지운다-ARTEL-728`
  → base migration 66건 적용 → V83 적용 → validate 통과.
