package kr.artel.orchestration.contentmap.scan

import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.sdk.dto.ActionItemDto
import kr.artel.orchestration.sdk.dto.ActionResponseDto
import kr.artel.orchestration.sdk.service.SessionManager
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
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
    private val statuses: ScanStatusRegistry,
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
     */
    suspend fun startScan(userId: Long, projectId: Long, gameBuildId: Long): ScanStatus? {
        gameBuilds.findAccessibleById(gameBuildId, projectId, userId) ?: return null

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

        // 이 id 로 짝을 맞추지 않는다 — 결과 라우팅은 액션 이름으로 한다(ScanResultRouter). 그래서
        // qa_log 가 발급하는 id 와 값이 겹쳐도 무해하다. 로그에서 한 번의 누름을 따라가는 용도다.
        sessionManager.sendAction(
            instanceId.toString(),
            ActionResponseDto(
                id = requestId,
                actions = listOf(
                    ActionItemDto(id = requestId, method = SCAN_EVIDENCE, params = emptyList()),
                ),
            ),
        )
        logger.info(
            "근거 스캔 요청 [gameBuildId={}, gameInstanceId={}, requestId={}]",
            gameBuildId, instanceId, requestId,
        )

        val status = ScanStatus(
            gameBuildId = gameBuildId,
            gameInstanceId = instanceId,
            gameInstanceName = instance.name,
            state = ScanState.REQUESTED,
            requestedAt = Instant.now(clock),
        )
        statuses.put(status)
        return status
    }

    companion object {
        /**
         * SDK 와 맞춘 액션 이름. 파라미터는 없다 — SDK 가 자기 `gameBuildId` 를 안다.
         *
         * `scan_all_scenes` 와 다른 것이다. 그것은 옛 AllSceneScanner 를 돌려 ALL_SCENES 를 답하는
         * 기능이고 근거 문서와 무관하다.
         */
        const val SCAN_EVIDENCE = "scan_evidence"

        /** 프로세스 안에서만 뜻이 있는 번호. 위 sendAction 의 주석이 그 범위를 말한다. */
        private val REQUEST_IDS = AtomicLong()
    }
}
