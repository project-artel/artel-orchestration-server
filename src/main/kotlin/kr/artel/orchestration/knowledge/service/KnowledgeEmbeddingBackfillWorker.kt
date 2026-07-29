package kr.artel.orchestration.knowledge.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.knowledge.agent.KnowledgeEmbeddingAgent
import kr.artel.orchestration.knowledge.agent.KnowledgeQueryItem
import kr.artel.orchestration.knowledge.config.KnowledgeBackfillProperties
import kr.artel.orchestration.knowledge.entity.KnowledgeEntity
import kr.artel.orchestration.knowledge.repository.ClaimedRow
import kr.artel.orchestration.knowledge.repository.EmbeddedText
import kr.artel.orchestration.knowledge.repository.KnowledgeEmbeddingRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

/**
 * knowledge 항목에 검색용 벡터를 채우는 백필 워커.
 *
 * **왜 인입 경로가 아니라 워커인가.** 임베딩을 `KnowledgeService.store`에 동기로 붙이면 문서 업로드가
 * LLM 호출만큼 느려지고, 임베딩이 실패하면 knowledge 저장까지 함께 날아간다. 떼어 두면 knowledge는
 * 무조건 저장되고 벡터는 나중에 붙는다. 재시도가 곧 다음 tick이고, 모델 교체 재색인도 같은 워커가
 * 처리한다.
 *
 * 한 tick의 흐름:
 * 1. 벡터가 없는 knowledge에 대기 행을 만든다(시딩).
 * 2. 대기 행을 `FOR UPDATE SKIP LOCKED`로 집고 그 자리에서 `attempts`를 올린다.
 * 3. 락 밖에서 Agent를 부른다: `/knowledge-queries` → `/embed`.
 * 4. 성공한 항목은 대기 행을 벡터 행들로 바꾸고, 실패한 항목은 `last_error`를 남긴다.
 *
 * 스케줄과 분리되어 있다([runOnce]는 그냥 suspend 함수다). 테스트가 스케줄러 없이 직접 부른다.
 */
