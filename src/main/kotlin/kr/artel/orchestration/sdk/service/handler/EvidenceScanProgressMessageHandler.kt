package kr.artel.orchestration.sdk.service.handler

import kr.artel.orchestration.contentmap.scan.ScanProgressRecorder
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketSession

/**
 * 근거 스캔 도중의 진행(`EVIDENCE_SCAN_PROGRESS`, ARTEL-1177)을 [ScanProgressRecorder] 에 넘긴다.
 *
 * `ACTION_RESULT` 에 섞지 않고 타입을 따로 둔 이유: 결과는 액션 하나에 한 번이고 QA 브리지가 같은 타입을 쓴다.
 * 진행은 씬마다 오므로, 섞으면 QA 로 새지 않게 막는 분기가 프레임마다 돈다.
 */
@Component
class EvidenceScanProgressMessageHandler(
    private val progress: ScanProgressRecorder,
) : SdkMessageHandler {

    override val messageType: String = "EVIDENCE_SCAN_PROGRESS"

    override suspend fun handle(instanceId: String, payloadText: String, session: WebSocketSession) {
        progress.record(instanceId.toLong(), payloadText)
    }
}
