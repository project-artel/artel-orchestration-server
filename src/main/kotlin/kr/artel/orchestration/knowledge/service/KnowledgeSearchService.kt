package kr.artel.orchestration.knowledge.service

import kr.artel.orchestration.common.embedding.agent.EmbeddingClient
import kr.artel.orchestration.common.error.UpstreamUnavailableException
import kr.artel.orchestration.knowledge.config.KnowledgeBackfillProperties
import kr.artel.orchestration.knowledge.config.KnowledgeGraphProperties
import kr.artel.orchestration.knowledge.config.KnowledgeSearchProperties
import kr.artel.orchestration.knowledge.dto.KnowledgeAnchorView
import kr.artel.orchestration.knowledge.dto.KnowledgeExpandResponse
import kr.artel.orchestration.knowledge.dto.KnowledgeNeighbour
import kr.artel.orchestration.knowledge.dto.KnowledgeSearchHit
import kr.artel.orchestration.knowledge.dto.KnowledgeSearchResponse
import kr.artel.orchestration.knowledge.entity.KnowledgeAnchorEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeMode
import kr.artel.orchestration.knowledge.entity.KnowledgeRetrievalKind
import kr.artel.orchestration.knowledge.entity.KnowledgeScope
import kr.artel.orchestration.knowledge.entity.KnowledgeSource
import kr.artel.orchestration.knowledge.entity.KnowledgeTag
import kr.artel.orchestration.common.embedding.EmbeddedText
import kr.artel.orchestration.knowledge.entity.KnowledgeUsageEntity
import kr.artel.orchestration.knowledge.repository.KnowledgeAnchorRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeSearchRow
import kr.artel.orchestration.knowledge.repository.KnowledgeRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeUsageRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeVectorSearchRepository
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * knowledge 벡터 검색(ARTEL-186). QA WebSocket의 `KNOWLEDGE_SEARCH`가 이 서비스를 부른다.
 *
 * **검색어 임베딩을 Agent에 맡기는 이유.** 벡터를 만들 자격증명(OpenRouter 키)이 Agent에만 있다.
 * 검색 시점에 Orchestration이 `POST /embed`를 부르는 쪽이 기존 호출 방향(항상 Orchestration → Agent)과
 * 일치한다. 반대로 Agent가 벡터를 만들어 WS로 실어 보내면 왕복은 줄지만 1024개 float가 qa_log에
 * 그대로 남는다.
 *
 * **실패를 삼키지 않는다.** Agent 호출 실패·모델 불일치는 그대로 올린다. 무엇을 ERROR 프레임으로
 * 돌려줄지는 WS 계약을 아는 호출자(라우터)의 몫이고, 여기서 빈 결과로 뭉개면 호출자가 "없음"과
 * "고장"을 구분할 수 없게 된다.
 */
