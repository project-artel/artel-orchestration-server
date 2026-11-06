package kr.artel.orchestration.telemetry.service

import io.netty.channel.ChannelOption
import kotlinx.coroutines.reactor.awaitSingle
import kr.artel.orchestration.telemetry.config.TelemetryProperties
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.netty.http.client.HttpClient

/**
 * `ARTEL_TELEMETRY_URL` 의 receiver 를 부른다. 둘 다 실패를 예외로 올리지 않고 false 로 답한다 — 부르는 쪽(scheduler,
 * 관리 화면)이 실패했을 때 할 일이 서로 달라서다.
 *
 * 로그에는 상태 코드나 예외 클래스 이름만 남긴다. 예외 message 에는 receiver 주소가 들어 있고, 그것은 설정에서 이미
 * 알 수 있는 값이라 다시 남길 이유가 없다.
 */
@Component
class TelemetryReceiverClient(private val properties: TelemetryProperties) {
    private val logger = LoggerFactory.getLogger(TelemetryReceiverClient::class.java)

    private val webClient: WebClient = WebClient.builder()
        .clientConnector(
            ReactorClientHttpConnector(
                HttpClient.create()
                    .responseTimeout(properties.sendTimeout)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.sendTimeout.toMillis().toInt())
            )
        )
        .build()

    /** `POST {endpoint}` 로 report 를 보낸다. 2xx 면 true 다. 직렬화는 부르는 쪽이 해서 보낸 바이트 수를 로그에 남긴다. */
    suspend fun send(reportJson: ByteArray): Boolean = call("send") {
        webClient.post().uri(properties.endpoint.trim())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(reportJson)
            .retrieve().toBodilessEntity().awaitSingle()
    }

    /** `DELETE {endpoint}/{installId}` 로 그 설치가 보낸 report 를 모두 지운다. 2xx 면 true 다. */
    suspend fun delete(installId: String): Boolean = call("delete") {
        webClient.delete().uri("${properties.endpoint.trim().trimEnd('/')}/{installId}", installId)
            .retrieve().toBodilessEntity().awaitSingle()
    }

    private suspend fun call(action: String, request: suspend () -> Unit): Boolean =
        try {
            request()
            true
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: WebClientResponseException) {
            logger.warn("Telemetry {} was answered with status {}", action, error.statusCode.value())
            false
        } catch (error: Exception) {
            logger.warn("Telemetry {} could not reach the receiver: {}", action, error.javaClass.simpleName)
            false
        }
}
