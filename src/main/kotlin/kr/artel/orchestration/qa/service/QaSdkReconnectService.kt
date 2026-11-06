package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.qa.repository.QaTryRepository
import org.springframework.stereotype.Service
import java.time.Duration

/** `qa_log` 의 `payload.event` 값. 타임라인과 조회 query 가 이 값으로 교체 행을 찾는다. */
const val SDK_SESSION_REPLACED_EVENT = "sdk_session_replaced"

/**
 * 같은 인스턴스의 새 SDK 연결이 끊긴 세션을 넘겨받았을 때 진행 중인 QA try 를 잇는다(ARTEL-1234).
 *
 * try 는 실패로 닫지 않는다. 교체 사실은 `qa_log` 에 `LOG` 한 줄로 남긴다 — 그 사이 나간 액션의 결과가 오지 않은
 * 이유를 타임라인에서 되짚을 자리가 이 행뿐이다. SDK 는 끊기면 판독을 스스로 멈추므로(ARTEL-417) agent 세션이
 * 붙은 try 면 `start_readings` 를 다시 보낸다.
 */
@Service
class QaSdkReconnectService(
    private val tryRepository: QaTryRepository,
    private val logService: QaLogService,
    private val readings: QaReadingsService,
    private val objectMapper: ObjectMapper
) {
    suspend fun sessionReplaced(gameInstanceId: Long, probeTimeout: Duration) {
        val active = tryRepository.findActiveByGameInstanceId(gameInstanceId) ?: return
        val qaTryId = requireNotNull(active.id)
        val payload = objectMapper.createObjectNode()
            .put("event", SDK_SESSION_REPLACED_EVENT)
            .put("gameInstanceId", gameInstanceId)
            .put("probeTimeoutMillis", probeTimeout.toMillis())
        logService.publish(
            logService.append(
                qaTryId = qaTryId,
                direction = "ORCHE_INTERNAL",
                type = "LOG",
                message = "SDK 연결을 새 연결로 교체했습니다. 이전 연결이 ${probeTimeout.toMillis()}ms 안에 ping 에 답하지 않았습니다.",
                payload = payload
            )
        )
        if (active.agentSessionId != null) {
            readings.start(gameInstanceId)
        }
    }
}