@Service
class KnowledgeSearchService(
    private val embeddingClient: EmbeddingClient,
    private val searchRepository: KnowledgeVectorSearchRepository,
    private val usageRepository: KnowledgeUsageRepository,
    private val knowledgeRepository: KnowledgeRepository,
    private val anchorRepository: KnowledgeAnchorRepository,
    private val knowledgeGraphService: KnowledgeGraphService,
    private val backfillProperties: KnowledgeBackfillProperties,
    private val searchProperties: KnowledgeSearchProperties,
    private val graphProperties: KnowledgeGraphProperties,
    private val clock: Clock
) {
    private val logger = LoggerFactory.getLogger(KnowledgeSearchService::class.java)

    /**
     * [projectId]의 [scope] 안에서 [query]에 의미가 가까운 knowledge를 찾는다.
     *
     * 결과가 비는 것은 정상이다 — 백필이 비동기라 벡터가 아직 없을 수 있고, 필터가 전부 걸러낼 수도
     * 있다. 그 경우 빈 [KnowledgeSearchResponse.results]로 답한다.
     *
     * 돌려주는 [KnowledgeSearchOutcome]은 **Agent로 나가는 응답과 기록용 사실을 갈라 둔 것**이다
     * (ARTEL-255). 버전을 [KnowledgeSearchHit]에 얹으면 WS payload가 달라지는데, 이 작업은 순수
     * 추가여야 하고 기존 읽기 경로의 결과가 달라져서는 안 된다.
     *
     * [scope]는 기본값이 없다(ARTEL-256). 검색은 지식창고를 읽는 가장 넓은 경로라, 여기서 스코프를
     * 빠뜨리면 실험 런이 다른 arm이 쌓은 지식을 그대로 읽는다 — 그리고 그 결과는 그럴듯해서 아무도
     * 알아채지 못한다. 빠뜨린 호출이 컴파일되지 않게 둔다.
     *
     * @param sceneName 이 씬에 묶인 지식만 본다(선택, ARTEL-591). **`anchor` 가 없는 지식은 걸러진다** —
     *   이 필터의 뜻이 "이 화면의 것"이라, 게임 전체의 사실까지 함께 내면 좁히는 것이 없다. 씬
     *   이름을 검증하지 않는 이유는 우리가 아는 씬 목록이 없기 때문이다(V55): 없는 이름은 오류가
     *   아니라 빈 결과다.
     */
    suspend fun search(
        projectId: Long,
        scope: KnowledgeScope,
        mode: KnowledgeMode,
        query: String,
        tags: List<KnowledgeTag>,
        source: KnowledgeSource?,
        sceneName: String?,
        limit: Int?
    ): KnowledgeSearchOutcome {
        // 검색이 읽을 파티션은 백필이 쓴 파티션이어야 한다. 그래서 model은 검색 설정이 아니라
        // 백필 설정에서 온다(KnowledgeSearchProperties 주석 참조).
        val model = backfillProperties.model
        val resolvedLimit = resolveLimit(limit)

        // knowledge_mode=off는 지식 없이 도는 대조군이다(ARTEL-256). 오류가 아니라 **정상적인 빈
        // 결과**로 답한다 — 빈 결과는 이미 계약상 정상이고(백필이 비동기라 늘 일어난다), 오류로
        // 답하면 Agent가 도구 실패로 보고 재시도하며 arm의 행동이 달라진다.
        //
        // 임베딩 호출 전에 끊는 것도 의도다. 어차피 버릴 벡터를 만드느라 대조군 arm에서만 /embed
        // 비용과 지연이 발생하면, 없애려던 변수를 다른 축으로 다시 들여오는 셈이다.
        if (!mode.readable) {
            logger.info("knowledge 검색 생략: project={}, scope={}, mode={}", projectId, scope, mode.wire)
            // 내보낸 것이 없으므로 남길 사용 기록(ARTEL-255)도 없다. 빈 결과를 "검색은 했는데 아무도
            // 안 걸렸다"로 기록하면 대조군 arm이 지식을 조회한 것처럼 집계된다.
            return KnowledgeSearchOutcome(
                response = KnowledgeSearchResponse(query = query, model = model, results = emptyList()),
                retrievals = emptyList()
            )
        }

        val rows = searchRepository.searchNearest(
            projectId = projectId,
            scope = scope,
            queryVector = embedQuery(query, model),
            kind = QUERY_KIND,
            model = model,
            tags = tags.map { it.name },
            source = source?.name,
            sceneName = sceneName,
            limit = resolvedLimit
        )

        // 검색어 본문과 결과 본문은 qa_log에 남기지 않는다(지식 본문이 타임라인을 오염시킨다).
        // 그래도 "검색이 돌긴 했는지"는 볼 수 있어야 하므로 여기서 길이와 개수만 남긴다.
        logger.info(
            "knowledge 검색: project={}, scope={}, 검색어 {}자, tags={}, source={}, scene={}, limit={}, 결과={}건",
            projectId, scope, query.length, tags, source, sceneName, resolvedLimit, rows.size
        )
        // 히트마다 한 홉 이웃을 붙인다(ARTEL-275). 관계가 하나도 없으면 인덱스 질의 하나가 빈
        // 결과를 내고 끝이라, 그래프가 쌓이기 전의 비용이 사실상 없다.
        val expansion = expandHits(projectId, scope, rows)
        // `anchor` 는 히트가 정해진 뒤 한 번에 데려온다(ARTEL-591). 히트마다 부르면 질의가 히트 수만큼
        // 늘고, 그 비용은 `anchor` 가 하나도 없는 프로젝트에서도 그대로 난다.
        val anchors = anchorsFor(scope, rows)

        return KnowledgeSearchOutcome(
            response = KnowledgeSearchResponse(
                query = query,
                model = model,
                results = rows.map { row ->
                    toHit(
                        row,
                        expansion.byHit[row.knowledgeId].orEmpty(),
                        anchors[row.knowledgeId].orEmpty()
                    )
                }
            ),
            // 순위는 이 목록의 순서다. 리포지토리가 동점까지 id로 못박아 정렬하므로 같은 질의는
            // 같은 순위를 낸다 — 기록된 rank가 재현 가능해야 나중에 순위와 유용성을 견줄 수 있다.
            //
            // 이웃의 기록은 뒤에 붙인다. rank가 null이라 히트의 순위를 흐리지 않는다.
            retrievals = rows.mapIndexed { index, row ->
                KnowledgeRetrieval(
                    knowledgeId = row.knowledgeId,
                    version = row.version,
                    rank = index + 1,
                    score = 1.0 - row.distance,
                    // 질의에 직접 걸린 히트. 이웃과 갈라 두는 이유는 KnowledgeRetrievalKind에 있다.
                    kind = KnowledgeRetrievalKind.DIRECT
                )
            } + expansion.retrievals
        )
    }

    /**
     * 히트마다 1홉 이웃을 데려온다(ARTEL-275).
     *
     * **한 번의 확장으로 히트 전부를 편다.** 히트마다 따로 부르면 같은 이웃이 여러 히트에 중복으로
     * 붙고 총 상한도 히트 수만큼 늘어난다. [KnowledgeGraphService.expand]가 seed 여럿을 받고
     * visited 집합을 공유하는 이유가 이것이다.
     *
     * `via`로 어느 히트에 매달렸는지가 돌아오므로 그 값으로 다시 나눈다. 다만 `via`는 **정규 id**이고
     * 히트의 id는 이 스코프에서 보이는 행의 id라, 그림자가 낀 경우 둘이 다르다 — 그래서 정규 id로
     * 되짚는 표를 만든다.
     *
     * 실패를 삼키지 않는 이 클래스의 규칙은 여기에도 적용된다. 이웃을 못 가져오는 것은 DB 오류이지
     * "이웃이 없음"이 아니고, 빈 목록으로 뭉개면 그래프가 비어 있는 것과 구분되지 않는다.
     */
    private suspend fun expandHits(
        projectId: Long,
        scope: KnowledgeScope,
        rows: List<KnowledgeSearchRow>
    ): HitExpansion {
        if (!graphProperties.expandSearchHits || rows.isEmpty()) return HitExpansion(emptyMap(), emptyList())

        val outcome = knowledgeGraphService.expand(
            projectId = projectId,
            scope = scope,
            seedIds = rows.map { it.knowledgeId },
            depth = 1,
            fanout = graphProperties.searchFanout,
            nodeBudget = graphProperties.searchNeighbourLimit,
            // 검색 자체가 벡터 검색이라 히트의 벡터 이웃은 limit을 올렸으면 나왔을 것에 가깝다.
            // 자동으로 붙이면 "limit 올리기"가 분장한 것이 되고 전사 비용만 두 배가 된다.
            similar = null,
            // 에이전트가 요청한 적 없이 밀어넣은 이웃이다. 안 쓰는 것이 정상이라, 직접 요청한
            // 이웃(EXPAND)과 같은 분모에 담기면 인용률이 검색 설정에 따라 흔들린다.
            kind = KnowledgeRetrievalKind.SEARCH_NEIGHBOR
        )
        if (outcome.neighbours.isEmpty()) return HitExpansion(emptyMap(), emptyList())

        val hitIdByCanonical = knowledgeGraphService.canonicalIndex(projectId, scope, rows.map { it.knowledgeId })
        val byHit = outcome.neighbours
            .groupBy { hitIdByCanonical[it.via.toLong()] }
            .filterKeys { it != null }
            .mapKeys { (key, _) -> key!! }
            .mapValues { (_, value) -> value.map { it.toDto() } }
        return HitExpansion(byHit, outcome.retrievals)
    }

    suspend fun expand(
        projectId: Long,
        scope: KnowledgeScope,
        mode: KnowledgeMode,
        knowledgeId: Long,
        depth: Int?,
        includeSimilar: Boolean
    ): KnowledgeExpandServiceOutcome {
        val empty = KnowledgeExpandServiceOutcome(
            response = KnowledgeExpandResponse(
                id = knowledgeId.toString(),
                summary = "",
                neighbors = emptyList(),
                truncated = false
            ),
            retrievals = emptyList()
        )
        // 확장도 검색과 **같은 게이트를 지난다.** off면 빈 결과이고 오류가 아니다 — 대조군 arm에서
        // 도구가 실패하면 Agent가 재시도하며 행동이 달라지고, 없애려던 변수가 다시 든다.
        if (!mode.readable) {
            logger.info("knowledge 확장 생략: project={}, scope={}, mode={}", projectId, scope, mode.wire)
            return empty
        }

        // 없는 항목·다른 프로젝트·다른 스코프의 항목은 여기서 걸린다. 오류가 아니라 빈 결과다 —
        // 스코프 런이 방금 자기가 지운 항목을 펴려는 경우가 정상 경로에 있다.
        val seed = knowledgeRepository.findVisibleById(knowledgeId, projectId, scope.id) ?: return empty

        // 상한으로 자르되 거절하지 않는다(resolveLimit과 같은 판단). 결과가 Agent 컨텍스트로
        // 들어가는 것을 막는 것이 목적이지 도구 호출을 실패시키는 것이 목적이 아니다.
        val resolvedDepth = (depth ?: graphProperties.defaultDepth).coerceIn(1, graphProperties.maxDepth)

        val outcome = knowledgeGraphService.expand(
            projectId = projectId,
            scope = scope,
            seedIds = listOf(knowledgeId),
            depth = resolvedDepth,
            fanout = graphProperties.fanout,
            nodeBudget = graphProperties.nodeBudget,
            similar = if (!includeSimilar || graphProperties.similarLimit == 0) {
                null
            } else {
                // 모델은 백필 설정에서 온다. 검색이 그러는 것과 같은 이유이고, 갈라 두면 실패가
                // 오류가 아니라 조용한 빈 결과다.
                SimilarSpec(
                    kind = QUERY_KIND,
                    model = backfillProperties.model,
                    maxDistance = graphProperties.similarMaxDistance,
                    limit = graphProperties.similarLimit
                )
            },
            // 에이전트가 `expand_knowledge`로 **직접 요청한** 이웃이다. 요청해 놓고 안 쓴 것은
            // 밀어넣은 이웃을 안 쓴 것보다 훨씬 강한 부정 신호라 따로 센다.
            kind = KnowledgeRetrievalKind.EXPAND
        )

        // 검색과 같은 이유로 본문은 남기지 않고 개수만 남긴다.
        logger.info(
            "knowledge 확장: project={}, scope={}, seed={}, depth={}, 이웃={}건, truncated={}",
            projectId, scope, knowledgeId, resolvedDepth, outcome.neighbours.size, outcome.truncated
        )
        return KnowledgeExpandServiceOutcome(
            response = KnowledgeExpandResponse(
                id = knowledgeId.toString(),
                summary = seed.summary,
                neighbors = outcome.neighbours.map { it.toDto() },
                truncated = outcome.truncated
            ),
            retrievals = outcome.retrievals
        )
    }

    /**
     * 검색이 [qaTryId]에 내보낸 히트를 기록한다(ARTEL-255).
     *
     * **실패를 삼키지 않는다** — 이 클래스의 나머지와 같은 규칙이다. 무엇을 어떻게 무마할지는
     * WS 계약을 아는 호출자(라우터)의 몫이고, 여기서 조용히 넘기면 기록이 빠진 것을 아무도 모른다.
     *
     * `cited`는 여기서 채우지 않는다. 검색 시점에는 그 항목이 쓰일지 알 수 없고, 답은 나중에
     * 스텝 판정이 가져온다([KnowledgeCitationService]). null이 "모른다"이며, false로 채우면
     * 인용을 보고할 수 없는 런까지 "아무것도 인용하지 않았다"로 읽힌다.
     *
     * @param step 이 검색이 난 런 스텝. Agent가 프레임에 실어 주면 값이 있고, 싣지 않는 런에서는
     *   null이다(ARTEL-293). 기록되는 메타데이터일 뿐 인용 매칭의 키가 아니다 — 앞선 스텝에서
     *   검색한 것을 뒤 스텝에서 인용하는 것이 정상 동작이라, 키로 삼으면 그 인용이 증발한다.
     */
    suspend fun recordRetrievals(
        qaTryId: Long,
        retrievals: List<KnowledgeRetrieval>,
        step: Int? = null
    ) {
        if (retrievals.isEmpty()) return
        val now = Instant.now(clock)
        usageRepository.saveAll(
            retrievals.map {
                KnowledgeUsageEntity(
                    qaTryId = qaTryId,
                    knowledgeId = it.knowledgeId,
                    knowledgeVersion = it.version,
                    step = step,
                    rank = it.rank,
                    score = it.score?.toFloat(),
                    // 종류는 만든 자리에서 실려 온다. 여기서 rank로 유추하면, 새 검색 경로가
                    // 생겼을 때 조용히 틀린다.
                    retrievalKind = it.kind.name,
                    retrievedAt = now
                )
            }
            // saveAll은 콜드 Flow라 소비해야 실제 저장이 일어난다.
        ).collect()
    }

    /**
     * 요청한 개수를 상한 안으로 자른다.
     *
     * 상한을 넘겼다고 거절하지 않는다. 결과가 Agent 컨텍스트로 들어가는 것을 막는 것이 목적이지
     * 도구 호출을 실패시키는 것이 목적이 아니다. 0이나 음수도 같은 이유로 1로 올린다.
     */
    private fun resolveLimit(requested: Int?): Int =
        (requested ?: searchProperties.defaultLimit).coerceIn(1, searchProperties.maxLimit)

    /**
     * 검색어를 pgvector 리터럴로 만든다.
     *
     * **모델 slug를 대조하는 이유.** 저장된 벡터는 설정 model로 라벨링돼 있다. Agent가 다른 모델로
     * 임베딩을 돌려주면 좌표계가 다른 벡터로 거리를 재는 셈이고, 결과는 오류가 아니라 **그럴듯한
     * 엉터리 순위**로 나온다. 조용히 틀리느니 실패하는 편이 낫다(백필 워커도 같은 판단이다).
     */
    private suspend fun embedQuery(query: String, model: String): String {
        val response = embeddingClient.embed(listOf(query))
        if (response.model != model) {
            throw KnowledgeQueryEmbeddingException(
                "Agent가 돌려준 임베딩 모델(${response.model})이 검색 설정(${model})과 다릅니다. " +
                    "artel.knowledge.backfill.model을 Agent 설정과 맞추세요."
            )
        }
        val vector = response.vectors.singleOrNull()
        if (vector.isNullOrEmpty()) {
            throw KnowledgeQueryEmbeddingException(
                "Agent /embed가 검색어 1건에 대해 벡터를 돌려주지 않았습니다: ${response.vectors.size}건"
            )
        }
        // 리터럴 형식은 EmbeddedText가 이미 정의한다. 여기서 다시 만들면 형식이 두 곳에 생긴다.
        return EmbeddedText(query, vector).toVectorLiteral()
    }

    /**
     * 코사인 거리를 유사도로 뒤집는다. pgvector의 `<=>`는 `1 - cosine_similarity`라 그대로 되돌린다.
     */
    private fun toHit(
        row: KnowledgeSearchRow,
        neighbours: List<KnowledgeNeighbour>,
        anchors: List<KnowledgeAnchorView>
    ) = KnowledgeSearchHit(
        id = row.knowledgeId.toString(),
        tag = row.tag,
        source = row.source,
        summary = row.summary,
        description = row.description,
        score = 1.0 - row.distance,
        neighbors = neighbours,
        anchors = anchors
    )

    /**
     * 히트마다 묶인 씬·화면을 데려온다(ARTEL-591).
     *
     * **스코프 술어를 리포지토리가 진다.** 히트는 이미 스코프를 통과한 것이라 여기서 한 번 더
     * 거는 것이 군더더기로 보이지만, `anchor` 조회가 자기 힘으로 스코프를 지키지 않으면 나중에 이
     * 리포지토리를 다른 곳에서 부르는 순간 가려진 지식의 `anchor` 가 샌다. 술어를 한 곳에만 두는
     * [kr.artel.orchestration.knowledge.entity.KnowledgeScopeSql] 의 규칙 그대로다.
     *
     * 실패를 삼키지 않는 이 클래스의 규칙은 여기에도 적용된다 — `anchor` 를 못 읽는 것은 DB 오류이지
     * "`anchor` 가 없음"이 아니다.
     */
    private suspend fun anchorsFor(
        scope: KnowledgeScope,
        rows: List<KnowledgeSearchRow>
    ): Map<Long, List<KnowledgeAnchorView>> {
        // 빈 컬렉션을 넘기면 `IN ()` 이 되어 SQL 이 깨진다.
        if (rows.isEmpty()) return emptyMap()
        return anchorRepository.findVisibleFor(rows.map { it.knowledgeId }, scope.id)
            .toList()
            .groupBy(KnowledgeAnchorEntity::knowledgeId) { it.toView() }
    }

    /** `anchor` 행을 WS 계약 모양으로. id 계열은 다른 payload와 같이 문자열로 낸다. */
    private fun KnowledgeAnchorEntity.toView() =
        KnowledgeAnchorView(sceneName = sceneName, screenId = screenId?.toString())

    /** 히트별 이웃과 이웃의 기록. 한 번의 확장 결과를 히트로 다시 나눈 것이다. */
    private data class HitExpansion(
        val byHit: Map<Long, List<KnowledgeNeighbour>>,
        val retrievals: List<KnowledgeRetrieval>
    )

    private companion object {
        /** 이번 스프린트가 채우는 벡터는 QUERY뿐이다. CONTENT는 V18에 자리만 있다. */
        const val QUERY_KIND = "QUERY"
    }
}

