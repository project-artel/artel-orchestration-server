package kr.artel.orchestration.game.service

import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.game.dto.GameInstanceResetResponse
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.sdk.dto.ActionItemDto
import kr.artel.orchestration.sdk.dto.ActionResponseDto
import kr.artel.orchestration.sdk.service.SessionManager
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * 세션 밖에서 **붙어 있는 게임 하나를 초기화한다.** `reset_game` 을 QA 세션 안에서만 부를 수 있으면
 * 런을 시작하기 전에 알려진 상태로 되돌릴 방법도, 죽은 런이 이상하게 남긴 게임을 되돌릴 방법도
 * 없다. 이 서비스가 그 문이다.
 *
 * [ContentMapScanService][kr.artel.orchestration.contentmap.scan.ContentMapScanService] 가 세운
 * 전례를 그대로 따른다 — 붙어 있는지는 DB 가 아니라 [SessionManager] 가 아는 사실이라 여기서 거르고,
 * 안 붙어 있으면 같은 코드(`game_instance_not_connected`)로 409 를 던진다. 202 로 답하고 아무 일도
 * 안 일어나는 조용한 실패를 두지 않는다.
 *
 * **판단: 그 인스턴스에 활성 QA 런(또는 단독 시도)이 있으면 초기화를 거절한다.** 그 순간 에이전트도
 * 같은 게임을 몰고 있다 — reset이 씬을 다시 열고 PlayerPrefs를 지우면 에이전트가 보던 상태와 서버가
 * 기록 중인 진행이 그 아래에서 통째로 어긋난다. 그 런은 실패로 끝나거나(정직하게 FAILED 로 남지
 * 않고) 원인을 알 수 없는 이상 동작으로 보고된다. 운영자가 정말 끊고 싶다면 이미 길이 있다 —
 * QA 취소 엔드포인트가 그 런을 정리하고, 배포로 소켓만 죽은 스테일 런은 `QaTryService` 의 `force`
 * 이어받기가 정리한다. 초기화가 그 정리까지 겸하면 사용자가 실제로 언제 CANCELLED 되는지, 언제
 * RUNNING 인 채로 게임만 리셋됐는지를 API 계약에서 더 이상 알 수 없다. 거절할 때는 어느 런이 그
 * 인스턴스를 쥐고 있는지 id 로 말한다 — 그래야 운영자가 무엇을 먼저 끝내야 하는지 안다.
 */
@Service
class GameInstanceResetService(
    private val instanceRepository: GameInstanceRepository,
    private val sessionManager: SessionManager,
    private val qaRunRepository: QaRunRepository,
    private val qaTryRepository: QaTryRepository,
    private val clock: Clock,
) {

    private val logger = LoggerFactory.getLogger(GameInstanceResetService::class.java)

    /**
     * 초기화를 시킨다. 접근할 수 없는 인스턴스면 null(→ 404).
     *
     * 경로의 [projectId] 까지 보는 이유는 [GameInstanceRepository.findAccessibleById] 의 KDoc 과
     * 같다 — 참여자가 아닌 프로젝트는 존재 여부조차 알리지 않는다.
     */
    suspend fun reset(userId: Long, projectId: Long, instanceId: Long, clearPlayerPrefs: Boolean): GameInstanceResetResponse? {
        instanceRepository.findAccessibleById(instanceId, projectId, userId) ?: return null

        if (!sessionManager.hasSession(instanceId.toString())) {
            throw ConflictException(
                "이 게임 인스턴스에 연결된 게임이 없습니다. 게임을 실행한 뒤 다시 시도해 주세요.",
                "game_instance_not_connected",
            )
        }

        rejectIfQaActive(instanceId)

        val requestId = REQUEST_IDS.incrementAndGet()
        sessionManager.sendAction(
            instanceId.toString(),
            ActionResponseDto(
                id = requestId,
                actions = listOf(
                    ActionItemDto(
                        id = requestId,
                        method = RESET_GAME,
                        // SDK 의 ResetRequestReader 가 읽는 모양: [] 또는 [options]. clearPlayerPrefs
                        // 는 늘 명시해 보낸다 — 생략하면 옛 서버가 보내던 씬만 리로드하는 호출과 구분이
                        // 안 되고, "true"/"1" 같은 truthy 변환은 SDK 쪽에서 아예 거절한다(ResetRequest.cs).
                        params = listOf(mapOf("clearPlayerPrefs" to clearPlayerPrefs)),
                    ),
                ),
            ),
        )
        val requestedAt = Instant.now(clock)
        logger.info(
            "게임 인스턴스 초기화 요청 [instanceId={}, clearPlayerPrefs={}, requestId={}]",
            instanceId, clearPlayerPrefs, requestId,
        )

        return GameInstanceResetResponse(
            gameInstanceId = instanceId.toString(),
            clearPlayerPrefs = clearPlayerPrefs,
            requestedAt = requestedAt,
        )
    }

    /**
     * 위 KDoc 의 판단을 코드로 옮긴다. 런(TR) 과 단독 시도(qa_try, `qaRunId` 가 null 인 하위호환
     * 경로) 둘 다 본다 — 하나만 보면 다른 한쪽이 몰고 있는 게임을 그냥 리셋해 버린다.
     */
    private suspend fun rejectIfQaActive(instanceId: Long) {
        qaRunRepository.findActiveByGameInstanceId(instanceId)?.let { activeRun ->
            throw ConflictException(
                "이 게임 인스턴스에서 QA 런(id=${activeRun.id})이 진행 중이라 초기화를 거절합니다. " +
                    "그 런을 취소한 뒤 다시 시도해 주세요.",
                "game_instance_qa_active",
            )
        }
        qaTryRepository.findActiveByGameInstanceId(instanceId)?.let { activeTry ->
            throw ConflictException(
                "이 게임 인스턴스에서 QA 시도(id=${activeTry.id})가 진행 중이라 초기화를 거절합니다. " +
                    "그 시도를 취소한 뒤 다시 시도해 주세요.",
                "game_instance_qa_active",
            )
        }
    }

    companion object {
        /** SDK 와 맞춘 액션 이름(ARTEL-803). */
        const val RESET_GAME = "reset_game"

        /** 프로세스 안에서만 뜻이 있는 번호. [ContentMapScanService][kr.artel.orchestration.contentmap.scan.ContentMapScanService] 와 같은 용도다. */
        private val REQUEST_IDS = AtomicLong()
    }
}
