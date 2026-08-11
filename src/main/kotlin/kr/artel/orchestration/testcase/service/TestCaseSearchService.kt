package kr.artel.orchestration.testcase.service

import kr.artel.orchestration.common.embedding.EmbeddedText
import kr.artel.orchestration.common.embedding.agent.EmbeddingClient
import kr.artel.orchestration.common.error.UpstreamUnavailableException
import kr.artel.orchestration.testcase.config.TestCaseEmbeddingProperties
import kr.artel.orchestration.testcase.config.TestCaseSearchProperties
import kr.artel.orchestration.testcase.dto.TestCaseSearchHit
import kr.artel.orchestration.testcase.dto.TestCaseSearchResponse
import kr.artel.orchestration.testcase.repository.TestCaseSearchRow
import kr.artel.orchestration.testcase.repository.TestCaseVectorSearchRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * TestCase 벡터 검색(ARTEL-206). 시나리오 작성 세션의 `TEST_CASE_SEARCH` 프레임(후속 Step)이 이
 * 서비스를 부른다. knowledge 검색과 대칭이다.
 *
 * **검색어 임베딩을 Agent에 맡긴다.** 벡터를 만들 자격증명이 Agent에만 있어, 검색 시점에
 * Orchestration이 `/embed`를 부른다(기존 호출 방향과 일치). 케이스 벡터는 CONTENT 파티션이며, 검색이
 * 읽는 model은 백필이 쓴 model과 같아야 하므로 [TestCaseEmbeddingProperties.model]을 그대로 쓴다.
 *
 * **실패를 삼키지 않는다.** Agent 호출 실패·모델 불일치는 그대로 올린다. 빈 결과로 뭉개면 호출자가
 * "없음"과 "고장"을 구분할 수 없게 된다.
 */
@Service
class TestCaseSearchService(
    private val embeddingClient: EmbeddingClient,
    private val searchRepository: TestCaseVectorSearchRepository,
    private val embeddingProperties: TestCaseEmbeddingProperties,
    private val searchProperties: TestCaseSearchProperties,
) {
    private val logger = LoggerFactory.getLogger(TestCaseSearchService::class.java)

    /**
     * [projectId] 안에서 [query]에 의미가 가까운 케이스를 찾는다. [category]로 기능군을 좁힐 수 있다.
     * 결과가 비는 것은 정상이다(백필 미완료·필터로 전부 걸러짐).
     */
    suspend fun search(
        projectId: Long,
        query: String,
        category: String?,
        limit: Int?,
    ): TestCaseSearchResponse {
        val model = embeddingProperties.model
        val resolvedLimit = resolveLimit(limit)

        val rows = searchRepository.searchNearest(
            projectId = projectId,
            queryVector = embedQuery(query, model),
            kind = CONTENT_KIND,
            model = model,
            category = category,
            limit = resolvedLimit,
        )

        logger.info(
            "TestCase 검색: project={}, 검색어 {}자, category={}, limit={}, 결과={}건",
            projectId, query.length, category, resolvedLimit, rows.size
        )
        return TestCaseSearchResponse(query = query, model = model, results = rows.map(::toHit))
    }

    private fun resolveLimit(requested: Int?): Int =
        (requested ?: searchProperties.defaultLimit).coerceIn(1, searchProperties.maxLimit)

    private suspend fun embedQuery(query: String, model: String): String {
        val response = embeddingClient.embed(listOf(query))
        if (response.model != model) {
            throw TestCaseQueryEmbeddingException(
                "Agent가 돌려준 임베딩 모델(${response.model})이 검색 설정(${model})과 다릅니다. " +
                    "artel.testcase.embedding.model을 Agent 설정과 맞추세요."
            )
        }
        val vector = response.vectors.singleOrNull()
        if (vector.isNullOrEmpty()) {
            throw TestCaseQueryEmbeddingException(
                "Agent /embed가 검색어 1건에 대해 벡터를 돌려주지 않았습니다: ${response.vectors.size}건"
            )
        }
        return EmbeddedText(query, vector).toVectorLiteral()
    }

    /** 코사인 거리를 유사도로 뒤집는다. pgvector의 `<=>`는 `1 - cosine_similarity`라 그대로 되돌린다. */
    private fun toHit(row: TestCaseSearchRow) = TestCaseSearchHit(
        id = row.testCaseId.toString(),
        category = row.category,
        title = row.title,
        precondition = row.precondition,
        expected = row.expected,
        verificationStatus = row.verificationStatus,
        score = 1.0 - row.distance,
    )

    private companion object {
        /** 케이스 벡터는 CONTENT 파티션에 있다(백필이 채운 kind). */
        const val CONTENT_KIND = "CONTENT"
    }
}

/**
 * Agent가 검색어를 벡터로 만들어 주지 못했다. `common/error` 계층을 따라 [UpstreamUnavailableException]을
 * 상속한다(knowledge의 동종 예외와 같은 판단 — 후속 WS 라우터가 ERROR 프레임으로 처리).
 */
class TestCaseQueryEmbeddingException(message: String, cause: Throwable? = null) :
    UpstreamUnavailableException(message, code = "test_case_embedding_unavailable", cause = cause)
