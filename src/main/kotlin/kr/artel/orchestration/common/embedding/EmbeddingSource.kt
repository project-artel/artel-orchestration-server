package kr.artel.orchestration.common.embedding

/**
 * 백필 워커가 claim한 대기 행들을 도메인 지식으로 "임베딩할 소스 텍스트"로 바꾼다.
 *
 * 워커 골격(seed→claim→embed→store)은 [EmbeddingBackfillWorker]가 공용으로 갖고, 도메인마다 다른
 * 부분만 여기서 구현한다: 소유 행을 로드하고, 텍스트를 만들고(knowledge는 /knowledge-queries로
 * 질문 N개, testcase는 본문 합성 1개), 각 대기 행을 성공/사라짐/실패로 분류한다.
 */
interface EmbeddingSource {
    suspend fun sourceTexts(claimed: List<ClaimedRow>): EmbeddingSourceResult
}

/**
 * 소싱 결과. claim된 각 행은 셋 중 하나로 분류된다.
 *
 * @property textsByOwnerId 소유 행 id → 임베딩할 텍스트들(순서 유지). 여기 있는 행만 임베딩·저장된다.
 * @property orphanedPendingIds 소유 행이 사라졌거나 삭제됨 → 대기 행을 큐에서 버린다(재시도 안 함).
 * @property failures 소싱 실패(pendingId → 사유) → last_error에 남기고 다음 tick에 재시도한다.
 */
data class EmbeddingSourceResult(
    val textsByOwnerId: Map<Long, List<String>>,
    val orphanedPendingIds: Set<Long> = emptySet(),
    val failures: Map<Long, String> = emptyMap(),
)
