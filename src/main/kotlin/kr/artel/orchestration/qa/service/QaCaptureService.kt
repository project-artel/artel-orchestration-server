package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.config.StorageProperties
import kr.artel.orchestration.project.storage.DocumentStorage
import kr.artel.orchestration.qa.dto.QaCaptureTicketRequest
import kr.artel.orchestration.qa.dto.QaCaptureTicketResponse
import kr.artel.orchestration.qa.repository.QaTryRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono
import java.util.UUID

/** 화면 캡처로 받아들이는 형식. 배포마다 달라지면 특정 환경만 열지 못하는 이미지가 생긴다. */
private val ALLOWED_CONTENT_TYPES = mapOf(
    "image/jpeg" to "jpg",
    "image/png" to "png"
)

private const val CAPTURE_KEY_PREFIX = "qa-captures"

/**
 * QA 캡처 업로드를 서명하고, 그 캡처가 있었다는 사실을 타임라인에 남긴다.
 *
 * 서명은 로컬 HMAC 계산이라 네트워크 I/O가 없어 이벤트 루프에서 불러도 안전하다.
 * 바이트는 이 서비스를 지나가지 않는다.
 */
@Service
class QaCaptureService(
    private val instanceRepository: GameInstanceRepository,
    private val tryRepository: QaTryRepository,
    private val logService: QaLogService,
    private val storage: DocumentStorage,
    private val properties: StorageProperties,
    private val objectMapper: ObjectMapper
) {
    fun issueTicket(request: QaCaptureTicketRequest): Mono<QaCaptureTicketResponse> {
        val extension = validate(request)

        return instanceRepository.findActiveByInstanceKey(request.instanceKey)
            .switchIfEmpty(
                // 등록과 같은 이유로 401이 아니라 404다. 호출자는 로그인할 사용자가 아니라서
                // 다시 시도할 realm이 없고, 404는 "그런 키는 없다"와 "그 인스턴스는 지워졌다"를
                // 같은 응답으로 묶어준다.
                Mono.error(
                    ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "등록된 게임 인스턴스를 찾을 수 없습니다."
                    )
                )
            )
            .flatMap { instance -> tryRepository.findActiveByGameInstanceId(requireNotNull(instance.id)) }
            .switchIfEmpty(
                // 404가 아니라 409다. 인스턴스는 존재하고 요청도 올바르다. 지금 이 게임이
                // QA 실행 중이 아니라는 상태 충돌이므로, SDK는 다시 붙어도 소용이 없다.
                Mono.error(
                    ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "이 게임 인스턴스에 실행 중인 QA가 없습니다."
                    )
                )
            )
            .flatMap { qaTry ->
                val qaTryId = requireNotNull(qaTry.id)
                val captureId = UUID.randomUUID().toString()
                val fileName = "$captureId.$extension"
                val objectKey = "$CAPTURE_KEY_PREFIX/$qaTryId/$fileName"

                val upload = storage.presignUpload(objectKey, request.contentType, request.contentLength)
                val download = storage.presignDownload(
                    objectKey,
                    fileName,
                    properties.captureDownloadUrlTtl
                )

                appendEvidence(qaTryId, captureId, objectKey, download.url, request)
                    .thenReturn(
                        QaCaptureTicketResponse(
                            captureId = captureId,
                            uploadUrl = upload.url,
                            requiredHeaders = upload.requiredHeaders,
                            uploadExpiresAt = upload.expiresAt,
                            downloadUrl = download.url,
                            downloadExpiresAt = download.expiresAt
                        )
                    )
            }
    }

    /**
     * 캡처 한 건을 타임라인에 기록한다. 티켓 발급 시점이라 업로드 성공을 뜻하지는 않는다.
     *
     * 업로드 후 두 번째 호출로 확정하지 않는 이유는, 실패한 업로드가 곧 실패한 액션이고
     * 그 실패는 ACTION_RESULT에 이미 남기 때문이다. 왕복을 한 번 더 두면 캡처마다
     * 라운드트립이 늘 뿐 리뷰어가 얻는 것이 없다.
     *
     * payload에는 이미지를 가리키는 값만 넣는다. 이 행은 SSE로도 발행되므로 킬로바이트를
     * 넘지 않아야 한다.
     */
    private fun appendEvidence(
        qaTryId: Long,
        captureId: String,
        objectKey: String,
        downloadUrl: String,
        request: QaCaptureTicketRequest
    ): Mono<Void> {
        val payload = objectMapper.createObjectNode()
            .put("captureId", captureId)
            .put("objectKey", objectKey)
            .put("contentType", request.contentType)
            .put("contentLength", request.contentLength)
            .put("url", downloadUrl)
        request.targetId?.let { payload.put("targetId", it) }

        val what = request.targetId?.let { "요소 $it" } ?: "전체 화면"
        return logService.append(
            qaTryId = qaTryId,
            direction = "SDK_TO_ORCHE",
            type = "SCREENSHOT",
            messageId = captureId,
            message = "$what 을(를) 캡처했습니다.",
            payload = payload
        ).doOnNext(logService::publish).then()
    }

    /** 통과하면 이 형식의 파일 확장자를 돌려준다. */
    private fun validate(request: QaCaptureTicketRequest): String {
        val extension = ALLOWED_CONTENT_TYPES[request.contentType]
            ?: throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "지원하지 않는 형식입니다: ${request.contentType}"
            )
        if (request.contentLength > properties.maxCaptureBytes) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "캡처는 ${properties.maxCaptureBytes / 1024 / 1024}MB를 넘을 수 없습니다."
            )
        }
        return extension
    }
}
