package kr.artel.orchestration.contentmap.scan

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.contentmap.dto.ContentMapStreamEvent
import kr.artel.orchestration.contentmap.dto.LastScanResponse
import kr.artel.orchestration.game.repository.GameInstanceRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * 게임이 스캔 도중 보내는 `EVIDENCE_SCAN_PROGRESS` 를 마지막 스캔 상태에 적고 화면에 흘린다(ARTEL-1178).
 *
 * ```
 * {"type":"EVIDENCE_SCAN_PROGRESS","stage":"SCANNING","done":3,"total":12,"scene":"Title"}
 * {"type":"EVIDENCE_SCAN_PROGRESS","stage":"UPLOADING","done":0,"total":1}
 * ```
 *
 * 이것이 없을 때 화면은 버튼을 누른 뒤 결과가 올 때까지 아무것도 몰랐다. 큰 게임은 수 분이 걸리고, 사람은
 * 얼마나 남았는지 모른 채 기다리다 다시 눌렀다.
 *
 * **우리가 보낸 스캔에만 적는다.** 그 빌드에 [ScanState.REQUESTED] 가 없거나 다른 인스턴스에 보낸 스캔이면
 * 버린다 — 게임이 스스로 돈 스캔의 진행이 남이 누른 스캔의 도넛을 움직이면 안 된다. 받은 값은 상대가 보낸
 * 것이라 범위와 길이를 여기서 자른다.
 */
@Service
class ScanProgressRecorder(
    private val gameInstances: GameInstanceRepository,
    private val statuses: ScanStatusRegistry,
    private val streamManager: ContentMapEventStreamManager,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {

    private val logger = LoggerFactory.getLogger(ScanProgressRecorder::class.java)

    suspend fun record(gameInstanceId: Long, payloadText: String) {
        val payload = runCatching { objectMapper.readTree(payloadText) }.getOrNull() ?: return
        val phase = phaseOf(payload) ?: run {
            logger.debug("알 수 없는 스캔 진행 단계 [gameInstanceId={}]: {}", gameInstanceId, payloadText)
            return
        }
        val gameBuildId = gameInstances.findById(gameInstanceId)?.lastGameBuildId ?: return
        val progress = progressOf(payload)

        statuses.advance(gameBuildId) { current ->
            if (current.gameInstanceId != gameInstanceId) return@advance null
            current.copy(phase = phase, progress = progress, updatedAt = Instant.now(clock))
        }?.let {
            streamManager.emit(gameBuildId, ContentMapStreamEvent(type = "scan", scan = LastScanResponse.of(it)))
        }
    }

    /** 게임이 알릴 수 있는 것은 게임 쪽 두 단계뿐이다. 서버 쪽 단계는 서버가 옮긴다. */
    private fun phaseOf(payload: JsonNode): ScanPhase? =
        when (payload.path(STAGE_FIELD).asText(null)) {
            ScanPhase.SCANNING.name -> ScanPhase.SCANNING
            ScanPhase.UPLOADING.name -> ScanPhase.UPLOADING
            else -> null
        }

    private fun progressOf(payload: JsonNode): ScanProgress? {
        val total = payload.path(TOTAL_FIELD).takeIf { it.isIntegralNumber }?.asInt() ?: return null
        if (total <= 0) return null
        val done = payload.path(DONE_FIELD).asInt(0).coerceIn(0, total)
        val scene = payload.path(SCENE_FIELD).asText(null)?.takeIf { it.isNotBlank() }?.take(SCENE_WIDTH)
        return ScanProgress(done = done, total = total, scene = scene)
    }

    private companion object {
        const val STAGE_FIELD = "stage"
        const val DONE_FIELD = "done"
        const val TOTAL_FIELD = "total"
        const val SCENE_FIELD = "scene"

        /** 씬 이름 한 줄. 화면 계약의 길이를 상대가 정하게 두지 않는다. */
        const val SCENE_WIDTH = 200
    }
}
