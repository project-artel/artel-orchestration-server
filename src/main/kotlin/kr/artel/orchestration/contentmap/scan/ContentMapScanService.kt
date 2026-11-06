package kr.artel.orchestration.contentmap.scan

import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.contentmap.repository.ContentMapDocumentRepository
import kr.artel.orchestration.contentmap.dto.ContentMapStreamEvent
import kr.artel.orchestration.contentmap.dto.LastScanResponse
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.sdk.dto.ActionItemDto
import kr.artel.orchestration.sdk.dto.ActionResponseDto
import kr.artel.orchestration.sdk.service.SessionManager
import kr.artel.orchestration.testcase.service.ContentMapTestCaseService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * 붙어 있는 게임에 **근거 스캔을 시킨다.** home 의 버튼 하나가 여기로 들어온다.
 *
 * ```
 * POST .../content-map/scan
 *   → 이 빌드를 물고 있는, 지금 붙어 있는 인스턴스를 고른다
 *   → scan_evidence 액션을 보낸다
 *   → 202. 문서는 SDK 가 /api/sdk/.../content-map 로 스스로 올린다
 * ```
 *
 * **보낸 뒤 기다리지 않는다.** 이 저장소에는 "보내고 답을 기다리는" 원시 도구가 없다 —
 * `QaActionDispatchService` 도 보내고 그대로 반환하며, `SessionManager.send` 의 KDoc 이
 * "보냈다가 아니라 보낼 줄에 세웠다"라고 못 박는다. 그 도구를 이 이슈에서 처음 만들면 상관
 * id 레지스트리·타임아웃·죽은 세션 정리를 이 diff 가 떠안는다.
 *
 * 대신 **응답이 무엇을 기다리면 되는지 말한다.** 어느 인스턴스가 받았는지와 상태를 실어 보내고,
 * 결과가 돌아오면 [ScanResultRouter] 가 적재까지 해서 그 상태를 옮긴다. 화면은 조회 API 의
 * `lastScan` 이 움직이는 것을 본다.
 *
 * 접근 검사가 컨트롤러가 아니라 여기 있는 이유는 `ContentMapViewService` · `EvidenceDocumentService`
 * 와 같다 — 스캔을 시작하는 유일한 문이 이 함수라야 아무도 검사를 빠뜨릴 수 없다.
 */
