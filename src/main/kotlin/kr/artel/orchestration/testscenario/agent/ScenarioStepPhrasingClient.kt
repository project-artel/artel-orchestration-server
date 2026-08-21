package kr.artel.orchestration.testscenario.agent

import com.fasterxml.jackson.annotation.JsonProperty
import io.netty.channel.ChannelOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactive.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration

/**
 * 사용자가 알려준 통과 방법을 **스텝 문장으로 다듬는다**(`POST {agent}/scenario-steps/phrase`, ARTEL-487).
 *
 * 자리는 코드가 정하고 문장만 모델이 만진다. 사용자가 적은 말은 그대로 스텝이 되기에는 앞뒤와
 * 결이 다르고("대화 끝나면 스페이스 한번 더"), 한 문장에 동작이 둘 들어 있기도 하다. 반대로
 * 자리까지 모델에게 맡기면 답이 엉뚱한 곳에 들어간다 — 그것이 이 경로가 생긴 이유다.
 *
 * **실패는 답을 잃는 이유가 되지 않는다.** 못 다듬으면 null 을 돌려주고 부르는 쪽이 사용자가 적은
 * 말을 그대로 넣는다. 어색한 문장이 남는 것과 사용자가 알려준 것이 사라지는 것은 무게가 다르다.
 */
@Component
class ScenarioStepPhrasingClient(
    @Value("\${artel.agent.base-url}") private val agentBaseUrl: String,
    @Value("\${artel.agent.model:}") private val configuredModel: String,
    @Value("\${artel.agent.step-phrasing-timeout:PT20S}") private val responseTimeout: Duration,
) {
    private val logger = LoggerFactory.getLogger(ScenarioStepPhrasingClient::class.java)

    private val webClient: WebClient = WebClient.builder()
        .clientConnector(
            ReactorClientHttpConnector(
                HttpClient.create()
                    .responseTimeout(responseTimeout)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
            )
        )
        .build()

    /**
     * @return 다듬은 스텝들. **빈 목록은 실패가 아니다** — 적은 말이 통과 방법이 아니라는 답이다
     *   ("잘 모르겠는데"). null 이면 못 물어봤다는 뜻이라 부르는 쪽이 원문을 그대로 쓴다.
     */
    suspend fun phrase(
        said: String,
        blockedBy: String,
        before: String,
        after: String,
        locale: String,
    ): List<PhrasedStep>? {
        if (said.isBlank()) return emptyList()
        return try {
            webClient.post()
                .uri("$agentBaseUrl/scenario-steps/phrase")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                    PhraseRequest(
                        said = said,
                        blockedBy = blockedBy,
                        before = before,
                        after = after,
                        locale = locale,
                        // 세션 오픈과 같은 규칙 — 명시적 override 가 있을 때만 보낸다.
                        model = configuredModel.takeIf { it.isNotBlank() },
                    )
                )
                .retrieve()
                .bodyToMono(PhraseResponse::class.java)
                .awaitSingle()
                .steps
                .filter { it.action.isNotBlank() }
                .map { it.copy(input = it.input?.takeIf(::replayable)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("스텝 문장 다듬기 실패 — 사용자가 적은 말을 그대로 쓴다: {}", e.message)
            null
        }
    }

    /**
     * `input` 은 실행하는 쪽이 그대로 읽는 값이라 두 모양뿐이다(`key:Space`·`click:Canvas/Start`).
     * 그 밖의 문자열은 **버린다** — 모양이 다른 값을 넣어 두면 실행이 엉뚱한 조작을 하거나 조용히
     * 무시하는데, 어느 쪽도 스텝 문장이 이미 말하고 있는 것보다 낫지 않다.
     */
    private fun replayable(input: String): Boolean =
        input.startsWith("key:") || input.startsWith("click:")
}

data class PhraseRequest(
    val said: String,
    @JsonProperty("blocked_by") val blockedBy: String,
    val before: String,
    val after: String,
    val locale: String,
    val model: String?,
)

data class PhraseResponse(val steps: List<PhrasedStep> = emptyList())

/** 다듬은 스텝 한 줄. `input` 은 누르는 키·컨트롤이며 없을 수 있다. */
data class PhrasedStep(val action: String = "", val input: String? = null)
