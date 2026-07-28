package kr.artel.orchestration.qa.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.Instant

/**
 * QA 캡처 업로드 티켓 요청. SDK가 이미지를 만든 뒤, 올리기 전에 부른다.
 *
 * 바이트는 이 요청에 실리지 않는다. 오케스트레이션은 서명만 하고 SDK가 스토리지로 직접
 * PUT한다. 이미지가 여기를 지나가면 WebFlux 이벤트 루프에 메가바이트 버퍼가 생기고,
 * 같은 바이트가 qa_log와 SSE로도 흘러간다.
 *
 * @property instanceKey 대시보드에서 발급한 인스턴스 자격증명. 등록과 같은 열쇠를 쓴다.
 * 게임 인스턴스 id를 대신 받으면, 이 경로는 엔드유저 JWT로 막히지 않으므로 순번을 훑어
 * 남의 QA 런 프리픽스에 쓰는 서명을 받아낼 수 있다
 * @property contentType 올릴 이미지의 형식. 서명에 포함되므로 다른 타입으로 PUT하면
 * 스토리지가 직접 거부한다
 * @property contentLength 클라이언트가 신고한 크기. 상한을 넘으면 티켓을 주지 않는다
 * @property targetId 요소를 크롭한 캡처면 그 대상 id. 전체 화면이면 비운다
 */
data class QaCaptureTicketRequest(
    @field:NotBlank
    @field:Size(max = 32)
    val instanceKey: String,

    @field:NotBlank
    val contentType: String,

    @field:Positive
    val contentLength: Long,

    val targetId: Int? = null
)

/**
 * 발급된 캡처 티켓.
 *
 * 업로드와 다운로드 만료를 따로 준다. 하나로 묶으면 SDK가 어느 쪽 시각을 액션 결과에
 * 실어야 하는지 알 수 없다. 에이전트에게 필요한 것은 [downloadExpiresAt]이다.
 *
 * @property captureId 객체 키와 SCREENSHOT 로그를 잇는 식별자
 * @property uploadUrl 이 URL로 직접 PUT한다
 * @property requiredHeaders PUT에 반드시 그대로 실어야 하는 헤더. 서명에 포함되어 있다
 * @property downloadUrl 에이전트가 이미지를 읽을 URL
 * @property downloadExpiresAt 이 시각 이후에는 이미지를 열 수 없다. QA 런 데드라인보다 길다
 */
data class QaCaptureTicketResponse(
    val captureId: String,
    val uploadUrl: String,
    val requiredHeaders: Map<String, String>,
    val uploadExpiresAt: Instant,
    val downloadUrl: String,
    val downloadExpiresAt: Instant
)
