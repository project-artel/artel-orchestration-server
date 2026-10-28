package kr.artel.orchestration.stream.service

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kr.artel.orchestration.qa.review.QaRecordingStore
import kr.artel.orchestration.common.error.TooManyRequestsException
import kr.artel.orchestration.sdk.service.SessionManager
import kr.artel.orchestration.stream.config.StreamProperties
import kr.artel.orchestration.stream.dto.*
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class MediaLease(val created: Boolean, val state: String)

/** 인스턴스당 SDK ingress는 하나다. viewer와 QA의 수명을 따로 유지한다. */
@Service
class QaMediaRelay(private val sessions: SessionManager, private val viewers: ViewerSessionRegistry,
    private val properties: StreamProperties, private val recordings: QaRecordingStore, private val engine: InProcessMediaService) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val admission = Mutex()
    private val ingress = ConcurrentHashMap<String, Owner>()
    private val maintenance = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("qa-media-maintenance"))
    private var expiryJob: Job? = null
    @Volatile private var stopping = false

    private class Owner(val instanceId: String) {
        val operations = Mutex()
        @Volatile var streamId: UUID = UUID.randomUUID()
        @Volatile var viewerId: String? = null
        var recordingId: UUID? = null
        var viewerNeedsOffer = false
        var startPending = true
        var maintenanceJob: Job? = null
    }

    private suspend fun admit(instanceId: String): Owner = admission.withLock {
        check(!stopping) { "영상 서비스를 종료하고 있습니다." }
        ingress[instanceId] ?: run {
            if (ingress.size >= properties.mediaMaxIngress)
                throw TooManyRequestsException("동시 영상 수신 상한에 도달했습니다.", 10)
            Owner(instanceId).also { ingress[instanceId] = it }
        }
    }

    suspend fun openViewer(instanceId: String, viewerId: String) {
        while (true) {
            val owner = admit(instanceId)
            val opened = owner.operations.withLock {
                if (ingress[instanceId] !== owner) return@withLock false
                owner.viewerId?.takeIf { it != viewerId }?.let { previous -> engine.closeViewer(owner.streamId, previous) }
                owner.viewerId = viewerId
                owner.viewerNeedsOffer = false
                lease(owner)
                engine.openViewer(owner.streamId, viewerId)
                true
            }
            if (opened) return
        }
    }

    suspend fun closeViewer(instanceId: String, viewerId: String) {
        val owner = ingress[instanceId] ?: return
        owner.operations.withLock {
            if (ingress[instanceId] !== owner) return@withLock
            engine.closeViewer(owner.streamId, viewerId)
            if (owner.viewerId == viewerId) owner.viewerId = null
            if (owner.viewerId == null && owner.recordingId == null) close(owner)
        }
    }

    suspend fun viewerSignal(instanceId: String, viewerId: String, rawJson: String) {
        val owner = ingress[instanceId]?.takeIf { it.viewerId == viewerId } ?: return
        engine.viewerSignal(owner.streamId, viewerId, rawJson)
    }

    suspend fun sdkSignal(instanceId: String, streamId: String?, rawJson: String) {
        val owner = ingress[instanceId]?.takeIf { it.streamId.toString() == streamId } ?: return
        engine.sdkSignal(owner.streamId, rawJson)
    }

    @Scheduled(fixedDelay = 10000, initialDelay = 2000)
    fun reconcile() = runBlocking { prepare(awaitOwners = false) }

    /** 운영 스케줄러는 느린 종료를 기다리지 않는다. 테스트는 owner 작업의 완료를 기다릴 수 있다. */
    suspend fun prepare(awaitOwners: Boolean = true) {
        if (stopping) return
        try {
            val active = recordings.active()
            if (!properties.enabled) {
                for (recording in active) recordings.error(recording.id, "스트리밍이 비활성화되어 있습니다.")
                expire(awaitOwners)
                return
            }
            val byInstance = active.associateBy { it.instanceId }
            for (recording in active) {
                try { admit(recording.instanceId) }
                catch (error: TooManyRequestsException) { recordings.error(recording.id, "동시 영상 수신 상한에 도달했습니다.") }
            }
            val jobs = admission.withLock {
                if (stopping) return@withLock emptyList<Job>()
                ingress.values.map { owner ->
                    owner.maintenanceJob?.takeIf { it.isActive } ?: maintenance.launch {
                        owner.operations.withLock {
                            if (ingress[owner.instanceId] !== owner) return@withLock
                            owner.recordingId = byInstance[owner.instanceId]?.id
                            maintain(owner)
                        }
                    }.also { owner.maintenanceJob = it }
                }
            }
            if (awaitOwners) jobs.joinAll()
            expire(awaitOwners)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.warn("QA 녹화 상태 확인 실패", error)
        }
    }

    private suspend fun expire(await: Boolean) {
        val job = admission.withLock {
            if (stopping) return@withLock null
            expiryJob?.takeIf { it.isActive } ?: maintenance.launch {
                try { recordings.expire() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { logger.warn("QA 영상 보관 기한 처리 실패", error) }
            }.also { expiryJob = it }
        }
        if (await) job?.join()
    }

    private suspend fun maintain(owner: Owner) {
        try {
            if (owner.recordingId == null && owner.viewerId == null) {
                close(owner)
                return
            }
            if (!sessions.hasSession(owner.instanceId)) error("게임 연결이 없어 영상을 받지 못하고 있습니다.")
            val state = lease(owner, recoverViewer = true)
            if (owner.viewerNeedsOffer && state == "connected") {
                owner.viewerId?.let { id -> viewers.findByStream(id)?.let { viewer ->
                    viewers.send(viewer, StreamReadyMessage(id, properties.iceServers))
                    engine.openViewer(owner.streamId, id)
                    owner.viewerNeedsOffer = false
                } }
            }
            owner.recordingId?.let { recordings.error(it, if (state == "connected") null else "영상 연결을 기다리고 있습니다.") }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.warn("영상 연결 유지 실패 [instanceId={}]", owner.instanceId, error)
            try { owner.recordingId?.let { recordings.error(it, "영상 연결 또는 저장 서버에 오류가 발생했습니다.") } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (storageError: Exception) { logger.warn("영상 오류 상태 저장 실패 [instanceId={}]", owner.instanceId, storageError) }
        }
    }

    @PreDestroy
    fun shutdown() = runBlocking {
        admission.withLock { stopping = true }
        maintenance.cancel()
        // 실제 peer 해제는 의존 bean인 InProcessMediaService가 담당한다.
        withTimeoutOrNull(5_000) { (ingress.values.mapNotNull { it.maintenanceJob } + listOfNotNull(expiryJob)).joinAll() }
    }

    private suspend fun lease(owner: Owner, recoverViewer: Boolean = false): String {
        val lease = engine.configure(owner.streamId, owner.instanceId, owner.recordingId, properties.iceServers)
        if (lease.created || lease.state in setOf("failed", "closed")) {
            val replacingStartedStream = !lease.created || !owner.startPending
            owner.startPending = true
            if (recoverViewer && owner.viewerId != null) owner.viewerNeedsOffer = true
            if (replacingStartedStream) {
                engine.closeStream(owner.streamId)
                // 이전 SDK 세션의 늦은 STOPPED와 ICE가 새 연결에 섞이지 않게 한다.
                owner.streamId = UUID.randomUUID()
                engine.configure(owner.streamId, owner.instanceId, owner.recordingId, properties.iceServers)
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
        // 인코더 종료에 실패하면 owner를 남겨 다음 주기에 안전하게 정리를 재시도한다.
        engine.closeStream(owner.streamId)
        try {
            if (sessions.hasSession(owner.instanceId)) sessions.send(owner.instanceId, StreamStopMessage(owner.streamId.toString()))
        } finally {
            ingress.remove(owner.instanceId, owner)
        }
    }

}