/**
 * 검색 한 번의 결과. Agent로 나갈 응답과 남길 사실을 갈라 둔다(ARTEL-255).
 *
 * 한 타입에 합치지 않은 이유는 [response]가 **WS 계약 그 자체**이기 때문이다. 여기에 기록용 필드를
 * 얹으면 그대로 Agent에게 나가고, 이 작업은 기존 읽기 경로의 결과를 바꾸지 않아야 한다.
 *
 * @property retrievals 응답에 실린 히트와 **같은 순서**의 기록용 사실. 비어 있으면 남길 것이 없다.
 */
data class KnowledgeSearchOutcome(
    val response: KnowledgeSearchResponse,
    val retrievals: List<KnowledgeRetrieval>
)

/**
 * 확장 한 번의 결과. [KnowledgeSearchOutcome]과 같은 이유로 응답과 기록을 갈라 둔다.
 *
 * 서비스 계층의 [KnowledgeExpandOutcome]과 이름이 비슷하지만 층이 다르다: 그쪽은 `version`을 든
 * 내부 표현이고, 이쪽은 WS로 나갈 payload가 이미 만들어진 상태다.
 */
data class KnowledgeExpandServiceOutcome(
    val response: KnowledgeExpandResponse,
    val retrievals: List<KnowledgeRetrieval>
)

