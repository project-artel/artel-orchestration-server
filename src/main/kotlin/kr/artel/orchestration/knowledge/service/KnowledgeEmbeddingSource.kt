package kr.artel.orchestration.knowledge.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.common.embedding.ClaimedRow
import kr.artel.orchestration.common.embedding.EmbeddingSource
import kr.artel.orchestration.common.embedding.EmbeddingSourceResult
import kr.artel.orchestration.knowledge.agent.KnowledgeEmbeddingAgent
import kr.artel.orchestration.knowledge.agent.KnowledgeQueriesResponse
import kr.artel.orchestration.knowledge.agent.KnowledgeQueryItem
import kr.artel.orchestration.knowledge.entity.KnowledgeEntity
import kr.artel.orchestration.knowledge.repository.KnowledgeRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * knowledge 백필의 도메인 소싱. 골격(seed/claim/embed/store)은 공용
 * [kr.artel.orchestration.common.embedding.EmbeddingBackfillWorker]가 담당하고, 여기서는 도메인 특정
 * 부분만 한다: 살아있는 knowledge를 로드하고 `/knowledge-queries`로 항목당 검색쿼리 여러 개를 만든다.
 */
@Component
class KnowledgeEmbeddingSource(
    private val knowledgeRepository: KnowledgeRepository,
    private val agent: KnowledgeEmbeddingAgent,
) : EmbeddingSource {

    private val logger = LoggerFactory.getLogger(KnowledgeEmbeddingSource::class.java)

    override suspend fun sourceTexts(claimed: List<ClaimedRow>): EmbeddingSourceResult {
        val live = loadLiveKnowledge(claimed)

        // 대상이 사라졌거나 소프트삭제된 대기 행은 orphan으로 넘긴다(워커가 큐에서 버린다).
        val orphanedPendingIds = claimed.filter { it.ownerId !in live }.map { it.pendingId }.toSet()
        val actionable = claimed.filter { it.ownerId in live }
        if (actionable.isEmpty()) {
            return EmbeddingSourceResult(emptyMap(), orphanedPendingIds, emptyMap())
        }

        val (texts, failures) = generateQueries(actionable, live)
        return EmbeddingSourceResult(texts, orphanedPendingIds, failures)
    }

    /**
     * 검색쿼리를 만든다. `/knowledge-queries`는 **all-or-nothing**이라 배치 중 한 항목만 실패해도 요청
     * 전체가 422로 떨어진다. 그대로 두면 멀쩡한 항목들까지 같은 tick에서 함께 실패한다. 그래서 배치가
     * 깨지면 항목별로 한 번씩 다시 부른다 — 추가 호출은 실패한 tick에서만 일어나고, 그 결과 문제 항목만
     * 홀로 `attempts`를 쌓다 상한에 걸려 큐에서 빠진다.
     *
     * @return (ownerId → 쿼리들, pendingId → 실패 사유)
     */
    private suspend fun generateQueries(
        actionable: List<ClaimedRow>,
        live: Map<Long, KnowledgeEntity>,
    ): Pair<Map<Long, List<String>>, Map<Long, String>> {
        val items = actionable.map { toQueryItem(live.getValue(it.ownerId)) }
        // 배치는 프로젝트를 가로질러 묶일 수 있다. 한 프로젝트로 떨어질 때만 id를 실어 Agent가 LLM
        // 사용량을 그 프로젝트에 귀속시키게 한다(ARTEL-233) — 섞였으면 null이라 지출만 남는다.
        val projectId = actionable.map { live.getValue(it.ownerId).projectId }.distinct().singleOrNull()
        try {
            return toQueryMap(agent.generateQueries(items, projectId)) to emptyMap()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (actionable.size == 1) {
                logger.warn("knowledge 백필 검색쿼리 생성 실패 knowledgeId={}: {}", actionable[0].ownerId, error.message)
                return emptyMap<Long, List<String>>() to mapOf(actionable[0].pendingId to describe(error))
            }
            logger.warn("knowledge 백필 검색쿼리 배치 실패({}건) — 항목별로 재시도한다: {}", actionable.size, error.message)
        }

        val collected = mutableMapOf<Long, List<String>>()
        val failures = mutableMapOf<Long, String>()
        for (row in actionable) {
            val entity = live.getValue(row.ownerId)
            val item = toQueryItem(entity)
            try {
                collected += toQueryMap(agent.generateQueries(listOf(item), entity.projectId))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.warn("knowledge 백필 검색쿼리 생성 실패 knowledgeId={}: {}", row.ownerId, error.message)
                failures[row.pendingId] = describe(error)
            }
        }
        return collected to failures
    }

    private suspend fun loadLiveKnowledge(claimed: List<ClaimedRow>): Map<Long, KnowledgeEntity> =
        knowledgeRepository.findAllById(claimed.map { it.ownerId })
            .toList()
            .filter { it.deletedAt == null }
            .associateBy { requireNotNull(it.id) }

    private fun toQueryItem(entity: KnowledgeEntity) = KnowledgeQueryItem(
        id = entity.id.toString(),
        summary = entity.summary,
        description = entity.description
    )

    /** Agent는 요청의 id를 그대로 돌려준다. 리스트 순서가 아니라 그 id로 맞춘다. */
    private fun toQueryMap(response: KnowledgeQueriesResponse): Map<Long, List<String>> =
        response.results
            .mapNotNull { result ->
                val id = result.id.toLongOrNull() ?: return@mapNotNull null
                val queries = result.queries.map { it.trim() }.filter { it.isNotEmpty() }
                if (queries.isEmpty()) null else id to queries
            }
            .toMap()

    private fun describe(error: Exception): String = "${error::class.simpleName}: ${error.message}"
}