@Service
class KnowledgeEmbeddingBackfillWorker(
    private val knowledgeRepository: KnowledgeRepository,
    private val embeddingRepository: KnowledgeEmbeddingRepository,
    private val agent: KnowledgeEmbeddingAgent,
    private val properties: KnowledgeBackfillProperties,
    private val transactionalOperator: TransactionalOperator
) {
    private val logger = LoggerFactory.getLogger(KnowledgeEmbeddingBackfillWorker::class.java)

    /**
     * 한 tick을 돈다. 예외를 밖으로 내지 않는다 — Agent가 죽어 있어도 워커는 살아 다음 tick에 다시 온다.
     */
    suspend fun runOnce(): BackfillTickResult {
        val kind = QUERY_KIND
        val model = properties.model

        embeddingRepository.seedPending(kind, model, properties.batchSize)
        val claimed = embeddingRepository.claimPending(kind, model, properties.maxAttempts, properties.batchSize)
        if (claimed.isEmpty()) return BackfillTickResult.EMPTY

        val knowledgeById = loadLiveKnowledge(claimed)
        val (actionable, orphaned) = claimed.partition { knowledgeById.containsKey(it.knowledgeId) }

        // 대상이 사라졌거나 소프트삭제된 대기 행은 큐에서 버린다. 남겨 두면 상한에 닿을 때까지
        // 매 tick 자리를 차지하면서 아무 일도 하지 않는다.
        orphaned.forEach { embeddingRepository.deletePending(it.pendingId) }

        if (actionable.isEmpty()) {
            logger.info("knowledge 백필: 처리 대상 없음 (버린 대기 행 {}건)", orphaned.size)
            return BackfillTickResult(claimed = claimed.size, discarded = orphaned.size)
        }

        val result = process(actionable, knowledgeById, kind, model)
            .copy(claimed = claimed.size, discarded = orphaned.size)

        // 처리 건수와 실패 건수를 남긴다. 워커가 조용히 멈춘 것과 할 일이 없는 것을 구분할 수 있어야 한다.
        logger.info(
            "knowledge 백필 tick 완료: claim={}, 성공={}, 실패={}, 버림={}, model={}",
            result.claimed, result.succeeded, result.failed, result.discarded, model
        )
        return result
    }

    private suspend fun process(
        rows: List<ClaimedRow>,
        knowledgeById: Map<Long, KnowledgeEntity>,
        kind: String,
        model: String
    ): BackfillTickResult {
        val queriesByKnowledgeId = generateQueriesResiliently(rows, knowledgeById)
        if (queriesByKnowledgeId.isEmpty()) {
            return BackfillTickResult(failed = rows.size)
        }

        // 질문을 못 받은 항목은 이미 실패로 기록됐다. 남은 것만 임베딩한다.
        val pending = rows.filter { queriesByKnowledgeId.containsKey(it.knowledgeId) }
        val flattened = pending.flatMap { row -> queriesByKnowledgeId.getValue(row.knowledgeId) }

        val embedded = try {
            embedAll(flattened, model)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.error("knowledge 백필 임베딩 실패 ({}건): {}", flattened.size, error.message)
            pending.forEach { embeddingRepository.recordFailure(it.pendingId, describe(error)) }
            return BackfillTickResult(failed = rows.size)
        }

        var succeeded = 0
        var failed = rows.size - pending.size
        var cursor = 0
        for (row in pending) {
            val queries = queriesByKnowledgeId.getValue(row.knowledgeId)
            val slice = embedded.subList(cursor, cursor + queries.size)
            cursor += queries.size
            if (storeVectors(row, kind, model, slice)) succeeded++ else failed++
        }
        return BackfillTickResult(succeeded = succeeded, failed = failed)
    }

    /**
     * 검색쿼리를 만든다. `/knowledge-queries`는 **all-or-nothing**이라 배치 중 한 항목만 실패해도
     * 요청 전체가 422로 떨어진다. 그대로 두면 멀쩡한 항목들까지 같은 tick에서 함께 실패한다.
     *
     * 그래서 배치가 깨지면 항목별로 한 번씩 다시 부른다. 추가 호출은 실패한 tick에서만 일어나고,
     * 그 결과 문제 항목만 홀로 `attempts`를 쌓다 상한에 걸려 큐에서 빠진다.
     */
    private suspend fun generateQueriesResiliently(
        rows: List<ClaimedRow>,
        knowledgeById: Map<Long, KnowledgeEntity>
    ): Map<Long, List<String>> {
        val items = rows.map { toQueryItem(knowledgeById.getValue(it.knowledgeId)) }
        try {
            return toQueryMap(agent.generateQueries(items))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (rows.size == 1) {
                logger.warn("knowledge 백필 검색쿼리 생성 실패 knowledgeId={}: {}", rows[0].knowledgeId, error.message)
                embeddingRepository.recordFailure(rows[0].pendingId, describe(error))
                return emptyMap()
            }
            logger.warn("knowledge 백필 검색쿼리 배치 실패({}건) — 항목별로 재시도한다: {}", rows.size, error.message)
        }

        val collected = mutableMapOf<Long, List<String>>()
        for (row in rows) {
            val item = toQueryItem(knowledgeById.getValue(row.knowledgeId))
            try {
                collected += toQueryMap(agent.generateQueries(listOf(item)))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.warn("knowledge 백필 검색쿼리 생성 실패 knowledgeId={}: {}", row.knowledgeId, error.message)
                embeddingRepository.recordFailure(row.pendingId, describe(error))
            }
        }
        return collected
    }

    /**
     * 응답 모델 slug가 설정과 다르면 저장하지 않는다.
     *
     * 그대로 설정값으로 적으면 벡터의 출처를 속이게 되고, 응답값으로 적으면 다음 tick 시딩이
     * "이 model로는 행이 없다"고 보아 같은 항목을 다시 집는다 — 무한 재임베딩이다. 어느 쪽도
     * 조용히 넘길 수 없어 실패로 남기고 사람이 설정을 맞추게 한다. `attempts` 상한이 그 사이의
     * 낭비를 막는다.
     */
    private suspend fun embedAll(texts: List<String>, model: String): List<EmbeddedText> {
        val response = agent.embed(texts)
        if (response.model != model) {
            throw IllegalStateException(
                "Agent가 돌려준 임베딩 모델(${response.model})이 설정(${model})과 다릅니다. " +
                    "artel.knowledge.backfill.model을 Agent 설정과 맞추세요."
            )
        }
        if (response.vectors.size != texts.size) {
            throw IllegalStateException("임베딩 개수가 요청(${texts.size})과 다릅니다: ${response.vectors.size}")
        }
        return texts.zip(response.vectors) { text, vector -> EmbeddedText(text, vector) }
    }

    private suspend fun storeVectors(
        row: ClaimedRow,
        kind: String,
        model: String,
        vectors: List<EmbeddedText>
    ): Boolean = try {
        // 대기 행 삭제와 벡터 삽입은 한 트랜잭션이어야 한다. 쪼개지면 대기 행은 사라졌는데 벡터가
        // 없는 상태가 되고, 시딩이 그 항목을 다시 집지 않아 영영 검색되지 않는다.
        transactionalOperator.executeAndAwait {
            embeddingRepository.replacePendingWithVectors(row.pendingId, row.knowledgeId, kind, model, vectors)
        }
        true
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        logger.error("knowledge 백필 저장 실패 knowledgeId={}: {}", row.knowledgeId, error.message)
        embeddingRepository.recordFailure(row.pendingId, describe(error))
        false
    }

    private suspend fun loadLiveKnowledge(rows: List<ClaimedRow>): Map<Long, KnowledgeEntity> =
        knowledgeRepository.findAllById(rows.map { it.knowledgeId })
            .toList()
            .filter { it.deletedAt == null }
            .associateBy { requireNotNull(it.id) }

    private fun toQueryItem(entity: KnowledgeEntity) = KnowledgeQueryItem(
        id = entity.id.toString(),
        summary = entity.summary,
        description = entity.description
    )

    /** Agent는 요청의 id를 그대로 돌려준다. 리스트 순서가 아니라 그 id로 맞춘다. */
    private fun toQueryMap(response: kr.artel.orchestration.knowledge.agent.KnowledgeQueriesResponse): Map<Long, List<String>> =
        response.results
            .mapNotNull { result ->
                val id = result.id.toLongOrNull() ?: return@mapNotNull null
                val queries = result.queries.map { it.trim() }.filter { it.isNotEmpty() }
                if (queries.isEmpty()) null else id to queries
            }
            .toMap()

    private fun describe(error: Exception): String = "${error::class.simpleName}: ${error.message}"

    private companion object {
        /** 이번 스프린트는 QUERY만 채운다. CONTENT는 스키마에 자리만 있다. */
        const val QUERY_KIND = "QUERY"
    }
}

/**
 * 한 tick의 결과. 로그와 테스트가 함께 쓴다.
 *
 * @param claimed 이번 tick이 잡은 대기 행 수
 * @param succeeded 벡터까지 채운 항목 수
 * @param failed 실패로 기록된 항목 수(다음 tick에 재시도된다)
 * @param discarded 대상 knowledge가 없어 버린 대기 행 수
 */
data class BackfillTickResult(
    val claimed: Int = 0,
    val succeeded: Int = 0,
    val failed: Int = 0,
    val discarded: Int = 0
) {
    companion object {
        val EMPTY = BackfillTickResult()
    }
}