@Service
class ContentMapScanService(
    private val gameBuilds: GameBuildRepository,
    private val gameInstances: GameInstanceRepository,
    private val sessionManager: SessionManager,
    private val documents: ContentMapDocumentRepository,
    private val statuses: ScanStatusRegistry,
    private val streamManager: ContentMapEventStreamManager,
    private val testCases: ContentMapTestCaseService,
    private val clock: Clock,
) {

    private val logger = LoggerFactory.getLogger(ContentMapScanService::class.java)

    /**
     * 스캔을 시킨다. 접근할 수 없는 빌드면 null(→ 404), 붙어 있는 인스턴스가 없으면 409.
     *
     * **404 와 409 를 가르는 것이 요점이다.** 둘 다 404 로 뭉개면 화면은 "빌드가 없다"와
     * "게임이 안 켜져 있다"를 구분하지 못해, 버튼을 비활성으로 두면서 그 이유를 말할 수 없다.
     *
     * 부재와 권한 없음은 반대로 같은 null 로 묶는다. 구분해서 알려주면 id 를 훑어 남의 빌드가
     * 존재한다는 사실을 알아낼 수 있다.
     *
     * 경로의 [projectId] 까지 보는 것은 그것이 장식이 되지 않게 하려는 것이다 — 안 보면 아무
     * 프로젝트 id 나 넣어도 통과하고, 한 사람이 여러 프로젝트의 멤버인 흔한 경우에 권한 검사만으로는
     * 안 걸린다.
     *
     * [testCaseRequest] 가 있으면 스캔 문서가 다 읽힌 뒤 TC 도 만든다([ScanResultRouter], ARTEL-1040). 이미 TC 를 만든
     * build 면 스캔을 보내기 전에 409 다. editor 버전도 만들 수 있다(ARTEL-1180).
     *
     * 같은 빌드에 스캔이 이미 돌고 있어도 409(`scan_in_progress`) 다(ARTEL-1178).
     */
    suspend fun startScan(
        userId: Long,
        projectId: Long,
        gameBuildId: Long,
        testCaseRequest: ScanTestCases? = null,
    ): ScanStatus? {
        val build = gameBuilds.findAccessibleById(gameBuildId, projectId, userId) ?: return null
        // 스캔하고 TC 생성(ARTEL-1040): 스캔이 끝난 뒤에 막히면 게임을 돌린 수고가 버려진다. 막힐 것은 먼저 막는다.
        if (testCaseRequest != null) testCases.requireGeneratable(build)

        // 붙어 있는지는 DB 가 아니라 SessionManager 가 아는 사실이라 여기서 거른다.
        val instance = gameInstances.findByLastGameBuildIdForMember(gameBuildId, userId)
            .filter { sessionManager.hasSession(it.id!!.toString()) }
            .firstOrNull()
            ?: throw ConflictException(
                "이 빌드를 실행 중인 게임이 붙어 있지 않습니다. 게임을 실행한 뒤 다시 시도해 주세요.",
                "game_instance_not_connected",
            )

        val instanceId = instance.id!!
        val requestId = REQUEST_IDS.incrementAndGet()
        val now = Instant.now(clock)
        val status = ScanStatus(
            gameBuildId = gameBuildId,
            gameInstanceId = instanceId,
            gameInstanceName = instance.name,
            state = ScanState.REQUESTED,
            requestedAt = now,
            testCases = testCaseRequest,
        )
        // 보내기 전에 자리부터 잡는다(ARTEL-1178). 이 빌드에 이미 돌고 있는 스캔이 있으면 두 번째 스캔을 게임 줄에
        // 세우지 않는다 — SDK 는 받은 대로 처음부터 다시 돌고, 앞 결과는 뒤 요청에 덮여 화면이 누구의 결과인지 모른다.
        val running = statuses.reserve(status) { isStillRunning(it, now) }
        if (running != null) {
            throw ConflictException(
                "${running.gameInstanceName} 에서 이 빌드의 스캔이 이미 돌고 있습니다. 끝난 뒤에 다시 시도해 주세요.",
                SCAN_IN_PROGRESS,
            )
        }

        // 이 id 로 짝을 맞추지 않는다 — 결과 라우팅은 액션 이름으로 한다(ScanResultRouter). 그래서
        // qa_log 가 발급하는 id 와 값이 겹쳐도 무해하다. 로그에서 한 번의 누름을 따라가는 용도다.
        try {
            sessionManager.sendAction(
                instanceId.toString(),
                ActionResponseDto(
                    id = requestId,
                    actions = listOf(
                        ActionItemDto(id = requestId, method = SCAN_EVIDENCE, params = emptyList()),
                    ),
                ),
            )
        } catch (failure: Throwable) {
            statuses.release(status)
            throw failure
        }
        logger.info(
            "근거 스캔 요청 [gameBuildId={}, gameInstanceId={}, requestId={}]",
            gameBuildId, instanceId, requestId,
        )

        // 이미 이 빌드의 events 스트림을 구독 중인 다른 사람이 있을 수 있다 — 그 화면은 이 버튼을
        // 누른 적이 없으므로, REQUESTED 로 넘어간 사실을 스스로 알 방법이 SSE 뿐이다.
        streamManager.emit(gameBuildId, ContentMapStreamEvent(type = "scan", scan = LastScanResponse.of(status)))
        return status
    }

    /**
     * 도는 스캔을 끊는다(ARTEL-1183).
     *
     * 빌드를 못 찾거나 접근할 수 없으면 null 이고, 호출부가 404 로 답한다.
     *
     * **게임 쪽 단계에서만 끊을 수 있다.** [ScanPhase.SCANNING] · [ScanPhase.UPLOADING] 은 게임이 쥐고 있어
     * 멈춰 달라고 보낼 상대가 있다. 뒤의 둘은 서버가 WS 프레임을 처리하는 코루틴 안에서 그대로 돌아, 바깥에서
     * 끊을 손잡이가 없다 — 그 자리에서는 409 로 "지금은 못 끊는다"를 말하는 것이 사실이다.
     *
     * 도는 스캔이 없을 때도 409 다. 204 로 조용히 넘기면 화면은 자기가 무언가를 끊었다고 여기는데, 그때 실제로
     * 일어난 일은 아무것도 없다. 버튼은 스캔이 도는 동안만 보이므로 여기 닿았다는 것은 화면이 낡았다는 뜻이고,
     * 409 와 사유를 받아야 다시 읽어 올 수 있다.
     *
     * 게임이 이미 끊겼어도 상태는 닫는다. 보낼 상대가 없는 것이 스캔을 열어 둘 이유는 아니다.
     */
    suspend fun cancelScan(userId: Long, projectId: Long, gameBuildId: Long): ScanStatus? {
        gameBuilds.findAccessibleById(gameBuildId, projectId, userId) ?: return null

        val running = statuses.find(gameBuildId)
        if (running == null || running.state != ScanState.REQUESTED) {
            throw ConflictException(
                "이 빌드에 돌고 있는 스캔이 없습니다. 화면을 다시 읽어 주세요.",
                SCAN_NOT_RUNNING,
            )
        }
        if (running.phase != ScanPhase.SCANNING && running.phase != ScanPhase.UPLOADING) {
            throw ConflictException(
                "올라온 문서를 서버가 읽는 중이라 지금은 끊을 수 없습니다. 끝난 뒤에 결과를 확인해 주세요.",
                SCAN_NOT_CANCELLABLE,
            )
        }

        // 보내기는 해 보되 못 보내도 멈추지 않는다. 게임이 그새 끊겼다면 받을 상대가 없을 뿐이고, 그때도 상태는
        // 닫아야 다음 요청이 풀린다.
        try {
            sessionManager.send(running.gameInstanceId.toString(), mapOf("type" to CANCEL_EVIDENCE_SCAN))
        } catch (failure: Throwable) {
            logger.warn(
                "스캔 취소를 게임에 못 보냈다. 상태는 닫는다 [gameBuildId={}, gameInstanceId={}]: {}",
                gameBuildId, running.gameInstanceId, failure.message,
            )
        }

        // 올라간 것이 있으면 접수증을 지운다. 남겨 두면 다음 스캔의 적재가 반쪽 순회의 문서까지 같이 앉히고,
        // 그 문서는 "이 게임엔 씬이 이것뿐" 이라고 말해 지도에서 못 돈 씬을 지운다.
        //
        // 이 스캔이 요청된 뒤에 받은 것만 지운다. 앞선 스캔에서 적재하다 깨진 문서는 다음에 다시 시도하려고
        // 대기에 남아 있는 것이라, 통째로 지우면 취소와 무관한 그것까지 날아간다.
        val discarded = runCatching { documents.deletePendingReceivedSince(gameBuildId, running.requestedAt) }
            .onFailure { logger.warn("끊긴 스캔의 접수증을 못 지웠다 [gameBuildId={}]: {}", gameBuildId, it.message) }
            .getOrDefault(0)
        if (discarded > 0) {
            logger.info("끊긴 스캔이 올린 문서 {}장을 대기에서 뺐다 [gameBuildId={}]", discarded, gameBuildId)
        }

        val cancelled = statuses.complete(gameBuildId) { status ->
            status.copy(
                state = ScanState.FAILED,
                finishedAt = Instant.now(clock),
                error = "사용자가 스캔을 취소했습니다.",
                cancelled = true,
            )
        } ?: return null

        logger.info("근거 스캔 취소 [gameBuildId={}, gameInstanceId={}]", gameBuildId, cancelled.gameInstanceId)
        streamManager.emit(gameBuildId, ContentMapStreamEvent(type = "scan", scan = LastScanResponse.of(cancelled)))
        return cancelled
    }

    /**
     * 앞 스캔이 아직 돌고 있다고 볼 것인가. 둘 다 맞아야 한다 — 받은 게임이 아직 붙어 있고, [STALE_AFTER] 안에
     * 소식이 있었다.
     *
     * 게임이 끊기면 결과는 오지 않는다. 서버는 그 스캔을 끝낼 길이 없으니 여기서 없는 것으로 친다.
     */
    private fun isStillRunning(status: ScanStatus, now: Instant): Boolean =
        sessionManager.hasSession(status.gameInstanceId.toString()) &&
            Duration.between(status.updatedAt, now) < STALE_AFTER

    companion object {
        /**
         * SDK 와 맞춘 액션 이름. 파라미터는 없다 — SDK 가 자기 `gameBuildId` 를 안다.
         *
         * `scan_all_scenes` 와 다른 것이다. 그것은 옛 AllSceneScanner 를 돌려 ALL_SCENES 를 답하는
         * 기능이고 근거 문서와 무관하다.
         */
        const val SCAN_EVIDENCE = "scan_evidence"

        /** 같은 빌드에 스캔이 돌고 있어 거절했다(ARTEL-1178). 화면은 이 code 로 "이미 도는 중" 을 말한다. */
        const val SCAN_IN_PROGRESS = "scan_in_progress"

        /**
         * 이만큼 소식이 없으면 게임이 붙어 있어도 그 스캔은 죽은 것으로 친다.
         *
         * 진행을 보내는 SDK 는 씬마다 이 시계를 민다. 진행을 안 보내는 옛 SDK 의 큰 게임은 한 시간 넘게 걸릴 수
         * 있고, 그때는 두 번째 요청이 통과한다 — 버튼이 영영 막히는 쪽보다 낫다. 설정값으로 두지 않은 이유는
         * [ScanStatusRegistry] 의 상한과 같다.
         */
        val STALE_AFTER: Duration = Duration.ofMinutes(60)

        /** 도는 스캔이 없을 때. 화면이 낡았다는 뜻이라 조용히 넘기지 않는다. */
        const val SCAN_NOT_RUNNING = "scan_not_running"

        /** 서버 쪽 단계라 끊을 손잡이가 없을 때. */
        const val SCAN_NOT_CANCELLABLE = "scan_not_cancellable"

        /** 게임이 액션 큐 밖에서 받는 멈춤 통지. SDK 의 `ArtelManager.HandleMessage` 가 가른다. */
        const val CANCEL_EVIDENCE_SCAN = "CANCEL_EVIDENCE_SCAN"

        /** 프로세스 안에서만 뜻이 있는 번호. 위 sendAction 의 주석이 그 범위를 말한다. */
        private val REQUEST_IDS = AtomicLong()
    }
}
