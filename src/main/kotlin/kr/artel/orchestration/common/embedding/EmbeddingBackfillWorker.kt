package kr.artel.orchestration.common.embedding

import kotlinx.coroutines.CancellationException
import kr.artel.orchestration.common.embedding.agent.EmbeddingClient
import org.slf4j.LoggerFactory
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

/**
 * 소유 행에 검색용 벡터를 채우는 백필 워커(도메인 무관). knowledge 워커에서 골격을 추출했다(ARTEL-215).
 *
 * **왜 인입 경로가 아니라 워커인가.** 임베딩을 저장 경로에 동기로 붙이면 쓰기가 LLM 호출만큼 느려지고,
 * 임베딩이 실패하면 본체 저장까지 함께 날아간다. 떼어 두면 본체는 무조건 저장되고 벡터는 나중에 붙는다.
 * 재시도가 곧 다음 tick이고, 모델 교체 재색인도 같은 워커가 처리한다.
 *
 * 한 tick의 흐름:
 * 1. 벡터가 없는 소유 행에 대기 행을 만든다(시딩).
 * 2. 대기 행을 `FOR UPDATE SKIP LOCKED`로 집고 그 자리에서 `attempts`를 올린다.
 * 3. 도메인 [source]가 텍스트를 만든다(orphan/실패 분류 포함). 락 밖에서 일어난다.
 * 4. 텍스트가 나온 행만 `/embed`로 벡터화하고, 대기 행을 벡터 행들로 바꾼다.
 *
 * 스케줄과 분리되어 있다([runOnce]는 그냥 suspend 함수다). 도메인 스케줄러/테스트가 직접 부른다.
 * 도메인마다 [queue]/[source]/[config]/[kind]만 다른 인스턴스를 만들어 쓴다.
 */
class EmbeddingBackfillWorker(
    private val queue: EmbeddingQueue,
    private val embeddingClient: EmbeddingClient,
    private val source: EmbeddingSource,
    private val config: EmbeddingBackfillConfig,
    private val transactionalOperator: TransactionalOperator,
    private val kind: String,
) {
    private val logger = LoggerFactory.getLogger(EmbeddingBackfillWorker::class.java)

    /**
     * 한 tick을 돈다. 예외를 밖으로 내지 않는다 — Agent가 죽어 있어도 워커는 살아 다음 tick에 다시 온다.
     */
    suspend fun runOnce(): BackfillTickResult {
        val model = config.model

        queue.seedPending(kind, model, config.batchSize)
        val claimed = queue.claimPending(kind, model, config.maxAttempts, config.batchSize)
        if (claimed.isEmpty()) return BackfillTickResult.EMPTY

        val sourced = source.sourceTexts(claimed)

        // 대상이 사라졌거나 삭제된 대기 행은 큐에서 버린다. 소싱 실패는 last_error를 남기고 다음
        // tick에 재시도한다(attempts는 claim 때 이미 올랐다).
        sourced.orphanedPendingIds.forEach { queue.deletePending(it) }
        sourced.failures.forEach { (pendingId, reason) -> queue.recordFailure(pendingId, reason) }

        val discarded = sourced.orphanedPendingIds.size
        val actionable = claimed.filter { sourced.textsByOwnerId.containsKey(it.ownerId) }

        if (actionable.isEmpty()) {
            logger.info("임베딩 백필: 처리 대상 없음 (버린 대기 행 {}건, 소싱 실패 {}건)", discarded, sourced.failures.size)
            return BackfillTickResult(claimed = claimed.size, failed = sourced.failures.size, discarded = discarded)
        }

        val processed = process(actionable, sourced.textsByOwnerId, model)
        val result = processed.copy(
            claimed = claimed.size,
            failed = processed.failed + sourced.failures.size,
            discarded = discarded,
        )
        logger.info(
            "임베딩 백필 tick 완료: claim={}, 성공={}, 실패={}, 버림={}, model={}",
            result.claimed, result.succeeded, result.failed, result.discarded, model
        )
        return result
    }

    private suspend fun process(
        rows: List<ClaimedRow>,
        textsByOwnerId: Map<Long, List<String>>,
        model: String,
    ): BackfillTickResult {
        val flattened = rows.flatMap { textsByOwnerId.getValue(it.ownerId) }

        val embedded = try {
            embedAll(flattened, model)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.error("임베딩 백필 임베딩 실패 ({}건): {}", flattened.size, error.message)
            rows.forEach { queue.recordFailure(it.pendingId, describe(error)) }
            return BackfillTickResult(failed = rows.size)
        }

        var succeeded = 0
        var failed = 0
        var cursor = 0
        for (row in rows) {
            val texts = textsByOwnerId.getValue(row.ownerId)
            val slice = embedded.subList(cursor, cursor + texts.size)
            cursor += texts.size
            if (storeVectors(row, model, slice)) succeeded++ else failed++
        }
        return BackfillTickResult(succeeded = succeeded, failed = failed)
    }

    /**
     * 응답 모델 slug가 설정과 다르면 저장하지 않는다.
     *
     * 설정값으로 적으면 벡터의 출처를 속이게 되고, 응답값으로 적으면 다음 tick 시딩이 "이 model로는
     * 행이 없다"고 보아 같은 항목을 다시 집는다(무한 재임베딩). 어느 쪽도 조용히 넘길 수 없어 실패로
     * 남기고 사람이 설정을 맞추게 한다. `attempts` 상한이 그 사이의 낭비를 막는다.
     */
    private suspend fun embedAll(texts: List<String>, model: String): List<EmbeddedText> {
        val response = embeddingClient.embed(texts)
        if (response.model != model) {
            throw IllegalStateException(
                "Agent가 돌려준 임베딩 모델(${response.model})이 설정(${model})과 다릅니다. " +
                    "임베딩 백필 model 설정을 Agent 설정과 맞추세요."
            )
        }
        if (response.vectors.size != texts.size) {
            throw IllegalStateException("임베딩 개수가 요청(${texts.size})과 다릅니다: ${response.vectors.size}")
        }
        return texts.zip(response.vectors) { text, vector -> EmbeddedText(text, vector) }
    }

    private suspend fun storeVectors(row: ClaimedRow, model: String, vectors: List<EmbeddedText>): Boolean = try {
        // 대기 행 삭제와 벡터 삽입은 한 트랜잭션이어야 한다. 쪼개지면 대기 행은 사라졌는데 벡터가
        // 없는 상태가 되고, 시딩이 그 항목을 다시 집지 않아 영영 검색되지 않는다.
        transactionalOperator.executeAndAwait {
            queue.replacePendingWithVectors(row.pendingId, row.ownerId, kind, model, vectors)
        }
        true
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        logger.error("임베딩 백필 저장 실패 ownerId={}: {}", row.ownerId, error.message)
        queue.recordFailure(row.pendingId, describe(error))
        false
    }

    private fun describe(error: Exception): String = "${error::class.simpleName}: ${error.message}"
}

/**
 * 한 tick의 결과. 로그와 테스트가 함께 쓴다.
 *
 * @param claimed 이번 tick이 잡은 대기 행 수
 * @param succeeded 벡터까지 채운 항목 수
 * @param failed 실패로 기록된 항목 수(다음 tick에 재시도된다) — 소싱 실패 + 임베딩/저장 실패
 * @param discarded 대상 소유 행이 없어 버린 대기 행 수
 */
data class BackfillTickResult(
    val claimed: Int = 0,
    val succeeded: Int = 0,
    val failed: Int = 0,
    val discarded: Int = 0,
) {
    companion object {
        val EMPTY = BackfillTickResult()
    }
}
