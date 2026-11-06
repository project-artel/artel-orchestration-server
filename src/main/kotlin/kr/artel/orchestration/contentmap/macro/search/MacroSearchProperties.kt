package kr.artel.orchestration.contentmap.macro.search

import kr.artel.orchestration.common.embedding.EmbeddingBackfillConfig
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * macro 검색 설정(`artel.macro.search`, ARTEL-986). 벡터 백필과 `MACRO_SEARCH` 처리가 한 묶음으로 읽는다.
 *
 * 백필 워커가 읽는 부분은 [EmbeddingBackfillConfig] 로 노출한다. [enabled] 와 [fixedDelay] 는 스케줄러
 * 몫이다.
 */
@ConfigurationProperties(prefix = "artel.macro.search")
data class MacroSearchProperties(

    /** 백필 스케줄러를 띄울지. 꺼 둬도 `MACRO_SEARCH` 는 키워드 결과만으로 답한다. */
    val enabled: Boolean = false,

    /** 벡터 모델 slug. knowledge 백필과 같은 모델이어야 하고 Agent 설정과도 같아야 한다. */
    override val model: String = "openai/text-embedding-3-large",

    /** 한 tick 에 임베딩할 macro 수 상한. `/embed` 한 번에 이만큼 실린다. Agent 상한 128 미만이다. */
    override val batchSize: Int = 64,

    /** 한 항목 시도 상한. 넘긴 항목은 큐 조회에서 빠지되 last_error 와 함께 남는다. */
    override val maxAttempts: Int = 5,

    /** 이전 tick 이 끝난 뒤 다음 tick 까지의 간격. */
    val fixedDelay: Duration = Duration.ofSeconds(10),

    /** 요청에 `limit` 이 없을 때 돌려줄 개수. */
    val defaultLimit: Int = 5,

    /** `limit` 상한. 넘는 요청은 잘라 쓰지 않고 거절한다. */
    val maxLimit: Int = 10,

    /** reciprocal rank fusion 의 k. 점수는 두 목록의 `1 / (k + 순위)` 합이다. */
    val rrfK: Int = 60,

    /** 질의 임베딩을 기다리는 시간. 넘기면 키워드 결과만으로 답한다. */
    val queryTimeout: Duration = Duration.ofSeconds(5),
) : EmbeddingBackfillConfig {
    init {
        require(model.isNotBlank()) { "artel.macro.search.model 은 비어 있을 수 없습니다." }
        require(batchSize > 0) { "artel.macro.search.batch-size 는 1 이상이어야 합니다." }
        require(maxAttempts > 0) { "artel.macro.search.max-attempts 는 1 이상이어야 합니다." }
        require(maxLimit >= 1) { "artel.macro.search.max-limit 은 1 이상이어야 합니다." }
        require(defaultLimit in 1..maxLimit) {
            "artel.macro.search.default-limit 은 1 이상 max-limit 이하여야 합니다."
        }
        require(rrfK > 0) { "artel.macro.search.rrf-k 는 1 이상이어야 합니다." }
        require(!queryTimeout.isNegative && !queryTimeout.isZero) {
            "artel.macro.search.query-timeout 은 0 보다 커야 합니다."
        }
    }
}

/**
 * [MacroSearchProperties] 바인딩. 스케줄러(enabled 조건부)와 분리해 항상 활성이라, 백필을 꺼도 검색
 * 서비스와 테스트가 프로퍼티 빈을 주입받는다.
 */
@Configuration
@EnableConfigurationProperties(MacroSearchProperties::class)
class MacroSearchConfig
