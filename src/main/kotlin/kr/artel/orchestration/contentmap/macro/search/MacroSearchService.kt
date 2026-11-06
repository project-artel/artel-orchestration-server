package kr.artel.orchestration.contentmap.macro.search

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeoutOrNull
import kr.artel.orchestration.common.embedding.EmbeddedText
import kr.artel.orchestration.common.embedding.agent.EmbeddingClient
import kr.artel.orchestration.contentmap.macro.MacroDefinitionService
import kr.artel.orchestration.contentmap.repository.ScreenMacroRepository
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.scenecontext.service.SceneContextMacroShapes
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** [MacroSearchService.search] 의 결과. 거절은 값으로 돌아온다(`MacroWrite` 와 같은 규율). */
sealed interface MacroSearchOutcome {
    data class Found(val response: MacroSearchResponse) : MacroSearchOutcome
    data class Rejected(val reason: String) : MacroSearchOutcome
}

/**
 * 등록된 macro 를 질의 문장으로 찾는다(ARTEL-986, `MACRO_SEARCH`).
 *
 * 후보 목록이 둘이다. 키워드 목록은 단어별 `ILIKE` 일치 개수 순이고, 벡터 목록은 질의 임베딩과 코사인
 * 거리가 가까운 순이다. 둘을 reciprocal rank fusion 으로 합쳐 `limit` 개를 돌려준다.
 *
 * 방금 등록된 macro 는 벡터가 아직 없을 수 있다(백필이 10초 주기). 키워드 목록이 그 macro 를 찾는다.
 * 질의 임베딩이 실패하거나 시간 안에 못 끝나면 경고를 남기고 키워드 목록만으로 답하며
 * `vectorAvailable=false` 를 싣는다. 검색이 통째로 실패하면 agent 의 tool 이 막히므로 임베딩 실패는
 * 거절 사유가 아니다.
 */
@Service
class MacroSearchService(
    private val definitions: MacroDefinitionService,
    private val searches: MacroSearchRepository,
    private val screenMacros: ScreenMacroRepository,
    private val embeddingClient: EmbeddingClient,
    private val properties: MacroSearchProperties,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(MacroSearchService::class.java)

    suspend fun search(qaTry: QaTryEntity, request: MacroSearchRequest): MacroSearchOutcome {
        val type = MacroSearchFrames.SEARCH
        val query = request.query?.trim()
        if (query.isNullOrEmpty()) return reject(type, "payload.query is required")
        if (query.length > MacroSearchFrames.MAX_QUERY_LENGTH) {
            return reject(type, "payload.query must be at most ${MacroSearchFrames.MAX_QUERY_LENGTH} characters")
        }
        val limit = request.limit ?: properties.defaultLimit
        if (limit !in 1..properties.maxLimit) {
            return reject(type, "payload.limit must be between 1 and ${properties.maxLimit}: $limit")
        }
        val contentMapId = when (val step = definitions.resolveContentMap(qaTry, type)) {
            is MacroDefinitionService.Step.No -> return MacroSearchOutcome.Rejected(step.reason)
            is MacroDefinitionService.Step.Ok -> step.value
        }

        val terms = query.split(WHITESPACE).filter { it.isNotEmpty() }.distinct().take(MacroSearchFrames.MAX_TERMS)
        val keywordRows = searches.searchByKeywords(contentMapId, terms, MacroSearchFrames.CANDIDATES_PER_LIST)
        val vectorRows = vectorCandidates(contentMapId, query)

        val ranked = fuse(keywordRows, vectorRows.orEmpty()).take(limit)
        val response = MacroSearchResponse(
            items = decorate(contentMapId, ranked),
            vectorAvailable = vectorRows != null,
        )
        logger.info(
            "macro 검색: contentMap={} step={} terms={} keyword={} vector={} 반환={} vectorAvailable={}",
            contentMapId, request.step, terms.size, keywordRows.size, vectorRows?.size, response.items.size,
            response.vectorAvailable,
        )
        return MacroSearchOutcome.Found(response)
    }

    /**
     * 질의를 임베딩해 벡터 목록을 읽는다. 어떤 이유로든 못 읽으면 null 이다(키워드만으로 답한다).
     * 취소는 삼키지 않는다.
     */
    private suspend fun vectorCandidates(contentMapId: Long, query: String): List<MacroSearchRow>? = try {
        val embedded = withTimeoutOrNull(properties.queryTimeout.toMillis()) { embedQuery(query) }
        if (embedded == null) {
            logger.warn("macro 검색: 질의 임베딩이 {} 안에 끝나지 않아 키워드 결과만 쓴다", properties.queryTimeout)
            null
        } else {
            searches.searchByVector(
                contentMapId, embedded.toVectorLiteral(), properties.model, MacroSearchFrames.CANDIDATES_PER_LIST,
            )
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        logger.warn("macro 검색: 벡터 목록을 읽지 못해 키워드 결과만 쓴다: {}", error.message)
        null
    }

    /** 백필과 같은 모델이어야 한다. 다른 모델의 벡터로 거리를 재면 순서가 아무 뜻도 없다. */
    private suspend fun embedQuery(query: String): EmbeddedText {
        val response = embeddingClient.embed(listOf(query))
        check(response.model == properties.model) {
            "Agent 가 돌려준 임베딩 모델(${response.model})이 설정(${properties.model})과 다릅니다"
        }
        check(response.vectors.size == 1) { "질의 하나에 벡터 ${response.vectors.size}개가 돌아왔습니다" }
        return EmbeddedText(query, response.vectors.single())
    }

    /** reciprocal rank fusion. 점수는 목록마다 `1 / (k + 순위)` 이고 순위는 1부터다. 동점은 이름 순이다. */
    private fun fuse(keywordRows: List<MacroSearchRow>, vectorRows: List<MacroSearchRow>): List<Fused> {
        val byId = LinkedHashMap<Long, Fused>()
        fun add(rows: List<MacroSearchRow>, source: MacroMatchSource) {
            rows.forEachIndexed { index, row ->
                val contribution = 1.0 / (properties.rrfK + index + 1)
                val current = byId[row.id]
                byId[row.id] = if (current == null) {
                    Fused(row, contribution, listOf(source))
                } else {
                    current.copy(score = current.score + contribution, matchedBy = current.matchedBy + source)
                }
            }
        }
        add(keywordRows, MacroMatchSource.KEYWORD)
        add(vectorRows, MacroMatchSource.VECTOR)
        return byId.values.sortedWith(compareByDescending<Fused> { it.score }.thenBy { it.row.name })
    }

    /** 고른 macro 에 parameter 와 `screen` 을 붙인다. `screen` 은 질의 하나로 읽는다. */
    private suspend fun decorate(contentMapId: Long, ranked: List<Fused>): List<MacroSearchItem> {
        if (ranked.isEmpty()) return emptyList()
        val screensByMacroId = screenMacros.findScreenRowsByMacroIds(contentMapId, ranked.map { it.row.id })
            .toList()
            .groupBy { it.macroId }
        return ranked.map { fused ->
            MacroSearchItem(
                name = fused.row.name,
                parameters = SceneContextMacroShapes.parameters(objectMapper, fused.row.declaredParameters),
                docstring = fused.row.docstring,
                screens = SceneContextMacroShapes.screens(screensByMacroId[fused.row.id].orEmpty()),
                matchedBy = fused.matchedBy,
                score = fused.score,
            )
        }
    }

    private fun reject(type: String, reason: String) = MacroSearchOutcome.Rejected("$type $reason")

    private data class Fused(val row: MacroSearchRow, val score: Double, val matchedBy: List<MacroMatchSource>)

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
