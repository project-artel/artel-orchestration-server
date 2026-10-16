package kr.artel.orchestration.developermacro.agent

import com.fasterxml.jackson.databind.JsonNode
import io.netty.channel.ChannelOption
import kotlinx.coroutines.reactor.awaitSingle
import kr.artel.orchestration.common.error.UpstreamUnavailableException
import kr.artel.orchestration.settings.config.AgentServerProperties
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import reactor.netty.http.client.HttpClient

/**
 * agent-server 의 macro 문법 글과 macro 검사를 부른다(ARTEL-1087, ARTEL-1088).
 *
 * 주소는 `artel.agent.base-url` 이고 그 값이 `/internal` 까지 포함한다(`AgentCacheInvalidator` 와 같다). 그래서
 * 경로는 `/macros/grammar`, `/macros/check` 만 붙인다.
 *
 * 실패는 모두 503 `agent_unavailable` 이다. agent-server 가 4xx 로 답한 경우도 같다 — 요청 모양은 이 서버가
 * 만들므로 그것이 거절되면 사용자가 고칠 것이 아니라 두 서버의 계약이 어긋난 것이다. 사용자가 고칠 것은
 * `ok: false` 의 `rejection` 으로만 온다.
 */
@Component
class AgentMacroClient(
    properties: AgentServerProperties,
    webClientBuilder: WebClient.Builder,
) {
    private val webClient: WebClient = webClientBuilder.clone()
        .baseUrl(properties.baseUrl.trimEnd('/'))
        .clientConnector(
            ReactorClientHttpConnector(
                HttpClient.create()
                    .responseTimeout(properties.macroTimeout)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.macroTimeout.toMillis().toInt())
            )
        )
        .build()

    /** 문법 글. agent-server 가 준 본문과 content type 을 그대로 돌려준다. */
    suspend fun grammar(): MacroGrammarDocument = callAgent("macro grammar") {
        val response = webClient.get().uri("/macros/grammar")
            .retrieve().toEntity(String::class.java).awaitSingle()
        MacroGrammarDocument(
            body = response.body.orEmpty(),
            contentType = response.headers.getFirst(HttpHeaders.CONTENT_TYPE),
        )
    }

    /** macro 하나를 검사한다. [imports] 는 같은 프로젝트의 다른 developer macro 원문이다. */
    suspend fun check(name: String, source: String, imports: Map<String, String>): MacroCheckOutcome {
        val response = callAgent("macro check") {
            webClient.post().uri("/macros/check")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(AgentMacroCheckRequest(name = name, source = source, imports = imports))
                .retrieve().awaitBody<AgentMacroCheckResponse>()
        }
        return outcomeOf(response)
    }

    private fun outcomeOf(response: AgentMacroCheckResponse): MacroCheckOutcome {
        if (!response.ok) {
            val rejection = response.rejection ?: throw contractBroken("rejection 없는 ok=false")
            return MacroCheckOutcome.Rejected(rejection.line, rejection.reason)
        }
        val definition = response.definition?.takeUnless { it.isNull } ?: throw contractBroken("definition 없는 ok=true")
        val docstring = response.docstring?.takeUnless { it.isBlank() } ?: throw contractBroken("docstring 없는 ok=true")
        return MacroCheckOutcome.Passed(definition, parameterNamesOf(response.parameters), docstring)
    }

    /** 원소가 이름 문자열이면 그대로, `{name, ...}` 객체면 `name` 을 읽는다. 그 밖의 모양은 계약 위반이다. */
    private fun parameterNamesOf(parameters: JsonNode?): List<String> {
        if (parameters == null || parameters.isNull) return emptyList()
        if (!parameters.isArray) throw contractBroken("parameters 가 배열이 아님")
        return parameters.map { parameter ->
            when {
                parameter.isTextual -> parameter.asText()
                parameter.isObject && parameter.path("name").isTextual -> parameter.path("name").asText()
                else -> throw contractBroken("parameters 원소의 모양을 읽을 수 없음")
            }
        }
    }

    private suspend fun <T> callAgent(what: String, call: suspend () -> T): T =
        try {
            call()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: UpstreamUnavailableException) {
            throw error
        } catch (error: Exception) {
            throw AgentMacroUnavailableException("agent-server $what failed", error)
        }

    private fun contractBroken(detail: String) =
        AgentMacroUnavailableException("agent-server macro check answered outside the contract: $detail", null)
}

/** agent-server 가 답하지 못했거나 계약 밖으로 답했다. 원인은 로그로만 가고 클라이언트는 code 만 받는다. */
class AgentMacroUnavailableException(message: String, cause: Throwable?) :
    UpstreamUnavailableException(message, code = "agent_unavailable", cause = cause)
