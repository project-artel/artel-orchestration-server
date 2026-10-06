package kr.artel.orchestration.settings.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `artel-agent-server` 의 `GET {base-url}/models/required` 를 부르는 설정. key 확인이 닿아야 하는
 * model 목록을 거기서 받는다.
 *
 * [baseUrl] 은 `artel.agent.base-url` 이고 `/internal` 까지 포함한다(`http://agent-server:8000/internal`).
 */
@ConfigurationProperties("artel.agent")
data class AgentModelsProperties(
    val baseUrl: String = "http://localhost:8000",
    /** 호출 한 번의 연결과 응답 상한. 관리 화면의 버튼이 agent 때문에 오래 멈추지 않게 짧게 둔다. */
    val requiredModelsTimeout: Duration = Duration.ofSeconds(3),
    /** 받은 목록을 들고 있는 시간. 버튼을 누를 때마다 agent 를 부르지 않기 위한 것이다. */
    val requiredModelsTtl: Duration = Duration.ofMinutes(5)
) {
    init {
        require(!requiredModelsTimeout.isNegative && !requiredModelsTimeout.isZero) {
            "artel.agent.required-models-timeout 은 0 보다 커야 합니다."
        }
        require(!requiredModelsTtl.isNegative) { "artel.agent.required-models-ttl 은 0 이상이어야 합니다." }
    }
}
