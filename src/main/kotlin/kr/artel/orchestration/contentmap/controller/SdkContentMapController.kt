package kr.artel.orchestration.contentmap.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.contentmap.dto.EvidenceUploadTicketRequest
import kr.artel.orchestration.contentmap.dto.EvidenceUploadTicketResponse
import kr.artel.orchestration.contentmap.dto.RegisterEvidenceDocumentRequest
import kr.artel.orchestration.contentmap.dto.RegisterEvidenceDocumentResponse
import kr.artel.orchestration.contentmap.service.EvidenceDocumentService
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * SDK 가 근거 문서를 올리는 지점. 등록과 같은 자리(`/api/sdk` 아래)이고 SDK 토큰으로 통과한다.
 *
 * `/internal` 이 아닌 이유: SDK 는 인터넷의 게임 클라이언트지 서버-투-서버가 아니다.
 *
 * 두 단계로 나뉜 이유는 문서가 크기 때문이다(실측 1,413 KB). 바이트는 이 서버를 지나가지 않고
 * SDK 가 스토리지에 직접 올린다.
 *
 * ```
 * 1) POST .../content-map/ticket   업로드 URL 을 받는다
 * 2) 스토리지에 PUT
 * 3) POST .../content-map          objectKey 를 등록한다
 * ```
 */
@Tag(name = "SDK Content Map", description = "SDK 가 보고하는 근거 문서 수용")
@RestController
@RequestMapping("/api/sdk/game-builds/{gameBuildId}/content-map")
class SdkContentMapController(
    private val service: EvidenceDocumentService,
) {

    @Operation(
        summary = "근거 문서 업로드 티켓",
        description = "스토리지에 직접 올릴 단기 URL 을 발급한다. 바이트는 서버를 지나가지 않는다.",
    )
    @PostMapping("/ticket")
    suspend fun createTicket(
        @CurrentUserId appUserId: Long,
        @PathVariable gameBuildId: Long,
        @Valid @RequestBody request: EvidenceUploadTicketRequest,
    ): EvidenceUploadTicketResponse =
        service.createUploadTicket(appUserId, gameBuildId, request)
            ?: throw NotFoundException("등록할 수 있는 게임 빌드를 찾을 수 없습니다.")

    @Operation(
        summary = "근거 문서 등록",
        description = "올라온 문서의 헤더를 서버가 직접 읽어 content_map 을 만들거나 갱신한다. " +
            "같은 내용이 다시 오면 기존 등록을 그대로 돌려준다.",
    )
    @PostMapping
    suspend fun register(
        @CurrentUserId appUserId: Long,
        @PathVariable gameBuildId: Long,
        @Valid @RequestBody request: RegisterEvidenceDocumentRequest,
    ): RegisterEvidenceDocumentResponse =
        service.register(appUserId, gameBuildId, request)
            ?: throw NotFoundException("등록할 수 있는 게임 빌드를 찾을 수 없습니다.")
}
