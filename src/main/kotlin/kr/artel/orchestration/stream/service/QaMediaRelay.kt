package kr.artel.orchestration.stream.service

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kr.artel.orchestration.qa.review.QaRecordingStore
import kr.artel.orchestration.sdk.service.SessionManager
import kr.artel.orchestration.stream.config.StreamProperties
import kr.artel.orchestration.stream.dto.*
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import kotlinx.coroutines.reactor.awaitSingleOrNull
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class MediaConfiguration(val iceServers: List<IceServer>, val recordingId: UUID?)
data class MediaLease(val created: Boolean, val state: String)

/** 인스턴스당 SDK ingress는 하나다. viewer와 QA의 수명을 따로 유지한다. */
@Service
class QaMediaRelay(private val sessions: SessionManager, private val viewers: ViewerSessionRegistry,
    private val properties: StreamProperties, private val recordings: QaRecordingStore) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val client = WebClient.builder().build()
    private val mutex = Mutex()
    private val ingress = ConcurrentHashMap<String, Owner>()
    val enabled: Boolean get() = properties.mediaWorkerUrl.isNotBlank()

    private data class Owner(val instanceId: String, var streamId: UUID = UUID.randomUUID(),
        var viewerId: String? = null, var recordingId: UUID? = null, var viewerNeedsOffer: Boolean = false,
        var startPending: Boolean = true)

    suspend fun openViewer(instanceId: String, viewerId: String) {
        val owner = mutex.withLock {
            val owner = ingress.computeIfAbsent(instanceId) { Owner(it) }
            owner.viewerId?.takeIf { it != viewerId }?.let { previous ->
                request("DELETE", "/${owner.streamId}/viewers/$previous")
            }
            owner.viewerId = viewerId
            owner.viewerNeedsOffer = false
            lease(owner)
            owner
        }
        val offer = request("POST", "/${owner.streamId}/viewers/$viewerId")
        if (offer != null) viewers.relayToStream(viewerId, offer)
    }

    suspend fun closeViewer(instanceId: String, viewerId: String) = mutex.withLock {
        val owner = ingress[instanceId] ?: return@withLock
        request("DELETE", "/${owner.streamId}/viewers/$viewerId")
        if (owner.viewerId == viewerId) owner.viewerId = null
        if (owner.viewerId == null && owner.recordingId == null) close(owner)
    }

    suspend fun viewerSignal(instanceId: String, viewerId: String, rawJson: String) {
        val owner = ingress[instanceId]?.takeIf { it.viewerId == viewerId } ?: return
        request("POST", "/${owner.streamId}/viewers/$viewerId/signal", rawJson)
    }

    suspend fun sdkSignal(instanceId: String, streamId: String?, rawJson: String) {
        val owner = ingress[instanceId]?.takeIf { it.streamId.toString() == streamId } ?: return
        val response = request("POST", "/${owner.streamId}/signal", rawJson)
        if (response != null && response != "null") sessions.sendRaw(instanceId, response)
    }

    @Scheduled(fixedDelay = 10000, initialDelay = 2000)
    fun reconcile() = runBlocking { prepare() }

    suspend fun prepare() {
        try {
            val active = recordings.active()
            if (!enabled) {
                for (recording in active) recordings.error(recording.id, "영상 저장 서버가 설정되지 않았습니다.")
                recordings.expire()
                return
            }
            mutex.withLock {
                val byInstance = active.associateBy { it.instanceId }
                for (recording in active) {
                    val owner = ingress.computeIfAbsent(recording.instanceId) { Owner(it) }
                    owner.recordingId = recording.id
                }
                for (owner in ingress.values.toList()) {
                    owner.recordingId = byInstance[owner.instanceId]?.id
                    try {
                        if (owner.recordingId == null && owner.viewerId == null) {
                            close(owner)
                            continue
                        }
                        if (!sessions.hasSession(owner.instanceId)) error("게임 연결이 없어 영상을 받지 못하고 있습니다.")
                        val state = lease(owner, recoverViewer = true)
                        if (owner.viewerNeedsOffer && state == "connected") {
                            owner.viewerId?.let { id -> viewers.findByStream(id)?.let { viewer ->
                                val offer = request("POST", "/${owner.streamId}/viewers/$id")
                                if (offer != null) {
                                    viewers.send(viewer, StreamReadyMessage(id, properties.iceServers))
                                    viewers.relayToStream(id, offer)
                                    owner.viewerNeedsOffer = false
                                }
                            } }
                        }
                        owner.recordingId?.let { recordings.error(it, if (state == "connected") null else "영상 연결을 기다리고 있습니다.") }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        owner.recordingId?.let { recordings.error(it, "영상 연결 또는 저장 서버에 오류가 발생했습니다.") }
                        logger.warn("영상 연결 유지 실패 [instanceId={}]", owner.instanceId, error)
                    }
                }
            }
            recordings.expire()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.warn("QA 녹화 상태 확인 실패", error)
        }
    }

    private suspend fun lease(owner: Owner, recoverViewer: Boolean = false): String {
        val lease = client.put().uri(properties.mediaWorkerUrl.trimEnd('/') + "/internal/streams/${owner.streamId}")
            .bodyValue(MediaConfiguration(properties.iceServers, owner.recordingId)).retrieve().bodyToMono(MediaLease::class.java)
            .timeout(Duration.ofSeconds(20)).awaitSingleOrNull() ?: error("영상 서버가 응답하지 않았습니다.")
        if (lease.created || lease.state in setOf("failed", "closed")) {
            val replacingStartedStream = !lease.created || !owner.startPending
            owner.startPending = true
            if (recoverViewer && owner.viewerId != null) owner.viewerNeedsOffer = true
            if (replacingStartedStream) {
                request("DELETE", "/${owner.streamId}")
                // 이전 SDK 세션의 늦은 STOPPED와 ICE가 새 연결에 섞이지 않게 한다.
                owner.streamId = UUID.randomUUID()
                client.put().uri(properties.mediaWorkerUrl.trimEnd('/') + "/internal/streams/${owner.streamId}")
                    .bodyValue(MediaConfiguration(properties.iceServers, owner.recordingId)).retrieve().toBodilessEntity()
                    .timeout(Duration.ofSeconds(20)).awaitSingleOrNull()
            }
        }
        if (owner.startPending) {
            sessions.send(owner.instanceId, StreamStartMessage(owner.streamId.toString(), properties.iceServers, properties.video, properties.leaseSeconds))
            // 전송 중 세션이 끊기면 다음 갱신에서 START부터 다시 보낸다.
            owner.startPending = false
        } else sessions.send(owner.instanceId, StreamRenewMessage(owner.streamId.toString()))
        return lease.state
    }

    private suspend fun close(owner: Owner) {
        try {
            request("DELETE", "/${owner.streamId}")
            if (sessions.hasSession(owner.instanceId)) sessions.send(owner.instanceId, StreamStopMessage(owner.streamId.toString()))
        } finally {
            ingress.remove(owner.instanceId, owner)
        }
    }

    private suspend fun request(method: String, path: String, raw: String? = null): String? {
        val request = client.method(org.springframework.http.HttpMethod.valueOf(method))
            .uri(properties.mediaWorkerUrl.trimEnd('/') + "/internal/streams" + path).contentType(MediaType.APPLICATION_JSON)
        return (if (raw != null) request.bodyValue(raw) else request).retrieve().bodyToMono(String::class.java)
            .timeout(Duration.ofSeconds(30)).awaitSingleOrNull()
    }
}
