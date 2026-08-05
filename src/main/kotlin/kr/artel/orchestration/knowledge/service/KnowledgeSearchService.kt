package kr.artel.orchestration.knowledge.service

import kr.artel.orchestration.common.embedding.agent.EmbeddingClient
import kr.artel.orchestration.common.error.UpstreamUnavailableException
import kr.artel.orchestration.knowledge.config.KnowledgeBackfillProperties
import kr.artel.orchestration.knowledge.config.KnowledgeSearchProperties
import kr.artel.orchestration.knowledge.dto.KnowledgeSearchHit
import kr.artel.orchestration.knowledge.dto.KnowledgeSearchResponse
import kr.artel.orchestration.knowledge.entity.KnowledgeMode
import kr.artel.orchestration.knowledge.entity.KnowledgeScope
import kr.artel.orchestration.knowledge.entity.KnowledgeSource
import kr.artel.orchestration.knowledge.entity.KnowledgeTag
import kr.artel.orchestration.common.embedding.EmbeddedText
import kr.artel.orchestration.knowledge.repository.KnowledgeSearchRow
import kr.artel.orchestration.knowledge.repository.KnowledgeVectorSearchRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

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
    private val backfillProperties: KnowledgeBackfillProperties,
    private val searchProperties: KnowledgeSearchProperties
) {
    private val logger = LoggerFactory.getLogger(KnowledgeSearchService::class.java)

    /**
     * [projectId]의 [scope] 안에서 [query]에 의미가 가까운 knowledge를 찾는다.
     *
     * 결과가 비는 것은 정상이다 — 백필이 비동기라 벡터가 아직 없을 수 있고, 필터가 전부 걸러낼 수도
     * 있다. 그 경우 빈 [KnowledgeSearchResponse.results]로 답한다.
     *
     * [scope]는 기본값이 없다(ARTEL-256). 검색은 지식창고를 읽는 가장 넓은 경로라, 여기서 스코프를
     * 빠뜨리면 실험 런이 다른 arm이 쌓은 지식을 그대로 읽는다 — 그리고 그 결과는 그럴듯해서 아무도
     * 알아채지 못한다. 빠뜨린 호출이 컴파일되지 않게 둔다.
     */
    suspend fun search(
        projectId: Long,
        scope: KnowledgeScope,
        mode: KnowledgeMode,
        query: String,
        tags: List<KnowledgeTag>,
        source: KnowledgeSource?,
        limit: Int?
    ): KnowledgeSearchResponse {
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
            return KnowledgeSearchResponse(query = query, model = model, results = emptyList())
        }

        val rows = searchRepository.searchNearest(
            projectId = projectId,
            scope = scope,
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
            "knowledge 검색: project={}, scope={}, 검색어 {}자, tags={}, source={}, limit={}, 결과={}건",
            projectId, scope, query.length, tags, source, resolvedLimit, rows.size
        )
        return KnowledgeSearchResponse(
            query = query,
            model = model,
            results = rows.map(::toHit)
        )
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
