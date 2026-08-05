package kr.artel.orchestration.knowledge.service

import kr.artel.orchestration.common.embedding.agent.EmbeddingClient
import kr.artel.orchestration.common.error.UpstreamUnavailableException
import kr.artel.orchestration.knowledge.config.KnowledgeBackfillProperties
import kr.artel.orchestration.knowledge.config.KnowledgeSearchProperties
import kr.artel.orchestration.knowledge.dto.KnowledgeSearchHit
import kr.artel.orchestration.knowledge.dto.KnowledgeSearchResponse
import kr.artel.orchestration.knowledge.entity.KnowledgeSource
import kr.artel.orchestration.knowledge.entity.KnowledgeTag
import kr.artel.orchestration.common.embedding.EmbeddedText
import kr.artel.orchestration.knowledge.entity.KnowledgeUsageEntity
import kr.artel.orchestration.knowledge.repository.KnowledgeSearchRow
import kr.artel.orchestration.knowledge.repository.KnowledgeUsageRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeVectorSearchRepository
import kotlinx.coroutines.flow.collect
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
    private val backfillProperties: KnowledgeBackfillProperties,
    private val searchProperties: KnowledgeSearchProperties,
    private val clock: Clock
) {
    private val logger = LoggerFactory.getLogger(KnowledgeSearchService::class.java)

    /**
     * [projectId] 안에서 [query]에 의미가 가까운 knowledge를 찾는다.
     *
     * 결과가 비는 것은 정상이다 — 백필이 비동기라 벡터가 아직 없을 수 있고, 필터가 전부 걸러낼 수도
     * 있다. 그 경우 빈 [KnowledgeSearchResponse.results]로 답한다.
     *
     * 돌려주는 [KnowledgeSearchOutcome]은 **Agent로 나가는 응답과 기록용 사실을 갈라 둔 것**이다
     * (ARTEL-255). 버전을 [KnowledgeSearchHit]에 얹으면 WS payload가 달라지는데, 이 작업은 순수
     * 추가여야 하고 기존 읽기 경로의 결과가 달라져서는 안 된다.
     */
    suspend fun search(
        projectId: Long,
        query: String,
        tags: List<KnowledgeTag>,
        source: KnowledgeSource?,
        limit: Int?
    ): KnowledgeSearchOutcome {
        // 검색이 읽을 파티션은 백필이 쓴 파티션이어야 한다. 그래서 model은 검색 설정이 아니라
        // 백필 설정에서 온다(KnowledgeSearchProperties 주석 참조).
        val model = backfillProperties.model
        val resolvedLimit = resolveLimit(limit)

        val rows = searchRepository.searchNearest(
            projectId = projectId,
            queryVector = embedQuery(query, model),
            kind = QUERY_KIND,
            model = model,
            tags = tags.map { it.name },
            source = source?.name,
            limit = resolvedLimit
        )

        // 검색어 본문과 결과 본문은 qa_log에 남기지 않는다(지식 본문이 타임라인을 오염시킨다).
        // 그래도 "검색이 돌긴 했는지"는 볼 수 있어야 하므로 여기서 길이와 개수만 남긴다.
        logger.info(
            "knowledge 검색: project={}, 검색어 {}자, tags={}, source={}, limit={}, 결과={}건",
            projectId, query.length, tags, source, resolvedLimit, rows.size
        )
        return KnowledgeSearchOutcome(
            response = KnowledgeSearchResponse(
                query = query,
                model = model,
                results = rows.map(::toHit)
            ),
            // 순위는 이 목록의 순서다. 리포지토리가 동점까지 id로 못박아 정렬하므로 같은 질의는
            // 같은 순위를 낸다 — 기록된 rank가 재현 가능해야 나중에 순위와 유용성을 견줄 수 있다.
            retrievals = rows.mapIndexed { index, row ->
                KnowledgeRetrieval(
                    knowledgeId = row.knowledgeId,
                    version = row.version,
                    rank = index + 1,
                    score = 1.0 - row.distance
                )
            }
        )
    }

    /**
     * 검색이 [qaTryId]에 내보낸 히트를 기록한다(ARTEL-255).
     *
     * **실패를 삼키지 않는다** — 이 클래스의 나머지와 같은 규칙이다. 무엇을 어떻게 무마할지는
     * WS 계약을 아는 호출자(라우터)의 몫이고, 여기서 조용히 넘기면 기록이 빠진 것을 아무도 모른다.
     *
     * `step`과 `cited`는 채우지 않는다. 전자는 `KnowledgeSearchRequest`가 step을 안 싣기 때문이고,
     * 후자는 인용 보고 기능이 아직 없기 때문이다. 둘 다 null이 "모른다"는 뜻이며, 특히 `cited`를
     * false로 채우면 이 시기의 런 전부가 "아무것도 인용하지 않았다"로 읽힌다.
     */
    suspend fun recordRetrievals(qaTryId: Long, retrievals: List<KnowledgeRetrieval>) {
        if (retrievals.isEmpty()) return
        val now = Instant.now(clock)
        usageRepository.saveAll(
            retrievals.map {
                KnowledgeUsageEntity(
                    qaTryId = qaTryId,
                    knowledgeId = it.knowledgeId,
                    knowledgeVersion = it.version,
                    rank = it.rank,
                    score = it.score.toFloat(),
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
    private fun toHit(row: KnowledgeSearchRow) = KnowledgeSearchHit(
        id = row.knowledgeId.toString(),
        tag = row.tag,
        source = row.source,
        summary = row.summary,
        description = row.description,
        score = 1.0 - row.distance
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
 * 검색이 내보낸 히트 하나에 대해 남길 사실.
 *
 * @property version 내보낸 시점의 content 버전. 나중에 그 항목이 고쳐져도 이 런이 읽은 것은 이 버전이다.
 * @property rank 1부터.
 * @property score 코사인 유사도. Agent에게 나간 값과 같다.
 */
data class KnowledgeRetrieval(
    val knowledgeId: Long,
    val version: Int,
    val rank: Int,
    val score: Double
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
