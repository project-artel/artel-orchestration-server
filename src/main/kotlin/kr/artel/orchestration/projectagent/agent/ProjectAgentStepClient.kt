package kr.artel.orchestration.projectagent.agent

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.netty.channel.ChannelOption
import kotlinx.coroutines.reactive.awaitSingle
import kr.artel.orchestration.projectagent.config.ProjectAgentProperties
import kr.artel.orchestration.qa.service.QaSessionLlmKey
import kr.artel.orchestration.settings.config.AgentServerProperties
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient

/**
 * agent-server 의 `POST {baseUrl}/project-agent/step` 을 부른다(ARTEL-1127). 한 번 부르면 model 을 한 번 돌리고,
 * agent-server 는 아무것도 저장하지 않는다. 실패는 예외로 던진다 — 대화를 ERROR 로 돌리는 것은 부르는 쪽이다.
 */
@Component
class ProjectAgentStepClient(
    agentServer: AgentServerProperties,
    private val properties: ProjectAgentProperties,
) {
    private val stepUrl = "${agentServer.baseUrl}/project-agent/step"

    private val webClient: WebClient = WebClient.builder()
        .clientConnector(
            ReactorClientHttpConnector(
                HttpClient.create()
                    .responseTimeout(properties.stepTimeout)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
            )
        )
        .build()

    suspend fun step(request: ProjectAgentStepRequest): ProjectAgentStepResponse =
        webClient.post()
            .uri(stepUrl)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .retrieve()
            .bodyToMono(ProjectAgentStepResponse::class.java)
            // responseTimeout 은 응답 한 덩어리를 기다리는 상한이라, 전체 상한을 따로 건다.
            .timeout(properties.stepTimeout)
            .awaitSingle()
}

/**
 * agent-server 로 보내는 요청(snake_case). `model` 이 null 이면 agent-server 가 기본 model 을 쓴다.
 *
 * `llm_key` 는 사용자 key 로 도는 step 에만 실린다. 원문이 담기므로 이 본문을 로그로 남기지 않는다.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ProjectAgentStepRequest(
    val conversationId: Long,
    val projectId: Long,
    val model: String?,
    val llmKey: QaSessionLlmKey? = null,
    val locale: String,
    val toolsEnabled: Boolean,
    val pageCatalog: JsonNode,
    val messages: List<StepMessage>,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class StepMessage(
    val role: String,
    val content: String,
    val toolCalls: List<StepToolCall>? = null,
    val toolCallId: String? = null,
)

data class StepToolCall(
    val id: String,
    val name: String,
    val args: JsonNode = JsonNodeFactory.instance.objectNode(),
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ProjectAgentStepResponse(
    val content: String? = null,
    val toolCalls: List<StepToolCall> = emptyList(),
)