/**
 * 검색이 내보낸 히트 하나에 대해 남길 사실.
 *
 * @property version 내보낸 시점의 content 버전. 나중에 그 항목이 고쳐져도 이 런이 읽은 것은 이 버전이다.
 * @property rank 1부터. **그래프 이웃은 null이다**(ARTEL-275) — 이웃은 관련도 순위를 매긴 결과가
 *   아니라 관계를 타고 온 것이라, 0이나 임의의 값으로 채우면 순위와 유용성을 견주는 질의가
 *   조용히 틀린다. null이 "모른다"인 그 컬럼의 관례 그대로다.
 * @property score 코사인 유사도. Agent에게 나간 값과 같다. 관계로 온 이웃은 유사도가 없어 null이다.
 * @property kind 이 사실이 **어느 경로로** 만들어졌는지(ARTEL-293). [rank]가 null인 두 경로를
 *   가르는 것이 이 필드의 존재 이유이고, 그래서 **만드는 자리에서 실린다** — 공용 싱크인
 *   [KnowledgeSearchService.recordRetrievals]는 여기 실린 값을 그대로 저장할 뿐 추측하지 않는다.
 */
data class KnowledgeRetrieval(
    val knowledgeId: Long,
    val version: Int,
    val rank: Int?,
    val score: Double?,
    val kind: KnowledgeRetrievalKind
)

/**
 * Agent가 검색어를 쓸 수 있는 벡터로 만들어 주지 못했다.
 *
 * `common/error` 계층을 따라 [UpstreamUnavailableException]을 상속한다(ARTEL-193). 지금 이 예외를 보는
 * 것은 HTTP advice가 아니라 QA WS 라우터지만, 계층 밖에 새 예외 종류를 만들면 나중에 이 검색이
 * HTTP로도 노출될 때 매핑이 빠진 채로 500이 된다.
 *
 * **재시도가 통하는 경우와 아닌 경우가 섞여 있다.** 모델 slug 불일치는 설정을 고쳐야 풀리고 저절로
 * 낫지 않는다. 그래도 별도 타입으로 쪼개지 않은 것은, 부르는 쪽(라우터)이 둘을 똑같이 ERROR
 * 프레임으로 처리하기 때문이다. 실제로 갈라 다뤄야 하는 소비처가 생기면 그때 나눈다.
 */
class KnowledgeQueryEmbeddingException(message: String, cause: Throwable? = null) :
    UpstreamUnavailableException(message, code = "knowledge_embedding_unavailable", cause = cause)
