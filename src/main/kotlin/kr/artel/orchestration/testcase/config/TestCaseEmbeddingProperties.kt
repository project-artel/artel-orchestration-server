package kr.artel.orchestration.testcase.config

import kr.artel.orchestration.common.embedding.EmbeddingBackfillConfig
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * test_case 벡터 백필 설정(`artel.testcase.embedding`).
 *
 * knowledge 백필과 달리 **버스트에 맞춘 기본값**이다. 케이스는 SDK 등록 시 CSV로 한 번에 수백~수만
 * 건이 들어올 수 있어(ARTEL-206), knowledge의 트리클(16/60s)로는 초기 드레인이 수 시간 걸린다.
 * CONTENT 1벡터/케이스라 `/embed` 한 번에 batchSize건이 실리고, Agent `/embed` 상한(기본 128)보다
 * 작게 유지한다.
 *
 * 공용 워커가 읽는 부분은 [EmbeddingBackfillConfig]로 노출한다(enabled·interval은 스케줄러 몫).
 */
@ConfigurationProperties(prefix = "artel.testcase.embedding")
data class TestCaseEmbeddingProperties(

    /** 워커를 돌릴지. 테스트·로컬은 꺼 둔다(Agent /embed가 없으면 매 tick 실패만 쌓인다). */
    val enabled: Boolean = false,

    /** 벡터 모델 slug. knowledge와 동일 모델로 통일한다(다국어 대응, Agent 설정과 일치해야 함). */
    override val model: String = "openai/text-embedding-3-large",

    /** 한 tick 처리 상한. CONTENT 1벡터/케이스 → `/embed` 최대 batchSize건. Agent 상한 128 미만으로 둔다. */
    override val batchSize: Int = 64,

    /** 한 항목 시도 상한. 넘긴 항목은 큐 조회에서 빠지되 last_error와 함께 남는다. */
    override val maxAttempts: Int = 5,

    /** tick 간격(밀리초). 초기 대량 적재를 빨리 소진하려 knowledge(60s)보다 짧게 둔다. */
    val intervalMillis: Long = 10_000,
) : EmbeddingBackfillConfig {
    init {
        require(batchSize > 0) { "artel.testcase.embedding.batch-size는 1 이상이어야 합니다." }
        require(maxAttempts > 0) { "artel.testcase.embedding.max-attempts는 1 이상이어야 합니다." }
        require(model.isNotBlank()) { "artel.testcase.embedding.model은 비어 있을 수 없습니다." }
    }
}
