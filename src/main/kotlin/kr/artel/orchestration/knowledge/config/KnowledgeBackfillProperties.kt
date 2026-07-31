package kr.artel.orchestration.knowledge.config

import kr.artel.orchestration.common.embedding.EmbeddingBackfillConfig
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * knowledge 벡터 백필 워커 설정(`artel.knowledge.backfill`).
 *
 * 기본값은 Agent 서버(ARTEL-184)의 설정과 맞물려 있다. 그쪽 한계를 넘기면 요청이 422로 떨어진다.
 * 공용 워커가 읽는 부분은 [EmbeddingBackfillConfig]로 노출한다(enabled·interval은 스케줄러 몫).
 */
@ConfigurationProperties(prefix = "artel.knowledge.backfill")
data class KnowledgeBackfillProperties(

    /**
     * 워커를 돌릴지. 테스트와 로컬에서는 꺼 둔다 — Agent가 없으면 매 tick 실패만 쌓인다.
     */
    val enabled: Boolean = false,

    /**
     * 벡터를 만들 모델 slug. `knowledge_embedding.model`의 파티션 키이자, 재색인의 단위다.
     *
     * **Agent 서버의 `embedding_model` 설정과 같아야 한다.** 다르면 워커가 채우는 행과 Agent가
     * 실제로 만든 벡터의 라벨이 어긋나므로, 워커는 응답 slug가 이 값과 다르면 저장하지 않고
     * 실패로 기록한다(자세한 이유는 워커 주석 참조).
     */
    override val model: String = "openai/text-embedding-3-large",

    /**
     * 한 tick에 처리할 knowledge 수의 상한.
     *
     * Agent의 `/knowledge-queries` 배치 상한이 32라 그보다 작아야 한다. 그런데 그 엔드포인트는
     * all-or-nothing이라 배치가 클수록 한 항목의 실패가 끌고 내려가는 동료가 많아진다. 16은
     * 왕복 횟수와 그 위험 사이의 절충이다. (항목당 질문 3개 → `/embed`는 최대 48건으로, 그쪽
     * 상한 128에 여유가 있다.)
     */
    override val batchSize: Int = 16,

    /**
     * 한 항목을 몇 번까지 시도할지. 넘긴 항목은 큐 조회에서 빠지되 행은 남아
     * `last_error`와 함께 조회할 수 있다.
     */
    override val maxAttempts: Int = 5,

    /** tick 간격(밀리초). 겹치지 않도록 fixedDelay로 쓴다. */
    val intervalMillis: Long = 60_000
) : EmbeddingBackfillConfig {
    init {
        require(batchSize > 0) { "artel.knowledge.backfill.batch-size는 1 이상이어야 합니다." }
        require(maxAttempts > 0) { "artel.knowledge.backfill.max-attempts는 1 이상이어야 합니다." }
        require(model.isNotBlank()) { "artel.knowledge.backfill.model은 비어 있을 수 없습니다." }
    }
}
